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
import static org.mockito.Mockito.verifyNoInteractions;
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
        job = new PortalUploadSweepJob(txService, propsWithStuckTimeout(30L));
    }

    /** {@code stuck-timeout-minutes} 만 달리한 프로퍼티 — 나머지 값은 기본 형상 그대로다. */
    private PortalUploadProperties propsWithStuckTimeout(long stuckTimeoutMinutes) {
        return new PortalUploadProperties(
                5_368_709_120L, List.of("mp4"), storageDir.toString(),
                List.of("jpg"), 20_971_520L, 50, 2000,
                16_777_216L, 2_097_152L, 30L, stuckTimeoutMinutes);
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

    @Test
    @DisplayName("무갱신경과_설정이_0이면_그_회차_전이를_건너뛴다")
    void zeroStuckTimeoutSkipsTheRound() {
        // given: 설정이 0 — "0분간 갱신 없으면 실패" 는 곧 <정상 처리 중인 자산 전량 즉시 실패>다.
        job = new PortalUploadSweepJob(txService, propsWithStuckTimeout(0L));

        // when
        int failed = job.failStuckUploads();

        // then: 임의 기본값으로 대체하지 않고 아무것도 전이시키지 않는다(전이는 삭제의 예고다).
        assertThat(failed).isZero();
        verifyNoInteractions(txService);
    }

    @Test
    @DisplayName("무갱신경과_설정이_음수면_기본값으로_대체하지_않고_건너뛴다")
    void negativeStuckTimeoutSkipsTheRound() {
        // given: 음수는 커트라인이 미래가 되어 <상태가 맞는 자산 전량>이 후보가 된다.
        job = new PortalUploadSweepJob(txService, propsWithStuckTimeout(-1L));

        // when
        int failed = job.failStuckUploads();

        // then
        assertThat(failed).isZero();
        verifyNoInteractions(txService);
    }

    @Test
    @DisplayName("무갱신경과_설정이_유효하면_그_값_그대로_전이_판정에_쓴다")
    void validStuckTimeoutIsPassedThrough() {
        // given: 설정된 값이 그대로 커트라인 계산에 쓰여야 한다(코드가 값을 다시 만들지 않는다).
        job = new PortalUploadSweepJob(txService, propsWithStuckTimeout(45L));
        when(txService.failStuckUploads(45L)).thenReturn(List.of());

        // when
        job.failStuckUploads();

        // then
        verify(txService).failStuckUploads(45L);
    }

    private static int assertNoException(java.util.function.Supplier<Integer> action) {
        int[] holder = new int[1];
        assertThatCode(() -> holder[0] = action.get()).doesNotThrowAnyException();
        return holder[0];
    }
}
