package kr.co.cudo.authoring.common.migration;

import kr.co.cudo.authoring.batch.queue.entity.LsClipScheduleQue;
import kr.co.cudo.authoring.batch.queue.service.LabelingBatchQueueService;
import kr.co.cudo.authoring.support.RawVideoFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V5 — {@code LS_CLIP_SCHEDULE_QUE}(7) · {@code LS_META_REPL_OUTBOX}(4) 비표준 컬럼 11종의
 * 표준용어 개명이 <b>실제 적용된 스키마</b>에 반영됐고, 그 위에서 두 테이블의 읽기 경로가 그대로
 * 동작함을 확인한다(Testcontainers PostgreSQL).
 *
 * <p>표준용어 근거는 마이그레이션 헤더에 있다. 여기서는 <b>결과 형상</b>만 고정한다 — 물리명 11종,
 * 폭 축소 2건, 그리고 <b>이번에 손대지 않기로 한</b> 두 컬럼이 그대로인지까지.
 *
 * @req R2
 */
@SpringBootTest
@ActiveProfiles("local")
class V5StandardColumnRenameIT {

    private static final String QUE = "ls_clip_schedule_que";
    private static final String OUTBOX = "ls_meta_repl_outbox";

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Autowired
    @Qualifier("controlTransactionManager")
    private PlatformTransactionManager txManager;

    @Autowired
    private LabelingBatchQueueService queueService;

    private JdbcTemplate jdbc;
    private TransactionTemplate txTemplate;
    private final List<Long> seededRawSns = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        txTemplate = new TransactionTemplate(txManager);
        seededRawSns.clear();
    }

    @AfterEach
    void tearDown() {
        if (!seededRawSns.isEmpty()) {
            RawVideoFixture.deleteRaws(jdbc, seededRawSns.stream().mapToLong(Long::longValue).toArray());
        }
    }

    // ------------------------------------------------------------------ 물리명

    @Test
    @DisplayName("개명된_컬럼이_표준용어_조합이다")
    void renamedColumnsUseStandardTerms() {
        // then — 새 이름 11종이 실재한다
        assertThat(columnExists(QUE, "job_type_cd")).isTrue();
        assertThat(columnExists(QUE, "stts_cd")).isTrue();
        assertThat(columnExists(QUE, "rtry_nmtm")).isTrue();
        assertThat(columnExists(QUE, "reg_dt")).isTrue();
        assertThat(columnExists(QUE, "bgng_dt")).isTrue();
        assertThat(columnExists(QUE, "cmptn_dt")).isTrue();
        assertThat(columnExists(QUE, "last_err_msg_cn")).isTrue();
        assertThat(columnExists(OUTBOX, "payload_cn")).isTrue();
        assertThat(columnExists(OUTBOX, "stts_cd")).isTrue();
        assertThat(columnExists(OUTBOX, "rtry_nmtm")).isTrue();
        assertThat(columnExists(OUTBOX, "prcs_dt")).isTrue();

        // then — 옛 이름 11종은 하나도 남지 않았다
        assertThat(columnExists(QUE, "job_type")).isFalse();
        assertThat(columnExists(QUE, "status")).isFalse();
        assertThat(columnExists(QUE, "retry_count")).isFalse();
        assertThat(columnExists(QUE, "registered_at")).isFalse();
        assertThat(columnExists(QUE, "started_at")).isFalse();
        assertThat(columnExists(QUE, "completed_at")).isFalse();
        assertThat(columnExists(QUE, "last_error")).isFalse();
        assertThat(columnExists(OUTBOX, "payload")).isFalse();
        assertThat(columnExists(OUTBOX, "status")).isFalse();
        assertThat(columnExists(OUTBOX, "retry_cnt")).isFalse();
        assertThat(columnExists(OUTBOX, "proc_dt")).isFalse();
    }

    @Test
    @DisplayName("폭_축소_2건이_표준도메인_크기로_적용된다")
    void narrowedColumnsMatchStandardDomainWidth() {
        // 코드값 표준도메인 V20 / 상태코드 표준용어 V16 — 형제 테이블(LS_BAT_RTY_WTNG 등)과 같은 형태다.
        assertThat(charMaxLength(QUE, "job_type_cd")).isEqualTo(20);
        assertThat(charMaxLength(QUE, "stts_cd")).isEqualTo(16);
        assertThat(charMaxLength(QUE, "last_err_msg_cn")).isEqualTo(2000);
        assertThat(charMaxLength(OUTBOX, "stts_cd")).isEqualTo(16);
    }

    @Test
    @DisplayName("개명된_컬럼의_기본값과_널제약이_보존된다")
    void renamedColumnsKeepDefaultsAndNullability() {
        assertThat(columnDefault(QUE, "stts_cd")).contains("PENDING");
        assertThat(columnDefault(QUE, "rtry_nmtm")).isEqualTo("0");
        assertThat(isNotNull(QUE, "reg_dt")).isTrue();
        assertThat(isNotNull(QUE, "bgng_dt")).isFalse();
        assertThat(columnDefault(OUTBOX, "stts_cd")).contains("PENDING");
        assertThat(columnDefault(OUTBOX, "rtry_nmtm")).isEqualTo("0");
        assertThat(isNotNull(OUTBOX, "prcs_dt")).isFalse();
    }

    @Test
    @DisplayName("아웃박스_식별자와_스냅샷_해시_컬럼은_이번에_바뀌지_않는다")
    void outboxIdentifierAndSnapshotHashAreOutOfScope() {
        // OUTBOX_SN 은 테이블명에서 온 이름이라 테이블명 축과 함께 간다.
        // ⚠ 이 테이블은 포털 메타 복제 철거(2026-08-31) 이후 <읽는 코드가 없다> — 이 시험은
        //   V5 개명이 컬럼에 실제로 적용됐는지를 DDL 수준에서 고정할 뿐이다.
        assertThat(columnExists(OUTBOX, "outbox_sn")).isTrue();
        assertThat(columnExists(OUTBOX, "snpsht_hash")).isTrue();
        assertThat(columnExists(QUE, "que_sn")).isTrue();
        assertThat(columnExists(QUE, "raw_sn")).isTrue();
    }

    // ------------------------------------------------------------------ 읽기 경로

    @Test
    @DisplayName("개명_후에도_라벨링_배치_큐가_PENDING_1건을_오래된_순으로_집는다")
    void queuePicksOldestPendingAfterRename() {
        // given — 오래된 것 먼저 적재
        long olderRaw = newRaw();
        long newerRaw = newRaw();
        long olderQueSn = queueService.enqueue(olderRaw).getQueSn();
        queueService.enqueue(newerRaw);
        // 등록일시가 같은 밀리초로 찍혀 순서가 흔들리지 않도록 앞선 행을 명시적으로 과거로 민다.
        jdbc.update("UPDATE LS_CLIP_SCHEDULE_QUE SET REG_DT = REG_DT - INTERVAL '1 hour' WHERE QUE_SN = ?",
                olderQueSn);

        // when
        Optional<LsClipScheduleQue> dequeued = queueService.dequeueOne();

        // then — 오래된 행이 나오고 IN_PROGRESS 로 전이된다
        assertThat(dequeued).isPresent();
        assertThat(dequeued.get().getQueSn()).isEqualTo(olderQueSn);
        assertThat(dequeued.get().getJobTypeCd()).isEqualTo(LsClipScheduleQue.JOB_LABELING_BATCH);
        assertThat(jdbc.queryForObject(
                "SELECT STTS_CD FROM LS_CLIP_SCHEDULE_QUE WHERE QUE_SN = ?", String.class, olderQueSn))
                .isEqualTo(LsClipScheduleQue.STATUS_IN_PROGRESS);
        assertThat(jdbc.queryForObject(
                "SELECT BGNG_DT FROM LS_CLIP_SCHEDULE_QUE WHERE QUE_SN = ?", java.sql.Timestamp.class, olderQueSn))
                .isNotNull();
    }

    // ------------------------------------------------------------------ 내부

    private long newRaw() {
        long rawSn = RawVideoFixture.newRaw(jdbc);
        seededRawSns.add(rawSn);
        return rawSn;
    }

    private boolean columnExists(String table, String column) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns"
                        + " WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                Long.class, table, column);
        return n != null && n > 0;
    }

    private Integer charMaxLength(String table, String column) {
        return jdbc.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns"
                        + " WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
    }

    private String columnDefault(String table, String column) {
        return jdbc.queryForObject(
                "SELECT column_default FROM information_schema.columns"
                        + " WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                String.class, table, column);
    }

    private boolean isNotNull(String table, String column) {
        return "NO".equals(jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns"
                        + " WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                String.class, table, column));
    }
}
