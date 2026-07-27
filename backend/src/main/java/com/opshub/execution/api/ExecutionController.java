package com.opshub.execution.api;

import com.opshub.execution.application.ExecutionDto;
import com.opshub.execution.application.ExecutionNotFoundException;
import com.opshub.execution.application.ExecutionService;
import com.opshub.execution.application.HubNotOnlineException;
import com.opshub.execution.application.OperationNotApprovedException;
import com.opshub.operation.application.OperationNotFoundException;
import com.opshub.operation.application.RevisionConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.UUID;

@RestController
public class ExecutionController {
    private final ExecutionService executionService;

    public ExecutionController(ExecutionService executionService) {
        this.executionService = executionService;
    }

    @PostMapping("/api/v1/operations/{operationId}/executions")
    public ResponseEntity<ExecutionResponse> start(@PathVariable UUID operationId, @RequestBody StartExecutionRequest request) {
        ExecutionDto execution = executionService.start(operationId, request.expectedRevision(), request.idempotencyKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(ExecutionResponse.from(execution));
    }

    @GetMapping("/api/v1/executions/{executionId}")
    public ExecutionStatusResponse get(@PathVariable UUID executionId) {
        return ExecutionStatusResponse.from(executionService.findById(executionId));
    }

    public record StartExecutionRequest(int expectedRevision, String idempotencyKey) {
    }

    public record ExecutionResponse(UUID id, UUID operationId, UUID planId, int sourceRevision, String status) {
        private static ExecutionResponse from(ExecutionDto execution) {
            return new ExecutionResponse(execution.id(), execution.operationId(), execution.planId(),
                    execution.sourceRevision(), execution.status());
        }
    }

    public record TestResultResponse(UUID id, UUID testCaseId, int attempt, String status, Long durationMs, String errorCategory) {
        private static TestResultResponse from(ExecutionService.TestResultDto dto) {
            return new TestResultResponse(dto.id(), dto.testCaseId(), dto.attempt(), dto.status(), dto.durationMs(), dto.errorCategory());
        }
    }

    public record ExecutionStatusResponse(UUID id, UUID operationId, UUID planId, int sourceRevision, String status,
                                           List<TestResultResponse> results) {
        private static ExecutionStatusResponse from(ExecutionService.ExecutionStatusDto dto) {
            ExecutionDto execution = dto.execution();
            return new ExecutionStatusResponse(execution.id(), execution.operationId(), execution.planId(),
                    execution.sourceRevision(), execution.status(),
                    dto.results().stream().map(TestResultResponse::from).toList());
        }
    }
}

@RestControllerAdvice
class ExecutionErrorHandler {
    @ExceptionHandler(RevisionConflictException.class)
    ResponseEntity<ErrorResponse> revisionConflict(RevisionConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("REVISION_CONFLICT", exception.getMessage(), exception.getCurrentRevision()));
    }

    @ExceptionHandler(OperationNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(OperationNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("OPERATION_NOT_FOUND", exception.getMessage(), null));
    }

    @ExceptionHandler(OperationNotApprovedException.class)
    ResponseEntity<ErrorResponse> notApproved(OperationNotApprovedException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("OPERATION_NOT_APPROVED", exception.getMessage(), null));
    }

    @ExceptionHandler(HubNotOnlineException.class)
    ResponseEntity<ErrorResponse> hubOffline(HubNotOnlineException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("HUB_NOT_ONLINE", exception.getMessage(), null));
    }

    @ExceptionHandler(ExecutionNotFoundException.class)
    ResponseEntity<ErrorResponse> executionNotFound(ExecutionNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("EXECUTION_NOT_FOUND", exception.getMessage(), null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", exception.getMessage(), null));
    }

    record ErrorResponse(String code, String message, Integer currentRevision) {
    }
}
