package dandelion.memory.cache

import chipsalliance.rocketchip.config._
import chisel3._
import dandelion.config._
import dandelion.memory.cache.{HasCacheAccelParams, State}
import chisel3.util._
import chisel3.util.Enum

object nextRoutine {

    val routineTriggerList = Array [RoutinePC](

                    Routine ("LOAD_I") , Trigger( Seq ("LOAD" , "I")),
                    Routine ("LOAD_M") , Trigger( Seq("LOAD", "M")),
                    Routine ("STORE_i") , Trigger( Seq("STORE", "I")),




    )


  // fill the input table
}
