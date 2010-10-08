/**
 * 
 */
package edu.brown.designer.generators;

import java.util.*;

import org.voltdb.catalog.*;

import edu.brown.designer.*;
import edu.brown.graphs.*;
import edu.uci.ics.jung.graph.util.EdgeType;

/**
 * @author pavlo
 *
 */
public class ReplicationTreeGenerator extends AbstractGenerator<AbstractDirectedGraph<Vertex, Edge>> {

    private final PartitionTree ptree;
    private final AccessGraph agraph;
    private Set<Table> replication_candidates = new HashSet<Table>();
    private Set<Edge> conflict_edges = new HashSet<Edge>();
    private Set<Vertex> conflict_vertices = new HashSet<Vertex>();
    
    public ReplicationTreeGenerator(DesignerInfo info, AccessGraph agraph, PartitionTree ptree) {
        super(info);
        this.agraph = agraph;
        this.ptree = ptree;
    }
    
    /**
     * Return the list of replication candidates generated
     * @return
     */
    public Set<Table> getReplicationCandidates() {
        return (this.replication_candidates);
    }
    
    /**
     * Return the list of edges that were added to the partition tree
     * @return
     */
    public Set<Edge> getConflictEdges() {
        return (this.conflict_edges);
    }
    
    /**
     * Return the list of vertices that cause conflict edges to be added to the tree
     * @return
     */
    public Set<Vertex> getConflictVertices() {
        return (this.conflict_vertices);
    }
    
    @Override
    public void generate(AbstractDirectedGraph<Vertex, Edge> graph) throws Exception {
        //
        // First clone the original partition tree
        //
        graph.clone(this.ptree);
        for (Vertex vertex : graph.getVertices()) {
            vertex.copyAttributes(this.ptree, graph);
        } // FOR
        
        //
        // Then for each root we want to traverse their section of the PartitionTree
        // and check to see that for each pair of adjacent vertices in the AccessGraph
        // that there is a path from one vertex to another
        //
        List<Vertex> ptree_vertices = new ArrayList<Vertex>();
        ptree_vertices.addAll(this.ptree.getVertices());
    
        Set<Vertex> modified = new HashSet<Vertex>();
        for (int ctr0 = 0, cnt = ptree_vertices.size(); ctr0 < cnt; ctr0++) {
            Vertex v0 = ptree_vertices.get(ctr0);
            if (this.ptree.isReplicated(v0)) continue;
            
            LOG.debug("v0: " + v0 + "\n");
            for (int ctr1 = ctr0 + 1; ctr1 < cnt; ctr1++) {
                Vertex v1 = ptree_vertices.get(ctr1);
                LOG.debug("  |- v1: " + v1 + "\n");
                Edge ag_edge = this.agraph.findEdge(v0, v1);
                
                if (((v0.getCatalogItem().getName().equals("WAREHOUSE") && v1.getCatalogItem().getName().equals("STOCK")) ||
                    (v1.getCatalogItem().getName().equals("WAREHOUSE") && v0.getCatalogItem().getName().equals("STOCK"))) && 
                    ag_edge == null) {
                    LOG.debug("???????????\n");
                }
                
                //
                // If this other vertex is already replicated, then we don't care
                //
                if (this.ptree.isReplicated(v1)) continue;
                
                //
                // If there is an edge between these two vertices, then check whether
                // there is a path from the "upper" vertex to the "lower" vertex
                //
                if (ag_edge != null &&
                    this.ptree.getPath(v0, v1).isEmpty() && this.ptree.getPath(v1, v0).isEmpty() &&
                    graph.getPath(v0, v1).isEmpty() && graph.getPath(v1, v0).isEmpty()) {
                        
                    //
                    // There was no edge in the PartitionTree, so that means we want to add
                    // an edge in our replication tree. Note that although edges in the AccessGraph
                    // are undirected, we need to make sure that our edge goes in the right direction.
                    // Well, that's easy then there is a foreign key but what about when it's just
                    // some random join?
                    //
                    boolean found = false;
                    for (int ctr = 0; ctr < 2; ctr++) {
                        if (ctr == 1) {
                            Vertex temp = v0;
                            v0 = v1;
                            v1 = temp;
                        }
                        //Edge dg_edge = this.info.dgraph.findEdge(v0, v1);
                        //Edge dg_edge = this.agraph.findEdge(v0, v1);
                        if (this.info.dependencies.getDescendants((Table)v0.getCatalogItem()).contains(v1.getCatalogItem())) {
                            LOG.debug("CREATED EDGE: " + v0 + " -> " + v1 + "\n");
                            Edge new_edge = new Edge(graph, ag_edge);
                            graph.addEdge(new_edge, v0, v1, EdgeType.DIRECTED);
                            this.conflict_edges.add(new_edge);
                            this.conflict_vertices.add(v1);
                            found = true;
                            modified.add(v0);
                            modified.add(v1);
                            break;
                        }
                    } // FOR
                    if (!found) {
                        //LOG.warn("Not creating missing edge " + ag_edge + " because there is no edge in the DependencyGraph");
                    }
                }
            } // FOR
        } // FOR
        
        //
        // Now for each vertex that was modified, we mark their roots as candidates
        //
        Map<Vertex, Set<Vertex>> descendants = new HashMap<Vertex, Set<Vertex>>();
        for (Vertex root : graph.getRoots()) {
            descendants.put(root, graph.getDescendants(root));
            LOG.debug(root + ": " + descendants.get(root) + "\n");
        } // FOR
        
        for (Vertex vertex : modified) {
            LOG.debug("Checking: " + vertex + "\n");
            for (Vertex root : descendants.keySet()) {
                if (descendants.get(root).contains(vertex)) {
                    LOG.debug("Found in " + descendants.get(root) + "\n");
                    this.replication_candidates.add((Table)root.getCatalogItem());
                    //break;
                }
            } // FOR
        } // FOR
        LOG.info("Replication Candidates: " + this.replication_candidates);
        return;
    }
}