//package memGen.fpu
//
//import chisel3._
//import chisel3.Module
//import chipsalliance.rocketchip.config._
//import memGen.config._
//import memGen.interfaces._
//import memGen.arbiters._
//import util._
//import muxes._
//import hardfloat._
//import chipsalliance.rocketchip.config._
//import memGen.config._
//
//class SharedFPUIO(NumOps: Int, argTypes: Seq[Int])
//                 (implicit p: Parameters) extends AccelBundle( )(p) {
//  val InData = Vec(NumOps, Flipped(Decoupled(new FUReq(argTypes))))
//
//  val OutData = Vec(NumOps, Output(new FUResp))
//
//  override def cloneType = new SharedFPUIO(NumOps, argTypes).asInstanceOf[this.type]
//}
//
//class SharedFPU(NumOps: Int, PipeDepth: Int)(t: FType)
//               (implicit val p: Parameters,
//                name: sourcecode.Name,
//                file: sourcecode.File)
//  extends Module with HasAccelParams {
//  override lazy val io = IO(new SharedFPUIO(NumOps, argTypes = List(xlen, xlen, xlen)))
//
//  // Printf debugging
//  val node_name       = name.value
//  val module_name     = file.value.split("/").tail.last.split("\\.").head.capitalize
//  val (cycleCount, _) = Counter(true.B, 32 * 1024)
//
//  print(t.expWidth)
//  // Arguments for function unit
//  val argTypes = List(xlen, xlen, xlen)
//  // The function unit
//  val ds       = Module(new DivSqrtRecFN_small(t.expWidth, t.sigWidth, 0))
//  //  Metadata queue associated with function unit
//  val RouteQ   = Module(new Queue(UInt(tlen.W), PipeDepth))
//
//  /**
//    * Instantiating Arbiter module and connecting inputs to the output
//    *
//    * @note we fix the base size to 8
//    */
//  val in_arbiter = Module(new ArbiterTree(BaseSize = 2, NumOps = NumOps, new FUReq(argTypes), Locks = 1))
//  val out_demux  = Module(new DeMuxTree(BaseSize = 2, NumOps = NumOps, new FUResp))
//
//  //@todo fix the base size
//  out_demux.io.input <> DontCare
//
//  for (i <- 0 until NumOps) {
//    in_arbiter.io.in(i) <> io.InData(i)
//    io.OutData(i) <> out_demux.io.outputs(i)
//  }
//
//  // Declare metadata queue associated with function unit.
//  // PipeDepth = Function unit pipeline stages
//  // The buffering depth of RouteQ is the maximum number of FU executions
//  // that can be outstanding.
//  RouteQ.io.enq.valid := in_arbiter.io.out.valid & ds.io.inReady
//  RouteQ.io.enq.bits := in_arbiter.io.out.bits.RouteID
//
//  // Wire up arbiter ready to function unit and queue ready
//  in_arbiter.io.out.ready := ds.io.inReady && RouteQ.io.enq.ready
//  // Wire up arbiter to function unit. Direct params to function unit
//  ds.io.inValid := in_arbiter.io.out.valid
//  ds.io.sqrtOp := in_arbiter.io.out.bits.data("field2").data.orR
//  ds.io.a := t.recode(in_arbiter.io.out.bits.data("field0").data)
//  ds.io.b := t.recode(in_arbiter.io.out.bits.data("field1").data)
//  //  ds.io.a := in_arbiter.io.out.bits.data("field0").data
//  //  ds.io.b := in_arbiter.io.out.bits.data("field1").data
//  ds.io.roundingMode := "b110".U(3.W)
//  ds.io.detectTininess := 0.U(1.W)
//
//  out_demux.io.enable := ds.io.outValid_div || ds.io.outValid_sqrt
//  RouteQ.io.deq.ready := ds.io.outValid_div || ds.io.outValid_sqrt
//  out_demux.io.input.data := fNFromRecFN(t.expWidth, t.sigWidth, ds.io.out)
//  out_demux.io.input.RouteID := RouteQ.io.deq.bits
//  out_demux.io.input.valid := ds.io.outValid_div || ds.io.outValid_sqrt
//  // Use RouteQ value to demux the output of fn
//  val x = fNFromRecFN(t.expWidth, t.sigWidth, ds.io.out)
//
//}
