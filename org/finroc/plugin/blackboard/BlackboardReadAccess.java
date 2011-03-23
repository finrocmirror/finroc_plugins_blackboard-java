/**
 * You received this file as part of an advanced experimental
 * robotics framework prototype ('finroc')
 *
 * Copyright (C) 2011 Max Reichardt,
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

import org.finroc.jc.HasDestructor;
import org.finroc.jc.annotation.Const;
import org.finroc.jc.annotation.CppDefault;
import org.finroc.jc.annotation.CppType;
import org.finroc.jc.annotation.IncludeClass;
import org.finroc.jc.annotation.Inline;
import org.finroc.jc.annotation.NoCpp;
import org.finroc.jc.annotation.NoSuperclass;
import org.finroc.jc.annotation.NoVirtualDestructor;
import org.finroc.jc.annotation.PassByValue;
import org.finroc.jc.annotation.Ptr;
import org.finroc.jc.annotation.RawTypeArgs;
import org.finroc.jc.annotation.Ref;
import org.finroc.jc.annotation.SizeT;
import org.finroc.serialization.PortDataList;

/**
 * @author max
 *
 * Object to use for read-accessing blackboard
 */
@IncludeClass(BBLockException.class)
@PassByValue @NoVirtualDestructor @NoSuperclass @Inline @NoCpp @RawTypeArgs
public class BlackboardReadAccess<T> implements HasDestructor {

    /** Locked blackboard */
    @Ref private BlackboardClient<T> blackboard;

    /** not null - if buffer is currently locked for writing */
    @SuppressWarnings("rawtypes")
    private @Const @Ptr @CppType("BlackboardClient<T>::BBVectorVar") PortDataList locked;

    /**
     * @param blackboard Blackboard to access
     * @param timeout Timeout for lock (in ms)
     */
    public BlackboardReadAccess(@Ref BlackboardClient<T> blackboard, @CppDefault("60000") int timeout) throws BBLockException {
        this.blackboard = blackboard;
        locked = blackboard.readLock(false, timeout);
        if (locked == null) {

            //JavaOnlyBlock
            throw new BBLockException();

            //Cpp throw BBLockException();
        }
    }

    @Override
    public void delete() {
        if (locked != null) {
            blackboard.unlock();
        }
    }

    /**
     * @return Number of elements in blackboard
     */
    public @SizeT int size() {
        return locked.size();
    }

    /*Cpp
    inline const T& operator[] (size_t index) {
        return get(index);
    }
     */

    /**
     * @param index Element index
     * @return Element at index
     */
    @SuppressWarnings("unchecked") @Inline
    public @Const @Ref T get(@SizeT int index) {
        assert(index < size());

        //JavaOnlyBlock
        return (T)locked.get(index);

        //Cpp return (*locked)[index];
    }
}