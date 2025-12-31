package com.jsparser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility methods for Test262 test suite parsing.
 */
public class Test262Utils {

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
        "/\\*---\\n([\\s\\S]*?)\\n---\\*/");

    /**
     * Check if source has 'module' flag in Test262 frontmatter
     */
    public static boolean hasModuleFlag(String source) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(source);

        if (!matcher.find()) return false;

        String yaml = matcher.group(1);

        // Check inline format: flags: [module, async]
        // Need (?m) for ^ to match start of line
        // Use [^\]]* to match only within the brackets, not past them
        if (yaml.matches("(?sm).*^flags:\\s*\\[[^\\]]*\\bmodule\\b[^\\]]*\\].*")) {
            return true;
        }

        // Check multiline format:
        //   flags:
        //     - module
        if (yaml.contains("- module")) {
            return true;
        }

        return false;
    }

    /**
     * Check if source is a negative parse test in Test262 frontmatter
     */
    public static boolean isNegativeParseTest(String source) {
        // Parse Test262 YAML frontmatter for negative parse test
        // Format:
        //   negative:
        //     phase: parse
        //     type: SyntaxError
        Matcher matcher = FRONTMATTER_PATTERN.matcher(source);

        if (!matcher.find()) return false;

        String yaml = matcher.group(1);

        // Check if negative section exists with phase: parse
        // Match "negative:" followed by any content, then "phase: parse"
        return yaml.matches("(?sm).*^negative:\\s*\\n.*?^\\s*phase:\\s*parse.*");
    }

    /**
     * Check if source has 'onlyStrict' flag in Test262 frontmatter
     */
    public static boolean hasOnlyStrictFlag(String source) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(source);

        if (!matcher.find()) return false;

        String yaml = matcher.group(1);

        // Check inline format: flags: [onlyStrict]
        if (yaml.matches("(?sm).*^flags:\\s*\\[[^\\]]*\\bonlyStrict\\b[^\\]]*\\].*")) {
            return true;
        }

        // Check multiline format:
        //   flags:
        //     - onlyStrict
        if (yaml.contains("- onlyStrict")) {
            return true;
        }

        return false;
    }
}
