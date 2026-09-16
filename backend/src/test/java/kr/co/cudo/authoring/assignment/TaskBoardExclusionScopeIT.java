package kr.co.cudo.authoring.assignment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.QLsDataSrc;
import kr.co.cudo.authoring.assignment.dto.TaskBoardSearchCondition;
import kr.co.cudo.authoring.assignment.repository.TaskBoardQueryRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoExclusionScope;
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

import javax.sql.DataSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ★<b>작업 목록 두 축의 제외분 배제</b> — 검수자 축({@code /v1/tasks/board*})과 작업자 축
 * ({@code /v1/assignments*}). [@design ADR-069] [@design API-073] [@design API-136] [@design API-072]
 * [@design AC-1124] [@design AC-1127]
 *
 * <h2>왜 두 축을 한 클래스에서 보나</h2>
 * <p>축마다 구동 테이블이 달라 배제 술어의 <b>형태가 다르다</b>(검수자 축은 영상 원장이 구동이라 직접
 * 비교, 작업자 축은 배정이 구동이라 상관 EXISTS). 형태가 달라도 <b>결과는 같아야</b> 하며, 한쪽만
 * 검증하면 다른 쪽이 조용히 빠져도 드러나지 않는다 — 제외는 실패가 아니라 <b>조용한 축소</b>로
 * 나타나는 축이라 정상 경로 시험을 전부 통과한다.
 *
 * <h2>검사 범위를 우리 영상으로 좁힌다</h2>
 * <p>테스트가 공유 컨테이너를 쓰므로, 다른 클래스가 남긴 영상이 섞이면 건수 단언이 헛돈다. 모든 조회에
 * 이 클래스가 만든 영상만 걸리는 <b>유일 검색어</b>를 실어 자족하게 만든다.
 *
 * <h2>작업자 축은 「구조적으로 도달 불가」한데도 검증한다</h2>
 * <p>배정이 있는 영상은 제외가 거부되므로(쓰기 경로의 조건부 갱신) 제외된 영상에는 배정이 없고, 따라서
 * 배정 목록에는 원래 나타나지 않는다. 그래서 여기서는 <b>그 거부가 뚫린 상태를 저장소에 직접 만들어</b>
 * 마지막 방어가 실제로 서 있는지를 본다 — 창구를 통해서는 재현할 수 없는 상황이기 때문이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = "/db/test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class TaskBoardExclusionScopeIT {

    private static final long REVIEWER_NO = 1L;
    private static final long WORKER_NO = 100L;

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private TaskBoardQueryRepository taskBoardQueryRepository;

    @PersistenceContext(unitName = "control")
    private EntityManager entityManager;

    private final JdbcTemplate jdbcTemplate;

    TaskBoardExclusionScopeIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String reviewerToken;
    /** 이 시험 실행에서만 매칭되는 검색어 — 조회 범위를 우리 영상으로 좁힌다. */
    private String tag;

    @BeforeEach
    void setUp() {
        reviewerToken = JwtTestSupport.token(
                secret, String.valueOf(REVIEWER_NO), "REVIEWER", "INTERNAL", issuer, 60);
        tag = "exclscope" + System.nanoTime();
    }

    // 정리 훅을 두지 않는다 — 이 시험이 만드는 행은 전부 자기 영상의 자식이라 공용 표에 남는 것이 없다.
    // ⚠ 공용 표(사용자 원장 등)에 남의 식별자 공간을 침범하는 행을 심고 지우는 방식으로 되돌리지 말 것.

    // ── 픽스처 ────────────────────────────────────────────────────────────────

    /** 배치 상태를 {@code COMPLETED} 로 둔 영상 — 작업 목록의 기본 필터가 그 값이다. */
    private Long newVideo(String eventTypeCd) {
        Long rawSn = videoRepository.save(LsDataRaw.createFromIngest(
                "CLIP-" + tag + "-" + System.nanoTime(), tag + System.nanoTime(),
                eventTypeCd, "11680", LsDataRaw.PRVC_TYPE_PRVC,
                "/var/raw/clip.mp4", LocalDateTime.now(), 30)).getRawSn();
        jdbcTemplate.update("UPDATE ls_data_raw SET data_stts_cd = 'COMPLETED' WHERE raw_sn = ?", rawSn);
        return rawSn;
    }

    /**
     * 이 시험의 검색어에 <b>걸리지 않는</b> 영상 — 「필터 범위 밖」을 만드는 데만 쓴다.
     *
     * <p>표시명 폴백이 곧 검색 대상이므로 CCTV 식별자를 다른 네임스페이스로 둔다. 배치 상태는 같게
     * 두어 <b>검색어 축 하나만</b> 다르게 한다 — 그래야 이 한 건이 무엇을 가르는지가 분명해진다.
     */
    private Long newVideoWithOtherTag() {
        String otherTag = "outsidefilter" + System.nanoTime();
        Long rawSn = videoRepository.save(LsDataRaw.createFromIngest(
                "CLIP-" + otherTag, otherTag, "EXCLD", "11680", LsDataRaw.PRVC_TYPE_PRVC,
                "/var/raw/clip.mp4", LocalDateTime.now(), 30)).getRawSn();
        jdbcTemplate.update("UPDATE ls_data_raw SET data_stts_cd = 'COMPLETED' WHERE raw_sn = ?", rawSn);
        return rawSn;
    }

    private void excludeViaApi(Long rawSn) throws Exception {
        mockMvc.perform(post("/v1/videos/" + rawSn + "/exclusion")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"시험 데이터\"}"))
                .andExpect(status().isOk());
    }

    /**
     * 쓰기 경로를 거치지 않고 제외 표시를 세운다 — <b>배정이 있는 영상</b>은 창구로는 제외할 수 없어
     * 작업자 축의 마지막 방어를 재현할 다른 방법이 없다.
     */
    private void forceExcluded(Long rawSn) {
        jdbcTemplate.update("UPDATE ls_data_raw SET excl_yn = ? WHERE raw_sn = ?",
                LsDataRaw.EXCL_YES, rawSn);
    }

    private void assign(Long rawSn, long workerNo) throws Exception {
        mockMvc.perform(post("/v1/assignments")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"workerId\":" + workerNo + ",\"rawDataIds\":[" + rawSn + "]}"))
                .andExpect(status().isCreated());
    }

    private JsonNode getJson(String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private JsonNode board() throws Exception {
        return getJson("/v1/tasks/board?status=COMPLETED&q=" + tag + "&size=100");
    }

    /** 같은 목록을 「제외분만 보기」 갈래로 — 다른 축은 전부 동일하게 둔다. */
    private JsonNode board(boolean excludedOnly) throws Exception {
        return getJson("/v1/tasks/board?status=COMPLETED&q=" + tag + "&size=100"
                + "&excludedOnly=" + excludedOnly);
    }

    private JsonNode boardSummary() throws Exception {
        return getJson("/v1/tasks/board/summary?status=COMPLETED&q=" + tag);
    }

    private JsonNode assignments() throws Exception {
        return getJson("/v1/assignments?workerId=" + WORKER_NO + "&q=" + tag + "&size=100");
    }

    private List<Long> videoIds(JsonNode page, String field) {
        List<Long> ids = new ArrayList<>();
        page.path("content").forEach(n -> ids.add(n.path(field).asLong()));
        return ids;
    }

    // ── 검수자 축 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★검수자축_제외된_영상이_작업목록과_총건수에서_함께_빠진다")
    void reviewerAxisDropsExcludedVideos() throws Exception {
        Long kept1 = newVideo("EXCLA");
        Long kept2 = newVideo("EXCLB");
        Long excluded = newVideo("EXCLC");

        JsonNode before = board();
        assertThat(before.path("totalElements").asLong())
                .as("기준값 자체가 성립해야 시험이 의미를 갖는다").isEqualTo(3L);

        excludeViaApi(excluded);

        JsonNode after = board();
        assertThat(after.path("totalElements").asLong())
                .as("★목록과 건수가 같은 조건을 공유해야 한다 — 한쪽에만 붙으면 건수와 내용이 어긋난다")
                .isEqualTo(2L);
        assertThat(videoIds(after, "videoId")).containsExactlyInAnyOrder(kept1, kept2);
    }

    /**
     * ★★<b>버킷 합 = 전체 건수 불변식이 제외 후에도 유지된다.</b>
     *
     * <p>제외 판정을 <b>버킷 판정식</b>에 섞으면 어느 버킷에도 들지 않는 행이 생겨 이 불변식이 깨진다
     * (그 위반은 서버 예외로 드러난다). 조회 조건에만 붙였음을 결과로 고정한다.
     *
     * <p>「제외됨 건수」는 <b>집계 창구가 소유</b>한다 — 목록 응답에는 그 키를 두지 않는다. 같은 숫자를
     * 두 창구가 각각 계산하면 한쪽만 조건이 바뀌어도 드러나지 않기 때문이다.
     *
     * <h3>★필터 범위 결박은 <b>픽스처가</b> 만든다 — 컨테이너 잔여 상태에 기대지 않는다</h3>
     * <p>그 숫자가 「필터 범위 안」인지 「필터를 무시한 전체」인지는 <b>범위 밖에 제외 영상이 실재할 때만</b>
     * 갈린다. 다른 클래스가 남긴 제외 영상에 기대면, 이 스위트만 깨끗한 저장소에서 돌 때 두 해석이 같은
     * 값(0 → 1)을 내어 <b>단언이 무력해지고 거짓 초록이 된다</b>. 그래서 <b>현재 검색어에 걸리지 않는
     * 제외 영상 1건</b>을 명시적으로 심어 이 시험이 스스로 두 해석을 가르게 한다.
     */
    @Test
    @DisplayName("★★검수자축_KPI집계에서_제외분이_빠지고_버킷합_불변식이_유지되며_제외됨건수는_집계창구만_싣는다")
    void reviewerAxisSummaryKeepsBucketInvariantAndOwnsExcludedCount() throws Exception {
        newVideo("EXCLA");
        newVideo("EXCLB");
        Long excluded = newVideo("EXCLC");
        // ★필터 <밖>의 제외 영상 — 검색어가 다르므로 어느 응답에도 잡히면 안 된다.
        //   이 한 건이 「필터 범위 안에서 센다」와 「전체를 센다」를 가르는 유일한 장치다.
        Long outsideFilter = newVideoWithOtherTag();
        excludeViaApi(outsideFilter);

        JsonNode before = boardSummary();
        assertThat(before.path("total").asLong()).isEqualTo(3L);
        assertBucketInvariant(before);
        assertThat(before.path("excludedCount").isMissingNode())
                .as("0건이어도 실려야 한다 — 빠지면 「제외된 것이 없다」와 「제외 기능이 없다」가 구분되지 않는다")
                .isFalse();
        assertThat(before.path("excludedCount").asLong())
                .as("★필터 밖에 제외 영상이 1건 실재하는데도 0이어야 한다 — 필터를 무시하고 세면 여기서 드러난다")
                .isZero();

        excludeViaApi(excluded);

        JsonNode after = boardSummary();
        assertThat(after.path("total").asLong())
                .as("집계가 목록과 같은 조건을 공유해야 한다 — 갈리면 목록은 줄었는데 카드 숫자는 그대로다")
                .isEqualTo(2L);
        assertBucketInvariant(after);
        assertThat(after.path("excludedCount").asLong())
                .as("★같은 필터 범위 안의 제외 건수여야 한다 — 필터 밖 1건까지 세면 2가 되어 여기서 드러난다"
                        + "(이 숫자를 눌러 얻는 목록과 정확히 같아야 한다)")
                .isEqualTo(1L);

        assertThat(board().path("excludedCount").isMissingNode())
                .as("★「제외됨 N건」의 진실원은 집계 창구 하나다 — 목록 응답에 그 키를 두지 않는다")
                .isTrue();
    }

    private void assertBucketInvariant(JsonNode summary) {
        long sum = summary.path("unassigned").asLong()
                + summary.path("inProgress").asLong()
                + summary.path("reviewPending").asLong()
                + summary.path("completed").asLong()
                + summary.path("rejected").asLong();
        assertThat(sum)
                .as("★버킷 합 = 전체 건수 — 제외 판정을 버킷 판정식에 섞으면 이 불변식이 깨진다")
                .isEqualTo(summary.path("total").asLong());
        assertThat(summary.path("excludedCount").asLong())
                .as("제외됨 건수는 이 합의 항이 아니다(제외분은 total 에서 이미 빠져 있다)")
                .isNotNegative();
    }

    @Test
    @DisplayName("검수자축_이벤트유형_옵션에서도_제외된_영상의_코드가_사라진다")
    void reviewerAxisEventTypeOptionsDropExcludedVideos() throws Exception {
        newVideo("EXCLA");
        Long excluded = newVideo("EXCLC");

        // 옵션 조회는 검색어를 반영하지 않으므로(되돌아갈 수 없게 되는 것을 막는다) 우리 코드만 골라 본다.
        assertThat(eventTypeOptions()).contains("EXCLA", "EXCLC");

        excludeViaApi(excluded);

        assertThat(eventTypeOptions())
                .as("조건 조립 단일 지점에 붙였으므로 옵션도 같은 가시 범위를 공유한다")
                .contains("EXCLA")
                .doesNotContain("EXCLC");
    }

    private List<String> eventTypeOptions() throws Exception {
        return codesOf(getJson("/v1/tasks/board/event-types?status=COMPLETED"));
    }

    // ── 제외분만 보기 (excludedOnly) ──────────────────────────────────────────

    /**
     * ★<b>「제외됨 N건」을 눌러 전환할 곳이 실재한다</b> — 복원 동선의 마지막 칸.
     * [@design API-073] [@design ADR-069] [@design AC-1124]
     *
     * <p>숫자만 뜨고 전환 수단이 없으면 <b>제외한 영상을 되돌릴 길이 없다</b>. 확정 요구가
     * 「그 숫자를 누르면 제외분만 보는 목록으로 전환하고 거기서 복원한다」이므로, 이 갈래는 편의가 아니라
     * <b>되돌리기의 필수 구간</b>이다.
     *
     * <h3>세 가지를 한 흐름에서 본다</h3>
     * <ul>
     *   <li><b>하위호환</b> — 보내지 않은 것과 {@code false} 로 보낸 것이 같다(기본값 불변).</li>
     *   <li><b>전환</b> — {@code true} 면 제외분만 남는다.</li>
     *   <li><b>숫자와 결과의 일치</b> — 그 총건수가 집계의 「제외됨 건수」와 같다. 이것이 없으면
     *       「3건」을 눌렀는데 1건이 나오는 화면이 된다.</li>
     * </ul>
     *
     * <h3>★픽스처가 자족한다</h3>
     * <p>「이 검색어에 걸리지 않는 제외 영상」을 직접 심어, 제외분 보기가 <b>다른 축 필터를 여전히
     * 적용하는지</b>를 스스로 가른다. 심지 않으면 「제외분 전체를 보여 주는 것」과 「필터 안의 제외분만
     * 보여 주는 것」이 같은 결과를 내어 단언이 무력해진다.
     */
    @Test
    @DisplayName("★제외분만_보기로_전환된다_안보내면_종전과_같고_총건수가_제외됨건수와_일치한다")
    void excludedOnlySwitchesTheListAndMatchesTheCount() throws Exception {
        Long kept1 = newVideo("EXCLA");
        Long kept2 = newVideo("EXCLB");
        Long excluded = newVideo("EXCLC");
        // ★이 검색어에 걸리지 않는 제외 영상 — 제외분 보기에도 다른 축 필터가 살아 있음을 가른다.
        excludeViaApi(newVideoWithOtherTag());
        excludeViaApi(excluded);

        // ① 하위호환 — 보내지 않은 것과 false 가 같다.
        JsonNode omitted = board();
        JsonNode explicitFalse = board(false);
        assertThat(explicitFalse.path("totalElements").asLong())
                .as("★보내지 않던 기존 호출의 결과가 달라지면 안 된다")
                .isEqualTo(omitted.path("totalElements").asLong())
                .isEqualTo(2L);
        assertThat(videoIds(explicitFalse, "videoId")).containsExactlyInAnyOrder(kept1, kept2);

        // ② 전환 — 제외분만 남는다.
        JsonNode only = board(true);
        assertThat(videoIds(only, "videoId"))
                .as("★제외분만 남아야 한다 — 표시분이 섞이면 어느 것이 제외분인지 행마다 구분해야 한다")
                .containsExactly(excluded);
        assertThat(only.path("totalElements").asLong())
                .as("★검색어 밖의 제외 영상까지 나오면 다른 축 필터가 죽은 것이다")
                .isEqualTo(1L);

        // ③ 숫자와 전환 결과가 일치한다 — 이 화면의 복원 동선이 성립하는 조건.
        assertThat(only.path("totalElements").asLong())
                .as("★집계의 「제외됨 건수」를 눌러 얻는 목록의 총건수가 그 숫자와 같아야 한다")
                .isEqualTo(boardSummary().path("excludedCount").asLong());
    }

    /**
     * ★<b>집계와 이벤트유형 옵션도 같은 갈래를 따른다</b> — 목록만 전환되고 나머지가 기본 갈래에
     * 남으면 화면 안에서 두 시야가 섞인다. [@design API-073]
     *
     * <h3>왜 창구가 아니라 조립 지점에서 보나</h3>
     * <p>집계·옵션 <b>창구</b>는 이 파라미터를 선언하지 않는다(설계에 없다). 그래서 HTTP 로는 이 성질을
     * 관측할 수 없고, 갈래가 한 길목(조건 조립)에서 정해진다는 사실 자체를 여기서 고정한다 — 그래야
     * 그 파라미터가 창구에 생기는 날 <b>저절로</b> 맞게 동작한다.
     */
    @Test
    @DisplayName("★집계와_이벤트유형_옵션이_목록과_같은_갈래를_따른다")
    void summaryAndOptionsFollowTheSameBranch() throws Exception {
        // ★이벤트유형 코드는 이 실행에서만 쓰는 값으로 만든다 — 옵션 조회는 검색어 축을 <반영하지 않아>
        //   (필터를 걸면 옵션이 사라져 되돌아갈 수 없기 때문) 검색어로 범위를 좁힐 수 없다. 고정 문자열을
        //   쓰면 공유 컨테이너에 다른 시험이 남긴 같은 코드의 영상에 단언이 얹힌다.
        String visibleCode = "EVA" + (System.nanoTime() % 1_000_000L);
        String excludedCode = "EVX" + (System.nanoTime() % 1_000_000L);
        newVideo(visibleCode);
        newVideo(visibleCode);
        Long excluded = newVideo(excludedCode);
        excludeViaApi(excluded);

        TaskBoardSearchCondition visible =
                new TaskBoardSearchCondition("COMPLETED", null, tag, null, null, false);
        TaskBoardSearchCondition excludedOnly =
                new TaskBoardSearchCondition("COMPLETED", null, tag, null, null, true);

        assertThat(totalOf(visible)).as("표시분 갈래의 집계").isEqualTo(2L);
        assertThat(totalOf(excludedOnly))
                .as("★집계가 갈래를 따르지 않으면 카드 숫자가 목록과 어긋난다")
                .isEqualTo(1L);

        assertThat(optionsOf(visible)).contains(visibleCode).doesNotContain(excludedCode);
        assertThat(optionsOf(excludedOnly))
                .as("★옵션이 갈래를 따르지 않으면 고른 값이 0건이 되는 선택지가 생긴다")
                .contains(excludedCode)
                .doesNotContain(visibleCode);
    }

    /** 그 조건의 KPI 버킷 합 — 집계 창구가 {@code total} 로 내보내는 값과 같은 것. */
    private long totalOf(TaskBoardSearchCondition condition) {
        return taskBoardQueryRepository.countByWorkStatus(condition).values().stream()
                .mapToLong(Long::longValue).sum();
    }

    /** 그 조건의 이벤트유형 옵션 — 옵션 조회가 쓰는 조립 지점을 그대로 통과시킨다. */
    private List<String> optionsOf(TaskBoardSearchCondition condition) {
        return taskBoardQueryRepository.findDistinctEventTypes(condition, 500);
    }

    /** 옵션 응답은 코드 문자열 목록이다 — 그룹 접기를 거쳐도 값의 형태는 코드 그대로다. */
    private List<String> codesOf(JsonNode data) {
        List<String> codes = new ArrayList<>();
        data.path("items").forEach(n -> codes.add(n.asText()));
        return codes;
    }

    // ── 작업자 축 ─────────────────────────────────────────────────────────────

    /**
     * ★<b>마지막 방어</b> — 제외 거부가 뚫렸을 때도 배정 목록에 제외된 영상이 뜨지 않는다.
     *
     * <p>구동 테이블이 배정이라 상관 EXISTS 형태를 쓰며, <b>목록·count·이벤트유형 옵션 세 경로</b>가
     * 같은 조건 조립을 통과한다. 「죽은 조건」으로 보고 지우면 이 시험이 실패한다.
     */
    @Test
    @DisplayName("★작업자축_마지막방어_제외된_영상의_배정이_목록·건수·옵션에서_함께_빠진다")
    void workerAxisDropsAssignmentsOfExcludedVideos() throws Exception {
        Long kept = newVideo("EXCLA");
        Long excluded = newVideo("EXCLC");
        assign(kept, WORKER_NO);
        assign(excluded, WORKER_NO);

        JsonNode before = assignments();
        assertThat(before.path("totalElements").asLong())
                .as("기준값 자체가 성립해야 시험이 의미를 갖는다").isEqualTo(2L);
        assertThat(assignmentEventTypeOptions()).contains("EXCLA", "EXCLC");

        // 창구로는 재현할 수 없다 — 배정이 있는 영상은 제외가 거부되기 때문이다.
        forceExcluded(excluded);

        JsonNode after = assignments();
        assertThat(after.path("totalElements").asLong()).isEqualTo(1L);
        assertThat(videoIds(after, "videoId")).containsExactly(kept);
        assertThat(assignmentEventTypeOptions())
                .contains("EXCLA")
                .doesNotContain("EXCLC");
    }

    private List<String> assignmentEventTypeOptions() throws Exception {
        return codesOf(getJson("/v1/assignments/event-types?workerId=" + WORKER_NO));
    }

    /**
     * ★<b>상관 EXISTS 가 총건수를 증식시키지 않는다</b> — 배정 1건 = 결과 1행.
     *
     * <p>조인을 넣으면 행이 증식해 {@code totalElements} 가 실제 배정 수와 어긋난다. 목록 쿼리의
     * 총건수를 저장소의 실제 행 수와 직접 견주어 고정한다.
     */
    @Test
    @DisplayName("★작업자축_상관EXISTS가_총건수를_증식시키지_않는다_배정_1건이_결과_1행이다")
    void correlatedExistsDoesNotMultiplyRows() throws Exception {
        Long a = newVideo("EXCLA");
        Long b = newVideo("EXCLB");
        assign(a, WORKER_NO);
        assign(b, WORKER_NO);

        Long actualRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ls_task_altmnt t JOIN ls_data_raw r ON r.raw_sn = t.raw_data_id"
                        + " WHERE t.task_type_cd = 'LABELER' AND t.user_no = ? AND r.vms_cctv_id LIKE ?",
                Long.class, WORKER_NO, tag + "%");

        JsonNode page = assignments();
        assertThat(page.path("totalElements").asLong())
                .as("★총건수가 실제 배정 행 수와 같아야 한다 — 어긋나면 배제 술어가 행을 증식시킨 것이다")
                .isEqualTo(actualRows);
        assertThat(page.path("content").size())
                .as("한 페이지에 담기는 크기로 조회했으므로 내용 수와 총건수가 같아야 한다")
                .isEqualTo(actualRows.intValue());
    }

    /**
     * ★<b>영상 행이 없으면 통과시킨다</b> — 배제 술어의 확정 의사결정.
     *
     * <h3>왜 이렇게 검사하나</h3>
     * <p>이 규칙이 발동하는 상황(배정은 있는데 영상 행이 없다)은 <b>저장소에서 만들 수 없다</b> —
     * 배정·상태·이력이 전부 영상 원장을 참조하는 외래키를 갖고 그 외래키가 연쇄 삭제라 고아가 생기지
     * 않는다. 그래서 술어를 <b>식별자를 나르는 칸</b>에 얹어 그 성질만 직접 본다: 바깥 식별자가
     * 영상 원장에 <b>없으면</b> 참, <b>있고 제외된 영상이면</b> 거짓.
     *
     * <p>이 구분이 술어의 형태를 고정한다 — 「제외가 아닌 영상이 존재한다」({@code EXISTS(... <> 'Y')})로
     * 뒤집으면 영상 행이 없을 때 <b>거짓</b>이 되어 기존 동작이 조용히 좁아진다. 이 술어의 책임은
     * 「제외한 영상 배제」이고 영상 부재는 다른 축의 문제다.
     *
     * <h3>★운반체는 이 시험이 만든 영상에 딸린 프레임 행이다</h3>
     * <p>운반 칸으로 프레임 원장의 <b>영상 내 프레임 위치</b>를 쓴다 — 외래키가 없어 임의 식별자를 담을
     * 수 있고, 그 행이 <b>이 시험이 만든 영상의 자식</b>이라 남에게 닿지 않는다.
     *
     * <p>⚠ 구 방식(사용자 원장에 {@code raw_sn} 값을 {@code USER_NO} 로 넣고 지우기)은 <b>시한폭탄</b>
     * 이었다. 영상 식별자 시퀀스는 재사용 컨테이너에서 계속 자라는데 공용 시드 사용자 번호에 2001·9001
     * 같은 값이 있어, 언젠가 시퀀스가 그 값에 닿으면 <b>{@code @Sql} 미적재 시험이 의존하는 사용자 행을
     * 영구 삭제</b>한다. 지금은 초록이라 신호가 없고 터질 때는 무관한 시험이 무너져 원인을 여기서 찾지
     * 않는다. <b>공용 표에 남의 식별자 공간을 침범하는 행을 심지 말 것.</b>
     */
    @Test
    @DisplayName("★배제술어는_영상_행이_없으면_통과시키고_제외된_영상이면_배제한다")
    void predicatePassesWhenTheVideoRowIsAbsent() throws Exception {
        Long excluded = newVideo("EXCLC");
        excludeViaApi(excluded);

        Long missingRawSn = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(raw_sn), 0) + 1000000 FROM ls_data_raw", Long.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ls_data_raw WHERE raw_sn = ?", Long.class, missingRawSn))
                .as("기준값 자체가 성립해야 한다 — 그 식별자의 영상 행이 실제로 없어야 시험이 의미를 갖는다")
                .isZero();

        long absentCarrier = seedIdentifierCarrier(excluded, 1L, missingRawSn);
        long excludedCarrier = seedIdentifierCarrier(excluded, 2L, excluded);

        assertThat(predicateHolds(absentCarrier))
                .as("★영상 행이 없으면 통과시킨다 — 뒤집으면 기존 동작이 조용히 좁아진다")
                .isTrue();
        assertThat(predicateHolds(excludedCarrier))
                .as("제외된 영상을 가리키면 배제한다")
                .isFalse();
    }

    /**
     * 검사할 식별자를 <b>영상 내 프레임 위치</b> 칸에 담은 프레임 행을 이 시험의 영상 밑에 심는다.
     *
     * <p>그 칸은 외래키가 없어 임의 식별자를 담을 수 있고, 행 자체는 <b>{@code ownerRawSn} 의 자식</b>
     * 이라 남의 식별자 공간을 침범하지 않는다(영상이 사라지면 연쇄로 함께 정리된다). 프레임 추출 순번은
     * 영상 안에서만 유일하면 되므로 호출부가 구분해 넘긴다.
     *
     * @param ownerRawSn  이 시험이 만든 영상 — 운반체 행의 주인
     * @param frameNo     그 영상 안에서의 추출 순번 (유니크 제약이 (영상, 순번)이다)
     * @param identifier  술어가 판정할 대상 식별자
     * @return 심은 프레임의 PK — {@link #predicateHolds} 가 그 행 하나를 지목하는 데 쓴다
     */
    private long seedIdentifierCarrier(long ownerRawSn, long frameNo, long identifier) {
        Long srcSn = jdbcTemplate.queryForObject(
                "INSERT INTO ls_data_src (raw_sn, frm_no, vdo_frm_no, src_file_path_nm, reg_dt)"
                        + " VALUES (?, ?, ?, '/frames/probe.jpg', CURRENT_TIMESTAMP) RETURNING src_sn",
                Long.class, ownerRawSn, frameNo, identifier);
        return srcSn;
    }

    /** 그 운반체 행이 담은 식별자에 대해 배제 술어가 참인가. */
    private boolean predicateHolds(long carrierSrcSn) {
        QLsDataSrc frame = QLsDataSrc.lsDataSrc;
        Long matched = new JPAQueryFactory(entityManager)
                .select(frame.count())
                .from(frame)
                .where(frame.srcSn.eq(carrierSrcSn),
                        VideoExclusionScope.notExcludedByRawSn(frame.videoFrameNo))
                .fetchOne();
        return matched != null && matched == 1L;
    }
}
