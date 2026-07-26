package com.opshub.validation.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class TextValidationPrompt {
    private TextValidationPrompt() {
    }

    static String requestBody(ObjectMapper objectMapper, TextValidationPort.TextValidationRequest request) throws JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode contents = root.putArray("contents");
        ObjectNode content = contents.addObject();
        ArrayNode parts = content.putArray("parts");
        parts.addObject().put("text", prompt(request));

        ObjectNode generationConfig = root.putObject("generationConfig");
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.set("responseSchema", responseSchema(objectMapper));
        return objectMapper.writeValueAsString(root);
    }

    private static String prompt(TextValidationPort.TextValidationRequest request) {
        StringBuilder prompt = new StringBuilder("Validate Vietnamese spelling, sentence casing, spacing, UX wording, and misleading claims. ")
                .append("Return exactly one finding for each supplied field. Do not add fields or executable code. ")
                .append("Use policyVersion gemini-text-v1. ")
                .append("Offsets are zero-based UTF-16 indexes into the supplied field value; use null offsets when there is no location. ")
                .append("Fields:\n");
        for (TextValidationPort.TextField field : request.fields()) {
            prompt.append(field.fieldName()).append(": ").append(field.value()).append('\n');
        }
        return prompt.toString();
    }

    private static ObjectNode responseSchema(ObjectMapper objectMapper) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "OBJECT");
        root.put("additionalProperties", false);
        ObjectNode properties = root.putObject("properties");
        properties.putObject("policyVersion").put("type", "STRING");
        ObjectNode findings = properties.putObject("findings");
        findings.put("type", "ARRAY");
        ObjectNode finding = findings.putObject("items");
        finding.put("type", "OBJECT");
        finding.put("additionalProperties", false);
        ObjectNode findingProperties = finding.putObject("properties");
        findingProperties.putObject("fieldName").put("type", "STRING");
        ObjectNode status = findingProperties.putObject("status");
        status.put("type", "STRING");
        ArrayNode statusValues = status.putArray("enum");
        statusValues.add("PASSED");
        statusValues.add("WARNING");
        statusValues.add("FAILED");
        nullableProperty(findingProperties, "message", "STRING");
        nullableProperty(findingProperties, "start", "INTEGER");
        nullableProperty(findingProperties, "end", "INTEGER");
        nullableProperty(findingProperties, "suggestion", "STRING");
        nullableProperty(findingProperties, "severity", "STRING");
        findingProperties.putObject("confidence").put("type", "NUMBER");
        ArrayNode required = finding.putArray("required");
        required.add("fieldName");
        required.add("status");
        required.add("message");
        required.add("start");
        required.add("end");
        required.add("suggestion");
        required.add("severity");
        required.add("confidence");
        ArrayNode rootRequired = root.putArray("required");
        rootRequired.add("policyVersion");
        rootRequired.add("findings");
        return root;
    }

    private static void nullableProperty(ObjectNode properties, String name, String type) {
        ObjectNode property = properties.putObject(name);
        property.put("type", type);
        property.put("nullable", true);
    }
}
