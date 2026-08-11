package kr.co.cudo.authoring.batch.entity;

import kr.co.cudo.authoring.batch.dto.KpstDeidentReportSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R14 — 비식별 처리 결과 리포트(KPST {@code GET /retrieve_report}) 적재 검증.
 *
 * <p>회차마다 새 행이 INSERT 되는 {@code LS_DEIDENT_PROC_LOG} 에 리포트 집계값 6종을 얹어,
 * 그 행들이 곧 영상 상세의 <b>비식별 이력</b>이 되게 한다. 별도 이력 테이블을 두지 않는다.
 */
class LsDeidentProcLogReportTest {

    private LsDeidentProcLog newLog() {
        return LsDeidentProcLog.request(9001L, "req-1", "/nas/raw/001.mp4", "batch");
    }

    @Test
    @DisplayName("리포트_집계값_6종을_기록하면_모두_반영된다")
    void recordReportAppliesAllFields() {
        // given
        LsDeidentProcLog log = newLog();
        KpstDeidentReportSummary summary = new KpstDeidentReportSummary(
                12L, 3L, 5400L,
                LocalDateTime.of(2026, 8, 11, 10, 0, 0),
                LocalDateTime.of(2026, 8, 11, 10, 5, 30),
                "/nas/raw/001.mp4");

        // when
        log.recordReport(summary);

        // then
        assertThat(log.getFaceDtctCnt()).isEqualTo(12L);
        assertThat(log.getNoPltDtctCnt()).isEqualTo(3L);
        assertThat(log.getFrmeCnt()).isEqualTo(5400L);
        assertThat(log.getPrcsBgngDt()).isEqualTo(LocalDateTime.of(2026, 8, 11, 10, 0, 0));
        assertThat(log.getPrcsEndDt()).isEqualTo(LocalDateTime.of(2026, 8, 11, 10, 5, 30));
        assertThat(log.getRptFilePathNm()).isEqualTo("/nas/raw/001.mp4");
    }

    @Test
    @DisplayName("리포트가_null_이면_기존_값을_지우지_않는다")
    void recordReportNullIsNoOp() {
        // given — 리포트 조회 실패 시 서비스가 null 을 넘긴다(완료 전이는 그대로 진행).
        LsDeidentProcLog log = newLog();
        log.recordReport(new KpstDeidentReportSummary(1L, 2L, 3L, null, null, "/p.mp4"));

        // when
        log.recordReport(null);

        // then — 앞선 회차 값이 남아 있어야 한다(덮어쓰기·초기화 금지).
        assertThat(log.getFaceDtctCnt()).isEqualTo(1L);
        assertThat(log.getNoPltDtctCnt()).isEqualTo(2L);
        assertThat(log.getFrmeCnt()).isEqualTo(3L);
        assertThat(log.getRptFilePathNm()).isEqualTo("/p.mp4");
    }

    @Test
    @DisplayName("리포트를_기록해도_비식별_완료_전이_필드는_건드리지_않는다")
    void recordReportDoesNotTouchCompletionFields() {
        // given
        LsDeidentProcLog log = newLog();
        log.markKpstSubmitted(101L, 202L);
        log.markDownloaded("/nas/deid/001-mask.mp4");

        // when
        log.recordReport(new KpstDeidentReportSummary(0L, 0L, 10L, null, null, null));

        // then
        assertThat(log.getProcSttsCd()).isEqualTo(LsDeidentProcLog.SUCCEEDED);
        assertThat(log.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_DOWNLOADED);
        assertThat(log.getDeIdntfFilePathNm()).isEqualTo("/nas/deid/001-mask.mp4");
    }
}
