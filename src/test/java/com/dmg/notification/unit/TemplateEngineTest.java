package com.dmg.notification.unit;

import com.dmg.notification.service.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateEngineTest {

    private final TemplateEngine engine = new TemplateEngine();

    @Test
    void substitutesAllVariables() {
        String template = "Hello {{name}}, your order {{orderId}} is ready!";
        String result = engine.render(template, Map.of("name", "Alice", "orderId", "ORD-123"));
        assertThat(result).isEqualTo("Hello Alice, your order ORD-123 is ready!");
    }

    @Test
    void leavesUnknownVariablesAsIs() {
        String template = "Hello {{name}}, ref: {{unknown}}";
        String result = engine.render(template, Map.of("name", "Bob"));
        assertThat(result).isEqualTo("Hello Bob, ref: {{unknown}}");
    }

    @Test
    void handlesNullTemplate() {
        assertThat(engine.render(null, Map.of("k", "v"))).isNull();
    }

    @Test
    void handlesNullVariables() {
        String template = "Hello {{name}}";
        assertThat(engine.render(template, null)).isEqualTo(template);
    }

    @Test
    void handlesEmptyVariables() {
        String template = "Static body";
        assertThat(engine.render(template, Map.of())).isEqualTo("Static body");
    }

    @Test
    void handlesMultipleOccurrencesOfSameVariable() {
        String template = "{{name}} ordered {{item}}. Thank you {{name}}!";
        String result = engine.render(template, Map.of("name", "Carol", "item", "Pizza"));
        assertThat(result).isEqualTo("Carol ordered Pizza. Thank you Carol!");
    }
}
