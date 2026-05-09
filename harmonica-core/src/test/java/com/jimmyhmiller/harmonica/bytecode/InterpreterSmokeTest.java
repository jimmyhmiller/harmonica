package com.jimmyhmiller.harmonica.bytecode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * End-to-end smoke tests for the bytecode interpreter dispatch loop.
 * Hand-built {@link Op} arrays — no generator yet.
 */
class InterpreterSmokeTest {

    /** {@code return 1 + 2} → 3.0 */
    @Test
    void addReturn() {
        Op[] ops = {
            new Op.Add(
                Variable.Register.ACCUMULATOR,
                new Operand.Constant(0, 1.0),
                new Operand.Constant(1, 2.0)
            ),
            new Op.Return(Variable.Register.ACCUMULATOR),
        };
        Executable exe = new Executable(ops, /* registers */ 8, new Object[]{1.0, 2.0},
            new Executable.ExceptionHandler[0], /* strict */ true);

        Object result = Interpreter.interpret(exe, new Object[0], /* locals */ 0);
        assertEquals(3.0, result);
    }

    /** {@code let x = 0; while (x < 3) x = x + 1; return x} → 3.0 */
    @Test
    void whileLoop() {
        // Layout:
        // pc=0: MOV  L0, K0          (x = 0)
        // pc=1: LT   ACC, L0, K1     (acc = x < 3)
        // pc=2: JUMP_FALSE ACC, 5    (if !acc -> exit)
        // pc=3: ADD  L0, L0, K2      (x = x + 1)
        // pc=4: JUMP 1               (loop back)
        // pc=5: RETURN L0
        Op[] ops = {
            /* 0 */ new Op.Mov(new Variable.Local(0), new Operand.Constant(0, 0.0)),
            /* 1 */ new Op.LessThan(Variable.Register.ACCUMULATOR,
                                    new Variable.Local(0),
                                    new Operand.Constant(1, 3.0)),
            /* 2 */ new Op.JumpFalse(Variable.Register.ACCUMULATOR, 5),
            /* 3 */ new Op.Add(new Variable.Local(0),
                               new Variable.Local(0),
                               new Operand.Constant(2, 1.0)),
            /* 4 */ new Op.Jump(1),
            /* 5 */ new Op.Return(new Variable.Local(0)),
        };
        Executable exe = new Executable(ops, 8, new Object[]{0.0, 3.0, 1.0},
            new Executable.ExceptionHandler[0], true);

        Object result = Interpreter.interpret(exe, new Object[0], /* locals */ 1);
        assertEquals(3.0, result);
    }

    /** {@code throw 42} with a catch handler → returns the caught value. */
    @Test
    void throwAndCatch() {
        // pc=0..1 = try region (handler @ pc=2)
        // pc=0: THROW K0
        // pc=1: RETURN K1   (unreached)
        // pc=2: CATCH L0
        // pc=3: RETURN L0
        Op[] ops = {
            /* 0 */ new Op.Throw(new Operand.Constant(0, 42.0)),
            /* 1 */ new Op.Return(new Operand.Constant(1, 0.0)),
            /* 2 */ new Op.Catch(new Variable.Local(0)),
            /* 3 */ new Op.Return(new Variable.Local(0)),
        };
        Executable.ExceptionHandler[] handlers = {
            new Executable.ExceptionHandler(/* startPc */ 0, /* endPc */ 2, /* handlerPc */ 2)
        };
        Executable exe = new Executable(ops, 8, new Object[]{42.0, 0.0}, handlers, true);

        Object result = Interpreter.interpret(exe, new Object[0], 1);
        assertEquals(42.0, result);
    }

    /** Throw with no handler propagates as AbruptCompletion. */
    @Test
    void uncaughtThrow() {
        Op[] ops = {
            new Op.Throw(new Operand.Constant(0, "boom")),
        };
        Executable exe = new Executable(ops, 8, new Object[]{"boom"},
            new Executable.ExceptionHandler[0], true);

        AbruptCompletion ex = assertThrows(AbruptCompletion.class,
            () -> Interpreter.interpret(exe, new Object[0], 0));
        assertEquals("boom", ex.value());
    }

    /** Operation flags table is sane. */
    @Test
    void flagsSanity() {
        assertEquals(true,  Operation.JUMP.isTerminator());
        assertEquals(false, Operation.JUMP.canThrow());
        assertEquals(true,  Operation.ADD.canThrow());
        assertEquals(false, Operation.ADD.isTerminator());
        assertEquals(true,  Operation.GET_BY_ID.hasIC());
        assertEquals(false, Operation.MOV.hasIC());
        assertEquals(true,  Operation.RETURN.isTerminator());
        assertEquals(true,  Operation.THROW.isTerminator());
        assertEquals(true,  Operation.THROW.canThrow());
    }
}
