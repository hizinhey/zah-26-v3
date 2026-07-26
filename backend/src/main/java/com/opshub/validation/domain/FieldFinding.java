package com.opshub.validation.domain;

public record FieldFinding(
        String fieldName,
        String validatorType,
        FieldStatus status,
        String issue,
        String location,
        String suggestion,
        String severity,
        Double confidence
) {
    public static FieldFinding passed(String fieldName, String validatorType) {
        return new FieldFinding(fieldName, validatorType, FieldStatus.PASSED, null, null, null, null, null);
    }

    public static FieldFinding failed(String fieldName, String validatorType, String issue) {
        return new FieldFinding(fieldName, validatorType, FieldStatus.FAILED, issue, null, null, "ERROR", null);
    }

    public static FieldFinding unableToCheck(String fieldName, String validatorType, String issue) {
        return new FieldFinding(fieldName, validatorType, FieldStatus.UNABLE_TO_CHECK, issue, null, null, "ERROR", null);
    }

    public FieldFinding forField(String fieldName) {
        return new FieldFinding(fieldName, validatorType, status, issue, location, suggestion, severity, confidence);
    }
}
