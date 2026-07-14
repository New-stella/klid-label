package kr.co.cudo.authoring.dataset.util;

import java.time.LocalDateTime;

/**
 * 촬영일시(SHT_DT) → 주야간구분코드(DAY_NGT_CD)·계절코드(SESN_CD) 결정론적 파생 유틸.
 *
 * <p>NIA 통합 메타(LS_DATASET_VIDEO_META)의 두 파생 컬럼을 SHT_DT 로부터 순수 함수로 계산한다.
 * 자동 출처(ffprobe/MNG)가 없는 파생값이므로 결정론적 규칙을 코드로 고정하고 주석으로 명시한다
 * (같은 입력 → 항상 같은 코드).
 *
 * <p><b>주야간(DAY_NGT_CD)</b>: 촬영 시각(hour)이 06:00(포함)~18:00(미포함)이면 {@code DAY},
 * 그 외(야간)는 {@code NGT}. program 표준 주야간=DAY_NGT(DAY/NGT) 코드와 정합(설계 §1-3/§1-4).
 * 경계는 06:00=DAY, 18:00=NGT 로 고정한다(일출/일몰 정밀 계산은 자동 출처 부재로 도입하지 않음).
 *
 * <p><b>계절(SESN_CD)</b>: 촬영 월(1~12) 기준 북반구 통상 구분 —
 * 3~5월 {@code SPRING}, 6~8월 {@code SUMMER}, 9~11월 {@code FALL}, 12·1·2월 {@code WINTER}.
 * program 표준 계절=SESN 코드(기존 코드의 FALL 등과 정합, 설계 §1-4).
 *
 * <p>{@code shtDt} 가 null 이면 두 파생값 모두 null(미상) 을 반환한다 — "값"과 "미상"을 구분한다.
 */
public final class TimeOfDaySeasonDeriver {

    /** 주야간구분코드 — 주간. */
    public static final String DAY = "DAY";
    /** 주야간구분코드 — 야간. */
    public static final String NIGHT = "NGT";

    /** 계절코드. VARCHAR(8) 범위 내(최대 6자). */
    public static final String SPRING = "SPRING";
    public static final String SUMMER = "SUMMER";
    public static final String FALL = "FALL";
    public static final String WINTER = "WINTER";

    /** 주간 시작 시각(포함) — 06:00. */
    private static final int DAY_START_HOUR = 6;
    /** 주간 종료 시각(미포함) — 18:00. */
    private static final int DAY_END_HOUR = 18;

    private TimeOfDaySeasonDeriver() {
    }

    /**
     * 촬영 시각으로 주야간구분코드를 파생한다.
     *
     * @param shtDt 촬영일시 (null 이면 null 반환)
     * @return {@link #DAY} 또는 {@link #NIGHT}, 입력 null 이면 null
     */
    public static String dayNight(LocalDateTime shtDt) {
        if (shtDt == null) {
            return null;
        }
        int hour = shtDt.getHour();
        return (hour >= DAY_START_HOUR && hour < DAY_END_HOUR) ? DAY : NIGHT;
    }

    /**
     * 촬영 월로 계절코드를 파생한다(북반구 3-3-3-3 통상 구분).
     *
     * @param shtDt 촬영일시 (null 이면 null 반환)
     * @return 계절코드, 입력 null 이면 null
     */
    public static String season(LocalDateTime shtDt) {
        if (shtDt == null) {
            return null;
        }
        int month = shtDt.getMonthValue();
        return switch (month) {
            case 3, 4, 5 -> SPRING;
            case 6, 7, 8 -> SUMMER;
            case 9, 10, 11 -> FALL;
            default -> WINTER; // 12, 1, 2
        };
    }
}
