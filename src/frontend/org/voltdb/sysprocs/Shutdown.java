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

import org.voltdb.HsqlBackend;
import org.voltdb.BackendTarget;
import org.voltdb.DependencySet;
import org.voltdb.ExecutionSite;
import org.voltdb.VoltDB;
import org.voltdb.ParameterSet;
import org.voltdb.ProcInfo;
import org.voltdb.ProcedureProfiler;
import org.voltdb.VoltSystemProcedure;
import org.voltdb.VoltTable;
import org.voltdb.catalog.Cluster;
import org.voltdb.catalog.Procedure;
import org.voltdb.dtxn.DtxnConstants;
import org.voltdb.utils.VoltLoggerFactory;
import org.apache.log4j.Logger;

import edu.brown.catalog.CatalogUtil;
import edu.brown.utils.PartitionEstimator;

/** A wholly improper shutdown. The only guarantee is that a transaction
 * is committed or not committed - never partially committed. However, no
 * promise is given to return a result to a client, to finish work queued
 * behind this procedure or to return meaningful errors for those queued
 * transactions.
 *
 * Invoking this procedure will terminate each node in the cluster.
 */

@ProcInfo(singlePartition = false)

public class Shutdown extends VoltSystemProcedure {

    private List<Integer> all_partitions;
    static final long DEP_distribute = SysProcFragmentId.PF_distribute | DtxnConstants.MULTIPARTITION_DEPENDENCY;
    static final long DEP_aggregate = SysProcFragmentId.PF_aggregate;

    @Override
    public void init(ExecutionSite site, Procedure catProc,
            BackendTarget eeType, HsqlBackend hsql, Cluster cluster,
            PartitionEstimator p_estimator, Integer local_partition) {
        super.init(site, catProc, eeType, hsql, cluster, p_estimator, local_partition);
        site.registerPlanFragment(SysProcFragmentId.PF_shutdownCommand, this);
        site.registerPlanFragment(SysProcFragmentId.PF_procedureDone, this);
        this.all_partitions = CatalogUtil.getAllPartitionIds(catProc);
    }

    @Override
    public DependencySet executePlanFragment(long txn_id, HashMap<Integer, List<VoltTable>> dependencies,
            int fragmentId,
                                           ParameterSet params,
            ExecutionSite.SystemProcedureExecutionContext context) {
        if (fragmentId == SysProcFragmentId.PF_shutdownCommand) {
            ProcedureProfiler.flushProfile();
            try {
                Thread.sleep(1000);
            }
            catch (InterruptedException e1) {
                e1.printStackTrace();
            }
            Thread shutdownThread = new Thread() {
                @Override
                public void run() {
                    try {
                        VoltDB.instance().shutdown(this);
                    } catch (InterruptedException e) {
                        Logger.getLogger("HOST", VoltLoggerFactory.instance()).error(
                                "Exception while attempting to shutdown VoltDB from shutdown sysproc",
                                e);
                    }
                    System.exit(0);
                }
            };
            shutdownThread.start();
            System.exit(0);
        }
        return null;
    }

    public VoltTable[] run() {
        SynthesizedPlanFragment pfs[] = new SynthesizedPlanFragment[this.all_partitions.size() + 1];
        for (int i = 1; i < pfs.length; i++) {
            pfs[i] = new SynthesizedPlanFragment();
            pfs[i].siteId = this.all_partitions.get(i-1);
            pfs[i].fragmentId = SysProcFragmentId.PF_shutdownCommand;
            pfs[i].outputDependencyIds = new int[]{ (int)DEP_distribute };
            pfs[i].inputDependencyIds = new int[]{};
            pfs[i].multipartition = false;
            pfs[i].nonExecSites = true;
            pfs[i].parameters = new ParameterSet();
        }
        // a final plan fragment to aggregate the results
        pfs[0] = new SynthesizedPlanFragment();
        pfs[0].siteId = local_partition;
        pfs[0].fragmentId = SysProcFragmentId.PF_aggregate;
        pfs[0].inputDependencyIds = new int[] { (int)DEP_distribute };
        pfs[0].outputDependencyIds = new int[] { (int)DEP_aggregate };
        pfs[0].multipartition = false;
        pfs[0].nonExecSites = false;
        pfs[0].parameters = new ParameterSet();

        executeSysProcPlanFragments(pfs, (int)SysProcFragmentId.PF_procedureDone);
        return new VoltTable[0];
    }
}