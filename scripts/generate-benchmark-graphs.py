#!/usr/bin/env python3
"""
Generate graphs from cross-language JavaScript parser benchmarks.
Creates comparison bar charts for parsing time and throughput across all parsers.
"""

import re
import sys
import os
from pathlib import Path
from typing import Dict, List, Tuple
from dataclasses import dataclass

try:
    import matplotlib.pyplot as plt
    import matplotlib.patches as mpatches
    import numpy as np
except ImportError:
    print("Error: matplotlib and numpy are required. Install with:")
    print("  pip install matplotlib numpy")
    sys.exit(1)


@dataclass
class BenchmarkResult:
    parser: str
    language: str  # 'Java', 'JavaScript', 'Rust'
    avg_time_ms: float
    throughput_kb_ms: float


@dataclass
class LibraryBenchmark:
    name: str
    size_kb: float
    results: List[BenchmarkResult]


def parse_java_results(filepath: str) -> Dict[str, List[BenchmarkResult]]:
    """Parse Java benchmark results file."""
    results = {}
    current_library = None

    try:
        with open(filepath, 'r') as f:
            content = f.read()
    except FileNotFoundError:
        return results

    # Find each library section
    library_pattern = r'Library: (.+?)\nSize: ([\d.]+) KB'
    result_pattern = r'Our Java Parser\s+\|\s+([\d.]+)\s+\|\s+([\d.]+)'

    for match in re.finditer(library_pattern, content):
        lib_name = match.group(1).strip()
        lib_size = float(match.group(2))

        # Find the result after this library header
        remaining = content[match.end():]
        result_match = re.search(result_pattern, remaining)

        if result_match:
            avg_time = float(result_match.group(1))
            throughput = float(result_match.group(2))

            results[lib_name] = [BenchmarkResult(
                parser="Harmonica",
                language="Java",
                avg_time_ms=avg_time,
                throughput_kb_ms=throughput
            )]

    return results


def parse_javascript_results(filepath: str) -> Dict[str, List[BenchmarkResult]]:
    """Parse JavaScript benchmark results file."""
    results = {}

    try:
        with open(filepath, 'r') as f:
            content = f.read()
    except FileNotFoundError:
        return results

    # Find each library section - library name followed by results
    library_pattern = r'Library: (.+?)\nSize: ([\d.]+) KB'
    # Match parser results like: 🥇 Meriyah            |           0.295 |            1.00x |         35.5 KB/ms
    result_pattern = r'[🥇🥈🥉]\s+(\S+)\s+\|\s+([\d.]+)\s+\|\s+[\d.]+x\s+\|\s+([\d.]+)\s+KB/ms'

    # Find all library positions
    lib_matches = list(re.finditer(library_pattern, content))

    for i, lib_match in enumerate(lib_matches):
        lib_name = lib_match.group(1).strip()
        lib_name = normalize_library_name(lib_name)

        # Get the section between this library and the next (or end)
        start = lib_match.end()
        end = lib_matches[i + 1].start() if i + 1 < len(lib_matches) else len(content)
        section = content[start:end]

        lib_results = []
        for result_match in re.finditer(result_pattern, section):
            parser = result_match.group(1).strip()
            avg_time = float(result_match.group(2))
            throughput = float(result_match.group(3))

            lib_results.append(BenchmarkResult(
                parser=parser,
                language="JavaScript",
                avg_time_ms=avg_time,
                throughput_kb_ms=throughput
            ))

        if lib_results:
            results[lib_name] = lib_results

    return results


def parse_rust_results(filepath: str) -> Dict[str, List[BenchmarkResult]]:
    """Parse Rust benchmark results file."""
    results = {}

    try:
        with open(filepath, 'r') as f:
            content = f.read()
    except FileNotFoundError:
        return results

    # Find each library section
    library_pattern = r'Library: (.+?)\nSize: ([\d.]+) KB'
    # Match parser results like: 🥇 OXC (Rust)         |           0.138 |            1.00x |                 76.2
    result_pattern = r'[🥇🥈🥉]\s+(.+?)\s+\|\s+([\d.]+)\s+\|\s+[\d.]+x\s+\|\s+([\d.]+)'

    # Find all library positions
    lib_matches = list(re.finditer(library_pattern, content))

    for i, lib_match in enumerate(lib_matches):
        lib_name = lib_match.group(1).strip()
        lib_name = normalize_library_name(lib_name)

        # Get the section between this library and the next (or end)
        start = lib_match.end()
        end = lib_matches[i + 1].start() if i + 1 < len(lib_matches) else len(content)
        section = content[start:end]

        lib_results = []
        for result_match in re.finditer(result_pattern, section):
            parser = result_match.group(1).strip()
            # Simplify parser names
            parser = parser.replace(' (Rust)', '')
            avg_time = float(result_match.group(2))
            throughput = float(result_match.group(3))

            lib_results.append(BenchmarkResult(
                parser=parser,
                language="Rust",
                avg_time_ms=avg_time,
                throughput_kb_ms=throughput
            ))

        if lib_results:
            results[lib_name] = lib_results

    return results


def normalize_library_name(name: str) -> str:
    """Normalize library names across different result files."""
    name_map = {
        'react.production.min.js': 'React',
        'vue.global.prod.js': 'Vue 3',
        'react-dom.production.min.js': 'React DOM',
        'lodash.js': 'Lodash',
        'three.js': 'Three.js',
        'typescript.js': 'TypeScript Compiler',
    }
    return name_map.get(name, name)


def find_latest_results(results_dir: str) -> Tuple[str, str, str]:
    """Find the latest benchmark result files."""
    results_path = Path(results_dir)

    java_files = sorted(results_path.glob('java_*.txt'), reverse=True)
    js_files = sorted(results_path.glob('js_*.txt'), reverse=True)
    rust_files = sorted(results_path.glob('rust_*.txt'), reverse=True)

    # Filter out realworld files for this purpose, we want the regular ones
    java_files = [f for f in java_files if 'realworld' not in f.name and 'our_parser' not in f.name]
    js_files = [f for f in js_files if 'realworld' not in f.name]
    rust_files = [f for f in rust_files if 'realworld' not in f.name]

    java_file = str(java_files[0]) if java_files else None
    js_file = str(js_files[0]) if js_files else None
    rust_file = str(rust_files[0]) if rust_files else None

    return java_file, js_file, rust_file


def merge_results(java_results: Dict, js_results: Dict, rust_results: Dict) -> Dict[str, List[BenchmarkResult]]:
    """Merge results from all languages into a single structure."""
    all_libraries = set(java_results.keys()) | set(js_results.keys()) | set(rust_results.keys())

    merged = {}
    for lib in all_libraries:
        merged[lib] = []
        if lib in java_results:
            merged[lib].extend(java_results[lib])
        if lib in js_results:
            merged[lib].extend(js_results[lib])
        if lib in rust_results:
            merged[lib].extend(rust_results[lib])

    return merged


def get_language_color(language: str) -> str:
    """Get color for a language."""
    colors = {
        'Rust': '#dea584',      # Rust orange
        'JavaScript': '#f7df1e', # JS yellow
        'Java': '#5382a1',       # Java blue
    }
    return colors.get(language, '#888888')


def get_parser_color(parser: str, language: str) -> str:
    """Get color for a specific parser."""
    colors = {
        # Rust parsers - orange tones
        'OXC': '#e07020',
        'SWC': '#ff9955',
        # JavaScript parsers - yellow/green tones
        'Meriyah': '#2d9f2d',
        'Acorn': '#7cb342',
        '@babel/parser': '#f5da55',
        # Java parsers - blue tones
        'Harmonica': '#1e88e5',
    }
    return colors.get(parser, get_language_color(language))


def create_time_comparison_chart(results: Dict[str, List[BenchmarkResult]], output_path: str):
    """Create a grouped bar chart comparing parsing times."""
    # Order libraries by size (smallest to largest)
    library_order = ['React', 'Vue 3', 'React DOM', 'Lodash', 'Three.js', 'TypeScript Compiler']
    libraries = [lib for lib in library_order if lib in results]

    # Get all unique parsers and their languages
    all_parsers = set()
    parser_language = {}
    for lib_results in results.values():
        for r in lib_results:
            all_parsers.add(r.parser)
            parser_language[r.parser] = r.language

    # Order parsers: Rust first, then JS, then Java
    language_order = {'Rust': 0, 'JavaScript': 1, 'Java': 2}
    parsers = sorted(all_parsers, key=lambda p: (language_order.get(parser_language.get(p, 'Other'), 3), p))

    # Setup the plot
    fig, ax = plt.subplots(figsize=(14, 8))

    x = np.arange(len(libraries))
    width = 0.12
    multiplier = 0

    for parser in parsers:
        times = []
        for lib in libraries:
            lib_results = results.get(lib, [])
            parser_result = next((r for r in lib_results if r.parser == parser), None)
            times.append(parser_result.avg_time_ms if parser_result else 0)

        offset = width * multiplier
        color = get_parser_color(parser, parser_language.get(parser, 'Other'))
        bars = ax.bar(x + offset, times, width, label=parser, color=color, edgecolor='black', linewidth=0.5)
        multiplier += 1

    # Customize the plot
    ax.set_ylabel('Parsing Time (ms)', fontsize=12)
    ax.set_xlabel('JavaScript Library', fontsize=12)
    ax.set_title('JavaScript Parser Performance Comparison\n(Lower is Better)', fontsize=14, fontweight='bold')
    ax.set_xticks(x + width * (len(parsers) - 1) / 2)
    ax.set_xticklabels(libraries, rotation=15, ha='right')
    ax.legend(loc='upper left', ncols=2)
    ax.set_yscale('log')
    ax.grid(axis='y', alpha=0.3)
    ax.set_axisbelow(True)

    # Add language legend
    rust_patch = mpatches.Patch(color='#dea584', label='Rust')
    js_patch = mpatches.Patch(color='#90b040', label='JavaScript')
    java_patch = mpatches.Patch(color='#5382a1', label='Java')

    plt.tight_layout()
    plt.savefig(output_path, dpi=150, bbox_inches='tight')
    print(f"Saved: {output_path}")
    plt.close()


def create_throughput_comparison_chart(results: Dict[str, List[BenchmarkResult]], output_path: str):
    """Create a grouped bar chart comparing throughput."""
    # Order libraries by size
    library_order = ['React', 'Vue 3', 'React DOM', 'Lodash', 'Three.js', 'TypeScript Compiler']
    libraries = [lib for lib in library_order if lib in results]

    # Get all unique parsers
    all_parsers = set()
    parser_language = {}
    for lib_results in results.values():
        for r in lib_results:
            all_parsers.add(r.parser)
            parser_language[r.parser] = r.language

    # Order parsers
    language_order = {'Rust': 0, 'JavaScript': 1, 'Java': 2}
    parsers = sorted(all_parsers, key=lambda p: (language_order.get(parser_language.get(p, 'Other'), 3), p))

    # Setup the plot
    fig, ax = plt.subplots(figsize=(14, 8))

    x = np.arange(len(libraries))
    width = 0.12
    multiplier = 0

    for parser in parsers:
        throughputs = []
        for lib in libraries:
            lib_results = results.get(lib, [])
            parser_result = next((r for r in lib_results if r.parser == parser), None)
            throughputs.append(parser_result.throughput_kb_ms if parser_result else 0)

        offset = width * multiplier
        color = get_parser_color(parser, parser_language.get(parser, 'Other'))
        bars = ax.bar(x + offset, throughputs, width, label=parser, color=color, edgecolor='black', linewidth=0.5)
        multiplier += 1

    # Customize the plot
    ax.set_ylabel('Throughput (KB/ms)', fontsize=12)
    ax.set_xlabel('JavaScript Library', fontsize=12)
    ax.set_title('JavaScript Parser Throughput Comparison\n(Higher is Better)', fontsize=14, fontweight='bold')
    ax.set_xticks(x + width * (len(parsers) - 1) / 2)
    ax.set_xticklabels(libraries, rotation=15, ha='right')
    ax.legend(loc='upper left', ncols=2)
    ax.grid(axis='y', alpha=0.3)
    ax.set_axisbelow(True)

    plt.tight_layout()
    plt.savefig(output_path, dpi=150, bbox_inches='tight')
    print(f"Saved: {output_path}")
    plt.close()


def create_per_library_charts(results: Dict[str, List[BenchmarkResult]], output_dir: str):
    """Create individual bar charts for each library."""
    os.makedirs(output_dir, exist_ok=True)

    library_order = ['React', 'Vue 3', 'React DOM', 'Lodash', 'Three.js', 'TypeScript Compiler']

    for lib in library_order:
        if lib not in results:
            continue

        lib_results = results[lib]

        # Sort by time (fastest first)
        lib_results_sorted = sorted(lib_results, key=lambda r: r.avg_time_ms)

        parsers = [r.parser for r in lib_results_sorted]
        times = [r.avg_time_ms for r in lib_results_sorted]
        colors = [get_parser_color(r.parser, r.language) for r in lib_results_sorted]

        fig, ax = plt.subplots(figsize=(10, 6))

        bars = ax.barh(parsers, times, color=colors, edgecolor='black', linewidth=0.5)

        # Add time labels on bars
        for bar, time in zip(bars, times):
            width = bar.get_width()
            ax.text(width + max(times) * 0.02, bar.get_y() + bar.get_height()/2,
                   f'{time:.2f} ms', va='center', fontsize=10)

        ax.set_xlabel('Parsing Time (ms)', fontsize=12)
        ax.set_title(f'Parser Performance: {lib}\n(Lower is Better)', fontsize=14, fontweight='bold')
        ax.invert_yaxis()
        ax.grid(axis='x', alpha=0.3)
        ax.set_axisbelow(True)

        # Extend x-axis to fit labels
        ax.set_xlim(0, max(times) * 1.25)

        plt.tight_layout()
        safe_name = lib.lower().replace(' ', '_').replace('.', '')
        output_path = os.path.join(output_dir, f'{safe_name}_comparison.png')
        plt.savefig(output_path, dpi=150, bbox_inches='tight')
        print(f"Saved: {output_path}")
        plt.close()


def create_typescript_focused_chart(results: Dict[str, List[BenchmarkResult]], output_path: str):
    """Create a focused chart for TypeScript (the largest benchmark)."""
    if 'TypeScript Compiler' not in results:
        return

    lib_results = sorted(results['TypeScript Compiler'], key=lambda r: r.avg_time_ms)

    parsers = [r.parser for r in lib_results]
    times = [r.avg_time_ms for r in lib_results]
    throughputs = [r.throughput_kb_ms for r in lib_results]
    colors = [get_parser_color(r.parser, r.language) for r in lib_results]

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(14, 6))

    # Time comparison
    bars1 = ax1.barh(parsers, times, color=colors, edgecolor='black', linewidth=0.5)
    for bar, time in zip(bars1, times):
        width = bar.get_width()
        ax1.text(width + max(times) * 0.02, bar.get_y() + bar.get_height()/2,
                f'{time:.1f} ms', va='center', fontsize=10)
    ax1.set_xlabel('Parsing Time (ms)', fontsize=12)
    ax1.set_title('Parsing Time (Lower is Better)', fontsize=12, fontweight='bold')
    ax1.invert_yaxis()
    ax1.grid(axis='x', alpha=0.3)
    ax1.set_xlim(0, max(times) * 1.3)

    # Throughput comparison
    bars2 = ax2.barh(parsers, throughputs, color=colors, edgecolor='black', linewidth=0.5)
    for bar, tp in zip(bars2, throughputs):
        width = bar.get_width()
        ax2.text(width + max(throughputs) * 0.02, bar.get_y() + bar.get_height()/2,
                f'{tp:.1f} KB/ms', va='center', fontsize=10)
    ax2.set_xlabel('Throughput (KB/ms)', fontsize=12)
    ax2.set_title('Throughput (Higher is Better)', fontsize=12, fontweight='bold')
    ax2.invert_yaxis()
    ax2.grid(axis='x', alpha=0.3)
    ax2.set_xlim(0, max(throughputs) * 1.3)

    fig.suptitle('TypeScript Compiler (8.8 MB) - Parser Performance', fontsize=14, fontweight='bold')

    plt.tight_layout()
    plt.savefig(output_path, dpi=150, bbox_inches='tight')
    print(f"Saved: {output_path}")
    plt.close()


def main():
    """Main function to generate benchmark graphs."""
    # Find the project root (where benchmark-results directory is)
    script_dir = Path(__file__).parent
    project_root = script_dir.parent
    results_dir = project_root / 'benchmark-results'
    output_dir = project_root / 'benchmark-graphs'

    if not results_dir.exists():
        print(f"Error: Results directory not found: {results_dir}")
        print("Run the benchmarks first with: ./scripts/run-all-benchmarks.sh")
        sys.exit(1)

    # Create output directory
    output_dir.mkdir(exist_ok=True)

    # Find latest result files
    java_file, js_file, rust_file = find_latest_results(str(results_dir))

    print("Parsing benchmark results...")
    print(f"  Java: {java_file}")
    print(f"  JavaScript: {js_file}")
    print(f"  Rust: {rust_file}")
    print()

    # Parse results
    java_results = parse_java_results(java_file) if java_file else {}
    js_results = parse_javascript_results(js_file) if js_file else {}
    rust_results = parse_rust_results(rust_file) if rust_file else {}

    if not any([java_results, js_results, rust_results]):
        print("Error: No benchmark results found!")
        sys.exit(1)

    # Merge all results
    all_results = merge_results(java_results, js_results, rust_results)

    print(f"Found results for {len(all_results)} libraries:")
    for lib in sorted(all_results.keys()):
        parsers = [r.parser for r in all_results[lib]]
        print(f"  {lib}: {', '.join(parsers)}")
    print()

    # Generate graphs
    print("Generating graphs...")

    # Main comparison charts
    create_time_comparison_chart(all_results, str(output_dir / 'parsing_time_comparison.png'))
    create_throughput_comparison_chart(all_results, str(output_dir / 'throughput_comparison.png'))

    # Per-library charts
    create_per_library_charts(all_results, str(output_dir / 'per_library'))

    # TypeScript focused chart
    create_typescript_focused_chart(all_results, str(output_dir / 'typescript_detailed.png'))

    print()
    print(f"All graphs saved to: {output_dir}")


if __name__ == '__main__':
    main()
