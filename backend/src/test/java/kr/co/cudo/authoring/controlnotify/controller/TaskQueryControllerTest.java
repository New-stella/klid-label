package kr.co.cudo.authoring.controlnotify.controller;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TaskQueryController 통합 테스트 (SpringBootTest + MockMvc).
 * <p>control-notify.enabled=true 로 설정하여 @ConditionalOnProperty 활성화.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = "authoring.control-notify.enabled=true")
@Transactional("controlTransactionManager")
@Sql(scripts = "/db/test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class TaskQueryControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository lblRepository;
    @Autowired private LsDataMetaRepository metaRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private Long rawSn;
    private Long srcSn;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);

        // raw + frame + label + meta seed
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-TQ-001", "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4",
                LocalDateTime.now(), 30);
        raw = rawRepository.save(raw);
        rawSn = raw.getRawSn();

        LsDataSrc src = LsDataSrc.create(rawSn, 0, "/var/raw/frame_0.jpg", LocalDateTime.now());
        src = srcRepository.save(src);
        srcSn = src.getSrcSn();

        // label
        LsDataLbl lbl = LsDataLbl.createManual(srcSn, "BBOX", null, "person",
                "[[10,10],[50,50]]", 1L);
        lblRepository.save(lbl);

        // meta
        LsDataMeta meta = LsDataMeta.create(rawSn, "weather", "sunny");
        metaRepository.save(meta);
    }

    @Test
    @DisplayName("영상별_요약_조회_정상_200")
    void getSummary_ok_200() throws Exception {
        mockMvc.perform(get("/v1/tasks/{rawSn}/summary", rawSn)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rawSn").value(rawSn))
                .andExpect(jsonPath("$.data.totalFrames").value(1))
                .andExpect(jsonPath("$.data.totalLabels").value(1))
                .andExpect(jsonPath("$.data.totalMeta").value(1));
    }

    @Test
    @DisplayName("영상별_라벨_목록_조회_정상_200")
    void getLabels_ok_200() throws Exception {
        mockMvc.perform(get("/v1/tasks/{rawSn}/labels", rawSn)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].srcSn").value(srcSn))
                .andExpect(jsonPath("$.data[0].labels", hasSize(1)))
                .andExpect(jsonPath("$.data[0].labels[0].label").value("person"));
    }

    @Test
    @DisplayName("특정_프레임_라벨_필터_조회_frameIds_파라미터")
    void getLabels_filtered_by_frameIds() throws Exception {
        // Create a second frame without labels
        LsDataSrc src2 = LsDataSrc.create(rawSn, 1, "/var/raw/frame_1.jpg", LocalDateTime.now());
        src2 = srcRepository.save(src2);
        Long srcSn2 = src2.getSrcSn();

        // Filter to only first frame
        mockMvc.perform(get("/v1/tasks/{rawSn}/labels", rawSn)
                        .param("frameIds", srcSn.toString())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].srcSn").value(srcSn));
    }

    @Test
    @DisplayName("영상별_메타데이터_조회_정상_200")
    void getMeta_ok_200() throws Exception {
        mockMvc.perform(get("/v1/tasks/{rawSn}/meta", rawSn)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rawSn").value(rawSn))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].metaKey").value("weather"))
                .andExpect(jsonPath("$.data.items[0].metaVal").value("sunny"));
    }

    @Test
    @DisplayName("미존재_영상_조회시_404")
    void getSummary_notFound_404() throws Exception {
        mockMvc.perform(get("/v1/tasks/{rawSn}/summary", 999999L)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("미인증_조회시_401")
    void getSummary_unauthenticated_401() throws Exception {
        mockMvc.perform(get("/v1/tasks/{rawSn}/summary", rawSn))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("응답에_원본_이미지_경로_미포함")
    void response_doesNotContainFilePath() throws Exception {
        // summary -- verify response JSON string does not contain file paths
        mockMvc.perform(get("/v1/tasks/{rawSn}/labels", rawSn)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].filePath").doesNotExist())
                .andExpect(jsonPath("$.data[0].deidentFilePath").doesNotExist());
    }
}
