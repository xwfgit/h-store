package edu.brown.correlations;

import java.io.File;
import java.util.*;

import org.voltdb.VoltProcedure;
import org.voltdb.benchmark.tpcc.procedures.neworder;
import org.voltdb.catalog.*;

import edu.brown.BaseTestCase;
import edu.brown.correlations.CorrelationCalculator.*;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.ProjectType;
import edu.brown.workload.*;
import edu.brown.workload.filters.ProcedureNameFilter;

public class TestCorrelationCalculator extends BaseTestCase {
    private static final int WORKLOAD_XACT_LIMIT = 1000;
    private static final Class<? extends VoltProcedure> TARGET_PROCEDURE = neworder.class;
    
    private static AbstractWorkload workload;
    private Random rand = new Random(0);
    private CorrelationCalculator pc;
    private Procedure catalog_proc;
    
    @Override
    protected void setUp() throws Exception {
        super.setUp(ProjectType.TPCC);

        if (workload == null) {
            File file = this.getWorkloadFile(ProjectType.TPCC);
            workload = new WorkloadTraceFileOutput(catalog);
            ProcedureNameFilter filter = new ProcedureNameFilter();
            filter.include(TARGET_PROCEDURE.getSimpleName(), WORKLOAD_XACT_LIMIT);
            ((WorkloadTraceFileOutput) workload).load(file.getAbsolutePath(), catalog_db, filter);
        }
        
        // Setup
        this.catalog_proc = this.getProcedure(TARGET_PROCEDURE);
        this.pc = new CorrelationCalculator(catalog_db);
    }
    
    /**
     * testQueryInstance
     */
    public void testQueryInstance() {
        ProcedureCorrelations procc = this.pc.getProcedureCorrelations(this.catalog_proc);
        procc.start();
        for (Statement catalog_stmt : this.catalog_proc.getStatements()) {
            Set<QueryInstance> previous = new HashSet<QueryInstance>();
            for (int i = 0; i < 2; i++) {
                QueryInstance query_instance = procc.getQueryInstance(catalog_stmt);
                assertNotNull(query_instance);
                assertEquals(i, query_instance.getSecond().intValue());
                assertFalse(previous.contains(query_instance));
                previous.add(query_instance);
            } // FOR
        } // FOR
        procc.finish();
    }
    
    /**
     * testProcParameterCorrelation
     */
    public void testProcParameterCorrelation() {
        ProcedureCorrelations procc = this.pc.getProcedureCorrelations(this.catalog_proc);
        procc.start();
        
        Statement catalog_stmt = CollectionUtil.getFirst(this.catalog_proc.getStatements());
        assertNotNull(catalog_stmt);

        QueryInstance query_instance = procc.getQueryInstance(catalog_stmt);
        assertNotNull(query_instance);
        assertEquals(0, query_instance.getSecond().intValue());
        for (StmtParameter catalog_stmt_param : catalog_stmt.getParameters()) {
            Set<AbstractCorrelation> previous = new HashSet<AbstractCorrelation>();
            
            for (ProcParameter catalog_proc_param : this.catalog_proc.getParameters()) {
                ProcParameterCorrelation ppc = query_instance.getProcParameterCorrelation(catalog_stmt_param, catalog_proc_param);
                assertNotNull(ppc);
                assertEquals(catalog_proc_param.getIsarray(), ppc.getIsArray());
                assertEquals(catalog_proc_param, ppc.getProcParameter());
                
                int cnt = (ppc.getIsArray() ? rand.nextInt(10) : 1);
                for (int i = 0; i < cnt; i++) {
                    AbstractCorrelation correlation = ppc.getAbstractCorrelation(i);
                    assertFalse(previous.contains(correlation));
                    previous.add(correlation);
                } // FOR
            } // FOR
        } // FOR
        procc.finish();
    }
    
    /**
     * testProcessTransaction
     */
    public void testProcessTransaction() throws Exception {
        TransactionTrace xact_trace = null;
        for (int i = 0; i < 100; i++) {
            xact_trace = workload.getTransactions().get(i);
            this.pc.processTransaction(xact_trace);
        }
        assertNotNull(xact_trace);
        
        this.pc.calculate();
        
        ProcedureCorrelations procc = this.pc.getProcedureCorrelations(this.catalog_proc);
        assertNotNull(procc);
//         System.err.println(xact_trace.debug(catalog_db));
        
        Statement catalog_stmt = this.catalog_proc.getStatements().get("getStockInfo01");
        assertNotNull(catalog_stmt);
        
        double threshold = 1.0d;
        ParameterCorrelations pc = procc.getCorrelations(threshold);
        assertNotNull(pc);
//        System.err.println(pc.debug());
        SortedMap<Integer, SortedMap<StmtParameter, SortedSet<Correlation>>> stmt_correlations = pc.get(catalog_stmt);
        assertNotNull(stmt_correlations);
        assertFalse(stmt_correlations.isEmpty());
//        assertEquals(1, stmt_correlations.size());
        SortedMap<StmtParameter, SortedSet<Correlation>> param_correlations = stmt_correlations.get(0);
        assertEquals(catalog_stmt.getParameters().size(), param_correlations.size());
        
//        System.err.println(procc.debug(catalog_stmt));
//        System.err.print(CorrelationCalculator.DEFAULT_DOUBLE_LINE);
//        for (StmtParameter catalog_stmt_param : param_correlations.keySet()) {
//            System.err.println(catalog_stmt_param + ": " + param_correlations.get(catalog_stmt_param));
//        }
//        System.err.print(CorrelationCalculator.DEFAULT_DOUBLE_LINE);
//        System.err.println(pc.debug(catalog_stmt));
//        System.err.print(CorrelationCalculator.DEFAULT_DOUBLE_LINE);
//        System.err.println("FULL DUMP:");
//        for (Correlation c : pc) {
//            if (c.getStatement().equals(catalog_stmt)) System.err.println("   " + c);
//        }
//        System.err.print(CorrelationCalculator.DEFAULT_DOUBLE_LINE);
        
        ProcParameter expected_param[] = new ProcParameter[] {
                this.catalog_proc.getParameters().get(4),
                this.catalog_proc.getParameters().get(5),
        };
        int expected_index[] = { 0, 11 };
        
        for (int i = 0, cnt = catalog_stmt.getParameters().size(); i < cnt; i++) {
            StmtParameter catalog_param = catalog_stmt.getParameters().get(i);
            assertNotNull(catalog_param);
            Correlation c = CollectionUtil.getFirst(param_correlations.get(catalog_param));
            assertNotNull(c);
            
            assert(c.getCoefficient() >= threshold);
            assertEquals("[" + i + "]", expected_param[i], c.getProcParameter());
            assertEquals("[" + i + "]", expected_index[i], c.getProcParameterIndex().intValue());
        } // FOR
    }
    
}