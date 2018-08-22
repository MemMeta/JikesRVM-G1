package org.mmtk.policy;

import org.mmtk.plan.Plan;
import org.mmtk.plan.TraceLocal;
import org.mmtk.plan.TransitiveClosure;
import org.mmtk.plan.concurrent.g1.G1;
import org.mmtk.utility.Constants;
import org.mmtk.utility.alloc.EmbeddedMetaData;
import org.mmtk.utility.alloc.LinearScan;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.*;

@Uninterruptible
public class RemSet {
  private final static AddressArray rememberedSets; // Array<RemSet: Array<PRT>>
  public final static int TOTAL_REGIONS;
  public final static int REMSET_PAGES;
  public final static int PAGES_IN_PRT;
  private final static int INTS_IN_PRT;

  static {
    if (Region.USE_CARDS) {
      Word heapSize = VM.HEAP_END.diff(VM.HEAP_START).toWord();
      TOTAL_REGIONS = heapSize.rshl(Region.LOG_BYTES_IN_BLOCK).toInt();
      rememberedSets = AddressArray.create(TOTAL_REGIONS);//new int[TOTAL_REGIONS][][];
      REMSET_PAGES = ceilDiv(TOTAL_REGIONS << Constants.LOG_BYTES_IN_ADDRESS, Constants.BYTES_IN_PAGE);
      int cardsPerRegion = Region.BYTES_IN_BLOCK >>> Region.Card.LOG_BYTES_IN_CARD;
      int bytesInPRT = cardsPerRegion >>> Constants.LOG_BITS_IN_BYTE;
      INTS_IN_PRT = ceilDiv(bytesInPRT, Constants.BYTES_IN_INT);
      PAGES_IN_PRT = ceilDiv(bytesInPRT, Constants.BYTES_IN_PAGE);
    } else {
      rememberedSets = null;
      TOTAL_REGIONS = 0;
      REMSET_PAGES = 0;
      PAGES_IN_PRT = 0;
      INTS_IN_PRT = 0;
    }
  }

  @Uninterruptible
  private static class PerRegionTable {
    @Inline
    private static boolean attemptBitInBuffer(Address buf, int index, boolean newBit) {
      int intIndex = index >>> Constants.LOG_BITS_IN_INT;
      int bitIndex = index ^ (intIndex << Constants.LOG_BITS_IN_INT);
      Offset offset = Offset.fromIntZeroExtend(intIndex << Constants.LOG_BYTES_IN_INT);
      Address pointer = buf.plus(offset);
//      if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(pointer.LT(buf.plus(Constants.BYTES_IN_PAGE * PAGES_IN_PRT)));
      int oldValue, newValue;
      do {
        // Get old int
        oldValue = buf.plus(intIndex << Constants.LOG_BYTES_IN_INT).loadInt();//buf[intIndex];
        // Build new int
        if (newBit) {
          newValue = oldValue | (1 << (31 - bitIndex));
        } else {
          newValue = oldValue & (~(1 << (31 - bitIndex)));
        }
        if (oldValue == newValue) return false; // this bit has been set by other threads
      } while (!pointer.attempt(oldValue, newValue));
      return true;
    }

    @Inline
    static boolean getBit(Address buf, int index) {
      int intIndex = index >>> Constants.LOG_BITS_IN_INT;
      int bitIndex = index ^ (intIndex << Constants.LOG_BITS_IN_INT);
      int entry = buf.plus(intIndex << Constants.LOG_BYTES_IN_INT).loadInt();//buf[intIndex];
      return (entry & (1 << (31 - bitIndex))) != 0;
    }

    @Inline
    static boolean contains(Address prt, Address card) {
      int index = card.diff(Region.of(card)).toInt() >>> Region.Card.LOG_BYTES_IN_CARD;
      return getBit(prt, index);
    }

    @Inline
    static boolean insert(Address prt, Address card) {
      int index = card.diff(Region.of(card)).toInt() >>> Region.Card.LOG_BYTES_IN_CARD;
      return attemptBitInBuffer(prt, index, true);
    }

    @Inline
    static boolean remove(Address prt, Address card) {
      int index = card.diff(Region.of(card)).toInt() >>> Region.Card.LOG_BYTES_IN_CARD;
      return attemptBitInBuffer(prt, index, false);
    }
  }

  @Inline
  private static void lock(Address region) {
    Address remSetLock = Region.metaDataOf(region, Region.METADATA_REMSET_LOCK_OFFSET);
    //int oldValue;
    do {
      //oldValue = remSetLock.prepareInt();
    } while (!remSetLock.attempt(0, 1));
  }

  @Inline
  private static void unlock(Address region) {
    Address remSetLock = Region.metaDataOf(region, Region.METADATA_REMSET_LOCK_OFFSET);
    remSetLock.store(0);
  }

  /** Get PRT of remset of the `region` that contains `card` */
  @Inline
  private static Address preparePRT(Address region, Address card, boolean create) {
    // Get region index
    int regionIndex = region.diff(VM.HEAP_START).toWord().rshl(Region.LOG_BYTES_IN_BLOCK).toInt();
    // Get card region index
    int cardRegionIndex = Region.of(card).diff(VM.HEAP_START).toWord().rshl(Region.LOG_BYTES_IN_BLOCK).toInt();
    // Get PerRegionTable list, this is a page size
    Address prtList = rememberedSets.get(regionIndex);//rememberedSets[regionIndex];
    if (prtList.isZero()) { // create remset
      if (create) {
        // rememberedSets[regionIndex] = new int[TOTAL_REGIONS][];
        rememberedSets.set(regionIndex, Plan.metaDataSpace.acquire(REMSET_PAGES));
        prtList = rememberedSets.get(regionIndex); // rememberedSets[regionIndex];
      } else {
        return Address.zero();
      }
    }
    // Insert PerRegionTable if necessary
    Address prtEntry = prtList.plus(cardRegionIndex << Constants.LOG_BYTES_IN_ADDRESS);
    if (VM.VERIFY_ASSERTIONS)
      VM.assertions._assert(prtEntry.LT(prtList.plus(Constants.BYTES_IN_PAGE * REMSET_PAGES)));
    if (create && prtEntry.loadAddress().isZero()) {
      // prtList[cardRegionIndex] = new int[PER_REGION_TABLE_BYTES];
      prtEntry.store(Plan.metaDataSpace.acquire(PAGES_IN_PRT));
    }
    // Get PerRegionTable
    return prtEntry.loadAddress();
  }

  @Inline
  public static void addCard(Address region, Address card) {
    addCard(region, card, true);
  }

  @Inline
  private static void addCard(Address region, Address card, boolean lock) {
    if (lock) lock(region);

    Address prt = preparePRT(region, card, true);

    // Insert card into the target PerRegionTable
    if (PerRegionTable.insert(prt, card)) {
      // Increase REMSET_SIZE
      Address sizePointer = Region.metaDataOf(region, Region.METADATA_REMSET_SIZE_OFFSET);
      int oldSize, newSize;// = Region.metaDataOf(region, Region.METADATA_REMSET_SIZE_OFFSET).loadInt();
      do {
        oldSize = Region.metaDataOf(region, Region.METADATA_REMSET_SIZE_OFFSET).prepareInt();
        newSize = oldSize + 1;
      } while (!sizePointer.attempt(oldSize, newSize));
    }

    if (lock) unlock(region);
  }

  @Inline
  public static void removeCard(Address region, Address card) {
//    Log.writeln("Remove card ");
    lock(region);
//    Log.writeln("Remove card enter");
    Address prt = preparePRT(region, card, false);
    if (prt.isZero()) {
      unlock(region);
      return;
    }
//    Log.writeln("Remove card attempt to remove");
//    PerRegionTable.remove(prt, card);
    if (PerRegionTable.remove(prt, card)) {
//      Log.writeln("Remove card decrease rs size");
      Address sizePointer = Region.metaDataOf(region, Region.METADATA_REMSET_SIZE_OFFSET);
      int oldSize, newSize;// = Region.metaDataOf(region, Region.METADATA_REMSET_SIZE_OFFSET).loadInt();
      do {
        oldSize = sizePointer.prepareInt();
        if (oldSize == 0) break;
        newSize = oldSize - 1;
      } while (!sizePointer.attempt(oldSize, newSize));
    }
    unlock(region);
//    Log.writeln("Remove card end");
  }

  @Inline
  public static boolean contains(Address region, Address card) {
    Address prt = preparePRT(region, card, false);
    if (prt.isZero()) return false;
    return PerRegionTable.contains(prt, card);
  }

  @Inline
  public static int ceilDiv(int a, int b) {
    return (a + b - 1) / b;
  }

  @Uninterruptible
  static public abstract class RemSetCardScanningTimer {
    @Inline
    public abstract void updateRemSetCardScanningTime(long time);
  }

  @Uninterruptible
  public static class Processor {
    RegionSpace regionSpace;
    TraceLocal redirectPointerTrace;

    public Processor(TraceLocal redirectPointerTrace, RegionSpace regionSpace) {
      this.redirectPointerTrace = redirectPointerTrace;
      this.regionSpace = regionSpace;
    }

    public TransitiveClosure redirectPointerTransitiveClosure = new TransitiveClosure() {
      @Uninterruptible
      public void processEdge(ObjectReference source, Address slot) {
        ObjectReference ref = slot.loadObjectReference();
        if (!ref.isNull() && Space.isMappedObject(ref) && Space.isInSpace(regionSpace.getDescriptor(), ref) && Region.relocationRequired(Region.of(ref))) {
          redirectPointerTrace.processRootEdge(slot, true);
        }
      }
    };

    public TransitiveClosure validateTC = new TransitiveClosure() {
      @Uninterruptible
      public void processEdge(ObjectReference source, Address slot) {
        ObjectReference ref = slot.loadObjectReference();
        if (G1.regionSpace.contains(ref)) {
          if ((VM.objectModel.readAvailableByte(ref) & 3) == 1) {
            VM.objectModel.dumpObject(source);
            VM.objectModel.dumpObject(ref);
          }
          VM.assertions._assert((VM.objectModel.readAvailableByte(ref) & 3) != 1);
        }
      }
    };

    LinearScan cardLinearScan = new LinearScan() {
      @Override @Uninterruptible @Inline public void scan(ObjectReference object) {
        if (!object.isNull()) {// && redirectPointerTrace.isLive(object)) {
          if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(VM.debugging.validRef(object));
          redirectPointerTrace.traceObject(object, true);
        }
      }
    };

    /** Scan all cards in remsets of collection regions */
    @Inline
    public void processRemSets(AddressArray relocationSet, boolean concurrent, RegionSpace regionSpace, RemSetCardScanningTimer remSetCardScanningTimer) {
      int workers = VM.activePlan.collector().parallelWorkerCount();
      int id = VM.activePlan.collector().getId();
      if (concurrent) id -= workers;
      int regionsToVisit = ceilDiv(relocationSet.length(), workers);
      final int REGION_SPACE = regionSpace.getDescriptor();

      for (int i = 0; i < regionsToVisit; i++) {
        //int cursor = regionsToVisit * id + i;
        int cursor = i * workers + id;
        if (cursor >= relocationSet.length()) continue;
        Address region = relocationSet.get(cursor);
        if (region.isZero()) continue;
        final int totalRemSetSize = Region.metaDataOf(region, Region.METADATA_REMSET_SIZE_OFFSET).loadInt();
        if (totalRemSetSize == 0) continue;
        //if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!region.isZero());
        // Iterate all its PRTs
        int regionIndex = region.diff(VM.HEAP_START).toWord().rshl(Region.LOG_BYTES_IN_BLOCK).toInt();
        Address prtList = rememberedSets.get(regionIndex);
        if (prtList.isZero()) continue;
        for (int j = 0; j < TOTAL_REGIONS; j++) {
          Address prt = prtList.plus(j << Constants.LOG_BYTES_IN_ADDRESS).loadAddress();
          if (prt.isZero()) continue;
          Address currentRegion = VM.HEAP_START.plus(j << Region.LOG_BYTES_IN_BLOCK);
          // Iterate all entries in prt
          for (int k = 0; k < INTS_IN_PRT; k++) {
            if (prt.plus(k << Constants.LOG_BYTES_IN_INT).loadInt() == 0) continue;
            int cardIndexStart = k << Constants.LOG_BITS_IN_INT;
            int cardIndexEnd = cardIndexStart + Constants.BITS_IN_INT;

            for (int cardIndex = cardIndexStart; cardIndex < cardIndexEnd; cardIndex++) {
              if (!PerRegionTable.getBit(prt, cardIndex)) continue;
              Address card = currentRegion.plus(cardIndex << Region.Card.LOG_BYTES_IN_CARD);
              // This `card` is in rem-set of `region`
              if (!Space.isMappedAddress(card)) continue;
              if (Space.isInSpace(Plan.VM_SPACE, card)) continue;
              if (Space.isInSpace(REGION_SPACE, card) && Region.relocationRequired(Region.of(card))) continue;

              long time = VM.statistics.nanoTime();
              Region.Card.linearScan(cardLinearScan, regionSpace, card, false);
              remSetCardScanningTimer.updateRemSetCardScanningTime(VM.statistics.nanoTime() - time);
            }
          }
        }
      }
    }
  }

  @Inline
  public static void removeRemsetForRegion(RegionSpace regionSpace, Address region) {
    int cursor = region.diff(VM.HEAP_START).toWord().rshl(Region.LOG_BYTES_IN_BLOCK).toInt();//..plus(cursor << Region.LOG_BYTES_IN_BLOCK);
    Address prtList = rememberedSets.get(cursor);
    if (!prtList.isZero()) {
      Address prtPrtEnd = prtList.plus(REMSET_PAGES << Constants.LOG_BYTES_IN_PAGE);
      for (Address prtPtr = prtList; prtPtr.LT(prtPrtEnd); prtPtr = prtPtr.plus(Constants.BYTES_IN_ADDRESS)) {
        if (VM.VERIFY_ASSERTIONS)
          VM.assertions._assert(prtPtr.LT(prtList.plus(Constants.BYTES_IN_PAGE * REMSET_PAGES)));
        Address prt = prtPtr.loadAddress();
        if (!prt.isZero()) {
          Plan.metaDataSpace.release(prt);
        }
      }
      Plan.metaDataSpace.release(prtList);
      rememberedSets.set(cursor, Address.zero());
    }
  }

  /** Remove cards in collection regions from remsets of other regions & Release remsets of collection regions */
  @Inline
  public static void cleanupRemSetRefsToRelocationSet(RegionSpace regionSpace, AddressArray relocationSet, boolean concurrent) {
    int workers = VM.activePlan.collector().parallelWorkerCount();
    int id = VM.activePlan.collector().getId();
    if (concurrent) id -= workers;
    int regionsToVisit = ceilDiv(TOTAL_REGIONS, workers);

    for (int i = 0; i < regionsToVisit; i++) {
      int cursor = regionsToVisit * id + i;
      if (cursor >= TOTAL_REGIONS) break;
      Address visitedRegion = VM.HEAP_START.plus(cursor << Region.LOG_BYTES_IN_BLOCK);
      // If this is a relocation region, clear its rem-sets
      if (Space.isInSpace(regionSpace.getDescriptor(), visitedRegion) && Region.of(visitedRegion).NE(EmbeddedMetaData.getMetaDataBase(visitedRegion)) && Region.relocationRequired(visitedRegion)) {
        Address prtList = rememberedSets.get(cursor);
        if (!prtList.isZero()) {
          Address prtPrtEnd = prtList.plus(REMSET_PAGES << Constants.LOG_BYTES_IN_PAGE);
          for (Address prtPtr = prtList; prtPtr.LT(prtPrtEnd); prtPtr = prtPtr.plus(Constants.BYTES_IN_ADDRESS)) {
            if (VM.VERIFY_ASSERTIONS)
              VM.assertions._assert(prtPtr.LT(prtList.plus(Constants.BYTES_IN_PAGE * REMSET_PAGES)));
            Address prt = prtPtr.loadAddress();
            if (!prt.isZero()) {
              Plan.metaDataSpace.release(prt);
            }
          }
          Plan.metaDataSpace.release(prtList);
          rememberedSets.set(cursor, Address.zero());
        }
        continue;
      }
      // Else, clear all PRT corresponds to CSet
      Address prtList = rememberedSets.get(cursor);
      if (prtList.isZero()) continue;
      for (int j = 0; j < relocationSet.length(); j++) {
        Address cRegion = relocationSet.get(j);
        if (cRegion.isZero()) continue;
        int index = cRegion.diff(VM.HEAP_START).toInt() >>> Region.LOG_BYTES_IN_BLOCK;
        Address prtEntry = prtList.plus(index << Constants.LOG_BYTES_IN_ADDRESS);
        if (!prtEntry.loadAddress().isZero()) {
          Plan.metaDataSpace.release(prtEntry.loadAddress());
          prtEntry.store(Address.zero());
        }
      }
    }
  }

  @Uninterruptible
  public static class Builder {
    RegionSpace regionSpace;
    int REGION_SPACE;
    TraceLocal traceLocal;
    boolean cardAdded = false;

    public Builder(TraceLocal traceLocal, RegionSpace regionSpace) {
      this.traceLocal = traceLocal;
      this.regionSpace = regionSpace;
      REGION_SPACE = regionSpace.getDescriptor();
    }

    TransitiveClosure rsBuilderTC = new TransitiveClosure() {
      @Uninterruptible
      @Inline
      public void processEdge(ObjectReference src, Address slot) {
        ObjectReference ref = slot.loadObjectReference();
        if (!ref.isNull() && Space.isInSpace(REGION_SPACE, ref)) {
          Address region = Region.of(ref);
          if (Region.metaDataOf(region, Region.METADATA_GENERATION_OFFSET).loadInt() == 0) return;
          if (Region.relocationRequired(region) && region.NE(Region.of(src))) {
            Address card = Region.Card.of(src);
            cardAdded = true;
            RemSet.addCard(region, card);
          }
        }
      }
    };

    LinearScan regionLinearScan = new LinearScan() {
      @Override @Uninterruptible public void scan(ObjectReference object) {
        if (regionSpace.isLive(object)) {
          cardAdded = false;
          VM.scanning.scanObject(rsBuilderTC, object);
          if (cardAdded) {
            Region.Card.updateCardMeta(object);
          }
        }
      }
    };
    public void scanRegionForConstructingRemSets(Address region) {
      Region.linearScan(regionLinearScan, region);
    }
  }
}
