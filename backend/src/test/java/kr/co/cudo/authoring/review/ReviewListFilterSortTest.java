package kr.co.cudo.authoring.review;

import kr.co.cudo.authoring.support.IngestFlatValueSeeder;
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
import org.springframework.test.web.servlet.RequestBuilder;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 — 검수목록({@code GET /v1/reviews}) 정렬 결함 수정 + 검색어 서버 필터 검증.
 *
 * <p>검증 축:
 * <ul>
 *   <li><b>R8 하위호환(HIGH-3)</b> — {@code sort} 미지정 호출의 결과·순서가 변경 전과 동일
 *       ({@code UPD_DT DESC} 전체)</li>
 *   <li><b>정렬 동작</b> — 제출일(submittedAt) 오름/내림 토글이 실제로 반영</li>
 *   <li><b>정렬 allowlist(관용 모드)</b> — 미등록 키·개수 상한 초과는 <b>400 이 아니라</b> 기본 정렬
 *       폴백(변경 전 이 API 는 {@code sort} 를 받지도 않아 항상 200 이었다 — R8/AC-8).
 *       작업목록({@code /v1/tasks/board})의 400 은 그대로라는 정책 차이도 함께 고정한다.</li>
 *   <li><b>표시 ↔ 검색 정합</b> — 작업자 tie-break(동일 REG_DT)와 CCTV 표시명 폴백(공백문자만 있는
 *       {@code CCTV_NM})이 표시측/검색측에서 같은 행·같은 값을 가리킨다</li>
 *   <li><b>HIGH-4</b> — 동일 {@code updDt} 다수 행에서도 페이지 경계 중복·누락 0 (PK tie-break)</li>
 *   <li><b>HIGH-1</b> — {@code q} 가 DB 단계에서 적용되어 {@code totalElements} 가 정확</li>
 *   <li><b>HIGH-2</b> — 검수 워크플로 화이트리스트가 {@code q} 경로에서도 상시 적용</li>
 *   <li><b>HIGH-7</b> — LIKE 와일드카드 이스케이프 + 파라미터 바인딩(CWE-89)</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ReviewListFilterSortTest {

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

    ReviewListFilterSortTest(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
    }

    // ---------------------------------------------------------------- fixtures

    private LsDataRaw seedVideo(String clipId, String cctvId) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                clipId, cctvId, "EVT-FIRE", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                LocalDateTime.of(2026, 5, 1, 9, 0, 0), 30);
        LsDataRaw saved = videoRepository.save(raw);
        seedIngestName(saved.getRawSn(), cctvId);
        return saved;
    }

    /** 검수 워크플로 상태 row 시드 + UPD_DT 고정(정렬 검증을 결정적으로 만들기 위해 DB 직접 갱신). */
    private void seedStatus(Long rawSn, String status, LocalDateTime updDt) {
        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        stts.transitionTo(status);
        dataSttsRepository.save(stts);
        jdbc.update("UPDATE LS_RAW_DATA_STATUS SET UPD_DT = ? WHERE RAW_DATA_ID = ?",
                Timestamp.valueOf(updDt), rawSn);
    }

    private Long seedReviewVideo(String clipId, String cctvId, String status, LocalDateTime updDt) {
        Long rawSn = seedVideo(clipId, cctvId).getRawSn();
        seedStatus(rawSn, status, updDt);
        return rawSn;
    }

    private LsTaskAssignment assignLabeler(Long rawSn, Long workerNo) {
        return authrtRepository.save(LsTaskAssignment.createLabeler(rawSn, workerNo, 1L));
    }

    /** {@link #seedCctv} 예약분 — CCTV ID → 표시명. 실제 적재는 영상 생성 시점에 한다. */
    private final java.util.Map<String, String> cctvNamesByCctvId = new java.util.HashMap<>();

    /**
     * 표시 영상명(cctvName) 소스 등록 — <b>관제 인입 평면값</b>({@code LS_DATA_INGEST.CCTV_NM}).
     *
     * <p>V167 로 구 CCTV 마스터({@code MNG_RESOURCE_CCTV})가 제거되면서 이름의 조달 축이
     * <b>CCTV(VMS_CCTV_ID) → 영상(RAW_SN)</b> 으로 바뀌었다. 그런데 인입 행은 영상 PK 를 알아야
     * 만들 수 있으므로, 이 메서드는 <b>이름을 예약</b>만 하고 실제 INSERT 는 영상을 만드는
     * {@code seedVideo} 가 수행한다(호출 순서를 기존 그대로 유지하기 위한 장치다).
     */
    private void seedCctv(String vmsCctvId, String cctvNm) {
        cctvNamesByCctvId.put(vmsCctvId, cctvNm);
    }

    /**
     * {@link #seedCctv} 로 예약된 이름을 영상(RAW_SN) 축의 인입 행으로 실제 적재한다.
     * 예약이 없으면 구 {@code test-data-video.sql} 의 잘 알려진 이름(CCTV-001/002)으로 폴백한다.
     */
    private void seedIngestName(Long rawSn, String cctvId) {
        String reserved = cctvNamesByCctvId.get(cctvId);
        if (reserved != null) {
            IngestFlatValueSeeder.seedName(jdbc, rawSn, cctvId, reserved);
        } else {
            IngestFlatValueSeeder.seedLegacyName(jdbc, rawSn, cctvId);
        }
    }


    // ---------------------------------------------------------------- helpers

    private RequestBuilder reviews(String... params) {
        var builder = get("/v1/reviews").header("Authorization", "Bearer " + reviewerToken);
        for (int i = 0; i + 1 < params.length; i += 2) {
            builder = builder.param(params[i], params[i + 1]);
        }
        return builder;
    }

    private JsonNode dataOf(RequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        // 표시 문자열(작업자명·CCTV 명)을 단언하므로 charset 을 명시한다 — 기본값(ISO-8859-1)으로 읽으면
        // 한글이 깨져 값 비교가 무의미해진다.
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private List<Long> videoIds(RequestBuilder request) throws Exception {
        JsonNode data = dataOf(request);
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : data.path("content")) {
            ids.add(item.path("videoId").asLong());
        }
        return ids;
    }

    private long totalElements(RequestBuilder request) throws Exception {
        return dataOf(request).path("totalElements").asLong();
    }

    /** 응답 목록에서 특정 영상 1건의 항목 노드를 꺼낸다 (표시값 단언용). */
    private JsonNode itemOf(RequestBuilder request, Long videoId) throws Exception {
        for (JsonNode item : dataOf(request).path("content")) {
            if (item.path("videoId").asLong() == videoId) {
                return item;
            }
        }
        throw new AssertionError("영상 " + videoId + " 이 목록에 없습니다");
    }

    // ------------------------------------------------------------------ R8/정렬

    @Test
    @DisplayName("sort_미지정시_변경전과_동일한_updDt_내림차순_반환")
    void defaultSortIsUpdDtDesc() throws Exception {
        // given — 서로 다른 updDt 3건 (t1 < t2 < t3)
        LocalDateTime t1 = LocalDateTime.of(2026, 5, 1, 10, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 5, 1, 11, 0, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 5, 1, 12, 0, 0);
        Long oldest = seedReviewVideo("CLIP-R8-1", "CCTV-001", LsRawDataStatus.STTS_PENDING, t1);
        Long newest = seedReviewVideo("CLIP-R8-2", "CCTV-001", LsRawDataStatus.STTS_IN_REVIEW, t3);
        Long middle = seedReviewVideo("CLIP-R8-3", "CCTV-001", LsRawDataStatus.STTS_APPROVED, t2);

        // when — 기존 계약 그대로(page/size 만)
        List<Long> ids = videoIds(reviews("page", "0", "size", "20"));

        // then — updDt DESC
        assertThat(ids).containsExactly(newest, middle, oldest);
        assertThat(totalElements(reviews("page", "0", "size", "20"))).isEqualTo(3);

        // 파라미터를 아예 보내지 않는 호출, 정렬을 해제하며 빈 sort 를 보내는 호출 모두 동일해야 한다
        // (빈 값이 400 이 되면 FE 가 정렬 해제를 못 한다).
        assertThat(videoIds(reviews())).containsExactly(newest, middle, oldest);
        assertThat(videoIds(reviews("size", "20", "sort", ""))).containsExactly(newest, middle, oldest);
    }

    @Test
    @DisplayName("sort_submittedAt_asc_요청시_제출일_오름차순으로_반환")
    void sortSubmittedAtAsc() throws Exception {
        LocalDateTime t1 = LocalDateTime.of(2026, 5, 1, 10, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 5, 1, 11, 0, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 5, 1, 12, 0, 0);
        Long oldest = seedReviewVideo("CLIP-SA-1", "CCTV-001", LsRawDataStatus.STTS_PENDING, t1);
        Long newest = seedReviewVideo("CLIP-SA-2", "CCTV-001", LsRawDataStatus.STTS_PENDING, t3);
        Long middle = seedReviewVideo("CLIP-SA-3", "CCTV-001", LsRawDataStatus.STTS_PENDING, t2);

        assertThat(videoIds(reviews("size", "20", "sort", "submittedAt,asc")))
                .containsExactly(oldest, middle, newest);
        // 토글 — desc 는 정확히 역순
        assertThat(videoIds(reviews("size", "20", "sort", "submittedAt,desc")))
                .containsExactly(newest, middle, oldest);
    }

    @Test
    @DisplayName("검수목록_미등록_정렬키는_400이_아니라_기본정렬로_폴백된다")
    void unknownSortKeyFallsBackToDefault() throws Exception {
        // given — updDt 가 서로 다른 3건 (기본 정렬이 실제로 적용됐는지 순서로 판정)
        LocalDateTime t1 = LocalDateTime.of(2026, 5, 1, 10, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 5, 1, 11, 0, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 5, 1, 12, 0, 0);
        Long oldest = seedReviewVideo("CLIP-SK-1", "CCTV-001", LsRawDataStatus.STTS_PENDING, t1);
        Long newest = seedReviewVideo("CLIP-SK-2", "CCTV-001", LsRawDataStatus.STTS_PENDING, t3);
        Long middle = seedReviewVideo("CLIP-SK-3", "CCTV-001", LsRawDataStatus.STTS_PENDING, t2);

        // when / then — 변경 전 이 API 는 sort 를 받지도 않아 어떤 값이든 200 이었다(R8/AC-8).
        // FE 가 URL 에 보존·재전송하는 다른 화면의 정렬 키(작업목록 regDt, cctvName 등)로 목록이 죽으면 안 된다.
        for (String key : List.of("regDt", "cctvName", "rawFilePathNm", "version", "stpCycl", "1=1", "password")) {
            assertThat(videoIds(reviews("size", "20", "sort", key + ",asc")))
                    .as("미등록 정렬키 [%s] 는 무시되고 기본 정렬(updDt DESC)로 폴백되어야 한다", key)
                    .containsExactly(newest, middle, oldest);
        }
    }

    @Test
    @DisplayName("검수목록_정렬키_개수_초과도_기본정렬로_폴백된다")
    void tooManySortOrdersFallsBackToDefault() throws Exception {
        LocalDateTime t1 = LocalDateTime.of(2026, 5, 1, 10, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 5, 1, 11, 0, 0);
        Long oldest = seedReviewVideo("CLIP-SL-1", "CCTV-001", LsRawDataStatus.STTS_PENDING, t1);
        Long newest = seedReviewVideo("CLIP-SL-2", "CCTV-001", LsRawDataStatus.STTS_PENDING, t2);

        // allowlist 고유 필드 수(updDt/rawDataId/dataSttsCd = 3) 초과 → 400 이 아니라 기본 정렬
        assertThat(videoIds(reviews("size", "20",
                "sort", "videoId,asc",
                "sort", "status,asc",
                "sort", "updDt,asc",
                "sort", "submittedAt,asc")))
                .containsExactly(newest, oldest);
    }

    @Test
    @DisplayName("작업목록_미등록_정렬키는_여전히_400이다")
    void taskBoardStillRejectsUnknownSortKey() throws Exception {
        // 회귀 가드 — 검수목록(폴백)과 작업목록(400)의 정책 차이를 고정한다.
        // 작업목록은 변경 전에도 Pageable 을 받아 잘못된 키가 PropertyReferenceException(500)이었으므로
        // 400 은 하위호환 파손이 아니라 개선이다. 검수목록만 관용 모드다.
        mockMvc.perform(get("/v1/tasks/board")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .param("status", "COMPLETED")
                        .param("sort", "rawFilePathNm,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("동일_제출일_다수_행에서_페이지경계_행이_중복되거나_누락되지_않는다")
    void stablePaginationOnTiedUpdDt() throws Exception {
        // given — updDt 가 완전히 동일한 5건 (배치가 초 단위로 일괄 갱신하는 실제 도메인 상황)
        LocalDateTime same = LocalDateTime.of(2026, 5, 2, 9, 0, 0);
        Set<Long> seeded = new LinkedHashSet<>();
        for (int i = 1; i <= 5; i++) {
            seeded.add(seedReviewVideo("CLIP-TIE-" + i, "CCTV-001", LsRawDataStatus.STTS_PENDING, same));
        }

        // 동률 구간의 기대 순서 = PK(videoId) 내림차순 tie-break. DB 의 우연한 반환 순서에 기대지 않고
        // tie-break 계약 자체를 단언해야 이 검증이 결정적이다(작은 테이블에서는 tie-break 가 없어도
        // 스캔 순서가 우연히 안정적으로 보일 수 있다).
        List<Long> expectedOrder = seeded.stream().sorted(java.util.Comparator.reverseOrder()).toList();

        // when — size=2 로 3페이지 연속 조회 (기본 정렬 + 사용자 지정 정렬 양쪽)
        for (String[] sortParams : List.of(new String[]{}, new String[]{"sort", "submittedAt,desc"})) {
            List<Long> collected = new ArrayList<>();
            for (int page = 0; page < 3; page++) {
                List<String> params = new ArrayList<>(List.of("page", String.valueOf(page), "size", "2"));
                params.addAll(List.of(sortParams));
                collected.addAll(videoIds(reviews(params.toArray(new String[0]))));
            }
            // then — 중복 0, 누락 0, 그리고 페이지 경계를 넘어 PK tie-break 순서가 유지
            assertThat(collected).doesNotHaveDuplicates();
            assertThat(collected).containsExactlyInAnyOrderElementsOf(seeded);
            assertThat(collected)
                    .as("동일 updDt 구간은 PK 내림차순으로 전순서가 확정되어야 한다")
                    .containsExactlyElementsOf(expectedOrder);
        }

        // 사용자 지정 정렬에 PK 축이 명시된 경우에도(오름차순) 그 지점에서 순서가 확정된다.
        List<Long> ascending = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            ascending.addAll(videoIds(reviews("page", String.valueOf(page), "size", "2", "sort", "videoId,asc")));
        }
        assertThat(ascending).containsExactlyElementsOf(
                seeded.stream().sorted().toList());
    }

    // ------------------------------------------------------------------ status

    @Test
    @DisplayName("status_파라미터로_BE코드_지정시_해당_상태만_반환")
    void statusFilterReturnsOnlyThatStatus() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 5, 3, 9, 0, 0);
        Long pending = seedReviewVideo("CLIP-ST-1", "CCTV-001", LsRawDataStatus.STTS_PENDING, now);
        Long inReview = seedReviewVideo("CLIP-ST-2", "CCTV-001", LsRawDataStatus.STTS_IN_REVIEW, now);
        Long approved = seedReviewVideo("CLIP-ST-3", "CCTV-001", LsRawDataStatus.STTS_APPROVED, now);

        assertThat(videoIds(reviews("size", "20", "status", "IN_REVIEW")))
                .containsExactly(inReview)
                .doesNotContain(pending, approved);
    }

    @Test
    @DisplayName("화이트리스트_밖_상태값_지정시_빈결과_반환")
    void statusOutsideWhitelistReturnsEmpty() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 5, 3, 9, 0, 0);
        seedReviewVideo("CLIP-WL-1", "CCTV-001", LsRawDataStatus.STTS_PROCESSING, now);
        seedReviewVideo("CLIP-WL-2", "CCTV-001", LsRawDataStatus.STTS_PENDING, now);

        // 기존 계약 — 400 이 아니라 빈 결과(200)
        mockMvc.perform(reviews("size", "20", "status", "PROCESSING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.content").isEmpty());
    }

    @Test
    @DisplayName("검수대상_아닌_배치상태_영상은_검색어와_매치돼도_반환되지_않는다")
    void batchStatusRowsNeverLeakThroughSearch() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 5, 3, 9, 0, 0);
        // 배치/작업 상태 5종 — CCTV-002('강남구 테헤란로 CCTV') 와 작업자100 을 붙여 검색어에 확실히 매치시킨다.
        for (String batchStatus : List.of(LsRawDataStatus.STTS_ASSIGNED, LsRawDataStatus.STTS_BATCH_QUEUED,
                LsRawDataStatus.STTS_PROCESSING, LsRawDataStatus.STTS_COMPLETED, LsRawDataStatus.STTS_FAILED)) {
            Long rawSn = seedReviewVideo("CLIP-LEAK-" + batchStatus, "CCTV-002", batchStatus, now);
            assignLabeler(rawSn, 100L);
        }
        Long visible = seedReviewVideo("CLIP-LEAK-OK", "CCTV-002", LsRawDataStatus.STTS_PENDING, now);
        assignLabeler(visible, 100L);

        // 영상명·작업자명 어느 축으로 검색해도 화이트리스트 밖 상태는 노출되지 않는다.
        assertThat(videoIds(reviews("size", "100", "q", "강남"))).containsExactly(visible);
        assertThat(videoIds(reviews("size", "100", "q", "작업자100"))).containsExactly(visible);
    }

    // ------------------------------------------------------------------ 검색어

    @Test
    @DisplayName("검색어가_영상명과_작업자명_양쪽에_부분일치")
    void searchMatchesVideoNameAndWorkerName() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 5, 4, 9, 0, 0);
        // A: 영상명(CCTV 명)만 매치 — 미배정
        Long byVideoName = seedReviewVideo("CLIP-Q-A", "CCTV-001", LsRawDataStatus.STTS_PENDING, now);
        // B: 작업자명만 매치 — CCTV-002(강남구) + 작업자101
        Long byWorkerName = seedReviewVideo("CLIP-Q-B", "CCTV-002", LsRawDataStatus.STTS_PENDING, now);
        assignLabeler(byWorkerName, 101L);

        assertThat(videoIds(reviews("size", "20", "q", "회기"))).containsExactly(byVideoName);
        assertThat(videoIds(reviews("size", "20", "q", "작업자101"))).containsExactly(byWorkerName);
        // 두 축을 모두 덮는 검색어는 합집합
        assertThat(videoIds(reviews("size", "20", "q", "CCTV")))
                .containsExactlyInAnyOrder(byVideoName, byWorkerName);
    }

    @Test
    @DisplayName("동일_REG_DT_재배정에서_표시되는_작업자와_검색되는_작업자가_동일하다")
    void workerDisplayAndSearchShareTieBreak() throws Exception {
        // given — 같은 영상에 REG_DT 가 완전히 동일한 LABELER 배정 2건 (재배정 누적 + 초 단위 일괄 처리)
        LocalDateTime now = LocalDateTime.of(2026, 5, 9, 9, 0, 0);
        Long rawSn = seedReviewVideo("CLIP-TIEW-1", "CCTV-001", LsRawDataStatus.STTS_PENDING, now);
        LsTaskAssignment first = assignLabeler(rawSn, 100L);
        LsTaskAssignment second = assignLabeler(rawSn, 101L);
        LocalDateTime sameRegDt = LocalDateTime.of(2026, 5, 9, 8, 0, 0);
        jdbc.update("UPDATE LS_TASK_ALTMNT SET REG_DT = ? WHERE ASSIGNMENT_ID IN (?, ?)",
                Timestamp.valueOf(sameRegDt), first.getAssignmentId(), second.getAssignmentId());

        // 계약상 최신 = (REG_DT, ASSIGNMENT_ID) 가 더 큰 배정. REG_DT 동률이므로 ASSIGNMENT_ID 가 판정한다.
        boolean secondIsLatest = second.getAssignmentId() > first.getAssignmentId();
        String latestWorker = secondIsLatest ? "작업자101" : "작업자100";
        String olderWorker = secondIsLatest ? "작업자100" : "작업자101";

        // then (1) 표시 — 최신 배정 작업자가 목록에 나온다 (ReviewService.laterAssignment)
        assertThat(itemOf(reviews("size", "100"), rawSn).path("workerName").asText())
                .as("표시되는 작업자는 (REG_DT, ASSIGNMENT_ID) 가 가장 큰 배정이어야 한다")
                .isEqualTo(latestWorker);

        // then (2) 검색 — 같은 배정 1건만 검색축이 열린다 (ReviewQueryRepository.latestLabelerMatches)
        assertThat(videoIds(reviews("size", "100", "q", latestWorker)))
                .as("표시된 작업자명으로 검색하면 그 영상이 나와야 한다")
                .contains(rawSn);
        assertThat(videoIds(reviews("size", "100", "q", olderWorker)))
                .as("최신이 아닌 과거 배정 작업자명으로는 검색되지 않아야 한다(표시와 불일치 방지)")
                .doesNotContain(rawSn);

        // then (3) 표시 ↔ 검색 정합 — 검색으로 걸린 행의 표시 작업자도 검색어와 같은 작업자여야 한다.
        assertThat(itemOf(reviews("size", "100", "q", latestWorker), rawSn).path("workerName").asText())
                .isEqualTo(latestWorker);
    }

    @Test
    @DisplayName("공백문자만_있는_CCTV명은_표시된_VMS_ID_로_검색된다")
    void blankCctvNameFallsBackToVmsIdOnBothDisplayAndSearch() throws Exception {
        // given — CCTV_NM 이 탭/개행/전각공백뿐이라 화면에는 VMS_CCTV_ID 로 표시되는 CCTV 들
        LocalDateTime now = LocalDateTime.of(2026, 5, 10, 9, 0, 0);
        Map<String, String> blankNames = new LinkedHashMap<>();
        blankNames.put("CCTV-BLANK-TAB", "\t");
        blankNames.put("CCTV-BLANK-NL", "\n");
        blankNames.put("CCTV-BLANK-IDEO", "　");     // 전각 공백 — 한글 데이터에서 흔하다
        blankNames.put("CCTV-BLANK-MIX", " \t \n ");
        Map<String, Long> videos = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : blankNames.entrySet()) {
            seedCctv(entry.getKey(), entry.getValue());
            videos.put(entry.getKey(), seedReviewVideo("CLIP-BLK-" + entry.getKey(), entry.getKey(),
                    LsRawDataStatus.STTS_PENDING, now));
        }
        // 대조군 — 이름이 있는 CCTV 는 화면에 보이지 않는 VMS_CCTV_ID 로 검색되면 안 된다.
        Long named = seedReviewVideo("CLIP-BLK-NAMED", "CCTV-001", LsRawDataStatus.STTS_PENDING, now);

        for (Map.Entry<String, Long> entry : videos.entrySet()) {
            String vmsCctvId = entry.getKey();
            Long rawSn = entry.getValue();

            // then (1) 표시 — Java isBlank() 판정으로 VMS_CCTV_ID 폴백
            assertThat(itemOf(reviews("size", "100"), rawSn).path("cctvName").asText())
                    .as("[%s] 공백문자만 있는 CCTV 명은 VMS ID 로 표시되어야 한다", vmsCctvId)
                    .isEqualTo(vmsCctvId);

            // then (2) 검색 — 표시된 그 값으로 검색하면 나와야 한다(SQL trim 은 공백문자만 제거해 어긋났었다)
            assertThat(videoIds(reviews("size", "100", "q", vmsCctvId)))
                    .as("[%s] 화면에 보이는 값으로 검색하면 그 영상이 나와야 한다", vmsCctvId)
                    .containsExactly(rawSn);
        }

        // then (3) 이름이 있는 CCTV 는 기존 규칙대로 VMS ID 축이 열리지 않는다.
        assertThat(videoIds(reviews("size", "100", "q", "CCTV-001"))).doesNotContain(named);
    }

    @Test
    @DisplayName("검색어_적용시_totalElements_가_필터_결과_기준으로_계산")
    void totalElementsReflectsSearchFilter() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 5, 5, 9, 0, 0);
        // 매치 3건(CCTV-001 회기로) + 비매치 4건(CCTV-002 테헤란로)
        List<Long> matched = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            matched.add(seedReviewVideo("CLIP-TE-M" + i, "CCTV-001", LsRawDataStatus.STTS_PENDING, now));
        }
        for (int i = 1; i <= 4; i++) {
            seedReviewVideo("CLIP-TE-N" + i, "CCTV-002", LsRawDataStatus.STTS_PENDING, now);
        }

        // 페이지 크기보다 매치 건수가 많지 않아도 totalElements 는 필터 결과 기준이어야 한다.
        assertThat(totalElements(reviews("size", "20", "q", "회기"))).isEqualTo(3);

        // 메모리 후처리였다면 첫 페이지가 2건 미만이거나 total 이 7 로 남는다(HIGH-1).
        JsonNode page0 = dataOf(reviews("size", "2", "page", "0", "q", "회기"));
        assertThat(page0.path("totalElements").asLong()).isEqualTo(3);
        assertThat(page0.path("content")).hasSize(2);
        JsonNode page1 = dataOf(reviews("size", "2", "page", "1", "q", "회기"));
        assertThat(page1.path("content")).hasSize(1);

        List<Long> collected = new ArrayList<>(videoIds(reviews("size", "2", "page", "0", "q", "회기")));
        collected.addAll(videoIds(reviews("size", "2", "page", "1", "q", "회기")));
        assertThat(collected).containsExactlyInAnyOrderElementsOf(matched);
    }

    @Test
    @DisplayName("검색어_LIKE_와일드카드_문자는_이스케이프되어_리터럴로_취급")
    void likeWildcardsEscaped() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 5, 6, 9, 0, 0);
        seedReviewVideo("CLIP-W-1", "CCTV-001", LsRawDataStatus.STTS_PENDING, now);
        seedReviewVideo("CLIP-W-2", "CCTV-002", LsRawDataStatus.STTS_PENDING, now);

        for (String wildcard : List.of("%", "_", "%%", "\\", "CCTV%", "동_문", "회%로")) {
            assertThat(totalElements(reviews("size", "100", "q", wildcard)))
                    .as("와일드카드 [%s] 는 리터럴로 취급되어야 한다", wildcard)
                    .isZero();
        }
    }

    @Test
    @DisplayName("SQL_인젝션_시도_검색어는_리터럴로_바인딩되어_데이터가_보존된다")
    void sqlInjectionAttemptIsBound() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 5, 7, 9, 0, 0);
        Long rawSn = seedReviewVideo("CLIP-INJ-1", "CCTV-001", LsRawDataStatus.STTS_PENDING, now);

        for (String malicious : List.of("'; DROP TABLE LS_RAW_DATA_STATUS; --", "' OR '1'='1",
                "' OR 1=1 --", "\" OR \"\"=\"")) {
            assertThat(totalElements(reviews("size", "100", "q", malicious)))
                    .as("악성 입력 [%s] 는 리터럴 검색어여야 한다", malicious)
                    .isZero();
        }

        // 데이터 보존 — 테이블/행이 그대로 살아있고 정상 조회가 계속된다.
        assertThat(dataSttsRepository.findById(rawSn)).isPresent();
        assertThat(videoIds(reviews("size", "100"))).contains(rawSn);
    }

    @Test
    @DisplayName("검색어_빈값은_필터로_취급되지_않아_기존_결과가_유지된다")
    void blankSearchIsNotAFilter() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 5, 8, 9, 0, 0);
        Long a = seedReviewVideo("CLIP-BL-1", "CCTV-001", LsRawDataStatus.STTS_PENDING, now);
        Long b = seedReviewVideo("CLIP-BL-2", "CCTV-002", LsRawDataStatus.STTS_APPROVED, now);

        for (String blank : List.of("", " ", "   ", "\t")) {
            assertThat(videoIds(reviews("size", "100", "q", blank)))
                    .as("q=[%s] 는 필터로 취급되지 않아야 한다", blank)
                    .containsExactlyInAnyOrder(a, b);
        }
    }

    @Test
    @DisplayName("과대_길이_검색어는_400")
    void oversizedSearchRejected() throws Exception {
        mockMvc.perform(reviews("size", "20", "q", "가".repeat(101)))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ 하위호환/인가

    @Test
    @DisplayName("size_한도_초과_요청은_변경전과_동일하게_400")
    void oversizedPageRejected() throws Exception {
        mockMvc.perform(reviews("page", "0", "size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("검수목록은_REVIEWER_아닌_사용자에게_403")
    void listForbiddenForWorker() throws Exception {
        mockMvc.perform(get("/v1/reviews").header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("검수목록_미인증시_401")
    void listUnauthorized() throws Exception {
        mockMvc.perform(get("/v1/reviews"))
                .andExpect(status().isUnauthorized());
    }
}
