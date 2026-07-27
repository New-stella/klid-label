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
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].srcSn").value(srcSn))
                .andExpect(jsonPath("$.data.content[0].labels", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].labels[0].label").value("person"));
    }

    @Test
    @DisplayName("라벨_목록은_페이징_없이_전건_반환하지_않는다_기본_20건")
    void getLabels_isPagedByDefault() throws Exception {
        // given — D-ISSUE-45 / CWE-770: 페이징 미지정 요청도 전건 조회로 흐르면 안 된다.
        mockMvc.perform(get("/v1/tasks/{rawSn}/labels", rawSn)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.number").value(0));
    }

    @Test
    @DisplayName("size_10000_요청도_상한_100_으로_클램프된다")
    void getLabels_oversizedPageIsClamped() throws Exception {
        // given — 관제(혹은 임의 호출자)가 무제한 size 를 보내도 전건 적재를 허용하지 않는다(CWE-770).
        mockMvc.perform(get("/v1/tasks/{rawSn}/labels", rawSn)
                        .param("page", "0")
                        .param("size", "10000")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
    }

    @Test
    @DisplayName("특정_프레임_라벨_필터_조회_frameIds_파라미터")
    void getLabels_filtered_by_frameIds() throws Exception {
        // Create a second frame without labels
        LsDataSrc src2 = LsDataSrc.create(rawSn, 1, "/var/raw/frame_1.jpg", LocalDateTime.now());
        srcRepository.save(src2);

        // Filter to only first frame
        mockMvc.perform(get("/v1/tasks/{rawSn}/labels", rawSn)
                        .param("frameIds", srcSn.toString())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].srcSn").value(srcSn));
    }

    @Test
    @DisplayName("frameIds_가_100개를_초과하면_400_을_반환한다")
    void getLabels_tooManyFrameIds_400() throws Exception {
        // given — 무제한 IN 절 방지(CWE-770). 컨트롤러 @Size(max=100) 가드.
        String[] tooMany = java.util.stream.LongStream.rangeClosed(1, 101)
                .mapToObj(String::valueOf).toArray(String[]::new);

        mockMvc.perform(get("/v1/tasks/{rawSn}/labels", rawSn)
                        .param("frameIds", tooMany)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest());
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
    @DisplayName("메타_조회도_페이징된다_기본_20건_상한_100")
    void getMeta_isPagedAndClamped() throws Exception {
        // given — B-4 / CWE-770: 메타도 전량 반환하지 않는다.
        mockMvc.perform(get("/v1/tasks/{rawSn}/meta", rawSn)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(1));

        mockMvc.perform(get("/v1/tasks/{rawSn}/meta", rawSn)
                        .param("size", "10000")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
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
    @DisplayName("배정되지_않은_WORKER_는_rawSn_순회로_라벨을_읽을_수_없다_403")
    void getLabels_unassignedWorker_403() throws Exception {
        // given — CWE-639 IDOR: 역할만으로는 부족하고 영상 단위 배정 검증이 걸려 있어야 한다.
        String unassignedWorkerToken =
                JwtTestSupport.token(secret, "424242", "WORKER", "INTERNAL", issuer, 60);

        // when / then — 세 경로 모두 동일 가드
        mockMvc.perform(get("/v1/tasks/{rawSn}/labels", rawSn)
                        .header("Authorization", "Bearer " + unassignedWorkerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/tasks/{rawSn}/summary", rawSn)
                        .header("Authorization", "Bearer " + unassignedWorkerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/tasks/{rawSn}/meta", rawSn)
                        .header("Authorization", "Bearer " + unassignedWorkerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("응답에_원본_이미지_경로_미포함")
    void response_doesNotContainFilePath() throws Exception {
        // summary -- verify response JSON string does not contain file paths
        mockMvc.perform(get("/v1/tasks/{rawSn}/labels", rawSn)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].filePath").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].deidentFilePath").doesNotExist());
    }
}
