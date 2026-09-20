package com.techeazy.notification.application;

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
    void valuesAreNeverReinterpretedAsPlaceholders() {
        assertThat(TemplateRenderer.render("{{a}}", Map.of("a", "{{b}}", "b", "secret"))).isEqualTo("{{b}}");
    }

    @Test
    void missingVariablesFailInsteadOfRenderingBlank() {
        assertThatThrownBy(() -> TemplateRenderer.render("Hi {{name}} {{code}}", Map.of("code", "1")))
                .isInstanceOf(TemplateRenderer.MissingVariableException.class)
                .hasMessageContaining("name");
    }

    @Test
    void lenientRenderFillsKnownVariablesAndLeavesTheRestVisible() {
        assertThat(TemplateRenderer.renderLenient("Hi {{name}}, code {{ code }}", Map.of("name", "Ann")))
                .isEqualTo("Hi Ann, code {{ code }}");
        assertThat(TemplateRenderer.renderLenient(null, Map.of())).isNull();
    }

    @Test
    void nullTemplateStaysNull() {
        assertThat(TemplateRenderer.render(null, Map.of())).isNull();
    }

    @Test
    void variablesAreListedInOrderOfFirstAppearanceAcrossAllTexts() {
        assertThat(TemplateRenderer.variables("Hi {{name}}", "{{code}} for {{ name }} at {{recipient}}", null))
                .containsExactly("name", "code", "recipient");
    }

    @Test
    void requiredVariablesExcludeTheBuiltInRecipient() {
        assertThat(TemplateRenderer.requiredVariables("Hello {{recipient}}, code {{code}}")).containsExactly("code");
    }

    @Test
    void strayBracesAreDetectedButWellFormedPlaceholdersAreNot() {
        assertThat(TemplateRenderer.hasStrayBraces("Hi {{name}} and {{ code }}")).isFalse();
        assertThat(TemplateRenderer.hasStrayBraces("Hi {{name}")).isTrue();
        assertThat(TemplateRenderer.hasStrayBraces("Hi name}}")).isTrue();
        assertThat(TemplateRenderer.hasStrayBraces("Hi {{ na me }}")).isTrue();
        assertThat(TemplateRenderer.hasStrayBraces(null)).isFalse();
    }
}
