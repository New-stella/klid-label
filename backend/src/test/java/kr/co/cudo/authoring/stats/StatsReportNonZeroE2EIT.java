package kr.co.cudo.authoring.stats;

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
import kr.co.cudo.authoring.eventtype.service.EventTypeCacheEvictor;
import kr.co.cudo.authoring.user.repository.UserRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API-058 리포트 CSV 의 <b>비제로 end-to-end</b> 회귀 가드 —
 * 실제 DB 집계 → DTO → CSV 구간이 이어져 흐르는지 고정한다.
 *
 * <h3>왜 이 시험이 따로 필요한가 (Critical)</h3>
 * <p>기존 커버리지는 두 토막이었다 — ①DB→DTO(집계 IT 들) ②DTO→CSV({@code
 * OverallStatReportCsvWriterTest}, 손으로 만든 DTO). <b>그 둘을 잇는 단언이 없었다.</b>
 * 컨트롤러 통합시험은 {@code test-data-stats-clean.sql} 로 비운 환경에서 돌아 수치가 대부분
 * 0 이라, <b>컬럼이 밀리거나 축이 뒤바뀌어도 초록</b>이었다(0 뿐인 표는 자리만 맞으면 통과한다).
 *
 * <p>그래서 여기서는 값을 <b>전부 다르게</b> 심는다. 처리현황 5값, 작업자 행의 라벨/진행/검수
 * 세 숫자, 두 비율이 서로 달라야 한 칸만 밀려도 실패한다.
 *
 * <h3>두 번째 목적 — DB 를 통과한 실제 문자열의 CSV 안전성</h3>
 * <p>단위테스트는 손으로 만든 DTO 라 <b>DB 컬럼을 통과한 값</b>이 어떻게 흐르는지 못 본다.
 * {@code USER_NM} 은 {@code varchar(100)} 이라 수식 문자와 쉼표가 그대로 저장되므로, 작업자
 * 이름을 각각 그렇게 심어 ①수식 무해화(CWE-1236) ②쉼표 값의 칸 밀림 없음(RFC 4180)을 본다.
 * 쉼표는 <b>문자열 포함 검사로는 밀림을 못 잡으므로</b> 그 행의 컬럼 수를 세어 단언한다.
 *
 * <h3>격리 전략</h3>
 * <p>{@code test-data-stats-clean.sql} 로 통계 대상 테이블을 비워 <b>절대값 단언</b>을 가능하게 하고,
 * 이벤트유형은 이 클래스 전용 코드('95' 대분류)를 심어 공유 시드의 행 구성 변화에 기대값이
 * 종속되지 않게 한다({@code StatsEventTypeGroupDistributionIT} 와 같은 방식).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = "/db/test-data-stats-clean.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class StatsReportNonZeroE2EIT {

    // ---------------------------------------------------------------- 시드 상수

    /** 이 클래스 전용 이벤트유형 — 표시명이 서로 달라 그룹이 접히지 않는다. */
    private static final String TYPE_FIRE = "EV95000101";
    private static final String LABEL_FIRE = "화재-리포트IT";
    private static final String TYPE_FALL = "EV95000201";
    private static final String LABEL_FALL = "쓰러짐-리포트IT";
    /** 영상 0건 유형 — 분포에 0 으로 함께 나와야 한다(빠지면 필터로 도달 불가해진다). */
    private static final String TYPE_EMPTY = "EV95000301";
    private static final String LABEL_EMPTY = "무데이터-리포트IT";

    private static final List<String> SEEDED_TYPE_CODES = List.of(TYPE_FIRE, TYPE_FALL, TYPE_EMPTY);

    /** ★수식 인젝션 원본(CWE-1236) — CSV 에서 작은따옴표로 무해화돼야 한다. */
    private static final long W1_NO = 951_000_001L;
    private static final String W1_NAME = "=1+1 작업자";
    /** ★쉼표 포함 — RFC 4180 으로 감싸지지 않으면 칸이 하나 밀린다. */
    private static final long W2_NO = 951_000_002L;
    private static final String W2_NAME = "홍길동, 팀장";

    /**
     * 덤프 경로를 <b>줄 때만</b> 실물 CSV 를 파일로 남긴다 — 평시 실행은 파일을 만들지 않는다
     * (저장소에 산출물을 남기지 않는다).
     *
     * <p>환경변수 폴백이 있는 이유: 이 저장소의 Gradle 테스트 워커는 데몬 환경을 물려받아
     * {@code -D} 가 워커까지 도달하지 않는다(build.gradle 이 경고하는 함정). 빌드 스크립트를
     * 고치지 않고 사람이 한 번 눈으로 볼 CSV 를 얻으려면 {@code --no-daemon} 실행 +
     * 환경변수 경로가 필요하다.
     */
    private static final String DUMP_PROPERTY = "stats.report.dump";
    private static final String DUMP_ENV = "STATS_REPORT_DUMP";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsRawDataStatusRepository dataSttsRepository;
    @Autowired private LsTaskAssignmentRepository assignmentRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository lblRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EventTypeCacheEvictor eventTypeCacheEvictor;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    @Autowired
    @Qualifier("controlTransactionManager")
    private PlatformTransactionManager txManager;

    private final JdbcTemplate jdbc;

    StatsReportNonZeroE2EIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    private TransactionTemplate tx;
    private String reviewerToken;

    // ------------------------------------------------------------------- 시드

    @BeforeEach
    void seed() {
        tx = new TransactionTemplate(txManager);
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);

        seedEventType(TYPE_FIRE, LABEL_FIRE, "95", "0001");
        seedEventType(TYPE_FALL, LABEL_FALL, "95", "0002");
        seedEventType(TYPE_EMPTY, LABEL_EMPTY, "95", "0003");
        // 장수명(6h) 캐시라 방금 심은 마스터가 반영되려면 비워야 한다.
        eventTypeCacheEvictor.evictNow();

        tx.executeWithoutResult(s -> {
            userRepository.upsertUser(W1_NO, "stat-rpt-w1", W1_NAME);
            userRepository.upsertUser(W2_NO, "stat-rpt-w2", W2_NAME);
        });

        // ── 영상 20건. 상태 5값을 전부 다르게 심어 4구간 접기 실수가 드러나게 한다.
        //    APPROVED 5 / ASSIGNED 3 / IN_REVIEW 4 / PENDING 2 / REJECTED 6
        //    → 완료 5 · 처리중 3+4=7 · 대기 2 · 실패 6 (네 구간도 서로 다르다)
        //    화재 8건(승인 3) · 쓰러짐 12건(승인 2) — 검수완료와 전체를 다르게 만든다.

        // 화재 8건
        Long a1 = video(TYPE_FIRE, LsRawDataStatus.STTS_APPROVED, 2);
        Long a2 = video(TYPE_FIRE, LsRawDataStatus.STTS_APPROVED, 2);
        Long a3 = video(TYPE_FIRE, LsRawDataStatus.STTS_APPROVED, 2);
        Long f1 = video(TYPE_FIRE, LsRawDataStatus.STTS_REJECTED, 0);
        Long f2 = video(TYPE_FIRE, LsRawDataStatus.STTS_ASSIGNED, 1);
        Long f3 = video(TYPE_FIRE, LsRawDataStatus.STTS_IN_REVIEW, 0);
        Long f4 = video(TYPE_FIRE, LsRawDataStatus.STTS_IN_REVIEW, 0);
        video(TYPE_FIRE, LsRawDataStatus.STTS_PENDING, 0);

        // 쓰러짐 12건
        Long b1 = video(TYPE_FALL, LsRawDataStatus.STTS_APPROVED, 2);
        Long b2 = video(TYPE_FALL, LsRawDataStatus.STTS_APPROVED, 2);
        Long c1 = video(TYPE_FALL, LsRawDataStatus.STTS_IN_REVIEW, 0);
        Long c2 = video(TYPE_FALL, LsRawDataStatus.STTS_IN_REVIEW, 0);
        Long d1 = video(TYPE_FALL, LsRawDataStatus.STTS_REJECTED, 0);
        Long d2 = video(TYPE_FALL, LsRawDataStatus.STTS_REJECTED, 0);
        Long e1 = video(TYPE_FALL, LsRawDataStatus.STTS_PENDING, 0);
        video(TYPE_FALL, LsRawDataStatus.STTS_ASSIGNED, 1);
        video(TYPE_FALL, LsRawDataStatus.STTS_ASSIGNED, 1);
        video(TYPE_FALL, LsRawDataStatus.STTS_REJECTED, 0);
        video(TYPE_FALL, LsRawDataStatus.STTS_REJECTED, 0);
        video(TYPE_FALL, LsRawDataStatus.STTS_REJECTED, 0);

        // ── 작업자 배정. 한 행 안의 라벨/진행/검수가 서로 다른 값이 되게 고른다.
        //    W1: 라벨 4(승인3+반려1) · 진행 2(반려1+배정1) · 검수 3 · 승인율 75.0 → 반려율 25.0
        //    W2: 라벨 8(승인2+검수중4+반려2) · 진행 7 · 검수 1 · 승인율 50.0 → 반려율 50.0
        labeler(a1, W1_NO); labeler(a2, W1_NO); labeler(a3, W1_NO);
        labeler(f1, W1_NO); labeler(f2, W1_NO);
        reviewer(c1, W1_NO); reviewer(c2, W1_NO); reviewer(d1, W1_NO);

        labeler(b1, W2_NO); labeler(b2, W2_NO);
        labeler(f3, W2_NO); labeler(f4, W2_NO); labeler(c1, W2_NO); labeler(c2, W2_NO);
        labeler(d1, W2_NO); labeler(d2, W2_NO);
        labeler(e1, W2_NO);
        reviewer(a1, W2_NO);

        // ── 오토라벨 비율도 두 작업자가 다르게: W1 1/5 = 20.0% · W2 3/4 = 75.0%
        seedLabels(a1, 1, 4, W1_NO);
        seedLabels(b1, 3, 1, W2_NO);

        // ── 승인 일자를 여러 날에 흩어 「값 있는 날」과 「0 인 날」이 둘 다 나오게 한다.
        approvedOn(List.of(a1, a2), LocalDate.now().atTime(9, 0));
        approvedOn(List.of(a3, b1, b2), LocalDate.now().minusDays(5).atTime(10, 0));
    }

    @AfterEach
    void cleanUp() {
        // 자식 → 부모 순서. eventType 캐시는 컨텍스트 공유라 반드시 비운다.
        String rawFilter = " WHERE RAW_SN IN (SELECT RAW_SN FROM LS_DATA_RAW WHERE EVNT_TYPE_CD IN ('"
                + String.join("','", SEEDED_TYPE_CODES) + "'))";
        jdbc.update("DELETE FROM LS_DATA_LBL WHERE SRC_SN IN (SELECT SRC_SN FROM LS_DATA_SRC" + rawFilter + ")");
        jdbc.update("DELETE FROM LS_DATA_SRC" + rawFilter);
        jdbc.update("DELETE FROM LS_TASK_ALTMNT WHERE RAW_DATA_ID IN "
                + "(SELECT RAW_SN FROM LS_DATA_RAW WHERE EVNT_TYPE_CD IN ('"
                + String.join("','", SEEDED_TYPE_CODES) + "'))");
        jdbc.update("DELETE FROM LS_RAW_DATA_STATUS WHERE RAW_DATA_ID IN "
                + "(SELECT RAW_SN FROM LS_DATA_RAW WHERE EVNT_TYPE_CD IN ('"
                + String.join("','", SEEDED_TYPE_CODES) + "'))");
        for (String code : SEEDED_TYPE_CODES) {
            jdbc.update("DELETE FROM LS_DATA_RAW WHERE EVNT_TYPE_CD = ?", code);
            jdbc.update("DELETE FROM LS_EVNT_TYPE WHERE EVNT_TYPE_CD = ?", code);
        }
        eventTypeCacheEvictor.evictNow();
    }

    // --------------------------------------------------------------- 시드 헬퍼

    private void seedEventType(String code, String name, String clsfCd, String ctgryCd) {
        jdbc.update("INSERT INTO LS_EVNT_TYPE "
                        + "(EVNT_TYPE_CD, EVNT_NM, EVNT_CLSF_CD, EVNT_CTGRY_CD, CLCT_YN) "
                        + "VALUES (?, ?, ?, ?, 'Y') "
                        + "ON CONFLICT (EVNT_TYPE_CD) DO UPDATE "
                        + "SET EVNT_NM = EXCLUDED.EVNT_NM, EVNT_CLSF_CD = EXCLUDED.EVNT_CLSF_CD, "
                        + "    EVNT_CTGRY_CD = EXCLUDED.EVNT_CTGRY_CD, CLCT_YN = EXCLUDED.CLCT_YN",
                code, name, clsfCd, ctgryCd);
    }

    /** 영상 1건 + 상태행 + 프레임 n장. */
    private Long video(String evntTypeCd, String status, int frames) {
        String clipId = "STAT-RPT-" + evntTypeCd + "-" + java.util.UUID.randomUUID();
        LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                clipId, "CCTV-STAT-RPT", evntTypeCd, "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                LocalDateTime.of(2026, 5, 1, 9, 0, 0), 30));
        Long rawSn = raw.getRawSn();

        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        if (!LsRawDataStatus.STTS_PENDING.equals(status)) {
            stts.transitionTo(status);
        }
        dataSttsRepository.save(stts);

        for (int i = 0; i < frames; i++) {
            srcRepository.save(LsDataSrc.create(
                    rawSn, i, "/var/frames/" + rawSn + "/" + i + ".jpg",
                    LocalDateTime.of(2026, 5, 1, 9, 0, 0)));
        }
        return rawSn;
    }

    private void labeler(Long rawSn, long userNo) {
        assignmentRepository.save(LsTaskAssignment.createLabeler(rawSn, userNo, userNo));
    }

    /**
     * <b>옛</b> 검수자 배정 행을 재현한다 — 새로 만드는 경로는 없어졌지만(ADR-067) 이미 적재된 행이
     * 통계 집계를 흔들지 않는지는 계속 확인해야 하므로, 제거된 팩토리 대신 빌더로 직접 세운다.
     */
    private void reviewer(Long rawSn, long userNo) {
        assignmentRepository.save(LsTaskAssignment.builder()
                .userNo(userNo)
                .rawDataId(rawSn)
                .taskTypeCd(LsTaskAssignment.TASK_REVIEWER)
                .regUserNo(userNo)
                .regDt(LocalDateTime.now())
                .build());
    }

    /** 그 영상의 첫 프레임에 자동/수동 라벨을 붙인다 — autoLabelRate 분자/분모. */
    private void seedLabels(Long rawSn, int autoCount, int manualCount, long userNo) {
        Long srcSn = srcRepository.findAll().stream()
                .filter(s -> rawSn.equals(s.getRawSn()))
                .map(LsDataSrc::getSrcSn)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("프레임 시드 누락 rawSn=" + rawSn));
        for (int i = 0; i < autoCount; i++) {
            LsDataLbl lbl = lblRepository.save(LsDataLbl.createAutoBbox(
                    srcSn, null, "person", "[]", new BigDecimal("0.90"), null));
            lbl.applyAiSource(LsDataLbl.SRC_YOLO, new BigDecimal("0.90"));
            lblRepository.saveAndFlush(lbl);
        }
        for (int i = 0; i < manualCount; i++) {
            lblRepository.save(LsDataLbl.createManual(
                    srcSn, LsDataLbl.TYPE_BBOX, null, "person", "[]", String.valueOf(userNo)));
        }
    }

    /**
     * 승인 시각을 특정 일자로 되돌린다 — 일별 작업량은 {@code LS_RAW_DATA_STATUS.UPD_DT} 축이고
     * 상태 전이는 항상 현재 시각을 찍으므로, 여러 날에 흩으려면 직접 이동시켜야 한다.
     */
    private void approvedOn(List<Long> rawSns, LocalDateTime when) {
        for (Long rawSn : rawSns) {
            jdbc.update("UPDATE LS_RAW_DATA_STATUS SET UPD_DT = ? WHERE RAW_DATA_ID = ?", when, rawSn);
        }
    }

    // --------------------------------------------------------------- 조회 헬퍼

    private String fetchCsv(String period) throws Exception {
        String csv = mockMvc.perform(get("/v1/stats/report")
                        .param("period", period)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        dumpIfRequested(csv);
        return csv;
    }

    private JsonNode fetchOverall() throws Exception {
        String body = mockMvc.perform(get("/v1/stats/overall")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).path("data");
    }

    /** 사람이 눈으로 볼 실물 CSV — 경로를 시스템 속성으로 준 실행에서만 남긴다. */
    private static void dumpIfRequested(String csv) {
        String target = System.getProperty(DUMP_PROPERTY);
        if (target == null || target.isBlank()) {
            target = System.getenv(DUMP_ENV);
        }
        if (target == null || target.isBlank()) {
            return;
        }
        try {
            Files.writeString(Path.of(target), csv, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("CSV 덤프 실패 — " + e.getMessage(), e);
        }
    }

    private static List<String> lines(String csv) {
        return List.of(csv.split("\n", -1));
    }

    /** 블록 제목 다음 컬럼행을 건너뛴 데이터 행들(다음 빈 줄 전까지). */
    private static List<String> blockRows(String csv, String title) {
        List<String> all = lines(csv);
        int idx = all.indexOf(title);
        assertThat(idx).as("블록 %s 존재", title).isNotNegative();
        List<String> rows = new ArrayList<>();
        for (int i = idx + 2; i < all.size() && !all.get(i).isEmpty(); i++) {
            rows.add(all.get(i));
        }
        return rows;
    }

    /**
     * RFC 4180 최소 파서 — <b>칸 밀림 탐지용</b>. 쉼표가 든 값이 따옴표로 감싸지지 않으면
     * 여기서 컬럼 수가 하나 늘어난다(문자열 포함 검사로는 잡히지 않는 결함이다).
     */
    private static List<String> cells(String row) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < row.length() && row.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static String rowStartingWith(List<String> rows, String prefix) {
        return rows.stream().filter(r -> r.startsWith(prefix)).findFirst()
                .orElseThrow(() -> new AssertionError("행 없음 prefix=" + prefix + " rows=" + rows));
    }

    // ------------------------------------------------------------------- tests

    @Test
    @DisplayName("실DB_비제로_집계가_CSV_다섯_블록에_그대로_실린다")
    void nonZeroAggregationFlowsIntoCsv() throws Exception {
        String csv = fetchCsv("MONTH");
        JsonNode overall = fetchOverall();

        // ── ① 누적 학습데이터 — 검수완료(10/5)와 전체(13/20)가 서로 다른 값이어야
        //     축이 뒤바뀐 경우가 드러난다.
        assertThat(blockRows(csv, "[누적 학습데이터]")).containsExactly(
                "이미지(장),10,13",
                "영상(건),5,20");
        assertThat(overall.path("approvedImageCount").asLong()).isEqualTo(10L);
        assertThat(overall.path("cumulativeImageCount").asLong()).isEqualTo(13L);
        assertThat(overall.path("approvedVideoCount").asLong()).isEqualTo(5L);
        assertThat(overall.path("cumulativeVideoCount").asLong()).isEqualTo(20L);

        // ── ② 처리현황 — 네 구간이 모두 다른 값이고 처리중은 실제 합(3+4=7)이다.
        JsonNode p = overall.path("processing");
        assertThat(p.path("approved").asLong()).isEqualTo(5L);
        assertThat(p.path("inProgress").asLong()).isEqualTo(3L);
        assertThat(p.path("reviewPending").asLong()).isEqualTo(4L);
        assertThat(p.path("pending").asLong()).isEqualTo(2L);
        assertThat(p.path("rejected").asLong()).isEqualTo(6L);
        assertThat(blockRows(csv, "[처리현황]")).containsExactly(
                "완료,5", "처리중,7", "대기,2", "실패,6");
    }

    @Test
    @DisplayName("일별_작업량은_값있는날과_0인날이_함께_나오고_합계가_승인건수와_같다")
    void dailyBlockScattersAcrossDays() throws Exception {
        List<String> rows = blockRows(fetchCsv("MONTH"), "[일별 작업량]");

        assertThat(rows).hasSize(30);
        // 오늘 2건 · 5일 전 3건 (index = 29 - n일전)
        assertThat(rows.get(29)).isEqualTo(LocalDate.now() + ",2");
        assertThat(rows.get(24)).isEqualTo(LocalDate.now().minusDays(5) + ",3");
        // 0-fill 이 실제로 남아 있는지 — 0 인 날이 반드시 존재한다.
        assertThat(rows).anyMatch(r -> r.endsWith(",0"));
        // 합계 = 승인 영상 수(5). 어느 날짜 칸으로도 새지 않았다.
        long sum = rows.stream().mapToLong(r -> Long.parseLong(r.split(",")[1])).sum();
        assertThat(sum).isEqualTo(5L);
    }

    @Test
    @DisplayName("이벤트_분포는_실DB에서도_유형코드로_짝지어지고_0건_유형도_함께_나온다")
    void eventDistributionPairsFromRealDb() throws Exception {
        String csv = fetchCsv("MONTH");
        List<String> rows = blockRows(csv, "[이벤트 유형 분포]");

        // 검수완료와 전체가 유형마다 다른 값 — 위치로 맞췄다면 여기서 어긋난다.
        assertThat(rows).contains(LABEL_FIRE + ",3,8");
        assertThat(rows).contains(LABEL_FALL + ",2,12");
        // 데이터 0건 유형도 빠지지 않는다(빠지면 그 유형이 필터로 도달 불가해진다).
        assertThat(rows).contains(LABEL_EMPTY + ",0,0");
        // 행 수 = 화면이 그리는 카테고리 수와 동일.
        assertThat(rows).hasSize(fetchOverall().path("eventDistribution").size());
    }

    @Test
    @DisplayName("작업자별_현황은_컬럼이_밀리지_않고_비율은_0에서_100_사이_실측값이다")
    void workerBlockKeepsColumnsAligned() throws Exception {
        List<String> rows = blockRows(fetchCsv("MONTH"), "[작업자별 현황]");

        assertThat(rows).hasSize(2);

        // ★쉼표가 든 이름 — 따옴표로 감싸지지 않으면 컬럼이 7개가 된다.
        String w2 = rowStartingWith(rows, "\"");
        List<String> w2Cells = cells(w2);
        assertThat(w2Cells).hasSize(6);
        assertThat(w2Cells).containsExactly(W2_NAME, "8", "7", "1", "75.0", "50.0");

        // ★수식으로 시작하는 이름 — 작은따옴표 무해화 후에도 나머지 칸은 그대로다.
        String w1 = rowStartingWith(rows, "'=");
        List<String> w1Cells = cells(w1);
        assertThat(w1Cells).hasSize(6);
        assertThat(w1Cells).containsExactly("'" + W1_NAME, "4", "2", "3", "20.0", "25.0");

        // 비율 두 칸은 0~100 범위 — 이중 곱하기(7120% 류)가 있으면 여기서 깨진다.
        for (List<String> row : List.of(w1Cells, w2Cells)) {
            assertThat(Double.parseDouble(row.get(4))).isBetween(0.0, 100.0);
            assertThat(Double.parseDouble(row.get(5))).isBetween(0.0, 100.0);
        }
        // 어떤 행도 = 로 시작하지 않는다(Excel 이 수식으로 실행하지 않는다).
        assertThat(rows).noneMatch(r -> r.startsWith("="));
    }
}
