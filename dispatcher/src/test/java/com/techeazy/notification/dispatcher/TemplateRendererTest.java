package com.techeazy.notification.dispatcher;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateRendererTest {

    @Test
    void substitutesVariablesWithOptionalWhitespace() {
        assertThat(TemplateRenderer.render("Hi {{name}}, code {{ code }}.", Map.of("name", "Ann", "code", "42")))
                .isEqualTo("Hi Ann, code 42.");
    }

    @Test
    void valuesContainingRegexSpecialsAreInsertedLiterally() {
        assertThat(TemplateRenderer.render("Pay {{amt}}", Map.of("amt", "$5 \\ done"))).isEqualTo("Pay $5 \\ done");
    }

    @Test
    void missingVariablesFailInsteadOfRenderingBlank() {
        assertThatThrownBy(() -> TemplateRenderer.render("Hi {{name}} {{code}}", Map.of("code", "1")))
                .isInstanceOf(TemplateRenderer.MissingVariableException.class)
                .hasMessageContaining("name");
    }

    @Test
    void nullTemplateStaysNull() {
        assertThat(TemplateRenderer.render(null, Map.of())).isNull();
    }
}
