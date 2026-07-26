package com.opshub.validation;

import com.opshub.validation.application.UrlDirectionValidator;
import com.opshub.validation.domain.FieldStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UrlDirectionValidatorTest {
    private final UrlDirectionValidator validator = new UrlDirectionValidator();

    @Test
    void rejectsRawWhitespaceRatherThanSilentlyNormalizingIt() {
        assertThat(validator.validate(" https://example.test/offer").status()).isEqualTo(FieldStatus.FAILED);
    }

    @Test
    void rejectsStagingUrls() {
        assertThat(validator.validate("https://stg-example.test/offer").status()).isEqualTo(FieldStatus.FAILED);
    }

    @Test
    void rejectsMalformedUrls() {
        assertThat(validator.validate("https://exa mple.test/offer").status()).isEqualTo(FieldStatus.FAILED);
    }

    @Test
    void acceptsHttpsUrls() {
        assertThat(validator.validate("https://example.test/offer").status()).isEqualTo(FieldStatus.PASSED);
    }
}
