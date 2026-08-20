package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-048 — 승인 보류 해제기의 <b>판정</b> 단위 시험.
 *
 * <p>통합 시험({@code DeidentApprovalHoldReleaseIT})이 "성공하면 승인이 통과한다"를 고정한다면,
 * 여기서는 <b>풀지 않아야 하는 경우</b>를 낱낱이 고정한다. 그쪽이 이 계약의 위험한 방향이다 —
 * 잘못 풀면 처리되지 않은 산출물이 검수 승인을 통과하고, 승인 이후에는 되돌릴 수단이 사실상 없다.
 *
 * @design ADR-048
 * @design AC-046
 */
class DeidentApprovalHoldReleaserTest {

    private static final long RAW_SN = 7001L;

    private LsRawDataStatusRepository statusRepository;
    private DeidentApprovalHoldReleaser releaser;

    @BeforeEach
    void setUp() {
        statusRepository = mock(LsRawDataStatusRepository.class);
        releaser = new DeidentApprovalHoldReleaser(statusRepository);
    }

    private LsDataRaw rawWith(String deIdntfYn) {
        LsDataRaw raw = LsDataRaw.createFromIngest("clip-hold", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/raw/clip.mp4", LocalDateTime.now(), 30);
        setField(raw, "rawSn", RAW_SN);
        raw.markDeidentified(deIdntfYn);
        return raw;
    }

    private LsRawDataStatus heldStatus() {
        LsRawDataStatus status = LsRawDataStatus.initial(RAW_SN);
        status.markDeidentNotCompleted();
        return status;
    }

    // ---------------------------------------------------------------- 푼다

    @Test
    @DisplayName("비식별이_성공_Y_로_기록된_영상은_승인_보류가_풀린다")
    void releasesWhenDeidentSucceeded() {
        LsRawDataStatus status = heldStatus();
        when(statusRepository.findById(RAW_SN)).thenReturn(Optional.of(status));

        releaser.releaseOnDeidentSuccess(rawWith("Y"));

        assertThat(status.isDeidentCompleted()).isTrue();
    }

    @Test
    @DisplayName("이미_풀린_영상에_다시_불러도_멱등이고_되돌아가지_않는다")
    void idempotentOnRepeatedCalls() {
        LsRawDataStatus status = heldStatus();
        when(statusRepository.findById(RAW_SN)).thenReturn(Optional.of(status));
        LsDataRaw raw = rawWith("Y");

        releaser.releaseOnDeidentSuccess(raw);
        releaser.releaseOnDeidentSuccess(raw);

        assertThat(status.isDeidentCompleted()).isTrue();
    }

    // ---------------------------------------------------------------- 풀지 않는다

    @Test
    @DisplayName("비식별_미수행_N_이면_보류를_풀지_않는다")
    void keepsHoldWhenDeidentNotPerformed() {
        LsRawDataStatus status = heldStatus();
        when(statusRepository.findById(RAW_SN)).thenReturn(Optional.of(status));

        releaser.releaseOnDeidentSuccess(rawWith("N"));

        assertThat(status.isDeidentCompleted()).isFalse();
    }

    @Test
    @DisplayName("비식별_실패_또는_누락신고_F_이면_보류를_풀지_않는다_거짓성공_차단")
    void keepsHoldWhenDeidentFailed() {
        LsRawDataStatus status = heldStatus();
        when(statusRepository.findById(RAW_SN)).thenReturn(Optional.of(status));

        releaser.releaseOnDeidentSuccess(rawWith("F"));

        assertThat(status.isDeidentCompleted()).isFalse();
    }

    @Test
    @DisplayName("성공이_아니면_작업상태_행을_조회조차_하지_않는다")
    void doesNotEvenLookUpStatusWhenNotSucceeded() {
        releaser.releaseOnDeidentSuccess(rawWith("F"));

        verify(statusRepository, never()).findById(anyLong());
    }

    // ---------------------------------------------------------------- 기존 영상 무영향

    @Test
    @DisplayName("이미_완료로_기록된_기존_영상에는_쓰기를_하지_않는다_불필요한_UPDATE_금지")
    void noWriteForAlreadyCompletedStatus() {
        // 기본값이 완료인 <b>기존 전 영상</b>이 여기 해당한다. 값을 다시 쓰면 낙관적 잠금 버전이
        //   올라가 동시 승인과 불필요하게 충돌한다.
        LsRawDataStatus status = mock(LsRawDataStatus.class);
        when(status.isDeidentCompleted()).thenReturn(true);
        when(statusRepository.findById(RAW_SN)).thenReturn(Optional.of(status));

        releaser.releaseOnDeidentSuccess(rawWith("Y"));

        verify(status, never()).markDeidentCompleted();
    }

    @Test
    @DisplayName("작업상태_행이_아직_없으면_예외없이_넘어간다_관제_인입_영상은_배정_시점에_생긴다")
    void gracefulWhenStatusRowAbsent() {
        when(statusRepository.findById(RAW_SN)).thenReturn(Optional.empty());

        releaser.releaseOnDeidentSuccess(rawWith("Y"));
    }

    @Test
    @DisplayName("영상이_null_이거나_식별자가_없으면_아무것도_하지_않는다")
    void gracefulOnNullInput() {
        releaser.releaseOnDeidentSuccess(null);
        releaser.releaseOnDeidentSuccess(rawWith("Y").getRawSn() == null ? null : newRawWithoutSn());

        verify(statusRepository, never()).findById(anyLong());
    }

    private LsDataRaw newRawWithoutSn() {
        LsDataRaw raw = LsDataRaw.createFromIngest("clip-nosn", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/raw/clip.mp4", LocalDateTime.now(), 30);
        raw.markDeidentified("Y");
        return raw;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
