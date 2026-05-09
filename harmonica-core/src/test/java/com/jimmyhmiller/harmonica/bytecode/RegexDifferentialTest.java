package com.jimmyhmiller.harmonica.bytecode;

import com.jimmyhmiller.harmonica.Parser;
import com.jimmyhmiller.harmonica.ast.Program;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Differential regex tests: run the same JS regex script through both
 * harmonica and Node.js, compare {@code JSON.stringify(result)}. Any
 * divergence is flagged.
 *
 * <p>Each row in {@link #CORPUS} is a self-contained JS expression that
 * yields a JSON-stringifiable value (string, number, array, object, null,
 * etc.). The corpus is hand-curated to exercise the edge cases real
 * libraries (lodash, jQuery, regex-heavy parsing) hit at runtime.
 *
 * <p>Skipped silently if {@code node} isn't on PATH; not gated, so it
 * runs in normal {@code mvn test}. Each row has a 5-second timeout.
 *
 * <p>Goal: catch translator bugs like the {@code [} -inside-class issue
 * that broke lodash by 22x before any benchmark would notice.
 */
class RegexDifferentialTest {

    /** Each entry is `{ id, jsExpression }`. The expression must produce a
     *  JSON-stringifiable value. */
    private record Row(String id, String js) {}

    private static final Row[] CORPUS = {
        // === Char class literal/escape edge cases ===
        new Row("class-with-bracket-paren-brace",
            "'function hasOwnProperty() { [native code] }'.replace(/[\\\\^$.*+?()[\\]{}|]/g, '\\\\$&')"),
        new Row("class-only-special-chars",
            "'a.b*c?d+e'.replace(/[.*+?]/g, 'X')"),
        new Row("class-with-escaped-backslash",
            "'a\\\\b'.replace(/[\\\\\\\\]/g, 'X')"),
        new Row("class-negated",
            "'abc123'.replace(/[^0-9]/g, '_')"),
        new Row("class-range",
            "'Hello World'.replace(/[a-z]/g, '_')"),
        new Row("class-with-bracket-as-literal",
            "'a[b]c'.replace(/[\\[\\]]/g, '_')"),
        new Row("class-with-dash-at-end",
            "'a-b-c'.replace(/[a-]/g, 'X')"),
        new Row("class-empty-after-newline",
            "'a\\nb'.split(/\\n/).join('|')"),

        // === Escapes ===
        new Row("backslash-zero",
            "'a\\u0000b'.replace(/\\0/g, '|').length"),
        new Row("digit-class",
            "'a1b22c333'.match(/\\d+/g)"),
        new Row("non-digit-class",
            "'1a22b333c'.match(/\\D+/g)"),
        new Row("word-class",
            "'foo-bar baz'.match(/\\w+/g)"),
        new Row("non-word-class",
            "'foo-bar baz'.match(/\\W+/g)"),
        new Row("whitespace-class",
            "'a b\\tc\\nd'.match(/\\s+/g)"),
        new Row("word-boundary",
            "'foo bar foo'.match(/\\bfoo\\b/g)"),

        // === Quantifiers ===
        new Row("greedy-star",      "'aaa'.match(/a*/)"),
        new Row("lazy-star",        "'aaa'.match(/a*?/)"),
        new Row("greedy-plus",      "'aaa'.match(/a+/)"),
        new Row("lazy-plus",        "'aaa'.match(/a+?/)"),
        new Row("optional",         "'aaa'.match(/ab?/)"),
        new Row("range-quantifier", "'aaaaa'.match(/a{2,3}/)"),
        new Row("exact-quantifier", "'aaaaa'.match(/a{4}/)"),

        // === Groups ===
        new Row("capture-group",    "'foobar'.match(/(foo)(bar)/)"),
        new Row("non-capture-group","'foobar'.match(/(?:foo)(bar)/)"),
        new Row("named-group",      "(function(){var m='foobar'.match(/(?<x>foo)/);return m && m.groups && m.groups.x;})()"),
        new Row("backref",          "'aabb'.match(/(.)\\1/)"),
        new Row("alternation",      "'foo'.match(/foo|bar/)"),
        new Row("alternation-grp",  "'aXbY'.match(/(a|b)([XY])/g)"),

        // === Anchors / lookaround ===
        new Row("anchor-start",     "'fooBar'.match(/^foo/) ? 'yes' : 'no'"),
        new Row("anchor-end",       "'fooBar'.match(/Bar$/) ? 'yes' : 'no'"),
        new Row("lookahead",        "'foo123'.match(/foo(?=\\d)/)"),
        new Row("negative-lookahead","'fooBar'.match(/foo(?!\\d)/)"),

        // === Flags ===
        new Row("global-flag",      "'aaaa'.match(/a/g).length"),
        new Row("ignore-case",      "'AbCdE'.replace(/[a-c]/gi, 'X')"),
        new Row("multiline-anchor", "'foo\\nbar\\nfoo'.match(/^foo/gm)"),
        new Row("dotall",           "'a\\nb'.match(/a.b/s)"),

        // === replace specifics ===
        new Row("replace-dollar-amp", "'hello'.replace(/l/g, '<$&>')"),
        new Row("replace-dollar-dollar","'hello'.replace(/l/g, '$$')"),
        new Row("replace-dollar-num", "'foobar'.replace(/(foo)(bar)/, '$2$1')"),
        new Row("replace-fn-callback",
            "'hello'.replace(/[el]/g, function(m){ return m.toUpperCase(); })"),
        new Row("replace-empty-pattern", "'abc'.replace(/^/, 'X')"),
        new Row("replace-no-match",  "'abc'.replace(/xyz/, 'Q')"),

        // === split ===
        new Row("split-simple",      "'a,b,c'.split(',')"),
        new Row("split-regex",       "'a, b ,c'.split(/\\s*,\\s*/)"),
        new Row("split-limit",       "'a,b,c,d'.split(',', 2)"),

        // === RegExp instance + properties ===
        new Row("regexp-source",     "(/foo/i).source"),
        new Row("regexp-flags",      "(/foo/gim).flags.split('').sort().join('')"),
        new Row("regexp-test",       "(/^[0-9]+$/).test('12345')"),
        new Row("regexp-test-fail",  "(/^[0-9]+$/).test('12a45')"),
        new Row("regexp-exec",       "(/(\\d+)/).exec('foo42bar')"),

        // === Real-world patterns ===
        new Row("trim-via-regex",    "'   hello   '.replace(/^\\s+|\\s+$/g, '')"),
        new Row("html-tag-strip",    "'<b>hi</b><i>x</i>'.replace(/<[^>]+>/g, '')"),
        new Row("camelize",          "'foo-bar-baz'.replace(/-(.)/g, function(_, c){return c.toUpperCase();})"),
        new Row("split-words",       "'one two   three'.split(/\\s+/)"),
        new Row("escape-regex-chars",
            "'a.b*c'.replace(/[.*+?^${}()|[\\]\\\\]/g, '\\\\$&')"),
        // === Tricky char-class patterns (the lodash-killer was here) ===
        new Row("nested-bracket-literal",
            "'a[b]c'.replace(/[\\[\\]]/g, '_')"),
        new Row("class-with-caret-not-first",
            "'a^b'.replace(/[a^]/g, '_')"),
        new Row("class-dash-first-and-last",
            "'-a-b-'.replace(/[-a-]/g, '_')"),
        new Row("posix-like-class",
            "'Hello123'.split(/[^a-zA-Z]+/).join('|')"),
        new Row("class-with-quote-and-paren",
            "'foo\\'bar(baz)'.replace(/[\"'()]/g, '_')"),

        // === Quantifier interactions ===
        new Row("greedy-vs-lazy-in-replace",
            "'<a><b>'.replace(/<(.+)>/g, '[$1]')"),
        new Row("greedy-vs-lazy-in-replace-lazy",
            "'<a><b>'.replace(/<(.+?)>/g, '[$1]')"),

        // === sticky / lastIndex ===
        new Row("sticky-flag",
            "(function(){var r=/foo/y; r.lastIndex=3; return r.test('   foo');})()"),
        new Row("global-lastIndex-after-exec",
            "(function(){var r=/foo/g; r.exec('foofoo'); return r.lastIndex;})()"),

        // === Unicode and code point edge cases ===
        new Row("unicode-flag-class",
            "'\\u00e9'.replace(/\\u00e9/g, '_')"),
        new Row("dot-does-not-match-newline",
            "'a\\nb'.replace(/./g, 'X')"),
        new Row("dot-with-s-flag",
            "'a\\nb'.replace(/./gs, 'X')"),

        // === replace replacement-template completeness ===
        new Row("replace-tick",
            "'foobar'.replace(/foo/, '$`')"),
        new Row("replace-quote",
            "'foobar'.replace(/foo/, '$\\'')"),
        new Row("replace-out-of-range-group",
            "'foo'.replace(/foo/, '$5')"),

        // === split edge cases ===
        new Row("split-empty-string",
            "''.split(',')"),
        new Row("split-no-match",
            "'abc'.split('x')"),
        new Row("split-by-empty-regex",
            "'abc'.split(/(?:)/)"),
        new Row("split-zero-limit",
            "'a,b'.split(',', 0)"),

        // === match without g vs with g ===
        new Row("match-no-g-has-index",
            "(function(){var m='foo123bar'.match(/(\\d+)/); return [m[0], m[1], m.index];})()"),
        new Row("match-g-flat",
            "'a1b22c333'.match(/\\d+/g)"),

        // === RegExp object methods ===
        new Row("regex-toString",
            "(/abc/gi).toString()"),
        new Row("regex-source-special",
            "(/[\\\\\\\\^]/).source"),

        // === Real-world snippets ===
        // Mirrors lodash's getNative regex chain that triggered the original bug.
        new Row("lodash-native-detect",
            "var hop = Object.prototype.hasOwnProperty;" +
            "var raw = Function.prototype.toString.call(hop);" +
            "var pat = '^' + raw.replace(/[\\\\^$.*+?()[\\]{}|]/g, '\\\\$&')" +
                ".replace(/hasOwnProperty|(function).*?(?=\\\\\\()| for .+?(?=\\\\\\])/g, '$1.*?') + '$';" +
            "new RegExp(pat).test(Function.prototype.toString.call(Map))"),
        new Row("kebab-to-camel",
            "'background-color-rgb'.replace(/-([a-z])/g, function(_, c){ return c.toUpperCase(); })"),
        new Row("normalize-newlines",
            "'a\\r\\nb\\nc\\rd'.replace(/\\r\\n|\\r/g, '\\n')"),
        new Row("strip-comments",
            "'/* a */ b /* c */'.replace(/\\/\\*[\\s\\S]*?\\*\\//g, '')"),
        new Row("number-format",
            "'1234567'.replace(/\\B(?=(\\d{3})+(?!\\d))/g, ',')"),
        new Row("uuid-like-test",
            "/^[0-9a-f]{8}-[0-9a-f]{4}$/.test('a1b2c3d4-e5f6')"),
        new Row("base64-like",
            "/^[A-Za-z0-9+/]+={0,2}$/.test('SGVsbG8=')"),
    };

    @Test
    void differentialAgainstNode() throws Exception {
        if (!nodeAvailable()) {
            System.out.println("[diff] node not on PATH — skipping");
            return;
        }

        List<String> failures = new ArrayList<>();
        int pass = 0, skip = 0;
        for (Row row : CORPUS) {
            // Wrap in JSON.stringify so we get a stable byte-equal comparison.
            String js = "JSON.stringify((function(){return " + row.js + ";})())";
            String nodeOut, harmOut;
            try {
                nodeOut = runNode(js);
            } catch (Throwable t) {
                skip++;
                System.out.println("[diff/skip] " + row.id + " — node: " + t.getMessage());
                continue;
            }
            try {
                harmOut = runHarm(js);
            } catch (Throwable t) {
                failures.add(row.id + " — harmonica threw: " + t.getMessage()
                    + " (node returned: " + nodeOut + ")");
                continue;
            }
            if (java.util.Objects.equals(nodeOut, harmOut)) {
                pass++;
            } else {
                failures.add(row.id + "\n    node: " + nodeOut + "\n    harm: " + harmOut);
            }
        }

        System.out.println();
        System.out.printf("=== Regex differential vs Node.js ===%n");
        System.out.printf("Total: %d  Pass: %d  Fail: %d  Skip: %d%n",
            CORPUS.length, pass, failures.size(), skip);
        if (!failures.isEmpty()) {
            System.out.println();
            System.out.println("Divergences:");
            for (String f : failures) System.out.println("  " + f);
            // Loud failure so CI catches it.
            org.junit.jupiter.api.Assertions.fail(
                "Regex differential test: " + failures.size() + " divergence(s) vs Node — see stdout");
        }
    }

    private static boolean nodeAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("node", "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static String runNode(String js) throws Exception {
        // node -e prints the expression result if you `console.log(...)`.
        ProcessBuilder pb = new ProcessBuilder("node", "-e",
            "process.stdout.write(String(" + js + "))");
        pb.redirectErrorStream(false);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            int c;
            while ((c = r.read()) >= 0) out.append((char) c);
        }
        StringBuilder err = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            int c;
            while ((c = r.read()) >= 0) err.append((char) c);
        }
        if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new RuntimeException("node timed out");
        }
        if (p.exitValue() != 0) {
            throw new RuntimeException("node exit " + p.exitValue() + ": " + err);
        }
        return out.toString();
    }

    private static String runHarm(String js) {
        Program ast = Parser.parse(js);
        Executable exe = Generator.generate(ast);
        Object result = Interpreter.interpret(exe, new Object[0], 64);
        if (result == null) return "null";
        if (result == Undefined.VALUE) return "undefined";
        return result.toString();
    }
}
