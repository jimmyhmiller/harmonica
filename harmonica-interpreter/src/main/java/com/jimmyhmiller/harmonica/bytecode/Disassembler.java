package com.jimmyhmiller.harmonica.bytecode;


/**
 * Render an {@link Executable} as a human-readable bytecode dump in a format
 * that matches Ladybird LibJS's {@code js --dump-bytecode} output as closely
 * as possible.
 *
 * <p>Goal: side-by-side textual diffing against LibJS. Where our IR diverges
 * (constant folding, scope handling, fused opcodes, byte-offset PCs), the
 * disassembler renders what we have honestly so the divergence is visible.
 *
 * <p>Format mirrored:
 * <pre>
 *   Registers: N
 *   Blocks:    N
 *   Constants:
 *     [0] = Int32(1)
 *     ...
 *
 *   block0:
 *     [   0] Opcode field:value, field:value
 *     ...
 * </pre>
 *
 * <p>v1 caveats:
 * <ul>
 *   <li>PC values are array indices, not byte offsets — when we add real
 *       byte encoding we'll switch to byte offsets to match LibJS's hex.</li>
 *   <li>Block partitioning is recovered post-hoc by treating jump targets
 *       as block starts. Once the generator builds explicit basic blocks
 *       this becomes a direct render.</li>
 * </ul>
 */
public final class Disassembler {

    private Disassembler() {}

    public static String dump(Executable exe) {
        StringBuilder out = new StringBuilder();
        out.append("  Registers: ").append(exe.numberOfRegisters()).append('\n');

        int[] blockStarts = exe.basicBlockStartPcs();
        out.append("  Blocks:    ").append(blockStarts.length).append('\n');

        String[] localNames = exe.localNames();
        if (localNames.length > 0) {
            out.append("  Locals:    ");
            for (int i = 0; i < localNames.length; i++) {
                if (i > 0) out.append(", ");
                out.append(localNames[i]).append("~").append(i);
            }
            out.append('\n');
        }

        Object[] constants = exe.constants();
        if (constants.length > 0) {
            out.append("  Constants:\n");
            for (int i = 0; i < constants.length; i++) {
                out.append("    [").append(i).append("] = ").append(formatValue(constants[i])).append('\n');
            }
        }
        out.append('\n');

        Op[] ops = exe.ops();
        int[] pcToBlock = mapPcToBlock(blockStarts, ops.length);

        for (int b = 0; b < blockStarts.length; b++) {
            out.append("block").append(b).append(":\n");
            int start = blockStarts[b];
            int end = (b + 1 < blockStarts.length) ? blockStarts[b + 1] : ops.length;
            for (int pc = start; pc < end; pc++) {
                out.append("  [").append(formatPc(pc)).append("] ");
                out.append(formatOp(ops[pc], exe, pcToBlock));
                out.append('\n');
            }
            if (b + 1 < blockStarts.length) out.append('\n');
        }
        return out.toString();
    }

    private static int[] mapPcToBlock(int[] blockStarts, int numOps) {
        int[] map = new int[numOps];
        int b = 0;
        for (int pc = 0; pc < numOps; pc++) {
            while (b + 1 < blockStarts.length && pc >= blockStarts[b + 1]) b++;
            map[pc] = b;
        }
        return map;
    }

    // ------------------------------------------------------------
    //  Per-op formatter
    // ------------------------------------------------------------

    private static String formatOp(Op op, Executable exe, int[] pcToBlock) {
        return switch (op) {
            case Op.Add o            -> "Add " + dst(o.dst()) + ", " + lhsRhs(o.lhs(), o.rhs(), exe);
            case Op.Sub o            -> "Sub " + dst(o.dst()) + ", " + lhsRhs(o.lhs(), o.rhs(), exe);
            case Op.Mul o            -> "Mul " + dst(o.dst()) + ", " + lhsRhs(o.lhs(), o.rhs(), exe);
            case Op.Div o            -> "Div " + dst(o.dst()) + ", " + lhsRhs(o.lhs(), o.rhs(), exe);
            case Op.Mod o            -> "Mod " + dst(o.dst()) + ", " + lhsRhs(o.lhs(), o.rhs(), exe);
            case Op.Exp o            -> "Exp " + dst(o.dst()) + ", " + lhsRhs(o.lhs(), o.rhs(), exe);
            case Op.BitwiseAnd o     -> "BitwiseAnd " + dst(o.dst()) + ", " + lhsRhs(o.lhs(), o.rhs(), exe);
            case Op.BitwiseOr  o     -> "BitwiseOr "  + dst(o.dst()) + ", " + lhsRhs(o.lhs(), o.rhs(), exe);
            case Op.BitwiseXor o     -> "BitwiseXor " + dst(o.dst()) + ", " + lhsRhs(o.lhs(), o.rhs(), exe);
            case Op.LeftShift o          -> "LeftShift "          + dst(o.dst()) + ", " + lhsRhs(o.lhs(), o.rhs(), exe);
            case Op.RightShift o         -> "RightShift "         + dst(o.dst()) + ", " + lhsRhs(o.lhs(), o.rhs(), exe);
            case Op.UnsignedRightShift o -> "UnsignedRightShift " + dst(o.dst()) + ", " + lhsRhs(o.lhs(), o.rhs(), exe);
            case Op.LessThan o          -> "LessThan "          + dst(o.dst()) + ", " + lhsRhs(o.lhs(), o.rhs(), exe);
            case Op.LessThanEquals o    -> "LessThanEquals "    + dst(o.dst()) + ", " + lhsRhs(o.lhs(), o.rhs(), exe);
            case Op.GreaterThan o       -> "GreaterThan "       + dst(o.dst()) + ", " + lhsRhs(o.lhs(), o.rhs(), exe);
            case Op.GreaterThanEquals o -> "GreaterThanEquals " + dst(o.dst()) + ", " + lhsRhs(o.lhs(), o.rhs(), exe);
            case Op.StrictlyEquals o    -> "StrictlyEquals "    + dst(o.dst()) + ", " + lhsRhs(o.lhs(), o.rhs(), exe);
            case Op.StrictlyInequals o  -> "StrictlyInequals "  + dst(o.dst()) + ", " + lhsRhs(o.lhs(), o.rhs(), exe);
            case Op.LooselyEquals o     -> "LooselyEquals "     + dst(o.dst()) + ", " + lhsRhs(o.lhs(), o.rhs(), exe);
            case Op.LooselyInequals o   -> "LooselyInequals "   + dst(o.dst()) + ", " + lhsRhs(o.lhs(), o.rhs(), exe);
            case Op.Instanceof o        -> "InstanceOf "        + dst(o.dst()) + ", " + lhsRhs(o.lhs(), o.rhs(), exe);
            case Op.In o                -> "In "                + dst(o.dst()) + ", " + lhsRhs(o.lhs(), o.rhs(), exe);

            case Op.UnaryMinus o      -> "UnaryMinus " + dst(o.dst()) + ", src:" + operand(o.src(), exe);
            case Op.UnaryPlus o       -> "UnaryPlus "  + dst(o.dst()) + ", src:" + operand(o.src(), exe);
            case Op.BitwiseNot o      -> "BitwiseNot " + dst(o.dst()) + ", src:" + operand(o.src(), exe);
            case Op.Not o             -> "Not "        + dst(o.dst()) + ", src:" + operand(o.src(), exe);
            case Op.ToBoolean o       -> "ToBoolean "  + dst(o.dst()) + ", value:" + operand(o.value(), exe);
            case Op.Typeof o          -> "Typeof "     + dst(o.dst()) + ", src:" + operand(o.src(), exe);
            case Op.TypeofBinding o   -> "TypeofBinding " + dst(o.dst()) + ", `" + o.name() + "`";
            case Op.DeleteById o      -> "DeleteById " + dst(o.dst()) + ", base:" + operand(o.base(), exe)
                                          + ", `" + o.property() + "`";
            case Op.DeleteByValue o   -> "DeleteByValue " + dst(o.dst()) + ", base:" + operand(o.base(), exe)
                                          + ", property:" + operand(o.property(), exe);
            case Op.DeleteVariable o  -> "DeleteVariable " + dst(o.dst()) + ", `" + o.identifier() + "`";
            case Op.Increment o       -> "Increment " + dst(o.dst());
            case Op.Decrement o       -> "Decrement " + dst(o.dst());
            case Op.PostfixIncrement o -> "PostfixIncrement " + dst(o.dst()) + ", src:" + operand(o.src(), exe);
            case Op.PostfixDecrement o -> "PostfixDecrement " + dst(o.dst()) + ", src:" + operand(o.src(), exe);

            case Op.Mov o            -> "Mov dst:" + operand(o.dst(), exe) + ", src:" + operand(o.src(), exe);
            case Op.Mov2 o           -> "Mov2 dst1:" + operand(o.dst1(), exe) + ", src1:" + operand(o.src1(), exe)
                                        + ", dst2:" + operand(o.dst2(), exe) + ", src2:" + operand(o.src2(), exe);
            case Op.Mov3 o           -> "Mov3 dst1:" + operand(o.dst1(), exe) + ", src1:" + operand(o.src1(), exe)
                                        + ", dst2:" + operand(o.dst2(), exe) + ", src2:" + operand(o.src2(), exe)
                                        + ", dst3:" + operand(o.dst3(), exe) + ", src3:" + operand(o.src3(), exe);

            case Op.Jump o           -> "Jump target:" + blockRef(o.targetPc(), pcToBlock);
            case Op.JumpTrue o       -> "JumpTrue condition:" + operand(o.condition(), exe)
                                        + ", target:" + blockRef(o.targetPc(), pcToBlock);
            case Op.JumpFalse o      -> "JumpFalse condition:" + operand(o.condition(), exe)
                                        + ", target:" + blockRef(o.targetPc(), pcToBlock);
            case Op.JumpIf o         -> "JumpIf condition:" + operand(o.condition(), exe)
                                        + ", true_target:" + blockRef(o.trueTargetPc(), pcToBlock)
                                        + ", false_target:" + blockRef(o.falseTargetPc(), pcToBlock);
            case Op.JumpNullish o    -> "JumpNullish condition:" + operand(o.condition(), exe)
                                        + ", true_target:" + blockRef(o.trueTargetPc(), pcToBlock)
                                        + ", false_target:" + blockRef(o.falseTargetPc(), pcToBlock);
            case Op.JumpUndefined o  -> "JumpUndefined condition:" + operand(o.condition(), exe)
                                        + ", true_target:" + blockRef(o.trueTargetPc(), pcToBlock)
                                        + ", false_target:" + blockRef(o.falseTargetPc(), pcToBlock);

            case Op.JumpLessThan          o -> fusedJump("JumpLessThan",          o.lhs(), o.rhs(), o.trueTargetPc(), o.falseTargetPc(), exe, pcToBlock);
            case Op.JumpLessThanEquals    o -> fusedJump("JumpLessThanEquals",    o.lhs(), o.rhs(), o.trueTargetPc(), o.falseTargetPc(), exe, pcToBlock);
            case Op.JumpGreaterThan       o -> fusedJump("JumpGreaterThan",       o.lhs(), o.rhs(), o.trueTargetPc(), o.falseTargetPc(), exe, pcToBlock);
            case Op.JumpGreaterThanEquals o -> fusedJump("JumpGreaterThanEquals", o.lhs(), o.rhs(), o.trueTargetPc(), o.falseTargetPc(), exe, pcToBlock);
            case Op.JumpStrictlyEquals    o -> fusedJump("JumpStrictlyEquals",    o.lhs(), o.rhs(), o.trueTargetPc(), o.falseTargetPc(), exe, pcToBlock);
            case Op.JumpStrictlyInequals  o -> fusedJump("JumpStrictlyInequals",  o.lhs(), o.rhs(), o.trueTargetPc(), o.falseTargetPc(), exe, pcToBlock);
            case Op.JumpLooselyEquals     o -> fusedJump("JumpLooselyEquals",     o.lhs(), o.rhs(), o.trueTargetPc(), o.falseTargetPc(), exe, pcToBlock);
            case Op.JumpLooselyInequals   o -> fusedJump("JumpLooselyInequals",   o.lhs(), o.rhs(), o.trueTargetPc(), o.falseTargetPc(), exe, pcToBlock);

            case Op.Return o         -> "Return value:" + operand(o.value(), exe);
            case Op.End o            -> "End value:" + operand(o.value(), exe);
            case Op.Throw o          -> "Throw src:" + operand(o.value(), exe);
            case Op.ThrowIfNullish o -> "ThrowIfNullish src:" + operand(o.src(), exe);
            case Op.Catch o          -> "Catch " + dst(o.dst());
            case Op.GetLexicalEnvironment o -> "GetLexicalEnvironment " + dst(o.dst());
            case Op.SetLexicalEnvironment o -> "SetLexicalEnvironment environment:" + operand(o.environment(), exe);
            case Op.CreateLexicalEnvironment o -> "CreateLexicalEnvironment " + dst(o.dst())
                                                  + ", parent:" + operand(o.parent(), exe)
                                                  + ", capacity:" + o.capacity();
            case Op.CreateVariable o -> "CreateVariable `" + o.name() + "`"
                                        + ", is_immutable:" + o.isImmutable()
                                        + ", is_global:" + o.isGlobal()
                                        + ", is_strict:" + o.isStrict();
            case Op.CreateMutableBinding o -> "CreateMutableBinding environment:" + operand(o.environment(), exe)
                                        + ", `" + o.name() + "`"
                                        + ", can_be_deleted:" + o.canBeDeleted();
            case Op.SetVariableBinding o -> "SetVariableBinding `" + o.identifier() + "`, src:" + operand(o.src(), exe);
            case Op.ResolveThisBinding o -> "ResolveThisBinding";
            case Op.CreatePrivateEnvironment o -> "CreatePrivateEnvironment";
            case Op.LeavePrivateEnvironment o -> "LeavePrivateEnvironment";
            case Op.AddPrivateName o -> "AddPrivateName `" + o.name() + "`";
            case Op.PushWithEnv o -> "PushWithEnv " + operand(o.object(), exe);
            case Op.PopWithEnv o -> "PopWithEnv";

            case Op.GetById o        -> "GetById " + dst(o.dst()) + ", base:" + operand(o.base(), exe)
                                        + ", `" + o.property() + "`"
                                        + (o.baseIdentifier() != null
                                            ? " (" + o.baseIdentifier() + "." + o.property() + ")"
                                            : "");
            case Op.GetLength o      -> "GetLength " + dst(o.dst()) + ", base:" + operand(o.base(), exe)
                                        + (o.baseIdentifier() != null
                                            ? " (" + o.baseIdentifier() + ".length)"
                                            : "");
            case Op.PutById o        -> "PutById base:" + operand(o.base(), exe)
                                        + ", src:" + operand(o.src(), exe)
                                        + ", kind:" + o.kind()
                                        + (o.baseIdentifier() != null
                                            ? " (" + o.baseIdentifier() + "." + o.property() + ")"
                                            : "")
                                        + ", `" + o.property() + "`";

            // LibJS dump format places the identifier as a positional `name` argument.
            case Op.InitializeLexicalBinding o -> "InitializeLexicalBinding `" + o.identifier() + "`"
                                                  + ", src:" + operand(o.src(), exe);
            case Op.InitializeImportBinding o  -> "InitializeImportBinding `" + o.identifier() + "`";
            case Op.RegisterModuleNames o      -> "RegisterModuleNames [" + String.join(", ", o.names()) + "]";
            case Op.RegisterConstNames o       -> "RegisterConstNames [" + String.join(", ", o.names()) + "]";
            case Op.GetGlobal o      -> "GetGlobal " + dst(o.dst()) + ", `" + o.identifier() + "`";
            case Op.GetBinding o     -> "GetBinding " + dst(o.dst()) + ", `" + o.identifier() + "`";
            case Op.SetGlobal o      -> "SetGlobal `" + o.identifier() + "`, src:" + operand(o.src(), exe);

            case Op.Call o           -> "Call " + dst(o.dst()) + ", callee:" + operand(o.callee(), exe)
                                        + ", this_value:" + operand(o.thisValue(), exe)
                                        + (o.expressionString() != null ? ", " + o.expressionString() : "")
                                        + (o.args().length == 0 ? "" : ", arguments:[" + joinArgs(o.args(), exe) + "]");
            case Op.CallDirectEval o -> "CallDirectEval " + dst(o.dst()) + ", callee:" + operand(o.callee(), exe)
                                        + ", this_value:" + operand(o.thisValue(), exe)
                                        + (o.expressionString() != null ? ", " + o.expressionString() : "")
                                        + (o.args().length == 0 ? "" : ", arguments:[" + joinArgs(o.args(), exe) + "]");
            case Op.CallCharCodeAt o -> "CallCharCodeAt " + dst(o.dst())
                                        + ", receiver:" + operand(o.receiver(), exe)
                                        + ", index:" + operand(o.index(), exe);
            case Op.CallCharAt o     -> "CallCharAt " + dst(o.dst())
                                        + ", receiver:" + operand(o.receiver(), exe)
                                        + ", index:" + operand(o.index(), exe);
            case Op.CallStringSlice o -> "CallStringSlice " + dst(o.dst())
                                        + ", receiver:" + operand(o.receiver(), exe)
                                        + ", start:" + operand(o.startArg(), exe)
                                        + (o.endArg() != null ? ", end:" + operand(o.endArg(), exe) : "");
            case Op.CallArrayPush o   -> "CallArrayPush " + dst(o.dst())
                                        + ", receiver:" + operand(o.receiver(), exe)
                                        + ", value:" + operand(o.value(), exe);
            case Op.CallMethod o     -> "CallMethod " + dst(o.dst())
                                        + ", receiver:" + operand(o.receiver(), exe)
                                        + ", `" + o.property() + "`"
                                        + (o.expressionString() != null ? ", " + o.expressionString() : "")
                                        + (o.args().length == 0 ? "" : ", arguments:[" + joinArgs(o.args(), exe) + "]");

            case Op.NewFunction o    -> "NewFunction " + dst(o.dst())
                                        + ", shared_function_data_index:" + o.sharedFunctionDataIndex()
                                        + (o.name() != null ? " (" + o.name() + ")" : "")
                                        + (o.homeObject() != null ? ", home_object:" + operand(o.homeObject(), exe) : "");
            case Op.CallConstruct o  -> "CallConstruct " + dst(o.dst()) + ", callee:" + operand(o.callee(), exe)
                                        + (o.expressionString() != null ? ", " + o.expressionString() : "")
                                        + (o.args().length == 0 ? "" : ", arguments:[" + joinArgs(o.args(), exe) + "]");
            case Op.SetFunctionPrototype o -> "SetFunctionPrototype function:" + operand(o.function(), exe)
                                              + ", prototype:" + operand(o.prototype(), exe);
            case Op.NewClass o -> {
                StringBuilder sb = new StringBuilder("NewClass " + dst(o.dst()));
                if (o.superClass() != null) {
                    sb.append(", super_class:").append(operand(o.superClass(), exe));
                }
                sb.append(", class_environment:").append(operand(o.classEnvironment(), exe));
                sb.append(", class_blueprint_index:").append(o.classBlueprintIndex());
                if (o.displayName() != null) {
                    sb.append(" (").append(o.displayName()).append(")");
                }
                // LibJS emits {@code element_keys:[]} for classes that have
                // declared members but all are private (so the public list
                // is empty). It omits the field entirely only when the class
                // has NO members at all (not even private ones). The
                // generator passes a null elementKeys array for the latter,
                // and any (possibly empty) array for the former.
                if (o.elementKeys() != null) {
                    sb.append(", element_keys:[");
                    for (int i = 0; i < o.elementKeys().length; i++) {
                        if (i > 0) sb.append(", ");
                        Operand k = o.elementKeys()[i];
                        sb.append("element_keys:");
                        // Defensive: any null entry is rendered as Undefined.
                        if (k == null) sb.append("Undefined");
                        else sb.append(operand(k, exe));
                    }
                    sb.append("]");
                }
                yield sb.toString();
            }
            case Op.SetPrototypeOf o -> "SetPrototypeOf child:" + operand(o.child(), exe)
                                        + ", parent:" + operand(o.parent(), exe);
            case Op.PutBySpread o    -> "PutBySpread base:" + operand(o.base(), exe)
                                        + ", src:" + operand(o.src(), exe);
            case Op.CopyOwnProperties o -> "CopyOwnProperties target:" + operand(o.target(), exe)
                                           + ", source:" + operand(o.source(), exe)
                                           + (o.excluded().length == 0 ? "" :
                                              ", excluded:[" + String.join(",", o.excluded()) + "]");
            case Op.DefineAccessor o -> "DefineAccessor target:" + operand(o.target(), exe)
                                        + ", `" + o.property() + "`"
                                        + ", getter:" + operand(o.getter(), exe)
                                        + ", setter:" + operand(o.setter(), exe);
            case Op.GetNewTarget o -> "GetNewTarget " + dst(o.dst());
            case Op.GetSuperConstructor o -> "GetSuperConstructor " + dst(o.dst());
            case Op.CreateArguments o -> "CreateArguments " + dst(o.dst());
            case Op.NewRegExp o    -> "NewRegExp " + dst(o.dst()) + ", " + o.pattern() + ","
                                        + (o.flags() == null || o.flags().isEmpty() ? "" : " " + o.flags());
            case Op.Yield o        -> "Yield " + dst(o.dst()) + ", value:" + operand(o.value(), exe)
                                      + (o.delegate() ? ", delegate:true" : "");
            case Op.Await o        -> "Await " + dst(o.dst()) + ", value:" + operand(o.value(), exe);

            case Op.NewObject o      -> "NewObject " + dst(o.dst());
            case Op.SetProtoOrNop o  -> "SetProtoOrNop target:" + operand(o.target(), exe)
                                        + ", value:" + operand(o.value(), exe);
            case Op.SuperBindThis o  -> "SuperBindThis result:" + operand(o.result(), exe);
            case Op.MakeShapedObject o -> {
                StringBuilder sb = new StringBuilder("MakeShapedObject " + dst(o.dst()) + ", {");
                for (int i = 0; i < o.propertyNames().length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append('`').append(o.propertyNames()[i]).append("`:")
                      .append(operand(o.values()[i], exe));
                }
                sb.append('}');
                yield sb.toString();
            }
            case Op.InitObjectLiteralProperty o -> "InitObjectLiteralProperty object:" + operand(o.object(), exe)
                                                   + ", `" + o.property() + "`"
                                                   + ", src:" + operand(o.src(), exe)
                                                   + ", shape_cache_index:" + o.shapeCacheIndex()
                                                   + ", property_slot:" + o.propertySlot();
            case Op.CacheObjectShape o -> "CacheObjectShape object:" + operand(o.object(), exe);
            case Op.NewArray o       -> "NewArray " + dst(o.dst())
                                        + (o.elements().length == 0 ? "" : ", elements:[" + joinArgs(o.elements(), exe) + "]");
            case Op.NewPrimitiveArray o -> {
                StringBuilder sb = new StringBuilder("NewPrimitiveArray " + dst(o.dst()) + ", elements:[");
                for (int i = 0; i < o.elements().length; i++) {
                    if (i > 0) sb.append(", ");
                    Object v = o.elements()[i];
                    if (v == Op.HOLE) sb.append("<empty>");
                    else if (v == null) sb.append("null");
                    else if (v instanceof Double d) {
                        // LibJS prints integers without a decimal point and
                        // doubles with their decimal form.
                        if (d == Math.floor(d) && !Double.isInfinite(d)) sb.append((long) (double) d);
                        else sb.append(d);
                    }
                    else if (v instanceof Boolean b) sb.append(b);
                    else sb.append(v);
                }
                sb.append("]");
                yield sb.toString();
            }
            case Op.ArrayAppend o    -> "ArrayAppend " + dst(o.dst()) + ", src:" + operand(o.src(), exe)
                                        + ", is_spread:" + o.isSpread();
            case Op.CallWithArgumentArray o -> "CallWithArgumentArray " + dst(o.dst())
                                               + ", callee:" + operand(o.callee(), exe)
                                               + ", this_value:" + operand(o.thisValue(), exe)
                                               + ", arguments:" + operand(o.arguments(), exe)
                                               + (o.expressionString() != null ? ", " + o.expressionString() : "");
            case Op.CallConstructWithArgumentArray o -> "CallConstructWithArgumentArray " + dst(o.dst())
                                               + ", callee:" + operand(o.callee(), exe)
                                               + ", this_value:Undefined"
                                               + ", arguments:" + operand(o.argumentsArray(), exe)
                                               + (o.expressionString() != null ? ", " + o.expressionString() : "");
            case Op.CreateRestParams o -> "CreateRestParams " + dst(o.dst()) + ", restIndex:" + o.restIndex();
            case Op.MaterializeIterable o -> "MaterializeIterable " + dst(o.dst()) + ", source:" + operand(o.source(), exe);
            case Op.GetIterator o    -> "GetIterator dst_iterator_object:" + operand(o.iteratorObject(), exe)
                                        + ", dst_iterator_next:" + operand(o.iteratorNext(), exe)
                                        + ", dst_iterator_done:" + operand(o.iteratorDone(), exe)
                                        + ", iterable:" + operand(o.iterable(), exe);
            case Op.IteratorNextUnpack o -> "IteratorNextUnpack dst_value:" + operand(o.dstValue(), exe)
                                        + ", dst_done:" + operand(o.dstDone(), exe)
                                        + ", iterator_object:" + operand(o.iteratorObject(), exe)
                                        + ", iterator_next:" + operand(o.iteratorNext(), exe)
                                        + ", iterator_done:" + operand(o.iteratorDone(), exe);
            case Op.IteratorToArray o -> "IteratorToArray " + dst(o.dst())
                                        + ", iterator_object:" + operand(o.iteratorObject(), exe)
                                        + ", iterator_next_method:" + operand(o.iteratorNext(), exe)
                                        + ", iterator_done_property:" + operand(o.iteratorDone(), exe);
            case Op.IteratorClose o  -> "IteratorClose iterator_object:" + operand(o.iteratorObject(), exe)
                                        + ", iterator_next:" + operand(o.iteratorNext(), exe)
                                        + ", iterator_done:" + operand(o.iteratorDone(), exe)
                                        + ", completion_value:" + operand(o.completionValue(), exe);
            case Op.KeysOf o          -> "KeysOf " + dst(o.dst()) + ", source:" + operand(o.source(), exe);
            case Op.GetByValue o     -> "GetByValue " + dst(o.dst()) + ", base:" + operand(o.base(), exe)
                                        + ", property:" + operand(o.property(), exe)
                                        + (o.baseIdentifier() != null
                                            ? " (" + o.baseIdentifier() + "[" + operand(o.property(), exe) + "])"
                                            : "");
            case Op.PutByValue o     -> "PutByValue base:" + operand(o.base(), exe)
                                        + ", property:" + operand(o.property(), exe)
                                        + ", src:" + operand(o.src(), exe)
                                        + ", kind:" + o.kind()
                                        + (o.baseIdentifier() != null
                                            ? " (" + o.baseIdentifier() + "[" + operand(o.property(), exe) + "])"
                                            : "");
            case Op.ToPrimitiveWithStringHint o -> "ToPrimitiveWithStringHint dst:" + operand(o.dst(), exe)
                                                   + ", value:" + operand(o.value(), exe);
            case Op.ToPropertyKey o -> "ToPropertyKey dst:" + dst(o.dst())
                                       + ", value:" + operand(o.value(), exe);
            case Op.ImportCall o     -> "ImportCall " + dst(o.dst())
                                        + ", specifier:" + operand(o.specifier(), exe)
                                        + ", options:" + operand(o.options(), exe);
        };
    }

    private static String dst(Variable v) {
        return "dst:" + operand(v, null);
    }

    private static String fusedJump(String name, Operand l, Operand r,
                                    int truePc, int falsePc,
                                    Executable exe, int[] pcToBlock) {
        return name + " lhs:" + operand(l, exe) + ", rhs:" + operand(r, exe)
             + ", true_target:" + blockRef(truePc, pcToBlock)
             + ", false_target:" + blockRef(falsePc, pcToBlock);
    }

    private static String lhsRhs(Operand lhs, Operand rhs, Executable exe) {
        return "lhs:" + operand(lhs, exe) + ", rhs:" + operand(rhs, exe);
    }

    private static String joinArgs(Operand[] args, Executable exe) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(operand(args[i], exe));
        }
        return sb.toString();
    }

    /**
     * Render an operand. For {@link Operand.Constant}, look up the value in
     * the constant pool and render it directly (matching LibJS, which shows
     * {@code Int32(2)} rather than {@code Constant(1)}).
     */
    private static String operand(Operand o, Executable exe) {
        return switch (o) {
            case Variable.Register r -> "reg" + r.index();
            case Variable.Local l    -> {
                // LibJS renders locals as `name~slot` when a name exists;
                // anonymous locals (rare, internal-only) fall back to `local{slot}`.
                String[] names = exe != null ? exe.localNames() : null;
                yield (names != null && l.slot() < names.length && names[l.slot()] != null)
                    ? names[l.slot()] + "~" + l.slot()
                    : "local" + l.slot();
            }
            case Variable.Argument a -> "arg" + a.position();
            case Operand.Constant c  -> exe != null
                ? formatValue(exe.constants()[c.index()])
                : "constant[" + c.index() + "]";
            case Operand.This t      -> "this";
            // Typed literals: render the value the same way the constants
            // pool would have, so byte-perfect dumps stay stable when the
            // generator switches a literal site between Operand.Constant
            // and a typed alternative.
            case Operand.DoubleLit d -> formatValue(d.value());
            case Operand.BoolLit b   -> formatValue(b.value());
            case Operand.StringLit s -> formatValue(s.value());
            case Operand.UndefinedLit u -> formatValue(Undefined.VALUE);
            case Operand.NullLit n   -> formatValue(null);
        };
    }

    private static String blockRef(int pc, int[] pcToBlock) {
        if (pc < 0 || pc >= pcToBlock.length) return "<bad pc " + pc + ">";
        return "block" + pcToBlock[pc];
    }

    private static String formatPc(int pc) {
        return String.format("%4d", pc);
    }

    /**
     * Render a constant-pool value the way LibJS does: {@code Int32(N)},
     * {@code Bool(true)}, {@code Undefined}, {@code Null}, {@code String("...")}.
     */
    static String formatValue(Object v) {
        if (v == null) return "Null";
        if (v == Undefined.VALUE) return "Undefined";
        if (v == Op.HOLE) return "<Empty>";
        if (v instanceof Boolean b) return "Bool(" + b + ")";
        if (v instanceof Double d) {
            if (d.isNaN()) return "Double(nan)";
            if (d == Double.POSITIVE_INFINITY) return "Double(inf)";
            if (d == Double.NEGATIVE_INFINITY) return "Double(-inf)";
            // Negative zero is distinguishable in IEEE 754 from positive
            // zero — LibJS dumps it as Double(0). Identify via bit pattern.
            if (Double.doubleToRawLongBits(d) == Double.doubleToRawLongBits(-0.0)) {
                return "Double(0)";
            }
            if (d == d.longValue()) {
                long l = d.longValue();
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return "Int32(" + l + ")";
            }
            return "Double(" + esNumberToString(d) + ")";
        }
        if (v instanceof Integer i) return "Int32(" + i + ")";
        if (v instanceof Long l)    return "Int32(" + l + ")";
        if (v instanceof String s)  return "String(\"" + s + "\")";
        return v.getClass().getSimpleName() + "(" + v + ")";
    }

    /**
     * ECMAScript Number {@code ToString} algorithm (ES spec §6.1.6.1.20).
     * LibJS dumps doubles using this format ({@code 1e-7}, {@code 1e+100},
     * {@code 10000000000}), not Java's {@code Double.toString}
     * ({@code 1.0E-7}, {@code 1.0E10}). The two engines agree on the
     * underlying double, but byte-perfect bytecode parity requires matching
     * the textual representation exactly.
     */
    static String esNumberToString(double x) {
        if (x == 0.0) return "0";
        String j = Double.toString(x);
        boolean negative = j.startsWith("-");
        if (negative) j = j.substring(1);

        // Java format: m[.f][Eexp]. Split.
        int eIdx = j.indexOf('E');
        String mantissa = eIdx >= 0 ? j.substring(0, eIdx) : j;
        int javaExp = eIdx >= 0 ? Integer.parseInt(j.substring(eIdx + 1)) : 0;

        int dotIdx = mantissa.indexOf('.');
        String beforeDot = dotIdx >= 0 ? mantissa.substring(0, dotIdx) : mantissa;
        String afterDot  = dotIdx >= 0 ? mantissa.substring(dotIdx + 1) : "";
        String allDigits = beforeDot + afterDot;

        // Strip leading zeros — they don't carry significance — and adjust n
        // to keep the decimal-point position consistent.
        int leadingZeros = 0;
        while (leadingZeros < allDigits.length() - 1 && allDigits.charAt(leadingZeros) == '0') leadingZeros++;
        String s = allDigits.substring(leadingZeros);
        // Strip trailing zeros from s; Java's shortest-roundtrip output
        // includes a {@code .0} for integer-valued doubles (e.g. "1.0E10"),
        // and we don't want those trailing zeros inflating k.
        int sLen = s.length();
        while (sLen > 1 && s.charAt(sLen - 1) == '0') sLen--;
        s = s.substring(0, sLen);

        int k = s.length();
        // n is the decimal-point position relative to s, accounting for the
        // original mantissa shape and Java's own exponent.
        int n = beforeDot.length() - leadingZeros + javaExp;

        String result;
        if (k <= n && n <= 21) {
            // Plain decimal with trailing zeros: e.g., 1e10 → "10000000000".
            StringBuilder sb = new StringBuilder(s);
            for (int i = 0; i < n - k; i++) sb.append('0');
            result = sb.toString();
        } else if (0 < n && n <= 21) {
            // Plain decimal with embedded point: e.g., 100.5 → "100.5".
            result = s.substring(0, n) + "." + s.substring(n);
        } else if (-6 < n && n <= 0) {
            // Plain decimal with leading zeros: e.g., 1e-6 → "0.000001".
            StringBuilder sb = new StringBuilder("0.");
            for (int i = 0; i < -n; i++) sb.append('0');
            sb.append(s);
            result = sb.toString();
        } else {
            // Scientific: e.g., 1e-7 → "1e-7", 1e100 → "1e+100".
            int e = n - 1;
            String sign = e >= 0 ? "+" : "-";
            if (k == 1) {
                result = s + "e" + sign + Math.abs(e);
            } else {
                result = s.charAt(0) + "." + s.substring(1) + "e" + sign + Math.abs(e);
            }
        }
        return negative ? "-" + result : result;
    }

    private static String escapeString(String s) {
        // LibJS does NOT escape quotes or backslashes — match its exact dump
        // format. Only escape control chars (newline/cr/tab) which would
        // otherwise break the line-based dump parser.
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }
}
