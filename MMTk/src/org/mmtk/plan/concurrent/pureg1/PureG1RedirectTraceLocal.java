package org.mmtk.plan.concurrent.pureg1;

import org.mmtk.plan.Plan;
import org.mmtk.plan.Trace;
import org.mmtk.plan.TraceLocal;
import org.mmtk.policy.*;
import org.mmtk.utility.ForwardingWord;
import org.mmtk.utility.Log;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.AddressArray;
import org.vmmagic.unboxed.ObjectReference;

@Uninterruptible
public class PureG1RedirectTraceLocal extends TraceLocal {
  boolean log = false;
  RemSet.Processor processor = new RemSet.Processor(this);

  public void linearUpdatePointers(AddressArray relocationSet, boolean concurrent) {
    processor.updatePointers(relocationSet, concurrent, PureG1.markBlockSpace);
  }

  public PureG1RedirectTraceLocal(Trace trace) {
    super(PureG1.SCAN_REDIRECT, trace);
  }

  @Override
  public boolean isLive(ObjectReference object) {
    if (object.isNull()) return false;
    if (Space.isInSpace(PureG1.MC, object)) {
      return PureG1.markBlockSpace.isLive(object);
    }
    return super.isLive(object);
  }

  @Inline
  public void processEdge(ObjectReference source, Address slot) {
    //if (remSetsProcessing) Log.writeln(">>>");
    ObjectReference oldRef = slot.loadObjectReference();
    //if (VM.VERIFY_ASSERTIONS) {
      //if (!oldRef.isNull()) VM.debugging.validRef(oldRef);
    //}
    /*Log.write("START processEdge ", source);
    Log.write(".", slot);
    Log.write(": ");
    Log.write(oldRef);
    Log.write(" ");
    Log.write(oldRef.isNull() ? "null" : Space.getSpaceForObject(oldRef).getName());
    Log.writeln(isLive(oldRef) ? " live" : " dead");*/
    super.processEdge(source, slot);

    ObjectReference ref = slot.loadObjectReference();
    /*Log.write("END processEdge ", source);
    Log.write(".", slot);
    Log.write(": ");
    Log.write(ref);
    Log.write(" ");
    Log.write(ref.isNull() ? "null" : Space.getSpaceForObject(ref).getName());
    Log.writeln(ref.isNull() ? " null" : isLive(ref) ? " live" : " dead");*/
    /*if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(ref.isNull() || isLive(ref));
      if (!ref.isNull()) VM.debugging.validRef(ref);
    }*/
    if (!ref.isNull() && Space.isMappedObject(ref) && Space.isInSpace(PureG1.MC, ref)) {
      Address block = MarkBlock.of(VM.objectModel.objectStartRef(ref));
      if (block.NE(MarkBlock.of(VM.objectModel.objectStartRef(source)))) {
        MarkBlock.Card.updateCardMeta(source);
        Address card = MarkBlock.Card.of(source);
        RemSet.addCard(block, card);
      }
    }
    //if (remSetsProcessing) Log.writeln("<<<");
  }

  @Inline
  @Override
  public void scanObject(ObjectReference object) {
    /*if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(VM.debugging.validRef(object));
      VM.assertions._assert(isLive(object));
    }*/
    if (object.isNull()) return;
    if (remSetsProcessing) {
      if (!Space.isMappedObject(object)) return;
    }
    super.scanObject(object);
  }

  @Override
  @Inline
  public ObjectReference traceObject(ObjectReference object) {
    if (object.isNull()) return object;

    if (remSetsProcessing) {
      if (Space.isInSpace(PureG1.MC, object)) {
        ObjectReference newObject = PureG1.markBlockSpace.traceEvacuateObject(this, object, PureG1.ALLOC_MC, false);
        return newObject;
      }
      return object;
    } else {
      if (VM.VERIFY_ASSERTIONS) {
        if (!VM.debugging.validRef(object)) Log.writeln(isLive(object) ? " live" : " dead");
        VM.assertions._assert(VM.debugging.validRef(object));
      }
      MarkBlock.Card.updateCardMeta(object);
      if (Space.isInSpace(PureG1.MC, object)) {
        ObjectReference newObject = PureG1.markBlockSpace.traceEvacuateObject(this, object, PureG1.ALLOC_MC, true);
        MarkBlock.Card.updateCardMeta(newObject);
        VM.assertions._assert(isLive(newObject));
        return newObject;
      }
      ObjectReference ref = super.traceObject(object);
      if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(isLive(ref));
      return ref;
    }
/*
    if (remSetsProcessing) {
      if (!Space.isMappedObject(object)) {
        return object;//ObjectReference.nullReference();
      }
    } else {
      if (VM.VERIFY_ASSERTIONS) {
        if (!VM.debugging.validRef(object)) {
          Log.writeln(isLive(object) ? " live" : " dead");
        }
        VM.assertions._assert(VM.debugging.validRef(object));
      }
    }

    if (!remSetsProcessing) {
      MarkBlock.Card.updateCardMeta(object);
    }
    if (Space.isInSpace(PureG1.MC, object)) {
      ObjectReference newObject = PureG1.markBlockSpace.traceEvacuateObject(this, object, PureG1.ALLOC_MC, !remSetsProcessing);
      if (!remSetsProcessing) {
        MarkBlock.Card.updateCardMeta(newObject);
      }
      return newObject;
    } else {
      if (remSetsProcessing) return object;
    }
    ObjectReference ref = super.traceObject(object);
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(isLive(ref));
    return ref;*/
  }

  @Override
  public boolean willNotMoveInCurrentCollection(ObjectReference object) {
    if (Space.isInSpace(PureG1.MC, object)) {
      return !MarkBlock.relocationRequired(MarkBlock.of(VM.objectModel.objectStartRef(object)));
    } else {
      return super.willNotMoveInCurrentCollection(object);
    }
  }

  boolean remSetsProcessing = false;
  //boolean remSetsProcessed = false;

  @Inline
  public void completeTrace() {
    //remSetsProcessed = false;
    super.completeTrace();
    //remSetsProcessed = false;
  }

  //@Override
  @Inline
  public void processRemSets() {
    //if (!remSetsProcessed) {
    //remSetsProcessing = true;
      processor.processRemSets(PureG1Collector.relocationSet, false, PureG1.markBlockSpace);
    //remSetsProcessing = false;
      //remSetsProcessed = true;
    //}
  }
}
