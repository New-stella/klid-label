package kr.co.cudo.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 네 목록 API({@code GET /v1/tasks/board}, {@code GET /v1/reviews}, {@code GET /v1/assignments},
 * {@code GET /v1/videos})의
 * <b>하위호환(R8) 회귀 가드</b>.
 *
 * <p>Phase 1~5 에서 두 목록에 필터/정렬/KPI 가 추가됐다. 개별 Phase 테스트가 각 기능을 이미
 * 검증하지만, 이 파일의 목적은 다르다 — <b>"신규 파라미터를 하나도 안 보내는 기존 호출"</b> 이
 * 계속 같은 계약으로 동작하는지를 한 파일에서 한눈에 고정한다. 나중에 누군가 정책을 바꾸면
 * 기능 테스트보다 여기가 먼저 깨져야 한다.
 *
 * <p><b>이 파일이 고정하는 정책 차이(가장 중요)</b>: 미등록 정렬 키의 응답 코드는
 * 엔드포인트마다 <b>의도적으로 다르다</b>.
 * <table border="1">
 *   <caption>미등록 정렬 키 처리</caption>
 *   <tr><th>엔드포인트</th><th>응답</th><th>근거(= 변경 전에 200 이었는가)</th></tr>
 *   <tr>
 *     <td>{@code /v1/tasks/board}</td><td><b>400</b> (strict)</td>
 *     <td>변경 전에도 {@code Pageable} 을 받아 잘못된 키면 {@code PropertyReferenceException}(500)
 *         이었다 — 원래 200 이 아니었으므로 400 은 파손이 아니라 개선이다.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code /v1/reviews}</td><td><b>200 + 기본 정렬 폴백</b> (lenient)</td>
 *     <td>변경 전 이 API 는 {@code sort} 를 받지도 않아 <b>항상 200</b> 이었다. 400 으로 바꾸면
 *         FE 가 URL 에 보존·재전송하는 다른 화면의 정렬 키(북마크·뒤로가기)로 목록이 통째로 죽는다.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code /v1/assignments}</td><td><b>400</b> (strict — 작업목록과 같은 편)</td>
 *     <td>이 엔드포인트도 변경 전부터 {@code Pageable} 을 받았으므로 잘못된 키는 원래 200 이 아니었다.
 *         검수목록의 관용 정책을 여기에 복사하면 하위호환이 아니라 <b>검증 약화</b>다.</td>
 *   </tr>
 * </table>
 * 두 정책을 "일관성" 을 이유로 통일하지 말 것 — 통일하는 순간 이 테스트가 막는다.
 *
 * <p>정렬 <b>순서</b>는 R1 로 의도적으로 바뀌었다(작업목록의 상태 우선순위 {@code ORDER BY CASE} 폐지).
 * 따라서 작업목록은 <b>결과 집합·건수</b>만 비교하고 순서는 단언하지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ListApiBackwardCompatibilityIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsRawDataStatusRepository dataSttsRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;

    private final JdbcTemplate jdbc;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    /** 배정 시드용 작업자 (test-data.sql 의 WORKER 계정). */
    private static final long WORKER = 100L;
    private static final long OTHER_WORKER = 101L;

    ListApiBackwardCompatibilityIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, String.valueOf(WORKER), "WORKER", "INTERNAL", issuer, 60);
    }

    // ---------------------------------------------------------------- fixtures

    /** 영상 1건 + 배치 상태(LS_DATA_RAW.DATA_STTS_CD). */
    private Long seedVideo(String clipId, String batchStatus) {
        return seedVideo(clipId, batchStatus, "EVT-FIRE");
    }

    /** 영상 1건 + 배치 상태 + 이벤트유형 코드. */
    private Long seedVideo(String clipId, String batchStatus, String evntTypeCd) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                clipId, "CCTV-001", evntTypeCd, "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                LocalDateTime.of(2026, 5, 1, 9, 0, 0), 30);
        raw = videoRepository.save(raw);
        raw.changeStatus(batchStatus);
        return videoRepository.save(raw).getRawSn();
    }

    /** 검수/작업 워크플로 상태 row + UPD_DT 고정(정렬 검증을 결정적으로 만들기 위해 DB 직접 갱신). */
    private void seedWorkflow(Long rawSn, String workflowStatus, LocalDateTime updDt) {
        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        stts.transitionTo(workflowStatus);
        dataSttsRepository.save(stts);
        jdbc.update("UPDATE LS_RAW_DATA_STATUS SET UPD_DT = ? WHERE RAW_DATA_ID = ?",
                Timestamp.valueOf(updDt), rawSn);
    }

    /** 검수목록에 뜨는 영상 1건(영상 + 워크플로 상태). */
    private Long seedReviewVideo(String clipId, String workflowStatus, LocalDateTime updDt) {
        Long rawSn = seedVideo(clipId, "COMPLETED");
        seedWorkflow(rawSn, workflowStatus, updDt);
        return rawSn;
    }

    private void assignLabeler(Long rawSn) {
        assignLabeler(rawSn, WORKER);
    }

    private void assignLabeler(Long rawSn, long workerNo) {
        authrtRepository.save(LsTaskAssignment.createLabeler(rawSn, workerNo, 1L));
    }

    /** 배정 1건(영상 + LABELER 배정) + 배정일 고정 — 기본 정렬(regDt DESC) 검증을 결정적으로 만든다. */
    private Long seedAssignment(String clipId, long workerNo, LocalDateTime regDt) {
        return seedAssignment(clipId, workerNo, regDt, "EVT-FIRE");
    }

    private Long seedAssignment(String clipId, long workerNo, LocalDateTime regDt, String evntTypeCd) {
        Long rawSn = seedVideo(clipId, "COMPLETED", evntTypeCd);
        assignLabeler(rawSn, workerNo);
        jdbc.update("UPDATE LS_TASK_ASSIGNMENT SET REG_DT = ? WHERE RAW_DATA_ID = ?",
                Timestamp.valueOf(regDt), rawSn);
        return rawSn;
    }

    // ---------------------------------------------------------------- helpers

    private MockHttpServletRequestBuilder request(String path, String... params) {
        return requestAs(reviewerToken, path, params);
    }

    private MockHttpServletRequestBuilder requestAs(String token, String path, String... params) {
        MockHttpServletRequestBuilder builder =
                get(path).header("Authorization", "Bearer " + token);
        for (int i = 0; i + 1 < params.length; i += 2) {
            builder = builder.param(params[i], params[i + 1]);
        }
        return builder;
    }

    private MockHttpServletRequestBuilder board(String... params) {
        return request("/v1/tasks/board", params);
    }

    private MockHttpServletRequestBuilder reviews(String... params) {
        return request("/v1/reviews", params);
    }

    /** 배정 목록 — 기본은 REVIEWER(전체 조회) 토큰. */
    private MockHttpServletRequestBuilder assignments(String... params) {
        return request("/v1/assignments", params);
    }

    private MockHttpServletRequestBuilder assignmentsAs(String token, String... params) {
        return requestAs(token, "/v1/assignments", params);
    }

    /** 영상 적재 시각 고정 — 기본 정렬(regDt DESC) 검증을 결정적으로 만든다. */
    private void fixRegDt(Long rawSn, LocalDateTime regDt) {
        jdbc.update("UPDATE LS_DATA_RAW SET REG_DT = ? WHERE RAW_SN = ?", Timestamp.valueOf(regDt), rawSn);
    }

    /** 영상 처리 현황 목록. */
    private MockHttpServletRequestBuilder videos(String... params) {
        return request("/v1/videos", params);
    }

    /** 영상 목록 응답의 id(=rawSn)를 등장 순서대로 추출한다. */
    private List<Long> videoListIds(MockHttpServletRequestBuilder req) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : dataOf(req).path("content")) {
            ids.add(item.path("id").asLong());
        }
        return ids;
    }

    /** 배정 이벤트유형 옵션 응답의 items. */
    private List<String> eventTypeItems(String token, String... params) throws Exception {
        List<String> out = new ArrayList<>();
        for (JsonNode item : dataOf(requestAs(token, "/v1/assignments/event-types", params)).path("items")) {
            out.add(item.asText());
        }
        return out;
    }

    private JsonNode dataOf(MockHttpServletRequestBuilder req) throws Exception {
        MvcResult result = mockMvc.perform(req).andExpect(status().isOk()).andReturn();
        // 한글 표시값을 다루므로 charset 을 명시한다 (기본 ISO-8859-1 로 읽으면 깨진다).
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    /** 응답 목록의 videoId 를 등장 순서대로 추출한다. */
    private List<Long> videoIds(MockHttpServletRequestBuilder req) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : dataOf(req).path("content")) {
            ids.add(item.path("videoId").asLong());
        }
        return ids;
    }

    private long totalElements(MockHttpServletRequestBuilder req) throws Exception {
        return dataOf(req).path("totalElements").asLong();
    }

    // ============================================================ 1~4. 작업목록

    @Test
    @DisplayName("기존_호출_작업목록_status_COMPLETED_page_size_는_COMPLETED_영상_전체를_반환한다")
    void boardCompletedReturnsSameResultSet() throws Exception {
        // given — COMPLETED 3건 + 다른 배치 상태 2건 (신규 파라미터는 하나도 보내지 않는다)
        Long c1 = seedVideo("CLIP-BC-C1", "COMPLETED");
        Long c2 = seedVideo("CLIP-BC-C2", "COMPLETED");
        Long c3 = seedVideo("CLIP-BC-C3", "COMPLETED");
        seedVideo("CLIP-BC-P1", "PENDING");
        seedVideo("CLIP-BC-F1", "FAILED");
        // 배정 유무는 COMPLETED 필터에 영향을 주지 않는다 (배정된 영상도 그대로 노출).
        assignLabeler(c1);
        seedWorkflow(c1, LsRawDataStatus.STTS_ASSIGNED, LocalDateTime.of(2026, 5, 1, 10, 0));

        // when / then — 정렬 순서는 R1 로 의도 변경됐으므로 집합·건수만 비교한다.
        assertThat(videoIds(board("status", "COMPLETED", "page", "0", "size", "20")))
                .containsExactlyInAnyOrder(c1, c2, c3);
        assertThat(totalElements(board("status", "COMPLETED", "page", "0", "size", "20"))).isEqualTo(3);

        // 페이징 메타도 기존 계약 그대로
        JsonNode data = dataOf(board("status", "COMPLETED", "page", "0", "size", "20"));
        assertThat(data.path("size").asInt()).isEqualTo(20);
        assertThat(data.path("number").asInt()).isZero();
    }

    @Test
    @DisplayName("기존_호출_작업목록_status_UNASSIGNED_는_배치상태_무관_미배정_전체를_반환한다")
    void boardUnassignedSpecialValueUnchanged() throws Exception {
        // given — 미배정 3건(배치 상태 제각각) + 배정 1건
        Long freeCompleted = seedVideo("CLIP-BU-C", "COMPLETED");
        Long freePending = seedVideo("CLIP-BU-P", "PENDING");
        Long freeFailed = seedVideo("CLIP-BU-F", "FAILED");
        Long assigned = seedVideo("CLIP-BU-A", "COMPLETED");
        assignLabeler(assigned);

        // when / then — UNASSIGNED 는 배치 상태 필터를 끄는 특수값(기존 동작 불변)
        assertThat(videoIds(board("status", "UNASSIGNED", "page", "0", "size", "20")))
                .containsExactlyInAnyOrder(freeCompleted, freePending, freeFailed)
                .doesNotContain(assigned);
        assertThat(totalElements(board("status", "UNASSIGNED", "page", "0", "size", "20"))).isEqualTo(3);
    }

    @Test
    @DisplayName("파라미터_없는_작업목록_호출은_기본값_status_COMPLETED_로_동작한다")
    void boardWithoutAnyParameterDefaultsToCompleted() throws Exception {
        // given — COMPLETED 2건 + 비COMPLETED 2건
        Long c1 = seedVideo("CLIP-BD-C1", "COMPLETED");
        Long c2 = seedVideo("CLIP-BD-C2", "COMPLETED");
        seedVideo("CLIP-BD-P1", "PENDING");
        seedVideo("CLIP-BD-Q1", "BATCH_QUEUED");

        // when / then — BE 기본값은 불변이어야 한다 (화면 진입 기본값은 FE 가 명시 전송한다)
        assertThat(videoIds(board()))
                .as("파라미터 없는 호출은 status=COMPLETED 와 동일해야 한다")
                .containsExactlyInAnyOrder(c1, c2);
        assertThat(videoIds(board()))
                .containsExactlyInAnyOrderElementsOf(videoIds(board("status", "COMPLETED")));
        // 기본 페이지 크기도 불변
        assertThat(dataOf(board()).path("size").asInt()).isEqualTo(20);
    }

    @Test
    @DisplayName("작업목록_미등록_정렬키는_strict_정책대로_400이다")
    void boardRejectsUnknownSortKeyWith400() throws Exception {
        seedVideo("CLIP-BS-1", "COMPLETED");

        mockMvc.perform(board("sort", "nonExistentField,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"))
                // CWE-209 — 입력값/내부 필드명이 메시지로 반사되지 않는다.
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("nonExistentField"))));
    }

    // ============================================================ 5~9. 검수목록

    @Test
    @DisplayName("기존_호출_검수목록_page_size_는_화이트리스트_4종을_제출일_내림차순으로_반환한다")
    void reviewsDefaultReturnsWhitelistOrderedByUpdDtDesc() throws Exception {
        // given — 검수 워크플로 4종(제출일 t1<t2<t3<t4) + 검수 대상이 아닌 배치/작업 상태 3종
        LocalDateTime t1 = LocalDateTime.of(2026, 5, 1, 10, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 5, 1, 11, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 5, 1, 12, 0);
        LocalDateTime t4 = LocalDateTime.of(2026, 5, 1, 13, 0);
        Long pending = seedReviewVideo("CLIP-RD-P", LsRawDataStatus.STTS_PENDING, t1);
        Long inReview = seedReviewVideo("CLIP-RD-I", LsRawDataStatus.STTS_IN_REVIEW, t2);
        Long approved = seedReviewVideo("CLIP-RD-A", LsRawDataStatus.STTS_APPROVED, t3);
        Long rejected = seedReviewVideo("CLIP-RD-R", LsRawDataStatus.STTS_REJECTED, t4);
        Long processing = seedReviewVideo("CLIP-RD-X1", LsRawDataStatus.STTS_PROCESSING, t4);
        Long assignedStts = seedReviewVideo("CLIP-RD-X2", LsRawDataStatus.STTS_ASSIGNED, t4);
        Long completed = seedReviewVideo("CLIP-RD-X3", LsRawDataStatus.STTS_COMPLETED, t4);

        // when / then — 신규 파라미터(q) 없이 기존 계약 그대로 호출
        assertThat(videoIds(reviews("page", "0", "size", "20")))
                .as("화이트리스트 4종만 · updDt DESC")
                .containsExactly(rejected, approved, inReview, pending)
                .doesNotContain(processing, assignedStts, completed);
        assertThat(totalElements(reviews("page", "0", "size", "20"))).isEqualTo(4);

        // 파라미터를 아예 보내지 않는 호출도 동일 (BE 기본값 불변)
        assertThat(videoIds(reviews())).containsExactly(rejected, approved, inReview, pending);
    }

    @Test
    @DisplayName("기존_호출_검수목록_status_PENDING_은_해당_상태만_반환한다")
    void reviewsStatusFilterUnchanged() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 5, 2, 9, 0);
        Long pendingA = seedReviewVideo("CLIP-RS-P1", LsRawDataStatus.STTS_PENDING, now);
        Long pendingB = seedReviewVideo("CLIP-RS-P2", LsRawDataStatus.STTS_PENDING, now);
        Long inReview = seedReviewVideo("CLIP-RS-I", LsRawDataStatus.STTS_IN_REVIEW, now);
        Long approved = seedReviewVideo("CLIP-RS-A", LsRawDataStatus.STTS_APPROVED, now);

        assertThat(videoIds(reviews("status", "PENDING", "page", "0", "size", "20")))
                .containsExactlyInAnyOrder(pendingA, pendingB)
                .doesNotContain(inReview, approved);
        assertThat(totalElements(reviews("status", "PENDING", "page", "0", "size", "20"))).isEqualTo(2);
    }

    @Test
    @DisplayName("검수목록_미등록_정렬키는_lenient_정책대로_400이_아니라_기본정렬_폴백이다")
    void reviewsFallsBackOnUnknownSortKey() throws Exception {
        LocalDateTime t1 = LocalDateTime.of(2026, 5, 3, 10, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 5, 3, 11, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 5, 3, 12, 0);
        Long oldest = seedReviewVideo("CLIP-RF-1", LsRawDataStatus.STTS_PENDING, t1);
        Long middle = seedReviewVideo("CLIP-RF-2", LsRawDataStatus.STTS_PENDING, t2);
        Long newest = seedReviewVideo("CLIP-RF-3", LsRawDataStatus.STTS_PENDING, t3);

        // 내부 필드(labelPayload)든 다른 화면의 정렬 키든 200 + 기본 정렬(updDt DESC)
        assertThat(videoIds(reviews("sort", "labelPayload,asc", "page", "0", "size", "20")))
                .containsExactly(newest, middle, oldest);
        assertThat(videoIds(reviews("sort", "nonExistentField,asc")))
                .containsExactly(newest, middle, oldest);
    }

    @Test
    @DisplayName("검수목록_sort_submittedAt_asc_는_제출일_오름차순으로_실제_적용된다")
    void reviewsSortSubmittedAtAscApplied() throws Exception {
        LocalDateTime t1 = LocalDateTime.of(2026, 5, 4, 10, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 5, 4, 11, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 5, 4, 12, 0);
        Long oldest = seedReviewVideo("CLIP-RA-1", LsRawDataStatus.STTS_PENDING, t1);
        Long middle = seedReviewVideo("CLIP-RA-2", LsRawDataStatus.STTS_PENDING, t2);
        Long newest = seedReviewVideo("CLIP-RA-3", LsRawDataStatus.STTS_PENDING, t3);

        assertThat(videoIds(reviews("sort", "submittedAt,asc", "page", "0", "size", "20")))
                .as("폴백이 아니라 요청한 오름차순이 실제로 적용되어야 한다")
                .containsExactly(oldest, middle, newest);
    }

    @Test
    @DisplayName("검수목록_화이트리스트_밖_status_는_400이_아니라_빈_결과다")
    void reviewsStatusOutsideWhitelistReturnsEmpty() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 5, 5, 9, 0);
        seedReviewVideo("CLIP-RW-X", LsRawDataStatus.STTS_PROCESSING, now);
        seedReviewVideo("CLIP-RW-P", LsRawDataStatus.STTS_PENDING, now);

        mockMvc.perform(reviews("status", "PROCESSING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.content").isEmpty());
    }

    // ============================================================ 10. size 상한

    @Test
    @DisplayName("size_상한_초과는_작업목록은_100으로_캡되고_검수목록은_400으로_각_기존_정책을_유지한다")
    void oversizedPageKeepsPerEndpointPolicy() throws Exception {
        seedVideo("CLIP-SZ-1", "COMPLETED");

        // 작업목록 — @PageableDefault + spring.data.web.pageable.max-page-size=100 으로 캡(200)
        mockMvc.perform(board("size", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));

        // 검수목록 — page/size 를 RequestParam 으로 받아 상한 초과는 명시적으로 400 (기존 계약)
        mockMvc.perform(reviews("page", "0", "size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    // ============================================================ 11~15. 배정 목록

    @Test
    @DisplayName("assignments_파라미터_없는_호출은_기본정렬_regDt_desc_페이지크기_20")
    void assignmentsDefaultSortAndPagingUnchanged() throws Exception {
        // given — 배정일 t1<t2<t3 (동값 tie-break 를 배제해 순서를 결정적으로 만든다)
        Long oldest = seedAssignment("CLIP-AS-1", WORKER, LocalDateTime.of(2026, 5, 7, 10, 0));
        Long middle = seedAssignment("CLIP-AS-2", WORKER, LocalDateTime.of(2026, 5, 7, 11, 0));
        Long newest = seedAssignment("CLIP-AS-3", WORKER, LocalDateTime.of(2026, 5, 7, 12, 0));

        // when / then — 배정일 최신순 + 기본 페이지 크기 20 (BE 기본값 불변)
        assertThat(videoIds(assignments()))
                .as("기본 정렬은 regDt DESC 여야 한다 — 상태 우선순위를 섞지 않는다")
                .containsExactly(newest, middle, oldest);

        JsonNode data = dataOf(assignments());
        assertThat(data.path("size").asInt()).isEqualTo(20);
        assertThat(data.path("number").asInt()).isZero();
        assertThat(data.path("totalElements").asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("assignments_신규_필터_파라미터는_전부_optional")
    void assignmentsNewFilterParametersAreAllOptional() throws Exception {
        Long a = seedAssignment("CLIP-AO-1", WORKER, LocalDateTime.of(2026, 5, 8, 10, 0), "EVT-FIRE");
        Long b = seedAssignment("CLIP-AO-2", WORKER, LocalDateTime.of(2026, 5, 8, 11, 0), "EVT-FALL");

        // 신규 파라미터를 하나도 보내지 않는 기존 호출 = 필터 미적용 전체
        assertThat(videoIds(assignments())).containsExactly(b, a);
        // page/size 만 보내던 기존 호출도 동일
        assertThat(videoIds(assignments("page", "0", "size", "20"))).containsExactly(b, a);
        // 빈 값/공백만 보내도 동일 (blank = 필터 미적용 — q·workStatus·eventTypeCd 규약이 같다)
        assertThat(videoIds(assignments("q", "", "workStatus", "", "eventTypeCd", "")))
                .as("빈 값이 필터로 살아나면 기존 호출 결과가 달라진다")
                .containsExactlyElementsOf(videoIds(assignments()));
        assertThat(videoIds(assignments("q", "   ", "workStatus", "   ", "eventTypeCd", "   ")))
                .containsExactlyElementsOf(videoIds(assignments()));
    }

    @Test
    @DisplayName("assignments_미등록_정렬키는_board_와_같은_strict_정책대로_400이다")
    void assignmentsRejectUnknownSortKeyWith400() throws Exception {
        seedAssignment("CLIP-AK-1", WORKER, LocalDateTime.of(2026, 5, 9, 10, 0));

        mockMvc.perform(assignments("sort", "nonExistentField,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"))
                // CWE-209 — 입력값/내부 필드명이 메시지로 반사되지 않는다.
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("nonExistentField"))));

        // 등록된 키(assignedAt=regDt alias)는 그대로 200 — allowlist 는 화면 표시명도 받는다.
        assertThat(videoIds(assignments("sort", "assignedAt,asc"))).hasSize(1);
    }

    @Test
    @DisplayName("assignments_는_reviews_의_lenient_폴백을_따르지_않는다")
    void assignmentsDoNotAdoptReviewLenientFallback() throws Exception {
        Long assignmentTarget = seedAssignment("CLIP-AP-1", WORKER, LocalDateTime.of(2026, 5, 10, 10, 0));
        Long reviewTarget = seedReviewVideo("CLIP-AP-2", LsRawDataStatus.STTS_PENDING,
                LocalDateTime.of(2026, 5, 10, 10, 0));

        String sameKey = "nonExistentField,asc";

        // 배정목록 = strict(작업목록과 같은 편) — 변경 전에도 Pageable 을 받아 200 이 아니었다.
        mockMvc.perform(assignments("sort", sameKey)).andExpect(status().isBadRequest());
        mockMvc.perform(board("sort", sameKey)).andExpect(status().isBadRequest());
        // 검수목록 = lenient — 변경 전 항상 200 이었으므로 목록이 살아 있어야 한다.
        assertThat(videoIds(reviews("sort", sameKey))).contains(reviewTarget);

        // 검수목록 allowlist 에만 있는 키(submittedAt)도 배정목록에서는 400 이다 —
        // 세 allowlist 는 각 화면이 실제로 보여주는 컬럼만 담으며 서로의 사본이 아니다.
        mockMvc.perform(assignments("sort", "submittedAt,desc")).andExpect(status().isBadRequest());
        assertThat(videoIds(reviews("sort", "submittedAt,desc"))).contains(reviewTarget);

        // ↑ 세 단언을 '일관성' 을 이유로 같게 만들면 R8 하위호환/검증 수준이 깨진다.
        assertThat(assignmentTarget).isNotNull();
    }

    @Test
    @DisplayName("assignments_event_types_도_목록과_같은_인가_규칙을_따른다")
    void assignmentEventTypeOptionsShareListAuthorization() throws Exception {
        seedAssignment("CLIP-AE-M", WORKER, LocalDateTime.of(2026, 5, 11, 10, 0), "EVT-FIRE");
        seedAssignment("CLIP-AE-O", OTHER_WORKER, LocalDateTime.of(2026, 5, 11, 11, 0), "EVT-SECRET");

        // WORKER — workerId 를 보내도 무시되고 본인 배정으로 고정된다(CWE-639, 403 아님).
        assertThat(eventTypeItems(workerToken)).containsExactly("EVT-FIRE");
        assertThat(eventTypeItems(workerToken, "workerId", String.valueOf(OTHER_WORKER)))
                .as("옵션은 '그 값이 존재한다' 는 사실 자체가 정보 누출이다")
                .containsExactly("EVT-FIRE");
        assertThat(videoIds(assignmentsAs(workerToken, "workerId", String.valueOf(OTHER_WORKER))))
                .hasSize(1);

        // REVIEWER — 작업자 필터가 유효하고, 미지정이면 전체를 본다.
        assertThat(eventTypeItems(reviewerToken, "workerId", String.valueOf(OTHER_WORKER)))
                .containsExactly("EVT-SECRET");
        assertThat(eventTypeItems(reviewerToken)).containsExactly("EVT-FIRE", "EVT-SECRET");

        // 그 외 역할 403 / 미인증 401 (목록과 동일)
        String portalToken = JwtTestSupport.token(secret, "500", "PORTAL_USER", "PORTAL", issuer, 60);
        mockMvc.perform(requestAs(portalToken, "/v1/assignments/event-types"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/assignments/event-types")).andExpect(status().isUnauthorized());
    }

    // ============================================================ 5. 영상 처리 현황

    @Test
    @DisplayName("영상현황_파라미터_없는_기존_호출은_검색필터_도입_후에도_동일_결과_동일_정렬이다")
    void videosWithoutParamsUnchanged() throws Exception {
        // given — 원본 3건(신규 파라미터는 하나도 보내지 않는다). 기본 정렬 검증을 결정적으로 만들기 위해
        // REG_DT 를 명시 고정한다(같은 밀리초 적재 시 순서가 비결정적이 되는 것을 차단).
        Long v1 = seedVideo("CLIP-VBC-1", "COMPLETED");
        Long v2 = seedVideo("CLIP-VBC-2", "PENDING");
        Long v3 = seedVideo("CLIP-VBC-3", "FAILED");
        fixRegDt(v1, LocalDateTime.of(2026, 5, 1, 9, 0));
        fixRegDt(v2, LocalDateTime.of(2026, 5, 2, 9, 0));
        fixRegDt(v3, LocalDateTime.of(2026, 5, 3, 9, 0));

        // when / then — 배치 상태와 무관하게 원본 전체, 기본 정렬은 regDt DESC(최신 적재가 앞)
        List<Long> ids = videoListIds(videos("size", "100"));
        assertThat(ids).contains(v1, v2, v3);
        assertThat(ids.indexOf(v3)).isLessThan(ids.indexOf(v2));
        assertThat(ids.indexOf(v2)).isLessThan(ids.indexOf(v1));
    }

    @Test
    @DisplayName("영상현황_신규_검색필터_파라미터는_전부_optional_이라_생략하면_상태필터_단독_동작이_유지된다")
    void videosNewFiltersAreOptional() throws Exception {
        // given
        Long completed = seedVideo("CLIP-VBC-4", "COMPLETED");
        Long pending = seedVideo("CLIP-VBC-5", "PENDING");

        // when / then — 기존 호출(dataSttsCd 단독)이 그대로 동작
        assertThat(videoListIds(videos("dataSttsCd", "COMPLETED", "size", "100")))
                .contains(completed)
                .doesNotContain(pending);
    }

    @Test
    @DisplayName("영상현황_미등록_정렬키는_reviews_와_같은_lenient_정책대로_400이_아니라_기본정렬_폴백이다")
    void videosSortIsLenient() throws Exception {
        Long v1 = seedVideo("CLIP-VBC-6", "COMPLETED");

        // 변경 전에도 이 엔드포인트는 미등록 키에 200(기본 정렬 폴백)이었다 — 400 으로 바꾸면 파손이다.
        assertThat(videoListIds(videos("sort", "nonExistentField,asc", "size", "100")))
                .contains(v1);
    }

    // ============================================================ 정책 차이 대비

    @Test
    @DisplayName("동일한_미등록_정렬키라도_작업목록은_400_검수목록은_200_으로_정책이_의도적으로_다르다")
    void sortPolicyDiffersByEndpointOnPurpose() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 5, 6, 9, 0);
        Long reviewTarget = seedReviewVideo("CLIP-PD-1", LsRawDataStatus.STTS_PENDING, now);

        // 완전히 같은 정렬 키 문자열을 두 엔드포인트에 보낸다.
        String sameKey = "nonExistentField,asc";

        // 작업목록 = strict — 변경 전 500 이었으므로 400 이 개선이다.
        mockMvc.perform(board("sort", sameKey))
                .andExpect(status().isBadRequest());

        // 검수목록 = lenient — 변경 전 항상 200 이었으므로 200 을 유지해야 한다.
        assertThat(videoIds(reviews("sort", sameKey)))
                .as("검수목록은 미등록 정렬키에도 목록이 살아 있어야 한다(북마크·뒤로가기)")
                .contains(reviewTarget);

        // ↑ 이 두 단언을 '일관성' 을 이유로 같게 만들면 R8 하위호환이 깨진다.
    }
}
