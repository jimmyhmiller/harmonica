#!/usr/bin/env node
/**
 * Generate graphs from cross-language JavaScript parser benchmarks.
 * Creates comparison bar charts for parsing time and throughput across all parsers.
 */

const fs = require('fs');
const path = require('path');
const vega = require('vega');
const vegaLite = require('vega-lite');

// Parser colors
const parserColors = {
  'OXC': '#e07020',
  'SWC': '#ff9955',
  'Meriyah': '#2d9f2d',
  'Acorn': '#7cb342',
  '@babel/parser': '#f5da55',
  'Harmonica': '#1e88e5',
};

const languageOrder = { 'Rust': 0, 'JavaScript': 1, 'Java': 2 };

const libraryOrder = ['React', 'Vue 3', 'React DOM', 'Lodash', 'Three.js', 'TypeScript Compiler'];

const libraryNameMap = {
  'react.production.min.js': 'React',
  'vue.global.prod.js': 'Vue 3',
  'react-dom.production.min.js': 'React DOM',
  'lodash.js': 'Lodash',
  'three.js': 'Three.js',
  'typescript.js': 'TypeScript Compiler',
};

function normalizeLibraryName(name) {
  return libraryNameMap[name] || name;
}

function parseJavaResults(filepath) {
  const results = {};

  try {
    const content = fs.readFileSync(filepath, 'utf8');
    const libraryPattern = /Library: (.+?)\nSize: ([\d.]+) KB/g;
    const resultPattern = /Our Java Parser\s+\|\s+([\d.]+)\s+\|\s+([\d.]+)/;

    let match;
    while ((match = libraryPattern.exec(content)) !== null) {
      const libName = normalizeLibraryName(match[1].trim());
      const remaining = content.slice(match.index + match[0].length);
      const resultMatch = remaining.match(resultPattern);

      if (resultMatch) {
        results[libName] = [{
          parser: 'Harmonica',
          language: 'Java',
          avgTimeMs: parseFloat(resultMatch[1]),
          throughputKbMs: parseFloat(resultMatch[2])
        }];
      }
    }
  } catch (e) {
    if (e.code !== 'ENOENT') console.error(`Error reading ${filepath}:`, e.message);
  }

  return results;
}

function parseJavaScriptResults(filepath) {
  const results = {};

  try {
    const content = fs.readFileSync(filepath, 'utf8');
    const libraryPattern = /Library: (.+?)\nSize: ([\d.]+) KB/g;

    const libMatches = [];
    let match;
    while ((match = libraryPattern.exec(content)) !== null) {
      libMatches.push({ name: match[1].trim(), index: match.index, end: match.index + match[0].length });
    }

    for (let i = 0; i < libMatches.length; i++) {
      const libName = normalizeLibraryName(libMatches[i].name);
      const start = libMatches[i].end;
      const end = i + 1 < libMatches.length ? libMatches[i + 1].index : content.length;
      const section = content.slice(start, end);

      const libResults = [];
      const resultPattern = /[🥇🥈🥉]\s+(\S+)\s+\|\s+([\d.]+)\s+\|\s+[\d.]+x\s+\|\s+([\d.]+)\s+KB\/ms/g;
      let resultMatch;
      while ((resultMatch = resultPattern.exec(section)) !== null) {
        libResults.push({
          parser: resultMatch[1].trim(),
          language: 'JavaScript',
          avgTimeMs: parseFloat(resultMatch[2]),
          throughputKbMs: parseFloat(resultMatch[3])
        });
      }

      if (libResults.length > 0) {
        results[libName] = libResults;
      }
    }
  } catch (e) {
    if (e.code !== 'ENOENT') console.error(`Error reading ${filepath}:`, e.message);
  }

  return results;
}

function parseRustResults(filepath) {
  const results = {};

  try {
    const content = fs.readFileSync(filepath, 'utf8');
    const libraryPattern = /Library: (.+?)\nSize: ([\d.]+) KB/g;

    const libMatches = [];
    let match;
    while ((match = libraryPattern.exec(content)) !== null) {
      libMatches.push({ name: match[1].trim(), index: match.index, end: match.index + match[0].length });
    }

    for (let i = 0; i < libMatches.length; i++) {
      const libName = normalizeLibraryName(libMatches[i].name);
      const start = libMatches[i].end;
      const end = i + 1 < libMatches.length ? libMatches[i + 1].index : content.length;
      const section = content.slice(start, end);

      const libResults = [];
      const resultPattern = /[🥇🥈🥉]\s+(.+?)\s+\|\s+([\d.]+)\s+\|\s+[\d.]+x\s+\|\s+([\d.]+)/g;
      let resultMatch;
      while ((resultMatch = resultPattern.exec(section)) !== null) {
        const parser = resultMatch[1].trim().replace(' (Rust)', '');
        libResults.push({
          parser,
          language: 'Rust',
          avgTimeMs: parseFloat(resultMatch[2]),
          throughputKbMs: parseFloat(resultMatch[3])
        });
      }

      if (libResults.length > 0) {
        results[libName] = libResults;
      }
    }
  } catch (e) {
    if (e.code !== 'ENOENT') console.error(`Error reading ${filepath}:`, e.message);
  }

  return results;
}

function findLatestResults(resultsDir) {
  const files = fs.readdirSync(resultsDir);

  const javaFiles = files.filter(f => f.startsWith('java_') && f.endsWith('.txt') && !f.includes('realworld') && !f.includes('our_parser')).sort().reverse();
  const jsFiles = files.filter(f => f.startsWith('js_') && f.endsWith('.txt') && !f.includes('realworld')).sort().reverse();
  const rustFiles = files.filter(f => f.startsWith('rust_') && f.endsWith('.txt') && !f.includes('realworld')).sort().reverse();

  return {
    java: javaFiles[0] ? path.join(resultsDir, javaFiles[0]) : null,
    js: jsFiles[0] ? path.join(resultsDir, jsFiles[0]) : null,
    rust: rustFiles[0] ? path.join(resultsDir, rustFiles[0]) : null
  };
}

function mergeResults(javaResults, jsResults, rustResults) {
  const allLibraries = new Set([
    ...Object.keys(javaResults),
    ...Object.keys(jsResults),
    ...Object.keys(rustResults)
  ]);

  const merged = {};
  for (const lib of allLibraries) {
    merged[lib] = [
      ...(javaResults[lib] || []),
      ...(jsResults[lib] || []),
      ...(rustResults[lib] || [])
    ];
  }

  return merged;
}

function getParserLanguage(results) {
  const parserLanguage = {};
  for (const libResults of Object.values(results)) {
    for (const r of libResults) {
      parserLanguage[r.parser] = r.language;
    }
  }
  return parserLanguage;
}

function getAllParsers(results) {
  const parserLanguage = getParserLanguage(results);
  const parsers = [...new Set(Object.values(results).flatMap(r => r.map(x => x.parser)))];
  return parsers.sort((a, b) => {
    const langA = languageOrder[parserLanguage[a]] ?? 3;
    const langB = languageOrder[parserLanguage[b]] ?? 3;
    if (langA !== langB) return langA - langB;
    return a.localeCompare(b);
  });
}

function resultsToData(results) {
  const data = [];
  const libraries = libraryOrder.filter(lib => lib in results);

  for (const lib of libraries) {
    for (const r of results[lib]) {
      data.push({
        library: lib,
        parser: r.parser,
        language: r.language,
        time: r.avgTimeMs,
        throughput: r.throughputKbMs
      });
    }
  }

  return data;
}

async function renderChart(spec, outputPath) {
  const vegaSpec = vegaLite.compile(spec).spec;
  const view = new vega.View(vega.parse(vegaSpec), { renderer: 'none' });
  const svg = await view.toSVG();

  // Convert SVG to PNG using sharp if available, otherwise save as SVG
  try {
    const sharp = require('sharp');
    const pngBuffer = await sharp(Buffer.from(svg)).png().toBuffer();
    fs.writeFileSync(outputPath, pngBuffer);
  } catch (e) {
    // Fallback to SVG
    const svgPath = outputPath.replace('.png', '.svg');
    fs.writeFileSync(svgPath, svg);
    console.log(`  (saved as SVG: sharp not available for PNG conversion)`);
  }

  console.log(`Saved: ${outputPath}`);
}

async function createTimeComparisonChart(results, outputPath) {
  const data = resultsToData(results);
  const parsers = getAllParsers(results);

  const spec = {
    $schema: 'https://vega.github.io/schema/vega-lite/v5.json',
    title: {
      text: 'JavaScript Parser Performance Comparison',
      subtitle: 'Parsing Time (ms) - Lower is Better',
      fontSize: 20
    },
    width: 120,
    height: 400,
    data: { values: data },
    facet: {
      column: {
        field: 'library',
        type: 'nominal',
        title: null,
        sort: libraryOrder,
        header: { labelAngle: -20, labelAlign: 'right' }
      }
    },
    spec: {
      mark: 'bar',
      width: 100,
      encoding: {
        x: {
          field: 'parser',
          type: 'nominal',
          title: null,
          sort: parsers,
          axis: null
        },
        y: {
          field: 'time',
          type: 'quantitative',
          title: 'Parsing Time (ms)'
        },
        color: {
          field: 'parser',
          type: 'nominal',
          title: 'Parser',
          sort: parsers,
          scale: {
            domain: parsers,
            range: parsers.map(p => parserColors[p] || '#888888')
          }
        },
        tooltip: [
          { field: 'parser', title: 'Parser' },
          { field: 'library', title: 'Library' },
          { field: 'time', title: 'Time (ms)', format: '.2f' },
          { field: 'language', title: 'Language' }
        ]
      }
    },
    config: {
      background: 'white',
      view: { stroke: null }
    }
  };

  await renderChart(spec, outputPath);
}

async function createThroughputComparisonChart(results, outputPath) {
  const data = resultsToData(results);
  const parsers = getAllParsers(results);

  const spec = {
    $schema: 'https://vega.github.io/schema/vega-lite/v5.json',
    title: {
      text: 'JavaScript Parser Throughput Comparison',
      subtitle: 'Throughput (KB/ms) - Higher is Better',
      fontSize: 20
    },
    width: 120,
    height: 400,
    data: { values: data },
    facet: {
      column: {
        field: 'library',
        type: 'nominal',
        title: null,
        sort: libraryOrder,
        header: { labelAngle: -20, labelAlign: 'right' }
      }
    },
    spec: {
      mark: 'bar',
      width: 100,
      encoding: {
        x: {
          field: 'parser',
          type: 'nominal',
          title: null,
          sort: parsers,
          axis: null
        },
        y: {
          field: 'throughput',
          type: 'quantitative',
          title: 'Throughput (KB/ms)'
        },
        color: {
          field: 'parser',
          type: 'nominal',
          title: 'Parser',
          sort: parsers,
          scale: {
            domain: parsers,
            range: parsers.map(p => parserColors[p] || '#888888')
          }
        },
        tooltip: [
          { field: 'parser', title: 'Parser' },
          { field: 'library', title: 'Library' },
          { field: 'throughput', title: 'Throughput (KB/ms)', format: '.1f' },
          { field: 'language', title: 'Language' }
        ]
      }
    },
    config: {
      background: 'white',
      view: { stroke: null }
    }
  };

  await renderChart(spec, outputPath);
}

async function createPerLibraryChart(libName, libResults, outputPath) {
  const sorted = [...libResults].sort((a, b) => a.avgTimeMs - b.avgTimeMs);
  const data = sorted.map(r => ({
    parser: r.parser,
    time: r.avgTimeMs,
    language: r.language
  }));

  const spec = {
    $schema: 'https://vega.github.io/schema/vega-lite/v5.json',
    title: {
      text: `Parser Performance: ${libName}`,
      subtitle: 'Parsing Time (ms) - Lower is Better',
      fontSize: 18
    },
    width: 500,
    height: 250,
    data: { values: data },
    mark: 'bar',
    encoding: {
      y: {
        field: 'parser',
        type: 'nominal',
        title: null,
        sort: sorted.map(r => r.parser)
      },
      x: {
        field: 'time',
        type: 'quantitative',
        title: 'Parsing Time (ms)'
      },
      color: {
        field: 'parser',
        type: 'nominal',
        legend: null,
        scale: {
          domain: sorted.map(r => r.parser),
          range: sorted.map(r => parserColors[r.parser] || '#888888')
        }
      },
      tooltip: [
        { field: 'parser', title: 'Parser' },
        { field: 'time', title: 'Time (ms)', format: '.2f' },
        { field: 'language', title: 'Language' }
      ]
    },
    config: {
      background: 'white',
      view: { stroke: null }
    }
  };

  await renderChart(spec, outputPath);
}

async function createTypeScriptDetailedChart(results, outputPath) {
  if (!results['TypeScript Compiler']) return;

  const libResults = results['TypeScript Compiler'];
  const sorted = [...libResults].sort((a, b) => a.avgTimeMs - b.avgTimeMs);

  const timeData = sorted.map(r => ({ parser: r.parser, value: r.avgTimeMs, metric: 'Time (ms)' }));
  const throughputData = sorted.map(r => ({ parser: r.parser, value: r.throughputKbMs, metric: 'Throughput (KB/ms)' }));

  const spec = {
    $schema: 'https://vega.github.io/schema/vega-lite/v5.json',
    title: {
      text: 'TypeScript Compiler (8.8 MB) - Parser Performance',
      fontSize: 20
    },
    hconcat: [
      {
        title: { text: 'Parsing Time (Lower is Better)', fontSize: 14 },
        width: 350,
        height: 200,
        data: { values: timeData },
        mark: 'bar',
        encoding: {
          y: { field: 'parser', type: 'nominal', title: null, sort: sorted.map(r => r.parser) },
          x: { field: 'value', type: 'quantitative', title: 'Time (ms)' },
          color: {
            field: 'parser',
            legend: null,
            scale: {
              domain: sorted.map(r => r.parser),
              range: sorted.map(r => parserColors[r.parser] || '#888888')
            }
          }
        }
      },
      {
        title: { text: 'Throughput (Higher is Better)', fontSize: 14 },
        width: 350,
        height: 200,
        data: { values: throughputData },
        mark: 'bar',
        encoding: {
          y: { field: 'parser', type: 'nominal', title: null, sort: sorted.map(r => r.parser) },
          x: { field: 'value', type: 'quantitative', title: 'Throughput (KB/ms)' },
          color: {
            field: 'parser',
            legend: null,
            scale: {
              domain: sorted.map(r => r.parser),
              range: sorted.map(r => parserColors[r.parser] || '#888888')
            }
          }
        }
      }
    ],
    config: {
      background: 'white',
      view: { stroke: null }
    }
  };

  await renderChart(spec, outputPath);
}

async function main() {
  const scriptDir = __dirname;
  const projectRoot = path.dirname(scriptDir);
  const resultsDir = path.join(projectRoot, 'benchmark-results');
  const outputDir = path.join(projectRoot, 'benchmark-graphs');

  if (!fs.existsSync(resultsDir)) {
    console.error(`Error: Results directory not found: ${resultsDir}`);
    console.error('Run the benchmarks first with: ./scripts/run-all-benchmarks.sh');
    process.exit(1);
  }

  fs.mkdirSync(outputDir, { recursive: true });
  fs.mkdirSync(path.join(outputDir, 'per_library'), { recursive: true });

  const { java: javaFile, js: jsFile, rust: rustFile } = findLatestResults(resultsDir);

  console.log('Parsing benchmark results...');
  console.log(`  Java: ${javaFile}`);
  console.log(`  JavaScript: ${jsFile}`);
  console.log(`  Rust: ${rustFile}`);
  console.log();

  const javaResults = javaFile ? parseJavaResults(javaFile) : {};
  const jsResults = jsFile ? parseJavaScriptResults(jsFile) : {};
  const rustResults = rustFile ? parseRustResults(rustFile) : {};

  if (Object.keys(javaResults).length === 0 &&
      Object.keys(jsResults).length === 0 &&
      Object.keys(rustResults).length === 0) {
    console.error('Error: No benchmark results found!');
    process.exit(1);
  }

  const allResults = mergeResults(javaResults, jsResults, rustResults);

  console.log(`Found results for ${Object.keys(allResults).length} libraries:`);
  for (const lib of Object.keys(allResults).sort()) {
    const parsers = allResults[lib].map(r => r.parser);
    console.log(`  ${lib}: ${parsers.join(', ')}`);
  }
  console.log();

  console.log('Generating graphs...');

  await createTimeComparisonChart(allResults, path.join(outputDir, 'parsing_time_comparison.png'));
  await createThroughputComparisonChart(allResults, path.join(outputDir, 'throughput_comparison.png'));

  for (const lib of libraryOrder) {
    if (allResults[lib]) {
      const safeName = lib.toLowerCase().replace(/ /g, '_').replace(/\./g, '');
      await createPerLibraryChart(lib, allResults[lib], path.join(outputDir, 'per_library', `${safeName}_comparison.png`));
    }
  }

  await createTypeScriptDetailedChart(allResults, path.join(outputDir, 'typescript_detailed.png'));

  console.log();
  console.log(`All graphs saved to: ${outputDir}`);
}

main().catch(err => {
  console.error('Error:', err);
  process.exit(1);
});
