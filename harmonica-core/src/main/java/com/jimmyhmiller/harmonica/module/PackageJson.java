package com.jimmyhmiller.harmonica.module;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal package.json reader. We only need a small subset of the file:
 * {@code "type"}, {@code "main"}, {@code "exports"}, {@code "imports"}, and
 * {@code "name"}. Everything else is parsed-but-ignored (we still need a real
 * JSON parser because {@code "exports"} is a recursive object/array/string).
 *
 * <p>Hand-rolled because {@code harmonica-core} is zero-dependency.
 */
public final class PackageJson {

    /** "module" or "commonjs" — defaults to "commonjs" per Node when unset. */
    public final String type;
    /** Legacy entry-point. */
    public final String main;
    /** {@code "exports"} field — String, Map, or List. {@code null} when absent. */
    public final Object exports;
    /** {@code "imports"} field — Map or null. */
    public final Object imports;
    public final String name;
    /** Absolute path of the package directory (the dir containing package.json). */
    public final Path packageDir;

    PackageJson(Path packageDir, String type, String main, Object exports, Object imports, String name) {
        this.packageDir = packageDir;
        this.type = type;
        this.main = main;
        this.exports = exports;
        this.imports = imports;
        this.name = name;
    }

    public boolean isModuleType() {
        return "module".equals(type);
    }

    public static PackageJson read(Path file) throws IOException {
        String src = Files.readString(file);
        Object root = JsonParser.parse(src);
        if (!(root instanceof Map<?, ?> obj)) {
            throw new IOException("package.json root is not an object: " + file);
        }
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) obj;
        return new PackageJson(
            file.getParent(),
            asString(m.get("type")),
            asString(m.get("main")),
            m.get("exports"),
            m.get("imports"),
            asString(m.get("name"))
        );
    }

    private static String asString(Object o) {
        return o instanceof String s ? s : null;
    }

    // ============================================================
    //  JSON parser — recursive descent, returns Map / List / String /
    //  Double / Boolean / null. Just enough for package.json.
    // ============================================================

    static final class JsonParser {
        private final String s;
        private int i;
        private JsonParser(String s) { this.s = s; }

        static Object parse(String s) throws IOException {
            JsonParser p = new JsonParser(s);
            p.skipWs();
            Object v = p.value();
            p.skipWs();
            if (p.i != s.length()) throw p.err("trailing characters");
            return v;
        }

        private Object value() throws IOException {
            skipWs();
            if (i >= s.length()) throw err("unexpected end of input");
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't', 'f' -> bool();
                case 'n' -> nul();
                default -> number();
            };
        }

        private Map<String, Object> object() throws IOException {
            expect('{');
            Map<String, Object> out = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { i++; return out; }
            while (true) {
                skipWs();
                String k = string();
                skipWs();
                expect(':');
                Object v = value();
                out.put(k, v);
                skipWs();
                char c = next();
                if (c == ',') continue;
                if (c == '}') return out;
                throw err("expected , or } in object");
            }
        }

        private List<Object> array() throws IOException {
            expect('[');
            List<Object> out = new ArrayList<>();
            skipWs();
            if (peek() == ']') { i++; return out; }
            while (true) {
                out.add(value());
                skipWs();
                char c = next();
                if (c == ',') continue;
                if (c == ']') return out;
                throw err("expected , or ] in array");
            }
        }

        private String string() throws IOException {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw err("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw err("bad escape");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) throw err("bad unicode escape");
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw err("unknown escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Object number() throws IOException {
            int start = i;
            if (peek() == '-') i++;
            while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
            String t = s.substring(start, i);
            if (t.isEmpty()) throw err("expected number");
            try { return Double.parseDouble(t); }
            catch (NumberFormatException nfe) { throw err("bad number: " + t); }
        }

        private Boolean bool() throws IOException {
            if (s.startsWith("true", i))  { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            throw err("expected true/false");
        }

        private Object nul() throws IOException {
            if (s.startsWith("null", i)) { i += 4; return null; }
            throw err("expected null");
        }

        private void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }
        private char next() throws IOException {
            if (i >= s.length()) throw err("unexpected end of input");
            return s.charAt(i++);
        }
        private void expect(char c) throws IOException {
            if (i >= s.length() || s.charAt(i) != c) throw err("expected '" + c + "'");
            i++;
        }
        private IOException err(String msg) {
            return new IOException("JSON parse error at position " + i + ": " + msg);
        }
    }
}
