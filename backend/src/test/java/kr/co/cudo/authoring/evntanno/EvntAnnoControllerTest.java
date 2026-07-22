package kr.co.cudo.authoring.evntanno;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnno;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnnoReview;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoRepository;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoReviewRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 — event_annotation 입력(WORKER)·검수(REVIEWER) API 통합 테스트.
 * MetaControllerTest 와 동일한 실 DB(@SpringBootTest) + JWT 토큰 방식.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data-evntanno-clean.sql", "/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class EvntAnnoControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;
    @Autowired private LsEvntAnnoRepository annoRepository;
    @Autowired private LsEvntAnnoReviewRepository reviewRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerAssignedToken;
    private String workerUnassignedToken;

    private Long rawSn;

    @BeforeEach
    void setup() {
        reviewerToken         = JwtTestSupport.token(secret, "1",   "REVIEWER", "INTERNAL", issuer, 60);
        workerAssignedToken   = JwtTestSupport.token(secret, "100", "WORKER",   "INTERNAL", issuer, 60);
        workerUnassignedToken = JwtTestSupport.token(secret, "200", "WORKER",   "INTERNAL", issuer, 60);

        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-EA-001", "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4",
                LocalDateTime.now(), 30);
        raw = rawRepository.save(raw);
        rawSn = raw.getRawSn();

        // worker 100 배정 (본인 배정), worker 200 미배정
        authrtRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L));
    }

    private String url() {
        return "/v1/videos/" + rawSn + "/event-annotation";
    }

    private String validPayload() {
        return "{\"event_class\":\"정차\",\"question\":\"무슨 이벤트인가?\"}";
    }

    @Test
    @DisplayName("WORKER_본인배정_영상_event_annotation_저장_성공_200")
    void workerAssignedUpsert200() throws Exception {
        mockMvc.perform(put(url())
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payload.event_class").value("정차"));

        LsEvntAnno saved = annoRepository.findByRawSn(rawSn).orElseThrow();
        assertThat(saved.getRegId()).isEqualTo("100");
        // 신규 저장 시 검토 row(AUTO_GENERATED) 함께 생성
        assertThat(reviewRepository.findByEvntAnnoSn(saved.getEvntAnnoSn()))
                .isNotEmpty()
                .allMatch(r -> LsEvntAnnoReview.STTS_AUTO_GENERATED.equals(r.getRvwSttsCd()));
    }

    @Test
    @DisplayName("저장후_GET_정상_조회_200")
    void getAfterUpsert200() throws Exception {
        mockMvc.perform(put(url())
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isOk());

        mockMvc.perform(get(url())
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payload.event_class").value("정차"))
                .andExpect(jsonPath("$.data.reviewStatus").value(LsEvntAnnoReview.STTS_AUTO_GENERATED));
    }

    @Test
    @DisplayName("WORKER_타인배정_영상_접근시_403")
    void workerUnassignedForbidden403() throws Exception {
        mockMvc.perform(put(url())
                        .header("Authorization", "Bearer " + workerUnassignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("미인증_요청시_401")
    void unauthenticated401() throws Exception {
        mockMvc.perform(get(url()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put(url())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("payload_필수키_event_class_누락시_400")
    void missingEventClass400() throws Exception {
        mockMvc.perform(put(url())
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"이벤트?\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("WORKER는_승인_불가_403_REVIEWER만_승인")
    void workerApproveForbidden403() throws Exception {
        // 검토 row 준비 (REVIEWER 로 저장)
        mockMvc.perform(put(url())
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isOk());

        mockMvc.perform(post(url() + "/approve")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("승인은_PENDING에서만_완료상태_재승인시_409")
    void reApprove409() throws Exception {
        mockMvc.perform(put(url())
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isOk());

        mockMvc.perform(post(url() + "/approve")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());

        // 재승인 → 이미 검토 완료 → 409
        mockMvc.perform(post(url() + "/approve")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("반려후_재저장시_재승인_성공_데드엔드_해소")
    void rejectThenResaveThenReapprove() throws Exception {
        // 저장(REVIEWER) → 검토 row AUTO_GENERATED
        mockMvc.perform(put(url())
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isOk());

        // 반려 → REJECTED
        mockMvc.perform(post(url() + "/reject")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"근거 부족\"}"))
                .andExpect(status().isOk());

        // WORKER 재저장(수정) → 리뷰 REJECTED→PENDING 리셋(재제출 = 재검수 대기)
        mockMvc.perform(put(url())
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value(LsEvntAnnoReview.STTS_PENDING));

        // 재승인 성공(영구 409 데드엔드 아님)
        mockMvc.perform(post(url() + "/approve")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("반려_사유_누락시_400")
    void rejectMissingReason400() throws Exception {
        mockMvc.perform(put(url())
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isOk());

        mockMvc.perform(post(url() + "/reject")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
