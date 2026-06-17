package kr.co.cudo.authoring.augment.integration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 운영 기본 ExternalAugmentClient — 외부 SFR-07 증강 시스템 미연결 상태의 no-op 구현.
 *
 * <p>실제 외부 호출 없이 로그만 남기고 ack(true)를 반환한다. 콜백 충실 플로우 Phase 1 의 산출물로,
 * 요청 측이 PENDING 행 + 멱등 키 + 콜백 컨텍스트를 정상적으로 만들도록 보장하되 회귀를 격리한다.
 *
 * <p>{@link ConditionalOnProperty matchIfMissing=true} 로 기본 활성이며, dev 환경 실제 콜백 호출
 * 구현(Phase 2)은 {@code authoring.augment.external.mode=dev} 설정으로 본 빈을 비활성화하고
 * 자신을 등록한다. {@link Primary} 로 다른 후보 빈과 공존해도 주입 모호성이 발생하지 않게 한다.
 *
 * <p>로그 안전(CWE-117): 콜백 컨텍스트 식별자는 형식 검증된 값(idempotencyKey/externalJobId)만
 * 출력하며 CR/LF 를 제거한다. 시크릿/PII 는 출력하지 않는다.
 */
@Slf4j
@Component
@Primary
@ConditionalOnProperty(name = "authoring.augment.external.mode", havingValue = "noop", matchIfMissing = true)
public class NoopExternalAugmentClient implements ExternalAugmentClient {

    private static final Pattern LOG_UNSAFE = Pattern.compile("[\\r\\n\\t]");

    @Override
    public boolean syncDecision(Long dataAugSn, String decision, String reasonOrNull) {
        // 외부 SFR-07 시스템 미연결 — mock 응답. 실제 연동 시 WebClient + Resilience4j 적용 예정.
        log.info("[Augment] external decision sync (noop) dataAugSn={} decision={} reason={}",
                dataAugSn, safe(decision), reasonOrNull == null ? "" : safe(reasonOrNull));
        return true;
    }

    @Override
    public boolean requestAugment(Long originAugSn, String augType, String idempotencyKey,
                                  String externalJobId, String callbackUrl) {
        // 외부 SFR-07 시스템 미연결 — no-op. 콜백 컨텍스트는 적재/발급만 완료, 실제 push 는 Phase 2.
        log.info("[Augment] external request (noop) originAugSn={} augType={} externalJobId={} callbackUrl={}",
                originAugSn, safe(augType), safe(externalJobId), safe(callbackUrl));
        return true;
    }

    private static String safe(String s) {
        if (s == null) return "null";
        return LOG_UNSAFE.matcher(s).replaceAll("_");
    }
}
