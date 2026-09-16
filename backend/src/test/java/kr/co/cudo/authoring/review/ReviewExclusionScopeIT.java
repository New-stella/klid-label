package kr.co.cudo.authoring.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
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

import javax.sql.DataSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ★<b>검수 목록의 제외분 배제</b> — 목록·전체 건수·KPI 집계가 함께 줄고, 「제외됨 N건」은 집계 창구만
 * 싣는다. [@design ADR-069] [@design API-008] [@design API-138] [@design AC-1124] [@design AC-1121]
 *
 * <h2>이 축에 제외분이 실제로 도달한다</h2>
 * <p>방어적 조건이 아니다. 배정이 있으면 제외가 거부되고 검수 목록에 뜨려면 제출을 거쳐야 해 대개
 * 배정이 있지만, <b>반려된 배정은 해제할 수 있고</b>(해제를 거부하는 것은 검수 대기·검수 중·승인
 * 셋이다) 그 뒤 제외가 가능하다 — 반려 상태를 통해 실제로 걸리는 조건이다.
 *
 * <h2>구동 테이블이 영상 원장이 아니다</h2>
 * <p>이 목록은 검수 워크플로 상태({@code LS_RAW_DATA_STATUS})에서 출발하므로 배제 술어가 <b>상관
 * EXISTS</b> 형태여야 한다. 영상 원장을 조인으로 끌어오면 행이 증식해 {@code totalElements} 가 실제
 * 건수와 어긋난다 — 그 성질을 총건수 단언으로 함께 고정한다.
 *
 * <h2>검사 범위를 우리 영상으로 좁히고, 그 좁힘을 픽스처가 스스로 만든다</h2>
 * <p>공유 컨테이너라 다른 클래스가 남긴 행이 섞이면 건수 단언이 헛돈다. 모든 조회에 이 클래스가 만든
 * 영상만 걸리는 <b>유일 검색어</b>를 싣는다. 나아가 「제외됨 N건」이 <b>필터 범위 안</b>의 값인지는
 * <b>범위 밖에 제외 영상이 실재할 때만</b> 갈리므로, 그 한 건을 픽스처가 직접 심는다 — 잔여 데이터에
 * 기대면 이 스위트만 깨끗한 저장소에서 돌 때 두 해석이 같은 값을 내어 단언이 무력해진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = "/db/test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ReviewExclusionScopeIT {

    /** 공용 시드의 검수자(표시 이름 「검수자1」). */
    private static final long REVIEWER_NO = 1L;

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsRawDataStatusRepository dataSttsRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;

    private final JdbcTemplate jdbcTemplate;

    ReviewExclusionScopeIT(@Qualifier("controlDataSource") DataSource dataSource) {
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
        tag = "revexcl" + System.nanoTime();
    }

    // 정리 훅을 두지 않는다 — 이 시험이 만드는 행은 전부 자기 영상의 자식이라 공용 표에 남는 것이 없다.

    // ── 픽스처 ────────────────────────────────────────────────────────────────

    /**
     * 검수 워크플로 상태를 가진 영상 하나.
     *
     * <p>CCTV 명(관제 인입)이 없는 영상이라 표시명이 CCTV 식별자로 폴백하고, 검색어는 그 값에 걸린다 —
     * 그래서 식별자에 이 시험의 유일 검색어를 심는다.
     */
    private Long newVideo(String sttsCd) {
        return newVideo(sttsCd, tag);
    }

    private Long newVideo(String sttsCd, String cctvTag) {
        Long rawSn = videoRepository.save(LsDataRaw.createFromIngest(
                "CLIP-" + cctvTag + "-" + System.nanoTime(), cctvTag + System.nanoTime(),
                "EVT-A", "11680", LsDataRaw.PRVC_TYPE_ANONY,
                "/var/raw/clip.mp4", LocalDateTime.now(), 30)).getRawSn();
        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        stts.transitionTo(sttsCd);
        dataSttsRepository.saveAndFlush(stts);
        return rawSn;
    }

    /**
     * 이 시험의 검색어에 <b>걸리지 않는</b> 영상 — 「필터 범위 밖」을 만드는 데만 쓴다.
     *
     * <p>검수 워크플로 상태는 같게 두어 <b>검색어 축 하나만</b> 다르게 한다. 그래야 이 한 건이 무엇을
     * 가르는지가 분명해진다.
     */
    private Long newVideoOutsideFilter(String sttsCd) {
        return newVideo(sttsCd, "outsidefilter" + System.nanoTime());
    }

    /** 승인 게이트(라벨 0건 차단)를 지나가게 하는 최소 라벨 한 건. */
    private void seedFrameWithLabel(Long rawSn) {
        LsDataSrc frame = srcRepository.save(
                LsDataSrc.create(rawSn, 0, "/var/raw/f0.jpg", LocalDateTime.now()));
        labelRepository.save(LsDataLbl.createManual(frame.getSrcSn(), "BBOX", null,
                "person", "[[1.0,1.0],[2.0,2.0]]", "100"));
    }

    private void excludeViaApi(Long rawSn) throws Exception {
        mockMvc.perform(post("/v1/videos/" + rawSn + "/exclusion")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"시험 데이터\"}"))
                .andExpect(status().isOk());
    }

    private void restoreViaApi(Long rawSn) throws Exception {
        mockMvc.perform(delete("/v1/videos/" + rawSn + "/exclusion")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
    }

    private JsonNode getJson(String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private JsonNode reviews() throws Exception {
        return getJson("/v1/reviews?q=" + tag + "&size=100");
    }

    private JsonNode excludedOnlyReviews() throws Exception {
        return getJson("/v1/reviews?q=" + tag + "&size=100&excludedOnly=true");
    }

    private JsonNode summary() throws Exception {
        return getJson("/v1/reviews/summary?q=" + tag);
    }

    private List<Long> videoIds(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("content").forEach(n -> ids.add(n.path("videoId").asLong()));
        return ids;
    }

    // ── 목록·건수 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★제외된_영상이_검수목록과_총건수에서_함께_빠진다")
    void excludedVideosDropFromListAndTotal() throws Exception {
        Long kept1 = newVideo(LsRawDataStatus.STTS_PENDING);
        Long kept2 = newVideo(LsRawDataStatus.STTS_IN_REVIEW);
        Long excluded = newVideo(LsRawDataStatus.STTS_REJECTED);

        JsonNode before = reviews();
        assertThat(before.path("totalElements").asLong())
                .as("기준값 자체가 성립해야 시험이 의미를 갖는다").isEqualTo(3L);

        excludeViaApi(excluded);

        JsonNode after = reviews();
        assertThat(after.path("totalElements").asLong())
                .as("★목록과 건수가 같은 조건을 공유해야 한다 — 한쪽에만 붙으면 건수와 내용이 어긋난다")
                .isEqualTo(2L);
        assertThat(videoIds(after)).containsExactlyInAnyOrder(kept1, kept2);
        assertThat(after.path("content").size())
                .as("★상관 EXISTS 가 행을 증식시키지 않는다 — 조인으로 끌어오면 여기서 드러난다")
                .isEqualTo(2);
    }

    /**
     * ★★<b>버킷 합 = 전체 건수 불변식이 제외 후에도 유지된다.</b>
     *
     * <p>제외 판정을 <b>버킷 판정식</b>에 섞으면 어느 버킷에도 들지 않는 행이 생겨 이 불변식이 깨진다
     * (그 위반은 서버 예외로 드러난다). 조회 조건에만 붙였음을 결과로 고정한다.
     *
     * <p>「제외됨 N건」은 <b>집계 창구가 소유</b>한다 — 목록 응답에는 그 키를 두지 않는다. 같은 숫자를
     * 두 창구가 각각 계산하면 한쪽만 조건이 바뀌어도 드러나지 않기 때문이다.
     */
    @Test
    @DisplayName("★★KPI집계도_같은_조건으로_줄고_버킷합_불변식이_유지되며_제외됨건수는_집계창구만_싣는다")
    void summaryDropsExcludedKeepsBucketInvariantAndOwnsExcludedCount() throws Exception {
        newVideo(LsRawDataStatus.STTS_PENDING);
        newVideo(LsRawDataStatus.STTS_APPROVED);
        Long excluded = newVideo(LsRawDataStatus.STTS_REJECTED);
        // ★필터 <밖>의 제외 영상 — 검색어가 다르므로 어느 응답에도 잡히면 안 된다.
        //   이 한 건이 「필터 범위 안에서 센다」와 「전체를 센다」를 가르는 유일한 장치다.
        excludeViaApi(newVideoOutsideFilter(LsRawDataStatus.STTS_PENDING));

        JsonNode before = summary();
        assertThat(before.path("total").asLong()).isEqualTo(3L);
        assertBucketInvariant(before);
        assertThat(before.path("excludedCount").isMissingNode())
                .as("0건이어도 실려야 한다 — 빠지면 「제외된 것이 없다」와 「제외 기능이 없다」가 구분되지 않는다")
                .isFalse();
        assertThat(before.path("excludedCount").asLong())
                .as("★필터 밖에 제외 영상이 1건 실재하는데도 0이어야 한다 — 필터를 무시하고 세면 여기서 드러난다")
                .isZero();

        excludeViaApi(excluded);

        JsonNode after = summary();
        assertThat(after.path("total").asLong())
                .as("집계가 목록과 같은 조건을 공유해야 한다 — 갈리면 목록은 줄었는데 카드 숫자는 그대로다")
                .isEqualTo(2L);
        assertThat(after.path("rejected").asLong())
                .as("제외된 반려 건이 그 버킷에서도 빠져야 한다").isZero();
        assertBucketInvariant(after);
        assertThat(after.path("excludedCount").asLong())
                .as("★같은 필터 범위 안의 제외 건수여야 한다 — 필터 밖 1건까지 세면 2가 되어 여기서 드러난다")
                .isEqualTo(1L);

        assertThat(reviews().path("excludedCount").isMissingNode())
                .as("★「제외됨 N건」의 진실원은 집계 창구 하나다 — 목록 응답에 그 키를 두지 않는다")
                .isTrue();
    }

    private void assertBucketInvariant(JsonNode summary) {
        long sum = summary.path("pending").asLong()
                + summary.path("inReview").asLong()
                + summary.path("approved").asLong()
                + summary.path("rejected").asLong();
        assertThat(sum)
                .as("★버킷 합 = 전체 건수 — 제외 판정을 버킷 판정식에 섞으면 이 불변식이 깨진다")
                .isEqualTo(summary.path("total").asLong());
    }

    /**
     * 화면은 「제외됨 N건」을 눌러 제외분 목록으로 전환한다 — 그 숫자와 전환 결과가 어긋나면 안 된다.
     */
    @Test
    @DisplayName("★제외분만_보기로_전환하면_제외분만_나오고_그_총건수가_제외됨건수와_같다")
    void excludedOnlyReturnsExactlyTheExcludedCount() throws Exception {
        Long kept = newVideo(LsRawDataStatus.STTS_PENDING);
        Long excludedA = newVideo(LsRawDataStatus.STTS_PENDING);
        Long excludedB = newVideo(LsRawDataStatus.STTS_APPROVED);
        excludeViaApi(newVideoOutsideFilter(LsRawDataStatus.STTS_PENDING));
        excludeViaApi(excludedA);
        excludeViaApi(excludedB);

        JsonNode page = excludedOnlyReviews();
        assertThat(videoIds(page))
                .as("제외분만 남는다 — 표시분과 섞어 보는 갈래는 없다")
                .containsExactlyInAnyOrder(excludedA, excludedB);
        assertThat(videoIds(page)).doesNotContain(kept);

        assertThat(page.path("totalElements").asLong())
                .as("★그 숫자를 눌러 얻는 목록의 총건수와 「제외됨 N건」이 같아야 한다")
                .isEqualTo(summary().path("excludedCount").asLong());

        assertThat(videoIds(reviews()))
                .as("보내지 않던 호출의 결과는 달라지지 않는다 — 기본은 제외분을 뺀 목록이다")
                .containsExactly(kept);
    }

    @Test
    @DisplayName("★복원하면_검수목록과_집계에_다시_나타난다")
    void restoringBringsTheVideoBack() throws Exception {
        Long kept = newVideo(LsRawDataStatus.STTS_PENDING);
        Long target = newVideo(LsRawDataStatus.STTS_REJECTED);

        excludeViaApi(target);
        assertThat(videoIds(reviews())).containsExactly(kept);

        restoreViaApi(target);

        JsonNode after = reviews();
        assertThat(videoIds(after)).containsExactlyInAnyOrder(kept, target);
        assertThat(after.path("totalElements").asLong()).isEqualTo(2L);

        JsonNode summary = summary();
        assertThat(summary.path("total").asLong()).isEqualTo(2L);
        assertThat(summary.path("rejected").asLong()).isEqualTo(1L);
        assertThat(summary.path("excludedCount").asLong())
                .as("복원하면 「제외됨 N건」도 함께 줄어든다").isZero();
        assertBucketInvariant(summary);
    }

    // ── 경계 ─────────────────────────────────────────────────────────────────

    /**
     * ★★<b>경계 — 제외는 목록 조회의 시야일 뿐 검수 자체를 막지 않는다.</b>
     * [@design AC-1121]
     *
     * <h3>왜 「기준값 대조」인가</h3>
     * <p>「승인이 된다」만 보면 승인 <b>이후의 연쇄</b>가 제외 때문에 조용히 끊겨도 통과한다. 그래서
     * 같은 조건의 <b>대조군(제외되지 않은 영상)</b>을 나란히 승인해, 승인 트랜잭션이 확정하는 산출물
     * 축의 관측값이 <b>둘 다 같은지</b>를 본다.
     *
     * <h3>무엇을 관측하나</h3>
     * <ul>
     *   <li><b>승인 결과</b> — 두 영상 모두 200 이고 상태가 승인으로 확정된다.</li>
     *   <li><b>라벨 버전 스냅샷</b> — 승인과 같은 트랜잭션에서 만들어지는 학습데이터 버전이 둘 다
     *       생긴다(제외가 이 연쇄를 끊으면 여기서 드러난다).</li>
     *   <li><b>데이터마트 조회 뷰</b> — 관제가 SELECT 하는 계약면에 두 영상이 <b>똑같이</b> 나타난다.
     *       이 항목의 근거는 검수 완료·통지 건에 대한 관제 접근을 무조건 보장한다는 ADR-037 이며,
     *       여기서 제외된 쪽 행이 사라지면 그 결정이 깨진 것이다.</li>
     * </ul>
     *
     * <p>그러면서도 <b>목록에서는 여전히 빠져 있다</b> — 승인이 제외를 해제하지 않는다.
     */
    @Test
    @DisplayName("★★경계_제외해도_검수_승인이_되고_승인_결과가_제외되지_않은_대조군과_같다")
    void approvalIsUnaffectedByExclusion() throws Exception {
        Long control = newVideo(LsRawDataStatus.STTS_PENDING);
        Long excluded = newVideo(LsRawDataStatus.STTS_PENDING);
        seedFrameWithLabel(control);
        seedFrameWithLabel(excluded);
        excludeViaApi(excluded);

        approve(control);
        approve(excluded);

        assertThat(sttsOf(control)).isEqualTo(LsRawDataStatus.STTS_APPROVED);
        assertThat(sttsOf(excluded))
                .as("★제외는 화면 시야만 바꾼다 — 승인이 제외를 이유로 막히면 안 된다")
                .isEqualTo(sttsOf(control));

        assertThat(labelVersionCount(excluded))
                .as("★승인과 같은 트랜잭션에서 만들어지는 학습데이터 버전이 대조군과 같아야 한다 —"
                        + " 제외가 승인 연쇄를 끊으면 여기서 드러난다")
                .isEqualTo(labelVersionCount(control))
                .isPositive();

        assertThat(datamartRows(excluded))
                .as("★데이터마트 조회 뷰에서 사라지면 ADR-037(검수 완료·통지 건의 관제 접근 무조건 보장)이"
                        + " 깨진다 — 제외는 그 계약면에 닿지 않는다")
                .isEqualTo(datamartRows(control))
                .isEqualTo(1L);

        assertThat(videoIds(reviews()))
                .as("그러면서도 목록에서는 여전히 빠져 있다 — 승인이 제외를 해제하지 않는다")
                .containsExactly(control);
    }

    private void approve(Long rawSn) throws Exception {
        mockMvc.perform(post("/v1/reviews/" + rawSn + "/start")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/reviews/" + rawSn + "/approve")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
    }

    private String sttsOf(Long rawSn) {
        return jdbcTemplate.queryForObject(
                "SELECT data_stts_cd FROM ls_raw_data_status WHERE raw_data_id = ?", String.class, rawSn);
    }

    /** 승인이 확정하는 학습데이터 버전 스냅샷 건수 — 승인 연쇄의 첫 고리다. */
    private long labelVersionCount(Long rawSn) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ls_label_version WHERE data_raw_sn = ?", Long.class, rawSn);
        return count != null ? count : 0L;
    }

    /** 관제가 SELECT 하는 계약면 — 그 영상에 대해 뷰가 돌려주는 행 수. */
    private long datamartRows(Long rawSn) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM v_completed_video WHERE raw_sn = ?", Long.class, rawSn);
        return count != null ? count : 0L;
    }
}
