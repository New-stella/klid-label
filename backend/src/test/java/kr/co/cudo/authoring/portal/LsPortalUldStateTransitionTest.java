package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.portal.entity.LsPortalUld;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V107 LS_PORTAL_ULD 상태 전이 메서드(순수 단위) — {@code @Setter} 없이 의미 있는
 * 메서드로만 상태가 바뀌는지 검증.
 */
class LsPortalUldStateTransitionTest {

    @Test
    @DisplayName("상태전이_메서드_동작_UPLOADED_PROCESSING_READY")
    void transitionToReady() {
        LsPortalUld uld = LsPortalUld.createVideo("u1", "v.mp4", "/p/v.mp4", 100L, "video/mp4");
        assertThat(uld.getUldSttsCd()).isEqualTo(LsPortalUld.STTS_UPLOADED);

        uld.markProcessing();
        assertThat(uld.getUldSttsCd()).isEqualTo(LsPortalUld.STTS_PROCESSING);

        uld.markReady(30.0, 25.0, 6);
        assertThat(uld.getUldSttsCd()).isEqualTo(LsPortalUld.STTS_READY);
        assertThat(uld.getVdoLenSec()).isEqualTo(30.0);
        assertThat(uld.getFps()).isEqualTo(25.0);
        assertThat(uld.getFrmeCnt()).isEqualTo(6);
        assertThat(uld.getFailRsnCn()).isNull();
    }

    @Test
    @DisplayName("상태전이_메서드_동작_FAILED_사유기록")
    void transitionToFailed() {
        LsPortalUld uld = LsPortalUld.createImage("u1", "a.jpg", "/p/a.jpg", 10L, "image/jpeg");
        uld.markProcessing();
        uld.markFailed("프레임 추출 실패");

        assertThat(uld.getUldSttsCd()).isEqualTo(LsPortalUld.STTS_FAILED);
        assertThat(uld.getFailRsnCn()).isEqualTo("프레임 추출 실패");
    }
}
