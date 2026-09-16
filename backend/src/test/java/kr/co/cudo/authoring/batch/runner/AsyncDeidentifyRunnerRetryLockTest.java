package kr.co.cudo.authoring.batch.runner;

import kr.co.cudo.authoring.auth.entity.LsAuthWorkLock;
import kr.co.cudo.authoring.batch.pipeline.BatchPipeline;
import kr.co.cudo.authoring.batch.service.DeidentExclusionPolicy;
import kr.co.cudo.authoring.batch.service.DeidentExclusionService;
import kr.co.cudo.authoring.batch.service.DeidentReservationHook;
import kr.co.cudo.authoring.batch.service.DeidentRetryLockFixture;
import kr.co.cudo.authoring.batch.service.KpstDeidentService;
import kr.co.cudo.authoring.batch.service.LeadDeidentRetryService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.batch.step.DeidentifyStep;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.marking.service.MarkingActivationTxService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 선두 비식별 러너가 관측하는 종결 — 제외 복사 성공·제외 복사 실패·제출 이전 실패 — 에서 재시작 잠금이
 * 풀리는지, 외부 위탁으로 넘어간 경우에는 잡은 채로 두는지. 단계는 실제 {@link DeidentifyStep} 이다.
 *
 * @design AC-1135
 */
class AsyncDeidentifyRunnerRetryLockTest {

    private static final long RAW_SN = 9201L;

    private DeidentRetryLockFixture locks;
    private VideoRepository videoRepository;
    private BatchTransitionService transitionService;
    private DeidentExclusionPolicy exclusionPolicy;
    private DeidentExclusionService exclusionService;
    private KpstDeidentService kpst;
    private DeidentifyStep step;
    private AsyncDeidentifyRunner runner;
    private LeadDeidentRetryService claimService;
    private LsDataRaw raw;

    @BeforeEach
    void setUp() throws Exception {
        locks = new DeidentRetryLockFixture();
        videoRepository = mock(VideoRepository.class);
        transitionService = mock(BatchTransitionService.class);
        exclusionPolicy = mock(DeidentExclusionPolicy.class);
        exclusionService = mock(DeidentExclusionService.class);
        kpst = mock(KpstDeidentService.class);
        step = new DeidentifyStep(kpst, null, exclusionPolicy, exclusionService);
        setField(step, "kpstEnabled", true);
        MarkingActivationTxService markingActivation = mock(MarkingActivationTxService.class);
        when(markingActivation.activateReserved(anyLong())).thenReturn(Optional.empty());
        runner = new AsyncDeidentifyRunner(new BatchPipeline(List.of(step)), transitionService,
                videoRepository, new DeidentReservationHook(markingActivation), locks.workLockService);

        raw = LsDataRaw.createFromIngest("clip-q", "cctv-q", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/raw/q.mp4", null, 60);
        setField(raw, "rawSn", RAW_SN);
        raw.markDeidentified("F");
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw));
        claimService = locks.claimService(videoRepository);
        assertThat(claimService.tryClaim(RAW_SN)).isTrue();
    }

    @Test
    @DisplayName("★출처유형_제외_복사_성공은_MARKING_READY_전이_뒤에_재시작_잠금을_푼다")
    void 제외복사성공() {
        when(exclusionPolicy.isExcluded(any())).thenReturn(true);
        when(exclusionService.complete(raw)).thenAnswer(inv -> {
            raw.markDeidentified("Y");
            return "/deid/q.mp4";
        });
        AtomicLong heldAtTransition = new AtomicLong(-1);
        doAnswer(inv -> {
            heldAtTransition.set(locks.activeRetryLocks(RAW_SN));
            raw.markMarkingReady();
            return null;
        }).when(transitionService).markRawDataMarkingReady(RAW_SN);

        runner.runNow(RAW_SN);

        assertThat(heldAtTransition.get()).as("전이 시점에는 아직 잠겨 있어야 한다").isEqualTo(1);
        assertThat(locks.activeRetryLocks(RAW_SN)).isZero();
        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
        assertThat(raw.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
        verify(kpst, never()).submit(any());
    }

    @Test
    @DisplayName("출처유형_제외_복사_실패로_끝나면_재시작_잠금이_풀려_재요청이_수락된다")
    void 제외복사실패() {
        when(exclusionPolicy.isExcluded(any())).thenReturn(true);
        when(exclusionService.complete(raw))
                .thenThrow(new CustomException(ErrorCode.INTERNAL_ERROR, "copy failed"));

        runner.runNow(RAW_SN);

        assertThat(locks.activeRetryLocks(RAW_SN)).isZero();
        verify(transitionService, never()).markRawDataMarkingReady(anyLong());
        assertThat(claimService.tryClaim(RAW_SN)).isTrue();
    }

    @Test
    @DisplayName("외부_제출_개시_이전_실패로_끝나면_재시작_잠금이_풀려_재요청이_수락된다")
    void 제출이전실패() {
        when(kpst.submit(raw)).thenThrow(new CustomException(ErrorCode.INVALID_INPUT, "source missing"));

        runner.runNow(RAW_SN);

        assertThat(locks.activeRetryLocks(RAW_SN)).isZero();
        assertThat(claimService.tryClaim(RAW_SN)).isTrue();
    }

    @Test
    @DisplayName("외부_위탁으로_넘어가면_종결_전까지_재시작_잠금을_잡고_있다")
    void 위탁진행중은_유지() {
        when(kpst.submit(raw)).thenReturn(null);

        runner.runNow(RAW_SN);

        verify(kpst).submit(raw);
        assertThat(locks.activeRetryLocks(RAW_SN)).isEqualTo(1);
        verify(transitionService, never()).markRawDataMarkingReady(anyLong());
    }

    @Test
    @DisplayName("재시작과_무관한_실행의_실패는_다른_기능의_잠금을_풀지_않는다")
    void 다른기능잠금_유지() {
        long other = 9202L;
        LsAuthWorkLock merge = locks.seedMergeLock(other);
        LsDataRaw otherRaw = LsDataRaw.createFromIngest("clip-p", "cctv-p", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/raw/p.mp4", null, 60);
        setField(otherRaw, "rawSn", other);
        when(videoRepository.findById(other)).thenReturn(Optional.of(otherRaw));
        when(kpst.submit(otherRaw)).thenThrow(new CustomException(ErrorCode.INVALID_INPUT, "x"));

        runner.runNow(other);

        assertThat(merge.getLockSttsCd()).isEqualTo(LsAuthWorkLock.STATUS_LOCKED);
    }

    @Test
    @DisplayName("잠금_축이_없는_구성에서도_실패를_삼킨다_기존_생성자_호환")
    void 잠금축없음() {
        AsyncDeidentifyRunner legacy = new AsyncDeidentifyRunner(new BatchPipeline(List.of(step)),
                transitionService, videoRepository, mock(DeidentReservationHook.class));
        when(kpst.submit(raw)).thenThrow(new CustomException(ErrorCode.INVALID_INPUT, "x"));

        legacy.runNow(RAW_SN);

        assertThat(locks.activeRetryLocks(RAW_SN)).isEqualTo(1);
        verify(transitionService, never()).markRawDataMarkingReady(anyLong());
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
