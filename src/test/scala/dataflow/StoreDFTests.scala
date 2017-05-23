package dataflow

/**
  * Created by nvedula on 17/5/17.
  */


import chisel3.iotesters.{ChiselFlatSpec, Driver, PeekPokeTester, OrderedDecoupledHWIOTester}
import org.scalatest.{Matchers, FlatSpec}

import config._


class StoreDFTests(c: StoreDataFlow) extends PeekPokeTester(c) {
  for (t <- 0 until 20) {

    //IF ready is set
    // send address


    println(s"t: ${t} GepAddr: ${peek(c.io.gepAddr.ready)}")
    println(s"t: ${t} GepValid: ${peek(c.io.gepAddr.valid)}")
    println(s"t: ${t} ACK:      ${peek(c.io.memOpAck)}")

    poke(c.io.memOpAck.ready,false)

    if(peek(c.io.gepAddr.ready) == 1 && t>2) {
      poke(c.io.gepAddr.valid,true)
      poke(c.io.gepAddr.bits,12)

      printf("\n rule1 fired \n")
    }
    else
    {
      poke(c.io.gepAddr.valid,false)
    }

    if(peek(c.io.inData.ready) == 1 && t>3) {
      printf("\n rule2 fired \n")
      poke(c.io.inData.valid,true)
      poke(c.io.inData.bits,12)
    }
    else
      poke(c.io.inData.valid,false)



    //at some clock - send src mem-op is done executing
    if(t > 4) {
      if (peek(c.io.predMemOp.ready) == 1) {
        poke(c.io.predMemOp.valid, true)
        println("\n predmemOP rule fired \n")
        //        poke(c.io.predMemOp(0).bits, 24)
      }
      else
        poke(c.io.predMemOp.valid, false)

    }
    else {

      poke(c.io.predMemOp.valid, false)
    }

    step(1)

  }
}


class StoreDFTester extends  FlatSpec with Matchers {
  implicit val p = config.Parameters.root((new MiniConfig).toInstance)
  it should "Store Node tester" in {
    chisel3.iotesters.Driver(() => new StoreDataFlow()) { c =>
      new StoreDFTests(c)
    } should be(true)
  }
}
