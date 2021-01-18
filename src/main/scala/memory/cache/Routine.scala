package dandelion.memory.cache

import chipsalliance.rocketchip.config._
import chisel3._
import dandelion.config._
import dandelion.memory.cache.{HasCacheAccelParams, State}
import chisel3.util._
import chisel3.util.Enum

object RoutineROM {

  val routineActions = Array [RoutinePC](


    // @todo should be fixed
    Routine ("LOAD_I") , Actions(Seq( "AllocateTBE","Allocate", "DataRQ", "SetState")),DstState("ID"),
    Routine ("LOAD_M"), Actions(Seq ("DataRQ", "SetState")),  DstState("M"),
    Routine("STORE_I"), Actions(Seq("Allocate", "DataRQ", "SetState")),DstState("IM"),
    Routine ("LOAD_ID") , Actions(Seq( "ReadInt", "DeallocateTBE", "SetState")), DstState("I"),
    Routine ("LOAD_IM") , Actions(Seq( "ReadInt","DeallocateTBE", "SetState")), DstState("M"),
    Routine ("STORE_IS") , Actions(Seq( "WrInt", "SetState")), DstState("S")




    /*
    Allocate
    Beq allocPassed PASSED
    replace
    PASSED :
    ReadExt

     */



  )


  // fill the input table
}
