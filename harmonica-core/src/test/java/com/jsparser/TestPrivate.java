package com.jsparser;

import org.junit.jupiter.api.Test;

public class TestPrivate {
    @Test
    void testPrivateMemberAccess() throws Exception {
        // Private names must be declared in a class and used within that class's scope
        String source = "class C { #x; method() { return this.#x; } }";
        Parser.parse(source);
        System.out.println("✓ Private member access works");
    }
}
