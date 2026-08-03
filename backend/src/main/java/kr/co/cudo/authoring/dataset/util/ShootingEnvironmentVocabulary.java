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
 * <p><b>날씨는 자동 출처가 없다(수기 전용)</b> — 고정 5종으로 한정한다. 관제 이벤트리스트에
 * {@code WTHR_CD} 가 있으나 <b>저작도구는 소비하지 않는다</b>(2026-07-31 사용자 확정): 관제는 코드값
 * (예: {@code CLEAR}), 여기는 한글 표시명이라 <b>대응표가 없어 영구 미매칭</b>이고, 대응표 없이 변환하면
 * 그 변환 자체가 추정(self-fill)이 된다. 이 집합에 관제 코드값을 섞어 넣어 "매칭되게" 만들지 말 것 —
 * 표시명과 코드값이 한 어휘에 공존하면 저장·동결·export 값이 영상마다 갈린다.
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
