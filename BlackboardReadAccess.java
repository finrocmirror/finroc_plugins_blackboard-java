//
// You received this file as part of Finroc
// A Framework for intelligent robot control
//
// Copyright (C) Finroc GbR (finroc.org)
//
// This program is free software; you can redistribute it and/or
// modify it under the terms of the GNU General Public License
// as published by the Free Software Foundation; either version 2
// of the License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
//
//----------------------------------------------------------------------
package org.finroc.plugins.blackboard;

import org.rrlib.finroc_core_utils.jc.HasDestructor;
import org.rrlib.finroc_core_utils.jc.Time;
import org.rrlib.finroc_core_utils.jc.log.LogDefinitions;
import org.rrlib.finroc_core_utils.log.LogDomain;
import org.rrlib.finroc_core_utils.log.LogLevel;
import org.rrlib.finroc_core_utils.serialization.PortDataList;

/**
 * @author Max Reichardt
 *
 * Object to use for read-accessing blackboard
 */
public class BlackboardReadAccess<T> implements HasDestructor {

    /** Locked blackboard */
    private BlackboardClient<T> blackboard;

    /** not null - if buffer is currently locked for writing */
    @SuppressWarnings("rawtypes")
    private PortDataList locked;

    /** Log domain for this class */
    public static final LogDomain logDomain = LogDefinitions.finroc.getSubDomain("blackboard");

    /**
     * @param blackboard Blackboard to access
     * @param timeout Timeout for lock (in ms)
     */
    public BlackboardReadAccess(BlackboardClient<T> blackboard, int timeout) throws BBLockException {
        this.blackboard = blackboard;
        locked = readLock(timeout);
        if (locked == null) {
            throw new BBLockException();
        }
    }

    @SuppressWarnings("rawtypes")
    private PortDataList readLock(int timeout) {
        logDomain.log(LogLevel.LL_DEBUG_VERBOSE_1, getLogDescription(), "Acquiring read lock on blackboard '" + blackboard.getName() + "' at " + Time.getPrecise());
        return blackboard.readLock(false, timeout);
    }

    private String getLogDescription() {
        return "BlackboardWriteAccess";
    }

    @Override
    public void delete() {
        if (locked != null) {
            logDomain.log(LogLevel.LL_DEBUG_VERBOSE_1, getLogDescription(), "Releasing read lock on blackboard '" + blackboard.getName() + "' at " + Time.getPrecise());
            blackboard.unlock();
        }
    }

    /**
     * @return Number of elements in blackboard
     */
    public int size() {
        return locked.size();
    }

    /**
     * @param index Element index
     * @return Element at index
     */
    @SuppressWarnings("unchecked")
    public T get(int index) {
        assert(index < size());
        return (T)locked.get(index);
    }
}
