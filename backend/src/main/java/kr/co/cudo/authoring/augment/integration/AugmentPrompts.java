package kr.co.cudo.authoring.augment.integration;

import kr.co.cudo.authoring.augment.entity.LsDataAug;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 외부 위탁 {@code mtdt} 조립 — 「생성형 AI API 연동명세서 v1.3」 §4.1 최상위 구조화 생성 조건.
 *
 * <p>mtdt 의 <b>키 집합과 순서</b>를 서비스/클라이언트 곳곳에 흩뿌리지 않고 이 한 곳에서만 정한다.
 *
 * <h3>2026-08-27 — v1.3 정합: {@code prompt}(객체) → {@code mtdt}(객체) + {@code prompt}(문자열)</h3>
 * <p>구 계약(v1.1)은 생성 조건 5항목을 {@code prompt} 라는 <b>자유 구조 dict</b> 로 받았다. v1.3 은 그
 * 항목들을 최상위 {@code mtdt} 객체로 옮기고 {@code prompt} 를 <b>자유 지시문 문자열</b> 로 재정의했으며,
 * 구 객체 형식({@code prompt.condition}/{@code prompt.text})과 최상위 {@code condition} 을 계약에서
 * 제외했다 — {@code prompt} 에 객체를 실으면 {@code 400 INVALID_PARAMETER} 다.
 *
 * <h3>값은 자유 문자열이 아니라 <b>허용 코드</b>다 (2026-08-27)</h3>
 * <p>v1.3 은 다섯 축 전부의 허용 코드를 닫아 두었다({@link Time}·{@link Season}·{@link Weather}·
 * {@link Terrain}·{@link Severity}). 그래서 구 서술("계약이 허용값을 규정하지 않으므로 우리가 좁히지
 * 않는다")은 <b>폐기</b>다 — 그대로 두면 코드 밖 값이 벤더에서 {@code 400} 으로 되돌아온다.
 * 허용 코드의 단일 원천은 이 클래스이며, API 요청 DTO 는 <b>사본을 만들지 않고 이 enum 을 그대로
 * 참조</b>한다(사본을 두면 계약이 늘 때 한쪽만 고쳐져 드리프트가 난다).
 *
 * <p><b>다섯 항목 전부 필수는 우리 규칙이다</b>: 벤더 계약은 "최소 1개" 이지만 하나라도 비면 벤더가
 * 어떤 기본값으로 채울지 우리가 알 수 없어 결과가 비결정적이 된다(2026-07-31 사용자 확정). 더 엄격한
 * 쪽이 <b>의도된 선택</b>이므로 "계약이 선택이니 완화하자" 로 되돌리지 말 것.
 *
 * <p><b>여기서 {@code AUG_TYPE_CD} 를 파생하지 않는다</b>: 증강 유형의 단일 원천은 요청 DTO 의
 * {@code types[]} enum 이다. 생성 조건 값(예: {@code season=WINTER})으로 유형을 유추하면 그 값이
 * {@code AUG_TYPE_CD} 를 거쳐 파생 산출물 경로({@code .../{augTypeCd}.mp4})와 해상도 네임스페이스
 * ({@code RESL_} 접두) 판별로 흘러 경로 순회(CWE-22)·검수 우회가 열린다. 허용 코드로 닫힌 뒤에도
 * 이 방어는 그대로 유효하다 — 코드 공간이 겹치기 때문이다({@code Season.WINTER} ↔ 구 {@code AUG_WINTER}).
 * 종류 코드가 단일값 {@code AUG_AUGMENT} 로 합쳐진 뒤에도(ADR-059) 마찬가지다 —
 * <b>단일 상수로 고정하는 것이지 조건에서 유도하는 것이 아니다</b>.
 *
 * @design INT-008
 */
public final class AugmentPrompts {

    /** mtdt 키 — 시간대. */
    public static final String KEY_TIME = "time";
    /** mtdt 키 — 계절. */
    public static final String KEY_SEASON = "season";
    /** mtdt 키 — 날씨. */
    public static final String KEY_WEATHER = "weather";
    /** mtdt 키 — 지형. */
    public static final String KEY_TERRAIN = "terrain";
    /** mtdt 키 — 심각도. */
    public static final String KEY_SEVERITY = "severity";

    /** v1.3 {@code mtdt.time} 허용 코드. */
    public enum Time {
        DAWN, DAY, DUSK, NIGHT
    }

    /** v1.3 {@code mtdt.season} 허용 코드. */
    public enum Season {
        SPRING, SUMMER, AUTUMN, WINTER
    }

    /** v1.3 {@code mtdt.weather} 허용 코드. */
    public enum Weather {
        CLEAR, CLOUDY, RAIN, SNOW, FOG, WINDY
    }

    /** v1.3 {@code mtdt.terrain} 허용 코드. */
    public enum Terrain {
        ROAD, UNDERPASS, RIVER, URBAN, RESIDENTIAL, RURAL, MOUNTAIN, FOREST
    }

    /** v1.3 {@code mtdt.severity} 허용 코드. */
    public enum Severity {
        LOW, MEDIUM, HIGH
    }

    /**
     * 자유 지시문({@code prompt}) 길이 상한 — v1.3 §4.1.
     *
     * <p>상한이 없으면 저장 컬럼({@code LS_DATA_AUG.PROMPT_CN}, VARCHAR(4000))을 넘겨 적재 시점 500 이
     * 되고, 무제한 입력이 그대로 외부로 중계된다(CWE-770).
     */
    public static final int MAX_PROMPT_LENGTH = 1000;

    private AugmentPrompts() {
    }

    /**
     * 허용 코드 5항목을 명세서와 동일한 키/순서의 {@code mtdt} 객체로 만든다.
     *
     * <p>입력이 enum 이므로 코드 밖 값은 <b>여기 도달할 수 없다</b>(바인딩 단계에서 400). 그럼에도
     * null 은 여기서 다시 거부한다 — 빈 조건이 외부로 나가면 벤더가 임의 기본값으로 채워 결과가
     * 비결정적이 되므로, 배선 실수를 조용히 통과시키지 않는다.
     *
     * @return 불변 dict (키 순서: time → season → weather → terrain → severity)
     * @throws IllegalArgumentException 5항목 중 하나라도 null 일 때(배선 오류 tripwire)
     */
    public static Map<String, Object> mtdt(Time time, Season season, Weather weather,
                                           Terrain terrain, Severity severity) {
        Map<String, Object> mtdt = new LinkedHashMap<>();
        mtdt.put(KEY_TIME, require(time, KEY_TIME).name());
        mtdt.put(KEY_SEASON, require(season, KEY_SEASON).name());
        mtdt.put(KEY_WEATHER, require(weather, KEY_WEATHER).name());
        mtdt.put(KEY_TERRAIN, require(terrain, KEY_TERRAIN).name());
        mtdt.put(KEY_SEVERITY, require(severity, KEY_SEVERITY).name());
        return Collections.unmodifiableMap(mtdt);
    }

    /** 빈 조건 외부 유출 차단. 값 자체는 예외 메시지에 싣지 않는다(CWE-117/209). */
    private static <T extends Enum<T>> T require(T value, String key) {
        if (value == null) {
            throw new IllegalArgumentException("mtdt 항목이 비어 있습니다: " + key);
        }
        return value;
    }

    /**
     * 외부 위탁 대상 코드 집합 — <b>단일 원천</b>.
     *
     * <p>{@link #isExternalAugType} 과 회수 스윕의 후보 SQL(만료 스윕)이 같은 목록을 쓰도록 상수로
     * 노출한다. 두 곳이 각자 코드 목록을 나열하면 증강 유형이 늘 때 한쪽만 고쳐져 드리프트가 난다.
     *
     * <p>신규 요청은 {@link LsDataAug#AUG_AUGMENT} 하나만 만들지만(ADR-059),
     * <b>구 3종은 목록에서 빼지 않는다</b> — 백필하지 않아 미종결 위탁이 그 코드로 남아 있을 수 있고,
     * 빼면 만료 스윕이 그 건을 후보로 집지 못해 <b>영원히 PENDING 으로 고착</b>한다.
     */
    public static final java.util.List<String> EXTERNAL_AUG_TYPES =
            java.util.List.of(LsDataAug.AUG_AUGMENT,
                    LsDataAug.AUG_WINTER, LsDataAug.AUG_NIGHT, LsDataAug.AUG_RAIN);

    /** 외부 위탁 대상(현행 단일값 + 구 3종)인지 판별한다. */
    public static boolean isExternalAugType(String augType) {
        return EXTERNAL_AUG_TYPES.contains(augType);
    }
}
