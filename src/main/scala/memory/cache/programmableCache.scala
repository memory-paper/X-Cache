package dandelion.memory.cache

import chisel3._
import chisel3.util._
import chipsalliance.rocketchip.config._
import dandelion.config._
import dandelion.util._
import dandelion.interfaces._
import dandelion.interfaces.Action
//import dandelion.memory.TBE
import dandelion.interfaces.axi._
import chisel3.util.experimental.loadMemoryFromFile
//import dandelion.memory.TBE.{ TBETable, TBE}


class programmableCacheIO (implicit val p:Parameters) extends Bundle
with HasCacheAccelParams
with HasAccelShellParams {

    val instruction = Flipped(Decoupled(new InstBundle))
}

class programmableCache (implicit val p:Parameters) extends Module
with HasCacheAccelParams
with HasAccelShellParams{

    val io = IO(new programmableCacheIO())

    val cache = Module(new Gem5Cache())
    val tbe = Module(new TBETable())
    val lockMem = Module(new lockVector())
    val stateMem = Module (new stateMem())
    val pc = Module(new PC())

    stateMem.io <> DontCare
    cache.io.cpu <> DontCare
    cache.io.mem <> DontCare

    /********************************************************************************/
    // Building ROMs
    val (routineAddr,setStateDst) = RoutinePtr.generateRoutineAddrMap(RoutineROM.routineActions)
    val (rombits, dstStateBits) = RoutinePtr.generateTriggerRoutineBit(routineAddr, nextRoutine.routineTriggerList)

    val uCodedNextPtr = VecInit(rombits)
    val dstStateRom = VecInit(dstStateBits)

    val actions = RoutinePtr.generateActionRom(RoutineROM.routineActions, ActionList.actions, setStateDst)
    val actionRom = VecInit(actions)
    /********************************************************************************/

    val state    = Wire(UInt(stateLen.W))
    val inputTBE = Wire(Vec(nParal, new TBE))

    val addr = RegInit(0.U(addrLen.W))
    val event = RegInit(0.U(eventLen.W))
    val pcWire = Wire(Vec(nParal, new PCBundle()))
    val updatedPC = Wire(Vec(nParal, UInt(pcLen.W)))
    val updatedPCValid = Wire(Vec(nParal, Bool()))

    val tbeAction   = Wire(Vec(nParal, UInt(nSigs.W)))
    val cacheAction = Wire(Vec(nParal, UInt(nSigs.W)))
    val stateAction = Wire(Vec(nParal, Bool()))

    val isTBEAction   = Wire(Vec(nParal, Bool()))
    val isStateAction = Wire(Vec(nParal, Bool()))
    val isCacheAction = Wire(Vec(nParal, Bool()))

    val isLocked      = Wire(Bool())

    val updateTBEWay   = Wire(Vec(nParal, Bool()))
    val updateTBEState = Wire(Vec(nParal, Bool()))

    val stall = WireInit(false.B)
    val stallInput = WireInit(false.B)
    val bubble = WireInit(stallInput)

    val readTBE   = Wire(Bool())
    val checkLock = Wire(Bool())
    val getState  = Wire(Bool())

    val routineAddrResValid = Wire(Bool())
    val actionResValid      = Wire(Vec(nParal, Bool()))
    val startRoutine = Wire(Bool())

    val defaultState = Wire(new State())

    val firstLineNextRoutine = Wire(Vec(nParal, Bool()))
    val endOfRoutine   = Wire(Vec(nParal, Bool()))

    val routine = WireInit( Cat (event, state))
    val cacheWayReg  = RegInit(nWays.U((wayLen+1).W))
    val cacheWayWire = Wire(UInt((wayLen+1).W))

    val wayInputCache = Wire(UInt((wayLen+1).W))
    val tbeWay        = RegInit(nWays.U((wayLen+1).W))

    val routineReg = Wire(UInt((eventLen + stateLen).W))
    val actionReg  = Wire(Vec(nParal, new ActionBundle()))

    val dstOfSetState = Wire(Vec(nParal, new State()))
    val stateMemOutput = Wire((new State()))

    val probeStart = WireInit(io.instruction.fire())

    val tbeActionInRom = Wire(Vec(nParal, Bool()))

    val addrWire   = WireInit(io.instruction.bits.addr)

    val addrInputCache  = Wire(UInt(addrLen.W))

    //latching
    when( io.instruction.fire() ){
        addr := io.instruction.bits.addr
        event := io.instruction.bits.event
    }

    when (cache.io.cpu.resp.fire()){
        cacheWayReg := cache.io.cpu.resp.bits.way
    }
    cacheWayWire := cache.io.cpu.resp.bits.way

    when(tbe.io.outputTBE.fire()){
        tbeWay := tbe.io.outputTBE.bits.way
    }

    /*************************************************************************/
    // control signals

    stallInput := isLocked

    io.instruction.ready := !stallInput // @todo should be changed for stalled situations

    readTBE     := RegEnable(io.instruction.fire(), false.B, !stallInput)
    checkLock   := RegEnable(io.instruction.fire(), false.B, !stallInput)
    getState    := RegEnable(io.instruction.fire(), false.B, !stallInput)

    routineAddrResValid := RegEnable(readTBE & !stallInput , false.B, !stall)
    startRoutine        := RegEnable(readTBE & !stallInput , false.B, !stall)

//    actionResValid := RegEnable(Mux(routineAddrResValid, true.B , Mux (firstLineRoutine, false.B,  true.B)), false.B , !stall)
    for (i <- 0 until nParal) {
        isTBEAction(i) :=   (actionReg(i).action.actionType === 1.U)
        isCacheAction(i) := (actionReg(i).action.actionType === 0.U)
        isStateAction(i) := (actionReg(i).action.actionType === 2.U | actionReg(i).action.actionType === 3.U)
    }

//    updateTBEWay   := (RegNext(cache.io.cpu.resp.fire()) & (cacheWayReg =/= nWays.U))

    /*************************************************************************/

    /*************************************************************************/
    // Elements

    defaultState := State.default
    state := Mux(tbe.io.outputTBE.valid, tbe.io.outputTBE.bits.state.state, Mux(stateMem.io.out.valid, stateMem.io.out.bits.state, defaultState.state ))
    routineReg := RegEnable(uCodedNextPtr(routine), 0.U, !stall)


    for (i <- 0 until nParal) {

        updateTBEState(i) := isStateAction(i)
        updateTBEWay(i)   := isStateAction(i)
        endOfRoutine(i)   := isStateAction(i)

        actionResValid(i):= RegEnable((routineAddrResValid | (!routineAddrResValid & !firstLineNextRoutine(i))) & !stallInput, false.B , !stall)
        tbeActionInRom(i) := (actionResValid(i) & isTBEAction(i))

        actionReg(i).action.signals := RegEnable(Mux(pcWire(i).valid , sigToAction(actionRom(pcWire(i).pc)), 0.U) , 0.U, !stall )
        actionReg(i).action.actionType := RegEnable(Mux(pcWire(i).valid ,sigToActType(actionRom(pcWire(i).pc)), 0.U) , 0.U, !stall & pcWire(i).valid)
        actionReg(i).addr := RegEnable(pcWire(i).addr, 0.U, !stall & pcWire(i).valid)
        actionReg(i).way := RegEnable(pcWire(i).way, 0.U, !stall   & pcWire(i).valid)
    }

    for (i <- 0 until nParal) {
        firstLineNextRoutine(i) := (sigToAction(actionRom(pcWire(i).pc)).asUInt() === 0.U )
        updatedPC(i) := Mux(firstLineNextRoutine(i), pcWire(i).pc, pcWire(i).pc + 1.U  )
        updatedPCValid(i) := !firstLineNextRoutine(i)
    }

    pc.io.write.in.bits.data.addr := addrInputCache
    pc.io.write.in.bits.data.way := wayInputCache
    pc.io.write.in.bits.data.pc := routineReg
    pc.io.write.in.bits.data.valid := true.B
    pc.io.write.in.valid := routineAddrResValid

    for (i <- 0 until nParal) {
        pc.io.read(i).in.bits.data.addr := DontCare //
        pc.io.read(i).in.bits.data.way := DontCare // I guess way and addr are not necessary
        pc.io.read(i).in.bits.data.pc := updatedPC(i)
        pc.io.read(i).in.bits.data.valid := updatedPCValid(i)

        pc.io.read(i).in.valid := DontCare // @todo Should be changed probably

        pcWire(i).pc   := pc.io.read(i).out.bits.pc
        pcWire(i).way  := pc.io.read(i).out.bits.way
        pcWire(i).addr  := pc.io.read(i).out.bits.addr
        pcWire(i).valid := pc.io.read(i).out.bits.valid

    }

    wayInputCache := (RegEnable(Mux( tbeWay === nWays.U , cacheWayReg, tbeWay ), 0.U, !stallInput)) // @todo Double-Check
    addrInputCache := (RegEnable(addr, 0.U, !stallInput))

    for (i <- 0 until nParal){
        tbeAction(i) := Mux(isTBEAction(i), actionReg(i).action.signals, tbe.idle)
        cacheAction(i) := Mux(isCacheAction(i), actionReg(i).action.signals, 0.U(nSigs.W))
        stateAction(i) := isStateAction(i)

        dstOfSetState(i).state := Mux( isStateAction(i), sigToState (actionReg(i).action.signals), State.default.state)

    }

    /*************************************************************************************/

    // TBE

    tbe.io.read.valid := readTBE
    tbe.io.read.bits.addr := addr

    for (i <- 0 until nParal)  {

        inputTBE(i).state.state := dstOfSetState(i).state
        inputTBE(i).way         := actionReg(i).way // @todo WRONG

        tbe.io.write(i).bits.command := Mux(updateTBEWay(i) | updateTBEState(i), tbe.write, tbeAction(i)) // @todo Wrong
        tbe.io.write(i).bits.addr := actionReg(i).addr
        tbe.io.write(i).bits.inputTBE := inputTBE(i)
        tbe.io.write(i).bits.inputTBE := inputTBE(i)
        tbe.io.write(i).bits.mask := tbe.maskAll // @todo Should be double-checked
        tbe.io.write(i).valid := DontCare
    }

//    tbe.io.mask := tbe.maskAll
//    when (updateTBEState & updateTBEWay){
//        tbe.io.mask := tbe.maskAll
//    }.elsewhen(updateTBEWay & !updateTBEState){
//        tbe.io.mask := tbe.maskWay
//    }.elsewhen(updateTBEState & !updateTBEWay){
//        tbe.io.mask := tbe.maskState
//    }
//    tbe.io.outputTBE.ready := true.B


    // lock Mem
    lockMem.io.lock.in.bits.data := DontCare
    lockMem.io.lock.in.bits.addr  := addr
    lockMem.io.lock.in.valid := checkLock
    lockMem.io.lock.in.bits.cmd := true.B // checking and locking
    isLocked := Mux(lockMem.io.lock.out.valid, lockMem.io.lock.out.bits, false.B)

    for (i <- 0 until nParal)  {
        lockMem.io.unLock(i).in.bits.data := DontCare
        lockMem.io.unLock(i).in.bits.addr := actionReg(i).addr
        lockMem.io.unLock(i).in.bits.cmd := false.B //unlock
        lockMem.io.unLock(i).in.valid := endOfRoutine(i)
    }

    // @todo tbe should have a higher priority for saving dst state
    // State Memory
    for (i <- 0 until nParal)  {
        stateMem.io.in(i).bits.isSet := true.B
        stateMem.io.in(i).bits.addr := actionReg(i).addr //
        stateMem.io.in(i).bits.state := dstOfSetState(i)
        stateMem.io.in(i).bits.way := actionReg(i).way
        stateMem.io.in(i).valid := isStateAction(i)
    }

    stateMem.io.in(nParal).bits.isSet := false.B // used for getting
    stateMem.io.in(nParal).bits.addr := addr
    stateMem.io.in(nParal).bits.state := DontCare
    stateMem.io.in(nParal).bits.way :=  cacheWayWire
    stateMem.io.in(nParal).valid := getState
    stateMemOutput := stateMem.io.out.bits


    // Cache Logic
    cache.io.cpu.req.bits.way := actionReg(0).way
    cache.io.cpu.req.bits.command := Mux(probeStart, sigToAction(ActionList.actions("Probe")), cacheAction(0))// @todo WRONG
    cache.io.cpu.req.bits.addr    := Mux(probeStart, addrWire, actionReg(0).addr)
    cache.io.cpu.req.valid := actionResValid(0) | io.instruction.fire()
}