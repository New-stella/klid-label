package kr.co.cudo.authoring.common.migration;

import jakarta.persistence.Column;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V179 · V180 · V181 — 프레임 폐기여부({@code LS_DATA_SRC.DSCD_YN}) 신설 + 라벨 버전번호
 * ({@code LS_LABEL_VERSION.VER_NO}) 재정의(V180 DDL · V181 레거시 값 무효화) 실동작 검증
 * (Testcontainers PostgreSQL, Flyway migrate 후 부팅).
 *
 * <h3>왜 정보 스키마를 직접 대조하나</h3>
 * <p>이 프로젝트의 {@code ddl-auto=validate} 는 실제로 동작하지 않는다({@code JpaBuilderConfig} 가
 * {@code spring.jpa.hibernate.*} 를 EMF 에 넘기지 않아 <b>없는 컬럼을 매핑해도 기동이 성공</b>한다).
 * 따라서 "기동 성공"을 엔티티↔DDL 정합의 증거로 삼을 수 없다({@code V176VrfcEvntTypeMigrationIT}
 * 와 동일 골격).
 *
 * @design D1
 * @design D5
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class FrameDiscardSchemaMigrationIT {

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsLabelVersionRepository labelVersionRepository;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(controlDataSource);
    }

    private Map<String, Object> columnMeta(String table, String column) {
        return jdbc().queryForMap(
                "SELECT data_type, character_maximum_length, is_nullable, column_default"
                        + " FROM information_schema.columns"
                        + " WHERE table_name = ? AND column_name = ?", table, column);
    }

    // ---------------------------------------------------------------- V179: LS_DATA_SRC.DSCD_YN

    @Test
    @DisplayName("V179_폐기여부가_여부C1_NOT_NULL_기본값_N_으로_생성된다")
    void 폐기여부_컬럼이_생성된다() {
        // when
        Map<String, Object> meta = columnMeta("ls_data_src", "dscd_yn");

        // then — 행안부 공통표준용어 폐기여부(DSCD_YN) + 공통표준도메인 여부C1(CHAR(1), Y/N)
        assertThat(meta.get("data_type")).isEqualTo("character");
        assertThat(((Number) meta.get("character_maximum_length")).intValue()).isEqualTo(1);
        // NOT NULL DEFAULT 'N' — 컬럼 신설 이전 행은 "폐기된 적이 없다"가 맞는 값이므로
        //   값을 지어내는 백필이 아니다(V177 REVLT_YN 과 같은 축).
        assertThat(meta.get("is_nullable")).isEqualTo("NO");
        assertThat(String.valueOf(meta.get("column_default"))).startsWith("'N'");
    }

    @Test
    @DisplayName("기존_행은_전부_폐기되지_않음_N_이다 — 백필_UPDATE_없이_DEFAULT가_채운다")
    void 기존_행은_전부_N_이다() {
        Integer notN = jdbc().queryForObject(
                "SELECT count(*) FROM ls_data_src WHERE dscd_yn IS NULL OR dscd_yn <> 'N'",
                Integer.class);
        assertThat(notN).isZero();
    }

    @Test
    @DisplayName("엔티티_폐기여부_매핑이_마이그레이션과_일치한다")
    void 엔티티_폐기여부_매핑이_마이그레이션과_일치한다() {
        Field field = Arrays.stream(LsDataSrc.class.getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(Column.class))
                .filter(f -> "DSCD_YN".equals(f.getAnnotation(Column.class).name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("LsDataSrc 에 DSCD_YN 매핑이 없다"));

        assertThat(field.getType()).isEqualTo(String.class);
        assertThat(field.getAnnotation(Column.class).length()).isEqualTo(1);
        assertThat(field.getAnnotation(Column.class).nullable()).isFalse();
    }

    // ------------------------------------------------------ V180: LS_LABEL_VERSION.VER_NO 재정의

    @Test
    @DisplayName("V180_버전번호가_nullable로_전환된다")
    void 버전번호가_nullable로_전환된다() {
        // when
        Map<String, Object> meta = columnMeta("ls_label_version", "ver_no");

        // then — 산출 버전 번호를 아직 모르는 스냅샷이 정상 상태이므로 NULL 을 허용해야 한다.
        assertThat(meta.get("is_nullable")).isEqualTo("YES");
        // DEFAULT 를 두면 "번호를 모른다"와 "그 번호다"가 영영 구분되지 않는다.
        assertThat(meta.get("column_default")).isNull();
    }

    @Test
    @DisplayName("버전번호_NULL_스냅샷이_엔티티_경로로_실제_적재된다")
    void 버전번호_NULL_스냅샷이_실제_적재된다() {
        // given — FK(ls_data_raw / ls_data_src)를 만족하는 실제 부모 행. 이 IT 는 트랜잭션 롤백된다.
        LsDataRaw raw = rawRepository.save(LsDataRaw.createFromIngest(
                "CLIP-VERNULL-" + System.nanoTime(), "CCTV-VERNULL", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/vernull.mp4", LocalDateTime.now(), 30));
        LsDataSrc frame = srcRepository.save(
                LsDataSrc.create(raw.getRawSn(), 0, "/frames/raw/vernull/0.jpg", LocalDateTime.now()));
        String hash = "it-vernull-" + System.nanoTime();

        // when — 산출 버전 번호를 아직 모르는 스냅샷(= 채번 배선 전 정상 값)
        labelVersionRepository.saveAndFlush(LsLabelVersion.create(
                raw.getRawSn(), frame.getSrcSn(), hash, "{\"items\":[]}", null,
                LsLabelVersion.SAVE_REASON_APPROVED, "1"));

        // then — DB 가 NOT NULL 로 거부하지 않고 NULL 그대로 적재한다
        assertThat(jdbc().queryForObject(
                "SELECT ver_no FROM ls_label_version WHERE version_hash = ?", Integer.class, hash))
                .isNull();
    }

    @Test
    @DisplayName("프레임_INSERT시_폐기여부가_N으로_실제_적재된다 — DB_DEFAULT가_아니라_팩토리가_채운다")
    void 프레임_INSERT시_폐기여부가_N으로_실제_적재된다() {
        // given — 이 엔티티에는 @DynamicInsert 가 없어 Hibernate 가 모든 컬럼을 명시 INSERT 한다.
        //   팩토리가 값을 안 채우면 명시적 NULL 이 들어가 NOT NULL 제약에 걸린다(DEFAULT 는 적용 안 됨).
        LsDataRaw raw = rawRepository.save(LsDataRaw.createFromIngest(
                "CLIP-DSCD-" + System.nanoTime(), "CCTV-DSCD", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/dscd.mp4", LocalDateTime.now(), 30));

        // when
        LsDataSrc frame = srcRepository.saveAndFlush(
                LsDataSrc.create(raw.getRawSn(), 0, "/frames/raw/dscd/0.jpg", LocalDateTime.now()));

        // then
        assertThat(jdbc().queryForObject(
                "SELECT dscd_yn FROM ls_data_src WHERE src_sn = ?", String.class, frame.getSrcSn()))
                .isEqualTo("N");
    }

    /** 마이그레이션 스크립트에서 주석을 걷어내고 실행되는 문장만 대문자로 정규화해 돌려준다. */
    private String migrationStatements(String fileName) throws Exception {
        return new String(getClass().getResourceAsStream("/db-archive/migration/" + fileName)
                .readAllBytes(), StandardCharsets.UTF_8)
                .replaceAll("(?m)^\\s*--.*$", "")   // 주석 제외 — 실행되는 문장만 본다
                .replaceAll("\\s+", " ")
                .toUpperCase();
    }

    @Test
    @DisplayName("V180은_DDL만_담는다 — 전_행_UPDATE를_같은_트랜잭션에_두지_않는다")
    void V180은_DDL만_담는다() throws Exception {
        // ★ 왜 이 가드가 필요한가: Flyway 는 mixed 설정이 없으면 한 스크립트를 <한 트랜잭션>으로
        //   실행하고, PostgreSQL 은 락을 <커밋 시점>에 해제한다. 따라서 ALTER 가 잡은 ACCESS
        //   EXCLUSIVE 락이 전 행 UPDATE 가 끝날 때까지 유지되어, 그 구간 동안 LS_LABEL_VERSION 에
        //   대한 <조회까지> 전부 대기한다(버전 목록·diff·롤백 조회 포함).
        String sql = migrationStatements("V180__redefine_ls_label_version_ver_no.sql");

        // then — 카탈로그 변경(ALTER · COMMENT)만 있고 행을 건드리는 DML 은 없어야 한다.
        assertThat(sql).contains("ALTER TABLE LS_LABEL_VERSION");
        assertThat(sql).contains("DROP NOT NULL");
        assertThat(sql).doesNotContain("UPDATE LS_LABEL_VERSION");
    }

    @Test
    @DisplayName("V181_마이그레이션이_기존_행의_버전번호를_비운다 — 옛_의미를_남기지_않는다")
    void V181이_기존_행의_버전번호를_비운다() throws Exception {
        // ★ 왜 스크립트 본문을 검사하나: 컨테이너는 매번 <빈 DB> 에서 Flyway 를 돌리므로
        //   "마이그레이션 이전부터 있던 행"을 런타임으로 재현할 수 없다. 반면 이 UPDATE 가
        //   빠지면 운영 DB 에는 옛 의미(프레임별 순번) 값이 그대로 남아, 그 값을 산출 회차로
        //   읽는 순간 한 영상 안에 서로 다른 시점의 프레임이 섞인다(이 설계가 막으려는 혼합본).
        //   따라서 "무효화 문장이 스크립트에 실재하는가"가 이 회귀의 유일한 결정적 가드다.
        String sql = migrationStatements("V181__invalidate_ls_label_version_legacy_ver_no.sql");

        assertThat(sql).contains("UPDATE LS_LABEL_VERSION SET VER_NO = NULL");
        // 무효화는 DML 전용 스크립트다 — DDL 을 도로 합치면 위 락 문제가 그대로 되살아난다.
        assertThat(sql).doesNotContain("ALTER TABLE LS_LABEL_VERSION");
    }

    @Test
    @DisplayName("엔티티_버전번호_매핑이_nullable과_일치한다")
    void 엔티티_버전번호_매핑이_nullable과_일치한다() {
        Field field = Arrays.stream(LsLabelVersion.class.getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(Column.class))
                .filter(f -> "VER_NO".equals(f.getAnnotation(Column.class).name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("LsLabelVersion 에 VER_NO 매핑이 없다"));

        // 원시 int 로 두면 DB NULL 을 읽는 순간 언박싱에서 터진다.
        assertThat(field.getType()).isEqualTo(Integer.class);
        assertThat(field.getAnnotation(Column.class).nullable()).isTrue();
    }
}
