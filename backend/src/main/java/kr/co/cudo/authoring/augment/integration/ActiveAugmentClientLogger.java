package kr.co.cudo.authoring.augment.integration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 기동 시 활성 {@link ExternalAugmentClient} 구현체를 1회 INFO 로 남긴다 (E-ISSUE-03).
 *
 * <p>과거에는 미연동 no-op 구현체가 전 환경 기본이었고, 그 사실이 어디에도 드러나지 않아 "증강 요청이
 * HTTP 로 나간 적이 없다"는 사실을 아무도 눈치채지 못했다. 오설정이 침묵하지 않도록 활성 구현체를
 * 명시적으로 알린다.
 *
 * <p>⚠ 미연동 모드 토글이 폐기(2026-09-03)되어 <b>구현체는 하나뿐</b>이라 이 로그로 갈리는 것은 이제
 * 없다. 그럼에도 남기는 것은 <b>AOP 프록시가 낀 실제 타깃</b>을 기동 로그에 남기는 진단 가치 때문이며,
 * 「연동됐는가」의 판정은 {@link AugmentExternalLinkPolicy}(= 위탁 주소 주입 여부)가 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActiveAugmentClientLogger {

    private final ExternalAugmentClient externalAugmentClient;

    @EventListener(ApplicationReadyEvent.class)
    public void logActiveClient() {
        log.info("[Augment] active ExternalAugmentClient={}", describe());
    }

    /** 활성 구현체 단순명 — 테스트/진단용. */
    public String describe() {
        // AOP 프록시(@Async 등)가 씌워져도 실제 타깃 클래스명이 나오도록 targetClass 를 우선 사용.
        return org.springframework.aop.support.AopUtils.getTargetClass(externalAugmentClient)
                .getSimpleName();
    }
}
