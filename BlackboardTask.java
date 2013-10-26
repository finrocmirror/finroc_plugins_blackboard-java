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

import org.finroc.core.port.std.PortDataManager;
import org.rrlib.finroc_core_utils.jc.container.Reusable;
import org.rrlib.finroc_core_utils.serialization.PortDataList;

/**
 * @author Max Reichardt
 *
 * Class to store pending blackboard tasks
 */
public class BlackboardTask extends Reusable { /* implements Task */

    /** In case a thread is waiting on BlackboardServer - his uid - may only be changed in context synchronized to blackboard server */
    public long threadUid;

    /** BlackboardBuffer to use for task - if this is set, it will be unlocked with recycle */
    @SuppressWarnings("rawtypes")
    public PortDataList buffer;

    /** Offset for asynch change command */
    public long offset;

    /** Start index for asynch change command */
    public int index;

    /** Recycle task */
    public void recycle2() {
        if (buffer != null) {
            PortDataManager.getManager(buffer).releaseLock();
            buffer = null;
        }
        //method = null;
        super.recycle();
    }
}
