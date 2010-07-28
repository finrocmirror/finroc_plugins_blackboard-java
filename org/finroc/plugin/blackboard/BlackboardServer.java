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

import org.finroc.jc.AtomicInt;
import org.finroc.jc.Time;
import org.finroc.jc.annotation.Const;
import org.finroc.jc.annotation.CppDefault;
import org.finroc.jc.annotation.InCpp;
import org.finroc.jc.annotation.JavaOnly;
import org.finroc.jc.annotation.PassLock;
import org.finroc.jc.annotation.Ptr;
import org.finroc.jc.log.LogDefinitions;
import org.finroc.log.LogDomain;
import org.finroc.log.LogLevel;
import org.finroc.core.CoreFlags;
import org.finroc.core.FrameworkElement;
import org.finroc.core.LockOrderLevels;
import org.finroc.core.port.PortCreationInfo;
import org.finroc.core.port.PortFlags;
import org.finroc.core.port.rpc.InterfaceServerPort;
import org.finroc.core.port.rpc.MethodCallException;
import org.finroc.core.port.std.Port;
import org.finroc.core.portdatabase.DataType;

/**
 * @author max
 *
 * This is the base class for a blackboard server
 */
@Ptr
public class BlackboardServer extends AbstractBlackboardServer {

    /** Unlock timeout in ms - if no keep-alive signal occurs in this period of time */
    private final static long UNLOCK_TIMEOUT = 1000;

    /** Interface port for write access */
    private InterfaceServerPort write;

    /** Is blackboard currently locked? - in this case points to duplicated buffer */
    private BlackboardBuffer locked;

    /** Time when last lock was performed */
    private volatile long lockTime;

    /** Last time a keep-alive-signal was received */
    private volatile long lastKeepAlive;

    /** ID of current lock - against outdated unlocks */
    private final AtomicInt lockIDGen = new AtomicInt(0);
    private int lockId = 0;

    /**
     * Currently published MemBuffer - not extra locked - attached to lock of read port
     * In single buffered mode - this is the one and only buffer
     */
    private BlackboardBuffer published;

    /**
     * @param description Name/Uid of blackboard
     */
    @JavaOnly
    public BlackboardServer(String description) {
        this(description, null);
    }

    /**
     * @param description Name/Uid of blackboard
     * @parent parent of BlackboardServer
     */
    public BlackboardServer(String description, @CppDefault("NULL") FrameworkElement parent) {
        this(description, BlackboardBuffer.BUFFER_TYPE, parent, true);
    }

    /**
     * @param description Name/Uid of blackboard
     * @param type Data Type of blackboard content
     * @param capacity Blackboard capacity (see BlackboardBuffer)
     * @param elements Number of element (see BlackboardBuffer)
     * @param elemSize Element size (see BlackboardBuffer)
     * @param parent parent of BlackboardServer
     * @param shared Share blackboard with other runtime environments?
     */
    public BlackboardServer(String description, DataType type, int capacity, int elements, int elemSize, @CppDefault("NULL") FrameworkElement parent, @CppDefault("true") boolean shared) {
        this(description, type, parent, shared);
        published.resize(capacity, elements, elemSize, false);
    }


    /**
     * @param description Name/Uid of blackboard
     * @param type Data Type of blackboard content
     * @param mcType Type of method calls
     * @param parent parent of BlackboardServer
     * @param shared Share blackboard with other runtime environments?
     */
    public BlackboardServer(String description, DataType type, @CppDefault("NULL") FrameworkElement parent, @CppDefault("true") boolean shared) {
        super(description, shared ? BlackboardManager.SHARED : BlackboardManager.LOCAL, parent);
        readPort = new Port<BlackboardBuffer>(new PortCreationInfo("read", this, type, PortFlags.OUTPUT_PORT | (shared ? CoreFlags.SHARED : 0)).lockOrderDerive(LockOrderLevels.REMOTE_PORT + 1));
        checkType(type);
        write = new InterfaceServerPort("write", this, type.getRelatedType(), this, shared ? CoreFlags.SHARED : 0, LockOrderLevels.REMOTE_PORT + 2);
        writePort = write;
        locked = null;
        setPublished((BlackboardBuffer)readPort.getDefaultBufferRaw());
    }

    /**
     * This method exists due to current imperfectness in java->c++ converter
     *
     * @param p
     */
    private void setPublished(BlackboardBuffer p) {
        published = p;
    }

    @Override
    protected void asynchChange(int offset, BlackboardBuffer buf, boolean checkLock) {
        synchronized (bbLock) {
            if (checkLock && locked != null) {
                checkCurrentLock();
                if (locked != null) { // ok, we don't get lock now... defer command to next unlock
                    deferAsynchChangeCommand(offset, buf);
                    return;
                }
            }

            assert((!checkLock) || (!pendingTasks()));

            // duplicate current buffer
            assert(locked == null);
            duplicateAndLock();

            // apply asynch change
            locked.getBuffer().put(offset, buf.getBuffer(), 0, buf.getSize());
            buf.getManager().releaseLock();

            // commit changes
            commitLocked();
            assert(locked == null);
            //processPendingCommands();
        }
    }

    @Override
    protected void keepAlive(int lockId) {
        synchronized (bbLock) {
            if (locked != null && this.lockId == lockId) {
                lastKeepAlive = Time.getCoarse();
            }
        }
    }

//  @Override
//  public void handleMethodCall(MethodCall mc, byte methodId, boolean deferred, long int1, long int2, long int3, double dbl1, double dbl2, double dbl3, TypedObject obj1, TypedObject obj2) {
//
//      if (mc.getMethodID() == RawBlackboardClient.IS_SINGLE_BUFFERED) {
//          mc.setReturn(0);
//          return;
//      }
//
//      // deferred calls only come synchronized
//      if (deferred) {
//          assert(write.access.hasLock());
//
//          // apply asynch change optimization...
//          if (locked != null && methodId == RawBlackboardClient.ASYNCH_CHANGE) { // still locked - before unlock
//              assert(locked.getManager().isLocked());
//              applyAsynchChange((int)int1, (BlackboardBuffer)obj1);
//              return;
//          }
//      }
//
//      // non-exclusive-access calls
//      int offset = 0, length = 0;
//      @Const BlackboardBuffer buffer;
//      BlackboardBuffer send = null;
//      long arrival = 0;
//
//      switch(methodId) {
//      case RawBlackboardClient.READ_PART:
//          // current buffer
//          buffer = (BlackboardBuffer)readPort.getLockedUnsafeRaw();
//          assert(buffer.getManager().isLocked());
//
//          // call parameters
//          offset = (int)int1;
//          length = (int)int2;
//
//          // prepare and set return value
//          send = (BlackboardBuffer)write.getUnusedBuffer(buffer.getType());
//          send.getBuffer().put(0, buffer.getBuffer(), offset, length);
//          send.bbCapacity = buffer.bbCapacity;
//          send.elements = buffer.elements;
//          send.elementSize = buffer.elementSize;
//          mc.setReturn(send, false);
//
//          buffer.getManager().getCurrentRefCounter().releaseLock();
//          return;
//
//      case RawBlackboardClient.DEPRECATED_DIRECT_BUFFER_ACCESS:
//          System.out.println("warning: Deprecated unlocked blackboard access");
//          mc.setReturn(published, true);
//          return;
//      }
//
//      // exclusive-access calls
//      // from here, everything has to be synchronized
//      System.out.println("Thread " + Thread.currentThread().toString() + ": Executing command " + methodString(methodId) + " for Thread " + mc.getThreadUid() + (deferred ? "(deferred)" : ""));
//      if (!deferred) {
//          assert(!write.access.hasLock());
//          System.out.println("Thread " + Thread.currentThread().toString() + ": Acquiring Mutex " + methodString(methodId) + " (Thread " + mc.getThreadUid() + ")");
//          write.access.lock();
//          System.out.println("Thread " + Thread.currentThread().toString() + ": Acquired Mutex " + methodString(methodId) + " (Thread " + mc.getThreadUid() + ")");
//      }
//
//      if (methodId == RawBlackboardClient.UNLOCK) { // should be executed before anything else
//
//          unlock(mc, (BlackboardBuffer)obj1, (int)int1, deferred);
//
//      } else if (deferred || write.handleDeferredCalls()) {
//
//          // defer call, because blackboard is currently locked?
//          if (locked != null) {
//              checkCurrentLock();
//              if (locked != null) {
//                  if (methodId == RawBlackboardClient.LOCK && int1 == 0) { // we do not need to enqueue lock commands with zero timeout
//                      mc.setReturnNull();
//                  } else {
//                      write.deferCall(mc, true);
//                  }
//                  if (!deferred) {
//                      write.access.release();
//                  }
//                  return;
//              }
//          }
//
//          switch(methodId) {
//
//          case RawBlackboardClient.LOCK:
//              System.out.println("Thread " + Thread.currentThread().toString() + ": handleLock");
//              assert(int2 == 0) : "Client must not attempt read lock on multi-buffered blackboard";
//              arrival = mc.getArrivalTime();
//              if (deferred && (arrival + int1 < Time.getCoarse())) { // already timed out... process next commands
//                  System.out.println("skipping lock command, because it already has timed out");
//                  mc.setReturnNull();
//                  break;
//              } else {
//                  duplicateAndLock();
//                  assert(locked != null && locked.getManager().isLocked());
//                  mc.setReturn(locked, false);
//              }
//              break;
//
//          case RawBlackboardClient.ASYNCH_CHANGE:
//              duplicateAndLock();
//              applyAsynchChange((int)int1, (BlackboardBuffer)obj1);
//              commitLocked();
//              assert(locked == null);
//              processPendingCommands(deferred);
//              break;
//
//          case RawBlackboardClient.DIRECT_COMMIT:
//              locked = (BlackboardBuffer)obj1;
//              mc.dontRecycleParam1();
//              assert(locked != null);
//              commitLocked();
//              assert(locked == null);
//              processPendingCommands(deferred);
//              break;
//
//          }
//      } else {
//
//          // defer call, because there are still commands in queue
//          write.deferCall(mc, true);
//      }
//
//      if (!deferred) {
//          System.out.println("Thread " + Thread.currentThread().toString() + ": preRelease " + methodString(methodId) /*+ " (Thread " + mc.getThreadUid() + ")"*/);
//          write.access.release();
//      }
//  }
//
//  private void unlock(MethodCall mc, BlackboardBuffer newBuffer, int newLockID, boolean deferred) {
//      //System.out.println("Thread " + Thread.currentThread().toString() + ": handleUnlock");
//      if (locked == null) {
//          System.out.println("somewhat fatal warning: Unlock without lock in blackboard");
//      }
//      if (newBuffer == null) { // read unlock
//          System.out.println("blackboard unlock without providing buffer (legacy read unlock?)");
//          releaseLockAndProcessPendingCommands(deferred);
//          return;
//      }
//      assert(newBuffer.getManager().isLocked());
//      assert(newBuffer.lockID == newLockID);
//      if (newBuffer.lockID != lockID) {
//          System.out.println("Skipping outdated unlock");
//          processPendingCommands(deferred);
//          return;
//      }
//      if (newBuffer != locked) {
//          locked.getManager().getCurrentRefCounter().releaseLock();
//          mc.dontRecycleParam1();
//          //newBuffer.getManager().getCurrentRefCounter().addLock();
//          locked = newBuffer;
//          System.out.println("Thread " + Thread.currentThread().toString() + ": lock = " + locked.toString());
//          assert(locked.getCurReference().isLocked());
//      }
//      releaseLockAndProcessPendingCommands(deferred);
//  }
//

    /**
     * Check if lock timed out (only call in synchronized/exclusive access context)
     */
    @PassLock("bbLock")
    private void checkCurrentLock() {
        if (locked != null && Time.getCoarse() > lastKeepAlive + UNLOCK_TIMEOUT) {
            log(LogLevel.LL_DEBUG, logDomain, "Blackboard server: Lock timed out... unlocking");
            locked.getCurReference().getRefCounter().releaseLock();
            lockId = lockIDGen.incrementAndGet();
            locked = null;
            log(LogLevel.LL_DEBUG, logDomain, "Thread " + Thread.currentThread().toString() + ": lock = null");
            boolean p = processPendingCommands();
            if ((!p) && (!isLocked())) {
                super.processPendingAsynchChangeTasks();
            }
        }
    }
//
//  /** process pending commands and commit currently locked buffer (if there is one) - (only call in synchronized context
//   * access lock will be released)
//   * @param deferred */
//  private void releaseLockAndProcessPendingCommands(boolean deferred) {
//      assert(write.access.hasLock());
//      if (deferred) { // this method is already active below in call stack
//          return;
//      }
//
//      // apply any asynch change commands before committing
//      MethodCall mc = write.deferred.peek();
//      while(mc != null && mc.getMethodID() == RawBlackboardClient.ASYNCH_CHANGE) {
//          write.handleDeferredCall();
//          mc = write.deferred.peek();
//      }
//
//      // unlock & commit
//      commitLocked();
//      assert(locked == null || locked.getCurReference().isLocked());
//
//      // process pending commands
//      processPendingCommands(deferred);
//  }
//
//  private void processPendingCommands(boolean deferred) {
//      if (deferred) {
//          return;
//      }
//      write.handleDeferredCalls();
//  }
//
    /** Release lock and commit changed buffer (needs to be called in synchronized context) */
    private void commitLocked() {
        assert(locked != null && locked.getCurReference().isLocked());

        // process any waiting asynch change commands
        processPendingAsynchChangeTasks();

        // publish new buffer
        locked.lockID = -1;
        readPort.publish(locked);
        locked.getCurReference().getRefCounter().releaseLock();
        published = locked;
        locked = null;
        lockId = lockIDGen.incrementAndGet();
        //System.out.println("Thread " + Thread.currentThread().toString() + ": lock = null");
    }

    /** Duplicate and lock current buffer (needs to be called in synchronized context) */
    private void duplicateAndLock() {
        assert(locked == null);
        lockId = lockIDGen.incrementAndGet();
        lockTime = Time.getCoarse();
        lastKeepAlive = lockTime;
        locked = (BlackboardBuffer)readPort.getUnusedBufferRaw();
        //System.out.println("Thread " + Thread.currentThread().toString() + ": lock = " + locked.toString());
        locked.getManager().getCurrentRefCounter().setOrAddLock();
        copyBlackboardBuffer(published, locked);
        //locked.
        locked.lockID = lockId;
    }
//
//  /**
//   * Apply asynchronous change to blackboard
//   *
//   * @param mc Method call buffer with change
//   */
//  private void applyAsynchChange(int offset, BlackboardBuffer buffer) {
//      locked.getBuffer().put(offset, buffer.getBuffer(), 0, buffer.getSize());
//  }

    @Override
    protected boolean isLocked() {
        return locked != null;
    }

    @Override
    public void lockCheck() {
        long curTime = Time.getCoarse();
        if (lastKeepAlive + UNLOCK_TIMEOUT > curTime) {
            return;
        }

        synchronized (bbLock) {
            checkCurrentLock();
        }
    }

    @Override
    protected void directCommit(BlackboardBuffer newBuffer) {
        if (newBuffer == null) {
            return;
        }

        synchronized (bbLock) {
            if (locked != null) { // note: current lock is obsolete, since we have a completely new buffer
                //lockID = lockIDGen.incrementAndGet(); // make sure, next unlock won't do anything => done in commitLocked()
                locked.getManager().releaseLock(); // discard current lock
                locked = null;
            }

            // Clear any asynch change commands from queue, since they were for old buffer
            clearAsyncChangeTasks();

            // commit new buffer
            locked = newBuffer;
            commitLocked();
            assert(locked == null);

            // any threads that want to lock this?
            processPendingCommands();
        }
    }

    @Override
    protected boolean isSingleBuffered() {
        return false;
    }

    @Override
    protected BlackboardBuffer readLock(long timeout) throws MethodCallException {
        log(LogLevel.LL_WARNING, logDomain, "warning: Client must not attempt read lock on multi-buffered blackboard - Call failed");
        throw new MethodCallException(MethodCallException.Type.INVALID_PARAM);
    }

    @Override
    protected BlackboardBuffer readPart(int offset, int length, int timeout) {
        // current buffer (note: we get it from readPort, since -this way- call does not need to be executed in synchronized context)
        @Const BlackboardBuffer buffer = (BlackboardBuffer)readPort.getLockedUnsafeRaw();
        assert(buffer.getManager().isLocked());

        // prepare and set return value
        BlackboardBuffer send = (BlackboardBuffer)write.getUnusedBuffer(buffer.getType());
        send.resize(1, 1, length, false); // ensure minimal size
        send.getBuffer().put(0, buffer.getBuffer(), offset, length);
        send.bbCapacity = buffer.bbCapacity;
        send.elements = buffer.elements;
        send.elementSize = buffer.elementSize;

        // release old lock
        buffer.getManager().getCurrentRefCounter().releaseLock();

        // return buffer with one read lock
        send.getManager().getCurrentRefCounter().setLocks((byte)1);
        return send;
    }

    @Override
    protected BlackboardBuffer writeLock(long timeout) {
        synchronized (bbLock) {
            if (locked != null || pendingTasks()) { // make sure lock command doesn't "overtake" others
                checkCurrentLock();
                if (locked != null || pendingTasks()) {
                    if (timeout <= 0) {
                        return null; // we do not need to enqueue lock commands with zero timeout
                    } else {
                        // wait for lock
                        boolean haveLock = waitForLock(timeout);
                        if (!haveLock) {
                            return null; // we didn't get lock :-/
                        }
                    }
                }

                // ok... we have lock here
                assert(locked == null);
            }

            //System.out.println("Thread " + Thread.currentThread().toString() + ": handleLock");

            duplicateAndLock();
            assert(locked != null && locked.getManager().isLocked());
            locked.getManager().addLock();
            return locked; // return buffer with one read lock
            //mc.setReturn(locked, false);
        }
    }

    @Override
    protected void readUnlock(int lockId) throws MethodCallException {
        log(LogLevel.LL_WARNING, logDomain, "warning: Client must not attempt read unlock on multi-buffered blackboard - Call failed");
        throw new MethodCallException(MethodCallException.Type.INVALID_PARAM);
    }

    @Override
    protected void writeUnlock(BlackboardBuffer buf) {
        if (buf == null) {
            log(LogLevel.LL_WARNING, logDomain, "blackboard write unlock without providing buffer - strange indeed - ignoring");
            return;
        }

        synchronized (bbLock) {
            if (this.lockId != buf.lockID) {
                log(LogLevel.LL_DEBUG, logDomain, "Skipping outdated unlock");
                buf.getManager().releaseLock();
                return;
            }

            assert(locked != null);
            assert(buf.getManager().isLocked());

            if (buf == locked) {
                // we got the same buffer back - we only need to release one lock from method call
                buf.getManager().getCurrentRefCounter().releaseLock();
                assert(locked.getCurReference().isLocked());
            } else {
                locked.getManager().getCurrentRefCounter().releaseLock();
                locked = buf;
                //System.out.println("Thread " + Thread.currentThread().toString() + ": lock = " + locked.toString());
                assert(locked.getCurReference().isLocked());
            }

            commitLocked();
            processPendingCommands();
        }
    }
}
