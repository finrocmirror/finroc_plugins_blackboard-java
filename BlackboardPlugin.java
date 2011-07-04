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

import org.rrlib.finroc_core_utils.jc.annotation.Const;
import org.rrlib.finroc_core_utils.jc.annotation.CppType;
import org.rrlib.finroc_core_utils.jc.annotation.ForwardDecl;
import org.rrlib.finroc_core_utils.jc.annotation.HAppend;
import org.rrlib.finroc_core_utils.jc.annotation.InCpp;
import org.rrlib.finroc_core_utils.jc.annotation.IncludeClass;
import org.rrlib.finroc_core_utils.jc.annotation.Inline;
import org.rrlib.finroc_core_utils.jc.annotation.JavaOnly;
import org.rrlib.finroc_core_utils.jc.annotation.Managed;
import org.rrlib.finroc_core_utils.jc.annotation.PassByValue;
import org.rrlib.finroc_core_utils.jc.annotation.PostInclude;
import org.rrlib.finroc_core_utils.jc.annotation.Ptr;
import org.rrlib.finroc_core_utils.jc.annotation.Ref;
import org.rrlib.finroc_core_utils.jc.annotation.SkipArgs;
import org.rrlib.finroc_core_utils.jc.container.ReusablesPoolCR;
import org.rrlib.finroc_core_utils.serialization.DataType;
import org.rrlib.finroc_core_utils.serialization.DataTypeBase;
import org.rrlib.finroc_core_utils.serialization.MemoryBuffer;
import org.finroc.core.plugin.Plugin;
import org.finroc.core.portdatabase.RPCInterfaceType;

/**
 * @author max
 *
 * Object to initialize the blackboard 2 mechanism
 */
//@CppInclude({"BlackboardBuffer.h", "core/portdatabase/DataType.h"})
@ForwardDecl(AbstractBlackboardServer.class)
@IncludeClass(RPCInterfaceType.class)
@PostInclude("AbstractBlackboardServer.h")
public class BlackboardPlugin implements Plugin {

//  /** Marks copy-on-write blackboard server ports */
//  public static int SINGLE_BUFFERED = PortFlags.FIRST_CUSTOM_PORT_FLAG;

    /** Reusable blackboard tasks */
    @JavaOnly
    @Ptr static ReusablesPoolCR<BlackboardTask> taskPool;

    public static DataTypeBase BB_MEM_BUFFER = registerBlackboardType(MemoryBuffer.class);
    public static DataTypeBase BB_BLACKBOARD_BUFFER = registerBlackboardType(BlackboardBuffer.class);

    @Override
    public void init(/*PluginManager mgr*/) {
//        taskPool = new ReusablesPoolCR<BlackboardTask>();
//        AutoDeleter.addStatic(taskPool);

        //JavaOnlyBlock
        @SuppressWarnings("unused")
        DataTypeBase x = BlackboardBuffer.TYPE;
    }

    /*Cpp
    //wrapper for below
    template <typename T>
    static rrlib::serialization::DataTypeBase registerBlackboardType(const finroc::util::String& name) {
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
    @Inline @SkipArgs("1")
    public static <T> DataTypeBase registerBlackboardType(@PassByValue @CppType("finroc::util::TypedClass<T>") Class<T> clazz, @Const @Ref String name) {
        @InCpp("rrlib::serialization::DataType<T> dt;")
        DataTypeBase dt = DataTypeBase.findType(clazz);

        //JavaOnlyBlock
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
    @Inline
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
    @HAppend( {})
    public static <T> DataTypeBase registerBlackboardType(DataTypeBase dt, @Const @Ref String name) {
        String bb_name = "Blackboard<" + name + ">";
        DataTypeBase dtbb = DataTypeBase.findType(bb_name);
        if (dtbb == null) {
            /*Cpp
            core::PortInterface* methods = &AbstractBlackboardServer<T>::getBlackboardInterface();
            methods->clear();
            methods->addMethod(&AbstractBlackboardServer<T>::LOCK);
            methods->addMethod(&AbstractBlackboardServer<T>::READ_LOCK);
            methods->addMethod(&AbstractBlackboardServer<T>::UNLOCK);
            methods->addMethod(&AbstractBlackboardServer<T>::READ_UNLOCK);
            methods->addMethod(&AbstractBlackboardServer<T>::ASYNCH_CHANGE);
            methods->addMethod(&AbstractBlackboardServer<T>::DIRECT_COMMIT);
            methods->addMethod(&AbstractBlackboardServer<T>::IS_SINGLE_BUFFERED);
            methods->addMethod(&AbstractBlackboardServer<T>::KEEP_ALIVE);
             */

            @InCpp("core::RPCInterfaceType rpct(bb_name, methods);")
            RPCInterfaceType rpct = new RPCInterfaceType(bb_name, AbstractBlackboardServer.getBlackboardInterface());
            dtbb = rpct;

            // add annotation to element type
            @Managed BlackboardTypeInfo bti = new BlackboardTypeInfo();
            bti.blackboardType = dtbb;
            dt.addAnnotation(bti);

            // add annotation to blackboard type
            @Managed BlackboardTypeInfo btibb = new BlackboardTypeInfo();
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
    @InCpp("return registerBlackboardType(clazz, rrlib::serialization::DataTypeBase::getDataTypeNameFromRtti(typeid(T).name()));")
    public static <T> DataTypeBase registerBlackboardType(@PassByValue @CppType("finroc::util::TypedClass<T>") Class<T> clazz) {
        return registerBlackboardType(clazz, clazz.getSimpleName());
    }
}
