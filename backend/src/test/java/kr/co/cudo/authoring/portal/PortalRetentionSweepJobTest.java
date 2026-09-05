package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.scheduler.PortalRetentionSweepJob;
import kr.co.cudo.authoring.portal.service.PortalRetentionSweepTxService;
import kr.co.cudo.authoring.portal.service.PortalRetentionSweepTxService.Axis;
import kr.co.cudo.authoring.portal.service.PortalRetentionSweepTxService.ExpiredUpload;
import kr.co.cudo.authoring.portal.service.PortalStoragePathGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 보존기간 삭제 잡의 <b>오케스트레이션</b> 회귀 가드. @design DFEAT-055, AC-037
 *
 * <h3>이 파일이 막는 것</h3>
 * <ol>
 *   <li><b>순서 역전</b> — DB 행을 먼저 지우면 어느 파일을 지워야 하는지 알 수 없어져 고아 파일이
 *       영구히 남는다. 순서는 "삭제 호출 시점에 파일이 이미 없는가"로 단언한다(호출 순서만 세면
 *       파일이 실제로 지워졌는지는 확인되지 않는다).</li>
 *   <li><b>경로 판정 실패 시 DB 행까지 지우는 것</b> — 파일이 남았는데 행만 지우면 그 파일은 영원히
 *       추적 불가가 된다(AC-037 and_examples[2]). 그 자산만 건너뛰고 회차는 계속돼야 한다.</li>
 * </ol>
 *
 * <p>★ 위험은 leaf 심링크가 아니라 <b>중간 디렉터리 심링크</b>다 — {@code deleteIfExists} 는 최종
 * 요소가 링크면 링크만 지우지만 중간 디렉터리 링크는 <b>투명하게 따라가</b> 실제 대상을 지운다.
 * 그래서 시나리오를 그 축으로 만든다({@code PortalUploadDeleteRealPathGuardTest} 와 같은 이유).
 */
class PortalRetentionSweepJobTest {

    /** 포털 저장 루트. */
    @TempDir Path storageDir;
    /** 저장 루트 <b>밖</b> — 지워져서는 안 되는 영역. */
    @TempDir Path outsideDir;

    private PortalRetentionSweepTxService txService;
    private PortalRetentionSweepJob job;

    @BeforeEach
    void setUp() {
        txService = mock(PortalRetentionSweepTxService.class);
        PortalUploadProperties props = new PortalUploadProperties(
                5_368_709_120L, List.of("mp4"), storageDir.toString(),
                List.of("jpg", "jpeg", "png"), 20_971_520L, 50, 2000,
                16_777_216L, 2_097_152L, 30L, 30L);
        job = new PortalRetentionSweepJob(txService, new PortalStoragePathGuard(props));
    }

    // ------------------------------------------------------------------ 순서

    @Test
    @DisplayName("파일을_먼저_지운_뒤에_DB행을_지운다")
    void deletesFilesBeforeDbRow() throws Exception {
        Path frame = Files.write(storageDir.resolve("frame-0.jpg"), new byte[]{1});
        Path source = Files.write(storageDir.resolve("v.mp4"), new byte[]{2});
        when(txService.findExpiredUploads()).thenReturn(List.of(candidate(
                1L, Axis.READY, frame.toString(), source.toString())));
        // 삭제 호출 시점에 파일이 이미 없어야 한다 — 순서가 뒤집히면 여기서 드러난다.
        boolean[] filesGoneAtDbDelete = {false};
        when(txService.deleteExpiredUpload(any(ExpiredUpload.class))).thenAnswer(inv -> {
            filesGoneAtDbDelete[0] = Files.notExists(frame) && Files.notExists(source);
            return 1;
        });

        assertThat(job.sweepExpiredUploads()).isEqualTo(1);

        assertThat(filesGoneAtDbDelete[0])
                .as("DB 행 삭제 시점에 파일이 이미 지워져 있어야 한다(파일 → DB 순서)")
                .isTrue();
    }

    // ------------------------------------------------------------------ 경로 판정 실패

    @Test
    @DisplayName("경로_중간_디렉터리가_저장루트_밖을_가리키면_DB행을_지우지_않고_대상파일도_살아있다")
    void skipsAssetWhenIntermediateDirectoryEscapesBase() throws Exception {
        // given — 저장 루트 밖의 희생 파일. 사라지면 그 자체로 비가역 유실이다.
        Path victimDir = Files.createDirectories(outsideDir.resolve("frames"));
        Path victim = Files.write(victimDir.resolve("original.jpg"), new byte[]{9});
        // 저장 루트 안의 디렉터리가 그 밖을 가리킨다 — lexical 검증만으로는 통과하는 형상.
        Path linkDir = storageDir.resolve("frames");
        Files.createSymbolicLink(linkDir, victimDir);
        when(txService.findExpiredUploads()).thenReturn(List.of(candidate(
                1L, Axis.READY, linkDir.resolve("original.jpg").toString())));

        int deleted = job.sweepExpiredUploads();

        assertThat(deleted).isZero();
        assertThat(victim).as("저장 루트 밖 파일이 지워지면 안 된다").exists();
        verify(txService, never()).deleteExpiredUpload(any(ExpiredUpload.class));
    }

    @Test
    @DisplayName("저장루트_밖_절대경로가_적재돼_있으면_그_자산을_건너뛰고_DB행을_남긴다")
    void skipsAssetWhenStoredPathIsOutsideBase() throws Exception {
        Path outside = Files.write(outsideDir.resolve("leak.jpg"), new byte[]{7});
        when(txService.findExpiredUploads())
                .thenReturn(List.of(candidate(1L, Axis.FAILED, outside.toString())));

        assertThat(job.sweepExpiredUploads()).isZero();

        assertThat(outside).exists();
        verify(txService, never()).deleteExpiredUpload(any(ExpiredUpload.class));
    }

    @Test
    @DisplayName("한_자산의_경로_판정_실패가_다음_자산_처리를_막지_않는다")
    void oneRejectedAssetDoesNotStopTheSweep() throws Exception {
        Path outside = Files.write(outsideDir.resolve("leak.jpg"), new byte[]{7});
        Path healthy = Files.write(storageDir.resolve("ok.jpg"), new byte[]{1});
        ExpiredUpload rejected = candidate(1L, Axis.READY, outside.toString());
        ExpiredUpload processable = candidate(2L, Axis.READY, healthy.toString());
        when(txService.findExpiredUploads()).thenReturn(List.of(rejected, processable));
        when(txService.deleteExpiredUpload(processable)).thenReturn(1);

        assertThat(job.sweepExpiredUploads()).isEqualTo(1);

        assertThat(healthy).doesNotExist();
        assertThat(outside).exists();
        verify(txService, never()).deleteExpiredUpload(rejected);
        verify(txService).deleteExpiredUpload(processable);
    }

    // ------------------------------------------------------------------ 멱등·경계

    @Test
    @DisplayName("파일이_이미_없어도_정상_처리해_DB행을_지운다")
    void absentFileIsIdempotentSuccess() {
        when(txService.findExpiredUploads()).thenReturn(List.of(candidate(
                1L, Axis.READY, storageDir.resolve("already-gone.jpg").toString())));
        when(txService.deleteExpiredUpload(any(ExpiredUpload.class))).thenReturn(1);

        assertThat(job.sweepExpiredUploads()).isEqualTo(1);
    }

    @Test
    @DisplayName("타노드가_먼저_지워_0행이면_오류로_보지_않는다")
    void zeroRowsWithoutRemainingRowIsNormal() throws Exception {
        Path frame = Files.write(storageDir.resolve("frame-0.jpg"), new byte[]{1});
        when(txService.findExpiredUploads())
                .thenReturn(List.of(candidate(1L, Axis.READY, frame.toString())));
        when(txService.deleteExpiredUpload(any(ExpiredUpload.class))).thenReturn(0);
        when(txService.exists(1L)).thenReturn(false);

        assertThat(job.sweepExpiredUploads()).isZero();
        verify(txService).exists(1L);
    }

    @Test
    @DisplayName("후보가_없으면_삭제를_한_번도_호출하지_않는다")
    void noCandidatesMeansNoDeletion() {
        when(txService.findExpiredUploads()).thenReturn(List.of());

        assertThat(job.sweepExpiredUploads()).isZero();
        verify(txService, never()).deleteExpiredUpload(any(ExpiredUpload.class));
    }

    @Test
    @DisplayName("한_회차가_예외로_죽어도_잡_자체는_살아남는다")
    void runSwallowsRuntimeException() {
        when(txService.sweepDatamartWorks()).thenThrow(new IllegalStateException("boom"));

        job.run();

        verify(txService).sweepDatamartWorks();
    }

    private static ExpiredUpload candidate(long uldSn, Axis axis, String... filePaths) {
        return new ExpiredUpload(uldSn, axis, LocalDateTime.now().minusDays(30), List.of(filePaths));
    }
}
