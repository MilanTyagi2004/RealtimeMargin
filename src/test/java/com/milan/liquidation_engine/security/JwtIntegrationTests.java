package com.milan.liquidation_engine.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.milan.liquidation_engine.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class JwtIntegrationTests {

    @Autowired
    private WebApplicationContext context;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void testPublicEndpointDoesNotRequireAuthentication() throws Exception {
        mockMvc.perform(get("/v1/health"))
                .andExpect(status().isOk());
    }

    @Test
    void testSecuredEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/positions/user/999"))
                .andExpect(status().isForbidden());
    }

    @Test
    void testFullAuthFlow() throws Exception {
        String uniqueUsername = "user_" + UUID.randomUUID().toString().substring(0, 8);
        String password = "securePassword123";

        // 1. Create/Register User
        User registerRequest = User.builder()
                .username(uniqueUsername)
                .password(password)
                .balance(BigDecimal.valueOf(1000.0))
                .build();

        MvcResult registerResult = mockMvc.perform(post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(uniqueUsername))
                .andReturn();

        Map<?, ?> registeredUser = objectMapper.readValue(registerResult.getResponse().getContentAsString(), Map.class);
        Long userId = ((Number) registeredUser.get("id")).longValue();
        assertNotNull(userId);

        // 2. Login User
        Map<String, String> loginRequest = new HashMap<>();
        loginRequest.put("username", uniqueUsername);
        loginRequest.put("password", password);

        MvcResult loginResult = mockMvc.perform(post("/users/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.username").value(uniqueUsername))
                .andReturn();

        Map<?, ?> loginResponse = objectMapper.readValue(loginResult.getResponse().getContentAsString(), Map.class);
        String token = (String) loginResponse.get("token");
        assertNotNull(token);

        // 3. Access Secured Endpoint with Token
        mockMvc.perform(get("/users/" + userId + "/margin")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.username").value(uniqueUsername));
    }
}
