package com.opshub.validation.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opshub.validation.domain.FieldFinding;
import com.opshub.validation.domain.FieldStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiTextValidationAdapterTest {
    private HttpServer server;
    private String baseUrl;
    private GeminiTextValidationAdapter adapter;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        adapter = adapter(Duration.ofSeconds(2));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void mapsSchemaValidPassedFindings() {
        respond("""
                {"candidates":[{"content":{"parts":[{"text":"{\\\"policyVersion\\\":\\\"gemini-text-v1\\\",\\\"findings\\\":[{\\\"fieldName\\\":\\\"oa[1].content.header\\\",\\\"status\\\":\\\"PASSED\\\",\\\"message\\\":null,\\\"start\\\":null,\\\"end\\\":null,\\\"suggestion\\\":null,\\\"severity\\\":null,\\\"confidence\\\":1.0}]}"}]}}]}
                """);

        FieldFinding finding = validateHeader().getFirst();

        assertThat(finding.fieldName()).isEqualTo("oa[1].content.header");
        assertThat(finding.validatorType()).isEqualTo("gemini-text");
        assertThat(finding.status()).isEqualTo(FieldStatus.PASSED);
        assertThat(finding.confidence()).isEqualTo(1.0);
    }

    @Test
    void mapsSpellingFailureWithItsLocationAndSuggestion() {
        respond("""
                {"candidates":[{"content":{"parts":[{"text":"{\\\"policyVersion\\\":\\\"gemini-text-v1\\\",\\\"findings\\\":[{\\\"fieldName\\\":\\\"oa[1].content.header\\\",\\\"status\\\":\\\"FAILED\\\",\\\"message\\\":\\\"Spelling error\\\",\\\"start\\\":0,\\\"end\\\":4,\\\"suggestion\\\":\\\"Ưu đãi\\\",\\\"severity\\\":\\\"ERROR\\\",\\\"confidence\\\":0.98}]}"}]}}]}
                """);

        FieldFinding finding = validateHeader().getFirst();

        assertThat(finding.status()).isEqualTo(FieldStatus.FAILED);
        assertThat(finding.location()).isEqualTo("0:4");
        assertThat(finding.suggestion()).isEqualTo("Ưu đãi");
        assertThat(finding.confidence()).isEqualTo(0.98);
    }

    @Test
    void preservesMisleadingClaimWarnings() {
        respond("""
                {"candidates":[{"content":{"parts":[{"text":"{\\\"policyVersion\\\":\\\"gemini-text-v1\\\",\\\"findings\\\":[{\\\"fieldName\\\":\\\"oa[1].content.header\\\",\\\"status\\\":\\\"WARNING\\\",\\\"message\\\":\\\"Misleading claim\\\",\\\"start\\\":0,\\\"end\\\":10,\\\"suggestion\\\":\\\"Add terms\\\",\\\"severity\\\":\\\"WARNING\\\",\\\"confidence\\\":0.8}]}"}]}}]}
                """);

        FieldFinding finding = validateHeader().getFirst();

        assertThat(finding.status()).isEqualTo(FieldStatus.WARNING);
        assertThat(finding.issue()).isEqualTo("Misleading claim");
    }

    @Test
    void preservesAValidLowConfidenceFinding() {
        respond("""
                {"candidates":[{"content":{"parts":[{"text":"{\\\"policyVersion\\\":\\\"gemini-text-v1\\\",\\\"findings\\\":[{\\\"fieldName\\\":\\\"oa[1].content.header\\\",\\\"status\\\":\\\"WARNING\\\",\\\"message\\\":\\\"Could be unclear\\\",\\\"start\\\":0,\\\"end\\\":2,\\\"suggestion\\\":\\\"Clarify\\\",\\\"severity\\\":\\\"WARNING\\\",\\\"confidence\\\":0.1}]}"}]}}]}
                """);

        FieldFinding finding = validateHeader().getFirst();

        assertThat(finding.status()).isEqualTo(FieldStatus.WARNING);
        assertThat(finding.confidence()).isEqualTo(0.1);
    }

    @Test
    void convertsMalformedOutputToUnableToCheckForEveryRequestedField() {
        respond("{" + "\"candidates\":[{" + "\"content\":{\"parts\":[{\"text\":\"not-json\"}]}}]}");

        FieldFinding finding = validateHeader().getFirst();

        assertThat(finding.status()).isEqualTo(FieldStatus.UNABLE_TO_CHECK);
        assertThat(finding.issue()).contains("response was invalid");
    }

    @Test
    void convertsMalformedOutputToUnableToCheckForAllRequestedFields() {
        respond(providerResponse("not-json"));

        List<FieldFinding> findings = adapter.validate(new TextValidationPort.TextValidationRequest(List.of(
                new TextValidationPort.TextField("oa[1].content.header", "Header"),
                new TextValidationPort.TextField("oa[1].content.body", "Body")
        )));

        assertThat(findings).extracting(FieldFinding::status)
                .containsOnly(FieldStatus.UNABLE_TO_CHECK);
    }

    @Test
    void rejectsUnknownRootProperties() {
        respond(providerResponse("""
                {"policyVersion":"gemini-text-v1","findings":[{"fieldName":"oa[1].content.header","status":"PASSED","message":null,"start":null,"end":null,"suggestion":null,"severity":null,"confidence":1.0}],"unexpected":true}
                """));

        assertThat(validateHeader().getFirst().status()).isEqualTo(FieldStatus.UNABLE_TO_CHECK);
    }

    @Test
    void rejectsUnknownFindingProperties() {
        respond(providerResponse("""
                {"policyVersion":"gemini-text-v1","findings":[{"fieldName":"oa[1].content.header","status":"PASSED","message":null,"start":null,"end":null,"suggestion":null,"severity":null,"confidence":1.0,"unexpected":true}]}
                """));

        assertThat(validateHeader().getFirst().status()).isEqualTo(FieldStatus.UNABLE_TO_CHECK);
    }

    @Test
    void rejectsUnknownStatuses() {
        respond(providerResponse("""
                {"policyVersion":"gemini-text-v1","findings":[{"fieldName":"oa[1].content.header","status":"UNKNOWN","message":null,"start":null,"end":null,"suggestion":null,"severity":null,"confidence":1.0}]}
                """));

        assertThat(validateHeader().getFirst().status()).isEqualTo(FieldStatus.UNABLE_TO_CHECK);
    }

    @Test
    void rejectsMissingRequiredFindingFields() {
        respond(providerResponse("""
                {"policyVersion":"gemini-text-v1","findings":[{"fieldName":"oa[1].content.header","status":"PASSED","message":null,"start":null,"end":null,"suggestion":null,"severity":null}]}
                """));

        assertThat(validateHeader().getFirst().status()).isEqualTo(FieldStatus.UNABLE_TO_CHECK);
    }

    @Test
    void rejectsOutOfRangeConfidence() {
        respond(providerResponse("""
                {"policyVersion":"gemini-text-v1","findings":[{"fieldName":"oa[1].content.header","status":"PASSED","message":null,"start":null,"end":null,"suggestion":null,"severity":null,"confidence":1.1}]}
                """));

        assertThat(validateHeader().getFirst().status()).isEqualTo(FieldStatus.UNABLE_TO_CHECK);
    }

    @Test
    void rejectsInvalidOffsets() {
        respond(providerResponse("""
                {"policyVersion":"gemini-text-v1","findings":[{"fieldName":"oa[1].content.header","status":"FAILED","message":"Error","start":4,"end":2,"suggestion":null,"severity":"ERROR","confidence":0.9}]}
                """));

        assertThat(validateHeader().getFirst().status()).isEqualTo(FieldStatus.UNABLE_TO_CHECK);
    }

    @Test
    void convertsRateLimitsToUnableToCheck() {
        server.createContext("/v1beta/models/test-model:generateContent", exchange -> response(exchange, 429, "{\"error\":{\"message\":\"rate limited\"}}"));

        FieldFinding finding = validateHeader().getFirst();

        assertThat(finding.status()).isEqualTo(FieldStatus.UNABLE_TO_CHECK);
        assertThat(finding.issue()).contains("unavailable");
    }

    @Test
    void convertsTimeoutsToUnableToCheck() {
        adapter = adapter(Duration.ofMillis(100));
        server.createContext("/v1beta/models/test-model:generateContent", exchange -> {
            try {
                Thread.sleep(500);
                response(exchange, 200, "{}");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });

        FieldFinding finding = validateHeader().getFirst();

        assertThat(finding.status()).isEqualTo(FieldStatus.UNABLE_TO_CHECK);
        assertThat(finding.issue()).contains("timed out");
    }

    private List<FieldFinding> validateHeader() {
        return adapter.validate(new TextValidationPort.TextValidationRequest(List.of(
                new TextValidationPort.TextField("oa[1].content.header", "Ưu đãi tháng này")
        )));
    }

    private GeminiTextValidationAdapter adapter(Duration timeout) {
        return new GeminiTextValidationAdapter(
                HttpClient.newBuilder().connectTimeout(timeout).build(),
                new ObjectMapper(),
                baseUrl,
                "test-key",
                "test-model",
                timeout
        );
    }

    private static String providerResponse(String responseText) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + jsonString(responseText) + "}]}}]}";
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private void respond(String body) {
        server.createContext("/v1beta/models/test-model:generateContent", exchange -> response(exchange, 200, body));
    }

    private static void response(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
