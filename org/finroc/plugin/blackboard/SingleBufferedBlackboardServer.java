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
import org.finroc.jc.annotation.SkipArgs;
import org.finroc.log.LogLevel;
import org.finroc.serialization.DataTypeBase;
import org.finroc.serialization.PortDataList;
import org.finroc.core.CoreFlags;
import org.finroc.core.FrameworkElement;
import org.finroc.core.LockOrderLevels;
import org.finroc.core.port.AbstractPort;
import org.finroc.core.port.PortCreationInfo;
import org.finroc.core.port.PortFlags;
import org.finroc.core.port.rpc.InterfaceServerPort;
import org.finroc.core.port.rpc.MethodCallException;
import org.finroc.core.port.std.PortBase;
import org.finroc.core.port.std.PortDataManager;
import org.finroc.core.port.std.PullRequestHandler;
import org.finroc.core.portdatabase.FinrocTypeInfo;

/**
 * @author max
 *
 * This is the base class for a blackboard server
 */
@Ptr
@SuppressWarnings( {"rawtypes", "unchecked"}) @RawTypeArgs
public class SingleBufferedBlackboardServer<T> extends AbstractBlackboardServer<T> implements PullRequestHandler {

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
    @Ptr private InterfaceServerPort write;

    /**
     * this is the one and only blackboard buffer
     * (can be replaced, when new buffers arrive from network => don't store pointers to it, when not locked)
     * (has exactly one lock)
     */
    private @CppType("BBVectorVar") PortDataList buffer;

    /**
     * Is blackboard currently locked?
     * (write locks are stored in this boolean, read locks in reference counter - this class holds one reference counter)
     *
     * positive numbers: # read locks
     * 0: no locks
     * -1: write lock
     */
    private int locks = 0;

    /** Time when last lock was performed */
    private volatile long lockTime;

    /** Last time a keep-alive-signal was received */
    private volatile long lastKeepAlive;

    /** ID of current lock - against outdated unlocks */
    private final AtomicInt lockIDGen = new AtomicInt(0);
    private int lockId = 0;

    /** revision of blackboard (incremented after each unlock) */
    private long revision;

    /** Current read copy of blackboard */
    private @CppType("BBVectorVar") PortDataList readCopy;

    /** revision of read copy */
    private long readCopyRevision = -1;

    /** Is a thread waiting for a blackboard copy? */
    private volatile boolean threadWaitingForCopy;

    ///** Writer for blackboard buffers in READ_PART (only use in synchronized context!) */
    //private OutputStreamBuffer bufWriter = new OutputStreamBuffer();

    /**
     * @param description Name/Uid of blackboard
     * @param type Data Type of blackboard content
     */
    @JavaOnly
    @SkipArgs("2")
    public SingleBufferedBlackboardServer(String description, DataTypeBase type) {
        this(description, 0, null, type);
    }

    /**
     * @param description Name/Uid of blackboard
     * @param elements Initial number of elements
     * @param parent of BlackboardServer
     * @param type Data Type of blackboard content
     */
    @JavaOnly
    @SkipArgs("4")
    public SingleBufferedBlackboardServer(String description, int elements, @CppDefault("NULL") FrameworkElement parent, DataTypeBase type) {
        this(description, elements, parent, true, type);
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
    @Init("buffer(write->getBufferForReturn<BBVector>())")
    public SingleBufferedBlackboardServer(String description, int capacity, int elements, int elemSize, @CppDefault("NULL") FrameworkElement parent, @CppDefault("true") boolean shared, @CppDefault("rrlib::serialization::DataType<T>()") DataTypeBase type) {
        super(description, shared ? BlackboardManager.SHARED : BlackboardManager.LOCAL, parent);
        assert(!FinrocTypeInfo.isMethodType(type)) : "Please provide data type of content here";
        readPortRaw = new BBReadPort(new PortCreationInfo("read", this, type.getListType(), PortFlags.OUTPUT_PORT | (shared ? CoreFlags.SHARED : 0)).lockOrderDerive(LockOrderLevels.REMOTE_PORT + 1));
        readPortRaw.setPullRequestHandler(this);
        AbstractBlackboardServerRaw.checkType(type);
        write = new InterfaceServerPort("write", this, this.getBlackboardMethodType(type), this, shared ? CoreFlags.SHARED : 0, LockOrderLevels.REMOTE_PORT + 2);
        writePortRaw = write;
        buffer = write.getBufferForReturn(readPortRaw.getDataType());

        //JavaOnlyBlock
        resize(buffer, elements, elements);

        //Cpp resize(*buffer, 1, 1);
        BlackboardManager.getInstance().init();
        //Cpp classicBlackboardResize(&((*buffer)[0]), capacity, elements, elemSize);
    }

    /**
     * @param description Name/Uid of blackboard
     * @param elements Initial number of elements
     * @param parent parent of BlackboardServer
     * @param shared Share blackboard with other runtime environments?
     * @param type Data Type of blackboard content
     */
    @SkipArgs("5")
    @Init("buffer(write->getBufferForReturn<BBVector>())")
    public SingleBufferedBlackboardServer(String description, @CppDefault("0") int elements, @CppDefault("NULL") FrameworkElement parent, @CppDefault("true") boolean shared, @CppDefault("rrlib::serialization::DataType<T>()") DataTypeBase type) {
        super(description, shared ? BlackboardManager.SHARED : BlackboardManager.LOCAL, parent);
        assert(!FinrocTypeInfo.isMethodType(type)) : "Please provide data type of content here";
        readPortRaw = new BBReadPort(new PortCreationInfo("read", this, type.getListType(), PortFlags.OUTPUT_PORT | (shared ? CoreFlags.SHARED : 0)).lockOrderDerive(LockOrderLevels.REMOTE_PORT + 1));
        readPortRaw.setPullRequestHandler(this);
        AbstractBlackboardServerRaw.checkType(type);
        write = new InterfaceServerPort("write", this, this.getBlackboardMethodType(type), this, shared ? CoreFlags.SHARED : 0, LockOrderLevels.REMOTE_PORT + 2);
        writePortRaw = write;
        buffer = write.getBufferForReturn(readPortRaw.getDataType());

        //JavaOnlyBlock
        resize(buffer, elements, elements);

        //Cpp resize(*buffer, elements, elements);

        BlackboardManager.getInstance().init();
    }

    /**
     * @return Port data manager for buffer
     */
    @InCpp("return t.getManager();")
    private static @Ptr <Q> PortDataManager getManager(@Ref @CppType("core::PortDataPtr<Q>") Q t) {
        return PortDataManager.getManager(t);
    }

    public void delete() {

        //JavaOnlyBlock
        if (readCopy != null) {
            getManager(readCopy).releaseLock();
            readCopy = null;
        }

        assert(buffer != null);

        //JavaOnlyBlock
        getManager(buffer).releaseLock();
        buffer = null;

    }

    @Override
    protected void asynchChange(@CppType("ConstChangeTransactionVar") PortDataList buf, int index, int offset, boolean checkLock) {
        synchronized (bbLock) {
            if (checkLock && isLocked()) {
                checkCurrentLock();
                if (isLocked()) {
                    deferAsynchChangeCommand(buf, index, offset);
                    return;
                }
            }

            assert((!checkLock) || (!isLocked()));

            //JavaOnlyBlock
            applyAsynchChange(buffer, buf, index, offset);

            //Cpp this->applyAsynchChange(*buffer, buf, index, offset);

            //buffer.getBuffer().put(offset, buf.getBuffer(), 0, buf.getSize());

            //JavaOnlyBlock
            getManager(buf).releaseLock();

            // commit changes
            newBufferRevision(true);

            assert((!checkLock) || (!isLocked()));
        }
    }

    @Override
    protected void keepAlive(int lockId) {
        synchronized (bbLock) {
            if (locks != 0 && this.lockId == lockId) {
                lastKeepAlive = Time.getCoarse();
            }
        }
    }

    /** Check if lock timed out (only call in synchronized/exclusive access context) */
    @PassLock("bbLock")
    private void checkCurrentLock() {
        if (isLocked() && Time.getCoarse() > lastKeepAlive + UNLOCK_TIMEOUT) {
            log(LogLevel.LL_DEBUG, logDomain, "Blackboard server: Lock timed out... unlocking");

            // meh... we have a read or write lock... so a client may make changes to it... or may still read it... it's safer to create new buffer here
            @InCpp("BBVectorVar newBuffer = write->getBufferForReturn<BBVector>();")
            PortDataList newBuffer = write.getBufferForReturn(readPortRaw.getDataType());

            //JavaOnlyBlock
            this.copyBlackboardBuffer(buffer, newBuffer);
            getManager(buffer).releaseLock();
            buffer = newBuffer;

            //Cpp this->copyBlackboardBuffer(*buffer, *newBuffer);
            //Cpp buffer = std::_move(newBuffer);

            newBufferRevision(true);
            locks = 0;

            boolean p = this.processPendingCommands();
            if ((!p) && (!isLocked())) {
                this.processPendingAsynchChangeTasks();
            }
        }
    }

    @PassLock("bbLock")
    private void newBufferRevision(boolean hasChanges) {
        lockId = lockIDGen.incrementAndGet();
        if (hasChanges) {
            revision++;
        }

        // process any waiting asynch change commands
        this.processPendingAsynchChangeTasks();
        if (threadWaitingForCopy || readPortRaw.getStrategy() > 0) {
            updateReadCopy();
        }
    }

    @Override
    protected boolean isLocked() {
        //Cpp assert(buffer._get() != NULL && buffer.getManager() != NULL);
        return locks != 0;
    }

    /**
     * wait until a read copy has been made
     * (only call in synchronized context)
     *
     * @param minRevision minimal revision we want to receive
     */
    @PassLock("bbLock")
    private void waitForReadCopy(long minRevision, long timeout) {
        long curTime = Time.getCoarse();
        while (readCopyRevision < minRevision) {
            long waitFor = timeout - (Time.getCoarse() - curTime);
            if (waitFor > 0) {
                threadWaitingForCopy = true;
                try {
                    bbLock.wait(waitFor);
                } catch (InterruptedException e) {
                    log(LogLevel.LL_WARNING, logDomain, "SingleBufferedBlackboardServer: Interrupted while waiting for read copy - strange");
                    //e.printStackTrace();
                }
            }
        }
    }

    /**
     * Make a copy for the read port - and hand it to anyone who is interested
     */
    @PassLock("bbLock")
    private void updateReadCopy() {
        assert(getManager(buffer).isLocked());

        if (readCopyRevision < revision) {

            //JavaOnlyBlock
            // release lock of old read buffer
            if (readCopy != null) {
                getManager(readCopy).releaseLock();
            }

            // copy current buffer

            //JavaOnlyBlock
            readCopy = write.getBufferForReturn(readPortRaw.getDataType());
            this.copyBlackboardBuffer(buffer, readCopy);

            //Cpp readCopy = write->getBufferForReturn<BBVector>();
            //Cpp this->copyBlackboardBuffer(*buffer, *read_copy);

            PortDataManager copymgr = getManager(readCopy);
            copymgr.lockID = -1;
            readPortRaw.publish(copymgr);

            readCopyRevision = revision;

            // publish read copy
            readPortRaw.publish(copymgr);
        }

        // notify all threads waiting for a buffer copy
        if (threadWaitingForCopy) {
            threadWaitingForCopy = false;
            wakeupThread = -1;
            bbLock.notifyAll();
        }

    }

    @Override
    // provides blocking access to blackboard (without copying)
    public PortDataManager pullRequest(PortBase origin, byte addLocks) {

        synchronized (bbLock) {

            // possibly wait for a copy
            while (readCopyRevision < revision) {  // not so clean, but everything else becomes rather complicated

                if (isLocked()) {
                    waitForReadCopy(revision, 2000);
                } else {
                    updateReadCopy();
                }
            }

            // add desired number of locks and return
            PortDataManager mgr = getManager(readCopy);
            mgr.getCurrentRefCounter().addLocks(addLocks);

            return mgr;
        }
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

            // note: current lock is obsolete, since we have a completely new buffer
            assert(newBuffer != buffer);

            //JavaOnlyBlock
            getManager(buffer).releaseLock();
            buffer = newBuffer;

            //Cpp buffer = std::_move(newBuffer);

            // Clear any asynch change commands from queue, since they were for old buffer
            this.clearAsyncChangeTasks();

            lockId = lockIDGen.incrementAndGet();
            assert(getManager(buffer).isLocked());
            locks = 0;
            this.processPendingCommands();
        }
    }

    @Override
    protected boolean isSingleBuffered() {
        return true;
    }

    @Override
    protected @CppType("AbstractBlackboardServer<T>::ConstBBVectorVar") PortDataList readLock(long timeout) throws MethodCallException {
        synchronized (bbLock) {
            return readLockImpl(timeout);
        }
    }

    /**
     * Helper method for above to avoid nested/double lock
     */
    @PassLock("bbLock")
    private @CppType("AbstractBlackboardServer<T>::ConstBBVectorVar") PortDataList readLockImpl(long timeout) throws MethodCallException {

        // Read Lock
        long currentRevision = revision;
        if (locks < 0 && currentRevision != readCopyRevision) {
            checkCurrentLock();
            if (locks < 0 && currentRevision != readCopyRevision) {
                if (timeout <= 0) {

                    //JavaOnlyBlock
                    return null; // we do not need to enqueue lock commands with zero timeout

                    //Cpp return ConstBBVectorVar(); // we do not need to enqueue lock commands with zero timeout
                }
                waitForReadCopy(currentRevision, timeout);
                assert(readCopyRevision >= currentRevision);
            }
        }

        if (readCopyRevision >= currentRevision) {
            // there's a copy... use this
            getManager(readCopy).addLock();

            //JavaOnlyBlock
            return readCopy;

            //Cpp return getManager(readCopy);
        }

        if (locks >= 0) {
            // okay, we either have no lock or a read lock
            if (this.pendingTasks() || threadWaitingForCopy) { // there are others waiting... make copy
                updateReadCopy();
                assert(readCopyRevision >= currentRevision);
                getManager(readCopy).addLock();

                //JavaOnlyBlock
                return readCopy;

                //Cpp return getManager(readCopy);
            } else { // no one waiting... simply lock buffer
                if (locks == 0) { // if this is the first lock: increment and set lock id of buffer
                    int lockIDNew = lockIDGen.incrementAndGet();
                    lockId = lockIDNew;
                    getManager(buffer).lockID = lockIDNew;
                }
                locks++;
                getManager(buffer).addLock();

                //JavaOnlyBlock
                return buffer;

                //Cpp return getManager(buffer);
            }
        }

        throw new MethodCallException(MethodCallException.Type.PROGRAMMING_ERROR);
    }

//    @Override
//    protected BlackboardBuffer readPart(int offset, int length, int timeout) throws MethodCallException {
//        synchronized (bbLock) {
//            @Const BlackboardBuffer bb = buffer;
//            boolean unlock = false;
//            long currentRevision = revision;
//            int locksCheck = 0;
//            if (locks < 0 && currentRevision != readCopyRevision) {
//                checkCurrentLock();
//                if (locks < 0 && currentRevision != readCopyRevision) {
//                    if (timeout <= 0) {
//                        return null; // we do not need to enqueue lock commands with zero timeout
//                    }
//
//                    // okay... we'll do a read lock
//                    bb = readLockImpl(timeout);
//                    if (bb == null) {
//                        return null;
//                    }
//                    locksCheck = locks;
//                    assert(locksCheck > 0);
//                    unlock = true;
//                }
//            }
//
//            if ((!unlock) && currentRevision == readCopyRevision) { // can we use read copy?
//                bb = readCopy;
//            }
//
//            // prepare and set return value
//            BlackboardBuffer send = (BlackboardBuffer)write.getUnusedBuffer(buffer.getType());
//            send.resize(1, 1, length, false); // ensure minimal size
//            send.getBuffer().put(0, bb.getBuffer(), offset, length);
//            send.bbCapacity = buffer.bbCapacity;
//            send.elements = buffer.elements;
//            send.elementSize = buffer.elementSize;
//
//            if (unlock) { // if we have a read lock, we need to release it
//                assert(locks == locksCheck);
//                readUnlockImpl(lockId);
//                assert(locks == locksCheck - 1);
//            }
//
//            // return buffer with one read lock
//            send.getManager().getCurrentRefCounter().setLocks((byte)1);
//            return send;
//        }
//    }

    @Override
    protected @CppType("AbstractBlackboardServer<T>::BBVectorVar")PortDataList writeLock(long timeout) {
        synchronized (bbLock) {
            if (isLocked() || this.pendingTasks()) {
                checkCurrentLock();
                if (isLocked() || this.pendingTasks()) {
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
            }

            assert(!isLocked());

            // lock current buffer... and return it with a lock
            int lockIDNew = lockIDGen.incrementAndGet();
            lockId = lockIDNew;
            getManager(buffer).lockID = lockIDNew;
            locks = -1;
            lockTime = Time.getCoarse();
            lastKeepAlive = lockTime;

            getManager(buffer).addLock();

            //JavaOnlyBlock
            return buffer;

            //Cpp return getManager(buffer);
        }
    }

    @Override
    protected void readUnlock(int lockId) throws MethodCallException {
        if (lockId < 0) {
            return; // not interested, since it's a copy
        }

        synchronized (bbLock) {
            readUnlockImpl(lockId);
        }
    }

    /**
     * Helper method for above to avoid nested/double lock
     */
    @PassLock("bbLock")
    private void readUnlockImpl(int lockId) throws MethodCallException {
        if (lockId < 0) {
            return; // not interested, since it's a copy
        }

        if (this.lockId != lockId) {
            log(LogLevel.LL_DEBUG, logDomain, "Skipping outdated unlock");
            return;
        }

        // okay, this is unlock for the current lock
        assert(locks > 0);
        locks--;
        if (locks == 0) {
            newBufferRevision(false);
            this.processPendingCommands();
        }
        return;
    }

    @Override
    protected void writeUnlock(@CppType("BBVectorVar")PortDataList buf) {
        if (buf == null) {
            log(LogLevel.LL_WARNING, logDomain, "blackboard write unlock without providing buffer - you shouldn't do that - ignoring");
            return;
        }
        PortDataManager bufmgr = getManager(buf);
        assert(bufmgr.lockID >= 0) : "lock IDs < 0 are typically only found in read copies";

        synchronized (bbLock) {
            if (this.lockId != bufmgr.lockID) {
                log(LogLevel.LL_DEBUG, logDomain, "Skipping outdated unlock");

                //JavaOnlyBlock
                bufmgr.releaseLock();

                return;
            }

            assert(locks < 0); // write lock
            assert(bufmgr.isLocked());

            lockId = lockIDGen.incrementAndGet();

            //JavaOnlyBlock
            if (buf == buffer) {
                // we got the same buffer back - we only need to release one lock from method call
                bufmgr.releaseLock();
            } else {
                getManager(buffer).releaseLock();
                buffer = buf;
                assert(getManager(buffer).isLocked());
            }

            /*Cpp
            if (buf != buffer) {
                buffer = std::_move(buf);
                assert(getManager(buffer)->isLocked());
            } else {
                buf._reset();
            }
             */

            locks = 0;
            newBufferRevision(true);

            this.processPendingCommands();
        }
    }

//    @Override
//    public void getSizeInfo(int elementSize, int elements, int capacity) {
//
//        // ok... three cases... 1) up to date copy  2) no lock  3) lock
//
//        // case 1: get buffer from superclass
//        if (readCopyRevision == revision) {
//            @Const BlackboardBuffer bb = (BlackboardBuffer)readPortRaw.getLockedUnsafeRaw();
//            elementSize = bb.getElementSize();
//            elements = bb.getElements();
//            capacity = bb.getBbCapacity();
//            bb.getManager().releaseLock();
//            return;
//        }
//
//        // case 2/3: okay... wait until blackboard has no lock (could be implemented more sophisticated, but that shouldn't matter here...)
//        while (true) {
//            synchronized (bbLock) {
//                if (locks >= 0) { // ok, not locked or read locked
//                    elementSize = buffer.getElementSize();
//                    elements = buffer.getElements();
//                    capacity = buffer.getBbCapacity();
//                    return;
//                }
//                try {
//                    Thread.sleep(50);
//                } catch (InterruptedException e) {}
//            }
//        }
//    }

    /** Special read port for blackboard buffer */
    class BBReadPort extends PortBase {

        public BBReadPort(PortCreationInfo pci) {
            super(pci);
        }

        @Override
        protected void initialPushTo(AbstractPort target, boolean reverse) {
            assert(!reverse) : "?!";

            // ok... three cases... 1) up to date copy  2) no lock  3) lock

            // case 1: let super class handle this
            if (readCopyRevision == revision) {
                super.initialPushTo(target, reverse);
                return;
            }

            // case 3: publish will happen anyway - since strategy is > 0

            // case 2: make read copy
            synchronized (bbLock) {
                if (locks >= 0) { // ok, not locked or read locked
                    locks++;
                    updateReadCopy();
                    locks--;
                }
            }
        }
    }
}
