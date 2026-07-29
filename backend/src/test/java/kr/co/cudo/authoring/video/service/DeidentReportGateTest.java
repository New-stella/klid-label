package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S7 — {@link DeidentReportGate} 판정 범위 회귀 고정 (2026-07-29 확정 정책).
 *
 * <p><b>판정은 자기 rawSn 행 하나</b>다. 파생영상(증강·해상도)은 원본의 비식별 누락 신고와 <b>무관하게</b>
 * 다룬다 — 원본이 {@code 'F'} 여도 파생본은 막히지 않는다. 이 파일은 그 경계를 잠근다.
 *
 * <p>구 {@code DeidentReportGateLockOrderTest}(조상 체인 잠금 순서 검증)는 체인 판정 자체가 철회되면서
 * 검증 대상이 사라져 이 파일로 대체됐다 — 잠금 대상이 한 행뿐이라 순서 개념이 없다.
 */
class DeidentReportGateTest {

    private VideoRepository videoRepository;
    private DeidentReportGate gate;

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        gate = new DeidentReportGate(videoRepository);
    }

    /** 무잠금 판정용 단일 컬럼 스텁 — {@code DE_IDNTF_YN}. */
    private void stubDeidentYn(Long rawSn, String deIdntfYn) {
        when(videoRepository.findDeIdntfYnByRawSn(rawSn)).thenReturn(Optional.ofNullable(deIdntfYn));
    }

    /** 잠금 조회 스텁 — 실제 RAW 행(잠금 시점 값). */
    private void stubLockedRow(Long rawSn, String deIdntfYn) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "LOCK-" + rawSn, "CCTV", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4", LocalDateTime.now(), 30);
        setField(raw, "rawSn", rawSn);
        raw.markDeidentified(deIdntfYn);
        when(videoRepository.findByRawSnForUpdate(rawSn)).thenReturn(Optional.of(raw));
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

    @Test
    @DisplayName("자기행이_F면_차단된다")
    void blocksWhenSelfReported() {
        stubDeidentYn(910L, "F");

        assertThat(gate.isUnderDeidentReport(910L)).isTrue();
    }

    @Test
    @DisplayName("Y_N_null_행부재는_모두_통과한다 — 일반 영상 흐름 회귀 방어")
    void passesForNormalStates() {
        stubDeidentYn(911L, "Y");
        stubDeidentYn(912L, "N");
        stubDeidentYn(913L, null);
        when(videoRepository.findDeIdntfYnByRawSn(914L)).thenReturn(Optional.empty());

        assertThat(gate.isUnderDeidentReport(911L)).isFalse();
        assertThat(gate.isUnderDeidentReport(912L)).isFalse();
        assertThat(gate.isUnderDeidentReport(913L)).isFalse();
        assertThat(gate.isUnderDeidentReport(914L)).isFalse();
        assertThat(gate.isUnderDeidentReport(null)).isFalse();
    }

    @Test
    @DisplayName("★원본이_신고중이어도_파생영상은_막히지_않는다 — 파생은 신고 체계 바깥(확정 정책)")
    void doesNotPropagateFromOriginToDerivative() {
        // given — 부모(920)는 신고 'F', 파생(921)은 자기 행 'Y'.
        stubDeidentYn(921L, "Y");

        // when / then — 파생은 통과하며, 부모 행을 조회조차 하지 않는다(조상 순회 철회 회귀 고정).
        assertThat(gate.isUnderDeidentReport(921L)).isFalse();
        verify(videoRepository, never()).findDeIdntfYnByRawSn(920L);
    }

    @Test
    @DisplayName("잠금판정도_자기행만_잠그고_그_값으로_판정한다")
    void lockedJudgementUsesSelfRowOnly() {
        stubLockedRow(930L, "F");
        stubLockedRow(931L, "Y");

        assertThat(gate.isUnderDeidentReportLocked(930L)).isTrue();
        assertThat(gate.isUnderDeidentReportLocked(931L)).isFalse();
        verify(videoRepository).findByRawSnForUpdate(930L);
        verify(videoRepository).findByRawSnForUpdate(931L);
    }

    @Test
    @DisplayName("잠금판정_행부재는_통과_rawSn_null도_통과")
    void lockedJudgementPassesWhenRowMissing() {
        when(videoRepository.findByRawSnForUpdate(932L)).thenReturn(Optional.empty());

        assertThat(gate.isUnderDeidentReportLocked(932L)).isFalse();
        assertThat(gate.isUnderDeidentReportLocked(null)).isFalse();
    }
}
