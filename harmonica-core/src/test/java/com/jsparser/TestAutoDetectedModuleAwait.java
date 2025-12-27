package com.jsparser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsparser.ast.Program;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class TestAutoDetectedModuleAwait {

    private static final ObjectMapper mapper = TestObjectMapper.get()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    @Test
    public void topLevelAwaitParsesInModuleMode() throws Exception {
        String source = String.join("\n",
                "import { foo } from './dep.js';",
                "await foo;");

        AcornResult acorn = parseWithAcorn(source);
        assertTrue(acorn.success, () -> {
            if (acorn.output.contains("Cannot find module 'acorn'")) {
                return "Acorn not installed; run `npm ci` (output: " + acorn.output + ")";
            }
            return "Acorn parse failed: " + acorn.output;
        });
        String acornJson = acorn.json;
        assertTrue(acornJson.contains("\"AwaitExpression\""), "Acorn should recognize top-level await");

        Program javaAst = assertDoesNotThrow(() -> Parser.parse(source, true),
                "Parser should parse modules in module mode without throwing");
        assertEquals("module", javaAst.sourceType(), "Module mode should produce sourceType: module");

        String javaJson = mapper.writeValueAsString(javaAst);

        // Debug output
        System.out.println("=== Java version: " + System.getProperty("java.version") + " ===");
        System.out.println("=== Checking parameter names on ImportDeclaration ===");
        try {
            java.lang.reflect.RecordComponent[] components = com.jsparser.ast.ImportDeclaration.class.getRecordComponents();
            for (java.lang.reflect.RecordComponent c : components) {
                System.out.println("  Component: " + c.getName() + " -> " + c.getType().getSimpleName());
            }
        } catch (Exception e) {
            System.out.println("  Error getting components: " + e);
        }
        System.out.println("=== Java JSON (first 1000 chars) ===");
        System.out.println(javaJson.substring(0, Math.min(1000, javaJson.length())));
        System.out.println("=== Acorn JSON (first 1000 chars) ===");
        System.out.println(acornJson.substring(0, Math.min(1000, acornJson.length())));

        Object acornObj = mapper.readValue(acornJson, Object.class);
        Object javaObj = mapper.readValue(javaJson, Object.class);

        List<String> diffs = findDifferences(acornObj, javaObj, "");
        assertTrue(diffs.isEmpty(), "Expected parser output to match Acorn but found differences: " + diffs);
    }

    private AcornResult parseWithAcorn(String source) throws Exception {
        Path tempSource = Files.createTempFile("test-source-", ".js");
        Files.writeString(tempSource, source);

        try {
            String[] cmd = {
                "node",
                "parse-with-acorn.js",
                tempSource.toAbsolutePath().toString()
            };

            ProcessBuilder pb = new ProcessBuilder(cmd);
            // Set working directory to scripts/ so node can find acorn in node_modules
            pb.directory(new java.io.File("scripts"));
            Process process = pb.start();

            int exitCode = process.waitFor();
            String stdout = new String(process.getInputStream().readAllBytes());
            String stderr = new String(process.getErrorStream().readAllBytes());
            if (exitCode != 0) {
                return new AcornResult(false, null, "exit=" + exitCode + ", stdout=" + stdout + ", stderr=" + stderr);
            }

            return new AcornResult(true, stdout, stdout + stderr);
        } finally {
            Files.deleteIfExists(tempSource);
        }
    }

    private record AcornResult(boolean success, String json, String output) {}

    @SuppressWarnings("unchecked")
    private List<String> findDifferences(Object expected, Object actual, String path) {
        List<String> diffs = new ArrayList<>();

        if (expected == null && actual == null) {
            return diffs;
        }

        if (expected == null || actual == null) {
            diffs.add(path + ": null mismatch (" + expected + " vs " + actual + ")");
            return diffs;
        }

        if (!expected.getClass().equals(actual.getClass())) {
            diffs.add(path + ": type mismatch (" + expected.getClass().getSimpleName() + " vs " + actual.getClass().getSimpleName() + ")");
            return diffs;
        }

        if (expected instanceof Map) {
            Map<String, Object> expMap = (Map<String, Object>) expected;
            Map<String, Object> actMap = (Map<String, Object>) actual;

            Set<String> allKeys = new HashSet<>();
            allKeys.addAll(expMap.keySet());
            allKeys.addAll(actMap.keySet());

            for (String key : allKeys) {
                if (!expMap.containsKey(key)) {
                    diffs.add(path + "." + key + ": missing in Acorn");
                } else if (!actMap.containsKey(key)) {
                    diffs.add(path + "." + key + ": missing in Java");
                } else {
                    diffs.addAll(findDifferences(expMap.get(key), actMap.get(key), path + "." + key));
                }
            }
        } else if (expected instanceof List) {
            List<Object> expList = (List<Object>) expected;
            List<Object> actList = (List<Object>) actual;

            if (expList.size() != actList.size()) {
                diffs.add(path + ": length mismatch (" + expList.size() + " vs " + actList.size() + ")");
            }

            int len = Math.min(expList.size(), actList.size());
            for (int i = 0; i < len; i++) {
                diffs.addAll(findDifferences(expList.get(i), actList.get(i), path + "[" + i + "]"));
            }
        } else {
            if (!expected.equals(actual)) {
                diffs.add(path + ": " + expected + " != " + actual);
            }
        }

        return diffs;
    }
}
