/*
 * Copyright 2004-2018 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore.tx;

import org.h2.mvstore.DataUtils;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.type.DataType;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A transaction.
 */
public class Transaction {

    /**
     * The status of a closed transaction (committed or rolled back).
     */
    public static final int STATUS_CLOSED = 0;

    /**
     * The status of an open transaction.
     */
    public static final int STATUS_OPEN = 1;

    /**
     * The status of a prepared transaction.
     */
    public static final int STATUS_PREPARED = 2;

    /**
     * The status of a transaction that is being committed, but possibly not
     * yet finished. A transactions can go into this state when the store is
     * closed while the transaction is committing. When opening a store,
     * such transactions should be committed.
     */
    public static final int STATUS_COMMITTING = 3;

    /**
     * The status of a transaction that has been logically committed or rather
     * marked as committed, because it might be still listed among prepared,
     * if it was prepared for commit, undo log entries might still exists for it
     * and not all of it's changes within map's are re-written as committed yet.
     * Nevertheless, those changes should be already viewed by other
     * transactions as committed.
     * This transaction's id can not be re-used until all the above is completed
     * and transaction is closed.
     */
    public static final int STATUS_COMMITTED    = 4;

    /**
     * The status of a transaction that currently in a process of rolling back
     * to a savepoint.
     */
    private static final int STATUS_ROLLING_BACK = 5;

    /**
     * The status of a transaction that has been rolled back completely,
     * but undo operations are not finished yet.
     */
    private static final int STATUS_ROLLED_BACK  = 6;

    private static final String STATUS_NAMES[] = {
            "CLOSED", "OPEN", "PREPARED", "COMMITTING",
            "COMMITTED", "ROLLING_BACK", "ROLLED_BACK"
    };

    /**
     * The transaction store.
     */
    final TransactionStore store;

    /**
     * The transaction id.
     */
    final int transactionId;

    /*
     * Transation state is an atomic composite field:
     * bit 44       : flag whether transaction had rollback(s)
     * bits 42-40   : status
     * bits 39-0    : log id of the last entry in the undo log map
     */
    private final AtomicLong statusAndLogId;

    private MVStore.TxCounter txCounter;

    private String name;

    Transaction(TransactionStore store, int transactionId, int status,
                String name, long logId) {
        this.store = store;
        this.transactionId = transactionId;
        this.statusAndLogId = new AtomicLong(composeState(status, logId, false));
        this.name = name;
    }

    public int getId() {
        return transactionId;
    }

    public int getStatus() {
        return getStatus(statusAndLogId.get());
    }

    long setStatus(int status) {
        while (true) {
            long currentState = statusAndLogId.get();
            long logId = getLogId(currentState);
            int currentStatus = getStatus(currentState);
            boolean valid;
            switch (status) {
                case STATUS_OPEN:
                    valid = currentStatus == STATUS_CLOSED ||
                            currentStatus == STATUS_ROLLING_BACK;
                    break;
                case STATUS_ROLLING_BACK:
                    valid = currentStatus == STATUS_OPEN;
                    break;
                case STATUS_PREPARED:
                    valid = currentStatus == STATUS_OPEN;
                    break;
                case STATUS_COMMITTING:
                    valid = currentStatus == STATUS_OPEN ||
                            currentStatus == STATUS_PREPARED ||
                            // this case is only possible if called
                            // from endLeftoverTransactions()
                            currentStatus == STATUS_COMMITTING;
                    break;
                case STATUS_COMMITTED:
                    valid = currentStatus == STATUS_COMMITTING;
                    break;
                case STATUS_ROLLED_BACK:
                    valid = currentStatus == STATUS_OPEN ||
                            currentStatus == STATUS_PREPARED;
                    break;
                case STATUS_CLOSED:
                    valid = currentStatus == STATUS_COMMITTING ||
                            currentStatus == STATUS_COMMITTED ||
                            currentStatus == STATUS_ROLLED_BACK;
                    break;
               default:
                   valid = false;
                   break;
            }
            if (!valid) {
                throw DataUtils.newIllegalStateException(
                        DataUtils.ERROR_TRANSACTION_ILLEGAL_STATE,
                        "Transaction was illegally transitioned from {0} to {1}",
                        STATUS_NAMES[currentStatus], STATUS_NAMES[status]);
            }
            long newState = composeState(status, logId, hasRollback(currentState));
            if (statusAndLogId.compareAndSet(currentState, newState)) {
                return currentState;
            }
        }
    }

    public void setName(String name) {
        checkNotClosed();
        this.name = name;
        store.storeTransaction(this);
    }

    public String getName() {
        return name;
    }

    /**
     * Create a new savepoint.
     *
     * @return the savepoint id
     */
    public long setSavepoint() {
        return getLogId();
    }

    public void markStatementStart() {
        markStatementEnd();
        txCounter = store.store.registerVersionUsage();
    }

    public void markStatementEnd() {
        MVStore.TxCounter counter = txCounter;
        txCounter = null;
        if(counter != null) {
            store.store.deregisterVersionUsage(counter);
        }
    }

    /**
     * Add a log entry.
     *
     * @param mapId the map id
     * @param key the key
     * @param oldValue the old value
     */
    void log(int mapId, Object key, VersionedValue oldValue) {
        long currentState = statusAndLogId.getAndIncrement();
        long logId = getLogId(currentState);
        store.log(this, logId, mapId, key, oldValue);
    }

    /**
     * Remove the last log entry.
     */
    void logUndo() {
        long currentState = statusAndLogId.decrementAndGet();
        long logId = getLogId(currentState);
        store.logUndo(this, logId);
    }

    /**
     * Open a data map.
     *
     * @param <K> the key type
     * @param <V> the value type
     * @param name the name of the map
     * @return the transaction map
     */
    public <K, V> TransactionMap<K, V> openMap(String name) {
        return openMap(name, null, null);
    }

    /**
     * Open the map to store the data.
     *
     * @param <K> the key type
     * @param <V> the value type
     * @param name the name of the map
     * @param keyType the key data type
     * @param valueType the value data type
     * @return the transaction map
     */
    public <K, V> TransactionMap<K, V> openMap(String name,
                                               DataType keyType, DataType valueType) {
        MVMap<K, VersionedValue> map = store.openMap(name, keyType, valueType);
        return openMap(map);
    }

    /**
     * Open the transactional version of the given map.
     *
     * @param <K> the key type
     * @param <V> the value type
     * @param map the base map
     * @return the transactional map
     */
    public <K, V> TransactionMap<K, V> openMap(MVMap<K, VersionedValue> map) {
        checkNotClosed();
        int mapId = map.getId();
        return new TransactionMap<>(this, map, mapId);
    }

    /**
     * Prepare the transaction. Afterwards, the transaction can only be
     * committed or completely rolled back.
     */
    public void prepare() {
        setStatus(STATUS_PREPARED);
        store.storeTransaction(this);
    }

    /**
     * Commit the transaction. Afterwards, this transaction is closed.
     */
    public void commit() {
        long state = setStatus(Transaction.STATUS_COMMITTING);
        long logId = Transaction.getLogId(state);
        int oldStatus = Transaction.getStatus(state);
        store.commit(this, logId, oldStatus);
    }

    /**
     * Roll back to the given savepoint. This is only allowed if the
     * transaction is open.
     *
     * @param savepointId the savepoint id
     */
    public void rollbackToSavepoint(long savepointId) {
        long lastState = setStatus(STATUS_ROLLING_BACK);
        long logId = getLogId(lastState);
        try {
            store.rollbackTo(this, logId, savepointId);
        } finally {
            long expectedState = composeState(STATUS_ROLLING_BACK, logId, hasRollback(lastState));
            long newState = composeState(STATUS_OPEN, savepointId, true);
            if (!statusAndLogId.compareAndSet(expectedState, newState)) {
                throw DataUtils.newIllegalStateException(
                        DataUtils.ERROR_TRANSACTION_ILLEGAL_STATE,
                        "Transaction {0} concurrently modified " +
                                "while rollback to savepoint was in progress",
                        transactionId);
            }
        }
    }

    /**
     * Roll the transaction back. Afterwards, this transaction is closed.
     */
    public void rollback() {
        try {
            long lastState = setStatus(STATUS_ROLLED_BACK);
            long logId = getLogId(lastState);
            if (logId > 0) {
                store.rollbackTo(this, logId, 0);
            }
        } finally {
            store.endTransaction(this, STATUS_ROLLED_BACK);
        }
    }

    /**
     * Get the list of changes, starting with the latest change, up to the
     * given savepoint (in reverse order than they occurred). The value of
     * the change is the value before the change was applied.
     *
     * @param savepointId the savepoint id, 0 meaning the beginning of the
     *            transaction
     * @return the changes
     */
    public Iterator<TransactionStore.Change> getChanges(long savepointId) {
        return store.getChanges(this, getLogId(), savepointId);
    }

    long getLogId() {
        return getLogId(statusAndLogId.get());
    }

    /**
     * Check whether this transaction is open or prepared.
     */
    void checkNotClosed() {
        if (getStatus() == STATUS_CLOSED) {
            throw DataUtils.newIllegalStateException(
                    DataUtils.ERROR_CLOSED, "Transaction is closed");
        }
    }

    /**
     * Remove the map.
     *
     * @param map the map
     */
    public <K, V> void removeMap(TransactionMap<K, V> map) {
        store.removeMap(map);
    }

    @Override
    public String toString() {
        long state = statusAndLogId.get();
        return transactionId + " " +  STATUS_NAMES[getStatus(state)] + " " + getLogId(state);
    }


    public static int getStatus(long state) {
        return (int)(state >>> 40) & 15;
    }

    public static long getLogId(long state) {
        return state & ((1L << 40) - 1);
    }

    private static boolean hasRollback(long state) {
        return (state & (1L << 44)) != 0;
    }

    private static boolean hasChanges(long state) {
        return getLogId(state) != 0;
    }

    private static long composeState(int status, long logId, boolean hasRollback) {
        if (hasRollback) {
            status |= 16;
        }
        return ((long)status << 40) | logId;
    }
}