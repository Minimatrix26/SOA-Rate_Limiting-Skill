package com.example.ratelimitdemo;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI demoApi() {
        return new OpenAPI().info(new Info()
                .title("Rate Limiting Demo API")
                .description("A baseline REST API for demonstrating rate limiting.")
                .version("0.0.1"));
    }
}
