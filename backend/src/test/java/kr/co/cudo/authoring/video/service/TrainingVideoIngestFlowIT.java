package kr.co.cudo.authoring.video.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.co.cudo.authoring.batch.runner.AsyncDeidentifyRunner;
import kr.co.cudo.authoring.common.storage.ArtifactRootTestSupport;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import kr.co.cudo.authoring.video.repository.LsDataIngestRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 인입 → 적재 → 비식별 체인 실동작 검증 (Phase 3, Testcontainers PostgreSQL).
 *
 * <h3>왜 통합 테스트가 필요한가 (설계 §6-0 규약 2)</h3>
 * <p>{@code ingestOne} 은 {@code REQUIRES_NEW} 트랜잭션이고 상태 전이({@code markDone}/
 * {@code markFailed})는 <b>dirty checking</b> 으로만 영속된다. 외부 {@code readOnly} 세션에서
 * 로드한 인스턴스를 그대로 변경하면 <b>DB 에는 아무것도 쓰이지 않는다</b>(PROCESSING 좀비).
 * 목 기반 단위 테스트는 필드값만 보므로 이 결함을 <b>구조적으로 잡지 못한다</b> — 그래서 여기서는
 * 전이 이후 <b>별도 트랜잭션(JDBC)</b> 으로 다시 읽어 DB 반영을 단언한다.
 *
 * <p>또한 {@code VideoIngestedEvent → IngestDeidentifyBridge}(AFTER_COMMIT) 체인이 살아 있는지
 * 확인한다 — 이 체인이 끊기면 비식별이 아예 돌지 않아 원본 PII 가 그대로 남는다. 비식별 본체는
 * 이 테스트의 관심사가 아니므로 {@link AsyncDeidentifyRunner} 만 목으로 대체해 <b>트리거 여부</b>를 본다.
 *
 * <p>이 클래스는 <b>클래스 레벨 {@code @Transactional} 을 쓰지 않는다</b> — REQUIRES_NEW 커밋을
 * 실제로 일으켜야 하기 때문이다. 시드는 {@code INGEST-FLOW-IT-} 접두로 격리하고 직접 정리한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        // 관제 NAS 마운트 모사 — 이 하위 경로만 적재 가능(고정 allowlist).
        "authoring.storage.raw-mount-roots=" + ArtifactRootTestSupport.IT_MOUNT_ROOT,
        // 미도착 대기 상한을 1시간으로 좁혀, 상한 초과 종결(설계 §6-0-1 ①)을 시간 조작 없이 검증한다.
        "authoring.control.training-scan.not-arrived-timeout-hours=1"
})
class TrainingVideoIngestFlowIT {

    private static final String CLIP_PREFIX = "INGEST-FLOW-IT-";

    @Autowired
    private TrainingVideoIngestTx ingestTx;

    @Autowired
    private TrainingVideoIngestService ingestService;

    @Autowired
    private LsDataIngestRepository ingestRepository;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    /** 재큐(쓰기 쿼리) 호출용 트랜잭션 경계 — 이 클래스는 앰비언트 트랜잭션을 쓰지 않는다. */
    @Autowired
    @Qualifier("controlTransactionManager")
    private PlatformTransactionManager controlTransactionManager;

    /** 비식별 본체는 관심사가 아니다 — 체인이 여기까지 도달하는지만 본다. */
    @MockBean
    private AsyncDeidentifyRunner asyncDeidentifyRunner;

    /**
     * 실물 빈 그대로 쓰되 <b>사전 멱등 조회 한 번만</b> 비워 UK 충돌 race 창을 재현하기 위한 spy.
     *
     * <p>다른 테스트는 전부 실동작(실 INSERT·실 제약)을 그대로 쓴다 — stub 은 해당 테스트 안에서만
     * {@code doReturn} 으로 심는다.
     */
    @SpyBean
    private VideoRepository videoRepository;

    private JdbcTemplate jdbc;
    private String runId;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        runId = String.valueOf(System.nanoTime());
    }

    @AfterEach
    void tearDown() {
        // 이 테스트는 실제 커밋을 일으키므로 롤백 정리가 없다 — 시드 접두로만 지운다.
        jdbc.update("DELETE FROM ls_data_raw WHERE vms_clip_id LIKE ?", CLIP_PREFIX + "%");
        jdbc.update("DELETE FROM ls_data_ingest WHERE vms_clip_id LIKE ?", CLIP_PREFIX + "%");
    }

    private String clip(String suffix) {
        return CLIP_PREFIX + suffix + "-" + runId;
    }

    /** 관제가 넣는 최소 형상(PENDING) 인입 행을 심고 PK 를 돌려준다. */
    private long seedPendingIngest(String clipId, String rawFilePathNm) {
        return seedPendingIngest(clipId, rawFilePathNm, LocalDateTime.now());
    }

    /**
     * 수신일시를 지정해 인입 행을 심는다 — 폴링은 {@code RCPTN_DT ASC} 라 이 값이 픽업 순서를,
     * 그리고 미도착 <b>대기 경과 상한</b>(설계 §6-0-1 ①) 판정을 좌우한다.
     */
    private long seedPendingIngest(String clipId, String rawFilePathNm, LocalDateTime rcptnDt) {
        jdbc.update("""
                INSERT INTO ls_data_ingest
                    (vms_clip_id, vms_cctv_id, vdo_file_nm, raw_file_path_nm, src_type,
                     rcptn_dt, proc_stts_cd, vdo_len_sec, lclgv_cd)
                VALUES (?, 'CCTV-FLOW-01', 'clip.mp4', ?, 'RELAY', ?, 'PENDING', 600, '30200')
                """, clipId, rawFilePathNm, Timestamp.valueOf(rcptnDt));
        return jdbc.queryForObject(
                "SELECT rcptn_sn FROM ls_data_ingest WHERE vms_clip_id = ?", Long.class, clipId);
    }

    /** 별도 트랜잭션(JDBC 직결)으로 인입 행 상태를 다시 읽는다 — 영속성 컨텍스트 잔상 배제. */
    private Map<String, Object> reloadIngestRow(long rcptnSn) {
        return jdbc.queryForMap(
                "SELECT proc_stts_cd, raw_sn, rty_cnt, err_msg, prcs_dt"
                        + " FROM ls_data_ingest WHERE rcptn_sn = ?", rcptnSn);
    }

    @Test
    @DisplayName("적재_성공시_인입행이_별도트랜잭션에서_조회해도_DONE으로_남는다")
    void ingestedRowStaysDoneInSeparateTransaction() throws IOException {
        // given — 허용 루트 하위에 실제 영상 파일 + PENDING 인입 행 1건
        Path video = ArtifactRootTestSupport.seedOriginalVideo("ingest-flow");
        String clipId = clip("DONE");
        long rcptnSn = seedPendingIngest(clipId, video.toString());

        // when — 스캔이 넘겨주는 것과 동일하게, 외부에서 읽은 인스턴스를 REQUIRES_NEW 로 넘긴다
        boolean ingested = ingestTx.ingestOne(ingestRepository.findById(rcptnSn).orElseThrow());

        // then — 적재 성공 + LS_DATA_RAW 1건
        assertThat(ingested).isTrue();
        Long rawSn = jdbc.queryForObject(
                "SELECT raw_sn FROM ls_data_raw WHERE vms_clip_id = ?", Long.class, clipId);
        assertThat(rawSn).isNotNull();

        // then — ★상태 전이가 DB 에 실제로 반영됐다(별도 트랜잭션 재조회). 행은 삭제되지 않는다.
        Map<String, Object> row = reloadIngestRow(rcptnSn);
        assertThat(row.get("proc_stts_cd")).isEqualTo(LsDataIngest.PROC_STTS_DONE);
        assertThat(((Number) row.get("raw_sn")).longValue()).isEqualTo(rawSn);
        assertThat(row.get("prcs_dt")).isNotNull();

        // then — 인입값이 그대로 적재됐다(영상길이 초 단위 · 지자체코드 · 출처유형)
        Map<String, Object> raw = jdbc.queryForMap(
                "SELECT vdo_len_sec, lclgv_cd, src_type, raw_file_path_nm"
                        + " FROM ls_data_raw WHERE vms_clip_id = ?", clipId);
        assertThat(((Number) raw.get("vdo_len_sec")).intValue()).isEqualTo(600);
        assertThat(raw.get("lclgv_cd")).isEqualTo("30200");
        assertThat(raw.get("src_type")).isEqualTo("RELAY");
        assertThat(raw.get("raw_file_path_nm")).isEqualTo(video.toString());

        // then — ★비식별 선두 체인(VideoIngestedEvent → IngestDeidentifyBridge)이 살아 있다
        verify(asyncDeidentifyRunner, timeout(5_000)).runAsync(rawSn);
    }

    @Test
    @DisplayName("파일이_아직_없으면_별도트랜잭션_조회에서도_PENDING이고_재시도횟수가_그대로다")
    void notArrivedFileRevertsToPendingWithoutRetryIncrement() {
        // given — 관제가 메타를 먼저 넣고 파일 복사가 아직 끝나지 않았다(허용 루트 하위, 파일 부재)
        String clipId = clip("WAIT");
        String notArrived = Path.of(ArtifactRootTestSupport.IT_MOUNT_ROOT, "videos", "never-copied.mp4")
                .toAbsolutePath().normalize().toString();
        long rcptnSn = seedPendingIngest(clipId, notArrived);

        // when
        boolean ingested = ingestTx.ingestOne(ingestRepository.findById(rcptnSn).orElseThrow());

        // then — 적재 없음
        assertThat(ingested).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ls_data_raw WHERE vms_clip_id = ?", Integer.class, clipId)).isZero();

        // then — ★영구 미적재(FAILED)도 영구 좀비(PROCESSING)도 아닌 PENDING 복귀. 재시도횟수 불변.
        Map<String, Object> row = reloadIngestRow(rcptnSn);
        assertThat(row.get("proc_stts_cd")).isEqualTo(LsDataIngest.PROC_STTS_PENDING);
        assertThat(((Number) row.get("rty_cnt")).intValue()).isZero();
        assertThat(row.get("err_msg")).isNull();
        verify(asyncDeidentifyRunner, never()).runAsync(anyLong());
    }

    @Test
    @DisplayName("미도착_행이_상한만큼_쌓여도_종결없이_뒤의_정상행이_적재된다")
    void notArrivedRowsDoNotStarveLaterCandidatesWithoutTermination() throws IOException {
        // given — 미도착 행을 tick 상한만큼(=폴링 한 페이지 전량) 심는다.
        //   ★수신 직후 상태다(대기 상한 1h 이내). 즉 <종결이 큐를 비워주지 않는> 구간이며,
        //     구 테스트는 전부 상한 초과(aged)로 심어 이 창을 통째로 비껴갔다.
        //     상한만 있고 backoff 가 없으면 이 행들이 최대 1시간 동안 FIFO 앞자리를 점유해
        //     그 뒤 정상 인입이 한 건도 픽업되지 않는다(head-of-line blocking).
        LocalDateTime fresh = LocalDateTime.now().minusMinutes(1);
        Path missingDir = Path.of(ArtifactRootTestSupport.IT_MOUNT_ROOT, "videos")
                .toAbsolutePath().normalize();
        for (int i = 0; i < TrainingVideoIngestService.INGEST_SCAN_LIMIT; i++) {
            seedPendingIngest(clip("STARVE-BLOCK-" + i),
                    missingDir.resolve("never-arrives-" + runId + "-" + i + ".mp4").toString(),
                    fresh.plusSeconds(i));
        }
        // given — 그 뒤에 도착한 정상 행 1건(실제 파일 존재). 수신일시는 막힌 행들보다 늦다.
        Path video = ArtifactRootTestSupport.seedOriginalVideo("ingest-starve");
        String healthyClip = clip("STARVE-OK");
        seedPendingIngest(healthyClip, video.toString(), LocalDateTime.now());

        // when — 첫 tick 이 상한만큼(=막힌 행 전량) 집어 backoff 로 밀어내고, 다음 tick 이 그 뒤를 본다.
        ingestService.scanAndIngest();
        ingestService.scanAndIngest();

        // then — ★정상 행이 적재된다. backoff 가 없으면 이 단언은 상한(1h)이 지나기 전까지 실패한다.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ls_data_raw WHERE vms_clip_id = ?", Integer.class, healthyClip))
                .isEqualTo(1);

        // then — ★막힌 행은 <종결되지 않았다>. 큐를 비운 것은 종결이 아니라 backoff 다(보류이지 실패가
        //   아니라는 R4 규약 유지 — 파일이 늦게 도착하면 그대로 적재된다).
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ls_data_ingest WHERE vms_clip_id LIKE ? AND proc_stts_cd <> ?",
                Integer.class, CLIP_PREFIX + "STARVE-BLOCK-%", LsDataIngest.PROC_STTS_PENDING))
                .as("미도착 행은 PENDING 유지(종결·좀비 아님)").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ls_data_ingest WHERE vms_clip_id LIKE ? AND rty_cnt <> 0",
                Integer.class, CLIP_PREFIX + "STARVE-BLOCK-%"))
                .as("대기는 실패 이력이 아니다").isZero();
        // then — 막힌 행 전량이 재시도 예정 시각을 갖는다(= 다음 tick 후보에서 빠진 근거)
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ls_data_ingest WHERE vms_clip_id LIKE ? AND next_rtry_dt IS NULL",
                Integer.class, CLIP_PREFIX + "STARVE-BLOCK-%"))
                .as("backoff 미적용 행 없음").isZero();
    }

    @Test
    @DisplayName("상한초과_종결분은_재큐하면_즉시_재종결되지_않고_다시_적재된다")
    void requeueResetsWaitBudgetSoRowIsNotTerminatedAgain() throws IOException {
        // given — 대기 상한(1h)을 넘겨 종결된 미도착 행.
        //   예산 앵커(PRCS_DT)를 그대로 두고 재큐하면 <다음 tick 에 즉시 재종결>되어 재시도 창이 0초다.
        String clipId = clip("BUDGET-RESET");
        Path missingDir = Path.of(ArtifactRootTestSupport.IT_MOUNT_ROOT, "videos")
                .toAbsolutePath().normalize();
        long rcptnSn = seedPendingIngest(clipId,
                missingDir.resolve("late-" + runId + ".mp4").toString(), LocalDateTime.now());
        // 최초 미도착 관측 앵커를 상한 밖으로 밀어 종결 조건을 만든다(관제 수신일시가 아니라 우리 스탬프).
        jdbc.update("UPDATE ls_data_ingest SET prcs_dt = ? WHERE rcptn_sn = ?",
                Timestamp.valueOf(LocalDateTime.now().minusHours(3)), rcptnSn);
        ingestService.scanAndIngest();
        assertThat(reloadIngestRow(rcptnSn).get("proc_stts_cd")).isEqualTo(LsDataIngest.PROC_STTS_FAILED);

        // given — 운영자가 원인을 고쳤다(파일이 실제로 도착).
        Path video = ArtifactRootTestSupport.seedOriginalVideo("ingest-budget-reset");
        jdbc.update("UPDATE ls_data_ingest SET raw_file_path_nm = ? WHERE rcptn_sn = ?",
                video.toString(), rcptnSn);

        // when — 재큐 후 다음 스캔
        new TransactionTemplate(controlTransactionManager)
                .execute(status -> ingestRepository.requeueFailedForRetry(rcptnSn));
        ingestService.scanAndIngest();

        // then — ★예산이 리셋돼 재큐 직후 tick 에서 종결되지 않고 정상 적재됐다.
        Map<String, Object> row = reloadIngestRow(rcptnSn);
        assertThat(row.get("proc_stts_cd")).isEqualTo(LsDataIngest.PROC_STTS_DONE);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ls_data_raw WHERE vms_clip_id = ?", Integer.class, clipId)).isEqualTo(1);
    }

    @Test
    @DisplayName("관제가_과거_수신일시를_INSERT해도_첫_픽업에서_종결되지_않는다")
    void controlSuppliedBackdatedReceiptDateDoesNotTerminateImmediately() {
        // given — RCPTN_DT 는 DEFAULT 일 뿐 강제가 없고 INSERT 주체가 관제다. 아주 과거 시각을 명시
        //   INSERT 한 미도착 행(파일 복사가 아직 진행 중일 수 있다).
        String clipId = clip("BACKDATED");
        Path missingDir = Path.of(ArtifactRootTestSupport.IT_MOUNT_ROOT, "videos")
                .toAbsolutePath().normalize();
        long rcptnSn = seedPendingIngest(clipId,
                missingDir.resolve("backdated-" + runId + ".mp4").toString(),
                LocalDateTime.now().minusDays(30));

        // when — 첫 픽업
        ingestService.scanAndIngest();

        // then — ★관제 수신값을 예산 축으로 쓰면 여기서 즉시 FAILED 가 된다(도착 기회조차 없다).
        Map<String, Object> row = reloadIngestRow(rcptnSn);
        assertThat(row.get("proc_stts_cd")).isEqualTo(LsDataIngest.PROC_STTS_PENDING);
        assertThat(((Number) row.get("rty_cnt")).intValue()).isZero();
        // 우리 시계로 찍은 앵커가 이제 막 시작됐다(상한은 여기서부터 잰다).
        assertThat(((Timestamp) row.get("prcs_dt")).toLocalDateTime())
                .isAfter(LocalDateTime.now().minusMinutes(5));
    }

    @Test
    @DisplayName("중복클립_UK충돌_롤백은_ERROR가_아니라_INFO로_남고_인입행은_PENDING으로_돌아온다")
    void duplicateClipUniqueViolationIsAbsorbedWithoutErrorLog() throws IOException {
        // given — 같은 클립이 이미 LS_DATA_RAW 에 있는데 <사전 멱등 조회가 그 사이를 놓친> 상태.
        //   실제로는 두 노드가 동시에 같은 클립을 적재할 때 생기는 창(조회~INSERT 사이)이며, 여기서는
        //   그 창을 결정적으로 재현하기 위해 사전 조회만 빈 결과로 만든다(INSERT 는 실제 DB 제약이 막는다).
        //   ★목으로 대체한 것은 <조회 한 번>뿐이고, UK 위반·트랜잭션 abort·REQUIRES_NEW 커밋 실패는
        //     전부 실제 PostgreSQL 동작이다 — 목 기반 단위 테스트는 커밋이 없어 이 경로를 못 만든다.
        Path video = ArtifactRootTestSupport.seedOriginalVideo("ingest-uk-race");
        String clipId = clip("UK-RACE");
        long rcptnSn = seedPendingIngest(clipId, video.toString());
        jdbc.update("""
                INSERT INTO ls_data_raw
                    (vms_clip_id, vms_cctv_id, prvc_type_cd, prvc_yn, de_ident_yn,
                     raw_file_path_nm, data_stts_cd, reg_dt)
                VALUES (?, 'CCTV-FLOW-01', 'ANONY', 'N', 'N', ?, 'PENDING', CURRENT_TIMESTAMP)
                """, clipId, video.toString());
        doReturn(Optional.empty()).when(videoRepository).findByVmsClipId(clipId);

        ListAppender<ILoggingEvent> logs = attachScanLogAppender();
        try {
            // when — 스캔이 이 행을 집어 INSERT 하다 UK(VMS_CLIP_ID) 위반 → 트랜잭션 abort
            ingestService.scanAndIngest();

            // then — ★정상 race 를 ERROR 로 올리지 않는다(실제 장애 알림과 섞이면 관측 가치가 없다).
            assertThat(logs.list.stream().filter(e -> e.getLevel() == Level.ERROR))
                    .as("UK 충돌 롤백은 장애가 아니다").isEmpty();
            // then — 그렇다고 조용히 삼키지도 않는다(관측은 남긴다).
            assertThat(logs.list.stream()
                    .filter(e -> e.getLevel() == Level.INFO)
                    .map(ILoggingEvent::getMessage))
                    .anyMatch(m -> m.contains("rolled back"));
        } finally {
            scanLogger().detachAppender(logs);
        }

        // then — 클레임까지 함께 롤백돼 행이 PENDING 으로 돌아간다(좀비 아님). 다음 tick 의 1차 멱등이
        //   DONE 으로 종결시킬 수 있는 상태다.
        assertThat(reloadIngestRow(rcptnSn).get("proc_stts_cd"))
                .isEqualTo(LsDataIngest.PROC_STTS_PENDING);
    }

    private static ch.qos.logback.classic.Logger scanLogger() {
        return (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(TrainingVideoIngestService.class);
    }

    private static ListAppender<ILoggingEvent> attachScanLogAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        scanLogger().addAppender(appender);
        return appender;
    }

    @Test
    @DisplayName("대기_상한_이내의_미도착_행은_종결되지_않고_PENDING으로_남는다")
    void notArrivedWithinDeadlineStaysPending() {
        // given — 방금 수신한 행(상한 1h 이내). 관제 파일 복사 지연은 정상 흐름이다.
        String clipId = clip("WITHIN-DEADLINE");
        String notArrived = Path.of(ArtifactRootTestSupport.IT_MOUNT_ROOT, "videos",
                        "still-copying-" + runId + ".mp4").toAbsolutePath().normalize().toString();
        long rcptnSn = seedPendingIngest(clipId, notArrived, LocalDateTime.now().minusMinutes(5));

        // when
        ingestService.scanAndIngest();

        // then — 조기 종결 금지. 다음 주기가 다시 집도록 PENDING 으로 남는다.
        Map<String, Object> row = reloadIngestRow(rcptnSn);
        assertThat(row.get("proc_stts_cd")).isEqualTo(LsDataIngest.PROC_STTS_PENDING);
        assertThat(((Number) row.get("rty_cnt")).intValue()).isZero();
    }

    @Test
    @DisplayName("종결된_행을_재큐하면_다음_스캔이_다시_집어_적재한다")
    void requeuedFailedRowIsIngestedOnNextScan() throws IOException {
        // given — 경로 오설정으로 FAILED 종결된 행. 관제 재INSERT 는 UK 로 불가하고 삭제도 금지라,
        //   재큐 통로가 없으면 이 클립은 수동 SQL 없이 영원히 적재되지 않는다(설계 §6-0-1 ②).
        Path outsideDir = Path.of("build", "tmp", "ingest-flow-outside").toAbsolutePath().normalize();
        Files.createDirectories(outsideDir);
        Path outside = outsideDir.resolve("requeue-" + runId + ".mp4");
        Files.writeString(outside, "raw-bytes");
        String clipId = clip("REQUEUE");
        long rcptnSn = seedPendingIngest(clipId, outside.toString());
        ingestTx.ingestOne(ingestRepository.findById(rcptnSn).orElseThrow());
        assertThat(reloadIngestRow(rcptnSn).get("proc_stts_cd")).isEqualTo(LsDataIngest.PROC_STTS_FAILED);

        // given — 운영자가 오설정을 바로잡는다(경로를 허용 루트 하위로 교정).
        Path video = ArtifactRootTestSupport.seedOriginalVideo("ingest-requeue");
        jdbc.update("UPDATE ls_data_ingest SET raw_file_path_nm = ? WHERE rcptn_sn = ?",
                video.toString(), rcptnSn);

        // when — ★종결은 가역이다. (쓰기 쿼리라 호출자 트랜잭션이 필요하다 — 이 클래스는 앰비언트
        //   트랜잭션이 없으므로 후속 운영 진입점과 동일하게 트랜잭션 경계 안에서 부른다.)
        int requeued = new TransactionTemplate(controlTransactionManager)
                .execute(status -> ingestRepository.requeueFailedForRetry(rcptnSn));

        // then — 재큐 1행 + 다음 스캔이 다시 집어 적재한다.
        assertThat(requeued).isEqualTo(1);
        ingestService.scanAndIngest();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ls_data_raw WHERE vms_clip_id = ?", Integer.class, clipId))
                .isEqualTo(1);
        Map<String, Object> row = reloadIngestRow(rcptnSn);
        assertThat(row.get("proc_stts_cd")).isEqualTo(LsDataIngest.PROC_STTS_DONE);
        // 재큐는 실패 이력을 지우지 않는다 — 사유만 비우고 시도 횟수는 누적한다
        //   (markFailed 1 + 재큐 1 = 2. 미도착 복귀와 달리 재큐는 실패 이력의 연장이다).
        assertThat(((Number) row.get("rty_cnt")).intValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("허용_루트_밖_경로는_적재하지_않고_사유와_함께_종결된다")
    void pathOutsideAllowedRootIsRejectedAndTerminated() throws IOException {
        // given — 실재하는 파일이지만 고정 allowlist 밖(관제 수신값은 신뢰 경계 밖이다)
        Path outsideDir = Path.of("build", "tmp", "ingest-flow-outside").toAbsolutePath().normalize();
        Files.createDirectories(outsideDir);
        Path outside = outsideDir.resolve("outside-" + runId + ".mp4");
        Files.writeString(outside, "raw-bytes");
        String clipId = clip("OUTSIDE");
        long rcptnSn = seedPendingIngest(clipId, outside.toString());

        // when
        boolean ingested = ingestTx.ingestOne(ingestRepository.findById(rcptnSn).orElseThrow());

        // then — 적재 금지 + 비식별 미트리거
        assertThat(ingested).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ls_data_raw WHERE vms_clip_id = ?", Integer.class, clipId)).isZero();
        verify(asyncDeidentifyRunner, never()).runAsync(anyLong());

        // then — 종결 사유가 DB 에 남는다(조용한 유실 금지). 좀비로 남지 않는다.
        Map<String, Object> row = reloadIngestRow(rcptnSn);
        assertThat(row.get("proc_stts_cd")).isEqualTo(LsDataIngest.PROC_STTS_FAILED);
        assertThat((String) row.get("err_msg")).isNotBlank();
        // 사유에 경로 원문(NAS 구조)을 담지 않는다(CWE-209/359).
        assertThat((String) row.get("err_msg")).doesNotContain(outside.toString());
    }
}
