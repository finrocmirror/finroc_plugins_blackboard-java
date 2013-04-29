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

import org.rrlib.finroc_core_utils.jc.log.LogDefinitions;
import org.rrlib.finroc_core_utils.log.LogDomain;
import org.rrlib.finroc_core_utils.log.LogLevel;
import org.rrlib.finroc_core_utils.rtti.DataTypeBase;
import org.rrlib.finroc_core_utils.serialization.PortDataList;
import org.finroc.core.FrameworkElement;
import org.finroc.core.FrameworkElementFlags;
import org.finroc.core.port.PortCreationInfo;
import org.finroc.core.port.rpc.MethodCallException;
import org.finroc.core.port.std.PortDataManager;

/**
 * @author Max Reichardt
 *
 * This is the base class for a blackboard client
 */
@SuppressWarnings( {"rawtypes", "unchecked"})
public class BlackboardClient<T> {

    /** Wrapped raw blackboard client */
    private RawBlackboardClient wrapped;

    /** not null - if buffer is currently locked for writing */
    protected PortDataList locked;

    /** not null - if buffer is currently locked for writing */
    protected PortDataList readLocked;

    /** Log domain for this class */
    public static final LogDomain logDomain = LogDefinitions.finroc.getSubDomain("blackboard");

    /**
     * @param name Name/Uid of blackboard
     * @param parent Parent of blackboard client
     * @param pushUpdates Use push strategy? (Any blackboard updates will be pushed to read port; required for changed-flag to work properly; disabled by default (network-bandwidth))
     * @param type Data Type of blackboard content
     */
    public BlackboardClient(String name, FrameworkElement parent, boolean pushUpdates, DataTypeBase type) {
        this(name, parent, pushUpdates, true, -1, true, true, type);
    }

    /**
     * @param name Name/Uid of blackboard
     * @param parent Parent of blackboard client
     * @param pushUpdates Use push strategy? (Any blackboard updates will be pushed to read port; required for changed-flag to work properly; disabled by default (network-bandwidth))
     * @param autoConnect Auto-Connect blackboard client to matching server?
     * @param autoConnectCategory If auto-connect is active: Limit auto-connecting to a specific blackboard category? (-1 is no)
     * @param readPort Create read port?
     * @param writePort Create write port?
     * @param type Data Type of blackboard content
     */
    public BlackboardClient(String name, FrameworkElement parent, boolean pushUpdates, boolean autoConnect, int autoConnectCategory,
                            boolean readPort, boolean writePort, DataTypeBase type) {
        wrapped = new RawBlackboardClient(new PortCreationInfo(name, parent, initBlackboardType(type),
                                          (writePort ? FrameworkElementFlags.EMITS_DATA : 0) | (readPort ? FrameworkElementFlags.ACCEPTS_DATA : 0) | (pushUpdates ? FrameworkElementFlags.PUSH_STRATEGY : 0)),
                                          (T)null, autoConnect, autoConnectCategory);
    }

    /**
     * Make sure specified type is registered for blackboards
     *
     * @param dt Type
     * @return The same as parameter type
     */
    private DataTypeBase initBlackboardType(DataTypeBase dt) {
        BlackboardTypeInfo bti = AbstractBlackboardServerRaw.getBlackboardTypeInfo(dt);
        if (bti == null || bti.blackboardType == null) {
            BlackboardPlugin.<T>registerBlackboardType(dt);
        }
        return dt;
    }

    /**
     * Often Non-blocking, safe blackboard read operation
     * (will always operate on read copy - for SingleBufferedBlackboardServers readLock can be more efficient, but also more blocking)
     *
     * @param timeout (relevant for SingleBufferedBlackboardClients only) Timeout for lock attempt
     * @return Raw memory buffer containing blackboard contents - locked - don't forget to release read lock
     */
    public PortDataList<T> read(long timeout) {
        return wrapped.getReadPort().getLockedUnsafeRaw().getObject().getData();
    }

    public PortDataList<T> read() {
        return read(2000);
    }

    /**
     * Commit asynchronous change to blackboard. Blackboard does
     * not need to be locked for this operation.
     * (if connection is broken, there's no guarantee that this will work or that an exception is thrown otherwise)
     *
     * @param changeBuf Contents to write to this position (unlocked buffer retrieved via getUnusedBuffer OR a used buffer with an additional lock)
     * @param index First element to change
     * @param offset Some custom offset in element (optional)
     * @return Did operation succeed? (usual reason for failing is that blackboard is not connected)
     */
    public boolean commitAsynchChange(PortDataList<T> changeBuf, int index, int offset) throws MethodCallException {

        try {
            assert(!PortDataManager.getManager(changeBuf).isUnused()) : "Obtain buffer from getUnusedChangeBuffer()";
            AbstractBlackboardServer.ASYNCH_CHANGE.call(wrapped.getWritePort(), changeBuf, index, offset, true);
            return true;
        } catch (MethodCallException e) {
            return false;
        }
    }
//
//    /**
//     * Read part of blackboard
//     *
//     * @param offset offset in byte
//     * @param length length in byte
//     * @param timeout timeout for this synchronous operation
//     * @return Lock Locked buffer - or null if operation failed (position 0 in this buffer is position 'offset' in original one)
//     *  is unlocked automatically
//     */
//    public BlackboardBuffer readPart(int offset, int length, @CppDefault("60000") int timeout) {
//        if (timeout <= 0) {
//            timeout = 60000; // wait one minute for method to complete if no time is specified
//        }
//        try {
//            return AbstractBlackboardServer.READ_PART.call(getWritePort(), offset, length, timeout, timeout + NET_TIMEOUT);
//        } catch (MethodCallException e) {
//            return null;
//        }
//    }
//
    /**
     * Lock blackboard in order to read and commit changes
     * (synchronous/blocking... only use if absolutely necessary)
     *
     * @param timeout timeout for lock
     * @return Lock Locked buffer - or null if lock failed - this buffer may be modified -
     * call unlock() after modifications are complete - locks of buffer should normally not be modified -
     * except of it should be used in some other port or stored for longer than the unlock() operation
     */
    public PortDataList<T> writeLock(int timeout) {
        if (timeout <= 0) {
            timeout = 60000; // wait one minute for method to complete if no time is specified
        }

        assert(locked == null && wrapped.lockType == RawBlackboardClient.LockType.NONE);
        assert(wrapped.curLockID == -1);
        assert(wrapped.isReady());
        try {
            PortDataList ret = AbstractBlackboardServer.LOCK.call(wrapped.getWritePort(), timeout, (int)(timeout + RawBlackboardClient.NET_TIMEOUT));

            if (ret != null) {
                wrapped.lockType = RawBlackboardClient.LockType.WRITE;
                wrapped.curLockID = (PortDataManager.getManager(ret)).lockID;
                locked = ret;

                // acknowledge lock
                wrapped.sendKeepAlive();
            } else {
                wrapped.curLockID = -1;
            }

            return locked;
        } catch (MethodCallException e) {
            wrapped.curLockID = -1;
            return null;
        }
    }

    /**
     * Read Lock on blackboard.
     *
     * Blackboard locked using this method needs to be unlocked via unlock() method!
     *
     * In most cases it will return a read copy (this can be forced).
     * On local single buffered blackboard servers - the same buffer might be used for reading (blocks more, but less copying)
     *
     * @param timeout Timeout for call
     */
    public PortDataList<T> readLock(int timeout) {
        return readLock(false, timeout);
    }

    /**
     * Read Lock on blackboard.
     *
     * Blackboard locked using this method needs to be unlocked via unlock() method!
     *
     * In most cases it will return a read copy (this can be forced).
     * On local single buffered blackboard servers - the same buffer might be used for reading (blocks more, but less copying)
     *
     * @param forceReadCopyToAvoidBlocking Force read copy to avoid blocking? (only relevant for single buffered blackboard servers)
     * @param timeout Timeout for call
     */
    public PortDataList<T> readLock(boolean forceReadCopyToAvoidBlocking, int timeout) {
        assert(locked == null && wrapped.lockType == RawBlackboardClient.LockType.NONE) : "Unlock first";
        if (timeout <= 0) {
            timeout = 60000; // wait one minute for method to complete if no time is specified
        }

        // determine whether blackboard server is single buffered
        wrapped.checkSingleBuffered();
        if (wrapped.serverBuffers == RawBlackboardClient.ServerBuffers.UNKNOWN) { // we currently have no partner (?)
            return null;
        }

        boolean viaPort = (wrapped.getReadPort() != null) && ((wrapped.serverBuffers == RawBlackboardClient.ServerBuffers.MULTI) || wrapped.getReadPort().pushStrategy() || forceReadCopyToAvoidBlocking || wrapped.getWritePort().hasRemoteServer());
        if (viaPort) {
            wrapped.lockType = RawBlackboardClient.LockType.READ;
            wrapped.curLockID = -1;
            readLocked = read(timeout);
            return readLocked;
        } else {

            assert(locked == null && wrapped.lockType == RawBlackboardClient.LockType.NONE);
            assert(wrapped.curLockID == -1);
            assert(wrapped.isReady());
            try {
                PortDataList ret = AbstractBlackboardServer.READ_LOCK.call(wrapped.getWritePort(), timeout, 0, (int)(timeout + RawBlackboardClient.NET_TIMEOUT));

                if (ret != null) {
                    wrapped.lockType = RawBlackboardClient.LockType.READ;
                    wrapped.curLockID = (PortDataManager.getManager(ret)).lockID;
                    readLocked = ret;

                    // acknowledge lock
                    wrapped.sendKeepAlive();
                    return readLocked;
                } else {
                    wrapped.curLockID = -1;
                    return null;
                }
            } catch (MethodCallException e) {
                wrapped.curLockID = -1;
                return null;
            }
        }
    }

    /**
     * Reset variables after unlock
     */
    private void resetVariables() {
        wrapped.curLockID = -1;
        wrapped.lockType = RawBlackboardClient.LockType.NONE;
        locked = null;
        readLocked = null;
    }

    /**
     * Commit changes of previously locked buffer
     */
    public void unlock() {
        if (wrapped.lockType == RawBlackboardClient.LockType.NONE) {
            logDomain.log(LogLevel.LL_WARNING, getLogDescription(), "BlackboardClient warning: nothing to unlock");
            resetVariables();
            return; // nothing to unlock
        }
        if (wrapped.lockType == RawBlackboardClient.LockType.READ) {

            // we only have a read copy
            assert(readLocked != null);
            if (wrapped.curLockID >= 0) {
                try {
                    AbstractBlackboardServer.READ_UNLOCK.call(wrapped.getWritePort(), wrapped.curLockID, true);
                } catch (MethodCallException e) {
                    logDomain.log(LogLevel.LL_WARNING, getLogDescription(), "warning: Unlocking blackboard (read) failed", e);
                }
            }
            PortDataManager.getManager(readLocked).releaseLock();

            resetVariables();
            return;
        }

        assert(wrapped.lockType == RawBlackboardClient.LockType.WRITE);
        assert(wrapped.curLockID >= 0);

        try {
            AbstractBlackboardServer.UNLOCK.call(wrapped.getWritePort(), locked, true);
        } catch (MethodCallException e) {
            logDomain.log(LogLevel.LL_WARNING, getLogDescription(), "warning: Unlocking blackboard failed");
            //e.printStackTrace();
        }
        resetVariables();
    }

    /**
     * @return log description
     */
    protected String getLogDescription() {
        return wrapped.getLogDescription();
    }

    /**
     * Directly commit/publish buffer - without lock
     *
     * @param buffer Buffer to publish (unlocked buffer retrieved via getUnusedBuffer OR a used buffer with an additional lock)
     */
    public void publish(PortDataList<T> buffer) {
        assert(wrapped.lockType == RawBlackboardClient.LockType.NONE);
        assert(!PortDataManager.getManager(buffer).isUnused()) : "Obtain buffer from getUnusedBuffer()";
        /*if (buffer.getManager().isUnused()) {
            buffer.getManager().getCurrentRefCounter().setLocks((byte)1);
        }*/
        try {
            AbstractBlackboardServer.DIRECT_COMMIT.call(wrapped.getWritePort(), buffer, true);
        } catch (MethodCallException e) {
            logDomain.log(LogLevel.LL_WARNING, getLogDescription(), "warning: Blackboard direct commit failed");
        }
    }

    /**
     * @return unused buffer - may be published/committed directly
     */
    public PortDataList<T> getUnusedBuffer() {
        return wrapped.getWritePort().getBufferForCall(wrapped.getReadPort().getDataType());
    }

    /**
     * @return unused change buffer - to be used in commitAsynchChange
     */
    public PortDataList<T> getUnusedChangeBuffer() {
        return wrapped.getWritePort().getBufferForCall(wrapped.getReadPort().getDataType());
    }

    /**
     * Initialize blackboard client
     */
    public void init() {
        wrapped.init();
    }

    /**
     * @return Is client currently holding read lock?
     */
    public boolean hasReadLock() {
        return wrapped.hasReadLock();
    }

    /**
     * @return Is client currently holding write lock?
     */
    public boolean hasWriteLock() {
        return wrapped.hasWriteLock();
    }

    /**
     * @return Is client currently holding read or write lock?
     */
    public boolean hasLock() {
        return wrapped.hasLock();
    }

    /**
     * @return Wrapped raw blackboard client
     */
    public RawBlackboardClient getWrapped() {
        return wrapped;
    }

    /**
     * @return Blackboard name
     */
    public String getName() {
        return wrapped.getName();
    }

    /**
     * (only works properly if pushUpdates in constructor was set to true)
     *
     * @return Has port changed since last changed-flag-reset?
     */
    public boolean hasChanged() {
        assert(wrapped.getReadPort() != null);
        if (!wrapped.getReadPort().getFlag(FrameworkElementFlags.PUSH_STRATEGY)) {
            logDomain.log(LogLevel.LL_DEBUG_WARNING, getLogDescription(), "This method only works properly, when push strategy is used.");
        }
        return wrapped.getReadPort().hasChanged();
    }

    /**
     * (only works properly if pushUpdates in constructor was set to true)
     *
     * Reset changed flag.
     */
    public void resetChanged() {
        assert(wrapped.getReadPort() != null);
        if (!wrapped.getReadPort().getFlag(FrameworkElementFlags.PUSH_STRATEGY)) {
            logDomain.log(LogLevel.LL_DEBUG_WARNING, getLogDescription(), "This method only works properly, when push strategy is used.");
        }
        wrapped.getReadPort().resetChanged();
    }
}
