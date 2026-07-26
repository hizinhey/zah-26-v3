package com.opshub.operation;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OperationControllerIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createsGetsAndReplacesOrderedAndroidOfficialAccounts() throws Exception {
        String createResponse = mockMvc.perform(post("/api/v1/operations")
                        .contentType(APPLICATION_JSON)
                        .content("{\"jiraId\":\"MOB-200\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.jiraId").value("MOB-200"))
                .andExpect(jsonPath("$.revision").value(1))
                .andReturn().getResponse().getContentAsString();
        String operationId = JsonPath.read(createResponse, "$.id");

        mockMvc.perform(put("/api/v1/operations/{id}/oas", operationId)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":1,"oas":[
                                  {"platform":"ANDROID","oaName":"Second","thumbnailUrl":"https://example.test/two.png","content":"Header two\\nBody two","buttonText":"Open","redirectUrl":"https://example.test/two"},
                                  {"platform":"ANDROID","oaName":"First","thumbnailUrl":"https://example.test/one.png","content":"Header one\\nBody one","buttonText":"Open","redirectUrl":"https://example.test/one"}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.oas[0].oaName").value("Second"))
                .andExpect(jsonPath("$.oas[1].oaName").value("First"));

        mockMvc.perform(get("/api/v1/operations/{id}", operationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.oas[0].oaOrder").value(1))
                .andExpect(jsonPath("$.oas[1].oaOrder").value(2));
    }

    @Test
    void rejectsNonAndroidOfficialAccounts() throws Exception {
        String operationId = createOperation("MOB-201");

        mockMvc.perform(put("/api/v1/operations/{id}/oas", operationId)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":1,"oas":[
                                  {"platform":"WEB","oaName":"Unsupported","thumbnailUrl":"https://example.test/thumb.png","content":"Header\\nBody","buttonText":"Open","redirectUrl":"https://example.test/target"}
                                ]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_PLATFORM"));
    }

    @Test
    void returnsCurrentRevisionForStaleOfficialAccountWrites() throws Exception {
        String operationId = createOperation("MOB-202");
        String request = """
                {"expectedRevision":1,"oas":[
                  {"platform":"ANDROID","oaName":"Account","thumbnailUrl":"https://example.test/thumb.png","content":"Header\\nBody","buttonText":"Open","redirectUrl":"https://example.test/target"}
                ]}
                """;

        mockMvc.perform(put("/api/v1/operations/{id}/oas", operationId)
                        .contentType(APPLICATION_JSON).content(request))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/operations/{id}/oas", operationId)
                        .contentType(APPLICATION_JSON).content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
                .andExpect(jsonPath("$.currentRevision").value(2));
    }

    private String createOperation(String jiraId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/operations")
                        .contentType(APPLICATION_JSON)
                        .content("{\"jiraId\":\"" + jiraId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }
}
