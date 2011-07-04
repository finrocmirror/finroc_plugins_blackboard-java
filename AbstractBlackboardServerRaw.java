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
package org.finroc.plugins.blackboard;

import org.rrlib.finroc_core_utils.jc.MutexLockOrderWithMonitor;
import org.rrlib.finroc_core_utils.jc.Time;
import org.rrlib.finroc_core_utils.jc.annotation.AtFront;
import org.rrlib.finroc_core_utils.jc.annotation.Const;
import org.rrlib.finroc_core_utils.jc.annotation.CppDefault;
import org.rrlib.finroc_core_utils.jc.annotation.CppUnused;
import org.rrlib.finroc_core_utils.jc.annotation.InCpp;
import org.rrlib.finroc_core_utils.jc.annotation.IncludeClass;
import org.rrlib.finroc_core_utils.jc.annotation.JavaOnly;
import org.rrlib.finroc_core_utils.jc.annotation.PassByValue;
import org.rrlib.finroc_core_utils.jc.annotation.PassLock;
import org.rrlib.finroc_core_utils.jc.annotation.Ptr;
import org.rrlib.finroc_core_utils.jc.annotation.Ref;
import org.rrlib.finroc_core_utils.jc.annotation.Struct;
import org.rrlib.finroc_core_utils.jc.annotation.Superclass;
import org.rrlib.finroc_core_utils.jc.annotation.VoidPtr;
import org.rrlib.finroc_core_utils.jc.container.SimpleList;
import org.rrlib.finroc_core_utils.jc.log.LogDefinitions;
import org.rrlib.finroc_core_utils.jc.thread.ThreadUtil;
import org.rrlib.finroc_core_utils.log.LogDomain;
import org.rrlib.finroc_core_utils.log.LogLevel;
import org.rrlib.finroc_core_utils.serialization.DataTypeBase;
import org.finroc.core.FrameworkElement;
import org.finroc.core.LockOrderLevels;
import org.finroc.core.port.rpc.InterfacePort;
import org.finroc.core.port.rpc.MethodCallException;
import org.finroc.core.port.rpc.method.AbstractMethod;
import org.finroc.core.port.rpc.method.AbstractMethodCallHandler;
import org.finroc.core.port.rpc.method.Method0Handler;
import org.finroc.core.port.rpc.method.Void1Handler;
import org.finroc.core.port.std.PortBase;
import org.finroc.core.portdatabase.FinrocTypeInfo;

/**
 * Abstract base class of all blackboard servers
 */
@SuppressWarnings("rawtypes") @IncludeClass( {InterfacePort.class, BlackboardTypeInfo.class})
@Struct @AtFront @Ptr @Superclass( {FrameworkElement.class, AbstractMethodCallHandler.class})
abstract class AbstractBlackboardServerRaw extends FrameworkElement implements Void1Handler, Method0Handler<Byte> {

    /** Lock for blackboard operation (needs to be deeper than runtime - (for initial pushes etc.)) */
    public MutexLockOrderWithMonitor bbLock = new MutexLockOrderWithMonitor(LockOrderLevels.INNER_MOST - 1000);

    /** read port */
    public PortBase readPortRaw;

    /** write port */
    public InterfacePort writePortRaw;

    /** Category index of Blackboard category that this server belongs to (see constants in BlackboardManager) */
    public final int categoryIndex;

    /** Blackboard category that this server belongs to */
    public final @Ptr BlackboardManager.BlackboardCategory myCategory;

    /**
     * Queue with pending major commands (e.g. LOCK, READ_PART in SingleBufferedBlackboard)
     * They are executed in another thread
     * may only be accessed in synchronized context */
    //@CppType("std::vector<BlackboardTask>")
    protected final @PassByValue SimpleList<BlackboardTask> pendingMajorTasks = new SimpleList<BlackboardTask>();

    /** Uid of thread that is allowed to wake up now - after notifyAll() - thread should reset this to -1 as soon as possible */
    protected long wakeupThread = -1;

    /** Log domain for this class */
    @InCpp("_RRLIB_LOG_CREATE_NAMED_DOMAIN(logDomain, \"blackboard\");")
    public static final LogDomain logDomain = LogDefinitions.finroc.getSubDomain("blackboard");

    /**
     * @param bbName Blackboard name
     * @param category Blackboard category (see constants in BlackboardManager)
     */
    public AbstractBlackboardServerRaw(String bbName, int category, @CppDefault("NULL") FrameworkElement parent) {
        this(bbName, category, BlackboardManager.getInstance().getCategory(category).defaultFlags, parent);
    }

    /**
     * @param bbName Blackboard name
     * @param category Blackboard category (see constants in BlackboardManager)
     * @param flags Flags for blackboard
     */
    @JavaOnly
    private AbstractBlackboardServerRaw(@Const @Ref String bbName, int category, int flags, @CppDefault("NULL") FrameworkElement parent) {
        super(parent == null ? BlackboardManager.getInstance().getCategory(category) : parent, bbName, flags, -1);
        myCategory = BlackboardManager.getInstance().getCategory(category);
        categoryIndex = category;
    }

    protected void postChildInit() {
        myCategory.add(this);
    }

    protected synchronized void prepareDelete() {
        synchronized (bbLock) {
            if (BlackboardManager.getInstance() != null) { // we don't need to remove it, if blackboard manager has already been deleted
                myCategory.remove(this);
            }

            clearAsyncChangeTasks();
        }
    }

    /**
     * @param dt Data type
     * @return Blackboard type info for data type
     */
    @InCpp("return dt.getAnnotation<BlackboardTypeInfo>();")
    public static BlackboardTypeInfo getBlackboardTypeInfo(DataTypeBase dt) {
        return dt.getAnnotation(BlackboardTypeInfo.class);
    }

    /**
     * Check whether this is a valid data type for blackboards
     *
     * @param dt Data type to check
     */
    public static void checkType(DataTypeBase dt) {
        @CppUnused
        BlackboardTypeInfo ti = getBlackboardTypeInfo(dt);
        assert(ti != null && ti.blackboardType != null && FinrocTypeInfo.isMethodType(ti.blackboardType)) : "Please register Blackboard types using BlackboardPlugin class";
    }

//    /**
//     * Copy a blackboard buffer
//     *
//     * @param src Source Buffer
//     * @param target Target Buffer
//     */
//    public void copyBlackboardBuffer(BlackboardBuffer src, BlackboardBuffer target) {
//        target.resize(src.getBbCapacity(), src.getElements(), src.getElementSize(), false);
//        target.getBuffer().put(0, src.getBuffer(), 0, src.getSize());
//    }
//
    /**
     * @return Unused blackboard task for pending tasks
     */
    @JavaOnly
    protected BlackboardTask getUnusedBlackboardTask() {
        BlackboardTask task = BlackboardPlugin.taskPool.getUnused();
        if (task == null) {
            task = new BlackboardTask();
            BlackboardPlugin.taskPool.attach(task, false);
        }
        return task;
    }
//
//    ////Cpp finroc::util::Lock* curlock;

    /**
     * Wait to receive lock on blackboard for specified amount of time
     * (MUST be called in synchronized context)
     *
     * @param timeout Time to wait for lock
     * @return Do we have a lock now? (or did rather timeout expire?)
     */
    @PassLock("bbLock")
    protected boolean waitForLock(/*@Const AbstractMethod method,*/ long timeout) {
        @InCpp("BlackboardTask task;")
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
                bbLock.wait(waitFor);
            } catch (InterruptedException e) {
                //e.printStackTrace();
                log(LogLevel.LL_WARNING, logDomain, "Wait interrupted in AbstractBlackboardServer - shouldn't happen... usually");
            }
            waitFor = timeout - (Time.getCoarse() - startTime);
            //System.out.println(createThreadString() + ": left wait; waitFor = " + waitFor + "; wakeupThread = " + wakeupThread);
            if (wakeupThread == ThreadUtil.getCurrentThreadId()) {
                // ok, it's our turn now
                pendingMajorTasks.removeElem(task);
                wakeupThread = -1;

                //JavaOnlyBlock
                task.recycle2();

                assert(!isLocked());
                return true;
            }
        }

        // ok, time seems to have run out - we have synchronized context though - so removing task is safe
        //System.out.println(createThreadString() + ": time has run out; isLocked() = " + isLocked());
        pendingMajorTasks.removeElem(task);

        //JavaOnlyBlock
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
    @PassLock("bbLock")
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
    protected abstract void clearAsyncChangeTasks();

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
     * @return Is this a single buffered blackboard server?
     */
    protected abstract boolean isSingleBuffered();

    // Call handling

    @Override
    public Byte handleCall(AbstractMethod method) throws MethodCallException {
        return isSingleBuffered() ? (byte)1 : (byte)0;
    }

    /**
     * Resize for Blackboards based on BlackboardBuffer (such as class MCA-style ones)
     *
     * @param buffer Blackboard buffer to resize
     * @param capacity Blackboard capacity (see BlackboardBuffer)
     * @param elements Number of element (see BlackboardBuffer)
     * @param elemSize Element size (see BlackboardBuffer)
     */
    protected void classicBlackboardResize(BlackboardBuffer buffer, int capacity, int elements, int elemSize) {
        buffer.resize(capacity, elements, elemSize, true);
    }

    /**
     * Overload for non-blackboard-types
     */
    protected void classicBlackboardResize(@VoidPtr Object o, int capacity, int elements, int elemSize) {
    }

}