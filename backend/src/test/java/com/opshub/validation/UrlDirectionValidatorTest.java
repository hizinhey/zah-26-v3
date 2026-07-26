package com.opshub.validation;

import com.opshub.validation.application.UrlDirectionValidator;
import com.opshub.validation.domain.FieldStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @Test
    void acceptsAndroidCustomDeeplinksWithAnAuthority() {
        assertThat(validator.validate("opshub://campaign/offer/123").status()).isEqualTo(FieldStatus.PASSED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///tmp/offer", "data:text/plain,offer", "javascript:alert(1)", "intent://example.test/offer"})
    void rejectsReservedOrDangerousSchemes(String rawValue) {
        assertThat(validator.validate(rawValue).status()).isEqualTo(FieldStatus.FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https:/offer", "opshub:/offer"})
    void rejectsUrlsAndDeeplinksWithoutAHost(String rawValue) {
        assertThat(validator.validate(rawValue).status()).isEqualTo(FieldStatus.FAILED);
    }
}
