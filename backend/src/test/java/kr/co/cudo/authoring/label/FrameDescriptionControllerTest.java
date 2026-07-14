package kr.co.cudo.authoring.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.label.dto.FrameDescriptionRequest;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FrameDescriptionController 통합 테스트 (@SpringBootTest + MockMvc).
 *
 * <p>저장/조회, 인가(401/403 IDOR), path·body srcSn 불일치(400), @Size 초과(400)를 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class FrameDescriptionControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerAssignedToken;
    private String workerUnassignedToken;

    private Long srcSn;

    @BeforeEach
    void setup() {
        reviewerToken          = JwtTestSupport.token(secret, "1",   "REVIEWER", "INTERNAL", issuer, 60);
        workerAssignedToken    = JwtTestSupport.token(secret, "100", "WORKER",   "INTERNAL", issuer, 60);
        workerUnassignedToken  = JwtTestSupport.token(secret, "200", "WORKER",   "INTERNAL", issuer, 60);

        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-DESC-001", "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4",
                LocalDateTime.now(), 30);
        raw = rawRepository.save(raw);
        Long rawSn = raw.getRawSn();

        LsDataSrc src = LsDataSrc.create(rawSn, 0, "/var/raw/frame_0.jpg", LocalDateTime.now());
        src = srcRepository.save(src);
        srcSn = src.getSrcSn();

        // worker 100 배정 (본인 배정) — worker 200 은 미배정(IDOR 검증용)
        authrtRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L));
    }

    private String body(Long bodySrcSn, String description) throws Exception {
        return objectMapper.writeValueAsString(new FrameDescriptionRequest(bodySrcSn, description));
    }

    @Test
    @DisplayName("설명_저장후_조회시_동일값_반환_WORKER_배정자")
    void 저장후_조회_동일값() throws Exception {
        mockMvc.perform(put("/v1/frames/" + srcSn + "/description")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(srcSn, "교차로 무단횡단 보행자")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.description").value("교차로 무단횡단 보행자"));

        mockMvc.perform(get("/v1/frames/" + srcSn + "/description")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.srcSn").value(srcSn))
                .andExpect(jsonPath("$.data.description").value("교차로 무단횡단 보행자"));

        LsDataSrc after = srcRepository.findById(srcSn).orElseThrow();
        assertThat(after.getFrmExpln()).isEqualTo("교차로 무단횡단 보행자");
    }

    @Test
    @DisplayName("빈값_저장시_설명_삭제_null허용")
    void 빈값_삭제() throws Exception {
        mockMvc.perform(put("/v1/frames/" + srcSn + "/description")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(srcSn, "먼저 채운 설명")))
                .andExpect(status().isOk());

        mockMvc.perform(put("/v1/frames/" + srcSn + "/description")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(srcSn, "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.description").doesNotExist());

        LsDataSrc after = srcRepository.findById(srcSn).orElseThrow();
        assertThat(after.getFrmExpln()).isNull();
    }

    @Test
    @DisplayName("미인증_토큰없음_401")
    void 미인증_401() throws Exception {
        mockMvc.perform(get("/v1/frames/" + srcSn + "/description"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("타인_배정_프레임_설명수정_403_IDOR")
    void 타인배정_403_IDOR() throws Exception {
        mockMvc.perform(put("/v1/frames/" + srcSn + "/description")
                        .header("Authorization", "Bearer " + workerUnassignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(srcSn, "권한없는 수정 시도")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("path_body_srcSn_불일치_400")
    void path_body_불일치_400() throws Exception {
        mockMvc.perform(put("/v1/frames/" + srcSn + "/description")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(srcSn + 999, "불일치")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("설명_1000자_초과_400")
    void 길이초과_400() throws Exception {
        String tooLong = "가".repeat(1001);
        mockMvc.perform(put("/v1/frames/" + srcSn + "/description")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(srcSn, tooLong)))
                .andExpect(status().isBadRequest());
    }
}
