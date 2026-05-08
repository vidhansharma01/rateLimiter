package com.rateLimiter.token;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Rate Limiter Service.
 *
 * <p>{@link EnableScheduling} activates the 60-second rule cache refresh
 * scheduled in {@link com.rateLimiter.token.config.RuleConfigService}.
 */
@SpringBootApplication
@EnableScheduling
public class TokenApplication {

    public static void main(String[] args) {
        SpringApplication.run(TokenApplication.class, args);
    }
}
