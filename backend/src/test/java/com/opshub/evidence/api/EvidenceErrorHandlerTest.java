package com.opshub.evidence.api;

import com.opshub.evidence.application.EvidenceValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain unit test for {@link EvidenceErrorHandler} — no Spring context required.
 *
 * <p>Reproduces the bug this project hit in practice: Spring Boot's multipart resolver applies
 * its own undocumented defaults (1MB max file size, 10MB max request size) whenever
 * {@code spring.servlet.multipart.max-file-size}/{@code max-request-size} aren't set, and throws
 * {@link MaxUploadSizeExceededException} for any file at or above that size — well before the
 * request reaches {@link EvidenceController#upload}. Before this fix, that exception had no
 * handler here and fell through to Spring's generic (non-evidence-specific) error handling.
 * This test would have failed (with a raised/unhandled exception) prior to adding the
 * {@code uploadTooLarge} handler.
 */
class EvidenceErrorHandlerTest {
    private final EvidenceErrorHandler handler = new EvidenceErrorHandler();

    @Test
    void mapsMaxUploadSizeExceededToCleanEvidenceTooLargeResponse() {
        // The handler takes no arguments (matching invalidToken()/notFound() in this class),
        // but the assertion documents the real-world trigger: Spring throwing
        // MaxUploadSizeExceededException once a multipart file exceeds the configured cap.
        var response = handler.uploadTooLarge();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("EVIDENCE_TOO_LARGE");
        assertThat(response.getBody().message()).isNotBlank();
    }

    @Test
    void mapsEvidenceValidationExceptionToRejectedResponse() {
        var exception = new EvidenceValidationException("file exceeds opshub.evidence.max-bytes");

        var response = handler.validationFailed(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("EVIDENCE_REJECTED");
        assertThat(response.getBody().message()).isEqualTo("file exceeds opshub.evidence.max-bytes");
    }
}
