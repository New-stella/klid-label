package kr.co.cudo.authoring.portal.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 포털 업로드 엔티티 상태 전이 도메인 메서드 단위 테스트(DB 불필요).
 * setter 없이 의미 있는 메서드로만 상태가 바뀌는지 검증한다.
 */
class LsPortalUldTest {

    @Test
    @DisplayName("상태전이_메서드_동작_UPLOADED에서_PROCESSING_READY_및_FAILED")
    void stateTransitions_work() {
        // given — 영상 업로드 생성(초기 UPLOADED)
        LsPortalUld video = LsPortalUld.createVideo(
                "portal-user-1", "clip.mp4", "/portal/1/clip.mp4", 1_000L, "video/mp4");
        assertThat(video.getUldSttsCd()).isEqualTo(LsPortalUld.STTS_UPLOADED);
        assertThat(video.getUldTypeCd()).isEqualTo(LsPortalUld.TYPE_VIDEO);

        // when/then — 처리 시작
        video.markProcessing();
        assertThat(video.getUldSttsCd()).isEqualTo(LsPortalUld.STTS_PROCESSING);

        // when/then — 완료(길이/FPS/프레임 수 기록)
        video.markReady(30.0, 25.0, 150);
        assertThat(video.getUldSttsCd()).isEqualTo(LsPortalUld.STTS_READY);
        assertThat(video.getVdoLenSec()).isEqualTo(30.0);
        assertThat(video.getFps()).isEqualTo(25.0);
        assertThat(video.getFrmeCnt()).isEqualTo(150);
        assertThat(video.getFailRsnCn()).isNull();
    }

    @Test
    @DisplayName("상태전이_실패시_사유_기록되고_FAILED로_전이")
    void markFailed_recordsReason() {
        // given
        LsPortalUld image = LsPortalUld.createImage(
                "portal-user-1", "a.jpg", "/portal/1/a.jpg", 500L, "image/jpeg");
        assertThat(image.getUldTypeCd()).isEqualTo(LsPortalUld.TYPE_IMAGE);

        // when
        image.markFailed("프레임 추출 실패");

        // then
        assertThat(image.getUldSttsCd()).isEqualTo(LsPortalUld.STTS_FAILED);
        assertThat(image.getFailRsnCn()).isEqualTo("프레임 추출 실패");
    }
}
