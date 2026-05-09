package com.jimmyhmiller.harmonica.module;

import com.jimmyhmiller.harmonica.bytecode.AbstractOps;
import com.jimmyhmiller.harmonica.bytecode.Accessor;
import com.jimmyhmiller.harmonica.bytecode.Executable;
import com.jimmyhmiller.harmonica.bytecode.InterpContext;
import com.jimmyhmiller.harmonica.bytecode.Interpreter;
import com.jimmyhmiller.harmonica.bytecode.JSObject;
import com.jimmyhmiller.harmonica.bytecode.Realm;
import com.jimmyhmiller.harmonica.bytecode.Undefined;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end tests for the module loader. Each test loads a fixture under
 * {@code src/test/resources/modules/<name>} and asserts on the resulting
 * exports.
 */
class ModuleLoaderTest {

    private static final Path FIXTURES = locateFixtures();

    private static Path locateFixtures() {
        // Surefire runs from harmonica-core/; standalone IDE runs may run from project root.
        Path[] candidates = {
            Paths.get("src/test/resources/modules"),
            Paths.get("harmonica-core/src/test/resources/modules"),
        };
        for (Path p : candidates) {
            if (Files.isDirectory(p)) return p.toAbsolutePath();
        }
        throw new IllegalStateException("Cannot locate test fixtures dir");
    }

    @BeforeEach void resetRealm() {
        // Each test gets a fresh prototype graph so cross-realm intrinsic
        // pollution can't propagate.
        Realm.resetForNewRun();
    }

    private ModuleLoader loader() { return new ModuleLoader(); }

    private Path fix(String name) { return FIXTURES.resolve(name); }

    /** Read an exported value from an ESM record's namespace, dereferencing
     *  the live-binding accessor we install on namespace properties. */
    private static Object esmExport(ModuleRecord rec, String name) {
        return derefAccessor(AbstractOps.getProperty(rec.namespace, name));
    }

    private static Object property(Object obj, String name) {
        return derefAccessor(AbstractOps.getProperty(obj, name));
    }

    /** Helper: invoke an Accessor's getter from a Java test context. */
    private static Object derefAccessor(Object v) {
        if (!(v instanceof Accessor acc) || acc.getter() == null) return v;
        // We need an InterpContext for the call. Build a minimal one against
        // the getter's body Executable (or a shim for native getters).
        InterpContext ctx;
        if (acc.getter().body() != null) {
            ctx = new InterpContext(acc.getter().body(), new Object[0], 0);
        } else {
            // Native getter — pull a placeholder Executable from anywhere
            // (we don't read it). Simpler: just synthesize via the getter's
            // body() which is null for natives, so use an existing executable
            // from any prior call site. The getter's nativeBody just needs
            // some context to satisfy the call ABI.
            Executable shim = anyExecutable();
            ctx = new InterpContext(shim, new Object[0], 0);
        }
        return Interpreter.invokeFunction(acc.getter(), Undefined.VALUE, new Object[0], ctx);
    }

    private static Executable shimExe;
    private static Executable anyExecutable() {
        if (shimExe != null) return shimExe;
        // Compile a trivial program once; reuse for the InterpContext shim.
        com.jimmyhmiller.harmonica.ast.Program p =
            com.jimmyhmiller.harmonica.Parser.parse(";");
        shimExe = com.jimmyhmiller.harmonica.bytecode.Generator.generate(p);
        return shimExe;
    }

    // ============================================================
    //  ESM
    // ============================================================

    @Test void esmRelativeImport() throws Exception {
        ModuleRecord rec = loader().load(fix("relative/main.mjs"), ModuleResolver.Format.ESM);
        assertThat(esmExport(rec, "result")).isEqualTo("hi world");
    }

    @Test void esmDefaultAndNamed() throws Exception {
        ModuleRecord rec = loader().load(fix("default-named/main.mjs"), ModuleResolver.Format.ESM);
        assertThat(esmExport(rec, "result")).isEqualTo(14.0);
    }

    @Test void esmNamespaceImport() throws Exception {
        ModuleRecord rec = loader().load(fix("namespace/main.mjs"), ModuleResolver.Format.ESM);
        assertThat(esmExport(rec, "result")).isEqualTo(6.0);
    }

    @Test void esmCycleHandlesPartialInit() throws Exception {
        ModuleRecord rec = loader().load(fix("cycle/main.mjs"), ModuleResolver.Format.ESM);
        Object resObj = esmExport(rec, "result");
        assertThat(property(resObj, "a")).isEqualTo("A:B");
        assertThat(property(resObj, "b")).isEqualTo("B");
        assertThat(property(resObj, "bGotTDZ")).isEqualTo(Boolean.TRUE);
    }

    @Test void esmLiveBindingReflectsCrossModuleMutation() throws Exception {
        ModuleRecord rec = loader().load(fix("live-binding/main.mjs"), ModuleResolver.Format.ESM);
        Object resObj = esmExport(rec, "result");
        assertThat(property(resObj, "before")).isEqualTo(0.0);
        assertThat(property(resObj, "after")).isEqualTo(3.0);
        assertThat(property(resObj, "assignThrew")).isEqualTo(Boolean.TRUE);
    }

    @Test void esmReexportNamed() throws Exception {
        ModuleRecord rec = loader().load(fix("reexport/main.mjs"), ModuleResolver.Format.ESM);
        Object resObj = esmExport(rec, "result");
        assertThat(property(resObj, "x")).isEqualTo(1.0);
        assertThat(property(resObj, "why")).isEqualTo(2.0);
    }

    @Test void esmExportStarReexportsAll() throws Exception {
        ModuleRecord rec = loader().load(fix("export-star/main.mjs"), ModuleResolver.Format.ESM);
        assertThat(esmExport(rec, "result")).isEqualTo(6.0);
    }

    @Test void esmAssigningImportThrowsTypeError() throws Exception {
        ModuleRecord rec = loader().load(fix("import-readonly/main.mjs"), ModuleResolver.Format.ESM);
        assertThat(esmExport(rec, "result")).isEqualTo(Boolean.TRUE);
    }

    // ============================================================
    //  CommonJS
    // ============================================================

    @Test void cjsBasicRequireAndExports() throws Exception {
        ModuleRecord rec = loader().load(fix("cjs-basic/main.cjs"), ModuleResolver.Format.CJS);
        assertThat(property(rec.cjsExports, "sum")).isEqualTo(42.0);
        assertThat(property(rec.cjsExports, "pi")).isEqualTo(3.14);
    }

    @Test void cjsCacheReturnsSameInstance() throws Exception {
        ModuleRecord rec = loader().load(fix("cjs-cache/main.cjs"), ModuleResolver.Format.CJS);
        assertThat(property(rec.cjsExports, "same")).isEqualTo(Boolean.TRUE);
    }

    @Test void cjsCyclePartialExportSemantics() throws Exception {
        // CJS cycles: when b requires a mid-evaluation, b sees only the partial
        // a.exports populated up to the require call. After all bodies run,
        // both modules' exports are fully populated.
        ModuleRecord rec = loader().load(fix("cjs-cycle/main.cjs"), ModuleResolver.Format.CJS);
        Object a = property(rec.cjsExports, "a");
        Object b = property(rec.cjsExports, "b");
        // a.aDone reflects a's final state (true after a's body completes).
        assertThat(property(a, "aDone")).isEqualTo(Boolean.TRUE);
        // b saw a's partial state (aDone was still false when b ran).
        assertThat(property(b, "bMid")).isEqualTo("b-saw-aDone=false");
    }

    // ============================================================
    //  Interop
    // ============================================================

    @Test void cjsRequiringEsmGetsNamespace() throws Exception {
        ModuleRecord rec = loader().load(fix("esm-from-cjs/main.cjs"), ModuleResolver.Format.CJS);
        assertThat(property(rec.cjsExports, "x")).isEqualTo(5.0);
        assertThat(property(rec.cjsExports, "def")).isEqualTo("the-default");
    }

    @Test void esmImportingCjsGetsDefaultPlusNamed() throws Exception {
        ModuleRecord rec = loader().load(fix("cjs-from-esm/main.mjs"), ModuleResolver.Format.ESM);
        Object resObj = esmExport(rec, "result");
        assertThat(property(resObj, "defaultFoo")).isEqualTo(11.0);
        assertThat(property(resObj, "named")).isEqualTo(33.0);
    }

    // ============================================================
    //  Resolution
    // ============================================================

    @Test void packageMainResolvesViaNodeModules() throws Exception {
        ModuleRecord rec = loader().load(fix("package-main/main.cjs"), ModuleResolver.Format.CJS);
        assertThat(property(rec.cjsExports, "result")).isEqualTo(42.0);
    }

    @Test void packageExportsImportConditionPicksEsm() throws Exception {
        ModuleRecord rec = loader().load(fix("package-exports/use-esm.mjs"), ModuleResolver.Format.ESM);
        assertThat(esmExport(rec, "result")).isEqualTo("esm-branch");
    }

    @Test void packageExportsRequireConditionPicksCjs() throws Exception {
        ModuleRecord rec = loader().load(fix("package-exports/use-cjs.cjs"), ModuleResolver.Format.CJS);
        assertThat(rec.cjsExports).isEqualTo("cjs-branch");
    }

    @Test void jsonModuleViaRequire() throws Exception {
        ModuleRecord rec = loader().load(fix("json-mod/main.cjs"), ModuleResolver.Format.CJS);
        assertThat(property(rec.cjsExports, "n")).isEqualTo(42.0);
        assertThat(property(rec.cjsExports, "second")).isEqualTo(2.0);
    }

    // ============================================================
    //  Errors
    // ============================================================

    @Test void missingModuleThrowsClearError() {
        assertThatThrownBy(() -> loader().load(fix("missing/main.cjs"), ModuleResolver.Format.CJS))
            .hasMessageContaining("Cannot find module");
    }

    @Test void nodeBuiltinThrowsNotImplemented() {
        assertThatThrownBy(() ->
                ModuleResolver.resolveCjs("node:fs", fix("missing/main.cjs")))
            .hasMessageContaining("Cannot find module 'node:fs'")
            .hasMessageContaining("not yet implement node: builtins");
    }

    // ============================================================
    //  Subpath patterns + imports field — end-to-end
    // ============================================================

    @Test void exportsSubpathPatternsResolveEndToEnd() throws Exception {
        ModuleRecord rec = loader().load(fix("exports-pattern/main.cjs"), ModuleResolver.Format.CJS);
        assertThat(property(rec.cjsExports, "rootHello")).isEqualTo("world");
        assertThat(property(rec.cjsExports, "upper")).isEqualTo("HI");
        // doubled is a JSArray; check via length + indices.
        Object doubled = property(rec.cjsExports, "doubled");
        assertThat(property(doubled, "length")).isEqualTo(3.0);
        assertThat(property(doubled, "0")).isEqualTo(2.0);
        assertThat(property(doubled, "2")).isEqualTo(6.0);
    }

    @Test void importsFieldHashSpecifierEndToEnd() throws Exception {
        ModuleRecord rec = loader().load(fix("imports-field/main.mjs"), ModuleResolver.Format.ESM);
        Object resObj = esmExport(rec, "result");
        assertThat(property(resObj, "cfg")).isEqualTo("1.0");
        assertThat(property(resObj, "total")).isEqualTo(42.0);
        assertThat(property(resObj, "greeting")).isEqualTo("[[hi]]");
    }
}
