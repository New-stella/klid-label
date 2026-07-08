package kr.co.cudo.authoring.common.config;

/**
 * 콜백 base URL 설정의 단일 출처 — 프로퍼티 키 + 폴백 리터럴 중복(드리프트) 예방 (Phase 2 DEV_FIX).
 *
 * <p>이전에는 {@code "http://localhost:8080/api"} 폴백 리터럴이 {@code WebClientConfig} /
 * {@code VlmTimeseriesStep}(@Value 기본값 + 런타임 폴백) 등 여러 곳에 흩어져 있어, 한쪽만 바뀌면
 * 조용히 어긋날 수 있었다. 본 상수로 통합해 {@code @Value} 표현식과 런타임 폴백이 항상 같은 값을 쓴다.
 *
 * <p>{@link #VALUE_EXPRESSION} 은 컴파일 타임 상수(모두 리터럴의 연결)라 애노테이션 속성으로 사용 가능하다.
 */
public final class WebhookCallbackDefaults {

    private WebhookCallbackDefaults() {
    }

    /** 콜백 base URL 프로퍼티 키. */
    public static final String PROPERTY_KEY = "authoring.webhook.callback-base-url";

    /** 미설정 시 폴백 base URL (local 자족 경로). */
    public static final String DEFAULT_BASE_URL = "http://localhost:8080/api";

    /** {@code @Value} 표현식 — 프로퍼티 키 + 폴백 기본값. */
    public static final String VALUE_EXPRESSION = "${" + PROPERTY_KEY + ":" + DEFAULT_BASE_URL + "}";
}
