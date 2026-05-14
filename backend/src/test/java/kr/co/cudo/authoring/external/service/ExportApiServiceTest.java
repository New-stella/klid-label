package kr.co.cudo.authoring.external.service;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataLblAiInfo;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.external.dto.DatasetResponse;
import kr.co.cudo.authoring.external.dto.FrameLabelInfo;
import kr.co.cudo.authoring.external.dto.LabelInfo;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
import kr.co.cudo.authoring.meta.repository.LsDataMetaReviewRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 4 — 외부 학습데이터 API 서비스 단위 테스트 (Phase 5/6 신규 테이블 정합화 반영).
 *
 * <p>변경 사항:
 * <ul>
 *   <li>LS_DATA_META.metaTypeCd 폐기 → LS_DATA_META + LS_DATA_META_REVIEW.META_TYPE_CD 로 분류</li>
 *   <li>LS_DATA_SRC.frmTypeCd 폐기 → RAW row 가 srcBkupFilePath 로 DEID 경로를 함께 보유 (단일 row)</li>
 *   <li>LS_DATA_LBL.autoLblYn/confScore 는 @Transient → LS_DATA_LBL_AI_INFO 와 join 으로 응답 빌드</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class ExportApiServiceTest {

    @Autowired private ExportApiService service;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository lblRepository;
    @Autowired private LsDataLblAiInfoRepository lblAiInfoRepository;
    @Autowired private LsDataMetaRepository metaRepository;
    @Autowired private LsDataMetaReviewRepository metaReviewRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanup() {
        jdbcTemplate.execute("DELETE FROM LS_DATA_LBL_AI_INFO");
        jdbcTemplate.execute("DELETE FROM LS_DATA_LBL");
        jdbcTemplate.execute("DELETE FROM LS_DATA_SRC");
        jdbcTemplate.execute("DELETE FROM LS_DATA_META_REVIEW");
        jdbcTemplate.execute("DELETE FROM LS_DATA_META");
        jdbcTemplate.execute("DELETE FROM LS_DATA_RAW");
    }

    @Test
    @DisplayName("존재하지_않는_rawSn_은_NOT_FOUND")
    void notFoundWhenRawMissing() {
        assertThatThrownBy(() -> service.build(99999L, false))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("정상_빌드_프레임_단일_row_DEID_경로_보유시도_RAW_filePath_만_노출")
    void buildExposesRawFilePathEvenWhenDeidPathAttached() {
        LsDataRaw raw = saveRaw("clip-001", "ANONY");
        LsDataSrc src0 = srcRepository.save(LsDataSrc.create(raw.getRawSn(), 0, "/raw/0.jpg",
                LocalDateTime.now()));
        // DEID 경로는 같은 row 에 attach (별도 row 아님)
        src0.attachDeidPath("/deid/0.jpg");
        srcRepository.save(src0);
        srcRepository.save(LsDataSrc.create(raw.getRawSn(), 1, "/raw/1.jpg", LocalDateTime.now()));

        lblRepository.save(LsDataLbl.createAutoBbox(src0.getSrcSn(), "person",
                "[[0,0],[10,10]]", new BigDecimal("0.9000"), "t-1"));

        DatasetResponse response = service.build(raw.getRawSn(), false);

        assertThat(response.video().rawSn()).isEqualTo(raw.getRawSn());
        assertThat(response.labels()).hasSize(2);
        List<Integer> frameNos = response.labels().stream().map(FrameLabelInfo::frameNo).toList();
        assertThat(frameNos).containsExactly(0, 1);
        // 외부 응답은 RAW filePath (DEID 경로는 노출 안 함)
        assertThat(response.labels().stream().map(FrameLabelInfo::filePath))
                .allSatisfy(p -> assertThat(p).startsWith("/raw/"));
    }

    @Test
    @DisplayName("meta_분리_모드_LS_DATA_META_REVIEW_DEID_분류_기준")
    void separatedMetaMode() {
        LsDataRaw raw = saveRaw("clip-002", "PRVC");
        metaRepository.save(LsDataMeta.create(raw.getRawSn(), "scene", "highway"));
        LsDataMeta weatherMeta = metaRepository.save(LsDataMeta.create(raw.getRawSn(), "weather", "clear"));
        // weather 만 DEID 분류 review row 부착
        metaReviewRepository.save(LsDataMetaReview.createAuto(
                weatherMeta.getMetaSn(), 0L, raw.getRawSn(), null,
                "DEID", LsDataMetaReview.SRC_AI_SERVER, LsDataMetaReview.STTS_AUTO_GENERATED));

        DatasetResponse response = service.build(raw.getRawSn(), false);

        assertThat(response.merged()).isFalse();
        // scene 은 review 없음 → rawMeta
        assertThat(response.rawMeta()).containsEntry("scene", "highway").hasSize(1);
        // weather 는 review.META_TYPE_CD='DEID' → deidMeta
        assertThat(response.deidMeta()).containsEntry("weather", "clear").hasSize(1);
        assertThat(response.meta()).isNull();
    }

    @Test
    @DisplayName("meta_병합_모드_RAW_DEID_단독_key_모두_포함_RAW_가_앞순서")
    void mergedMetaModeIncludesBothSides() {
        LsDataRaw raw = saveRaw("clip-003", "PSDO");
        metaRepository.save(LsDataMeta.create(raw.getRawSn(), "scene", "highway"));
        LsDataMeta weatherMeta = metaRepository.save(LsDataMeta.create(raw.getRawSn(), "weather", "clear"));
        metaReviewRepository.save(LsDataMetaReview.createAuto(
                weatherMeta.getMetaSn(), 0L, raw.getRawSn(), null,
                "DEID", LsDataMetaReview.SRC_AI_SERVER, LsDataMetaReview.STTS_AUTO_GENERATED));

        DatasetResponse response = service.build(raw.getRawSn(), true);

        assertThat(response.merged()).isTrue();
        assertThat(response.meta())
                .containsEntry("scene", "highway")
                .containsEntry("weather", "clear");
        // RAW 가 앞 순서 (LinkedHashMap)
        assertThat(response.meta().keySet().iterator().next()).isEqualTo("scene");
        assertThat(response.rawMeta()).isNull();
        assertThat(response.deidMeta()).isNull();
    }

    @Test
    @DisplayName("review_없는_메타는_모두_rawMeta_로_간주")
    void allMetaWithoutReviewAreRaw() {
        LsDataRaw raw = saveRaw("clip-003b", "ANONY");
        metaRepository.save(LsDataMeta.create(raw.getRawSn(), "scene", "park"));
        metaRepository.save(LsDataMeta.create(raw.getRawSn(), "weather", "sunny"));

        DatasetResponse response = service.build(raw.getRawSn(), false);

        assertThat(response.rawMeta()).hasSize(2)
                .containsEntry("scene", "park")
                .containsEntry("weather", "sunny");
        assertThat(response.deidMeta()).isEmpty();
    }

    @Test
    @DisplayName("라벨_없는_영상은_빈_라벨_리스트_원본_프레임_정보는_유지")
    void noLabelsReturnsEmptyArrays() {
        LsDataRaw raw = saveRaw("clip-004", "ANONY");
        srcRepository.save(LsDataSrc.create(raw.getRawSn(), 0, "/raw/0.jpg", LocalDateTime.now()));

        DatasetResponse response = service.build(raw.getRawSn(), false);

        assertThat(response.labels()).hasSize(1);
        assertThat(response.labels().get(0).labels()).isEmpty();
    }

    @Test
    @DisplayName("프레임_정렬은_frameNo_오름차순")
    void framesOrderedByFrameNo() {
        LsDataRaw raw = saveRaw("clip-005", "ANONY");
        srcRepository.save(LsDataSrc.create(raw.getRawSn(), 2, "/raw/2.jpg", LocalDateTime.now()));
        srcRepository.save(LsDataSrc.create(raw.getRawSn(), 0, "/raw/0.jpg", LocalDateTime.now()));
        srcRepository.save(LsDataSrc.create(raw.getRawSn(), 1, "/raw/1.jpg", LocalDateTime.now()));

        DatasetResponse response = service.build(raw.getRawSn(), false);

        List<Integer> nos = response.labels().stream().map(FrameLabelInfo::frameNo).toList();
        assertThat(nos).containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("VideoInfo_에_사용자_식별_정보_미포함")
    void videoInfoOmitsUserIdentifiers() {
        LsDataRaw raw = saveRaw("clip-006", "ANONY");

        DatasetResponse response = service.build(raw.getRawSn(), false);

        var components = java.util.Arrays.stream(response.video().getClass().getRecordComponents())
                .map(c -> c.getName().toLowerCase())
                .toList();
        assertThat(components).noneMatch(n -> n.contains("user") || n.contains("reporter")
                || n.contains("uploader") || n.contains("regno"));
    }

    @Test
    @DisplayName("라벨_좌표는_원본_좌표_그대로_AI_INFO_있으면_autoGenerated_confidence_lblSrcCd_채움")
    void labelsPreserveAllFieldsWithAiInfo() {
        LsDataRaw raw = saveRaw("clip-007", "ANONY");
        LsDataSrc src = srcRepository.save(LsDataSrc.create(raw.getRawSn(), 0, "/raw/0.jpg",
                LocalDateTime.now()));
        LsDataLbl lbl = lblRepository.save(LsDataLbl.createAutoBbox(src.getSrcSn(), "car",
                "[[1,2],[3,4]]", new BigDecimal("0.8500"), "track-7"));
        lblAiInfoRepository.save(LsDataLblAiInfo.create(
                lbl.getLblSn(), 0L, raw.getRawSn(), src.getSrcSn(),
                LsDataLblAiInfo.SRC_YOLO, new BigDecimal("0.8500"), "system"));

        DatasetResponse response = service.build(raw.getRawSn(), false);

        LabelInfo info = response.labels().get(0).labels().get(0);
        assertThat(info.type()).isEqualTo("BBOX");
        assertThat(info.label()).isEqualTo("car");
        assertThat(info.points()).containsExactly(List.of(1.0, 2.0), List.of(3.0, 4.0));
        assertThat(info.autoGenerated()).isTrue();
        assertThat(info.confidence()).isEqualByComparingTo("0.8500");
        assertThat(info.trackId()).isEqualTo("track-7");
        assertThat(info.lblSrcCd()).isEqualTo(LsDataLblAiInfo.SRC_YOLO);
    }

    @Test
    @DisplayName("AI_INFO_없는_라벨은_수동_라벨로_간주_autoGenerated_false_confidence_null")
    void labelsWithoutAiInfoAreManual() {
        LsDataRaw raw = saveRaw("clip-008", "ANONY");
        LsDataSrc src = srcRepository.save(LsDataSrc.create(raw.getRawSn(), 0, "/raw/0.jpg",
                LocalDateTime.now()));
        lblRepository.save(LsDataLbl.createManual(src.getSrcSn(), "BBOX", "person",
                "[[0,0],[10,10]]", 9001L));

        DatasetResponse response = service.build(raw.getRawSn(), false);

        LabelInfo info = response.labels().get(0).labels().get(0);
        assertThat(info.autoGenerated()).isFalse();
        assertThat(info.confidence()).isNull();
        assertThat(info.lblSrcCd()).isNull();
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private LsDataRaw saveRaw(String clipId, String prvcTypeCd) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                clipId, "CCTV-001", "EVT-001", "LCL-11", prvcTypeCd,
                "/clips/" + clipId + ".mp4",
                LocalDateTime.now(), 30
        );
        return videoRepository.save(raw);
    }
}
