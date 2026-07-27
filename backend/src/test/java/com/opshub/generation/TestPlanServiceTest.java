package com.opshub.generation;

import com.opshub.generation.application.TestPlanService;
import com.opshub.generation.application.TemplateReadinessValidator;
import com.opshub.generation.application.FileSystemTemplateReadinessValidator;
import com.opshub.generation.application.TemplateReadinessProperties;
import com.opshub.generation.domain.TemplateId;
import com.opshub.operation.application.RevisionConflictException;
import com.opshub.operation.domain.OfficialAccount;
import com.opshub.operation.domain.Operation;
import com.opshub.validation.application.ContentParser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestPlanServiceTest {
    private final Operation operation = operationWithOneAccount();

    @TempDir
    Path tempDirectory;

    @Test
    void blocksGenerationUntilEveryCurrentValidationFindingPassed() {
        assertThat(TestPlanService.isGenerationAllowed(0, 0)).isFalse();
        assertThat(TestPlanService.isGenerationAllowed(1, 1)).isFalse();
        assertThat(TestPlanService.isGenerationAllowed(1, 0)).isTrue();
    }

    @Test
    void generatesFiveFixedCasesForTheCurrentRevisionWithParsedParameters() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate(1, 0);
        TestPlanService service = new TestPlanService(
                entityManagerReturning(operation), jdbcTemplate, new ContentParser(),
                TemplateReadinessValidator.alwaysReady()
        );

        TestPlanService.TestPlanDto plan = service.generate(operation.getId(), operation.getRevision());
        List<TestPlanService.TestCaseDto> cases = plan.testCases();

        assertThat(plan.sourceRevision()).isEqualTo(operation.getRevision());
        assertThat(cases).hasSize(5);
        assertThat(cases).extracting(TestPlanService.TestCaseDto::order).containsExactly(1, 2, 3, 4, 5);
        assertThat(cases).extracting(TestPlanService.TestCaseDto::templateId)
                .containsExactlyElementsOf(List.of(TemplateId.values()).stream().map(TemplateId::id).toList());
        assertThat(cases).allSatisfy(testCase -> {
            assertThat(testCase.status()).isEqualTo("READY");
            assertThat(testCase.parameters().expectedHeader()).isEqualTo("Header");
            assertThat(testCase.parameters().expectedBody()).isEqualTo("Body");
            assertThat(testCase.parameters().expectedRedirectDomain()).isEqualTo("business.example.test");
        });
        assertThat(jdbcTemplate.updates).hasSize(7);
    }

    @Test
    void keepsAPlanGenerationFailedWhenAnyRenderedTemplateIsNotReady() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate(1, 0);
        TestPlanService service = new TestPlanService(
                entityManagerReturning(operation), jdbcTemplate, new ContentParser(),
                (template, parameters) -> template == TemplateId.CONTENT
                        ? TemplateReadinessValidator.Readiness.notReady("TypeScript failed")
                        : TemplateReadinessValidator.Readiness.readyResult()
        );

        TestPlanService.TestPlanDto plan = service.generate(operation.getId(), operation.getRevision());

        assertThat(plan.status()).isEqualTo("GENERATION_FAILED");
        assertThat(plan.testCases()).extracting(TestPlanService.TestCaseDto::status)
                .containsExactly("READY", "READY", "NOT_READY", "READY", "READY");
        assertThat(plan.testCases().get(2).reason()).isEqualTo("TypeScript failed");
    }

    @Test
    void keepsWrongCatalogVersionCasesNotReadyAndBlocksApproval() throws Exception {
        Files.writeString(tempDirectory.resolve("manifest.json"), """
                {"catalogVersion":"android-v2","templates":[]}
                """);
        TemplateReadinessProperties properties = new TemplateReadinessProperties();
        properties.setTemplateRoot(tempDirectory.toString());
        ApprovalBlockingJdbcTemplate jdbcTemplate = new ApprovalBlockingJdbcTemplate();
        TestPlanService service = new TestPlanService(
                entityManagerReturning(operation), jdbcTemplate, new ContentParser(),
                new FileSystemTemplateReadinessValidator(properties, new com.fasterxml.jackson.databind.ObjectMapper())
        );

        TestPlanService.TestPlanDto plan = service.generate(operation.getId(), operation.getRevision());

        assertThat(plan.status()).isEqualTo("GENERATION_FAILED");
        assertThat(plan.testCases()).allSatisfy(testCase -> {
            assertThat(testCase.status()).isEqualTo("NOT_READY");
            assertThat(testCase.reason()).contains("catalog version");
        });
        assertThatThrownBy(() -> service.approve(plan.planId(), operation.getRevision()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fully ready");
    }

    @Test
    void blocksApprovalWhenAnyCaseIsNotReady() {
        TestPlanService service = new TestPlanService(null, new RecordingJdbcTemplate(1, 0) {
            @Override
            public int update(String sql, Object... args) {
                return 0;
            }

            @Override
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                return requiredType.cast(1);
            }
        }, new ContentParser(), TemplateReadinessValidator.alwaysReady());

        assertThatThrownBy(() -> service.approve(UUID.randomUUID(), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fully ready");
    }

    @Test
    void rejectsApprovalForAStaleRevision() {
        TestPlanService service = new TestPlanService(null, new RecordingJdbcTemplate(1, 0) {
            @Override
            public int update(String sql, Object... args) {
                return 0;
            }

            @Override
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                return requiredType.cast(2);
            }
        }, new ContentParser(), TemplateReadinessValidator.alwaysReady());

        assertThatThrownBy(() -> service.approve(UUID.randomUUID(), 1))
                .isInstanceOfSatisfying(RevisionConflictException.class,
                        conflict -> assertThat(conflict.getCurrentRevision()).isEqualTo(2));
    }

    private static Operation operationWithOneAccount() {
        Operation operation = Operation.create("MOB-500");
        operation.addOfficialAccount(new OfficialAccount(
                operation, 1, "ANDROID", "Account", "https://cdn.example.test/thumb.png",
                "Header\nBody", "Open now", "https://business.example.test/offer?campaign=summer"
        ));
        return operation;
    }

    @SuppressWarnings("unchecked")
    private static EntityManager entityManagerReturning(Operation operation) {
        return (EntityManager) Proxy.newProxyInstance(
                TestPlanServiceTest.class.getClassLoader(),
                new Class<?>[]{EntityManager.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("createQuery")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    return Proxy.newProxyInstance(
                            TestPlanServiceTest.class.getClassLoader(),
                            new Class<?>[]{TypedQuery.class},
                            (queryProxy, queryMethod, queryArgs) -> switch (queryMethod.getName()) {
                                case "setParameter" -> queryProxy;
                                case "getResultStream" -> Stream.of(operation);
                                default -> throw new UnsupportedOperationException(queryMethod.getName());
                            }
                    );
                }
        );
    }

    private static class RecordingJdbcTemplate extends JdbcTemplate {
        private final Integer passingRuns;
        private final Integer nonPassedFindings;
        private final List<String> updates = new ArrayList<>();

        private RecordingJdbcTemplate(Integer passingRuns, Integer nonPassedFindings) {
            this.passingRuns = passingRuns;
            this.nonPassedFindings = nonPassedFindings;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (sql.contains("FROM validation_runs")) {
                return requiredType.cast(passingRuns);
            }
            if (sql.contains("FROM field_findings")) {
                return requiredType.cast(nonPassedFindings);
            }
            throw new UnsupportedOperationException(sql);
        }

        @Override
        public int update(String sql, Object... args) {
            updates.add(sql);
            return 1;
        }
    }

    private static class ApprovalBlockingJdbcTemplate extends RecordingJdbcTemplate {
        private ApprovalBlockingJdbcTemplate() {
            super(1, 0);
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.contains("UPDATE test_plans plan")) {
                return 0;
            }
            return super.update(sql, args);
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (sql.contains("JOIN test_plans")) {
                return requiredType.cast(1);
            }
            return super.queryForObject(sql, requiredType, args);
        }
    }
}
