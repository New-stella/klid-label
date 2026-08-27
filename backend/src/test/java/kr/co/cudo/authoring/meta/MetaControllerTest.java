package kr.co.cudo.authoring.meta;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.meta.dto.MetaReviewRejectRequest;
import kr.co.cudo.authoring.meta.dto.MetaUpdateRequest;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
import kr.co.cudo.authoring.meta.repository.LsDataMetaReviewRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.transfer.ImportMetaKeys;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.service.VlmResultService;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
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

    // ───────────── 와이어(HTTP 상태 · JSON 직렬화) 계약 — 네 목록 응답 [design: API-066] ─────────────
    //
    // 아래 다섯 시험은 서비스 레이어가 아니라 **직렬화된 응답 바이트와 HTTP 상태**를 관측한다.
    // 계약의 소비자가 프론트엔드라 record 컴포넌트명이 JSON 키로 나오는지·거부가 실제로 400 으로
    // 매핑되는지를 추론이 아니라 관측으로 고정해야 한다(레이어 시험은 그 둘을 증명하지 못한다).

    /** {@code VideoMetaService} 소유 키 — 그 상수가 패키지 밖에서 보이지 않아 리터럴로 적는다. */
    private static final String TECHNICAL_KEY = "video.fps";

    @Test
    @DisplayName("GET_직렬화된_JSON에_네_목록이_모두_나오고_이관원문은_items가_아니라_importedMeta에_담긴다")
    void getMetaWireExposesFourLists() throws Exception {
        // given — 네 분류가 모두 섞인 영상(setup 시드 weather·time_of_day 가 시계열 축)
        metaRepository.save(LsDataMeta.create(rawSn, TECHNICAL_KEY, "30"));
        metaRepository.save(LsDataMeta.create(rawSn, VlmResultService.META_KEY_ACCURACY, "0.8"));
        metaRepository.save(LsDataMeta.create(rawSn, ImportMetaKeys.VIDEO_COORDINATES, "37.1234,127.5678"));
        metaRepository.save(LsDataMeta.create(rawSn, ImportMetaKeys.VIDEO_CCTV_HEIGHT, "4.5"));

        // when / then
        mockMvc.perform(get("/v1/frames/" + srcSn + "/meta")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                // 신설 목록이 실제로 직렬화된다 — 키 이름·타입·내용까지 와이어에서 관측한다.
                .andExpect(jsonPath("$.data.importedMeta").isArray())
                .andExpect(jsonPath("$.data.importedMeta.length()").value(2))
                .andExpect(jsonPath("$.data.importedMeta[*].metaKey").value(containsInAnyOrder(
                        ImportMetaKeys.VIDEO_COORDINATES, ImportMetaKeys.VIDEO_CCTV_HEIGHT)))
                .andExpect(jsonPath("$.data.importedMeta[?(@.metaKey=='"
                        + ImportMetaKeys.VIDEO_COORDINATES + "')].metaVal").value("37.1234,127.5678"))
                // 기존 세 목록도 같은 응답에 그대로 나온다(회귀) — 신규 분류가 기존 계약을 잠식하지 않는다.
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items[*].metaKey")
                        .value(containsInAnyOrder("weather", "time_of_day")))
                .andExpect(jsonPath("$.data.technicalMeta").isArray())
                .andExpect(jsonPath("$.data.technicalMeta[*].metaKey")
                        .value(containsInAnyOrder(TECHNICAL_KEY)))
                .andExpect(jsonPath("$.data.readOnlyMeta").isArray())
                .andExpect(jsonPath("$.data.readOnlyMeta[*].metaKey")
                        .value(containsInAnyOrder(VlmResultService.META_KEY_ACCURACY)));
    }

    @Test
    @DisplayName("이관원문이_없는_영상에서_importedMeta는_null이_아니라_빈_배열로_직렬화된다")
    void getMetaWireImportedMetaIsEmptyArrayNotNull() throws Exception {
        // given — 대다수 영상이 이 모양이다(setup 시드는 시계열 메타 2건뿐이라 이관 원문이 0건).

        // when
        String json = mockMvc.perform(get("/v1/frames/" + srcSn + "/meta")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                // isArray() 는 값이 null 이면 실패한다 — 타입까지 못박는다.
                .andExpect(jsonPath("$.data.importedMeta").isArray())
                .andExpect(jsonPath("$.data.importedMeta.length()").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // then — 직렬화 바이트로 null 과 [] 를 구분한다(화면이 분기 없이 그리는 계약의 핵심).
        assertThat(json).contains("\"importedMeta\":[]");
        assertThat(json).doesNotContain("\"importedMeta\":null");
    }

    @Test
    @DisplayName("메타가_0건인_영상도_네_목록이_모두_빈_배열로_직렬화된다")
    void getMetaWireEmptyResponseHasFourEmptyArrays() throws Exception {
        // given — 메타가 한 건도 없는 별개 영상(MetaResponse.empty() 경로 — 위 시험과 다른 분기다)
        LsDataRaw bare = rawRepository.save(LsDataRaw.createFromIngest(
                "CLIP-META-EMPTY", "CCTV-002", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/bare.mp4", LocalDateTime.now(), 30));
        LsDataSrc bareSrc = srcRepository.save(
                LsDataSrc.create(bare.getRawSn(), 0, "/var/raw/bare_0.jpg", LocalDateTime.now()));

        // when
        String json = mockMvc.perform(get("/v1/frames/" + bareSrc.getSrcSn() + "/meta")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.technicalMeta").isArray())
                .andExpect(jsonPath("$.data.readOnlyMeta").isArray())
                .andExpect(jsonPath("$.data.importedMeta").isArray())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // then
        assertThat(json).contains("\"items\":[]");
        assertThat(json).contains("\"technicalMeta\":[]");
        assertThat(json).contains("\"readOnlyMeta\":[]");
        assertThat(json).contains("\"importedMeta\":[]");
    }

    @Test
    @DisplayName("PUT에_이관원문_키를_보내면_HTTP_400이고_메시지에_요청_키를_되돌려_담지_않는다")
    void putImportedKeyReturnsBadRequest() throws Exception {
        // given
        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item(ImportMetaKeys.VIDEO_COORDINATES, "0,0")));

        // when — 상태 코드를 추론하지 않고 실제로 관측한다.
        String json = mockMvc.perform(put("/v1/frames/" + srcSn + "/meta")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.INVALID_INPUT.name()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // then — 요청 키 echo 금지(CWE-117/209). 접두조차 응답 어디에도 담기지 않는다.
        assertThat(json).doesNotContain(ImportMetaKeys.PREFIX);

        // 거부된 키가 새 행으로 만들어지지도 않는다(검토 큐 오염 방지).
        assertThat(metaRepository.findByRawSnAndMetaKey(rawSn, ImportMetaKeys.VIDEO_COORDINATES)).isEmpty();
    }

    @Test
    @DisplayName("PUT에_편집가능_키와_이관원문_키를_섞으면_400이고_편집가능_키도_저장되지_않는다")
    void putMixedEditableAndImportedSavesNothing() throws Exception {
        // given — 편집 가능 키가 먼저 오는 순서라, 검증이 첫 upsert 뒤에 걸리면 부분 저장이 남는다.
        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item("weather", "snow"),
                new MetaUpdateRequest.Item(ImportMetaKeys.VIDEO_LOCATION, "somewhere")));

        // when
        mockMvc.perform(put("/v1/frames/" + srcSn + "/meta")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.INVALID_INPUT.name()));

        // then — 부분 저장 없음. 편집 가능 키는 시드값 그대로다.
        assertThat(metaRepository.findByRawSnAndMetaKey(rawSn, "weather").orElseThrow().getMetaVl())
                .isEqualTo("rain");
        assertThat(metaRepository.findByRawSnAndMetaKey(rawSn, ImportMetaKeys.VIDEO_LOCATION)).isEmpty();
    }
}
