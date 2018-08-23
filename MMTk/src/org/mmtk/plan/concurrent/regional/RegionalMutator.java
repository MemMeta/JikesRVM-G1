/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.mmtk.plan.concurrent.regional;

import org.mmtk.plan.MutatorContext;
import org.mmtk.plan.StopTheWorldMutator;
import org.mmtk.plan.TraceWriteBuffer;
import org.mmtk.plan.concurrent.ConcurrentMutator;
import org.mmtk.policy.Region;
import org.mmtk.policy.Space;
import org.mmtk.utility.alloc.Allocator;
import org.mmtk.utility.alloc.RegionAllocator;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;

/**
 * This class implements <i>per-mutator thread</i> behavior
 * and state for the <i>RegionalCopy</i> plan, which implements a full-heap
 * semi-space collector.<p>
 *
 * Specifically, this class defines <i>RegionalCopy</i> mutator-time allocation
 * and per-mutator thread collection semantics (flushing and restoring
 * per-mutator allocator state).<p>
 *
 * See {@link Regional} for an overview of the semi-space algorithm.
 *
 * @see Regional
 * @see RegionalCollector
 * @see StopTheWorldMutator
 * @see MutatorContext
 */
@Uninterruptible
public class RegionalMutator extends ConcurrentMutator {

  /****************************************************************************
   * Instance fields
   */
  protected final RegionAllocator mc;
  private final TraceWriteBuffer remset;

  /****************************************************************************
   *
   * Initialization
   */

  /**
   * Constructor
   */
  public RegionalMutator() {
    mc = new RegionAllocator(Regional.regionSpace, false);
    remset = new TraceWriteBuffer(global().markTrace);
  }

  /****************************************************************************
   *
   * Mutator-time allocation
   */

  /**
   * {@inheritDoc}
   */
  @Override
  @Inline
  public Address alloc(int bytes, int align, int offset, int allocator, int site) {
    if (allocator == Regional.ALLOC_MC) {
      if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(bytes <= Region.BYTES_IN_BLOCK);
      return mc.alloc(bytes, align, offset);
    } else {
      return super.alloc(bytes, align, offset, allocator, site);
    }
  }

  @Override
  @Inline
  public void postAlloc(ObjectReference object, ObjectReference typeRef, int bytes, int allocator) {
    if (allocator == Regional.ALLOC_MC) {
      Regional.regionSpace.postAlloc(object, bytes);
    } else {
      super.postAlloc(object, typeRef, bytes, allocator);
    }
  }

  @Override
  public Allocator getAllocatorFromSpace(Space space) {
    if (space == Regional.regionSpace) return mc;
    return super.getAllocatorFromSpace(space);
  }

  /****************************************************************************
   *
   * Collection
   */

  /**
   * {@inheritDoc}
   */
  @Override
  @Inline
  public void collectionPhase(short phaseId, boolean primary) {
    //Log.write("[Mutator] ");
    //Log.writeln(Phase.getName(phaseId));
    if (phaseId == Regional.PREPARE) {
      mc.reset();
      super.collectionPhase(phaseId, primary);
      return;
    }

    if (phaseId == Regional.RELEASE) {
      mc.reset();
      super.collectionPhase(phaseId, primary);
      return;
    }

    if (phaseId == Regional.EVACUATE_PREPARE) {
      mc.reset();
      super.collectionPhase(Regional.PREPARE, primary);
      return;
    }

    if (phaseId == Regional.EVACUATE_RELEASE) {
      mc.reset();
      super.collectionPhase(Regional.RELEASE, primary);
      return;
    }

    super.collectionPhase(phaseId, primary);
  }

  @Override
  public void flushRememberedSets() {
    remset.flush();
    mc.reset();
    assertRemsetsFlushed();
  }

  @Override
  protected void checkAndEnqueueReference(ObjectReference ref) {
    if (ref.isNull()) return;

    if (Space.isInSpace(Regional.RS, ref)) Regional.regionSpace.traceMarkObject(remset, ref);
    else if (Space.isInSpace(Regional.IMMORTAL, ref)) Regional.immortalSpace.traceObject(remset, ref);
    else if (Space.isInSpace(Regional.LOS, ref)) Regional.loSpace.traceObject(remset, ref);
    else if (Space.isInSpace(Regional.NON_MOVING, ref)) Regional.nonMovingSpace.traceObject(remset, ref);
    else if (Space.isInSpace(Regional.SMALL_CODE, ref)) Regional.smallCodeSpace.traceObject(remset, ref);
    else if (Space.isInSpace(Regional.LARGE_CODE, ref)) Regional.largeCodeSpace.traceObject(remset, ref);
  }

  @Override
  public final void assertRemsetsFlushed() {
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(remset.isFlushed());
    }
  }

  @Inline
  Regional global() {
    return (Regional) VM.activePlan.global();
  }
}