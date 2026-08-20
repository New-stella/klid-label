package kr.co.cudo.authoring.transfer.parser;

import kr.co.cudo.authoring.dataset.util.ShootingEnvironmentVocabulary;

import java.util.Locale;
import java.util.Map;

/**
 * 외부 산출물의 촬영환경 표기를 저작도구 <b>정규값</b>으로 옮기는 단일 지점.
 *
 * <h3>별칭은 여기에만 둔다</h3>
 * <p>같은 뜻을 다르게 적은 표기({@code NIGHT} 와 {@code NGT}, {@code AUTUMN} 과 {@code FALL})를
 * 호출부마다 다시 매칭하면 한 곳이 빠져 같은 산출물이 경로마다 다르게 저장된다. 별칭표는 이 클래스의
 * 상수 하나뿐이다.
 *
 * <h3>없는 별칭을 만들지 않는다</h3>
 * <p>여기 담긴 것은 <b>실제로 확인된 표기</b>뿐이다. 허용값 밖의 값은 {@code null} 로 돌려 호출부가
 * 경고를 남기게 한다 — 짐작으로 옮기면 촬영환경이 틀린 채로 학습데이터 산출물에 실리고, 저장된 뒤에는
 * 어느 것이 짐작이었는지 구분할 수 없다.
 *
 * <h3>날씨에는 별칭표를 두지 않는다</h3>
 * <p>저작도구의 날씨 허용값은 한글 5종({@code 맑음/흐림/비/눈/안개})이고 외부 산출물이 그 축을 어떤
 * 표기로 적는지 <b>확인된 적이 없다</b>(확인한 산출물에서는 빈 값이었다). 대응표 없이 옮기면 그 변환
 * 자체가 추정이 되므로, 허용값과 정확히 같을 때만 통과시키고 나머지는 {@code null} + 경고다.
 * ({@code ShootingEnvironmentVocabulary} 가 같은 이유로 관제 코드값을 섞지 않는 것과 같은 축이다.)
 *
 * @design DOMAIN-017
 */
public final class ImportShootingEnvironment {

    /**
     * 시간대 별칭 — 열쇠는 <b>trim + 대문자</b> 후의 값이다. 정규값 자신도 넣어 호출부가 분기하지
     * 않게 한다.
     */
    private static final Map<String, String> TIME_OF_DAY_ALIASES = Map.of(
            "DAY", "DAY",
            "NIGHT", "NGT",
            "NGT", "NGT");

    /** 계절 별칭 — {@code AUTUMN} 과 {@code FALL} 이 같은 계절이다. */
    private static final Map<String, String> SEASON_ALIASES = Map.of(
            "SPRING", "SPRING",
            "SUMMER", "SUMMER",
            "FALL", "FALL",
            "AUTUMN", "FALL",
            "WINTER", "WINTER");

    private ImportShootingEnvironment() {
    }

    /**
     * 시간대 정규화.
     *
     * @return {@code DAY}/{@code NGT}, 알 수 없으면 {@code null}
     */
    public static String timeOfDay(String raw) {
        String key = key(raw);
        if (key == null) {
            return null;
        }
        String canonical = TIME_OF_DAY_ALIASES.get(key);
        // 별칭표를 통과했어도 저작도구 허용값인지 다시 확인한다 — 허용값 집합이 바뀌면 여기가 먼저 막힌다.
        return canonical != null && ShootingEnvironmentVocabulary.TIME_OF_DAYS.contains(canonical)
                ? canonical : null;
    }

    /**
     * 계절 정규화.
     *
     * @return {@code SPRING}/{@code SUMMER}/{@code FALL}/{@code WINTER}, 알 수 없으면 {@code null}
     */
    public static String season(String raw) {
        String key = key(raw);
        if (key == null) {
            return null;
        }
        String canonical = SEASON_ALIASES.get(key);
        return canonical != null && ShootingEnvironmentVocabulary.SEASONS.contains(canonical)
                ? canonical : null;
    }

    /**
     * 날씨 — <b>별칭 없이</b> 허용값과 정확히 같을 때만 통과시킨다(위 클래스 주석 참조).
     *
     * @return 허용값 그대로, 아니면 {@code null}
     */
    public static String weather(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return ShootingEnvironmentVocabulary.WEATHERS.contains(trimmed) ? trimmed : null;
    }

    /** 빈 값은 "적지 않았다" 이지 "모르는 값" 이 아니다 — 경고 대상과 구분하려 호출부가 먼저 판정한다. */
    public static boolean isAbsent(String raw) {
        return raw == null || raw.isBlank();
    }

    private static String key(String raw) {
        if (isAbsent(raw)) {
            return null;
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }
}
