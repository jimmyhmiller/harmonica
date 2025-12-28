package com.jsparser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsparser.ast.Program;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

public class JacksonTest {
    @Test
    void testJacksonDeserialization() throws Exception {
        String json = """
            {
              "type": "Program",
              "body": [],
              "sourceType": "script"
            }
            """;

        ObjectMapper mapper = TestObjectMapper.get();

        Program program = mapper.readValue(json, Program.class);
        System.out.println("Program: " + program);
        System.out.println("Type: " + program.type());
        System.out.println("SourceType: " + program.sourceType());
        System.out.println("Body: " + program.body());
    }

    @Test
    void testJacksonDeserializationWithLoc() throws Exception {
        String json = """
            {
              "type": "Program",
              "start": 0,
              "end": 15,
              "loc": {
                "start": { "line": 1, "column": 0 },
                "end": { "line": 1, "column": 15 }
              },
              "body": [],
              "sourceType": "script"
            }
            """;

        ObjectMapper mapper = TestObjectMapper.get();

        Program program = mapper.readValue(json, Program.class);
        System.out.println("Program with loc: " + program);
        System.out.println("startLine: " + program.startLine());
        System.out.println("startCol: " + program.startCol());
        System.out.println("endLine: " + program.endLine());
        System.out.println("endCol: " + program.endCol());

        // These should be transformed from loc
        assertEquals(1, program.startLine(), "startLine should be 1");
        assertEquals(0, program.startCol(), "startCol should be 0");
        assertEquals(1, program.endLine(), "endLine should be 1");
        assertEquals(15, program.endCol(), "endCol should be 15");
    }

    @Test
    void testSerializeSimple() throws Exception {
        String source = "let x = 1;";
        ObjectMapper mapper = TestObjectMapper.get();
        Program ast = Parser.parse(source, false);
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(ast);
        System.out.println("Serialized AST:\n" + json);

        // Verify loc is present
        assertTrue(json.contains("\"loc\""), "Should have loc field");
        // Verify startLine etc are NOT present
        assertFalse(json.contains("\"startLine\""), "Should NOT have startLine field");
        assertFalse(json.contains("\"startCol\""), "Should NOT have startCol field");
    }

    @Test
    void testLargeNumberParsing() throws Exception {
        ObjectMapper mapper = TestObjectMapper.get();

        // Parse JSON with a very large number (larger than Long.MAX_VALUE)
        String json = "{\"value\": 9223372036854776000}";
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> map = mapper.readValue(json, java.util.Map.class);
        Object value = map.get("value");

        System.out.println("Value: " + value);
        System.out.println("Type: " + value.getClass().getName());
        System.out.println("Long.MAX_VALUE: " + Long.MAX_VALUE);

        // This large number should be preserved, not clamped to Long.MAX_VALUE
        assertNotEquals(Long.MAX_VALUE, ((Number) value).longValue(),
            "Large number should not be clamped to Long.MAX_VALUE");
    }

    @Test
    void testParserLargeNumber() throws Exception {
        ObjectMapper mapper = TestObjectMapper.get();

        // Parse source with our parser
        String source = "const x = 9223372036854776000;";
        Program ast = Parser.parse(source, false);

        // Serialize to JSON
        String actualJson = mapper.writeValueAsString(ast);
        System.out.println("Our parser output: " + actualJson);

        // Get the value from our parsed AST
        com.jsparser.ast.VariableDeclaration varDecl = (com.jsparser.ast.VariableDeclaration) ast.body().get(0);
        com.jsparser.ast.VariableDeclarator declarator = varDecl.declarations().get(0);
        com.jsparser.ast.Literal literal = (com.jsparser.ast.Literal) declarator.init();

        System.out.println("Literal value: " + literal.value());
        System.out.println("Literal value type: " + literal.value().getClass().getName());

        // Debug the conversion
        double d = 9.223372036854776E18;
        long asLong = (long) d;
        double back = (double) asLong;
        System.out.println("d=" + d);
        System.out.println("asLong=" + asLong);
        System.out.println("back=" + back);
        System.out.println("equal=" + (back == d));
        System.out.println("d >= Long.MIN_VALUE=" + (d >= Long.MIN_VALUE));
        System.out.println("d <= Long.MAX_VALUE=" + (d <= Long.MAX_VALUE));
    }

    @Test
    void testCuratedComparison() throws Exception {
        Path sourceFile = Path.of("test-oracles/test262/test/built-ins/GeneratorPrototype/return/try-catch-following-catch.js");
        Path cacheFile = Path.of("test-oracles/test262-cache/built-ins/GeneratorPrototype/return/try-catch-following-catch.js.json");

        if (!Files.exists(sourceFile) || !Files.exists(cacheFile)) {
            System.out.println("Skipping - files not found");
            return;
        }

        ObjectMapper mapper = TestObjectMapper.get();

        // Parse expected
        String expectedJson = Files.readString(cacheFile);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> expectedObj = (java.util.Map<String, Object>) mapper.readValue(expectedJson, Object.class);
        expectedObj.remove("_metadata");

        // Parse actual
        String source = Files.readString(sourceFile);
        boolean isModule = sourceFile.toString().endsWith(".mjs");
        Program ast = Parser.parse(source, isModule);
        String actualJson = mapper.writeValueAsString(ast);
        Object actualObj = mapper.readValue(actualJson, Object.class);

        System.out.println("Expected JSON (first 500 chars):\n" + expectedJson.substring(0, Math.min(500, expectedJson.length())));
        System.out.println("\nActual JSON (first 500 chars):\n" + actualJson.substring(0, Math.min(500, actualJson.length())));

        // Compare
        boolean equals = java.util.Objects.deepEquals(expectedObj, actualObj);
        System.out.println("\nEquals: " + equals);

        if (!equals) {
            // Find first difference
            findDiff("", expectedObj, actualObj);
        }
    }

    @SuppressWarnings("unchecked")
    private void findDiff(String path, Object expected, Object actual) {
        if (expected == null && actual == null) return;
        if (expected == null || actual == null) {
            System.out.println("Diff at " + path + ": expected=" + expected + ", actual=" + actual);
            return;
        }
        if (!expected.getClass().equals(actual.getClass())) {
            System.out.println("Type diff at " + path + ": expected=" + expected.getClass() + ", actual=" + actual.getClass());
            return;
        }
        if (expected instanceof java.util.Map) {
            java.util.Map<String, Object> expMap = (java.util.Map<String, Object>) expected;
            java.util.Map<String, Object> actMap = (java.util.Map<String, Object>) actual;

            // Check for missing keys in actual
            for (String key : expMap.keySet()) {
                if (!actMap.containsKey(key)) {
                    System.out.println("Missing key at " + path + "." + key);
                }
            }

            // Check for extra keys in actual
            for (String key : actMap.keySet()) {
                if (!expMap.containsKey(key)) {
                    System.out.println("Extra key at " + path + "." + key + " = " + actMap.get(key));
                }
            }

            // Recurse into matching keys
            for (String key : expMap.keySet()) {
                if (actMap.containsKey(key)) {
                    Object expVal = expMap.get(key);
                    Object actVal = actMap.get(key);
                    if (!java.util.Objects.deepEquals(expVal, actVal)) {
                        findDiff(path + "." + key, expVal, actVal);
                    }
                }
            }
        } else if (expected instanceof java.util.List) {
            java.util.List<Object> expList = (java.util.List<Object>) expected;
            java.util.List<Object> actList = (java.util.List<Object>) actual;
            if (expList.size() != actList.size()) {
                System.out.println("List size diff at " + path + ": expected=" + expList.size() + ", actual=" + actList.size());
            }
            for (int i = 0; i < Math.min(expList.size(), actList.size()); i++) {
                if (!java.util.Objects.deepEquals(expList.get(i), actList.get(i))) {
                    findDiff(path + "[" + i + "]", expList.get(i), actList.get(i));
                }
            }
        } else if (!expected.equals(actual)) {
            System.out.println("Value diff at " + path + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
