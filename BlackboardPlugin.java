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

import org.rrlib.finroc_core_utils.jc.container.ReusablesPoolCR;
import org.rrlib.serialization.MemoryBuffer;
import org.rrlib.serialization.rtti.DataType;
import org.rrlib.serialization.rtti.DataTypeBase;
import org.finroc.core.plugin.Plugin;

/**
 * @author Max Reichardt
 *
 * Object to initialize the blackboard 2 mechanism
 */
public class BlackboardPlugin implements Plugin {

    public static DataTypeBase BB_MEM_BUFFER = registerBlackboardType(MemoryBuffer.class);
    public static DataTypeBase BB_BLACKBOARD_BUFFER = registerBlackboardType(BlackboardBuffer.class);

    @Override
    public void init(/*PluginManager mgr*/) {
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

            //throw new RuntimeException("TODO");
            /*RPCInterfaceType rpct = new RPCInterfaceType(bb_name, AbstractBlackboardServer.getBlackboardInterface());
            dtbb = rpct;

            // add annotation to element type
            BlackboardTypeInfo bti = new BlackboardTypeInfo();
            bti.blackboardType = dtbb;
            dt.addAnnotation(bti);

            // add annotation to blackboard type
            BlackboardTypeInfo btibb = new BlackboardTypeInfo();
            btibb.elementType = dt;
            dtbb.addAnnotation(btibb);*/
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
