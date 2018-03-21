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
package org.mmtk.plan.concurrent.concmark;

import org.mmtk.plan.Phase;
import org.mmtk.plan.Trace;
import org.mmtk.plan.TraceLocal;
import org.mmtk.policy.MarkRegion;
import org.mmtk.policy.Space;
import org.mmtk.utility.Log;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;

/**
 * This class implements the core functionality for a transitive
 * closure over the heap graph.
 */
@Uninterruptible
public class ConcMarkMarkTraceLocal extends TraceLocal {

  public ConcMarkMarkTraceLocal(Trace trace) {
    super(trace);
  }

  /****************************************************************************
   *
   * Externally visible Object processing and tracing
   */

  /**
   * {@inheritDoc}
   */
  @Override
  protected boolean overwriteReferenceDuringTrace() {
    return false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isLive(ObjectReference object) {
    if (object.isNull()) return false;
    if (Space.isInSpace(ConcMark.CONC_MARK, object))
      return ConcMark.markRegionSpace.isLive(object);
    return super.isLive(object);
  }

  @Override
  public void prepare() {
    super.prepare();
    for (Address region : MarkRegion.iterate()) {
      MarkRegion.setUsedSize(region, 0);
      MarkRegion.setRelocationState(region, false);
    };
  }

  @Override
  @Inline
  public ObjectReference traceObject(ObjectReference object) {
    Log.writeln("Trace conc=" + Phase.concurrentPhaseActive());
    if (object.isNull()) return object;
    if (Space.isInSpace(ConcMark.CONC_MARK, object))
      return ConcMark.markRegionSpace.traceMarkObject(this, object);
    return super.traceObject(object);
  }

  /**
   * Will this object move from this point on, during the current trace ?
   *
   * @param object The object to query.
   * @return True if the object will not move.
   */
  @Override
  public boolean willNotMoveInCurrentCollection(ObjectReference object) {
    if (Space.isInSpace(ConcMark.CONC_MARK, object)) {
      return true;
    } else {
      return super.willNotMoveInCurrentCollection(object);
    }
  }
}
