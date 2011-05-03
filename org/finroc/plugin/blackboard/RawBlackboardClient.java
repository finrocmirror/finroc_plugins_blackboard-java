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
import org.finroc.jc.annotation.ConstMethod;
import org.finroc.jc.annotation.CppDefault;
import org.finroc.jc.annotation.Friend;
import org.finroc.jc.annotation.InCpp;
import org.finroc.jc.annotation.IncludeClass;
import org.finroc.jc.annotation.Init;
import org.finroc.jc.annotation.Inline;
import org.finroc.jc.annotation.JavaOnly;
import org.finroc.jc.annotation.Ptr;
import org.finroc.jc.annotation.SharedPtr;
import org.finroc.log.LogLevel;
import org.finroc.serialization.DataTypeBase;
import org.finroc.serialization.MemoryBuffer;
import org.finroc.core.FrameworkElement;
import org.finroc.core.port.AbstractPort;
import org.finroc.core.port.PortCreationInfo;
import org.finroc.core.port.PortFlags;
import org.finroc.core.port.rpc.InterfaceClientPort;
import org.finroc.core.port.rpc.MethodCallException;
import org.finroc.core.port.std.PortBase;

/**
 * @author max
 *
 * This is the base class for a blackboard client
 */
@Friend(BlackboardClient.class)
@IncludeClass( {MemoryBuffer.class, AbstractBlackboardServer.class})
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

        public WritePort(DataTypeBase type) {
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
    public static final int NET_TIMEOUT = 1000;

    /*Cpp

    //Pointers to methods to use
    template <typename T>
    static int8 callIsSingleBuffered(WritePort* port) {
        return AbstractBlackboardServer<T>::IS_SINGLE_BUFFERED.call(*port, NET_TIMEOUT);
    }

    template <typename T>
    static void callKeepAlive(WritePort* port, int lockid) {
        AbstractBlackboardServer<T>::KEEP_ALIVE.call(*port, lockid, false);
    }

    _int8 (*isSingleBufferedFunc)(WritePort*);
    _void (*keepAliveFunc)(WritePort*, int);
     */

    /** Interface port for write access */
    protected @SharedPtr WritePort writePort;

    /** Port for reading */
    protected ReadPort readPort;

//    /** not null - if buffer is currently locked for writing */
//    protected BlackboardBuffer locked;
//
//    /** not null - if buffer is currently locked for writing */
//    @Const protected BlackboardBuffer readLocked;

    public enum LockType { NONE, READ, WRITE }

    /** Is there currently a lock? */
    protected LockType lockType = LockType.NONE;

    /** ID of current locking operation */
    protected volatile int curLockID = -1;

    /** Auto-Connect blackboard client to matching server? */
    protected final boolean autoConnect;

    /** If auto-connect is active: Limit auto-connecting to a specific blackboard category? (-1 is no) */
    protected final int autoConnectCategory;

    /** Default PortCreationInfo for Blackboard clients (creates both read write ports) */
    @JavaOnly
    private static PortCreationInfo defaultPci = new PortCreationInfo(MemoryBuffer.TYPE, PortFlags.EMITS_DATA | PortFlags.ACCEPTS_DATA);

    /** Are we client of a SingleBuffered blackboard */
    protected enum ServerBuffers { UNKNOWN, MULTI, SINGLE }
    protected ServerBuffers serverBuffers = ServerBuffers.UNKNOWN;

    /**
     * @return Default ProtCreationInfo for Blackboard clients (creates both read write ports)
     */
    @InCpp( {"static core::PortCreationInfo defaultPci(rrlib::serialization::MemoryBuffer::TYPE, core::PortFlags::EMITS_DATA | core::PortFlags::ACCEPTS_DATA);",
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
    @Inline
    @Init( {"writePort(pci.getFlag(core::PortFlags::EMITS_DATA) ? AbstractBlackboardServerRaw::getBlackboardTypeInfo(pci.dataType)->blackboardType) : NULL)",
            "isSingleBufferedFunc(_M_callIsSingleBuffered<T>)",
            "keepAliveFunc(_M_callKeepAlive<T>)"
           })
    <T> RawBlackboardClient(PortCreationInfo pci, @Ptr T t, @CppDefault("true") boolean autoConnect, @CppDefault("-1") int autoConnectCategory) {
        super(pci.parent, pci.description);
        AbstractBlackboardServerRaw.checkType(pci.dataType);
        readPort = pci.getFlag(PortFlags.ACCEPTS_DATA) ? new ReadPort(new PortCreationInfo("read", this, pci.dataType.getListType(), PortFlags.ACCEPTS_DATA | (pci.flags & PortFlags.PUSH_STRATEGY))) : null;
        writePort = pci.getFlag(PortFlags.EMITS_DATA) ? new WritePort(AbstractBlackboardServer.getBlackboardTypeInfo(pci.dataType).blackboardType) : null ;
        this.autoConnect = autoConnect;
        this.autoConnectCategory = autoConnectCategory;
    }

    @JavaOnly
    public RawBlackboardClient(PortCreationInfo pci) {
        this(pci, null, true, -1);
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
    public boolean checkConnect(AbstractBlackboardServerRaw server) {
        assert(autoConnect);
        if (server.readPortRaw == null || server.writePortRaw == null) {
            return false;
        }
        if (isConnected()) {
            return true; // already connected
        }
        if (autoConnectCategory >= 0 && autoConnectCategory != server.categoryIndex) {
            return false; // wrong category
        }
        if (readPort != null && server.readPortRaw.getDataType() != readPort.getDataType()) {
            return false; // data types don't fit
        }
        if (writePort != null && server.writePortRaw.getDataType() != writePort.getDataType()) {
            return false; // data types don't fit
        }
        if (!getDescription().equals(server.getDescription())) {
            return false; // descriptions don't match
        }

        // checks passed => connect
        if (readPort != null) {
            readPort.connectToSource(server.readPortRaw);
        }
        if (writePort != null) {
            writePort.connectToSource(server.writePortRaw);
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
     * Send keep-alive signal to server (usually done automatically...)
     */
    @SuppressWarnings("unchecked")
    public void sendKeepAlive() {
        int curLockID = this.curLockID;
        if (curLockID >= 0) {
            try {

                //JavaOnlyBlock
                AbstractBlackboardServer.KEEP_ALIVE.call(writePort, curLockID, false);

                //Cpp (*keepAliveFunc)(write_port._get(), curLockID);

            } catch (MethodCallException e) {
                log(LogLevel.LL_WARNING, logDomain, "warning: Sending Keep-Alive failed");
            }
        }
    }

    /** Check whether we are dealing with a single buffered blackboard server */
    protected void checkSingleBuffered() {
        if (serverBuffers != ServerBuffers.UNKNOWN) {
            return;
        }
        try {
            @InCpp("int8 result = (*isSingleBufferedFunc)(write_port._get());")
            Byte result = AbstractBlackboardServer.IS_SINGLE_BUFFERED.call(writePort, NET_TIMEOUT);
            serverBuffers = (result == 0) ? ServerBuffers.MULTI : ServerBuffers.SINGLE;
        } catch (MethodCallException e) {
            serverBuffers = ServerBuffers.UNKNOWN;
        }
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

    public @Ptr WritePort getWritePort() {
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
