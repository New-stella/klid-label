package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.video.dto.VideoDetailResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 영상 상세 응답(VideoDetailResponse)에 비식별 여부(deIdntfYn)가 노출되는지 검증.
 *
 * <p>FE 재비식별 버튼이 "APPROVED && deIdntfYn != 'Y'" 조건으로 노출되려면 상세 응답에
 * 비식별 여부가 LS_DATA_RAW.DE_IDENT_YN 값 그대로 실려야 한다(additive, 하위호환).
 */
class VideoDetailResponseDeIdentTest {

    private LsDataRaw raw() {
        return LsDataRaw.createFromIngest("clip-1", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "raw/path.mp4", null, 60);
    }

    @Test
    @DisplayName("비식별_미수행_N_영상은_상세응답_deIdntfYn_이_N_이다")
    void deIdent_notProcessed_returnsN() {
        // given — 신규 적재 영상은 DE_IDENT_YN='N'
        LsDataRaw entity = raw();

        // when
        VideoDetailResponse response = VideoDetailResponse.from(entity);

        // then
        assertThat(response.deIdntfYn()).isEqualTo("N");
    }

    @Test
    @DisplayName("비식별_완료_Y_영상은_상세응답_deIdntfYn_이_Y_이다")
    void deIdent_completed_returnsY() {
        // given — 비식별 완료 처리(DE_IDENT_YN='Y')
        LsDataRaw entity = raw();
        entity.markDeidentified("Y");

        // when
        VideoDetailResponse response = VideoDetailResponse.from(entity, "카메라", "지자체", 5L);

        // then
        assertThat(response.deIdntfYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("비식별_실패_F_영상은_상세응답_deIdntfYn_이_F_이다")
    void deIdent_failed_returnsF() {
        // given — 비식별 실패/신고(DE_IDENT_YN='F')
        LsDataRaw entity = raw();
        entity.markDeidentified("F");

        // when
        VideoDetailResponse response = VideoDetailResponse.from(entity);

        // then
        assertThat(response.deIdntfYn()).isEqualTo("F");
    }
}
