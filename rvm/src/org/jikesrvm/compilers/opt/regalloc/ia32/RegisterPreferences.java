/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.jikesrvm.compilers.opt.regalloc.ia32;

import static org.jikesrvm.compilers.opt.ir.ia32.ArchOperators.IA32_MOV_opcode;

import java.util.Enumeration;

import org.jikesrvm.compilers.opt.ir.IR;
import org.jikesrvm.compilers.opt.ir.Instruction;
import org.jikesrvm.compilers.opt.ir.Register;
import org.jikesrvm.compilers.opt.ir.ia32.MIR_Move;
import org.jikesrvm.compilers.opt.ir.operand.Operand;
import org.jikesrvm.compilers.opt.regalloc.GenericRegisterPreferences;

public class RegisterPreferences extends GenericRegisterPreferences {

  @Override
  public void initialize(IR ir) {

    for (Enumeration<Instruction> e = ir.forwardInstrEnumerator(); e.hasMoreElements();) {
      Instruction s = e.nextElement();
      switch (s.getOpcode()) {
        case IA32_MOV_opcode:
          // add affinities produced by MOVE instructions
          Operand result = MIR_Move.getResult(s);
          Operand value = MIR_Move.getValue(s);
          if (result.isRegister() && value.isRegister()) {
            Register r1 = result.asRegister().getRegister();
            Register r2 = value.asRegister().getRegister();
            addAffinity(1, r1, r2);
          }
          break;
        default:
          break;
      }
    }
  }
}
