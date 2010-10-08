package edu.brown.designer;

import java.util.*;

import org.apache.commons.collections15.set.ListOrderedSet;
import org.voltdb.catalog.*;
import org.voltdb.plannodes.AbstractPlanNode;
import org.voltdb.utils.Pair;

import edu.brown.BaseTestCase;
import edu.brown.catalog.QueryPlanUtil;
import edu.brown.designer.ColumnSet;
import edu.brown.designer.DesignerUtil;
import edu.brown.utils.ProjectType;

/**
 * 
 * @author pavlo
 *
 */
public class TestDesignerUtil extends BaseTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp(ProjectType.TPCC, false);
    }
    
    /**
     * Checks that the ColumnSet has the proper mapping from Columns to StmtParameters
     * @param cset
     * @param expected
     */
    private void checkColumnSet(ColumnSet cset, Set<Pair<Column, Integer>> expected) {
        for (ColumnSet.Entry entry : cset) {
            int column_idx = (entry.getFirst() instanceof StmtParameter ? 1 : 0);
            int param_idx = (column_idx == 0 ? 1 : 0);
            
            assertEquals(entry.get(column_idx).getClass(), Column.class);
            assertEquals(entry.get(param_idx).getClass(), StmtParameter.class);
            
            Column catalog_col = (Column)entry.get(column_idx);
            StmtParameter catalog_param = (StmtParameter)entry.get(param_idx); 
            
            Pair<Column, Integer> found = null;
            // System.err.println("TARGET: " + catalog_col + " <=> " + catalog_param);
            for (Pair<Column, Integer> pair : expected) {
                //System.err.println("  COMPARE: " + pair);
                if (pair.getFirst().equals(catalog_col) && pair.getSecond() == catalog_param.getIndex()) {
                    found = pair;
                    break;
                }
            } // FOR
            if (found == null) System.err.println("Failed to find " + entry + "\n" + cset.debug());
            assertNotNull("Failed to find " + entry, found);
            expected.remove(found);
        } // FOR
        assertEquals(0, expected.size());
    }
    
    /**
     * testExtractStatementColumnSet
     */
//    public void testExtractStatementColumnSet() throws Exception {
//        Table catalog_tbl = catalog_db.getTables().get("DISTRICT");
//        assertNotNull(catalog_tbl);
//        Procedure catalog_proc = catalog_db.getProcedures().get("neworder");
//        assertNotNull(catalog_proc);
//        Statement catalog_stmt = catalog_proc.getStatements().get("getDistrict");
//        assertNotNull(catalog_stmt);
//        ColumnSet cset = DesignerUtil.extractStatementColumnSet(catalog_stmt, false, catalog_tbl);
//        
//        // Column -> StmtParameter Index
//        Set<Pair<Column, Integer>> expected_columns = new HashSet<Pair<Column, Integer>>();
//        expected_columns.add(Pair.of(catalog_tbl.getColumns().get("D_ID"), 0));
//        expected_columns.add(Pair.of(catalog_tbl.getColumns().get("D_W_ID"), 1));
//        assertEquals(catalog_stmt.getParameters().size(), expected_columns.size());
//        
//        this.checkColumnSet(cset, expected_columns);
//    }
    
    /**
     * testExtractUpdateColumnSet
     */
//    public void testExtractUpdateColumnSet() throws Exception {
//        Table catalog_tbl = catalog_db.getTables().get("DISTRICT");
//        assertNotNull(catalog_tbl);
//        Procedure catalog_proc = catalog_db.getProcedures().get("neworder");
//        assertNotNull(catalog_proc);
//        Statement catalog_stmt = catalog_proc.getStatements().get("incrementNextOrderId");
//        assertNotNull(catalog_stmt);
//        ColumnSet cset = DesignerUtil.extractUpdateColumnSet(catalog_stmt, false, catalog_tbl);
//        // System.out.println(cset.debug());
//        
//        // Column -> StmtParameter Index
//        Set<Pair<Column, Integer>> expected_columns = new HashSet<Pair<Column, Integer>>();
//        expected_columns.add(Pair.of(catalog_tbl.getColumns().get("D_NEXT_O_ID"), 0));
//        
//        this.checkColumnSet(cset, expected_columns);
//    }

    /**
     * testExtractPlanNodeColumnSet
     */
    public void testExtractPlanNodeColumnSet() throws Exception {
        Procedure catalog_proc = catalog_db.getProcedures().get("slev");
        assertNotNull(catalog_proc);
        Statement catalog_stmt = catalog_proc.getStatements().get("GetStockCount");
        
        AbstractPlanNode root_node = QueryPlanUtil.deserializeStatement(catalog_stmt, true);
        assertNotNull(root_node);
        // System.out.println(PlanNodeUtil.debug(root_node));
        
        ColumnSet cset = new ColumnSet();
        Table tables[] = new Table[] {
                catalog_db.getTables().get("ORDER_LINE"),
                catalog_db.getTables().get("STOCK")
        };
        for (Table catalog_tbl : tables) {
            Set<Table> table_set = new HashSet<Table>();
            table_set.add(catalog_tbl);    
            DesignerUtil.extractPlanNodeColumnSet(catalog_stmt, catalog_db, cset, root_node, false, table_set);
        }
        
        // Column -> StmtParameter Index
        Set<Pair<Column, Integer>> expected_columns = new ListOrderedSet<Pair<Column, Integer>>();
        expected_columns.add(Pair.of(tables[0].getColumns().get("OL_W_ID"), 0));
        expected_columns.add(Pair.of(tables[0].getColumns().get("OL_D_ID"), 1));
        expected_columns.add(Pair.of(tables[0].getColumns().get("OL_O_ID"), 2));
        expected_columns.add(Pair.of(tables[0].getColumns().get("OL_O_ID"), 3));
        expected_columns.add(Pair.of(tables[1].getColumns().get("S_W_ID"), 4));
        expected_columns.add(Pair.of(tables[1].getColumns().get("S_QUANTITY"), 5));
        
        this.checkColumnSet(cset, expected_columns);
    }    
}