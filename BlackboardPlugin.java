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

import org.rrlib.finroc_core_utils.jc.container.ReusablesPoolCR;
import org.rrlib.finroc_core_utils.rtti.DataType;
import org.rrlib.finroc_core_utils.rtti.DataTypeBase;
import org.rrlib.finroc_core_utils.serialization.MemoryBuffer;
import org.finroc.core.plugin.Plugin;
import org.finroc.core.portdatabase.RPCInterfaceType;

/**
 * @author Max Reichardt
 *
 * Object to initialize the blackboard 2 mechanism
 */
public class BlackboardPlugin implements Plugin {

//  /** Marks copy-on-write blackboard server ports */
//  public static int SINGLE_BUFFERED = PortFlags.FIRST_CUSTOM_PORT_FLAG;

    /** Reusable blackboard tasks */
    static ReusablesPoolCR<BlackboardTask> taskPool;

    public static DataTypeBase BB_MEM_BUFFER = registerBlackboardType(MemoryBuffer.class);
    public static DataTypeBase BB_BLACKBOARD_BUFFER = registerBlackboardType(BlackboardBuffer.class);

    @Override
    public void init(/*PluginManager mgr*/) {
//        taskPool = new ReusablesPoolCR<BlackboardTask>();
//        AutoDeleter.addStatic(taskPool);

        @SuppressWarnings("unused")
        DataTypeBase x = BlackboardBuffer.TYPE;
    }

    /**
     * Registers blackboard data type
     * (actually two: one for buffer and one for method calls)
     *
     * @param clazz Type
     * @param name Blackboard buffer type name
     * @return Blackboard buffer type
     */
    public static <T> DataTypeBase registerBlackboardType(Class<T> clazz, String name) {
        DataTypeBase dt = DataTypeBase.findType(clazz);

        if (dt == null) {
            dt = new DataType<T>(clazz, name);
        }

        return BlackboardPlugin.<T>registerBlackboardType(dt, name);
    }

    /**
     * Registers blackboard data type
     * (actually two: one for buffer and one for method calls)
     *
     * @param dt Data type to create blackboard type for
     * @return Blackboard buffer type
     */
    public static <T> DataTypeBase registerBlackboardType(DataTypeBase dt) {
        return BlackboardPlugin.<T>registerBlackboardType(dt, dt.getName());
    }

    /**
     * Registers blackboard data type
     * (actually two: one for buffer and one for method calls)
     *
     * @param dt Data type to create blackboard type for
     * @param name Blackboard buffer type name
     * @return Blackboard buffer type
     */
    public static <T> DataTypeBase registerBlackboardType(DataTypeBase dt, String name) {
        String bb_name = "Blackboard<" + name + ">";
        DataTypeBase dtbb = DataTypeBase.findType(bb_name);
        if (dtbb == null) {

            RPCInterfaceType rpct = new RPCInterfaceType(bb_name, AbstractBlackboardServer.getBlackboardInterface());
            dtbb = rpct;

            // add annotation to element type
            BlackboardTypeInfo bti = new BlackboardTypeInfo();
            bti.blackboardType = dtbb;
            dt.addAnnotation(bti);

            // add annotation to blackboard type
            BlackboardTypeInfo btibb = new BlackboardTypeInfo();
            btibb.elementType = dt;
            dtbb.addAnnotation(btibb);
        }

        return dtbb;
    }

    /**
     * Registers blackboard data type
     * (actually two: one for buffer and one for method calls)
     *
     * @param clazz Type
     * @return Blackboard buffer type
     */
    public static <T> DataTypeBase registerBlackboardType(Class<T> clazz) {
        return registerBlackboardType(clazz, clazz.getSimpleName());
    }
}
