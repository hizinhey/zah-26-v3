package com.opshub.evidence.api;

import com.opshub.evidence.application.EvidenceService;
import com.opshub.evidence.application.EvidenceValidationException;
import com.opshub.hub.application.HubTokenValidator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Hub-facing evidence upload endpoint - requires the same {@code X-Hub-Token} shared-secret
 * check every other Hub-facing endpoint enforces ({@link com.opshub.hub.api.HubPollingController},
 * {@link com.opshub.hub.api.HubWebSocketConfig}). Without this (I1), Nginx exposing all of
 * {@code /api/} publicly made this an unauthenticated disk-fill vector.
 */
@RestController
@RequestMapping("/api/v1/test-results/{testResultId}/evidence")
public class EvidenceController {
    private final EvidenceService evidenceService;
    private final HubTokenValidator hubTokenValidator;

    public EvidenceController(EvidenceService evidenceService, HubTokenValidator hubTokenValidator) {
        this.evidenceService = evidenceService;
        this.hubTokenValidator = hubTokenValidator;
    }

    @PostMapping
    public ResponseEntity<EvidenceResponse> upload(@PathVariable UUID testResultId,
                                                     @RequestHeader("X-Hub-Token") String token,
                                                     @RequestParam String evidenceType,
                                                     @RequestParam long declaredSize,
                                                     @RequestParam String declaredSha256,
                                                     @RequestParam MultipartFile file) {
        if (!hubTokenValidator.isValid(token)) {
            throw new InvalidHubTokenException();
        }
        try (var content = file.getInputStream()) {
            UUID evidenceId = evidenceService.store(testResultId, evidenceType, file.getOriginalFilename(),
                    declaredSize, declaredSha256, content);
            return ResponseEntity.status(HttpStatus.CREATED).body(new EvidenceResponse(evidenceId));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @GetMapping
    public List<EvidenceItemResponse> list(@PathVariable UUID testResultId) {
        return evidenceService.listForTestResult(testResultId).stream()
                .map(EvidenceItemResponse::from)
                .toList();
    }

    public record EvidenceResponse(UUID id) {
    }

    public record EvidenceItemResponse(UUID id, String evidenceType, long sizeBytes, String checksum, Instant createdAt) {
        static EvidenceItemResponse from(EvidenceService.EvidenceSummary summary) {
            return new EvidenceItemResponse(summary.id(), summary.evidenceType(), summary.sizeBytes(),
                    summary.checksum(), summary.createdAt());
        }
    }

    static class InvalidHubTokenException extends RuntimeException {
    }
}

@RestControllerAdvice
class EvidenceErrorHandler {
    @ExceptionHandler(EvidenceValidationException.class)
    ResponseEntity<ErrorResponse> validationFailed(EvidenceValidationException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("EVIDENCE_REJECTED", exception.getMessage()));
    }

    @ExceptionHandler(EvidenceController.InvalidHubTokenException.class)
    ResponseEntity<Void> invalidToken() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    @ExceptionHandler(com.opshub.evidence.application.EvidenceNotFoundException.class)
    ResponseEntity<Void> notFound() {
        return ResponseEntity.notFound().build();
    }

    record ErrorResponse(String code, String message) {
    }
}
