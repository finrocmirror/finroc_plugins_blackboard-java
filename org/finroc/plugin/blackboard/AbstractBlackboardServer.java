/**
 * You received this file as part of an advanced experimental
 * robotics framework prototype ('finroc')
 *
 * Copyright (C) 2007-2010 Max Reichardt,
 *   Robotics Research Lab, University of Kaiserslautern
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.finroc.plugin.blackboard;

import org.finroc.jc.Time;
import org.finroc.jc.annotation.AtFront;
import org.finroc.jc.annotation.Const;
import org.finroc.jc.annotation.CppDefault;
import org.finroc.jc.annotation.CppType;
import org.finroc.jc.annotation.Elems;
import org.finroc.jc.annotation.JavaOnly;
import org.finroc.jc.annotation.PassByValue;
import org.finroc.jc.annotation.PassLock;
import org.finroc.jc.annotation.Ptr;
import org.finroc.jc.annotation.SizeT;
import org.finroc.jc.annotation.Struct;
import org.finroc.jc.annotation.Superclass;
import org.finroc.jc.container.SimpleList;
import org.finroc.jc.thread.ThreadUtil;
import org.finroc.core.FrameworkElement;
import org.finroc.core.port.rpc.InterfacePort;
import org.finroc.core.port.rpc.MethodCallException;
import org.finroc.core.port.rpc.method.AbstractMethod;
import org.finroc.core.port.rpc.method.AbstractMethodCallHandler;
import org.finroc.core.port.rpc.method.Method0Handler;
import org.finroc.core.port.rpc.method.Method1Handler;
import org.finroc.core.port.rpc.method.Method2Handler;
import org.finroc.core.port.rpc.method.Method3Handler;
import org.finroc.core.port.rpc.method.Port0Method;
import org.finroc.core.port.rpc.method.Port1Method;
import org.finroc.core.port.rpc.method.Port2Method;
import org.finroc.core.port.rpc.method.Port3Method;
import org.finroc.core.port.rpc.method.PortInterface;
import org.finroc.core.port.rpc.method.Void1Handler;
import org.finroc.core.port.rpc.method.Void1Method;
import org.finroc.core.port.rpc.method.Void2Handler;
import org.finroc.core.port.rpc.method.Void2Method;
import org.finroc.core.port.std.PortBase;
import org.finroc.core.portdatabase.DataType;

/** Blackboard info */
@SuppressWarnings("unchecked")
@Struct @AtFront @Ptr @Superclass( {FrameworkElement.class, AbstractMethodCallHandler.class})
abstract class AbstractBlackboardServer extends FrameworkElement implements
        Method1Handler<BlackboardBuffer, Long>,
        Method2Handler<BlackboardBuffer, Long, Integer>,
        Void2Handler<Integer, BlackboardBuffer>,
        Method3Handler<BlackboardBuffer, Integer, Integer, Integer>,
            Void1Handler, Method0Handler<Byte> {

    /** read port */
    public PortBase readPort;

    /** write port */
    public InterfacePort writePort;

    /** Category index of Blackboard category that this server belongs to (see constants in BlackboardManager) */
    public final int categoryIndex;

    /** Blackboard category that this server belongs to */
    public final @Ptr BlackboardManager.BlackboardCategory myCategory;

    /**
     * Queue with pending major commands (e.g. LOCK, READ_PART in SingleBufferedBlackboard)
     * They are executed in another thread
     * may only be accessed in synchronized context */
    protected final @PassByValue SimpleList<BlackboardTask> pendingMajorTasks = new SimpleList<BlackboardTask>();

    /**
     * Queue with pending asynch change commands
     * they don't lock and don't execute in an extra thread
     * may only be accessed in synchronized context
     */
    protected final @PassByValue SimpleList<BlackboardTask> pendingAsynchChangeTasks = new SimpleList<BlackboardTask>();

    /** Uid of thread that is allowed to wake up now - after notifyAll() - thread should reset this to -1 as soon as possible */
    protected long wakeupThread = -1;

    // Methods...

    /** Blackboard interface */
    @PassByValue public static PortInterface METHODS = new PortInterface("Blackboard Interface");

    /** Write Lock */
    @PassByValue public static Port1Method<AbstractBlackboardServer, BlackboardBuffer, Long> LOCK =
        new Port1Method<AbstractBlackboardServer, BlackboardBuffer, Long>(METHODS, "Lock", "timeout", true);

    /** Read Lock (only useful for SingleBufferedBlackboardBuffers) */
    @Elems( {Void.class, Const.class, Void.class, Void.class})
    @PassByValue public static Port2Method<AbstractBlackboardServer, BlackboardBuffer, Long, Integer> READ_LOCK =
        new Port2Method<AbstractBlackboardServer, BlackboardBuffer, Long, Integer>(METHODS, "Lock", "timeout", "dummy", true);

    /** Write Unlock */
    @CppType("core::Void1Method<AbstractBlackboardServer*, BlackboardBuffer*>")
    @PassByValue public static Void1Method UNLOCK =
        new Void1Method(METHODS, "Unlock", "Blackboard Buffer", false);

    /** Read Unlock */
    @CppType("core::Void1Method<AbstractBlackboardServer*, int>")
    @PassByValue public static Void1Method READ_UNLOCK =
        new Void1Method(METHODS, "Unlock", "Lock ID", false);

    /** Asynch Change */
    @Elems( {Void.class, Void.class, Const.class})
    @PassByValue public static Void2Method<AbstractBlackboardServer, Integer, BlackboardBuffer> ASYNCH_CHANGE =
        new Void2Method<AbstractBlackboardServer, Integer, BlackboardBuffer>(METHODS, "Asynchronous Change", "Offset", "Blackboard Buffer (null if only read locked", false);

    /** Read part of blackboard (no extra thread with multi-buffered blackboards) */
    @PassByValue public static Port3Method<AbstractBlackboardServer, BlackboardBuffer, Integer, Integer, Integer> READ_PART =
        new Port3Method<AbstractBlackboardServer, BlackboardBuffer, Integer, Integer, Integer>(METHODS, "Read Part", "Offset", "Length", "Timeout", true);

    /** Directly commit buffer */
    @CppType("core::Void1Method<AbstractBlackboardServer*, BlackboardBuffer*>")
    @PassByValue public static Void1Method DIRECT_COMMIT =
        new Void1Method(METHODS, "Direct Commit", "Buffer", false);

    /** Is server a single-buffered blackboard server? */
    @PassByValue public static Port0Method<AbstractBlackboardServer, Byte> IS_SINGLE_BUFFERED =
        new Port0Method<AbstractBlackboardServer, Byte>(METHODS, "Is Single Buffered?", false);

    /** Is server a single-buffered blackboard server? */
    @CppType("core::Void1Method<AbstractBlackboardServer*, int>")
    @PassByValue public static Void1Method KEEP_ALIVE =
        new Void1Method(METHODS, "KeepAliveSignal", "Lock ID", false);

    /**
     * @param bbName Blackboard name
     * @param category Blackboard category (see constants in BlackboardManager)
     */
    public AbstractBlackboardServer(String bbName, int category, @CppDefault("NULL") FrameworkElement parent) {
        this(bbName, category, BlackboardManager.getInstance().getCategory(category).defaultFlags, parent);
    }

    /**
     * @param bbName Blackboard name
     * @param category Blackboard category (see constants in BlackboardManager)
     * @param flags Flags for blackboard
     */
    @JavaOnly
    private AbstractBlackboardServer(String bbName, int category, int flags, @CppDefault("NULL") FrameworkElement parent) {
        super(bbName, parent == null ? BlackboardManager.getInstance().getCategory(category) : parent, flags, -1);
        myCategory = BlackboardManager.getInstance().getCategory(category);
        categoryIndex = category;
    }

    protected void postChildInit() {
        myCategory.add(this);
    }

    protected synchronized void prepareDelete() {
        if (BlackboardManager.getInstance() != null) { // we don't need to remove it, if blackboard manager has already been deleted
            myCategory.remove(this);
        }

        clearAsyncChangeTasks();
    }

    /**
     * Check whether this is a valid data type for blackboards
     *
     * @param dt Data type to check
     */
    public static void checkType(DataType dt) {
        assert(dt.getRelatedType() != null && dt.getRelatedType().isMethodType()) : "Please register Blackboard types using Blackboard2Plugin class";
    }

    /**
     * Copy a blackboard buffer
     *
     * @param src Source Buffer
     * @param target Target Buffer
     */
    public void copyBlackboardBuffer(BlackboardBuffer src, BlackboardBuffer target) {
        target.resize(src.getBbCapacity(), src.getElements(), src.getElementSize(), false);
        target.getBuffer().put(0, src.getBuffer(), 0, src.getSize());
    }

    /**
     * @return Unused blackboard task for pending tasks
     */
    protected BlackboardTask getUnusedBlackboardTask() {
        BlackboardTask task = BlackboardPlugin.taskPool.getUnused();
        if (task == null) {
            task = new BlackboardTask();
            BlackboardPlugin.taskPool.attach(task, false);
        }
        return task;
    }

    ////Cpp finroc::util::Lock* curlock;

    /**
     * Wait to receive lock on blackboard for specified amount of time
     * (MUST be called in synchronized context)
     *
     * @param timeout Time to wait for lock
     * @return Do we have a lock now? (or did rather timeout expire?)
     */
    @PassLock("writePort")
    protected boolean waitForLock(/*@Const AbstractMethod method,*/ long timeout) {
        BlackboardTask task = getUnusedBlackboardTask();
        //task.method = method;
        task.threadUid = ThreadUtil.getCurrentThreadId();
        pendingMajorTasks.add(task);
        long startTime = Time.getCoarse();
        //long curTime = startTime;
        long waitFor = timeout;
        //System.out.println(createThreadString() + ": waiting " + timeout + " ms for lock");
        while (waitFor > 0) {
            try {
                //System.out.println(createThreadString() + ": entered wait");
                writePort.wait(waitFor);
            } catch (InterruptedException e) {
                //e.printStackTrace();
                System.out.println("Wait interrupted in AbstractBlackboardServer - shouldn't happen... usually");
            }
            waitFor = timeout - (Time.getCoarse() - startTime);
            //System.out.println(createThreadString() + ": left wait; waitFor = " + waitFor + "; wakeupThread = " + wakeupThread);
            if (wakeupThread == ThreadUtil.getCurrentThreadId()) {
                // ok, it's our turn now
                pendingMajorTasks.removeElem(task);
                wakeupThread = -1;
                task.recycle2();
                assert(!isLocked());
                return true;
            }
        }

        // ok, time seems to have run out - we have synchronized context though - so removing task is safe
        //System.out.println(createThreadString() + ": time has run out; isLocked() = " + isLocked());
        pendingMajorTasks.removeElem(task);
        task.recycle2();
        assert(isLocked()) : "Somebody forgot thread waiting on blackboard";
        return false;
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
    @PassLock("writePort")
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
        writePort.notifyAll();
        return true;
    }

    /**
     * (only call in synchronized context)
     * Clear any asynch change tasks from list
     */
    protected void clearAsyncChangeTasks() {
        for (@SizeT int i = 0; i < pendingAsynchChangeTasks.size(); i++) {
            pendingAsynchChangeTasks.get(i).recycle2();
        }
        pendingAsynchChangeTasks.clear();
    }

    /**
     * (only call in synchronized context)
     * Process pending asynch change commands (good idea when unlocking blackboard)
     */
    protected void processPendingAsynchChangeTasks() {
        for (@SizeT int i = 0; i < pendingAsynchChangeTasks.size(); i++) {
            BlackboardTask task = pendingAsynchChangeTasks.get(i);
            asynchChange(task.offset, task.buffer, false);
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
    protected void deferAsynchChangeCommand(int offset, @Const BlackboardBuffer buf) {
        BlackboardTask task = getUnusedBlackboardTask();
        task.offset = offset;
        task.buffer = buf;
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
     * @param offset Offset in blackboard
     * @param buf Buffer with contents to write there
     * @param checkLock Check whether buffer is currently locked, before performing asynch change (normal operation)
     */
    protected abstract void asynchChange(int i, @Const BlackboardBuffer buf, boolean checkLock);

    /**
     * Unlock blackboard (from write lock)
     *
     * @param buf Buffer containing changes (may be the same or another one - the latter is typically the case with remote clients)
     */
    protected abstract void writeUnlock(BlackboardBuffer buf);

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
    protected abstract void directCommit(BlackboardBuffer buf);

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
    protected abstract @Const BlackboardBuffer readLock(long timeout) throws MethodCallException;

    /**
     * Perform read lock (only do this on single-buffered blackboards)
     *
     * @param timeout Timeout in ms
     * @return Locked BlackboardBuffer (if lockId is < 0 this is a copy)
     */
    protected abstract BlackboardBuffer writeLock(long timeout);

    /**
     * Read part of blackboard
     *
     * @param offset Offset to start reading
     * @param length Length (in bytes) of area to read
     * @param timeout Timeout for this command
     * @return Memory buffer containing read area
     */
    protected abstract BlackboardBuffer readPart(int offset, int length, int timeout) throws MethodCallException ;

    // Call handling

    @Override
    @JavaOnly
    public void handleVoidCall(AbstractMethod method, Object p1) throws MethodCallException {
        if (method == KEEP_ALIVE) {
            keepAlive((Integer)p1);
        } else if (method == DIRECT_COMMIT) {
            directCommit((BlackboardBuffer)p1);
        } else if (method == READ_UNLOCK) {
            readUnlock((Integer)p1);
        } else if (method == UNLOCK) {
            writeUnlock((BlackboardBuffer)p1);
        } else {
            LOCK.cleanup(p1);
            throw new MethodCallException(MethodCallException.Type.UNKNOWN_METHOD);
        }
    }

    /*Cpp
    void handleVoidCall(core::AbstractMethod* method, BlackboardBuffer* p1) {
        if (method == &DIRECT_COMMIT) {
            directCommit(p1);
        } else if (method == &UNLOCK) {
            writeUnlock(p1);
        } else {
            LOCK.cleanup(p1);
            throw core::MethodCallException(core::MethodCallException::_eUNKNOWN_METHOD);
        }
    }

    void handleVoidCall(core::AbstractMethod* method, int p1) {
        if (method == &KEEP_ALIVE) {
            keepAlive(p1);
        } else if (method == &READ_UNLOCK) {
            readUnlock(p1);
        } else {
            LOCK.cleanup(p1);
            throw core::MethodCallException(core::MethodCallException::_eUNKNOWN_METHOD);
        }
    }
     */

    @Override
    public BlackboardBuffer handleCall(AbstractMethod method, Integer p1, Integer p2, Integer p3) throws MethodCallException {
        assert(method == READ_PART);
        return readPart(p1, p2, p3);
    }

    @Override
    public BlackboardBuffer handleCall(AbstractMethod method, Long p1) throws MethodCallException {
        assert(method == LOCK);
        return writeLock(p1);
    }

    @Override @Const
    public BlackboardBuffer handleCall(AbstractMethod method, Long p1, Integer dummy) throws MethodCallException {
        assert(method == READ_LOCK);
        return readLock(p1);
    }

    @Override
    public Byte handleCall(AbstractMethod method) throws MethodCallException {
        return isSingleBuffered() ? (byte)1 : (byte)0;
    }

    @Override
    public void handleVoidCall(AbstractMethod method, Integer p1, @Const BlackboardBuffer p2) throws MethodCallException {
        assert(method == ASYNCH_CHANGE);
        asynchChange(p1, p2, true);
    }
}