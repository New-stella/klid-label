package kr.co.cudo.authoring.assignment;

import kr.co.cudo.authoring.assignment.domain.BoardWorkStatus;
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

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 1 — 작업목록({@code GET /v1/tasks/board}) 정렬 정책 전환 + 서버 필터 + 정렬 allowlist 검증.
 *
 * <p>검증 축:
 * <ul>
 *   <li><b>R8 하위호환</b> — 기존 파라미터만 보낸 호출의 결과 집합·건수·페이징·UNASSIGNED 분기가 불변</li>
 *   <li><b>R1</b> — 상태 우선순위 정렬 폐기, 시간축(등록일 최신순) 단일 정렬</li>
 *   <li><b>HIGH-1</b> — 배정 이력 다건 영상이 중복 행으로 증식되지 않음(EXISTS 기반 필터)</li>
 *   <li><b>HIGH-2</b> — 목록/count 가 동일 조건(totalElements 정합)</li>
 *   <li><b>HIGH-3</b> — workStatus 필터 결과가 화면 표시 상태와 일치</li>
 *   <li><b>HIGH-4</b> — 빈 문자열 필터가 결과를 바꾸지 않음</li>
 *   <li><b>HIGH-5</b> — allowlist 밖 정렬 키는 400(500 아님)</li>
 *   <li><b>HIGH-10</b> — 어떤 정렬 조합에도 rawSn tie-break 가 마지막에 강제되어 페이지 경계 안정</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class TaskBoardFilterSortTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;
    @Autowired private LsRawDataStatusRepository dataSttsRepository;

    private final JdbcTemplate jdbc;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    TaskBoardFilterSortTest(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

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
        return videoRepository.save(raw);
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

    private void forceRegDt(Long rawSn, LocalDateTime regDt) {
        jdbc.update("UPDATE LS_DATA_RAW SET REG_DT = ? WHERE RAW_SN = ?", Timestamp.valueOf(regDt), rawSn);
    }

    private void forceShtDt(Long rawSn, LocalDateTime shtDt) {
        jdbc.update("UPDATE LS_DATA_RAW SET SHT_DT = ? WHERE RAW_SN = ?", Timestamp.valueOf(shtDt), rawSn);
    }

    private static final Pattern VIDEO_ID = Pattern.compile("\"videoId\"\\s*:\\s*(\\d+)");

    /**
     * 작업목록 요청 빌더 — 검색어 등 비ASCII 값은 URL 문자열이 아니라 {@code param()} 으로 전달한다
     * (MockMvc 는 URL 의 퍼센트 인코딩을 디코드하지 않아 값이 리터럴로 들어간다).
     */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder board(String... queryParams) {
        var builder = get("/v1/tasks/board").header("Authorization", "Bearer " + reviewerToken);
        for (int i = 0; i + 1 < queryParams.length; i += 2) {
            builder = builder.param(queryParams[i], queryParams[i + 1]);
        }
        return builder;
    }

    /** 응답 본문에서 videoId 를 등장 순서대로 추출한다(정렬 검증용). */
    private List<Long> videoIds(String url) throws Exception {
        return videoIds(get(url).header("Authorization", "Bearer " + reviewerToken));
    }

    private List<Long> videoIds(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();
        Matcher m = VIDEO_ID.matcher(result.getResponse().getContentAsString());
        List<Long> ids = new ArrayList<>();
        while (m.find()) {
            ids.add(Long.parseLong(m.group(1)));
        }
        return ids;
    }

    // ------------------------------------------------------- R8 하위호환 (Critical)

    @Test
    @DisplayName("기존_파라미터만_보내면_변경전과_동일한_결과집합_반환")
    void legacyCallReturnsSameResultSet() throws Exception {
        LsDataRaw done1 = seedCompleted("CLIP-L-1");
        assignAndSetWorkflow(done1.getRawSn(), LsRawDataStatus.STTS_REJECTED);
        LsDataRaw done2 = seedCompleted("CLIP-L-2");           // 미배정
        LsDataRaw done3 = seedCompleted("CLIP-L-3");
        assignAndSetWorkflow(done3.getRawSn(), LsRawDataStatus.STTS_APPROVED);
        seedVideo("CLIP-L-PENDING", "CCTV-001", "EVT-FIRE", "PENDING"); // 배치 PENDING — 제외 대상

        List<Long> ids = videoIds("/v1/tasks/board?status=COMPLETED&page=0&size=20");

        assertThat(ids).containsExactlyInAnyOrder(done1.getRawSn(), done2.getRawSn(), done3.getRawSn());
        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.number").value(0));
    }

    @Test
    @DisplayName("파라미터_없이_호출하면_기본_status_COMPLETED_로_동작")
    void noParamDefaultsToCompleted() throws Exception {
        LsDataRaw done = seedCompleted("CLIP-D-DONE");
        seedVideo("CLIP-D-PENDING", "CCTV-001", "EVT-FIRE", "PENDING");

        mockMvc.perform(get("/v1/tasks/board").header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].videoId").value(done.getRawSn()))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    @DisplayName("status_UNASSIGNED_는_배치상태_무관_미배정만_반환")
    void unassignedIgnoresBatchStatus() throws Exception {
        LsDataRaw freePending = seedVideo("CLIP-U-PENDING", "CCTV-001", "EVT-FIRE", "PENDING");
        LsDataRaw freeCompleted = seedCompleted("CLIP-U-DONE");
        LsDataRaw assigned = seedCompleted("CLIP-U-ASSIGNED");
        assignLabeler(assigned.getRawSn(), 100L);

        List<Long> ids = videoIds("/v1/tasks/board?status=UNASSIGNED&page=0&size=20");

        assertThat(ids).containsExactlyInAnyOrder(freePending.getRawSn(), freeCompleted.getRawSn());
    }

    // ------------------------------------------------------------------ R1 정렬

    @Test
    @DisplayName("상태_우선순위_정렬이_적용되지_않고_등록일_최신순으로_반환")
    void noStatusPriorityOnlyRegDtDesc() throws Exception {
        // given: (구 정책이라면 최상단이었을) 반려 건이 가장 오래된 등록일, 완료 건이 가장 최신 등록일
        LsDataRaw rejected = seedCompleted("CLIP-S-REJECTED");
        assignAndSetWorkflow(rejected.getRawSn(), LsRawDataStatus.STTS_REJECTED);
        LsDataRaw unassigned = seedCompleted("CLIP-S-UNASSIGNED");
        LsDataRaw approved = seedCompleted("CLIP-S-APPROVED");
        assignAndSetWorkflow(approved.getRawSn(), LsRawDataStatus.STTS_APPROVED);

        forceRegDt(rejected.getRawSn(), LocalDateTime.of(2026, 6, 1, 0, 0));
        forceRegDt(unassigned.getRawSn(), LocalDateTime.of(2026, 6, 2, 0, 0));
        forceRegDt(approved.getRawSn(), LocalDateTime.of(2026, 6, 3, 0, 0));

        List<Long> ids = videoIds("/v1/tasks/board?status=COMPLETED&page=0&size=20");

        // then: 상태 그룹핑 없이 등록일 최신순 — 완료 > 미배정 > 반려
        assertThat(ids).containsExactly(approved.getRawSn(), unassigned.getRawSn(), rejected.getRawSn());
    }

    @Test
    @DisplayName("정렬키_미지정시_등록일_내림차순_폴백")
    void defaultSortIsRegDtDesc() throws Exception {
        LsDataRaw older = seedCompleted("CLIP-F-OLD");
        LsDataRaw newer = seedCompleted("CLIP-F-NEW");
        forceRegDt(older.getRawSn(), LocalDateTime.of(2026, 6, 1, 0, 0));
        forceRegDt(newer.getRawSn(), LocalDateTime.of(2026, 6, 5, 0, 0));

        assertThat(videoIds("/v1/tasks/board?status=COMPLETED&page=0&size=20"))
                .containsExactly(newer.getRawSn(), older.getRawSn());
    }

    @Test
    @DisplayName("allowlist_밖_정렬키_요청시_400")
    void unknownSortKeyRejected() throws Exception {
        seedCompleted("CLIP-SORT-BAD");

        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&sort=rawFilePathNm,asc")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("rawFilePathNm"))));
    }

    @Test
    @DisplayName("허용_정렬키_capturedAt_은_촬영일시로_매핑되어_동작한다")
    void capturedAtSortMapsToShtDt() throws Exception {
        LsDataRaw a = seedCompleted("CLIP-CA-A");
        LsDataRaw b = seedCompleted("CLIP-CA-B");
        forceShtDt(a.getRawSn(), LocalDateTime.of(2026, 1, 1, 0, 0));
        forceShtDt(b.getRawSn(), LocalDateTime.of(2026, 3, 1, 0, 0));

        assertThat(videoIds("/v1/tasks/board?status=COMPLETED&sort=capturedAt,desc"))
                .containsExactly(b.getRawSn(), a.getRawSn());
    }

    @Test
    @DisplayName("다중_정렬키_요청시_rawSn_tie_break_가_항상_마지막에_적용")
    void multiSortAlwaysAppendsRawSnTieBreak() throws Exception {
        LsDataRaw first = seedCompleted("CLIP-TB-1");
        LsDataRaw second = seedCompleted("CLIP-TB-2");
        LsDataRaw third = seedCompleted("CLIP-TB-3");
        LocalDateTime same = LocalDateTime.of(2026, 6, 1, 12, 0);
        for (LsDataRaw v : List.of(first, second, third)) {
            forceRegDt(v.getRawSn(), same);
            forceShtDt(v.getRawSn(), same);
        }

        // 정렬값이 전부 동일 → rawSn DESC tie-break 로 결정론적 순서
        assertThat(videoIds("/v1/tasks/board?status=COMPLETED&sort=capturedAt,desc&sort=regDt,desc"))
                .containsExactly(third.getRawSn(), second.getRawSn(), first.getRawSn());
    }

    @Test
    @DisplayName("rawSn_정렬을_명시하면_그_지점에서_전순서가_확정된다")
    void explicitRawSnSortDeterminesTotalOrder() throws Exception {
        // given: 등록일은 서로 다르지만(regDt ASC 가 rawSn DESC 와 정반대) rawSn 이 앞선 정렬 키
        LsDataRaw first = seedCompleted("CLIP-VID-1");
        LsDataRaw second = seedCompleted("CLIP-VID-2");
        LsDataRaw third = seedCompleted("CLIP-VID-3");
        forceRegDt(first.getRawSn(), LocalDateTime.of(2026, 6, 1, 0, 0));
        forceRegDt(second.getRawSn(), LocalDateTime.of(2026, 6, 2, 0, 0));
        forceRegDt(third.getRawSn(), LocalDateTime.of(2026, 6, 3, 0, 0));

        // when / then: rawSn 이 유니크 PK 이므로 뒤따르는 regDt ASC 는 비교에 도달하지 못한다.
        assertThat(videoIds("/v1/tasks/board?status=COMPLETED&sort=videoId,desc&sort=regDt,asc"))
                .containsExactly(third.getRawSn(), second.getRawSn(), first.getRawSn());
    }

    @Test
    @DisplayName("정렬_기준_개수가_상한을_넘으면_400")
    void tooManySortOrdersRejected() throws Exception {
        seedCompleted("CLIP-SORT-MANY");

        StringBuilder url = new StringBuilder("/v1/tasks/board?status=COMPLETED");
        String[] keys = {"regDt", "shtDt", "rawSn"};
        for (int i = 0; i < 100; i++) {
            url.append("&sort=").append(keys[i % keys.length]).append(i % 2 == 0 ? ",asc" : ",desc");
        }

        mockMvc.perform(get(url.toString()).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"))
                // CWE-209 — 개수/상한 등 내부 정책을 메시지로 흘리지 않는다.
                .andExpect(jsonPath("$.message").value("정렬 기준이 너무 많습니다."));
    }

    @Test
    @DisplayName("동일_엔티티필드_중복_정렬키는_한_번만_적용")
    void duplicateSortKeyAppliedOnce() throws Exception {
        LsDataRaw older = seedCompleted("CLIP-DUPSORT-OLD");
        LsDataRaw newer = seedCompleted("CLIP-DUPSORT-NEW");
        forceShtDt(older.getRawSn(), LocalDateTime.of(2026, 1, 1, 0, 0));
        forceShtDt(newer.getRawSn(), LocalDateTime.of(2026, 3, 1, 0, 0));

        // capturedAt/shtDt 는 같은 엔티티 필드 — 첫 지정(ASC)만 적용되고 뒤의 DESC 는 무시된다.
        assertThat(videoIds("/v1/tasks/board?status=COMPLETED&sort=capturedAt,asc&sort=shtDt,desc"))
                .containsExactly(older.getRawSn(), newer.getRawSn());
    }

    @Test
    @DisplayName("동일_등록일_다수_행에서_페이지경계_행이_중복되거나_누락되지_않는다")
    void pageBoundaryStableWithIdenticalRegDt() throws Exception {
        LocalDateTime same = LocalDateTime.of(2026, 6, 1, 12, 0);
        Set<Long> expected = new LinkedHashSet<>();
        for (int i = 0; i < 5; i++) {
            LsDataRaw v = seedCompleted("CLIP-PB-" + i);
            forceRegDt(v.getRawSn(), same);
            expected.add(v.getRawSn());
        }

        List<Long> page0 = videoIds("/v1/tasks/board?status=COMPLETED&page=0&size=2");
        List<Long> page1 = videoIds("/v1/tasks/board?status=COMPLETED&page=1&size=2");
        List<Long> page2 = videoIds("/v1/tasks/board?status=COMPLETED&page=2&size=2");

        List<Long> all = new ArrayList<>();
        all.addAll(page0);
        all.addAll(page1);
        all.addAll(page2);

        assertThat(all).as("중복 없음").doesNotHaveDuplicates();
        assertThat(all).as("누락 없음").containsExactlyInAnyOrderElementsOf(expected);
    }

    // ------------------------------------------------------------ workStatus 필터

    @Test
    @DisplayName("workStatus_필터가_워크플로_상태로만_거른다")
    void workStatusFilters() throws Exception {
        LsDataRaw rejected = seedCompleted("CLIP-W-REJ");
        assignAndSetWorkflow(rejected.getRawSn(), LsRawDataStatus.STTS_REJECTED);
        LsDataRaw reviewPending = seedCompleted("CLIP-W-REV");
        assignAndSetWorkflow(reviewPending.getRawSn(), LsRawDataStatus.STTS_IN_REVIEW);
        LsDataRaw unassigned = seedCompleted("CLIP-W-UNASSIGNED");
        LsDataRaw approved = seedCompleted("CLIP-W-APPROVED");
        assignAndSetWorkflow(approved.getRawSn(), LsRawDataStatus.STTS_APPROVED);

        assertThat(videoIds("/v1/tasks/board?status=COMPLETED&workStatus=REJECTED"))
                .containsExactly(rejected.getRawSn());
        assertThat(videoIds("/v1/tasks/board?status=COMPLETED&workStatus=REVIEW_PENDING"))
                .containsExactly(reviewPending.getRawSn());
        assertThat(videoIds("/v1/tasks/board?status=COMPLETED&workStatus=UNASSIGNED"))
                .containsExactly(unassigned.getRawSn());
        assertThat(videoIds("/v1/tasks/board?status=COMPLETED&workStatus=COMPLETED"))
                .containsExactly(approved.getRawSn());
    }

    @Test
    @DisplayName("workStatus_와_기존_status_는_서로_독립적으로_동작")
    void workStatusAndBatchStatusAreIndependent() throws Exception {
        LsDataRaw completedRejected = seedCompleted("CLIP-IND-DONE-REJ");
        assignAndSetWorkflow(completedRejected.getRawSn(), LsRawDataStatus.STTS_REJECTED);
        LsDataRaw pendingRejected = seedVideo("CLIP-IND-PEND-REJ", "CCTV-001", "EVT-FIRE", "PENDING");
        assignAndSetWorkflow(pendingRejected.getRawSn(), LsRawDataStatus.STTS_REJECTED);

        // 배치 COMPLETED + 워크플로 REJECTED = 첫 건만
        assertThat(videoIds("/v1/tasks/board?status=COMPLETED&workStatus=REJECTED"))
                .containsExactly(completedRejected.getRawSn());
        // 배치 PENDING + 워크플로 REJECTED = 둘째 건만
        assertThat(videoIds("/v1/tasks/board?status=PENDING&workStatus=REJECTED"))
                .containsExactly(pendingRejected.getRawSn());
    }

    @Test
    @DisplayName("workStatus_필터결과가_mapBoardStatus_매핑과_일치한다")
    void workStatusFilterMatchesDisplayedStatus() throws Exception {
        LsDataRaw unassigned = seedCompleted("CLIP-M-UNASSIGNED");
        LsDataRaw pending = seedCompleted("CLIP-M-PENDING");
        assignAndSetWorkflow(pending.getRawSn(), LsRawDataStatus.STTS_ASSIGNED);
        LsDataRaw reviewPending = seedCompleted("CLIP-M-REVIEW");
        assignAndSetWorkflow(reviewPending.getRawSn(), LsRawDataStatus.STTS_PENDING);
        LsDataRaw completed = seedCompleted("CLIP-M-COMPLETED");
        assignAndSetWorkflow(completed.getRawSn(), LsRawDataStatus.STTS_APPROVED);
        LsDataRaw rejected = seedCompleted("CLIP-M-REJECTED");
        assignAndSetWorkflow(rejected.getRawSn(), LsRawDataStatus.STTS_REJECTED);

        for (String ws : List.of("UNASSIGNED", "PENDING", "REVIEW_PENDING", "COMPLETED", "REJECTED")) {
            mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&size=100&workStatus=" + ws)
                            .header("Authorization", "Bearer " + reviewerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    // 필터 결과의 화면 표시 상태가 필터 값과 정확히 일치해야 한다.
                    .andExpect(jsonPath("$.data.content[0].status").value(ws));
        }
    }

    @Test
    @DisplayName("dataSttsCd_null_행이_workStatus_PENDING_필터에_포함된다")
    void nullWorkflowStatusIncludedInPending() throws Exception {
        // 배정은 있으나 LS_RAW_DATA_STATUS row 가 없는 레거시 행 → 표시 상태 PENDING
        LsDataRaw legacy = seedCompleted("CLIP-NULL-STTS");
        assignLabeler(legacy.getRawSn(), 100L);

        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&workStatus=PENDING")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].videoId").value(legacy.getRawSn()))
                .andExpect(jsonPath("$.data.content[0].status").value("PENDING"));
    }

    @Test
    @DisplayName("미지_상태코드_행도_workStatus_PENDING_필터에_포함된다")
    void unknownStatusCodesIncludedInPending() throws Exception {
        // given: BoardWorkStatus 가 명시적으로 분기하지 않는 코드(배치/작업 축) + 레거시 null 행
        List<String> defaultBranchCodes = List.of(
                LsRawDataStatus.STTS_BATCH_QUEUED,
                LsRawDataStatus.STTS_PROCESSING,
                LsRawDataStatus.STTS_FAILED,
                LsRawDataStatus.STTS_COMPLETED,   // 워크플로 COMPLETED(≠ APPROVED) 도 진행중으로 표시된다
                LsRawDataStatus.STTS_ASSIGNED);
        List<Long> expected = new ArrayList<>();
        for (String code : defaultBranchCodes) {
            LsDataRaw v = seedCompleted("CLIP-UNK-" + code);
            assignAndSetWorkflow(v.getRawSn(), code);
            expected.add(v.getRawSn());
        }
        LsDataRaw legacyNull = seedCompleted("CLIP-UNK-NULL"); // 상태 row 없음
        assignLabeler(legacyNull.getRawSn(), 100L);
        expected.add(legacyNull.getRawSn());

        // 검수 축 코드/미배정은 PENDING 이 아니다(대조군)
        LsDataRaw inReview = seedCompleted("CLIP-UNK-REVIEW");
        assignAndSetWorkflow(inReview.getRawSn(), LsRawDataStatus.STTS_IN_REVIEW);
        seedCompleted("CLIP-UNK-FREE");

        // when / then: SQL 술어(NOT EXISTS 제외집합)가 실제로 미지 코드를 진행중으로 포함해야 한다.
        assertThat(videoIds("/v1/tasks/board?status=COMPLETED&size=100&workStatus=PENDING"))
                .containsExactlyInAnyOrderElementsOf(expected);
        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&size=100&workStatus=PENDING")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].status",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("PENDING"))));
    }

    @Test
    @DisplayName("모든_workStatus_필터의_SQL_결과가_matches_판정과_동일한_행집합이다")
    void sqlPredicateEqualsJavaMatches() throws Exception {
        record Fixture(Long rawSn, String code, boolean hasLabeler) {
        }
        List<String> codes = new ArrayList<>(Arrays.asList(
                null, "ASSIGNED", "PENDING", "IN_REVIEW", "APPROVED", "REJECTED",
                "BATCH_QUEUED", "PROCESSING", "FAILED", "COMPLETED", "UNKNOWN_FUTURE"));
        List<Fixture> fixtures = new ArrayList<>();
        int seq = 0;
        for (String code : codes) {
            for (boolean hasLabeler : new boolean[]{true, false}) {
                LsDataRaw v = seedCompleted("CLIP-XV-" + (seq++));
                if (hasLabeler) {
                    assignLabeler(v.getRawSn(), 100L);
                }
                if (code != null) {
                    setWorkflow(v.getRawSn(), code);
                }
                fixtures.add(new Fixture(v.getRawSn(), code, hasLabeler));
            }
        }

        for (BoardWorkStatus ws : BoardWorkStatus.values()) {
            List<Long> expected = fixtures.stream()
                    .filter(f -> ws.matches(f.code(), f.hasLabeler()))
                    .map(Fixture::rawSn)
                    .toList();
            assertThat(videoIds("/v1/tasks/board?status=COMPLETED&size=100&workStatus=" + ws.name()))
                    .as("workStatus=%s 의 SQL 결과가 BoardWorkStatus.matches 판정과 달라졌다", ws)
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    @Test
    @DisplayName("허용되지_않은_workStatus_값이면_400")
    void invalidWorkStatusRejected() throws Exception {
        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&workStatus=DROP_TABLE")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    // ---------------------------------------------------------- 작업자 필터 (HIGH-1)

    @Test
    @DisplayName("재배정_이력이_여러건인_영상이_중복행으로_반환되지_않는다")
    void multipleAssignmentRowsDoNotDuplicateVideo() throws Exception {
        LsDataRaw video = seedCompleted("CLIP-DUP");
        // 재배정 이력 누적 — 같은 영상에 LABELER 배정 행이 2건
        assignLabeler(video.getRawSn(), 100L);
        assignLabeler(video.getRawSn(), 101L);
        setWorkflow(video.getRawSn(), LsRawDataStatus.STTS_ASSIGNED);

        // 필터 없이 조회해도, 작업자 검색어로 조회해도 영상은 1행이어야 한다.
        assertThat(videoIds("/v1/tasks/board?status=COMPLETED&size=100")).containsExactly(video.getRawSn());

        mockMvc.perform(board("status", "COMPLETED", "size", "100", "q", "작업자10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(1));

        // 최신 배정 작업자(101) 기준으로 workerId 필터가 동작하고, 역시 1행.
        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&size=100&workerId=101")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].videoId").value(video.getRawSn()))
                .andExpect(jsonPath("$.data.content[0].workerId").value(101));
    }

    @Test
    @DisplayName("workerId_필터는_해당_작업자에게_배정된_영상만_반환")
    void workerIdFilter() throws Exception {
        LsDataRaw mine = seedCompleted("CLIP-WK-MINE");
        assignLabeler(mine.getRawSn(), 100L);
        LsDataRaw others = seedCompleted("CLIP-WK-OTHER");
        assignLabeler(others.getRawSn(), 101L);
        seedCompleted("CLIP-WK-FREE");

        assertThat(videoIds("/v1/tasks/board?status=COMPLETED&size=100&workerId=100"))
                .containsExactly(mine.getRawSn());
    }

    // ------------------------------------------------------------------ 검색어 q

    @Test
    @DisplayName("검색어가_영상명과_작업자명_양쪽에_부분일치")
    void searchMatchesVideoNameAndWorkerName() throws Exception {
        LsDataRaw gangnam = seedVideo("CLIP-Q-GANGNAM", "CCTV-002", "EVT-FIRE", "COMPLETED"); // 강남구 테헤란로 CCTV
        LsDataRaw dongdaemun = seedVideo("CLIP-Q-DDM", "CCTV-001", "EVT-FIRE", "COMPLETED");  // 동대문구 회기로 CCTV
        assignLabeler(dongdaemun.getRawSn(), 101L);                                          // 작업자101

        assertThat(videoIds(board("status", "COMPLETED", "size", "100", "q", "강남")))
                .containsExactly(gangnam.getRawSn());
        assertThat(videoIds(board("status", "COMPLETED", "size", "100", "q", "작업자101")))
                .containsExactly(dongdaemun.getRawSn());
    }

    @Test
    @DisplayName("CCTV_마스터가_없으면_영상명_폴백값으로_검색된다")
    void searchFallsBackToVmsCctvId() throws Exception {
        LsDataRaw orphan = seedVideo("CLIP-Q-ORPHAN", "CCTV-999", "EVT-FIRE", "COMPLETED");
        seedVideo("CLIP-Q-OTHER", "CCTV-001", "EVT-FIRE", "COMPLETED");

        assertThat(videoIds(board("status", "COMPLETED", "size", "100", "q", "CCTV-999")))
                .containsExactly(orphan.getRawSn());
    }

    @Test
    @DisplayName("검색어_LIKE_와일드카드_문자는_이스케이프되어_리터럴로_취급")
    void likeWildcardsEscaped() throws Exception {
        seedVideo("CLIP-Q-W1", "CCTV-001", "EVT-FIRE", "COMPLETED");
        seedVideo("CLIP-Q-W2", "CCTV-002", "EVT-FIRE", "COMPLETED");

        for (String wildcard : List.of("%", "_", "%%", "\\", "CCTV%", "동_문")) {
            mockMvc.perform(board("status", "COMPLETED", "size", "100", "q", wildcard))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(0));
        }
    }

    @Test
    @DisplayName("SQL_인젝션_시도_검색어는_리터럴로_바인딩되어_데이터가_보존된다")
    void sqlInjectionAttemptIsBound() throws Exception {
        LsDataRaw v = seedCompleted("CLIP-Q-INJECT");

        for (String malicious : List.of("'; DROP TABLE LS_DATA_RAW; --", "' OR 1=1 --", "\" OR \"\"=\"")) {
            mockMvc.perform(board("status", "COMPLETED", "size", "100", "q", malicious))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(0));
        }

        // 테이블/행이 그대로 살아있어야 한다.
        assertThat(videoRepository.findById(v.getRawSn())).isPresent();
    }

    @Test
    @DisplayName("빈문자열_필터는_모든_파라미터에서_동일하게_필터로_취급되지_않는다")
    void blankSearchIsNotAFilter() throws Exception {
        LsDataRaw a = seedCompleted("CLIP-BLANK-A");
        assignAndSetWorkflow(a.getRawSn(), LsRawDataStatus.STTS_REJECTED);
        LsDataRaw b = seedCompleted("CLIP-BLANK-B"); // 미배정
        List<Long> allIds = List.of(a.getRawSn(), b.getRawSn());

        // q / eventTypeCd / workStatus 는 blank 처리 의미가 동일해야 한다 —
        // workStatus 만 400 이 되면 "빈 값 = 필터 미적용" 계약이 파라미터마다 갈린다.
        for (String param : List.of("q", "eventTypeCd", "workStatus")) {
            for (String blank : List.of("", " ", "   ", "\t")) {
                assertThat(videoIds(board("status", "COMPLETED", "size", "100", param, blank)))
                        .as("%s=%s 는 필터로 취급되지 않아야 한다", param, "[" + blank + "]")
                        .containsExactlyInAnyOrderElementsOf(allIds);
            }
        }
    }

    @Test
    @DisplayName("과대_길이_검색어는_400")
    void tooLongSearchRejected() throws Exception {
        String tooLong = "가".repeat(101);
        mockMvc.perform(board("status", "COMPLETED", "q", tooLong))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    // ------------------------------------------------------------- eventTypeCd

    @Test
    @DisplayName("eventTypeCd_필터는_해당_이벤트유형만_반환")
    void eventTypeFilter() throws Exception {
        LsDataRaw fire = seedVideo("CLIP-E-FIRE", "CCTV-001", "EVT-FIRE", "COMPLETED");
        seedVideo("CLIP-E-FALL", "CCTV-001", "EVT-FALL", "COMPLETED");

        assertThat(videoIds("/v1/tasks/board?status=COMPLETED&size=100&eventTypeCd=EVT-FIRE"))
                .containsExactly(fire.getRawSn());
    }

    @Test
    @DisplayName("과대_길이_eventTypeCd_는_400")
    void tooLongEventTypeRejected() throws Exception {
        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&eventTypeCd=" + "A".repeat(21))
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    // ----------------------------------------------------------- count 정합/인가

    @Test
    @DisplayName("필터_적용시_totalElements_가_필터_결과_기준으로_계산")
    void totalElementsReflectsFilters() throws Exception {
        for (int i = 0; i < 3; i++) {
            LsDataRaw rejected = seedCompleted("CLIP-C-REJ-" + i);
            assignAndSetWorkflow(rejected.getRawSn(), LsRawDataStatus.STTS_REJECTED);
        }
        for (int i = 0; i < 4; i++) {
            seedCompleted("CLIP-C-FREE-" + i);
        }

        // size=1 로 강제해 count 쿼리가 실제로 실행되게 한다(목록/count 조건 불일치 검출).
        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&page=0&size=1&workStatus=REJECTED")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.content.length()").value(1));

        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&page=0&size=1&workStatus=UNASSIGNED")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(4));
    }

    @Test
    @DisplayName("REVIEWER_아닌_사용자_요청시_403")
    void workerForbidden() throws Exception {
        seedCompleted("CLIP-SEC-1");
        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&workStatus=REJECTED")
                        .param("q", "작업자")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("미인증_요청시_401")
    void unauthenticated() throws Exception {
        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&workStatus=REJECTED"))
                .andExpect(status().isUnauthorized());
    }
}
