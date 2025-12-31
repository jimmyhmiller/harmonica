package com.jsparser;

import com.jsparser.jackson.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;

public class LineSepDebugTest {
    public static void main(String[] args) throws Exception {
        String testFile = "../test-oracles/test262/test/language/literals/string/line-separator.js";
        String cacheFile = "../test-oracles/test262-cache/language/literals/string/line-separator.js.json";

        String source = Files.readString(Path.of(testFile));
        String expected = Files.readString(Path.of(cacheFile));

        ObjectMapper mapper = HarmonicaJackson.createObjectMapper();

        var prog = Parser.parse(source, false);
        String actual = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(prog);

        JsonNode expectedJson = mapper.readTree(expected);
        JsonNode actualJson = mapper.readTree(actual);

        System.out.println("=== SOURCE (around line 18) ===");
        String[] lines = source.split("\n");
        for (int i = Math.max(0, 15); i < Math.min(lines.length, 20); i++) {
            System.out.printf("Line %d: %s%n", i+1, lines[i].replace("\u2028", "\\u2028"));
        }

        System.out.println("\n=== EXPECTED ===");
        System.out.println(expectedJson.path("body").get(0).toPrettyString());

        System.out.println("\n=== ACTUAL ===");
        System.out.println(actualJson.path("body").get(0).toPrettyString());
    }
}
