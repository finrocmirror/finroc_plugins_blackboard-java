//
// You received this file as part of Finroc
// A Framework for intelligent robot control
//
// Copyright (C) Finroc GbR (finroc.org)
//
// This program is free software; you can redistribute it and/or
// modify it under the terms of the GNU General Public License
// as published by the Free Software Foundation; either version 2
// of the License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
//
//----------------------------------------------------------------------
package org.finroc.plugins.blackboard;

import java.util.ArrayList;

import org.rrlib.finroc_core_utils.jc.thread.ThreadUtil;
import org.rrlib.finroc_core_utils.rtti.DataTypeBase;
import org.rrlib.finroc_core_utils.serialization.PortDataList;
import org.finroc.core.FrameworkElement;
import org.finroc.core.port.rpc.MethodCallException;
import org.finroc.core.port.rpc.method.AbstractMethod;
import org.finroc.core.port.rpc.method.Method1Handler;
import org.finroc.core.port.rpc.method.Method2Handler;
import org.finroc.core.port.rpc.method.Port0Method;
import org.finroc.core.port.rpc.method.Port1Method;
import org.finroc.core.port.rpc.method.Port2Method;
import org.finroc.core.port.rpc.method.PortInterface;
import org.finroc.core.port.rpc.method.Void1Method;
import org.finroc.core.port.rpc.method.Void3Handler;
import org.finroc.core.port.rpc.method.Void3Method;

/**
 * Abstract base class of all blackboard servers - typed version
 */
@SuppressWarnings("rawtypes")
abstract class AbstractBlackboardServer<T> extends AbstractBlackboardServerRaw implements
    Method1Handler<PortDataList, Integer>,
    Method2Handler<PortDataList, Integer, Integer>,
    Void3Handler<PortDataList, Integer, Integer> {

    /**
     * Queue with pending asynch change commands
     * they don't lock and don't execute in an extra thread
     * may only be accessed in synchronized context
     */
    public final ArrayList<BlackboardTask> pendingAsynchChangeTasks = new ArrayList<BlackboardTask>();

    // Methods...

    /** Blackboard interface */
    private static PortInterface METHODS = new PortInterface("Blackboard Interface", true);

    /** Write Lock */
    public static Port1Method < AbstractBlackboardServer<?>, PortDataList, Integer > LOCK =
        new Port1Method < AbstractBlackboardServer<?>, PortDataList, Integer > (getBlackboardInterface(), "Write Lock", "timeout", true);

    /** Read Lock (only useful for SingleBufferedBlackboardBuffers) */
    public static Port2Method < AbstractBlackboardServer<?>, PortDataList, Integer, Integer > READ_LOCK =
        new Port2Method < AbstractBlackboardServer<?>, PortDataList, Integer, Integer > (getBlackboardInterface(), "Read Lock", "timeout", "dummy", true);

    /** Write Unlock */
    public static Void1Method UNLOCK = new Void1Method(getBlackboardInterface(), "Write Unlock", "Blackboard Buffer", false);

    /** Read Unlock */
    public static Void1Method READ_UNLOCK = new Void1Method(getBlackboardInterface(), "Read Unlock", "Lock ID", false);

    /** Asynch Change */
    public static Void3Method < AbstractBlackboardServer<?>, PortDataList, Integer, Integer > ASYNCH_CHANGE =
        new Void3Method < AbstractBlackboardServer<?>, PortDataList, Integer, Integer > (getBlackboardInterface(), "Asynchronous Change", "Blackboard Buffer", "Start Index", "Custom Offset", false);

//    /** Read part of blackboard (no extra thread with multi-buffered blackboards) */
//    @PassByValue public static Port3Method<AbstractBlackboardServer, BlackboardBuffer, Integer, Integer, Integer> READ_PART =
//        new Port3Method<AbstractBlackboardServer, BlackboardBuffer, Integer, Integer, Integer>(METHODS, "Read Part", "Offset", "Length", "Timeout", true);

    /** Directly commit buffer */
    public static Void1Method DIRECT_COMMIT = new Void1Method(getBlackboardInterface(), "Direct Commit", "Buffer", false);

    /** Is server a single-buffered blackboard server? */
    public static Port0Method<AbstractBlackboardServer<Byte>, Byte> IS_SINGLE_BUFFERED =
        new Port0Method<AbstractBlackboardServer<Byte>, Byte>(getBlackboardInterface(), "Is Single Buffered?", false);

    /** Send keep-alive signal for lock */
    public static Void1Method KEEP_ALIVE = new Void1Method(getBlackboardInterface(), "KeepAliveSignal", "Lock ID", false);

    /**
     * @param bbName Blackboard name
     * @param category Blackboard category (see constants in BlackboardManager)
     */
    public AbstractBlackboardServer(String bbName, int category, FrameworkElement parent) {
        super(bbName, category, parent);
    }

    public static PortInterface getBlackboardInterface() {
        return METHODS;
    }

    /**
     * Copy a blackboard buffer
     * TODO: provide factory for buffer reuse
     *
     * @param src Source Buffer
     * @param target Target Buffer
     */
    public void copyBlackboardBuffer(PortDataList<T> src, PortDataList<T> target) {
        target.copyFrom(src);
        //target.resize(src.getBbCapacity(), src.getElements(), src.getElementSize(), false);
        //target.getBuffer().put(0, src.getBuffer(), 0, src.getSize());
    }

    /**
     * @return Thread string (debug helper method)
     */
    protected String createThreadString() {
        return "Thread " + Thread.currentThread().toString() + " (" + ThreadUtil.getCurrentThreadId() + ")";
    }

    /**
     * @return Is blackboard currently locked?
     */
    protected abstract boolean isLocked();

    /**
     * Execute any pending tasks
     * (may only be called as last statement in synchronized context - and when there's no lock)
     *
     * @return Were there any pending commands that are (were) now executed?
     */
    protected boolean processPendingCommands() {
        //System.out.println(createThreadString() + ": process pending commands");
        if (pendingMajorTasks.size() == 0) {
            //System.out.println(createThreadString() + ": nothing to do");
            return false;
        }
        assert(wakeupThread == -1);
        BlackboardTask nextTask = pendingMajorTasks.remove(0);
        wakeupThread = nextTask.threadUid;
        //System.out.println(createThreadString() + ": waking up thread " + wakeupThread);
        bbLock.notifyAll();
        return true;
    }

    /**
     * (only call in synchronized context)
     * Clear any asynch change tasks from list
     */
    protected void clearAsyncChangeTasks() {

        for (int i = 0; i < pendingAsynchChangeTasks.size(); i++) {
            pendingAsynchChangeTasks.get(i).recycle2();
        }

        pendingAsynchChangeTasks.clear();
    }

    /**
     * (only call in synchronized context)
     * Process pending asynch change commands (good idea when unlocking blackboard)
     */
    protected void processPendingAsynchChangeTasks() {
        for (int i = 0; i < pendingAsynchChangeTasks.size(); i++) {
            BlackboardTask task = pendingAsynchChangeTasks.get(i);
            asynchChange(task.buffer, (int)task.index, (int)task.offset, false);
            task.buffer = null; // already unlocked by method
            task.recycle2();
        }
        pendingAsynchChangeTasks.clear();
    }

    /**
     * (only call in synchronized context)
     * Process change command later
     *
     * @param offset Offset offset to start writing
     * @param buf (Locked) buffer with contents to write
     */
    protected void deferAsynchChangeCommand(PortDataList buf, int index, int offset) {
        BlackboardTask task = getUnusedBlackboardTask();
        task.offset = offset;
        task.buffer = buf;
        task.index = index;
        pendingAsynchChangeTasks.add(task);
    }

    /**
     * Check whether lock has timed out
     */
    abstract public void lockCheck();

    /**
     * @return Does blackboard have pending commands that are waiting for execution?
     */
    protected boolean pendingTasks() {
        return (pendingMajorTasks.size() > 0) || (wakeupThread != -1);
    }

    // methods that need to be implemented

    /**
     * Keep-alive signal received
     *
     * @param lockId LockId from origin
     */
    protected abstract void keepAlive(int lockId);

    /**
     * Asynchronous change to blackboard
     *
     * @param buf Buffer with contents to write there
     * @param startIdx Start index in blackboard
     * @param offset Custom optional Offset in element type
     * @param checkLock Check whether buffer is currently locked, before performing asynch change (normal operation)
     */
    protected abstract void asynchChange(PortDataList buf, int startIdx, int offset, boolean checkLock);

    /**
     * Unlock blackboard (from write lock)
     *
     * @param buf Buffer containing changes (may be the same or another one - the latter is typically the case with remote clients)
     */
    protected abstract void writeUnlock(PortDataList buf);

    /**
     * Unlock blackboard (from read lock)
     *
     * @param lockId LockId from origin
     */
    protected abstract void readUnlock(int lockId) throws MethodCallException;

    /**
     * Direct commit of new blackboard buffer
     *
     * @param buf New Buffer
     */
    protected abstract void directCommit(PortDataList buf);

    /**
     * @return Is this a single buffered blackboard server?
     */
    protected abstract boolean isSingleBuffered();

    /**
     * Perform read lock (only do this on single-buffered blackboards)
     *
     * @param timeout Timeout in ms
     * @return Locked BlackboardBuffer (if lockId is < 0 this is a copy)
     */
    protected abstract PortDataList readLock(long timeout) throws MethodCallException;

    /**
     * Perform read lock (only do this on single-buffered blackboards)
     *
     * @param timeout Timeout in ms
     * @return Locked BlackboardBuffer (if lockId is < 0 this is a copy)
     */
    protected abstract PortDataList writeLock(long timeout);

//    /**
//     * Read part of blackboard
//     *
//     * @param offset Offset to start reading
//     * @param length Length (in bytes) of area to read
//     * @param timeout Timeout for this command
//     * @return Memory buffer containing read area
//     */
//    protected abstract BlackboardBuffer readPart(int offset, int length, int timeout) throws MethodCallException ;

    // Call handling

    @Override
    public void handleVoidCall(AbstractMethod method, Object p1) throws MethodCallException {
        if (method == KEEP_ALIVE) {
            keepAlive((Integer)p1);
        } else if (method == DIRECT_COMMIT) {
            directCommit((PortDataList)p1);
        } else if (method == READ_UNLOCK) {
            readUnlock((Integer)p1);
        } else if (method == UNLOCK) {
            writeUnlock((PortDataList)p1);
        } else {
            LOCK.cleanup(p1);
            throw new MethodCallException(MethodCallException.Type.UNKNOWN_METHOD);
        }
    }

//    @Override
//    public BlackboardBuffer handleCall(AbstractMethod method, Integer p1, Integer p2, Integer p3) throws MethodCallException {
//        assert(method == READ_PART);
//        return readPart(p1, p2, p3);
//    }

    @Override
    public PortDataList handleCall(AbstractMethod method, Integer p1) throws MethodCallException {
        assert(method == LOCK);
        return writeLock(p1);
    }

    @Override
    public PortDataList handleCall(AbstractMethod method, Integer p1, Integer dummy) throws MethodCallException {
        assert(method == READ_LOCK);
        return readLock(p1);
    }

    @Override
    public void handleVoidCall(AbstractMethod method, PortDataList p2, Integer index, Integer offset) throws MethodCallException {
        assert(method == ASYNCH_CHANGE);
        asynchChange(p2, index, offset, true);
    }

    /**
     * Resize blackboard
     *
     * @param buf Buffer to resize
     * @param newCapacity new Capacity
     * @param newElements new current number of elements
     */
    protected void resize(PortDataList<T> buf, int newCapacity, int newElements/*, int newElementSize, boolean keepContents*/) {
        buf.resize(newElements);
    }

    /**
     * Apply asynch change to blackboard
     *
     * @param bb blackboard buffer
     * @param changes Changes to apply
     */
    @SuppressWarnings("unchecked")
    protected void applyAsynchChange(PortDataList bb, PortDataList changes, int index, int offset) {
        bb.applyChange(changes, index, offset);
    }

    /**
     * Helper for constructor
     *
     * @param dt DataType T
     * @return Blackboard method type of write ports
     */
    protected DataTypeBase getBlackboardMethodType(DataTypeBase dt) {
        BlackboardTypeInfo ti = getBlackboardTypeInfo(dt);
        if (ti != null && ti.blackboardType != null) {
            return ti.blackboardType;
        }
        BlackboardPlugin.<T>registerBlackboardType(dt);
        return getBlackboardTypeInfo(dt).blackboardType;
    }

    @Override
    protected void postChildInit() {
        // check that methods have correct indices
        assert(LOCK.getMethodId() == 0);
        assert(READ_LOCK.getMethodId() == 1);
        super.postChildInit();
    }
}
