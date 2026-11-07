package com.example.ratelimitdemo.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class DemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void pingReturnsOk() throws Exception {
        mockMvc.perform(get("/api/public/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void validOrderReturnsCreatedWithId() throws Exception {
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"book\",\"quantity\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.item").value("book"))
                .andExpect(jsonPath("$.quantity").value(1));
    }

    @Test
    void invalidOrderReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"\",\"quantity\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void demoLoginReturnsToken() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@test.com\",\"password\":\"Passw0rd!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("demo-token"));
    }

    @Test
    void wrongPasswordReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@test.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }
}
