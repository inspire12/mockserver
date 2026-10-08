package org.mockserver.serialization.java;

import org.junit.Test;
import org.mockserver.model.HttpTemplate;

import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockserver.character.Character.NEW_LINE;

public class FluentJavaBuilderTest {

    @Test
    public void shouldWriteEachValueAsAJavaLiteralOfItsType() {
        assertThat(FluentJavaBuilder.literal("a \"quoted\"\nvalue é"), is("\"a \\\"quoted\\\"\\nvalue \\u00E9\""));
        assertThat(FluentJavaBuilder.literal(7), is("7"));
        assertThat(FluentJavaBuilder.literal(7L), is("7L"));
        assertThat(FluentJavaBuilder.literal(0.25), is("0.25"));
        assertThat(FluentJavaBuilder.literal(1.0E-7), is("1.0E-7"));
        assertThat(FluentJavaBuilder.literal(Double.NaN), is("Double.NaN"));
        assertThat(FluentJavaBuilder.literal(Double.NEGATIVE_INFINITY), is("Double.NEGATIVE_INFINITY"));
        assertThat(FluentJavaBuilder.literal(true), is("true"));
        assertThat(FluentJavaBuilder.literal(HttpTemplate.TemplateType.VELOCITY), is("org.mockserver.model.HttpTemplate.TemplateType.VELOCITY"));
        assertThat(FluentJavaBuilder.literal(null), is("null"));
        assertThrows(IllegalArgumentException.class, () -> FluentJavaBuilder.literal(new Object()));
    }

    @Test
    public void shouldWriteACallOnlyForAValueThatIsSet() {
        String code = new FluentJavaBuilder(1, "Thing.thing()")
            .with("withName", "name")
            .with("withMissing", null)
            .withLiterals("withCodes", Arrays.asList(1, 2))
            .withLiterals("withNoCodes", null)
            .withBytes("withBytes", new byte[]{0, 1, (byte) 255})
            .comment(false, "NOT WRITTEN")
            .build();

        assertThat(code, is(NEW_LINE +
            "        Thing.thing()" + NEW_LINE +
            "                .withName(\"name\")" + NEW_LINE +
            "                .withCodes(1, 2)" + NEW_LINE +
            "                .withBytes(java.util.Base64.getDecoder().decode(\"AAH/\"))"));
    }
}
