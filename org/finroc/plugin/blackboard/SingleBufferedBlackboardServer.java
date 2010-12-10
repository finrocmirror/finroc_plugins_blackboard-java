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
import org.finroc.jc.annotation.Const;
import org.finroc.jc.annotation.CppDefault;
import org.finroc.jc.annotation.JavaOnly;
import org.finroc.jc.annotation.PassLock;
import org.finroc.jc.annotation.Ptr;
import org.finroc.log.LogLevel;
import org.finroc.core.CoreFlags;
import org.finroc.core.FrameworkElement;
import org.finroc.core.LockOrderLevels;
import org.finroc.core.port.AbstractPort;
import org.finroc.core.port.PortCreationInfo;
import org.finroc.core.port.PortFlags;
import org.finroc.core.port.rpc.InterfaceServerPort;
import org.finroc.core.port.rpc.MethodCallException;
import org.finroc.core.port.rpc.method.AbstractMethod;
import org.finroc.core.port.std.Port;
import org.finroc.core.port.std.PortBase;
import org.finroc.core.port.std.PortData;
import org.finroc.core.port.std.PullRequestHandler;
import org.finroc.core.portdatabase.DataType;

/**
 * @author max
 *
 * This is the base class for a blackboard server
 */
@Ptr
public class SingleBufferedBlackboardServer extends AbstractBlackboardServer implements PullRequestHandler {

    /** Unlock timeout in ms - if no keep-alive signal occurs in this period of time */
    private final static long UNLOCK_TIMEOUT = 1000;

    /** Interface port for write access */
    private InterfaceServerPort write;

    /**
     * this is the one and only blackboard buffer
     * (can be replaced, when new buffers arrive from network => don't store pointers to it, when not locked)
     * (has exactly one lock)
     */
    private BlackboardBuffer buffer;

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
    private BlackboardBuffer readCopy;

    /** revision of read copy */
    private long readCopyRevision = -1;

    /** Is a thread waiting for a blackboard copy? */
    private volatile boolean threadWaitingForCopy;

    ///** Writer for blackboard buffers in READ_PART (only use in synchronized context!) */
    //private OutputStreamBuffer bufWriter = new OutputStreamBuffer();

    /**
     * @param description Name/Uid of blackboard
     */
    @JavaOnly
    public SingleBufferedBlackboardServer(String description) {
        this(description, null);
    }

    /**
     * @param description Name/Uid of blackboard
     * @parent parent of BlackboardServer
     */
    public SingleBufferedBlackboardServer(String description, @CppDefault("NULL") FrameworkElement parent) {
        this(description, BlackboardBuffer.BUFFER_TYPE, parent, true);
    }

    /**
     * @param description Name/Uid of blackboard
     * @param type Data Type of blackboard content
     * @param capacity Blackboard capacity (see BlackboardBuffer)
     * @param elements Number of element (see BlackboardBuffer)
     * @param elemSize Element size (see BlackboardBuffer)
     * @param parent parent of BlackboardServer
     * @param shared Share blackboard with other runtime environments?
     */
    public SingleBufferedBlackboardServer(String description, DataType type, int capacity, int elements, int elemSize, @CppDefault("NULL") FrameworkElement parent, @CppDefault("true") boolean shared) {
        this(description, type, parent, shared);
        buffer.resize(capacity, elements, elemSize, false);
    }

    /**
     * @param description Name/Uid of blackboard
     * @param type Data Type of blackboard content
     * @param mcType Type of method calls
     * @param parent parent of BlackboardServer
     * @param shared Share blackboard with other runtime environments?
     */
    public SingleBufferedBlackboardServer(String description, DataType type, @CppDefault("NULL") FrameworkElement parent, @CppDefault("true") boolean shared) {
        super(description, shared ? BlackboardManager.SHARED : BlackboardManager.LOCAL, parent);
        readPort = new BBReadPort(new PortCreationInfo("read", this, type, PortFlags.OUTPUT_PORT | (shared ? CoreFlags.SHARED : 0)).lockOrderDerive(LockOrderLevels.REMOTE_PORT + 1));
        readPort.setPullRequestHandler(this);
        checkType(type);
        write = new InterfaceServerPort("write", this, type.getRelatedType(), this, shared ? CoreFlags.SHARED : 0, LockOrderLevels.REMOTE_PORT + 2);
        writePort = write;
        buffer = (BlackboardBuffer)write.getUnusedBuffer(type);
        buffer.getManager().getCurrentRefCounter().setLocks((byte)1);
    }

    public void delete() {
        if (readCopy != null) {
            readCopy.getManager().releaseLock();
            readCopy = null;
        }
        assert(buffer != null);
        buffer.getManager().releaseLock();
        buffer = null;
    }

    @Override
    public Byte handleCall(AbstractMethod method) throws MethodCallException {
        assert(method == IS_SINGLE_BUFFERED);
        return 1;
    }

    @Override
    protected void asynchChange(int offset, BlackboardBuffer buf, boolean checkLock) {
        synchronized (bbLock) {
            if (checkLock && isLocked()) {
                checkCurrentLock();
                if (isLocked()) {
                    deferAsynchChangeCommand(offset, buf);
                    return;
                }
            }

            assert((!checkLock) || (!isLocked()));

            buffer.getBuffer().put(offset, buf.getBuffer(), 0, buf.getSize());
            buf.getManager().releaseLock();

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
            BlackboardBuffer newBuffer = (BlackboardBuffer)readPort.getUnusedBufferRaw();
            newBuffer.getManager().getCurrentRefCounter().setLocks((byte)1);;
            copyBlackboardBuffer(buffer, newBuffer);
            buffer.getCurReference().getRefCounter().releaseLock();
            buffer = newBuffer;

            newBufferRevision(true);
            locks = 0;

            boolean p = processPendingCommands();
            if ((!p) && (!isLocked())) {
                super.processPendingAsynchChangeTasks();
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
        processPendingAsynchChangeTasks();
        if (threadWaitingForCopy || readPort.getStrategy() > 0) {
            updateReadCopy();
        }
    }

    @Override
    protected boolean isLocked() {
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
        assert(buffer.getManager().isLocked());

        if (readCopyRevision < revision) {

            // release lock of old read buffer
            if (readCopy != null) {
                readCopy.getManager().releaseLock();
            }

            // copy current buffer
            readCopy = (BlackboardBuffer)readPort.getUnusedBufferRaw();
            readCopy.getManager().getCurrentRefCounter().setLocks((byte)1);;
            copyBlackboardBuffer(buffer, readCopy);
            readCopy.lockID = -1;
            readPort.publish(readCopy);

            readCopyRevision = revision;

            // publish read copy
            readPort.publish(readCopy);
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
    public PortData pullRequest(PortBase origin, byte addLocks) {

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
            readCopy.getManager().getCurrentRefCounter().addLocks(addLocks);
            return readCopy;
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
    protected void directCommit(BlackboardBuffer newBuffer) {
        if (newBuffer == null) {
            return;
        }

        synchronized (bbLock) {

            assert(newBuffer != buffer);

            // note: current lock is obsolete, since we have a completely new buffer
            buffer.getManager().getCurrentRefCounter().releaseLock();
            buffer = newBuffer;

            // Clear any asynch change commands from queue, since they were for old buffer
            clearAsyncChangeTasks();

            lockId = lockIDGen.incrementAndGet();
            assert(buffer.getManager().isLocked());
            locks = 0;
            processPendingCommands();
        }
    }

    @Override
    protected boolean isSingleBuffered() {
        return true;
    }

    @Override
    protected BlackboardBuffer readLock(long timeout) throws MethodCallException {
        synchronized (bbLock) {
            return readLockImpl(timeout);
        }
    }

    /**
     * Helper method for above to avoid nested/double lock
     */
    @PassLock("bbLock")
    private BlackboardBuffer readLockImpl(long timeout) throws MethodCallException {

        // Read Lock
        long currentRevision = revision;
        if (locks < 0 && currentRevision != readCopyRevision) {
            checkCurrentLock();
            if (locks < 0 && currentRevision != readCopyRevision) {
                if (timeout <= 0) {
                    return null; // we do not need to enqueue lock commands with zero timeout
                }
                waitForReadCopy(currentRevision, timeout);
                assert(readCopyRevision >= currentRevision);
            }
        }

        if (readCopyRevision >= currentRevision) {
            // there's a copy... use this
            readCopy.getManager().addLock();
            return readCopy;
        }

        if (locks >= 0) {
            // okay, we either have no lock or a read lock
            if (pendingTasks() || threadWaitingForCopy) { // there are others waiting... make copy
                updateReadCopy();
                assert(readCopyRevision >= currentRevision);
                readCopy.getManager().addLock();
                return readCopy;
            } else { // no one waiting... simply lock buffer
                if (locks == 0) { // if this is the first lock: increment and set lock id of buffer
                    int lockIDNew = lockIDGen.incrementAndGet();
                    lockId = lockIDNew;
                    buffer.lockID = lockIDNew;
                }
                locks++;
                buffer.getManager().addLock();
                return buffer;
            }
        }

        throw new MethodCallException(MethodCallException.Type.PROGRAMMING_ERROR);
    }

    @Override
    protected BlackboardBuffer readPart(int offset, int length, int timeout) throws MethodCallException {
        synchronized (bbLock) {
            @Const BlackboardBuffer bb = buffer;
            boolean unlock = false;
            long currentRevision = revision;
            int locksCheck = 0;
            if (locks < 0 && currentRevision != readCopyRevision) {
                checkCurrentLock();
                if (locks < 0 && currentRevision != readCopyRevision) {
                    if (timeout <= 0) {
                        return null; // we do not need to enqueue lock commands with zero timeout
                    }

                    // okay... we'll do a read lock
                    bb = readLockImpl(timeout);
                    if (bb == null) {
                        return null;
                    }
                    locksCheck = locks;
                    assert(locksCheck > 0);
                    unlock = true;
                }
            }

            if ((!unlock) && currentRevision == readCopyRevision) { // can we use read copy?
                bb = readCopy;
            }

            // prepare and set return value
            BlackboardBuffer send = (BlackboardBuffer)write.getUnusedBuffer(buffer.getType());
            send.resize(1, 1, length, false); // ensure minimal size
            send.getBuffer().put(0, bb.getBuffer(), offset, length);
            send.bbCapacity = buffer.bbCapacity;
            send.elements = buffer.elements;
            send.elementSize = buffer.elementSize;

            if (unlock) { // if we have a read lock, we need to release it
                assert(locks == locksCheck);
                readUnlockImpl(lockId);
                assert(locks == locksCheck - 1);
            }

            // return buffer with one read lock
            send.getManager().getCurrentRefCounter().setLocks((byte)1);
            return send;
        }
    }

    @Override
    protected BlackboardBuffer writeLock(long timeout) {
        synchronized (bbLock) {
            if (isLocked() || pendingTasks()) {
                checkCurrentLock();
                if (isLocked() || pendingTasks()) {
                    if (timeout <= 0) {
                        return null; // we do not need to enqueue lock commands with zero timeout
                    } else {
                        // wait for lock
                        boolean haveLock = waitForLock(timeout);
                        if (!haveLock) {
                            return null; // we didn't get lock :-/
                        }
                    }
                }
            }

            assert(!isLocked());

            // lock current buffer... and return it with a lock
            int lockIDNew = lockIDGen.incrementAndGet();
            lockId = lockIDNew;
            buffer.lockID = lockIDNew;
            locks = -1;
            lockTime = Time.getCoarse();
            lastKeepAlive = lockTime;
            buffer.getManager().addLock();
            return buffer;
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
            processPendingCommands();
        }
        return;
    }

    @Override
    protected void writeUnlock(BlackboardBuffer buf) {
        if (buf == null) {
            log(LogLevel.LL_WARNING, logDomain, "blackboard write unlock without providing buffer - you shouldn't do that - ignoring");
            return;
        }
        assert(buf.lockID >= 0) : "lock IDs < 0 are typically only found in read copies";

        synchronized (bbLock) {
            if (this.lockId != buf.lockID) {
                log(LogLevel.LL_DEBUG, logDomain, "Skipping outdated unlock");
                buf.getManager().releaseLock();
                return;
            }

            assert(locks < 0); // write lock
            assert(buf.getManager().isLocked());

            lockId = lockIDGen.incrementAndGet();
            if (buf == buffer) {
                // we got the same buffer back - we only need to release one lock from method call
                buf.getManager().getCurrentRefCounter().releaseLock();
            } else {
                buffer.getManager().getCurrentRefCounter().releaseLock();
                buffer = buf;
                //System.out.println("Thread " + Thread.currentThread().toString() + ": lock = " + buffer.toString());
                assert(buffer.getCurReference().isLocked());
            }
            locks = 0;
            newBufferRevision(true);

            processPendingCommands();
        }
    }

    @Override
    public void getSizeInfo(int elementSize, int elements, int capacity) {

        // ok... three cases... 1) up to date copy  2) no lock  3) lock

        // case 1: get buffer from superclass
        if (readCopyRevision == revision) {
            @Const BlackboardBuffer bb = (BlackboardBuffer)readPort.getLockedUnsafeRaw();
            elementSize = bb.getElementSize();
            elements = bb.getElements();
            capacity = bb.getBbCapacity();
            bb.getManager().releaseLock();
            return;
        }

        // case 2/3: okay... wait until blackboard has no lock (could be implemented more sophisticated, but that shouldn't matter here...)
        while (true) {
            synchronized (bbLock) {
                if (locks >= 0) { // ok, not locked or read locked
                    elementSize = buffer.getElementSize();
                    elements = buffer.getElements();
                    capacity = buffer.getBbCapacity();
                    return;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {}
            }
        }


    }

    /** Special read port for blackboard buffer */
    class BBReadPort extends Port<BlackboardBuffer> {

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
