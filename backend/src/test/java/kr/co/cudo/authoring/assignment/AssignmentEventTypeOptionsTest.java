package kr.co.cudo.authoring.assignment;

import kr.co.cudo.authoring.support.IngestFlatValueSeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.version.entity.LabelChange;
import kr.co.cudo.authoring.version.entity.LsDataLblHstry;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 — 배정 이벤트유형 옵션({@code GET /v1/assignments/event-types}) 검증.
 *
 * <p>검증 축:
 * <ul>
 *   <li><b>R4</b> — 옵션이 <b>현재 페이지가 아니라 본인 배정 전체</b>에서 수집된다.</li>
 *   <li><b>옵션↔목록 정합(위험 #6)</b> — 목록과 같은 조건({@code buildWhere})을 쓰되 <b>자기 축
 *       ({@code eventTypeCd})만 제외</b>한다. 옵션에서 고른 값으로 목록을 필터하면 반드시 1건 이상이다.</li>
 *   <li><b>R6 (IDOR, CWE-639)</b> — WORKER 는 {@code workerId} 를 보내도 본인 배정의 이벤트유형만 본다.
 *       옵션 API 는 "값의 존재" 자체가 정보 누출이므로 목록과 동일한 인가 규칙이 필요하다.</li>
 *   <li><b>자원 고갈(OWASP API4)</b> — 페이징 없는 목록이라 개수 상한 + {@code truncated} 표기.</li>
 *   <li><b>응답 계약</b> — REVIEWER 쪽 {@code GET /v1/tasks/board/event-types} 와 동일한
 *       {@code {items, truncated}} 형태(FE 가 한 컴포넌트로 두 경로를 다룬다).</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AssignmentEventTypeOptionsTest {

    private static final long WORKER = 100L;
    private static final long OTHER_WORKER = 101L;

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;
    @Autowired private LsRawDataStatusRepository dataSttsRepository;
    @Autowired private LsDataSrcRepository dataSrcRepository;
    @Autowired private LsDataLblRepository dataLblRepository;
    @Autowired private LsDataLblHstryRepository labelHistoryRepository;

    private final JdbcTemplate jdbc;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String workerToken;
    private String reviewerToken;

    /** 프레임 번호 발급기 — UK (RAW_SN, FRM_NO) 충돌 방지. */
    private final java.util.concurrent.atomic.AtomicLong frameSeq = new java.util.concurrent.atomic.AtomicLong();

    AssignmentEventTypeOptionsTest(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setup() {
        workerToken = JwtTestSupport.token(secret, String.valueOf(WORKER), "WORKER", "INTERNAL", issuer, 60);
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
    }

    // ---------------------------------------------------------------- fixtures

    private LsDataRaw seedVideo(String clipId, String cctvId, String evntTypeCd) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                clipId, cctvId, evntTypeCd, "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                LocalDateTime.of(2026, 5, 1, 9, 0, 0), 30);
        raw = videoRepository.save(raw);
        raw.changeStatus("COMPLETED");
        LsDataRaw saved = videoRepository.save(raw);
        seedIngestName(saved.getRawSn(), cctvId);
        return saved;
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


    /** 표시명·이벤트유형을 지정한 영상 1건을 만들고 지정 작업자에게 배정한다. */
    private LsDataRaw seedAssigned(String key, String displayName, String evntTypeCd, long workerNo) {
        String cctvId = "CCTV-" + key;
        seedCctv(cctvId, displayName);
        LsDataRaw video = seedVideo("CLIP-" + key, cctvId, evntTypeCd);
        authrtRepository.save(LsTaskAssignment.createLabeler(video.getRawSn(), workerNo, 1L));
        return video;
    }

    private LsDataRaw seedAssigned(String key, String displayName, String evntTypeCd) {
        return seedAssigned(key, displayName, evntTypeCd, WORKER);
    }

    private void setWorkflow(Long rawSn, String workflowStatus) {
        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        stts.transitionTo(workflowStatus);
        dataSttsRepository.save(stts);
    }

    private Long seedFrame(Long rawSn) {
        long frameNo = frameSeq.getAndIncrement();
        return dataSrcRepository.save(LsDataSrc.create(
                        rawSn, frameNo, "/var/frames/" + rawSn + "-" + frameNo + ".jpg",
                        LocalDateTime.of(2026, 5, 1, 9, 0, 0)))
                .getSrcSn();
    }

    /** 작업자 라벨 저장(= '작업중' 판정 근거) 1건. */
    private void recordUserLabelSave(Long rawSn) {
        Long srcSn = seedFrame(rawSn);
        LsDataLbl saved = dataLblRepository.save(LsDataLbl.createManual(
                srcSn, LsDataLbl.TYPE_BBOX, null, "person", "[[10,10],[20,20]]", WORKER));
        labelHistoryRepository.save(LsDataLblHstry.recordSaveEvent(
                srcSn, String.valueOf(WORKER),
                List.of(LabelChange.added(saved.getLblSn(), "person", null))));
    }

    private void forceAssignmentRegDt(Long rawSn, LocalDateTime regDt) {
        jdbc.update("UPDATE LS_TASK_ALTMNT SET REG_DT = ? WHERE RAW_DATA_ID = ?",
                java.sql.Timestamp.valueOf(regDt), rawSn);
    }

    // ---------------------------------------------------------------- requests

    private MockHttpServletRequestBuilder eventTypes(String token, String... params) {
        return withParams(get("/v1/assignments/event-types")
                .header("Authorization", "Bearer " + token), params);
    }

    private MockHttpServletRequestBuilder assignments(String token, String... params) {
        return withParams(get("/v1/assignments")
                .header("Authorization", "Bearer " + token), params);
    }

    private MockHttpServletRequestBuilder withParams(MockHttpServletRequestBuilder builder, String... params) {
        for (int i = 0; i + 1 < params.length; i += 2) {
            builder = builder.param(params[i], params[i + 1]);
        }
        return builder;
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        byte[] raw = mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        return new ObjectMapper().readTree(new String(raw, StandardCharsets.UTF_8)).path("data");
    }

    private List<String> options(MockHttpServletRequestBuilder request) throws Exception {
        List<String> out = new ArrayList<>();
        for (JsonNode item : data(request).path("items")) {
            out.add(item.asText());
        }
        return out;
    }

    private long listTotal(MockHttpServletRequestBuilder request) throws Exception {
        return data(request).path("totalElements").asLong();
    }

    // ------------------------------------------------------------------ R4 전체 기준

    @Test
    @DisplayName("이벤트유형_옵션은_현재페이지가_아닌_전체_배정에서_수집된다")
    void optionsCollectedFromWholeDatasetNotCurrentPage() throws Exception {
        // given: 기본 정렬(regDt DESC) 기준 첫 페이지(20건)를 EVT-FIRE 로 채우고,
        //        EVT-RARE 는 뒷페이지에만 존재하도록 배정일을 과거로 민다.
        LsDataRaw rare = seedAssigned("OPT-RARE", "뒷페이지 영상", "EVT-RARE");
        forceAssignmentRegDt(rare.getRawSn(), LocalDateTime.of(2020, 1, 1, 0, 0));
        for (int i = 0; i < 24; i++) {
            LsDataRaw noise = seedAssigned("OPT-N-" + i, "앞페이지 영상 " + i, "EVT-FIRE");
            forceAssignmentRegDt(noise.getRawSn(), LocalDateTime.of(2026, 6, 1, 0, 0).plusMinutes(i));
        }

        assertThat(options(eventTypes(workerToken)))
                .as("첫 페이지에 없는 이벤트유형이 옵션에서 누락되면 뒷페이지 데이터에 도달할 수 없다")
                .containsExactly("EVT-FIRE", "EVT-RARE");
    }

    @Test
    @DisplayName("이벤트유형_옵션은_중복없이_오름차순이고_빈값은_제외된다")
    void optionsAreDistinctSortedAndNonBlank() throws Exception {
        seedAssigned("OPT-D1", "영상1", "EVT-FALL");
        seedAssigned("OPT-D2", "영상2", "EVT-FALL");
        seedAssigned("OPT-D3", "영상3", "EVT-FIRE");
        // 이벤트유형이 공백만인 영상 — 셀렉트박스에 빈 옵션이 새면 안 된다.
        seedAssigned("OPT-D4", "영상4", "   ");

        assertThat(options(eventTypes(workerToken))).containsExactly("EVT-FALL", "EVT-FIRE");
    }

    @Test
    @DisplayName("옵션_값은_trim_되어_목록_필터에_그대로_사용할_수_있다")
    void optionsAreTrimmedSoTheyMatchListFilter() throws Exception {
        LsDataRaw padded = seedAssigned("OPT-TRIM", "패딩 영상", " EVT-PAD ");

        assertThat(options(eventTypes(workerToken))).containsExactly("EVT-PAD");
        assertThat(listTotal(assignments(workerToken, "eventTypeCd", "EVT-PAD")))
                .as("옵션 값 그대로 필터했는데 0건이면 UI 로 도달할 수 없는 데이터가 된다")
                .isEqualTo(1);
        assertThat(padded.getRawSn()).isNotNull();
    }

    // ------------------------------------------------------- 옵션↔목록 정합 (위험 #6)

    /**
     * <b>옵션 값 ↔ 필터 입력의 정규화 축이 같아야 한다</b> — 왕복(옵션에서 고른 값을 그대로 필터로
     * 되돌려 보내기)이 데이터에 의존해 깨지면 안 된다.
     *
     * <p>관제 유래 코드에 제어문자(탭 등)가 섞여 들어오는 경우가 문제였다: 옵션은 SQL {@code trim}
     * (공백만 제거)으로 만들어지는데 필터 입력은 {@code AssignmentSearchCondition} 이 <b>제어문자까지
     * 제거</b>하므로, FE 가 받은 값을 그대로 재전송하면 서로 다른 문자열이 되어 <b>0건</b>이 된다.
     * 적재 시점 정제(마이그레이션/배치)는 이 범위 밖이므로 <b>조회 축을 일치</b>시켜 방어한다.
     *
     * <p>제어문자만으로 이뤄진 코드가 <b>빈 옵션</b>으로 새는 것도 같은 축의 문제다(공백만인 코드는
     * 이미 제외되지만 탭만인 코드는 SQL {@code trim} 을 통과한다).
     */
    @Test
    @DisplayName("제어문자가_포함된_이벤트유형도_옵션에서_고르면_목록이_0건이_아니다")
    void controlCharacterEventTypeRoundTripsToListFilter() throws Exception {
        seedAssigned("OPT-CTRL-EVT", "제어문자 영상", "\tEVT-FIRE");
        // 제어문자만인 코드 — 셀렉트박스에 '빈 것처럼 보이는' 옵션이 새면 안 된다.
        seedAssigned("OPT-CTRL-BLANK", "제어문자만 영상", "\t");

        List<String> opts = options(eventTypes(workerToken));

        assertThat(opts).containsExactly("EVT-FIRE");
        assertThat(listTotal(assignments(workerToken, "eventTypeCd", opts.get(0))))
                .as("옵션에서 고른 값으로 필터했는데 0건이면 UI 로 도달할 수 없는 데이터가 된다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("이벤트유형_옵션은_검색어_상태_필터를_반영한다")
    void optionsReflectSearchAndWorkStatusFilters() throws Exception {
        LsDataRaw target = seedAssigned("OPT-F-A", "강남구 테헤란로 CCTV", "EVT-FIRE");
        setWorkflow(target.getRawSn(), LsRawDataStatus.STTS_ASSIGNED);
        recordUserLabelSave(target.getRawSn());
        LsDataRaw other = seedAssigned("OPT-F-B", "동대문구 회기로 CCTV", "EVT-FALL");
        setWorkflow(other.getRawSn(), LsRawDataStatus.STTS_ASSIGNED);

        // 검색어 축
        assertThat(options(eventTypes(workerToken, "q", "테헤란로"))).containsExactly("EVT-FIRE");
        // 상태 축
        assertThat(options(eventTypes(workerToken, "workStatus", "IN_PROGRESS"))).containsExactly("EVT-FIRE");
        assertThat(options(eventTypes(workerToken, "workStatus", "PENDING"))).containsExactly("EVT-FALL");
        // 필터 없음 = 전체
        assertThat(options(eventTypes(workerToken))).containsExactly("EVT-FALL", "EVT-FIRE");
    }

    @Test
    @DisplayName("이벤트유형_옵션은_자기축_eventTypeCd_필터에는_영향받지_않는다")
    void optionsIgnoreOwnAxisFilter() throws Exception {
        seedAssigned("OPT-SELF-A", "영상 A", "EVT-FIRE");
        seedAssigned("OPT-SELF-B", "영상 B", "EVT-FALL");

        // 이미 EVT-FIRE 를 고른 상태로 옵션을 다시 조회해도 선택지가 사라지면 안 된다(되돌아갈 수 없다).
        assertThat(options(eventTypes(workerToken, "eventTypeCd", "EVT-FIRE")))
                .containsExactly("EVT-FALL", "EVT-FIRE");
    }

    @Test
    @DisplayName("옵션에서_고른_값으로_목록을_필터하면_0건이_아니다")
    void everyOptionYieldsNonEmptyList() throws Exception {
        LsDataRaw fire = seedAssigned("OPT-M-A", "강남구 테헤란로 CCTV", "EVT-FIRE");
        setWorkflow(fire.getRawSn(), LsRawDataStatus.STTS_ASSIGNED);
        recordUserLabelSave(fire.getRawSn());
        LsDataRaw fall = seedAssigned("OPT-M-B", "강남구 논현로 CCTV", "EVT-FALL");
        setWorkflow(fall.getRawSn(), LsRawDataStatus.STTS_ASSIGNED);
        recordUserLabelSave(fall.getRawSn());
        seedAssigned("OPT-M-C", "동대문구 회기로 CCTV", "EVT-THEFT");

        // 옵션 조회에 걸었던 필터를 그대로 유지한 채 목록을 조회한다(FE 동선과 동일).
        String[] filters = {"q", "강남구", "workStatus", "IN_PROGRESS"};
        List<String> opts = options(eventTypes(workerToken, filters));
        assertThat(opts).containsExactly("EVT-FALL", "EVT-FIRE");
        for (String opt : opts) {
            assertThat(listTotal(assignments(workerToken, "q", "강남구",
                    "workStatus", "IN_PROGRESS", "eventTypeCd", opt)))
                    .as("옵션 %s 로 필터한 목록이 0건", opt)
                    .isPositive();
        }
    }

    // -------------------------------------------------------------- R6 IDOR 방어

    @Test
    @DisplayName("WORKER_는_본인_배정의_이벤트유형만_본다")
    void workerSeesOnlyOwnEventTypes() throws Exception {
        seedAssigned("OPT-IDOR-MINE", "내 영상", "EVT-FIRE");
        seedAssigned("OPT-IDOR-OTHER", "남의 영상", "EVT-SECRET", OTHER_WORKER);

        assertThat(options(eventTypes(workerToken)))
                .as("옵션은 '그 값이 존재한다'는 사실 자체가 정보 누출이다")
                .containsExactly("EVT-FIRE");
    }

    @Test
    @DisplayName("WORKER_가_workerId_를_보내도_무시된다")
    void workerIdParamIgnoredForWorker() throws Exception {
        seedAssigned("OPT-WID-MINE", "내 영상", "EVT-FIRE");
        seedAssigned("OPT-WID-OTHER", "남의 영상", "EVT-SECRET", OTHER_WORKER);

        assertThat(options(eventTypes(workerToken, "workerId", String.valueOf(OTHER_WORKER))))
                .containsExactly("EVT-FIRE");
    }

    @Test
    @DisplayName("WORKER_는_어떤_필터조합으로도_타인_이벤트유형을_볼_수_없다")
    void workerCannotSeeOthersUnderAnyFilterCombination() throws Exception {
        LsDataRaw others = seedAssigned("OPT-COMBO-OTHER", "남의 영상 테헤란로", "EVT-SECRET", OTHER_WORKER);
        setWorkflow(others.getRawSn(), LsRawDataStatus.STTS_ASSIGNED);
        recordUserLabelSave(others.getRawSn());

        List<String[]> combos = List.of(
                new String[] {"workerId", String.valueOf(OTHER_WORKER)},
                new String[] {"workerId", String.valueOf(OTHER_WORKER), "q", "테헤란로"},
                new String[] {"workerId", String.valueOf(OTHER_WORKER), "workStatus", "IN_PROGRESS"},
                new String[] {"q", "테헤란로"},
                new String[] {"q", "작업자101"},
                new String[] {"workStatus", "IN_PROGRESS"},
                new String[] {"workStatus", "PENDING"});

        for (String[] combo : combos) {
            assertThat(options(eventTypes(workerToken, combo)))
                    .as("필터 조합 %s 에서 타인 이벤트유형 노출", String.join("&", combo))
                    .doesNotContain("EVT-SECRET");
        }
    }

    @Test
    @DisplayName("REVIEWER_는_workerId_로_특정_작업자의_이벤트유형만_조회한다")
    void reviewerCanFilterByWorkerId() throws Exception {
        seedAssigned("OPT-REV-MINE", "작업자100 영상", "EVT-FIRE");
        seedAssigned("OPT-REV-OTHER", "작업자101 영상", "EVT-FALL", OTHER_WORKER);

        assertThat(options(eventTypes(reviewerToken, "workerId", String.valueOf(OTHER_WORKER))))
                .containsExactly("EVT-FALL");
        assertThat(options(eventTypes(reviewerToken)))
                .containsExactly("EVT-FALL", "EVT-FIRE");
    }

    @Test
    @DisplayName("역할이_WORKER_REVIEWER_가_아니면_403")
    void otherRolesForbidden() throws Exception {
        seedAssigned("OPT-ROLE", "영상", "EVT-FIRE");

        String portalToken = JwtTestSupport.token(secret, "500", "PORTAL_USER", "PORTAL", issuer, 60);
        mockMvc.perform(eventTypes(portalToken)).andExpect(status().isForbidden());

        // 저작도구 역할이 배정되지 않은 INTERNAL 토큰도 통과할 수 없다(fail-closed).
        String roleless = JwtTestSupport.token(secret, "501", null, "INTERNAL", issuer, 60);
        mockMvc.perform(eventTypes(roleless)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("인증_토큰이_없으면_401")
    void anonymousRejected() throws Exception {
        mockMvc.perform(get("/v1/assignments/event-types")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 입력 검증

    @Test
    @DisplayName("과대_검색어와_잘못된_workStatus_workerId_는_400")
    void invalidInputsRejected() throws Exception {
        mockMvc.perform(eventTypes(workerToken, "q", "가".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
        mockMvc.perform(eventTypes(workerToken, "workStatus", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
        mockMvc.perform(eventTypes(reviewerToken, "workerId", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("빈값_공백만_보내면_필터_미적용_200")
    void blankParamsMeanNoFilter() throws Exception {
        seedAssigned("OPT-BLANK-A", "영상 A", "EVT-FIRE");
        seedAssigned("OPT-BLANK-B", "영상 B", "EVT-FALL");

        assertThat(options(eventTypes(workerToken, "q", "", "workStatus", "")))
                .containsExactly("EVT-FALL", "EVT-FIRE");
    }

    @Test
    @DisplayName("제어문자가_포함된_검색어는_500이_아니라_정상_처리된다")
    void controlCharactersSanitized() throws Exception {
        seedAssigned("OPT-CTRL", "강남구 테헤란로 CCTV", "EVT-FIRE");

        assertThat(options(eventTypes(workerToken, "q", "테헤란\0로"))).containsExactly("EVT-FIRE");
    }

    @Test
    @DisplayName("LIKE_와일드카드_문자를_검색어로_넣어도_이스케이프된다")
    void likeWildcardsAreEscaped() throws Exception {
        seedAssigned("OPT-LIKE-LIT", "A%B", "EVT-LITERAL");
        seedAssigned("OPT-LIKE-OTHER", "AXB", "EVT-OTHER");

        assertThat(options(eventTypes(workerToken, "q", "A%B"))).containsExactly("EVT-LITERAL");
        // 단독 '%' 로 전체를 긁을 수 없다.
        assertThat(options(eventTypes(workerToken, "q", "%"))).containsExactly("EVT-LITERAL");
    }

    // -------------------------------------------------------------- 응답 계약/상한

    @Test
    @DisplayName("응답_형태는_board_이벤트유형_옵션과_동일하다")
    void responseShapeMatchesBoardEndpoint() throws Exception {
        seedAssigned("OPT-SHAPE", "영상", "EVT-FIRE");

        mockMvc.perform(eventTypes(workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items[0]").value("EVT-FIRE"))
                .andExpect(jsonPath("$.data.truncated").value(false));
    }

    /**
     * 절단 분기(상한 초과) — 잘린 사실이 <b>응답에 드러나야</b> 한다.
     *
     * <p>{@code truncated=false} 경로만 검증하면 {@code MAX+1} 조회 · {@code subList(0, MAX)} 오프바이원
     * · 플래그 반전이 무증상으로 통과한다(자원 고갈 방어 OWASP API4 의 실행 증거 부재). 상한값은
     * 서비스 내부 상수라 외부로 노출되지 않으므로, REVIEWER 쪽 {@code TaskBoardSummaryTest} 와
     * <b>동형</b>으로 상한+1 종을 시드해 실측한다.
     */
    @Test
    @DisplayName("이벤트유형_옵션이_상한을_넘으면_잘라내고_truncated_로_알린다")
    void eventTypeOptionsSignalTruncationOverLimit() throws Exception {
        // given — 상한(AssignmentService.MAX_EVENT_TYPE_OPTIONS = 500) + 1 종의 이벤트유형 코드
        int limit = 500;
        List<LsDataRaw> videos = new ArrayList<>();
        for (int i = 0; i <= limit; i++) {
            LsDataRaw raw = LsDataRaw.createFromIngest(
                    String.format("CLIP-AOL-%03d", i), "CCTV-001", String.format("EVT-%03d", i), "11680",
                    LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/aol.mp4",
                    LocalDateTime.of(2026, 5, 1, 9, 0, 0), 30);
            raw.changeStatus("COMPLETED");
            videos.add(raw);
        }
        List<LsTaskAssignment> rows = new ArrayList<>();
        for (LsDataRaw saved : videoRepository.saveAll(videos)) {
            rows.add(LsTaskAssignment.createLabeler(saved.getRawSn(), WORKER, 1L));
        }
        authrtRepository.saveAll(rows);

        // when
        JsonNode data = data(eventTypes(workerToken));
        List<String> items = new ArrayList<>();
        data.path("items").forEach(n -> items.add(n.asText()));

        // then — 상한만큼만 오름차순으로 잘리고, 잘렸다는 사실이 응답에 드러난다
        assertThat(items).hasSize(limit);
        assertThat(data.path("truncated").asBoolean())
                .as("절단이 응답에 드러나지 않으면 사용자는 데이터가 없다고 오인한다")
                .isTrue();
        assertThat(items).first().isEqualTo("EVT-000");
        assertThat(items).last().isEqualTo(String.format("EVT-%03d", limit - 1));
        assertThat(items).doesNotContain(String.format("EVT-%03d", limit));
        // 잘린 코드로도 목록 필터는 정상 동작한다 = "데이터는 있는데 UI 로 도달 불가" 상황의 근거.
        assertThat(listTotal(assignments(workerToken, "eventTypeCd", String.format("EVT-%03d", limit))))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("배정이_없으면_빈_옵션_200")
    void emptyOptionsAreSafe() throws Exception {
        assertThat(options(eventTypes(workerToken))).isEmpty();
        mockMvc.perform(eventTypes(workerToken))
                .andExpect(jsonPath("$.data.truncated").value(false));
    }
}
