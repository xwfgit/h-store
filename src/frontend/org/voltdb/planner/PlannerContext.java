/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
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

package org.voltdb.planner;

import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.voltdb.expressions.AbstractExpression;
import org.voltdb.planner.PlanColumn.SortOrder;
import org.voltdb.planner.PlanColumn.Storage;

public class PlannerContext {

    /**
     * Generator for PlanColumn.m_guid
     */
    private AtomicInteger s_nextId = new AtomicInteger();

    /**
     * Global hash of PlanColumn guid to PlanColumn reference
     */
    private TreeMap<Integer, PlanColumn>
        s_columnPool = new TreeMap<Integer, PlanColumn>();

    public PlanColumn getPlanColumn(AbstractExpression expression, String columnName) {
        return getPlanColumn(expression, columnName, SortOrder.kUnsorted, Storage.kTemporary);
    }

    /** Provide the common defaults */
    public PlanColumn getPlanColumn(AbstractExpression expression,
            String columnName,
            SortOrder sortOrder,
            Storage storage) {

        int guid = s_nextId.incrementAndGet();
        PlanColumn retval = new PlanColumn(guid, expression, columnName, sortOrder, storage);
        // in to the pool...
        assert(s_columnPool.get(guid) == null);
        s_columnPool.put(guid, retval);

        return retval;
    }
    
    public void add(int guid, PlanColumn col) {
        assert(!this.s_columnPool.containsKey(guid)) :
            "PlannerContext already contains entry for guid #" + guid + ": " + this.s_columnPool.get(guid);
        this.s_columnPool.put(guid, col);
    }
    
    /**
     * Retrieve a column instance by guid.
     */
    public PlanColumn get(int guid) {
        PlanColumn column = s_columnPool.get(guid);
//        assert(column != null) : "Failed to retrieve PlanColumn guid=" + guid;
        return column;
    }

    public synchronized void freeColumn(int guid) {
        s_columnPool.remove(guid);
    }
    
    // PAVLO: Global singleton for us to use to get back the PlanColumns we need
    private static final PlannerContext m_singleton = new PlannerContext();
    public static PlannerContext singleton() {
        return (m_singleton);
    }
    
}