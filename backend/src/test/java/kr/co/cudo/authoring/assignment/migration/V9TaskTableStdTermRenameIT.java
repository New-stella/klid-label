package kr.co.cudo.authoring.assignment.migration;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.support.RawVideoFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V9 개명이 <b>실제 적용된 스키마</b>에 반영됐고, 그 위에서 엔티티 매핑이 그대로 붙는지 확인한다
 * (Testcontainers PostgreSQL).
 *
 * <p>표준용어 근거는 마이그레이션 헤더에 있다. 여기서는 <b>결과 형상</b>과 <b>엔티티가 실제로 붙는지</b>만
 * 고정한다. 후자가 이 파일의 존재 이유다 — {@code @Table(name=...)} 만 바꾸고 마이그레이션을 빠뜨리거나
 * 그 반대여도 <b>컴파일은 통과</b>하고, 어긋남은 런타임 SQL 에서야 드러난다. 이 저장소는
 * {@code ddl-auto=validate} 가 EMF 로 전달되지 않아 <b>부팅 시 스키마 검증이 실제로 수행되지 않으므로</b>
 * 기동 성공은 근거가 되지 못한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class V9TaskTableStdTermRenameIT {

    private static final String NEW_ASGN = "ls_task_altmnt";
    private static final String NEW_EVT = "ls_task_evnt_log";

    /** 옛 물리명 — 라이브 스키마에 하나도 남아 있으면 안 된다. */
    private static final List<String> STALE_NAMES = List.of(
            "ls_task_assignment",
            "ls_task_event_log",
            "ls_task_assignment_assignment_id_seq",
            "ls_task_event_log_event_seq_seq",
            "ls_task_assignment_pkey",
            "ls_task_event_log_pkey",
            "uk_ls_task_assignment",
            "ix_ls_task_assignment_raw",
            "ix_ls_task_assignment_user",
            "ix_ls_task_event_log_actor",
            "ix_ls_task_event_log_raw",
            "fk_ls_task_assignment_raw",
            "fk_ls_task_event_log_raw");

    /** 새 물리명 13종 — 전부 실재해야 한다. */
    private static final List<String> EXPECTED_NAMES = List.of(
            NEW_ASGN,
            NEW_EVT,
            "ls_task_altmnt_assignment_id_seq",
            "ls_task_evnt_log_evnt_id_seq",
            "ls_task_altmnt_pkey",
            "ls_task_evnt_log_pkey",
            "uk_ls_task_altmnt",
            "ix_ls_task_altmnt_raw",
            "ix_ls_task_altmnt_user",
            "ix_ls_task_evnt_log_actor",
            "ix_ls_task_evnt_log_raw",
            "fk_ls_task_altmnt_raw",
            "fk_ls_task_evnt_log_raw");

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Autowired
    private LsTaskAssignmentRepository assignmentRepository;

    @Autowired
    private LsTaskEventLogRepository eventLogRepository;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
    }

    // ------------------------------------------------------------------ 형상

    @Test
    @DisplayName("옛_물리명_13종이_라이브_스키마에_하나도_남지_않는다")
    void noStalePhysicalNameRemains() {
        // when — 관계(테이블·시퀀스·인덱스)와 제약을 <함께> 센다. 한쪽만 보면 제약 이름 잔재를 놓친다.
        List<String> found = new ArrayList<>();
        for (String name : STALE_NAMES) {
            if (relExists(name) || constraintExists(name)) {
                found.add(name);
            }
        }

        // then
        assertThat(found)
                .as("V9 개명 이후에도 옛 이름이 카탈로그에 남아 있다 — ALTER TABLE ... RENAME TO 는 "
                        + "시퀀스·제약·인덱스 이름을 따라오게 하지 않으므로 하나씩 빠뜨리기 쉽다")
                .isEmpty();
    }

    @Test
    @DisplayName("새_물리명_13종이_전부_존재한다")
    void everyRenamedObjectExists() {
        // when
        List<String> missing = new ArrayList<>();
        for (String name : EXPECTED_NAMES) {
            if (!relExists(name) && !constraintExists(name)) {
                missing.add(name);
            }
        }

        // then
        assertThat(missing).as("표준용어 물리명으로 존재해야 할 객체가 없다").isEmpty();
    }

    @Test
    @DisplayName("컬럼은_하나도_개명되지_않았다")
    void noColumnWasRenamed() {
        // 컬럼은 전부 등록 용어라 이미 정합이다 — 테이블 축과 함께 건드리면 범위가 어긋난다.
        assertThat(columnNames(NEW_ASGN))
                .containsExactly("assignment_id", "user_no", "raw_data_id", "task_type_cd",
                        "reg_user_no", "reg_dt", "ver");
        // ★ 뒤에 <더해진> 컬럼은 이 단언을 넓혀 받는다 — 개명이 아니므로 이 가드가 막을 대상이 아니다.
        //   actor_role_cd 는 V38 이 더한 「행위 시점의 행위자 역할」이며 맨 끝에 붙는다(ADR-067).
        assertThat(columnNames(NEW_EVT))
                .containsExactly("evnt_id", "raw_data_id", "evnt_type_cd", "actor_user_no",
                        "subject_user_no", "prev_user_no", "rsn", "ocrn_dt", "actor_role_cd");
    }

    @Test
    @DisplayName("이벤트로그_시퀀스_이름이_실재하지_않던_컬럼명_잔재에서_실제_컬럼명으로_바로잡혔다")
    void eventLogSequenceNameNowMatchesItsColumn() {
        // 옛 이름은 존재하지 않는 event_seq 컬럼을 달고 있었다(과거 컬럼 개명이 시퀀스를 빠뜨린 잔재).
        String sequence = jdbc.queryForObject(
                "SELECT pg_get_serial_sequence(?, 'evnt_id')", String.class, NEW_EVT);
        assertThat(sequence)
                .as("IDENTITY 연결이 끊기지 않고 이름만 실제 컬럼명(evnt_id)에 맞게 바뀌어야 한다")
                .isNotNull()
                .endsWith(".ls_task_evnt_log_evnt_id_seq");
    }

    // ------------------------------------------------------------------ 엔티티 매핑

    @Test
    @DisplayName("두_엔티티가_개명된_테이블에_실제로_매핑되어_저장_조회된다")
    void entitiesMapToRenamedTables() {
        // given — 엔티티로 저장한다(매핑이 어긋나면 여기서 relation 없음 오류가 난다)
        long rawSn = RawVideoFixture.newRaw(jdbc);
        try {
            LsTaskAssignment saved = assignmentRepository.save(
                    LsTaskAssignment.builder()
                            .userNo(920001L)
                            .rawDataId(rawSn)
                            .taskTypeCd(LsTaskAssignment.TASK_LABELER)
                            .regUserNo(920002L)
                            .regDt(LocalDateTime.now())
                            .build());
            eventLogRepository.save(
                    LsTaskEventLog.assign(rawSn, 920002L, 920001L));

            // then — 엔티티가 쓴 값이 <새 이름 테이블>에서 읽힌다(엔티티↔DB 양방향 확인)
            assertThat(saved.getAssignmentId()).isNotNull();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM " + NEW_ASGN + " WHERE raw_data_id = ?", Integer.class, rawSn))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM " + NEW_EVT + " WHERE raw_data_id = ?", Integer.class, rawSn))
                    .isEqualTo(1);
            assertThat(assignmentRepository.findById(saved.getAssignmentId())).isPresent();
        } finally {
            RawVideoFixture.deleteRaws(jdbc, rawSn);
        }
    }

    // ------------------------------------------------------------------ 내부

    private boolean relExists(String name) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE n.nspname = current_schema() AND c.relname = ?",
                Integer.class, name);
        return count != null && count > 0;
    }

    private boolean constraintExists(String name) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace"
                        + " WHERE n.nspname = current_schema() AND c.conname = ?",
                Integer.class, name);
        return count != null && count > 0;
    }

    private List<String> columnNames(String table) {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = current_schema() AND table_name = ?"
                        + " ORDER BY ordinal_position",
                String.class, table);
    }
}
