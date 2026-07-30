package kr.co.cudo.authoring.batch.test;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.support.RawVideoFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관제 학습용 자동 적재 픽업 경로 — 외부 관제 DB 없이 로컬 검증 (Testcontainers PostgreSQL).
 *
 * <p>{@code POST /v1/dev/batch/scan} 가 {@code JOB_DMND_YN='Y'} 시드 클립(MNG_CLIP_MASTER)을
 * 픽업해 {@code LS_DATA_RAW} 로 적재하고 {@code VideoIngestedEvent} 를 발행하는 경로를,
 * dev-seed.sql 자동 적재(local 한정) 없이 테스트가 직접 시드(JdbcTemplate)한 뒤 검증한다.
 *
 * <p>시드는 {@code DEV-CLIP-9*} / {@code DEV-EVT-9*} 접두사로 격리하고 매 테스트 전후로 정리해
 * 다른 통합테스트 상태를 오염시키지 않는다(application-local.yml dev.seed.enabled=false 와 동일 격리).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BatchDevScanIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BatchDevScanIntegrationTest.class);

    private static final String SEED_CLIP_ID = "DEV-CLIP-9101";
    private static final String SEED_EVNT_ID = "DEV-EVT-9101";

    /**
     * 비동기 선두 비식별의 종결 대기 상한(ms). mock 비식별은 수 ms~수백 ms 안에 끝나므로 넉넉한 상한이며,
     * 초과는 "비동기가 멈췄다" 는 신호로 취급해 진단 로그를 남긴다(무한 대기 금지).
     */
    private static final long DEIDENT_SETTLE_TIMEOUT_MS = 30_000L;
    /** 종결 폴링 간격(ms) — 폴링마다 커넥션을 즉시 반납해 비동기 스레드가 커넥션을 얻게 한다. */
    private static final long DEIDENT_POLL_INTERVAL_MS = 50L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    /**
     * 선두 비식별을 수행하는 {@code @Async} 풀 — 정리 전에 "이 JVM 에 진행 중인 배치 비동기 작업이 없음"
     * 을 확인하는 데 쓴다. 작업 스레드가 반납되는 시점은 {@code DeidentifyStep.run()} 트랜잭션이
     * 커밋/롤백으로 <b>완전히 끝난 뒤</b>라, 부모 영상 행의 {@code FOR KEY SHARE} 해제를 순서상 보장한다.
     */
    @Autowired
    @Qualifier("batchAsyncExecutor")
    private Executor batchAsyncExecutor;

    @Value("${authoring.jwt.secret}")
    private String secret;

    @Value("${authoring.jwt.issuer}")
    private String issuer;

    private JdbcTemplate jdbc;

    /** REVIEWER + INTERNAL 채널 토큰 — /v1/dev/batch/** 는 REVIEWER 인증 필수. */
    private String reviewerToken() {
        return JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanup();
        // 픽업 후보 CCTV 마스터(FK 참조 대상) — 기존 시드와 무관한 격리 ID 보장.
        jdbc.update("INSERT INTO MNG_RESOURCE_CCTV (VMS_CCTV_ID, CCTV_NM, USE_YN) VALUES (?, ?, 'Y') "
                + "ON CONFLICT (VMS_CCTV_ID) DO NOTHING", "CCTV-001", "CCTV-강남구-001");
    }

    @AfterEach
    void tearDown() {
        // 이 테스트가 스스로 띄운 비동기 선두 비식별이 <b>종결(커밋)</b>된 뒤에 정리한다 — 아래 await 의
        // javadoc 참조. setUp 쪽 cleanup 에는 대기를 걸지 않는다(그 시점의 미종결 행은 이 JVM 이 만든 것이
        // 아니라 이전 실행의 잔여물이므로, 기다릴 대상이 없어 상한 시간만 소모한다).
        awaitPreMarkingDeidentSettled();
        cleanup();
    }

    private void cleanup() {
        // V146(DB-ISSUE-01) 이후 적재 영상에는 자식 FK 가 붙는다. 적재는 AFTER_COMMIT → @Async 선두
        // 비식별을 띄우므로, 그 비동기 트랜잭션이 LS_DEIDENT_PROC_LOG 를 INSERT 한 채 커밋 전이면 부모
        // 영상 행에 FOR KEY SHARE 가 걸려 삭제가 대기한다(커넥션 기본 lock_timeout 30s → 테스트 실패).
        // 짧은 lock_timeout + 재시도로 비동기 커밋을 기다린 뒤 삭제한다(자식은 CASCADE 동반 삭제).
        RawVideoFixture.deleteRawsWhere(jdbc, "VMS_CLIP_ID LIKE ?", "DEV-CLIP-%");
        jdbc.update("DELETE FROM MNG_CLIP_EVNT_LST WHERE EVNT_ID LIKE 'DEV-EVT-%'");
        jdbc.update("DELETE FROM MNG_CLIP_MASTER WHERE EVNT_ID LIKE 'DEV-EVT-%'");
    }

    /**
     * 적재된 시드 영상의 <b>비동기 선두 비식별</b>이 종결될 때까지 상태로 대기한다(시간 기반 슬립 아님).
     *
     * <p><b>왜 필요한가 — 정리(delete)와 자기 비동기 작업 사이의 커넥션 기아 교착</b>:
     * scan 적재는 {@code AFTER_COMMIT → IngestDeidentifyBridge → AsyncDeidentifyRunner}(@Async)로 선두
     * 비식별을 띄운다. 그 스레드의 {@code DeidentifyStep.run()}(REQUIRES_NEW)이 커넥션 #1 을 잡고
     * {@code LS_DEIDENT_PROC_LOG} 를 INSERT 하면 부모 영상 행에 {@code FOR KEY SHARE} 가 걸린다. 테스트
     * 환경의 시드 원본 파일은 실재하지 않으므로({@code ./storage/raw/seed/*.mp4}) mock 비식별은 실패
     * 분기로 가서 {@code BatchTransitionService.recordDeidentFailure}(또 다른 REQUIRES_NEW)를 호출하는데,
     * 이때 <b>커넥션 #2 가 필요</b>하다. 그런데 테스트 풀은 {@code maximum-pool-size: 2} 이고, 예전 tearDown
     * 은 {@code deleteRawsWhere} 가 {@code ConnectionCallback} 안에서 커넥션 #2 를 <b>15초 내내 붙잡은 채</b>
     * 잠금 재시도를 돌렸다. 결과: 잠금 보유자(비동기)는 커넥션을 못 얻어 커밋하지 못하고(Hikari 대기 30s),
     * 삭제자는 그 보유자의 커밋을 기다리다 재시도 예산(15 × lock_timeout 1s)을 소진해 {@code 55P03}
     * (canceling statement due to lock timeout)로 실패했다 — 재시도로는 풀 수 없는 교착이다.
     *
     * <p><b>대기 방식</b>: 커넥션을 <b>붙잡지 않고</b> 짧은 조회(폴링)로 종결 여부만 본다. 매 폴링은 커넥션을
     * 즉시 반납하므로 비동기 스레드가 커넥션 #2 를 얻어 커밋을 마칠 수 있고, 그 뒤 삭제는 잠금 경합 없이
     * 1회에 성공한다. 종결 판정은 {@code LS_DATA_RAW.DE_IDENT_YN} 이 {@code 'N'}(미수행)을 벗어나는 것
     * ({@code 'Y'}=성공 / {@code 'F'}=실패 — 실패도 별도 커밋 트랜잭션으로 확정된다)이다.
     *
     * <p><b>상한 초과 시</b>: 무한 대기하지 않는다. 잔여 행의 상태를 진단 로그로 남기고 정리로 넘어가
     * {@code RawVideoFixture} 의 재시도(2차 방어)가 판정하게 한다 — 여기서 즉시 예외를 던지면 삭제가
     * 아예 수행되지 않아 잔여 행이 재사용 컨테이너에 남고, 이후 실행까지 오염된다.
     */
    private void awaitPreMarkingDeidentSettled() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEIDENT_SETTLE_TIMEOUT_MS);
        while (!batchAsyncIdle() || countUnsettledDeident() > 0) {
            if (System.nanoTime() - deadline >= 0) {
                log.warn("[BatchDevScanIT] 선두 비식별이 {}ms 안에 종결되지 않았습니다 — asyncIdle={} 잔여 행={}"
                                + " (정리는 계속 진행: RawVideoFixture 재시도가 2차 방어)",
                        DEIDENT_SETTLE_TIMEOUT_MS, batchAsyncIdle(), unsettledDiagnostics());
                return;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(DEIDENT_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 배치 비동기 풀이 유휴(진행 중 0 + 대기 큐 비어 있음)인지.
     *
     * <p>상태 컬럼({@code DE_IDENT_YN})만으로는 부족하다 — 실패 기록은 별도 트랜잭션에서 먼저 커밋되고
     * 부모 행 잠금을 쥔 {@code run()} 트랜잭션의 롤백은 그 <b>직후</b>에 일어나므로, 상태만 보면 잠금이
     * 아직 살아 있는 짧은 창이 남는다. 스레드 반납은 그 롤백 이후이므로 이 조건이 창을 닫는다.
     *
     * <p><b>⚠ 결합 위험 — 테스트 병렬 실행을 켜면 이 조건이 역효과가 된다.</b> {@code batchAsyncExecutor}
     * 는 증강·export·해상도·VLM·포털 러너가 함께 쓰는 싱글턴이라 여기서 기다리는 것은 "이 테스트의 작업"
     * 이 아니라 <b>풀 전체</b>의 유휴다. 현재는 테스트가 단일 JVM 순차 실행({@code maxParallelForks}·
     * {@code junit-platform.properties} 미설정)이라 다른 테스트의 비동기 작업이 동시에 떠 있을 수 없어
     * 무해하다. 병렬 실행을 도입하면 무관한 작업을 기다려 상한을 소모하고 헛 WARN 을 내므로, 판정을
     * "이 테스트가 띄운 작업"으로 좁히는 선행 작업이 필요하다(그 경우 시드 격리도 함께 손봐야 한다).
     */
    private boolean batchAsyncIdle() {
        if (!(batchAsyncExecutor instanceof ThreadPoolTaskExecutor pool)) {
            return true; // 풀 구현이 바뀌면 상태 조건만으로 판단(대기를 무한정 끌지 않는다).
        }
        return pool.getActiveCount() == 0 && pool.getThreadPoolExecutor().getQueue().isEmpty();
    }

    /** 시드 영상 중 비식별이 아직 종결되지 않은(DE_IDENT_YN='N') 행 수. */
    private long countUnsettledDeident() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM LS_DATA_RAW WHERE VMS_CLIP_ID LIKE 'DEV-CLIP-%' AND DE_IDENT_YN = 'N'",
                Long.class);
        return n == null ? 0L : n;
    }

    /** 상한 초과 시 남길 진단 정보 — 식별자·상태 코드만(경로·PII 미포함). */
    private String unsettledDiagnostics() {
        return jdbc.queryForList(
                "SELECT RAW_SN, DE_IDENT_YN, DATA_STTS_CD FROM LS_DATA_RAW "
                        + "WHERE VMS_CLIP_ID LIKE 'DEV-CLIP-%' AND DE_IDENT_YN = 'N'").toString();
    }

    /** JOB_DMND_YN='Y' 픽업 후보 클립 1건 + 이벤트리스트 1행을 관제 stub 에 직접 세팅. */
    private void seedTrainingClip() {
        jdbc.update("""
                INSERT INTO MNG_CLIP_MASTER
                    (EVNT_ID, CLIP_TYPE_CD, CLIP_ID, LCLGV_CD, FILE_NM, FILE_PATH, FILE_FMT,
                     VDO_LEN_SEC, CLIP_STTS_CD, CRT_DT, JOB_DMND_YN, VMS_CCTV_ID)
                VALUES (?, 'ORIGINAL', ?, '11110', 'clip-9101.mp4', ?, 'mp4',
                        30000, 'mediainfo_complete', ?, 'Y', 'CCTV-001')
                """,
                SEED_EVNT_ID, SEED_CLIP_ID, "./storage/raw/seed/clip-9101.mp4", LocalDateTime.now());
        jdbc.update("INSERT INTO MNG_CLIP_EVNT_LST (EVNT_ID, EVNT_TYPE_CD, SHT_DT) VALUES (?, 'INTRUSION', ?)",
                SEED_EVNT_ID, LocalDateTime.now());
    }

    private long countSeedRaw() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM LS_DATA_RAW WHERE VMS_CLIP_ID = ?", Long.class, SEED_CLIP_ID);
        return n == null ? 0L : n;
    }

    @Test
    @DisplayName("scan트리거_호출시_JOB_DMND_Y_시드클립을_픽업해_LS_DATA_RAW에_적재한다")
    void scanIngestsTrainingDesignatedSeedClip() throws Exception {
        // given — 학습용 지정 시드 클립 1건
        seedTrainingClip();

        // when — scan 1회 동기 실행 (REVIEWER 인증)
        mockMvc.perform(post("/v1/dev/batch/scan")
                        .header("Authorization", "Bearer " + reviewerToken()))
                // then — 200 + 적재 건수 >= 1
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        // LS_DATA_RAW 에 시드 CLIP_ID 가 적재됨
        assertThat(countSeedRaw()).isEqualTo(1L);
    }

    @Test
    @DisplayName("scan트리거_재호출시_CLIP_ID로_멱등하여_중복적재되지_않는다")
    void scanIsIdempotentByClipId() throws Exception {
        // given
        seedTrainingClip();

        // when — 2회 호출 (REVIEWER 인증)
        mockMvc.perform(post("/v1/dev/batch/scan")
                .header("Authorization", "Bearer " + reviewerToken())).andExpect(status().isOk());
        mockMvc.perform(post("/v1/dev/batch/scan")
                .header("Authorization", "Bearer " + reviewerToken())).andExpect(status().isOk());

        // then — 동일 VMS_CLIP_ID row 1건만 존재 (CLIP_ID 멱등키)
        assertThat(countSeedRaw()).isEqualTo(1L);
    }

    @Test
    @DisplayName("픽업된_클립에_대해_PENDING_상태로_적재되어_VideoIngestedEvent_발행_경로를_탄다")
    void ingestedClipStartsPendingForVideoIngestedEvent() throws Exception {
        // given
        seedTrainingClip();

        // when (REVIEWER 인증)
        mockMvc.perform(post("/v1/dev/batch/scan")
                .header("Authorization", "Bearer " + reviewerToken())).andExpect(status().isOk());

        // then — 적재 직후 DATA_STTS_CD=PENDING (VideoIngestedEvent → 비식별 선두 트리거 시작점)
        String status = jdbc.queryForObject(
                "SELECT DATA_STTS_CD FROM LS_DATA_RAW WHERE VMS_CLIP_ID = ?", String.class, SEED_CLIP_ID);
        assertThat(status).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("픽업_대상이_없으면_0건_200으로_응답한다")
    void scanWithNoCandidatesReturnsZero() throws Exception {
        // given — 시드 클립 없음 (cleanup 만 수행됨)

        // when / then — 0건도 200 + 0 (REVIEWER 인증)
        mockMvc.perform(post("/v1/dev/batch/scan")
                        .header("Authorization", "Bearer " + reviewerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(0));
    }

    @Test
    @DisplayName("미인증_요청은_401_또는_403으로_거부된다")
    void scanWithoutAuthIsRejected() throws Exception {
        // given — 토큰 미첨부
        // when / then — Authorization 헤더 없이 호출 시 인증 거부 (permitAll 제거 → REVIEWER 강제)
        mockMvc.perform(post("/v1/dev/batch/scan"))
                .andExpect(result -> {
                    int sc = result.getResponse().getStatus();
                    assertThat(sc).isIn(401, 403);
                });
    }

    @Test
    @DisplayName("WORKER_토큰은_403으로_거부된다")
    void scanWithWorkerTokenIsForbidden() throws Exception {
        // given — REVIEWER 가 아닌 WORKER 토큰
        String workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);

        // when / then — REVIEWER 미보유 → 403
        mockMvc.perform(post("/v1/dev/batch/scan")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("REVIEWER_인증시_미픽업_scan은_정상_200으로_호출된다")
    void scanWithReviewerAuthSucceeds() throws Exception {
        // given — 시드 클립 없음
        // when / then — REVIEWER 인증 → 정상 200 + 0건
        mockMvc.perform(post("/v1/dev/batch/scan")
                        .header("Authorization", "Bearer " + reviewerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(0));
    }
}
