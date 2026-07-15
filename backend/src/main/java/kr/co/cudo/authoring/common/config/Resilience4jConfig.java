package kr.co.cudo.authoring.common.config;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
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

    /**
     * Phase 3 — YOLO 오토라벨 <b>온라인(수동 트리거)</b> 경로 전용 Bulkhead (F-2 동시성 제한).
     * <p>인스턴스명 {@code aiOnline} 은 application.yml resilience4j.bulkhead 설정 키와 일치.
     * 배치 YOLO 경로({@code AiServerClient} 공유 CircuitBreaker/Retry)와 <b>격리</b>되어 온라인 트리거만
     * 동시 호출 수를 제한한다 — Tomcat 스레드 고갈 방어. 초과 시 {@code BulkheadFullException} → 429.
     */
    @Bean(name = "aiOnlineBulkhead")
    public Bulkhead aiOnlineBulkhead(BulkheadRegistry registry) {
        return registry.bulkhead("aiOnline");
    }

    /**
     * Phase 9 — 포털 SAM2 인터랙티브 추론(세그/추적) 경로 전용 Bulkhead (HIGH #4 자원 격리).
     * <p>인스턴스명 {@code portalSam2} 는 application.yml resilience4j.bulkhead 설정 키와 일치.
     * 내부 온라인 AI 경로({@code aiOnline})와 <b>격리</b>되어 외부 포털 트래픽이 내부 라벨링 작업의
     * AI 자원을 잠식하지 못하도록 동시 호출 수를 독립 제한한다 — 초과 시 {@code BulkheadFullException} → 429.
     */
    @Bean(name = "portalSam2Bulkhead")
    public Bulkhead portalSam2Bulkhead(BulkheadRegistry registry) {
        return registry.bulkhead("portalSam2");
    }
}
