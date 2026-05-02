package com.jimmyhmiller.harmonica;

import org.junit.jupiter.api.Test;

public class TestSimple {
    @Test
    void test() throws Exception {
        Parser.parse("var x = 1;");
        System.out.println("✓ Simple var works");
    }
}
