/* -*-coding: iso-8859-1 -*-
 * 
 * (C) Copyright IBM Corp. 2001
 *
 * $Id$
 */
package org.mmtk.utility;

import org.mmtk.plan.TraceLocal;

import org.mmtk.vm.ActivePlan;
import org.mmtk.vm.Assert;
import org.mmtk.vm.ReferenceGlue;

import org.vmmagic.unboxed.*;
import org.vmmagic.pragma.*;

/**
 * This class manages soft, weak and phantom references.
 * The VM maintains a list of pending reference objects of the various
 * types.  This list is either outside the heap or uses addresses,
 * so that a reference will not stay alive during GC if it is not
 * used elsewhere in the mutator.  During GC, the various lists are
 * processed in the proper order to determine if any reference objects
 * are no longer active or whether referents that have died should be
 * kept alive.
 *
 * Loosely based on Finalizer.java
 * 
 * To ensure that processing is efficient with generational collectors
 * in languages that have immutable reference types, there is a   
 * reference nursery. If the appropriate flag is passed during processing
 * (and the language has immutable references), then it is safe to only
 * process references created since the last collection.
 * 
 * @author Chris Hoffmann
 * @modified Andrew Gray
 */
public class ReferenceProcessor implements Uninterruptible {

  public static final int SOFT_SEMANTICS = 0;
  public static final int WEAK_SEMANTICS = 1;
  public static final int PHANTOM_SEMANTICS = 2;

  public static final String semanticStrings [] = {
    "SOFT", "WEAK", "PHANTOM" };

  private static boolean clearSoftReferences = false;

  /* Debug flags */
  private static final boolean  TRACE        = false;
  private static final boolean  TRACE_DETAIL = false;

  /* Suppress default constructor for noninstantiability */
  private ReferenceProcessor() {
    /* constructor will never be invoked */
  }

  /**
   * Scan references with the specified semantics.
   * @param semantics The number representing the semantics
   * @param nursery It is safe to only collect new references 
   */
  private static void traverse(int semantics, boolean nursery)
    throws LogicallyUninterruptiblePragma, InlinePragma {

    if (TRACE) {
      Log.write("Starting ReferenceProcessor.traverse(");
      Log.write(semanticStrings[semantics]);
      Log.writeln(")");
    }
    
    ReferenceGlue.scanReferences(semantics, nursery);

    if (TRACE) {
      Log.writeln("Ending ReferenceProcessor.traverse()");
    }
  }

  /**
   * Process a reference with the specified semantics.
   * @param reference the address of the reference. This may or may not
   * be the address of a heap object, depending on the VM.
   * @param semantics the code number of the semantics
   */
  public static Address processReference(Address reference,
                                         int semantics) throws InlinePragma {
    if (Assert.VERIFY_ASSERTIONS) Assert._assert(!reference.isZero());
    
    TraceLocal trace = ActivePlan.local().getCurrentTrace();
    
    if (TRACE) {
      Log.write("+++ old reference: "); Log.writeln(reference);
    }

    Address newReference;
    /*
     * If the reference is dead, we're done with it. Let it (and
     * possibly its referent) be garbage-collected.
     */
    if (ReferenceGlue.REFERENCES_ARE_OBJECTS && 
        !trace.isReferentLive(reference.toObjectReference())) {
      newReference = Address.zero();
    } else {
      /* Otherwise... */
      if (ReferenceGlue.REFERENCES_ARE_OBJECTS)
        newReference = trace.getForwardedReferent(reference.toObjectReference()).toAddress();
      else
        newReference = reference;
      ObjectReference oldReferent = ReferenceGlue.getReferent(reference);

      if (TRACE_DETAIL) {
        Log.write("    new reference: "); Log.writeln(newReference);
        Log.write(" old referent: "); Log.writeln(oldReferent);
      }
      /*
       * If the application has cleared the referent the Java spec says
       * this does not cause the Reference object to be enqueued. We
       * simply allow the Reference object to fall out of our
       * waiting list.
       */
      if (oldReferent.isNull()) {
        newReference = Address.zero();
      } else {
        boolean enqueue = false;

        if (semantics == PHANTOM_SEMANTICS && !trace.isLive(oldReferent)) {
            /*
             * Keep phantomly reachable objects from being collected
             * until they are completely unreachable.
             */
            if (TRACE_DETAIL) {
              Log.write("    resurrecting: "); Log.writeln(oldReferent);
            }
            trace.retainReferent(oldReferent);
            enqueue = true;
        } else if (semantics == SOFT_SEMANTICS && !clearSoftReferences) {
          /*
           * Unless we've completely run out of memory, we keep
           * softly reachable objects alive.
           */
          if (TRACE_DETAIL) {
            Log.write("    resurrecting: "); Log.writeln(oldReferent);
          }
          trace.retainReferent(oldReferent);
        }
          
        if (trace.isLive(oldReferent)) {
          /*
           * Referent is still reachable in a way that is as strong as
           * or stronger than the current reference level.
           */
          ObjectReference newReferent = trace.getForwardedReferent(oldReferent);

          if (TRACE) {
            Log.write(" new referent: "); Log.writeln(newReferent);
          }
            
          /*
           * The reference object stays on the waiting list, and the
           * referent is untouched. The only thing we must do is
           * ensure that the former addresses are updated with the
           * new forwarding addresses in case the collector is a
           * copying collector.
           */
          
          /* Update the referent */
          ReferenceGlue.setReferent(newReference, newReferent);
        }
        else {
          /* Referent is unreachable. */
            
          if (TRACE) {
            Log.write(" UNREACHABLE:  "); Log.writeln(oldReferent);
          }

          /*
           * Weak and soft references always clear the referent
           * before enqueueing. We don't actually call
           * Reference.clear() as the user could have overridden the
           * implementation and we don't want any side-effects to
           * occur.
           */
          if (semantics != PHANTOM_SEMANTICS) {
            if (TRACE_DETAIL) {
              Log.write(" clearing: "); Log.writeln(oldReferent);
            }
            ReferenceGlue.setReferent(newReference, ObjectReference.nullReference());
          }
          enqueue = true;
        }

        if (enqueue) {
          /*
           * Ensure phantomly reachable objects are enqueued only
           * the first time they become phantomly reachable.
           */
          ReferenceGlue.enqueueReference(newReference,
                                         semantics == PHANTOM_SEMANTICS);
            
        }
      }
    }
    return newReference;
  }

  /**
   * Set flag indicating if soft references referring to non-strongly
   * reachable objects should be cleared during GC. Usually this is 
   * false so the referent will stay alive. But when memory becomes
   * scarce the collector should reclaim all such objects before it is
   * forced to throw an OutOfMemory exception. Note that this flag
   * applies only to the next collection. After each collection the
   * setting is restored to false.
   * @param set <code>true</code> if soft references should be cleared
   */
  public static void setClearSoftReferences(boolean set) {
    clearSoftReferences = set;
  }

  /**
   * Process soft references.
   * @param nursery It is safe to only collect new references
   */
  public static void processSoftReferences(boolean nursery)
    throws NoInlinePragma {
    traverse(SOFT_SEMANTICS, nursery);
    clearSoftReferences = false;
  }
  
  /**
   * Process weak references.
   * @param nursery It is safe to only collect new references
   */
  public static void processWeakReferences(boolean nursery)
    throws NoInlinePragma {
    traverse(WEAK_SEMANTICS, nursery);
  }
  
  /**
   * Process phantom references.
   * @param nursery It is safe to only collect new references
   */
  public static void processPhantomReferences(boolean nursery) 
    throws NoInlinePragma {
    traverse(PHANTOM_SEMANTICS, nursery);
  }
  
  /**
   * Forward references.
   */
  public static void forwardReferences() {
    //  TODO: ReferenceGlue.forwardReferences(trace);
    Assert._assert(false);
  }
}
