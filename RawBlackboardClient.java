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

import org.rrlib.finroc_core_utils.log.LogLevel;
import org.rrlib.finroc_core_utils.rtti.DataTypeBase;
import org.rrlib.finroc_core_utils.serialization.MemoryBuffer;
import org.finroc.core.FrameworkElement;
import org.finroc.core.port.AbstractPort;
import org.finroc.core.port.PortCreationInfo;
import org.finroc.core.port.PortFlags;
import org.finroc.core.port.rpc.InterfaceClientPort;
import org.finroc.core.port.rpc.MethodCallException;
import org.finroc.core.port.std.PortBase;

/**
 * @author Max Reichardt
 *
 * This is the base class for a blackboard client
 */
public class RawBlackboardClient extends FrameworkElement { /*implements ReturnHandler*/

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
                    writePort.connectTo((AbstractPort)w);
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
                    readPort.connectTo((AbstractPort)w);
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

    /** Interface port for write access */
    protected WritePort writePort;

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
    private static PortCreationInfo defaultPci = new PortCreationInfo(MemoryBuffer.TYPE, PortFlags.EMITS_DATA | PortFlags.ACCEPTS_DATA);

    /** Are we client of a SingleBuffered blackboard */
    protected enum ServerBuffers { UNKNOWN, MULTI, SINGLE }
    protected ServerBuffers serverBuffers = ServerBuffers.UNKNOWN;

    /**
     * @return Default ProtCreationInfo for Blackboard clients (creates both read write ports)
     */
    public static PortCreationInfo getDefaultPci() {
        return defaultPci;
    }

    /**
     * @param pci PortCreationInfo (relevant info: name (blackboard name), parent (of client), type (data type of blackboard content),
     * flags (emit data => write port, accept data => read port
     * @param autoConnect Auto-Connect blackboard client to matching server?
     * @param autoConnectCategory If auto-connect is active: Limit auto-connecting to a specific blackboard category? (-1 is no)
     */
    <T> RawBlackboardClient(PortCreationInfo pci, T t, boolean autoConnect, int autoConnectCategory) {
        super(pci.parent, pci.name);
        AbstractBlackboardServerRaw.checkType(pci.dataType);
        readPort = pci.getFlag(PortFlags.ACCEPTS_DATA) ? new ReadPort(new PortCreationInfo("read", this, pci.dataType.getListType(), PortFlags.ACCEPTS_DATA | (pci.flags & PortFlags.PUSH_STRATEGY))) : null;
        writePort = pci.getFlag(PortFlags.EMITS_DATA) ? new WritePort(AbstractBlackboardServer.getBlackboardTypeInfo(pci.dataType).blackboardType) : null ;
        this.autoConnect = autoConnect;
        this.autoConnectCategory = autoConnectCategory;
    }

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
        if (!getName().equals(server.getName())) {
            return false; // names don't match
        }

        // checks passed => connect
        if (readPort != null) {
            readPort.connectTo(server.readPortRaw);
        }
        if (writePort != null) {
            writePort.connectTo(server.writePortRaw);
        }
        return true;
    }

    /**
     * (relevant mainly for auto-connect)
     *
     * @return Is client connected to blackboard server?
     */
    public boolean isConnected() {
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
