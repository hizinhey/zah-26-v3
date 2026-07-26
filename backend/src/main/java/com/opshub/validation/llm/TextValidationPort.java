package com.opshub.validation.llm;

import com.opshub.validation.domain.FieldFinding;

import java.util.List;

public interface TextValidationPort {
    List<FieldFinding> validate(TextValidationRequest request);

    String model();

    String policyVersion();

    record TextValidationRequest(List<TextField> fields) {
        public TextValidationRequest {
            fields = List.copyOf(fields);
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("At least one text field is required");
            }
        }
    }

    record TextField(String fieldName, String value) {
        public TextField {
            if (fieldName == null || fieldName.isBlank() || value == null) {
                throw new IllegalArgumentException("Text field name and value are required");
            }
        }
    }
}
