package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.portal.entity.LsPortalUld;
import kr.co.cudo.authoring.portal.entity.LsPortalUldFrme;
import kr.co.cudo.authoring.portal.repository.LsPortalUldFrmeRepository;
import kr.co.cudo.authoring.portal.repository.LsPortalUldRepository;
import kr.co.cudo.authoring.portal.service.PortalFrameExtractTxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 포털 프레임 추출 트랜잭션 서비스 단위 테스트 — 조건부 READY/FAILED 전이(adversarial #1)와
 * 하트비트(adversarial #2)가 리포지토리 조건부 UPDATE 로 위임되는지 검증.
 */
class PortalFrameExtractTxServiceTest {

    private static final long ULD_SN = 555L;

    private LsPortalUldRepository uldRepository;
    private LsPortalUldFrmeRepository frmeRepository;
    private PortalFrameExtractTxService txService;

    @BeforeEach
    void setUp() {
        uldRepository = mock(LsPortalUldRepository.class);
        frmeRepository = mock(LsPortalUldFrmeRepository.class);
        txService = new PortalFrameExtractTxService(uldRepository, frmeRepository);
    }

    @Test
    @DisplayName("READY_조건부_전이_1행이면_프레임_저장하고_true")
    void completeReadySavesFramesWhenTransitioned() {
        when(uldRepository.transitionToReady(eq(ULD_SN), any(), any(), eq(1), any(LocalDateTime.class)))
                .thenReturn(1);
        List<LsPortalUldFrme> frames = List.of(LsPortalUldFrme.create(ULD_SN, 0, "/p/0.jpg"));

        boolean ready = txService.completeReady(ULD_SN, frames, 10.0, 30.0);

        assertThat(ready).isTrue();
        verify(frmeRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("READY_조건부_전이_0행이면_프레임_미저장하고_false")
    void completeReadySkipsFramesWhenNotProcessing() {
        // 스윕이 이미 FAILED 시킨 자산 → transitionToReady 0행 → 부활 금지, 프레임 고아 INSERT 방지.
        when(uldRepository.transitionToReady(eq(ULD_SN), any(), any(), any(), any(LocalDateTime.class)))
                .thenReturn(0);
        List<LsPortalUldFrme> frames = List.of(LsPortalUldFrme.create(ULD_SN, 0, "/p/0.jpg"));

        boolean ready = txService.completeReady(ULD_SN, frames, 10.0, 30.0);

        assertThat(ready).isFalse();
        verify(frmeRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("markFailed는_PROCESSING_상태집합_조건부_UPDATE로_위임")
    void markFailedDelegatesConditionalUpdate() {
        when(uldRepository.failIfInStatus(eq(ULD_SN), anyString(),
                eq(List.of(LsPortalUld.STTS_PROCESSING)), any(LocalDateTime.class))).thenReturn(1);

        txService.markFailed(ULD_SN, "실패");

        verify(uldRepository).failIfInStatus(eq(ULD_SN), anyString(),
                eq(List.of(LsPortalUld.STTS_PROCESSING)), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("touchProcessing_1행이면_true_0행이면_false")
    void touchProcessingReflectsAffectedRows() {
        when(uldRepository.touchProcessing(eq(ULD_SN), any(LocalDateTime.class))).thenReturn(1);
        assertThat(txService.touchProcessing(ULD_SN)).isTrue();

        when(uldRepository.touchProcessing(eq(ULD_SN), any(LocalDateTime.class))).thenReturn(0);
        assertThat(txService.touchProcessing(ULD_SN)).isFalse();
    }
}
