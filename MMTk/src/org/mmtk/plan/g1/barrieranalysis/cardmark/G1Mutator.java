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
package org.mmtk.plan.g1.barrieranalysis.cardmark;

import org.mmtk.policy.region.Card;
import org.mmtk.policy.region.CardTable;
import org.mmtk.utility.Constants;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Word;

@Uninterruptible
public class G1Mutator extends org.mmtk.plan.g1.barrieranalysis.baseline.G1Mutator {
  @Inline
  @Override
  protected void cardMarkingBarrier(ObjectReference src) {
    if (G1.MEASURE_TAKERATE) G1.barrierFast.inc(1);
    int index = CardTable.getIndex(src);
    if (CardTable.get(index) == Card.NOT_DIRTY) {
      if (G1.MEASURE_TAKERATE) G1.barrierSlow.inc(1);
      CardTable.set(index, Card.DIRTY);
      rsEnqueue(Word.fromIntZeroExtend(index).lsh(Card.LOG_BYTES_IN_CARD).toAddress());
    }
  }

  @Inline
  @NoInline
  private void rsEnqueue(Address card) {
    if (dirtyCardQueue.isZero()) acquireDirtyCardQueue();
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(dirtyCardQueueCursor.plus(4).LE(dirtyCardQueueLimit));
    dirtyCardQueueCursor.store(card);
    dirtyCardQueueCursor = dirtyCardQueueCursor.plus(Constants.BYTES_IN_ADDRESS);
    if (dirtyCardQueueCursor.GE(dirtyCardQueueLimit)) {
      dirtyCardQueueCursor = dirtyCardQueue;
    }
  }

  @Inline
  @Override
  public void objectReferenceWrite(ObjectReference src, Address slot, ObjectReference tgt, Word metaDataA, Word metaDataB, int mode) {
    VM.barriers.objectReferenceWrite(src, tgt, metaDataA, metaDataB, mode);
    cardMarkingBarrier(src);
  }

  @Inline
  @Override
  public boolean objectReferenceTryCompareAndSwap(ObjectReference src, Address slot, ObjectReference old, ObjectReference tgt, Word metaDataA, Word metaDataB, int mode) {
    boolean result = VM.barriers.objectReferenceTryCompareAndSwap(src, old, tgt, metaDataA, metaDataB, mode);
    cardMarkingBarrier(src);
    return result;
  }
}