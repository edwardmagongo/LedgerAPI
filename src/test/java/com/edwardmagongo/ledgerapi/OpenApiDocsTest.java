package com.edwardmagongo.ledgerapi;

import com.edwardmagongo.ledgerapi.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OpenApiDocsTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;

    @Test
    void apiDocsArePubliclyReachableAndDescribeEveryEndpoint() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("LedgerAPI"))
                .andExpect(jsonPath("$.paths['/api/auth/register']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login']").exists())
                .andExpect(jsonPath("$.paths['/api/accounts']").exists())
                .andExpect(jsonPath("$.paths['/api/transfers']").exists())
                .andExpect(jsonPath("$.paths['/api/accounts/{accountId}/deposit']").exists())
                .andExpect(jsonPath("$.paths['/api/accounts/{accountId}/withdraw']").exists())
                .andExpect(jsonPath("$.paths['/api/accounts/{accountId}/transactions']").exists());
    }

    @Test
    void bearerAuthSchemeIsDeclaredSoSwaggerUiCanAuthorize() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"));
    }
}
