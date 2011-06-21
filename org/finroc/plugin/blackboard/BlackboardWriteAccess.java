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
import org.finroc.jc.Time;
import org.finroc.jc.annotation.CppDefault;
import org.finroc.jc.annotation.CppType;
import org.finroc.jc.annotation.InCpp;
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
import org.finroc.jc.log.LogDefinitions;
import org.finroc.log.LogDomain;
import org.finroc.log.LogLevel;
import org.finroc.serialization.PortDataList;
import org.finroc.serialization.Serialization;

/**
 * @author max
 *
 * Object to use for write-accessing blackboard
 */
@IncludeClass( {BBLockException.class, Serialization.class})
@PassByValue @NoVirtualDestructor @NoSuperclass @Inline @NoCpp @RawTypeArgs
public class BlackboardWriteAccess<T> implements HasDestructor {

    /*Cpp
    // no heap allocation permitted
    void *operator new( size_t );
    void *operator new[]( size_t );
    */

    /** Locked blackboard */
    @Ref private BlackboardClient<T> blackboard;

    /** not null - if buffer is currently locked for writing */
    @SuppressWarnings("rawtypes")
    private @Ptr @CppType("BlackboardClient<T>::BBVector") PortDataList locked;

    /** Log domain for this class */
    @InCpp("_RRLIB_LOG_CREATE_NAMED_DOMAIN(logDomain, \"blackboard\");")
    public static final LogDomain logDomain = LogDefinitions.finroc.getSubDomain("blackboard");

    /**
     * @param blackboard Blackboard to access
     * @param timeout Timeout for lock (in ms)
     */
    public BlackboardWriteAccess(@Ref BlackboardClient<T> blackboard, @CppDefault("60000") int timeout) throws BBLockException {
        this.blackboard = blackboard;
        locked = writeLock(timeout);
        if (locked == null) {

            //JavaOnlyBlock
            throw new BBLockException();

            //Cpp throw BBLockException();
        }
    }

    @SuppressWarnings("rawtypes")
    private @Ptr @CppType("BlackboardClient<T>::BBVector") PortDataList writeLock(@CppDefault("60000") int timeout) {
        logDomain.log(LogLevel.LL_DEBUG_VERBOSE_1, getLogDescription(), "Acquiring write lock on blackboard '" + blackboard.getDescription() + "' at " + Time.getPrecise());
        return blackboard.writeLock(timeout);
    }

    private @CppType("const char*") String getLogDescription() {
        return "BlackboardWriteAccess";
    }

    @Override
    public void delete() {
        if (locked != null) {
            logDomain.log(LogLevel.LL_DEBUG_VERBOSE_1, getLogDescription(), "Releasing write lock on blackboard '" + blackboard.getDescription() + "' at " + Time.getPrecise());
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
    inline T& operator[] (size_t index) {
        return get(index);
    }
     */

    /**
     * @param index Element index
     * @return Element at index
     */
    @SuppressWarnings("unchecked") @Inline
    public @Ref T get(@SizeT int index) {
        assert(index < size());

        //JavaOnlyBlock
        return (T)locked.get(index);

        //Cpp return (*locked)[index];
    }

    /**
     * @param newSize New size (number of elements) in blackboard
     */
    public void resize(@SizeT int newSize) {
        if (newSize != size()) {
            locked.resize(newSize);
        }
    }
}