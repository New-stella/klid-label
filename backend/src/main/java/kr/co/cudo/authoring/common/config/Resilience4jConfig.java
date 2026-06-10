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

    /**
     * 외부 VLM 시계열 분석 위탁 서비스용 CircuitBreaker — Phase 1.
     * <p>인스턴스명 {@code vlmClient} 는 application.yml 의 resilience4j 설정 키와 일치.
     */
    @Bean(name = "vlmClientCircuitBreaker")
    public CircuitBreaker vlmClientCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("vlmClient");
    }

    /**
     * Phase 2 — 관제서버 outbound 통지 클라이언트용 CircuitBreaker.
     */
    @Bean(name = "controlNotifyCircuitBreaker")
    public CircuitBreaker controlNotifyCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("controlNotify");
    }

    /**
     * Phase 1 — KPST 비식별 솔루션 폴링 클라이언트용 CircuitBreaker.
     * <p>인스턴스명 {@code kpstDeid} 는 application.yml resilience4j 설정 키와 일치.
     */
    @Bean(name = "kpstDeidCircuitBreaker")
    public CircuitBreaker kpstDeidCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("kpstDeid");
    }
}
