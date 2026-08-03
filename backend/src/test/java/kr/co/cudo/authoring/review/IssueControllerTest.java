package kr.co.cudo.authoring.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.review.dto.IssueCommentRequest;
import kr.co.cudo.authoring.review.dto.IssueCreateRequest;
import kr.co.cudo.authoring.review.entity.LsDataIssue;
import kr.co.cudo.authoring.review.repository.IssueRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class IssueControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsTaskAssignmentRepository assignmentRepository;
    @Autowired private IssueRepository issueRepository;
    @Autowired private LsDataSrcRepository srcRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;       // user 1
    private String workerAssigned;      // user 100
    private String workerNotAssigned;   // user 101

    private Long videoId;

    @BeforeEach
    void setup() {
        reviewerToken     = JwtTestSupport.token(secret, "1",   "REVIEWER", "INTERNAL", issuer, 60);
        workerAssigned    = JwtTestSupport.token(secret, "100", "WORKER",   "INTERNAL", issuer, 60);
        workerNotAssigned = JwtTestSupport.token(secret, "101", "WORKER",   "INTERNAL", issuer, 60);

        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-ISS-001", "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/iss.mp4",
                LocalDateTime.now(), 30);
        raw = rawRepository.save(raw);
        videoId = raw.getRawSn();

        assignmentRepository.save(LsTaskAssignment.createLabeler(videoId, 100L, 1L));
    }

    private Long createInquiry(String token) throws Exception {
        IssueCreateRequest req = new IssueCreateRequest("이 프레임 라벨이 맞나요?", null);
        String body = mockMvc.perform(post("/v1/videos/" + videoId + "/issues")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("issueSn").asLong();
    }

    @Test
    @DisplayName("작업자가_본인_배정_영상에_문의_등록시_201_INQUIRY_OPEN")
    void workerCreateInquiryOnAssignedVideo() throws Exception {
        IssueCreateRequest req = new IssueCreateRequest("라벨 확인 요청", null);
        mockMvc.perform(post("/v1/videos/" + videoId + "/issues")
                        .header("Authorization", "Bearer " + workerAssigned)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.issueTypeCd").value("INQUIRY"))
                .andExpect(jsonPath("$.data.issueSttsCd").value("OPEN"))
                .andExpect(jsonPath("$.data.reportedUserNo").value("100"));
    }

    @Test
    @DisplayName("작업자가_타인_배정_영상에_문의_등록시_403")
    void workerCreateInquiryOnOthersVideoForbidden() throws Exception {
        IssueCreateRequest req = new IssueCreateRequest("권한 없는 문의", null);
        mockMvc.perform(post("/v1/videos/" + videoId + "/issues")
                        .header("Authorization", "Bearer " + workerNotAssigned)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("검수자가_댓글_작성시_OPEN_문의가_ANSWERED로_전이")
    void reviewerCommentTransitionsToAnswered() throws Exception {
        Long issueSn = createInquiry(workerAssigned);
        IssueCommentRequest req = new IssueCommentRequest("네 맞습니다.");
        mockMvc.perform(post("/v1/issues/" + issueSn + "/comments")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.authorRoleCd").value("REVIEWER"));

        LsDataIssue after = issueRepository.findById(issueSn).orElseThrow();
        assertThat(after.getIssueSttsCd()).isEqualTo("ANSWERED");
    }

    @Test
    @DisplayName("스레드_목록에_작성자_이름이_내려간다_사번역할은_그대로")
    void threadListExposesAuthorNames() throws Exception {
        // given — 작업자100 이 문의, 검수자1 이 답변
        Long issueSn = createInquiry(workerAssigned);
        mockMvc.perform(post("/v1/issues/" + issueSn + "/comments")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new IssueCommentRequest("네 맞습니다."))))
                .andExpect(status().isCreated())
                // 등록 응답에도 동일 필드가 채워져야 한다(GET 과 형태 일치)
                .andExpect(jsonPath("$.data.authorName").value("검수자1"));

        // when / then — 기존 필드(authorNo/authorRoleCd/reportedUserNo)는 불변, 이름만 추가
        mockMvc.perform(get("/v1/videos/" + videoId + "/issues")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].reportedUserNo").value("100"))
                .andExpect(jsonPath("$.data[0].reportedUserName").value("작업자100"))
                .andExpect(jsonPath("$.data[0].comments[0].authorNo").value("1"))
                .andExpect(jsonPath("$.data[0].comments[0].authorRoleCd").value("REVIEWER"))
                .andExpect(jsonPath("$.data[0].comments[0].authorName").value("검수자1"));
    }

    @Test
    @DisplayName("사용자마스터에_없는_작성자여도_스레드_조회는_200이고_이름은_null")
    void threadListSurvivesUnknownAuthor() throws Exception {
        // given — 사용자 마스터에 없는 사번(9999)으로 작성된 반려 이슈
        issueRepository.save(LsDataIssue.create(videoId, "반려 사유", "9999"));

        // when / then — 이름 매핑 실패가 조회 전체를 죽이면 안 된다
        mockMvc.perform(get("/v1/videos/" + videoId + "/issues")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].reportedUserNo").value("9999"))
                .andExpect(jsonPath("$.data[0].reportedUserName").isEmpty());
    }

    @Test
    @DisplayName("작업자_댓글은_상태_전이_없음")
    void workerCommentDoesNotTransition() throws Exception {
        Long issueSn = createInquiry(workerAssigned);
        IssueCommentRequest req = new IssueCommentRequest("추가 설명입니다.");
        mockMvc.perform(post("/v1/issues/" + issueSn + "/comments")
                        .header("Authorization", "Bearer " + workerAssigned)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        LsDataIssue after = issueRepository.findById(issueSn).orElseThrow();
        assertThat(after.getIssueSttsCd()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("검수자가_resolve시_RESOLVED_재호출은_멱등")
    void resolveIsIdempotent() throws Exception {
        Long issueSn = createInquiry(workerAssigned);
        mockMvc.perform(post("/v1/issues/" + issueSn + "/resolve")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
        // 멱등 — 재호출도 200
        mockMvc.perform(post("/v1/issues/" + issueSn + "/resolve")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());

        LsDataIssue after = issueRepository.findById(issueSn).orElseThrow();
        assertThat(after.getIssueSttsCd()).isEqualTo("RESOLVED");
    }

    @Test
    @DisplayName("RESOLVED_문의에_댓글_작성시_409")
    void commentOnResolvedInquiryConflict() throws Exception {
        Long issueSn = createInquiry(workerAssigned);
        mockMvc.perform(post("/v1/issues/" + issueSn + "/resolve")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());

        IssueCommentRequest req = new IssueCommentRequest("해소 후 댓글");
        mockMvc.perform(post("/v1/issues/" + issueSn + "/comments")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    @DisplayName("반려_이슈에는_RESOLVED여도_댓글_허용_상태전이_없음")
    void rejectionIssueAllowsCommentEvenWhenResolved() throws Exception {
        // 반려 이슈 직접 시드 (REJECTION/RESOLVED 고정)
        LsDataIssue rejection = issueRepository.save(
                LsDataIssue.create(videoId, "반려 사유", "1"));
        Long issueSn = rejection.getDataIssueSn();

        IssueCommentRequest req = new IssueCommentRequest("반려 관련 문의드립니다.");
        mockMvc.perform(post("/v1/issues/" + issueSn + "/comments")
                        .header("Authorization", "Bearer " + workerAssigned)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        LsDataIssue after = issueRepository.findById(issueSn).orElseThrow();
        assertThat(after.getIssueTypeCd()).isEqualTo("REJECTION");
        assertThat(after.getIssueSttsCd()).isEqualTo("RESOLVED"); // 전이 없음
    }

    @Test
    @DisplayName("작업자_목록조회는_본인_배정_또는_본인_작성_영상만_그외_403")
    void workerListThreadsOnUnassignedNonAuthoredForbidden() throws Exception {
        // 다른 영상 — 미배정 + 본인 작성 이슈 없음
        LsDataRaw raw2 = rawRepository.save(LsDataRaw.createFromIngest(
                "CLIP-ISS-002", "CCTV-002", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/iss2.mp4", LocalDateTime.now(), 30));

        mockMvc.perform(get("/v1/videos/" + raw2.getRawSn() + "/issues")
                        .header("Authorization", "Bearer " + workerNotAssigned))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("재배정된_이전_작업자도_본인_작성_문의는_조회_가능")
    void reassignedFormerWorkerCanStillReadOwnInquiry() throws Exception {
        // 작업자 100 이 문의 작성
        createInquiry(workerAssigned);
        // 재배정 — 100 의 LABELER 배정을 101 로 변경
        LsTaskAssignment a = assignmentRepository.findAll().stream()
                .filter(x -> x.getRawDataId().equals(videoId)
                        && LsTaskAssignment.TASK_LABELER.equals(x.getTaskTypeCd())
                        && x.getUserNo().equals(100L))
                .findFirst().orElseThrow();
        a.reassignTo(101L);
        assignmentRepository.save(a);

        // 100 은 더 이상 배정되지 않았지만 본인 작성 문의가 있으므로 조회 가능
        mockMvc.perform(get("/v1/videos/" + videoId + "/issues")
                        .header("Authorization", "Bearer " + workerAssigned))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("반려_이슈와_문의가_통합_스레드로_조회됨")
    void rejectionAndInquiryReturnedInUnifiedThreads() throws Exception {
        issueRepository.save(LsDataIssue.create(videoId, "반려 사유", "1"));
        createInquiry(workerAssigned);

        mockMvc.perform(get("/v1/videos/" + videoId + "/issues")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("content_1000자_초과시_400")
    void contentTooLongReturns400() throws Exception {
        String tooLong = "a".repeat(1001);
        IssueCreateRequest req = new IssueCreateRequest(tooLong, null);
        mockMvc.perform(post("/v1/videos/" + videoId + "/issues")
                        .header("Authorization", "Bearer " + workerAssigned)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("댓글_요청에_역할_필드를_보내도_무시되고_토큰_역할로_저장")
    void unknownRoleFieldIgnoredAuthorRoleFromToken() throws Exception {
        Long issueSn = createInquiry(workerAssigned);
        // 임의 필드(authorRoleCd=ADMIN, authorNo=999) 를 JSON 에 추가 — DTO 에 없으므로 무시되어야 함
        String body = "{\"content\":\"역할 위조 시도\",\"authorRoleCd\":\"ADMIN\",\"authorNo\":\"999\"}";
        mockMvc.perform(post("/v1/issues/" + issueSn + "/comments")
                        .header("Authorization", "Bearer " + workerAssigned)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                // 토큰 역할(WORKER)/토큰 sub(100) 로 저장 — 요청 본문 무시
                .andExpect(jsonPath("$.data.authorRoleCd").value("WORKER"))
                .andExpect(jsonPath("$.data.authorNo").value("100"));
    }

    @Test
    @DisplayName("다른_영상_이슈의_issueSn으로_댓글시_권한_재검증_403")
    void commentOnOthersVideoIssueIdorForbidden() throws Exception {
        // 검수자가 videoId 에 문의를 만든다 (작성자=1). 작업자 101 은 이 영상 미배정 + 미작성.
        Long issueSn = createInquiry(reviewerToken);
        IssueCommentRequest req = new IssueCommentRequest("권한 없는 댓글");
        mockMvc.perform(post("/v1/issues/" + issueSn + "/comments")
                        .header("Authorization", "Bearer " + workerNotAssigned)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    // ---------- DEV_FIX #1 — srcSn 교차 영상 참조 검증 (CWE-639) ----------

    @Test
    @DisplayName("타_영상_프레임_srcSn으로_문의_등록시_400")
    void crossVideoSrcSnRejected() throws Exception {
        // 다른 영상(raw2)의 프레임을 생성 — videoId 와 무관한 srcSn
        LsDataRaw raw2 = rawRepository.save(LsDataRaw.createFromIngest(
                "CLIP-ISS-SRC2", "CCTV-002", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/iss-src2.mp4", LocalDateTime.now(), 30));
        LsDataSrc otherFrame = srcRepository.save(
                LsDataSrc.create(raw2.getRawSn(), 0, "/var/raw/iss-src2-f0.jpg", LocalDateTime.now()));

        // videoId 에 배정된 작업자가 raw2 의 프레임 srcSn 을 주입 → 400
        IssueCreateRequest req = new IssueCreateRequest("프레임 PK 주입 시도", otherFrame.getSrcSn());
        mockMvc.perform(post("/v1/videos/" + videoId + "/issues")
                        .header("Authorization", "Bearer " + workerAssigned)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("본인_영상_프레임_srcSn은_정상_등록")
    void ownVideoSrcSnAccepted() throws Exception {
        LsDataSrc ownFrame = srcRepository.save(
                LsDataSrc.create(videoId, 0, "/var/raw/iss-f0.jpg", LocalDateTime.now()));

        IssueCreateRequest req = new IssueCreateRequest("이 프레임 라벨 확인", ownFrame.getSrcSn());
        mockMvc.perform(post("/v1/videos/" + videoId + "/issues")
                        .header("Authorization", "Bearer " + workerAssigned)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.issueTypeCd").value("INQUIRY"));
    }

    @Test
    @DisplayName("존재하지_않는_srcSn으로_문의_등록시_404")
    void nonexistentSrcSnReturns404() throws Exception {
        IssueCreateRequest req = new IssueCreateRequest("없는 프레임 참조", 9_999_999L);
        mockMvc.perform(post("/v1/videos/" + videoId + "/issues")
                        .header("Authorization", "Bearer " + workerAssigned)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    // ---------- DEV_FIX #2 — REJECTION 댓글 인가 정책 명문화 (CWE-863) ----------

    @Test
    @DisplayName("재배정된_새_작업자가_반려_이슈에_댓글_가능")
    void reassignedNewWorkerCanCommentOnRejection() throws Exception {
        // REJECTION/RESOLVED 반려 이슈 (작성자=검수자 1)
        LsDataIssue rejection = issueRepository.save(
                LsDataIssue.create(videoId, "라벨 누락 반려", "1"));
        Long issueSn = rejection.getDataIssueSn();

        // 재배정 — 기존 작업자 100 배정을 101 로 변경 → 101 이 현재 배정 작업자
        LsTaskAssignment a = assignmentRepository.findAll().stream()
                .filter(x -> x.getRawDataId().equals(videoId)
                        && LsTaskAssignment.TASK_LABELER.equals(x.getTaskTypeCd())
                        && x.getUserNo().equals(100L))
                .findFirst().orElseThrow();
        a.reassignTo(101L);
        assignmentRepository.save(a);

        // 새 작업자 101 은 반려 사유에 질문 가능 (의도된 동작)
        IssueCommentRequest req = new IssueCommentRequest("반려 사유 관련 질문드립니다.");
        mockMvc.perform(post("/v1/issues/" + issueSn + "/comments")
                        .header("Authorization", "Bearer " + workerNotAssigned)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.authorNo").value("101"));
    }

    @Test
    @DisplayName("배정_해제된_작업자는_타인_작성_반려_이슈에_댓글_403")
    void unassignedFormerWorkerCannotCommentOnOthersRejection() throws Exception {
        // REJECTION/RESOLVED 반려 이슈 (작성자=검수자 1, 작업자 100 작성 아님)
        LsDataIssue rejection = issueRepository.save(
                LsDataIssue.create(videoId, "라벨 누락 반려", "1"));
        Long issueSn = rejection.getDataIssueSn();

        // 재배정 — 100 의 배정을 101 로 옮겨 100 은 더 이상 배정 작업자가 아님
        LsTaskAssignment a = assignmentRepository.findAll().stream()
                .filter(x -> x.getRawDataId().equals(videoId)
                        && LsTaskAssignment.TASK_LABELER.equals(x.getTaskTypeCd())
                        && x.getUserNo().equals(100L))
                .findFirst().orElseThrow();
        a.reassignTo(101L);
        assignmentRepository.save(a);

        // 100 은 배정 해제 + 반려 이슈 작성자도 아니므로 403
        IssueCommentRequest req = new IssueCommentRequest("이전 작업자의 댓글 시도");
        mockMvc.perform(post("/v1/issues/" + issueSn + "/comments")
                        .header("Authorization", "Bearer " + workerAssigned)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    // ---------- DEV_FIX #3 — content 제어문자 가드 (CWE-117/79 예방) ----------

    @Test
    @DisplayName("content에_널문자_포함시_400")
    void controlCharInContentRejected() throws Exception {
        // JSON   escape — Jackson 이 NUL(U+0000) 제어문자로 역직렬화
        String body = "{\"content\":\"널문자\\u0000포함\",\"srcSn\":null}";
        mockMvc.perform(post("/v1/videos/" + videoId + "/issues")
                        .header("Authorization", "Bearer " + workerAssigned)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("content에_DEL문자_포함시_400")
    void delCharInContentRejected() throws Exception {
        // DEL(U+007F) 제어문자 포함 — C0 외 제어문자도 차단되어야 함
        String body = "{\"content\":\"DEL문자\\u007F포함\",\"srcSn\":null}";
        mockMvc.perform(post("/v1/videos/" + videoId + "/issues")
                        .header("Authorization", "Bearer " + workerAssigned)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("개행은_허용")
    void newlineInContentAllowed() throws Exception {
        IssueCreateRequest req = new IssueCreateRequest("첫줄\n둘째줄\t탭\r캐리지", null);
        mockMvc.perform(post("/v1/videos/" + videoId + "/issues")
                        .header("Authorization", "Bearer " + workerAssigned)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("댓글_content에_제어문자_포함시_400")
    void controlCharInCommentRejected() throws Exception {
        Long issueSn = createInquiry(workerAssigned);
        // SOH(U+0001) 제어문자 포함 댓글
        String body = "{\"content\":\"댓글\\u0001제어문자\"}";
        mockMvc.perform(post("/v1/issues/" + issueSn + "/comments")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }
}
