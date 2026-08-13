package kr.co.cudo.authoring.common.migration;

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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-ISSUE-01 — {@code LS_DATA_RAW} 참조 무결성(FK + ON DELETE CASCADE) 실동작 검증
 * (V146, Testcontainers PostgreSQL 실 DB).
 *
 * <p>구 스키마는 {@code LS_DATA_RAW} 를 참조하는 FK 가 {@code LS_EVNT_ANNO} 단 1건뿐이라, 영상 행이
 * 사라져도 자식(마킹·프레임·상태 등)이 고아로 남았다(실측: rawSn 23/24/25 삭제 후 {@code LS_MARKING}
 * 고아 2행). Mockito 로는 FK 가 전혀 검증되지 않으므로 실 DB 로 다음을 고정한다.
 * <ol>
 *   <li>영상 삭제 시 자식이 함께 사라진다(CASCADE) — 고아가 구조적으로 불가능.</li>
 *   <li>존재하지 않는 영상을 참조하는 자식 INSERT 는 거부된다(고아 생성 자체 차단).</li>
 *   <li>대상 자식 테이블 전량에 FK 가 실제로 걸려 있고 삭제 규칙이 확정 정책과 일치한다.</li>
 *   <li>원장/세션 2건은 CASCADE 가 아니라 SET NULL — 행은 살아남고 참조만 끊긴다.</li>
 *   <li>관제 공유(MNG_*) 테이블에는 FK 를 걸지 않았다(관제팀 선승인 대상).</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("local")
class LsDataRawChildFkCascadeIT {

    /** V146 이 FK 를 건 자식 (테이블, 컬럼, 삭제규칙). 마이그레이션 목록과 동일해야 한다. */
    private static final List<String[]> EXPECTED_FKS = List.of(
            new String[]{"ls_bat_rty_wtng", "raw_sn", "CASCADE"},
            new String[]{"ls_batch_proc_log", "data_raw_sn", "CASCADE"},
            new String[]{"ls_data_lbl_ai_info", "data_raw_sn", "CASCADE"},
            new String[]{"ls_data_meta", "raw_sn", "CASCADE"},
            new String[]{"ls_data_src", "raw_sn", "CASCADE"},
            new String[]{"ls_deident_proc_log", "data_raw_sn", "CASCADE"},
            new String[]{"ls_marking", "raw_sn", "CASCADE"},
            new String[]{"ls_auth_work_lock", "data_raw_sn", "CASCADE"},
            new String[]{"ls_data_issue", "data_raw_sn", "CASCADE"},
            new String[]{"ls_data_meta_review", "data_raw_sn", "CASCADE"},
            new String[]{"ls_deident_report", "data_raw_sn", "CASCADE"},
            // ls_raw_data_enrollment 은 V3(사용처 0 테이블 제거)로 테이블째 사라져 FK 검증 대상이 아니다.
            new String[]{"ls_raw_data_status", "raw_data_id", "CASCADE"},
            new String[]{"ls_task_assign_history", "raw_data_id", "CASCADE"},
            new String[]{"ls_task_assignment", "raw_data_id", "CASCADE"},
            new String[]{"ls_task_event_log", "raw_data_id", "CASCADE"},
            new String[]{"ls_data_aug_rvw", "data_raw_sn", "CASCADE"},
            new String[]{"ls_data_raw_hstry", "raw_sn", "CASCADE"},
            new String[]{"ls_dataset_export", "data_raw_sn", "CASCADE"},
            new String[]{"ls_dataset_video_meta", "raw_sn", "CASCADE"},
            new String[]{"ls_label_version", "data_raw_sn", "CASCADE"},
            new String[]{"ls_control_notify_fallback", "raw_sn", "CASCADE"},
            new String[]{"ls_meta_repl_outbox", "raw_sn", "CASCADE"},
            new String[]{"ls_mon_noti_acml", "raw_sn", "CASCADE"},
            new String[]{"ls_portal_user_label", "src_raw_sn", "CASCADE"},
            new String[]{"ls_tus_upload", "raw_sn", "SET NULL"},
            new String[]{"ls_webhook_idempotency", "raw_sn", "SET NULL"});

    private static final String CLIP_ID = "FKCASCADE-CLIP-1";

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        // CASCADE 로 자식이 함께 지워진다 — 부모만 지우면 된다(FK 부재 시엔 자식이 남아 후속 테스트가 실패).
        jdbc.update("DELETE FROM LS_DATA_RAW WHERE VMS_CLIP_ID LIKE 'FKCASCADE-CLIP-%'");
        jdbc.update("DELETE FROM LS_TUS_UPLOAD WHERE VMS_CLIP_ID LIKE 'FKCASCADE-CLIP-%'");
        jdbc.update("DELETE FROM LS_WEBHOOK_IDEMPOTENCY WHERE IDMP_KEY LIKE 'FKCASCADE-%'");
    }

    private long insertRaw() {
        return jdbc.queryForObject("""
                INSERT INTO LS_DATA_RAW
                    (VMS_CLIP_ID, VMS_CCTV_ID, PRVC_TYPE_CD, RAW_FILE_PATH_NM, DATA_STTS_CD)
                VALUES (?, 'CCTV-FK', 'ANONY', '/nas/fk.mp4', 'PENDING')
                RETURNING RAW_SN
                """, Long.class, CLIP_ID);
    }

    private long countBy(String table, String column, long rawSn) {
        Long n = jdbc.queryForObject(
                String.format("SELECT count(*) FROM %s WHERE %s = ?", table, column), Long.class, rawSn);
        return n == null ? 0L : n;
    }

    @Test
    @DisplayName("영상_삭제시_자식_마킹_프레임_상태가_CASCADE로_함께_삭제된다")
    void deletingRawCascadesToChildren() {
        // given — 영상 + 대표 자식 3종(마킹/프레임/작업상태)
        long rawSn = insertRaw();
        jdbc.update("INSERT INTO LS_MARKING (RAW_SN, EVNT_NM, VIDEO_FILE_PATH_NM, MARK_MODE_CD, STTS_CD, MARK_CN, REG_DT) "
                + "VALUES (?, 'FK-TEST', '/nas/fk.mp4', 'AUTO', 'PENDING', '[]', ?)", rawSn, LocalDateTime.now());
        jdbc.update("INSERT INTO LS_DATA_SRC (RAW_SN, FRM_NO, SRC_FILE_PATH_NM) VALUES (?, 0, ?)",
                rawSn, "/nas/frames/raw/" + rawSn + "/0.jpg");
        jdbc.update("INSERT INTO LS_RAW_DATA_STATUS (RAW_DATA_ID, DATA_STTS_CD, STP_CYCL, IGI_CYCL, UPD_DT) "
                + "VALUES (?, 'ASSIGNED', 0, 0, ?)", rawSn, LocalDateTime.now());
        assertThat(countBy("LS_MARKING", "RAW_SN", rawSn)).isEqualTo(1L);

        // when — 영상 삭제
        jdbc.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", rawSn);

        // then — 고아가 남지 않는다 (FK/CASCADE 제거 시 마킹 1행이 남아 실패)
        assertThat(countBy("LS_MARKING", "RAW_SN", rawSn)).isZero();
        assertThat(countBy("LS_DATA_SRC", "RAW_SN", rawSn)).isZero();
        assertThat(countBy("LS_RAW_DATA_STATUS", "RAW_DATA_ID", rawSn)).isZero();
    }

    @Test
    @DisplayName("존재하지_않는_영상을_참조하는_자식_INSERT는_거부된다")
    void insertingOrphanChildIsRejected() {
        // given — 실재하지 않는 rawSn
        long ghostRawSn = 9_999_999_999L;

        // when / then — FK 위반으로 애초에 고아를 만들 수 없다
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO LS_MARKING (RAW_SN, EVNT_NM, VIDEO_FILE_PATH_NM, MARK_MODE_CD, STTS_CD, MARK_CN, REG_DT) "
                        + "VALUES (?, 'FK-TEST', '/nas/fk.mp4', 'AUTO', 'PENDING', '[]', ?)", ghostRawSn, LocalDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("원장_세션_참조는_SET_NULL로_끊기고_행_자체는_보존된다")
    void ledgerRowsSurviveWithNullReference() {
        // given — 웹훅 멱등 원장 1행이 영상을 참조
        long rawSn = insertRaw();
        jdbc.update("INSERT INTO LS_WEBHOOK_IDEMPOTENCY (IDMP_KEY, CHNL_CD, STTS_CD, RAW_SN, REG_DT, MDFCN_DT) "
                        + "VALUES (?, 'AUGMENT', 'APPLIED', ?, ?, ?)",
                "FKCASCADE-KEY-1", rawSn, LocalDateTime.now(), LocalDateTime.now());

        // when — 영상 삭제
        jdbc.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", rawSn);

        // then — 원장 행은 남고(재전송 방지 유지) 참조만 NULL
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT RAW_SN FROM LS_WEBHOOK_IDEMPOTENCY WHERE IDMP_KEY = ?", "FKCASCADE-KEY-1");
        assertThat(row.get("raw_sn")).isNull();
    }

    @Test
    @DisplayName("대상_자식_테이블_전량에_FK가_확정_삭제규칙으로_생성되어_있다")
    void allExpectedChildFksExistWithConfirmedDeleteRule() {
        for (String[] spec : EXPECTED_FKS) {
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
                       AND tc.table_name = ?
                       AND ccu.table_name = 'ls_data_raw'
                    """, spec[0]);
            assertThat(rows).as("%s 에 ls_data_raw 참조 FK 가 있어야 한다", spec[0]).isNotEmpty();
            assertThat(rows.get(0).get("column_name")).as("%s FK 컬럼", spec[0]).isEqualTo(spec[1]);
            assertThat(rows.get(0).get("delete_rule")).as("%s 삭제 규칙", spec[0]).isEqualTo(spec[2]);
        }
    }

    @Test
    @DisplayName("기존_LS_EVNT_ANNO_FK도_CASCADE로_통일되어_부모_삭제를_막지_않는다")
    void evntAnnoFkIsCascade() {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT rc.delete_rule
                  FROM information_schema.referential_constraints rc
                 WHERE rc.constraint_name = 'fk_ls_evnt_anno_raw'
                """);
        assertThat(row.get("delete_rule")).isEqualTo("CASCADE");
    }

    @Test
    @DisplayName("MNG_로_오인됐던_배치큐는_LS_로_개명되어_FK_대상에_편입됐다")
    void misnamedQueueTableWasRenamedAndCovered() {
        // V146 은 이 테이블을 이름만 보고 "관제서버 소유(MNG_*)" 로 판단해 FK 대상에서 제외했으나,
        // 실제로는 저작도구가 V2 에서 직접 CREATE 한 자체 소유 배치 큐였다(관제 미참조).
        // V162 가 LS_CLIP_SCHEDULE_QUE 로 개명하고 누락된 FK 를 보강했다 — 상세 검증은
        // batch.queue.LsClipScheduleQueFkIT 가 담당한다. 여기서는 구 이름이 남지 않았음만 고정한다.
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'mng_clip_schedule_que'",
                Long.class);
        assertThat(n).isZero();
    }

    @Test
    @DisplayName("파생_계보_ORGNL_RAW_SN에는_FK를_걸지_않았다")
    void derivativeLineageColumnHasNoFk() {
        // 자식이 아니라 self-reference 계보이며, 고아 자동 복구(NULL/삭제)가 둘 다 위험해 별건으로 남긴다.
        Long n = jdbc.queryForObject("""
                SELECT count(*)
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.key_column_usage kcu
                    ON kcu.constraint_name = tc.constraint_name
                 WHERE tc.constraint_type = 'FOREIGN KEY'
                   AND tc.table_name = 'ls_data_raw'
                   AND kcu.column_name = 'orgnl_raw_sn'
                """, Long.class);
        assertThat(n).isZero();
    }
}
