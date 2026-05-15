package kr.co.cudo.authoring.common.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Resilience4jConfig {

    @Bean(name = "deidCircuitBreaker")
    public CircuitBreaker deidCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("deid");
    }

    @Bean(name = "aiCircuitBreaker")
    public CircuitBreaker aiCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("ai");
    }

    @Bean(name = "giteaCircuitBreaker")
    public CircuitBreaker giteaCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("gitea");
    }
}
