package kr.co.cudo.authoring.augment.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 외부 증강 연동 모드 판정 — <b>단일 진실원</b> (R8 · D4, {@code @design API-060}).
 *
 * <h3>왜 필요한가</h3>
 * <p>{@code authoring.augment.external.mode=noop}(dev/stg/prd <b>기본값</b>)이면 위탁이 실제로 나가지
 * 않고({@link NoopExternalAugmentClient} 가 로그만 남기고 {@link AugmentSubmitResult#skipped()} 반환)
 * <b>콜백도 영영 오지 않는다</b>. 그런데 요청 접수는 성공해 {@code LS_DATA_AUG} 에 PENDING 행이 생기므로,
 * REVIEWER 화면에는 <b>만료 스윕이 돌 때까지 「진행 중」으로 보인다</b>. 데이터가 오염되지는 않지만
 * (가짜 산출물을 만들지 않고 PENDING→만료로 회수된다) <b>되지도 않을 요청이 접수되는 것</b> 하나가
 * 남는다. 그래서 요청 접수 단계에서 503 으로 거부한다.
 *
 * <h3>왜 빈 타입 검사가 아니라 프로퍼티를 읽는가</h3>
 * <p>{@link ExternalAugmentClient} 인터페이스에는 연동 여부를 알려주는 메서드가 없고,
 * {@code instanceof NoopExternalAugmentClient} 는 AOP 프록시가 끼면 조용히 거짓이 된다(프록시가
 * 인터페이스 기반이면 구현 클래스 타입이 사라진다). 그래서 빈 배선을 결정하는 <b>같은 프로퍼티</b>를
 * 같은 기본값으로 읽는다 — {@link kr.co.cudo.authoring.common.config.GenAiIntegrationWiringGuard} 가
 * 이미 같은 방식으로 같은 값을 읽는 전례다.
 *
 * <h3>기본값이 {@code http} 여야 하는 이유 (뒤집지 말 것)</h3>
 * <p>{@link NoopExternalAugmentClient} 는 {@code @ConditionalOnProperty(havingValue="noop")} 로
 * <b>명시 지정 전용</b>이고 {@code matchIfMissing} 이 없다. 즉 <b>미설정 = http 클라이언트 배선</b>이다.
 * 여기서 기본값을 {@code noop} 으로 잡으면 미설정 환경에서 <b>빈은 http 인데 접수는 503</b> 이라는
 * 모순이 생긴다. 판정과 배선은 같은 기본값을 공유해야 한다.
 *
 * <p><b>생명주기</b>: 이 값은 프로퍼티라 재기동 없이 바뀌지 않는다 — 빈 배선과 같은 생명주기다.
 * 동적 override(설정 화면·DB 기반 토글)를 여기에 얹지 말 것. 얹는 순간 "판정은 연동인데 주입된 빈은
 * noop" 같은 어긋남이 다시 열린다.
 */
@Component
public class AugmentExternalModePolicy {

    /** 빈 배선을 결정하는 프로퍼티 키 — {@code NoopExternalAugmentClient} 의 조건과 같은 값이다. */
    public static final String KEY_MODE = "authoring.augment.external.mode";

    /** 외부 미연동 모드 값 — 이 값일 때만 요청 접수를 거부한다. */
    static final String MODE_NOOP = "noop";

    private final String mode;

    public AugmentExternalModePolicy(
            // 코드 기본값은 http 다 — NoopExternalAugmentClient 가 "noop 명시" 전용이라 미설정=http 배선이다.
            @Value("${" + KEY_MODE + ":http}") String mode) {
        this.mode = mode;
    }

    /**
     * 외부 증강 시스템과 <b>연동되지 않은</b> 모드인가 ({@code mode=noop}).
     *
     * <p>판정은 {@code noop} <b>하나만</b> 참이다. 미설정·빈 값은 http 배선과 같으므로 거짓(=연동)이다.
     *
     * <p><b>그 밖의 값(오타 등)도 거짓으로 둔다</b> — 알 수 없는 값을 미연동으로 해석하면 오타 하나가
     * 정상 요청을 전건 503 으로 막는다. 그리고 그런 값은 애초에 <b>요청 경로에 도달하지 못한다</b>:
     * {@code mode=dev} 같은 미지의 값에서는 http·noop 어느 구현체도 활성되지 않아 기동 자체가 실패한다
     * ({@code ExternalAugmentClientBeanConditionTest}). 즉 이 판정이 다루는 실효 값은 http 와 noop 뿐이다.
     */
    public boolean isNotLinked() {
        return mode != null && MODE_NOOP.equalsIgnoreCase(mode.trim());
    }
}
