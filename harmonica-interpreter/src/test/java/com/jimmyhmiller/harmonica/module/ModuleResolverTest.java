package com.jimmyhmiller.harmonica.module;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleResolverTest {

    private static final Path FIXTURES = locateFixtures();

    private static Path locateFixtures() {
        Path[] candidates = {
            Paths.get("src/test/resources/modules"),
            Paths.get("harmonica-interpreter/src/test/resources/modules"),
        };
        for (Path p : candidates) {
            if (Files.isDirectory(p)) return p.toAbsolutePath();
        }
        throw new IllegalStateException("Cannot locate test fixtures dir");
    }

    @Test void esmRelativeResolves() throws Exception {
        Path parent = FIXTURES.resolve("relative/main.mjs");
        ModuleResolver.Resolved r = ModuleResolver.resolveEsm("./dep.mjs", parent);
        assertThat(r.path()).isEqualTo(parent.getParent().resolve("dep.mjs").toRealPath());
        assertThat(r.format()).isEqualTo(ModuleResolver.Format.ESM);
    }

    @Test void esmRelativeFallsBackToJsExtension() throws Exception {
        // For DX, we accept extensionless ESM imports — Node strict ESM does
        // not, but most code relies on this loosened lookup.
        Path parent = FIXTURES.resolve("package-main/main.cjs");
        ModuleResolver.Resolved r = ModuleResolver.resolveCjs("./node_modules/mini/lib/index", parent);
        assertThat(r.path().getFileName().toString()).isEqualTo("index.js");
    }

    @Test void cjsResolvesPackageMain() throws Exception {
        Path parent = FIXTURES.resolve("package-main/main.cjs");
        ModuleResolver.Resolved r = ModuleResolver.resolveCjs("mini", parent);
        assertThat(r.path().getFileName().toString()).isEqualTo("index.js");
        assertThat(r.format()).isEqualTo(ModuleResolver.Format.CJS);
    }

    @Test void packageExportsConditionalImportPicksEsm() throws Exception {
        Path parent = FIXTURES.resolve("package-exports/use-esm.mjs");
        ModuleResolver.Resolved r = ModuleResolver.resolveEsm("dual", parent);
        assertThat(r.path().getFileName().toString()).isEqualTo("esm.mjs");
        assertThat(r.format()).isEqualTo(ModuleResolver.Format.ESM);
    }

    @Test void packageExportsConditionalRequirePicksCjs() throws Exception {
        Path parent = FIXTURES.resolve("package-exports/use-cjs.cjs");
        ModuleResolver.Resolved r = ModuleResolver.resolveCjs("dual", parent);
        assertThat(r.path().getFileName().toString()).isEqualTo("cjs.cjs");
        assertThat(r.format()).isEqualTo(ModuleResolver.Format.CJS);
    }

    @Test void nodeColonBuiltinsAreRefusedClearly() {
        Path parent = FIXTURES.resolve("relative/main.mjs");
        assertThatThrownBy(() -> ModuleResolver.resolveEsm("node:fs", parent))
            .hasMessageContaining("Cannot find module 'node:fs'")
            .hasMessageContaining("not yet implement node: builtins");
    }

    @Test void unknownBareSpecifierGivesClearError() {
        Path parent = FIXTURES.resolve("relative/main.mjs");
        assertThatThrownBy(() -> ModuleResolver.resolveCjs("totally-not-a-package", parent))
            .hasMessageContaining("Cannot find package 'totally-not-a-package'");
    }

    @Test void exportsSubpathPatternMatches() {
        java.util.Map<String, Object> exports = new java.util.LinkedHashMap<>();
        exports.put("./*", "./src/*.js");
        // "./foo" → captured "foo" → "./src/foo.js"
        assertThat(ModuleResolver.packageExportsResolve(
                "./foo", exports, ModuleResolver.ESM_CONDITIONS))
            .isEqualTo("./src/foo.js");
        // Multi-segment captures also work.
        assertThat(ModuleResolver.packageExportsResolve(
                "./a/b/c", exports, ModuleResolver.ESM_CONDITIONS))
            .isEqualTo("./src/a/b/c.js");
    }

    @Test void exportsPatternMostSpecificWins() {
        // PATTERN_KEY_COMPARE: "./fp/*" beats "./*" for "./fp/x".
        java.util.Map<String, Object> exports = new java.util.LinkedHashMap<>();
        exports.put("./*",    "./src/*.js");
        exports.put("./fp/*", "./fp-impl/*.js");
        assertThat(ModuleResolver.packageExportsResolve(
                "./fp/map", exports, ModuleResolver.ESM_CONDITIONS))
            .isEqualTo("./fp-impl/map.js");
        assertThat(ModuleResolver.packageExportsResolve(
                "./other", exports, ModuleResolver.ESM_CONDITIONS))
            .isEqualTo("./src/other.js");
    }

    @Test void exportsPatternWithConditions() {
        // Pattern value is itself a conditions object — substitute * in the
        // selected branch.
        java.util.Map<String, Object> branch = new java.util.LinkedHashMap<>();
        branch.put("import",  "./esm/*.mjs");
        branch.put("require", "./cjs/*.cjs");
        java.util.Map<String, Object> exports = new java.util.LinkedHashMap<>();
        exports.put("./util/*", branch);
        assertThat(ModuleResolver.packageExportsResolve(
                "./util/strings", exports, ModuleResolver.ESM_CONDITIONS))
            .isEqualTo("./esm/strings.mjs");
        assertThat(ModuleResolver.packageExportsResolve(
                "./util/strings", exports, ModuleResolver.CJS_CONDITIONS))
            .isEqualTo("./cjs/strings.cjs");
    }

    @Test void exportsNullTargetIsExplicitDenial() {
        java.util.Map<String, Object> exports = new java.util.LinkedHashMap<>();
        exports.put("./public/*",  "./src/*.js");
        exports.put("./private/*", null);  // explicit denial
        assertThat(ModuleResolver.packageExportsResolve(
                "./public/x", exports, ModuleResolver.ESM_CONDITIONS))
            .isEqualTo("./src/x.js");
        assertThat(ModuleResolver.packageExportsResolve(
                "./private/x", exports, ModuleResolver.ESM_CONDITIONS))
            .isNull();
    }

    @Test void importsFieldHashSpecifier() {
        java.util.Map<String, Object> imports = new java.util.LinkedHashMap<>();
        imports.put("#config",   "./src/config/index.js");
        imports.put("#utils/*",  "./src/utils/*.js");
        // Exact match
        assertThat(ModuleResolver.packageImportsResolve(
                "#config", imports, ModuleResolver.ESM_CONDITIONS))
            .isEqualTo("./src/config/index.js");
        // Pattern match
        assertThat(ModuleResolver.packageImportsResolve(
                "#utils/format", imports, ModuleResolver.ESM_CONDITIONS))
            .isEqualTo("./src/utils/format.js");
    }

    @Test void importsFieldHashSpecifierEndToEnd() throws Exception {
        Path parent = FIXTURES.resolve("imports-field/main.mjs");
        ModuleResolver.Resolved r = ModuleResolver.resolveEsm("#utils/sum", parent);
        assertThat(r.path().getFileName().toString()).isEqualTo("sum.mjs");
        assertThat(r.format()).isEqualTo(ModuleResolver.Format.ESM);
    }

    @Test void exportsRejectsMultiStarKey() {
        java.util.Map<String, Object> exports = new java.util.LinkedHashMap<>();
        exports.put("./*-*", "./*.js");
        assertThatThrownBy(() -> ModuleResolver.packageExportsResolve(
                "./a-b", exports, ModuleResolver.ESM_CONDITIONS))
            .hasMessageContaining("more than one '*'");
    }
}
