package com.jimmyhmiller.harmonica.benchmarks;

import com.jimmyhmiller.harmonica.Parser;
import com.jimmyhmiller.harmonica.ast.Program;
import com.jimmyhmiller.harmonica.bytecode.AbruptCompletion;
import com.jimmyhmiller.harmonica.bytecode.Executable;
import com.jimmyhmiller.harmonica.bytecode.Generator;
import com.jimmyhmiller.harmonica.bytecode.Interpreter;
import com.jimmyhmiller.harmonica.bytecode.JSObject;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Run each lodash operation in isolation so we can identify which specific
 * one(s) are dominating the gap to Rhino.
 *
 * <p>Each row pre-builds the same 5,000-element data array and then loops
 * the target lodash op many times. The composite (lodash + bootstrap +
 * data-build) is shared across all timed iterations.
 */
public final class LodashOpBenchmark {

    public static void main(String[] args) throws Exception {
        Path libsDir = Paths.get("benchmarks/real-world-libs");
        if (!Files.exists(libsDir)) libsDir = Paths.get("../benchmarks/real-world-libs");
        String lodash = Files.readString(libsDir.resolve("lodash.js"));

        // Shared setup: parse + bootstrap lodash + build the 5000-element data
        // array. Each row appends a tight loop over a single lodash op.
        String setup = lodash + "\n" +
            "var n = 5000;\n" +
            "var data = _.range(n).map(function(i) { return { id: i, group: i % 13, name: 'item-' + i }; });\n";

        // Each row uses ITERS so the total work is comparable.
        record Op(String label, int iters, String body) {}
        Op[] ops = {
            new Op("range(5000)        ",  200, "_.range(n);"),
            new Op("map(buildObj)      ",  100, "_.map(_.range(n), function(i){return {id:i};});"),
            new Op("groupBy('group')   ",  50,  "_.groupBy(data, 'group');"),
            new Op("sortBy ['group',id]",  20,  "_.sortBy(data, ['group','id']);"),
            new Op("sumBy('id')        ",  100, "_.sumBy(data, 'id');"),
            new Op("uniqBy(d=>d.id%7)  ",  100, "_.uniqBy(data, function(d){return d.id%7;});"),
            new Op("partition(group<5) ",  100, "_.partition(data, function(d){return d.group<5;});"),
            new Op("filter(id%3===0)   ",  100, "_.filter(data, function(d){return d.id%3===0&&d.group>2;});"),
            new Op("map(filtered,'name')", 100, "var f = _.filter(data, function(d){return d.group<3;}); _.map(f,'name');"),
            new Op("chunk(50)          ",  100, "_.chunk(data, 50);"),
            new Op("cloneDeep(grouped) ",  20,  "var g=_.groupBy(data,'group'); _.cloneDeep(g);"),
            new Op("get('a.b.c')       ",  500, "var o={a:{b:{c:42}}}; for(var i=0;i<n;i++) _.get(o,'a.b.c');"),
            new Op("isObject(d)        ",  500, "for(var i=0;i<n;i++) _.isObject(data[i%n]);"),
            new Op("cloneDeep(simple)  ",  100, "var s={a:1,b:2,c:3,d:'x'}; _.cloneDeep(s);"),
            new Op("cloneDeep(item)    ", 5000, "_.cloneDeep(data[0]);"),
            new Op("cloneDeep(data[100"
                 + "])                  ", 200, "var sl=data.slice(0,100); _.cloneDeep(sl);"),
            new Op("manualClone(grouped",  20,
                "var manualClone = function(o){"
                  + "if(typeof o!=='object'||o===null) return o;"
                  + "if(Array.isArray(o)){var arr=[];for(var i=0;i<o.length;i++)arr[i]=manualClone(o[i]);return arr;}"
                  + "var copy={};var keys=Object.keys(o);"
                  + "for(var j=0;j<keys.length;j++) copy[keys[j]]=manualClone(o[keys[j]]);"
                  + "return copy;};"
                + "var g=_.groupBy(data,'group'); manualClone(g);"),
        };

        System.out.println();
        System.out.println("=== Lodash op-by-op micro-bench: harmonica vs Rhino (-opt -1, ES6) ===");
        System.out.printf("JVM: %s %s    n=5000-element array%n",
            System.getProperty("java.vm.name"), System.getProperty("java.version"));
        System.out.println();
        System.out.printf("%-22s  %5s  %-14s  %-14s  %-14s%n",
            "op", "iters", "harmonica", "Rhino -1", "ratio");
        System.out.printf("%-22s  %5s  %-14s  %-14s  %-14s%n",
            "----------------------", "-----", "--------------", "--------------", "--------------");

        for (Op op : ops) {
            String src = setup + "var __k = " + op.iters + ";\n" +
                "for (var __i = 0; __i < __k; __i++) {\n" + op.body + "\n}";
            // Pre-build once; bestOf 3 to mimic harness
            try { runHarm(src); } catch (Throwable t) { System.err.println("[warmup harm " + op.label + "] " + t); }
            try { runRhino(src); } catch (Throwable t) { System.err.println("[warmup rhino " + op.label + "] " + t); }
            double harm = bestOf(3, () -> { try { runHarm(src); } catch (Throwable t) { throw new RuntimeException(t); } });
            double rhin = bestOf(3, () -> { try { runRhino(src); } catch (Throwable t) { throw new RuntimeException(t); } });
            String harmS = harm < 0 ? "fail" : String.format("%9.1f ms", harm);
            String rhinS = rhin < 0 ? "fail" : String.format("%9.1f ms", rhin);
            String ratio = (harm > 0 && rhin > 0)
                ? String.format("%.2fx %s", harm < rhin ? rhin/harm : harm/rhin, harm < rhin ? "faster" : "slower")
                : "—";
            System.out.printf("%-22s  %5d  %-14s  %-14s  %-14s%n",
                op.label, op.iters, harmS, rhinS, ratio);
        }
    }

    private static void runHarm(String source) {
        Program ast = Parser.parse(source);
        Executable exe = Generator.generate(ast);
        Interpreter.interpret(exe, new Object[0], 64);
    }

    private static void runRhino(String source) {
        Context ctx = Context.enter();
        try {
            ctx.setOptimizationLevel(-1);
            ctx.setLanguageVersion(Context.VERSION_ES6);
            Scriptable scope = ctx.initStandardObjects();
            ctx.evaluateString(scope, source, "<bench>", 1, null);
        } finally { Context.exit(); }
    }

    private static double bestOf(int iters, Runnable r) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            try { r.run(); }
            catch (RuntimeException ex) {
                if (ex.getCause() instanceof AbruptCompletion ac) {
                    if (ac.value() instanceof JSObject jo) {
                        System.err.println("[harm AC] " + jo.get("name") + ": " + jo.get("message"));
                    } else {
                        System.err.println("[harm AC] " + ac.value());
                    }
                } else {
                    System.err.println("[error] " + ex);
                }
                return -1;
            } catch (Throwable t) {
                System.err.println("[error] " + t);
                return -1;
            }
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            if (ms < best) best = ms;
        }
        return best;
    }
}
