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
package org.mmtk.plan.zgc;

import org.mmtk.plan.StopTheWorldConstraints;
import org.mmtk.policy.CopySpace;
import org.mmtk.policy.zgc.ZObjectHeader;
import org.vmmagic.pragma.Uninterruptible;

/**
 * SemiSpace common constants.
 */
@Uninterruptible
public class ZGCConstraints extends StopTheWorldConstraints {
  @Override
  public boolean movesObjects() {
    return true;
  }
  @Override
  public int gcHeaderBits() {
    return ZObjectHeader.LOCAL_GC_BITS_REQUIRED;
  }
  @Override
  public int gcHeaderWords() {
    return ZObjectHeader.GC_HEADER_WORDS_REQUIRED;
  }
  @Override
  public int numSpecializedScans() {
    return 1;
  }
}