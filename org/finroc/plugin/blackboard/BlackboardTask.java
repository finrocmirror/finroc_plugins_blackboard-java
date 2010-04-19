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

import org.finroc.jc.annotation.Const;
import org.finroc.jc.annotation.Inline;
import org.finroc.jc.annotation.NoCpp;
import org.finroc.jc.container.Reusable;

/**
 * @author max
 *
 * Class to store pending blackboard tasks
 */
@Inline @NoCpp
public class BlackboardTask extends Reusable { /* implements Task */

    /** Method that is pending (possible are lock and asynch_change) */
    //public @Const AbstractMethod method;

    /** In case a thread is waiting on BlackboardServer - his uid - may only be changed in context synchronized to blackboard server */
    public long threadUid;

    ///** Notify waiting thread on lock when ready (instead of calling executeTask()) */
    //public boolean notifyOnly;

    /** BlackboardBuffer to use for task - if this is set, it will be unlocked with recycle */
    @Const public BlackboardBuffer buffer;

    /** Offset for asynch change command */
    public int offset;

    /** Recycle task */
    public void recycle2() {
        if (buffer != null) {
            buffer.getManager().releaseLock();
            buffer = null;
        }
        //method = null;
        super.recycle();
    }
}
