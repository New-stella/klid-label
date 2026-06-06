package kr.co.cudo.authoring.assignment;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
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
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 — REVIEWER 작업 목록 통합 BE 엔드포인트 (/v1/tasks/board) 검증.
 *
 * <p>처리 완료 영상(LS_DATA_RAW.DATA_STTS_CD='COMPLETED') 을 페이징 응답하면서
 * LEFT JOIN 으로 LABELER 배정 정보를 enrich 한다. 미배정 영상도 함께 노출되어
 * REVIEWER 가 단건/일괄 배정 액션을 수행할 수 있어야 한다.
 *
 * <p>보안: REVIEWER 권한 이중 가드 — Controller @PreAuthorize + Service requireReviewer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class TaskBoardControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;
    @Autowired private LsRawDataStatusRepository dataSttsRepository;
    @Autowired private LsDataSrcRepository dataSrcRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1",   "REVIEWER", "INTERNAL", issuer, 60);
        workerToken   = JwtTestSupport.token(secret, "100", "WORKER",   "INTERNAL", issuer, 60);
    }

    private LsDataRaw seedCompletedVideo(String clipId, String cctvId, String evntType) {
        return seedVideoWithStatus(clipId, cctvId, evntType, "COMPLETED");
    }

    private LsDataRaw seedVideoWithStatus(String clipId, String cctvId, String evntType, String status) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                clipId, cctvId, evntType, "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                LocalDateTime.now(), 30);
        raw = videoRepository.save(raw);
        raw.changeStatus(status);
        videoRepository.save(raw);
        return raw;
    }

    @Test
    @DisplayName("GET_v1_tasks_board_status_COMPLETED_page_0_size_20_정상_페이징_응답")
    void completedVideosPaging() throws Exception {
        seedCompletedVideo("CLIP-BOARD-1", "CCTV-001", "EVT-FIRE");
        seedCompletedVideo("CLIP-BOARD-2", "CCTV-002", "EVT-INTRUSION");

        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.number").value(0));
    }

    @Test
    @DisplayName("미배정_영상도_포함_left_join_task가_null인_케이스")
    void unassignedVideoIncluded() throws Exception {
        LsDataRaw raw = seedCompletedVideo("CLIP-BOARD-UNASSIGN", "CCTV-001", "EVT-FALL");

        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].videoId").value(raw.getRawSn()))
                .andExpect(jsonPath("$.data.content[0].assignmentId").isEmpty())
                .andExpect(jsonPath("$.data.content[0].workerId").isEmpty())
                .andExpect(jsonPath("$.data.content[0].workerName").isEmpty())
                .andExpect(jsonPath("$.data.content[0].reviewerId").isEmpty())
                .andExpect(jsonPath("$.data.content[0].status").value("UNASSIGNED"));
    }

    @Test
    @DisplayName("REVIEWER_외_역할은_403_WORKER_토큰_거부")
    void workerCannotAccess() throws Exception {
        seedCompletedVideo("CLIP-BOARD-FORBID", "CCTV-001", "EVT-FIRE");
        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&page=0&size=20")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("인증_토큰_없으면_401_UNAUTHORIZED")
    void unauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&page=0&size=20"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("cctvName_workerName_eventName_status_모두_enrich되어_반환")
    void enrichedFieldsPopulated() throws Exception {
        LsDataRaw raw = seedCompletedVideo("CLIP-BOARD-ENR", "CCTV-001", "EVT-FIRE");

        authrtRepository.save(LsTaskAssignment.createLabeler(raw.getRawSn(), 100L, 1L));
        LsRawDataStatus stts = LsRawDataStatus.initial(raw.getRawSn());
        stts.transitionTo(LsRawDataStatus.STTS_APPROVED);
        dataSttsRepository.save(stts);

        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].videoId").value(raw.getRawSn()))
                .andExpect(jsonPath("$.data.content[0].cctvName").value("동대문구 회기로 CCTV"))
                .andExpect(jsonPath("$.data.content[0].eventTypeCd").value("EVT-FIRE"))
                .andExpect(jsonPath("$.data.content[0].workerId").value(100))
                .andExpect(jsonPath("$.data.content[0].workerName").value("작업자100"))
                .andExpect(jsonPath("$.data.content[0].status").value("COMPLETED"));
    }

    @Test
    @DisplayName("status_파라미터_허용되지_않은_값이면_400_Bad_Request")
    void invalidStatusRejected() throws Exception {
        mockMvc.perform(get("/v1/tasks/board?status=DROP_TABLE&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("I2_status_UNASSIGNED_요청시_200_미배정_영상만_반환_배정된_영상은_제외")
    void unassignedStatusReturnsOnlyUnassigned() throws Exception {
        // given: 처리 완료 영상 2건 — 하나는 LABELER 배정됨, 하나는 미배정
        LsDataRaw assigned = seedCompletedVideo("CLIP-UA-ASSIGNED", "CCTV-001", "EVT-FIRE");
        LsDataRaw unassigned = seedCompletedVideo("CLIP-UA-FREE", "CCTV-002", "EVT-FALL");
        authrtRepository.save(LsTaskAssignment.createLabeler(assigned.getRawSn(), 100L, 1L));

        // when / then: UNASSIGNED 필터는 400 이 아니라 200, 미배정 영상 1건만 반환
        mockMvc.perform(get("/v1/tasks/board?status=UNASSIGNED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].videoId").value(unassigned.getRawSn()))
                .andExpect(jsonPath("$.data.content[0].status").value("UNASSIGNED"))
                .andExpect(jsonPath("$.data.content[0].workerId").isEmpty());
    }

    @Test
    @DisplayName("I2_미배정_영상이_없으면_UNASSIGNED_빈_페이지_반환")
    void unassignedStatusEmptyWhenAllAssigned() throws Exception {
        LsDataRaw v = seedCompletedVideo("CLIP-UA-ALL", "CCTV-001", "EVT-FIRE");
        authrtRepository.save(LsTaskAssignment.createLabeler(v.getRawSn(), 100L, 1L));

        mockMvc.perform(get("/v1/tasks/board?status=UNASSIGNED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("I1_업로드직후_PENDING_영상도_상태무관_UNASSIGNED_목록에_포함")
    void unassignedIncludesNonCompletedVideos() throws Exception {
        // given: 막 업로드되어 아직 처리 전(PENDING)이며 LABELER 배정 없는 영상
        LsDataRaw pending = seedVideoWithStatus("CLIP-UA-PENDING", "CCTV-001", "EVT-FIRE", "PENDING");
        // 그리고 COMPLETED 미배정 영상 1건
        LsDataRaw completed = seedCompletedVideo("CLIP-UA-DONE", "CCTV-002", "EVT-FALL");

        // when / then: UNASSIGNED 는 상태 무관 — PENDING + COMPLETED 둘 다 포함 (과거엔 COMPLETED 만)
        mockMvc.perform(get("/v1/tasks/board?status=UNASSIGNED&page=0&size=50")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath(
                        "$.data.content[?(@.videoId==" + pending.getRawSn() + ")].batchStatus")
                        .value("PENDING"))
                .andExpect(jsonPath(
                        "$.data.content[?(@.videoId==" + completed.getRawSn() + ")].batchStatus")
                        .value("COMPLETED"));
    }

    @Test
    @DisplayName("I1_FAILED_영상도_상태무관_UNASSIGNED_목록에_포함_상태뱃지_노출")
    void unassignedIncludesFailedVideos() throws Exception {
        // given: 배치 실패(FAILED) 미배정 영상
        LsDataRaw failed = seedVideoWithStatus("CLIP-UA-FAILED", "CCTV-003", "EVT-INTRUSION", "FAILED");

        // when / then: FAILED 도 UNASSIGNED 목록에 포함되고 batchStatus 로 구분 가능
        mockMvc.perform(get("/v1/tasks/board?status=UNASSIGNED&page=0&size=50")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].videoId").value(failed.getRawSn()))
                .andExpect(jsonPath("$.data.content[0].batchStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.content[0].status").value("UNASSIGNED"));
    }

    @Test
    @DisplayName("I1_배정된_영상은_상태무관_UNASSIGNED_목록에서_제외")
    void unassignedExcludesAssignedRegardlessOfStatus() throws Exception {
        // given: PENDING 이지만 LABELER 배정된 영상 + PENDING 미배정 영상
        LsDataRaw assigned = seedVideoWithStatus("CLIP-UA-ASG", "CCTV-001", "EVT-FIRE", "PENDING");
        LsDataRaw free = seedVideoWithStatus("CLIP-UA-FREE2", "CCTV-002", "EVT-FALL", "PENDING");
        authrtRepository.save(LsTaskAssignment.createLabeler(assigned.getRawSn(), 100L, 1L));

        // when / then: 미배정(free) 1건만 반환
        mockMvc.perform(get("/v1/tasks/board?status=UNASSIGNED&page=0&size=50")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].videoId").value(free.getRawSn()));
    }

    @Test
    @DisplayName("Pageable_size_100_초과시_상한_100으로_캡")
    void pageableSizeCappedAt100() throws Exception {
        // size=500 요청 → spring.data.web.pageable.max-page-size=100 으로 캡됨.
        // 응답 data.size 가 100 으로 내려와야 한다 (요청한 500 이 아니라).
        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&page=0&size=500")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
    }

    @Test
    @DisplayName("frameCount_batch_GROUP_BY_정상_매핑")
    void frameCountBatchMapping() throws Exception {
        LsDataRaw raw1 = seedCompletedVideo("CLIP-FC-1", "CCTV-001", "EVT-FIRE");
        LsDataRaw raw2 = seedCompletedVideo("CLIP-FC-2", "CCTV-002", "EVT-FIRE");
        LsDataRaw raw3 = seedCompletedVideo("CLIP-FC-3", "CCTV-003", "EVT-FIRE");

        // raw1: 3 프레임 / raw2: 1 프레임 / raw3: 0 프레임 (DB 결과에 없음 → 0L 폴백)
        for (int i = 0; i < 3; i++) {
            dataSrcRepository.save(LsDataSrc.create(raw1.getRawSn(), i,
                    "frames/" + raw1.getRawSn() + "/f" + i + ".jpg", LocalDateTime.now()));
        }
        dataSrcRepository.save(LsDataSrc.create(raw2.getRawSn(), 0,
                "frames/" + raw2.getRawSn() + "/f0.jpg", LocalDateTime.now()));

        // 응답에서 각 영상의 frameCount 가 정확히 매핑되는지 확인.
        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                // CLIP-FC-1, FC-2, FC-3 의 videoId 별로 frameCount 검증
                .andExpect(jsonPath(
                        "$.data.content[?(@.videoId==" + raw1.getRawSn() + ")].frameCount")
                        .value(3))
                .andExpect(jsonPath(
                        "$.data.content[?(@.videoId==" + raw2.getRawSn() + ")].frameCount")
                        .value(1))
                .andExpect(jsonPath(
                        "$.data.content[?(@.videoId==" + raw3.getRawSn() + ")].frameCount")
                        .value(0));
    }

    @Test
    @DisplayName("REVIEWER_배정자_정보도_enrich되어_reviewerId_reviewerName_반환")
    void reviewerInfoEnriched() throws Exception {
        LsDataRaw raw = seedCompletedVideo("CLIP-BOARD-REV", "CCTV-002", "EVT-INTRUSION");

        authrtRepository.save(LsTaskAssignment.createLabeler(raw.getRawSn(), 100L, 1L));
        authrtRepository.save(LsTaskAssignment.createReviewer(raw.getRawSn(), 1L, 1L));

        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].reviewerId").value(1))
                .andExpect(jsonPath("$.data.content[0].reviewerName").value("검수자1"));
    }
}
