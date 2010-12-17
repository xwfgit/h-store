package edu.brown.benchmark.locality.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

import edu.brown.benchmark.locality.LocalityConstants;

/**
 * 
 * @author sw47
 */
public class GetLocal extends VoltProcedure {

    public final SQLStmt GetA = new SQLStmt(
        "SELECT * FROM TABLEA WHERE A_ID = ? ");
    
    public final SQLStmt GetB = new SQLStmt(
		"SELECT * FROM TABLEB WHERE B_A_ID = ? LIMIT " + LocalityConstants.GET_TABLEB_LIMIT);

    /**
     * 
     * @param a_id
     * @return
     */
    public VoltTable[] run(long a_id) {
        voltQueueSQL(GetA, a_id);
        voltQueueSQL(GetB, a_id);
        return (voltExecuteSQL());
    }
    
}
