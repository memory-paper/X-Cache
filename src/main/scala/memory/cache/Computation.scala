package memGen.memory.cache

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import chisel3.util.experimental._

/*
 functions: add: 0, sub: 1, mult: 2, shift_r: 3, shift_l: 4, xor: 5 

// opcodes:
//  ---------------------------
// | 00 | operand1, operand2   |
// | 01 | operand1, address_2  |
// | 10 | address_1, address_2 |
//  ---------------------------

 Instruction:
  ----------------------- --------------------- ------------ ---------- --------
 |  tbe_field/imm/ operand_2 | tbe_field/ operand_1 | write_addr | function |
  ----------------------- --------------------- ------------ ---------- --------

*/ 

class Computation [T <: UInt] (  val OperandType: T)(implicit val p:Parameters)
extends Module with HasCacheAccelParams {

    def ALU(function: UInt, op1: T, op2: T, jump: T) = {
        val result = Wire(OperandType.cloneType);
        result := 0.U;

        switch (function) {
            is (add) { result := op1 + op2; }
            is (and) { result := op1 & op2; }
            is (sub) { result := op1 - op2; }
            is (shift_r) { result := op1 >> op2; }
            is (shift_l) { result := op1 << op2(7,0); }
            is (xor) { result := op1 ^ op2; }
            is (blt) { result  := Mux(op1 < op2, jump, 0.U)}
            is (bneq) { result := Mux(op1 =/= op2, jump, 0.U)}
            is (or) { result := op1 | op2; }
            is (beq) { result := Mux(op1 === op2, jump, 0.U)}

        }
        result
    }

    def OpcodeEnd() = opcodeWidth;
    def FunctionEnd() = OpcodeEnd() + funcWidth;
    def DestEnd() = FunctionEnd() + log2Ceil(regFileSize) + 4
//    def Operand1End() = DestEnd() + OperandType.cloneType.getWidth;
//    def Operand2End() = Operand1End() + OperandType.cloneType.getWidth;
    def Op1End() =  DestEnd() + log2Ceil(regFileSize);

    val io = IO (new Bundle {
        val instruction = Flipped(Valid(UInt(instructionWidth.W)))
        val clear = Input(Bool())
        val op1 = Flipped(Valid(OperandType.cloneType))
        val op2 = Flipped(Valid(OperandType.cloneType))
        val pc = Output(UInt(pcLen.W))
        val reg_file = Output(Vec(regFileSize, OperandType.cloneType))
    })

    val add :: and :: sub :: shift_r :: shift_l :: xor ::blt :: bneq  :: or :: beq :: Nil = Enum (10)
    val result = Wire(OperandType.cloneType);
    val reg_out1 = Wire(OperandType.cloneType);
    val reg_out2 = Wire(OperandType.cloneType);
    
    // *******************************************  FETCH  *******************************************
//    val opcode      = io.instruction.bits(OpcodeEnd()-1, 0);
    val function    = io.instruction.bits(FunctionEnd()-1, OpcodeEnd());
    val write_addr  = io.instruction.bits(DestEnd()-1, FunctionEnd());
//    val operand1    = io.instruction(Operand1End()-1, DestEnd());
//    val operand2    = io.instruction(Operand2End()-1, Operand1End());
    val read_addr1  = io.instruction.bits(Op1End()-1, DestEnd());
    val read_addr2  = io.instruction.bits(instructionWidth-1, Op1End()); // second op larger for imm value

//    printf(s"opcode: %d\nfunction: %d\nwrite_addr: %d\noperand1: %d\n, operand2: %d\n, read_addr1: %d\n, read_addr2: %d\n",
//             opcode, function, write_addr, io.op1.bits, io.op2.bits, read_addr1, read_addr2);
    
    val alu_in1 = Wire(OperandType.cloneType);
    val alu_in2 = Wire(OperandType.cloneType);

    alu_in1 := Mux(io.op1.fire(), io.op1.bits, reg_out1)
    alu_in2 := Mux(io.op2.fire(), io.op2.bits, reg_out2)

    // *******************************************  Register File IO  *******************************************
    val reg_file = RegInit(VecInit(Seq.fill(regFileSize)(0.U(OperandType.cloneType.getWidth.W))))

    val write_en = io.instruction.fire() && (function =/= blt && function =/= bneq)

    when (write_en) { reg_file(write_addr) := result; }
    when (io.clear){(0 until regFileSize) map (i => reg_file(i) := 0.U(OperandType.cloneType.getWidth.W))}
    reg_out1 := reg_file(read_addr1);
    reg_out2 := reg_file(read_addr2);
    io.reg_file := reg_file;
    
    // *******************************************  ALU  *******************************************
    result := Mux(io.instruction.fire(), ALU(function, alu_in1, alu_in2, write_addr.asTypeOf(OperandType.cloneType)), 0.U)
    io.pc :=  Mux(function =/= blt && function =/= bneq && function =/= beq , 0.U, result(pcLen - 1, 0))
//    io.output := result;

}

//object Computation extends App {
//  (new chisel3.stage.ChiselStage).emitVerilog(new Computation(UInt(16.W)))
//}
