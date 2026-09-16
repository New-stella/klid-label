package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.step.DeidentFrameAttacher;
import kr.co.cudo.authoring.common.cache.StreamMetaCacheEvictor;
import kr.co.cudo.authoring.common.config.CacheConfig;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.support.TestVideoFixtures;
import kr.co.cudo.authoring.label.service.DeidentReportService;
import kr.co.cudo.authoring.marking.service.MarkingActivationTxService;
import kr.co.cudo.authoring.notification.NotificationService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoStreamService;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 / UC018 — KpstDeidentTxService 상태 전이 단위 테스트.
 *
 * <p>완료 공유 로직(Y/MARKING_READY/락해제/신고해소/알림) · 타임아웃('F') · 다운로드 완료 · 폴링 진행 전이.
 */
class KpstDeidentTxServiceTest {

    @TempDir
    Path tmp;

    private VideoRepository videoRepository;
    private LsDeidentProcLogRepository procLogRepository;
    private DeidentReportService deidentReportService;
    private NotificationService notificationService;
    private WorkLockService workLockService;
    private DeidentFrameAttacher deidentFrameAttacher;
    private StreamMetaCacheEvictor streamMetaCacheEvictor;
    private LsRawDataStatusRepository rawDataStatusRepository;
    private DeidentApprovalHoldReleaser deidentApprovalHoldReleaser;
    /** ADR-052 — 예약 마킹 활성화·마감의 실제 전이 주체(마킹 도메인 소유). 훅은 실물, 이쪽만 mock 이다. */
    private MarkingActivationTxService markingActivationTxService;
    private DeidentReservationHook reservationHook;
    /** ADR-072 — 재생 인덱스 재배치. 이 시험은 「언제 넘기는가」만 보므로 mock 이다. */
    private DeidentFaststartService faststartService;
    private KpstDeidentTxService tx;

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        procLogRepository = mock(LsDeidentProcLogRepository.class);
        deidentReportService = mock(DeidentReportService.class);
        notificationService = mock(NotificationService.class);
        workLockService = mock(WorkLockService.class);
        deidentFrameAttacher = mock(DeidentFrameAttacher.class);
        streamMetaCacheEvictor = mock(StreamMetaCacheEvictor.class);
        rawDataStatusRepository = mock(LsRawDataStatusRepository.class);
        // 해제기는 mock 이 아니라 실물이다 — 기존 완료 경로 테스트가 "보류 해제가 기존 영상에 아무
        //   부작용을 만들지 않는다"를 그대로 통과해야 회귀 가드가 성립한다.
        deidentApprovalHoldReleaser = new DeidentApprovalHoldReleaser(rawDataStatusRepository);
        // ADR-052 — 훅은 실물을 쓴다. mock 으로 바꾸면 "커밋 이후에만 활성화한다"는 이 배선의 핵심이
        //   테스트에서 사라진다(훅 내부의 afterCommit 지연이 검증 대상이다).
        markingActivationTxService = mock(MarkingActivationTxService.class);
        when(markingActivationTxService.activateReserved(anyLong())).thenReturn(Optional.empty());
        reservationHook = new DeidentReservationHook(markingActivationTxService);
        faststartService = mock(DeidentFaststartService.class);
        tx = new KpstDeidentTxService(videoRepository, procLogRepository,
                deidentReportService, notificationService, workLockService, deidentFrameAttacher,
                streamMetaCacheEvictor, deidentApprovalHoldReleaser, reservationHook, faststartService);
        // B-ISSUE-82 — 완료 처리는 조건부 UPDATE 클레임(1행)을 얻은 호출만 진행한다. 단위 테스트의
        // 기본은 "이 호출이 선점에 성공" 이며, 중복 완료(0행) 시나리오는 개별 테스트가 재정의한다.
        when(procLogRepository.claimDownloadCompletion(anyLong(), anyString(), any(LocalDateTime.class)))
                .thenReturn(1);
    }

    private LsDataRaw newRaw() {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-x", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/raw/clip.mp4", null, 60);
        setField(raw, "rawSn", 9001L);
        return raw;
    }

    private LsDeidentProcLog submitted() {
        LsDeidentProcLog p = LsDeidentProcLog.request(9001L, null, "/raw/clip.mp4", "batch");
        setField(p, "procLogSn", 1L);
        p.markKpstSubmitted(101L, null);
        return p;
    }

    /**
     * 실재하는 비식별 파일 — completeDeidentification 의 무결성 검증(정규파일 + 크기 하한 +
     * 컨테이너 시그니처) 통과용. 텍스트 스텁은 더 이상 산출물로 인정되지 않으므로 실제 최소 mp4 를 쓴다.
     */
    private String realDeidFile() {
        return TestVideoFixtures.writeTinyMp4(tmp.resolve("deid-9001.mp4")).toString();
    }

    @Test
    @DisplayName("완료처리시_Y전이_MARKING_READY_락해제_신고해소_알림이_수행된다")
    void completeTransitionsAll() {
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        when(workLockService.isRawLocked(9001L)).thenReturn(true);

        tx.completeDeidentification(9001L, realDeidFile());

        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
        assertThat(raw.getDataSttsCd()).isEqualTo("MARKING_READY");
        verify(workLockService).releaseRaw(eq(9001L), eq("batch"), eq("DEIDENT_SUCCEEDED"));
        verify(deidentReportService).resolveOpenReports(9001L);
        verify(notificationService).notifyReviewersOnLockRelease(raw);
    }

    @Test
    @DisplayName("비식별완료시_OPEN신고가_RESOLVED되고_알림이_발송된다_잠금없으면_release미호출")
    void completeResolvesReportsNoLock() {
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        when(workLockService.isRawLocked(9001L)).thenReturn(false);

        tx.completeDeidentification(9001L, realDeidFile());

        verify(deidentReportService).resolveOpenReports(9001L);
        verify(notificationService).notifyReviewersOnLockRelease(raw);
        verify(workLockService, never()).releaseRaw(any(), any(), any());
    }

    @Test
    @DisplayName("완료처리시_비식별파일이_존재하지_않으면_F로_처리하고_Y전이하지_않는다")
    void completeRejectsMissingFile() {
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        String missing = tmp.resolve("nope.mp4").toString();

        assertThatThrownBy(() -> tx.completeDeidentification(9001L, missing))
                .isInstanceOf(CustomException.class);

        assertThat(raw.getDeIdntfYn()).isEqualTo("F");
        verify(workLockService, never()).releaseRaw(any(), any(), any());
        verify(notificationService, never()).notifyReviewersOnLockRelease(any());
    }

    @Test
    @DisplayName("완료처리시_비식별파일이_0바이트면_F로_처리하고_Y전이하지_않는다")
    void completeRejectsZeroByteFile() throws Exception {
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        Path empty = tmp.resolve("empty.mp4");
        Files.createFile(empty);

        assertThatThrownBy(() -> tx.completeDeidentification(9001L, empty.toString()))
                .isInstanceOf(CustomException.class);

        assertThat(raw.getDeIdntfYn()).isEqualTo("F");
        verify(notificationService, never()).notifyReviewersOnLockRelease(any());
    }

    @Test
    @DisplayName("다운로드완료와_Y전이가_단일트랜잭션으로_원자적으로_수행된다")
    void finishDownloadAndCompleteIsAtomic() {
        LsDeidentProcLog p = submitted();
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        String deid = realDeidFile();

        tx.finishDownloadAndComplete(9001L, 1L, 202L, deid);

        // procLog: DOWNLOADED/SUCCEEDED/datasetId + raw: Y/MARKING_READY 가 한 호출로 모두 반영.
        assertThat(p.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_DOWNLOADED);
        assertThat(p.getProcSttsCd()).isEqualTo(LsDeidentProcLog.SUCCEEDED);
        assertThat(p.getKpstDatasetId()).isEqualTo(202L);
        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
        assertThat(raw.getDataSttsCd()).isEqualTo("MARKING_READY");
    }

    @Test
    @DisplayName("배치_비식별완료시_stream-meta_캐시가_실제로_무효화되어_재조회시_새_경로가_반환된다")
    void batchCompletionInvalidatesStreamMetaCache() {
        // given — mock 이 아닌 <b>실제 Caffeine 캐시</b> + 실제 evictor 로 구성해 캐시 상태를 직접 관측한다.
        CacheManager realCacheManager = realCacheManager();
        Cache cache = realCacheManager.getCache(CacheConfig.CACHE_STREAM_META);
        KpstDeidentTxService realTx = new KpstDeidentTxService(videoRepository, procLogRepository,
                deidentReportService, notificationService, workLockService, deidentFrameAttacher,
                new StreamMetaCacheEvictor(realCacheManager), deidentApprovalHoldReleaser, reservationHook, faststartService);

        LsDeidentProcLog p = submitted();
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        String oldPath = "/nas/deid/videos/9001/old-deidentified.mp4";
        cache.put(9001L, new VideoStreamService.StreamMeta(
                Paths.get(oldPath), Paths.get(oldPath).getParent(), 1_000L,
                MediaType.parseMediaType("video/mp4")));

        // when — 재구동/재위탁으로 새 비식별 산출물 경로가 커밋된다.
        String newPath = realDeidFile();
        realTx.finishDownloadAndComplete(9001L, 1L, 202L, newPath);

        // then — 캐시 엔트리 제거 + 재조회 시 procLog 의 새 경로가 적재된다(무효화 없으면 구 경로 히트).
        assertThat(cache.get(9001L)).isNull();
        VideoStreamService.StreamMeta reloaded = cache.get(9001L,
                () -> new VideoStreamService.StreamMeta(Paths.get(p.getDeIdntfFilePathNm()),
                        Paths.get(p.getDeIdntfFilePathNm()).getParent(), 2_000L,
                        MediaType.parseMediaType("video/mp4")));
        assertThat(reloaded.path().toString()).isEqualTo(newPath);
    }

    @Test
    @DisplayName("활성_트랜잭션에서는_커밋_전에_evict되지_않고_커밋_콜백에서만_무효화된다")
    void evictHappensOnlyAfterCommitWhenTransactionActive() {
        // 지금까지의 단위 테스트는 트랜잭션 없이 호출돼 evictAfterCommit 의 <b>즉시 evict 폴백</b>만 탔다.
        // 그래서 evictAfterCommit → evict 로 바꿔도 GREEN 이었다(커밋 전 evict 회귀에 둔감).
        // 여기서는 트랜잭션 동기화를 실제로 열어 <b>순서</b>를 단언한다.
        CacheManager realCacheManager = realCacheManager();
        Cache cache = realCacheManager.getCache(CacheConfig.CACHE_STREAM_META);
        KpstDeidentTxService realTx = new KpstDeidentTxService(videoRepository, procLogRepository,
                deidentReportService, notificationService, workLockService, deidentFrameAttacher,
                new StreamMetaCacheEvictor(realCacheManager), deidentApprovalHoldReleaser, reservationHook, faststartService);
        LsDeidentProcLog p = redeidentSubmitted();
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        cache.put(9001L, new VideoStreamService.StreamMeta(
                Paths.get("/nas/deid/videos/9001/old.mp4"), Paths.get("/nas/deid"), 1_000L,
                MediaType.parseMediaType("video/mp4")));

        TransactionSynchronizationManager.initSynchronization();
        try {
            realTx.finishDownloadAndComplete(9001L, 1L, 202L, realDeidFile());

            // 아직 커밋 전 — 캐시는 그대로여야 한다(즉시 evict 로 바꾸면 여기서 실패한다).
            assertThat(cache.get(9001L)).isNotNull();

            // 커밋 콜백 발화 — 이때 비로소 무효화된다.
            TransactionSynchronizationUtils.triggerAfterCommit();
            assertThat(cache.get(9001L)).isNull();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("롤백되면_stream-meta_캐시를_비우지_않는다_afterCompletion만_발화")
    void evictSkippedOnRollback() {
        CacheManager realCacheManager = realCacheManager();
        Cache cache = realCacheManager.getCache(CacheConfig.CACHE_STREAM_META);
        KpstDeidentTxService realTx = new KpstDeidentTxService(videoRepository, procLogRepository,
                deidentReportService, notificationService, workLockService, deidentFrameAttacher,
                new StreamMetaCacheEvictor(realCacheManager), deidentApprovalHoldReleaser, reservationHook, faststartService);
        LsDeidentProcLog p = redeidentSubmitted();
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        VideoStreamService.StreamMeta cached = new VideoStreamService.StreamMeta(
                Paths.get("/nas/deid/videos/9001/old.mp4"), Paths.get("/nas/deid"), 1_000L,
                MediaType.parseMediaType("video/mp4"));
        cache.put(9001L, cached);

        TransactionSynchronizationManager.initSynchronization();
        try {
            realTx.finishDownloadAndComplete(9001L, 1L, 202L, realDeidFile());
            // 롤백 — afterCommit 은 발화하지 않는다.
            TransactionSynchronizationUtils.triggerAfterCompletion(
                    TransactionSynchronization.STATUS_ROLLED_BACK);

            assertThat(cache.get(9001L)).isNotNull();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("배치완료_evict는_현재_도달가능한_흐름에서_no-op이다_캐시미적재_부작용없음")
    void batchCompletionEvictIsNoOpWhenNothingCached() {
        // [도달성 정직성] 배치 완료 경로의 evict 는 방어적(defense-in-depth)으로 남긴 호출이다.
        // stream-meta 는 비식별 완료 후에만 적재되고, 'Y' 인 영상의 배치 재위탁 경로가 없다
        // (재비식별은 ApprovedRedeidentService 가 409 로 거부 —
        //  ApprovedRedeidentServiceTest.이미_비식별된_deIdentY_영상_요청시_CONFLICT_네이티브배제).
        // 따라서 최초 완료 시점에는 캐시 엔트리가 없고 evict 는 관측 가능한 부작용이 없어야 한다.
        CacheManager realCacheManager = realCacheManager();
        Cache cache = realCacheManager.getCache(CacheConfig.CACHE_STREAM_META);
        KpstDeidentTxService realTx = new KpstDeidentTxService(videoRepository, procLogRepository,
                deidentReportService, notificationService, workLockService, deidentFrameAttacher,
                new StreamMetaCacheEvictor(realCacheManager), deidentApprovalHoldReleaser, reservationHook, faststartService);
        LsDeidentProcLog p = submitted(); // REQ_KIND null = 배치 경로
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        // 다른 rawSn 의 캐시는 영향을 받지 않아야 한다.
        cache.put(9002L, new VideoStreamService.StreamMeta(
                Paths.get("/nas/deid/videos/9002/x.mp4"), Paths.get("/nas/deid"), 10L,
                MediaType.parseMediaType("video/mp4")));

        realTx.finishDownloadAndComplete(9001L, 1L, 202L, realDeidFile());

        assertThat(raw.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
        assertThat(cache.get(9001L)).isNull();   // 애초에 없었다(evict 는 no-op)
        assertThat(cache.get(9002L)).isNotNull(); // 무관한 키는 건드리지 않는다
    }

    @Test
    @DisplayName("타임아웃_F마킹시_POLL_STTS가_POLL_FAILED_종료값으로_전이되어_재폴링대상에서_제외된다")
    void timeoutTransitionsPollStatusToTerminal() {
        LsDeidentProcLog p = submitted();
        setField(p, "pollAttemptCnt", 3);
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        tx.markTimeoutIfExpired(1L, 3, 60L);

        assertThat(p.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_FAILED);
    }

    @Test
    @DisplayName("폴링진행_위임시_POLLING_시도증가_datasetId보충")
    void recordPollingProgress() {
        LsDeidentProcLog p = submitted();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));

        tx.recordPollingProgress(1L, 202L);

        assertThat(p.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_POLLING);
        assertThat(p.getPollAttemptCnt()).isEqualTo(1);
        assertThat(p.getKpstDatasetId()).isEqualTo(202L);
    }

    @Test
    @DisplayName("폴링_시도횟수_초과시_F로_마킹하고_true반환")
    void timeoutByAttempts() {
        LsDeidentProcLog p = submitted();
        setField(p, "pollAttemptCnt", 3);
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        boolean timedOut = tx.markTimeoutIfExpired(1L, 3, 60L);

        assertThat(timedOut).isTrue();
        assertThat(p.getProcSttsCd()).isEqualTo(LsDeidentProcLog.FAILED);
        assertThat(raw.getDeIdntfYn()).isEqualTo("F");
    }

    @Test
    @DisplayName("위탁후_경과시간_초과시_F로_마킹한다")
    void timeoutByElapsed() {
        LsDeidentProcLog p = submitted();
        setField(p, "reqDt", LocalDateTime.now().minusHours(2));
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        boolean timedOut = tx.markTimeoutIfExpired(1L, 999, 60L);

        assertThat(timedOut).isTrue();
        assertThat(p.getProcSttsCd()).isEqualTo(LsDeidentProcLog.FAILED);
        assertThat(raw.getDeIdntfYn()).isEqualTo("F");
    }

    @Test
    @DisplayName("markTimeoutIfExpired_REDEIDENT_타임아웃이면_락해제한다")
    void timeoutRedeidentReleasesLock() {
        // HIGH-2: REDEIDENT 건이 끝내 procState=2 미도달(타임아웃)이면 'F' 마킹만으로는 작업락이 영구 잔존.
        // 타임아웃 F 마킹 시 락 보유면 releaseRaw 도 수행하여 재요청 가능하게 한다(영구 잠금 방지).
        LsDeidentProcLog p = redeidentSubmitted();
        setField(p, "pollAttemptCnt", 3);
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        when(workLockService.isRawLocked(9001L)).thenReturn(true);

        boolean timedOut = tx.markTimeoutIfExpired(1L, 3, 60L);

        assertThat(timedOut).isTrue();
        assertThat(raw.getDeIdntfYn()).isEqualTo("F");
        assertThat(p.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_FAILED);
        verify(workLockService).releaseRaw(eq(9001L), eq("batch"), eq("REDEIDENT_TIMEOUT"));
    }

    @Test
    @DisplayName("markTimeoutIfExpired_REDEIDENT_타임아웃이고_락미보유면_release미호출하지만_F전이_terminal도달")
    void timeoutRedeidentNoLockStillTerminal() {
        // 락 미보유 케이스도 안전 — release 미호출하되 F + terminal 도달.
        LsDeidentProcLog p = redeidentSubmitted();
        setField(p, "pollAttemptCnt", 3);
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        when(workLockService.isRawLocked(9001L)).thenReturn(false);

        boolean timedOut = tx.markTimeoutIfExpired(1L, 3, 60L);

        assertThat(timedOut).isTrue();
        assertThat(raw.getDeIdntfYn()).isEqualTo("F");
        assertThat(p.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_FAILED);
        verify(workLockService, never()).releaseRaw(any(), any(), any());
    }

    @Test
    @DisplayName("markTimeoutIfExpired_비REDEIDENT_타임아웃은_락해제_안한다")
    void timeoutNonRedeidentDoesNotReleaseLock() {
        // 회귀(HIGH-2): 배치 경로(REQ_KIND null)는 락이 없으므로 isRawLocked 조회/releaseRaw 미수행.
        LsDeidentProcLog p = submitted(); // REQ_KIND null
        setField(p, "pollAttemptCnt", 3);
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        boolean timedOut = tx.markTimeoutIfExpired(1L, 3, 60L);

        assertThat(timedOut).isTrue();
        assertThat(raw.getDeIdntfYn()).isEqualTo("F");
        verify(workLockService, never()).releaseRaw(any(), any(), any());
    }

    @Test
    @DisplayName("타임아웃_미도달이면_F전이없이_false반환")
    void notTimedOut() {
        LsDeidentProcLog p = submitted();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));

        boolean timedOut = tx.markTimeoutIfExpired(1L, 999, 9999L);

        assertThat(timedOut).isFalse();
        assertThat(p.getProcSttsCd()).isEqualTo(LsDeidentProcLog.REQUESTED);
    }

    // ────────────────────────── REDEIDENT 분기 (Phase 3 — APPROVED 강등 금지) ──────────────────────────

    private LsDeidentProcLog redeidentSubmitted() {
        LsDeidentProcLog p = submitted();
        p.markRedeident();
        return p;
    }

    @Test
    @DisplayName("REDEIDENT완료_after_APPROVED상태_유지된다_markMarkingReady_미호출")
    void redeidentKeepsApproved() {
        LsDeidentProcLog p = redeidentSubmitted();
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        String deid = realDeidFile();

        tx.finishDownloadAndComplete(9001L, 1L, 202L, deid);

        // APPROVED 유지 — LS_DATA_RAW 배치 상태(DATA_STTS_CD)는 MARKING_READY 로 강등되지 않는다.
        assertThat(raw.getDataSttsCd()).isNotEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
        // 마킹 단계 재진입을 만드는 부수효과(신고 해소/마킹 알림) 미호출.
        verify(deidentReportService, never()).resolveOpenReports(any());
        verify(notificationService, never()).notifyReviewersOnLockRelease(any());
    }

    @Test
    @DisplayName("REDEIDENT완료_de_ident_yn_Y로_갱신된다")
    void redeidentMarksDeidentY() {
        LsDeidentProcLog p = redeidentSubmitted();
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        tx.finishDownloadAndComplete(9001L, 1L, 202L, realDeidFile());

        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
        // 재비식별 완료(비식별본 교체) 후 스트림 메타 캐시 무효화 훅 호출.
        verify(streamMetaCacheEvictor).evictAfterCommit(9001L);
    }

    @Test
    @DisplayName("REDEIDENT완료시_DeidentFrameAttacher가_호출된다")
    void redeidentCallsAttacher() {
        LsDeidentProcLog p = redeidentSubmitted();
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        tx.finishDownloadAndComplete(9001L, 1L, 202L, realDeidFile());

        verify(deidentFrameAttacher).attachDeidentFrames(eq(raw), any(), eq(true));
    }

    @Test
    @DisplayName("REDEIDENT완료시_prvc_UNKNOWN이_PRVC로_정정된다")
    void redeidentCorrectsUnknownPrvc() {
        LsDeidentProcLog p = redeidentSubmitted();
        LsDataRaw raw = newRaw();
        setField(raw, "prvcTypeCd", LsDataRaw.PRVC_TYPE_UNKNOWN);
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        tx.finishDownloadAndComplete(9001L, 1L, 202L, realDeidFile());

        assertThat(raw.getPrvcTypeCd()).isEqualTo(LsDataRaw.PRVC_TYPE_PRVC);
        assertThat(raw.getPrvcYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("REDEIDENT완료_attach가_0건이어도_예외없이_Y전이된다_WARN만_LOW방어")
    void redeidentZeroAttachStillCompletesWithWarn() {
        LsDeidentProcLog p = redeidentSubmitted();
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        // attach 처리 프레임 0건(frames 없는 영상 등) — 예외로 막지 않고 Y 전이는 진행(WARN 신호만).
        when(deidentFrameAttacher.attachDeidentFrames(eq(raw), any(), eq(true))).thenReturn(0);

        tx.finishDownloadAndComplete(9001L, 1L, 202L, realDeidFile());

        // 0건이어도 Y 전이 + 락 해제 흐름은 정상 진행(예외 미발생).
        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
        verify(deidentFrameAttacher).attachDeidentFrames(eq(raw), any(), eq(true));
    }

    @Test
    @DisplayName("REDEIDENT_해상도불일치_완료시_예외전파되고_Y전이_PRVC정정_락해제_미수행_롤백")
    void redeidentResolutionMismatchRollsBack() {
        LsDeidentProcLog p = redeidentSubmitted();
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        // Attacher 가 해상도 불일치로 예외 → 전파 → 메인 완료 트랜잭션 전체 롤백. 종결(락해제/FAILED)은
        // 폴링 오케스트레이터가 failRedeidentCompletion 으로 별도 커밋한다(아래 별도 테스트로 검증).
        when(deidentFrameAttacher.attachDeidentFrames(eq(raw), any(), eq(true)))
                .thenThrow(new CustomException(ErrorCode.INVALID_INPUT, "해상도 불일치"));

        assertThatThrownBy(() -> tx.finishDownloadAndComplete(9001L, 1L, 202L, realDeidFile()))
                .isInstanceOf(CustomException.class);

        // attach 가 먼저 실패 — Y 마킹/PRVC 정정 미수행. 메인 트랜잭션 내 락 해제(성공 경로)도 미수행.
        assertThat(raw.getDeIdntfYn()).isNotEqualTo("Y");
        verify(workLockService, never()).releaseRaw(eq(9001L), any(), eq("REDEIDENT_SUCCEEDED"));
    }

    @Test
    @DisplayName("REDEIDENT_attach실패시_failRedeidentCompletion으로_락해제되고_procLog_FAILED_재요청가능")
    void redeidentAttachFailureReleasesLockAndMarksFailed() {
        LsDeidentProcLog p = redeidentSubmitted(); // POLL_STTS=WAITING
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        when(workLockService.isRawLocked(9001L)).thenReturn(true);

        // 폴링 오케스트레이터가 메인 롤백 후 호출하는 종결 핸들러.
        tx.failRedeidentCompletion(1L, 9001L, "CustomException");

        // procLog: terminal(FAILED + POLL_FAILED) — 재폴링 대상에서 제외.
        assertThat(p.getProcSttsCd()).isEqualTo(LsDeidentProcLog.FAILED);
        assertThat(p.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_FAILED);
        // de_ident_yn=F (Y 미전이), 락 해제(재요청 가능).
        assertThat(raw.getDeIdntfYn()).isEqualTo("F");
        verify(workLockService).releaseRaw(eq(9001L), eq("batch"), eq("REDEIDENT_FAILED"));
    }

    @Test
    @DisplayName("REDEIDENT_attach실패_종결은_락없으면_release미호출하지만_procLog_FAILED_terminal도달")
    void redeidentAttachFailureNoLockStillTerminal() {
        LsDeidentProcLog p = redeidentSubmitted();
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        when(workLockService.isRawLocked(9001L)).thenReturn(false);

        tx.failRedeidentCompletion(1L, 9001L, "CustomException");

        // terminal 도달 보장(무한 재폴링 차단) — 락 없어도 POLL_FAILED.
        assertThat(p.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_FAILED);
        verify(workLockService, never()).releaseRaw(any(), any(), any());
    }

    @Test
    @DisplayName("M1_비식별파일무효시_markRawDeidentFailed가_F를_별도커밋한다")
    void markRawDeidentFailedCommitsF() {
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        tx.markRawDeidentFailed(9001L);

        assertThat(raw.getDeIdntfYn()).isEqualTo("F");
    }

    @Test
    @DisplayName("기존_BATCH완료는_여전히_markMarkingReady를_호출한다_회귀_REQ_KIND_null")
    void batchCompletionStillMarksMarkingReady() {
        LsDeidentProcLog p = submitted(); // REQ_KIND null = 기존 배치 경로
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        tx.finishDownloadAndComplete(9001L, 1L, 202L, realDeidFile());

        // 기존 배치 경로 — MARKING_READY 전이 + 신고해소 + 알림 유지(회귀 금지). Attacher 미호출.
        assertThat(raw.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
        verify(deidentReportService, times(1)).resolveOpenReports(9001L);
        verify(notificationService, times(1)).notifyReviewersOnLockRelease(raw);
        verify(deidentFrameAttacher, never()).attachDeidentFrames(any(), any(), anyBoolean());
        // [의도 변경] 배치 완료도 스트림 메타 캐시를 무효화한다(구 기대 "재비식별만 evict" 폐기).
        // 근거: 본 트랜잭션의 markDownloaded 가 비식별 영상 경로(stream-meta 해석 원천)를 새 값으로 쓴다.
        // "최초 완료라 교체가 아니다"는 전제는 재구동/재위탁(dev 재드라이브·재폴링)에서 성립하지 않아,
        // 이미 'Y'로 캐시된 rawSn 이 새 경로로 바뀌면 TTL 동안 옛 경로가 서빙된다(해상도 백필과 동일 결함).
        // 캐시 미스일 때 evict 는 no-op 이므로 최초 완료 경로에 부작용이 없다.
        verify(streamMetaCacheEvictor, times(1)).evictAfterCommit(9001L);
    }

    // ------------------------------------------------------------ 승인 보류 해제 (ADR-048)

    @Test
    @DisplayName("배치_비식별완료_트랜잭션이_이관원본의_검수승인_보류를_함께_푼다")
    void batchCompletionReleasesApprovalHold() {
        // given — 이관 원본이라 승인 보류가 서 있는 작업 상태 행.
        LsDataRaw raw = newRaw();
        LsRawDataStatus held = LsRawDataStatus.initial(9001L);
        held.markDeidentNotCompleted();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        when(rawDataStatusRepository.findById(9001L)).thenReturn(Optional.of(held));

        // when — 저작도구가 태운 비식별이 성공으로 기록된다.
        tx.finishDownloadAndComplete(9001L, 1L, 202L, realDeidFile());

        // then — 성공의 정의(Y + MARKING_READY)와 보류 해제가 같은 지점에서 함께 일어난다.
        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
        assertThat(raw.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
        assertThat(held.isDeidentCompleted()).isTrue();
    }

    @Test
    @DisplayName("산출물이_유효하지_않으면_보류를_풀지_않는다_거짓완료_차단")
    void invalidArtifactKeepsApprovalHold() {
        LsDataRaw raw = newRaw();
        LsRawDataStatus held = LsRawDataStatus.initial(9001L);
        held.markDeidentNotCompleted();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        when(rawDataStatusRepository.findById(9001L)).thenReturn(Optional.of(held));

        assertThatThrownBy(() -> tx.finishDownloadAndComplete(
                9001L, 1L, 202L, tmp.resolve("no-such-artifact.mp4").toString()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        assertThat(held.isDeidentCompleted()).isFalse();
    }

    @Test
    @DisplayName("재비식별_완료경로는_승인보류_축을_건드리지_않는다_검수완료_영상_전용")
    void redeidentCompletionDoesNotTouchApprovalHold() {
        // 재비식별은 검수완료(APPROVED) 영상 전용이라 승인 보류가 서 있을 수 없다(보류 중이면 승인
        //   자체가 412 로 막힌다). 그 경로가 이 축을 조회조차 하지 않는 것이 의도다.
        LsDeidentProcLog p = redeidentSubmitted();
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        tx.finishDownloadAndComplete(9001L, 1L, 202L, realDeidFile());

        verify(rawDataStatusRepository, never()).findById(anyLong());
    }

    // ────────── ADR-052 — 예약 마킹 활성화·마감 배선 (KPST 폴링 완료 경로) ──────────

    @Test
    @DisplayName("KPST_배치_비식별완료시_예약마킹이_활성화된다")
    void batchCompletionActivatesReservation() {
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        tx.completeDeidentification(9001L, realDeidFile());

        verify(markingActivationTxService).activateReserved(9001L);
    }

    @Test
    @DisplayName("KPST_배치_완료의_예약활성화는_커밋_전에는_일어나지_않는다 — 브리지가 skip 으로 종결시킨다")
    void batchCompletionActivatesOnlyAfterCommit() {
        // 단위 테스트는 트랜잭션 없이 호출돼 훅의 <b>즉시 실행 폴백</b>만 탄다. 그래서 훅을 그냥
        // activateReserved 직접 호출로 바꿔도 위 테스트는 GREEN 이다(커밋 전 활성화 회귀에 둔감).
        // 여기서는 동기화를 실제로 열어 <b>순서</b>를 단언한다.
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        String deid = realDeidFile();

        TransactionSynchronizationManager.initSynchronization();
        try {
            tx.completeDeidentification(9001L, deid);

            // 커밋 전 — 아직 활성화하면 안 된다. 이 시점의 DE_IDENT_YN/MARKING_READY 는 다른 트랜잭션에
            // 보이지 않아, MarkingBatchBridge 가 "비식별 미완료" 로 판정하고 방금 깨운 마킹을 종결시킨다.
            verify(markingActivationTxService, never()).activateReserved(anyLong());

            TransactionSynchronizationUtils.triggerAfterCommit();

            verify(markingActivationTxService).activateReserved(9001L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("REDEIDENT완료는_예약마킹을_활성화하지_않는다 — APPROVED 유지 경로(R1)")
    void redeidentCompletionDoesNotActivateReservation() {
        LsDeidentProcLog p = redeidentSubmitted();
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        tx.finishDownloadAndComplete(9001L, 1L, 202L, realDeidFile());

        verify(markingActivationTxService, never()).activateReserved(anyLong());
    }

    @Test
    @DisplayName("폴링_terminal실패시_예약마킹이_마감된다_적재는_유지")
    void failPollingClosesReservation() {
        LsDeidentProcLog p = submitted();
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        tx.failPolling(1L, 9001L);

        assertThat(raw.getDeIdntfYn()).isEqualTo("F");
        verify(markingActivationTxService).closeReservations(9001L, "DEIDENT_FAILED");
        // 적재 미롤백 — 영상 행을 지우지 않는다(AC-1033).
        verify(videoRepository, never()).delete(any());
    }

    @Test
    @DisplayName("폴링_타임아웃_확정시_예약마킹이_마감된다")
    void timeoutClosesReservation() {
        LsDeidentProcLog p = submitted();
        setField(p, "reqDt", LocalDateTime.now().minusMinutes(500));
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        boolean timedOut = tx.markTimeoutIfExpired(1L, 10, 180);

        assertThat(timedOut).isTrue();
        verify(markingActivationTxService).closeReservations(9001L, "DEIDENT_FAILED");
    }

    @Test
    @DisplayName("타임아웃이_아직_아니면_예약마킹을_마감하지_않는다")
    void notYetTimedOutKeepsReservation() {
        LsDeidentProcLog p = submitted();
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        boolean timedOut = tx.markTimeoutIfExpired(1L, 10, 180);

        assertThat(timedOut).isFalse();
        verify(markingActivationTxService, never()).closeReservations(anyLong(), anyString());
    }

    @Test
    @DisplayName("제출_확정실패시_예약마킹이_마감된다")
    void failSubmitClosesReservation() {
        LsDeidentProcLog p = LsDeidentProcLog.request(9001L, null, "/raw/clip.mp4", "batch");
        setField(p, "procLogSn", 1L);
        when(procLogRepository.claimSubmitFailure(eq(1L), anyString(), anyString(),
                any(LocalDateTime.class))).thenReturn(1);
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(newRaw()));

        boolean applied = tx.failSubmit(1L, 9001L, "ACK_MISSING", "submit ack not received");

        assertThat(applied).isTrue();
        verify(markingActivationTxService).closeReservations(9001L, "DEIDENT_FAILED");
    }

    @Test
    @DisplayName("이미_종결된_건의_지각_제출실패는_예약마킹을_마감하지_않는다")
    void lateSubmitFailureDoesNotCloseReservation() {
        when(procLogRepository.claimSubmitFailure(eq(1L), anyString(), anyString(),
                any(LocalDateTime.class))).thenReturn(0);

        boolean applied = tx.failSubmit(1L, 9001L, "ACK_MISSING", "late");

        assertThat(applied).isFalse();
        verify(markingActivationTxService, never()).closeReservations(anyLong(), anyString());
    }

    // ── ADR-072 재생 인덱스 재배치 배선 ──────────────────────────────────────────

    @Test
    @DisplayName("ADR072_배치_완료는_기록된_산출물_경로로_재생인덱스_재배치를_넘긴다")
    void batchCompletionSchedulesFaststartWithRecordedPath() {
        LsDeidentProcLog p = submitted();
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        String deid = realDeidFile();

        tx.finishDownloadAndComplete(9001L, 1L, 202L, deid);

        verify(faststartService).scheduleAfterCommit(9001L, "/raw/clip.mp4", deid);
        // 기록 경로·완료 상태는 재배치와 무관하게 그대로다.
        assertThat(p.getDeIdntfFilePathNm()).isEqualTo(deid);
        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
        assertThat(raw.getDataSttsCd()).isEqualTo("MARKING_READY");
    }

    @Test
    @DisplayName("ADR072_재비식별_완료도_같은_경로로_재생인덱스_재배치를_넘긴다")
    void redeidentCompletionSchedulesFaststart() {
        LsDeidentProcLog p = redeidentSubmitted();
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        String deid = realDeidFile();

        tx.finishDownloadAndComplete(9001L, 1L, 202L, deid);

        verify(faststartService).scheduleAfterCommit(9001L, "/raw/clip.mp4", deid);
    }

    @Test
    @DisplayName("ADR072_완료_선점을_얻지_못한_노드는_재배치를_넘기지_않는다")
    void duplicateCompletionDoesNotScheduleFaststart() {
        when(procLogRepository.claimDownloadCompletion(anyLong(), anyString(), any(LocalDateTime.class)))
                .thenReturn(0);

        tx.finishDownloadAndComplete(9001L, 1L, 202L, realDeidFile());

        verify(faststartService, never()).scheduleAfterCommit(any(), any(), any());
    }

    @Test
    @DisplayName("ADR072_재비식별_후처리가_실패해_롤백되면_재배치를_넘기지_않는다")
    void redeidentAttachFailureDoesNotScheduleFaststart() {
        LsDeidentProcLog p = redeidentSubmitted();
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        when(deidentFrameAttacher.attachDeidentFrames(eq(raw), any(), eq(true)))
                .thenThrow(new CustomException(ErrorCode.INVALID_INPUT, "해상도 불일치"));

        assertThatThrownBy(() -> tx.finishDownloadAndComplete(9001L, 1L, 202L, realDeidFile()))
                .isInstanceOf(CustomException.class);

        verify(faststartService, never()).scheduleAfterCommit(any(), any(), any());
    }

    /** 운영과 동일한 캐시 스펙(CacheConfig)으로 실제 Caffeine 캐시매니저를 만든다(초기화 포함). */
    private static CacheManager realCacheManager() {
        SimpleCacheManager manager = (SimpleCacheManager) new CacheConfig().cacheManager();
        manager.afterPropertiesSet(); // SimpleCacheManager 는 초기화해야 getCache 가 채워진다.
        return manager;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
