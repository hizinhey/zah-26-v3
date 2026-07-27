package com.opshub.evidence.api;

import com.opshub.evidence.application.EvidenceService;
import com.opshub.evidence.application.EvidenceValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/test-results/{testResultId}/evidence")
public class EvidenceController {
    private final EvidenceService evidenceService;

    public EvidenceController(EvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    @PostMapping
    public ResponseEntity<EvidenceResponse> upload(@PathVariable UUID testResultId,
                                                     @RequestParam String evidenceType,
                                                     @RequestParam long declaredSize,
                                                     @RequestParam String declaredSha256,
                                                     @RequestParam MultipartFile file) {
        try (var content = file.getInputStream()) {
            UUID evidenceId = evidenceService.store(testResultId, evidenceType, file.getOriginalFilename(),
                    declaredSize, declaredSha256, content);
            return ResponseEntity.status(HttpStatus.CREATED).body(new EvidenceResponse(evidenceId));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public record EvidenceResponse(UUID id) {
    }
}

@RestControllerAdvice
class EvidenceErrorHandler {
    @ExceptionHandler(EvidenceValidationException.class)
    ResponseEntity<ErrorResponse> validationFailed(EvidenceValidationException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("EVIDENCE_REJECTED", exception.getMessage()));
    }

    record ErrorResponse(String code, String message) {
    }
}
