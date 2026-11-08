package com.example.ratelimitdemo.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class LoginRateLimitTest {
    @Autowired MockMvc mockMvc;

    private org.springframework.test.web.servlet.ResultActions loginFrom(String ip, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").with(request -> { request.setRemoteAddr(ip); return request; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"user@test.com\",\"password\":\"" + password + "\"}"));
    }

    @Test
    void allowsFiveThenReturnsProblemDetail429AndRetryHeaders() throws Exception {
        for (int i = 0; i < 5; i++) loginFrom("198.51.100.31", "wrong").andExpect(status().isUnauthorized());
        loginFrom("198.51.100.31", "wrong").andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("RateLimit-Limit", "5"))
                .andExpect(header().string("RateLimit-Remaining", "0"))
                .andExpect(header().exists("RateLimit-Reset"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    void differentClientIpGetsIndependentBucket() throws Exception {
        for (int i = 0; i < 5; i++) loginFrom("198.51.100.41", "wrong").andExpect(status().isUnauthorized());
        loginFrom("198.51.100.42", "wrong").andExpect(status().isUnauthorized());
    }

    @Test
    void unrelatedEndpointsRemainUnaffected() throws Exception {
        for (int i = 0; i < 12; i++) mockMvc.perform(get("/api/public/ping")).andExpect(status().isOk());
        for (int i = 0; i < 12; i++) mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"item\":\"book\",\"quantity\":1}")).andExpect(status().isCreated());
    }
}
