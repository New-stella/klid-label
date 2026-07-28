package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.observability.metrics.ResolutionMetrics;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.runner.AsyncResolutionRunner;
import kr.co.cudo.authoring.video.service.ResolutionFileMaterializer;
import kr.co.cudo.authoring.video.service.ResolutionPersistService;
import kr.co.cudo.authoring.video.service.ResolutionSnapshot;
import kr.co.cudo.authoring.video.service.ResolutionSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 해상도 파생영상 비동기 오케스트레이터 단위 테스트 — 락-I/O 분리 리팩터(A/B/C 별도 빈).
 *
 * <p>러너가 Phase A(스냅샷)→B(파일)→C(영속)를 순차 호출하고, 실패 시 cleanup + 중복 finalize 승자 보호
 * + 예약 aug 해제 + FAILED 전이를 수행하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsyncResolutionRunnerTest {

    @Mock ResolutionSnapshotService snapshotService;
    @Mock ResolutionFileMaterializer fileMaterializer;
    @Mock ResolutionPersistService persistService;
    @Mock BatchTransitionService batchTransitionService;
    @Mock ResolutionMetrics resolutionMetrics;

    AsyncResolutionRunner runner;

    @BeforeEach
    void setup() {
        runner = new AsyncResolutionRunner(snapshotService, fileMaterializer, persistService,
                batchTransitionService, resolutionMetrics);
        // cleanup 기본값 = 정리 성공(true). 각 실패 테스트에서 필요 시 재스텁.
        when(fileMaterializer.cleanup(any(), any())).thenReturn(true);
    }

    private ResolutionSnapshot snap(long newRawSn, long parentRawSn, long dataAugSn, Path videoDst) {
        return new ResolutionSnapshot(newRawSn, parentRawSn, dataAugSn, ResolutionPreset.RESL_720P,
                1920, 1080, 1280, 720, 0.6667, 0.6667, 0, 0, "rev1",
                Paths.get("/base/videos/deid.mp4"), videoDst, java.time.Instant.now(), List.of());
    }

    @Test
    @DisplayName("정상확정시_A_B_C를_순차호출하고_FAILED전이나_정리를_하지않는다")
    void successRunsAllPhasesWithoutFailureHandling() {
        Path videoDst = Paths.get("/base/resolution/700/video/RESL_720P.mp4");
        ResolutionSnapshot s = snap(700L, 200L, 9L, videoDst);
        when(snapshotService.snapshot(700L, 200L, 9L, ResolutionPreset.RESL_720P)).thenReturn(Optional.of(s));
        when(persistService.persist(s)).thenReturn(ResolutionPersistService.Result.PERSISTED);

        runner.runAsync(700L, 200L, 9L, ResolutionPreset.RESL_720P);

        verify(fileMaterializer).materialize(s);
        verify(persistService).persist(s);
        verify(batchTransitionService, never()).markRawDataFailed(any());
        verify(fileMaterializer, never()).cleanup(any(), any());
        verify(persistService, never()).releaseReservedAug(any());
    }

    @Test
    @DisplayName("PhaseA가_멱등skip이면_B_C_정리_전이_모두_수행하지않는다")
    void snapshotEmptySkipsEverything() {
        when(snapshotService.snapshot(701L, 200L, 9L, ResolutionPreset.RESL_720P)).thenReturn(Optional.empty());

        runner.runAsync(701L, 200L, 9L, ResolutionPreset.RESL_720P);

        verify(fileMaterializer, never()).materialize(any());
        verify(persistService, never()).persist(any());
        verify(fileMaterializer, never()).cleanup(any(), any());
        verify(batchTransitionService, never()).markRawDataFailed(any());
    }

    @Test
    @DisplayName("PhaseC가_SKIPPED_중복finalize패자면_정리도_FAILED전이도_하지않는다")
    void persistSkippedDoesNotCleanupWinnerArtifacts() {
        ResolutionSnapshot s = snap(702L, 200L, 9L, Paths.get("/base/resolution/702/video/RESL_720P.mp4"));
        when(snapshotService.snapshot(702L, 200L, 9L, ResolutionPreset.RESL_720P)).thenReturn(Optional.of(s));
        when(persistService.persist(s)).thenReturn(ResolutionPersistService.Result.SKIPPED);

        runner.runAsync(702L, 200L, 9L, ResolutionPreset.RESL_720P);

        // 파일은 승자와 동일 경로 — 정리하면 승자 산출물을 지우므로 절대 cleanup/FAILED 안 함.
        verify(fileMaterializer, never()).cleanup(any(), any());
        verify(batchTransitionService, never()).markRawDataFailed(any());
        verify(persistService, never()).releaseReservedAug(any());
    }

    @Test
    @DisplayName("PhaseB_실패시_아티팩트정리후_예약aug해제_및_FAILED전이한다")
    void materializeFailureCleansUpAndFails() {
        Path videoDst = Paths.get("/base/resolution/703/video/RESL_720P.mp4");
        ResolutionSnapshot s = snap(703L, 200L, 42L, videoDst);
        when(snapshotService.snapshot(703L, 200L, 42L, ResolutionPreset.RESL_720P)).thenReturn(Optional.of(s));
        doThrow(new CustomException(ErrorCode.NOT_FOUND, "deid video missing"))
                .when(fileMaterializer).materialize(s);
        when(persistService.isAlreadyFinalized(703L)).thenReturn(false);

        runner.runAsync(703L, 200L, 42L, ResolutionPreset.RESL_720P);

        verify(fileMaterializer).cleanup(703L, videoDst);
        verify(persistService).releaseReservedAug(42L);
        verify(batchTransitionService).markRawDataFailed(703L);
    }

    @Test
    @DisplayName("cleanup이_잔존false를_반환하면_cleanupFailed_메트릭을_올린다")
    void cleanupResidualEmitsMetric() {
        Path videoDst = Paths.get("/base/resolution/704/video/RESL_720P.mp4");
        ResolutionSnapshot s = snap(704L, 200L, 42L, videoDst);
        when(snapshotService.snapshot(704L, 200L, 42L, ResolutionPreset.RESL_720P)).thenReturn(Optional.of(s));
        doThrow(new CustomException(ErrorCode.INTERNAL_ERROR, "resize failed"))
                .when(fileMaterializer).materialize(s);
        when(fileMaterializer.cleanup(704L, videoDst)).thenReturn(false); // 삭제 후에도 잔존
        when(persistService.isAlreadyFinalized(704L)).thenReturn(false);

        runner.runAsync(704L, 200L, 42L, ResolutionPreset.RESL_720P);

        verify(resolutionMetrics).cleanupFailed();
        verify(batchTransitionService).markRawDataFailed(704L); // 메트릭과 무관하게 FAILED 전이는 진행
    }

    @Test
    @DisplayName("실패했지만_이미_승자가_확정(Y)했으면_cleanup도_FAILED전이도_aug해제도_스킵한다(M-1_승자산출물보호)")
    void concurrentWinnerProtectionSkipsCleanupAndFailedTransition() {
        Path videoDst = Paths.get("/base/resolution/705/video/RESL_720P.mp4");
        ResolutionSnapshot s = snap(705L, 200L, 42L, videoDst);
        when(snapshotService.snapshot(705L, 200L, 42L, ResolutionPreset.RESL_720P)).thenReturn(Optional.of(s));
        doThrow(new CustomException(ErrorCode.CONFLICT, "uk violation"))
                .when(persistService).persist(s);
        // newRaw 최신 상태가 이미 확정 — 승자를 FAILED 로 덮어쓰지 않고 승자 산출물도 cleanup 하지 않는다.
        when(persistService.isAlreadyFinalized(705L)).thenReturn(true);

        runner.runAsync(705L, 200L, 42L, ResolutionPreset.RESL_720P);

        // M-1 (CWE-362) — 승자 보호 선점검이 cleanup 보다 먼저라 패자 정리가 승자 파일을 지우지 않는다.
        verify(fileMaterializer, never()).cleanup(any(), any());
        verify(batchTransitionService, never()).markRawDataFailed(any());
        verify(persistService, never()).releaseReservedAug(any());
    }

    @Test
    @DisplayName("PhaseC_stale게이트_CONFLICT시_승자없으면_복사된PII파일_cleanup후_예약aug해제_및_FAILED전이한다(H-1)")
    void persistStaleConflictCleansUpAndFails() {
        Path videoDst = Paths.get("/base/resolution/708/video/RESL_720P.mp4");
        ResolutionSnapshot s = snap(708L, 200L, 45L, videoDst);
        when(snapshotService.snapshot(708L, 200L, 45L, ResolutionPreset.RESL_720P)).thenReturn(Optional.of(s));
        // Phase B 는 성공(파일 산출), Phase C stale 게이트가 CONFLICT abort.
        doThrow(new CustomException(ErrorCode.CONFLICT, "deident replaced since snapshot"))
                .when(persistService).persist(s);
        when(persistService.isAlreadyFinalized(708L)).thenReturn(false); // 승자 없음 → 정리·전이 진행

        runner.runAsync(708L, 200L, 45L, ResolutionPreset.RESL_720P);

        verify(fileMaterializer).cleanup(708L, videoDst); // 복사된 (구버전 PII 가능) 파일 정리
        verify(persistService).releaseReservedAug(45L);
        verify(batchTransitionService).markRawDataFailed(708L);
    }

    @Test
    @DisplayName("예약aug_해제가_예외를_던져도_FAILED전이는_수행된다")
    void augReleaseFailureDoesNotBlockFailedTransition() {
        Path videoDst = Paths.get("/base/resolution/706/video/RESL_720P.mp4");
        ResolutionSnapshot s = snap(706L, 200L, 43L, videoDst);
        when(snapshotService.snapshot(706L, 200L, 43L, ResolutionPreset.RESL_720P)).thenReturn(Optional.of(s));
        doThrow(new CustomException(ErrorCode.INTERNAL_ERROR, "persist failed"))
                .when(persistService).persist(s);
        when(persistService.isAlreadyFinalized(706L)).thenReturn(false);
        doThrow(new RuntimeException("db down")).when(persistService).releaseReservedAug(43L);

        runner.runAsync(706L, 200L, 43L, ResolutionPreset.RESL_720P);

        verify(persistService).releaseReservedAug(43L);
        verify(batchTransitionService).markRawDataFailed(706L);
    }

    @Test
    @DisplayName("PhaseA_예외시_스냅샷이없어_cleanup은_생략하고_예약aug해제_및_FAILED전이한다")
    void snapshotPhaseFailureSkipsCleanupButFails() {
        // Phase A 자체 예외(예: PII 재검증 CONFLICT) — 파일이 안 쓰였으므로 cleanup 대상 없음.
        doThrow(new CustomException(ErrorCode.CONFLICT, "parent not deidentified"))
                .when(snapshotService).snapshot(707L, 200L, 44L, ResolutionPreset.RESL_720P);
        when(persistService.isAlreadyFinalized(707L)).thenReturn(false);

        runner.runAsync(707L, 200L, 44L, ResolutionPreset.RESL_720P);

        verify(fileMaterializer, never()).cleanup(any(), any());
        verify(persistService).releaseReservedAug(44L);
        verify(batchTransitionService).markRawDataFailed(707L);
    }
}
