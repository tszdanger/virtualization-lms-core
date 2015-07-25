package scala.virtualization.lms
package common

import scala.collection.{immutable,mutable}
import scala.reflect.SourceContext

trait ForwardTransformer extends internal.AbstractSubstTransformer with internal.IRVisitor { self =>
  val IR: BaseFatExp with EffectExp //LoopsFatExp with IfThenElseFatExp
  import IR._
  
  def transformBlock[A:Manifest](block: Block[A]): Block[A] = {
    reifyEffects {
      reflectBlock(block)
    }
  }

  override def hasContext = true
  
  override def apply[A:Manifest](xs: Block[A]): Block[A] = transformBlock(xs)

  // perform only one step of lookup, otherwise we confuse things: 
  // TODO: should this be changed in AbstractSubstTransformer, too?
  //
  //                     x4 --> x7 (input)
  // val x5 = 2 * x4     x5 --> x8
  // val x6 = x5 + 3     x6 --> x9          
  // val x7 = x4 + 1                val x12 = x7 + 1
  // val x8 = 2 * x7                val x13 = 2 * x12
  // val x9 = x8 + 3                val x14 = x13 + 3     // this sets x9 --> x14
  // val x10 = x6 + x9              val x15 = x14 + x14   // here, transitively x6 --> x9 --> x14
  //                                                      // but we'd rather have x15 = x9 + x14
  
  override def apply[A](x: Exp[A]): Exp[A] = subst.get(x) match { 
    case Some(y) => 
      // HACK: Mirror tunable parameter mappings
      if (tunableParams.contains(x) && !tunableParams.contains(y))
        tunableParams += (y -> tunableParams(x))

      y.asInstanceOf[Exp[A]] 
    case _ => x 
  }
  
  override def reflectBlock[A](block: Block[A]): Exp[A] = {
    withSubstScope {
      traverseBlock(block)
      apply(getBlockResult(block))
    }
  }

  override def traverseStm(stm: Stm): Unit = stm match {
    case TP(sym, rhs) => 
      val sym2 = apply(sym)
      if (sym2 == sym) {
        val replace = transformStm(stm)
        // printlog("registering forward transformation: " + sym + " to " + replace)
        // printlog("while processing stm: " + stm)          
        assert(!subst.contains(sym) || subst(sym) == replace)
        if (sym != replace) { // record substitution only if result is different
          subst += (sym -> replace)
        }
      } else {
        if (recursive.contains(sym)) { // O(n) since recursive is a list!
          transformStm(stm)
        } else {
          //warn("transformer already has a substitution " + sym + "->" + sym2 + " when encountering stm " + stm)
          printerr("warning: transformer already has a substitution " + sym + "->" + sym2 + " when encountering stm " + stm)
          // what to do? bail out? lookup def and then transform???
        }
      }
    case TTP(syms, mhs, rhs) => 
      if ( syms forall {sym => apply(sym) == sym} ) {
        val replaces = transformFatStm(stm)
        syms.zip(replaces).foreach{s => 
          assert(!subst.contains(s._1) || subst(s._1) == s._2)
          
          if (s._1 != s._2) subst += (s._1 -> s._2)
        }
      }
      else {
        printerr("warning: transformer already has a substitution for " + syms + " when encountering stm " + stm)
      }
  }
  
  def transformFatStm(stm: Stm): List[Exp[Any]] = stm match {
    case TTP(syms,mhs,rhs) => 
      self_fat_mirror(syms, rhs)
  }

  def transformStm(stm: Stm): Exp[Any] = stm match { // override this to implement custom transform
    case TP(sym,rhs) =>
      /*
       TBD: optimization from MirrorRetainBlockTransformer in TestMiscTransform -- is it worth doing??        
       // we want to skip those statements that don't have symbols that need substitution
       // however we need to recurse into any blocks
       if (!syms(rhs).exists(subst contains _) && blocks(rhs).isEmpty) {
       if (!globalDefs.contains(stm)) reflectSubGraph(List(stm))
       return sym
       }
       */
      self_mirror(sym, rhs)
  }

  // HACK: 
  def self_fat_mirror(syms: List[Sym[Any]], rhs: FatDef)(implicit ctx: SourceContext): List[Exp[Any]] = {
    mirror(syms, rhs, self.asInstanceOf[Transformer])
  }

  def self_mirror[A](sym: Sym[A], rhs : Def[A]): Exp[A] = {
    try {
      mirror(rhs, self.asInstanceOf[Transformer])(mtype(sym.tp),mpos(sym.pos)) // cast needed why?
    } catch { //hack -- should not catch errors
      case e if e.toString contains "don't know how to mirror" => 
        printerr("error: " + e.getMessage)
      sym
      case e: Throwable => 
        printerr("error: exception during mirroring of "+rhs+": "+ e)
        e.printStackTrace; 
        sym            
    }
  }
}


trait RecursiveTransformer extends ForwardTransformer { self =>
  import IR._

  override def runOnce[A:Manifest](s: Block[A]): Block[A] = transformBlock(s)

  def transformDef[A](lhs: Sym[A], rhs: Def[A]): Option[() => Def[A]] = None

  override def traverseStmsInBlock[A](stms: List[Stm]): Unit = {
    for (sym <- recursive) {
      subst += (sym -> fresh(mtype(sym.tp)))
    }
    super.traverseStmsInBlock(stms)
  }

  override def transformStm(stm: Stm): Exp[Any] = stm match {
    case TP(s, rhs) => transformDef(s, rhs) match {
      case Some(rhsThunk) =>
        val s2 = subst.get(s) match {
          case Some(s2@Sym(_)) => assert(recursive.contains(s)); s2
          case _ => assert(!recursive.contains(s)); fresh(mtype(s.tp))
        }
        createDefinition(s2, rhsThunk())
        s2
      case None => subst.get(s) match {
        case Some(s2@Sym(_)) =>
          assert(recursive.contains(s))
          createDefinition(s2, Def.unapply(self_mirror(s, rhs)).get)
          s2
        case None =>
          assert(!recursive.contains(s))
          super.transformStm(stm)
      }
    }
    case _ => super.transformStm(stm)
  }
}

// need backward version, too?
trait WorklistTransformer extends ForwardTransformer with internal.IterativeIRVisitor { 
  val IR: LoopsFatExp with IfThenElseFatExp
  import IR._
  var curSubst: Map[Sym[Any],() => Exp[Any]] = Map.empty
  var nextSubst: Map[Sym[Any],() => Exp[Any]] = Map.empty
  def register[A](x: Exp[A])(y: => Exp[A]): Unit = {
    if (nextSubst.contains(x.asInstanceOf[Sym[A]]))
      printdbg("discarding, already have a replacement for " + x)
    else {
      printdbg("register replacement for " + x)
      nextSubst = nextSubst + (x.asInstanceOf[Sym[A]] -> (() => y))
    }
  }
  override def hasConverged = runs > 0 && nextSubst.isEmpty

  override def runOnce[A:Manifest](s: Block[A]): Block[A] = {
    subst = Map.empty
    curSubst = nextSubst
    nextSubst = Map.empty
    transformBlock(s)
  }

  override def transformStm(stm: Stm): Exp[Any] = stm match {
    case TP(sym, rhs) => 
      curSubst.get(sym) match {
        case Some(replace) =>
          printdbg("install replacement for " + sym)
          //val b = reifyEffects(replace())
          //reflectBlock(b)
          replace()
        case None => 
          super.transformStm(stm)
      }
  }

}
