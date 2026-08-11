package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.batch.dto.KpstDeidentReportSummary;
import kr.co.cudo.authoring.common.client.dto.KpstReportResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R14 — 벤더 리포트 응답({@code dsStatus[]} 1행) → 적재 요약 변환 검증.
 *
 * <p>이 변환은 <b>절대 예외를 던지지 않는다</b>. 리포트 조회·해석 실패가 비식별 완료 전이를 막으면
 * 안 되기 때문이다(정본 정책: 조회 실패는 WARN + 리포트 컬럼 null, 흐름은 계속).
 */
class KpstDeidentReportSummaryTest {

    private KpstReportResponse.DsStatus ds(String startTime, String endTime) {
        return new KpstReportResponse.DsStatus(
                1270L, "/nas/raw/001.mp4", 12L, 3L, 5400L, startTime, endTime);
    }

    @Test
    @DisplayName("벤더_집계값과_처리시각을_그대로_옮긴다")
    void mapsCountsAndTimes() {
        // given — 목/실서버 공통 포맷 'yyyy-MM-dd HH:mm:ss'
        KpstReportResponse.DsStatus ds = ds("2026-08-11 10:00:00", "2026-08-11 10:05:30");

        // when
        KpstDeidentReportSummary summary = KpstDeidentReportSummary.from(ds);

        // then
        assertThat(summary).isNotNull();
        assertThat(summary.faceCount()).isEqualTo(12L);
        assertThat(summary.lpCount()).isEqualTo(3L);
        assertThat(summary.totalFrame()).isEqualTo(5400L);
        assertThat(summary.startedAt()).isEqualTo(LocalDateTime.of(2026, 8, 11, 10, 0, 0));
        assertThat(summary.endedAt()).isEqualTo(LocalDateTime.of(2026, 8, 11, 10, 5, 30));
        assertThat(summary.reportFilePath()).isEqualTo("/nas/raw/001.mp4");
    }

    @Test
    @DisplayName("ISO_형식_처리시각도_해석한다")
    void parsesIsoDateTime() {
        KpstDeidentReportSummary summary =
                KpstDeidentReportSummary.from(ds("2026-08-11T10:00:00", "2026-08-11T10:05:30"));

        assertThat(summary.startedAt()).isEqualTo(LocalDateTime.of(2026, 8, 11, 10, 0, 0));
        assertThat(summary.endedAt()).isEqualTo(LocalDateTime.of(2026, 8, 11, 10, 5, 30));
    }

    @Test
    @DisplayName("처리시각이_None_이거나_형식이_다르면_null_로_두고_예외를_던지지_않는다")
    void unparseableTimesBecomeNull() {
        // given — 실서버는 미시작 구간에서 문자열 "None" 을 반환하는 것이 실측 확인됐다.
        KpstDeidentReportSummary summary = KpstDeidentReportSummary.from(ds("None", "언제인지모름"));

        // then — 집계값은 살아있고 시각만 null 이다(부분 성공 허용).
        assertThat(summary.startedAt()).isNull();
        assertThat(summary.endedAt()).isNull();
        assertThat(summary.faceCount()).isEqualTo(12L);
    }

    @Test
    @DisplayName("데이터셋_응답이_null_이면_요약도_null_이다")
    void nullDatasetYieldsNull() {
        assertThat(KpstDeidentReportSummary.from(null)).isNull();
    }

    @Test
    @DisplayName("집계값이_전부_null_이어도_예외없이_null_요약값으로_변환된다")
    void allNullCountsAreTolerated() {
        KpstReportResponse.DsStatus ds =
                new KpstReportResponse.DsStatus(1270L, null, null, null, null, null, null);

        KpstDeidentReportSummary summary = KpstDeidentReportSummary.from(ds);

        assertThat(summary).isNotNull();
        assertThat(summary.faceCount()).isNull();
        assertThat(summary.lpCount()).isNull();
        assertThat(summary.totalFrame()).isNull();
        assertThat(summary.reportFilePath()).isNull();
    }

    @Test
    @DisplayName("컬럼_폭을_넘는_파일경로는_잘라서_적재한다_DB오류로_새지_않는다")
    void overlongPathIsTruncatedToColumnWidth() {
        // given — 외부값이라 길이를 신뢰할 수 없다. 컬럼(VARCHAR 1000)을 넘기면 INSERT 가 500 으로 샌다.
        String overlong = "/nas/" + "a".repeat(2000) + ".mp4";
        KpstReportResponse.DsStatus ds =
                new KpstReportResponse.DsStatus(1270L, overlong, 1L, 1L, 1L, null, null);

        // when
        KpstDeidentReportSummary summary = KpstDeidentReportSummary.from(ds);

        // then
        assertThat(summary.reportFilePath())
                .hasSize(KpstDeidentReportSummary.MAX_REPORT_FILE_PATH_LEN);
    }
}
