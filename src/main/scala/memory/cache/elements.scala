package memGen.memory.cache

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util.{Valid, _}
import chisel3.util._
import chisel3.util.Cat
import memGen.interfaces.Action
import memGen.config.{AXIAccelBundle, HasAccelShellParams}

class DecoderIO (nSigs: Int)(implicit val p:Parameters) extends Bundle {
    val inAction = Input(UInt(nSigs.W))
    val outSignals = Output(Vec(nSigs, Bool()))
}

class Decoder (nSigs:Int) (implicit val p:Parameters) extends Module
  with HasCacheAccelParams
  with HasAccelShellParams {

    val io = IO(new DecoderIO(nSigs))
    io.outSignals := io.inAction.asBools()
}
class Find[T1 <: Data , T2 <: Data]( K: T1, D:T2, depth: Int, outLen: Int = 32) (implicit val p:Parameters) extends Module {

    val io = IO(new Bundle {
        val key = Input(D.cloneType)
        val data = Input(Vec(depth, D.cloneType))
        val valid = Input(Vec(depth, Bool()))
        val value = Valid(UInt(outLen.W))
    })

    val bitmap = Wire(UInt(depth.W))
    val idx = Wire(UInt(outLen.W))

    bitmap := Cat(io.data.map(addr => (addr.asUInt() === io.key.asUInt() )).reverse)
    idx := OHToUInt((bitmap & io.valid.asUInt()))

    io.value.bits := idx
    io.value.valid := (bitmap & io.valid.asUInt()) =/= 0.U
}

class FindMultiLine[T1 <: Data , T2 <: Data]( K: T1, D:T2, depth: Int, outLen: Int = 32) (implicit val p:Parameters) extends Module {

    val io = IO(new Bundle {
        val key = Input(D.cloneType)
        val data = Input(Vec(depth, D.cloneType))
        val valid = Input(Vec(depth, Bool()))
        val value = Valid(Vec(depth, Bool()))
    })

    val bitmap = Wire(UInt(depth.W))
    val idx = Wire(UInt(outLen.W))

    bitmap := Cat(io.data.map(addr => (addr.asUInt() === io.key.asUInt() )).reverse)
    idx := OHToUInt((bitmap & io.valid.asUInt()))

    io.value.bits := bitmap.asTypeOf(Vec(depth, Bool()))
    io.value.valid := (bitmap & io.valid.asUInt()) =/= 0.U
}

class FindEmptyLine(depth: Int, outLen: Int) (implicit val p:Parameters) extends Module {

    val io = IO(new Bundle {
        val data = Input(Vec(depth, Bool()))
        val value = Valid(UInt(outLen.W))
    })

    io.value.valid := false.B

    val idx = WireInit(depth.U((outLen + 1).W))

    (0 until depth).foldLeft(when(false.B) {}) {
        case (whenContext, searchIdx) =>
            whenContext.elsewhen(io.data(searchIdx) === false.B) {
                idx := searchIdx.U
                io.value.valid := true.B
            }
    }
    io.value.bits := idx

}

class paralRegIO [T <: Data](gen: T, size: Int, pDegree: Int, nRead:Int)(implicit val p: Parameters) extends Bundle
  with HasCacheAccelParams  {


    val port = Vec(pDegree, new Bundle {
        val write = Flipped(Valid(new Bundle {
            val addr = (UInt(xlen.W))
            val value = (gen.cloneType)
        }))

        val read = new Bundle {
            val in = Flipped(Valid(new Bundle {
                val addr = (UInt(xlen.W))
            }))
            val out = Vec(nRead, Output(gen.cloneType))
        }
    })
}

class paralReg[T <: Data](gen: T, size: Int, pDegree: Int, nRead:Int)(implicit val p: Parameters) extends Module
with HasCacheAccelParams {


    val io = IO( new paralRegIO(gen, size, pDegree, nRead))
    val content = RegInit(VecInit(Seq.fill(size)(0.U))) // @todo should be generic

    val readEn = Wire(Vec(pDegree, Bool()))
    val writeEn = Wire(Vec(pDegree, Bool()))

    for (i <- 0 until pDegree) {
        readEn(i) := io.port(i).read.in.fire()
        writeEn(i) := io.port(i).write.fire()


        when(readEn(i)) {
            io.port(i).read.out := (Cat((0 until nRead).map( j => content(io.port(i).read.in.bits.addr + j.asUInt())).reverse )).asTypeOf(Vec(nRead, gen.cloneType))
        }.otherwise{
            io.port(i).read.out := (Cat((0 until nRead).map( j => 0.U)).asTypeOf(Vec(nRead, gen.cloneType)))
        }

        when(writeEn(i)) {
            content(io.port(i).write.bits.addr) := io.port(i).write.bits.value
        }
    }
}


class lockVectorIO (implicit val p :Parameters) extends Bundle
with HasCacheAccelParams {


    val lock = new portWithCMD(UInt(addrLen.W), UInt(2.W), Bool())(addrLen)
    val probe = lock.cloneType
    val unLock = Vec(nParal, lock.cloneType)
}

class lockVector (implicit val p :Parameters) extends Module
with HasCacheAccelParams {

    val PROBE = 0
    val LOCK = 1
    val UNLOCK = 2

    val io = IO (new lockVectorIO())

    val addrVec = RegInit(VecInit(Seq.fill(lockDepth)(0.U(addrLen.W))))
    val valid   = RegInit(VecInit(Seq.fill(lockDepth)(false.B)))

    val bitmapProbe =  Wire(UInt(lockDepth.W))

    val idxLocking = Wire(UInt(lockDepth.W))
    val idxProbe   = Wire(idxLocking.cloneType)
    val idxUnlock  = Wire(Vec(nParal, idxLocking.cloneType))

    val finder = for (i <- 0 until nParal) yield{
        val Finder = Module(new Find(UInt(addrLen.W), UInt(addrLen.W), lockDepth, log2Ceil(lockDepth)))
        Finder
    }
//    io.isLocked.bits := addrVec.map( addr => (io.inAddress.bits === addr)).foldLeft(0.U)({
//        case (res, bit) =>
//         Cat(res,bit)
//    } )

//    def idxFinder(comp: UInt) : UInt = Cat( addrVec.map( addr => (addr === comp)))

    val isLocked = Wire(Bool())

    val probe =   WireInit(io.probe.in.fire() && (io.probe.in.bits.cmd === PROBE.U))
    val write =   WireInit(!isLocked && io.lock.in.fire() && (io.lock.in.bits.cmd === LOCK.U))
    val erase =   Wire(Vec(nParal, Bool()))

    //
    bitmapProbe := (Cat( addrVec.map( addr => (addr === addrNoOffset(io.probe.in.bits.addr))).reverse))
    idxProbe := OHToUInt((bitmapProbe & valid.asUInt())) // exactly one bit should be one otherwise OHToUInt won't work
    idxLocking := lockDepth.U


    for (i <- 0 until nParal) {
        erase(i) := (io.unLock(i).in.fire() && (io.unLock(i).in.bits.cmd === UNLOCK.U))
        finder(i).io.data := addrVec
        finder(i).io.key := addrNoOffset(io.unLock(i).in.bits.addr)
        finder(i).io.valid := valid
        idxUnlock(i) := finder(i).io.value.bits
        io.unLock(i).out := DontCare
    }
    for (i <- 0 until nParal) {
        when(erase(i) && finder(i).io.value.valid) {
            valid(idxUnlock(i)) := false.B
        }
    }

    (0 until lockDepth).foldLeft(when(false.B) {}) {
        case (whenContext, line) =>
            whenContext.elsewhen(valid(line) === false.B) {
                idxLocking := line.U
            }
    }

    isLocked := ((bitmapProbe & valid.asUInt()) =/= 0.U)
    io.probe.out.bits := isLocked
    io.probe.out.valid := probe

    io.lock.out := DontCare

    when(write) {
        addrVec(idxLocking) := addrNoOffset(io.lock.in.bits.addr)
    }

    when(write){
        valid(idxLocking) := true.B
    }
}

class stateMemIO (implicit val p: Parameters) extends Bundle
with HasCacheAccelParams{

    val in = Vec ((nParal + 1), Flipped(Valid(new Bundle() {
        val state = new State()
        val addr = UInt(addrLen.W)
        val way  = UInt(wayLen.W)
        val isSet = Bool()
    } )))
    val out = Valid(new State())
}

/* the last in port is used for getting */
class stateMem (implicit val p: Parameters) extends Module
  with HasCacheAccelParams{

    val io = IO (new stateMemIO())

    val states = RegInit(VecInit(Seq.fill(nSets*nWays)( (State.default))))

    val isSet = Wire(Vec(nParal, Bool()))
    val isGet = io.in(nParal).fire() & !io.in(nParal).bits.isSet & io.in(nParal).bits.way =/= nWays.U // third one might be unnecessary

    val idxGet = Wire(UInt(cacheLen.W))
    val idxSet =  Wire(Vec(nParal, UInt(cacheLen.W)))

    for (i <- 0 until nParal) {
        isSet  (i) := io.in(i).fire() & io.in(i).bits.isSet & io.in(i).bits.way =/= nWays.U
        idxSet (i) := Mux(io.in(i).bits.way =/= nWays.U, addrToSet(io.in(i).bits.addr) * nWays.U + io.in(i).bits.way, 0.U)

    }
    for (i <- 0 until nParal) {
        when(isSet(i)) {
            states(idxSet(i)) := io.in(i).bits.state
        }
    }

    idxGet :=  Mux(io.in(nParal).bits.way =/= nWays.U, addrToSet(io.in(nParal).bits.addr) * nWays.U + io.in(nParal).bits.way, 0.U)
    io.out.bits := states(idxGet)
    io.out.valid := (io.in(nParal).bits.way =/= nWays.U) & isGet

  }

class PCIO ( implicit val p:Parameters) extends Bundle
with HasCacheAccelParams{

    val write = Flipped(Decoupled(new PCBundle))

    val read = Vec(nParal ,  new portNoAddr(new PCBundle, new PCBundle))
    val isFull = Output(Bool())

}

class PC (implicit val p :Parameters) extends Module
with HasCacheAccelParams{

   val io = IO (new PCIO())

    val pcContent =  RegInit(VecInit(Seq.fill(nParal)(PCBundle.default)))

    val write = WireInit (io.write.fire())

    val findNewLine = Module(new FindEmptyLine(nParal,log2Ceil(nParal + 1)))
    findNewLine.io.data := pcContent.map(_.valid).toVector
    val writeIdx = WireInit(findNewLine.io.value.bits)

    for (i <-  0 until nParal){
        io.read(i).out.bits  <> pcContent(i)
        io.read(i).out.valid := pcContent(i).valid
    }

    for (i <- 0 until nParal){
        when(!write | (write & writeIdx =/= i.U)){
            pcContent(i).pc := io.read(i).in.bits.data.pc
            pcContent(i).valid := io.read(i).in.bits.data.valid
            pcContent(i).way := io.read(i).in.bits.data.way
        }
    }

    when( write){
        pcContent(writeIdx)<> io.write.bits
    }

    io.write.ready := findNewLine.io.value.valid
    io.isFull := !io.write.ready
}

class bipassLDIO (implicit val p:Parameters) extends  Bundle
with HasCacheAccelParams{

        val in = Flipped(Valid( new Bundle{
            val addr = (UInt(addrLen.W))
            val way = UInt((wayLen + 1).W)
        }))
        val dataMem = Flipped (new MemBankIO(UInt(xlen.W)) (dataLen = xlen, addrLen= addrLen, banks =nWords, bankDepth= nSets * nWays, bankLen = wordLen).read)

        val out = Valid(new Bundle() {
            val data = UInt((xlen*nWords).W)
        })


}
class bipassLD (implicit val p: Parameters) extends Module
with HasCacheAccelParams {

    val io = IO(new bipassLDIO())
    val set = addrToSet(io.in.bits.addr)
    val dataRead = RegInit(VecInit(Seq.fill(nWords)(0.U(xlen.W))))

    io.dataMem.in.valid := io.in.fire()
    io.dataMem.in.bits.bank := 0.U
    io.dataMem.in.bits.address := set * nWays.U + io.in.bits.way

    dataRead := io.dataMem.outputValue

    io.out.valid := RegNext(io.in.fire())
    io.out.bits.data  := Cat(dataRead).asUInt()
}