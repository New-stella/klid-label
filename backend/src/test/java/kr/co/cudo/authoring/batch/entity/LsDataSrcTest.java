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
    @DisplayName("videoFrameNo_받는_팩토리는_해당_값을_보존한다")
    void videoFrameNo_받는_팩토리는_해당_값을_보존한다() {
        // given
        Long rawSn = 200L;
        int frameNo = 5;            // 추출 순번
        Integer videoFrameNo = 1250; // 실제 영상 내 디코더 프레임 위치
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
