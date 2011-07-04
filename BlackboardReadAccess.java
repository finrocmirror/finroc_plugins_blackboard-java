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
package org.finroc.plugins.blackboard;

import org.rrlib.finroc_core_utils.jc.HasDestructor;
import org.rrlib.finroc_core_utils.jc.Time;
import org.rrlib.finroc_core_utils.jc.annotation.Const;
import org.rrlib.finroc_core_utils.jc.annotation.CppDefault;
import org.rrlib.finroc_core_utils.jc.annotation.CppType;
import org.rrlib.finroc_core_utils.jc.annotation.InCpp;
import org.rrlib.finroc_core_utils.jc.annotation.IncludeClass;
import org.rrlib.finroc_core_utils.jc.annotation.Inline;
import org.rrlib.finroc_core_utils.jc.annotation.NoCpp;
import org.rrlib.finroc_core_utils.jc.annotation.NoSuperclass;
import org.rrlib.finroc_core_utils.jc.annotation.NoVirtualDestructor;
import org.rrlib.finroc_core_utils.jc.annotation.PassByValue;
import org.rrlib.finroc_core_utils.jc.annotation.Ptr;
import org.rrlib.finroc_core_utils.jc.annotation.RawTypeArgs;
import org.rrlib.finroc_core_utils.jc.annotation.Ref;
import org.rrlib.finroc_core_utils.jc.annotation.SizeT;
import org.rrlib.finroc_core_utils.jc.log.LogDefinitions;
import org.rrlib.finroc_core_utils.log.LogDomain;
import org.rrlib.finroc_core_utils.log.LogLevel;
import org.rrlib.finroc_core_utils.serialization.PortDataList;

/**
 * @author max
 *
 * Object to use for read-accessing blackboard
 */
@IncludeClass(BBLockException.class)
@PassByValue @NoVirtualDestructor @NoSuperclass @Inline @NoCpp @RawTypeArgs
public class BlackboardReadAccess<T> implements HasDestructor {

    /*Cpp
    // no heap allocation permitted
    void *operator new( size_t );
    void *operator new[]( size_t );
    */

    /** Locked blackboard */
    @Ref private BlackboardClient<T> blackboard;

    /** not null - if buffer is currently locked for writing */
    @SuppressWarnings("rawtypes")
    private @Const @Ptr @CppType("BlackboardClient<T>::BBVector") PortDataList locked;

    /** Log domain for this class */
    @InCpp("_RRLIB_LOG_CREATE_NAMED_DOMAIN(logDomain, \"blackboard\");")
    public static final LogDomain logDomain = LogDefinitions.finroc.getSubDomain("blackboard");

    /**
     * @param blackboard Blackboard to access
     * @param timeout Timeout for lock (in ms)
     */
    public BlackboardReadAccess(@Ref BlackboardClient<T> blackboard, @CppDefault("60000") int timeout) throws BBLockException {
        this.blackboard = blackboard;
        locked = readLock(timeout);
        if (locked == null) {

            //JavaOnlyBlock
            throw new BBLockException();

            //Cpp throw BBLockException();
        }
    }

    @SuppressWarnings("rawtypes")
    private @Const @Ptr @CppType("BlackboardClient<T>::BBVector") PortDataList readLock(@CppDefault("60000") int timeout) {
        logDomain.log(LogLevel.LL_DEBUG_VERBOSE_1, getLogDescription(), "Acquiring read lock on blackboard '" + blackboard.getDescription() + "' at " + Time.getPrecise());
        return blackboard.readLock(false, timeout);
    }

    private @CppType("const char*") String getLogDescription() {
        return "BlackboardWriteAccess";
    }

    @Override
    public void delete() {
        if (locked != null) {
            logDomain.log(LogLevel.LL_DEBUG_VERBOSE_1, getLogDescription(), "Releasing read lock on blackboard '" + blackboard.getDescription() + "' at " + Time.getPrecise());
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