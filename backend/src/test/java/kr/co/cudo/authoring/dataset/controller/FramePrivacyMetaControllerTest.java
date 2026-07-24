package kr.co.cudo.authoring.dataset.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.dataset.dto.FramePrivacyBulkItem;
import kr.co.cudo.authoring.dataset.dto.FramePrivacyBulkRequest;
import kr.co.cudo.authoring.dataset.dto.FramePrivacyMetaUpdateRequest;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FramePrivacyMetaController 통합 테스트 (@SpringBootTest + MockMvc).
 *
 * <p>저장/조회(수동 우선), 인가(401/403 IDOR/포털 채널 격리), 미존재(404), path·body srcSn 불일치(400),
 * 허용값 외(400), 벌크 저장을 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class FramePrivacyMetaControllerTest {

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
    private String portalToken;

    private Long srcSn;

    @BeforeEach
    void setup() {
        reviewerToken         = JwtTestSupport.token(secret, "1",   "REVIEWER",    "INTERNAL", issuer, 60);
        workerAssignedToken   = JwtTestSupport.token(secret, "100", "WORKER",      "INTERNAL", issuer, 60);
        workerUnassignedToken = JwtTestSupport.token(secret, "200", "WORKER",      "INTERNAL", issuer, 60);
        portalToken           = JwtTestSupport.token(secret, "300", "PORTAL_USER", "PORTAL",   issuer, 60);

        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-PMETA-001", "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_PSDO, "/var/raw/clip.mp4",
                LocalDateTime.now(), 30);
        raw = rawRepository.save(raw);
        Long rawSn = raw.getRawSn();

        LsDataSrc src = LsDataSrc.create(rawSn, 0, "/var/raw/frame_0.jpg", LocalDateTime.now());
        src = srcRepository.save(src);
        srcSn = src.getSrcSn();

        authrtRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L));
    }

    private String body(Long bodySrcSn, String a, String p, String pi) throws Exception {
        return objectMapper.writeValueAsString(new FramePrivacyMetaUpdateRequest(bodySrcSn, a, p, pi));
    }

    @Test
    @DisplayName("저장값_없으면_파생값_프리필_PSDO_영상")
    void 파생프리필() throws Exception {
        // 영상 개인정보 유형 PSDO → pseudonymity=Y, anonymity=N, privacyIncluded=Y(파생)
        mockMvc.perform(get("/v1/frames/" + srcSn + "/privacy-meta")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pseudonymity").value("Y"))
                .andExpect(jsonPath("$.data.anonymity").value("N"))
                .andExpect(jsonPath("$.data.privacyIncluded").value("Y"));
    }

    @Test
    @DisplayName("프레임_수동저장후_재조회_유지_WORKER_배정자")
    void 수동저장후_재조회유지() throws Exception {
        mockMvc.perform(put("/v1/frames/" + srcSn + "/privacy-meta")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(srcSn, "Y", "N", "N")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.anonymity").value("Y"))
                .andExpect(jsonPath("$.data.pseudonymity").value("N"))
                .andExpect(jsonPath("$.data.privacyIncluded").value("N"));

        mockMvc.perform(get("/v1/frames/" + srcSn + "/privacy-meta")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.anonymity").value("Y"))
                .andExpect(jsonPath("$.data.pseudonymity").value("N"));

        LsDataSrc after = srcRepository.findById(srcSn).orElseThrow();
        assertThat(after.getAnonyInclYn()).isEqualTo("Y");
        assertThat(after.getPsdoInclYn()).isEqualTo("N");
    }

    @Test
    @DisplayName("미인증_토큰없음_401")
    void 미인증_401() throws Exception {
        mockMvc.perform(get("/v1/frames/" + srcSn + "/privacy-meta"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("타인_배정_프레임_수정_403_IDOR")
    void 타인배정_403_IDOR() throws Exception {
        mockMvc.perform(put("/v1/frames/" + srcSn + "/privacy-meta")
                        .header("Authorization", "Bearer " + workerUnassignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(srcSn, "Y", "Y", "Y")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("포털_채널_토큰_내부API_호출시_403_차단")
    void 포털차단_403() throws Exception {
        mockMvc.perform(get("/v1/frames/" + srcSn + "/privacy-meta")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("존재하지않는_srcSn_404")
    void 미존재_404() throws Exception {
        mockMvc.perform(get("/v1/frames/" + (srcSn + 999999) + "/privacy-meta")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("path_body_srcSn_불일치_400")
    void path_body_불일치_400() throws Exception {
        mockMvc.perform(put("/v1/frames/" + srcSn + "/privacy-meta")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(srcSn + 999, "Y", "Y", "Y")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("허용값_외_문자열_PUT시_400")
    void 허용값외_400() throws Exception {
        mockMvc.perform(put("/v1/frames/" + srcSn + "/privacy-meta")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(srcSn, "X", "N", "N")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("벌크_PUT_저장_200_및_유효값_반환")
    void 벌크_저장_200() throws Exception {
        String bulk = objectMapper.writeValueAsString(new FramePrivacyBulkRequest(
                List.of(new FramePrivacyBulkItem(srcSn, "N", "Y", "N"))));
        mockMvc.perform(put("/v1/frames/privacy-meta")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bulk))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].srcSn").value(srcSn))
                .andExpect(jsonPath("$.data[0].pseudonymity").value("Y"));

        LsDataSrc after = srcRepository.findById(srcSn).orElseThrow();
        assertThat(after.getPsdoInclYn()).isEqualTo("Y");
        assertThat(after.getPrvcInclYn()).isEqualTo("N");
    }
}
