package edu.brown.hstore.dtxn;

import java.util.Collection;
import java.util.Collections;

import org.apache.log4j.Logger;
import org.voltdb.StoredProcedureInvocation;
import org.voltdb.VoltTable;
import org.voltdb.VoltTableRow;
import org.voltdb.catalog.Procedure;
import org.voltdb.catalog.Table;

import com.google.protobuf.RpcCallback;

import edu.brown.catalog.CatalogUtil;
import edu.brown.hstore.HStoreSite;
import edu.brown.hstore.Hstoreservice;
import edu.brown.hstore.Hstoreservice.Status;
import edu.brown.hstore.Hstoreservice.TransactionMapResponse;
import edu.brown.hstore.Hstoreservice.TransactionReduceResponse;
import edu.brown.hstore.callbacks.SendDataCallback;
import edu.brown.hstore.callbacks.TransactionCleanupCallback;
import edu.brown.hstore.callbacks.TransactionMapCallback;
import edu.brown.hstore.callbacks.TransactionMapWrapperCallback;
import edu.brown.hstore.callbacks.TransactionReduceCallback;
import edu.brown.hstore.callbacks.TransactionReduceWrapperCallback;
import edu.brown.logging.LoggerUtil;
import edu.brown.logging.LoggerUtil.LoggerBoolean;

/**
 * Special transaction state object for MapReduce jobs
 * @author pavlo
 * @author xin
 */
public class MapReduceTransaction extends LocalTransaction {
    private static final Logger LOG = Logger.getLogger(MapReduceTransaction.class);
    private final static LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private final static LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }
    
    private final LocalTransaction local_txns[];
    public int partitions_size;
    
    private VoltTable mapOutput[];
    private VoltTable reduceInput[];
    private VoltTable reduceOutput[];

    public enum State {
        MAP,
        SHUFFLE,
        REDUCE,
        FINISH;
    }

    /**
     * MapReduce Phases
     */
    private State mr_state = null;
    
    private Table mapEmit;
    private Table reduceEmit;
    
    /**
     * This is for non-blocking reduce executing in MapReduceHelperThread
     */
    public boolean basePartition_reduce_runed = false;
    public boolean basePartition_map_runed = false;
    
    // ----------------------------------------------------------------------------
    // CALLBACKS
    // ----------------------------------------------------------------------------

    
    /**
     */
    private final TransactionMapCallback map_callback;

    private final TransactionMapWrapperCallback mapWrapper_callback;
    
    private final SendDataCallback sendData_callback;
    
    private final TransactionReduceCallback reduce_callback;
    
    private final TransactionReduceWrapperCallback reduceWrapper_callback;
    
    private final TransactionCleanupCallback cleanup_callback;

    /**
     * Constructor 
     * @param hstore_site
     */
    public MapReduceTransaction(HStoreSite hstore_site) {
        super(hstore_site);
        // new local_txns
        this.partitions_size = this.hstore_site.getLocalPartitionIds().size();
        this.local_txns = new LocalTransaction[this.partitions_size];
        for (int i = 0; i < this.partitions_size; i++) {
            this.local_txns[i] = new LocalTransaction(hstore_site) {
                @Override
                public String toString() {
                    if (this.isInitialized()) {
                        return MapReduceTransaction.this.toString() + "/" + this.base_partition;
                    } else {
                        return ("<Uninitialized>");
                    }
                }
            };
        } // FOR
        
        // new mapout and reduce output talbes for each partition it wants to touch
        this.mapOutput = new VoltTable[this.partitions_size];
        this.reduceInput = new VoltTable[this.partitions_size];
        this.reduceOutput = new VoltTable[this.partitions_size];
                
        this.map_callback = new TransactionMapCallback(hstore_site);
        this.mapWrapper_callback = new TransactionMapWrapperCallback(hstore_site);
        
        this.sendData_callback = new SendDataCallback(hstore_site);
        //this.sendDataWrapper_callback = new SendDataWrapperCallback(hstore_site);
        
        this.reduce_callback = new TransactionReduceCallback(hstore_site);
        this.reduceWrapper_callback = new TransactionReduceWrapperCallback(hstore_site);
        
        this.cleanup_callback = new TransactionCleanupCallback(hstore_site);
    }
    
    
    @Override
    public LocalTransaction init(Long txn_id, long clientHandle, int base_partition,
            Collection<Integer> predict_touchedPartitions, boolean predict_readOnly, boolean predict_canAbort,
            Procedure catalog_proc, StoredProcedureInvocation invocation, RpcCallback<byte[]> client_callback) {
        assert (invocation != null) : "invalid StoredProcedureInvocation parameter for MapReduceTransaction.init()";
        assert (catalog_proc != null) : "invalid Procedure parameter for MapReduceTransaction.init()";
        
        super.init(txn_id, clientHandle, base_partition,
                   predict_touchedPartitions, predict_readOnly, predict_canAbort,
                   catalog_proc, invocation, client_callback);
        this.mapEmit = hstore_site.getDatabase().getTables().get(this.catalog_proc.getMapemittable());
        this.reduceEmit = hstore_site.getDatabase().getTables().get(this.catalog_proc.getReduceemittable());
        LOG.info(" CatalogUtil.getVoltTable(thisMapEmit): -> " + this.catalog_proc.getMapemittable());
        LOG.info("MapReduce LocalPartitionIds: " + this.hstore_site.getLocalPartitionIds());
        
        // Get the Table catalog object for the map/reduce outputs
        // For each partition there should be a map/reduce output voltTable
        for (int partition : this.hstore_site.getLocalPartitionIds()) {
            int offset = hstore_site.getLocalPartitionOffset(partition);
            //int offset = partition;
            LOG.info(String.format("Partition[%d] -> Offset[%d]", partition, offset));
            this.local_txns[offset].init(this.txn_id, this.client_handle, partition,
                                         Collections.singleton(partition),
                                         this.predict_readOnly, this.predict_abortable,
                                         catalog_proc, invocation, null);
            
            this.local_txns[offset].setPartOfMapreduce(true);
            // init map/reduce Output for each partition
            assert(this.mapEmit != null): "mapEmit has not been initialized\n ";
            assert(this.reduceEmit != null): "reduceEmit has not been initialized\n ";
            this.mapOutput[offset] = CatalogUtil.getVoltTable(this.mapEmit);
            this.reduceInput[offset] = CatalogUtil.getVoltTable(this.mapEmit);
            this.reduceOutput[offset] = CatalogUtil.getVoltTable(this.reduceEmit);
            
        } // FOR
        
        this.setMapPhase();
        this.map_callback.init(this);
        assert(this.map_callback.isInitialized()) : "Unexpected error for " + this;
        this.reduce_callback.init(this);
        assert(this.reduce_callback.isInitialized()) : "Unexpected error for " + this;
        
        // TODO(xin): Initialize the TransactionCleanupCallback if this txn's base partition
        //            is not at this HStoreSite. 
        if (!this.hstore_site.isLocalPartition(base_partition)) {
            cleanup_callback.init(this, Status.OK, this.hstore_site.getLocalPartitionIds());
        }
        
        
        LOG.info("Invoked MapReduceTransaction.init() -> " + this);
        return (this);
    }

    public MapReduceTransaction init(Long txn_id, int base_partition, Procedure catalog_proc, StoredProcedureInvocation invocation) {
        this.init(txn_id, invocation.getClientHandle(), base_partition,
                  hstore_site.getAllPartitionIds(), false, true,
                  catalog_proc, invocation, null);
        LOG.info("Invoked MapReduceTransaction.init() -> " + this);
        assert(this.map_callback.isInitialized()) : "Unexpected error for " + this;
        //assert(this.sendData_callback.isInitialized()) : "Unexpected error for " + this;
        return (this);
    }
    
    @Override
    public void finish() {
        super.finish();
//        for (int i = 0; i < this.partitions_size; i++) {
//            this.local_txns[i].finish();
//        } // FOR
        this.mr_state = null;
        
        this.map_callback.finish();
        this.mapWrapper_callback.finish();
        this.sendData_callback.finish();
        this.reduce_callback.finish();
        this.reduceWrapper_callback.finish();
        
        // TODO(xin): Only call TransactionCleanupCallback.finish() if this txn's base
        //            partition is not at this HStoreSite. 
        if (!this.hstore_site.isLocalPartition(this.base_partition)) {
            this.cleanup_callback.finish();
        }
        
        
        if(debug.get()) LOG.debug("<MapReduceTransaction> this.reduceWrapper_callback.finish().......................");
        this.mapEmit = null;
        this.reduceEmit = null;
        this.mapOutput = null;
        this.reduceInput = null;
        this.reduceOutput = null;
    }
    /**
     * Store Data from MapOutput table into reduceInput table
     * ReduceInput table is the result of all incoming mapOutput table from other partitions
     * @see edu.brown.hstore.dtxn.AbstractTransaction#storeData(int, org.voltdb.VoltTable)
     */
    @Override
    public synchronized Hstoreservice.Status storeData(int partition, VoltTable vt) {
        VoltTable input = this.getReduceInputByPartition(partition);
        
        assert(input != null);
        if (debug.get())
            LOG.debug(String.format("StoreData into Partition #%d: RowCount=%d ",
                    partition, vt.getRowCount()));
        
        if (debug.get())
            LOG.debug(String.format("<StoreData, change to ReduceInputTable> to Partition:%d>\n %s",partition,vt));
        while (vt.advanceRow()) {
            VoltTableRow row = vt.fetchRow(vt.getActiveRowIndex());
            assert(row != null);
            input.add(row);
        }
        vt.resetRowPosition();
        
        return Hstoreservice.Status.OK;
    }
    
    /**
     * Get a LocalTransaction handle for a local partition
     * 
     * @param partition
     * @return
     */
    public LocalTransaction getLocalTransaction(int partition) {
        int offset = hstore_site.getLocalPartitionOffset(partition);
        //int offset = partition;
        return (this.local_txns[offset]);
    }
    
    // ----------------------------------------------------------------------------
    // ACCESS METHODS
    // ----------------------------------------------------------------------------
    
    @Override
    public boolean isDeletable() {
        // I think that there is still a race condition here...
        if (this.cleanup_callback != null && this.cleanup_callback.getCounter() == 0) {
            return (false);
        }
        return (super.isDeletable());
    }
    
    public boolean isBasePartition_map_runed() {
        return basePartition_map_runed;
    }

    public void setBasePartition_map_runed(boolean map_runed) {
        this.basePartition_map_runed = map_runed;
    }

    public boolean isBasePartition_reduce_runed() {
        return basePartition_reduce_runed;
    }

    public void setBasePartition_reduce_runed(boolean reduce_runed) {
        basePartition_reduce_runed = reduce_runed;
    }
    
    /*
     * Return the MapOutput Table schema 
     */
    
    public boolean isMapPhase() {
        return (this.mr_state == State.MAP);
    }
   
    public boolean isShufflePhase() {
        return (this.mr_state == State.SHUFFLE); 
    }
    
    public boolean isReducePhase() {
        return (this.mr_state == State.REDUCE);
    }
    
    public boolean isFinishPhase() {
        return (this.mr_state == State.FINISH);
    }
    
    public void setMapPhase() {
        assert (this.mr_state == null);
        this.mr_state = State.MAP;
    }

    public void setShufflePhase() {
        assert(this.isMapPhase());
        this.mr_state = State.SHUFFLE;
    }
    
    public void setReducePhase() {
        assert(this.isShufflePhase());
        
        for (int i = 0; i < this.partitions_size; i++) {
            this.local_txns[i].resetExecutionState();
        }
        
        this.mr_state = State.REDUCE;
    }
    
    public void setFinishPhase() {
        assert(this.isReducePhase());
        this.mr_state = State.FINISH;
    }
    /*
     * return the size of partitions that MapReduce Transaction will touch 
     */
    public int getSize() {
        return partitions_size;
    }    
    public Table getMapEmit() {
        return mapEmit;
    }
    /*
     * Return the ReduceOutput Table schema 
     */
    
    public Table getReduceEmit() {
        
        return reduceEmit;
    }

    public State getState() {
        return (this.mr_state);
    }
        
    public VoltTable[] getReduceOutput() {
        return this.reduceOutput;
    }
    
    public Collection<Integer> getPredictTouchedPartitions() {
        return (this.hstore_site.getAllPartitionIds());
    }

    public TransactionMapCallback getTransactionMapCallback() {
        return (this.map_callback);
    }

    public TransactionMapWrapperCallback getTransactionMapWrapperCallback() {
        assert(this.mapWrapper_callback.isInitialized());
        return (this.mapWrapper_callback);
    }
    
    public SendDataCallback getSendDataCallback() {
        return sendData_callback;
    }

    public TransactionReduceCallback getTransactionReduceCallback() {
        return (this.reduce_callback);
    }
    
    public TransactionReduceWrapperCallback getTransactionReduceWrapperCallback() {
        assert(this.reduceWrapper_callback.isInitialized());
        return (this.reduceWrapper_callback);
    }
    
    public TransactionCleanupCallback getCleanupCallback() {
        // TODO(xin): This should return null if this handle is located at
        //            the txn's basePartition HStoreSite
        if (this.hstore_site.isLocalPartition(base_partition)) return null;
        else return (this.cleanup_callback);
    }
    
    public void initTransactionMapWrapperCallback(RpcCallback<TransactionMapResponse> orig_callback) {
        if (debug.get()) LOG.debug("Trying to intialize TransactionMapWrapperCallback for " + this);
        assert (this.mapWrapper_callback.isInitialized() == false);
        this.mapWrapper_callback.init(this, orig_callback);
    }
    
    public void initTransactionReduceWrapperCallback(RpcCallback<TransactionReduceResponse> orig_callback) {
        if (debug.get()) LOG.debug("Trying to initialize TransactionReduceWrapperCallback for " + this);
        //assert (this.reduceWrapper_callback.isInitialized() == false);
        this.reduceWrapper_callback.init(this, orig_callback);
    }
    
    

    @Override
    public String toString() {
        if (this.isInitialized()) {
            return String.format("%s-%s #%d/%d", this.getProcedureName(), (this.getState().toString()), this.txn_id, this.base_partition);
        } else {
            return ("<Uninitialized>");
        }
    }

//    @Override
//    public boolean isPredictSinglePartition() {
//        if (debug.get() && !this.hstore_site.getHStoreConf().site.mr_map_blocking) 
//            LOG.debug("Trying to do asynchronous map execution way, txs:" + this);
//        return !this.hstore_site.getHStoreConf().site.mr_map_blocking;
//    }
    

    @Override
    public void initRound(int partition, long undoToken) {
        throw new RuntimeException("initRound should not be invoked on " + this.getClass());
    }

    @Override
    public void startRound(int partition) {
        throw new RuntimeException("startRound should not be invoked on " + this.getClass());
    }

    @Override
    public void finishRound(int partition) {
        throw new RuntimeException("finishRound should not be invoked on " + this.getClass());
    }
    
    public VoltTable getMapOutputByPartition( int partition ) {
        if (debug.get()) LOG.debug("Trying to getMapOutputByPartition: [ " + partition + " ]");
        return this.mapOutput[hstore_site.getLocalPartitionOffset(partition)];
    }
    
    public VoltTable getReduceInputByPartition ( int partition ) {
        if (debug.get()) LOG.debug("Trying to getReduceInputByPartition: [ " + partition + " ]");
        return this.reduceInput[hstore_site.getLocalPartitionOffset(partition)];
        //return this.reduceInput[partition];
    }
    
    public VoltTable getReduceOutputByPartition ( int partition ) {
        if (debug.get()) LOG.debug("Trying to getReduceOutputByPartition: [ " + partition + " ]");
        return this.reduceOutput[hstore_site.getLocalPartitionOffset(partition)];
        //return this.reduceOutput[partition];
    }
    
}
