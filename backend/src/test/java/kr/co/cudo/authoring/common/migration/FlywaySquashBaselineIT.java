package kr.co.cudo.authoring.common.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway 스쿼시(2026-08-13) 형상 고정 — <b>Flyway 가 실제로 무엇을 적용했는가</b>를 이력 테이블로 확인한다.
 *
 * <h3>왜 이 가드가 필요한가</h3>
 * <p>구 마이그레이션 180개(V0~V185)는 삭제하지 않고 {@code src/test/resources/db-archive/migration/} 에
 * 원문 보존했다 — 21개 테스트 클래스가 그 파일들을 읽어 백필 로직·DROP 순서·롤백 절차 주석을 검증하기
 * 때문이다. 그런데 <b>Flyway 의 classpath 스캔은 같은 리소스 경로를 여러 클래스패스 엔트리에서 병합</b>한다
 * (실제로 {@code src/test/resources/db/migration/V9001__test_seed_user_roles.sql} 이 그 성질로 테스트에서만
 * 합류한다). 아카이브를 {@code db/migration} 아래로 되돌리거나 {@code locations} 를 넓히면 구 180개가
 * <b>베이스라인 위에 다시 적용</b>되어, 신규 설치가 "만들었다 지우는" 왕복으로 되돌아간다.
 *
 * <p>그 회귀는 스키마 최종형이 비슷해 <b>기존 테스트로는 드러나지 않는다</b>. 이력 행을 직접 세는 이 가드가
 * 유일한 탐지 수단이다.
 *
 * <h3>기대 형상</h3>
 * <ul>
 *   <li>{@code V1} — 베이스라인(스키마 전량 + 시드 14행. 기록된 예외 2건으로 미사용 7종과
 *       {@code ls_com_cd} 시드 5행을 덜어낸 뒤의 수치다)</li>
 *   <li>{@code V2} — {@code CM_CODE} → {@code LS_COM_CD} 개명(신규 설치에서는 no-op)</li>
 *   <li>{@code V3} — 사용처 0 테이블 3종 DROP(신규 설치에서는 no-op)</li>
 *   <li>{@code V4} — 사용처 0 테이블 4종 DROP 2회차(신규 설치에서는 no-op)</li>
 *   <li>{@code V5} — 배치 큐·메타복제 발신함 비표준 컬럼 11종 표준용어 개명. V1 을 고치지 않으므로
 *       <b>신규 설치도 옛 이름으로 만들어진 뒤 여기서 개명</b>된다(no-op 이 아니다)</li>
 *   <li>{@code V6} — {@code LS_DATA_LBL_AI_INFO} 를 {@code LS_DATA_LBL} 로 흡수</li>
 *   <li>{@code V7} — {@code LS_MON_NOTI_ACML.STTS_CD} 폭 20 → 16(표준도메인 정합). V5 와 같은 이유로
 *       <b>신규 설치도 20 으로 만들어진 뒤 여기서 축소</b>된다(no-op 이 아니다)</li>
 *   <li>{@code V8} — {@code LS_WEBHOOK_IDEMPOTENCY.APLY_DT} → {@code APLCN_DT} 개명(표준용어 정합).
 *       V5·V7 과 같은 이유로 <b>신규 설치도 옛 이름으로 만들어진 뒤 여기서 개명</b>된다(no-op 이 아니다)</li>
 *   <li>{@code V9} — {@code LS_TASK_ASSIGNMENT} → {@code LS_TASK_ALTMNT} ·
 *       {@code LS_TASK_EVENT_LOG} → {@code LS_TASK_EVNT_LOG} 개명(표준용어 정합). 테이블뿐 아니라
 *       시퀀스·제약·인덱스 13종을 함께 옮긴다. V5·V7·V8 과 같은 이유로 <b>신규 설치도 옛 이름으로
 *       만들어진 뒤 여기서 개명</b>된다(no-op 이 아니다)</li>
 *   <li>{@code V9001} — 테스트 전용 시드(테스트 클래스패스에만 존재)</li>
 * </ul>
 *
 * <p><b>새 마이그레이션을 추가할 때</b>는 위 목록과 아래 두 단언에 <i>한 줄씩 명시적으로</i> 더한다.
 * 개수 비교나 접두 매칭으로 느슨하게 바꾸면 아카이브 유입을 못 잡아 이 가드의 존재 이유가 사라진다.
 *
 * @design D9
 */
@SpringBootTest
@ActiveProfiles("local")
class FlywaySquashBaselineIT {

    /** Flyway 가 읽는 <b>유일한</b> 마이그레이션 디렉터리(운영 배포본). */
    private static final Path LIVE_MIGRATION_DIR = Path.of("src/main/resources/db/migration");

    /** 구 180개 원문 보존처 — Flyway 는 읽지 않고 테스트만 읽는다. */
    private static final Path ARCHIVE_DIR = Path.of("src/test/resources/db-archive/migration");

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Test
    @DisplayName("Flyway는_스쿼시본만_적용한다 — 아카이브_180개가_다시_적용되지_않는다")
    void Flyway는_스쿼시본만_적용한다() {
        // given: 컨텍스트 기동 시 Flyway 가 이미 migrate 를 마쳤다
        List<String> applied = new JdbcTemplate(controlDataSource).queryForList(
                "SELECT version FROM flyway_schema_history WHERE type = 'SQL' ORDER BY installed_rank",
                String.class);

        // then: 운영 3개 + 테스트 전용 시드 1개. 아카이브가 합류하면 여기가 180+ 로 부푼다.
        //   ★ 정상적인 신규 마이그레이션을 추가할 때는 이 목록에 <의도적으로> 한 줄을 더한다.
        //     느슨하게(예: hasSizeGreaterThan) 바꾸지 말 것 — 아카이브 유입 탐지력이 사라진다.
        assertThat(applied)
                .as("Flyway 가 적용한 SQL 마이그레이션 — 아카이브가 db/migration 으로 새어 들어오면 실패한다")
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "9001");
    }

    @Test
    @DisplayName("운영_마이그레이션_디렉터리에는_스쿼시본과_그_이후_파일만_있다")
    void 운영_마이그레이션_디렉터리에는_스쿼시본과_그_이후_파일만_있다() throws Exception {
        // when
        List<String> live = listSql(LIVE_MIGRATION_DIR);

        // then: 여기에 구 파일(V0·V4~V185 등)이 되돌아오면 즉시 잡힌다.
        //   ★ 신규 마이그레이션 추가 시 이 목록에 <의도적으로> 한 줄을 더한다(느슨하게 바꾸지 말 것).
        assertThat(live)
                .as("배포되는 마이그레이션 파일 목록")
                .containsExactly(
                        "V1__baseline.sql",
                        "V2__rename_cm_code_to_ls_com_cd.sql",
                        "V3__drop_unused_tables.sql",
                        "V4__drop_unused_tables_round2.sql",
                        "V5__rename_queue_outbox_columns_to_std.sql",
                        "V6__absorb_lbl_ai_info_into_ls_data_lbl.sql",
                        "V7__narrow_mon_noti_acml_stts_cd_to_std_width.sql",
                        "V8__rename_webhook_idempotency_aply_dt_to_aplcn_dt.sql",
                        "V9__rename_task_tables_to_std_terms.sql");
    }

    @Test
    @DisplayName("구_마이그레이션_180개_원문이_아카이브에_보존돼_있다")
    void 구_마이그레이션_원문이_보존돼_있다() throws Exception {
        // when
        List<String> archived = listSql(ARCHIVE_DIR);

        // then: 21개 테스트 클래스가 이 파일들을 직접 읽는다 — 지우면 그 회귀 커버리지가 사라진다.
        //       (개수 자체를 고정하는 이유: 일부만 지워도 그 파일을 읽는 테스트만 깨져서
        //        "왜 없어졌는지" 를 되짚기 어렵기 때문이다.)
        assertThat(archived)
                .as("보존된 구 마이그레이션 원문 개수")
                .hasSize(180);
        assertThat(archived)
                .as("스쿼시가 접은 구간의 처음과 끝")
                .contains("V0__init.sql", "V185__align_control_ingest_contract_and_export_frme_cnt_comment.sql");
    }

    @Test
    @DisplayName("마이그레이션_파일에_Flyway_플레이스홀더_표기가_없다")
    void 마이그레이션_파일에_플레이스홀더_표기가_없다() throws Exception {
        // given: Flyway 는 ${...} 를 <주석 안이라도> 플레이스홀더로 해석한다.
        //   실측 — 베이스라인 헤더 주석의 ${DB_SCHEMA} 하나로 "No value provided for placeholder" 가 나
        //   파일 전체가 적용되지 않았다. 스키마 설명을 주석에 적을 때 재발하기 쉬워 가드로 고정한다.
        for (String name : listSql(LIVE_MIGRATION_DIR)) {
            String sql = Files.readString(LIVE_MIGRATION_DIR.resolve(name));

            // then
            assertThat(sql)
                    .as("%s 에 ${...} 표기가 있으면 Flyway 가 파싱 단계에서 실패한다", name)
                    .doesNotContain("${");
        }
    }

    private List<String> listSql(Path dir) throws Exception {
        assertThat(Files.isDirectory(dir))
                .as("디렉터리(%s)가 존재해야 한다 (테스트 작업 디렉토리=backend 모듈 루트)", dir.toAbsolutePath())
                .isTrue();
        try (Stream<Path> paths = Files.list(dir)) {
            return paths.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".sql"))
                    .sorted()
                    .toList();
        }
    }
}
