package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.portal.service.PortalUploadSweepTxService;
import kr.co.cudo.authoring.portal.upload.PortalTusSessionRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import kr.co.cudo.authoring.upload.entity.LsTusUpload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 포털 업로드 스윕 <b>트랜잭션 경계 서비스</b> 단위 테스트 — 조건부 삭제/전이의 소유 획득 판정
 * (1행=소유, 0행=타노드 선점)만 검증한다. 실 트랜잭션 동작은 {@code PortalUploadSweepIT} 가 본다.
 */
class PortalUploadSweepTxServiceTest {

    private PortalTusSessionRepository tusRepository;
    private PortalUploadAssetRepository assetRepository;
    private PortalUploadSweepTxService txService;

    @BeforeEach
    void setUp() {
        tusRepository = mock(PortalTusSessionRepository.class);
        assetRepository = mock(PortalUploadAssetRepository.class);
        txService = new PortalUploadSweepTxService(tusRepository, assetRepository);
    }

    @Test
    @DisplayName("소유_획득한_만료세션만_임시파일_경로_반환")
    void returnsFilePathsForClaimedSessionsOnly() {
        LsTusUpload claimed = LsTusUpload.createPortalSession(
                UUID.randomUUID(), "u1", 100L, "/store/claimed.mp4", "a.mp4");
        LsTusUpload lost = LsTusUpload.createPortalSession(
                UUID.randomUUID(), "u2", 100L, "/store/lost.mp4", "b.mp4");
        when(tusRepository.findExpired(any(LocalDateTime.class))).thenReturn(List.of(claimed, lost));
        // claimed 는 이 노드가 삭제(1행), lost 는 타노드가 선점(0행) — 멱등.
        when(tusRepository.deleteExpiredInProgress(claimed.getUploadId())).thenReturn(1);
        when(tusRepository.deleteExpiredInProgress(lost.getUploadId())).thenReturn(0);

        List<String> paths = txService.claimExpiredSessions();

        assertThat(paths).containsExactly("/store/claimed.mp4");
    }

    @Test
    @DisplayName("만료세션_없으면_빈목록")
    void emptyWhenNoExpiredSessions() {
        when(tusRepository.findExpired(any(LocalDateTime.class))).thenReturn(List.of());

        assertThat(txService.claimExpiredSessions()).isEmpty();
    }

    @Test
    @DisplayName("★방치_전이는_삽입_겸_갱신이다 — 상태_행이_없는_자산도_출발점이기_때문")
    void returnsUldSnForFailedTransitionsOnly() {
        when(assetRepository.findStuck(any(LocalDateTime.class))).thenReturn(List.of(42L, 7L));
        // 42 는 이 노드가 전이(1행), 7 은 타노드가 선점(0행).
        when(assetRepository.failStuck(42L)).thenReturn(1);
        when(assetRepository.failStuck(7L)).thenReturn(0);

        List<Long> failed = txService.failStuckUploads(30L);

        assertThat(failed).containsExactly(42L);
        // 출발 상태 집합이 {업로드됨, 후처리 중}이고 업로드됨은 <상태 행 부재>로도 표현되므로,
        // 단순 UPDATE(failFromProcessing)로는 그 자산을 영영 집지 못한다.
        verify(assetRepository).failStuck(42L);
        verify(assetRepository, never()).failFromProcessing(42L);
    }

    @Test
    @DisplayName("★전이에_성공한_자산에만_실패_사유를_남긴다")
    void writesReasonOnlyForClaimedTransitions() {
        when(assetRepository.findStuck(any(LocalDateTime.class))).thenReturn(List.of(42L, 7L));
        when(assetRepository.failStuck(42L)).thenReturn(1);
        when(assetRepository.failStuck(7L)).thenReturn(0);

        txService.failStuckUploads(30L);

        verify(assetRepository).upsertMeta(eq(42L),
                eq(PortalUploadLedger.KEY_FAIL_REASON), anyString());
        verify(assetRepository, never()).upsertMeta(eq(7L),
                eq(PortalUploadLedger.KEY_FAIL_REASON), anyString());
    }
}
