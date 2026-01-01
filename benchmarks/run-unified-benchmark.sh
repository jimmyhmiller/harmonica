#!/bin/bash
# Unified cross-language benchmark runner
# Runs our Java parser, Rust parsers, and JS parsers on the same files
# and produces a unified comparison table

set -e

# Get the script's directory and project root
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Create results directory (use absolute path)
RESULTS_DIR="$PROJECT_ROOT/benchmark-results"
mkdir -p "$RESULTS_DIR"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# Change to script directory for consistent relative paths
cd "$SCRIPT_DIR"

# Check for real-world test libraries
if [ ! -d "real-world-libs" ] || [ -z "$(ls -A real-world-libs 2>/dev/null)" ]; then
    echo "Downloading real-world JavaScript libraries for benchmarking..."
    cd "$PROJECT_ROOT"
    ./benchmarks/download-real-world-libs.sh
    cd "$SCRIPT_DIR"
    echo ""
fi

echo "════════════════════════════════════════════════════════════════"
echo "  Unified Cross-Language Parser Benchmark"
echo "════════════════════════════════════════════════════════════════"
echo ""
echo "This will benchmark:"
echo "  - Our Java Parser"
echo "  - Rust parsers (OXC, SWC)"
echo "  - JavaScript parsers (Babel, Acorn, Esprima, Meriyah)"
echo ""
echo "On real-world libraries:"
echo "  - React (10.5 KB)"
echo "  - Vue 3 (130 KB)"
echo "  - React DOM (128.8 KB)"
echo "  - Lodash (531.3 KB)"
echo "  - Three.js (1.28 MB)"
echo "  - TypeScript (8.8 MB)"
echo ""
echo "════════════════════════════════════════════════════════════════"
echo ""

# Build our Java parser if needed
CORE_JAR="$PROJECT_ROOT/harmonica-core/target/harmonica-core-1.0-SNAPSHOT.jar"
if [ ! -f "$CORE_JAR" ]; then
    echo "Building Java parser..."
    cd "$PROJECT_ROOT"
    mvn clean package -q -DskipTests
    cd "$SCRIPT_DIR"
fi

# Compile and run our simple Java benchmark
JAVA_BENCH_DIR="$SCRIPT_DIR/java"
JAVA_BENCH_CLASS="$JAVA_BENCH_DIR/RealWorldBenchmark.class"

echo "[1/3] Benchmarking Our Java Parser..."
cd "$JAVA_BENCH_DIR"
javac -cp "$CORE_JAR" RealWorldBenchmark.java 2>/dev/null || {
    echo "Failed to compile Java benchmark"
    cd "$SCRIPT_DIR"
}
java -cp ".:$CORE_JAR" RealWorldBenchmark 2>&1 | tee "$RESULTS_DIR/java_${TIMESTAMP}.txt"
cd "$SCRIPT_DIR"
echo ""

# 2. Run Rust benchmarks
echo "[2/3] Benchmarking Rust Parsers (OXC, SWC)..."
cd "$SCRIPT_DIR/rust"
cargo run --release --bin benchmark-real-world --quiet -- 20 50 2>&1 > "$RESULTS_DIR/rust_${TIMESTAMP}.txt"
cd "$SCRIPT_DIR"
echo ""

# 3. Run JavaScript benchmarks
echo "[3/3] Benchmarking JavaScript Parsers..."
cd "$SCRIPT_DIR/javascript"
if [ ! -d "node_modules" ]; then
    echo "Installing npm dependencies..."
    npm install
fi
node benchmark-real-world.js 20 50 2>&1 > "$RESULTS_DIR/js_${TIMESTAMP}.txt"
cd "$SCRIPT_DIR"
echo ""

# 4. Generate unified comparison table
echo "════════════════════════════════════════════════════════════════"
echo "  Generating Unified Results Table"
echo "════════════════════════════════════════════════════════════════"
echo ""

RESULTS_DIR_FOR_PYTHON="$RESULTS_DIR" python3 - <<'EOF'
import json
import re
import sys
import os

# Parse Java results from text output (same format as Rust)
def parse_java_results(txt_file):
    results = {}
    try:
        with open(txt_file) as f:
            content = f.read()
            current_lib = None
            for line in content.split('\n'):
                lib_match = re.match(r'Library:\s*(.+)', line)
                if lib_match:
                    current_lib = normalize_lib_name(lib_match.group(1))
                    if current_lib not in results:
                        results[current_lib] = {}
                elif current_lib and 'Our Java Parser' in line and '|' in line:
                    parts = line.split('|')
                    if len(parts) >= 2:
                        time_str = parts[1].strip()
                        try:
                            results[current_lib] = float(time_str)
                        except:
                            pass
    except Exception as e:
        print(f"Warning: Could not parse Java results: {e}", file=sys.stderr)
    return results

# Normalize library names to canonical keys
def normalize_lib_name(name):
    name = name.strip().lower()
    # Map variations to canonical names
    if 'react' in name and 'dom' in name:
        return 'react_dom'
    if 'react' in name:
        return 'react'
    if 'vue' in name:
        return 'vue'
    if 'lodash' in name:
        return 'lodash'
    if 'three' in name:
        return 'three'
    if 'typescript' in name:
        return 'typescript'
    return name.replace(' ', '_').replace('.', '_').replace('-', '_')

# Parse Rust results
def parse_rust_results(txt_file):
    results = {}
    try:
        with open(txt_file) as f:
            content = f.read()
            current_lib = None
            for line in content.split('\n'):
                lib_match = re.match(r'Library:\s*(.+)', line)
                if lib_match:
                    current_lib = normalize_lib_name(lib_match.group(1))
                    if current_lib not in results:
                        results[current_lib] = {}
                elif current_lib and 'OXC' in line and '|' in line:
                    parts = line.split('|')
                    if len(parts) >= 2:
                        time_str = parts[1].strip()
                        try:
                            results[current_lib]['oxc'] = float(time_str)
                        except:
                            pass
                elif current_lib and 'SWC' in line and '|' in line:
                    parts = line.split('|')
                    if len(parts) >= 2:
                        time_str = parts[1].strip()
                        try:
                            results[current_lib]['swc'] = float(time_str)
                        except:
                            pass
    except:
        pass
    return results

# Parse JavaScript results
def parse_js_results(txt_file):
    results = {}
    try:
        with open(txt_file) as f:
            content = f.read()
            current_lib = None
            for line in content.split('\n'):
                if line.startswith('Library:'):
                    lib_name = line.split(':')[1].strip()
                    current_lib = normalize_lib_name(lib_name)
                    if current_lib not in results:
                        results[current_lib] = {}
                elif '|' in line and current_lib:
                    parts = [p.strip() for p in line.split('|')]
                    if len(parts) >= 2 and parts[0]:
                        parser = parts[0].replace('🥇', '').replace('🥈', '').replace('🥉', '').strip().lower()
                        time_str = parts[1].strip()
                        try:
                            time = float(time_str)
                            # Only add if not already present (avoid duplicates)
                            if parser not in results[current_lib]:
                                results[current_lib][parser] = time
                        except:
                            pass
    except:
        pass
    return results

# Load all results
import glob

results_dir = os.environ.get('RESULTS_DIR_FOR_PYTHON', '../benchmark-results')
latest_java = max(glob.glob(f'{results_dir}/java_*.txt'), default=None, key=os.path.getctime)
latest_rust = max(glob.glob(f'{results_dir}/rust_*.txt'), default=None, key=os.path.getctime)
latest_js = max(glob.glob(f'{results_dir}/js_*.txt'), default=None, key=os.path.getctime)

java_results = parse_java_results(latest_java) if latest_java else {}
rust_results = parse_rust_results(latest_rust) if latest_rust else {}
js_results = parse_js_results(latest_js) if latest_js else {}

# Library mappings
libs = [
    ('react', 'React', '10.5 KB'),
    ('vue', 'Vue 3', '130 KB'),
    ('react_dom', 'React DOM', '128.8 KB'),
    ('lodash', 'Lodash', '531.3 KB'),
    ('three', 'Three.js', '1.28 MB'),
    ('typescript', 'TypeScript', '8.8 MB'),
]

print("\n" + "="*120)
print("UNIFIED CROSS-LANGUAGE PARSER BENCHMARK RESULTS")
print("="*120)

for lib_key, lib_name, size in libs:
    print(f"\n{lib_name} ({size})")
    print("-" * 120)
    print(f"{'Parser':<25} | {'Time (ms)':>12} | {'vs Our Parser':>15} | {'Throughput':>20}")
    print("-" * 120)

    # Collect all times for this library
    times = []

    # Our Java parser
    if lib_key in java_results:
        times.append(('Our Java Parser', java_results[lib_key]))

    # Rust parsers
    if lib_key in rust_results:
        if 'oxc' in rust_results[lib_key]:
            times.append(('OXC (Rust)', rust_results[lib_key]['oxc']))
        if 'swc' in rust_results[lib_key]:
            times.append(('SWC (Rust)', rust_results[lib_key]['swc']))

    # JS parsers (keys are now normalized)
    if lib_key in js_results:
        for parser, time in js_results[lib_key].items():
            if 'babel' in parser:
                times.append(('Babel (JS)', time))
            elif 'acorn' in parser:
                times.append(('Acorn (JS)', time))
            elif 'esprima' in parser:
                times.append(('Esprima (JS)', time))
            elif 'meriyah' in parser:
                times.append(('Meriyah (JS)', time))

    # Sort by time and display
    times.sort(key=lambda x: x[1])

    our_time = java_results.get(lib_key, times[0][1] if times else 1)

    for i, (parser, time) in enumerate(times):
        vs_ours = time / our_time if our_time > 0 else 0
        vs_str = f"{vs_ours:.2f}x"

        # Calculate throughput (rough estimate)
        size_kb = float(size.split()[0])
        if 'MB' in size:
            size_kb *= 1024
        throughput = size_kb / time if time > 0 else 0

        medal = ''
        if i == 0:
            medal = '🥇 '
        elif i == 1:
            medal = '🥈 '
        elif i == 2:
            medal = '🥉 '

        print(f"{medal}{parser:<25} | {time:>12.3f} | {vs_str:>15} | {throughput:>17.1f} KB/ms")

    if not times:
        print("  No results available")

print("\n" + "="*120)
print()
EOF

echo "✅ Unified benchmark complete!"
echo ""
echo "Results saved to: $RESULTS_DIR/"
