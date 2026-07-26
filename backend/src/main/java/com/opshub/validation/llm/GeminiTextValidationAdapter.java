package com.opshub.validation.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opshub.validation.domain.FieldFinding;
import com.opshub.validation.domain.FieldStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class GeminiTextValidationAdapter implements TextValidationPort {
    private static final String VALIDATOR_TYPE = "gemini-text";
    private static final String DEFAULT_POLICY_VERSION = "gemini-text-v1";

    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration requestTimeout;

    @Autowired
    public GeminiTextValidationAdapter(
            @Value("${opshub.validation.gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl,
            @Value("${GEMINI_API_KEY:}") String apiKey,
            @Value("${GEMINI_MODEL:gemini-2.5-flash-lite}") String model,
            @Value("${opshub.validation.gemini.request-timeout:5s}") Duration requestTimeout
    ) {
        this(HttpClient.newBuilder().connectTimeout(requestTimeout).build(), new ObjectMapper(), baseUrl, apiKey, model, requestTimeout);
    }

    GeminiTextValidationAdapter(
            HttpClient client,
            ObjectMapper objectMapper,
            String baseUrl,
            String apiKey,
            String model,
            Duration requestTimeout
    ) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model == null || model.isBlank() ? "gemini-2.5-flash-lite" : model;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public List<FieldFinding> validate(TextValidationRequest request) {
        if (apiKey.isBlank()) {
            return unable(request, "Gemini API key is not configured");
        }
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint())
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(TextValidationPrompt.requestBody(objectMapper, request)))
                    .build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return unable(request, "Gemini validation is unavailable");
            }
            return toFindings(request, response.body());
        } catch (java.net.http.HttpTimeoutException exception) {
            return unable(request, "Gemini validation timed out");
        } catch (IOException exception) {
            return unable(request, "Gemini validation is unavailable");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return unable(request, "Gemini validation was interrupted");
        } catch (RuntimeException exception) {
            return unable(request, "Gemini response was invalid");
        }
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public String policyVersion() {
        return DEFAULT_POLICY_VERSION;
    }

    private List<FieldFinding> toFindings(TextValidationRequest request, String responseBody) {
        try {
            JsonNode providerResponse = objectMapper.readTree(responseBody);
            JsonNode responseText = providerResponse.path("candidates").path(0).path("content").path("parts").path(0).path("text");
            if (!responseText.isTextual()) {
                throw new IllegalArgumentException("Gemini response text is missing");
            }
            GeminiResponse response = parseResponse(responseText.asText());
            validateResponse(request, response);
            Map<String, GeminiResponse.Finding> byField = new HashMap<>();
            for (GeminiResponse.Finding finding : response.findings()) {
                byField.put(finding.fieldName(), finding);
            }
            return request.fields().stream().map(field -> toFinding(field, byField.get(field.fieldName()))).toList();
        } catch (IOException | IllegalArgumentException exception) {
            return unable(request, "Gemini response was invalid");
        }
    }

    private GeminiResponse parseResponse(String responseText) throws IOException {
        JsonNode root = objectMapper.readTree(responseText);
        if (!root.isObject() || !root.has("policyVersion") || !root.get("policyVersion").isTextual()
                || root.get("policyVersion").asText().isBlank() || !root.has("findings") || !root.get("findings").isArray()) {
            throw new IllegalArgumentException("Gemini response schema is invalid");
        }
        List<GeminiResponse.Finding> findings = new java.util.ArrayList<>();
        for (JsonNode finding : root.get("findings")) {
            findings.add(parseFinding(finding));
        }
        return new GeminiResponse(root.get("policyVersion").asText(), List.copyOf(findings));
    }

    private GeminiResponse.Finding parseFinding(JsonNode finding) {
        String[] required = {"fieldName", "status", "message", "start", "end", "suggestion", "severity", "confidence"};
        for (String field : required) {
            if (!finding.has(field)) {
                throw new IllegalArgumentException("Missing Gemini finding field: " + field);
            }
        }
        if (!finding.get("fieldName").isTextual() || !finding.get("status").isTextual() || !finding.get("confidence").isNumber()) {
            throw new IllegalArgumentException("Gemini finding has invalid field types");
        }
        return new GeminiResponse.Finding(
                finding.get("fieldName").asText(),
                finding.get("status").asText(),
                nullableText(finding.get("message")),
                nullableInteger(finding.get("start")),
                nullableInteger(finding.get("end")),
                nullableText(finding.get("suggestion")),
                nullableText(finding.get("severity")),
                finding.get("confidence").doubleValue()
        );
    }

    private void validateResponse(TextValidationRequest request, GeminiResponse response) {
        if (!DEFAULT_POLICY_VERSION.equals(response.policyVersion()) || response.findings().size() != request.fields().size()) {
            throw new IllegalArgumentException("Gemini response has an unexpected number of findings");
        }
        Map<String, TextField> requested = new HashMap<>();
        for (TextField field : request.fields()) {
            if (requested.put(field.fieldName(), field) != null) {
                throw new IllegalArgumentException("Duplicate requested text field");
            }
        }
        Set<String> received = new HashSet<>();
        for (GeminiResponse.Finding finding : response.findings()) {
            TextField field = requested.get(finding.fieldName());
            if (field == null || !received.add(finding.fieldName()) || !isAllowedStatus(finding.status())
                    || finding.confidence() == null || !Double.isFinite(finding.confidence())
                    || finding.confidence() < 0 || finding.confidence() > 1 || !hasValidOffsets(finding, field.value())) {
                throw new IllegalArgumentException("Gemini response finding is invalid");
            }
        }
    }

    private FieldFinding toFinding(TextField field, GeminiResponse.Finding finding) {
        return new FieldFinding(
                field.fieldName(),
                VALIDATOR_TYPE,
                FieldStatus.valueOf(finding.status()),
                finding.message(),
                finding.start() == null ? null : finding.start() + ":" + finding.end(),
                finding.suggestion(),
                finding.severity(),
                finding.confidence()
        );
    }

    private List<FieldFinding> unable(TextValidationRequest request, String issue) {
        return request.fields().stream()
                .map(field -> FieldFinding.unableToCheck(field.fieldName(), VALIDATOR_TYPE, issue))
                .toList();
    }

    private URI endpoint() {
        return URI.create(baseUrl + "/v1beta/models/" + URLEncoder.encode(model, StandardCharsets.UTF_8)
                + ":generateContent?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
    }

    private static boolean isAllowedStatus(String status) {
        return "PASSED".equals(status) || "WARNING".equals(status) || "FAILED".equals(status);
    }

    private static boolean hasValidOffsets(GeminiResponse.Finding finding, String value) {
        if (finding.start() == null || finding.end() == null) {
            return finding.start() == null && finding.end() == null;
        }
        return finding.start() >= 0 && finding.start() < finding.end() && finding.end() <= value.length();
    }

    private static String nullableText(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException("Gemini finding text field is invalid");
        }
        return node.asText();
    }

    private static Integer nullableInteger(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (!node.canConvertToInt() || !node.isIntegralNumber()) {
            throw new IllegalArgumentException("Gemini finding offset is invalid");
        }
        return node.intValue();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
