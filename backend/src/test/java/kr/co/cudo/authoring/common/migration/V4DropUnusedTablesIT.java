package kr.co.cudo.authoring.common.migration;

import kr.co.cudo.authoring.support.RawVideoFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.FileCopyUtils;

import javax.sql.DataSource;

import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V4(사용처 0 테이블 4종 제거) 가드 — <b>지워졌는가</b>와 <b>안전하게 지웠는가</b>를 함께 고정한다.
 *
 * <h3>무엇을 지웠나</h3>
 * <ul>
 *   <li>{@code LS_COM_CD} — 조회 코드·API·화면 참조 0. 시드 5행은 {@code LsRawDataStatus} 자바 상수와
 *       중복된 <b>두 번째 진실원</b>이었다.</li>
 *   <li>{@code LS_DATA_META_HSTRY} · {@code LS_DATA_RAW_HSTRY} — 리포지토리는 있으나 주입·호출하는
 *       클래스가 0건.</li>
 *   <li>{@code LS_TASK_ASSIGN_HISTORY} — 재배정이 이 테이블과 {@code LS_TASK_EVNT_LOG}(V4 당시
 *       물리명은 {@code LS_TASK_EVENT_LOG}, V9 에서 개명) 에 <b>이중
 *       기록</b>하는데, 조회 API 는 이벤트 로그만 읽었다(완전한 쓰기 중복).</li>
 * </ul>
 *
 * <h3>두 경로가 하는 일이 다르다</h3>
 * <ul>
 *   <li><b>신규 설치</b> — V1 에서 정의를 덜어냈으므로 애초에 만들지 않는다. V4 는 완전 no-op.
 *       그 "덜어냄" 자체는 {@link #신규_설치는_애초에_네_테이블을_만들지_않는다()} 가 지킨다.</li>
 *   <li><b>기존 DB</b> — 스쿼시 이전에 만들어져 실재하므로 V4 가 DROP 한다. 그 경로의 전제 판정을
 *       아래 fail-closed 케이스들이 지킨다.</li>
 * </ul>
 *
 * <h3>왜 fail-closed 를 테스트로 고정하나</h3>
 * <p>제거 근거는 "행이 없다 / 감사 증적이 이미 다른 테이블로 옮겨져 있다"는 <b>전제</b>다. 그 전제가
 * 깨진 DB 가 있다면 조용히 지우는 것이 곧 비가역 데이터 손실이다. 그래서 V4 는 전제 위반 시 DROP 하지
 * 않고 기동을 멈춘다. 그 중단이 실제로 발화하는지는 <b>중단 케이스를 만들어 봐야만</b> 알 수 있다 —
 * 조건문을 잘못 쓰면 항상 참/항상 거짓이 되어도 정상 경로는 똑같이 통과하기 때문이다.
 *
 * <p>반대 방향(과도한 엄격)도 함께 고정한다: 대응 이벤트 로그가 <b>있는</b> 이력은 통과해야 한다.
 * 이 술어가 틀리면 dev(실측 17행)에서 앱이 기동하지 못한다.
 *
 * @design D9
 */
@SpringBootTest
@ActiveProfiles("local")
class V4DropUnusedTablesIT {

    /** V4 가 제거하는 테이블(정보 스키마 소문자). */
    private static final List<String> DROPPED_TABLES = List.of(
            "ls_com_cd",
            "ls_data_meta_hstry",
            "ls_data_raw_hstry",
            "ls_task_assign_history");

    /**
     * V4 재실행을 위해 이 테스트가 <b>임시로 되살리는</b> 테이블 — V9 가 개명해 라이브 스키마에는
     * 없는 이름이다. {@link #DROPPED_TABLES} 와 분리해 둔 이유는 그쪽이 "V4 가 지웠는가" 단언의
     * 목록이기도 해서, 여기에 섞으면 그 단언이 대상을 잘못 잡기 때문이다.
     */
    private static final List<String> SCRATCH_TABLES = List.of("ls_task_event_log");

    /** 배포되는 베이스라인 — {@code LS_} 접두 테이블 총수의 근거 파일. */
    private static final Path BASELINE = Path.of("src/main/resources/db/migration/V1__baseline.sql");

    /**
     * 베이스라인이 만드는 {@code LS_} 접두 테이블 수.
     *
     * <p><b>왜 살아 있는 DB 를 세지 않나</b>: 테스트는 컨텍스트 캐시로 <b>한 컨테이너를 공유</b>하고,
     * 일부 마이그레이션 IT 가 구 테이블({@code ls_resolution_export} 등)을 재생성해 재생한다. 살아 있는
     * 카탈로그를 세면 실행 순서에 따라 값이 흔들려 <b>가드가 아니라 잡음</b>이 된다. 그래서 배포되는
     * 정의 파일에서 센다 — 이 수치가 바뀌면 스키마 정의가 바뀐 것이고, 그건 의도적 변경이어야 한다.
     *
     * <p>V4 가 <b>빼는 것이 아니라</b> 베이스라인이 처음부터 이 수만큼만 만든다 — 4종 정의를 V1 에서
     * 덜어냈기 때문이다(기록된 예외 2건). 그래서 신규 설치에서 V4 는 완전 no-op 이다.
     */
    private static final int EXPECTED_LS_TABLE_COUNT = 58;

    /** 베이스라인 시드 행수 — {@code ls_system_config} 12 + {@code qrtz_locks} 2. 구 {@code ls_com_cd} 5행 소멸. */
    private static final int EXPECTED_SEED_ROWS = 14;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc;
    private String migrationSql;

    @BeforeEach
    void setUp() throws IOException {
        jdbc = new JdbcTemplate(controlDataSource);
        migrationSql = FileCopyUtils.copyToString(new InputStreamReader(
                new ClassPathResource("db/migration/V4__drop_unused_tables_round2.sql").getInputStream(),
                StandardCharsets.UTF_8));
    }

    /**
     * 중단 케이스는 DO 블록이 예외로 빠져 DROP 이 롤백되지만, <b>테스트가 되살린 테이블</b> 자체는
     * 별개 문장이라 남는다. 공유 컨테이너를 오염시키지 않도록 매번 지운다.
     */
    @AfterEach
    void tearDown() {
        for (String table : DROPPED_TABLES) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        for (String table : SCRATCH_TABLES) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    @Test
    @DisplayName("사용처_0_테이블_4종이_스키마에서_사라진다")
    void 사용처_0_테이블_4종이_스키마에서_사라진다() {
        // given / when — 컨텍스트 기동 시 Flyway 가 V4 까지 적용을 마쳤다

        // then
        for (String table : DROPPED_TABLES) {
            assertThat(tableExists(table))
                    .as("%s 는 V4 가 제거했으므로 남아 있으면 안 된다", table)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("베이스라인이_만드는_LS_접두_테이블은_58개다")
    void 베이스라인이_만드는_LS_접두_테이블은_58개다() throws IOException {
        // given / when — V3·V4 가 덜어낸 7종은 이미 정의에 없다
        String baseline = Files.readString(BASELINE);
        long created = baseline.lines().filter(l -> l.startsWith("CREATE TABLE ls_")).count();

        // then
        assertThat(created)
                .as("V1 베이스라인의 LS_ 접두 CREATE TABLE 수")
                .isEqualTo(EXPECTED_LS_TABLE_COUNT);
    }

    /**
     * 기록된 예외 2건의 <b>본체 가드</b> — V1 에서 4종 정의를 실제로 덜어냈는지 본다.
     *
     * <p>이게 없으면 누군가 V1 을 되돌려도(예: 덤프 재생성 실수로 4종이 다시 들어와도) 겉으로는 아무
     * 일도 안 일어난다 — V4 가 조용히 지워 주기 때문에 <b>최종 스키마가 같아 다른 테스트가 전부
     * 통과</b>한다. 신규 설치가 "만들었다 지우는 왕복"으로 되돌아간 것을 잡는 유일한 지점이다.
     */
    @Test
    @DisplayName("신규_설치는_애초에_네_테이블을_만들지_않는다")
    void 신규_설치는_애초에_네_테이블을_만들지_않는다() throws IOException {
        // given
        String baseline = Files.readString(BASELINE);

        // when / then — CREATE TABLE·시퀀스·제약·인덱스·FK·시드 어느 형태로도 남아 있으면 안 된다.
        //   주석에는 제거 근거가 적혀 있으므로 <실행 구문 라인만> 본다(주석은 '--' 로 시작).
        List<String> statements = baseline.lines()
                .filter(l -> !l.stripLeading().startsWith("--"))
                .toList();
        for (String table : DROPPED_TABLES) {
            assertThat(statements)
                    .as("V1 베이스라인의 실행 구문에 %s 가 남아 있으면 신규 설치가 다시 만든다", table)
                    .noneMatch(l -> l.toLowerCase().contains(table));
        }

        // 시드도 함께 덜어냈다 — ls_com_cd 5행이 사라져 19 → 14행.
        assertThat(statements.stream().filter(l -> l.startsWith("INSERT INTO ")).count())
                .as("V1 베이스라인 시드 행수")
                .isEqualTo(EXPECTED_SEED_ROWS);
    }

    @Test
    @DisplayName("V4를_두_번_적용해도_안전하다")
    void V4를_두_번_적용해도_안전하다() {
        // given — 이미 1회 적용된 상태(컨텍스트 기동 시)

        // when / then — 대상이 없으므로 완전 no-op
        assertThatCode(() -> jdbc.execute(migrationSql)).doesNotThrowAnyException();
        for (String table : DROPPED_TABLES) {
            assertThat(tableExists(table)).isFalse();
        }
    }

    @Test
    @DisplayName("대응_이벤트로그가_없는_배정이력이_있으면_마이그레이션이_중단된다")
    void 대응_이벤트로그가_없는_배정이력이_있으면_마이그레이션이_중단된다() {
        // given — 감사 증적이 이벤트 로그로 옮겨지지 않은 이력 1행
        recreateTaskAssignHistory();
        recreateTaskEventLogUnderPreV9Name();
        insertAssignHistory(1000L, 100L, 101L);

        // when / then — 지우면 그 재배정 사실이 어디에도 남지 않으므로 멈춘다.
        //   ★ 사유 문자열을 <행 미대응 사유로 특정>한다. 이 술어가 발화한 것인지 확인하지 않으면,
        //     V4 가 그 앞 단계(이벤트 로그 테이블 부재)에서 멈춰도 사유에 같은 이름이 들어 있어
        //     통과해 버린다 — 실제로 V9 개명 이후 그 false green 이 성립한다.
        assertThatThrownBy(() -> jdbc.execute(migrationSql))
                .hasMessageContaining("ls_task_assign_history")
                .hasMessageContaining("대응하는 REASSIGN 이벤트 로그를 갖고 있지 않다");

        // 부분 삭제도 없어야 한다 — DO 블록은 원자적이다
        assertThat(tableExists("ls_task_assign_history")).isTrue();
    }

    @Test
    @DisplayName("대응_이벤트로그가_있는_배정이력은_통과하고_테이블이_제거된다")
    void 대응_이벤트로그가_있는_배정이력은_통과하고_테이블이_제거된다() {
        // given — dev 실측 형상(이력 17행 전부 대응 이벤트 로그 보유)의 최소 재현.
        //   부모 영상은 스크래치 이벤트 로그에 FK 가 없어도 시나리오를 현실적으로 유지하려 시드한다.
        long rawSn = RawVideoFixture.newRaw(jdbc);
        try {
            recreateTaskAssignHistory();
            recreateTaskEventLogUnderPreV9Name();
            insertAssignHistory(rawSn, 100L, 101L);
            insertReassignEvent(rawSn, 100L, 101L);

            // when
            jdbc.execute(migrationSql);

            // then — 술어가 과도하게 엄격하면 여기서 기동이 막힌다(정상 dev 를 세우지 못한다)
            assertThat(tableExists("ls_task_assign_history")).isFalse();
        } finally {
            // CASCADE 로 이벤트 로그 행까지 함께 정리된다.
            RawVideoFixture.deleteRaws(jdbc, rawSn);
        }
    }

    @Test
    @DisplayName("공통코드_시드_외의_행이_있으면_마이그레이션이_중단된다")
    void 공통코드_시드_외의_행이_있으면_마이그레이션이_중단된다() {
        // given — 시드(DATA_STTS_CD 5행) 외에 누군가 새 코드 그룹을 넣어 쓰고 있는 DB
        recreateComCd();
        jdbc.update("INSERT INTO LS_COM_CD (GROUP_CODE, CODE, CODE_NM) VALUES ('EVNT_TYPE_CD', 'FIRE', '화재')");

        // when / then
        assertThatThrownBy(() -> jdbc.execute(migrationSql))
                .hasMessageContaining("ls_com_cd");
        assertThat(tableExists("ls_com_cd")).isTrue();
    }

    @Test
    @DisplayName("공통코드가_시드_5행뿐이면_통과하고_테이블이_제거된다")
    void 공통코드가_시드_5행뿐이면_통과하고_테이블이_제거된다() {
        // given — dev·신규 설치 실측 형상
        recreateComCd();
        seedComCd();

        // when
        jdbc.execute(migrationSql);

        // then
        assertThat(tableExists("ls_com_cd")).isFalse();
    }

    @Test
    @DisplayName("이력_테이블에_행이_있으면_마이그레이션이_중단된다")
    void 이력_테이블에_행이_있으면_마이그레이션이_중단된다() {
        // given — 0 행을 전제로 지우는 테이블에 행이 생겼다 = 우리가 모르는 사용처가 있다
        jdbc.execute("CREATE TABLE ls_data_meta_hstry ("
                + "hstry_seq bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
                + "meta_sn bigint NOT NULL, prev_vl varchar(2000), new_vl varchar(2000), "
                + "chg_user_no bigint, chg_dt timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL)");
        jdbc.update("INSERT INTO ls_data_meta_hstry (META_SN, NEW_VL) VALUES (1, 'x')");

        // when / then
        assertThatThrownBy(() -> jdbc.execute(migrationSql))
                .hasMessageContaining("ls_data_meta_hstry");
        assertThat(tableExists("ls_data_meta_hstry")).isTrue();
    }

    // ------------------------------------------------------------------
    // 헬퍼 — 제거된 테이블을 <구 DB 형상>으로 되살린다(V1 베이스라인 정의와 동일 컬럼).
    //   FK 는 붙이지 않는다. 이 테스트가 검증하는 것은 V4 의 전제 판정이지 FK 배선이 아니다.
    // ------------------------------------------------------------------

    /**
     * V4 가 증적 이관을 확인할 때 쓰는 {@code ls_task_event_log} 를 <b>V9 개명 이전 이름</b>으로
     * 되살린다 — V1 베이스라인 정의와 동일 컬럼, FK 없음.
     *
     * <p><b>왜 필요한가</b>: V4 는 동결본이라 옛 물리명으로 이관을 확인하는데, 실제 배포에서는
     * <b>V4 가 먼저 돌고 V9 가 나중에</b> 개명하므로 순서상 문제가 없다. 그러나 이 테스트는 V4 를
     * <b>이미 V9 까지 적용된 라이브 스키마에서 수동 재실행</b>하므로 그 이름이 없다. 되살리지 않으면
     * V4 는 검증 대상인 <b>행 단위 대조에 도달하기도 전에</b> "이벤트 로그가 없어 확인할 수 없다"로
     * 멈춘다 — 중단 사유에 {@code ls_task_assign_history} 가 함께 들어 있어 <b>중단 케이스는 통과처럼
     * 보이고</b>(false green) 통과 케이스만 깨진다.
     *
     * <p>{@code ls_task_assign_history} 를 되살리는 것과 같은 성질의 스크래치 픽스처이며,
     * {@link #tearDown()} 이 매번 지운다.
     */
    private void recreateTaskEventLogUnderPreV9Name() {
        jdbc.execute("CREATE TABLE ls_task_event_log ("
                + "evnt_id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
                + "raw_data_id bigint NOT NULL, evnt_type_cd varchar(20) NOT NULL, "
                + "actor_user_no bigint NOT NULL, subject_user_no bigint, prev_user_no bigint, "
                + "rsn varchar(500), "
                + "ocrn_dt timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL)");
    }

    private void recreateTaskAssignHistory() {
        jdbc.execute("CREATE TABLE ls_task_assign_history ("
                + "hstry_seq bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
                + "authrt_seq bigint NOT NULL, raw_data_id bigint NOT NULL, "
                + "prev_user_no bigint NOT NULL, new_user_no bigint NOT NULL, "
                + "task_type_cd varchar(20) NOT NULL, chg_user_no bigint NOT NULL, "
                + "chg_dt timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL)");
    }

    private void insertAssignHistory(long rawDataId, long prevUserNo, long newUserNo) {
        jdbc.update("INSERT INTO ls_task_assign_history "
                        + "(AUTHRT_SEQ, RAW_DATA_ID, PREV_USER_NO, NEW_USER_NO, TASK_TYPE_CD, CHG_USER_NO) "
                        + "VALUES (1, ?, ?, ?, 'LABELER', 1)",
                rawDataId, prevUserNo, newUserNo);
    }

    /**
     * 대응 REASSIGN 이벤트 1행. {@code subject_user_no} 가 이력의 {@code new_user_no} 에 대응한다.
     *
     * <p>대상은 {@link #recreateTaskEventLogUnderPreV9Name()} 이 세운 <b>스크래치 옛 이름</b> 테이블이다
     * — V4 가 그 이름으로 대조하므로 여기도 같은 이름이어야 한다. 라이브 감사 원장(V9 개명 후
     * {@code LS_TASK_EVNT_LOG})을 가리키는 것이 아니다.
     */
    private void insertReassignEvent(long rawDataId, long prevUserNo, long newUserNo) {
        jdbc.update("INSERT INTO LS_TASK_EVENT_LOG "
                        + "(RAW_DATA_ID, EVNT_TYPE_CD, ACTOR_USER_NO, SUBJECT_USER_NO, PREV_USER_NO) "
                        + "VALUES (?, 'REASSIGN', 1, ?, ?)",
                rawDataId, newUserNo, prevUserNo);
    }

    private void recreateComCd() {
        jdbc.execute("CREATE TABLE ls_com_cd ("
                + "group_code varchar(64) NOT NULL, code varchar(64) NOT NULL, "
                + "code_nm varchar(255) NOT NULL, code_dc varchar(500), "
                + "use_yn varchar(1) DEFAULT 'Y' NOT NULL, sort_ordr integer DEFAULT 0 NOT NULL, "
                + "reg_dt timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL, "
                + "PRIMARY KEY (group_code, code))");
    }

    private void seedComCd() {
        for (String code : List.of("PENDING", "IN_REVIEW", "APPROVED", "REJECTED", "BATCH_QUEUED")) {
            jdbc.update("INSERT INTO LS_COM_CD (GROUP_CODE, CODE, CODE_NM) VALUES ('DATA_STTS_CD', ?, ?)",
                    code, code);
        }
    }

    private boolean tableExists(String table) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND table_name = ?",
                Long.class, table);
        return n != null && n > 0;
    }
}
