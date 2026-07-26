package com.opshub.validation.application;

import com.opshub.operation.domain.OfficialAccount;
import com.opshub.operation.domain.Operation;
import com.opshub.validation.domain.FieldFinding;
import com.opshub.validation.domain.FieldStatus;
import com.opshub.validation.llm.TextValidationPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationServiceTest {
    @Test
    void retainsDeterministicFailuresWhenGeminiPassesTheSameField() {
        List<TextValidationPort.TextField> requestedFields = new ArrayList<>();
        TextValidationPort passingPort = new TextValidationPort() {
            @Override
            public List<FieldFinding> validate(TextValidationRequest request) {
                requestedFields.addAll(request.fields());
                return request.fields().stream()
                        .map(field -> FieldFinding.passed(field.fieldName(), "gemini-text"))
                        .toList();
            }

            @Override
            public String model() {
                return "stub-model";
            }

            @Override
            public String policyVersion() {
                return "stub-policy";
            }
        };
        ValidationService service = new ValidationService(null, new ContentParser(), new UrlDirectionValidator(), passingThumbnailValidator(), passingPort);
        Operation operation = Operation.create("MOB-400");
        operation.addOfficialAccount(new OfficialAccount(
                operation, 1, "ANDROID", "Account", "https://example.test/image.png", "\nBody", "Open", "https://example.test/offer"
        ));

        List<FieldFinding> findings = service.collectFindings(operation);

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.fieldName()).isEqualTo("oa[1].content.body");
            assertThat(finding.validatorType()).isEqualTo("required");
            assertThat(finding.status()).isEqualTo(FieldStatus.FAILED);
        });
        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.fieldName()).isEqualTo("oa[1].content.body");
            assertThat(finding.validatorType()).isEqualTo("gemini-text");
            assertThat(finding.status()).isEqualTo(FieldStatus.PASSED);
        });
        assertThat(requestedFields).containsExactly(
                new TextValidationPort.TextField("oa[1].content.header", "Body"),
                new TextValidationPort.TextField("oa[1].content.body", ""),
                new TextValidationPort.TextField("oa[1].buttonText", "Open")
        );
    }

    private static ThumbnailValidator passingThumbnailValidator() {
        return new ThumbnailValidator(java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1), 1, 0) {
            @Override
            public FieldFinding validate(String ignored) {
                return FieldFinding.passed("thumbnailUrl", "thumbnail");
            }
        };
    }
}
