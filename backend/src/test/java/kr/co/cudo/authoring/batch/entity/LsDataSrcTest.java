package kr.co.cudo.authoring.batch.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LsDataSrc 팩토리 하위호환 + videoFrameNo 보존 단위 테스트.
 * VDO_FRM_NO(실제 영상 프레임 위치) 추가에 따른 기존 4인자 create 무영향 검증.
 */
class LsDataSrcTest {

    @Test
    @DisplayName("기존_create_팩토리는_videoFrameNo가_null로_생성된다")
    void 기존_create_팩토리는_videoFrameNo가_null로_생성된다() {
        // given
        Long rawSn = 100L;
        int frameNo = 3;
        String path = "/frames/raw/100/000003.jpg";
        LocalDateTime shtDt = LocalDateTime.of(2026, 6, 29, 10, 0, 0);

        // when
        LsDataSrc src = LsDataSrc.create(rawSn, frameNo, path, shtDt);

        // then
        assertThat(src.getRawSn()).isEqualTo(rawSn);
        assertThat(src.getFrameNo()).isEqualTo(frameNo);
        assertThat(src.getSrcFilePathNm()).isEqualTo(path);
        assertThat(src.getVideoFrameNo()).isNull();
    }

    @Test
    @DisplayName("파생_팩토리는_부모_개인정보_3필드를_복사한다")
    void 파생_팩토리는_부모_개인정보_3필드를_복사한다() {
        // given — 부모 개인정보 값을 파생 프레임 생성 시 전달
        // when
        LsDataSrc derived = LsDataSrc.create(300L, 2, 500L, "/frames/raw/300/2.jpg",
                "/frames/deid/300/2.jpg", LocalDateTime.now(), "Y", "N", "Y");

        // then
        assertThat(derived.getAnonyInclYn()).isEqualTo("Y");
        assertThat(derived.getPsdoInclYn()).isEqualTo("N");
        assertThat(derived.getPrvcInclYn()).isEqualTo("Y");
        assertThat(derived.getDeidFilePath()).isEqualTo("/frames/deid/300/2.jpg");
    }

    @Test
    @DisplayName("부모_미입력이면_파생도_null로_시작한다")
    void 부모_미입력이면_파생도_null() {
        // when — 부모 3필드 null(미입력)
        LsDataSrc derived = LsDataSrc.create(300L, 2, 500L, "/frames/raw/300/2.jpg",
                "/frames/deid/300/2.jpg", LocalDateTime.now(), null, null, null);

        // then — 파생도 null → 파생 폴백 적용
        assertThat(derived.getAnonyInclYn()).isNull();
        assertThat(derived.getPsdoInclYn()).isNull();
        assertThat(derived.getPrvcInclYn()).isNull();
    }

    @Test
    @DisplayName("updatePrivacyMeta는_blank를_null로_정규화한다")
    void updatePrivacyMeta_blank_null() {
        // given
        LsDataSrc src = LsDataSrc.create(100L, 0, "/raw/0.jpg", LocalDateTime.now());
        src.updatePrivacyMeta("Y", "N", "Y");

        // when — blank/null 로 재설정
        src.updatePrivacyMeta("  ", null, "N");

        // then — blank→null, null→null, 값은 값
        assertThat(src.getAnonyInclYn()).isNull();
        assertThat(src.getPsdoInclYn()).isNull();
        assertThat(src.getPrvcInclYn()).isEqualTo("N");
    }

    @Test
    @DisplayName("videoFrameNo_받는_팩토리는_해당_값을_보존한다")
    void videoFrameNo_받는_팩토리는_해당_값을_보존한다() {
        // given
        Long rawSn = 200L;
        int frameNo = 5;            // 추출 순번
        Long videoFrameNo = 1250L; // 실제 영상 내 디코더 프레임 위치
        String path = "/frames/raw/200/000005.jpg";
        LocalDateTime shtDt = LocalDateTime.of(2026, 6, 29, 11, 0, 0);

        // when
        LsDataSrc src = LsDataSrc.create(rawSn, frameNo, videoFrameNo, path, shtDt);

        // then
        assertThat(src.getFrameNo()).isEqualTo(frameNo);
        assertThat(src.getVideoFrameNo()).isEqualTo(videoFrameNo);
        assertThat(src.getSrcFilePathNm()).isEqualTo(path);
    }
}
