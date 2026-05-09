// Stub Octane harness — captures benchmarks without auto-running them so a
// host runner can iterate them with custom timing.
var __benchmarks = [];
function BenchmarkSuite(name, refs, list) { /* ignore — benchmarks self-register via __benchmarks */ }
function Benchmark(name, doWarmup, doDeterministic, deterministicIterations, run, setup, tearDown) {
  __benchmarks.push({
    name: name,
    iterations: deterministicIterations,
    run: run,
    setup: setup,
    tearDown: tearDown
  });
}
// Splay calls performance.now() inside its setup — provide a Date.now-backed
// fallback (millis precision is fine for benchmark timing).
var performance = { now: function () { return Date.now(); } };
// Octane assumes a global `print` (test-shell convention). Rhino has print()
// but harmonica does not — alias from console.log so logging benchmarks don't
// crash when their setup logs progress.
if (typeof print !== "function") {
  print = function () { /* swallow */ };
}
