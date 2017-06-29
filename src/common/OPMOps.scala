package scala.lms
package common

import java.io.PrintWriter

import internal.{GenericNestedCodegen, GenerationFailedException}
import scala.reflect.SourceContext

trait OMPOps extends Base {
  def parallel_region(b: => Rep[Unit]): Rep[Unit]
  def critical_region(b: => Rep[Unit]): Rep[Unit]

  def ompGetThreadId(): Exp[Long]
  def ompSetNumThreads(n: Rep[Long]): Rep[Unit]
}

trait OMPOpsExp extends OMPOps {

  case class ParallelRegion(b: Block[Unit]) extends Def[Unit]
  def parallel_region(b: => Exp[Unit]): Exp[Unit] = {
    val br = reifyEffects(b)
    reflectEffect(ParallelRegion(br))
  }

  case class CriticalRegion(b: Block[Unit]) extends Def[Unit]
  def critical_region(b: => Exp[Unit]): Exp[Unit] = {
    val br = reifyEffects(b)
    reflectEffect(CriticalRegion(br))
  }

  case class GetThreadId() extends Def[Long]
  // def ompGetThreadId() = GetThreadId()

  case class SetNumThreads(nbThreads: Exp[Long]) extends Def[Unit]
  def ompSetNumThreads(n: Exp[Long]): Exp[Unit] = SetNumThreads(n)

  override def boundSyms(e: Any): List[Sym[Any]] = e match {
    case ParallelRegion(b) => effectSyms(b)
    case CriticalRegion(b) => effectSyms(b)
    case _ => super.boundSyms(e)
  }

  override def syms(e: Any): List[Sym[Any]] = e match {
    case ParallelRegion(body) => syms(body)
    case CriticalRegion(body) => syms(body)
    case _ => super.syms(e)
  }

  override def symsFreq(e: Any): List[(Sym[Any], Double)] = e match {
    case ParallelRegion(body) => freqHot(body)
    case CriticalRegion(body) => freqHot(body)
    case _ => super.symsFreq(e)
  }

}

trait BaseGenOMPOps extends GenericNestedCodegen {
  val IR: OMPOpsExp
}

trait CGenOMPOps extends CGenEffect with BaseGenOMPOps {
  import IR._

  override def emitNode(sym: Sym[Any], rhs: Def[Any]) = rhs match {
    case ParallelRegion(body) =>
      gen"""#pragma omp parallel
      |{
      |${nestedBlock(body)}
      |}"""
    case CriticalRegion(body) =>
      gen"""#pragma omp critical
      |{
      |${nestedBlock(body)}
      |}"""
    case GetThreadId() => emitValDef(sym, "omp_get_thread_num()")
    case SetNumThreads(threads) => emitValDef(sym, src"omp_set_num_threads($threads)")
    case _ => super.emitNode(sym, rhs)
  }
}
