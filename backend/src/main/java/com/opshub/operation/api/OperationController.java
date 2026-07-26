package com.opshub.operation.api;

import com.opshub.operation.application.OperationNotFoundException;
import com.opshub.operation.application.OperationService;
import com.opshub.operation.application.RevisionConflictException;
import com.opshub.operation.application.SaveOaCommand;
import com.opshub.operation.application.UnsupportedPlatformException;
import com.opshub.operation.domain.OfficialAccount;
import com.opshub.operation.domain.Operation;
import com.opshub.operation.domain.OperationStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operations")
public class OperationController {
    private final OperationService operationService;

    public OperationController(OperationService operationService) {
        this.operationService = operationService;
    }

    @PostMapping
    public ResponseEntity<OperationResponse> create(@RequestBody CreateOperationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(OperationResponse.from(operationService.create(request.jiraId())));
    }

    @GetMapping("/{operationId}")
    public OperationResponse get(@PathVariable UUID operationId) {
        return OperationResponse.from(operationService.get(operationId));
    }

    @PutMapping("/{operationId}/oas")
    public OperationResponse replaceOas(@PathVariable UUID operationId, @RequestBody ReplaceOasRequest request) {
        return OperationResponse.from(operationService.replaceOas(operationId, request.expectedRevision(), request.oas()));
    }

    public record CreateOperationRequest(String jiraId) {
    }

    public record ReplaceOasRequest(int expectedRevision, List<SaveOaCommand> oas) {
    }

    public record OperationResponse(
            UUID id,
            String jiraId,
            int revision,
            OperationStatus status,
            Instant createdAt,
            Instant updatedAt,
            List<OfficialAccountResponse> oas
    ) {
        private static OperationResponse from(Operation operation) {
            return new OperationResponse(
                    operation.getId(),
                    operation.getJiraId(),
                    operation.getRevision(),
                    operation.getStatus(),
                    operation.getCreatedAt(),
                    operation.getUpdatedAt(),
                    operation.getOfficialAccounts().stream().map(OfficialAccountResponse::from).toList()
            );
        }
    }

    public record OfficialAccountResponse(
            UUID id,
            int oaOrder,
            String platform,
            String oaName,
            String thumbnailUrl,
            String content,
            String buttonText,
            String redirectUrl
    ) {
        private static OfficialAccountResponse from(OfficialAccount account) {
            return new OfficialAccountResponse(
                    account.getId(),
                    account.getOaOrder(),
                    account.getPlatform(),
                    account.getOaName(),
                    account.getThumbnailUrl(),
                    account.getContent(),
                    account.getButtonText(),
                    account.getRedirectUrl()
            );
        }
    }
}

@RestControllerAdvice
class OperationErrorHandler {
    @ExceptionHandler(RevisionConflictException.class)
    ResponseEntity<ErrorResponse> revisionConflict(RevisionConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("REVISION_CONFLICT", exception.getMessage(), exception.getCurrentRevision()));
    }

    @ExceptionHandler(UnsupportedPlatformException.class)
    ResponseEntity<ErrorResponse> unsupportedPlatform(UnsupportedPlatformException exception) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("UNSUPPORTED_PLATFORM", exception.getMessage(), null));
    }

    @ExceptionHandler({IllegalArgumentException.class})
    ResponseEntity<ErrorResponse> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_REQUEST", exception.getMessage(), null));
    }

    @ExceptionHandler(OperationNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(OperationNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("OPERATION_NOT_FOUND", exception.getMessage(), null));
    }

    record ErrorResponse(String code, String message, Integer currentRevision) {
    }
}
