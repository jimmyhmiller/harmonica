package com.jimmyhmiller.harmonica.module;

import com.jimmyhmiller.harmonica.Parser;
import com.jimmyhmiller.harmonica.ast.ExportAllDeclaration;
import com.jimmyhmiller.harmonica.ast.ExportNamedDeclaration;
import com.jimmyhmiller.harmonica.ast.ExportSpecifier;
import com.jimmyhmiller.harmonica.ast.Identifier;
import com.jimmyhmiller.harmonica.ast.ImportDeclaration;
import com.jimmyhmiller.harmonica.ast.ImportDefaultSpecifier;
import com.jimmyhmiller.harmonica.ast.ImportNamespaceSpecifier;
import com.jimmyhmiller.harmonica.ast.ImportSpecifier;
import com.jimmyhmiller.harmonica.ast.Literal;
import com.jimmyhmiller.harmonica.ast.Node;
import com.jimmyhmiller.harmonica.ast.Program;
import com.jimmyhmiller.harmonica.ast.Statement;
import com.jimmyhmiller.harmonica.ast.VariableDeclaration;
import com.jimmyhmiller.harmonica.ast.FunctionDeclaration;
import com.jimmyhmiller.harmonica.ast.ClassDeclaration;
import com.jimmyhmiller.harmonica.ast.VariableDeclarator;
import com.jimmyhmiller.harmonica.ast.Pattern;
import com.jimmyhmiller.harmonica.bytecode.AbruptCompletion;
import com.jimmyhmiller.harmonica.bytecode.AbstractOps;
import com.jimmyhmiller.harmonica.bytecode.Accessor;
import com.jimmyhmiller.harmonica.bytecode.Executable;
import com.jimmyhmiller.harmonica.bytecode.Generator;
import com.jimmyhmiller.harmonica.bytecode.Interpreter;
import com.jimmyhmiller.harmonica.bytecode.JSFunction;
import com.jimmyhmiller.harmonica.bytecode.JSObject;
import com.jimmyhmiller.harmonica.bytecode.NativeBody;
import com.jimmyhmiller.harmonica.bytecode.Realm;
import com.jimmyhmiller.harmonica.bytecode.Undefined;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves and loads ES modules and CommonJS modules. One instance per CLI
 * invocation; modules are cached by canonical absolute path so import-cycles
 * and CJS {@code require} share the same module instance.
 *
 * <p>The loader is the only place that walks an AST for import/export records:
 * it extracts them once after parsing, recurses into dependencies, pre-installs
 * {@link ImportRef} sentinels in the importing module's globals, then runs the
 * module body. Live bindings are realized at runtime by
 * {@code Op.GetGlobal}/{@code Op.GetBinding} dereferencing those sentinels
 * through to the source module's globals on every read.
 */
public final class ModuleLoader {

    /** Wrapper-function name used for CJS source isolation. Picked to be
     *  unlikely to collide with user code. */
    private static final String CJS_WRAPPER_NAME = "__harmonica_cjs_wrapper$__";

    /**
     * Per-thread active loader + referrer directory, set by the entry
     * point (CLI / test runner / module body) before running JS code so
     * that dynamic {@code import(...)} expressions in that code can
     * resolve specifiers relative to the calling source. Cleared after
     * the run via {@link #clearActive}.
     */
    private static final ThreadLocal<Active> ACTIVE = new ThreadLocal<>();

    public record Active(ModuleLoader loader, Path referrer) {}

    public static void setActive(ModuleLoader loader, Path referrer) {
        ACTIVE.set(new Active(loader, referrer));
    }
    public static void clearActive() { ACTIVE.remove(); }
    public static Active active() { return ACTIVE.get(); }

    /** Cache keyed by canonical absolute path. */
    private final Map<Path, ModuleRecord> cache = new HashMap<>();

    /** Backing JSObject for {@code require.cache}. Each loaded CJS module
     *  appears here keyed by its absolute path string. Mutations from JS
     *  (delete-and-reload patterns) are observed on next require. */
    private final JSObject requireCache = new JSObject();

    /** Convenience: load and run a file as the program entry. The file's
     *  detected format determines whether it runs as ESM or CJS. */
    public Object runEntry(Path file) throws IOException {
        Path canonical = canonicalize(file);
        ModuleResolver.Format fmt = ModuleResolver.formatForFile(canonical);
        return load(canonical, fmt).effectiveExports();
    }

    /** Get the module-namespace JSObject for ESM, or the {@code module.exports}
     *  for CJS. Used by tests + the CLI. */
    public ModuleRecord load(Path file, ModuleResolver.Format format) throws IOException {
        Path canonical = canonicalize(file);
        ModuleRecord existing = cache.get(canonical);
        if (existing != null) return existing;

        ModuleRecord rec = new ModuleRecord(canonical, format);
        cache.put(canonical, rec);
        try {
            switch (format) {
                case ESM -> evaluateEsm(rec);
                case CJS -> evaluateCjs(rec);
                case JSON -> evaluateJson(rec);
                case BUILTIN -> throw new ModuleResolver.ModuleNotFoundException(
                    "node: builtins are not yet implemented: " + canonical);
            }
            rec.phase = ModuleRecord.Phase.EVALUATED;
        } catch (RuntimeException | IOException re) {
            // On failure the cache slot is poisoned; remove it so retries
            // can re-attempt (matches Node's behavior on syntax errors).
            cache.remove(canonical);
            throw re;
        }
        return rec;
    }

    public Map<Path, ModuleRecord> cache() { return cache; }
    public JSObject requireCacheObject() { return requireCache; }

    private static Path canonicalize(Path p) throws IOException {
        return p.toRealPath();
    }

    // ============================================================
    //  JSON modules — module.exports = parsed JSON
    // ============================================================

    private void evaluateJson(ModuleRecord rec) throws IOException {
        String src = Files.readString(rec.path);
        Object parsed;
        try {
            parsed = jsonValueToJs(PackageJson.JsonParser.parse(src));
        } catch (IOException io) {
            throw new IOException("Failed to parse JSON module " + rec.path + ": " + io.getMessage(), io);
        }
        rec.cjsExports = parsed;
    }

    private static Object jsonValueToJs(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean || v instanceof Double || v instanceof String) return v;
        if (v instanceof List<?> list) {
            com.jimmyhmiller.harmonica.bytecode.JSArray arr =
                new com.jimmyhmiller.harmonica.bytecode.JSArray();
            for (Object e : list) arr.push(jsonValueToJs(e));
            return arr;
        }
        if (v instanceof Map<?, ?> mapRaw) {
            JSObject obj = new JSObject();
            for (Map.Entry<?, ?> e : mapRaw.entrySet()) {
                obj.set((String) e.getKey(), jsonValueToJs(e.getValue()));
            }
            return obj;
        }
        return Undefined.VALUE;
    }

    // ============================================================
    //  CommonJS — IIFE-style wrap, run, capture module.exports
    // ============================================================

    private void evaluateCjs(ModuleRecord rec) throws IOException {
        rec.phase = ModuleRecord.Phase.LINKING;
        String userSrc = Files.readString(rec.path);
        // Wrap in a function so the user's top-level vars/lets stay scoped to
        // the wrapper (matching Node) and so we can hand the user explicit
        // (exports, require, module, __filename, __dirname) parameters.
        String wrapped = "function " + CJS_WRAPPER_NAME
            + "(exports,require,module,__filename,__dirname){\n"
            + userSrc + "\n}";
        Program ast;
        try {
            ast = Parser.parse(wrapped);
        } catch (RuntimeException pe) {
            throw new IOException("Parse error in CJS module " + rec.path + ": " + pe.getMessage(), pe);
        }
        Executable exe = Generator.generate(ast);

        // Run the wrapper script — this materializes the wrapper as a global
        // function. We then call it with our injected arguments.
        //
        // Use ModuleGlobals (identity-distinct from plain HashMap) so the
        // interpreter stamps homeGlobals on functions hoisted inside the
        // wrapper. Without this, exported functions called from another
        // module would inherit the caller's globals and lose access to the
        // defining module's top-level lexical bindings (e.g. classes).
        Map<String, Object> wrapperGlobals = new ModuleGlobals();
        Interpreter.interpret(exe, new Object[0], 64, wrapperGlobals);

        Object fnVal = wrapperGlobals.get(CJS_WRAPPER_NAME);
        if (!(fnVal instanceof JSFunction wrapperFn)) {
            throw new IOException("CJS wrapper did not materialize for " + rec.path);
        }

        JSObject exportsObj = new JSObject();
        JSObject moduleObj = new JSObject();
        moduleObj.set("exports", exportsObj);
        moduleObj.set("id", rec.path.toString());
        moduleObj.set("filename", rec.path.toString());
        moduleObj.set("loaded", Boolean.FALSE);

        // CJS cycle semantics: a mid-evaluation `require(thisModule)` must
        // return the partial exports as they exist at that moment. Pre-assign
        // {@code rec.cjsExports} BEFORE running the body so the cache returns
        // the live exports object during recursion.
        rec.cjsExports = exportsObj;
        // Also reflect on require.cache early so any cycle inspection sees us.
        requireCache.set(rec.path.toString(), moduleObj);

        JSFunction requireFn = buildRequireFunction(rec.path);
        Object[] args = new Object[] {
            exportsObj,
            requireFn,
            moduleObj,
            rec.path.toString(),
            rec.path.getParent() != null ? rec.path.getParent().toString() : "",
        };

        rec.phase = ModuleRecord.Phase.EVALUATING;
        try {
            Interpreter.invokeFunction(wrapperFn, Undefined.VALUE, args,
                new com.jimmyhmiller.harmonica.bytecode.InterpContext(exe, args, 64, wrapperGlobals));
        } catch (AbruptCompletion ac) {
            IOException io = new IOException("Module evaluation threw: "
                + abruptDescribe(ac) + " (in " + rec.path + ")");
            if (Boolean.getBoolean("harmonica.cli.trace")) ac.printStackTrace();
            throw io;
        }
        moduleObj.set("loaded", Boolean.TRUE);

        // Pick up the final {@code module.exports} value — user code may have
        // replaced the wrapper's initial empty object via {@code module.exports = X}.
        rec.cjsExports = moduleObj.get("exports");
        // Mirror the final exports onto require.cache as well.
        moduleObj.set("exports", rec.cjsExports);
    }

    /**
     * Build the per-module {@code require} JSFunction. Closes over the
     * importer's path so relative specifiers resolve correctly.
     */
    private JSFunction buildRequireFunction(Path importerPath) {
        NativeBody body = (thisVal, args, callerCtx) -> {
            if (args.length == 0) throw AbruptCompletion.typeError(
                "require() expects 1 argument");
            String specifier = AbstractOps.toString(args[0]);
            try {
                ModuleResolver.Resolved r = ModuleResolver.resolveCjs(specifier, importerPath);
                ModuleRecord dep = load(r.path(), r.format());
                return dep.effectiveExports();
            } catch (IOException io) {
                throw AbruptCompletion.plainError(io.getMessage());
            }
        };
        JSFunction fn = new JSFunction("require", 1, body);
        // require.resolve(specifier) — returns the resolved absolute path.
        NativeBody resolveBody = (thisVal, args, callerCtx) -> {
            if (args.length == 0) throw AbruptCompletion.typeError(
                "require.resolve() expects 1 argument");
            String specifier = AbstractOps.toString(args[0]);
            try {
                ModuleResolver.Resolved r = ModuleResolver.resolveCjs(specifier, importerPath);
                return r.path().toString();
            } catch (IOException io) {
                throw AbruptCompletion.plainError(io.getMessage());
            }
        };
        fn.properties().put("resolve", new JSFunction("resolve", 1, resolveBody));
        fn.properties().put("cache", requireCache);
        return fn;
    }

    // ============================================================
    //  ES modules — link, evaluate, build live namespace
    // ============================================================

    private void evaluateEsm(ModuleRecord rec) throws IOException {
        rec.phase = ModuleRecord.Phase.LINKING;
        String src = Files.readString(rec.path);
        Program ast;
        try {
            ast = Parser.parse(src, /* forceModuleMode */ true);
        } catch (RuntimeException pe) {
            throw new IOException("Parse error in ESM module " + rec.path + ": " + pe.getMessage(), pe);
        }
        Executable exe = Generator.generate(ast, /* moduleMode */ true);

        rec.moduleGlobals = new ModuleGlobals();
        rec.namespace = new JSObject();

        // Walk the program for import/export records.
        ImportExportRecords records = extractImportsExports(ast);

        // Register our own exports FIRST — populating rec.exports before we
        // recurse into deps means a cyclic importer that names us as a dep
        // will see our export-binding entries (their values may still be TDZ,
        // but the name resolution succeeds at link time).
        registerOwnExports(rec, ast);

        // TDZ-prime our globals for our own export names so reads observe
        // ReferenceError until the body initializes them.
        for (Map.Entry<String, ModuleRecord.ExportBinding> e : rec.exports.entrySet()) {
            if (e.getValue().source() == rec.moduleGlobals) {
                rec.moduleGlobals.putIfAbsent(e.getValue().sourceName(),
                    com.jimmyhmiller.harmonica.bytecode.InterpContext.TDZ);
            }
        }

        // Recurse into dependencies. Cycles return the partially-built record
        // (its rec.exports already populated above).
        Map<String, ModuleRecord> deps = new HashMap<>();
        for (String spec : records.specifiers) {
            ModuleResolver.Resolved r = ModuleResolver.resolveEsm(spec, rec.path);
            // Transitive imports of an ESM module: when the resolver
            // picked CJS by default for an ambiguous {@code .js} file
            // (no {@code "type": "module"} package.json), the file is
            // almost certainly ESM in practice — that's how the parent
            // got here. Explicit {@code .cjs} stays CJS.
            ModuleResolver.Format depFmt = r.format();
            if (depFmt == ModuleResolver.Format.CJS
                    && r.path().getFileName().toString().endsWith(".js")) {
                depFmt = ModuleResolver.Format.ESM;
            }
            ModuleRecord dep = cache.get(canonicalize(r.path()));
            if (dep == null) dep = load(r.path(), depFmt);
            deps.put(spec, dep);
        }

        // Wire imports into our globals as ImportRefs.
        for (ImportRecord ir : records.imports) {
            ModuleRecord dep = deps.get(ir.specifier);
            installImport(rec, ir, dep);
        }

        // Re-exports: `export { x } from './m'` and `export * from './m'`.
        for (ReexportRecord rx : records.reexports) {
            ModuleRecord dep = deps.get(rx.specifier);
            registerReexport(rec, rx, dep);
        }

        // Build the namespace JSObject — accessor properties that read live
        // values from each export's source globals on every read.
        rebuildNamespace(rec);

        rec.phase = ModuleRecord.Phase.EVALUATING;
        try {
            Interpreter.interpret(exe, new Object[0], 64, rec.moduleGlobals);
        } catch (AbruptCompletion ac) {
            // Re-raise as IOException carrying a useful message; the caller
            // (CLI / require / outer load) will format.
            throw new IOException("Module evaluation threw: "
                + abruptDescribe(ac) + " (in " + rec.path + ")");
        }

        // Re-build the namespace AFTER evaluation in case re-exports' source
        // namespaces have grown (no-op in the common case).
        rebuildNamespace(rec);
    }

    private static String abruptDescribe(AbruptCompletion ac) {
        Object v = ac.value();
        if (v instanceof JSObject jo) {
            Object name = jo.get("name");
            Object msg = jo.get("message");
            return AbstractOps.toString(name) + ": " + AbstractOps.toString(msg);
        }
        return AbstractOps.toString(v);
    }

    /**
     * For ESM-imports-CJS interop: synthesize a virtual ModuleGlobals for the
     * CJS dep so {@link ImportRef} can point at it. The default export is
     * {@code module.exports}; named exports come from enumerable own properties
     * of {@code module.exports} when it's a JSObject (Node's
     * {@code cjs-module-lexer} approximation, snapshot at link time).
     */
    private static Map<String, Object> cjsAsEsmGlobals(ModuleRecord cjsRec) {
        Map<String, Object> synthetic = new HashMap<>();
        synthetic.put(Generator.DEFAULT_EXPORT_BINDING, cjsRec.cjsExports);
        if (cjsRec.cjsExports instanceof JSObject jo) {
            for (Map.Entry<String, Object> e : jo.propertiesIfPresent().entrySet()) {
                if (jo.isEnumerable(e.getKey())) {
                    synthetic.putIfAbsent(e.getKey(), e.getValue());
                }
            }
        }
        return synthetic;
    }

    private void installImport(ModuleRecord importer, ImportRecord ir, ModuleRecord dep) {
        Map<String, Object> sourceGlobals = sourceGlobalsForDep(dep);

        switch (ir.kind) {
            case DEFAULT -> importer.moduleGlobals.put(ir.localName,
                new ImportRef(sourceGlobals, Generator.DEFAULT_EXPORT_BINDING));
            case NAMESPACE -> importer.moduleGlobals.put(ir.localName, dep.namespace != null
                ? dep.namespace : buildCjsNamespace(dep));
            case NAMED -> {
                // Resolve through dep's export table (covers re-exports).
                if (dep.format == ModuleResolver.Format.ESM) {
                    ModuleRecord.ExportBinding eb = dep.exports.get(ir.importedName);
                    if (eb == null) {
                        throw new RuntimeException("Module " + dep.path
                            + " has no exported member '" + ir.importedName + "'");
                    }
                    importer.moduleGlobals.put(ir.localName,
                        new ImportRef(eb.source(), eb.sourceName()));
                } else {
                    // CJS dep — point at the synthetic globals.
                    importer.moduleGlobals.put(ir.localName,
                        new ImportRef(sourceGlobals, ir.importedName));
                }
            }
        }
    }

    private Map<String, Object> sourceGlobalsForDep(ModuleRecord dep) {
        if (dep.format == ModuleResolver.Format.ESM) return dep.moduleGlobals;
        if (dep.moduleGlobals == null) {
            // Synthesize once and cache on the record so repeat imports share.
            dep.moduleGlobals = cjsAsEsmGlobals(dep);
        }
        return dep.moduleGlobals;
    }

    /**
     * For a CJS dep imported with {@code import * as ns from './c'} — synthesize
     * a namespace object exposing the same default + named view.
     */
    private JSObject buildCjsNamespace(ModuleRecord dep) {
        if (dep.namespace != null) return dep.namespace;
        JSObject ns = new JSObject();
        ns.set("default", dep.cjsExports);
        if (dep.cjsExports instanceof JSObject jo) {
            for (Map.Entry<String, Object> e : jo.propertiesIfPresent().entrySet()) {
                if (jo.isEnumerable(e.getKey()) && !"default".equals(e.getKey())) {
                    ns.set(e.getKey(), e.getValue());
                }
            }
        }
        dep.namespace = ns;
        return ns;
    }

    /** For ESM modules, walk the AST and record locally-declared exports. */
    private static void registerOwnExports(ModuleRecord rec, Program ast) {
        for (Node n : ast.body()) {
            if (n instanceof ExportNamedDeclaration end) {
                if (end.source() != null) continue;  // re-exports handled separately
                if (end.declaration() != null) {
                    for (String name : declaredNames(end.declaration())) {
                        rec.exports.put(name, new ModuleRecord.ExportBinding(rec.moduleGlobals, name));
                    }
                }
                if (end.specifiers() != null) {
                    for (Node sp : end.specifiers()) {
                        if (sp instanceof ExportSpecifier es) {
                            String local = nameOf(es.local());
                            String exported = nameOf(es.exported());
                            rec.exports.put(exported,
                                new ModuleRecord.ExportBinding(rec.moduleGlobals, local));
                        }
                    }
                }
            } else if (n instanceof com.jimmyhmiller.harmonica.ast.ExportDefaultDeclaration) {
                rec.exports.put("default",
                    new ModuleRecord.ExportBinding(rec.moduleGlobals, Generator.DEFAULT_EXPORT_BINDING));
            }
        }
    }

    /** Add re-exported names ({@code export { x } from} / {@code export *}). */
    private void registerReexport(ModuleRecord rec, ReexportRecord rx, ModuleRecord dep) {
        if (rx.specifiers != null) {
            // export { a, b as c } from './m'
            for (ExportSpecifier es : rx.specifiers) {
                String local = nameOf(es.local());        // name in dep
                String exported = nameOf(es.exported());  // re-exported name
                ModuleRecord.ExportBinding eb;
                if (dep.format == ModuleResolver.Format.ESM) {
                    eb = dep.exports.get(local);
                    if (eb == null) {
                        throw new RuntimeException("Module " + dep.path
                            + " has no exported member '" + local + "' for re-export");
                    }
                } else {
                    eb = new ModuleRecord.ExportBinding(sourceGlobalsForDep(dep), local);
                }
                rec.exports.put(exported, eb);
            }
        } else if (rx.namespaceAlias != null) {
            // export * as ns from './m' — bind a single name to the dep namespace.
            // We model this as an entry whose source is a tiny wrapper map
            // returning the namespace object on every read.
            String nsKey = "*ns:" + rx.specifier + "*";
            rec.moduleGlobals.put(nsKey, dep.namespace != null ? dep.namespace : buildCjsNamespace(dep));
            rec.exports.put(rx.namespaceAlias,
                new ModuleRecord.ExportBinding(rec.moduleGlobals, nsKey));
        } else {
            // export * from './m' — re-export all named exports of dep, NOT default.
            if (dep.format == ModuleResolver.Format.ESM) {
                for (Map.Entry<String, ModuleRecord.ExportBinding> e : dep.exports.entrySet()) {
                    if ("default".equals(e.getKey())) continue;
                    rec.exports.putIfAbsent(e.getKey(), e.getValue());
                }
            } else {
                Map<String, Object> srcGlobals = sourceGlobalsForDep(dep);
                for (String key : new ArrayList<>(srcGlobals.keySet())) {
                    if (Generator.DEFAULT_EXPORT_BINDING.equals(key)) continue;
                    rec.exports.putIfAbsent(key,
                        new ModuleRecord.ExportBinding(srcGlobals, key));
                }
            }
        }
    }

    /** Build accessor properties on rec.namespace for each export binding. */
    private void rebuildNamespace(ModuleRecord rec) {
        rec.namespace = new JSObject();
        for (Map.Entry<String, ModuleRecord.ExportBinding> e : rec.exports.entrySet()) {
            String exportedName = e.getKey();
            Map<String, Object> source = e.getValue().source();
            String sourceName = e.getValue().sourceName();
            NativeBody getter = (thisVal, args, ctx) -> {
                if (!source.containsKey(sourceName)) return Undefined.VALUE;
                Object v = source.get(sourceName);
                if (v == com.jimmyhmiller.harmonica.bytecode.InterpContext.TDZ) {
                    throw AbruptCompletion.referenceError("cannot access '" + sourceName
                        + "' before initialization");
                }
                return v;
            };
            JSFunction getterFn = new JSFunction("get " + exportedName, 0, getter);
            rec.namespace.set(exportedName, new Accessor(getterFn, null));
        }
        // Spec: namespace also has a Symbol.toStringTag of "Module".
        rec.namespace.set("@@toStringTag", "Module");
    }

    // ============================================================
    //  AST walk — extract import / export / re-export records.
    // ============================================================

    enum ImportKind { DEFAULT, NAMED, NAMESPACE }
    record ImportRecord(String specifier, String localName, String importedName, ImportKind kind) {}
    record ReexportRecord(String specifier, List<ExportSpecifier> specifiers, String namespaceAlias) {}

    static final class ImportExportRecords {
        final List<ImportRecord> imports = new ArrayList<>();
        final List<ReexportRecord> reexports = new ArrayList<>();
        final Set<String> specifiers = new LinkedHashSet<>();
    }

    private static ImportExportRecords extractImportsExports(Program ast) {
        ImportExportRecords r = new ImportExportRecords();
        for (Node n : ast.body()) {
            if (n instanceof ImportDeclaration id) {
                String spec = (String) id.source().value();
                r.specifiers.add(spec);
                for (Node sp : id.specifiers()) {
                    if (sp instanceof ImportDefaultSpecifier ids) {
                        r.imports.add(new ImportRecord(spec, ids.local().name(), "default", ImportKind.DEFAULT));
                    } else if (sp instanceof ImportNamespaceSpecifier ins) {
                        r.imports.add(new ImportRecord(spec, ins.local().name(), null, ImportKind.NAMESPACE));
                    } else if (sp instanceof ImportSpecifier is) {
                        r.imports.add(new ImportRecord(spec, is.local().name(), nameOf(is.imported()), ImportKind.NAMED));
                    }
                }
            } else if (n instanceof ExportNamedDeclaration end && end.source() != null) {
                String spec = (String) end.source().value();
                r.specifiers.add(spec);
                List<ExportSpecifier> specs = new ArrayList<>();
                if (end.specifiers() != null) {
                    for (Node sp : end.specifiers()) {
                        if (sp instanceof ExportSpecifier es) specs.add(es);
                    }
                }
                r.reexports.add(new ReexportRecord(spec, specs, null));
            } else if (n instanceof ExportAllDeclaration ead) {
                String spec = (String) ead.source().value();
                r.specifiers.add(spec);
                String alias = ead.exported() != null ? nameOf(ead.exported()) : null;
                r.reexports.add(new ReexportRecord(spec, null, alias));
            }
        }
        return r;
    }

    private static List<String> declaredNames(Statement decl) {
        List<String> names = new ArrayList<>();
        if (decl instanceof VariableDeclaration vd) {
            for (VariableDeclarator d : vd.declarations()) {
                addPatternNames(d.id(), names);
            }
        } else if (decl instanceof FunctionDeclaration fd && fd.id() != null) {
            names.add(fd.id().name());
        } else if (decl instanceof ClassDeclaration cd && cd.id() != null) {
            names.add(cd.id().name());
        }
        return names;
    }

    private static void addPatternNames(Pattern p, List<String> out) {
        if (p instanceof Identifier id) out.add(id.name());
        // Destructuring patterns aren't exhaustively traversed in v1 — `export
        // const { a, b } = …` is a long-tail case we'll add if a real package
        // needs it. For now those names won't be listed in exports.
    }

    private static String nameOf(Node n) {
        if (n instanceof Identifier id) return id.name();
        if (n instanceof Literal lit && lit.value() instanceof String s) return s;
        throw new IllegalArgumentException("expected Identifier/Literal, got " + n);
    }
}
