/*
 * (C) Copyright IBM Corp. 2001
 */
//$Id$

package com.ibm.JikesRVM.memoryManagers;

import VM;
import VM_Magic;
import VM_Constants;
import VM_BootRecord;
import VM_Processor;
import VM_Scheduler;
import VM_Time;
import VM_Processor;
import VM_PragmaInterruptible;
import VM_PragmaUninterruptible;
import VM_ObjectModel;
import VM_Address;
import VM_Thread;

/**
 * Write buffers used by the initial set of RVM generational collectors.
 * <p>
 * The initial set of RVM generational collectors all use the same write
 * barrier that adds to the write buffer references to "old" objects that have
 * had reference fields modified.  All the compilers generate the barrier code
 * to do this when the static final boolean flag "writeBarrier" in VM_Allocator
 * is set to true.
 * <p>
 * The write buffers are local to each VM_Processor.  Each VM_Processor has an
 * initial buffer allocated when it is created (an int[]) called 
 * modifiedOldObjects).  Additional buffers are allocated as needed.  
 * At the present time, the additional buffers are allocated from the "C" 
 * heap.  During a collection, the references in the write buffers become 
 * part of the "Root Set" for that collection.  After the
 * buffer entries have been processed, the extra buffers are freed.  
 * The initial buffer is always retained and is reset to an empty state.
 * <p>
 * The size of each buffer is specified by WRITE_BUFFER_SIZE.
 * <pre>
 * Within a VM_Processor:
 *    modifiedOldObjects    ALWAYS points the initial array of ints
 *    modifiedOldObjectsTop is the current position in the "current" buffer
 *    modifiedOldObjectsMax is the last useable position in the "current" 
 *                          buffer
 * </pre>
 * @see VM_Processor
 * @see VM_Barriers        for write barrier generated by baseline compiler
 * @see VM_WriteBarrier    write barrier generated by the OPT compiler
 * @see VM_Allocator       for processing of write buffers during collection
 *
 * @author Stephen Smith
 */ 
public class VM_WriteBuffer implements VM_Constants, 
				       VM_GCConstants {

  private static final boolean trace = false;
  
  private static final boolean DEBUG_WRITEBUFFER = false;

  static final int WRITE_BUFFER_SIZE = 32*1024*4;

  /**
   * Grow the write buffer for the current executing VM_Processor.  Called from
   * generated write barrier code when the current buffer is full.  Allocates
   * an additional buffer, appends it to the end of the current list of
   * buffers, and resets the pointers in VM_Processor used by the write
   * barrier to insert entries into the buffer.  Presently the additonal
   * buffers are allocated from the C heap via sysMalloc.  This may change
   * to allcoate from the RVM Large Object Heap in the future.
   */
  static void growWriteBuffer() throws VM_PragmaUninterruptible {
    VM_Processor vp = VM_Processor.getCurrentProcessor();

    if (VM.VerifyAssertions) VM.assert(vp.modifiedOldObjectsTop==vp.modifiedOldObjectsMax);

    VM_Address newBufAddr = VM_Address.fromInt(VM.sysCall1(VM_BootRecord.the_boot_record.sysMallocIP,
							   WRITE_BUFFER_SIZE));
    if (newBufAddr.isZero()) {
      VM.sysWrite(" In growWriteBuffer, call to sysMalloc returned 0 \n");
      VM.sysExit(1800);
    }

    // set last word in current buffer to address of next buffer
    VM_Magic.setMemoryAddress(vp.modifiedOldObjectsTop.add(4), newBufAddr);
    // set fptr in new buffer to null, to identify it as last
    VM_Magic.setMemoryWord(newBufAddr.add(WRITE_BUFFER_SIZE-4),0);
    // set writebuffer pointers in processor object for stores into new buffer
    vp.modifiedOldObjectsTop = newBufAddr.sub(4);
    vp.modifiedOldObjectsMax = newBufAddr.add(WRITE_BUFFER_SIZE - 8);
  }

  static void setupProcessor(VM_Processor p) throws VM_PragmaInterruptible {
    if (VM.VerifyAssertions) VM.assert(VM_Collector.NEEDS_WRITE_BARRIER == true);

    // setupProcessor will be called twice for the PRIMORDIAL processor.
    // Once while building the bootImage (VM.runningVM=false) and again when
    // the VM is booting (VM.runningVM=true)
    //
    if (p.id == VM_Scheduler.PRIMORDIAL_PROCESSOR_ID) {
      if (!VM.runningVM) {
	// allocate buffer, but cannot set TOP and MAX addresses
	p.modifiedOldObjects = new int[WRITE_BUFFER_SIZE >> 2];
      } else {
	if (VM.VerifyAssertions) VM.assert(p.modifiedOldObjects != null);
	// initialize write buffer pointers, setting "max" so as to reserve last slot
	// for ptr to next write buffer (initially null). see also: VM.boot() write buffer init.
	p.modifiedOldObjectsTop = VM_Magic.objectAsAddress(p.modifiedOldObjects).sub(4);
	p.modifiedOldObjectsMax = p.modifiedOldObjectsTop.add((p.modifiedOldObjects.length << 2) - 4);
      }
    } else {
      // setup for processors created while the VM is running.
      // allocate buffer and initialize integer pointers to TOP & MAX
      if (VM.VerifyAssertions) VM.assert(VM.runningVM == true);
      p.modifiedOldObjects = new int[WRITE_BUFFER_SIZE >> 2];
      p.modifiedOldObjectsTop = VM_Magic.objectAsAddress(p.modifiedOldObjects).sub(4);
      p.modifiedOldObjectsMax = p.modifiedOldObjectsTop.add((p.modifiedOldObjects.length << 2) - 4);
    }
  }

  /**
   * Process the write buffer entries in the write buffers associated with
   * the specified VM_Processor.  Called by collector threads during collection.
   * Resets the VM_Processors write buffer to empty, and frees any additional
   * buffers allocated during the preceeding mutator cycle.
   *
   * @param vp  VM_Processor whose buffers are to be processed
   */
  static void processWriteBuffer(VM_Processor vp) throws VM_PragmaUninterruptible {
    int count = 0;
    VM_Address end;
    double startTime;

    if (trace) {
      VM_Scheduler.trace("VM_WriteBuffer", "in processWriteBuffer");
      startTime = VM_Time.now();
    }
    
    // reset vp.modifiedOldObjectsTop pointer to beginning of FIRST buffer (the buffer
    // about to be processed). set to beginning - 4, since "store with update" instruction
    // will increment first, then store
    // 
    // This collector should NOT be adding more refs to buffer during rest of this
    // collection...if it does, check out what they are
    //
    VM_Address top = vp.modifiedOldObjectsTop; // last occuppied slot in "current" buffer at start of GC
    vp.modifiedOldObjectsTop = VM_Magic.objectAsAddress(vp.modifiedOldObjects).sub(4);
    vp.modifiedOldObjectsMax = vp.modifiedOldObjectsTop.add((vp.modifiedOldObjects.length << 2) - 4);
    
    VM_Address start = VM_Magic.objectAsAddress(vp.modifiedOldObjects);  // first buffer
    while ( !start.isZero() ) {
      VM_Address lastSlotAddr = start.add(WRITE_BUFFER_SIZE - 4);
      // determine if this is last buffer or not, by seeing if there is a next ptr
      if ( VM_Magic.getMemoryWord(lastSlotAddr) == 0 )
	end = top;  // last buffer, stop at last filled in slot in "current" buffer
      else
	end = lastSlotAddr.sub(4); // stop at last entry in buffer
      
      while ( start.LE(end) ) {
	VM_Address wbref = VM_Magic.getMemoryAddress( start );
	
	VM_AllocatorHeader.setBarrierBit(VM_Magic.addressAsObject(wbref));

	// Call method in specific collector to process write buffer entry
	VM_Allocator.processWriteBufferEntry(wbref);

	if (trace) count++;

	start = start.add(4);
      }
      start = VM_Magic.getMemoryAddress( lastSlotAddr );  // get addr of next buffer
    }
    
    if (VM.VerifyAssertions)
      VM.assert( (vp.modifiedOldObjectsTop.LE(vp.modifiedOldObjectsMax)) &&
		 (vp.modifiedOldObjectsTop.GE(VM_Magic.objectAsAddress(vp.modifiedOldObjects).sub(4))) );
    
    freeBuffers(vp);
    
    if (trace) {
      VM_Scheduler.outputMutex.lock();
      VM.sysWrite(VM_Processor.getCurrentProcessorId(),false);
      VM.sysWrite(" VM_WriteBuffer processing time = ");
      VM.sysWrite((int)((VM_Time.now() - startTime)*1000),false);
      VM.sysWrite(" ms, number of entries processed = ");
      VM.sysWrite(count,false);
      VM.sysWrite("\n");
      VM_Scheduler.outputMutex.unlock();
    }
  }

  /**
   * Reset the barrier bits for the object references in the write buffers
   * of the specified VM_Processor & reset the write buffers to empty.
   * The barrier bits are in the status word of the object headers, and
   * are used by the generated write barrier code to decide if an entry
   * should be generated for an object being modified. Resetting the bits
   * will cause entries to be generated the next time the objects are
   * modified.
   * 
   * Called during garbage collection to reset the barrier bits for
   * write buffer entries generated during the collection process.
   * Ideally this should not occur, but has been observed to happen,
   * so this method is used to correct the problem.
   *
   * @param vp  VM_Processor whose write buffer entries are to be reset
   */
  static void resetBarrierBits(VM_Processor vp) throws VM_PragmaUninterruptible {
    int count = 0;
    
    if (VM.VerifyAssertions) VM.assert(VM_Allocator.writeBarrier == true);
    
    if (trace) VM_Scheduler.trace("VM_WriteBuffer", "in resetBarrierBits");
    
    if (vp.modifiedOldObjects == null) return;
    
    // reset vp.modifiedOldObjectsTop pointer to beginning of FIRST buffer (the buffer
    // about to be processed). set to beginning - 4, since "store with update" instruction
    // will increment first, then store
    // 
    // This collector should NOT be adding more refs to buffer during rest of this
    // collection...if it does, check out what they are
    //
    VM_Address top = vp.modifiedOldObjectsTop; // last occuppied slot in "current" buffer at start of GC
    vp.modifiedOldObjectsTop = VM_Magic.objectAsAddress(vp.modifiedOldObjects).sub(4);
    vp.modifiedOldObjectsMax = vp.modifiedOldObjectsTop.add((vp.modifiedOldObjects.length << 2) - 4);
    
    VM_Address start = VM_Magic.objectAsAddress(vp.modifiedOldObjects);  // first buffer
    while ( !start.isZero() ) {
      VM_Address lastSlotAddr = start.add(WRITE_BUFFER_SIZE - 4);
      // determine if this is last buffer or not, by seeing if there is a next ptr
      VM_Address end;
      if ( VM_Magic.getMemoryWord(lastSlotAddr) == 0 )
	end = top;  // last buffer, stop at last filled in slot in "current" buffer
      else
	end = lastSlotAddr.sub(4); // stop at last entry in buffer
      
      if (trace) count += end.diff(start) >> 2;
      
      while ( start.LE(end) ) {
	VM_Address wbref = VM_Magic.getMemoryAddress( start );
	VM_AllocatorHeader.setBarrierBit(VM_Magic.addressAsObject(wbref)); // IS THIS CORRECT???
	start = start.add(4);
      }
      start = VM_Magic.getMemoryAddress( lastSlotAddr );  // get addr of next buffer
    }
    
    if (VM.VerifyAssertions)
      VM.assert( (vp.modifiedOldObjectsTop.LE(vp.modifiedOldObjectsMax)) &&
		 (vp.modifiedOldObjectsTop.GE(VM_Magic.objectAsAddress(vp.modifiedOldObjects).sub(4))) );
    
    freeBuffers(vp);
    
    if (trace) VM_Scheduler.trace("VM_WriteBuffer","resetBarrierBits: count = ",count);

  }
  
  /**
   * Empty a processors write buffer, moving object references in the buffer
   * to the calling threads (a VM_CollectorThread) work queue of objects 
   * to be scanned. Resets the barrier bits for the moved references, and
   * resets the write buffer to an empty state.
   *
   * Only used when the collection process allows collection to happen with
   * a subset of the processors.  In that case, it is called for those
   * processors not participating in a collection.
   *
   * @param vp  VM_Processor whose write buffer entries are to be moved
   */
  static void  moveToWorkQueue (VM_Processor vp) throws VM_PragmaUninterruptible {
    int count = 0;
    VM_Address top = vp.modifiedOldObjectsTop;    // last occuppied slot in "current" buffer at start of GC
    
    // reset buffer to empty state...see above for why we do this early
    //
    vp.modifiedOldObjectsTop = VM_Magic.objectAsAddress(vp.modifiedOldObjects).sub(4);
    vp.modifiedOldObjectsMax = vp.modifiedOldObjectsTop.add((vp.modifiedOldObjects.length << 2) - 4);
    
    VM_Address start = VM_Magic.objectAsAddress(vp.modifiedOldObjects);  // first buffer
    while ( !start.isZero() ) {
      VM_Address lastSlotAddr = start.add(WRITE_BUFFER_SIZE - 4);
      // determine if this is last buffer or not, by seeing if there is a next ptr
      VM_Address end;
      if ( VM_Magic.getMemoryWord(lastSlotAddr) == 0 )
	end = top;  // last buffer, stop at last filled in slot in "current" buffer
      else
	end = lastSlotAddr.sub(4); // stop at last entry in buffer
      
      while ( start.LE(end) ) {
	VM_Address wbref = VM_Magic.getMemoryAddress( start );
	
	VM_AllocatorHeader.setBarrierBit(VM_Magic.addressAsObject(wbref));

	// added writebuffer ref to work queue of executing collector thread
	VM_GCWorkQueue.putToWorkBuffer( wbref );
	
	start = start.add(4);
      }
      
      start = VM_Magic.getMemoryAddress(lastSlotAddr);  // get addr of next buffer
    }
    
    if (VM.VerifyAssertions)
      VM.assert( (vp.modifiedOldObjectsTop.LE(vp.modifiedOldObjectsMax)) &&
		 (vp.modifiedOldObjectsTop.GE(VM_Magic.objectAsAddress(vp.modifiedOldObjects).sub(4))) );
    
    freeBuffers(vp);

  }

  /**
   * Check that VM_Processor write buffer is empty, dumping the
   * contents of the write buffer.  Will reset the write buffer to empty.
   *  
   * ONLY FOR DEBUGGING - MAY NOT PROPERLY RESET BARRIER BITS
   *
   * @param vp VM_Processor to check
   */
  static void checkForEmpty(VM_Processor vp) throws VM_PragmaUninterruptible {
    if ( vp.modifiedOldObjectsTop.NE(VM_Magic.objectAsAddress(vp.modifiedOldObjects).sub(4)) ) {
      
      if (DEBUG_WRITEBUFFER) { 
	VM_Scheduler.trace("VM_WriteBuffer","WARNING: BUFFER NOT EMPTY");
	VM_Address end = vp.modifiedOldObjectsTop;
	VM_Address start = VM_Magic.objectAsAddress(vp.modifiedOldObjects);
	if ( end.GE(start) && end.LT(start.add(WRITE_BUFFER_SIZE-8)) )
	  while ( start.LE(end) ) {
	    VM_Address wbref = VM_Magic.getMemoryAddress( start );
	    VM_Scheduler.trace("************","value of ref",wbref);
	    VM_ObjectModel.dumpHeader(wbref);
	    start = start.add(4);
	  }
      }  // DEBUG_WRITEBUFFER
      
      // force buffer empty...if these are valid object their barrier bits ARE OFF
      // THIS MAY INDICATE AN ERROR
      // ... 12/01/98 - looks like these are putfields to stackmap iterators, so they
      // can be reset, since these are temporary values used during scanning a stack
      //
      vp.modifiedOldObjectsTop = VM_Magic.objectAsAddress(vp.modifiedOldObjects).sub(4);
      vp.modifiedOldObjectsMax = vp.modifiedOldObjectsTop.add((vp.modifiedOldObjects.length << 2) - 4);
      freeBuffers(vp);
    }
  } 

  /**
   * Free all additional write buffers, keeping the first, and reset
   * the write buffer to an empty state.
   *
   * @param vp VM_Processor whose extra buffers are to be freed
   */
  private static void freeBuffers(VM_Processor vp) throws VM_PragmaUninterruptible {

    // remember address of last slot in first buffer (the next buffer pointer)
    VM_Address temp = VM_Magic.objectAsAddress(vp.modifiedOldObjects).add(WRITE_BUFFER_SIZE - 4);
    VM_Address buf = VM_Address.fromInt(VM_Magic.getMemoryWord( temp ));

    while( !buf.isZero() ) {
      VM_Address nextbuf = VM_Magic.getMemoryAddress( buf.add(WRITE_BUFFER_SIZE - 4) );
      VM.sysCall1(VM_BootRecord.the_boot_record.sysFreeIP, buf.toInt());
      buf = nextbuf;
    }
    // reset next pointer in first buffer to null
    VM_Magic.setMemoryWord( temp, 0 );
  }
}
