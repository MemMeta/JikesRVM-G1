package org.mmtk.policy.region;

import org.mmtk.utility.Log;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoBoundsCheck;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.WordArray;

@Uninterruptible
public class CardTable {
  private static final byte HOTNESS_THRESHOLD = 4;
  private static final byte[] table = new byte[Card.CARDS_IN_HEAP];
  private static final byte[] hotnessTable = new byte[Card.CARDS_IN_HEAP];

  @Inline
  @NoBoundsCheck
  public static void clear() {
    for (int i = 0; i < Card.CARDS_IN_HEAP; i++)
      table[i] = 0;
  }

  @Inline
  public static void assertAllCleared() {
    if (!VM.VERIFY_ASSERTIONS) return;
    for (int i = 0; i < Card.CARDS_IN_HEAP; i++) {
      VM.assertions._assert(table[i] == 0);
    }
  }

  @Inline
  @NoBoundsCheck
  public static boolean increaseHotness(Address card) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(Card.isAligned(card));
    int index = getIndex(card);
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(index >= 0 && index < Card.CARDS_IN_HEAP);
    int hotness = hotnessTable[index];
    if (hotness >= HOTNESS_THRESHOLD) return true;
    hotnessTable[index] += 1;
    return false;
  }

  @Inline
  @NoBoundsCheck
  public static void clearAllHotnessPar(int id, int workers) {
    int totalSize = (Card.CARDS_IN_HEAP + workers - 1) / workers;
    int start = totalSize * id;
    int _limit = totalSize * (id + 1);
    int limit = _limit > Card.CARDS_IN_HEAP ? Card.CARDS_IN_HEAP : _limit;
    for (int i = start; i < limit; i++)
      hotnessTable[i] = 0;
  }

  @Inline
  private static int getIndex(Address card) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(Card.isAligned(card));
    return card.diff(VM.HEAP_START).toWord().rshl(Card.LOG_BYTES_IN_CARD).toInt();
  }

  @Inline
  @NoBoundsCheck
  public static byte get(Address card) {
    if (VM.VERIFY_ASSERTIONS) {
      if (!Card.isAligned(card)) Log.writeln("Card is not aligned ", card);
      VM.assertions._assert(Card.isAligned(card));
    }
    int index = getIndex(card);
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(index >= 0 && index < Card.CARDS_IN_HEAP);
    return table[index];
  }

  @Inline
  @NoBoundsCheck
  public static void set(Address card, byte value) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(Card.isAligned(card));
    int index = getIndex(card);
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(index >= 0 && index < Card.CARDS_IN_HEAP);
    table[index] = value;
  }
}
