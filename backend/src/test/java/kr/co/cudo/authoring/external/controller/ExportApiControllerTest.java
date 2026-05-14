package kr.co.cudo.authoring.external.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataLblAiInfo;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
import kr.co.cudo.authoring.meta.repository.LsDataMetaReviewRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 — 외부 학습데이터 API Controller 통합 테스트 (Phase 5/6 신규 테이블 정합화 반영).
 * M2M 토큰 권한 분리, 메타 분리/병합, 라벨 직렬화 검증.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ExportApiControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository lblRepository;
    @Autowired private LsDataLblAiInfoRepository lblAiInfoRepository;
    @Autowired private LsDataMetaRepository metaRepository;
    @Autowired private LsDataMetaReviewRepository metaReviewRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Value("${authoring.integration.learning-data.m2m-token}")
    private String learningDataToken;

    @Value("${authoring.integration.control-server.m2m-token}")
    private String controlToken;

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
    @DisplayName("M2M_토큰_없으면_401")
    void missingTokenReturns401() throws Exception {
        mockMvc.perform(get("/v1/export-api/datasets/1"))
                .andExpect(ExportApiControllerTest::expect401Or403);
    }

    @Test
    @DisplayName("잘못된_M2M_토큰은_401")
    void invalidTokenReturns401() throws Exception {
        mockMvc.perform(get("/v1/export-api/datasets/1")
                        .header("X-M2M-Token", "wrong-token"))
                .andExpect(ExportApiControllerTest::expect401Or403);
    }

    @Test
    @DisplayName("CONTROL_M2M_토큰으로_LEARNING_DATA_경로_접근시_차단")
    void controlTokenCannotAccessLearningDataPath() throws Exception {
        mockMvc.perform(get("/v1/export-api/datasets/1")
                        .header("X-M2M-Token", controlToken))
                .andExpect(ExportApiControllerTest::expect401Or403);
    }

    @Test
    @DisplayName("존재하지_않는_rawSn_은_404")
    void notFoundRawSn() throws Exception {
        mockMvc.perform(get("/v1/export-api/datasets/999999")
                        .header("X-M2M-Token", learningDataToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("유효_M2M_토큰_merge_false_200_원본_비식별_메타_분리")
    void validTokenMergeFalse() throws Exception {
        LsDataRaw raw = saveRaw("clip-100", "PRVC");
        metaRepository.save(LsDataMeta.create(raw.getRawSn(), "scene", "highway"));
        LsDataMeta weatherMeta = metaRepository.save(LsDataMeta.create(raw.getRawSn(), "weather", "rain"));
        metaReviewRepository.save(LsDataMetaReview.createAuto(
                weatherMeta.getMetaSn(), 0L, raw.getRawSn(), null,
                "DEID", LsDataMetaReview.SRC_AI_SERVER, LsDataMetaReview.STTS_AUTO_GENERATED));

        MvcResult result = mockMvc.perform(get("/v1/export-api/datasets/" + raw.getRawSn())
                        .header("X-M2M-Token", learningDataToken)
                        .param("merge", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.merged").value(false))
                .andExpect(jsonPath("$.data.rawMeta.scene").value("highway"))
                .andExpect(jsonPath("$.data.deidMeta.weather").value("rain"))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        // merge=false 모드에서 통합 meta 필드는 응답에서 누락 (NON_NULL include)
        JsonNode metaNode = body.get("data").get("meta");
        assertThat(metaNode == null || metaNode.isNull()).isTrue();
        // 사용자 식별 정보 미포함
        JsonNode video = body.get("data").get("video");
        assertThat(video.has("regUserNo")).isFalse();
        assertThat(video.has("reporter")).isFalse();
        assertThat(video.has("uploader")).isFalse();
    }

    @Test
    @DisplayName("유효_M2M_토큰_merge_true_200_병합_응답")
    void validTokenMergeTrue() throws Exception {
        LsDataRaw raw = saveRaw("clip-101", "PSDO");
        metaRepository.save(LsDataMeta.create(raw.getRawSn(), "scene", "RAW-V"));
        LsDataMeta weatherMeta = metaRepository.save(LsDataMeta.create(raw.getRawSn(), "weather", "clear"));
        metaReviewRepository.save(LsDataMetaReview.createAuto(
                weatherMeta.getMetaSn(), 0L, raw.getRawSn(), null,
                "DEID", LsDataMetaReview.SRC_AI_SERVER, LsDataMetaReview.STTS_AUTO_GENERATED));

        mockMvc.perform(get("/v1/export-api/datasets/" + raw.getRawSn())
                        .header("X-M2M-Token", learningDataToken)
                        .param("merge", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.merged").value(true))
                .andExpect(jsonPath("$.data.meta.scene").value("RAW-V"))
                .andExpect(jsonPath("$.data.meta.weather").value("clear"))
                .andExpect(jsonPath("$.data.rawMeta").doesNotExist())
                .andExpect(jsonPath("$.data.deidMeta").doesNotExist());
    }

    @Test
    @DisplayName("merge_파라미터_미지정시_기본_false")
    void mergeParamDefaultsFalse() throws Exception {
        LsDataRaw raw = saveRaw("clip-102", "ANONY");

        mockMvc.perform(get("/v1/export-api/datasets/" + raw.getRawSn())
                        .header("X-M2M-Token", learningDataToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.merged").value(false));
    }

    @Test
    @DisplayName("좌표_라벨_RAW_프레임_기준_1벌만_응답_AI_INFO_있으면_autoGenerated_true")
    void labelsBuiltFromFramesWithAiInfo() throws Exception {
        LsDataRaw raw = saveRaw("clip-103", "PRVC");
        LsDataSrc src0 = srcRepository.save(LsDataSrc.create(raw.getRawSn(), 0, "/raw/0.jpg", LocalDateTime.now()));
        // 동일 row 에 DEID 경로 attach — 외부 응답은 RAW filePath 만 노출
        src0.attachDeidPath("/deid/0.jpg");
        srcRepository.save(src0);
        LsDataLbl lbl = lblRepository.save(LsDataLbl.createAutoBbox(src0.getSrcSn(), "person",
                "[[0,0],[10,10]]", new BigDecimal("0.9000"), "t-1"));
        lblAiInfoRepository.save(LsDataLblAiInfo.create(
                lbl.getLblSn(), 0L, raw.getRawSn(), src0.getSrcSn(),
                LsDataLblAiInfo.SRC_YOLO, new BigDecimal("0.9000"), "system"));

        mockMvc.perform(get("/v1/export-api/datasets/" + raw.getRawSn())
                        .header("X-M2M-Token", learningDataToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.labels.length()").value(1))
                .andExpect(jsonPath("$.data.labels[0].frameNo").value(0))
                .andExpect(jsonPath("$.data.labels[0].filePath").value("/raw/0.jpg"))
                .andExpect(jsonPath("$.data.labels[0].labels[0].type").value("BBOX"))
                .andExpect(jsonPath("$.data.labels[0].labels[0].label").value("person"))
                .andExpect(jsonPath("$.data.labels[0].labels[0].trackId").value("t-1"))
                .andExpect(jsonPath("$.data.labels[0].labels[0].autoGenerated").value(true))
                .andExpect(jsonPath("$.data.labels[0].labels[0].lblSrcCd").value("YOLO"));
    }

    // ---------------------------------------------------------------------

    private LsDataRaw saveRaw(String clipId, String prvcTypeCd) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                clipId, "CCTV-001", "EVT-001", "LCL-11", prvcTypeCd,
                "/clips/" + clipId + ".mp4",
                LocalDateTime.now(), 30
        );
        return videoRepository.save(raw);
    }

    private static void expect401Or403(MvcResult result) {
        int s = result.getResponse().getStatus();
        if (s != 401 && s != 403) {
            throw new AssertionError("expected 401 or 403, got " + s);
        }
    }
}
