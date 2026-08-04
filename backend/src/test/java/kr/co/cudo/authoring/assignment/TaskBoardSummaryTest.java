package kr.co.cudo.authoring.assignment;

import kr.co.cudo.authoring.support.IngestFlatValueSeeder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.domain.BoardWorkStatus;
import kr.co.cudo.authoring.assignment.dto.TaskBoardSearchCondition;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.assignment.repository.TaskBoardQueryRepository;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 — 작업목록 KPI 집계({@code GET /v1/tasks/board/summary}) + 이벤트유형 옵션
 * ({@code GET /v1/tasks/board/event-types}) 검증.
 *
 * <p>검증 축:
 * <ul>
 *   <li><b>HIGH-1</b> — 집계 버킷이 {@link BoardWorkStatus} 에서 파생되어 목록 필터 결과와 일치</li>
 *   <li><b>HIGH-2</b> — {@code total == 5종 합} 불변식</li>
 *   <li><b>HIGH-3</b> — {@code workStatus} 를 걸어도 summary 는 5종 전부를 집계(카드 자체가 선택지)</li>
 *   <li><b>HIGH-6</b> — 두 신규 엔드포인트 REVIEWER 이중 가드(403/401)</li>
 *   <li><b>HIGH-9</b> — 매칭 0건이어도 6개 필드가 모두 0 으로 채워짐(버킷 누락 방지)</li>
 *   <li><b>HIGH-12</b> — q/eventTypeCd/workerId 가 목록과 동일한 집합을 센다</li>
 *   <li><b>UNASSIGNED 두 축 계약</b> — KPI {@code unassigned} 버킷 ==
 *       {@code board?status=X&workStatus=UNASSIGNED} 이며 {@code board?status=UNASSIGNED} 와는 다르다</li>
 *   <li><b>옵션 절단 신호</b> — 상한 초과 시 {@code truncated=true} 로 손실이 드러난다</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class TaskBoardSummaryTest {

    /** 인입 평면값 시드용 — CCTV 명의 유일한 조달처(V167). */
    @Autowired
    @Qualifier("controlDataSource")
    private javax.sql.DataSource controlDataSource;

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;
    @Autowired private LsRawDataStatusRepository dataSttsRepository;
    @Autowired private TaskBoardQueryRepository taskBoardQueryRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    /** summary 응답 필드명 ↔ BoardWorkStatus (필드명 inProgress 는 PENDING 에 대응한다). */
    private static final Map<BoardWorkStatus, String> FIELD_BY_STATUS = Map.of(
            BoardWorkStatus.UNASSIGNED, "unassigned",
            BoardWorkStatus.PENDING, "inProgress",
            BoardWorkStatus.REVIEW_PENDING, "reviewPending",
            BoardWorkStatus.COMPLETED, "completed",
            BoardWorkStatus.REJECTED, "rejected");

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
    }

    // ---------------------------------------------------------------- fixtures

    private LsDataRaw seedVideo(String clipId, String cctvId, String evntTypeCd, String batchStatus) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                clipId, cctvId, evntTypeCd, "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                LocalDateTime.of(2026, 5, 1, 9, 0, 0), 30);
        raw = videoRepository.save(raw);
        raw.changeStatus(batchStatus);
        LsDataRaw saved = videoRepository.save(raw);
        // CCTV 명은 관제 인입 평면값에서 온다(V167 — 구 test-data-video.sql 의 CCTV 마스터 시드 대체).
        IngestFlatValueSeeder.seedLegacyName(
                new JdbcTemplate(controlDataSource), saved.getRawSn(), cctvId);
        return saved;
    }

    private LsDataRaw seedCompleted(String clipId) {
        return seedVideo(clipId, "CCTV-001", "EVT-FIRE", "COMPLETED");
    }

    private void assignLabeler(Long rawSn, Long workerNo) {
        authrtRepository.save(LsTaskAssignment.createLabeler(rawSn, workerNo, 1L));
    }

    private void setWorkflow(Long rawSn, String workflowStatus) {
        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        stts.transitionTo(workflowStatus);
        dataSttsRepository.save(stts);
    }

    private void assignAndSetWorkflow(Long rawSn, String workflowStatus) {
        assignLabeler(rawSn, 100L);
        setWorkflow(rawSn, workflowStatus);
    }

    /**
     * 5종 워크플로 상태를 각각 다른 개수로 seed 한다 (배치 상태 COMPLETED 기준).
     *
     * @return 상태별 기대 건수
     */
    private Map<BoardWorkStatus, Long> seedAllWorkStatuses() {
        // UNASSIGNED × 2
        seedCompleted("CLIP-SUM-U1");
        seedCompleted("CLIP-SUM-U2");
        // PENDING(작업중) × 3 — 배정 O + (ASSIGNED / 상태row 없음 / 미지코드)
        LsDataRaw p1 = seedCompleted("CLIP-SUM-P1");
        assignAndSetWorkflow(p1.getRawSn(), LsRawDataStatus.STTS_ASSIGNED);
        LsDataRaw p2 = seedCompleted("CLIP-SUM-P2");
        assignLabeler(p2.getRawSn(), 100L);
        LsDataRaw p3 = seedCompleted("CLIP-SUM-P3");
        assignAndSetWorkflow(p3.getRawSn(), "UNKNOWN_FUTURE");
        // REVIEW_PENDING × 4 (PENDING 2 + IN_REVIEW 2)
        for (int i = 0; i < 2; i++) {
            LsDataRaw v = seedCompleted("CLIP-SUM-RP-P" + i);
            assignAndSetWorkflow(v.getRawSn(), LsRawDataStatus.STTS_PENDING);
            LsDataRaw w = seedCompleted("CLIP-SUM-RP-R" + i);
            assignAndSetWorkflow(w.getRawSn(), LsRawDataStatus.STTS_IN_REVIEW);
        }
        // COMPLETED × 5
        for (int i = 0; i < 5; i++) {
            LsDataRaw v = seedCompleted("CLIP-SUM-C" + i);
            assignAndSetWorkflow(v.getRawSn(), LsRawDataStatus.STTS_APPROVED);
        }
        // REJECTED × 1
        LsDataRaw rj = seedCompleted("CLIP-SUM-RJ");
        assignAndSetWorkflow(rj.getRawSn(), LsRawDataStatus.STTS_REJECTED);

        return Map.of(
                BoardWorkStatus.UNASSIGNED, 2L,
                BoardWorkStatus.PENDING, 3L,
                BoardWorkStatus.REVIEW_PENDING, 4L,
                BoardWorkStatus.COMPLETED, 5L,
                BoardWorkStatus.REJECTED, 1L);
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpServletRequestBuilder request(String path, String... queryParams) {
        var builder = get(path).header("Authorization", "Bearer " + reviewerToken);
        for (int i = 0; i + 1 < queryParams.length; i += 2) {
            builder = builder.param(queryParams[i], queryParams[i + 1]);
        }
        return builder;
    }

    private JsonNode summary(String... queryParams) throws Exception {
        MvcResult result = mockMvc.perform(request("/v1/tasks/board/summary", queryParams))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    /** 이벤트유형 옵션 응답 본문({@code {items, truncated}}). */
    private JsonNode eventTypeResponse(String... queryParams) throws Exception {
        MvcResult result = mockMvc.perform(request("/v1/tasks/board/event-types", queryParams))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private List<String> eventTypes(String... queryParams) throws Exception {
        JsonNode data = eventTypeResponse(queryParams);
        List<String> values = new ArrayList<>();
        data.get("items").forEach(n -> values.add(n.asText()));
        return values;
    }

    /** 목록 조회의 totalElements — summary 버킷과 대조할 "정답" 값. */
    private long listTotal(String... queryParams) throws Exception {
        MvcResult result = mockMvc.perform(request("/v1/tasks/board", queryParams))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("totalElements").asLong();
    }

    private static void assertInvariant(JsonNode data) {
        long sum = data.get("unassigned").asLong()
                + data.get("inProgress").asLong()
                + data.get("reviewPending").asLong()
                + data.get("completed").asLong()
                + data.get("rejected").asLong();
        assertThat(data.get("total").asLong())
                .as("total 은 5종 상태 합계와 항상 일치해야 한다")
                .isEqualTo(sum);
    }

    /**
     * 결과가 실제로 존재하는 상태에서의 불변식 검증.
     *
     * <p>0건이면 불변식은 {@code 0 == 0} 이라 어떤 구현에서도 성립한다 — 필터가 걸린 상태의 버킷 합을
     * 실질 검증하려면 <b>매칭되는 행이 있어야</b> 한다.
     */
    private static void assertInvariantOnNonEmpty(JsonNode data) {
        assertThat(data.get("total").asLong())
                .as("이 케이스는 0 == 0 이 아니라 실제 결과 위에서 불변식을 검증해야 한다")
                .isPositive();
        assertInvariant(data);
    }

    // -------------------------------------------------------------- KPI 집계

    @Test
    @DisplayName("summary_카운트가_페이지크기와_무관하게_전체기준으로_집계된다")
    void summaryCountsWholeResultSetRegardlessOfPageSize() throws Exception {
        Map<BoardWorkStatus, Long> expected = seedAllWorkStatuses();
        long total = expected.values().stream().mapToLong(Long::longValue).sum(); // 15 > 기본 페이지 20 아님

        // 목록은 size=2 로 잘려도(2건만 반환) summary 는 전체 15건 기준이어야 한다.
        assertThat(listTotal("status", "COMPLETED", "size", "2")).isEqualTo(total);
        JsonNode data = summary("status", "COMPLETED", "size", "2");

        assertThat(data.get("total").asLong()).isEqualTo(total);
        for (Map.Entry<BoardWorkStatus, Long> e : expected.entrySet()) {
            assertThat(data.get(FIELD_BY_STATUS.get(e.getKey())).asLong())
                    .as("%s 버킷", e.getKey())
                    .isEqualTo(e.getValue());
        }
    }

    @Test
    @DisplayName("summary_의_total_은_5종_상태_합계와_항상_일치한다")
    void summaryTotalEqualsSumOfBuckets() throws Exception {
        seedAllWorkStatuses();

        assertInvariant(summary("status", "COMPLETED"));
        assertInvariant(summary("status", "COMPLETED", "workStatus", "REJECTED"));
        // 검색어는 픽스처(CCTV-001 = "동대문구 회기로 CCTV" / 작업자100)에 실제로 매칭되는 값이어야
        // 0 == 0 이 아닌 결과 위에서 불변식이 검증된다.
        assertInvariantOnNonEmpty(summary("status", "COMPLETED", "q", "회기로"));
        assertInvariantOnNonEmpty(summary("status", "COMPLETED", "q", "작업자100"));
        assertInvariant(summary("status", "UNASSIGNED"));
        assertInvariant(summary());
    }

    @Test
    @DisplayName("summary_의_각_버킷이_목록조회로_센_개수와_일치한다")
    void eachBucketMatchesListCount() throws Exception {
        seedAllWorkStatuses();
        // 대조군 — 배치 상태가 다른 영상은 양쪽 모두에서 제외돼야 한다.
        LsDataRaw other = seedVideo("CLIP-SUM-OTHER", "CCTV-001", "EVT-FIRE", "PENDING");
        assignAndSetWorkflow(other.getRawSn(), LsRawDataStatus.STTS_APPROVED);

        JsonNode data = summary("status", "COMPLETED");
        for (BoardWorkStatus ws : BoardWorkStatus.values()) {
            long fromList = listTotal("status", "COMPLETED", "size", "1", "workStatus", ws.name());
            assertThat(data.get(FIELD_BY_STATUS.get(ws)).asLong())
                    .as("summary.%s 가 목록(workStatus=%s) 건수와 달라졌다", FIELD_BY_STATUS.get(ws), ws)
                    .isEqualTo(fromList);
        }
        assertThat(data.get("total").asLong()).isEqualTo(listTotal("status", "COMPLETED", "size", "1"));
    }

    @Test
    @DisplayName("workStatus_를_지정해도_summary_는_5종_전부를_집계한다")
    void workStatusIsIgnoredBySummary() throws Exception {
        seedAllWorkStatuses();

        JsonNode noFilter = summary("status", "COMPLETED");
        for (BoardWorkStatus ws : BoardWorkStatus.values()) {
            JsonNode filtered = summary("status", "COMPLETED", "workStatus", ws.name());
            assertThat(filtered.toString())
                    .as("workStatus=%s 로 좁혀지면 안 된다(카드 자체가 workStatus 선택지)", ws)
                    .isEqualTo(noFilter.toString());
            // 5종 카드가 모두 채워져 있어야 한다(1개만 non-zero 가 아니다).
            for (String field : FIELD_BY_STATUS.values()) {
                assertThat(filtered.get(field).asLong()).isPositive();
            }
        }
    }

    @Test
    @DisplayName("summary_에_검색어_이벤트유형_작업자_필터가_목록과_동일하게_적용된다")
    void filtersAppliedIdenticallyToList() throws Exception {
        // 영상명(CCTV-002 = "강남구 테헤란로 CCTV") + 이벤트유형 + 작업자 축을 각각 갈라 둔다.
        LsDataRaw gangnamFall = seedVideo("CLIP-SUM-F1", "CCTV-002", "EVT-FALL", "COMPLETED");
        assignLabeler(gangnamFall.getRawSn(), 100L);
        LsDataRaw gangnamFire = seedVideo("CLIP-SUM-F2", "CCTV-002", "EVT-FIRE", "COMPLETED");
        assignAndSetWorkflow(gangnamFire.getRawSn(), LsRawDataStatus.STTS_APPROVED);
        LsDataRaw otherFall = seedVideo("CLIP-SUM-F3", "CCTV-001", "EVT-FALL", "COMPLETED");
        assignLabeler(otherFall.getRawSn(), 101L);
        seedVideo("CLIP-SUM-F4", "CCTV-001", "EVT-FIRE", "COMPLETED");

        String[][] filters = {
                {"q", "강남"},
                {"eventTypeCd", "EVT-FALL"},
                {"workerId", "100"},
                {"q", "강남", "eventTypeCd", "EVT-FALL"},
                {"q", "작업자101"},
                {"q", "존재하지않는영상명"},
        };
        for (String[] filter : filters) {
            String[] params = new String[filter.length + 4];
            params[0] = "status";
            params[1] = "COMPLETED";
            params[2] = "size";
            params[3] = "1";
            System.arraycopy(filter, 0, params, 4, filter.length);

            long fromList = listTotal(params);
            JsonNode data = summary(params);
            assertThat(data.get("total").asLong())
                    .as("필터 %s 의 summary.total 이 목록 totalElements 와 다르다", String.join("=", filter))
                    .isEqualTo(fromList);
            assertInvariant(data);
        }
    }

    @Test
    @DisplayName("필터_결과가_0건이면_summary_전_필드가_0이다")
    void zeroResultReturnsAllZeroFields() throws Exception {
        seedAllWorkStatuses();

        JsonNode data = summary("status", "COMPLETED", "q", "존재하지않는영상명_XYZ");

        for (String field : List.of("total", "unassigned", "inProgress", "reviewPending", "completed", "rejected")) {
            assertThat(data.has(field)).as("%s 필드가 응답에 존재해야 한다", field).isTrue();
            assertThat(data.get(field).isNull()).as("%s 필드가 null 이면 안 된다", field).isFalse();
            assertThat(data.get(field).asLong()).as("%s", field).isZero();
        }
    }

    @Test
    @DisplayName("status_UNASSIGNED_일_때_summary_는_배치상태_무관_전체를_기준으로_한다")
    void unassignedVirtualStatusIgnoresBatchStatus() throws Exception {
        seedVideo("CLIP-SUM-UV1", "CCTV-001", "EVT-FIRE", "PENDING");   // 미배정 + 배치 PENDING
        seedCompleted("CLIP-SUM-UV2");                                   // 미배정 + 배치 COMPLETED
        LsDataRaw assigned = seedCompleted("CLIP-SUM-UV3");              // 배정 O — 제외
        assignAndSetWorkflow(assigned.getRawSn(), LsRawDataStatus.STTS_APPROVED);

        JsonNode data = summary("status", "UNASSIGNED");

        assertThat(data.get("total").asLong()).isEqualTo(2);
        assertThat(data.get("unassigned").asLong()).isEqualTo(2);
        assertThat(data.get("inProgress").asLong()).isZero();
        assertThat(data.get("reviewPending").asLong()).isZero();
        assertThat(data.get("completed").asLong()).isZero();
        assertThat(data.get("rejected").asLong()).isZero();
        assertThat(data.get("total").asLong()).isEqualTo(listTotal("status", "UNASSIGNED", "size", "1"));
    }

    /**
     * <b>UNASSIGNED 두 축 계약 고정</b> — KPI 미배정 카드를 클릭할 때 FE 가 보내야 할 파라미터는
     * {@code status=UNASSIGNED} 가 아니라 {@code workStatus=UNASSIGNED} 다.
     *
     * <p>미배정 영상이 여러 배치 상태에 흩어져 있으면 두 축의 결과가 갈린다:
     * <ul>
     *   <li>{@code summary?status=COMPLETED} 의 {@code unassigned} = <b>배치 상태 필터 안에서의</b> 미배정</li>
     *   <li>{@code board?status=COMPLETED&workStatus=UNASSIGNED} = 같은 집합 (카드 ↔ 목록 일치)</li>
     *   <li>{@code board?status=UNASSIGNED} = <b>배치 상태 무관</b> 미배정 전체 (더 넓다)</li>
     * </ul>
     */
    @Test
    @DisplayName("미배정_카드는_workStatus_UNASSIGNED_목록과_일치하고_status_UNASSIGNED_목록과는_다르다")
    void unassignedCardMatchesWorkStatusAxisNotVirtualStatus() throws Exception {
        // given — 미배정 13건을 배치 상태 두 축으로 흩어 놓는다 (COMPLETED 3 + PENDING 10)
        for (int i = 0; i < 3; i++) {
            seedCompleted("CLIP-UAX-C" + i);
        }
        for (int i = 0; i < 10; i++) {
            seedVideo("CLIP-UAX-P" + i, "CCTV-001", "EVT-FIRE", "PENDING");
        }
        // 대조군 — 배정된 COMPLETED 영상은 어느 축에서도 미배정으로 세지 않는다.
        LsDataRaw assigned = seedCompleted("CLIP-UAX-A");
        assignAndSetWorkflow(assigned.getRawSn(), LsRawDataStatus.STTS_APPROVED);

        // when
        long cardUnassigned = summary("status", "COMPLETED").get("unassigned").asLong();
        long workStatusAxis = listTotal("status", "COMPLETED", "workStatus", "UNASSIGNED", "size", "1");
        long virtualStatusAxis = listTotal("status", "UNASSIGNED", "size", "1");

        // then — 카드는 workStatus 축과 일치한다(FE 가 클릭 시 보내야 할 파라미터)
        assertThat(cardUnassigned).isEqualTo(3);
        assertThat(cardUnassigned)
                .as("KPI 미배정 카드는 board?status=X&workStatus=UNASSIGNED 와 같아야 한다")
                .isEqualTo(workStatusAxis);
        // 그리고 status=UNASSIGNED 는 배치 상태 무관 전체라 더 넓다 — 이 함정을 명시적으로 고정한다.
        assertThat(virtualStatusAxis).isEqualTo(13);
        assertThat(virtualStatusAxis)
                .as("status=UNASSIGNED 는 배치 상태 필터를 끄므로 카드 숫자와 달라질 수 있다(FE 혼동 함정)")
                .isNotEqualTo(cardUnassigned);
    }

    // -------------------------------------------------------- 이벤트유형 옵션

    @Test
    @DisplayName("이벤트유형_옵션이_중복없이_정렬되어_반환된다")
    void eventTypeOptionsAreDistinctAndSorted() throws Exception {
        seedVideo("CLIP-ET-1", "CCTV-001", "EVT-FIRE", "COMPLETED");
        seedVideo("CLIP-ET-2", "CCTV-001", "EVT-FIRE", "COMPLETED");
        seedVideo("CLIP-ET-3", "CCTV-001", "EVT-FALL", "COMPLETED");
        seedVideo("CLIP-ET-4", "CCTV-001", "EVT-CRASH", "COMPLETED");
        seedVideo("CLIP-ET-5", "CCTV-001", "EVT-INTRUDE", "PENDING"); // 배치 상태 다름 — 제외

        assertThat(eventTypes("status", "COMPLETED"))
                .containsExactly("EVT-CRASH", "EVT-FALL", "EVT-FIRE");
    }

    @Test
    @DisplayName("이벤트유형_옵션에_null_은_포함되지_않는다")
    void eventTypeOptionsExcludeNull() throws Exception {
        seedVideo("CLIP-ET-N1", "CCTV-001", null, "COMPLETED");
        seedVideo("CLIP-ET-N2", "CCTV-001", "EVT-FIRE", "COMPLETED");

        List<String> options = eventTypes("status", "COMPLETED");

        assertThat(options).containsExactly("EVT-FIRE");
        assertThat(options).doesNotContainNull();
    }

    /**
     * HTTP 계약 — 옵션 엔드포인트에 status 외 파라미터를 붙여도 옵션이 좁아지지 않는다.
     *
     * <p>⚠ 이 테스트만으로는 방어({@code statusOnly()})가 검증되지 않는다 — 컨트롤러가 선언하지 않은
     * 파라미터는 조건 객체에 애초에 도달하지 않아 방어를 제거해도 통과한다. 방어 자체는 아래
     * {@link #eventTypeOptionsIgnoreNonStatusFiltersAtRepositoryLevel()} 과
     * {@code TaskBoardSearchConditionTest} 가 검증한다.
     */
    @Test
    @DisplayName("이벤트유형_옵션은_검색어_작업자_필터에_영향받지_않는다")
    void eventTypeOptionsIgnoreNonStatusFilters() throws Exception {
        LsDataRaw gangnam = seedVideo("CLIP-ET-G", "CCTV-002", "EVT-FALL", "COMPLETED");
        assignLabeler(gangnam.getRawSn(), 100L);
        seedVideo("CLIP-ET-O", "CCTV-001", "EVT-FIRE", "COMPLETED");

        List<String> all = List.of("EVT-FALL", "EVT-FIRE");
        assertThat(eventTypes("status", "COMPLETED")).containsExactlyElementsOf(all);
        assertThat(eventTypes("status", "COMPLETED", "q", "강남")).containsExactlyElementsOf(all);
        assertThat(eventTypes("status", "COMPLETED", "workerId", "100")).containsExactlyElementsOf(all);
        assertThat(eventTypes("status", "COMPLETED", "eventTypeCd", "EVT-FALL")).containsExactlyElementsOf(all);
        assertThat(eventTypes("status", "COMPLETED", "workStatus", "REJECTED")).containsExactlyElementsOf(all);
    }

    /**
     * <b>방어 검증(수정 1-A)</b> — 리포지토리를 직접 호출해 <b>모든 필터가 채워진 조건</b>을 넘겨도
     * 옵션이 좁아지지 않음을 확인한다({@code findDistinctEventTypes} 가 {@code statusOnly()} 를 적용).
     *
     * <p>컨트롤러 시그니처를 우회하므로, Phase 3 에서 옵션 엔드포인트에 파라미터가 추가돼도 이 테스트는
     * 방어가 살아있는지를 계속 판정한다. {@code .statusOnly()} 를 제거하면 RED 가 된다.
     */
    @Test
    @DisplayName("이벤트유형_옵션조회는_리포지토리_레벨에서도_status_외_필터를_무시한다")
    void eventTypeOptionsIgnoreNonStatusFiltersAtRepositoryLevel() {
        // given — 검색어/이벤트유형/작업자/워크플로 축이 각각 다른 부분집합을 가리키도록 배치
        LsDataRaw gangnam = seedVideo("CLIP-ETR-G", "CCTV-002", "EVT-FALL", "COMPLETED");
        assignLabeler(gangnam.getRawSn(), 100L);
        seedVideo("CLIP-ETR-O", "CCTV-001", "EVT-FIRE", "COMPLETED");
        seedVideo("CLIP-ETR-X", "CCTV-001", "EVT-INTRUDE", "PENDING"); // 배치 상태 다름 — 유일한 유효 필터

        List<String> expected = List.of("EVT-FALL", "EVT-FIRE");
        int limit = 100;

        // when/then — status 외 축을 전부 채워도 status-only 결과와 동일해야 한다
        assertThat(taskBoardQueryRepository.findDistinctEventTypes(
                new TaskBoardSearchCondition("COMPLETED", null, null, null, null), limit))
                .containsExactlyElementsOf(expected);
        assertThat(taskBoardQueryRepository.findDistinctEventTypes(
                new TaskBoardSearchCondition("COMPLETED", "REJECTED", "강남", "EVT-FALL", 100L), limit))
                .as("q/eventTypeCd/workerId/workStatus 가 옵션 목록을 좁히면 안 된다")
                .containsExactlyElementsOf(expected);
        // 배치 상태(status) 는 유일하게 반영되는 축이다.
        assertThat(taskBoardQueryRepository.findDistinctEventTypes(
                new TaskBoardSearchCondition("PENDING", "REJECTED", "강남", "EVT-FALL", 100L), limit))
                .containsExactly("EVT-INTRUDE");
    }

    @Test
    @DisplayName("이벤트유형_옵션값은_앞뒤_공백이_제거되어_필터_입력과_의미가_같다")
    void eventTypeOptionsAreTrimmedToMatchFilterInput() throws Exception {
        // given — 선행 공백이 섞인 코드. 필터 입력은 trim 되므로 옵션도 trim 돼야 매칭된다.
        seedVideo("CLIP-ETT-1", "CCTV-001", " EVT-FIRE", "COMPLETED");
        seedVideo("CLIP-ETT-2", "CCTV-001", "EVT-FIRE ", "COMPLETED");
        seedVideo("CLIP-ETT-3", "CCTV-001", "   ", "COMPLETED"); // 공백만 — 제외

        // when
        List<String> options = eventTypes("status", "COMPLETED");

        // then — 공백만 다른 코드는 하나로 합쳐지고, 그 옵션으로 필터하면 실제로 행이 나온다.
        assertThat(options).containsExactly("EVT-FIRE");
        assertThat(listTotal("status", "COMPLETED", "eventTypeCd", options.get(0), "size", "1"))
                .as("옵션으로 고른 값이 필터에서 0건이면 UI 로 도달할 수 없는 코드가 된다")
                .isPositive();
    }

    @Test
    @DisplayName("이벤트유형_옵션이_상한_이하면_truncated_는_false_다")
    void eventTypeOptionsNotTruncatedUnderLimit() throws Exception {
        seedVideo("CLIP-ETN-1", "CCTV-001", "EVT-FIRE", "COMPLETED");

        JsonNode data = eventTypeResponse("status", "COMPLETED");

        assertThat(data.get("truncated").asBoolean()).isFalse();
        assertThat(data.get("items")).hasSize(1);
    }

    /**
     * 절단 분기(상한 초과) — 잘린 사실이 <b>응답에 드러나야</b> 한다.
     *
     * <p>{@code truncated} 신호가 없으면 상한 밖 코드는 셀렉트박스에 영원히 나타나지 않는데
     * 목록 필터({@code eventTypeCd})로는 정상 조회되므로, 사용자는 "그 이벤트유형 영상이 없다"고
     * 오인한다(조용한 데이터 손실).
     */
    @Test
    @DisplayName("이벤트유형_옵션이_상한을_넘으면_잘라내고_truncated_로_알린다")
    void eventTypeOptionsSignalTruncationOverLimit() throws Exception {
        // given — 상한(500) + 1 종의 서로 다른 이벤트유형 코드
        int limit = 500;
        List<LsDataRaw> videos = new ArrayList<>();
        for (int i = 0; i <= limit; i++) {
            LsDataRaw raw = LsDataRaw.createFromIngest(
                    String.format("CLIP-ETL-%03d", i), "CCTV-001", String.format("EVT-%03d", i), "11680",
                    LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/etl.mp4",
                    LocalDateTime.of(2026, 5, 1, 9, 0, 0), 30);
            raw.changeStatus("COMPLETED");
            videos.add(raw);
        }
        videoRepository.saveAll(videos);

        // when
        JsonNode data = eventTypeResponse("status", "COMPLETED");
        List<String> items = new ArrayList<>();
        data.get("items").forEach(n -> items.add(n.asText()));

        // then — 상한만큼만 오름차순으로 잘리고, 잘렸다는 사실이 응답에 드러난다
        assertThat(items).hasSize(limit);
        assertThat(data.get("truncated").asBoolean())
                .as("절단이 응답에 드러나지 않으면 사용자는 데이터가 없다고 오인한다")
                .isTrue();
        assertThat(items).first().isEqualTo("EVT-000");
        assertThat(items).last().isEqualTo(String.format("EVT-%03d", limit - 1));
        assertThat(items).doesNotContain(String.format("EVT-%03d", limit));
        // 잘린 코드로도 목록 필터는 정상 동작한다 = "데이터는 있는데 UI 로 도달 불가" 상황의 근거.
        assertThat(listTotal("status", "COMPLETED", "eventTypeCd", String.format("EVT-%03d", limit), "size", "1"))
                .isEqualTo(1);
    }

    // -------------------------------------------------------------- 인가 (HIGH-6)

    @Test
    @DisplayName("summary_는_REVIEWER_아닌_사용자에게_403")
    void summaryForbiddenForWorker() throws Exception {
        seedCompleted("CLIP-SEC-S1");
        mockMvc.perform(get("/v1/tasks/board/summary?status=COMPLETED")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("event_types_는_REVIEWER_아닌_사용자에게_403")
    void eventTypesForbiddenForWorker() throws Exception {
        seedCompleted("CLIP-SEC-E1");
        mockMvc.perform(get("/v1/tasks/board/event-types?status=COMPLETED")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("summary_미인증시_401")
    void summaryUnauthenticated() throws Exception {
        mockMvc.perform(get("/v1/tasks/board/summary?status=COMPLETED"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("event_types_미인증시_401")
    void eventTypesUnauthenticated() throws Exception {
        mockMvc.perform(get("/v1/tasks/board/event-types?status=COMPLETED"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------- 입력 검증

    @Test
    @DisplayName("summary_의_허용되지_않은_status_값이면_400")
    void summaryRejectsUnknownStatus() throws Exception {
        mockMvc.perform(get("/v1/tasks/board/summary?status=DROP_TABLE")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("summary_의_과대_길이_검색어는_400")
    void summaryRejectsTooLongQuery() throws Exception {
        mockMvc.perform(request("/v1/tasks/board/summary", "status", "COMPLETED", "q", "가".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("summary_검색어의_SQL_인젝션_시도는_리터럴로_바인딩된다")
    void summaryBindsInjectionAttempt() throws Exception {
        LsDataRaw v = seedCompleted("CLIP-SUM-INJ");

        for (String malicious : List.of("'; DROP TABLE LS_DATA_RAW; --", "' OR 1=1 --")) {
            JsonNode data = summary("status", "COMPLETED", "q", malicious);
            assertThat(data.get("total").asLong()).isZero();
        }
        assertThat(videoRepository.findById(v.getRawSn())).isPresent();
    }

    /**
     * FE 가 상태 필터를 해제하며 {@code status=} 를 보내도 KPI 카드/옵션이 통째로 400 이 되면 안 된다
     * (대시보드가 빈다). 빈 값/공백만은 기본값(COMPLETED)으로 정규화한다 — 기존
     * {@code GET /v1/tasks/board} 의 status regex 는 R8 하위호환 제약으로 그대로 둔다.
     */
    @Test
    @DisplayName("summary_와_event_types_의_빈_status_는_기본값으로_정규화된다")
    void blankStatusFallsBackToDefaultOnNewEndpoints() throws Exception {
        seedCompleted("CLIP-BLANK-1");
        seedVideo("CLIP-BLANK-2", "CCTV-001", "EVT-FALL", "PENDING"); // 기본값(COMPLETED) 밖 — 제외돼야 한다

        for (String blank : List.of("", "   ")) {
            JsonNode data = summary("status", blank);
            assertThat(data.get("total").asLong())
                    .as("status=%s 는 400 이 아니라 기본값(COMPLETED) 집계여야 한다", blank)
                    .isEqualTo(summary("status", "COMPLETED").get("total").asLong());
            assertThat(eventTypes("status", blank)).containsExactly("EVT-FIRE");
        }
    }

    @Test
    @DisplayName("event_types_의_허용되지_않은_status_값이면_400")
    void eventTypesRejectsUnknownStatus() throws Exception {
        mockMvc.perform(get("/v1/tasks/board/event-types?status=DROP_TABLE")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }
}
