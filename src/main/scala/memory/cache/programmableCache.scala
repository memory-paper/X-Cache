package dandelion.memory.cache

import chisel3._
import chisel3.util._
import chipsalliance.rocketchip.config._
import dandelion.config._
import dandelion.util._
import dandelion.interfaces._
import dandelion.interfaces.ActionBundle
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
    val state = Wire(UInt(stateLen.W))

    val inputTBE = WireInit(TBE.default)




    val routineAddr = RoutinePtr.generateRoutineAddrMap(RoutineROM.routineActions)
//    printf(p"routine addr ${routineAddr} \n")
    val (rombits, dstStateBits) = RoutinePtr.generateTriggerRoutineBit(routineAddr, nextRoutine.routineTriggerList)
//    printf(p"romBits ${rombits} \n")

    val uCodedNextPtr = VecInit(rombits)
    val dstStateRom = VecInit(dstStateBits)


    val actions = RoutinePtr.generateActionRom(RoutineROM.routineActions, ActionList.actions)
    val actionRom = VecInit(actions)

//    printf(p"uCodedNextPtr ${uCodedNextPtr}\n")
//    printf(p"actionRom ${actionRom}\n")

    val addr = RegInit(0.U(addrLen.W))
    val event = RegInit(0.U(eventLen.W))

    val tbeAction = Wire(UInt(nSigs.W))
    val cacheAction = Wire(UInt(nSigs.W))
    val isCacheAction = Wire(Bool())

    val readTBE = Reg(Bool())
    val pc = Reg(UInt())
    val action = Wire(new ActionBundle())

//    val tbeRdRdy = Wire(Bool())
    val tbeResRdy = Reg(Bool())
    val routineAddrResRdy = Reg(Bool())
    val actionResRdy = Reg(Bool())

    val routineStart = Wire(Bool())

    val defaultState = Wire(new State())
    val startOfRoutine = Wire(Bool())

    val isProbe = Wire(Bool())

    io.instruction.ready := true.B

    when(io.instruction.fire()){
        // latching
        addr := io.instruction.bits.addr
        event := io.instruction.bits.event
    }



    readTBE := io.instruction.fire()
//    tbe.io <> DontCare

    printf(p"tbe command ${tbe.io.command} \n")

    tbe.io.command := Mux (readTBE, tbe.read, tbeAction )
    tbe.io.addr := addr

    inputTBE.state := State.default // @todo should be changed
    inputTBE.way := 1.U // @todo should be changed

    tbe.io.inputTBE := inputTBE
    tbe.io.outputTBE.ready := true.B

    tbeResRdy := (readTBE)
    routineAddrResRdy := (tbeResRdy)

    isProbe := (action.signals === ActionList.actions("Probe").asUInt()(nSigs-1 , 0))

    val (probeHandled, _) = Counter(isProbe, 2)
//    printf(p"${probeHandled} \n")


    actionResRdy := Mux(routineAddrResRdy, true.B , Mux (startOfRoutine, false.B, Mux(isProbe, (probeHandled.asBool()), true.B)))

    defaultState := State.default

    state := Mux(tbe.io.outputTBE.valid, tbe.io.outputTBE.bits.state.state, defaultState.state )

    val routine = WireInit( Cat (event, state))

    routineStart := routineAddrResRdy



    pc := Mux(routineStart, uCodedNextPtr(routine), Mux(startOfRoutine, pc,  Mux(isProbe, pc + probeHandled.asUInt(), pc+ 1.U  )))

    action.signals := actionRom(pc)(nSigs -1,0)
    startOfRoutine := (action.signals === 0.U & action.isCacheAction === 0.U)
    action.isCacheAction := actionRom(pc)(nSigs)

    isCacheAction := (action.isCacheAction === true.B)

    tbeAction := Mux(!isCacheAction, action.signals, tbe.idle )
    cacheAction := Mux(isCacheAction, action.signals, 0.U(nSigs.W))

    cache.io.cpu <> DontCare
    cache.io.mem <> DontCare

    cache.io.cpu.req.bits.command := cacheAction
    cache.io.cpu.req.bits.addr := addr
    cache.io.cpu.req.valid := actionResRdy



}