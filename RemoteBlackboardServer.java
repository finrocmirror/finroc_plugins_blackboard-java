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

import org.rrlib.finroc_core_utils.serialization.PortDataList;
import org.finroc.core.datatype.CoreNumber;
import org.finroc.core.port.rpc.MethodCallException;

/**
 * @author Max Reichardt
 *
 * Dummy object to handle/categorise remote blackboards
 */
@SuppressWarnings("rawtypes")
public class RemoteBlackboardServer extends AbstractBlackboardServer<CoreNumber> {

    public RemoteBlackboardServer(String name) {
        super(name, BlackboardManager.REMOTE, null);
    }

    @Override
    protected void asynchChange(PortDataList buf, int index, int offset, boolean checkLock) {
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
    protected void directCommit(PortDataList buf) {
        throw new RuntimeException("Operation not supported");
    }

    @Override
    protected boolean isSingleBuffered() {
        throw new RuntimeException("Operation not supported");
    }

    @Override
    protected PortDataList readLock(long timeout) throws MethodCallException {
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
    protected PortDataList writeLock(long timeout) {
        throw new RuntimeException("Operation not supported");
    }

    @Override
    protected void writeUnlock(PortDataList buf) {
        throw new RuntimeException("Operation not supported");
    }
}
