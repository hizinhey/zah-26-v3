package com.opshub.generation.api;

import com.opshub.generation.application.TestPlanNotFoundException;
import com.opshub.generation.application.TestPlanService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class TestPlanController {
    private final TestPlanService testPlanService;

    public TestPlanController(TestPlanService testPlanService) {
        this.testPlanService = testPlanService;
    }

    @PostMapping("/operations/{operationId}/plans")
    public TestPlanService.TestPlanDto generate(@PathVariable UUID operationId, @RequestBody RevisionRequest request) {
        return testPlanService.generate(operationId, request.expectedRevision());
    }

    @GetMapping("/plans/{planId}")
    public TestPlanService.TestPlanDto get(@PathVariable UUID planId) {
        return testPlanService.findById(planId);
    }

    @PostMapping("/plans/{planId}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@PathVariable UUID planId, @RequestBody RevisionRequest request) {
        testPlanService.approve(planId, request.expectedRevision());
    }

    public record RevisionRequest(int expectedRevision) {
    }
}

@RestControllerAdvice
class TestPlanErrorHandler {
    @ExceptionHandler(TestPlanNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(TestPlanNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("PLAN_NOT_FOUND", exception.getMessage()));
    }

    record ErrorResponse(String code, String message) {
    }
}
