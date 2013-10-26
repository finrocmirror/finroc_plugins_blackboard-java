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

import org.rrlib.finroc_core_utils.rtti.DataType;
import org.rrlib.finroc_core_utils.rtti.DataTypeBase;
import org.rrlib.finroc_core_utils.serialization.InputStreamBuffer;
import org.rrlib.finroc_core_utils.serialization.MemoryBuffer;
import org.rrlib.finroc_core_utils.serialization.OutputStreamBuffer;

/**
 * @author Max Reichardt
 *
 * Buffer containing blackboard data - in MCA style
 */
public class BlackboardBuffer extends MemoryBuffer {

//    /** Data type of this class */
//    @Const public final static DataTypeBase TYPE = DataTypeRegister.;

//    /** Lock ID of buffer - against outdated unlocks */
//    int lockID;

    /** Number of entries and entry size */
    int bbCapacity, elements, elementSize;

    /** Data type of this class */
    public final static DataTypeBase TYPE = new DataType<BlackboardBuffer>(BlackboardBuffer.class);

    @Override
    public void deserialize(InputStreamBuffer is) {
        //lockID = is.readInt();
        bbCapacity = is.readInt();
        elements = is.readInt();
        elementSize = is.readInt();
        super.deserialize(is);
    }

    @Override
    public void serialize(OutputStreamBuffer os) {
        //os.writeInt(lockID);
        os.writeInt(bbCapacity);
        os.writeInt(elements);
        os.writeInt(elementSize);
        super.serialize(os);
    }

    /**
     * Resize blackboard
     *
     * @param newCapacity new Capacity
     * @param newElements new current number of elements
     * @param newElementSize new size of elements
     * @param keepContents Keep current data - otherwise buffer will be nulled or random
     */
    public void resize(int newCapacity, int newElements, int newElementSize, boolean keepContents) {

        // remember old values
        int oldElemSize = elementSize;
        //int oldCapacity = bbCapacity;
        int oldElements = elements;
        int oldEnd = oldElemSize * oldElements;

        // set new values
        bbCapacity = newCapacity;
        elements = newElements;
        elementSize = newElementSize < 0 ? elementSize : newElementSize;
        int newEnd = elementSize * bbCapacity;

        ensureCapacity(newEnd, keepContents, oldEnd);
        if (!keepContents || elementSize == oldElemSize) {
            curSize = newElements * newElementSize;
            if (oldEnd < newEnd) { // zero new memory out
                backend.zeroOut(oldEnd, newEnd - oldEnd);
            }
            return;
        }

        // do some copying
        int copyElems = Math.min(oldElements, elements);
        int copySize = Math.min(oldElemSize, elementSize);

        if (elementSize < oldElemSize) {
            for (int i = 1; i < copyElems; i++) {
                backend.put(i * elementSize, backend, i * oldElemSize, copySize);
            }
        } else {
            // new element size > old element size... we need to be careful in this case => copy from back to front
            for (int i = copyElems - 1; i > 0; i--) {
                // copy from back to front
                int srcPtr =  i * oldElemSize + copySize;
                int destPtr = i * elementSize + copySize;
                backend.zeroOut(destPtr, elementSize - oldElemSize);
                for (int j = 0; j < copySize; j++) {
                    srcPtr--;
                    destPtr--;
                    backend.putByte(destPtr, backend.getByte(srcPtr));
                }
            }
        }

        if (oldEnd < newEnd) { // zero new memory out
            backend.zeroOut(oldEnd, newEnd - oldEnd);
        }

        curSize = newElements * newElementSize;
    }

    /**
     * @return Number of elements that fit in blackboard
     */
    public int getBbCapacity() {
        return bbCapacity;
    }

    /**
     * @return Number of elements in blackboard
     */
    public int getElements() {
        return elements;
    }

    /**
     * @return Element size
     */
    public int getElementSize() {
        return elementSize;
    }

    /**
     * Offset of element in buffer (in bytes)
     *
     * @param index Index of element
     * @return Offset
     */
    public int getElementOffset(int index) {
        return index * elementSize;
    }

    @Override
    public void copyFrom(MemoryBuffer source) {
        copyFromBlackboardBuffer((BlackboardBuffer)source);
    }

    /**
     * Implementation of copy operation
     *
     * @param source Source to copy data from
     */
    public void copyFromBlackboardBuffer(BlackboardBuffer source) {
        super.copyFrom(source);
        bbCapacity = source.bbCapacity;
        elements = source.elements;
        elementSize = source.elementSize;
    }
}
