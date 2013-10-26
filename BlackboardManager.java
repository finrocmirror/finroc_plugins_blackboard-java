//
// You received this file as part of Finroc
// A framework for intelligent robot control
//
// Copyright (C) Finroc GbR (finroc.org)
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along
// with this program; if not, write to the Free Software Foundation, Inc.,
// 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
//
//----------------------------------------------------------------------
package org.finroc.plugins.blackboard;

import org.rrlib.finroc_core_utils.jc.ArrayWrapper;
import org.rrlib.finroc_core_utils.jc.container.SafeConcurrentlyIterableList;
import org.rrlib.finroc_core_utils.jc.container.SimpleListWithMutex;
import org.rrlib.finroc_core_utils.jc.thread.ThreadUtil;
import org.rrlib.finroc_core_utils.rtti.DataTypeBase;
import org.finroc.core.FrameworkElement;
import org.finroc.core.LockOrderLevels;
import org.finroc.core.RuntimeEnvironment;
import org.finroc.core.RuntimeListener;
import org.finroc.core.plugin.Plugins;
import org.finroc.core.port.AbstractPort;
import org.finroc.core.port.PortCreationInfo;
import org.finroc.core.port.rpc.InterfacePort;
import org.finroc.core.port.std.PortBase;
import org.finroc.core.thread.CoreLoopThreadBase;

/**
 * @author Max Reichardt
 *
 * Blackboard Manager (has similar tasks as blackboard handler in MCA2)
 *
 * is also framework element that groups blackboard servers
 */
public class BlackboardManager extends FrameworkElement implements RuntimeListener {

    /**
     * Categories of blackboards - same as in MCA2 - questionable if that makes sense
     * Is, however, the easiest & most efficient way to remain compatible
     */
    public static final int ALL = -1, SHARED = 0, LOCAL = 1, REMOTE = 2, DIMENSION = 3;

    /** Name of Blackboard Manager Framework Element */
    public static final String NAME = "Blackboards";

    /** Cached Value: Name with slashes */
    public static final String SLASHED_NAME = "/" + NAME + "/";

    /** Name of read and write ports */
    public static final String READ_PORT_NAME = "read", WRITE_PORT_NAME = "write";

    /** The same with prepended slahes */
    public static final String READ_POSTFIX = "/" + READ_PORT_NAME, WRITE_POSTFIX = "/" + WRITE_PORT_NAME;

    /**
     * Class that stores infos for one category of blackboards
     */
    class BlackboardCategory extends FrameworkElement {

        /** Default flags for AbstractBlackboardServers in this category */
        public final int defaultFlags;

        /** List of blackboards in category */
        final SafeConcurrentlyIterableList<AbstractBlackboardServerRaw> blackboards = new SafeConcurrentlyIterableList<AbstractBlackboardServerRaw>(100, 4);

        /**
         * @param categoryName Name of category
         * @param Default flags for AbstractBlackboardServers in this category
         */
        BlackboardCategory(String categoryName, int defaultFlags) {
            super(BlackboardManager.this, categoryName, defaultFlags, -1);
            this.defaultFlags = defaultFlags;
        }

        /**
         * Add Blackboard to this category
         *
         * @param blackboard Blackboard to add
         */
        void add(AbstractBlackboardServerRaw blackboard) {
            synchronized (autoConnectClients) {
                blackboards.add(blackboard, false);
                checkAutoConnect(blackboard);
            }
        }

        /**
         * Remove blackboard from this category
         *
         * @param blackboard Blackboard to remove
         */
        void remove(AbstractBlackboardServerRaw blackboard) {
            synchronized (autoConnectClients) {
                blackboards.remove(blackboard);
            }
        }

        /**
         * Check whether blackboard client wants to connect to any contained blackboards
         *
         * @param client Blackboard client
         */
        void checkConnect(RawBlackboardClient client) {
            ArrayWrapper<AbstractBlackboardServerRaw> it = blackboards.getIterable();
            for (int i = 0; i < it.size(); i++) {
                AbstractBlackboardServerRaw info = it.get(i);
                if (client.checkConnect(info)) {
                    return;
                }
            }
        }
    }

    /** Singleton instance */
    private static volatile BlackboardManager instance;

    /** Blackboard categories */
    private final BlackboardCategory[] categories = new BlackboardCategory[DIMENSION];

    /** Temporary StringBuilder */
    private StringBuilder tempBuffer = new StringBuilder();

    /** all blackboard clients */
    private SafeConcurrentlyIterableList<RawBlackboardClient> bbClients = new SafeConcurrentlyIterableList<RawBlackboardClient>(10, 4);

    /** Clients that wish to autoconnect */
    private SimpleListWithMutex<RawBlackboardClient> autoConnectClients = new SimpleListWithMutex<RawBlackboardClient>(LockOrderLevels.INNER_MOST - 50);

    private BlackboardManager() {
        super(RuntimeEnvironment.getInstance(), NAME);
        categories[LOCAL] = new BlackboardCategory("Local", 0);
        categories[SHARED] = new BlackboardCategory("Shared", Flag.SHARED | Flag.GLOBALLY_UNIQUE_LINK);
        categories[REMOTE] = new BlackboardCategory("Remote", Flag.NETWORK_ELEMENT);
        LockCheckerThread checker = new LockCheckerThread();
        ThreadUtil.setAutoDelete(checker);
        checker.start();
    }

    /**
     * @return Singleton instance - may be null after blackboard manager has been deleted
     */
    public static BlackboardManager getInstance() {
        if (instance == null && (!RuntimeEnvironment.shuttingDown())) {
            createBlackboardManager(); // should be okay with respect to double-checked locking
        }
        return instance;
    }

    /**
     * Synchronized helper method
     */
    private static void createBlackboardManager() {
        synchronized (RuntimeEnvironment.getInstance().getRegistryLock()) {
            if (instance == null) {
                instance = new BlackboardManager();
                instance.init();
                RuntimeEnvironment.getInstance().addListener(instance);

                // TODO do this properly
                Plugins.getInstance().addPlugin(new BlackboardPlugin());
                ////Cpp core::Plugins::getInstance()->addPlugin(new Blackboard2Plugin());
            }
        }
    }

    /**
     * Get blackboard matching the specified features
     *
     * @param name Blackboard name
     * @param category Blackboard Category (-1 all categories)
     * @param type Data type of blackboard (null = all types)
     * @return Blackboard - or null if no blackboard could be found
     */
    public AbstractBlackboardServerRaw getBlackboard(String name, int category, DataTypeBase type) {
        int startCat = category < 0 ? 0 : category;
        int endCat = category < 0 ? DIMENSION - 1 : startCat;
        return getBlackboard(name, startCat, endCat, type);
    }

    /**
     * Get blackboard matching the specified features
     *
     * @param name Blackboard name
     * @param startCat category index to start looking (inclusive)
     * @param endCat end category index (inclusive)
     * @param type Data type of blackboard (null = all types)
     * @return Blackboard - or null if no blackboard could be found
     */
    public AbstractBlackboardServerRaw getBlackboard(String name, int startCat, int endCat, DataTypeBase type) {
        if (type.getListType() != null) {
            type = type.getListType();
        }
        for (int c = startCat; c <= endCat; c++) {
            BlackboardCategory cat = categories[c];
            ArrayWrapper<AbstractBlackboardServerRaw> it = cat.blackboards.getIterable();
            for (int i = 0; i < it.size(); i++) {
                AbstractBlackboardServerRaw info = it.get(i);
                if (info.getName().equals(name) && (type == null || info.readPortRaw.getDataType() == type)) {
                    return info;
                }
            }
        }
        return null;
    }

    /**
     * Retrieve blackboard with specified index
     *
     * @param index Index
     * @param category Index in which category? (-1 all)
     * @return Blackboard - or null, if it does not exist (can happen, because lists are not filled continuously when blackboards are deleted)
     */
    public AbstractBlackboardServerRaw getBlackboard(int index, int category) {
        int startCat = category < 0 ? 0 : category;
        int endCat = category < 0 ? DIMENSION - 1 : startCat;
        return getBlackboard(index, startCat, endCat);
    }

    /**
     * Retrieve blackboard with specified index
     *
     * @param index Index
     * @param startCat category index to start looking (inclusive)
     * @param endCat end category index (inclusive)
     * @return Blackboard - or null, if it does not exist (can happen, because lists are not filled continuously when blackboards are deleted)
     */
    public AbstractBlackboardServerRaw getBlackboard(int index, int startCat, int endCat) {
        for (int c = startCat; c <= endCat; c++) {
            BlackboardCategory cat = categories[c];
            ArrayWrapper<AbstractBlackboardServerRaw> it = cat.blackboards.getIterable();
            if (index >= it.size()) {
                index -= it.size();
                continue;
            }
            return it.get(index);
        }
        return null;
    }

    /**
     * Retrieve number of blackboards (may include empty entries, if blackboards have been deleted)
     *
     * @param category Index in which category? (-1 all)
     */
    public int getNumberOfBlackboards(int category) {
        int startCat = category < 0 ? 0 : category;
        int endCat = category < 0 ? DIMENSION - 1 : startCat;
        return getNumberOfBlackboards(startCat, endCat);
    }

    /**
     * Retrieve number of blackboards (may include empty entries, if blackboards have been deleted)
     *
     * @param startCat category index to start looking (inclusive)
     * @param endCat end category index (inclusive)
     */
    public int getNumberOfBlackboards(int startCat, int endCat) {
        int result = 0;
        for (int c = startCat; c <= endCat; c++) {
            result += categories[c].blackboards.size();
        }
        return result;
    }

    @Override
    public void runtimeChange(byte changeType, FrameworkElement element) {

        if (changeType == RuntimeListener.ADD /*|| changeType == RuntimeListener.REMOVE || changeType == RuntimeListener.PRE_INIT*/) {

            // Is this a remote blackboard? -> Create proxy
            if (element.getFlag(Flag.NETWORK_ELEMENT) && element.getFlag(Flag.PORT) && (!element.isChildOf(this))) {
                element.getQualifiedLink(tempBuffer);
                String qname = tempBuffer.toString();
                String name = getBlackboardNameFromQualifiedLink(tempBuffer.toString());
                boolean read = qname.endsWith(READ_POSTFIX);
                boolean write = qname.endsWith(WRITE_POSTFIX);

                if (name.length() > 0) {
                    AbstractBlackboardServerRaw info = getBlackboard(name, REMOTE, null);

                    // okay create blackboard proxy
                    boolean add = (info == null);
                    if (add) {
                        info = new RemoteBlackboardServer(name);
                    }
                    if (read && info.readPortRaw == null) {
                        PortBase port = (PortBase)element;
                        info.readPortRaw = new PortBase(new PortCreationInfo(READ_PORT_NAME, info, port.getDataType(), Flag.OUTPUT_PROXY | Flag.NETWORK_ELEMENT));
                        info.init();
                        info.readPortRaw.connectTo(qname, AbstractPort.ConnectDirection.TO_SOURCE, false);
                    } else if (write && info.writePortRaw == null) {
                        InterfacePort port = (InterfacePort)element;
                        info.writePortRaw = new InterfacePort(WRITE_PORT_NAME, info, port.getDataType(), InterfacePort.Type.Routing, Flag.NETWORK_ELEMENT);
                        info.init();
                        info.writePortRaw.connectTo(qname, AbstractPort.ConnectDirection.TO_TARGET, false);
                    }
                    checkAutoConnect(info);
                }
            }
        }
    }

    @Override
    public void runtimeEdgeChange(byte changeType, AbstractPort source, AbstractPort target) {
        // do nothing
    }

    /**
     * Check whether any of the autoConnectClients wishes to connect
     *
     * @param server new server to check with
     */
    private void checkAutoConnect(AbstractBlackboardServerRaw server) {
        if (server.readPortRaw == null || server.writePortRaw == null) {
            return;
        }
        synchronized (autoConnectClients) {
            for (int i = 0; i < autoConnectClients.size(); i++) {
                autoConnectClients.get(i).checkConnect(server);
            }
        }
    }

    /**
     * @param qname qualified link
     * @return Blackboard name - empty string if no blackboard
     */
    public String getBlackboardNameFromQualifiedLink(String qname) {
        boolean b = qname.startsWith(SLASHED_NAME);
        if (b && (qname.endsWith(READ_POSTFIX) || qname.endsWith(WRITE_POSTFIX))) {
            String qname2 = qname.substring(0, qname.lastIndexOf("/"));
            return qname2.substring(qname2.lastIndexOf("/") + 1);
        }
        return "";
    }

    /**
     * @param categoryIndex Index (see constants at beginning of class)
     * @return Category object
     */
    BlackboardCategory getCategory(int categoryIndex) {
        return categories[categoryIndex];
    }

    /**
     * Add client to blackboard manager
     *
     * @param client Blackboard client
     * @param autoConnect Auto-connect client
     */
    public void addClient(RawBlackboardClient client, boolean autoConnect) {
        synchronized (bbClients) {
            bbClients.add(client, false);
        }
        if (!autoConnect) {
            return;
        }
        synchronized (autoConnectClients) {
            autoConnectClients.add(client);

            for (int j = 0; (j < DIMENSION) && (!client.isConnected()); j++) {
                categories[j].checkConnect(client);
            }
        }
    }

    /**
     * @param client Remove Blackboard client
     */
    public void removeClient(RawBlackboardClient client) {
        synchronized (bbClients) {
            bbClients.remove(client);
        }
        if (!client.autoConnectClient()) {
            return;
        }
        synchronized (autoConnectClients) {
            autoConnectClients.removeElem(client);
        }
    }

    @Override
    protected void prepareDelete() {
        RuntimeEnvironment.getInstance().removeListener(this);
        instance = null;
        super.prepareDelete();
    }

    /**
     * @author Max Reichardt
     *
     * Thread checks for outdated locks in BlackboardServers
     * Thread sends alive signals for blackboard clients that hold lock.
     */
    private class LockCheckerThread extends CoreLoopThreadBase {

        /** Frequency to check for locks */
        public final static int CYCLE_TIME = 250;

        public LockCheckerThread() {
            super(CYCLE_TIME);
            setName("Blackboard Lock-Checker Thread");
            setDaemon(true);
        }

        @Override
        public void mainLoopCallback() throws Exception {

            // send keep-alive signals
            ArrayWrapper<RawBlackboardClient> it = bbClients.getIterable();
            for (int i = 0; i < it.size(); i++) {
                RawBlackboardClient client = it.get(i);
                if (client != null && client.isReady()) {
                    client.sendKeepAlive();
                }
            }

            // check for outdated locks (do this for local and shared blackboards)
            for (int i = 0; i < 2; i++) {
                BlackboardCategory cat = getCategory(i == 0 ? LOCAL : SHARED);
                ArrayWrapper<AbstractBlackboardServerRaw> it2 = cat.blackboards.getIterable();
                for (int j = 0; j < it2.size(); j++) {
                    AbstractBlackboardServerRaw bb = it2.get(j);
                    if (bb != null && bb.isReady()) {
                        bb.lockCheck();
                    }
                }
            }

        }
    }
}
