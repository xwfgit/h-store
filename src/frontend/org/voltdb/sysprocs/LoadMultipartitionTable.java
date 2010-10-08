/* This file is part of VoltDB.
 * Copyright (C) 2008-2009 VoltDB L.L.C.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.sysprocs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.voltdb.HsqlBackend;
import org.voltdb.BackendTarget;
import org.voltdb.DependencySet;
import org.voltdb.ExecutionSite;
import org.voltdb.ParameterSet;
import org.voltdb.ProcInfo;
import org.voltdb.VoltSystemProcedure;
import org.voltdb.VoltTable;
import org.voltdb.VoltType;
import org.voltdb.ExecutionSite.SystemProcedureExecutionContext;
import org.voltdb.catalog.*;
import org.voltdb.dtxn.DtxnConstants;

import edu.brown.utils.PartitionEstimator;

@ProcInfo(singlePartition = false)
/*
 * Given a VoltTable with a schema corresponding to a persistent table, load all
 * of the rows applicable to the current partitioning at each node in the
 * cluster.
 */
public class LoadMultipartitionTable extends VoltSystemProcedure {
    private static final Logger LOG = Logger.getLogger(LoadMultipartitionTable.class);

    static final long DEP_distribute = SysProcFragmentId.PF_distribute
            | DtxnConstants.MULTIPARTITION_DEPENDENCY;

    static final long DEP_aggregate = SysProcFragmentId.PF_aggregate;
    
    private Cluster m_cluster = null;

    @Override
    public void init(ExecutionSite site, Procedure catProc,
            BackendTarget eeType, HsqlBackend hsql, Cluster cluster,
            PartitionEstimator p_estimator, Integer local_partition) {
        super.init(site, catProc, eeType, hsql, cluster, p_estimator, local_partition);
        m_cluster = cluster;
        site.registerPlanFragment(SysProcFragmentId.PF_distribute, this);
        site.registerPlanFragment(SysProcFragmentId.PF_aggregate, this);
    }

    @Override
    public DependencySet executePlanFragment(
            long txn_id,
            HashMap<Integer, List<VoltTable>> dependencies, int fragmentId,
            ParameterSet params, SystemProcedureExecutionContext context) {
        this.txn_id = txn_id;
        
        // need to return something ..
        VoltTable[] result = new VoltTable[1];
        result[0] = new VoltTable(new VoltTable.ColumnInfo("TxnId",
                VoltType.BIGINT));
        result[0].addRow(1);

        if (fragmentId == SysProcFragmentId.PF_distribute) {
            assert context.getCluster().getName() != null;
            assert context.getDatabase().getName() != null;
            assert params != null;
            assert params.toArray() != null;
            assert params.toArray()[0] != null;
            assert params.toArray()[1] != null;
            String table_name = (String) (params.toArray()[0]);
            
            LOG.debug("Executing voltLoadTable() sysproc fragment for table '" + table_name + "' in txn #" + this.txn_id);
            assert(this.isInitialized()) : " The sysproc " + this.getClass().getSimpleName() + " was not initialized properly";
            try {
                // voltLoadTable is void. Assume success or exception.
                super.voltLoadTable(context.getCluster().getName(), context.getDatabase().getName(),
                                    table_name, (VoltTable)(params.toArray()[1]), 0);
            } catch (VoltAbortException e) {
                // must continue and reply with dependency.
                e.printStackTrace();
            }
            LOG.debug("Finished loading table. Things look good...");
            return new DependencySet(new int[] { (int)DEP_distribute }, result);

        } else if (fragmentId == SysProcFragmentId.PF_aggregate) {
            return new DependencySet(new int[] { (int)DEP_aggregate }, result);
        }
        // must handle every dependency id.
        assert (false);
        return null;
    }

    public VoltTable[] run(String tableName, VoltTable table) throws VoltAbortException {
        assert(table != null) : "VoltTable to be loaded into " + tableName + " is null in txn #" + this.txn_id;
        final boolean debug = LOG.isDebugEnabled();
        if (debug) LOG.debug("Executing multi-partition loader for " + tableName + " with " + table.getRowCount() + " tuples in txn #" + this.txn_id);
        
        VoltTable[] results;
        SynthesizedPlanFragment pfs[];
        int numPartitions = m_cluster.getNum_partitions();

        // if tableName is replicated, just send table everywhere.
        // otherwise, create a VoltTable for each partition and
        // split up the incoming table .. then send those partial
        // tables to the appropriate sites.

        // TODO: hard-codes database name.
        Table catTable = m_cluster.getDatabases().get("database").getTables().getIgnoreCase(tableName);
        if (catTable == null) {
            throw new VoltAbortException("Table not present in catalog.");
        }
        
        if (catTable.getIsreplicated()) {
            LOG.debug(catTable + " is replicated. Creating " + numPartitions + " fragments to send to all partitions");
            pfs = new SynthesizedPlanFragment[numPartitions + 1];

            ParameterSet params = new ParameterSet();
            params.setParameters(tableName, table);
            
            // create a work unit to invoke super.loadTable() on each site.
            for (int i = 1; i <= numPartitions; ++i) {
                int partition = i - 1;
                pfs[i] = new SynthesizedPlanFragment();
                pfs[i].fragmentId = SysProcFragmentId.PF_distribute;
                pfs[i].outputDependencyIds = new int[] { (int)DEP_distribute };
                pfs[i].inputDependencyIds = new int[] { };
                pfs[i].multipartition = false; // true
                pfs[i].nonExecSites = false;
                pfs[i].parameters = params;
                pfs[i].siteId = partition;
            } // FOR

            // create a work unit to aggregate the results.
            // MULTIPARTION_DEPENDENCY bit set, requiring result from ea. site
            pfs[0] = new SynthesizedPlanFragment();
            pfs[0].fragmentId = SysProcFragmentId.PF_aggregate;
            pfs[0].outputDependencyIds = new int[] { (int)DEP_aggregate };
            pfs[0].inputDependencyIds = new int[] { (int)DEP_distribute };
            pfs[0].multipartition = false;
            pfs[0].nonExecSites = false;
            pfs[0].parameters = new ParameterSet();
            pfs[0].siteId = local_partition;

            // distribute and execute the fragments providing pfs and id
            // of the aggregator's output dependency table.
            results = executeSysProcPlanFragments(pfs, (int)DEP_aggregate);
            return results;
        } else {
            LOG.debug(catTable + " is not replicated. Splitting table data into separate pieces for partitions");
            
            // create a table for each partition
            VoltTable partitionedTables[] = new VoltTable[numPartitions];
            for (int i = 0; i < partitionedTables.length; i++) {
                partitionedTables[i] = table.clone(1024 * 1024);
            }

            // map site id to partition (this assumes 1:1, sorry).
//            int partitionsToSites[] = new int[numPartitions];
//            for (Site site : m_cluster.getSites()) {
//                if (site.getPartition() != null)
//                    partitionsToSites[Integer.parseInt(site.getPartition()
//                            .getName())] = Integer.parseInt(site.getName());
//            }

            // split the input table into per-partition units
            while (table.advanceRow()) {
                int p = -1;
                try {
                    p = this.p_estimator.getPartition(catTable, table.fetchRow(table.getActiveRowIndex()));
                } catch (Exception e) {
                    LOG.fatal("Failed to split input table into partitions", e);
                    throw new RuntimeException(e.getMessage());
                }
                assert(p >= 0);
                // this adds the active row from table
                partitionedTables[p].add(table);
            }
            
            String debug_msg = "LoadMultipartition Info for " + tableName + ":";

            // generate a plan fragment for each site using the sub-tables
            pfs = new SynthesizedPlanFragment[numPartitions  + 1];
            for (int i = 1; i <= partitionedTables.length; ++i) {
                int partition = i - 1;
                ParameterSet params = new ParameterSet();
                params.setParameters(tableName, partitionedTables[partition]);
                pfs[i] = new SynthesizedPlanFragment();
                pfs[i].fragmentId = SysProcFragmentId.PF_distribute;
                pfs[i].inputDependencyIds = new int[] { };
                pfs[i].outputDependencyIds = new int[] { (int)DEP_distribute };
                pfs[i].multipartition = false;
                pfs[i].nonExecSites = false;
                pfs[i].siteId = partition; // partitionsToSites[i - 1];
                pfs[i].parameters = params;
                pfs[i].last_task = true;
                
                if (debug) debug_msg += "\n  Partition #" + partition + ": " + partitionedTables[partition].getRowCount() + " tuples";
            }
            if (debug) LOG.debug(debug_msg);

            // a final plan fragment to aggregate the results
            pfs[0] = new SynthesizedPlanFragment();
            pfs[0].siteId = local_partition;
            pfs[0].fragmentId = SysProcFragmentId.PF_aggregate;
            pfs[0].inputDependencyIds = new int[] { (int)DEP_distribute };
            pfs[0].outputDependencyIds = new int[] { (int)DEP_aggregate };
            pfs[0].multipartition = false;
            pfs[0].nonExecSites = false;
            pfs[0].parameters = new ParameterSet();

            // send these forth in to the world .. and wait
            if (debug) LOG.debug("Passing " + pfs.length + " sysproc fragments to executeSysProcPlanFragments()");
            results = executeSysProcPlanFragments(pfs, (int)DEP_aggregate);
            return results;
        }
    }
}