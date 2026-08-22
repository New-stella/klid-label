package kr.co.cudo.authoring.assignment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.eventtype.service.EventTypeCacheEvictor;
import kr.co.cudo.authoring.support.IngestFlatValueSeeder;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 — 작업목록·배정목록의 이벤트유형 <b>표시명 그룹 접기 + 그룹 필터</b> 검증 (R6).
 *
 * <h3>배경</h3>
 * <p>관제가 유형별 이름({@code EVNT_NM})을 아직 보내지 않아 표시명이 카테고리명으로 폴백되면서,
 * 두 엔드포인트({@code GET /v1/tasks/board/event-types}, {@code GET /v1/assignments/event-types})의
 * 드롭다운에 <b>같은 이름이 여러 번</b>(침수 3·교통사고 3) 떴다. 이제 표시명이 같은 코드들을
 * 그룹 대표코드 1건으로 접는다.
 *
 * <h3>고정하는 계약</h3>
 * <ul>
 *   <li>두 엔드포인트 응답에 <b>같은 표시명으로 해석되는 코드가 둘 이상 들어 있지 않다</b>.</li>
 *   <li>미등록·비규격 코드는 <b>원문 그대로</b> 남아 필터로 도달 가능하다.</li>
 *   <li><b>대표코드로 필터하면 그룹 전체</b>의 작업/배정이 조회된다 — 옵션만 접고 필터가 단일 코드
 *       동등비교로 남으면 "옵션에서 골랐는데 일부가 사라지는" 조용한 누락이 된다.</li>
 *   <li><b>비대표 코드로 필터해도</b> 그룹 전체가 조회된다(그룹 도입 이전 북마크 하위호환).</li>
 *   <li>KPI 집계도 목록과 같은 집합을 센다(확장이 조건 정규화 단일 지점에 있다는 증거).</li>
 *   <li><b>인가 축은 그대로</b> — WORKER 는 그룹 필터를 걸어도 본인 배정분만 본다(CWE-639).</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class EventTypeGroupFilterIT {

    private static final long WORKER = 100L;
    private static final long OTHER_WORKER = 101L;

    /** 표시명이 같은 3종(그룹) — 대표코드는 그룹 내 최소 코드인 {@code EV91000101} 이다. */
    private static final String GROUP_REPRESENTATIVE = "EV91000101";
    private static final String GROUP_MEMBER_2 = "EV91000102";
    private static final String GROUP_MEMBER_3 = "EV91000103";
    /** 이름이 다른 독립 유형. */
    private static final String STANDALONE = "EV92000101";
    /** 마스터에 없는 비규격 코드 — 실제로 인입 중인 형태다. */
    private static final String UNREGISTERED = "INTRUSION";

    private static final List<String> SEEDED_TYPE_CODES =
            List.of(GROUP_REPRESENTATIVE, GROUP_MEMBER_2, GROUP_MEMBER_3, STANDALONE);

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;
    @Autowired private EventTypeCacheEvictor eventTypeCacheEvictor;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    EventTypeGroupFilterIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, String.valueOf(WORKER), "WORKER", "INTERNAL", issuer, 60);

        // 3종은 <같은 관제 수신명> 이라 표시명이 같다 → 한 그룹. 나머지 하나는 이름이 달라 독립 그룹.
        //   ★표시명은 <실제 마스터에 없는 이름>이어야 한다. 그룹 대표코드는 <그룹 내 최소 코드>라,
        //     여기서 '침수(범람)'·'화재' 처럼 시드된 유형의 표시명을 쓰면 그 유형들과 한 그룹이 되어
        //     대표가 EV01000101·EV02000101 로 넘어간다(이 파일의 EV91*/EV92* 가 대표를 잃는다).
        //     코드 대역만 갈라 두는 것으로는 부족하다 — 접기 축이 코드가 아니라 <표시명>이기 때문이다.
        seedEventType(GROUP_REPRESENTATIVE, "그룹표시명-시험용", "91");
        seedEventType(GROUP_MEMBER_2, "그룹표시명-시험용", "91");
        seedEventType(GROUP_MEMBER_3, "그룹표시명-시험용", "91");
        seedEventType(STANDALONE, "독립표시명-시험용", "92");
        // 장수명 캐시(eventType)를 비워 방금 심은 마스터가 즉시 반영되게 한다.
        eventTypeCacheEvictor.evictNow();
    }

    /**
     * 심어 둔 마스터를 되돌린다 — 이 캐시는 <b>컨텍스트 공유</b>라 남겨 두면 같은 컨텍스트를 쓰는
     * 다른 테스트가 "DB 에는 없는데 캐시에는 있는" 유형을 보게 된다.
     */
    @AfterEach
    void tearDown() {
        for (String code : SEEDED_TYPE_CODES) {
            jdbc.update("DELETE FROM LS_EVNT_TYPE WHERE EVNT_TYPE_CD = ?", code);
        }
        eventTypeCacheEvictor.evictNow();
    }

    // ---------------------------------------------------------------- fixtures

    private void seedEventType(String code, String name, String clsfCd) {
        jdbc.update("INSERT INTO LS_EVNT_TYPE (EVNT_TYPE_CD, EVNT_NM, EVNT_CLSF_CD, EVNT_CTGRY_CD, CLCT_YN) "
                        + "VALUES (?, ?, ?, ?, 'Y') "
                        + "ON CONFLICT (EVNT_TYPE_CD) DO UPDATE "
                        + "SET EVNT_NM = EXCLUDED.EVNT_NM, EVNT_CLSF_CD = EXCLUDED.EVNT_CLSF_CD, "
                        + "    EVNT_CTGRY_CD = EXCLUDED.EVNT_CTGRY_CD, CLCT_YN = 'Y'",
                code, name, clsfCd, clsfCd + "0001");
    }

    /** 배치 완료 영상 1건 + LABELER 배정 1건. */
    private LsDataRaw seedAssignedVideo(String key, String evntTypeCd, long workerNo) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-" + key, "CCTV-001", evntTypeCd, "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + key + ".mp4",
                LocalDateTime.of(2026, 5, 1, 9, 0, 0), 30);
        raw = videoRepository.save(raw);
        raw.changeStatus("COMPLETED");
        LsDataRaw saved = videoRepository.save(raw);
        IngestFlatValueSeeder.seedLegacyName(jdbc, saved.getRawSn(), "CCTV-001");
        authrtRepository.save(LsTaskAssignment.createLabeler(saved.getRawSn(), workerNo, 1L));
        return saved;
    }

    /**
     * 그룹 3종 + 독립 1종 + 비규격 1종을 배정과 함께 심는다.
     *
     * <p>그룹의 <b>세 번째 코드만 타인({@link #OTHER_WORKER}) 배정</b>이다 — WORKER 가 그룹 필터로
     * 타인 배정을 끌어오지 못한다는 인가 회귀를 같은 픽스처로 검증하기 위해서다.
     */
    private void seedAll() {
        seedAssignedVideo("G1", GROUP_REPRESENTATIVE, WORKER);
        seedAssignedVideo("G2", GROUP_MEMBER_2, WORKER);
        seedAssignedVideo("G3", GROUP_MEMBER_3, OTHER_WORKER);
        seedAssignedVideo("S1", STANDALONE, WORKER);
        seedAssignedVideo("U1", UNREGISTERED, WORKER);
    }

    // ----------------------------------------------------------------- requests

    private MockHttpServletRequestBuilder request(String path, String token, String... params) {
        var builder = get(path).header("Authorization", "Bearer " + token);
        for (int i = 0; i + 1 < params.length; i += 2) {
            builder = builder.param(params[i], params[i + 1]);
        }
        return builder;
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        byte[] body = mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        return objectMapper.readTree(new String(body, StandardCharsets.UTF_8)).path("data");
    }

    private List<String> options(String path, String token, String... params) throws Exception {
        List<String> out = new ArrayList<>();
        data(request(path, token, params)).path("items").forEach(n -> out.add(n.asText()));
        return out;
    }

    private long total(String path, String token, String... params) throws Exception {
        return data(request(path, token, params)).path("totalElements").asLong();
    }

    // ------------------------------------------------------------------ 옵션 접기

    @Test
    @DisplayName("작업목록_이벤트옵션에_같은_표시명_코드가_중복되지_않는다")
    void 작업목록_이벤트옵션에_같은_표시명_코드가_중복되지_않는다() throws Exception {
        seedAll();

        List<String> items = options("/v1/tasks/board/event-types", reviewerToken, "status", "COMPLETED");

        assertThat(items)
                .as("같은 표시명의 3종이 각각 옵션이 되면 드롭다운에 같은 이름이 3번 뜬다")
                .containsExactly(GROUP_REPRESENTATIVE, STANDALONE, UNREGISTERED);
        assertThat(items).doesNotContain(GROUP_MEMBER_2, GROUP_MEMBER_3);
    }

    @Test
    @DisplayName("배정목록_이벤트옵션에_같은_표시명_코드가_중복되지_않는다")
    void 배정목록_이벤트옵션에_같은_표시명_코드가_중복되지_않는다() throws Exception {
        seedAll();

        List<String> items = options("/v1/assignments/event-types", workerToken);

        // 두 경로는 같은 응답 계약을 쓰고 FE 도 같은 컴포넌트로 다룬다 — 접기 동작이 갈라지면 안 된다.
        assertThat(items).containsExactly(GROUP_REPRESENTATIVE, STANDALONE, UNREGISTERED);
        assertThat(items).doesNotContain(GROUP_MEMBER_2, GROUP_MEMBER_3);
    }

    @Test
    @DisplayName("미등록_비규격코드는_옵션에서_제거되지_않고_원문으로_남는다")
    void 미등록_비규격코드는_옵션에서_제거되지_않고_원문으로_남는다() throws Exception {
        seedAll();

        assertThat(options("/v1/tasks/board/event-types", reviewerToken, "status", "COMPLETED"))
                .as("접을 수 없다고 버리면 그 코드의 영상이 필터로 도달 불가능해진다")
                .contains(UNREGISTERED);
        assertThat(options("/v1/assignments/event-types", workerToken)).contains(UNREGISTERED);

        // 그 옵션으로 필터하면 실제로 조회된다(= UI 로 도달 가능하다).
        assertThat(total("/v1/tasks/board", reviewerToken,
                "status", "COMPLETED", "eventTypeCd", UNREGISTERED, "size", "1")).isEqualTo(1);
        assertThat(total("/v1/assignments", workerToken, "eventTypeCd", UNREGISTERED, "size", "1"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("옵션_개수가_상한_이하면_truncated는_false다")
    void 옵션_개수가_상한_이하면_truncated는_false다() throws Exception {
        seedAll();

        assertThat(data(request("/v1/tasks/board/event-types", reviewerToken, "status", "COMPLETED"))
                .path("truncated").asBoolean()).isFalse();
        assertThat(data(request("/v1/assignments/event-types", workerToken))
                .path("truncated").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------ 그룹 필터

    @Test
    @DisplayName("대표코드로_필터하면_그룹_전체_작업이_조회된다")
    void 대표코드로_필터하면_그룹_전체_작업이_조회된다() throws Exception {
        seedAll();

        assertThat(total("/v1/tasks/board", reviewerToken,
                "status", "COMPLETED", "eventTypeCd", GROUP_REPRESENTATIVE, "size", "20"))
                .as("옵션이 대표코드로 접혔는데 필터가 단일 코드 동등비교면 그룹의 나머지가 사라진다")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("비대표코드로_필터해도_그룹_전체_작업이_조회된다")
    void 비대표코드로_필터해도_그룹_전체_작업이_조회된다() throws Exception {
        seedAll();

        // 그룹 도입 이전에 만들어진 북마크(?eventTypeCd=EV91000103)가 0건/1건이 되면 하위호환 파손이다.
        assertThat(total("/v1/tasks/board", reviewerToken,
                "status", "COMPLETED", "eventTypeCd", GROUP_MEMBER_3, "size", "20")).isEqualTo(3);
        assertThat(total("/v1/tasks/board", reviewerToken,
                "status", "COMPLETED", "eventTypeCd", GROUP_MEMBER_2, "size", "20")).isEqualTo(3);
    }

    @Test
    @DisplayName("작업목록_KPI_집계도_그룹_전체를_센다")
    void 작업목록_KPI_집계도_그룹_전체를_센다() throws Exception {
        seedAll();

        JsonNode summary = data(request("/v1/tasks/board/summary", reviewerToken,
                "status", "COMPLETED", "eventTypeCd", GROUP_REPRESENTATIVE));

        assertThat(summary.path("total").asLong())
                .as("KPI 가 목록과 다른 집합을 세면 카드 숫자와 totalElements 가 갈라진다")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("독립_표시명_코드는_자기_1건만_조회된다")
    void 독립_표시명_코드는_자기_1건만_조회된다() throws Exception {
        seedAll();

        assertThat(total("/v1/tasks/board", reviewerToken,
                "status", "COMPLETED", "eventTypeCd", STANDALONE, "size", "20")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 인가 축

    @Test
    @DisplayName("WORKER는_그룹_필터로도_본인_배정분만_조회된다")
    void WORKER는_그룹_필터로도_본인_배정분만_조회된다() throws Exception {
        seedAll();

        // 그룹 3종 중 하나(G3)는 타인 배정이다 — 그룹 확장이 인가 축을 넘어서면 안 된다(CWE-639).
        assertThat(total("/v1/assignments", workerToken, "eventTypeCd", GROUP_REPRESENTATIVE, "size", "20"))
                .isEqualTo(2);
        assertThat(total("/v1/assignments", workerToken, "eventTypeCd", GROUP_MEMBER_3, "size", "20"))
                .as("비대표 코드로 필터해도 본인 배정분(2건)만 보여야 한다")
                .isEqualTo(2);
        // REVIEWER 는 전체를 본다(같은 필터, 다른 인가 범위).
        assertThat(total("/v1/assignments", reviewerToken, "eventTypeCd", GROUP_REPRESENTATIVE, "size", "20"))
                .isEqualTo(3);
    }
}
