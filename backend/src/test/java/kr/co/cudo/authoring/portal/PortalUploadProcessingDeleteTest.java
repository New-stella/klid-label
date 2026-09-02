package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.service.PortalRetentionPolicy;
import kr.co.cudo.authoring.portal.service.PortalStoragePathGuard;
import kr.co.cudo.authoring.portal.service.PortalUploadService;
import kr.co.cudo.authoring.portal.upload.PortalUploadAsset;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadFrameRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 후처리 진행 중인 자산은 삭제할 수 없다(409) — 러너가 쓰는 파일·행과 경합하지 않기 위한 가드.
 */
class PortalUploadProcessingDeleteTest {

    private static final String OWNER = "portal-user-1";
    private static final long ULD_SN = 42L;

    @Test
    @DisplayName("후처리_중_자산_삭제시_409이고_파일_수집조차_하지_않는다")
    void deleteProcessingConflict() {
        PortalUploadAssetRepository assetRepository = mock(PortalUploadAssetRepository.class);
        PortalUploadFrameRepository frmeRepository = mock(PortalUploadFrameRepository.class);
        PortalUploadProperties props = new PortalUploadProperties(
                5_368_709_120L, List.of("mp4"), "./storage/raw/portal",
                List.of("jpg"), 20_971_520L, 50, 2000,
                16_777_216L, 2_097_152L, 30L, 30L);
        PortalUploadService service = new PortalUploadService(assetRepository, frmeRepository, props,
                new PortalRetentionPolicy(mock(SystemConfigService.class)),
                new PortalStoragePathGuard(props));

        when(assetRepository.findByOwner(ULD_SN, OWNER))
                .thenReturn(Optional.of(processingAsset()));

        assertThatThrownBy(() -> service.deleteUpload(ULD_SN, OWNER))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // 파일/행 삭제로 진입하지 않음 — 러너와의 경합 차단.
        verify(assetRepository, never()).deleteOwned(anyLong(), anyString());
        verify(assetRepository, never()).findFilePaths(anyLong());
    }

    private static PortalUploadAsset processingAsset() {
        return new PortalUploadAsset(ULD_SN, OWNER, PortalUploadLedger.TYPE_VIDEO,
                "v.mp4", "/p/v.mp4", 1024L, "video/mp4",
                PortalUploadLedger.STATUS_PROCESSING, null, null, 0, null,
                LocalDateTime.now(), LocalDateTime.now());
    }
}
