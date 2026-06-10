package kr.co.cudo.authoring.batch.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 — KPST 폴링 연동 컬럼/상태 전이 단위 테스트.
 * 기존 콜백 경로(request/succeed/fail)는 무영향이어야 한다.
 */
class LsDeidentProcLogKpstPollingTest {

    private LsDeidentProcLog newRequested() {
        return LsDeidentProcLog.request(10L, "req-1", "/raw/a.mp4", "system");
    }

    @Test
    @DisplayName("위탁시_KPST_prjId와_datasetId가_기록되고_POLL_STTS_WAITING이다")
    void markKpstSubmittedRecordsIdsAndWaiting() {
        LsDeidentProcLog log = newRequested();

        log.markKpstSubmitted(101L, 202L);

        assertThat(log.getKpstPrjId()).isEqualTo(101L);
        assertThat(log.getKpstDatasetId()).isEqualTo(202L);
        assertThat(log.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_WAITING);
        // 위탁 직후 PROC_STTS_CD 는 REQUESTED 유지
        assertThat(log.getProcSttsCd()).isEqualTo(LsDeidentProcLog.REQUESTED);
    }

    @Test
    @DisplayName("폴링시_POLL_STTS_POLLING과_시도횟수가_증가한다")
    void markPollingIncrementsAttempt() {
        LsDeidentProcLog log = newRequested();
        log.markKpstSubmitted(101L, 202L);

        log.markPolling();
        assertThat(log.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_POLLING);
        assertThat(log.getPollAttemptCnt()).isEqualTo(1);
        assertThat(log.getPollLastDt()).isNotNull();

        log.markPolling();
        assertThat(log.getPollAttemptCnt()).isEqualTo(2);
    }

    @Test
    @DisplayName("다운로드완료시_POLL_STTS_DOWNLOADED_PROC_STTS_SUCCEEDED_비식별경로가_기록된다")
    void markDownloadedSetsSucceeded() {
        LsDeidentProcLog log = newRequested();
        log.markKpstSubmitted(101L, 202L);
        log.markPolling();

        log.markDownloaded("/deid/a.mp4");

        assertThat(log.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_DOWNLOADED);
        assertThat(log.getProcSttsCd()).isEqualTo(LsDeidentProcLog.SUCCEEDED);
        assertThat(log.getDeIdntfFilePathNm()).isEqualTo("/deid/a.mp4");
        assertThat(log.getResDt()).isNotNull();
    }

    @Test
    @DisplayName("상태전이_WAITING_POLLING_DOWNLOADED가_순서대로_진행된다")
    void stateTransitionInOrder() {
        LsDeidentProcLog log = newRequested();

        log.markKpstSubmitted(1L, 2L);
        assertThat(log.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_WAITING);

        log.markPolling();
        assertThat(log.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_POLLING);

        log.markDownloaded("/deid/x.mp4");
        assertThat(log.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_DOWNLOADED);
        assertThat(log.getProcSttsCd()).isEqualTo(LsDeidentProcLog.SUCCEEDED);
    }

    @Test
    @DisplayName("위탁시_datasetId가_미상이면_null로_두고_WAITING이다")
    void markKpstSubmittedAllowsNullDatasetId() {
        LsDeidentProcLog log = newRequested();

        // 규격: /project 응답엔 prj_id 만 — datasetId 는 첫 retrieve_progress 에서 보충.
        log.markKpstSubmitted(101L, null);

        assertThat(log.getKpstPrjId()).isEqualTo(101L);
        assertThat(log.getKpstDatasetId()).isNull();
        assertThat(log.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_WAITING);
    }

    @Test
    @DisplayName("첫_폴링에서_datasetId를_보충_기록한다")
    void recordDatasetIdBackfills() {
        LsDeidentProcLog log = newRequested();
        log.markKpstSubmitted(101L, null);

        log.recordDatasetId(202L);
        assertThat(log.getKpstDatasetId()).isEqualTo(202L);

        // 이미 채워졌으면 덮어쓰지 않음 (idempotent)
        log.recordDatasetId(999L);
        assertThat(log.getKpstDatasetId()).isEqualTo(202L);
    }

    @Test
    @DisplayName("markDownloaded_deidPath가_null이면_거부한다")
    void markDownloadedRejectsNullPath() {
        LsDeidentProcLog log = newRequested();
        log.markKpstSubmitted(101L, 202L);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> log.markDownloaded(null));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> log.markDownloaded("  "));
    }

    @Test
    @DisplayName("기존_request_succeed_콜백경로는_POLL_STTS_null로_무영향이다")
    void legacyCallbackPathUnaffected() {
        LsDeidentProcLog log = newRequested();
        assertThat(log.getPollSttsCd()).isNull();

        log.succeed("/deid/legacy.mp4");
        assertThat(log.getProcSttsCd()).isEqualTo(LsDeidentProcLog.SUCCEEDED);
        assertThat(log.getPollSttsCd()).isNull();
    }

    @Test
    @DisplayName("폴링중_fail시_POLL_STTS가_POLL_FAILED_종료값으로_전이되어_재폴링대상에서_제외된다")
    void failTransitionsPollStatusToTerminal() {
        LsDeidentProcLog log = newRequested();
        log.markKpstSubmitted(101L, 202L);
        log.markPolling();
        assertThat(log.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_POLLING);

        log.fail("DEIDENT_TIMEOUT", "polling timeout");

        assertThat(log.getProcSttsCd()).isEqualTo(LsDeidentProcLog.FAILED);
        // POLL_STTS_CD 가 종료값으로 전이 — findByPollSttsCdIn([WAITING,POLLING]) 미포함.
        assertThat(log.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_FAILED);
    }

    @Test
    @DisplayName("콜백경로_fail은_POLL_STTS가_null로_유지된다")
    void failOnCallbackPathKeepsPollStatusNull() {
        LsDeidentProcLog log = newRequested();
        assertThat(log.getPollSttsCd()).isNull();

        log.fail("EXTERNAL_API_ERROR", "boom");

        assertThat(log.getProcSttsCd()).isEqualTo(LsDeidentProcLog.FAILED);
        // 폴링 경로가 아니면(POLL_STTS null) 그대로 null — 콜백 경로 무영향.
        assertThat(log.getPollSttsCd()).isNull();
    }
}
