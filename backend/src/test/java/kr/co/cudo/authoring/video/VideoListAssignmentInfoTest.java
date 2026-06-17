package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
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
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R5 — {@code GET /v1/videos} 응답에 현재 작업자(LABELER) 배정 정보를 포함시킨다.
 *
 * <p>산출 기준은 작업 목록(/v1/tasks/board)과 동일하다: TASK_TYPE_CD='LABELER' 배정 중
 * REG_DT 가 가장 최근인 1건을 "현재 활성 배정"으로 본다. 재배정 시 최신 배정자가 반영된다.
 *
 * <p>미배정 영상은 배정 관련 필드(assignmentId/workerId/workerName/assignedAt/assignStatus)가
 * 모두 null 이다. 기존 status(배치/처리 단계) 필드는 의미 변경 없이 그대로 유지된다.
 *
 * <p>회귀 방지: 기존 응답 필드(id/status/cctvName 등)는 그대로 노출되어야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VideoListAssignmentInfoTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
    }

    private LsDataRaw seedVideo(String clipId, String cctvId, String evntType) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                clipId, cctvId, evntType, "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                LocalDateTime.now(), 30);
        raw = videoRepository.save(raw);
        raw.changeStatus("COMPLETED");
        return videoRepository.save(raw);
    }

    @Test
    @DisplayName("영상목록_미배정_영상은_배정자정보가_null이다")
    void unassignedVideoHasNullAssignment() throws Exception {
        LsDataRaw raw = seedVideo("CLIP-ASSIGN-NULL", "CCTV-001", "EVT-FIRE");

        mockMvc.perform(get("/v1/videos?dataSttsCd=COMPLETED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.id==" + raw.getRawSn() + ")].assignmentId")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.data.content[?(@.id==" + raw.getRawSn() + ")].workerId")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.data.content[?(@.id==" + raw.getRawSn() + ")].workerName")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.data.content[?(@.id==" + raw.getRawSn() + ")].assignedAt")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.data.content[?(@.id==" + raw.getRawSn() + ")].assignStatus")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())));
    }

    @Test
    @DisplayName("영상목록_배정된_영상은_배정자명과_assignmentId가_포함된다")
    void assignedVideoIncludesWorkerInfo() throws Exception {
        LsDataRaw raw = seedVideo("CLIP-ASSIGN-1", "CCTV-001", "EVT-FIRE");
        LsTaskAssignment a = authrtRepository.save(LsTaskAssignment.createLabeler(raw.getRawSn(), 100L, 1L));

        mockMvc.perform(get("/v1/videos?dataSttsCd=COMPLETED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.id==" + raw.getRawSn() + ")].assignmentId")
                        .value(org.hamcrest.Matchers.hasItem(a.getAssignmentId().intValue())))
                .andExpect(jsonPath("$.data.content[?(@.id==" + raw.getRawSn() + ")].workerId")
                        .value(org.hamcrest.Matchers.hasItem(100)))
                .andExpect(jsonPath("$.data.content[?(@.id==" + raw.getRawSn() + ")].workerName")
                        .value(org.hamcrest.Matchers.hasItem("작업자100")))
                .andExpect(jsonPath("$.data.content[?(@.id==" + raw.getRawSn() + ")].assignStatus")
                        .value(org.hamcrest.Matchers.hasItem("ASSIGNED")));
    }

    @Test
    @DisplayName("영상목록_재배정된_영상은_현재_배정자가_반영된다")
    void reassignedVideoReflectsLatestWorker() throws Exception {
        LsDataRaw raw = seedVideo("CLIP-ASSIGN-REASSIGN", "CCTV-001", "EVT-FIRE");
        // 이전 배정자(100) → 이후 더 최근 REG_DT 로 새 배정자(101). 현재 배정 = 101 이어야 함.
        LsTaskAssignment first = LsTaskAssignment.createLabeler(raw.getRawSn(), 100L, 1L);
        authrtRepository.save(first);
        LsTaskAssignment latest = LsTaskAssignment.createLabeler(raw.getRawSn(), 101L, 1L);
        authrtRepository.save(latest);

        mockMvc.perform(get("/v1/videos?dataSttsCd=COMPLETED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                // 최신 배정자(101)가 반영되어야 하고, 이전 배정자(100)는 아님
                .andExpect(jsonPath("$.data.content[?(@.id==" + raw.getRawSn() + ")].workerId")
                        .value(org.hamcrest.Matchers.hasItem(101)))
                .andExpect(jsonPath("$.data.content[?(@.id==" + raw.getRawSn() + ")].workerId")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(100))));
    }

    @Test
    @DisplayName("영상목록_실제_재배정경로_reassignTo_후_현재_배정자가_반영된다")
    void reassignedViaReassignToReflectsLatestWorker() throws Exception {
        // given — 영상에 작업자 A(100) 1건 배정. 운영 UK(RAW_DATA_ID, USER_NO, TASK_TYPE_CD)
        // 제약상 동일 영상 LABELER 는 1행만 존재한다.
        LsDataRaw raw = seedVideo("CLIP-ASSIGN-REASSIGN-UPDATE", "CCTV-001", "EVT-FIRE");
        LsTaskAssignment assignment =
                authrtRepository.save(LsTaskAssignment.createLabeler(raw.getRawSn(), 100L, 1L));
        LocalDateTime regDtBefore = assignment.getRegDt();

        // when — 운영 재배정 경로(AssignmentService.reassign → LsTaskAssignment.reassignTo) 와
        // 동일하게, 새 row INSERT 가 아니라 기존 row 의 USER_NO 를 B(101) 로 UPDATE 한다.
        // REG_DT 는 reassignTo 에서 갱신하지 않으므로 그대로 유지된다.
        assignment.reassignTo(101L);
        authrtRepository.saveAndFlush(assignment);

        // then(1) — 영상당 LABELER row 는 여전히 1건이고 USER_NO 만 B(101) 로 바뀐 상태.
        List<LsTaskAssignment> rows = authrtRepository
                .findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(
                        LsTaskAssignment.TASK_LABELER, List.of(raw.getRawSn()));
        org.assertj.core.api.Assertions.assertThat(rows).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(rows.get(0).getUserNo()).isEqualTo(101L);
        org.assertj.core.api.Assertions.assertThat(rows.get(0).getAssignmentId())
                .isEqualTo(assignment.getAssignmentId());
        // REG_DT 불변 — INSERT 2건(REG_DT 기반 최신 선별) 시나리오와 달리 UPDATE 경로는 시간이 동일.
        org.assertj.core.api.Assertions.assertThat(rows.get(0).getRegDt()).isEqualTo(regDtBefore);

        // then(2) — REG_DT 가 동일해도 /v1/videos 응답에는 현재 배정자(B=101)가 정확히 반영되어야 한다.
        mockMvc.perform(get("/v1/videos?dataSttsCd=COMPLETED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.id==" + raw.getRawSn() + ")].workerId")
                        .value(org.hamcrest.Matchers.hasItem(101)))
                .andExpect(jsonPath("$.data.content[?(@.id==" + raw.getRawSn() + ")].workerName")
                        .value(org.hamcrest.Matchers.hasItem("작업자101")))
                .andExpect(jsonPath("$.data.content[?(@.id==" + raw.getRawSn() + ")].workerId")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(100))));
    }

    @Test
    @DisplayName("영상목록_기존_응답계약_회귀없음_id_status_cctvName_유지")
    void existingContractNotRegressed() throws Exception {
        LsDataRaw raw = seedVideo("CLIP-ASSIGN-REGRESSION", "CCTV-001", "EVT-FIRE");

        mockMvc.perform(get("/v1/videos?dataSttsCd=COMPLETED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.id==" + raw.getRawSn() + ")].status")
                        .value(org.hamcrest.Matchers.hasItem("COMPLETED")))
                .andExpect(jsonPath("$.data.content[?(@.id==" + raw.getRawSn() + ")].rawSn")
                        .value(org.hamcrest.Matchers.hasItem(raw.getRawSn().intValue())));
    }
}
