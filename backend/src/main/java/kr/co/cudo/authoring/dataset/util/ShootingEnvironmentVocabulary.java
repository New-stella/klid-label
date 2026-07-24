package kr.co.cudo.authoring.dataset.util;

import java.util.Set;

/**
 * 촬영환경(날씨·시간대·계절) 수동입력 <b>허용값 화이트리스트</b> (CWE-20/79 방어).
 *
 * <p>수동 저장값은 검수 승인 스냅샷({@code LS_DATASET_VIDEO_META})과 학습데이터 export JSON
 * (NIA {@code video.weather/time_of_day/season})에 그대로 실린다. 따라서 자유 텍스트를 허용하면
 * ①스크립트 문자열이 산출물로 새고(CWE-79) ②저장 컬럼 길이(V130 — WTHR_NM/DAY_NGT_CD/SESN_CD 모두
 * VARCHAR(20))를 초과해
 * 승인 트랜잭션이 통째로 롤백된다. 입력은 아래 고정 코드집합만 허용한다.
 *
 * <p><b>시간대·계절은 {@link TimeOfDaySeasonDeriver} 의 파생 상수를 그대로 재사용</b>한다 —
 * 파생값(SHT_DT 기반)과 수동값의 코드집합을 소스 레벨에서 일치시켜, 프리필→저장→동결→export
 * 전 구간에서 같은 값 공간을 쓴다(코드 드리프트 차단).
 *
 * <p>날씨는 자동 출처가 없어(수기 전용) 고정 5종으로 한정한다.
 */
public final class ShootingEnvironmentVocabulary {

    /** 날씨 허용값 5종 — 자동 출처 없음(작업자 수기). */
    public static final Set<String> WEATHERS = Set.of("맑음", "흐림", "비", "눈", "안개");

    /** 시간대 허용값 — 파생 상수 재사용(DAY/NGT). */
    public static final Set<String> TIME_OF_DAYS =
            Set.of(TimeOfDaySeasonDeriver.DAY, TimeOfDaySeasonDeriver.NIGHT);

    /** 계절 허용값 — 파생 상수 재사용(SPRING/SUMMER/FALL/WINTER). */
    public static final Set<String> SEASONS = Set.of(
            TimeOfDaySeasonDeriver.SPRING, TimeOfDaySeasonDeriver.SUMMER,
            TimeOfDaySeasonDeriver.FALL, TimeOfDaySeasonDeriver.WINTER);

    /** 저장 컬럼 길이 상한 — {@code LS_DATA_RAW.WTHR_NM/DAY_NGT_CD/SESN_CD} VARCHAR(20). */
    public static final int MAX_LENGTH = 20;

    private ShootingEnvironmentVocabulary() {
    }
}
