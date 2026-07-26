package com.opshub.validation;

import com.opshub.validation.application.ContentParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContentParserTest {
    private final ContentParser parser = new ContentParser();

    @Test
    void rejectsContentWithoutABodyAfterTheHeader() {
        ContentParser.ParsedContent parsed = parser.parse("Expected header");

        assertThat(parsed.header()).isEqualTo("Expected header");
        assertThat(parsed.body()).isBlank();
    }

    @Test
    void usesTheFirstNonEmptyLineAsHeaderAndPreservesTheRemainingBodyLines() {
        ContentParser.ParsedContent parsed = parser.parse("\n\nExpected header\nFirst body line\n\nLast body line");

        assertThat(parsed.header()).isEqualTo("Expected header");
        assertThat(parsed.body()).isEqualTo("First body line\n\nLast body line");
    }

    @Test
    void preservesTheOriginalLineSeparatorsInTheBody() {
        ContentParser.ParsedContent parsed = parser.parse("Expected header\r\nFirst body line\r\nSecond body line");

        assertThat(parsed.body()).isEqualTo("First body line\r\nSecond body line");
    }
}
