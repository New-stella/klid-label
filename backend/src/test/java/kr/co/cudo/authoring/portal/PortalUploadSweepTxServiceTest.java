package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.portal.entity.LsPortalTusUpload;
import kr.co.cudo.authoring.portal.entity.LsPortalUld;
import kr.co.cudo.authoring.portal.repository.LsPortalTusUploadRepository;
import kr.co.cudo.authoring.portal.repository.LsPortalUldRepository;
import kr.co.cudo.authoring.portal.service.PortalUploadSweepTxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 포털 업로드 스윕 <b>트랜잭션 경계 서비스</b> 단위 테스트 — 조건부 벌크 삭제/전이의 소유 획득 판정
 * (1행=소유, 0행=타노드 선점) 로직만 검증. 실 트랜잭션 동작은 {@code PortalUploadSweepIT} 가 검증.
 */
class PortalUploadSweepTxServiceTest {

    private LsPortalTusUploadRepository tusRepository;
    private LsPortalUldRepository uldRepository;
    private PortalUploadSweepTxService txService;

    @BeforeEach
    void setUp() {
        tusRepository = mock(LsPortalTusUploadRepository.class);
        uldRepository = mock(LsPortalUldRepository.class);
        txService = new PortalUploadSweepTxService(tusRepository, uldRepository);
    }

    @Test
    @DisplayName("소유_획득한_만료세션만_임시파일_경로_반환")
    void returnsFilePathsForClaimedSessionsOnly() {
        LsPortalTusUpload claimed = LsPortalTusUpload.create(
                UUID.randomUUID(), "u1", 100L, "/store/claimed.mp4", "a.mp4");
        LsPortalTusUpload lost = LsPortalTusUpload.create(
                UUID.randomUUID(), "u2", 100L, "/store/lost.mp4", "b.mp4");
        when(tusRepository.findExpired(any(LocalDateTime.class)))
                .thenReturn(List.of(claimed, lost));
        // claimed 는 이 노드가 삭제(1행), lost 는 타노드가 선점(0행) — 멱등.
        when(tusRepository.deleteExpiredInProgress(claimed.getUldId())).thenReturn(1);
        when(tusRepository.deleteExpiredInProgress(lost.getUldId())).thenReturn(0);

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
    @DisplayName("FAILED_전이_성공한_고착자산_uldSn만_반환")
    void returnsUldSnForFailedTransitionsOnly() throws Exception {
        LsPortalUld claimed = stuckUld(42L);
        LsPortalUld lost = stuckUld(7L);
        when(uldRepository.findStuck(anyList(), any(LocalDateTime.class)))
                .thenReturn(List.of(claimed, lost));
        // 42 는 이 노드가 전이(1행), 7 은 타노드가 선점(0행).
        when(uldRepository.failIfInStatus(eq(42L), anyString(), anyList(), any(LocalDateTime.class)))
                .thenReturn(1);
        when(uldRepository.failIfInStatus(eq(7L), anyString(), anyList(), any(LocalDateTime.class)))
                .thenReturn(0);

        List<Long> failed = txService.failStuckUploads(30L);

        assertThat(failed).containsExactly(42L);
    }

    private static LsPortalUld stuckUld(long uldSn) throws Exception {
        LsPortalUld uld = LsPortalUld.createVideo("u1", "v.mp4", "/p/v.mp4", 1024L, "video/mp4");
        Field f = LsPortalUld.class.getDeclaredField("uldSn");
        f.setAccessible(true);
        f.set(uld, uldSn);
        return uld;
    }
}
