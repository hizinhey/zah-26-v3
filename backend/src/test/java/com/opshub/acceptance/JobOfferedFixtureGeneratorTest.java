package com.opshub.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.opshub.hub.domain.HubEnvelopeV1;
import com.opshub.hub.domain.HubPayloads;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Generates {@code local-hub/tests/fixtures/job_offered_multi_oa.json} - the C1 item 7
 * cross-language contract fixture that
 * {@code local-hub/tests/integration/test_backend_contract.py}'s
 * {@code test_backend_produced_multi_oa_job_offered_fixture_round_trips_through_pydantic} parses
 * with {@code JobOfferedPayload.model_validate}.
 *
 * <p>{@link MvpLifecycleIT} also (re)writes this same fixture from a real, DB-backed
 * {@code ExecutionService.buildJobOfferedEnvelope} call, which is the stronger of the two
 * generators since it proves the backend's actual query/join produces a conformant payload. This
 * class is a Testcontainers-free fallback that can regenerate the fixture (using the exact same
 * {@link HubPayloads.TestCase} shape {@code buildJobOfferedEnvelope} constructs, with two
 * hand-built OA groups) in environments - like this one - where Testcontainers cannot start, so
 * the fixture and the test that depends on it are never left stale/missing for long.
 */
class JobOfferedFixtureGeneratorTest {
    private static final Path FIXTURE_PATH = Path.of(
            "..", "local-hub", "tests", "fixtures", "job_offered_multi_oa.json");

    @Test
    void generatesTheMultiOaJobOfferedFixture() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        List<HubPayloads.TestCase> testCases = List.of(
                testCase(1, "OA One", 1, "android-oa-delivery-v1"),
                testCase(1, "OA One", 2, "android-thumbnail-v1"),
                testCase(1, "OA One", 3, "android-content-v1"),
                testCase(1, "OA One", 4, "android-button-text-v1"),
                testCase(1, "OA One", 5, "android-redirect-v1"),
                testCase(2, "OA Two", 1, "android-oa-delivery-v1"),
                testCase(2, "OA Two", 2, "android-thumbnail-v1"),
                testCase(2, "OA Two", 3, "android-content-v1"),
                testCase(2, "OA Two", 4, "android-button-text-v1"),
                testCase(2, "OA Two", 5, "android-redirect-v1")
        );
        HubPayloads.JobOfferedPayload payload = new HubPayloads.JobOfferedPayload(
                UUID.randomUUID(), "fixture-idempotency-key", 1, "ANDROID", testCases, UUID.randomUUID());
        HubEnvelopeV1 envelope = HubEnvelopeV1.of(HubEnvelopeV1.TYPE_JOB_OFFERED, payload);

        Files.createDirectories(FIXTURE_PATH.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(FIXTURE_PATH.toFile(), envelope);
    }

    private static HubPayloads.TestCase testCase(int oaOrder, String oaName, int order, String templateId) {
        Map<String, Object> parameters = Map.of(
                "oaName", oaName,
                "thumbnailUrl", "https://example.test/thumb-" + oaOrder + ".png",
                "expectedHeader", "Header " + oaOrder,
                "expectedBody", "Body " + oaOrder,
                "expectedButtonText", "Open " + oaOrder,
                "expectedRedirectUrl", "https://business" + oaOrder + ".example.test/offer",
                "expectedRedirectDomain", "business" + oaOrder + ".example.test"
        );
        return new HubPayloads.TestCase(UUID.randomUUID(), oaOrder, oaName, order, templateId, 1, parameters);
    }
}
