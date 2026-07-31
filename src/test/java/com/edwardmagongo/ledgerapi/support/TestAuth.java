package com.edwardmagongo.ledgerapi.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

public final class TestAuth {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TestAuth() {
    }

    /** Registers the user then logs in, returning the raw JWT (no "Bearer " prefix). */
    public static String registerAndLogin(MockMvc mockMvc, String email, String password) throws Exception {
        String body = """
                {"email":"%s","password":"%s"}""".formatted(email, password);

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body));

        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();

        JsonNode node = MAPPER.readTree(response);
        return node.get("token").asText();
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }
}
