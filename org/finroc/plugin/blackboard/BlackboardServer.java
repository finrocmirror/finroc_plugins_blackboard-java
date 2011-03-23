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

import org.finroc.jc.AtomicInt;
import org.finroc.jc.Time;
import org.finroc.jc.annotation.CppDefault;
import org.finroc.jc.annotation.CppType;
import org.finroc.jc.annotation.InCpp;
import org.finroc.jc.annotation.Init;
import org.finroc.jc.annotation.JavaOnly;
import org.finroc.jc.annotation.PassLock;
import org.finroc.jc.annotation.Ptr;
import org.finroc.jc.annotation.RawTypeArgs;
import org.finroc.jc.annotation.Ref;
import org.finroc.jc.annotation.SharedPtr;
import org.finroc.jc.annotation.SkipArgs;
import org.finroc.log.LogLevel;
import org.finroc.serialization.DataTypeBase;
import org.finroc.serialization.PortDataList;
import org.finroc.core.CoreFlags;
import org.finroc.core.FrameworkElement;
import org.finroc.core.LockOrderLevels;
import org.finroc.core.port.Port;
import org.finroc.core.port.PortCreationInfo;
import org.finroc.core.port.PortFlags;
import org.finroc.core.port.rpc.InterfaceServerPort;
import org.finroc.core.port.rpc.MethodCallException;
import org.finroc.core.port.std.PortBase;
import org.finroc.core.port.std.PortDataManager;

/**
 * @author max
 *
 * This is the base class for a blackboard server
 */
@Ptr @RawTypeArgs
@SuppressWarnings( {"rawtypes", "unchecked"})
public class BlackboardServer<T> extends AbstractBlackboardServer<T> {

    /*Cpp
    typedef typename AbstractBlackboardServer<T>::BBVector BBVector;
    typedef typename AbstractBlackboardServer<T>::BBVectorVar BBVectorVar;
    typedef typename AbstractBlackboardServer<T>::ConstBBVectorVar ConstBBVectorVar;
    typedef typename AbstractBlackboardServer<T>::ChangeTransaction ChangeTransaction;
    typedef typename AbstractBlackboardServer<T>::ChangeTransactionVar ChangeTransactionVar;
    typedef typename AbstractBlackboardServer<T>::ConstChangeTransactionVar ConstChangeTransactionVar;

    using AbstractBlackboardServer<T>::logDomain;
    */

    /** Unlock timeout in ms - if no keep-alive signal occurs in this period of time */
    private final static long UNLOCK_TIMEOUT = 1000;

    /** Interface port for write access */
    private InterfaceServerPort write;

    /** Is blackboard currently locked? - in this case points to duplicated buffer */
    private @CppType("BBVectorVar") PortDataList locked;

    /** Time when last lock was performed */
    private volatile long lockTime;

    /** Last time a keep-alive-signal was received */
    private volatile long lastKeepAlive;

    /** ID of current lock - against outdated unlocks */
    private final AtomicInt lockIDGen = new AtomicInt(0);
    private int lockId = 0;

    /**
     * Currently published MemBuffer - not extra locked - attached to lock of read port
     * In single buffered mode - this is the one and only buffer
     */
    private @CppType("BBVector*") PortDataList published;

    /** read port */
    @SharedPtr @CppType("core::Port<BBVector>")
    public Port<PortDataList> readPort;

    /**
     * @param description Name/Uid of blackboard
     * @param type Data Type of blackboard content
     */
    @JavaOnly
    @SkipArgs("2")
    public BlackboardServer(String description, DataTypeBase type) {
        this(description, null, type);
    }

    /**
     * @param description Name/Uid of blackboard
     * @param parent of BlackboardServer
     * @param type Data Type of blackboard content
     */
    @JavaOnly
    @SkipArgs("3")
    public BlackboardServer(String description, @CppDefault("NULL") FrameworkElement parent, DataTypeBase type) {
        this(description, parent, true, type);
    }

    /**
     * @param description Name/Uid of blackboard
     * @param capacity Blackboard capacity (see BlackboardBuffer)
     * @param elements Number of element (see BlackboardBuffer)
     * @param elemSize Element size (see BlackboardBuffer)
     * @param parent parent of BlackboardServer
     * @param shared Share blackboard with other runtime environments?
     * @param type Data Type of blackboard content
     */
    @SkipArgs("7")
    public BlackboardServer(String description, int capacity, int elements, int elemSize, @CppDefault("NULL") FrameworkElement parent, @CppDefault("true") boolean shared, @CppDefault("rrlib::serialization::DataType<T>()") DataTypeBase type) {
        this(description, parent, shared, type);
        resize(published, capacity, elements/*, elemSize, false*/);
    }


    /**
     * @param description Name/Uid of blackboard
     * @param parent parent of BlackboardServer
     * @param shared Share blackboard with other runtime environments?
     * @param type Data Type of blackboard content
     */
    @SkipArgs("4")
    @Init( {"locked()", "published()"})
    public BlackboardServer(String description, @CppDefault("NULL") FrameworkElement parent, @CppDefault("true") boolean shared, @CppDefault("rrlib::serialization::DataType<T>()") DataTypeBase type) {
        super(description, shared ? BlackboardManager.SHARED : BlackboardManager.LOCAL, parent);
        PortCreationInfo readPci = new PortCreationInfo("read", this, type.getListType(), PortFlags.OUTPUT_PORT | (shared ? CoreFlags.SHARED : 0)).lockOrderDerive(LockOrderLevels.REMOTE_PORT + 1);

        //JavaOnlyBlock
        readPort = new Port<PortDataList>(readPci);

        //Cpp readPort._reset(new core::_T_Port<BBVector>(readPci));

        readPortRaw = (PortBase)readPort.getWrapped();
        AbstractBlackboardServerRaw.checkType(type);
        write = new InterfaceServerPort("write", this, type.getRelatedType(), this, shared ? CoreFlags.SHARED : 0, LockOrderLevels.REMOTE_PORT + 2);
        writePortRaw = write;
        locked = null;
        setPublished(readPort.getDefaultBuffer());
    }

    /**
     * @return Port data manager for buffer
     */
    @InCpp("return t.getManager();")
    private static @Ptr <Q> PortDataManager getManager(@Ref @CppType("core::PortDataPtr<Q>") Q t) {
        return PortDataManager.getManager(t);
    }

    /**
     * This method exists due to current imperfectness in java->c++ converter
     *
     * @param p
     */
    @InCpp("published = p;")
    private void setPublished(@Ptr @CppType("BBVector") PortDataList p) {
        published = p;
    }

    @Override
    protected void asynchChange(@CppType("ConstChangeTransactionVar") PortDataList buf, int index, int offset, boolean checkLock) {
        synchronized (bbLock) {
            if (checkLock && locked != null) {
                checkCurrentLock();
                if (locked != null) { // ok, we don't get lock now... defer command to next unlock
                    deferAsynchChangeCommand(buf, index, offset);
                    return;
                }
            }

            assert((!checkLock) || (!this.pendingTasks()));

            // duplicate current buffer
            assert(locked == null);
            duplicateAndLock();

            // apply asynch change
            //JavaOnlyBlock
            applyAsynchChange(locked, buf, index, offset);
            getManager(buf).releaseLock();
            //locked.getBuffer().put(offset, buf.getBuffer(), 0, buf.getSize());

            //Cpp this->applyAsynchChange(*locked, buf, index, offset);


            // commit changes
            commitLocked();
            assert(locked == null);
            //processPendingCommands();
        }
    }

    @Override
    protected void keepAlive(int lockId) {
        synchronized (bbLock) {
            if (locked != null && this.lockId == lockId) {
                lastKeepAlive = Time.getCoarse();
            }
        }
    }

    /**
     * Check if lock timed out (only call in synchronized/exclusive access context)
     */
    @PassLock("bbLock")
    private void checkCurrentLock() {
        if (locked != null && Time.getCoarse() > lastKeepAlive + UNLOCK_TIMEOUT) {
            log(LogLevel.LL_DEBUG, logDomain, "Blackboard server: Lock timed out... unlocking");

            //JavaOnlyBlock
            getManager(locked).releaseLock();

            lockId = lockIDGen.incrementAndGet();

            //JavaOnlyBlock
            locked = null;

            //Cpp locked._reset();
            log(LogLevel.LL_DEBUG, logDomain, "Thread " + Thread.currentThread().toString() + ": lock = null");
            boolean p = this.processPendingCommands();
            if ((!p) && (!isLocked())) {
                this.processPendingAsynchChangeTasks();
            }
        }
    }

    /** Release lock and commit changed buffer (needs to be called in synchronized context) */
    private void commitLocked() {
        assert(locked != null && getManager(locked).isLocked());

        // process any waiting asynch change commands
        this.processPendingAsynchChangeTasks();

        // publish new buffer
        PortDataManager mgr = getManager(locked);
        mgr.lockID = -1;

        //JavaOnlyBlock
        readPort.publish(locked);
        published = locked;
        mgr.releaseLock();
        locked = null;

        //Cpp readPort->publish(locked);
        //Cpp published = locked._get();
        //Cpp locked._reset();

        lockId = lockIDGen.incrementAndGet();
        //System.out.println("Thread " + Thread.currentThread().toString() + ": lock = null");
    }

    /** Duplicate and lock current buffer (needs to be called in synchronized context) */
    private void duplicateAndLock() {
        assert(locked == null);
        lockId = lockIDGen.incrementAndGet();
        lockTime = Time.getCoarse();
        lastKeepAlive = lockTime;

        //JavaOnlyBlock
        locked = write.getBufferForReturn(readPort.getDataType());

        //Cpp locked = write->getBufferForReturn<BBVector>();

        //System.out.println("Thread " + Thread.currentThread().toString() + ": lock = " + locked.toString());

        //JavaOnlyBlock
        this.copyBlackboardBuffer(published, locked);

        //Cpp this->copyBlackboardBuffer(*published, *locked);

        getManager(locked).lockID = lockId;
    }

    @Override
    protected boolean isLocked() {
        return locked != null;
    }

    @Override
    public void lockCheck() {
        long curTime = Time.getCoarse();
        if (lastKeepAlive + UNLOCK_TIMEOUT > curTime) {
            return;
        }

        synchronized (bbLock) {
            checkCurrentLock();
        }
    }

    @Override
    protected void directCommit(@CppType("BBVectorVar") PortDataList newBuffer) {
        if (newBuffer == null) {
            return;
        }

        synchronized (bbLock) {
            if (locked != null) { // note: current lock is obsolete, since we have a completely new buffer
                //lockID = lockIDGen.incrementAndGet(); // make sure, next unlock won't do anything => done in commitLocked()

                // JavaOnlyBlock
                getManager(locked).releaseLock(); // discard current lock
                locked = null;

                //Cpp locked._reset();
            }

            // Clear any asynch change commands from queue, since they were for old buffer
            this.clearAsyncChangeTasks();

            // commit new buffer

            locked = newBuffer;
            commitLocked();

            assert(locked == null);

            // any threads that want to lock this?
            this.processPendingCommands();
        }
    }

    @Override
    protected boolean isSingleBuffered() {
        return false;
    }

    @Override
    protected @CppType("AbstractBlackboardServer<T>::ConstBBVectorVar") PortDataList readLock(long timeout) throws MethodCallException {
        log(LogLevel.LL_WARNING, logDomain, "warning: Client must not attempt read lock on multi-buffered blackboard - Call failed");
        throw new MethodCallException(MethodCallException.Type.INVALID_PARAM);
    }

//    @Override
//    protected BlackboardBuffer readPart(int offset, int length, int timeout) {
//        // current buffer (note: we get it from readPort, since -this way- call does not need to be executed in synchronized context)
//        @Const BlackboardBuffer buffer = (BlackboardBuffer)readPort.getLockedUnsafeRaw();
//        assert(buffer.getManager().isLocked());
//
//        // prepare and set return value
//        BlackboardBuffer send = (BlackboardBuffer)write.getUnusedBuffer(buffer.getType());
//        send.resize(1, 1, length, false); // ensure minimal size
//        send.getBuffer().put(0, buffer.getBuffer(), offset, length);
//        send.bbCapacity = buffer.bbCapacity;
//        send.elements = buffer.elements;
//        send.elementSize = buffer.elementSize;
//
//        // release old lock
//        buffer.getManager().getCurrentRefCounter().releaseLock();
//
//        // return buffer with one read lock
//        send.getManager().getCurrentRefCounter().setLocks((byte)1);
//        return send;
//    }

    @Override
    protected @CppType("AbstractBlackboardServer<T>::BBVectorVar")PortDataList writeLock(long timeout) {
        synchronized (bbLock) {
            if (locked != null || this.pendingTasks()) { // make sure lock command doesn't "overtake" others
                checkCurrentLock();
                if (locked != null || this.pendingTasks()) {
                    if (timeout <= 0) {

                        //JavaOnlyBlock
                        return null; // we do not need to enqueue lock commands with zero timeout

                        //Cpp return BBVectorVar(); // we do not need to enqueue lock commands with zero timeout
                    } else {
                        // wait for lock
                        boolean haveLock = this.waitForLock(timeout);
                        if (!haveLock) {

                            //JavaOnlyBlock
                            return null; // we didn't get lock :-/

                            //Cpp return BBVectorVar(); // we didn't get lock :-/
                        }
                    }
                }

                // ok... we have lock here
                assert(locked == null);
            }

            //System.out.println("Thread " + Thread.currentThread().toString() + ": handleLock");

            duplicateAndLock();

            //JavaOnlyBlock
            PortDataManager mgr = getManager(locked);
            assert(locked != null && mgr.isLocked());
            mgr.addLock();

            return locked; // return buffer with one read lock
            //mc.setReturn(locked, false);
        }
    }

    @Override
    protected void readUnlock(int lockId) throws MethodCallException {
        log(LogLevel.LL_WARNING, logDomain, "warning: Client must not attempt read unlock on multi-buffered blackboard - Call failed");
        throw new MethodCallException(MethodCallException.Type.INVALID_PARAM);
    }

    @Override
    protected void writeUnlock(@CppType("BBVectorVar")PortDataList buf) {
        if (buf == null) {
            log(LogLevel.LL_WARNING, logDomain, "blackboard write unlock without providing buffer - strange indeed - ignoring");
            return;
        }

        synchronized (bbLock) {
            PortDataManager bufmgr = getManager(buf);
            if (this.lockId != bufmgr.lockID) {
                log(LogLevel.LL_DEBUG, logDomain, "Skipping outdated unlock");

                //JavaOnlyBlock
                bufmgr.releaseLock();

                return;
            }

            assert(locked != null);
            assert(bufmgr.isLocked());

            if (buf == locked) {
                // we got the same buffer back - we only need to release one lock from method call

                //JavaOnlyBlock
                bufmgr.releaseLock();
                assert(bufmgr.getCurReference().isLocked());

            } else {

                locked = buf;
                //System.out.println("Thread " + Thread.currentThread().toString() + ": lock = " + locked.toString());

                //JavaOnlyBlock
                bufmgr.releaseLock();
                assert(bufmgr.getCurReference().isLocked());

            }

            commitLocked();
            this.processPendingCommands();
        }
    }
}
