package kr.co.cudo.authoring.assignment;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.dto.AssignmentCreateRequest;
import kr.co.cudo.authoring.assignment.dto.ReassignRequest;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = "/db/test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AssignmentControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LsTaskAssignmentRepository authrtRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;
    private String otherWorkerToken;

    @BeforeEach
    void setup() {
        reviewerToken    = JwtTestSupport.token(secret, "1",   "REVIEWER", "INTERNAL", issuer, 60);
        workerToken      = JwtTestSupport.token(secret, "100", "WORKER",   "INTERNAL", issuer, 60);
        otherWorkerToken = JwtTestSupport.token(secret, "101", "WORKER",   "INTERNAL", issuer, 60);
    }

    @Test
    @DisplayName("WORKER가_assignments_POST_호출시_403")
    void workerCannotAssign() throws Exception {
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1000L));
        mockMvc.perform(post("/v1/assignments")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("REVIEWER가_배정_시_LS_TASK_ALTMNT에_LABELER_INSERT")
    void reviewerAssignsLabeler() throws Exception {
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1000L, 1001L));
        mockMvc.perform(post("/v1/assignments")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].taskTypeCd").value("LABELER"));

        List<LsTaskAssignment> all = authrtRepository.findAll();
        assertThat(all).hasSize(2);
        assertThat(all).allSatisfy(a -> {
            assertThat(a.getTaskTypeCd()).isEqualTo("LABELER");
            assertThat(a.getUserNo()).isEqualTo(100L);
            assertThat(a.getRegUserNo()).isEqualTo(1L);
        });
    }

    /**
     * <b>하위호환</b> — 검수자 항목을 없앤 뒤에도(ADR-067) 옛 호출자가 그것을 그대로 보내면
     * <b>무시하고 성공</b>한다. 400 으로 거부하면 옵션 항목이었던 것 때문에 기존 호출만 깨진다.
     *
     * <p>요청 본문을 DTO 로 만들지 않고 <b>생 JSON</b>으로 쓴다 — DTO 에는 그 필드가 이미 없어서
     * 직렬화로는 옛 호출자를 재현할 수 없고, 재현하지 못하면 이 가드는 아무것도 지키지 못한다.
     */
    @Test
    @DisplayName("옛_호출자가_검수자_항목을_보내도_무시하고_201이며_검수자_배정_행도_안_생긴다")
    void legacyReviewerIdIsIgnoredNotRejected() throws Exception {
        String legacyBody = """
                {"workerId":100,"rawDataIds":[1000],"reviewerId":1}
                """;

        mockMvc.perform(post("/v1/assignments")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(legacyBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                // 응답에서도 검수자 축이 사라졌다(필드 자체가 없다).
                .andExpect(jsonPath("$.data.items[0].reviewerId").doesNotExist());

        assertThat(authrtRepository.findAll())
                .as("검수자 배정 행은 새로 생기지 않는다")
                .allSatisfy(a -> assertThat(a.getTaskTypeCd()).isEqualTo(LsTaskAssignment.TASK_LABELER));
    }

    /**
     * 재배정 API 가 <b>이력을 남긴다</b>는 불변식. 구 {@code LS_TASK_ASSIGN_HISTORY} 이중 쓰기가 V4 로
     * 제거돼 단독 적재처인 {@code LS_TASK_EVNT_LOG} 로 단언을 이관했다(검증 유실 없음). 여기서는
     * 리포지토리를 직접 읽지 않고 <b>이력 조회 API 응답</b>으로 확인한다 — 실제 소비자가 그 경로이고,
     * 제거로 조회 결과가 달라지지 않았음을 같은 단언이 함께 증명한다.
     */
    @Test
    @DisplayName("재배정하면_이력_조회_API에_REASSIGN이_추가로_보인다")
    void reassignWritesHistory() throws Exception {
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1000L));
        String body = mockMvc.perform(post("/v1/assignments")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long authrtSeq = objectMapper.readTree(body).path("data").path("items").get(0).path("authrtSeq").asLong();

        ReassignRequest reassignReq = new ReassignRequest(101L);
        mockMvc.perform(patch("/v1/assignments/" + authrtSeq)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reassignReq)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/assignments/" + authrtSeq + "/history")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].eventTypeCd").value("ASSIGN"))
                .andExpect(jsonPath("$.data[1].eventTypeCd").value("REASSIGN"))
                .andExpect(jsonPath("$.data[1].actorUserNo").value(1))
                .andExpect(jsonPath("$.data[1].prevUserNo").value(100))
                .andExpect(jsonPath("$.data[1].subjectUserNo").value(101));
        assertThat(authrtRepository.findById(authrtSeq).orElseThrow().getUserNo()).isEqualTo(101L);
    }

    @Test
    @DisplayName("WORKER의_listAssignments는_본인_배정만_반환")
    void workerListsOnlyOwn() throws Exception {
        // 사전 데이터: worker100, worker101 각각 1건씩 배정
        AssignmentCreateRequest req1 = new AssignmentCreateRequest(100L, List.of(1000L));
        AssignmentCreateRequest req2 = new AssignmentCreateRequest(101L, List.of(1001L));
        mockMvc.perform(post("/v1/assignments")
                .header("Authorization", "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req1))).andExpect(status().isCreated());
        mockMvc.perform(post("/v1/assignments")
                .header("Authorization", "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req2))).andExpect(status().isCreated());

        // worker100 본인 토큰으로 조회
        mockMvc.perform(get("/v1/assignments")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].workerId").value(100));
    }

    @Test
    @DisplayName("WORKER가_다른_workerId_조회시_본인_데이터만_반환")
    void workerCannotEscalateViaWorkerIdParam() throws Exception {
        AssignmentCreateRequest req = new AssignmentCreateRequest(101L, List.of(1001L));
        mockMvc.perform(post("/v1/assignments")
                .header("Authorization", "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isCreated());

        // worker100 이 workerId=101 을 파라미터로 줘도 본인(100) 데이터만 반환
        mockMvc.perform(get("/v1/assignments?workerId=101")
                        .header("Authorization", "Bearer " + otherWorkerToken)) // 101 본인
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].workerId").value(101));

        mockMvc.perform(get("/v1/assignments?workerId=101")
                        .header("Authorization", "Bearer " + workerToken)) // 100, 101 데이터 요청해도 본인거(100)만
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0));
    }

    @Test
    @DisplayName("list_기본_sort_regDt_desc_적용_최신_배정이_먼저")
    void listDefaultSortIsRegDtDesc() throws Exception {
        // worker100 에 영상을 3건 시간차로 배정 (1000 → 1001 → 1002 순)
        AssignmentCreateRequest r1 = new AssignmentCreateRequest(100L, List.of(1000L));
        AssignmentCreateRequest r2 = new AssignmentCreateRequest(100L, List.of(1001L));
        AssignmentCreateRequest r3 = new AssignmentCreateRequest(100L, List.of(1002L));
        mockMvc.perform(post("/v1/assignments")
                .header("Authorization", "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(r1))).andExpect(status().isCreated());
        Thread.sleep(10);
        mockMvc.perform(post("/v1/assignments")
                .header("Authorization", "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(r2))).andExpect(status().isCreated());
        Thread.sleep(10);
        mockMvc.perform(post("/v1/assignments")
                .header("Authorization", "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(r3))).andExpect(status().isCreated());

        // sort 파라미터 미지정 → 기본 regDt DESC → 가장 최근(r3=videoId 1002) 이 첫 번째
        mockMvc.perform(get("/v1/assignments")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(3))
                .andExpect(jsonPath("$.data.content[0].videoId").value(1002))
                .andExpect(jsonPath("$.data.content[1].videoId").value(1001))
                .andExpect(jsonPath("$.data.content[2].videoId").value(1000));
    }

    @Test
    @DisplayName("list_sort_파라미터_명시시_override_적용")
    void listExplicitSortOverridesDefault() throws Exception {
        AssignmentCreateRequest r1 = new AssignmentCreateRequest(100L, List.of(1000L));
        AssignmentCreateRequest r2 = new AssignmentCreateRequest(100L, List.of(1001L));
        mockMvc.perform(post("/v1/assignments")
                .header("Authorization", "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(r1))).andExpect(status().isCreated());
        Thread.sleep(10);
        mockMvc.perform(post("/v1/assignments")
                .header("Authorization", "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(r2))).andExpect(status().isCreated());

        // sort=regDt,asc 명시 → 오름차순으로 override
        mockMvc.perform(get("/v1/assignments?sort=regDt,asc")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].videoId").value(1000))
                .andExpect(jsonPath("$.data.content[1].videoId").value(1001));
    }

    @Test
    @DisplayName("존재하지_않는_workerId_배정시_INVALID_INPUT")
    void unknownWorkerRejected() throws Exception {
        AssignmentCreateRequest req = new AssignmentCreateRequest(99999L, List.of(1000L));
        mockMvc.perform(post("/v1/assignments")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("PATCH로_reassign시_정상_동작_및_workerId_갱신")
    void patchReassignSucceeds() throws Exception {
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1000L));
        String body = mockMvc.perform(post("/v1/assignments")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long authrtSeq = objectMapper.readTree(body).path("data").path("items").get(0).path("authrtSeq").asLong();

        ReassignRequest reassignReq = new ReassignRequest(101L);
        mockMvc.perform(patch("/v1/assignments/" + authrtSeq)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reassignReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].workerId").value(101));

        assertThat(authrtRepository.findById(authrtSeq).orElseThrow().getUserNo()).isEqualTo(101L);
    }

    @Test
    @DisplayName("WORKER가_본인_배정_이력_조회시_200")
    void workerCanReadOwnHistory() throws Exception {
        // worker100 에게 1000 영상 배정
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1000L));
        String body = mockMvc.perform(post("/v1/assignments")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long authrtSeq = objectMapper.readTree(body).path("data").path("items").get(0).path("authrtSeq").asLong();

        // worker100 본인 토큰으로 이력 조회 → 200
        mockMvc.perform(get("/v1/assignments/" + authrtSeq + "/history")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].eventTypeCd").value("ASSIGN"));
    }

    @Test
    @DisplayName("WORKER가_다른_작업자_배정_이력_조회시_403_FORBIDDEN_IDOR_방어")
    void workerCannotReadOthersHistory() throws Exception {
        // worker101 에게 1001 영상 배정
        AssignmentCreateRequest req = new AssignmentCreateRequest(101L, List.of(1001L));
        String body = mockMvc.perform(post("/v1/assignments")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long authrtSeq = objectMapper.readTree(body).path("data").path("items").get(0).path("authrtSeq").asLong();

        // worker100 토큰으로 worker101 의 배정 이력 조회 → 403
        mockMvc.perform(get("/v1/assignments/" + authrtSeq + "/history")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("REVIEWER는_모든_배정_이력_조회_가능_200")
    void reviewerCanReadAnyHistory() throws Exception {
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1000L));
        String body = mockMvc.perform(post("/v1/assignments")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long authrtSeq = objectMapper.readTree(body).path("data").path("items").get(0).path("authrtSeq").asLong();

        mockMvc.perform(get("/v1/assignments/" + authrtSeq + "/history")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("한_영상에_같은_작업자_중복_배정시_409_CONFLICT")
    void duplicateAssignmentConflict() throws Exception {
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1000L));
        mockMvc.perform(post("/v1/assignments")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/v1/assignments")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }
}
