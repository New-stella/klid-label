package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.batch.dto.BatchBulkRetryRequest;
import kr.co.cudo.authoring.batch.dto.BatchBulkRetryResponse;
import kr.co.cudo.authoring.batch.dto.BatchReprocessResponse;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 배치 일괄 재시작 서비스 단위 테스트. [@design API-199]
 *
 * <p>핵심 수용 기준은 <b>부분 성공</b>이다 — 한 건의 거부·예외가 다른 건의 재기동을 막지 않고,
 * 한 건도 성공하지 못해도 예외를 던지지 않으며(200), 실패 사유에 내부 정보가 실리지 않는다.
 * @design AC-050
 */
class BatchBulkRetryServiceTest {

    private VideoRepository videoRepository;
    private BatchReprocessService reprocessService;
    private BatchBulkRetryService service;

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        reprocessService = mock(BatchReprocessService.class);
        service = new BatchBulkRetryService(videoRepository, reprocessService);
        // 기본값 — 모든 rawSn 은 원본 영상(파생 아님).
        when(videoRepository.findById(anyLong())).thenAnswer(inv -> Optional.of(originVideo()));
    }

    private static LsDataRaw originVideo() {
        return LsDataRaw.createFromIngest("clip", "cctv", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/storage/raw/1.mp4", null, 30);
    }

    private static LsDataRaw derivativeVideo() throws Exception {
        LsDataRaw parent = originVideo();
        Field f = LsDataRaw.class.getDeclaredField("rawSn");
        f.setAccessible(true);
        f.set(parent, 100L);
        return LsDataRaw.createFromAugment(parent, "/storage/augment/winter.mp4", "WINTER", 7001L);
    }

    private static BatchReprocessResponse ok(long rawSn) {
        return new BatchReprocessResponse(rawSn, BatchStage.COMPLETED.name());
    }

    @Test
    @DisplayName("모두_성공하면_successCount가_요청건수와_같다")
    void allSucceed() {
        // given
        when(reprocessService.retry(anyLong())).thenAnswer(inv -> ok(inv.getArgument(0)));

        // when
        BatchBulkRetryResponse response = service.retryAll(new BatchBulkRetryRequest(List.of(1L, 2L, 3L)));

        // then
        assertThat(response.successCount()).isEqualTo(3);
        assertThat(response.failureCount()).isZero();
        assertThat(response.results()).extracting(BatchBulkRetryResponse.Item::success)
                .containsExactly(true, true, true);
        assertThat(response.results()).extracting(BatchBulkRetryResponse.Item::reason)
                .containsOnlyNulls();
    }

    @Test
    @DisplayName("★한_건이_거부돼도_나머지는_재기동된다_부분성공")
    void partialSuccess() {
        // given — 2번 영상만 FAILED 가 아니라 409 로 거부된다.
        when(reprocessService.retry(1L)).thenReturn(ok(1L));
        when(reprocessService.retry(2L)).thenThrow(
                new CustomException(ErrorCode.CONFLICT, "배치가 실패(FAILED)한 영상만 재처리할 수 있습니다."));
        when(reprocessService.retry(3L)).thenReturn(ok(3L));

        // when
        BatchBulkRetryResponse response = service.retryAll(new BatchBulkRetryRequest(List.of(1L, 2L, 3L)));

        // then — 1·3 은 실제로 재기동됐고, 2 만 사유와 함께 실패로 기록된다.
        verify(reprocessService).retry(1L);
        verify(reprocessService).retry(3L);
        assertThat(response.successCount()).isEqualTo(2);
        assertThat(response.failureCount()).isEqualTo(1);
        assertThat(response.results()).anySatisfy(item -> {
            assertThat(item.rawSn()).isEqualTo(2L);
            assertThat(item.success()).isFalse();
            assertThat(item.reason()).contains("FAILED");
        });
    }

    @Test
    @DisplayName("★한_건도_성공하지_못해도_예외를_던지지_않는다")
    void allFailStillReturns() {
        // given
        when(reprocessService.retry(anyLong()))
                .thenThrow(new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));

        // when — 예외가 아니라 결과 목록으로 판정한다(HTTP 는 200).
        BatchBulkRetryResponse response = service.retryAll(new BatchBulkRetryRequest(List.of(1L, 2L)));

        // then
        assertThat(response.successCount()).isZero();
        assertThat(response.failureCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("★예상치_못한_예외도_그_건만_실패로_격리되고_내부정보를_노출하지_않는다")
    void unexpectedExceptionIsolated() {
        // given — 2번에서 내부 원문이 담긴 런타임 예외가 터진다.
        when(reprocessService.retry(1L)).thenReturn(ok(1L));
        when(reprocessService.retry(2L)).thenThrow(new IllegalStateException(
                "could not execute statement [ERROR: duplicate key value violates unique constraint \"uk_ls_data_src_raw_frame\"]"));
        when(reprocessService.retry(3L)).thenReturn(ok(3L));

        // when
        BatchBulkRetryResponse response = service.retryAll(new BatchBulkRetryRequest(List.of(1L, 2L, 3L)));

        // then — 다른 건은 정상 처리되고, 사유에는 DB 제약명·SQL 이 실리지 않는다(CWE-209).
        assertThat(response.successCount()).isEqualTo(2);
        assertThat(response.results()).anySatisfy(item -> {
            assertThat(item.rawSn()).isEqualTo(2L);
            assertThat(item.reason())
                    .isEqualTo(BatchBulkRetryService.UNEXPECTED_FAILURE_REASON)
                    .doesNotContain("constraint")
                    .doesNotContain("uk_");
        });
    }

    @Test
    @DisplayName("중복된_rawSn은_1건으로_취급해_한_번만_재기동한다")
    void duplicatesCollapse() {
        // given
        when(reprocessService.retry(anyLong())).thenAnswer(inv -> ok(inv.getArgument(0)));

        // when
        BatchBulkRetryResponse response =
                service.retryAll(new BatchBulkRetryRequest(List.of(5L, 5L, 5L, 6L)));

        // then — 두 번째 5L 은 자기 자신의 클레임에 막혀 "실패"로 보고될 뿐 사용자에게 뜻이 없다.
        verify(reprocessService, times(1)).retry(5L);
        assertThat(response.results()).extracting(BatchBulkRetryResponse.Item::rawSn)
                .containsExactly(5L, 6L);
    }

    @Test
    @DisplayName("파생영상은_재기동_대상이_아니며_그_건만_사유와_함께_실패한다")
    void derivativeRejectedPerItem() throws Exception {
        // given — 파생은 배치 파이프라인을 타지 않아 재기동으로 복구되지 않는다.
        when(videoRepository.findById(1L)).thenReturn(Optional.of(originVideo()));
        when(videoRepository.findById(2L)).thenReturn(Optional.of(derivativeVideo()));
        when(reprocessService.retry(1L)).thenReturn(ok(1L));

        // when
        BatchBulkRetryResponse response = service.retryAll(new BatchBulkRetryRequest(List.of(1L, 2L)));

        // then — 파생은 재기동 경로에 들어가지도 않는다.
        verify(reprocessService, times(0)).retry(2L);
        assertThat(response.successCount()).isEqualTo(1);
        assertThat(response.results()).anySatisfy(item -> {
            assertThat(item.rawSn()).isEqualTo(2L);
            assertThat(item.reason()).isEqualTo(BatchBulkRetryService.DERIVATIVE_REASON);
        });
    }

    @Test
    @DisplayName("실질_대상이_0건이면_400이다")
    void emptyTargetsRejected() {
        // given — null 원소만 담긴 목록은 @NotEmpty 를 통과하지만 대상이 없다.
        List<Long> nullsOnly = new java.util.ArrayList<>();
        nullsOnly.add(null);

        // when / then
        assertThatThrownBy(() -> service.retryAll(new BatchBulkRetryRequest(nullsOnly)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("★접수_용량을_넘긴_건은_사유와_함께_건별_실패로_돌아온다_내부_풀_정보는_실리지_않는다")
    void dispatchRejectionSurfacesAsPerItemReason() {
        // 재기동 큐를 실행 능력에 맞춰 좁혔으므로(선점해 놓고 대기하는 폭 = 고착 위험 폭), 일괄 100건을
        //   보내면 감당하지 못하는 분량은 접수 단계에서 거부된다. 그 거부가 조용히 삼켜지면 사용자는
        //   접수됐다고 믿는데 아무것도 돌지 않는다 — 반드시 건별 사유로 도달해야 한다.
        when(reprocessService.retry(1L)).thenReturn(ok(1L));
        when(reprocessService.retry(2L)).thenThrow(new CustomException(
                ErrorCode.SERVICE_UNAVAILABLE, BatchReprocessService.DISPATCH_REJECTED_REASON));

        BatchBulkRetryResponse response = service.retryAll(new BatchBulkRetryRequest(List.of(1L, 2L)));

        assertThat(response.successCount()).isEqualTo(1);
        assertThat(response.results()).anySatisfy(item -> {
            assertThat(item.rawSn()).isEqualTo(2L);
            assertThat(item.success()).isFalse();
            // "지금은 밀려서 못 받았다, 잠시 후 다시" 라는 뜻이 사용자에게 전달돼야 한다.
            assertThat(item.reason()).isEqualTo(BatchReprocessService.DISPATCH_REJECTED_REASON);
            // 내부 실행기·풀 구성은 드러내지 않는다(CWE-209).
            assertThat(item.reason()).doesNotContain("Executor", "ThreadPool", "queue");
        });
    }

    @Test
    @DisplayName("각_건은_단건_재기동과_같은_경로를_그대로_탄다")
    void reusesSingleRetryPath() {
        // given — 상태 판정·원자 클레임을 여기서 재구현하지 않는다는 계약(CWE-362).
        when(reprocessService.retry(anyLong())).thenAnswer(inv -> ok(inv.getArgument(0)));

        // when
        service.retryAll(new BatchBulkRetryRequest(List.of(9L)));

        // then
        verify(reprocessService).retry(9L);
    }

    // ── 선두 비식별 실패 영상 혼합 [@design API-199] [@design AC-1133] [@design AC-1134] ──

    /**
     * 실제 건별 서비스·실제 판정 서비스로 일괄을 돌린다 — 일괄에 별도 분기가 없고 건별 판정을 그대로
     * 탄다는 것을 결과로 확인한다.
     */
    @Test
    @DisplayName("★일괄에_선두비식별_실패·배치실패·미실패·신고표식_영상이_섞여도_건별과_같은_결과")
    void 혼합일괄() {
        DeidentRetryLockFixture locks = new DeidentRetryLockFixture();
        VideoRepository videos = mock(VideoRepository.class);
        LsDataRaw deidFailed = rawOf(31L, "F", LsDataRaw.STATUS_PENDING);
        LsDataRaw batchFailed = rawOf(32L, "Y", LsDataRaw.DATA_STTS_FAILED);
        LsDataRaw healthy = rawOf(33L, "Y", LsDataRaw.DATA_STTS_COMPLETED);
        LsDataRaw reported = rawOf(34L, "F", LsDataRaw.STATUS_PENDING);
        for (LsDataRaw r : List.of(deidFailed, batchFailed, healthy, reported)) {
            when(videos.findById(r.getRawSn())).thenReturn(Optional.of(r));
            when(videos.existsById(r.getRawSn())).thenReturn(true);
        }
        kr.co.cudo.authoring.label.repository.LsDeidentReportRepository reports =
                mock(kr.co.cudo.authoring.label.repository.LsDeidentReportRepository.class);
        when(reports.findAllByDataRawSnAndReportSttsCd(34L,
                kr.co.cudo.authoring.label.entity.LsDeidentReport.REPORT_OPEN))
                .thenReturn(List.of(mock(kr.co.cudo.authoring.label.entity.LsDeidentReport.class)));
        LeadDeidentRetryService lead = new LeadDeidentRetryService(videos,
                mock(kr.co.cudo.authoring.assignment.service.ReviewApprovalGate.class), reports,
                mock(kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository.class),
                locks.workLockService);
        kr.co.cudo.authoring.batch.status.BatchTransitionService transition =
                mock(kr.co.cudo.authoring.batch.status.BatchTransitionService.class);
        when(transition.tryClaimReprocessFromFailed(32L)).thenReturn(true);
        when(transition.tryClaimReprocessFromFailed(33L)).thenReturn(false);
        kr.co.cudo.authoring.batch.runner.AsyncBatchReprocessRunner batchRunner =
                mock(kr.co.cudo.authoring.batch.runner.AsyncBatchReprocessRunner.class);
        BatchReprocessService single = new BatchReprocessService(videos, transition,
                mock(kr.co.cudo.authoring.batch.status.BatchStatusService.class), batchRunner,
                mock(kr.co.cudo.authoring.batch.retry.BatchRetryQueue.class), lead);
        BatchBulkRetryService bulk = new BatchBulkRetryService(videos, single);

        BatchBulkRetryResponse res = bulk.retryAll(new BatchBulkRetryRequest(List.of(31L, 32L, 33L, 34L)));

        assertThat(res.successCount()).isEqualTo(2);
        assertThat(res.results()).extracting(BatchBulkRetryResponse.Item::rawSn)
                .containsExactly(31L, 32L, 33L, 34L);
        assertThat(res.results()).extracting(BatchBulkRetryResponse.Item::success)
                .containsExactly(true, true, false, false);
        assertThat(res.results().get(2).reason()).isEqualTo(BatchReprocessService.NOT_CLAIMABLE_REASON);
        assertThat(res.results().get(3).reason()).isEqualTo(LeadDeidentRetryService.OPEN_REPORT_REASON);
        // 선두 비식별 재수행은 31 한 건만, 마킹 이후 재기동은 32 한 건만.
        verify(batchRunner, times(1)).runLeadDeidentRetryAsync(anyLong());
        verify(batchRunner).runLeadDeidentRetryAsync(31L);
        verify(batchRunner, times(1)).runAsync(anyLong(), org.mockito.ArgumentMatchers.anyString());
        verify(batchRunner).runAsync(32L, LsDataRaw.DATA_STTS_FAILED);
        // 선두 비식별 재시작 잠금은 31 에만, 신고 표식 영상에는 남지 않는다.
        assertThat(locks.activeRetryLocks(31L)).isEqualTo(1);
        assertThat(locks.activeOf(34L)).isEmpty();
        verify(transition, org.mockito.Mockito.never()).tryClaimReprocessFromFailed(31L);
        verify(transition, org.mockito.Mockito.never()).tryClaimReprocessFromFailed(34L);
    }

    @Test
    @DisplayName("★일괄에서_파생_선두비식별_실패영상은_기존_파생거부_사유가_먼저_걸린다")
    void 파생은_기존사유() {
        VideoRepository videos = mock(VideoRepository.class);
        LsDataRaw derived = mock(LsDataRaw.class);
        when(derived.isDerivative()).thenReturn(true);
        when(videos.findById(41L)).thenReturn(Optional.of(derived));
        BatchReprocessService single = mock(BatchReprocessService.class);

        BatchBulkRetryResponse res = new BatchBulkRetryService(videos, single)
                .retryAll(new BatchBulkRetryRequest(List.of(41L)));

        assertThat(res.successCount()).isZero();
        assertThat(res.results().get(0).reason()).isEqualTo(BatchBulkRetryService.DERIVATIVE_REASON);
        verify(single, org.mockito.Mockito.never()).retry(anyLong());
    }

    private static LsDataRaw rawOf(long rawSn, String deIdntfYn, String dataSttsCd) {
        LsDataRaw raw = LsDataRaw.createFromIngest("clip-" + rawSn, "cctv", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/raw/" + rawSn + ".mp4", null, 60);
        try {
            Field sn = LsDataRaw.class.getDeclaredField("rawSn");
            sn.setAccessible(true);
            sn.set(raw, rawSn);
            Field st = LsDataRaw.class.getDeclaredField("dataSttsCd");
            st.setAccessible(true);
            st.set(raw, dataSttsCd);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        raw.markDeidentified(deIdntfYn);
        return raw;
    }
}
