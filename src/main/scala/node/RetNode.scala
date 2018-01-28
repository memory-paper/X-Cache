package node

import chisel3._
import chisel3.iotesters.{ChiselFlatSpec, Driver, PeekPokeTester, OrderedDecoupledHWIOTester}
import chisel3.Module
import chisel3.testers._
import chisel3.util._
import org.scalatest.{Matchers, FlatSpec}

import config._
import interfaces._
import muxes._
import util._

class RetNodeIO(NumOuts: Int)
                   (implicit p: Parameters)
extends HandShakingIONPS (NumOuts)(new DataBundle) {
  // Input data
  val InputIO = Flipped (Decoupled (new DataBundle) )
  val CtlIO  = Vec(NumOuts, Decoupled(new ControlBundle))
}

class RetNode(NumOuts: Int, ID: Int)
                 (implicit p: Parameters)
  extends HandShakingNPS(NumOuts, ID)(new DataBundle)(p) {
  override lazy val io = IO(new RetNodeIO(NumOuts))
  // Printf debugging
  override val printfSigil = "Node (RET) ID: " + ID + " "

  /*===========================================*
   *            Registers                      *
   *===========================================*/
  // Left Input
  val input_R = RegInit(DataBundle.default)
  val input_valid_R = RegInit(false.B)

  val s_idle :: s_LATCH :: s_COMPUTE :: Nil = Enum(3)
  val state = RegInit(s_idle)

  /*==========================================*
   *           Predicate Evaluation           *
   *==========================================*/

  val predicate = input_R.predicate && IsEnable()
  val start = input_valid_R && IsEnableValid()

  // Control Handshaking
  val ctl_ready_R = RegInit(VecInit(Seq.fill(NumOuts){false.B}))
  val ctl_valid_R = RegInit(VecInit(Seq.fill(NumOuts){false.B}))
  val ctl_ready_W = WireInit(VecInit(Seq.fill(NumOuts){false.B}))

  // Wire up OUT READYs and VALIDs
  for (i <- 0 until NumOuts) {
    io.CtlIO(i).valid := ctl_valid_R(i)
    ctl_ready_W(i) := io.CtlIO(i).ready
    when(io.CtlIO(i).fire()) {
      // Detecting when to reset
      ctl_ready_R(i) := io.CtlIO(i).ready
      // Propagating output
      ctl_valid_R(i) := false.B
    }
  }

  /*===============================================*
   *            Latch inputs. Wire up output       *
   *===============================================*/

  //printfInfo("start: %x\n", start)

  io.InputIO.ready := ~input_valid_R
  when(io.InputIO.fire()) {
    //printfInfo("Latch left data\n")
    state := s_LATCH
    input_R <> io.InputIO.bits
    input_valid_R := true.B
  }

  /*============================================*
   *            ACTIONS (possibly dangerous)    *
   *============================================*/

  // Wire up Outputs
  for (i <- 0 until NumOuts) {
    io.Out(i).bits <> input_R
    io.CtlIO(i).bits.control <> input_R.predicate
  }

  when(start && state =/= s_COMPUTE) {
    state := s_COMPUTE
    when(predicate) {
      ValidOut()
      ctl_valid_R := VecInit(Seq.fill(NumOuts)(true.B))
    }
  }


  /*==========================================*
   *            Output Handshaking and Reset  *
   *==========================================*/

  when(IsOutReady() && ( out_ready_R.asUInt.andR || out_ready_W.asUInt.andR) && (state === s_COMPUTE)) {
    // Reset data
    input_R := DataBundle.default
    input_valid_R := false.B

    //Reset state
    state := s_idle
    //Reset output
    Reset()
    ctl_ready_R := VecInit(Seq.fill(NumOuts)(false.B))
    when(predicate) {
      printfInfo("Output fired")
    }
  }
}
