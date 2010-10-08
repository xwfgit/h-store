package edu.brown.markov;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;

import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.json.JSONStringer;
import org.voltdb.catalog.*;

import edu.brown.catalog.CatalogKey;
import edu.brown.catalog.CatalogUtil;
import edu.brown.graphs.GraphvizExport;
import edu.brown.graphs.GraphvizExport.Attributes;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.FileUtil;
import edu.brown.utils.PartitionEstimator;
import edu.brown.workload.*;
import edu.brown.workload.filters.ProcedureNameFilter;

/**
 * MarkovUtil class is here to help with comparisons and help cut down on the clutter in 
 * MarkovGraph.
 * 
 * For convenience use stop_stmt,start_stmt, and abort_stmt to get the vertices you would like
 * 
 * Constants are defined here as well. Define FIRST_PARTITION and NUM_PARTITIONS for your install
 * 
 * @author svelagap
 * @author pavlo
 */
public abstract class MarkovUtil {
    private static final Logger LOG = Logger.getLogger(MarkovUtil.class.getName());

    /**
     * Wrapper class for our special "marker" vertices
     */
    public static class StatementWrapper extends Statement {
        private final Vertex.Type type;
        private String id;
        private final Procedure parent = new Procedure() {
            public String getName() {
                return ("markov");
            };
        };

        public StatementWrapper(Vertex.Type type) {
            this.type = type;
            this.id="";
            this.setReadonly(true);
        }
        public StatementWrapper(Vertex.Type type, boolean readonly, String id) {
            this.type = type;
            this.id = id;
            this.setReadonly(readonly);
        }
        public String getName() {
            return ("--" + this.type.toString() + id +"--");
        } 
        @SuppressWarnings("unchecked")
        @Override
        public CatalogType getParent() {
            return (this.parent);
        }
    } // END CLASS
    private final static StatementWrapper start_stmt = new StatementWrapper(Vertex.Type.START);
    private final static StatementWrapper stop_stmt = new StatementWrapper(Vertex.Type.COMMIT);
    private final static StatementWrapper abort_stmt = new StatementWrapper(Vertex.Type.ABORT);

    /**
     * Return the Statement object representing the special Vertex type
     * @param type
     * @return
     */
    public static Statement getSpecialStatement(Vertex.Type type) {
        Statement ret = null;
        switch (type) {
            case START:
                ret = MarkovUtil.start_stmt;
                break;
            case COMMIT:
                ret = MarkovUtil.stop_stmt;
                break;
            case ABORT:
                ret = MarkovUtil.abort_stmt;
                break;
            default:
                assert(false) : "Unexpected Vertex type '" + type + "'";
        } // SWITCH
        return (ret);
    }
    
    /**
     * For the given Vertex type, return a unique Vertex instance
     * @param type
     * @return
     */
    public static Vertex getSpecialVertex(Database catalog_db, Vertex.Type type) {
        Vertex v = null;
        switch (type) {
            case START:
                v = new Vertex(MarkovUtil.start_stmt, Vertex.Type.START);
                break;
            case COMMIT:
                v = new Vertex(MarkovUtil.stop_stmt, Vertex.Type.COMMIT);
                break;
            case ABORT:
                v = new Vertex(MarkovUtil.abort_stmt, Vertex.Type.ABORT);
                v.addAbortProbability((float) 1.0);
                break;
            default:
                assert(false) : "Unexpected Vertex type '" + type + "'";
        } // SWITCH
        if (type != Vertex.Type.START) {
            for (int i : CatalogUtil.getAllPartitionIds(catalog_db)) {
                v.addDoneProbability(i, 1.0f);
            } // FOR
        }
        assert(v != null);
        return (v);
    }
    
    /**
     * Return a new instance of the special start vertex
     * @return
     */
    public static Vertex getStartVertex(Database catalog_db) {
        return (MarkovUtil.getSpecialVertex(catalog_db, Vertex.Type.START));
    }
    /**
     * A stop vertex has done probability of 1.0 at all partitions
     * @return a stop Vertex
     */
    public static Vertex getCommitVertex(Database catalog_db) {
        return (MarkovUtil.getSpecialVertex(catalog_db, Vertex.Type.COMMIT));
    }
    /**
     * An aborted vertex has an abort and done probability of 1.0 at all partitions
     * @return an abort Vertex
     */
    public static Vertex getAbortVertex(Database catalog_db) {
        return (MarkovUtil.getSpecialVertex(catalog_db, Vertex.Type.ABORT));
    }
    
    /**
     * To allow for any strange sort of partitioning proposition:
     * Say only partitions 1,3,7 exist in this guy or the numbering scheme 
     * starts somewhere strange, we just modify this instead of all the for loops everywhere.
     * @return a List of partitions
     */

    /**
     * Give a blank graph to fill in for each partition for the given procedure. Called by 
     * anyone who wants to create MarkovGraphs
     * @param numPartitions the number of partitions/sites in this installation
     * @param catalog_proc - the given procedure
     * @return mapping of Partition to MarkovGraph
     */
    public static Map<Integer, MarkovGraph> getBlankPartitionGraphs(Procedure catalog_proc) {
        HashMap<Integer, MarkovGraph> graphs = new HashMap<Integer, MarkovGraph>();
        Database catalog_db = CatalogUtil.getDatabase(catalog_proc);
        for (int i : CatalogUtil.getAllPartitionIds(catalog_db)) {
            MarkovGraph g = new MarkovGraph(catalog_proc, i);
            g.addVertex(MarkovUtil.getStartVertex(catalog_db));
            g.addVertex(MarkovUtil.getAbortVertex(catalog_db));
            g.addVertex(MarkovUtil.getCommitVertex(catalog_db));
            graphs.put(i, g);
        } // FOR
        return (graphs);
    }

    /**
     * Returns a unique set of Statement catalog objects for a given collection of vertices
     * @param vertices
     * @return
     */
    public static Set<Statement> getStatements(Collection<Vertex> vertices) {
        Set<Statement> ret = new HashSet<Statement>();
        for (Vertex v : vertices) {
            Statement catalog_stmt = v.getCatalogItem();
            assert(catalog_stmt != null);
            ret.add(catalog_stmt);
        } // FOR
        return (ret);
    }

    /**
     * Construct all of the Markov graphs for a workload+catalog
     * @param catalog_db
     * @param workload
     * @param p_estimator
     * @return
     */
    public static MarkovGraphsContainer createGraphs(
            Database catalog_db, AbstractWorkload workload, PartitionEstimator p_estimator) {
        assert(workload != null);
        assert(p_estimator != null);
        
        final List<Integer> partitions = CatalogUtil.getAllPartitionIds(catalog_db);
        
        final MarkovGraphsContainer graphs_per_partition = new MarkovGraphsContainer();
        for (Procedure catalog_proc : catalog_db.getProcedures()) {
            if (catalog_proc.getSystemproc()) continue;
            LOG.debug("Creating MarkovGraphs for " + catalog_proc);
            Map<Integer, MarkovGraph> gs = MarkovUtil.createGraphsForProcedure(catalog_proc, workload, p_estimator);
            for (int partition : gs.keySet()) {
                assert(partitions.contains(partition));
                MarkovGraph markov = gs.get(partition);
                graphs_per_partition.put(partition, markov);
            } // FOR
        } // FOR
        return (graphs_per_partition);
    }
    
    /**
     * Here we create a MarkovGraph for each partition for each procedure The
     * partition in this instance signifies the base partition of the
     * transaction
     * 
     * @param catalog_proc
     *            the procedure we are building the graphs for
     * @param catalog_db
     *            database
     * @param workload
     *            workload to trace through
     * @return
     */
    public static Map<Integer, MarkovGraph> createGraphsForProcedure(
            Procedure catalog_proc, AbstractWorkload workload, PartitionEstimator p_estimator) {
        final boolean trace = LOG.isTraceEnabled();
        assert(catalog_proc != null);
        assert(workload != null);
        assert(p_estimator != null);
        
        // read in from WorkloadTraceFileOutput
        ProcedureNameFilter filter = new ProcedureNameFilter();
        filter.include(catalog_proc.getName());
        Iterator<AbstractTraceElement<? extends CatalogType>> it = workload.iterator(filter);

        Map<Integer, MarkovGraph> partitiongraphs = MarkovUtil.getBlankPartitionGraphs(catalog_proc);
        while (it.hasNext()) {
            AbstractTraceElement<? extends CatalogType> element = it.next();
            if (element instanceof TransactionTrace) {
                // Estimate which partition this transaction started on (i.e., the base_partition)
                TransactionTrace xact = (TransactionTrace) element;
                assert(xact != null);
                Integer base_partition = null;
                try {
                    base_partition = p_estimator.getPartition(catalog_proc, xact.getParams(), true);
                } catch (Exception ex) {
                    LOG.fatal("Failed to calculate base partition for " + xact, ex);
                    System.exit(1);
                }
                // IMPORTANT: We should never have a null base_partition 
                assert(base_partition != null);
                if (trace) LOG.trace("Process " + xact + " => Partition #" + base_partition);
                
                MarkovGraph g = partitiongraphs.get(base_partition);
                assert(g != null) : "No MarkovGraph exists for base partition #" + base_partition;
                g.processTransaction(xact, p_estimator);
            }
        } // WHILE
        for (MarkovGraph g : partitiongraphs.values()) {
            g.setTransactionCount(workload.getTransactionCount());
            g.calculateProbabilities();
            g.unmarkAllEdges();
        } // FOR
        return partitiongraphs;
    }

    public static MarkovGraphsContainer load(Database catalog_db, String input_path) throws Exception {
        return (MarkovUtil.load(catalog_db, input_path, CatalogUtil.getAllPartitionIds(catalog_db)));
    }
    
    /**
     * Load a list of Markov objects from a serialized for a particular base partition
     * @param catalog_db
     * @param input_path
     * @param base_partition
     * @return
     * @throws Exception
     */
    public static Map<Procedure, MarkovGraph> load(Database catalog_db, String input_path, int base_partition) throws Exception {
        Map<Integer, Map<Procedure, MarkovGraph>> markovs = MarkovUtil.load(catalog_db, input_path,
                                                                            new ArrayList<Integer>(Arrays.asList(new Integer[] { base_partition })));
        assert(markovs.size() == 1);
        assert(markovs.containsKey(base_partition));
        return (markovs.get(base_partition));
    }
    
    /**
     * 
     * @param catalog_db
     * @param input_path
     * @param partitions
     * @return
     * @throws Exception
     */
    public static MarkovGraphsContainer load(Database catalog_db, String input_path, Collection<Integer> partitions) throws Exception {
        MarkovGraphsContainer ret = new MarkovGraphsContainer(); // HashMap<Integer, Map<Procedure,MarkovGraph>>();
        final String className = MarkovGraph.class.getSimpleName();
        LOG.info("Loading in serialized " + className + " from '" + input_path + "' [partitions=" + partitions + "]");
        String contents = FileUtil.readFile(input_path);
        if (contents.isEmpty()) {
            throw new IOException("The " + className + " file '" + input_path + "' is empty");
        }
        
        // File Format: One Partition per line, each with a mapping from Procedure to MarkovGraph
        HashSet<Integer> remaining = new HashSet<Integer>();
        remaining.addAll(partitions);
        try {
            BufferedReader in = FileUtil.getReader(input_path);
            while (in.ready()) {
                JSONObject json_object = new JSONObject(in.readLine());
                String partition_key = CollectionUtil.getFirst(json_object.keys());
                Integer partition = Integer.valueOf(partition_key);
                if (!remaining.contains(partition)) continue;
                
                Map<Procedure, MarkovGraph> markovs = new HashMap<Procedure, MarkovGraph>();
                JSONObject json_procs = json_object.getJSONObject(partition_key);
                Iterator<String> proc_keys = json_procs.keys();
                while (proc_keys.hasNext()) {
                    String proc_key = proc_keys.next();
                    Procedure catalog_proc = CatalogKey.getFromKey(catalog_db, proc_key, Procedure.class);
                    assert(catalog_proc != null);
                    
                    JSONObject json_graph = json_procs.getJSONObject(proc_key);
                    MarkovGraph markov = new MarkovGraph(catalog_proc, partition);
                    markov.fromJSON(json_graph, catalog_db);
                    markovs.put(catalog_proc, markov);
                } // WHILE
                ret.put(partition, markovs);
                remaining.remove(partition);
                if (remaining.isEmpty()) break;
            } // WHILE
        } catch (Exception ex) {
            LOG.error("Failed to deserialize the " + className + " from file '" + input_path + "'", ex);
            throw new IOException(ex);
        }
        LOG.debug("The loading of the " + className + " is complete");
        return (ret);
    }
    
    /**
     * For the given list of MarkovGraph objects, serialize them out to a file
     * @param markovs
     * @param output_path
     * @throws Exception
     */
    public static void save(MarkovGraphsContainer markovs, String output_path) throws Exception {
        final String className = MarkovGraph.class.getSimpleName();
        LOG.info("Writing out graphs of " + className + " to '" + output_path + "'");
        
        File file = new File(output_path);
        try {
            FileOutputStream out = new FileOutputStream(file);
            for (Integer partition : markovs.keySet()) {
                LOG.debug("Serializing " + markovs.get(partition).size() + " graphs for partition " + partition);
                JSONStringer stringer = new JSONStringer();
                stringer.object().key(partition.toString()).object();
                for (Entry<Procedure, MarkovGraph> e : markovs.get(partition).entrySet()) {
                    stringer.key(CatalogKey.createKey(e.getKey())).object();
                    e.getValue().toJSON(stringer);
                    stringer.endObject();
                } // FOR
                stringer.endObject().endObject();
                out.write(stringer.toString().getBytes());
                out.write("\n".getBytes());
            } // FOR
            out.close();
        } catch (Exception ex) {
            LOG.error("Failed to serialize the " + className + " file '" + output_path + "'", ex);
            throw new IOException(ex);
        }
        LOG.debug(className + " objects were written out to '" + output_path + "'");
    }
    
    /**
     * 
     * @param path
     * @throws Exception
     */
    public static GraphvizExport<Vertex, Edge> exportGraphviz(MarkovGraph markov, List<Vertex> path) throws Exception {
        GraphvizExport<Vertex, Edge> graphviz = new GraphvizExport<Vertex, Edge>(markov);
        graphviz.setEdgeLabels(true);
        graphviz.getGlobalGraphAttributes().put(Attributes.PACK, "true");
        
        Vertex v = markov.getStartVertex();
        graphviz.getAttributes(v).put(Attributes.FILLCOLOR, "darkgreen");
        graphviz.getAttributes(v).put(Attributes.FONTCOLOR, "white");
        graphviz.getAttributes(v).put(Attributes.STYLE, "filled,bold");

        v = markov.getCommitVertex();
        graphviz.getAttributes(v).put(Attributes.FILLCOLOR, "darkgreen");
        graphviz.getAttributes(v).put(Attributes.FONTCOLOR, "white");
        graphviz.getAttributes(v).put(Attributes.STYLE, "filled,bold");
        
        v = markov.getAbortVertex();
        graphviz.getAttributes(v).put(Attributes.FILLCOLOR, "firebrick4");
        graphviz.getAttributes(v).put(Attributes.FONTCOLOR, "white");
        graphviz.getAttributes(v).put(Attributes.STYLE, "filled,bold");

        if (path != null) graphviz.highlightPath(markov.getPath(path), "red");
        
        return (graphviz);
    }
    
    public static Set<Integer> getTouchedPartitions(List<Vertex> path) {
        Set<Integer> partitions = new HashSet<Integer>();
        for (Vertex v : path) {
            partitions.addAll(v.getPartitions());
        } // FOR
        return (partitions);
    }
}