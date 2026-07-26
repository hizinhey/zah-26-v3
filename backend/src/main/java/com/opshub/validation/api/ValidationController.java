package com.opshub.validation.api;

import com.opshub.validation.application.ValidationRunDto;
import com.opshub.validation.application.ValidationService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operations")
public class ValidationController {
    private final ValidationService validationService;

    public ValidationController(ValidationService validationService) {
        this.validationService = validationService;
    }

    @PostMapping("/{operationId}/validate")
    public ValidationRunDto validate(@PathVariable UUID operationId, @RequestBody ValidateOperationRequest request) {
        return validationService.validate(operationId, request.expectedRevision());
    }

    public record ValidateOperationRequest(int expectedRevision) {
    }
}
