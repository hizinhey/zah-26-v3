package com.opshub.validation.llm;

import java.util.List;

record GeminiResponse(String policyVersion, List<Finding> findings) {
    record Finding(
            String fieldName,
            String status,
            String message,
            Integer start,
            Integer end,
            String suggestion,
            String severity,
            Double confidence
    ) {
    }
}
