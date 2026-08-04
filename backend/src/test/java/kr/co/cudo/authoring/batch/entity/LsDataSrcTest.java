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
    @DisplayName("부모_미입력이면_파생은_적재_기본값으로_시작한다 (구 기대 '파생도 null' 폐기 — 2026-08-04)")
    void 부모_미입력이면_파생도_null() {
        // ★ 구 기대 폐기 경위: 종전에는 부모가 null 이면 파생도 null 로 두고 <읽는 시점>에
        //   ExportPrivacyPolicy 프리필이 값을 채웠다. 2026-08-04 사용자 확정으로 비식별 3필드를
        //   <쓰는 시점>에 실제 값으로 적재하게 되면서, 부모 미입력(레거시 행·신고 리셋 직후)이면
        //   파생은 적재 기본값(Y/N/N)으로 시작한다. export 산출값은 프리필 상수와 같아 <파일 내용은
        //   달라지지 않는다>. 지우지 않고 기대만 정정해 경위를 남긴다.
        // when — 부모 3필드 null(미입력)
        LsDataSrc derived = LsDataSrc.create(300L, 2, 500L, "/frames/raw/300/2.jpg",
                "/frames/deid/300/2.jpg", LocalDateTime.now(), null, null, null);

        // then
        assertThat(derived.getAnonyInclYn()).isEqualTo("Y");
        assertThat(derived.getPsdoInclYn()).isEqualTo("N");
        assertThat(derived.getPrvcInclYn()).isEqualTo("N");
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

    // ------------------------------------------------------------ 비식별 축 적재 기본값 (2026-08-04)

    @Test
    @DisplayName("비식별_3필드는_적재_시점에_YNN_으로_채워진다")
    void 비식별_3필드는_적재_시점에_YNN_으로_채워진다() {
        // given / when — 프레임 생성 4종 팩토리 전부(누락 통로가 있으면 그 경로만 null 로 남는다)
        LsDataSrc a = LsDataSrc.create(1L, 0, "/f/0.jpg", LocalDateTime.now());
        LsDataSrc b = LsDataSrc.create(1L, 1, 10L, "/f/1.jpg", LocalDateTime.now());
        LsDataSrc c = LsDataSrc.create(1L, 2, 20L, "/f/2.jpg", "/d/2.jpg", LocalDateTime.now());

        // then — 값이 <실제로 INSERT> 된다(읽는 시점 프리필이 아니다).
        //   ★ DB 컬럼 DEFAULT 로 하지 않은 이유: 엔티티에 @DynamicInsert 가 없어 Hibernate 가 모든
        //     컬럼을 명시 INSERT(값 없으면 명시적 NULL)하므로 DEFAULT 가 적용될 수 없다.
        for (LsDataSrc src : new LsDataSrc[]{a, b, c}) {
            assertThat(src.getAnonyInclYn()).isEqualTo("Y");
            assertThat(src.getPsdoInclYn()).isEqualTo("N");
            assertThat(src.getPrvcInclYn()).isEqualTo("N");
        }
    }

    @Test
    @DisplayName("파생_프레임은_부모값을_계승하고_부모_미입력이면_적재_기본값을_쓴다")
    void 파생_프레임은_부모값을_계승하고_부모_미입력이면_적재_기본값을_쓴다() {
        // given / when — 부모가 사람 판정을 보유한 경우
        LsDataSrc inherited = LsDataSrc.create(2L, 0, 0L, "/f.jpg", "/d.jpg", LocalDateTime.now(),
                "N", "Y", "Y");
        // 부모가 미입력(레거시 행·신고 리셋 직후)인 경우
        LsDataSrc defaulted = LsDataSrc.create(2L, 1, 1L, "/f.jpg", "/d.jpg", LocalDateTime.now(),
                null, null, " ");

        // then
        assertThat(inherited.getAnonyInclYn()).isEqualTo("N");
        assertThat(inherited.getPsdoInclYn()).isEqualTo("Y");
        assertThat(inherited.getPrvcInclYn()).isEqualTo("Y");
        assertThat(defaulted.getAnonyInclYn()).isEqualTo("Y");
        assertThat(defaulted.getPsdoInclYn()).isEqualTo("N");
        assertThat(defaulted.getPrvcInclYn()).isEqualTo("N");
    }

    @Test
    @DisplayName("라벨링화면_수정이_적재값을_덮어쓴다")
    void 라벨링화면_수정이_적재값을_덮어쓴다() {
        // given — 적재 기본값(Y/N/N)을 가진 프레임
        LsDataSrc src = LsDataSrc.create(3L, 0, "/f.jpg", LocalDateTime.now());

        // when — 라벨링 화면(PUT /v1/frames/{srcSn}/privacy-meta)이 쓰는 기존 경로
        src.updatePrivacyMeta("N", "Y", "Y");

        // then — 사람 판정이 정본이다(새 화면·새 API 없이 기존 경로 그대로).
        assertThat(src.getAnonyInclYn()).isEqualTo("N");
        assertThat(src.getPsdoInclYn()).isEqualTo("Y");
        assertThat(src.getPrvcInclYn()).isEqualTo("Y");
    }
}
