package com.jsparser.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.math.BigInteger;

/**
 * Custom serializer that formats numbers like JavaScript does.
 * Integers are serialized without decimal points, while floats keep their decimal representation.
 */
public class JavaScriptNumberSerializer extends JsonSerializer<Object> {
    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
        } else if (value instanceof Double d) {
            if (d.isInfinite()) {
                gen.writeNull();
            } else if (d.isNaN()) {
                gen.writeNull();
            } else if (d == Math.floor(d) && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE) {
                gen.writeNumber(d.longValue());
            } else {
                gen.writeNumber(d);
            }
        } else if (value instanceof Float f) {
            if (f.isInfinite() || f.isNaN()) {
                gen.writeNull();
            } else if (f == Math.floor(f) && f >= Long.MIN_VALUE && f <= Long.MAX_VALUE) {
                gen.writeNumber(f.longValue());
            } else {
                gen.writeNumber(f);
            }
        } else if (value instanceof Long l) {
            gen.writeNumber(l);
        } else if (value instanceof Integer i) {
            gen.writeNumber(i);
        } else if (value instanceof BigInteger bi) {
            gen.writeNumber(bi);
        } else if (value instanceof Number n) {
            gen.writeNumber(n.doubleValue());
        } else if (value instanceof Boolean b) {
            gen.writeBoolean(b);
        } else if (value instanceof String s) {
            gen.writeString(s);
        } else {
            gen.writeObject(value);
        }
    }
}
