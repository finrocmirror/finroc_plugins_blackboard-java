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
import org.finroc.jc.annotation.ConstMethod;
import org.finroc.jc.annotation.NonVirtual;
import org.finroc.jc.annotation.Ref;
import org.finroc.serialization.InputStreamBuffer;
import org.finroc.serialization.MemoryBuffer;
import org.finroc.serialization.OutputStreamBuffer;

/**
 * @author max
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

    /*Cpp
    BlackboardBuffer(BlackboardBuffer&& o) :
        rrlib::serialization::MemoryBuffer(std::_forward<rrlib::serialization::MemoryBuffer>(o)),
        bbCapacity(0),
        elements(0),
        elementSize(0)
    {
        std::_swap(bbCapacity, o.bbCapacity);
        std::_swap(elements, o.elements);
        std::_swap(elementSize, o.elementSize);
    }

    BlackboardBuffer& operator=(BlackboardBuffer&& o)
    {
        rrlib::serialization::MemoryBuffer::operator=(std::_forward<rrlib::serialization::MemoryBuffer>(o));
        std::_swap(bbCapacity, o.bbCapacity);
        std::_swap(elements, o.elements);
        std::_swap(elementSize, o.elementSize);
        return *this;
    }
     */

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

    /*Cpp
    void* getElementPointer(int index) {
        assert(index < elements);
        return getBufferPointer(index * elementSize);
    }

    const void* getElementPointer(int index) const {
        assert(index < elements);
        return getBufferPointer(index * elementSize);
    }

     */

    /**
     * @return Number of elements that fit in blackboard
     */
    @ConstMethod public int getBbCapacity() {
        return bbCapacity;
    }

    /**
     * @return Number of elements in blackboard
     */
    @ConstMethod public int getElements() {
        return elements;
    }

    /**
     * @return Element size
     */
    @ConstMethod public int getElementSize() {
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

    @Override @NonVirtual
    public void copyFrom(MemoryBuffer source) {
        copyFromBlackboardBuffer((BlackboardBuffer)source);
    }

    /**
     * Implementation of copy operation
     *
     * @param source Source to copy data from
     */
    public void copyFromBlackboardBuffer(@Const @Ref BlackboardBuffer source) {
        super.copyFrom(source);
        bbCapacity = source.bbCapacity;
        elements = source.elements;
        elementSize = source.elementSize;
    }
}
