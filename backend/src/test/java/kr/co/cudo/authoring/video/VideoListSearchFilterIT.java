package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.support.IngestFlatValueSeeder;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.common.config.CacheConfig;
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
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 영상 처리 현황({@code GET /v1/videos}) <b>검색·필터</b> 회귀 가드.
 *
 * <p>화면(SCR-VIDEO-001)은 예전부터 {@code cctvNameKeyword}/{@code eventTypeCd}/{@code from}/{@code to}
 * 를 전송했지만 컨트롤러 시그니처에 없어 <b>조용히 버려졌다</b>(상태 필터만 동작). 이 파일은 네 필터의
 * 실동작과 그 경계·보안 케이스를 고정한다.
 *
 * <h2>축(axis) 주의</h2>
 * <ul>
 *   <li><b>이벤트</b>: 축은 <b>유형</b>이라(V168) FE 가 보내는 값도 영상이 보유한 값도 EV-코드다
 *       (구 카테고리 키 {@code 010001} 방식 폐기). 다만 옵션은 <b>표시명 그룹</b>으로 접히므로
 *       (2026-08-05) 필터는 단일 코드 동등비교가 아니라 <b>그룹 전체</b>로 확장돼야 한다 —
 *       대표코드·비대표코드 어느 쪽으로 들어와도 같은 그룹을 매칭한다.</li>
 *   <li><b>기간</b>: 기준 컬럼은 {@code LS_DATA_RAW.SHT_DT}(촬영일시)다 — 정렬 allowlist
 *       ({@code capturedAt → shtDt})·표시 컬럼("녹화일")과 같은 축이어야 한다.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VideoListSearchFilterIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private CacheManager cacheManager;

    private final JdbcTemplate jdbc;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;

    private static final String EV_FLOOD_1 = "EV01000101";
    private static final String EV_FLOOD_2 = "EV01000102";
    private static final String EV_TRAFFIC_1 = "EV03000101";
    /** 이벤트유형 마스터에 등록되지 않은 코드 — 필터 키로 해석 불가(매칭 제외 대상). */
    private static final String EV_UNREGISTERED = "EV99999999";

    /**
     * 표시명 — <b>그룹 형성의 유일한 축</b>이다(2026-08-05). 침수 2종은 <b>같은 표시명</b>이라 한 그룹이고
     * 대표코드는 그룹 내 최소 코드인 {@link #EV_FLOOD_1} 이다.
     *
     * <p>이름에 IT 접미사를 붙이는 이유: 이 컨텍스트를 공유하는 다른 시드(dev-seed·stats-clean)가
     * 같은 카테고리(01,0001)의 <b>다른 코드</b>를 카테고리명 '침수(범람)' 으로 폴백시켜 두면, 그 코드까지
     * 우리 그룹에 빨려 들어와 이 클래스의 기대 건수가 시드 구성에 종속된다. 고유 이름으로 그룹 경계를
     * 이 클래스가 소유한다.
     */
    private static final String NM_FLOOD = "침수(범람)-필터IT";
    private static final String NM_TRAFFIC = "교통사고-필터IT";

    /**
     * 필터 파라미터로 보내는 값 — <b>축이 유형(V168)</b>이라 이벤트유형코드 자체다.
     * (구 값은 카테고리 키 "010001"/"030001" 이었다.)
     */
    private static final String FILTER_FLOOD = EV_FLOOD_1;
    private static final String FILTER_TRAFFIC = EV_TRAFFIC_1;

    VideoListSearchFilterIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        seedEventTypeMaster();
        evictEventTypeCache();
    }

    @AfterEach
    void tearDown() {
        for (String cd : new String[]{EV_FLOOD_1, EV_FLOOD_2, EV_TRAFFIC_1}) {
            jdbc.update("DELETE FROM LS_EVNT_TYPE WHERE EVNT_TYPE_CD = ?", cd);
        }
        evictEventTypeCache();
    }

    /**
     * 이벤트유형 마스터(LS_EVNT_TYPE) — 필터 키 판정의 유일한 원천. 축은 유형이다(V168).
     *
     * <p>침수 2종은 <b>같은 표시명</b>으로 심어 실제로 한 그룹이 되게 한다. 구 픽스처는 {@code EVNT_NM}
     * 자리에 <b>코드 자신</b>을 넣어 두 코드의 표시명이 서로 달랐고, 그래서 {@link #EV_FLOOD_2} 는
     * 선언·정리만 될 뿐 <b>그룹이 형성되지 않는 죽은 픽스처</b>였다(그룹 필터 회귀 가드 0건).
     */
    private void seedEventTypeMaster() {
        insertEventType(EV_FLOOD_1, NM_FLOOD, "01", "0001");
        insertEventType(EV_FLOOD_2, NM_FLOOD, "01", "0001");
        insertEventType(EV_TRAFFIC_1, NM_TRAFFIC, "03", "0001");
    }

    /**
     * 유형 1건 시드 — 표시명({@code EVNT_NM})과 카테고리코드({@code EVNT_CTGRY_CD})를 모두 실제로
     * 바인딩한다. 구 헬퍼는 {@code ctgryCd} 를 받고도 INSERT 에 쓰지 않아 카테고리가 항상 null 이었다.
     */
    private void insertEventType(String cd, String nm, String clsCd, String ctgryCd) {
        jdbc.update("DELETE FROM LS_EVNT_TYPE WHERE EVNT_TYPE_CD = ?", cd);
        jdbc.update("INSERT INTO LS_EVNT_TYPE "
                + "(EVNT_TYPE_CD, EVNT_NM, EVNT_CLSF_CD, EVNT_CTGRY_CD, CLCT_YN) "
                + "VALUES (?, ?, ?, ?, 'Y')", cd, nm, clsCd, ctgryCd);
    }

    /** {@code @Cacheable} 역인덱스는 컨텍스트 수명 동안 살아 있으므로 시드 전후로 비운다. */
    private void evictEventTypeCache() {
        Cache cache = cacheManager.getCache(CacheConfig.CACHE_EVENT_TYPE);
        if (cache != null) {
            cache.clear();
        }
    }

    // ---------------------------------------------------------------- fixtures

    private LsDataRaw seedVideo(String clipId, String cctvId, String evntTypeCd, LocalDateTime shtDt) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                clipId, cctvId, evntTypeCd, "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                shtDt, 30);
        raw = videoRepository.save(raw);
        raw.changeStatus(LsDataRaw.DATA_STTS_COMPLETED);
        LsDataRaw saved = videoRepository.save(raw);
        // CCTV 명은 관제 인입 평면값에서 온다(V167 — 구 test-data-video.sql 의 CCTV 마스터 시드 대체).
        //   조인 축이 VMS_CCTV_ID 가 아니라 영상(RAW_SN)이라 시드 SQL 로는 미리 넣을 수 없다.
        IngestFlatValueSeeder.seedLegacyName(jdbc, saved.getRawSn(), cctvId);
        return saved;
    }

    private LsDataRaw seedDerived(LsDataRaw parent) {
        LsDataRaw derived = LsDataRaw.createFromResolution(
                parent, "/var/raw/deriv-" + parent.getRawSn() + ".mp4", "RESL_480P");
        derived = videoRepository.save(derived);
        derived.changeStatus(LsDataRaw.DATA_STTS_COMPLETED);
        return videoRepository.save(derived);
    }

    /** CCTV-001 = '동대문구 회기로 CCTV' / CCTV-002 = '강남구 테헤란로 CCTV' (IngestFlatValueSeeder). */
    private LsDataRaw seedFlood() {
        return seedVideo("CLIP-SF-FLOOD", "CCTV-001", EV_FLOOD_1, LocalDateTime.of(2026, 5, 10, 0, 0, 0));
    }

    /** 침수 그룹의 <b>비대표</b> 코드 영상 — 대표코드로 필터할 때 함께 나와야 한다. */
    private LsDataRaw seedFloodGroupMember() {
        return seedVideo("CLIP-SF-FLOOD2", "CCTV-001", EV_FLOOD_2, LocalDateTime.of(2026, 5, 11, 12, 0, 0));
    }

    private LsDataRaw seedTraffic() {
        return seedVideo("CLIP-SF-TRAFFIC", "CCTV-002", EV_TRAFFIC_1, LocalDateTime.of(2026, 5, 12, 23, 59, 59));
    }

    private LsDataRaw seedUnregistered() {
        return seedVideo("CLIP-SF-UNREG", "CCTV-001", EV_UNREGISTERED, null);
    }

    // ---------------------------------------------------------------- R1 검색어

    @Test
    @DisplayName("검색어로_CCTV명_부분일치_검색된다")
    void keywordMatchesCctvNamePartially() throws Exception {
        // given
        seedFlood();               // CCTV-001 동대문구 회기로 CCTV
        LsDataRaw traffic = seedTraffic();  // CCTV-002 강남구 테헤란로 CCTV

        // when / then: '테헤란' 부분일치 → CCTV-002 영상만
        mockMvc.perform(get("/v1/videos").param("cctvNameKeyword", "테헤란")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(traffic.getRawSn()));
    }

    @Test
    @DisplayName("검색어가_숫자면_영상ID로도_매칭된다")
    void numericKeywordMatchesRawSn() throws Exception {
        // given: CCTV 명에는 숫자가 없으므로 rawSn 매칭만 성립한다
        LsDataRaw flood = seedFlood();
        seedTraffic();

        // when / then
        mockMvc.perform(get("/v1/videos").param("cctvNameKeyword", String.valueOf(flood.getRawSn()))
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(flood.getRawSn()));
    }

    @Test
    @DisplayName("LIKE_와일드카드_퍼센트는_전체매칭되지_않는다")
    void likeWildcardIsEscaped() throws Exception {
        // given
        seedFlood();
        seedTraffic();

        // when / then: '%' 는 리터럴로 취급되어 어떤 CCTV 명과도 매칭되지 않는다 (CWE-89 준하는 오검색 차단)
        mockMvc.perform(get("/v1/videos").param("cctvNameKeyword", "%")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("검색어_길이상한_초과는_400")
    void keywordTooLongRejected() throws Exception {
        // given: FE maxLength=100 과 정합 — 101자
        String tooLong = "가".repeat(101);

        // when / then
        mockMvc.perform(get("/v1/videos").param("cctvNameKeyword", tooLong)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- R2 이벤트 유형

    @Test
    @DisplayName("이벤트유형코드로_필터하면_해당_유형_영상만_반환")
    void eventTypeCategoryFilter() throws Exception {
        // given: 침수 그룹의 비대표 코드 영상은 심지 않는다 — 그룹 축은 아래 전용 테스트가 고정한다.
        LsDataRaw flood = seedFlood();
        seedTraffic();
        seedUnregistered();

        // when / then
        mockMvc.perform(get("/v1/videos").param("eventTypeCd", FILTER_FLOOD)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(flood.getRawSn()));
    }

    @Test
    @DisplayName("미등록_필터키는_500이_아니라_0건")
    void unknownCategoryKeyReturnsEmpty() throws Exception {
        // given
        seedFlood();
        seedTraffic();

        // when / then
        mockMvc.perform(get("/v1/videos").param("eventTypeCd", "999999")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("미등록_EV코드_영상은_이벤트필터에_잡히지_않는다")
    void unregisteredVideoCodeExcluded() throws Exception {
        // given
        seedUnregistered();

        // when / then: 어떤 유형으로 걸러도 미등록 코드는 매칭되지 않는다(fail-safe)
        mockMvc.perform(get("/v1/videos").param("eventTypeCd", FILTER_TRAFFIC)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    // ------------------------------------------------------- R2-b 표시명 그룹 (2026-08-05)

    @Test
    @DisplayName("대표코드로_필터하면_표시명_그룹_전체_영상이_조회된다")
    void groupRepresentativeFilterReturnsWholeGroup() throws Exception {
        // given: 표시명이 같은 침수 2종(대표 EV_FLOOD_1 / 비대표 EV_FLOOD_2) + 다른 그룹(교통사고)
        LsDataRaw flood = seedFlood();
        LsDataRaw floodMember = seedFloodGroupMember();
        LsDataRaw traffic = seedTraffic();

        // when / then: 드롭다운은 대표코드만 노출한다 — 필터가 단일 코드 동등비교로 남으면
        //   대표코드로 골랐을 때 그룹의 나머지 영상이 조용히 사라진다.
        mockMvc.perform(get("/v1/videos").param("eventTypeCd", FILTER_FLOOD).param("size", "50")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[?(@.id == " + flood.getRawSn() + ")]").isNotEmpty())
                .andExpect(jsonPath("$.data.content[?(@.id == " + floodMember.getRawSn() + ")]").isNotEmpty())
                // 음성: 다른 표시명 그룹(교통사고)은 섞여 나오지 않는다 — 그룹핑이 표시명을 무시하고
                //   전부 한 덩어리로 뭉치면 여기서 먼저 깨진다.
                .andExpect(jsonPath("$.data.content[?(@.id == " + traffic.getRawSn() + ")]").isEmpty());
    }

    @Test
    @DisplayName("비대표코드로_필터해도_그룹_전체_영상이_조회된다")
    void groupMemberFilterReturnsWholeGroup() throws Exception {
        // given
        LsDataRaw flood = seedFlood();
        LsDataRaw floodMember = seedFloodGroupMember();
        seedTraffic();

        // when / then: 그룹 도입 이전에 만들어진 북마크(?eventTypeCd=EV01000102)가 1건으로 줄면
        //   하위호환 파손이다 — codesForFilterKey 는 비대표 코드도 그룹 전체로 해석해야 한다.
        mockMvc.perform(get("/v1/videos").param("eventTypeCd", EV_FLOOD_2).param("size", "50")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[?(@.id == " + flood.getRawSn() + ")]").isNotEmpty())
                .andExpect(jsonPath("$.data.content[?(@.id == " + floodMember.getRawSn() + ")]").isNotEmpty());
    }

    @Test
    @DisplayName("다른_표시명_그룹으로_필터하면_그_그룹_영상만_조회된다")
    void otherGroupFilterIsNotContaminated() throws Exception {
        // given
        seedFlood();
        seedFloodGroupMember();
        LsDataRaw traffic = seedTraffic();

        // when / then: 교통사고 그룹은 자기 1건만 — 침수 2건이 섞여 나오면 그룹 경계가 무너진 것이다
        mockMvc.perform(get("/v1/videos").param("eventTypeCd", FILTER_TRAFFIC).param("size", "50")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(traffic.getRawSn()));
    }

    // ---------------------------------------------------------------- R3 기간

    @Test
    @DisplayName("기간필터는_SHT_DT_기준_경계포함")
    void periodFilterIsInclusiveOnShtDt() throws Exception {
        // given: from 당일 00:00:00 / to 당일 23:59:59 경계값 + 촬영일시 없는 영상
        LsDataRaw flood = seedFlood();        // 2026-05-10T00:00:00
        LsDataRaw traffic = seedTraffic();    // 2026-05-12T23:59:59
        seedUnregistered();                   // shtDt = null

        // when / then
        mockMvc.perform(get("/v1/videos").param("from", "2026-05-10").param("to", "2026-05-12")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[?(@.id == " + flood.getRawSn() + ")]").isNotEmpty())
                .andExpect(jsonPath("$.data.content[?(@.id == " + traffic.getRawSn() + ")]").isNotEmpty());
    }

    @Test
    @DisplayName("기간_시작일이_종료일보다_늦으면_400")
    void reversedPeriodRejected() throws Exception {
        mockMvc.perform(get("/v1/videos").param("from", "2026-05-12").param("to", "2026-05-10")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("잘못된_날짜_형식은_400")
    void malformedDateRejected() throws Exception {
        mockMvc.perform(get("/v1/videos").param("from", "2026/05/12")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- 조합 · 파생 · 표시축

    @Test
    @DisplayName("네_필터_조합시_정확하고_totalElements도_필터_적용후_건수")
    void combinedFilters() throws Exception {
        // given
        LsDataRaw flood = seedFlood();
        seedTraffic();
        seedUnregistered();

        // when / then: 검색어 + 이벤트 + 기간 + 상태 전부 만족하는 1건
        mockMvc.perform(get("/v1/videos")
                        .param("cctvNameKeyword", "회기로")
                        .param("eventTypeCd", FILTER_FLOOD)
                        .param("from", "2026-05-01")
                        .param("to", "2026-05-31")
                        .param("dataSttsCd", LsDataRaw.DATA_STTS_COMPLETED)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(flood.getRawSn()));
    }

    @Test
    @DisplayName("파생영상은_어떤_필터_조합에서도_목록에_노출되지_않는다")
    void derivativeNeverExposed() throws Exception {
        // given: 원본 + 그 원본에서 만든 해상도 파생(ORGNL_RAW_SN NOT NULL) — CCTV/이벤트/촬영일시를 계승
        LsDataRaw flood = seedFlood();
        LsDataRaw derived = seedDerived(flood);

        // when / then: 필터 없음
        mockMvc.perform(get("/v1/videos")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.id == " + derived.getRawSn() + ")]").isEmpty());

        // when / then: 검색어 + 이벤트 + 기간 조합
        mockMvc.perform(get("/v1/videos")
                        .param("cctvNameKeyword", "회기로")
                        .param("eventTypeCd", FILTER_FLOOD)
                        .param("from", "2026-05-01")
                        .param("to", "2026-05-31")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(flood.getRawSn()));
    }

    @Test
    @DisplayName("capturedAt은_SHT_DT를_반환하고_촬영일시가_없으면_null")
    void capturedAtIsShtDtWithoutFallback() throws Exception {
        // given
        LsDataRaw flood = seedFlood();          // shtDt = 2026-05-10T00:00:00
        LsDataRaw unreg = seedUnregistered();   // shtDt = null (regDt 는 존재)

        // when
        String body = mockMvc.perform(get("/v1/videos").param("size", "50")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        com.fasterxml.jackson.databind.JsonNode content =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("data").path("content");

        // then
        org.assertj.core.api.Assertions.assertThat(nodeOf(content, flood.getRawSn()).path("capturedAt").asText())
                .startsWith("2026-05-10T00:00:00");
        // 촬영일시 미상은 regDt 로 몰래 채우지 않는다(표시·정렬·필터 축 일치)
        org.assertj.core.api.Assertions.assertThat(nodeOf(content, unreg.getRawSn()).path("capturedAt").isNull())
                .as("shtDt 가 없으면 capturedAt 도 null 이어야 한다 (regDt 폴백 금지)")
                .isTrue();
    }

    private static com.fasterxml.jackson.databind.JsonNode nodeOf(
            com.fasterxml.jackson.databind.JsonNode content, Long rawSn) {
        for (com.fasterxml.jackson.databind.JsonNode n : content) {
            if (n.path("id").asLong() == rawSn) {
                return n;
            }
        }
        throw new AssertionError("rawSn=" + rawSn + " 이 목록에 없습니다: " + content);
    }
}
