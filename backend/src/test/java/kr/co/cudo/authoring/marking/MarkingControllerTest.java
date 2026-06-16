package kr.co.cudo.authoring.marking;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.dto.MarkingRequest;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MarkingController 통합 테스트 (SpringBootTest + MockMvc + H2).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class MarkingControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsMarkingRepository markingRepository;
    @Autowired private LsTaskAssignmentRepository assignmentRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;
    /** 타인(2002) 토큰 — I4 수평 권한 상승 재현용 (sub 200 토큰은 위 workerToken 과 별개). */
    private String otherWorkerToken;
    private Long rawSn;

    /** workerToken 의 sub = 100 (= LABELER 배정 대상), otherWorkerToken 의 sub = 200 (미배정). */
    private static final long WORKER_NO = 100L;

    @BeforeEach
    void setUp() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
        otherWorkerToken = JwtTestSupport.token(secret, "200", "WORKER", "INTERNAL", issuer, 60);

        // 영상 시드 — 마킹은 비식별 완료(deIdntfYn='Y') 영상 대상이므로 시드도 비식별 완료로 둔다.
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-MARKING-" + System.nanoTime(), "CCTV-001", "FIRE", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/marking-test.mp4",
                LocalDateTime.now(), 60);
        raw.markDeidentified("Y");
        raw = videoRepository.save(raw);
        rawSn = raw.getRawSn();

        // I4: WORKER(sub=100) 를 이 영상의 LABELER 로 배정 — 본인 배정 영상은 마킹 생성/조회 가능해야 한다.
        assignmentRepository.save(LsTaskAssignment.createLabeler(rawSn, WORKER_NO, 1L));
    }

    @Test
    @DisplayName("POST_마킹_자동모드_생성_201")
    void createAutoMode201() throws Exception {
        // given — intervalFrames=300 (30fps * 10sec), durationSec=60
        MarkingRequest req = new MarkingRequest("화재", "AUTO", 300, null);

        // when / then
        mockMvc.perform(post("/v1/videos/" + rawSn + "/markings")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.markingMode").value("AUTO"))
                .andExpect(jsonPath("$.data.eventName").value("화재"))
                .andExpect(jsonPath("$.data.intervalFrames").value(300))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.marks").isArray())
                .andExpect(jsonPath("$.data.marks.length()").value(7)); // 60*30=1800 / 300 + 1 = 7개

        // DB 검증
        List<LsMarking> saved = markingRepository.findByRawSnOrderByRegDtDesc(rawSn);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getMarkModeCd()).isEqualTo("AUTO");
    }

    @Test
    @DisplayName("POST_마킹_수동모드_생성_201")
    void createManualMode201() throws Exception {
        // given
        List<MarkItem> marks = List.of(
                new MarkItem(0, "00:00"),
                new MarkItem(150, "00:05"),
                new MarkItem(300, "00:10")
        );
        MarkingRequest req = new MarkingRequest("침입", "MANUAL", null, marks);

        // when / then
        mockMvc.perform(post("/v1/videos/" + rawSn + "/markings")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.markingMode").value("MANUAL"))
                .andExpect(jsonPath("$.data.eventName").value("침입"))
                .andExpect(jsonPath("$.data.marks.length()").value(3))
                .andExpect(jsonPath("$.data.marks[0].frameIndex").value(0))
                .andExpect(jsonPath("$.data.marks[2].frameIndex").value(300));
    }

    @Test
    @DisplayName("GET_마킹_목록_조회_200")
    void listMarkings200() throws Exception {
        // given — 마킹 2건 시드
        markingRepository.save(LsMarking.createAuto(rawSn, "화재", 10, "/path", "[]", 1L));
        markingRepository.save(LsMarking.createManual(rawSn, "침입", "/path",
                "[{\"frameIndex\":0,\"timestamp\":\"00:00\"}]", 1L));

        // when / then
        mockMvc.perform(get("/v1/videos/" + rawSn + "/markings")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("GET_마킹_단건_조회_200")
    void getSingleMarking200() throws Exception {
        // given
        LsMarking marking = markingRepository.save(
                LsMarking.createAuto(rawSn, "화재", 5, "/path",
                        "[{\"frameIndex\":0,\"timestamp\":\"00:00\"}]", 1L));

        // when / then
        mockMvc.perform(get("/v1/videos/" + rawSn + "/markings/" + marking.getMarkingSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.markingSn").value(marking.getMarkingSn()))
                .andExpect(jsonPath("$.data.eventName").value("화재"));
    }

    @Test
    @DisplayName("DELETE_마킹_삭제_REVIEWER_200")
    void deleteByReviewer200() throws Exception {
        // given
        LsMarking marking = markingRepository.save(
                LsMarking.createAuto(rawSn, "화재", 5, "/path", "[]", 1L));

        // when / then
        mockMvc.perform(delete("/v1/videos/" + rawSn + "/markings/" + marking.getMarkingSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // DB 검증
        assertThat(markingRepository.findById(marking.getMarkingSn())).isEmpty();
    }

    @Test
    @DisplayName("DELETE_마킹_삭제_WORKER_403")
    void deleteByWorker403() throws Exception {
        // given
        LsMarking marking = markingRepository.save(
                LsMarking.createAuto(rawSn, "화재", 5, "/path", "[]", 1L));

        // when / then
        mockMvc.perform(delete("/v1/videos/" + rawSn + "/markings/" + marking.getMarkingSn())
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("POST_미존재_영상_마킹_404")
    void createNonExistentVideo404() throws Exception {
        // given
        Long nonExistentRawSn = 999999L;
        MarkingRequest req = new MarkingRequest("화재", "AUTO", 10, null);

        // when / then
        mockMvc.perform(post("/v1/videos/" + nonExistentRawSn + "/markings")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("POST_eventName_빈값_400")
    void createBlankEventName400() throws Exception {
        // given
        MarkingRequest req = new MarkingRequest("", "AUTO", 10, null);

        // when / then
        mockMvc.perform(post("/v1/videos/" + rawSn + "/markings")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("미인증_요청_401")
    void unauthenticated401() throws Exception {
        // given
        MarkingRequest req = new MarkingRequest("화재", "AUTO", 10, null);

        // when / then — Authorization 헤더 없이 요청
        mockMvc.perform(post("/v1/videos/" + rawSn + "/markings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    // ── I4 (CWE-639) 수평 권한 상승 가드 — WORKER 는 본인 배정 영상만 ──

    @Test
    @DisplayName("I4_타인배정_영상_마킹생성_미배정_WORKER_403")
    void createMarkingUnassignedWorkerForbidden() throws Exception {
        // given — otherWorkerToken(sub=200) 은 이 영상의 LABELER 가 아니다 (배정자는 sub=100)
        MarkingRequest req = new MarkingRequest("화재", "AUTO", 300, null);

        // when / then — 미배정 WORKER 의 마킹 생성은 403
        mockMvc.perform(post("/v1/videos/" + rawSn + "/markings")
                        .header("Authorization", "Bearer " + otherWorkerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        // DB 검증 — 마킹이 생성되지 않아야 한다
        assertThat(markingRepository.findByRawSnOrderByRegDtDesc(rawSn)).isEmpty();
    }

    @Test
    @DisplayName("I4_타인배정_영상_마킹조회_미배정_WORKER_403")
    void listMarkingsUnassignedWorkerForbidden() throws Exception {
        // given — 영상에 마킹 1건 시드, otherWorkerToken(sub=200) 은 미배정
        markingRepository.save(LsMarking.createAuto(rawSn, "화재", 10, "/path", "[]", 1L));

        // when / then — 미배정 WORKER 의 목록 조회는 403 (마킹 노출 차단)
        mockMvc.perform(get("/v1/videos/" + rawSn + "/markings")
                        .header("Authorization", "Bearer " + otherWorkerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("I4_본인배정_영상_마킹조회_WORKER_200")
    void listMarkingsAssignedWorkerOk() throws Exception {
        // given — workerToken(sub=100) 은 setUp 에서 LABELER 배정됨, 마킹 1건 시드
        markingRepository.save(LsMarking.createAuto(rawSn, "화재", 10, "/path", "[]", 1L));

        // when / then — 본인 배정 WORKER 의 목록 조회는 200
        mockMvc.perform(get("/v1/videos/" + rawSn + "/markings")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("I4_본인배정_영상_마킹생성_WORKER_201")
    void createMarkingAssignedWorkerOk() throws Exception {
        // given — workerToken(sub=100) 본인 배정 영상
        MarkingRequest req = new MarkingRequest("침입", "MANUAL", null,
                List.of(new MarkItem(0, "00:00")));

        // when / then — 본인 배정 WORKER 의 마킹 생성은 201
        mockMvc.perform(post("/v1/videos/" + rawSn + "/markings")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("I4_REVIEWER_는_미배정여부_무관_마킹조회_200")
    void listMarkingsReviewerOkRegardlessOfAssignment() throws Exception {
        // given — 마킹 1건 시드, REVIEWER 는 배정 없이도 전체 허용
        markingRepository.save(LsMarking.createAuto(rawSn, "화재", 10, "/path", "[]", 1L));

        // when / then
        mockMvc.perform(get("/v1/videos/" + rawSn + "/markings")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("GET_단건_다른영상_마킹_접근시_403_IDOR_방어")
    void getMarkingDifferentVideo403() throws Exception {
        // given — 다른 영상에 생성된 마킹
        LsDataRaw otherRaw = videoRepository.save(LsDataRaw.createFromIngest(
                "CLIP-OTHER-" + System.nanoTime(), "CCTV-002", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/other.mp4", LocalDateTime.now(), 30));
        LsMarking otherMarking = markingRepository.save(
                LsMarking.createAuto(otherRaw.getRawSn(), "화재", 5, "/path", "[]", 1L));

        // when / then — rawSn 과 다른 영상의 marking 접근
        mockMvc.perform(get("/v1/videos/" + rawSn + "/markings/" + otherMarking.getMarkingSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }
}
