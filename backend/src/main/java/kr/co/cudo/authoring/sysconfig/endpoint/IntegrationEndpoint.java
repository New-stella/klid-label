package kr.co.cudo.authoring.sysconfig.endpoint;

import kr.co.cudo.authoring.sysconfig.ConfigKeys;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 저장 시 <b>주소 형식 검증 + 관리자 유효창</b>을 요구하는 외부 연동 열거 (R11).
 *
 * <p>대상은 비식별 서버 · AI 추론 서버 · 외부 시계열 분석 벤더 · 관제 통지 수신처 · 외부 증강 벤더다.
 *
 * <h3>⚠ 구 서술 폐기(2026-09-08)</h3>
 * <p><i>"운영 화면에서 주소를 바꿀 수 있는 외부 연동 4종"</i> 은 <b>더 이상 사실이 아니다.</b> 화면에서
 * AI 추론 · 외부 시계열 분석 벤더 칸이 빠지고 외부 증강 벤더 칸이 들어왔다. <b>개수 표기도 쓰지
 * 않는다</b> — 축이 하나 늘거나 옮겨갈 때마다 그 숫자를 인용한 자리가 한꺼번에 틀린다(이 저장소에서
 * 같은 형태의 사고가 이미 여러 번 났다). 열거로 읽는다.
 *
 * <h3>★ 이 열거는 화면 칸 목록과 같은 집합이 아니다 — 같게 만들지 말 것</h3>
 * <p>이 열거가 정하는 것은 <b>「주소 형식 검증을 태우고 관리자 유효창을 요구할 대상인가」</b>이고,
 * 사람이 화면에서 고치는 칸은 그보다 <b>좁다</b>(비식별 · 외부 증강 벤더 · 관제 통지).
 *
 * <p>{@link #AI_SERVER}·{@link #VLM} 은 <b>주소의 진실원이 장비 원장으로 옮겨가</b> 화면 칸에서
 * 빠졌지만, 그 배포 설정값은 <b>그 유형의 장비가 하나도 없을 때 최초 1회 씨앗</b>으로 계속 저장될 수
 * 있다. 즉 그 두 키로 값이 들어오는 경로가 남아 있으므로 이 열거에는 <b>남는 것이 맞다</b> —
 * 빼면 그 경로가 <b>형식 검증도 유효창도 없이</b> 저장된다(인가가 조용히 약해진다).
 *
 * <p>⚠ 두 집합을 «일관성» 을 이유로 맞추려는 시도를 막는 것이 이 절의 목적이다.
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
    CONTROL_NOTIFY(ConfigKeys.CONTROL_NOTIFY_URL, "관제 통지 수신처"),

    /**
     * 외부 증강(생성형 AI) 벤더. [@design ADR-046] [@design API-069] [@design SCREEN-042]
     *
     * <p>확정 정책이 처음부터 대상으로 정해 두고도 이 열거에 <b>들어 있지 않던 잔여</b>였다. 그 상태의
     * 결과는 셋이었다 — ①화이트리스트 밖이라 저장 요청이 400 ②선언 타입이 없어 최초 저장이 404
     * ③<b>이 열거에 없어 주소 형식 검증도 관리자 유효창도 걸리지 않는다</b>. 셋째가 특히 문제였는데,
     * 기능이 아니라 <b>인가</b>가 다른 연동 주소와 달라지는 축이기 때문이다.
     *
     * <p>★ <b>비어 있는 것이 정상 상태</b>다 — 저장 행이 없는 것이 「아직 연동하지 않았다」를 나타내는
     * 유일한 표현이라 연동 확정 전에는 채우지 않는다. 단 <b>빈 문자열 저장은 다른 연동과 똑같이
     * 400</b> 이다({@code IntegrationEndpointUrlValidator}) — 「행이 없음」과 「빈 값 저장」은 다른 것이다.
     *
     * <p>주소 값 판정 축은 다른 연동과 <b>같다</b>(스킴 + 형식, 대역 미차단). 증강 전용 정책
     * ({@code AugmentUrlPolicy})은 <b>다른 층</b>이라 어느 쪽이 이기는 관계가 아니다 — 이쪽은
     * <b>저장 시점</b>, 그쪽은 <b>전송 시점</b>이며 둘 다 걸린다.
     */
    AUGMENT(ConfigKeys.AUGMENT_EXTERNAL_BASE_URL, "외부 증강 벤더");

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
