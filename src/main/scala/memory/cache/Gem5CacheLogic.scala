package memGen.memory.cache

import chisel3._
import chisel3.util._
import chipsalliance.rocketchip.config._
import memGen.config._
import memGen.util._
import memGen.interfaces._
import chisel3.util.experimental._
import memGen.interfaces.axi._
import chisel3.util.experimental.loadMemoryFromFile

import memGen.junctions._
import memGen.shell._

trait HasCacheAccelParams extends HasAccelParams with HasAccelShellParams {

  // val (memType :: cacheType :: Nil) = Enum(2)
  //Caused Weird error!!



  val States = accelParams.States
  val Events = accelParams.Events
  val BM = accelParams.benchmark

  val nWays = accelParams.nways
  val nSets = accelParams.nsets
  val nCache = accelParams.nCache


  val tbeDepth = accelParams.tbeDepth
  val lockDepth = accelParams.lockDepth

  val nTBEFields = 1
  val nTBECmds = 4
  val TBEFieldWidth = 32

  val CompLen = 64
  override val addrLen = accelParams.addrlen
  val dataLen = accelParams.dataLen
  val eventLen = Events.eventLen
  val stateLen = States.stateLen

  //  val nStates = States.stateLen
  val nCom = 8
  val nParal = accelParams.nParal
  val pcLen = 16

  val wBytes = xlen >> 3
  val nWords = accelParams.nWords // 4

  val bBits = bSize
  val blen = log2Ceil(nWords) // 2
  
  val byteOffsetBits = log2Ceil(wBytes) //
  val wordLen = byteOffsetBits

//  val offsetLen = blen + byteOffsetBits
  def getOffsetLen: Int = BM.toLowerCase() match {
    case "walker" =>
      0
    case "dasxarray" =>
      blen + byteOffsetBits
    case "gp" =>
      0
    case "sparch" =>
      blen + byteOffsetBits

    case "syn" =>
      blen + byteOffsetBits
  }
  val offsetLen = getOffsetLen

  val nData = bBits / memParams.dataBits
  val dataBeats = nData

  val setLen = log2Ceil(nSets)
  val wayLen = log2Ceil(nWays) + 1
  val cacheLen = log2Ceil(nSets*nWays)

  val taglen = addrLen - (setLen + offsetLen) // 3 + 2

  val replacementPolicy = "lru"

  val actionTypeLen = ActionList.typeLen
  val nSigs = ActionList.actionLen - actionTypeLen
  val actionLen = ActionList.actionLen

  val opcodeWidth :Int = 0
  val funcWidth: Int = 4
  val regFileSize: Int = 4
  val regFileLen = log2Ceil(regFileSize)
  val instructionWidth = nSigs


  def addrToSet(addr: UInt): UInt = {
    val set = addr(setLen - 1 + offsetLen , offsetLen)
    set.asUInt()
  }

  def addrNoOffset (addr: UInt) :UInt  ={
    val addrNoOff = addr >> (offsetLen)
    addrNoOff.asUInt()
  }

  def addrToTag(addr: UInt): UInt = {
    val tag = (addr(addrLen - 1, setLen + offsetLen))
    tag.asUInt()
  }


  def sigToAction(sigs : Bits) :UInt = sigs.asUInt()(nSigs - 1, 0)
  def sigToActType(sigs : Bits) :UInt = sigs.asUInt()(nSigs + actionTypeLen - 1, nSigs)

  def sigToTBEOp2(sigs : Bits) : UInt = sigs.asUInt()(log2Ceil(regFileSize) +TBE.default.fieldLen + TBE.default.cmdLen - 1 , TBE.default.fieldLen + TBE.default.cmdLen)
  def sigToTBEOp1(sigs : Bits) : UInt = sigs.asUInt()(TBE.default.fieldLen + TBE.default.cmdLen  - 1, TBE.default.cmdLen)
  def sigToTBECmd(sigs : Bits) : UInt = sigs.asUInt()(TBE.default.cmdLen - 1, 0)

  def sigToState (sigs :Bits) : UInt = sigs.asUInt()(States.stateLen - 1, 0)

  def sigToCompOpSel1(sigs:Bits): UInt = sigs.asUInt()(0,0)
  def sigToCompOpSel2(sigs:Bits): UInt = sigs.asUInt()(2,1)

  def sigToDQSrc(sigs:Bits): UInt = sigs.asUInt()(0,0)
  def sigToDQRegSrc(sigs:Bits): UInt = sigs.asUInt()(regFileLen,1)

  def sigToFeedbackInc (sigs:Bits): UInt = sigs.asUInt()(7,0)
  def sigToFeedbackData (sigs:Bits): UInt = sigs.asUInt()(11,8)
  def sigToFeedbackEvent (sigs:Bits): UInt = sigs.asUInt()(12 + eventLen - 1,12)

  def SelectROM(BM : String): RoutineRom ={
    BM.toLowerCase() match {
      case "walker" =>
        RoutineROMWalker
      case "dasxarray" =>
        RoutineROMDasxArray
      case "sparch" =>
        RoutineROMSpArch
      case "gp" =>
        RoutineROMGP
      case "syn" =>
        RoutineROMSyn
    }


  }

  def SelectNextRoutine (BM : String): NextRoutine = {
    BM.toLowerCase() match {
      case "walker" =>
        nextRoutineWalker
      case "dasxarray" =>
        nextRoutineDASX
      case "gp" =>
        nextRoutineGP
      case "sparch" =>
        nextRoutineSpArch
      case "syn" =>
        nextRoutineSyn
    }
  }




}

class CacheCPUIO(implicit p: Parameters) extends DandelionGenericParameterizedBundle(p) {
  val abort = Input(Bool())
  val flush = Input(Bool())
  val flush_done = Output(Bool())
  val req = Flipped(Decoupled(new MemReq))
  val resp = (Valid(new MemResp))
}

// @todo bipass for reading
class Gem5CacheLogic(val ID:Int = 0)(implicit  val p: Parameters) extends Module
  with HasCacheAccelParams
  with HasAccelShellParams {

  val io = IO(new Bundle {

    val cpu = new CacheCPUIO

    val metaMem =  Flipped (new MemBankIO(new MetaData())(dataLen = xlen, addrLen = setLen, banks = nWays, bankDepth = nSets, bankLen = wayLen))
    val dataMem =  Flipped (new MemBankIO(UInt(xlen.W)) (dataLen = xlen, addrLen= addrLen, banks =nWords, bankDepth= nSets * nWays, bankLen = nWords))

    val validBits = new RegIO(Bool(), nRead = 1)
    val validTagBits = new RegIO(Bool(), nWays)
//    val dirtyBits = new RegIO(Bool(), nWays)

  })
  val decoder = Module(new Decoder(nSigs))
  val cacheID = WireInit(ID.U(8.W))

  io.cpu <> DontCare
  io.metaMem <> DontCare
  io.dataMem <> DontCare

  val Axi_param = memParams


  // cache states

  val (sigLoadWays :: sigFindInSet ::sigAddrToWay :: sigPrepMDRead ::sigPrepMDWrite:: sigAllocate :: sigDeallocate :: sigWrite :: sigRead :: sigDataReq:: Nil) = Enum(10)

  val addr_reg = RegInit(0.U(io.cpu.req.bits.addr.getWidth.W))
  val cpu_data = RegInit(0.U(io.cpu.req.bits.data.getWidth.W))
  val cpu_mask = RegInit(0.U(io.cpu.req.bits.mask.getWidth.W))
  val cpu_command = RegInit(0.U(io.cpu.req.bits.command.getWidth.W))

  val tag = RegInit(0.U(taglen.W))
  val set = RegInit(0.U(setLen.W))
  val wayInput = RegInit(nWays.U((wayLen + 1).W))
  val replaceWayInput = RegInit(nWays.U((wayLen + 1).W))

  val way = WireInit(0.U((wayLen + 1).W))

  val dataBuffer = RegInit(VecInit(Seq.fill(nWords)(0.U(xlen.W))))

  val findInSetSig = Wire(Bool())
  val addrToLocSig = Wire(Bool())
  val dataValidCheckSig = Wire(Bool())

  val start = WireInit(VecInit(Seq.fill(nCom)(false.B)))
  val res = WireInit(VecInit(Seq.fill(nCom)(false.B)))
  val done = WireInit(false.B)
  val dataValid = WireInit(false.B)

  // Counters
  require(nData > 0)

  val signals = Wire(Vec(nSigs, Bool()))

  val loadWaysMeta = Wire(Bool())
  val loadLineData = Wire(Bool())
  //  val loadFromMemSig = Wire(Bool())
  val loadDataBuffer = Wire(Bool())

  val waysInASet = Reg(Vec(nWays, new MetaData()))
//  val cacheLine = Reg(Vec(nWords, UInt(xlen.W)))

  val addrReadValid = Wire(Bool())
  val dataReadReady = Wire(Bool())

  val addrWriteValid = Wire(Bool())
  val dataWriteValid = Wire(Bool())

  val wayInvalid = Wire(Bool())

  val readMetaData = Wire(Bool())
  val targetWayReg = RegInit(nWays.U((wayLen + 1).W))
  val targetWayWire = WireInit(nWays.U((wayLen + 1).W))

  val hit = Wire(Bool())

  /********************************************************************************/
  // Signals
  val allocate = WireInit(signals(sigAllocate))
  val prepMDRead = WireInit(signals(sigPrepMDRead))
  val prepMDWrite = WireInit(signals(sigPrepMDWrite))
  val deallocate = WireInit(signals(sigDeallocate))
  val addrToWaySig = WireInit(signals(sigAddrToWay))
  val writeSig = WireInit(signals(sigWrite))
  val readSig = WireInit(signals(sigRead))
  val dataReq = WireInit(signals(sigDataReq))

  loadWaysMeta := signals(sigLoadWays)
  findInSetSig := signals(sigFindInSet)
  /********************************************************************************/

  /********************************************************************************/
  // Latching
  when(io.cpu.req.fire()) {
    addr_reg := io.cpu.req.bits.addr
    cpu_data := io.cpu.req.bits.data
    cpu_mask := io.cpu.req.bits.mask
    cpu_command := io.cpu.req.bits.command
    tag := addrToTag(io.cpu.req.bits.addr)
    set := addrToSet(io.cpu.req.bits.addr)
    wayInput := io.cpu.req.bits.way
    replaceWayInput := io.cpu.req.bits.replaceWay
  }
//
//  when(loadLineData) {
//    cacheLine := io.dataMem.read.outputValue
//  }

//  when(dataValidCheckSig) {
//    dataValid := validBits(set * nSets.U + way)
//  }

  dataValid := io.validBits.read.out.asUInt()
  io.validBits.read.in.bits.addr := set * nSets.U + way
  io.validBits.read.in.valid := dataValidCheckSig

  /********************************************************************************/



  /********************************************************************************/
  //Decoder
  decoder.io.inAction := cpu_command
  signals := decoder.io.outSignals
  /********************************************************************************/

  io.cpu.req.ready := true.B

  loadLineData := false.B
  addrToLocSig := false.B
  dataValidCheckSig := false.B

  addrWriteValid := false.B
  dataWriteValid := false.B
  addrReadValid := false.B
  dataReadReady := false.B
  loadDataBuffer := false.B

  def prepForRead[T <: Data](D: MemBankIO[T]): Unit = {
    D.read.in.valid := true.B

    D match {
      case io.dataMem => {
        D.read.in.bits.address := set * nSets.U + way
        D.read.in.bits.bank := 0.U
      }
      case io.metaMem => {
        D.read.in.bits.bank := 0.U
        D.read.in.bits.address := set
      }
    }
  }

  def prepForWrite[T <: Data](D: MemBankIO[T]): Unit = {
    D.write.valid := true.B

    D match {
      case io.dataMem => {
        D.write.bits.address := set * nSets.U + way
        D.write.bits.bank := 1.U << nWords.U - 1.U
      }
      case io.metaMem => {
        D.write.bits.bank := 1.U << way
        D.write.bits.address := set
      }
    }
  }

  val MD = Wire(new MetaData())
  MD.tag := tag
  io.metaMem.write.valid := false.B
  io.dataMem.write.valid := false.B
  io.metaMem.read.in.valid := false.B
  io.dataMem.read.in.valid := false.B

  wayInvalid := (wayInput === nWays.U)

  val (missCount, _) = Counter( !hit, 1000)
  val (hitCount,  _) = Counter( hit , 1000)
  hit := (readSig & !wayInvalid)

  readMetaData := !(sigAllocate | sigDeallocate)

  val emptyLine = Module(new FindEmptyLine(nWays, addrLen))
  emptyLine.io.data := io.validTagBits.read.out

  val tagFinder = Module(new Find(new MetaData(), new MetaData(),nWays, addrLen))
  tagFinder.io.key := MD
  tagFinder.io.data := io.metaMem.read.outputValue
  tagFinder.io.valid := io.validTagBits.read.out

  when(addrToWaySig | (findInSetSig & loadWaysMeta)){
    io.validTagBits.read.in.bits.addr := set * nWays.U
  }.otherwise{
    io.validTagBits.read.in.bits.addr := 0.U
  }

  io.validTagBits.read.in.valid := addrToWaySig  | (findInSetSig & loadWaysMeta) // probing and allocating


  when (addrToWaySig) {
    way := replaceWayInput
  }.elsewhen(!wayInvalid){
    way := wayInput
  }.otherwise{
    way := nWays.U
  }

  when(prepMDRead){
    prepForRead(io.metaMem)
  }.elsewhen(prepMDWrite){
    prepForWrite(io.metaMem)
  }

  when(writeSig){
    prepForWrite(io.dataMem)
  }.elsewhen(readSig){
    prepForRead(io.dataMem)
  }

  when(writeSig){
    io.dataMem.write.bits.inputValue := cpu_data.asTypeOf(Vec(nWords, UInt(xlen.W)))
  }.otherwise{
    io.dataMem.write.bits.inputValue := DontCare
  }

  io.validBits.write.valid := writeSig
  io.validBits.write.bits.addr := (set * nWays.U + way)
  io.validBits.write.bits.value := writeSig
//
//  when(writeSig){
//    io.validBits.write.bits.value := true.B
//  }

  when (readSig){
    dataBuffer := io.dataMem.read.outputValue
  }.otherwise{
    dataBuffer := dataBuffer
  }
  io.metaMem.write.bits.inputValue:= DontCare

  when(prepMDWrite){
      io.metaMem.write.bits.inputValue(way) := MD
  }.otherwise{
      io.metaMem.write.bits.inputValue := DontCare
  }

  when((findInSetSig & loadWaysMeta)) { // probing way
    targetWayWire := Mux(tagFinder.io.value.valid, tagFinder.io.value.bits, nWays.U)
  }.elsewhen(addrToWaySig) { // allocate
    targetWayWire := way
  }.otherwise{
    targetWayReg := targetWayReg // @todo no completed
  }
  targetWayReg := targetWayWire

//  when(allocate | deallocate){
//    io.metaMem.isRead := false.B
//  }.otherwise{
//    io.metaMem.isRead := true.B
//  }

  when(loadWaysMeta){
    waysInASet := io.metaMem.read.outputValue
  }.otherwise{
    waysInASet := waysInASet
  }

  io.validTagBits.write.bits.addr := (set * nWays.U + way)
  io.validTagBits.write.valid := allocate | deallocate
  io.validTagBits.write.bits.value := allocate | !deallocate
//  when(allocate ) {
//    validTagBits := true.B
//  }.elsewhen(deallocate){
//    validTagBits(set * nWays.U + way) := false.B
//  }

  io.cpu.resp.bits.way := targetWayWire
  io.cpu.resp.bits.data := Cat(dataBuffer)
  io.cpu.resp.bits.iswrite := RegNext(readSig)
  io.cpu.resp.valid    := addrToWaySig | (findInSetSig & loadWaysMeta) | RegNext(readSig) // @todo should be changed to sth more generic

  when(true.B){

    when(addrToWaySig && !emptyLine.io.value.valid ){
      printf(p"Replacement in Set: ${set}, Way: ${way}, Addr: ${addr_reg}\n")
    }

  }
  val boreWire = WireInit((addrToWaySig && !emptyLine.io.value.valid))
      // BoringUtils.addSource(boreWire, "numRepl")

    // when(cacheID =/= nParal.U && io.cpu.req.fire()){
    //   printf(p"Req for Cache_Node: ${cacheID} in Set: ${set}, Way: ${way}, Addr: ${addr_reg}\n")
    // }
  
}
