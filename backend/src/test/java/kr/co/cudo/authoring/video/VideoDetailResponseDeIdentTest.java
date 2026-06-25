package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.video.dto.VideoDetailResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

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

    @Test
    @DisplayName("검수상태_미전달_시_reviewSttsCd_는_null_이다")
    void reviewSttsCd_notProvided_isNull() {
        // given — 기존 오버로드(검수상태 인자 없음)
        LsDataRaw entity = raw();

        // when
        VideoDetailResponse response = VideoDetailResponse.from(entity, "카메라", "지자체", 5L);

        // then — status(배치단계)는 채워지나 검수상태는 별도 필드로 null
        assertThat(response.reviewSttsCd()).isNull();
        assertThat(response.status()).isEqualTo(entity.getDataSttsCd());
    }

    @Test
    @DisplayName("검수완료_APPROVED_전달_시_status_와_별개로_reviewSttsCd_에_노출된다")
    void reviewSttsCd_approved_isExposedSeparatelyFromStatus() {
        // given — 배치단계 status 와 무관하게 검수완료(APPROVED) 를 전달
        LsDataRaw entity = raw();

        // when
        VideoDetailResponse response = VideoDetailResponse.from(
                entity, "카메라", "지자체", 5L, Collections.emptyList(), "APPROVED");

        // then — 배치단계(status)는 entity 값 그대로, 검수상태(reviewSttsCd)는 APPROVED 로 분리 노출
        assertThat(response.reviewSttsCd()).isEqualTo("APPROVED");
        assertThat(response.status()).isEqualTo(entity.getDataSttsCd());
        assertThat(response.status()).isNotEqualTo("APPROVED");
    }
}
