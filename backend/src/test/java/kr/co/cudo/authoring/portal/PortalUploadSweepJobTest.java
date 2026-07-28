package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.scheduler.PortalUploadSweepJob;
import kr.co.cudo.authoring.portal.service.PortalUploadSweepTxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 포털 업로드 스윕 잡 단위 테스트 — 오케스트레이션 책임만 검증.
 *
 * <p>벌크 UPDATE/DELETE(트랜잭션 경계)는 {@link PortalUploadSweepTxService} 별 빈에 위임하므로
 * 여기서는 그 반환값(소유 획득 세션·자산)에 대해 스윕 잡이 <b>파일/프레임 정리(비-tx best-effort)</b>를
 * 수행하고, 중복 실행(빈 반환)에도 예외 없이 멱등함을 검증한다.
 */
class PortalUploadSweepJobTest {

    @TempDir
    Path storageDir;

    private PortalUploadSweepTxService txService;
    private PortalUploadSweepJob job;

    @BeforeEach
    void setUp() {
        txService = mock(PortalUploadSweepTxService.class);
        PortalUploadProperties props = new PortalUploadProperties(
                5_368_709_120L, List.of("mp4"), storageDir.toString(),
                List.of("jpg"), 20_971_520L, 50, 2000,
                16_777_216L, 2_097_152L, 30L, 30L);
        job = new PortalUploadSweepJob(txService, props);
    }

    @Test
    @DisplayName("스윕잡이_만료_세션과_임시파일_정리")
    void cleansExpiredSessionsAndTempFiles() throws Exception {
        Path temp = Files.createFile(storageDir.resolve(UUID.randomUUID() + ".mp4"));
        // tx 빈이 소유 획득한 세션의 임시파일 경로를 반환 → 잡이 파일 정리.
        when(txService.claimExpiredSessions()).thenReturn(List.of(temp.toString()));

        int cleaned = job.cleanupExpiredSessions();

        assertThat(cleaned).isEqualTo(1);
        assertThat(Files.exists(temp)).isFalse();
        verify(txService).claimExpiredSessions();
    }

    @Test
    @DisplayName("스윕잡이_고착_자산의_부분_프레임_정리")
    void failsStuckUploadsAndCleansFrames() throws Exception {
        // tx 빈이 FAILED 전이 성공한 uldSn 반환 → 잡이 프레임 디렉토리 정리.
        Path framesDir = storageDir.resolve("frames").resolve("42");
        Files.createDirectories(framesDir);
        Path frameFile = Files.createFile(framesDir.resolve("frame-0.jpg"));
        when(txService.failStuckUploads(30L)).thenReturn(List.of(42L));

        int failed = job.failStuckUploads();

        assertThat(failed).isEqualTo(1);
        verify(txService).failStuckUploads(30L);
        // adversarial #3: 부분 프레임 파일/디렉토리 정리.
        assertThat(Files.exists(frameFile)).isFalse();
        assertThat(Files.exists(framesDir)).isFalse();
    }

    @Test
    @DisplayName("스윕_중복_실행시_예외없이_멱등")
    void duplicateSweepIsIdempotent() throws Exception {
        // 다른 노드가 먼저 정리 → tx 빈이 빈 목록 반환. 예외 없이 카운트 0, 파일 미삭제.
        Path temp = Files.createFile(storageDir.resolve(UUID.randomUUID() + ".mp4"));
        when(txService.claimExpiredSessions()).thenReturn(List.of());
        when(txService.failStuckUploads(anyLong())).thenReturn(List.of());

        int cleaned = assertNoException(job::cleanupExpiredSessions);
        int failed = assertNoException(job::failStuckUploads);

        assertThat(cleaned).isZero();
        assertThat(failed).isZero();
        // 소유 획득이 없으므로 이 노드는 파일을 건드리지 않음.
        assertThat(Files.exists(temp)).isTrue();
    }

    private static int assertNoException(java.util.function.Supplier<Integer> action) {
        int[] holder = new int[1];
        assertThatCode(() -> holder[0] = action.get()).doesNotThrowAnyException();
        return holder[0];
    }
}
