package kr.co.cudo.authoring.meta;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.meta.dto.MetaReviewRejectRequest;
import kr.co.cudo.authoring.meta.dto.MetaUpdateRequest;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class MetaControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataMetaRepository metaRepository;
    @Autowired private LsDataMetaReviewRepository metaReviewRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerAssignedToken;

    private Long srcSn;
    private Long rawSn;

    @BeforeEach
    void setup() {
        reviewerToken       = JwtTestSupport.token(secret, "1",   "REVIEWER", "INTERNAL", issuer, 60);
        workerAssignedToken = JwtTestSupport.token(secret, "100", "WORKER",   "INTERNAL", issuer, 60);

        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-META-001", "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4",
                LocalDateTime.now(), 30);
        raw = rawRepository.save(raw);
        rawSn = raw.getRawSn();

        LsDataSrc src = LsDataSrc.create(rawSn, 0, "/var/raw/frame_0.jpg", LocalDateTime.now());
        src = srcRepository.save(src);
        srcSn = src.getSrcSn();

        // worker 100 배정 — 작업자도 메타 조회 가능 검증용
        authrtRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L));

        // 외부 시스템이 생성한 시계열 메타 시드
        metaRepository.save(LsDataMeta.create(rawSn, "weather", "rain"));
        metaRepository.save(LsDataMeta.create(rawSn, "time_of_day", "night"));
    }

    @Test
    @DisplayName("MetaController_외부_생성_메타_GET_정상_조회_REVIEWER")
    void reviewerGetMeta() throws Exception {
        mockMvc.perform(get("/v1/frames/" + srcSn + "/meta")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2));
    }

    @Test
    @DisplayName("MetaController_외부_생성_메타_GET_정상_조회_WORKER_배정자")
    void assignedWorkerGetMeta() throws Exception {
        mockMvc.perform(get("/v1/frames/" + srcSn + "/meta")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2));
    }

    @Test
    @DisplayName("MetaController_메타_수정_PUT_정상_동작")
    void putUpdatesMeta() throws Exception {
        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item("weather", "snow")
        ));
        mockMvc.perform(put("/v1/frames/" + srcSn + "/meta")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.metaKey=='weather')].metaVal").value("snow"));

        LsDataMeta after = metaRepository.findByRawSnAndMetaKey(rawSn, "weather").orElseThrow();
        assertThat(after.getMetaVl()).isEqualTo("snow");
    }

    @Test
    @DisplayName("값_무변경_저장도_200으로_성공한다")
    void 값_무변경_저장도_200으로_성공한다() throws Exception {
        // given — 시드와 동일한 값으로 다시 저장(저장 버튼 연타 동선)
        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item("weather", "rain")
        ));

        // when / then — 재생성·통지만 생략하고 저장 자체는 성공이어야 한다(오류로 바꾸지 않는다).
        mockMvc.perform(put("/v1/frames/" + srcSn + "/meta")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.metaKey=='weather')].metaVal").value("rain"));

        LsDataMeta after = metaRepository.findByRawSnAndMetaKey(rawSn, "weather").orElseThrow();
        assertThat(after.getMetaVl()).isEqualTo("rain");
    }

    // ─────────────────────────── Phase 5: 메타 검토 approve/reject ───────────────────────────

    private Long seedReview(String rvwSttsCd) {
        LsDataMeta meta = metaRepository.save(LsDataMeta.create(rawSn, "VLM_META.scene", "intersection"));
        LsDataMetaReview review = LsDataMetaReview.createAuto(
                meta.getMetaSn(), rawSn, null,
                LsDataMetaReview.META_TYPE_VLM,
                LsDataMetaReview.SRC_AI_SERVER,
                rvwSttsCd);
        review = metaReviewRepository.save(review);
        return review.getDataMetaReviewSn();
    }

    @Test
    @DisplayName("MetaController_검토_승인_POST_REVIEWER_정상_200")
    void reviewerApproveOk() throws Exception {
        Long reviewSn = seedReview(LsDataMetaReview.STTS_AUTO_GENERATED);
        mockMvc.perform(post("/v1/meta/" + reviewSn + "/approve")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());

        LsDataMetaReview after = metaReviewRepository.findById(reviewSn).orElseThrow();
        assertThat(after.getRvwSttsCd()).isEqualTo(LsDataMetaReview.STTS_APPROVED);
        assertThat(after.getRvwId()).isEqualTo("1");
        assertThat(after.getRvwDt()).isNotNull();
    }

    @Test
    @DisplayName("MetaController_검토_반려_POST_REVIEWER_사유_포함_정상_200")
    void reviewerRejectWithReasonOk() throws Exception {
        Long reviewSn = seedReview(LsDataMetaReview.STTS_AUTO_GENERATED);
        MetaReviewRejectRequest body = new MetaReviewRejectRequest("정확도 부족");
        mockMvc.perform(post("/v1/meta/" + reviewSn + "/reject")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        LsDataMetaReview after = metaReviewRepository.findById(reviewSn).orElseThrow();
        assertThat(after.getRvwSttsCd()).isEqualTo(LsDataMetaReview.STTS_REJECTED);
        assertThat(after.getRejectRsn()).isEqualTo("정확도 부족");
        assertThat(after.getRvwId()).isEqualTo("1");
    }

    @Test
    @DisplayName("MetaController_검토_반려_POST_사유_누락_400")
    void rejectMissingReason400() throws Exception {
        Long reviewSn = seedReview(LsDataMetaReview.STTS_AUTO_GENERATED);
        mockMvc.perform(post("/v1/meta/" + reviewSn + "/reject")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("MetaController_검토_승인_POST_WORKER_권한_403")
    void workerApproveForbidden() throws Exception {
        Long reviewSn = seedReview(LsDataMetaReview.STTS_AUTO_GENERATED);
        mockMvc.perform(post("/v1/meta/" + reviewSn + "/approve")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("MetaController_검토_승인_POST_미존재_404")
    void approveNotFound404() throws Exception {
        mockMvc.perform(post("/v1/meta/999999/approve")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound());
    }
}
