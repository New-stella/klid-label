package kr.co.cudo.authoring.batch.queue;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-ISSUE — 배치 큐 테이블 개명({@code MNG_CLIP_SCHEDULE_QUE} → {@code LS_CLIP_SCHEDULE_QUE}) +
 * {@code LS_DATA_RAW} 참조 무결성(FK + ON DELETE CASCADE) 실동작 검증
 * (V162, Testcontainers PostgreSQL 실 DB).
 *
 * <p>이 테이블은 이름만 {@code MNG_} 접두라 관제서버 소유(공유·읽기전용)로 오인됐고, 그 오인 때문에
 * {@code V146__add_ls_data_raw_child_fk.sql}(LS_DATA_RAW 자식 FK 전수 보강)에서 <b>제외</b>됐다.
 * 실제로는 저작도구가 {@code V2__phase3_video_queue_quartz.sql} 에서 직접 CREATE 한 자체 소유 배치 큐라,
 * 다른 자식 테이블과 동일하게 FK 로 고아를 구조적으로 차단해야 한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class LsClipScheduleQueFkIT {

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Autowired
    private LabelingBatchQueueService queueService;

    private JdbcTemplate jdbc;
    private final List<Long> seededRawSns = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        seededRawSns.clear();
    }

    @AfterEach
    void tearDown() {
        if (!seededRawSns.isEmpty()) {
            long[] rawSns = seededRawSns.stream().mapToLong(Long::longValue).toArray();
            RawVideoFixture.deleteRaws(jdbc, rawSns);
        }
    }

    private long newRaw() {
        long rawSn = RawVideoFixture.newRaw(jdbc);
        seededRawSns.add(rawSn);
        return rawSn;
    }

    private long countQueueRows(long rawSn) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM LS_CLIP_SCHEDULE_QUE WHERE RAW_SN = ?", Long.class, rawSn);
        return n == null ? 0L : n;
    }

    // ------------------------------------------------------------------ 개명

    @Test
    @DisplayName("배치큐_테이블은_LS_접두로_개명되고_구_MNG_이름은_남지_않는다")
    void queueTableIsRenamedToLsPrefix() {
        // 이름이 MNG_ 면 "관제 소유라 손대면 안 된다" 는 오판이 반복된다 — V146 이 실제로 그렇게 실패했다.
        assertThat(tableExists("ls_clip_schedule_que")).isTrue();
        assertThat(tableExists("mng_clip_schedule_que")).isFalse();
    }

    @Test
    @DisplayName("배치큐_인덱스도_LS_접두로_개명되었다")
    void queueIndexesAreRenamedToLsPrefix() {
        assertThat(indexExists("ix_ls_clip_schedule_que_status")).isTrue();
        assertThat(indexExists("ix_ls_clip_schedule_que_raw")).isTrue();
        assertThat(indexExists("ix_mng_clip_schedule_que_status")).isFalse();
        assertThat(indexExists("ix_mng_clip_schedule_que_raw")).isFalse();
    }

    // -------------------------------------------------------------------- FK

    @Test
    @DisplayName("배치큐_RAW_SN에_LS_DATA_RAW_참조_FK가_CASCADE_규칙으로_존재한다")
    void queueHasCascadeFkToDataRaw() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT rc.delete_rule, kcu.column_name
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.referential_constraints rc
                    ON rc.constraint_name = tc.constraint_name
                  JOIN information_schema.key_column_usage kcu
                    ON kcu.constraint_name = tc.constraint_name
                  JOIN information_schema.constraint_column_usage ccu
                    ON ccu.constraint_name = tc.constraint_name
                 WHERE tc.constraint_type = 'FOREIGN KEY'
                   AND tc.table_name = 'ls_clip_schedule_que'
                   AND ccu.table_name = 'ls_data_raw'
                """);

        assertThat(rows).as("ls_clip_schedule_que 에 ls_data_raw 참조 FK 가 있어야 한다").isNotEmpty();
        assertThat(rows.get(0).get("column_name")).isEqualTo("raw_sn");
        assertThat(rows.get(0).get("delete_rule")).isEqualTo("CASCADE");
    }

    @Test
    @DisplayName("RAW_SN이_삭제되면_배치큐_행도_CASCADE로_함께_삭제된다")
    void deletingRawCascadesToQueueRow() {
        // given — 실재하는 영상 1건 + 그 영상을 참조하는 큐 행 1건(운영 경로와 동일하게 서비스로 적재)
        long rawSn = newRaw();
        LsClipScheduleQue enqueued = queueService.enqueue(rawSn);
        assertThat(enqueued.getQueSn()).isNotNull();
        assertThat(countQueueRows(rawSn)).isEqualTo(1L);

        // when — 영상 원본 삭제
        jdbc.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", rawSn);

        // then — 큐 행도 함께 사라진다 (FK 부재 시 고아로 잔존해 실패)
        assertThat(countQueueRows(rawSn)).isZero();
    }

    @Test
    @DisplayName("존재하지_않는_영상을_참조하는_배치큐_INSERT는_거부된다")
    void insertingOrphanQueueRowIsRejected() {
        // given — 실재하지 않는 rawSn
        long ghostRawSn = 9_999_999_998L;

        // when / then — FK 위반으로 애초에 고아를 만들 수 없다
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO LS_CLIP_SCHEDULE_QUE (RAW_SN, JOB_TYPE, STATUS) VALUES (?, ?, ?)",
                ghostRawSn, LsClipScheduleQue.JOB_LABELING_BATCH, LsClipScheduleQue.STATUS_PENDING))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------ 내부

    private boolean tableExists(String tableName) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = ?", Long.class, tableName);
        return n != null && n > 0;
    }

    private boolean indexExists(String indexName) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = ?", Long.class, indexName);
        return n != null && n > 0;
    }
}
