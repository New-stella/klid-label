package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.auth.entity.LsAuthWorkLock;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.step.DeidentFrameAttacher;
import kr.co.cudo.authoring.common.cache.StreamMetaCacheEvictor;
import kr.co.cudo.authoring.label.service.DeidentReportService;
import kr.co.cudo.authoring.marking.service.MarkingActivationTxService;
import kr.co.cudo.authoring.notification.NotificationService;
import kr.co.cudo.authoring.support.TestVideoFixtures;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 외부 위탁 종결 지점마다 선두 비식별 재시작 잠금이 풀리고 재요청이 다시 수락되는지 — 지점별로 따로 본다.
 *
 * <p>잠금은 실제 {@code WorkLockService} 가 메모리 저장소({@link DeidentRetryLockFixture})에서 다루며,
 * 재요청 수락은 실제 판정 서비스({@link LeadDeidentRetryService#tryClaim})로 확인한다.
 *
 * @design AC-1135
 */
class KpstDeidentTxRetryLockReleaseTest {

    private static final long RAW_SN = 9101L;
    private static final long PROC_LOG_SN = 11L;

    @TempDir
    Path tmp;

    private DeidentRetryLockFixture locks;
    private VideoRepository videoRepository;
    private LsDeidentProcLogRepository procLogRepository;
    private KpstDeidentTxService tx;
    private LeadDeidentRetryService claimService;
    private LsDataRaw raw;

    @BeforeEach
    void setUp() {
        locks = new DeidentRetryLockFixture();
        videoRepository = mock(VideoRepository.class);
        procLogRepository = mock(LsDeidentProcLogRepository.class);
        MarkingActivationTxService markingActivation = mock(MarkingActivationTxService.class);
        when(markingActivation.activateReserved(anyLong())).thenReturn(Optional.empty());
        tx = new KpstDeidentTxService(videoRepository, procLogRepository,
                mock(DeidentReportService.class), mock(NotificationService.class), locks.workLockService,
                mock(DeidentFrameAttacher.class), mock(StreamMetaCacheEvictor.class),
                new DeidentApprovalHoldReleaser(mock(LsRawDataStatusRepository.class)),
                new DeidentReservationHook(markingActivation), mock(DeidentFaststartService.class));

        // 선두 비식별 실패 형상의 영상 — 재시작이 수락돼 잠금을 잡은 상태에서 출발한다.
        raw = LsDataRaw.createFromIngest("clip-r", "cctv-r", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/raw/clip.mp4", null, 60);
        setField(raw, "rawSn", RAW_SN);
        raw.markDeidentified("F");
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw));
        claimService = locks.claimService(videoRepository);
        assertThat(claimService.tryClaim(RAW_SN)).isTrue();
        assertThat(locks.activeRetryLocks(RAW_SN)).isEqualTo(1);
        // 잠긴 동안에는 재요청이 거부된다(해제 여부 단언의 대조군).
        assertThat(catchConflict()).isTrue();
    }

    private boolean catchConflict() {
        try {
            claimService.tryClaim(RAW_SN);
            return false;
        } catch (kr.co.cudo.authoring.common.exception.CustomException e) {
            return true;
        }
    }

    private void assertReleasedAndReclaimable() {
        assertThat(locks.activeRetryLocks(RAW_SN)).as("재시작 잠금이 풀려야 한다").isZero();
        assertThat(raw.getDeIdntfYn()).isEqualTo("F");
        assertThat(claimService.tryClaim(RAW_SN)).as("같은 영상 재요청이 다시 수락돼야 한다").isTrue();
    }

    private LsDeidentProcLog batchLedger() {
        LsDeidentProcLog p = LsDeidentProcLog.request(RAW_SN, null, "/raw/clip.mp4", "batch");
        setField(p, "procLogSn", PROC_LOG_SN);
        p.markKpstSubmitted(101L, null);
        when(procLogRepository.findById(PROC_LOG_SN)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    @DisplayName("외부_제출_확정실패로_끝나면_재시작_잠금이_풀려_재요청이_수락된다")
    void 제출실패() {
        batchLedger();
        when(procLogRepository.claimSubmitFailure(eq(PROC_LOG_SN), anyString(), anyString(),
                any(LocalDateTime.class))).thenReturn(1);

        assertThat(tx.failSubmit(PROC_LOG_SN, RAW_SN, KpstDeidentService.SUBMIT_FAILED_CODE, "x")).isTrue();

        assertReleasedAndReclaimable();
    }

    @Test
    @DisplayName("수락응답_미관측_회수로_끝나면_재시작_잠금이_풀린다")
    void ACK미관측회수() {
        batchLedger();
        when(procLogRepository.claimSubmitFailure(eq(PROC_LOG_SN), eq(KpstDeidentService.ACK_MISSING_CODE),
                anyString(), any(LocalDateTime.class))).thenReturn(1);

        assertThat(tx.failSubmit(PROC_LOG_SN, RAW_SN, KpstDeidentService.ACK_MISSING_CODE,
                "submit ack not received")).isTrue();

        assertReleasedAndReclaimable();
    }

    @Test
    @DisplayName("지각_제출실패_0행이면_진행중_위탁이라_잠금을_풀지_않는다")
    void 지각실패는_유지() {
        when(procLogRepository.claimSubmitFailure(eq(PROC_LOG_SN), anyString(), anyString(),
                any(LocalDateTime.class))).thenReturn(0);

        assertThat(tx.failSubmit(PROC_LOG_SN, RAW_SN, KpstDeidentService.SUBMIT_FAILED_CODE, "late")).isFalse();

        assertThat(locks.activeRetryLocks(RAW_SN)).isEqualTo(1);
    }

    @Test
    @DisplayName("취소_종결로_끝나면_영상상태는_그대로이고_재시작_잠금이_풀린다")
    void 취소종결() {
        when(procLogRepository.claimSubmitFailure(eq(PROC_LOG_SN), eq(KpstDeidentService.SUBMIT_CANCELED_CODE),
                anyString(), any(LocalDateTime.class))).thenReturn(1);

        assertThat(tx.cancelSubmit(PROC_LOG_SN, RAW_SN)).isTrue();

        assertReleasedAndReclaimable();
    }

    @Test
    @DisplayName("폴링_실패로_끝나면_재시작_잠금이_풀린다")
    void 폴링실패() {
        batchLedger();

        tx.failPolling(PROC_LOG_SN, RAW_SN);

        assertReleasedAndReclaimable();
    }

    @Test
    @DisplayName("처리시한_초과로_끝나면_재시작_잠금이_풀린다")
    void 시한초과() {
        LsDeidentProcLog p = batchLedger();
        setField(p, "pollAttemptCnt", 5);

        assertThat(tx.markTimeoutIfExpired(PROC_LOG_SN, 5, 180)).isTrue();

        assertReleasedAndReclaimable();
    }

    @Test
    @DisplayName("처리시한_미도달이면_잠금을_풀지_않는다")
    void 시한미도달은_유지() {
        batchLedger();

        assertThat(tx.markTimeoutIfExpired(PROC_LOG_SN, 100, 100_000)).isFalse();

        assertThat(locks.activeRetryLocks(RAW_SN)).isEqualTo(1);
    }

    @Test
    @DisplayName("산출물_무효_확정실패로_끝나면_재시작_잠금이_풀린다")
    void 산출물무효() {
        tx.markRawDeidentFailed(RAW_SN);

        assertReleasedAndReclaimable();
    }

    @Test
    @DisplayName("외부_위탁_성공이면_Y·MARKING_READY가_되고_재시작_잠금이_풀린다")
    void 위탁성공() {
        batchLedger();
        when(procLogRepository.claimDownloadCompletion(anyLong(), anyString(), any(LocalDateTime.class)))
                .thenReturn(1);
        String deid = TestVideoFixtures.writeTinyMp4(tmp.resolve("deid-r.mp4")).toString();

        tx.finishDownloadAndComplete(RAW_SN, PROC_LOG_SN, 202L, deid);

        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
        assertThat(raw.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
        assertThat(locks.activeRetryLocks(RAW_SN)).isZero();
        // 성공한 영상은 더 이상 형상이 아니다 — 재시작은 기존 경로로 간다(false).
        assertThat(claimService.tryClaim(RAW_SN)).isFalse();
    }

    @Test
    @DisplayName("★재시작과_무관한_배치_실패종결은_다른_기능의_잠금을_풀지_않는다")
    void 다른기능잠금_유지() {
        // 재시작 잠금이 없는 영상(적재 직후 자동 실행)에 다른 기능의 잠금이 있다.
        long other = 9102L;
        LsAuthWorkLock merge = locks.seedMergeLock(other);
        LsDataRaw otherRaw = LsDataRaw.createFromIngest("clip-o", "cctv-o", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/raw/o.mp4", null, 60);
        setField(otherRaw, "rawSn", other);
        when(videoRepository.findById(other)).thenReturn(Optional.of(otherRaw));
        LsDeidentProcLog p = LsDeidentProcLog.request(other, null, "/raw/o.mp4", "batch");
        setField(p, "procLogSn", 12L);
        p.markKpstSubmitted(102L, null);
        setField(p, "pollAttemptCnt", 9);
        when(procLogRepository.findById(12L)).thenReturn(Optional.of(p));
        when(procLogRepository.claimSubmitFailure(eq(12L), anyString(), anyString(),
                any(LocalDateTime.class))).thenReturn(1);

        tx.failSubmit(12L, other, KpstDeidentService.SUBMIT_FAILED_CODE, "x");
        tx.failPolling(12L, other);
        tx.markTimeoutIfExpired(12L, 9, 180);
        tx.markRawDeidentFailed(other);
        tx.cancelSubmit(12L, other);

        assertThat(merge.getLockSttsCd()).isEqualTo(LsAuthWorkLock.STATUS_LOCKED);
        assertThat(locks.activeOf(other)).containsExactly(merge);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Class<?> c = target.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    f.set(target, value);
                    return;
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
            throw new NoSuchFieldException(name);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
