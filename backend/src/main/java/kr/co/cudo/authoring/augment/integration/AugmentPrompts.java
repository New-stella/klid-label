package kr.co.cudo.authoring.augment.integration;

import kr.co.cudo.authoring.augment.entity.LsDataAug;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 외부 위탁 {@code prompt} 조립 — 「생성형 AI API 연동명세서 v1.1」 §4.1 {@code prompt}(자유 구조 dict).
 *
 * <p>prompt 의 <b>키 집합과 순서</b>를 서비스/클라이언트 곳곳에 흩뿌리지 않고 이 한 곳에서만 정한다.
 *
 * <h3>2026-07-31 — 서버 고정 문구에서 <b>사용자 입력 통과</b>로 전환</h3>
 * <p>구 구현은 증강 유형(WINTER/NIGHT/RAIN)마다 영문 지시문을 서버가 만들어 보냈다. 그래서 REVIEWER 는
 * 생성 조건을 조절할 수 없었고, 같은 유형은 언제 요청해도 같은 조건이었다. 지금은 REVIEWER 가 입력한
 * 5필드(time/season/weather/terrain/severity)를 <b>가공 없이 그대로</b> dict 로 만들어 보낸다.
 *
 * <p><b>여기서 {@code AUG_TYPE_CD} 를 파생하지 않는다</b>: 증강 유형의 단일 원천은 요청 DTO 의
 * {@code types[]} enum 이다. 사용자 자유 문자열(예: {@code season="WINTER"})로 유형을 유추하면 그 값이
 * {@code AUG_TYPE_CD} 를 거쳐 파생 산출물 경로({@code .../{augTypeCd}.mp4})와 해상도 네임스페이스
 * ({@code RESL_} 접두) 판별로 흘러 경로 순회(CWE-22)·검수 우회가 열린다. enum 유지가 그 방어다.
 */
public final class AugmentPrompts {

    /** prompt 키 — 시간대. */
    public static final String KEY_TIME = "time";
    /** prompt 키 — 계절. */
    public static final String KEY_SEASON = "season";
    /** prompt 키 — 날씨. */
    public static final String KEY_WEATHER = "weather";
    /** prompt 키 — 지형. */
    public static final String KEY_TERRAIN = "terrain";
    /** prompt 키 — 심각도. */
    public static final String KEY_SEVERITY = "severity";

    private AugmentPrompts() {
    }

    /**
     * 사용자 입력 5필드를 명세서 샘플과 동일한 키/순서의 prompt dict 로 만든다.
     *
     * <p>입력은 <b>이미 정규화·검증된 값</b>이어야 한다(제어문자 제거·공백 제거·길이 확인은 호출부
     * {@code AugmentRequestService} 책임). 그럼에도 빈 값은 여기서 다시 거부한다 — 빈 조건이 외부로
     * 나가면 벤더가 임의 기본값으로 채워 결과가 비결정적이 되므로, 배선 실수를 조용히 통과시키지 않는다.
     *
     * @return 불변 dict (키 순서: time → season → weather → terrain → severity)
     * @throws IllegalArgumentException 5필드 중 하나라도 null/공백일 때(배선 오류 tripwire)
     */
    public static Map<String, Object> of(String time, String season, String weather,
                                         String terrain, String severity) {
        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put(KEY_TIME, require(time, KEY_TIME));
        prompt.put(KEY_SEASON, require(season, KEY_SEASON));
        prompt.put(KEY_WEATHER, require(weather, KEY_WEATHER));
        prompt.put(KEY_TERRAIN, require(terrain, KEY_TERRAIN));
        prompt.put(KEY_SEVERITY, require(severity, KEY_SEVERITY));
        return Collections.unmodifiableMap(prompt);
    }

    /** 빈 조건 외부 유출 차단. 값 자체는 예외 메시지에 싣지 않는다(CWE-117/209). */
    private static String require(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("prompt 항목이 비어 있습니다: " + key);
        }
        return value;
    }

    /**
     * 외부 위탁 대상(SFR-07 3종) 코드 집합 — <b>단일 원천</b>.
     *
     * <p>{@link #isExternalAugType} 과 회수 스윕의 후보 SQL(만료 스윕)이 같은 목록을 쓰도록 상수로
     * 노출한다. 두 곳이 각자 코드 목록을 나열하면 증강 유형이 늘 때 한쪽만 고쳐져 드리프트가 난다.
     */
    public static final java.util.List<String> EXTERNAL_AUG_TYPES =
            java.util.List.of(LsDataAug.AUG_WINTER, LsDataAug.AUG_NIGHT, LsDataAug.AUG_RAIN);

    /** 외부 위탁 대상(SFR-07 3종)인지 판별한다. */
    public static boolean isExternalAugType(String augType) {
        return EXTERNAL_AUG_TYPES.contains(augType);
    }
}
