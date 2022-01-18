package memGen.memory.cache

import chisel3._
import chisel3.util._
import chipsalliance.rocketchip.config._
import memGen.config._
import memGen.util._
import memGen.interfaces._
import memGen.interfaces.axi._
import memGen.memory.message._
import chisel3.util.experimental.loadMemoryFromFile
import memGen.junctions._
import memGen.shell._
import dsptools.counters.CounterWithReset




class memoryWrapperIO (implicit val p:Parameters) extends Bundle
with HasAccelShellParams
with HasCacheAccelParams {

    val in = Flipped(Decoupled( new Flit()))
    val mem = new AXIMaster(memParams)
    val out = Decoupled(new Flit())
//    mem.tieoff()
}

class memoryWrapper ( ID:Int = 4)(implicit val p:Parameters) extends Module
with HasCacheAccelParams
with HasAccelShellParams{
    val io = IO(new memoryWrapperIO)

    val DRAM_LATENCY = 200

    val addrReg = RegInit(0.U(addrLen.W))
    val numberOfLines = RegInit(0.U(3.W))
    val srcReg = RegInit(0.U(io.out.bits.srcLen.W))
    val returnAddr = RegInit(0.U(addrLen.W))
    val dataRegRead = RegInit(VecInit(Seq.fill(nData * 7)(0.U(xlen.W))))
    val dataRegWrite = RegInit(VecInit(Seq.fill(nData)(0.U(xlen.W))))
    val (rd :: wr_back :: Nil ) = Enum(2)
    val (stIdle :: stWriteAddr :: stWriteData :: stReadAddr :: stReadData :: stCmdIssue :: Nil) = Enum(6)
    val stReg = RegInit(stIdle)

    val start = Wire(Bool())
    start := io.in.fire() & stReg === stIdle & io.in.bits.data(addrLen -1 , 0) =/= 0.U

    when (start){
        printf(p"Memory Ctrl started Addr: ${(io.in.bits.data(addrLen -1 , 3) << 3)} Data: ${Mux(io.in.bits.data(2,0) === 0.U(3.W), 1.U, io.in.bits.data(2,0))}\n")

        addrReg       := io.in.bits.data(addrLen -1 , 3) << 3 // use the data of the package for its requests
        numberOfLines := Mux(io.in.bits.data(2,0) === 0.U(3.W), 1.U, io.in.bits.data(2,0))
        returnAddr    := io.in.bits.addr
        srcReg        := io.in.bits.src
    }

    io.in.ready := stReg === stIdle

    val writeInst = WireInit(start & io.in.bits.inst === wr_back)

    // Reading
    val (readCount, readWrapped)   = CounterWithReset (io.mem.r.fire(), nData * 7, start)

    val (writeCount, writeWrapped) = CounterWithReset (io.mem.w.fire(), nData, start)

    val (dramLatencyCnt, _) =  CounterWithReset(stReg === stCmdIssue, 1000000, start )
    val (nextPacket, _) =  CounterWithReset(io.out.fire(), 1000000, start )
    val packetsDone = WireInit((nextPacket === numberOfLines - 1.U))

    when(io.out.fire()) {
        returnAddr := returnAddr + (nData.U << 3)
    }

    when(writeInst){
        dataRegWrite := io.in.bits.data.asTypeOf(Vec(nData, UInt(xlen.W)))
    }

    when(io.mem.r.fire()) {
        dataRegRead(readCount) := io.mem.r.bits.data
    }
// 
    // val lenBits = 64.U
    io.mem.aw.bits.addr := Mux(stReg === stWriteAddr, addrReg, 0.U(addrLen.W))
    io.mem.aw.bits.len := nData.U

    io.mem.w.bits.data := Mux(stReg === stWriteData, dataRegWrite(writeCount), 0.U(xlen.W))
    io.mem.w.bits.last := false.B

    io.mem.ar.bits.addr := addrReg
    io.mem.ar.bits.len := nData.U * numberOfLines - 1.U
    io.mem.b.ready := stReg === stWriteData

    io.mem.ar.valid := false.B
    io.mem.aw.valid := false.B
    io.mem.r.ready := false.B
    io.mem.w.valid := false.B

    val issueCmd = Wire(Bool())
    issueCmd := false.B


    io.out.bits.data := Cat((0 until nData).map (i => dataRegRead(nextPacket * nData.U + i.U)))
    io.out.bits.addr := returnAddr
    io.out.bits.inst := Events.EventArray("DATA").U
    io.out.bits.src := ID.U
    io.out.bits.dst := srcReg
  //  io.out.bits.msgType := memType Cause Bug!
    io.out.bits.msgType := 0.U

    io.out.valid := false.B

    switch(stReg){
        is(stIdle){
            when (start){
                when(writeInst){
                    stReg := stWriteAddr
                }.otherwise{
                    stReg := stReadAddr
                }
            }
        }
        is(stReadAddr){
            io.mem.ar.valid := true.B
            when(io.mem.ar.fire()){
                stReg := stReadData
            }
        }
        is(stReadData) {
            io.mem.r.ready := true.B
            when(io.mem.r.fire() && io.mem.r.bits.last) {
                stReg := stCmdIssue
            }
        }
        is(stWriteAddr){
            io.mem.aw.valid := true.B
            when(io.mem.aw.fire()){
                stReg := stWriteData
            }
        }
        is (stWriteData){
            io.mem.w.valid := true.B
            when(writeWrapped){
                stReg := stIdle
            }
        }
        is(stCmdIssue){
            when (dramLatencyCnt > DRAM_LATENCY.U){
                io.out.valid := true.B
                when(packetsDone && io.out.fire()){
                    stReg := stIdle
                }
            }

        }
    }

    io.mem.setConst()


}
