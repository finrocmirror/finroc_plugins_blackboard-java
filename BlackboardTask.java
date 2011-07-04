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

import org.finroc.core.port.std.PortDataManager;
import org.rrlib.finroc_core_utils.jc.annotation.Const;
import org.rrlib.finroc_core_utils.jc.annotation.Inline;
import org.rrlib.finroc_core_utils.jc.annotation.JavaOnly;
import org.rrlib.finroc_core_utils.jc.annotation.NoCpp;
import org.rrlib.finroc_core_utils.jc.annotation.PassByValue;
import org.rrlib.finroc_core_utils.jc.annotation.Superclass;
import org.rrlib.finroc_core_utils.jc.container.Reusable;
import org.rrlib.finroc_core_utils.serialization.PortDataList;

/**
 * @author max
 *
 * Class to store pending blackboard tasks
 */
@Inline @NoCpp @PassByValue @Superclass( {})
public class BlackboardTask extends Reusable { /* implements Task */

    /** In case a thread is waiting on BlackboardServer - his uid - may only be changed in context synchronized to blackboard server */
    public long threadUid;

    /** BlackboardBuffer to use for task - if this is set, it will be unlocked with recycle */
    @SuppressWarnings("rawtypes")
    @JavaOnly @Const public PortDataList buffer;

    /** Offset for asynch change command */
    @JavaOnly public long offset;

    /** Start index for asynch change command */
    @JavaOnly public int index;

    /** Recycle task */
    @JavaOnly
    public void recycle2() {
        if (buffer != null) {
            PortDataManager.getManager(buffer).releaseLock();
            buffer = null;
        }
        //method = null;
        super.recycle();
    }

    /*Cpp
    bool operator==(const BlackboardTask& other) const {
        return threadUid == other.threadUid;
    }
     */
}
