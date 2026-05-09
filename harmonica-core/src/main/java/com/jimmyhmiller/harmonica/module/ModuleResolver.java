package com.jimmyhmiller.harmonica.module;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves module specifiers against the filesystem. Two entry points:
 *
 * <ul>
 *   <li>{@link #resolveEsm} — Node ESM resolution (the algorithm at
 *       https://nodejs.org/api/esm.html#resolution-and-loading-algorithm).
 *   <li>{@link #resolveCjs} — CJS {@code Module._resolveFilename}.
 * </ul>
 *
 * <p>Both share package.json reading, conditional-exports walking, and the
 * {@code node_modules} traversal. They differ in extension handling and
 * which conditions are active by default ({@code "import"} vs {@code "require"}).
 *
 * <p><b>Not yet implemented</b> (each throws a clear error per project
 * convention against silent stubs):
 * <ul>
 *   <li>Subpath patterns in {@code "exports"}/{@code "imports"} (keys with {@code "*"}).
 *   <li>{@code "imports"} field with {@code "#"} specifiers.
 *   <li>{@code node:} builtins (throws "Cannot find module 'node:X'").
 * </ul>
 */
public final class ModuleResolver {

    public enum Format {
        ESM,        // .mjs or .js with package "type":"module"
        CJS,        // .cjs or .js with no/commonjs package "type"
        JSON,       // .json
        BUILTIN     // node: protocol — currently unsupported
    }

    public record Resolved(Path path, Format format) {}

    /** "import" + "node" + "default" — the active conditions for ESM resolution. */
    public static final Set<String> ESM_CONDITIONS = Set.of("import", "node", "default");
    /** "require" + "node" + "default" — the active conditions for CJS resolution. */
    public static final Set<String> CJS_CONDITIONS = Set.of("require", "node", "default");

    private ModuleResolver() {}

    // ============================================================
    //  ESM: ESM_RESOLVE(specifier, parentURL)
    // ============================================================

    public static Resolved resolveEsm(String specifier, Path parentFile) throws IOException {
        if (specifier.startsWith("node:")) {
            throw new ModuleNotFoundException(
                "Cannot find module '" + specifier + "' (Harmonica does not yet implement node: builtins)");
        }
        if (specifier.startsWith("#")) {
            return resolveHashImport(specifier, parentFile, ESM_CONDITIONS, /* esm */ true);
        }
        Path parentDir = parentFile.getParent();
        // Relative or absolute path.
        if (specifier.startsWith("./") || specifier.startsWith("../") || specifier.startsWith("/")
                || specifier.equals(".") || specifier.equals("..")) {
            Path raw = specifier.startsWith("/")
                ? Paths.get(specifier)
                : parentDir.resolve(specifier).normalize();
            // ESM_RESOLVE for relative does NOT do extension lookup nor /index.js
            // — Node requires the extension. But we add a small DX-affordance:
            // if exact path doesn't exist, try .js/.mjs (still strict by default
            // when pathExists succeeds first).
            Path file = resolveExistingEsmFile(raw);
            if (file == null) {
                throw new ModuleNotFoundException("Cannot find module '" + specifier + "' from '" + parentFile + "'");
            }
            return new Resolved(file, formatForFile(file));
        }
        // Bare specifier → PACKAGE_RESOLVE.
        return packageResolve(specifier, parentDir, ESM_CONDITIONS, /* esm */ true);
    }

    // ============================================================
    //  CJS: Module._resolveFilename
    // ============================================================

    public static Resolved resolveCjs(String specifier, Path parentFile) throws IOException {
        if (specifier.startsWith("node:")) {
            throw new ModuleNotFoundException(
                "Cannot find module '" + specifier + "' (Harmonica does not yet implement node: builtins)");
        }
        if (specifier.startsWith("#")) {
            return resolveHashImport(specifier, parentFile, CJS_CONDITIONS, /* esm */ false);
        }
        Path parentDir = parentFile.getParent();
        if (specifier.startsWith("./") || specifier.startsWith("../") || specifier.startsWith("/")
                || specifier.equals(".") || specifier.equals("..")) {
            Path raw = specifier.startsWith("/")
                ? Paths.get(specifier)
                : parentDir.resolve(specifier).normalize();
            Path file = loadAsFile(raw);
            if (file == null) file = loadAsDirectory(raw, CJS_CONDITIONS);
            if (file == null) {
                throw new ModuleNotFoundException("Cannot find module '" + specifier + "' from '" + parentFile + "'");
            }
            return new Resolved(file, formatForFile(file));
        }
        return packageResolve(specifier, parentDir, CJS_CONDITIONS, /* esm */ false);
    }

    // ============================================================
    //  PACKAGE_RESOLVE — bare specifier walking node_modules
    // ============================================================

    private static Resolved packageResolve(
            String specifier, Path startDir, Set<String> conditions, boolean esm) throws IOException {
        // Split "name/sub/path" → ("name", "./sub/path"). Scoped names start with "@".
        String name;
        String subpath;
        int firstSlash = specifier.indexOf('/');
        if (specifier.startsWith("@")) {
            int second = firstSlash < 0 ? -1 : specifier.indexOf('/', firstSlash + 1);
            if (second < 0) { name = specifier; subpath = "."; }
            else { name = specifier.substring(0, second); subpath = "." + specifier.substring(second); }
        } else if (firstSlash < 0) {
            name = specifier; subpath = ".";
        } else {
            name = specifier.substring(0, firstSlash);
            subpath = "." + specifier.substring(firstSlash);
        }

        // Walk up looking for node_modules/<name>/.
        Path cursor = startDir;
        while (cursor != null) {
            Path candidate = cursor.resolve("node_modules").resolve(name.replace('/', java.io.File.separatorChar));
            if (Files.isDirectory(candidate)) {
                Resolved r = resolvePackageEntry(candidate, subpath, conditions, esm);
                if (r != null) return r;
            }
            cursor = cursor.getParent();
        }
        throw new ModuleNotFoundException(
            "Cannot find package '" + name + "' imported from '" + startDir + "'");
    }

    /**
     * PACKAGE_IMPORTS_RESOLVE entry point — handle a {@code #}-prefixed
     * specifier by walking up from {@code parentFile} to the nearest
     * {@code package.json}, then matching against its {@code "imports"}
     * field. The resolved target may be a {@code ./}-relative path within
     * the importing package or a bare specifier (re-resolved as a package).
     */
    private static Resolved resolveHashImport(
            String specifier, Path parentFile, Set<String> conditions, boolean esm) throws IOException {
        Path dir = parentFile.getParent();
        PackageJson pj = nearestPackageJson(parentFile);
        if (pj == null || pj.imports == null) {
            throw new ModuleNotFoundException(
                "Cannot resolve imports specifier '" + specifier
                + "': no enclosing package.json with an 'imports' field (from " + parentFile + ")");
        }
        String resolved = packageImportsResolve(specifier, pj.imports, conditions);
        if (resolved == null) {
            throw new ModuleNotFoundException(
                "Specifier '" + specifier + "' is not defined by 'imports' in " + pj.packageDir + "/package.json");
        }
        // The resolved target may be:
        //   "./relative/path.js" — relative to the package dir.
        //   "lodash" / "lodash/foo" — a bare specifier to re-resolve.
        if (resolved.startsWith("./") || resolved.startsWith("/")) {
            Path target = resolved.startsWith("/")
                ? java.nio.file.Paths.get(resolved)
                : pj.packageDir.resolve(stripLeadingDotSlash(resolved)).normalize();
            Path file = esm ? resolveExistingEsmFile(target) : loadAsFile(target);
            if (file == null && !esm) file = loadAsDirectory(target, conditions);
            if (file == null) {
                throw new ModuleNotFoundException(
                    "imports target for '" + specifier + "' does not exist: " + target);
            }
            return new Resolved(file, formatForFile(file, pj));
        }
        // Bare specifier — re-resolve as a package, starting from the
        // importing package's directory so the lookup walks node_modules.
        return packageResolve(resolved, pj.packageDir, conditions, esm);
    }

    /** Resolve {@code subpath} inside an already-located package directory. */
    private static Resolved resolvePackageEntry(
            Path packageDir, String subpath, Set<String> conditions, boolean esm) throws IOException {
        Path pjFile = packageDir.resolve("package.json");
        PackageJson pj = Files.isRegularFile(pjFile) ? PackageJson.read(pjFile) : null;

        // 1. exports field, if present, is authoritative.
        if (pj != null && pj.exports != null) {
            String resolved = packageExportsResolve(subpath, pj.exports, conditions);
            if (resolved == null) {
                throw new ModuleNotFoundException(
                    "Package subpath '" + subpath + "' is not defined by 'exports' in " + pjFile);
            }
            // Resolved is a string like "./dist/index.js" — relative to packageDir.
            Path target = packageDir.resolve(stripLeadingDotSlash(resolved)).normalize();
            if (!Files.isRegularFile(target)) {
                throw new ModuleNotFoundException(
                    "Package subpath target '" + resolved + "' does not exist: " + target);
            }
            return new Resolved(target, formatForFile(target, pj));
        }

        // 2. No exports — fall back to main / index.
        if (subpath.equals(".")) {
            if (pj != null && pj.main != null) {
                Path mainPath = packageDir.resolve(stripLeadingDotSlash(pj.main)).normalize();
                Path file = esm ? resolveExistingEsmFile(mainPath) : loadAsFile(mainPath);
                if (file == null && !esm) file = loadAsDirectory(mainPath, conditions);
                if (file != null) return new Resolved(file, formatForFile(file, pj));
            }
            // index.js / index.json
            Path idx = loadAsDirectory(packageDir, conditions);
            if (idx != null) return new Resolved(idx, formatForFile(idx, pj));
            throw new ModuleNotFoundException(
                "Cannot find main entry of package: " + packageDir);
        } else {
            Path target = packageDir.resolve(stripLeadingDotSlash(subpath)).normalize();
            Path file = esm ? resolveExistingEsmFile(target) : loadAsFile(target);
            if (file == null && !esm) file = loadAsDirectory(target, conditions);
            if (file == null) {
                throw new ModuleNotFoundException(
                    "Cannot find subpath '" + subpath + "' in package " + packageDir);
            }
            return new Resolved(file, formatForFile(file, pj));
        }
    }

    // ============================================================
    //  PACKAGE_EXPORTS_RESOLVE
    // ============================================================

    /**
     * Resolve a subpath against the {@code "exports"} field. Returns the target
     * string (relative to the package dir) or {@code null} if no match.
     *
     * <p>Accepts the three shapes Node supports at the top level:
     * <ul>
     *   <li>String — only valid when subpath == ".".
     *   <li>Object whose keys are subpaths ({@code "."}, {@code "./foo"}).
     *   <li>Object whose keys are conditions ({@code "import"}, {@code "default"}).
     *   <li>Array — first viable target wins.
     * </ul>
     */
    static String packageExportsResolve(String subpath, Object exports, Set<String> conditions) {
        // Top-level string: only matches subpath ".".
        if (exports instanceof String s) {
            if (subpath.equals(".")) return s;
            return null;
        }
        if (exports instanceof List<?> arr) {
            return resolveTarget(subpath, arr, conditions, /* patternMatch */ null);
        }
        if (exports instanceof Map<?, ?> mapRaw) {
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) mapRaw;
            // Determine whether this is a conditions-map or a subpath-map.
            // Node's rule: conditions-map iff no key starts with ".".
            boolean isConditionsMap = map.keySet().stream().noneMatch(k -> k.startsWith("."));
            if (isConditionsMap) {
                if (!subpath.equals(".")) return null;
                return resolveConditionalMap(subpath, map, conditions, /* patternMatch */ null);
            }
            // Subpath-map: try exact match first.
            if (map.containsKey(subpath)) {
                Object target = map.get(subpath);
                return resolveTarget(subpath, target, conditions, /* patternMatch */ null);
            }
            // Then pattern keys, picking the most specific (PATTERN_KEY_COMPARE).
            return resolveSubpathPattern(subpath, map, conditions, "./");
        }
        return null;
    }

    /**
     * PACKAGE_IMPORTS_RESOLVE — resolve a {@code #}-prefixed specifier against
     * the importing package's {@code "imports"} field. Returns the resolved
     * target string (which may itself be a {@code ./} relative path or a bare
     * package specifier the caller must resolve recursively), or {@code null}
     * if no match.
     */
    static String packageImportsResolve(String specifier, Object importsField, Set<String> conditions) {
        if (importsField == null) return null;
        if (!(importsField instanceof Map<?, ?> mapRaw)) return null;
        @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) mapRaw;
        if (map.containsKey(specifier)) {
            return resolveTarget(specifier, map.get(specifier), conditions, /* patternMatch */ null);
        }
        return resolveSubpathPattern(specifier, map, conditions, "#");
    }

    /**
     * Walk the subpath/imports map looking for the most-specific pattern key
     * that matches {@code subpath}, then perform PACKAGE_TARGET_RESOLVE with
     * the captured wildcard substituted in.
     *
     * <p>PATTERN_KEY_COMPARE: the winning key is the one whose
     * {@code patternBase} (text before {@code *}) is longest; ties broken by
     * the longest {@code patternTrailer} (text after {@code *}). This makes
     * {@code "./fp/*"} win over {@code "./*"} for the subpath {@code "./fp/x"}.
     *
     * @param keyPrefix the required prefix for valid pattern keys —
     *                  {@code "./"} for exports, {@code "#"} for imports.
     */
    private static String resolveSubpathPattern(
            String subpath, Map<String, Object> map, Set<String> conditions, String keyPrefix) {
        String bestKey = null;
        String bestMatch = null;
        int bestBaseLen = -1;
        int bestTrailerLen = -1;
        for (String key : map.keySet()) {
            int star = key.indexOf('*');
            if (star < 0) continue;
            if (!key.startsWith(keyPrefix)) continue;
            // Spec restriction: a pattern key has at most one `*`. Multi-star
            // keys are illegal in Node and we surface that loudly rather than
            // silently mismatch.
            if (key.indexOf('*', star + 1) >= 0) {
                throw new IllegalArgumentException(
                    "Invalid pattern key (more than one '*'): " + key);
            }
            String base = key.substring(0, star);
            String trailer = key.substring(star + 1);
            if (!subpath.startsWith(base)) continue;
            if (!subpath.endsWith(trailer)) continue;
            if (subpath.length() < base.length() + trailer.length()) continue;
            int baseLen = base.length();
            int trailerLen = trailer.length();
            if (baseLen > bestBaseLen
                    || (baseLen == bestBaseLen && trailerLen > bestTrailerLen)) {
                bestKey = key;
                bestMatch = subpath.substring(baseLen, subpath.length() - trailerLen);
                bestBaseLen = baseLen;
                bestTrailerLen = trailerLen;
            }
        }
        if (bestKey == null) return null;
        Object target = map.get(bestKey);
        return resolveTarget(subpath, target, conditions, bestMatch);
    }

    /**
     * PACKAGE_TARGET_RESOLVE. {@code patternMatch} is the captured wildcard
     * text from a pattern-key match (substituted for every {@code *} in the
     * resolved leaf string), or {@code null} for exact-match resolution
     * (in which case any {@code *} in the target is treated literally —
     * Node's behavior).
     */
    private static String resolveTarget(String subpath, Object target, Set<String> conditions,
                                        String patternMatch) {
        if (target == null) return null;
        if (target instanceof String s) {
            return patternMatch == null ? s : s.replace("*", patternMatch);
        }
        if (target instanceof Map<?, ?> mapRaw) {
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) mapRaw;
            return resolveConditionalMap(subpath, map, conditions, patternMatch);
        }
        if (target instanceof List<?> arr) {
            for (Object alt : arr) {
                String r = resolveTarget(subpath, alt, conditions, patternMatch);
                if (r != null) return r;
            }
            return null;
        }
        return null;
    }

    private static String resolveConditionalMap(String subpath, Map<String, Object> map,
                                                Set<String> conditions, String patternMatch) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = e.getKey();
            if (key.equals("default") || conditions.contains(key)) {
                String r = resolveTarget(subpath, e.getValue(), conditions, patternMatch);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ============================================================
    //  Helpers
    // ============================================================

    /**
     * ESM relative-resolution: prefer the literal path, fall back to .js / .mjs
     * tries and /index.js for directories. Strict ESM forbids the fallbacks but
     * Node has loosened this and most projects rely on it.
     */
    private static Path resolveExistingEsmFile(Path p) {
        if (Files.isRegularFile(p)) return p;
        for (String ext : List.of(".js", ".mjs", ".cjs", ".json")) {
            Path withExt = Paths.get(p.toString() + ext);
            if (Files.isRegularFile(withExt)) return withExt;
        }
        if (Files.isDirectory(p)) {
            for (String idx : List.of("index.js", "index.mjs", "index.cjs", "index.json")) {
                Path withIdx = p.resolve(idx);
                if (Files.isRegularFile(withIdx)) return withIdx;
            }
        }
        return null;
    }

    /** CJS LOAD_AS_FILE(X). */
    private static Path loadAsFile(Path p) {
        if (Files.isRegularFile(p)) return p;
        for (String ext : List.of(".js", ".json", ".mjs", ".cjs")) {
            Path withExt = Paths.get(p.toString() + ext);
            if (Files.isRegularFile(withExt)) return withExt;
        }
        return null;
    }

    /** CJS LOAD_AS_DIRECTORY(X). */
    private static Path loadAsDirectory(Path p, Set<String> conditions) throws IOException {
        if (!Files.isDirectory(p)) return null;
        Path pjFile = p.resolve("package.json");
        if (Files.isRegularFile(pjFile)) {
            PackageJson pj = PackageJson.read(pjFile);
            if (pj.exports != null) {
                String r = packageExportsResolve(".", pj.exports, conditions);
                if (r != null) {
                    Path target = p.resolve(stripLeadingDotSlash(r)).normalize();
                    if (Files.isRegularFile(target)) return target;
                }
            }
            if (pj.main != null) {
                Path mainPath = p.resolve(stripLeadingDotSlash(pj.main)).normalize();
                Path mf = loadAsFile(mainPath);
                if (mf != null) return mf;
                if (Files.isDirectory(mainPath)) {
                    Path idx = loadAsIndex(mainPath);
                    if (idx != null) return idx;
                }
            }
        }
        return loadAsIndex(p);
    }

    private static Path loadAsIndex(Path dir) {
        for (String idx : List.of("index.js", "index.json", "index.mjs", "index.cjs")) {
            Path withIdx = dir.resolve(idx);
            if (Files.isRegularFile(withIdx)) return withIdx;
        }
        return null;
    }

    private static String stripLeadingDotSlash(String s) {
        if (s.startsWith("./")) return s.substring(2);
        return s;
    }

    /** ESM_FILE_FORMAT(url) — pick format from extension + nearest package.json. */
    public static Format formatForFile(Path file) throws IOException {
        return formatForFile(file, nearestPackageJson(file));
    }

    static Format formatForFile(Path file, PackageJson pj) {
        String name = file.getFileName().toString();
        if (name.endsWith(".mjs"))  return Format.ESM;
        if (name.endsWith(".cjs"))  return Format.CJS;
        if (name.endsWith(".json")) return Format.JSON;
        if (name.endsWith(".js")) {
            return (pj != null && pj.isModuleType()) ? Format.ESM : Format.CJS;
        }
        // Unknown extension — treat as CJS (Node behavior for explicit-loader cases).
        return Format.CJS;
    }

    /** Walk up directories from {@code file} looking for the nearest package.json. */
    public static PackageJson nearestPackageJson(Path file) throws IOException {
        Path dir = Files.isDirectory(file) ? file : file.getParent();
        while (dir != null) {
            Path pj = dir.resolve("package.json");
            if (Files.isRegularFile(pj)) {
                try { return PackageJson.read(pj); }
                catch (IOException io) { /* unreadable — keep walking */ }
            }
            dir = dir.getParent();
        }
        return null;
    }

    public static final class ModuleNotFoundException extends IOException {
        public ModuleNotFoundException(String msg) { super(msg); }
    }
}
