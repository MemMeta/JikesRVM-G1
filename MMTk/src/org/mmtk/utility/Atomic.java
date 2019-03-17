package org.mmtk.utility;

import org.mmtk.vm.VM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Offset;

@Uninterruptible
public class Atomic {
  @Uninterruptible
  public static class Int {
    private int value;

    @Inline
    public final void set(int v) {
      ObjectReference.fromObject(this).toAddress().store(v);
    }

    @Inline
    public final int get() {
      return ObjectReference.fromObject(this).toAddress().prepareInt();
    }

    @Inline
    public final boolean attempt(int oldValue, int newValue) {
      Address pointer = ObjectReference.fromObject(this).toAddress();
      return pointer.attempt(oldValue, newValue);
    }

    @Inline
    public final int add(int value) {
      Address pointer = ObjectReference.fromObject(this).toAddress();
      int oldValue, newValue;
      do {
        oldValue = pointer.prepareInt();
        newValue = oldValue + value;
      } while (!pointer.attempt(oldValue, newValue));
      return oldValue;
    }

    @Inline
    public final void addNonAtomic(int value) {
      set(get() + value);
    }
  }

  @Uninterruptible
  public static class Long {
    private final long[] value = new long[] { 0 };

    @Inline
    public final void set(long v) {
      value[0] = v;
    }

    @Inline
    public final long get() {
      return value[0];
    }

    @Inline
    public final boolean attempt(long oldValue, long newValue) {
      return VM.memory.attemptLong(value, Offset.fromIntZeroExtend(0), oldValue, newValue);
    }

    @Inline
    public final long add(long value) {
      Address pointer = ObjectReference.fromObject(this.value).toAddress();
      long oldValue, newValue;
      do {
        oldValue = pointer.loadLong();
        newValue = oldValue + value;
      } while (!attempt(oldValue, newValue));
      return oldValue;
    }
  }
}
