package com.opshub.generation.api;

import com.opshub.generation.application.TestPlanService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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

    @PostMapping("/plans/{planId}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@PathVariable UUID planId, @RequestBody RevisionRequest request) {
        testPlanService.approve(planId, request.expectedRevision());
    }

    public record RevisionRequest(int expectedRevision) {
    }
}
