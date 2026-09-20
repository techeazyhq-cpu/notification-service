package com.techeazy.notification.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LikePatternsTest {

    @Test
    void wrapsLowerCasedTermInWildcards() {
        assertThat(LikePatterns.contains("  Ann@Example.com ")).isEqualTo("%ann@example.com%");
    }

    @Test
    void wildcardsInTheTermMatchLiterally() {
        assertThat(LikePatterns.contains("50%_off")).isEqualTo("%50\\%\\_off%");
    }

    @Test
    void backslashesAreEscapedFirst() {
        assertThat(LikePatterns.contains("a\\b")).isEqualTo("%a\\\\b%");
    }
}
