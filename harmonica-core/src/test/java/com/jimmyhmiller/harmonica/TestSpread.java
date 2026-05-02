package com.jimmyhmiller.harmonica;

import org.junit.jupiter.api.Test;

public class TestSpread {
    @Test
    void testSpreadProperty() throws Exception {
        String source = "var obj = {...x};";
        Parser.parse(source);
        System.out.println("✓ Spread property works");
    }
}
