package edu.brown.hstore.callbacks;

import java.util.Collection;

import org.apache.log4j.Logger;

import edu.brown.hstore.HStoreSite;
import edu.brown.hstore.Hstoreservice;
import edu.brown.hstore.Hstoreservice.Status;
import edu.brown.hstore.dtxn.AbstractTransaction;
import edu.brown.logging.LoggerUtil;
import edu.brown.logging.LoggerUtil.LoggerBoolean;

/**
 * Special callback that keeps track as to whether we have finished up
 * with everything that we need for a given transaction at a HStoreSite.
 * If we have, then we know it is safe to go ahead and call HStoreSite.deleteTransaction()
 * @author pavlo
 */
public class TransactionCleanupCallback extends BlockingCallback<Integer, Integer> {
    private static final Logger LOG = Logger.getLogger(TransactionCleanupCallback.class);
    private final static LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private final static LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }
 
    private AbstractTransaction ts;
    private Status status;
    
    /**
     * Constructor
     * @param hstore_site
     */
    public TransactionCleanupCallback(HStoreSite hstore_site) {
        super(hstore_site, false);
    }

    public void init(AbstractTransaction ts, Hstoreservice.Status status, Collection<Integer> partitions) {
        if (debug.get())
            LOG.debug("Initializing " + this.getClass().getSimpleName() + " for " + ts);
        
        // Only include local partitions
        int counter = 0;
        for (Integer p : hstore_site.getLocalPartitionIdArray()) {
            if (partitions.contains(p)) counter++;
        } // FOR
        assert(counter > 0);
        super.init(ts.getTransactionId(), counter, null);
        
        this.ts = ts;
        this.status = status;
    }
    
    @Override
    protected void finishImpl() {
        this.ts = null;
    }
    
    @Override
    public boolean isInitialized() {
        return (this.ts != null && super.isInitialized());
    }
    
    @Override
    protected void unblockCallback() {
        hstore_site.deleteTransaction(this.getTransactionId(), this.status);
    }
    
    @Override
    protected void abortCallback(Hstoreservice.Status status) {
        String msg = String.format("Unexpected %s abort for %s", this.getClass().getSimpleName(), this.ts);
        throw new RuntimeException(msg); 
    }
    
    @Override
    protected int runImpl(Integer partition) {
        return (1);
    }
}
