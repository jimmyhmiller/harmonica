#!/usr/bin/env node

const fs = require('fs');
const path = require('path');
const acorn = require('acorn');
const babelParser = require('@babel/parser');

const TEST262_DIR = path.join(__dirname, '..', 'test-oracles', 'test262', 'test');
const CACHE_DIR = path.join(__dirname, '..', 'test-oracles', 'test262-cache');

// Categories we skip in the cache generator
const SKIP_PATTERNS = ['/staging/', '/annexB/', '/decorator/', 'accessor'];

// Parse Test262 frontmatter
function parseFrontmatter(source) {
    const match = source.match(/\/\*---\n([\s\S]*?)\n---\*\//);
    if (!match) return { isModule: false, isNegative: false };

    const yaml = match[1];
    const result = { isModule: false, isNegative: false };

    // Check for module flag
    if (/\bmodule\b/.test(yaml)) {
        result.isModule = true;
    }

    // Check for negative parse tests
    if (/^negative:/m.test(yaml) && /phase:\s*parse/m.test(yaml)) {
        result.isNegative = true;
    }

    return result;
}

// Collect skipped files
function collectSkippedFiles(dir, files = []) {
    const entries = fs.readdirSync(dir);

    for (const entry of entries) {
        const fullPath = path.join(dir, entry);
        const stat = fs.statSync(fullPath);

        if (stat.isDirectory()) {
            collectSkippedFiles(fullPath, files);
        } else if (entry.endsWith('.js')) {
            // Check if this file would be skipped by our cache generator
            const isSkipped = SKIP_PATTERNS.some(pattern => fullPath.includes(pattern));
            if (isSkipped) {
                files.push(fullPath);
            }
        }
    }

    return files;
}

// Try parsing with Acorn
function tryAcorn(source, isModule) {
    try {
        acorn.parse(source, {
            ecmaVersion: 2025,
            locations: true,
            sourceType: isModule ? 'module' : 'script'
        });
        return { success: true };
    } catch (e) {
        return { success: false, error: e.message };
    }
}

// Try parsing with Babel (ESTree mode)
function tryBabel(source, isModule) {
    try {
        babelParser.parse(source, {
            sourceType: isModule ? 'module' : 'script',
            plugins: [
                'estree',
                ['decorators', { decoratorsBeforeExport: true }],
                'decoratorAutoAccessors',
                'classProperties',
                'classPrivateProperties',
                'classPrivateMethods',
                'classStaticBlock',
                'importMeta',
                'dynamicImport',
                'exportDefaultFrom',
                'exportNamespaceFrom',
                'bigInt',
                'nullishCoalescingOperator',
                'optionalChaining',
                'asyncGenerators',
                'objectRestSpread',
            ]
        });
        return { success: true };
    } catch (e) {
        return { success: false, error: e.message };
    }
}

console.log('Verifying skipped tests...');
console.log('Scanning:', TEST262_DIR);

const skippedFiles = collectSkippedFiles(TEST262_DIR);
console.log(`Found ${skippedFiles.length} files in skipped categories\n`);

// Categorize results
const results = {
    bothFail: [],
    acornFailBabelPass: [],
    bothPass: [],
    negativeTests: []
};

let processed = 0;
for (const filePath of skippedFiles) {
    processed++;
    if (processed % 100 === 0) {
        process.stdout.write(`\rProcessed ${processed}/${skippedFiles.length}...`);
    }

    const source = fs.readFileSync(filePath, 'utf-8');
    const frontmatter = parseFrontmatter(source);
    const relativePath = path.relative(TEST262_DIR, filePath);

    // Skip negative tests - they're supposed to fail
    if (frontmatter.isNegative) {
        results.negativeTests.push(relativePath);
        continue;
    }

    const acornResult = tryAcorn(source, frontmatter.isModule);
    const babelResult = tryBabel(source, frontmatter.isModule);

    if (!acornResult.success && babelResult.success) {
        results.acornFailBabelPass.push({
            file: relativePath,
            acornError: acornResult.error
        });
    } else if (!acornResult.success && !babelResult.success) {
        results.bothFail.push({
            file: relativePath,
            acornError: acornResult.error,
            babelError: babelResult.error
        });
    } else if (acornResult.success && babelResult.success) {
        results.bothPass.push(relativePath);
    }
}

console.log('\n\n=== Results ===\n');

console.log(`Negative tests (expected to fail): ${results.negativeTests.length}`);
console.log(`Both Acorn & Babel pass: ${results.bothPass.length}`);
console.log(`Acorn fails, Babel passes: ${results.acornFailBabelPass.length}`);
console.log(`Both fail: ${results.bothFail.length}`);

if (results.bothPass.length > 0) {
    console.log('\n--- Files that BOTH parsers can parse (should have cache!) ---');
    results.bothPass.slice(0, 20).forEach(f => console.log('  ' + f));
    if (results.bothPass.length > 20) {
        console.log(`  ... and ${results.bothPass.length - 20} more`);
    }
}

if (results.acornFailBabelPass.length > 0) {
    console.log('\n--- Acorn fails but Babel succeeds (could use Babel for cache) ---');

    // Group by error type
    const errorGroups = {};
    for (const item of results.acornFailBabelPass) {
        const errorType = item.acornError.split(' ')[0];
        if (!errorGroups[errorType]) errorGroups[errorType] = [];
        errorGroups[errorType].push(item);
    }

    for (const [errorType, items] of Object.entries(errorGroups)) {
        console.log(`\n  [${items.length}] ${errorType}:`);
        items.slice(0, 3).forEach(item => {
            console.log(`    ${item.file}`);
            console.log(`      Acorn: ${item.acornError.substring(0, 80)}`);
        });
        if (items.length > 3) {
            console.log(`    ... and ${items.length - 3} more`);
        }
    }
}

if (results.bothFail.length > 0) {
    console.log('\n--- Both parsers fail (truly unsupported syntax) ---');
    results.bothFail.slice(0, 10).forEach(item => {
        console.log(`  ${item.file}`);
        console.log(`    Acorn: ${item.acornError.substring(0, 60)}`);
        console.log(`    Babel: ${item.babelError.substring(0, 60)}`);
    });
    if (results.bothFail.length > 10) {
        console.log(`  ... and ${results.bothFail.length - 10} more`);
    }
}

// Summary
console.log('\n=== Summary ===');
const total = skippedFiles.length;
const canTest = results.bothPass.length + results.acornFailBabelPass.length;
console.log(`Total skipped files: ${total}`);
console.log(`Could be tested with Babel: ${canTest} (${(canTest * 100 / total).toFixed(1)}%)`);
console.log(`Truly unsupported by both: ${results.bothFail.length + results.negativeTests.length}`);
