package kr.co.cudo.authoring.controlnotify.migration;

import kr.co.cudo.authoring.controlnotify.debounce.LsMonNotiAcml;
import kr.co.cudo.authoring.controlnotify.debounce.LsMonNotiAcmlRepository;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V7 — {@code LS_MON_NOTI_ACML.STTS_CD} 를 표준도메인 「상태코드 = V/16」에 맞춰 20 → 16 으로 줄인
 * 결과가 <b>실제 적용된 스키마</b>에 반영됐고, 그 위에서 디바운스 윈도우 경로가 그대로 도는지 확인한다
 * (Testcontainers PostgreSQL).
 *
 * <p>표준용어·표준도메인 근거는 마이그레이션 헤더에 있다. 여기서는 <b>결과 형상</b>만 고정한다 —
 * 폭 16, 그리고 폭 변경이 <b>딸려 무너뜨리기 쉬운 것</b>(기본값 · NOT NULL · 이 컬럼을 물고 있는
 * 인덱스 3종)이 그대로인지까지. 형제 테이블({@code LS_CLIP_SCHEDULE_QUE} · {@code LS_META_REPL_OUTBOX})
 * 은 V5 에서 이미 16 이라 이 테이블만 남아 있었다.
 *
 * @design ERD-027
 */
@SpringBootTest
@ActiveProfiles("local")
class V7MonNotiAcmlSttsWidthIT {

    private static final String TABLE = "ls_mon_noti_acml";
    private static final String COLUMN = "stts_cd";

    /** 표준도메인 「상태코드」 = VARCHAR(16). 형제 테이블(V5)과 같은 폭이다. */
    private static final int STANDARD_STATUS_CODE_WIDTH = 16;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Autowired
    @Qualifier("controlTransactionManager")
    private PlatformTransactionManager txManager;

    @Autowired
    private LsMonNotiAcmlRepository repository;

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

    // ------------------------------------------------------------------ 형상

    @Test
    @DisplayName("상태코드_컬럼_폭이_표준도메인_16자다")
    void statusColumnMatchesStandardDomainWidth() {
        assertThat(charMaxLength(TABLE, COLUMN)).isEqualTo(STANDARD_STATUS_CODE_WIDTH);
    }

    @Test
    @DisplayName("폭_축소_후에도_기본값과_널제약이_보존된다")
    void statusColumnKeepsDefaultAndNotNull() {
        // ALTER … TYPE 이 DEFAULT 를 재캐스팅하면서 떨어뜨리면 새 윈도우가 상태 없이 INSERT 되어
        // 부분 유니크(STTS_CD='PENDING')가 걸리지 않는다 — 영상당 윈도우 1개 불변식이 조용히 깨진다.
        assertThat(columnDefault(TABLE, COLUMN)).contains(LsMonNotiAcml.STATUS_PENDING);
        assertThat(isNotNull(TABLE, COLUMN)).isTrue();
    }

    @Test
    @DisplayName("상태코드를_물고_있는_인덱스_3종이_그대로_남는다")
    void statusIndexesSurviveNarrowing() {
        // 복합 인덱스 2종 — flush 후보 조회(PENDING+REG_DT / FLUSHING+MDFCN_DT)의 접근 경로다.
        assertThat(indexDef("idx_lmna_stts_reg")).contains(COLUMN).contains("reg_dt");
        assertThat(indexDef("idx_lmna_stts_mdfcn")).contains(COLUMN).contains("mdfcn_dt");

        // 부분 유니크 — 술어에 이 컬럼이 있다. 술어가 사라지면 영상당 열린 윈도우 1개가 무너진다.
        assertThat(indexDef("uk_lmna_raw_pending"))
                .contains("UNIQUE")
                .contains("raw_sn")
                .contains(LsMonNotiAcml.STATUS_PENDING);
    }

    // ------------------------------------------------------------------ 읽기·쓰기 경로

    @Test
    @DisplayName("폭_축소_후에도_PENDING_개시부터_FLUSHING_클레임까지_동작한다")
    void debounceWindowLifecycleWorksAfterNarrowing() {
        // given — 실제로 저장되는 값은 PENDING(7자) · FLUSHING(8자) 둘뿐이라 16 에 여유가 있다
        long rawSn = newRaw();
        LocalDateTime now = LocalDateTime.now();

        // when — 윈도우 개시
        Integer inserted = txTemplate.execute(s -> repository.insertPendingIfAbsent(rawSn, now));

        // then
        assertThat(inserted).isEqualTo(1);
        assertThat(statusOf(rawSn)).isEqualTo(LsMonNotiAcml.STATUS_PENDING);

        // when — 만료된 윈도우를 한 노드가 클레임
        Long anchor = jdbc.queryForObject(
                "SELECT NOTI_ACML_SN FROM LS_MON_NOTI_ACML WHERE RAW_SN = ?", Long.class, rawSn);
        Integer claimed = txTemplate.execute(s -> repository.claimForFlush(
                anchor, now.plusMinutes(1), now.plusMinutes(1), now.plusSeconds(1)));

        // then — FLUSHING(8자)까지 잘림 없이 전이된다
        assertThat(claimed).isEqualTo(1);
        assertThat(statusOf(rawSn)).isEqualTo(LsMonNotiAcml.STATUS_FLUSHING);

        // when / then — 발송 완료 후 행 삭제
        Integer deleted = txTemplate.execute(s -> repository.deleteFlushed(anchor));
        assertThat(deleted).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 내부

    private long newRaw() {
        long rawSn = RawVideoFixture.newRaw(jdbc);
        seededRawSns.add(rawSn);
        return rawSn;
    }

    private String statusOf(long rawSn) {
        return jdbc.queryForObject(
                "SELECT STTS_CD FROM LS_MON_NOTI_ACML WHERE RAW_SN = ?", String.class, rawSn);
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

    private String indexDef(String indexName) {
        List<String> defs = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes"
                        + " WHERE schemaname = current_schema() AND tablename = ? AND indexname = ?",
                String.class, TABLE, indexName);
        assertThat(defs).as("인덱스 %s 가 존재해야 한다", indexName).hasSize(1);
        return defs.get(0);
    }
}
