package kr.co.cudo.authoring.sysconfig.endpoint;

import kr.co.cudo.authoring.sysconfig.ConfigKeys;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 운영 화면에서 주소를 바꿀 수 있는 <b>외부 연동 4종</b> (R11).
 *
 * <h3>왜 설정 키가 애플리케이션 속성명과 같은가</h3>
 * <p>설정 키를 별도로 만들면 "설정 키 ↔ 속성명" 매핑표가 생기고, 그 표가 <b>두 번째 진실원</b>이 되어
 * 한쪽만 갱신되는 순간 화면에서 바꾼 주소가 엉뚱한 연동에 반영된다. 키를 속성명과 <b>같게</b> 두면
 * "설정에 값이 있으면 설정, 없으면 배포 기본값" 이 표 없이 성립한다.
 *
 * <h3>{@code displayName} 의 용도</h3>
 * <p>운영자에게 보여줄 대상 이름이다. 검증 실패 메시지에 <b>속성명 대신</b> 이 값을 쓴다 —
 * 내부 속성명은 설정 파일 구조를 알려주는 정보라 응답에 실을 이유가 없다.
 */
public enum IntegrationEndpoint {

    /**
     * 비식별 서버.
     *
     * <p><b>키가 {@code kpst.deid.base-url} 인 이유</b>(2026-08-10 사용자 확정): 실제 비식별 위탁은
     * {@code KpstDeidentifyClient} 가 이 속성으로 만들어진 빈을 통해 보낸다. 구 설계가 지정했던
     * {@code authoring.integration.deidentify.base-url} 은 <b>주입 대상이 0건인 빈</b>을 구동해,
     * 바꿔도 위탁 주소가 달라지지 않았다 — 실효 0 인 칸을 화면에 남기지 않는다.
     */
    DEIDENTIFY(ConfigKeys.KPST_DEID_BASE_URL, "비식별 서버"),

    /** AI 추론 서버(ai-server). */
    AI_SERVER(ConfigKeys.INTEGRATION_AI_SERVER_BASE_URL, "AI 추론 서버"),

    /** 외부 시계열 분석 벤더. */
    VLM(ConfigKeys.VLM_CLIENT_URL, "외부 시계열 분석 벤더"),

    /** 관제 통지 수신처. */
    CONTROL_NOTIFY(ConfigKeys.CONTROL_NOTIFY_URL, "관제 통지 수신처");

    private static final Map<String, IntegrationEndpoint> BY_CONFIG_KEY =
            java.util.Arrays.stream(values())
                    .collect(Collectors.toUnmodifiableMap(IntegrationEndpoint::configKey, Function.identity()));

    /** 화이트리스트 판정용 — 이 집합의 키만 관리자 세션 토큰을 추가로 요구한다. */
    public static final Set<String> CONFIG_KEYS = BY_CONFIG_KEY.keySet();

    private final String configKey;
    private final String displayName;

    IntegrationEndpoint(String configKey, String displayName) {
        this.configKey = configKey;
        this.displayName = displayName;
    }

    public String configKey() {
        return configKey;
    }

    public String displayName() {
        return displayName;
    }

    public static Optional<IntegrationEndpoint> byConfigKey(String key) {
        return Optional.ofNullable(BY_CONFIG_KEY.get(key));
    }

    /** 이 키가 연동 주소 키인가 — 관리자 세션 요구 여부 판정의 단일 지점. */
    public static boolean isEndpointKey(String key) {
        return key != null && BY_CONFIG_KEY.containsKey(key);
    }
}
