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

import org.finroc.jc.AutoDeleter;
import org.finroc.jc.annotation.Const;
import org.finroc.jc.annotation.CppType;
import org.finroc.jc.annotation.Inline;
import org.finroc.jc.annotation.PassByValue;
import org.finroc.jc.annotation.Ptr;
import org.finroc.jc.annotation.Ref;
import org.finroc.jc.container.ReusablesPoolCR;
import org.finroc.core.plugin.Plugin;
import org.finroc.core.plugin.PluginManager;
import org.finroc.core.portdatabase.DataType;
import org.finroc.core.portdatabase.DataTypeRegister;

/**
 * @author max
 *
 * Object to initialize the blackboard 2 mechanism
 */
//@CppInclude({"BlackboardBuffer.h", "core/portdatabase/DataType.h"})
public class BlackboardPlugin implements Plugin {

//  /** Marks copy-on-write blackboard server ports */
//  public static int SINGLE_BUFFERED = PortFlags.FIRST_CUSTOM_PORT_FLAG;

    /** Reusable blackboard tasks */
    @Ptr static ReusablesPoolCR<BlackboardTask> taskPool;

    @Override
    public void init(PluginManager mgr) {
        taskPool = new ReusablesPoolCR<BlackboardTask>();
        AutoDeleter.addStatic(taskPool);

        //JavaOnlyBlock
        mgr.addDataType(BlackboardBuffer.class);
    }

    /*Cpp
    //wrapper for below
    template <typename T>
    static core::DataType* registerBlackboardType(const finroc::util::String& name) {
        return registerBlackboardType<T>(finroc::util::TypedClass<T>(), name);
    }

     */

    /**
     * Registers blackboard data type
     * (actually two: one for buffer and one for method calls)
     *
     * @param clazz Type
     * @param name Blackboard buffer type name
     * @return Blackboard buffer type
     */
    @Inline
    public static <T extends BlackboardBuffer> DataType registerBlackboardType(@PassByValue @CppType("finroc::util::TypedClass<T>") Class<T> clazz, @Const @Ref String name) {
        DataType dt = DataTypeRegister.getInstance().getDataType(clazz, name);
        DataType mc = DataTypeRegister.getInstance().addMethodDataType(name + " blackboard method calls", AbstractBlackboardServer.METHODS);
        dt.setRelatedType(mc);
        mc.setRelatedType(dt);
        return dt;
    }

    /**
     * Registers blackboard data type
     * (actually two: one for buffer and one for method calls)
     *
     * @param clazz Type
     * @return Blackboard buffer type
     */
    public static <T extends BlackboardBuffer> DataType registerBlackboardType(@PassByValue @CppType("finroc::util::TypedClass<T>") Class<T> clazz) {
        return registerBlackboardType(clazz, DataTypeRegister.getCleanClassName(clazz));
    }
}
