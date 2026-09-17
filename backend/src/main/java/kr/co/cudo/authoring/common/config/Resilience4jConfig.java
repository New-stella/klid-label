package kr.co.cudo.authoring.common.config;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import kr.co.cudo.authoring.common.client.AiWorkload;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Resilience4jConfig {

    @Bean(name = "deidCircuitBreaker")
    public CircuitBreaker deidCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("deid");
    }

    /**
     * ai-server 추론 호출 CircuitBreaker — <b>용도별로 갈린다</b> (배치 / 저작도구 화면).
     *
     * <p>구 형상은 인스턴스 하나({@code "ai"})를 다섯 호출이 공유했다. 그러면 <b>배치가 연달아
     * 실패해 서킷이 열릴 때 화면 요청까지 함께 30초 막힌다</b> — ai-server 안에서 실행 슬롯을 아무리
     * 잘 나눠도 이 차단은 호출하는 쪽에서 일어나므로 막히지 않는다. 슬롯 분리와 서킷 분리는
     * <b>짝이며 하나만 하면 반쪽</b>이다({@code ADR-056}).
     *
     * <p>인스턴스 이름은 {@link AiWorkload#circuitName()} 하나가 만든다 — 여기에 문자열을 다시 적지
     * 않는다. 이중화가 들어오면 노드 축이 그 메서드에 인자로 추가된다.
     */
    @Bean(name = "aiBatchCircuitBreaker")
    public CircuitBreaker aiBatchCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker(AiWorkload.BATCH.circuitName());
    }

    /** ai-server 추론 호출 CircuitBreaker — 저작도구 화면 경로. 근거는 {@link #aiBatchCircuitBreaker}. */
    @Bean(name = "aiInteractiveCircuitBreaker")
    public CircuitBreaker aiInteractiveCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker(AiWorkload.INTERACTIVE.circuitName());
    }

    /**
     * ai-server 객체 검증 경로 전용 CircuitBreaker — 용도 축에 넣지 않는다.
     *
     * <p>그 경로는 추론 모델을 올리지 않고 정해진 형태의 응답만 돌려주므로 가속기 부하가 없다.
     * 배치·화면 어느 쪽에 묶어도 의미가 없고, 오히려 그쪽 실패율 계산에 <b>잡음</b>만 넣는다.
     */
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
     * 관제 계정 창구 <b>갱신</b> 중계 전용 CircuitBreaker — 통지와도, 로그아웃 중계와도 <b>별도 인스턴스</b>다.
     *
     * <p>통지 서킷을 공유하면 통지 실패(관제 데이터셋 창구 장애)가 세션 연장까지 막고, 그 반대도
     * 성립한다. 두 창구는 같은 관제 서버에 있어도 다른 서비스다.
     *
     * <p>★ 로그아웃과도 나눈다(2026-09-14 확정) — 공유하면 갱신의 누적 일시 장애가 서킷을 열어
     * <b>로그아웃 중계까지 막혀 관제 서버 세션이 닫히지 않는다</b>(현장 실사고). 로그아웃은
     * {@link #controlAccountLogoutCircuitBreaker} 를 쓴다.
     *
     * <p>⚠ 인스턴스명 {@code controlAccount} 는 <b>개명하지 않는다</b> — 배포본이 resilience4j 속성을 이
     * 이름으로 덮어쓰고 있으면 개명이 조용히 무시된다. application.yml resilience4j 설정 키와 일치.
     *
     * @design INT-015
     */
    @Bean(name = "controlAccountCircuitBreaker")
    public CircuitBreaker controlAccountCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("controlAccount");
    }

    /**
     * 관제 계정 창구 <b>로그아웃</b> 중계 전용 CircuitBreaker — 갱신 서킷이 열려도 로그아웃은 나간다.
     *
     * <p>인스턴스명 {@code controlAccountLogout} 은 application.yml resilience4j 설정 키와 일치.
     * 근거는 {@link #controlAccountCircuitBreaker}.
     *
     * @design INT-015
     * @design API-246
     */
    @Bean(name = "controlAccountLogoutCircuitBreaker")
    public CircuitBreaker controlAccountLogoutCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("controlAccountLogout");
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
}
