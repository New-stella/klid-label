package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.entity.LsPortalUld;
import kr.co.cudo.authoring.portal.repository.LsPortalUldFrmeRepository;
import kr.co.cudo.authoring.portal.repository.LsPortalUldRepository;
import kr.co.cudo.authoring.portal.service.PortalUploadService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 시나리오 #12 — 프레임 추출 진행 중(PROCESSING) 자산은 삭제 불가(409) 검증.
 */
class PortalUploadProcessingDeleteTest {

    private static final String OWNER = "portal-user-1";
    private static final long ULD_SN = 42L;

    @Test
    @DisplayName("PROCESSING_자산_삭제시_409")
    void deleteProcessingConflict() throws Exception {
        LsPortalUldRepository uldRepository = mock(LsPortalUldRepository.class);
        LsPortalUldFrmeRepository frmeRepository = mock(LsPortalUldFrmeRepository.class);
        PortalUploadProperties props = new PortalUploadProperties(
                5_368_709_120L, List.of("mp4"), "./storage/raw/portal",
                List.of("jpg"), 20_971_520L, 50, 2000);
        PortalUploadService service = new PortalUploadService(uldRepository, frmeRepository, props);

        LsPortalUld uld = LsPortalUld.createVideo(OWNER, "v.mp4", "/p/v.mp4", 1024L, "video/mp4");
        setField(uld, "uldSttsCd", LsPortalUld.STTS_PROCESSING);
        when(uldRepository.findByUldSnAndPortalUserNo(ULD_SN, OWNER)).thenReturn(Optional.of(uld));

        assertThatThrownBy(() -> service.deleteUpload(ULD_SN, OWNER))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // 파일/행 삭제로 진입하지 않음 — 러너와의 경합 차단.
        verify(uldRepository, never()).delete(any());
        verify(frmeRepository, never()).findAllByUldSnOrderByFrmeNo(eq(ULD_SN));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
