package kr.co.cudo.authoring.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.portal.service.PortalExtractionPlan;
import kr.co.cudo.authoring.portal.upload.PortalUploadAsset;
import kr.co.cudo.authoring.portal.service.PortalFrameExtractTxService;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadFrameRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 포털 프레임 추출 트랜잭션 서비스 단위 테스트 — 조건부 완료/실패 전이와 하트비트가 리포지토리의
 * 조건부 갱신으로 위임되는지 검증한다.
 *
 * <h3>흡수(ADR-058) 뒤 달라진 점</h3>
 * <p>상태가 컬럼이 아니라 메타 원장의 키·값에 산다. 그래서 전이는 <b>문장 종류가 갈린다</b> —
 * 완료·실패·방치 전이가 모두 <b>단순 UPDATE</b> 다(상태 행이 없는 자산 = 마킹 대기 자산을 건드리지
 * 않는다). 이 시험은 그 위임을 고정한다.
 */
class PortalFrameExtractTxServiceTest {

    private static final long ULD_SN = 555L;

    private PortalUploadAssetRepository assetRepository;
    private PortalUploadFrameRepository frmeRepository;
    private LsMarkingRepository markingRepository;
    private PortalFrameExtractTxService txService;

    @BeforeEach
    void setUp() {
        assetRepository = mock(PortalUploadAssetRepository.class);
        frmeRepository = mock(PortalUploadFrameRepository.class);
        markingRepository = mock(LsMarkingRepository.class);
        txService = new PortalFrameExtractTxService(assetRepository, frmeRepository,
                markingRepository, new ObjectMapper());
    }

    private PortalUploadAsset asset(String status) {
        return new PortalUploadAsset(ULD_SN, "u1", PortalUploadLedger.TYPE_VIDEO, "v.mp4",
                "/p/v.mp4", 1024L, "video/mp4", status, 10.0, 30.0, 0, null,
                LocalDateTime.now(), LocalDateTime.now());
    }

    private void givenMarking(String marksJson) {
        LsMarking marking = LsMarking.createManual(ULD_SN, marksJson, "u1", 30.0, null);
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(ULD_SN))
                .thenReturn(List.of(marking));
    }

    // ======================== 추출 계획 확정 ========================

    /**
     * ★ 되돌림 실증 — 러너 진입이 상태를 <b>전이시키지 않는다</b>(2026-09-02 순서 반전).
     *
     * <p>전이는 마킹 저장이 이미 원자적으로 했다. 여기서 다시 전이시키면 이미 추출 중이라 0행이 되어
     * 정상 추출이 통째로 중단된다.
     */
    @Test
    @DisplayName("★추출_진입은_상태를_전이시키지_않고_마킹_지점을_돌려준다")
    void beginExtractionReturnsMarkedFramesWithoutTransition() {
        when(assetRepository.findPortalAsset(ULD_SN))
                .thenReturn(Optional.of(asset(PortalUploadLedger.STATUS_PROCESSING)));
        givenMarking("[{\"frameIndex\":300,\"timestamp\":\"00:10\"},"
                + "{\"frameIndex\":0,\"timestamp\":\"00:00\"}]");

        Optional<PortalExtractionPlan> plan = txService.beginExtraction(ULD_SN);

        assertThat(plan).isPresent();
        // 오름차순·중복 제거로 정규화된다(추출 순서 = 영상 시간 순).
        assertThat(plan.get().frameNumbers()).containsExactly(0, 300);
        verify(assetRepository, never()).transitionToProcessing(ULD_SN);
    }

    @Test
    @DisplayName("추출_중이_아닌_자산은_추출하지_않는다")
    void beginExtractionSkipsWhenNotProcessing() {
        when(assetRepository.findPortalAsset(ULD_SN))
                .thenReturn(Optional.of(asset(PortalUploadLedger.STATUS_UPLOADED)));

        assertThat(txService.beginExtraction(ULD_SN)).isEmpty();
    }

    /**
     * ★ 마킹이 없으면 <b>고정 간격으로 대신 채우지 않는다</b> — 그러면 사용자가 고르지 않은 지점이
     * 뽑혀 「마킹 지점으로만 뽑는다」가 깨진다.
     */
    @Test
    @DisplayName("★마킹이_없으면_추출하지_않는다_고정간격_대체_금지")
    void beginExtractionSkipsWhenNoMarking() {
        when(assetRepository.findPortalAsset(ULD_SN))
                .thenReturn(Optional.of(asset(PortalUploadLedger.STATUS_PROCESSING)));
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(ULD_SN))
                .thenReturn(List.of());

        assertThat(txService.beginExtraction(ULD_SN)).isEmpty();
    }

    @Test
    @DisplayName("완료_조건부_전이_1행이면_프레임_저장하고_true")
    void completeReadySavesFramesWhenTransitioned() {
        when(assetRepository.transitionToReady(ULD_SN)).thenReturn(1);
        List<LsDataSrc> frames = List.of(LsDataSrc.create(ULD_SN, 0L, "/p/0.jpg", null));

        boolean ready = txService.completeReady(ULD_SN, frames, 10.0, 30.0);

        assertThat(ready).isTrue();
        verify(frmeRepository).saveAll(anyList());
        // 프레임 수는 <보관하지 않는다>(ERD-028) — 길이·프레임률만 적재한다.
        verify(assetRepository).applyVideoDuration(ULD_SN, 10.0);
        verify(assetRepository).upsertMeta(ULD_SN, PortalUploadLedger.KEY_FPS, "30.0");
        // 성공 전이 뒤에 남은 실패 사유는 거짓말이 된다 — 값을 비우는 대신 행을 지운다.
        verify(assetRepository).deleteMeta(ULD_SN, PortalUploadLedger.KEY_FAIL_REASON);
    }

    @Test
    @DisplayName("완료_조건부_전이_0행이면_프레임_미저장하고_false")
    void completeReadySkipsFramesWhenNotProcessing() {
        // 스윕이 이미 실패로 마감한 자산 → 0행 → 부활 금지, 프레임 고아 INSERT 방지.
        when(assetRepository.transitionToReady(ULD_SN)).thenReturn(0);
        List<LsDataSrc> frames = List.of(LsDataSrc.create(ULD_SN, 0L, "/p/0.jpg", null));

        boolean ready = txService.completeReady(ULD_SN, frames, 10.0, 30.0);

        assertThat(ready).isFalse();
        verify(frmeRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("★실패_전이는_후처리_중에서만_출발한다 — 상태_행이_없는_자산을_실패로_만들지_않는다")
    void markFailedDelegatesConditionalUpdate() {
        when(assetRepository.failFromProcessing(ULD_SN)).thenReturn(1);

        txService.markFailed(ULD_SN, "실패");

        // 삽입 겸 갱신(failStuck)이 아니라 단순 UPDATE(failFromProcessing)여야 한다 —
        // 삽입 겸 갱신으로 쓰면 상태 행이 없는 자산(= 업로드됨)이 곧바로 실패가 된다.
        verify(assetRepository).failFromProcessing(ULD_SN);
        verify(assetRepository, never()).failStuck(ULD_SN);
        verify(assetRepository).upsertMeta(eq(ULD_SN),
                eq(PortalUploadLedger.KEY_FAIL_REASON), contains("실패"));
    }

    @Test
    @DisplayName("★전이에_실패하면_사유도_남기지_않는다 — 이미_다른_상태인_자산에_사유만_덧씌워지지_않게")
    void markFailedSkipsReasonWhenTransitionLost() {
        when(assetRepository.failFromProcessing(ULD_SN)).thenReturn(0);

        txService.markFailed(ULD_SN, "실패");

        verify(assetRepository, never())
                .upsertMeta(eq(ULD_SN), eq(PortalUploadLedger.KEY_FAIL_REASON), anyString());
    }

    @Test
    @DisplayName("하트비트_1행이면_true_0행이면_false")
    void touchProcessingReflectsAffectedRows() {
        when(assetRepository.touchProcessing(ULD_SN)).thenReturn(1);
        assertThat(txService.touchProcessing(ULD_SN)).isTrue();

        when(assetRepository.touchProcessing(ULD_SN)).thenReturn(0);
        assertThat(txService.touchProcessing(ULD_SN)).isFalse();
    }
}
