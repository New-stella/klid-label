package kr.co.cudo.authoring.portal.config;

import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 포털 업로드 프레임 간격 설정 키(V109 시드)와 프로퍼티 바인딩 통합 테스트.
 */
@SpringBootTest
@ActiveProfiles("local")
class PortalUploadFrameIntervalConfigIT {

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private PortalUploadProperties uploadProperties;

    @Test
    @DisplayName("시스템설정_프레임간격_기본값_조회")
    void frameIntervalDefault_isFive() {
        Integer interval = systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_FRAME_INTERVAL_SEC);
        assertThat(interval).isEqualTo(5);
    }

    @Test
    @DisplayName("포털업로드_프로퍼티_이미지정책_바인딩됨")
    void uploadProperties_bindsImagePolicy() {
        assertThat(uploadProperties.allowedImageExtensions())
                .containsExactlyInAnyOrder("jpg", "jpeg", "png");
        assertThat(uploadProperties.maxImageSizeBytes()).isEqualTo(20_971_520L);
        assertThat(uploadProperties.maxImagesPerRequest()).isEqualTo(50);
        assertThat(uploadProperties.maxFrames()).isEqualTo(2000);
        assertThat(uploadProperties.allowedExtensions())
                .containsExactlyInAnyOrder("mp4", "mov", "avi");
    }
}
