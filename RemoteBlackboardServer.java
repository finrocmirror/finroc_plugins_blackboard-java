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

import org.rrlib.finroc_core_utils.jc.annotation.CppType;
import org.rrlib.finroc_core_utils.jc.annotation.Inline;
import org.rrlib.finroc_core_utils.jc.annotation.NoCpp;
import org.rrlib.finroc_core_utils.serialization.PortDataList;
import org.finroc.core.datatype.CoreNumber;
import org.finroc.core.port.rpc.MethodCallException;

/**
 * @author max
 *
 * Dummy object to handle/categorise remote blackboards
 */
@Inline @NoCpp
@SuppressWarnings("rawtypes")
public class RemoteBlackboardServer extends AbstractBlackboardServer<CoreNumber> {

    public RemoteBlackboardServer(String name) {
        super(name, BlackboardManager.REMOTE, null);
    }

    @Override
    protected void asynchChange(@CppType("ConstChangeTransactionVar") PortDataList buf, int index, int offset, boolean checkLock) {
        throw new RuntimeException("Operation not supported");
    }

    @Override
    protected boolean isLocked() {
        throw new RuntimeException("Operation not supported");
    }

    @Override
    protected void keepAlive(int lockId) {
        throw new RuntimeException("Operation not supported");
    }

    @Override
    public void lockCheck() {
        // do nothing
    }

    @Override
    protected void directCommit(@CppType("BBVectorVar") PortDataList buf) {
        throw new RuntimeException("Operation not supported");
    }

    @Override
    protected boolean isSingleBuffered() {
        throw new RuntimeException("Operation not supported");
    }

    @Override
    protected @CppType("ConstBBVectorVar") PortDataList readLock(long timeout) throws MethodCallException {
        throw new RuntimeException("Operation not supported");
    }

//    @Override
//    protected BlackboardBuffer readPart(int offset, int length, int timeout) {
//        throw new RuntimeException("Operation not supported");
//    }

    @Override
    protected void readUnlock(int lockId) throws MethodCallException {
        throw new RuntimeException("Operation not supported");
    }

    @Override
    protected @CppType("BBVectorVar")PortDataList writeLock(long timeout) {
        throw new RuntimeException("Operation not supported");
    }

    @Override
    protected void writeUnlock(@CppType("BBVectorVar") PortDataList buf) {
        throw new RuntimeException("Operation not supported");
    }
}
