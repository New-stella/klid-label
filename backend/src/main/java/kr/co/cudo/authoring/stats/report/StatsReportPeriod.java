package kr.co.cudo.authoring.stats.report;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;

/**
 * SCR-STAT-002 리포트 다운로드({@code GET /v1/stats/report})의 집계 기간.
 *
 * <p><b>기간 → 일수 매핑의 단일 지점이다.</b> 컨트롤러·서비스·작성기가 각자 유도하면 한쪽만
 * 바뀌는 드리프트가 생기므로 이 enum 밖에서 일수를 계산하지 않는다.
 *
 * <p>이 창은 <b>「일별 작업량」 블록에만</b> 적용된다 — 누적 학습데이터·처리현황·이벤트 유형
 * 분포·작업자별 현황은 기간과 무관한 누적 또는 현재 시점 집계다.
 *
 * @design API-058
 */
public enum StatsReportPeriod {

    WEEK(7),
    MONTH(30),
    QUARTER(90),
    YEAR(365);

    /** allowlist 위반 시 사용자 메시지 — 컨트롤러 {@code @Pattern} 메시지와 동일 문구. */
    private static final String ALLOWLIST_MESSAGE = "period 는 WEEK|MONTH|QUARTER|YEAR 만 허용";

    private final int days;

    StatsReportPeriod(int days) {
        this.days = days;
    }

    /** 「일별 작업량」 블록의 집계 창(일수). */
    public int days() {
        return days;
    }

    /**
     * 요청 파라미터 문자열을 기간으로 해석한다.
     *
     * <p>컨트롤러의 {@code @Pattern} allowlist 가 1차 방어선이고 이 메서드는 심층 방어다.
     * 실패해도 <b>입력값을 메시지에 되비추지 않는다</b>(CWE-117 로그/응답 인젝션 차단).
     */
    public static StatsReportPeriod from(String raw) {
        if (raw == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, ALLOWLIST_MESSAGE);
        }
        for (StatsReportPeriod p : values()) {
            if (p.name().equals(raw)) {
                return p;
            }
        }
        throw new CustomException(ErrorCode.INVALID_INPUT, ALLOWLIST_MESSAGE);
    }
}
