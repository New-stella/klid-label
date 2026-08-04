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
import kr.co.cudo.authoring.version.entity.LabelChangeKind;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 1 — 배정 목록({@code GET /v1/assignments}) 서버사이드 검색·필터·정렬 allowlist 검증.
 *
 * <p>검증 축:
 * <ul>
 *   <li><b>R1/R2</b> — 검색·상태·이벤트유형 필터가 <b>현재 페이지가 아니라 전체 기준</b>으로 적용되고
 *       {@code totalElements} 도 그 기준으로 계산된다.</li>
 *   <li><b>R5/R8 (HIGH-3)</b> — '작업중(IN_PROGRESS)' 판정 근거(<b>사용자 라벨 저장 이력</b>)가 WHERE 와
 *       응답 표시에서 동일하다. 즉 {@code workStatus=IN_PROGRESS} 로 걸러온 행은 응답 {@code status} 도
 *       IN_PROGRESS. 배치 오토라벨은 이 근거에 섞이지 않는다.</li>
 *   <li><b>R6 (HIGH-1 IDOR)</b> — WORKER 는 어떤 필터·정렬·{@code workerId} 조합으로도 타인 배정을
 *       볼 수 없다.</li>
 *   <li><b>R3</b> — 정렬 키 allowlist(strict, 미등록 키 400) + 개수 상한 + tie-break 페이지 안정성.</li>
 *   <li><b>CWE-89</b> — LIKE 와일드카드({@code % _ \})가 이스케이프되어 리터럴로 매칭된다.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AssignmentSearchFilterTest {

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

    /** 프레임 번호 발급기 — UK (RAW_SN, FRM_NO) 충돌 방지 (테스트 인스턴스는 메서드마다 새로 생성). */
    private final java.util.concurrent.atomic.AtomicLong frameSeq = new java.util.concurrent.atomic.AtomicLong();

    AssignmentSearchFilterTest(@Qualifier("controlDataSource") DataSource dataSource) {
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


    /** 표시명(cctvName)이 지정 문자열인 영상 1건을 만들고 WORKER 에게 배정한다. */
    private LsDataRaw seedAssignedVideoNamed(String key, String displayName) {
        String cctvId = "CCTV-" + key;
        seedCctv(cctvId, displayName);
        LsDataRaw video = seedVideo("CLIP-" + key, cctvId, "EVT-FIRE");
        assign(video.getRawSn(), WORKER);
        return video;
    }

    private LsTaskAssignment assign(Long rawSn, Long workerNo) {
        return authrtRepository.save(LsTaskAssignment.createLabeler(rawSn, workerNo, 1L));
    }

    private void setWorkflow(Long rawSn, String workflowStatus) {
        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        stts.transitionTo(workflowStatus);
        dataSttsRepository.save(stts);
    }

    /**
     * 프레임 1건을 만든다 (라벨/이력이 매달릴 자리).
     * 한 영상에 오토라벨 프레임 + 작업 프레임을 함께 둘 수 있어야 하므로 프레임 번호는 매번 증가시킨다
     * (UK {@code (RAW_SN, FRM_NO)}).
     */
    private Long seedFrame(Long rawSn) {
        long frameNo = frameSeq.getAndIncrement();
        return dataSrcRepository.save(LsDataSrc.create(
                        rawSn, frameNo, "/var/frames/" + rawSn + "-" + frameNo + ".jpg",
                        LocalDateTime.of(2026, 5, 1, 9, 0, 0)))
                .getSrcSn();
    }

    /**
     * 배치 파이프라인이 적재한 <b>오토라벨</b>({@code AUTO_LBL_YN='Y'}) 1건 — 저장 이력은 남기지 않는다.
     * 실제 배치 스텝(YOLO/SAM2/트랙보간)이 하는 일과 동일하다.
     */
    private void seedAutoLabel(Long rawSn) {
        dataLblRepository.save(LsDataLbl.createAutoBbox(
                seedFrame(rawSn), null, "person", "[[10,10],[20,20]]", new BigDecimal("0.90"), null));
    }

    /**
     * 작업자가 라벨을 <b>저장</b>한 상태를 만든다 — 라벨 + {@code LS_DATA_LBL_HSTRY} 저장 이벤트.
     * 저장 이벤트가 '작업중(IN_PROGRESS)' 판정의 단일 근거다.
     */
    private void recordUserLabelSave(Long rawSn) {
        Long srcSn = seedFrame(rawSn);
        LsDataLbl saved = dataLblRepository.save(LsDataLbl.createManual(
                srcSn, LsDataLbl.TYPE_BBOX, null, "person", "[[10,10],[20,20]]", WORKER));
        seedSaveEvent(srcSn, saved.getLblSn(), LabelChangeKind.ADDED);
    }

    /** 저장 이벤트 이력 1건 (사용자 저장 경로가 남기는 것과 동일 형태). */
    private void seedSaveEvent(Long srcSn, Long lblSn, LabelChangeKind kind) {
        LabelChange change = switch (kind) {
            case ADDED -> LabelChange.added(lblSn, "person", null);
            case UPDATED -> LabelChange.updated(lblSn, "person", null, null);
            case DELETED -> LabelChange.deleted(lblSn, "person", null);
        };
        labelHistoryRepository.save(
                LsDataLblHstry.recordSaveEvent(srcSn, String.valueOf(WORKER), List.of(change)));
    }

    private void forceAssignmentRegDt(Long rawSn, LocalDateTime regDt) {
        jdbc.update("UPDATE LS_TASK_ASSIGNMENT SET REG_DT = ? WHERE RAW_DATA_ID = ?",
                Timestamp.valueOf(regDt), rawSn);
    }

    // ---------------------------------------------------------------- requests

    private MockHttpServletRequestBuilder assignments(String token, String... params) {
        MockHttpServletRequestBuilder builder = get("/v1/assignments")
                .header("Authorization", "Bearer " + token);
        for (int i = 0; i + 1 < params.length; i += 2) {
            builder = builder.param(params[i], params[i + 1]);
        }
        return builder;
    }

    private JsonNode body(MockHttpServletRequestBuilder request) throws Exception {
        byte[] raw = mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        return new ObjectMapper().readTree(new String(raw, StandardCharsets.UTF_8)).path("data");
    }

    private List<Long> videoIds(MockHttpServletRequestBuilder request) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : body(request).path("content")) {
            ids.add(item.path("videoId").asLong());
        }
        return ids;
    }

    private long totalElements(MockHttpServletRequestBuilder request) throws Exception {
        return body(request).path("totalElements").asLong();
    }

    // ------------------------------------------------------------ R1/R2 서버 필터

    @Test
    @DisplayName("검색어로_필터하면_현재페이지가_아닌_전체에서_찾는다")
    void searchScansWholeDatasetNotCurrentPage() throws Exception {
        // given: 기본 정렬(regDt DESC) 기준 뒷페이지에만 존재하는 대상 1건 + 앞페이지를 채우는 24건
        LsDataRaw target = seedAssignedVideoNamed("TARGET", "강남구 테헤란로 CCTV");
        forceAssignmentRegDt(target.getRawSn(), LocalDateTime.of(2020, 1, 1, 0, 0));
        for (int i = 0; i < 24; i++) {
            LsDataRaw noise = seedAssignedVideoNamed("NOISE-" + i, "동대문구 회기로 CCTV " + i);
            forceAssignmentRegDt(noise.getRawSn(), LocalDateTime.of(2026, 6, 1, 0, 0).plusMinutes(i));
        }

        // when: 첫 페이지만 요청해도 뒷페이지 항목이 검색돼야 한다
        List<Long> ids = videoIds(assignments(workerToken, "q", "테헤란로", "page", "0", "size", "20"));

        // then
        assertThat(ids).containsExactly(target.getRawSn());
    }

    @Test
    @DisplayName("검색어_필터시_전체건수가_필터_적용_기준으로_반환된다")
    void totalElementsReflectsFilter() throws Exception {
        seedAssignedVideoNamed("TE-A", "강남구 테헤란로 CCTV");
        for (int i = 0; i < 5; i++) {
            seedAssignedVideoNamed("TE-N-" + i, "동대문구 회기로 CCTV " + i);
        }

        assertThat(totalElements(assignments(workerToken, "q", "테헤란로"))).isEqualTo(1);
        assertThat(totalElements(assignments(workerToken))).isEqualTo(6);
    }

    @Test
    @DisplayName("검색어는_작업자명도_매칭한다")
    void searchMatchesWorkerName() throws Exception {
        seedAssignedVideoNamed("WN-1", "동대문구 회기로 CCTV");

        // 시드 사용자 100 의 USER_NM = '작업자100'
        assertThat(totalElements(assignments(workerToken, "q", "작업자100"))).isEqualTo(1);
        assertThat(totalElements(assignments(workerToken, "q", "존재하지않는이름"))).isZero();
    }

    @Test
    @DisplayName("이벤트유형_필터가_적용된다")
    void eventTypeFilterApplies() throws Exception {
        seedCctv("CCTV-EVT-A", "이벤트 A");
        LsDataRaw fire = seedVideo("CLIP-EVT-A", "CCTV-EVT-A", "EVT-FIRE");
        assign(fire.getRawSn(), WORKER);
        seedCctv("CCTV-EVT-B", "이벤트 B");
        LsDataRaw fall = seedVideo("CLIP-EVT-B", "CCTV-EVT-B", "EVT-FALL");
        assign(fall.getRawSn(), WORKER);

        assertThat(videoIds(assignments(workerToken, "eventTypeCd", "EVT-FALL")))
                .containsExactly(fall.getRawSn());
        assertThat(totalElements(assignments(workerToken, "eventTypeCd", "EVT-FALL"))).isEqualTo(1);
    }

    @Test
    @DisplayName("필터결과_0건이면_빈페이지와_총건수0")
    void emptyResultIsSafe() throws Exception {
        seedAssignedVideoNamed("EMPTY-1", "동대문구 회기로 CCTV");

        assertThat(videoIds(assignments(workerToken, "q", "없는영상명"))).isEmpty();
        mockMvc.perform(assignments(workerToken, "q", "없는영상명"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.content.length()").value(0));
    }

    // ------------------------------------------------------- R5/R8 작업중 판정 일원화

    @Test
    @DisplayName("상태_IN_PROGRESS_는_라벨이_저장된_배정만_반환한다")
    void inProgressRequiresLabel() throws Exception {
        LsDataRaw withLabel = seedAssignedVideoNamed("IP-Y", "라벨 있는 영상");
        setWorkflow(withLabel.getRawSn(), LsRawDataStatus.STTS_ASSIGNED);
        recordUserLabelSave(withLabel.getRawSn());
        LsDataRaw withoutLabel = seedAssignedVideoNamed("IP-N", "라벨 없는 영상");
        setWorkflow(withoutLabel.getRawSn(), LsRawDataStatus.STTS_ASSIGNED);

        assertThat(videoIds(assignments(workerToken, "workStatus", "IN_PROGRESS")))
                .containsExactly(withLabel.getRawSn());
        assertThat(totalElements(assignments(workerToken, "workStatus", "IN_PROGRESS"))).isEqualTo(1);
    }

    @Test
    @DisplayName("상태_PENDING_은_라벨이_없는_배정만_반환한다")
    void pendingExcludesLabelled() throws Exception {
        LsDataRaw withLabel = seedAssignedVideoNamed("PD-Y", "라벨 있는 영상");
        setWorkflow(withLabel.getRawSn(), LsRawDataStatus.STTS_ASSIGNED);
        recordUserLabelSave(withLabel.getRawSn());
        LsDataRaw withoutLabel = seedAssignedVideoNamed("PD-N", "라벨 없는 영상");
        setWorkflow(withoutLabel.getRawSn(), LsRawDataStatus.STTS_ASSIGNED);
        // 상태 row 자체가 없는 레거시 배정도 PENDING 으로 떨어진다(응답 status 폴백과 동치).
        LsDataRaw noStatusRow = seedAssignedVideoNamed("PD-NULL", "상태행 없는 영상");

        assertThat(videoIds(assignments(workerToken, "workStatus", "PENDING")))
                .containsExactlyInAnyOrder(withoutLabel.getRawSn(), noStatusRow.getRawSn());
    }

    /**
     * 적대검증 HIGH — 배치 파이프라인(YOLO/SAM2/트랙보간)은 배정 <b>이전에</b> 오토라벨을 적재하고
     * 배정 대상은 배치가 끝난 영상이다. 라벨 존재로 '작업중'을 판정하면 배정 직후 전 영상이 '작업중'이
     * 되고 '대기' 에는 "AI 가 아무것도 못 찾은 영상" 만 남는다.
     */
    @Test
    @DisplayName("오토라벨만_있는_배정은_대기로_분류된다")
    void autoLabelAloneDoesNotMeanInProgress() throws Exception {
        LsDataRaw autoOnly = seedAssignedVideoNamed("AUTO-ONLY", "오토라벨만 있는 영상");
        setWorkflow(autoOnly.getRawSn(), LsRawDataStatus.STTS_ASSIGNED);
        seedAutoLabel(autoOnly.getRawSn());

        // 필터
        assertThat(videoIds(assignments(workerToken, "workStatus", "PENDING")))
                .containsExactly(autoOnly.getRawSn());
        assertThat(videoIds(assignments(workerToken, "workStatus", "IN_PROGRESS"))).isEmpty();
        // 표시 (R8 — 필터와 같은 근거)
        JsonNode content = body(assignments(workerToken)).path("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).path("status").asText()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("작업자가_라벨을_저장하면_작업중으로_바뀐다")
    void userSaveTurnsAssignmentIntoInProgress() throws Exception {
        LsDataRaw video = seedAssignedVideoNamed("SAVE-1", "작업 착수 영상");
        setWorkflow(video.getRawSn(), LsRawDataStatus.STTS_ASSIGNED);
        seedAutoLabel(video.getRawSn());

        // 저장 전 — 대기
        assertThat(videoIds(assignments(workerToken, "workStatus", "IN_PROGRESS"))).isEmpty();

        // 저장 후 — 작업중
        recordUserLabelSave(video.getRawSn());
        assertThat(videoIds(assignments(workerToken, "workStatus", "IN_PROGRESS")))
                .containsExactly(video.getRawSn());
        assertThat(videoIds(assignments(workerToken, "workStatus", "PENDING"))).isEmpty();
    }

    /**
     * {@code LabelService} 는 기존 라벨 UPDATE 시 {@code AUTO_LBL_YN} 을 유지한다(오토라벨을 고쳐도
     * 'Y' 그대로). 따라서 {@code AUTO_LBL_YN <> 'Y'} 로 판정하면 실제 작업자가 '대기' 로 남는다 —
     * 판정 축이 라벨 플래그가 아니라 <b>저장 이력</b>이어야 하는 이유.
     */
    @Test
    @DisplayName("오토라벨을_수정만_해도_작업중이다")
    void editingAutoLabelCountsAsWork() throws Exception {
        LsDataRaw video = seedAssignedVideoNamed("AUTO-EDIT", "오토라벨 수정 영상");
        setWorkflow(video.getRawSn(), LsRawDataStatus.STTS_ASSIGNED);

        // 오토라벨(AUTO_LBL_YN='Y' 유지) 을 수정한 저장 이벤트만 존재 — 수동 라벨은 1건도 없다.
        Long srcSn = seedFrame(video.getRawSn());
        LsDataLbl auto = dataLblRepository.save(LsDataLbl.createAutoBbox(
                srcSn, null, "person", "[[10,10],[20,20]]", new BigDecimal("0.90"), null));
        seedSaveEvent(srcSn, auto.getLblSn(), LabelChangeKind.UPDATED);

        assertThat(videoIds(assignments(workerToken, "workStatus", "IN_PROGRESS")))
                .containsExactly(video.getRawSn());
        JsonNode content = body(assignments(workerToken)).path("content");
        assertThat(content.get(0).path("status").asText()).isEqualTo("IN_PROGRESS");
    }

    /**
     * 같은 이력 테이블에 라벨 델타 0건인 감사 이벤트(개인정보 메타 리셋)가 <b>과거에 적재돼 있다</b>
     * (2026-08-04 리셋 폐기로 신규 발생은 없지만 기존 행은 남는다). 이를 작업 착수로 세면 그 영상의
     * 전 프레임이 '작업중' 으로 뒤집히므로, 이 픽스처는 <b>과거 행 판독</b> 시나리오를 고정한다.
     */
    @Test
    @DisplayName("개인정보_메타_리셋_이력은_작업중으로_치지_않는다")
    void privacyMetaResetHistoryIsNotWork() throws Exception {
        LsDataRaw video = seedAssignedVideoNamed("PRIV-RESET", "개인정보 리셋 영상");
        setWorkflow(video.getRawSn(), LsRawDataStatus.STTS_ASSIGNED);
        Long srcSn = seedFrame(video.getRawSn());
        labelHistoryRepository.save(LsDataLblHstry.recordPrivacyMetaResetEvent(
                srcSn, String.valueOf(WORKER), 77L));

        assertThat(videoIds(assignments(workerToken, "workStatus", "IN_PROGRESS"))).isEmpty();
        assertThat(videoIds(assignments(workerToken, "workStatus", "PENDING")))
                .containsExactly(video.getRawSn());
    }

    @Test
    @DisplayName("IN_PROGRESS_로_조회한_행은_응답_status_도_IN_PROGRESS_다")
    void inProgressFilterAndDisplayAgree() throws Exception {
        LsDataRaw withLabel = seedAssignedVideoNamed("AGREE-1", "라벨 있는 영상");
        setWorkflow(withLabel.getRawSn(), LsRawDataStatus.STTS_ASSIGNED);
        recordUserLabelSave(withLabel.getRawSn());

        JsonNode content = body(assignments(workerToken, "workStatus", "IN_PROGRESS")).path("content");
        assertThat(content).isNotEmpty();
        for (JsonNode item : content) {
            assertThat(item.path("status").asText()).isEqualTo("IN_PROGRESS");
        }
    }

    @Test
    @DisplayName("PENDING_으로_조회한_행은_응답_status_도_PENDING_이다")
    void pendingFilterAndDisplayAgree() throws Exception {
        LsDataRaw withoutLabel = seedAssignedVideoNamed("AGREE-2", "라벨 없는 영상");
        setWorkflow(withoutLabel.getRawSn(), LsRawDataStatus.STTS_ASSIGNED);

        JsonNode content = body(assignments(workerToken, "workStatus", "PENDING")).path("content");
        assertThat(content).isNotEmpty();
        for (JsonNode item : content) {
            assertThat(item.path("status").asText()).isEqualTo("PENDING");
        }
    }

    @Test
    @DisplayName("REVIEW_PENDING_COMPLETED_REJECTED_필터는_라벨존재와_무관하다")
    void reviewStagesIgnoreLabelPresence() throws Exception {
        record Case(String feStatus, String beStatus) { }
        List<Case> cases = List.of(
                new Case("REVIEW_PENDING", LsRawDataStatus.STTS_PENDING),
                new Case("COMPLETED", LsRawDataStatus.STTS_APPROVED),
                new Case("REJECTED", LsRawDataStatus.STTS_REJECTED));

        for (Case c : cases) {
            LsDataRaw labelled = seedAssignedVideoNamed(c.feStatus() + "-Y", c.feStatus() + " 라벨있음");
            setWorkflow(labelled.getRawSn(), c.beStatus());
            recordUserLabelSave(labelled.getRawSn());
            LsDataRaw bare = seedAssignedVideoNamed(c.feStatus() + "-N", c.feStatus() + " 라벨없음");
            setWorkflow(bare.getRawSn(), c.beStatus());

            assertThat(videoIds(assignments(workerToken, "workStatus", c.feStatus())))
                    .as("%s 는 라벨 존재와 무관하게 두 건 모두 반환", c.feStatus())
                    .containsExactlyInAnyOrder(labelled.getRawSn(), bare.getRawSn());
        }
    }

    @Test
    @DisplayName("검수단계_행은_라벨이_있어도_응답_status_가_IN_PROGRESS_로_바뀌지_않는다")
    void reviewStageDisplayNotOverriddenByLabel() throws Exception {
        LsDataRaw approved = seedAssignedVideoNamed("DISP-APPROVED", "승인 영상");
        setWorkflow(approved.getRawSn(), LsRawDataStatus.STTS_APPROVED);
        recordUserLabelSave(approved.getRawSn());

        JsonNode content = body(assignments(workerToken, "workStatus", "COMPLETED")).path("content");
        assertThat(content).isNotEmpty();
        for (JsonNode item : content) {
            assertThat(item.path("status").asText()).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("workStatus_5분기는_전체_목록을_빠짐없이_겹치지_않게_나눈다")
    void workStatusFiltersPartitionTheWholeList() throws Exception {
        // given: 5개 분기에 각각 떨어지는 배정 + 상태 row 가 없는 레거시 배정
        LsDataRaw pending = seedAssignedVideoNamed("PART-PENDING", "대기 영상");
        setWorkflow(pending.getRawSn(), LsRawDataStatus.STTS_ASSIGNED);
        LsDataRaw inProgress = seedAssignedVideoNamed("PART-INPROGRESS", "작업중 영상");
        setWorkflow(inProgress.getRawSn(), LsRawDataStatus.STTS_ASSIGNED);
        recordUserLabelSave(inProgress.getRawSn());
        LsDataRaw reviewPending = seedAssignedVideoNamed("PART-REVIEW", "검수대기 영상");
        setWorkflow(reviewPending.getRawSn(), LsRawDataStatus.STTS_IN_REVIEW);
        LsDataRaw completed = seedAssignedVideoNamed("PART-DONE", "완료 영상");
        setWorkflow(completed.getRawSn(), LsRawDataStatus.STTS_APPROVED);
        LsDataRaw rejected = seedAssignedVideoNamed("PART-REJECTED", "반려 영상");
        setWorkflow(rejected.getRawSn(), LsRawDataStatus.STTS_REJECTED);
        LsDataRaw legacy = seedAssignedVideoNamed("PART-LEGACY", "상태행 없는 영상");

        List<Long> all = videoIds(assignments(workerToken, "size", "50"));
        assertThat(all).hasSize(6);

        // when: 5분기를 각각 조회해 합치면 전체와 정확히 일치해야 한다(누락·중복 없음)
        List<Long> union = new ArrayList<>();
        for (String ws : List.of("PENDING", "IN_PROGRESS", "REVIEW_PENDING", "COMPLETED", "REJECTED")) {
            union.addAll(videoIds(assignments(workerToken, "workStatus", ws, "size", "50")));
        }

        assertThat(union).as("한 행이 두 분기에 중복 등장").doesNotHaveDuplicates();
        assertThat(union).as("어떤 분기에도 속하지 않는 행 존재").containsExactlyInAnyOrderElementsOf(all);
        assertThat(union).contains(pending.getRawSn(), inProgress.getRawSn(), reviewPending.getRawSn(),
                completed.getRawSn(), rejected.getRawSn(), legacy.getRawSn());
    }

    // -------------------------------------------------------------- R6 IDOR 방어

    @Test
    @DisplayName("WORKER_는_workerId_를_보내도_본인_배정만_조회된다")
    void workerIdParamIgnoredForWorker() throws Exception {
        LsDataRaw mine = seedAssignedVideoNamed("IDOR-MINE", "내 영상");
        seedCctv("CCTV-IDOR-OTHER", "남의 영상");
        LsDataRaw others = seedVideo("CLIP-IDOR-OTHER", "CCTV-IDOR-OTHER", "EVT-FIRE");
        assign(others.getRawSn(), OTHER_WORKER);

        assertThat(videoIds(assignments(workerToken, "workerId", String.valueOf(OTHER_WORKER))))
                .containsExactly(mine.getRawSn());
        assertThat(totalElements(assignments(workerToken, "workerId", String.valueOf(OTHER_WORKER))))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("WORKER_는_어떤_필터조합으로도_타인_배정을_볼_수_없다")
    void workerCannotSeeOthersUnderAnyFilterCombination() throws Exception {
        seedCctv("CCTV-COMBO-OTHER", "남의 영상 테헤란로");
        LsDataRaw others = seedVideo("CLIP-COMBO-OTHER", "CCTV-COMBO-OTHER", "EVT-FIRE");
        assign(others.getRawSn(), OTHER_WORKER);
        setWorkflow(others.getRawSn(), LsRawDataStatus.STTS_ASSIGNED);
        recordUserLabelSave(others.getRawSn());

        List<String[]> combos = List.of(
                new String[] {"workerId", String.valueOf(OTHER_WORKER)},
                new String[] {"workerId", String.valueOf(OTHER_WORKER), "q", "테헤란로"},
                new String[] {"workerId", String.valueOf(OTHER_WORKER), "workStatus", "IN_PROGRESS"},
                new String[] {"workerId", String.valueOf(OTHER_WORKER), "workStatus", "PENDING"},
                new String[] {"workerId", String.valueOf(OTHER_WORKER), "eventTypeCd", "EVT-FIRE"},
                new String[] {"workerId", String.valueOf(OTHER_WORKER), "sort", "regDt,asc"},
                new String[] {"q", "테헤란로"},
                new String[] {"q", "작업자101"},
                new String[] {"eventTypeCd", "EVT-FIRE"},
                new String[] {"workStatus", "IN_PROGRESS"});

        for (String[] combo : combos) {
            assertThat(videoIds(assignments(workerToken, combo)))
                    .as("필터 조합 %s 에서 타인 배정 노출", String.join("&", combo))
                    .doesNotContain(others.getRawSn());
            assertThat(totalElements(assignments(workerToken, combo)))
                    .as("필터 조합 %s 의 총건수에 타인 배정 포함", String.join("&", combo))
                    .isZero();
        }
    }

    @Test
    @DisplayName("REVIEWER_는_workerId_로_특정_작업자_배정을_조회한다")
    void reviewerCanFilterByWorkerId() throws Exception {
        LsDataRaw mine = seedAssignedVideoNamed("REV-MINE", "작업자100 영상");
        seedCctv("CCTV-REV-OTHER", "작업자101 영상");
        LsDataRaw others = seedVideo("CLIP-REV-OTHER", "CCTV-REV-OTHER", "EVT-FIRE");
        assign(others.getRawSn(), OTHER_WORKER);

        assertThat(videoIds(assignments(reviewerToken, "workerId", String.valueOf(OTHER_WORKER))))
                .containsExactly(others.getRawSn());
        assertThat(videoIds(assignments(reviewerToken)))
                .containsExactlyInAnyOrder(mine.getRawSn(), others.getRawSn());
    }

    // ------------------------------------------------------------- R3 정렬 allowlist

    @Test
    @DisplayName("미등록_정렬키는_400")
    void unknownSortKeyRejected() throws Exception {
        seedAssignedVideoNamed("SORT-BAD", "정렬 영상");

        mockMvc.perform(assignments(workerToken, "sort", "taskTypeCd,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"))
                // CWE-209 — 입력값/내부 필드명을 반사하지 않는다.
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("taskTypeCd"))));
    }

    @Test
    @DisplayName("정렬키_개수가_상한을_넘으면_400")
    void tooManySortOrdersRejected() throws Exception {
        seedAssignedVideoNamed("SORT-MANY", "정렬 영상");

        MockHttpServletRequestBuilder request = get("/v1/assignments")
                .header("Authorization", "Bearer " + workerToken);
        String[] keys = {"regDt", "videoId", "id"};
        for (int i = 0; i < 30; i++) {
            request = request.param("sort", keys[i % keys.length] + (i % 2 == 0 ? ",asc" : ",desc"));
        }

        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("정렬 기준이 너무 많습니다."));
    }

    @Test
    @DisplayName("미등록_workStatus_값은_400")
    void unknownWorkStatusRejected() throws Exception {
        seedAssignedVideoNamed("WS-BAD", "상태 영상");

        mockMvc.perform(assignments(workerToken, "workStatus", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("빈_workStatus_는_필터_미적용_200")
    void blankWorkStatusMeansNoFilter() throws Exception {
        seedAssignedVideoNamed("WS-BLANK", "상태 영상");

        assertThat(totalElements(assignments(workerToken, "workStatus", ""))).isEqualTo(1);
    }

    @Test
    @DisplayName("기존_기본정렬_regDt_desc_는_200_이고_결과_순서가_변경_전과_같다")
    void defaultSortUnchanged() throws Exception {
        LsDataRaw first = seedAssignedVideoNamed("DEF-1", "첫 영상");
        LsDataRaw second = seedAssignedVideoNamed("DEF-2", "둘째 영상");
        LsDataRaw third = seedAssignedVideoNamed("DEF-3", "셋째 영상");
        forceAssignmentRegDt(first.getRawSn(), LocalDateTime.of(2026, 6, 1, 0, 0));
        forceAssignmentRegDt(second.getRawSn(), LocalDateTime.of(2026, 6, 2, 0, 0));
        forceAssignmentRegDt(third.getRawSn(), LocalDateTime.of(2026, 6, 3, 0, 0));

        // 파라미터 없는 기존 호출 — 기본 정렬(regDt DESC)·페이징 메타 불변
        assertThat(videoIds(assignments(workerToken)))
                .containsExactly(third.getRawSn(), second.getRawSn(), first.getRawSn());
        mockMvc.perform(assignments(workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.number").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(3));

        // 명시 정렬(regDt,asc)도 기존과 동일하게 동작
        assertThat(videoIds(assignments(workerToken, "sort", "regDt,asc")))
                .containsExactly(first.getRawSn(), second.getRawSn(), third.getRawSn());
    }

    @Test
    @DisplayName("정렬값이_같아도_페이지간_중복_누락이_없다")
    void pageBoundaryStableWithIdenticalRegDt() throws Exception {
        LocalDateTime same = LocalDateTime.of(2026, 6, 1, 12, 0);
        Set<Long> expected = new LinkedHashSet<>();
        for (int i = 0; i < 5; i++) {
            LsDataRaw v = seedAssignedVideoNamed("TIE-" + i, "동시각 영상 " + i);
            forceAssignmentRegDt(v.getRawSn(), same);
            expected.add(v.getRawSn());
        }

        List<Long> all = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            all.addAll(videoIds(assignments(workerToken, "page", String.valueOf(page), "size", "2")));
        }

        assertThat(all).as("중복 없음").doesNotHaveDuplicates();
        assertThat(all).as("누락 없음").containsExactlyInAnyOrderElementsOf(expected);
    }

    // ------------------------------------------------------------- CWE-89 LIKE 방어

    @Test
    @DisplayName("LIKE_와일드카드_문자를_검색어로_넣어도_이스케이프된다")
    void likeWildcardsAreEscaped() throws Exception {
        LsDataRaw literal = seedAssignedVideoNamed("LIKE-LIT", "A%B");
        seedAssignedVideoNamed("LIKE-OTHER", "AXB");
        seedAssignedVideoNamed("LIKE-UNDER", "C_D");
        seedAssignedVideoNamed("LIKE-UNDER2", "CZD");
        seedAssignedVideoNamed("LIKE-BS", "E\\F");

        // '%' 는 와일드카드가 아니라 리터럴로 매칭
        assertThat(videoIds(assignments(workerToken, "q", "A%B"))).containsExactly(literal.getRawSn());
        // '_' 도 리터럴 — CZD 는 매칭되지 않는다
        assertThat(videoIds(assignments(workerToken, "q", "C_D"))).hasSize(1);
        // 백슬래시가 이스케이프 문자로 오해되어 쿼리가 깨지지 않는다
        assertThat(videoIds(assignments(workerToken, "q", "E\\F"))).hasSize(1);
        // 단독 '%' 로 전체를 긁을 수 없다 — 리터럴 '%' 를 포함한 이름(A%B) 하나만 매칭된다
        assertThat(videoIds(assignments(workerToken, "q", "%"))).containsExactly(literal.getRawSn());
    }

    @Test
    @DisplayName("과대_검색어는_400")
    void oversizedSearchKeywordRejected() throws Exception {
        mockMvc.perform(assignments(workerToken, "q", "가".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    // ------------------------------------------------------------ 제어문자 (A10:2025)

    /**
     * {@code trim()} 은 양끝만 자르므로 문자열 <b>중간</b>의 {@code U+0000} 이 그대로 바인딩되고,
     * PgJDBC 가 {@code Zero bytes may not occur in string parameters} 로 거부해 500 + 스택트레이스가
     * 매 요청 로그에 쌓인다(인증 사용자가 반복 호출 가능).
     */
    @Test
    @DisplayName("제어문자가_포함된_검색어는_500이_아니라_정상_처리된다")
    void controlCharactersInKeywordAreSanitized() throws Exception {
        LsDataRaw target = seedAssignedVideoNamed("CTRL-Q", "강남구 테헤란로 CCTV");

        // 중간 NUL — 제거 후 매칭되어야 한다.
        assertThat(videoIds(assignments(workerToken, "q", "테헤란\0로")))
                .containsExactly(target.getRawSn());
        // 개행/탭 등 다른 제어문자도 동일 처리.
        assertThat(videoIds(assignments(workerToken, "q", "테헤란\n\t로")))
                .containsExactly(target.getRawSn());
        // 제어문자만 = 빈 입력과 동일하게 '필터 미적용'.
        assertThat(totalElements(assignments(workerToken, "q", "\0"))).isEqualTo(1);
    }

    @Test
    @DisplayName("제어문자가_포함된_이벤트유형코드는_500이_아니라_정상_처리된다")
    void controlCharactersInEventTypeAreSanitized() throws Exception {
        LsDataRaw target = seedAssignedVideoNamed("CTRL-EVT", "이벤트 영상");

        assertThat(videoIds(assignments(workerToken, "eventTypeCd", "EVT-\0FIRE")))
                .containsExactly(target.getRawSn());
        assertThat(totalElements(assignments(workerToken, "eventTypeCd", "\0"))).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 경계값

    @Test
    @DisplayName("workerId_는_0과_음수를_400으로_거부한다")
    void workerIdMustBePositive() throws Exception {
        for (String bad : List.of("0", "-1")) {
            mockMvc.perform(assignments(reviewerToken, "workerId", bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
        }
    }

    @Test
    @DisplayName("길이_경계값은_상한까지_허용하고_초과만_거부한다")
    void lengthBoundaries() throws Exception {
        seedAssignedVideoNamed("BOUND-1", "경계값 영상");

        // q — 100자 통과 / 101자 거부(위 과대 검색어 테스트와 쌍)
        mockMvc.perform(assignments(workerToken, "q", "가".repeat(100))).andExpect(status().isOk());
        // eventTypeCd — 20자 통과 / 21자 거부
        mockMvc.perform(assignments(workerToken, "eventTypeCd", "E".repeat(20)))
                .andExpect(status().isOk());
        mockMvc.perform(assignments(workerToken, "eventTypeCd", "E".repeat(21)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("정렬_개수_상한_경계는_3개_허용_4개_거부")
    void sortCountBoundary() throws Exception {
        seedAssignedVideoNamed("BOUND-SORT", "정렬 경계 영상");

        MockHttpServletRequestBuilder atLimit = get("/v1/assignments")
                .header("Authorization", "Bearer " + workerToken)
                .param("sort", "regDt,desc").param("sort", "videoId,asc").param("sort", "id,desc");
        mockMvc.perform(atLimit).andExpect(status().isOk());

        // 서로 다른 키 3개가 상한이므로, 중복 키를 얹어 4개째가 되면 거부된다.
        MockHttpServletRequestBuilder overLimit = get("/v1/assignments")
                .header("Authorization", "Bearer " + workerToken)
                .param("sort", "regDt,desc").param("sort", "videoId,asc")
                .param("sort", "id,desc").param("sort", "regDt,asc");
        mockMvc.perform(overLimit)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }
}
