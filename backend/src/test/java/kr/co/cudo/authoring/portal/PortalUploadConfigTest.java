package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V107 포털 업로드 설정 검증 — ① 시스템 설정 프레임 간격 키(ConfigKeys 등록 + V107 시드)가
 * SystemConfigService.getInt 로 조회되고 기본값 5 인지, ② PortalUploadProperties 바인딩.
 */
@SpringBootTest
@ActiveProfiles("local")
class PortalUploadConfigTest {

    @Autowired private SystemConfigService systemConfigService;
    @Autowired private PortalUploadProperties portalUploadProperties;

    @Test
    @DisplayName("시스템설정_프레임간격_기본값_조회")
    void frameIntervalDefault() {
        assertThat(ConfigKeys.ALLOWED).contains(ConfigKeys.PORTAL_UPLOAD_FRAME_INTERVAL_SEC);
        assertThat(ConfigKeys.NUMBER_RANGE.get(ConfigKeys.PORTAL_UPLOAD_FRAME_INTERVAL_SEC))
                .containsExactly(1, 600);

        Integer interval = systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_FRAME_INTERVAL_SEC);
        assertThat(interval).isEqualTo(5);
    }

    @Test
    @DisplayName("포털업로드_프로퍼티_이미지정책_바인딩")
    void imagePropertiesBound() {
        assertThat(portalUploadProperties.allowedImageExtensions())
                .containsExactlyInAnyOrder("jpg", "jpeg", "png");
        assertThat(portalUploadProperties.maxImageSizeBytes()).isEqualTo(20_971_520L);
        assertThat(portalUploadProperties.maxImagesPerRequest()).isEqualTo(50);
        assertThat(portalUploadProperties.maxFrames()).isEqualTo(2000);
    }
}
