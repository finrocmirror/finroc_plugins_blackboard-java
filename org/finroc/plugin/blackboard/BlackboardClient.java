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

import org.finroc.jc.annotation.Const;
import org.finroc.jc.annotation.ConstMethod;
import org.finroc.jc.annotation.CppDefault;
import org.finroc.jc.annotation.CppType;
import org.finroc.jc.annotation.ForwardDecl;
import org.finroc.jc.annotation.HAppend;
import org.finroc.jc.annotation.InCpp;
import org.finroc.jc.annotation.Include;
import org.finroc.jc.annotation.Inline;
import org.finroc.jc.annotation.JavaOnly;
import org.finroc.jc.annotation.PassByValue;
import org.finroc.jc.annotation.PostInclude;
import org.finroc.jc.annotation.Ptr;
import org.finroc.jc.annotation.RawTypeArgs;
import org.finroc.jc.annotation.Ref;
import org.finroc.jc.annotation.SkipArgs;
import org.finroc.jc.log.LogDefinitions;
import org.finroc.log.LogDomain;
import org.finroc.log.LogLevel;
import org.finroc.serialization.DataTypeBase;
import org.finroc.serialization.PortDataList;
import org.finroc.core.FrameworkElement;
import org.finroc.core.port.PortCreationInfo;
import org.finroc.core.port.PortFlags;
import org.finroc.core.port.rpc.MethodCallException;
import org.finroc.core.port.std.PortDataManager;

/**
 * @author max
 *
 * This is the base class for a blackboard client
 */
@SuppressWarnings( {"rawtypes", "unchecked"}) @PassByValue @RawTypeArgs
@ForwardDecl( {BlackboardReadAccess.class, BlackboardWriteAccess.class})
@PostInclude( {"BlackboardReadAccess.h", "BlackboardWriteAccess.h"})
@Include("core/port/PortUtil.h")
@HAppend( {
    "extern template class BlackboardClient<BlackboardBuffer>;",
    "extern template class BlackboardClient<rrlib::serialization::MemoryBuffer>;"
})
public class BlackboardClient<T> {

    /*Cpp
    public:
    typedef typename AbstractBlackboardServer<T>::BBVector BBVector;
    typedef typename AbstractBlackboardServer<T>::BBVectorVar BBVectorVar;
    typedef typename AbstractBlackboardServer<T>::ConstBBVectorVar ConstBBVectorVar;
    typedef typename AbstractBlackboardServer<T>::ChangeTransaction ChangeTransaction;
    typedef typename AbstractBlackboardServer<T>::ChangeTransactionVar ChangeTransactionVar;
    typedef typename AbstractBlackboardServer<T>::ConstChangeTransactionVar ConstChangeTransactionVar;

    private:
    */

    /** Wrapped raw blackboard client */
    private RawBlackboardClient wrapped;

    /** not null - if buffer is currently locked for writing */
    protected @CppType("BBVectorVar") PortDataList locked;

    /** not null - if buffer is currently locked for writing */
    protected @CppType("ConstBBVectorVar") PortDataList readLocked;

    /*Cpp
    typedef BlackboardWriteAccess<T> WriteAccess;
    typedef BlackboardReadAccess<T> ReadAccess;
    */

    /** Log domain for this class */
    @InCpp("_RRLIB_LOG_CREATE_NAMED_DOMAIN(logDomain, \"blackboard\");")
    public static final LogDomain logDomain = LogDefinitions.finroc.getSubDomain("blackboard");

    /**
     * @param description Name/Uid of blackboard
     * @param parent Parent of blackboard client
     * @param type Data Type of blackboard content
     */
    @JavaOnly
    @SkipArgs("3")
    public BlackboardClient(String description, @CppDefault("NULL") FrameworkElement parent, @CppDefault("rrlib::serialization::DataType<T>()") DataTypeBase type) {
        this(description, parent, true, -1, true, true, type);
    }

    /**
     * @param description Name/Uid of blackboard
     * @param parent Parent of blackboard client
     * @param autoConnect Auto-Connect blackboard client to matching server?
     * @param autoConnectCategory If auto-connect is active: Limit auto-connecting to a specific blackboard category? (-1 is no)
     * @param readPort Create read port?
     * @param writePort Create write port?
     * @param type Data Type of blackboard content
     */
    @SkipArgs("7")
    public BlackboardClient(String description, @CppDefault("NULL") FrameworkElement parent, @CppDefault("true") boolean autoConnect, @CppDefault("-1") int autoConnectCategory, @CppDefault("true") boolean readPort, @CppDefault("true") boolean writePort, @CppDefault("rrlib::serialization::DataType<T>()") DataTypeBase type) {
        wrapped = new RawBlackboardClient(new PortCreationInfo(description, parent, initBlackboardType(type), (writePort ? PortFlags.EMITS_DATA : 0) | (readPort ? PortFlags.ACCEPTS_DATA : 0)), (T)null, autoConnect, autoConnectCategory);
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
    @Inline
    public @CppType("ConstBBVectorVar") PortDataList<T> read(long timeout) {

        // JavaOnlyBlock
        return wrapped.getReadPort().getLockedUnsafeRaw().getObject().getData();

        //Cpp return core::PortUtil<BBVector>::getValueWithLock(wrapped->getReadPort());
    }

    @Inline
    public @CppType("ConstBBVectorVar") PortDataList<T> read() {
        return read(2000);
    }
//
//    /**
//     * same as read(long) with automatic locking of buffer.
//     * (needs to be released by calling ThreadLocalCache.getFast().releaseAllLocks())
//     */
//    @Inline
//    @Const public BlackboardBuffer readAutoLocked(long timeout) {
//        @Const BlackboardBuffer bb = read(timeout);
//        ThreadLocalCache.getFast().addAutoLock(bb);
//        return bb;
//    }
//
//    @Inline
//    @Const public BlackboardBuffer readAutoLocked() {
//        return readAutoLocked(2000);
//    }
//
//
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
    public boolean commitAsynchChange(@CppType("ChangeTransactionVar") PortDataList<T> changeBuf, int index, int offset) throws MethodCallException {

        try {

            //JavaOnlyBlock
            assert(!PortDataManager.getManager(changeBuf).isUnused()) : "Obtain buffer from getUnusedChangeBuffer()";
            AbstractBlackboardServer.ASYNCH_CHANGE.call(wrapped.getWritePort(), changeBuf, index, offset, true);

            /*Cpp
            assert(!changeBuf.getManager()->isUnused() && "Obtain buffer from getUnusedChangeBuffer()");
            AbstractBlackboardServer<T>::ASYNCH_CHANGE.call(*wrapped->getWritePort(), static_cast<ConstChangeTransactionVar&>(changeBuf), index, offset, true);
             */

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
    public @Ptr @CppType("AbstractBlackboardServer<T>::BBVector") PortDataList<T> writeLock(@CppDefault("60000") int timeout) {
        if (timeout <= 0) {
            timeout = 60000; // wait one minute for method to complete if no time is specified
        }

        assert(locked == null && wrapped.lockType == RawBlackboardClient.LockType.NONE);
        assert(wrapped.curLockID == -1);
        assert(wrapped.isReady());
        try {

            @InCpp("BBVectorVar ret = AbstractBlackboardServer<T>::LOCK.call(*wrapped->getWritePort(), timeout, static_cast<int>((timeout + RawBlackboardClient::NET_TIMEOUT)));")
            PortDataList ret = AbstractBlackboardServer.LOCK.call(wrapped.getWritePort(), timeout, (int)(timeout + RawBlackboardClient.NET_TIMEOUT));

            if (ret != null) {
                wrapped.lockType = RawBlackboardClient.LockType.WRITE;

                //JavaOnlyBlock
                wrapped.curLockID = (PortDataManager.getManager(ret)).lockID;
                locked = ret;

                //Cpp wrapped->curLockID = ret.getManager()->lockID;
                //Cpp locked = std::_move(ret);

                // acknowledge lock
                wrapped.sendKeepAlive();
            } else {
                wrapped.curLockID = -1;
            }

            //JavaOnlyBlock
            return locked;

            //Cpp return locked.get();

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
    @JavaOnly
    public @Const @Ptr @CppType("BBVectorVar") PortDataList<T> readLock(@CppDefault("60000") int timeout) {
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
    public @Ptr @Const @CppType("AbstractBlackboardServer<T>::BBVector") PortDataList<T> readLock(@CppDefault("false") boolean forceReadCopyToAvoidBlocking, @CppDefault("60000") int timeout) {
        assert(locked == null && wrapped.lockType == RawBlackboardClient.LockType.NONE) : "Unlock first";
        if (timeout <= 0) {
            timeout = 60000; // wait one minute for method to complete if no time is specified
        }

        // determine whether blackboard server is single buffered
        wrapped.checkSingleBuffered();
        if (wrapped.serverBuffers == RawBlackboardClient.ServerBuffers.UNKNOWN) { // we currently have no partner (?)
            return null;
        }

        boolean viaPort = (wrapped.serverBuffers == RawBlackboardClient.ServerBuffers.MULTI) || wrapped.getReadPort().pushStrategy() || forceReadCopyToAvoidBlocking || wrapped.getWritePort().hasRemoteServer();
        if (viaPort) {
            wrapped.lockType = RawBlackboardClient.LockType.READ;
            wrapped.curLockID = -1;
            readLocked = read(timeout);

            //JavaOnlyBlock
            return readLocked;

            //Cpp return readLocked._get();
        } else {

            assert(locked == null && wrapped.lockType == RawBlackboardClient.LockType.NONE);
            assert(wrapped.curLockID == -1);
            assert(wrapped.isReady());
            try {
                @InCpp("ConstBBVectorVar ret = AbstractBlackboardServer<T>::READ_LOCK.call(*wrapped->getWritePort(), timeout, 0, static_cast<int>((timeout + RawBlackboardClient::NET_TIMEOUT)));")
                PortDataList ret = AbstractBlackboardServer.READ_LOCK.call(wrapped.getWritePort(), timeout, 0, (int)(timeout + RawBlackboardClient.NET_TIMEOUT));

                if (ret != null) {
                    wrapped.lockType = RawBlackboardClient.LockType.READ;

                    //JavaOnlyBlock
                    wrapped.curLockID = (PortDataManager.getManager(ret)).lockID;
                    readLocked = ret;

                    //Cpp wrapped->curLockID = ret.getManager()->lockID;
                    //Cpp readLocked = std::_move(ret);

                    // acknowledge lock
                    wrapped.sendKeepAlive();

                    //JavaOnlyBlock
                    return readLocked;

                    //Cpp return readLocked._get();
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
    @Inline private void resetVariables() {
        wrapped.curLockID = -1;
        wrapped.lockType = RawBlackboardClient.LockType.NONE;

        //JavaOnlyBlock
        locked = null;
        readLocked = null;

        //Cpp locked._reset();
        //Cpp readLocked._reset();
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

                    //JavaOnlyBlock
                    AbstractBlackboardServer.READ_UNLOCK.call(wrapped.getWritePort(), wrapped.curLockID, true);

                    //Cpp AbstractBlackboardServer<T>::READ_UNLOCK.call(*wrapped->getWritePort(), wrapped->curLockID, true);
                } catch (MethodCallException e) {
                    logDomain.log(LogLevel.LL_WARNING, getLogDescription(), "warning: Unlocking blackboard (read) failed", e);
                }
            }

            //JavaOnlyBlock
            PortDataManager.getManager(readLocked).releaseLock();

            resetVariables();
            return;
        }

        assert(wrapped.lockType == RawBlackboardClient.LockType.WRITE);
        assert(wrapped.curLockID >= 0);

        try {

            //JavaOnlyBlock
            AbstractBlackboardServer.UNLOCK.call(wrapped.getWritePort(), locked, true);

            //Cpp AbstractBlackboardServer<T>::UNLOCK.call(*wrapped->getWritePort(), locked, true);
        } catch (MethodCallException e) {
            logDomain.log(LogLevel.LL_WARNING, getLogDescription(), "warning: Unlocking blackboard failed");
            //e.printStackTrace();
        }
        resetVariables();
    }

    /**
     * @return log description
     */
    @InCpp("return *wrapped;")
    @ConstMethod @Const @Ref @CppType("core::FrameworkElement")
    protected String getLogDescription() {
        return wrapped.getLogDescription();
    }

    /**
     * Directly commit/publish buffer - without lock
     *
     * @param buffer Buffer to publish (unlocked buffer retrieved via getUnusedBuffer OR a used buffer with an additional lock)
     */
    public void publish(@CppType("BBVectorVar") PortDataList<T> buffer) {
        assert(wrapped.lockType == RawBlackboardClient.LockType.NONE);

        //JavaOnlyBlock
        assert(!PortDataManager.getManager(buffer).isUnused()) : "Obtain buffer from getUnusedBuffer()";

        //Cpp assert(!buffer.getManager()->isUnused() && "Obtain buffer from getUnusedBuffer()");

        /*if (buffer.getManager().isUnused()) {
            buffer.getManager().getCurrentRefCounter().setLocks((byte)1);
        }*/
        try {

            //JavaOnlyBlock
            AbstractBlackboardServer.DIRECT_COMMIT.call(wrapped.getWritePort(), buffer, true);

            //Cpp AbstractBlackboardServer<T>::DIRECT_COMMIT.call(*wrapped->getWritePort(), buffer, true);
        } catch (MethodCallException e) {
            logDomain.log(LogLevel.LL_WARNING, getLogDescription(), "warning: Blackboard direct commit failed");
        }
    }

    /**
     * @return unused buffer - may be published/committed directly
     */
    @InCpp("return wrapped->getWritePort()->getBufferForCall<BBVector>();")
    public @CppType("BBVectorVar") PortDataList<T> getUnusedBuffer() {
        return wrapped.getWritePort().getBufferForCall(wrapped.getReadPort().getDataType());
    }

    /**
     * @return unused change buffer - to be used in commitAsynchChange
     */
    @InCpp("return wrapped->getWritePort()->getBufferForCall<ChangeTransaction>();")
    public @CppType("ChangeTransactionVar") PortDataList<T> getUnusedChangeBuffer() {
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
     * @return Blackboard name/description
     */
    public String getDescription() {
        return wrapped.getDescription();
    }
}
