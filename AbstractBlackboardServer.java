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

import java.util.ArrayList;

import org.rrlib.finroc_core_utils.jc.annotation.AtFront;
import org.rrlib.finroc_core_utils.jc.annotation.Const;
import org.rrlib.finroc_core_utils.jc.annotation.CppDefault;
import org.rrlib.finroc_core_utils.jc.annotation.CppType;
import org.rrlib.finroc_core_utils.jc.annotation.InCpp;
import org.rrlib.finroc_core_utils.jc.annotation.Include;
import org.rrlib.finroc_core_utils.jc.annotation.IncludeClass;
import org.rrlib.finroc_core_utils.jc.annotation.JavaOnly;
import org.rrlib.finroc_core_utils.jc.annotation.PassByValue;
import org.rrlib.finroc_core_utils.jc.annotation.PassLock;
import org.rrlib.finroc_core_utils.jc.annotation.Ptr;
import org.rrlib.finroc_core_utils.jc.annotation.RawTypeArgs;
import org.rrlib.finroc_core_utils.jc.annotation.Ref;
import org.rrlib.finroc_core_utils.jc.annotation.SizeT;
import org.rrlib.finroc_core_utils.jc.annotation.Struct;
import org.rrlib.finroc_core_utils.jc.annotation.Superclass;
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
@Struct @AtFront @Ptr @Superclass( {AbstractBlackboardServerRaw.class})
@Include("core/port/tPortTypeMap.h") @RawTypeArgs
@IncludeClass(PortInterface.class)
abstract class AbstractBlackboardServer<T> extends AbstractBlackboardServerRaw implements
    Method1Handler<PortDataList, Integer>,
    Method2Handler<PortDataList, Integer, Integer>,
    Void3Handler<PortDataList, Integer, Integer> {

    /*Cpp

    typedef typename core::PortTypeMap<T>::ListType BBVector;
    typedef typename core::PortDataPtr<BBVector> BBVectorVar;
    typedef typename core::PortDataPtr<const BBVector> ConstBBVectorVar;
    typedef typename core::PortTypeMap<BBVector>::GenericChange ChangeTransaction;
    typedef typename core::PortDataPtr<ChangeTransaction> ChangeTransactionVar;
    typedef typename core::PortDataPtr<const ChangeTransaction> ConstChangeTransactionVar;

    using AbstractBlackboardServerRaw::_M_handleCall;

    class AsynchChangeTask : public BlackboardTask {

    public:

        // BlackboardBuffer to use for task - if this is set, it will be unlocked with recycle
        ConstChangeTransactionVar buffer;

        // Offset for asynch change command
        int64_t offset;

        // index for asynch change command
        int64_t index;

        AsynchChangeTask(ConstChangeTransactionVar&& buffer_, int64_t offset_, int64_t index_) :
            buffer(),
            offset(offset_),
            index(index_)
        {
            buffer = std::_move(buffer);
        }

        AsynchChangeTask(AsynchChangeTask && o) :
            buffer(),
            offset(0),
            index(0)
        {
            std::_swap(buffer, o.buffer);
            std::_swap(offset, o.offset);
            std::_swap(index, o.index);
        }

        AsynchChangeTask& operator=(AsynchChangeTask && o)
        {
            std::_swap(buffer, o.buffer);
            std::_swap(offset, o.offset);
            std::_swap(index, o.index);
            return *this;
        }
    };
     */

    /**
     * Queue with pending asynch change commands
     * they don't lock and don't execute in an extra thread
     * may only be accessed in synchronized context
     */
    @CppType("std::vector<AsynchChangeTask>")
    public final @PassByValue ArrayList<BlackboardTask> pendingAsynchChangeTasks = new ArrayList<BlackboardTask>();

    // Methods...

    /** Blackboard interface */
    @JavaOnly
    @PassByValue private static PortInterface METHODS = new PortInterface("Blackboard Interface", true);

    /** Write Lock */
    @CppType("core::Port1Method<AbstractBlackboardServer<T>*, typename AbstractBlackboardServer<T>::BBVectorVar, int>")
    @PassByValue public static Port1Method < AbstractBlackboardServer<?>, PortDataList, Integer > LOCK =
        new Port1Method < AbstractBlackboardServer<?>, PortDataList, Integer > (getBlackboardInterface(), "Write Lock", "timeout", true);

    /** Read Lock (only useful for SingleBufferedBlackboardBuffers) */
    @CppType("core::Port2Method<AbstractBlackboardServer<T>*, typename AbstractBlackboardServer<T>::ConstBBVectorVar, int, int>")
    @PassByValue public static Port2Method < AbstractBlackboardServer<?>, PortDataList, Integer, Integer > READ_LOCK =
        new Port2Method < AbstractBlackboardServer<?>, PortDataList, Integer, Integer > (getBlackboardInterface(), "Read Lock", "timeout", "dummy", true);

    /** Write Unlock */
    @CppType("core::Void1Method<AbstractBlackboardServer<T>*, typename AbstractBlackboardServer<T>::BBVectorVar>")
    @PassByValue public static Void1Method UNLOCK =
        new Void1Method(getBlackboardInterface(), "Write Unlock", "Blackboard Buffer", false);

    /** Read Unlock */
    @CppType("core::Void1Method<AbstractBlackboardServer<T>*, int>")
    @PassByValue public static Void1Method READ_UNLOCK =
        new Void1Method(getBlackboardInterface(), "Read Unlock", "Lock ID", false);

    /** Asynch Change */
    @CppType("core::Void3Method<AbstractBlackboardServer<T>*, typename AbstractBlackboardServer<T>::ConstChangeTransactionVar, int, int>")
    @PassByValue public static Void3Method < AbstractBlackboardServer<?>, PortDataList, Integer, Integer > ASYNCH_CHANGE =
        new Void3Method < AbstractBlackboardServer<?>, PortDataList, Integer, Integer > (getBlackboardInterface(), "Asynchronous Change", "Blackboard Buffer", "Start Index", "Custom Offset", false);

//    /** Read part of blackboard (no extra thread with multi-buffered blackboards) */
//    @PassByValue public static Port3Method<AbstractBlackboardServer, BlackboardBuffer, Integer, Integer, Integer> READ_PART =
//        new Port3Method<AbstractBlackboardServer, BlackboardBuffer, Integer, Integer, Integer>(METHODS, "Read Part", "Offset", "Length", "Timeout", true);

    /** Directly commit buffer */
    @CppType("core::Void1Method<AbstractBlackboardServer<T>*, typename AbstractBlackboardServer<T>::BBVectorVar>")
    @PassByValue public static Void1Method DIRECT_COMMIT =
        new Void1Method(getBlackboardInterface(), "Direct Commit", "Buffer", false);

    /** Is server a single-buffered blackboard server? */
    @CppType("core::Port0Method<AbstractBlackboardServer<T>*, int8>")
    @PassByValue public static Port0Method<AbstractBlackboardServer<Byte>, Byte> IS_SINGLE_BUFFERED =
        new Port0Method<AbstractBlackboardServer<Byte>, Byte>(getBlackboardInterface(), "Is Single Buffered?", false);

    /** Send keep-alive signal for lock */
    @CppType("core::Void1Method<AbstractBlackboardServer<T>*, int>")
    @PassByValue public static Void1Method KEEP_ALIVE =
        new Void1Method(getBlackboardInterface(), "KeepAliveSignal", "Lock ID", false);

    /**
     * @param bbName Blackboard name
     * @param category Blackboard category (see constants in BlackboardManager)
     */
    public AbstractBlackboardServer(String bbName, int category, @CppDefault("NULL") FrameworkElement parent) {
        super(bbName, category, parent);
    }

    @InCpp("static core::PortInterface pi(\"Blackboard Interface\", true); return pi;")
    public static @Ref PortInterface getBlackboardInterface() {
        return METHODS;
    }

    /**
     * Copy a blackboard buffer
     * TODO: provide factory for buffer reuse
     *
     * @param src Source Buffer
     * @param target Target Buffer
     */
    @InCpp("rrlib::serialization::_sSerialization::deepCopy(src, target, NULL);")
    public void copyBlackboardBuffer(@Const @CppType("BBVector") @Ref PortDataList<T> src, @Ref @CppType("BBVector") PortDataList<T> target) {
        target.copyFrom(src);
        //target.resize(src.getBbCapacity(), src.getElements(), src.getElementSize(), false);
        //target.getBuffer().put(0, src.getBuffer(), 0, src.getSize());
    }

    ////Cpp finroc::util::Lock* curlock;

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
    protected void clearAsyncChangeTasks() {

        //JavaOnlyBlock
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
            @CppType("AsynchChangeTask")
            @Ref BlackboardTask task = pendingAsynchChangeTasks.get(i);
            asynchChange(task.buffer, (int)task.index, (int)task.offset, false);

            //JavaOnlyBlock
            task.buffer = null; // already unlocked by method
            task.recycle2();

            //Cpp task.buffer._reset();
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
    @InCpp("pendingAsynchChangeTasks.push_back(AsynchChangeTask(std::_move(buf), offset, index));")
    protected void deferAsynchChangeCommand(@CppType("ConstChangeTransactionVar") PortDataList buf, int index, int offset) {
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
    protected abstract void asynchChange(@CppType("ConstChangeTransactionVar") PortDataList buf, int startIdx, int offset, boolean checkLock);

    /**
     * Unlock blackboard (from write lock)
     *
     * @param buf Buffer containing changes (may be the same or another one - the latter is typically the case with remote clients)
     */
    protected abstract void writeUnlock(@CppType("BBVectorVar") PortDataList buf);

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
    protected abstract void directCommit(@CppType("BBVectorVar") PortDataList buf);

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
    protected abstract @CppType("AbstractBlackboardServer<T>::ConstBBVectorVar") PortDataList readLock(long timeout) throws MethodCallException;

    /**
     * Perform read lock (only do this on single-buffered blackboards)
     *
     * @param timeout Timeout in ms
     * @return Locked BlackboardBuffer (if lockId is < 0 this is a copy)
     */
    protected abstract @CppType("AbstractBlackboardServer<T>::BBVectorVar") PortDataList writeLock(long timeout);

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
    @JavaOnly
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

    /*Cpp
    void handleVoidCall(core::AbstractMethod* method, BBVectorVar& p1) {
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

//    @Override
//    public BlackboardBuffer handleCall(AbstractMethod method, Integer p1, Integer p2, Integer p3) throws MethodCallException {
//        assert(method == READ_PART);
//        return readPart(p1, p2, p3);
//    }

    @Override
    public @CppType("BBVectorVar") PortDataList handleCall(AbstractMethod method, Integer p1) throws MethodCallException {
        assert(method == LOCK);
        return writeLock(p1);
    }

    @Override
    public @CppType("ConstBBVectorVar") PortDataList handleCall(AbstractMethod method, Integer p1, Integer dummy) throws MethodCallException {
        assert(method == READ_LOCK);
        return readLock(p1);
    }

    @Override
    public void handleVoidCall(AbstractMethod method, @Ref @CppType("ConstChangeTransactionVar") PortDataList p2, Integer index, Integer offset) throws MethodCallException {
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
    @InCpp("rrlib::serialization::_sSerialization::resizeVector(buf, newElements);")
    protected void resize(@Ref @CppType("BBVector") PortDataList<T> buf, int newCapacity, int newElements/*, int newElementSize, boolean keepContents*/) {
        buf.resize(newElements);
    }

    /**
     * Apply asynch change to blackboard
     *
     * @param bb blackboard buffer
     * @param changes Changes to apply
     */
    @SuppressWarnings("unchecked")
    @InCpp("core::typeutil::applyChange(bb, *changes, index, offset);")
    protected void applyAsynchChange(@Ref @CppType("BBVector") PortDataList bb, @CppType("ConstChangeTransactionVar") PortDataList changes, int index, int offset) {
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