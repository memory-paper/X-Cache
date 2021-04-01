package memGen.shell

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import memGen.interfaces._
import memGen.config._
import memGen.interfaces.axi._


class memGenDCRIO[T <: Data](val PtrsIn: Seq[Int],
                                     val ValsIn: Seq[Int],
                                     val RetsOut: Seq[Int])
                                    (implicit p: Parameters)
  extends AccelBundle()(p) with HasAccelShellParams {
  val in = Flipped(Decoupled(new CallDCR(PtrsIn, ValsIn)))
  val out = Decoupled(new Call(RetsOut))
  val events = Valid (Vec(16, UInt(32.W)))

  override def cloneType = new memGenDCRIO(PtrsIn, ValsIn, RetsOut)(p).asInstanceOf[this.type]
}

class memGenModuleIO[T <: Data](PtrsIn: Seq[Int],
                                ValsIn: Seq[Int],
                                RetsOut: Seq[Int])
                               (implicit p: Parameters)
  extends memGenDCRIO( PtrsIn: Seq[Int], ValsIn: Seq[Int], RetsOut: Seq[Int]){

  val mem = new AXIMaster(memParams)

  override def cloneType = new memGenModuleIO(PtrsIn, ValsIn, RetsOut)(p).asInstanceOf[this.type]
}

abstract class memGenModule(val PtrsIn: Seq[Int],
                            val ValsIn: Seq[Int],
                            val RetsOut: Seq[Int])
                           (implicit val p: Parameters) extends MultiIOModule with HasAccelParams {
  lazy val io = IO(new memGenModuleIO(PtrsIn, ValsIn, RetsOut))
}
