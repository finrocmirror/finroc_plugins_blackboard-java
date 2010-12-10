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

import org.finroc.jc.annotation.AtFront;
import org.finroc.jc.annotation.Const;
import org.finroc.jc.annotation.ConstMethod;
import org.finroc.jc.annotation.CppDefault;
import org.finroc.jc.annotation.InCpp;
import org.finroc.jc.annotation.Inline;
import org.finroc.jc.annotation.JavaOnly;
import org.finroc.log.LogLevel;
import org.finroc.core.FrameworkElement;
import org.finroc.core.port.AbstractPort;
import org.finroc.core.port.PortCreationInfo;
import org.finroc.core.port.PortFlags;
import org.finroc.core.port.ThreadLocalCache;
import org.finroc.core.port.rpc.InterfaceClientPort;
import org.finroc.core.port.rpc.MethodCallException;
import org.finroc.core.port.std.PortBase;
import org.finroc.core.portdatabase.DataType;

/**
 * @author max
 *
 * This is the base class for a blackboard client
 */
public class RawBlackboardClient extends FrameworkElement { /*implements ReturnHandler*/

    @AtFront
    /** Special blackboard client read port - when connected, connect write port also */
    public class ReadPort extends PortBase {

        public ReadPort(PortCreationInfo pci) {
            super(pci);
        }

        @Override
        protected void newConnection(AbstractPort partner) {
            super.newConnection(partner);
            if (writePort != null) {
                FrameworkElement w = partner.getParent().getChild("write");
                if (w != null) {
                    writePort.connectToSource((AbstractPort)w);
                }
            }
            serverBuffers = ServerBuffers.UNKNOWN;
        }

        public RawBlackboardClient getBBClient() {
            return RawBlackboardClient.this;
        }

        @Override
        protected void connectionRemoved(AbstractPort partner) {
            serverBuffers = ServerBuffers.UNKNOWN;
        }
    }

    @AtFront
    /** Special blackboard client write port - when connected, connect read port also */
    public class WritePort extends InterfaceClientPort {

        public WritePort(DataType type) {
            super("write", RawBlackboardClient.this, type);
        }

        @Override
        protected void newConnection(AbstractPort partner) {
            super.newConnection(partner);
            if (readPort != null) {
                FrameworkElement w = partner.getParent().getChild("read");
                if (w != null) {
                    readPort.connectToSource((AbstractPort)w);
                }
            }
            serverBuffers = ServerBuffers.UNKNOWN;
        }

        public RawBlackboardClient getBBClient() {
            return RawBlackboardClient.this;
        }

        @Override
        protected void connectionRemoved(AbstractPort partner) {
            serverBuffers = ServerBuffers.UNKNOWN;
        }
    }

    /** Default network timeout (added to any other timeouts for network calls) */
    public int NET_TIMEOUT = 1000;

    /** valid IDs of methods */
    //public static final byte LOCK = 1, ASYNCH_CHANGE = 2, UNLOCK = 3, READ_PART = 4, DIRECT_COMMIT = 5, DEPRECATED_DIRECT_BUFFER_ACCESS = 6, IS_SINGLE_BUFFERED = 7;

    /** Interface port for write access */
    protected WritePort writePort;

    /** Port for reading */
    protected ReadPort readPort;

    /** not null - if buffer is currently locked for writing */
    protected BlackboardBuffer locked;

    /** not null - if buffer is currently locked for writing */
    @Const protected BlackboardBuffer readLocked;

    public enum LockType { NONE, READ, WRITE }

    /** Is there currently a lock? */
    protected LockType lockType = LockType.NONE;

    /** ID of current locking operation */
    protected volatile int curLockID = -1;

    /** Auto-Connect blackboard client to matching server? */
    protected final boolean autoConnect;

    /** If auto-connect is active: Limit auto-connecting to a specific blackboard category? (-1 is no) */
    protected final int autoConnectCategory;

    /** Default ProtCreationInfo for Blackboard clients (creates both read write ports) */
    @JavaOnly
    private static PortCreationInfo defaultPci = new PortCreationInfo(BlackboardBuffer.BUFFER_TYPE, PortFlags.EMITS_DATA | PortFlags.ACCEPTS_DATA);

    /** Are we client of a SingleBuffered blackboard */
    private enum ServerBuffers { UNKNOWN, MULTI, SINGLE }
    private ServerBuffers serverBuffers = ServerBuffers.UNKNOWN;

    /**
     * @return Default ProtCreationInfo for Blackboard clients (creates both read write ports)
     */
    @InCpp( {"static core::PortCreationInfo defaultPci(BlackboardBuffer::cBUFFER_TYPE, core::PortFlags::EMITS_DATA | core::PortFlags::ACCEPTS_DATA);",
             "return defaultPci;"
            })
    public static PortCreationInfo getDefaultPci() {
        return defaultPci;
    }

    /**
     * @param pci PortCreationInfo (relevant info: description (blackboard name), parent (of client), type (data type of blackboard content),
     * flags (emit data => write port, accept data => read port
     * @param autoConnect Auto-Connect blackboard client to matching server?
     * @param autoConnectCategory If auto-connect is active: Limit auto-connecting to a specific blackboard category? (-1 is no)
     */
    public RawBlackboardClient(PortCreationInfo pci, @CppDefault("true") boolean autoConnect, @CppDefault("-1") int autoConnectCategory) {
        super(pci.parent, pci.description);
        AbstractBlackboardServer.checkType(pci.dataType);
        readPort = pci.getFlag(PortFlags.ACCEPTS_DATA) ? new ReadPort(new PortCreationInfo("read", this, pci.dataType, PortFlags.ACCEPTS_DATA | (pci.flags & PortFlags.PUSH_STRATEGY))) : null;
        writePort = pci.getFlag(PortFlags.EMITS_DATA) ? new WritePort(pci.dataType.getRelatedType()) : null ;
        this.autoConnect = autoConnect;
        this.autoConnectCategory = autoConnectCategory;
    }

    @JavaOnly
    public RawBlackboardClient(PortCreationInfo pci) {
        this(pci, true, -1);
    }

    @Override
    protected void postChildInit() {
        super.postChildInit();
        assert(BlackboardManager.getInstance() != null) : "truly strange";
        BlackboardManager.getInstance().addClient(this, autoConnect);
    }

    @Override
    protected void prepareDelete() {
        BlackboardManager instance = BlackboardManager.getInstance();
        if (instance != null) { // we don't need to remove it, if blackboard manager has already been deleted
            instance.removeClient(this);
        }
        super.prepareDelete();
    }

    /**
     * Check whether this auto-connecting client wants to auto-connect to this server
     *
     * @param server Server that is a candidate for connecting
     * @return True if client is connected
     */
    public boolean checkConnect(AbstractBlackboardServer server) {
        assert(autoConnect);
        if (server.readPort == null || server.writePort == null) {
            return false;
        }
        if (isConnected()) {
            return true; // already connected
        }
        if (autoConnectCategory >= 0 && autoConnectCategory != server.categoryIndex) {
            return false; // wrong category
        }
        if (readPort != null && server.readPort.getDataType() != readPort.getDataType()) {
            return false; // data types don't fit
        }
        if (writePort != null && server.writePort.getDataType() != writePort.getDataType()) {
            return false; // data types don't fit
        }
        if (!getDescription().equals(server.getDescription())) {
            return false; // descriptions don't match
        }

        // checks passed => connect
        if (readPort != null) {
            readPort.connectToSource(server.readPort);
        }
        if (writePort != null) {
            writePort.connectToSource(server.writePort);
        }
        return true;
    }

    /**
     * (relevant mainly for auto-connect)
     *
     * @return Is client connected to blackboard server?
     */
    @ConstMethod public boolean isConnected() {
        boolean w = (writePort == null) ? true : writePort.isConnected();
        return w && readPort.isConnected();
    }

    /**
     * Often Non-blocking, safe blackboard read operation
     * (will always operate on read copy - for SingleBufferedBlackboardServers readLock can be more efficient, but also more blocking)
     *
     * @param timeout (relevant for SingleBufferedBlackboardClients only) Timeout for lock attempt
     * @return Raw memory buffer containing blackboard contents - locked - don't forget to release read lock
     */
    @Inline
    @Const public BlackboardBuffer read(long timeout) {

        // JavaOnlyBlock
        return (BlackboardBuffer)readPort.getLockedUnsafeRaw();

        //Cpp return static_cast<const BlackboardBuffer*>(readPort->getLockedUnsafeRaw());
    }

    @Inline
    @Const public BlackboardBuffer read() {
        return read(2000);
    }

    /**
     * same as read(long) with automatic locking of buffer.
     * (needs to be released by calling ThreadLocalCache.getFast().releaseAllLocks())
     */
    @Inline
    @Const public BlackboardBuffer readAutoLocked(long timeout) {
        @Const BlackboardBuffer bb = read(timeout);
        ThreadLocalCache.getFast().addAutoLock(bb);
        return bb;
    }

    @Inline
    @Const public BlackboardBuffer readAutoLocked() {
        return readAutoLocked(2000);
    }


    /**
     * Commit asynchronous change to blackboard. Blackboard does
     * not need to be locked for this operation.
     * (if connection is broken, there's no guarantee that this will work or that an exception is thrown otherwise)
     *
     * @param offset Offset in byte in blackboard
     * @param changeBuf Contents to write to this position (unlocked buffer retrieved via getUnusedBuffer OR a used buffer with an additional lock)
     */
    public void commitAsynchChange(int offset, @Const BlackboardBuffer changeBuf) throws MethodCallException {
        if (changeBuf.getManager().isUnused()) {
            changeBuf.getManager().getCurrentRefCounter().setLocks((byte)1);
        }
        AbstractBlackboardServer.ASYNCH_CHANGE.call(writePort, offset, changeBuf, true);
    }

    /**
     * Read part of blackboard
     *
     * @param offset offset in byte
     * @param length length in byte
     * @param timeout timeout for this synchronous operation
     * @return Lock Locked buffer - or null if operation failed (position 0 in this buffer is position 'offset' in original one)
     *  is unlocked automatically
     */
    public BlackboardBuffer readPart(int offset, int length, @CppDefault("60000") int timeout) {
        if (timeout <= 0) {
            timeout = 60000; // wait one minute for method to complete if no time is specified
        }
        try {
            return AbstractBlackboardServer.READ_PART.call(writePort, offset, length, timeout, timeout + NET_TIMEOUT);
        } catch (MethodCallException e) {
            return null;
        }
    }

    /**
     * Lock blackboard in order to read and commit changes
     * (synchronous... therefore deprecated if not absolutely necessary)
     *
     * @param timeout timeout for lock
     * @return Lock Locked buffer - or null if lock failed - this buffer may be modified -
     * call unlock() after modifications are complete - locks of buffer should normally not be modified -
     * except of it should be used in some other port or stored for longer than the unlock() operation
     */
    public BlackboardBuffer writeLock(@CppDefault("60000") long timeout) {
        if (timeout <= 0) {
            timeout = 60000; // wait one minute for method to complete if no time is specified
        }

        assert(locked == null && lockType == LockType.NONE);
        assert(curLockID == -1);
        assert(isReady());
        try {
            BlackboardBuffer ret = AbstractBlackboardServer.LOCK.call(writePort, timeout, (int)(timeout + NET_TIMEOUT));
            if (ret != null) {
                this.lockType = LockType.WRITE;
                curLockID = ret.lockID;
                locked = ret;

                // acknowledge lock
                sendKeepAlive();
            } else {
                curLockID = -1;
            }
            return ret;
        } catch (MethodCallException e) {
            curLockID = -1;
            return null;
        }
    }

    /**
     * Send keep-alive signal to server (usually done automatically...)
     */
    @SuppressWarnings("unchecked")
    public void sendKeepAlive() {
        int curLockID = this.curLockID;
        if (curLockID >= 0) {
            try {
                AbstractBlackboardServer.KEEP_ALIVE.call(writePort, curLockID, false);
            } catch (MethodCallException e) {
                log(LogLevel.LL_WARNING, logDomain, "warning: Sending Keep-Alive failed");
            }
        }
    }

    /** Check whether we are dealing with a single buffered blackboard server */
    private void checkSingleBuffered() {
        if (serverBuffers != ServerBuffers.UNKNOWN) {
            return;
        }
        try {
            Byte result = AbstractBlackboardServer.IS_SINGLE_BUFFERED.call(writePort, NET_TIMEOUT);
            serverBuffers = (result == 0) ? ServerBuffers.MULTI : ServerBuffers.SINGLE;
        } catch (MethodCallException e) {
            serverBuffers = ServerBuffers.UNKNOWN;
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
    @Const public BlackboardBuffer readLock(@CppDefault("60000") long timeout) {
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
    public @Const BlackboardBuffer readLock(@CppDefault("false") boolean forceReadCopyToAvoidBlocking, @CppDefault("60000") long timeout) {
        assert(locked == null && lockType == LockType.NONE) : "Unlock first";
        if (timeout <= 0) {
            timeout = 60000; // wait one minute for method to complete if no time is specified
        }

        // determine whether blackboard server is single buffered
        checkSingleBuffered();
        if (serverBuffers == ServerBuffers.UNKNOWN) { // we currently have no partner (?)
            return null;
        }

        boolean viaPort = (serverBuffers == ServerBuffers.MULTI) || readPort.pushStrategy() || forceReadCopyToAvoidBlocking || writePort.hasRemoteServer();
        if (viaPort) {
            lockType = LockType.READ;
            curLockID = -1;
            return (readLocked = read(timeout));
        } else {

            assert(locked == null && lockType == LockType.NONE);
            assert(curLockID == -1);
            assert(isReady());
            try {
                @Const BlackboardBuffer ret = AbstractBlackboardServer.READ_LOCK.call(writePort, timeout, 0, (int)(timeout + NET_TIMEOUT));
                if (ret != null) {
                    this.lockType = LockType.READ;
                    curLockID = ret.lockID;
                    readLocked = ret;

                    // acknowledge lock
                    sendKeepAlive();
                } else {
                    curLockID = -1;
                }
                return ret;
            } catch (MethodCallException e) {
                curLockID = -1;
                return null;
            }
        }
    }

    /**
     * Commit changes of previously locked buffer
     */
    @SuppressWarnings("unchecked")
    public void unlock() {
        if (lockType == LockType.NONE) {
            log(LogLevel.LL_WARNING, logDomain, "BlackboardClient warning: nothing to unlock");
            curLockID = -1;
            lockType = LockType.NONE;
            locked = null;
            readLocked = null;
            return; // nothing to unlock
        }
        if (lockType == LockType.READ) {

            // we only have a read copy
            assert(readLocked != null);
            if (curLockID >= 0) {
                try {
                    AbstractBlackboardServer.READ_UNLOCK.call(writePort, curLockID, true);
                } catch (MethodCallException e) {
                    log(LogLevel.LL_WARNING, logDomain, "warning: Unlocking blackboard (read) failed", e);
                }
            }
            readLocked.getManager().releaseLock();
            locked = null;
            readLocked = null;
            curLockID = -1;
            lockType = LockType.NONE;
            return;
        }

        assert(lockType == LockType.WRITE);
        assert(curLockID >= 0);

        try {
            AbstractBlackboardServer.UNLOCK.call(writePort, locked, true);
        } catch (MethodCallException e) {
            log(LogLevel.LL_WARNING, logDomain, "warning: Unlocking blackboard failed");
            //e.printStackTrace();
        }
        locked = null;
        readLocked = null;
        curLockID = -1;
        lockType = LockType.NONE;
    }

    /**
     * Directly commit/publish buffer - without lock
     *
     * @param buffer Buffer to publish (unlocked buffer retrieved via getUnusedBuffer OR a used buffer with an additional lock)
     */
    @SuppressWarnings("unchecked")
    public void publish(BlackboardBuffer buffer) {
        assert(lockType == LockType.NONE);
        if (buffer.getManager().isUnused()) {
            buffer.getManager().getCurrentRefCounter().setLocks((byte)1);
        }
        try {
            AbstractBlackboardServer.DIRECT_COMMIT.call(writePort, buffer, true);
        } catch (MethodCallException e) {
            log(LogLevel.LL_WARNING, logDomain, "warning: Blackboard direct commit failed");
        }
    }

    /**
     * @return unused buffer - may be published/committed directly
     */
    public BlackboardBuffer getUnusedBuffer() {
        return (BlackboardBuffer)writePort.getUnusedBuffer(readPort.getDataType());
    }

    public boolean hasWriteLock() {
        return lockType == LockType.WRITE;
    }

    public boolean hasReadLock() {
        return lockType == LockType.READ;
    }

    public boolean hasLock() {
        return lockType != LockType.NONE;
    }

    public WritePort getWritePort() {
        return writePort;
    }

    public ReadPort getReadPort() {
        return readPort;
    }

    /**
     * @return Auto-connect client?
     */
    public boolean autoConnectClient() {
        return autoConnect;
    }
}
