package kr.co.cudo.authoring.eventtype.migration;

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

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V168 <b>이벤트유형 마스터 이관</b> 실동작 검증 (Testcontainers PostgreSQL).
 *
 * <h3>왜 필요한가</h3>
 * <p>이관이 실패하면 기존 영상의 이벤트 라벨이 통째로 원문 코드로 떨어지고 필터 드롭다운이 빈다.
 * 그런데 <b>빈 테스트 DB 에서는 이관 원본(관제 마스터)도 비어 있어</b> 마이그레이션이 자동 적용되는
 * 것만으로는 이관이 검증되지 않는다 — 원본을 세워 실제로 옮겨지는지 봐야 한다.
 *
 * <h3>검증 방식</h3>
 * <p>{@code V167CctvBackfillIT} 와 같은 관례로 <b>배포되는 마이그레이션 파일 원본을 다시 실행</b>한다
 * (테스트가 SQL 을 복제하면 파일이 바뀌어도 통과하는 흉내가 된다). 컨텍스트 기동 시 이미 V168 이
 * 적용돼 관제 마스터가 없으므로, 재실행 전에 <b>V71 원문 DDL 로 스크래치 마스터를 만들고</b> 종료 시
 * DROP 한다 — 실제 배포 순서(마스터 존재 → 이관 → DROP)를 재현하는 것이 목적이다.
 *
 * <p>파일 전체를 실행하면 스크래치 마스터를 DROP 해 버리므로 <b>DROP 직전까지</b>만 실행한다.
 * 그 앞 문장들은 전부 멱등이다({@code IF NOT EXISTS} · {@code ON CONFLICT DO NOTHING} · 술어 한정
 * UPDATE)라 이미 적용된 DB 에서 재실행해도 안전하다.
 */
@SpringBootTest
@ActiveProfiles("local")
class V168EvntTypeMigrationIT {

    private static final String MIGRATION = "db/migration/V168__create_ls_evnt_type_and_drop_mng_masters.sql";

    /** 이 테스트가 만드는 행 접두 — 시드·다른 테스트와 겹치지 않게 한다(유형코드 컬럼 길이 20). */
    private static final String CODE_PREFIX = "V168IT";

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc;

    /**
     * 실행 전 마스터 스냅샷 — 이관 ②는 <b>인입 원장 전체</b>를 훑으므로 이 테스트가 만들지 않은
     * 유형(dev-seed 인입분 등)까지 등록될 수 있다. 그대로 두면 같은 JVM 의 뒤 테스트가 보는
     * 필터 옵션이 늘어나 <b>이 테스트가 다른 테스트를 깨뜨린다</b>. 종료 시 원상 복구한다.
     */
    private List<String> preExistingCodes;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanup();
        preExistingCodes = jdbc.queryForList("SELECT evnt_type_cd FROM ls_evnt_type", String.class);
        createScratchMasters();
    }

    @AfterEach
    void tearDown() {
        dropScratchMasters();
        cleanup();
        restoreMasterSnapshot();
    }

    /** 이 테스트 실행으로 새로 등록된 유형을 전부 제거한다(사전 스냅샷 기준). */
    private void restoreMasterSnapshot() {
        for (String code : jdbc.queryForList("SELECT evnt_type_cd FROM ls_evnt_type", String.class)) {
            if (!preExistingCodes.contains(code)) {
                jdbc.update("DELETE FROM ls_evnt_type WHERE evnt_type_cd = ?", code);
            }
        }
    }

    private String ctgryName(String clsf, String ctgry) {
        return jdbc.queryForObject(
                "SELECT evnt_ctgry_nm FROM ls_evnt_ctgry WHERE evnt_clsf_cd = ? AND evnt_ctgry_cd = ?",
                String.class, clsf, ctgry);
    }

    private void cleanup() {
        jdbc.update("DELETE FROM ls_label_preset_code WHERE preset_id IN"
                + " (SELECT preset_id FROM ls_label_preset WHERE preset_nm LIKE ?)", CODE_PREFIX + "%");
        jdbc.update("DELETE FROM ls_label_preset WHERE preset_nm LIKE ?", CODE_PREFIX + "%");
        jdbc.update("DELETE FROM ls_evnt_type WHERE evnt_type_cd LIKE ?", CODE_PREFIX + "%");
        jdbc.update("DELETE FROM ls_evnt_ctgry WHERE evnt_clsf_cd IN ('91','92','93','94','95')");
        jdbc.update("DELETE FROM ls_data_ingest WHERE vms_clip_id LIKE ?", CODE_PREFIX + "%");
    }

    /** V71 원문 DDL — 이관 원본을 재현하기 위한 스크래치(테스트 종료 시 DROP). */
    private void createScratchMasters() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS MNG_EX_EVNT_TYPE (
                    EVNT_TYPE_CD   VARCHAR(20)   NOT NULL,
                    EVNT_CLS_CD    VARCHAR(2)    NOT NULL,
                    EVNT_CTGRY_CD  VARCHAR(4)    NOT NULL,
                    CLCT_EVNT_NM   VARCHAR(4000),
                    CLCT_YN        VARCHAR(2)    NOT NULL,
                    PRIMARY KEY (EVNT_TYPE_CD)
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS MNG_EX_EVNT_TYPE_MAP (
                    CD_TYPE        VARCHAR(2)    NOT NULL,
                    EVNT_CLS_CD    VARCHAR(2)    NOT NULL,
                    EVNT_CTGRY_CD  VARCHAR(4)    NOT NULL,
                    DTL_EVNT       VARCHAR(2)    NOT NULL,
                    EVNT_TYPE_CD   VARCHAR(20)   NOT NULL,
                    EVNT_NM        VARCHAR(4000),
                    USE_YN         VARCHAR(2)    NOT NULL,
                    PRIMARY KEY (CD_TYPE, EVNT_CLS_CD, EVNT_CTGRY_CD, DTL_EVNT, EVNT_TYPE_CD)
                )""");
    }

    private void dropScratchMasters() {
        jdbc.execute("DROP TABLE IF EXISTS MNG_EX_EVNT_TYPE_MAP");
        jdbc.execute("DROP TABLE IF EXISTS MNG_EX_EVNT_TYPE");
    }

    @Test
    @DisplayName("기존_마스터_값이_이관된다")
    void 기존_마스터_값이_이관된다() throws IOException {
        // given — 관제 마스터에 유형 2종(같은 카테고리) + 카테고리명행 + 비수집 1종
        seedMasterType(CODE_PREFIX + "01", "91", "0001", "Y");
        seedMasterType(CODE_PREFIX + "02", "91", "0001", "Y");
        seedMasterType(CODE_PREFIX + "03", "92", "0002", "N");
        seedCategoryName("91", "0001", "이관침수");
        seedCategoryName("92", "0002", "이관기타");

        // when — 배포되는 마이그레이션 파일의 이관 구간을 그대로 재실행
        runMigrationUpToDrop();

        // then — 유형코드·대분류·카테고리·수집여부가 옮겨진다.
        //   ★관제 수신 유형명(EVNT_NM)은 <비어 있어야> 한다 — 관제 마스터에 유형별 이름이
        //     없었으므로 카테고리명을 여기 복사하면 중복 이름이 만들어진다.
        Map<String, Object> first = evntTypeRow(CODE_PREFIX + "01");
        assertThat(first.get("evnt_nm")).isNull();
        assertThat(first.get("evnt_clsf_cd")).isEqualTo("91");
        assertThat(first.get("evnt_ctgry_cd")).isEqualTo("0001");
        assertThat(((String) first.get("clct_yn")).trim()).isEqualTo("Y");

        // then — 카테고리명은 <카테고리 마스터>로 옮겨진다(표시명 폴백의 원천)
        assertThat(ctgryName("91", "0001")).isEqualTo("이관침수");

        // then — 같은 카테고리의 상세 유형도 <각각> 등록된다(축이 유형이라 뭉치지 않는다)
        assertThat(evntTypeRow(CODE_PREFIX + "02").get("evnt_ctgry_cd")).isEqualTo("0001");

        // then — 비수집(N)도 이관된다(라벨 해석 대상이므로 버리지 않는다)
        Map<String, Object> nonCollected = evntTypeRow(CODE_PREFIX + "03");
        assertThat(((String) nonCollected.get("clct_yn")).trim()).isEqualTo("N");
        assertThat(ctgryName("92", "0002")).isEqualTo("이관기타");
    }

    @Test
    @DisplayName("인입에만_있는_유형코드도_등록된다")
    void 인입에만_있는_유형코드도_등록된다() throws IOException {
        // given — 관제 마스터에 없는 <비규격 코드>가 인입 원장에만 존재한다(실측: INTRUSION 9건).
        //   이 문장이 없으면 그 유형의 영상이 필터에서 사라지고 라벨이 원문 코드로 떨어진다.
        seedIngest(CODE_PREFIX + "-CLIP-1", CODE_PREFIX + "IN", "인입전용이벤트", null);

        // when
        runMigrationUpToDrop();

        // then — 자동등록과 같은 규칙으로 등록된다(이름=수신값, 대분류=미송신이라 null, 수집=Y)
        Map<String, Object> row = evntTypeRow(CODE_PREFIX + "IN");
        assertThat(row.get("evnt_nm")).isEqualTo("인입전용이벤트");
        assertThat(row.get("evnt_clsf_cd")).isNull();
        assertThat(((String) row.get("clct_yn")).trim()).isEqualTo("Y");
    }

    @Test
    @DisplayName("마스터_값이_인입값보다_우선한다")
    void 마스터_값이_인입값보다_우선한다() throws IOException {
        // given — 같은 유형코드가 마스터(정본)와 인입(수신값) 양쪽에 있다
        seedMasterType(CODE_PREFIX + "04", "93", "0003", "Y");
        seedCategoryName("93", "0003", "마스터이름");
        seedIngest(CODE_PREFIX + "-CLIP-2", CODE_PREFIX + "04", "인입이름", "99");

        // when
        runMigrationUpToDrop();

        // then — 이관 ①(마스터)이 먼저 실행되므로 마스터 값이 남는다(관제 수신명은 비어 있고
        //   대분류·카테고리는 마스터에서 온다). 인입값이 덮어쓰지 않는다.
        Map<String, Object> row = evntTypeRow(CODE_PREFIX + "04");
        assertThat(row.get("evnt_nm")).isNull();
        assertThat(row.get("evnt_clsf_cd")).isEqualTo("93");
        assertThat(row.get("evnt_ctgry_cd")).isEqualTo("0003");
    }

    @Test
    @DisplayName("이관은_멱등이라_재실행해도_덮어쓰지_않는다")
    void 이관은_멱등이라_재실행해도_덮어쓰지_않는다() throws IOException {
        // given — 1차 이관 후 운영자가 표시명을 정했다
        seedMasterType(CODE_PREFIX + "05", "94", "0004", "Y");
        seedCategoryName("94", "0004", "원본이름");
        runMigrationUpToDrop();
        jdbc.update("UPDATE ls_evnt_type SET optr_indct_nm = ? WHERE evnt_type_cd = ?",
                "정정이름", CODE_PREFIX + "05");

        // when — 부분 실패 후 재개 등으로 마이그레이션이 다시 돌아도
        runMigrationUpToDrop();

        // then — 기존 행을 덮어쓰지 않는다(ON CONFLICT DO NOTHING)
        assertThat(evntTypeRow(CODE_PREFIX + "05").get("optr_indct_nm")).isEqualTo("정정이름");
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ls_evnt_type WHERE evnt_type_cd = ?", Integer.class,
                CODE_PREFIX + "05");
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("프리셋_카테고리키가_대표_유형코드로_전환된다")
    void 프리셋_카테고리키가_대표_유형코드로_전환된다() throws IOException {
        // given — V72 이후 프리셋은 <카테고리 키>(6자리 숫자)를 담고 있다. 축이 유형으로 바뀌면
        //   이 값은 어떤 유형과도 매칭되지 않아 오토라벨 프리셋이 조용히 죽는다.
        seedMasterType(CODE_PREFIX + "07", "95", "0005", "Y");
        seedMasterType(CODE_PREFIX + "06", "95", "0005", "Y");
        seedCategoryName("95", "0005", "이관프리셋");
        long presetId = seedPreset("950005");

        // when
        runMigrationUpToDrop();

        // then — 그 카테고리의 <최소> 유형코드로 정정된다(결정적)
        assertThat(presetEventTypeCd(presetId)).isEqualTo(CODE_PREFIX + "06");

        // then — 멱등: 재실행해도 값이 더 바뀌지 않는다(EV-코드는 '^[0-9]{6}$' 에 걸리지 않는다)
        runMigrationUpToDrop();
        assertThat(presetEventTypeCd(presetId)).isEqualTo(CODE_PREFIX + "06");
    }

    // --- helpers ---

    /**
     * 마이그레이션 파일을 <b>첫 DROP 직전까지</b> 실행한다.
     *
     * <p>라인 주석을 걷어낸 뒤 {@code ;} 로 문장을 나눠 순서대로 실행한다. 이 파일의 문자열
     * 리터럴에는 {@code ;} 가 없으므로 단순 분할로 충분하다(있게 되면 이 헬퍼부터 고쳐야 한다).
     */
    private void runMigrationUpToDrop() throws IOException {
        String sql = new String(new ClassPathResource(MIGRATION).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        String executable = sql.replaceAll("--[^\\n\\r]*", " ");
        int dropAt = executable.toUpperCase().indexOf("DROP TABLE IF EXISTS");
        assertThat(dropAt).as("V168 에 DROP 구문이 있어야 한다").isPositive();
        for (String statement : executable.substring(0, dropAt).split(";")) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                jdbc.execute(trimmed);
            }
        }
    }

    private void seedMasterType(String code, String cls, String ctgry, String clctYn) {
        jdbc.update("INSERT INTO MNG_EX_EVNT_TYPE"
                        + " (EVNT_TYPE_CD, EVNT_CLS_CD, EVNT_CTGRY_CD, CLCT_EVNT_NM, CLCT_YN)"
                        + " VALUES (?, ?, ?, '', ?) ON CONFLICT (EVNT_TYPE_CD) DO NOTHING",
                code, cls, ctgry, clctYn);
    }

    private void seedCategoryName(String cls, String ctgry, String name) {
        jdbc.update("INSERT INTO MNG_EX_EVNT_TYPE_MAP"
                        + " (CD_TYPE, EVNT_CLS_CD, EVNT_CTGRY_CD, DTL_EVNT, EVNT_TYPE_CD, EVNT_NM, USE_YN)"
                        + " VALUES ('02', ?, ?, '', '', ?, 'Y')"
                        + " ON CONFLICT (CD_TYPE, EVNT_CLS_CD, EVNT_CTGRY_CD, DTL_EVNT, EVNT_TYPE_CD)"
                        + " DO NOTHING",
                cls, ctgry, name);
    }

    private void seedIngest(String clipId, String evntTypeCd, String evntNm, String evntClsfCd) {
        jdbc.update("INSERT INTO LS_DATA_INGEST"
                        + " (VMS_CLIP_ID, VMS_CCTV_ID, VDO_FILE_NM, RAW_FILE_PATH_NM, SRC_TYPE,"
                        + "  RCPTN_DT, PRCS_STTS_CD, EVNT_TYPE_CD, EVNT_NM, EVNT_CLSF_CD)"
                        + " VALUES (?, 'CCTV-V168IT', 'a.mp4', '/tmp/a.mp4', 'ORIGINAL',"
                        + "  CURRENT_TIMESTAMP, 'DONE', ?, ?, ?)"
                        + " ON CONFLICT (VMS_CLIP_ID) DO NOTHING",
                clipId, evntTypeCd, evntNm, evntClsfCd);
    }

    private long seedPreset(String categoryKey) {
        jdbc.update("INSERT INTO LS_LABEL_PRESET (PRESET_NM, EXPLN, EVNT_TYPE_CD)"
                + " VALUES (?, '이관 검증용', ?)", CODE_PREFIX + "-PRESET", categoryKey);
        Long id = jdbc.queryForObject("SELECT PRESET_ID FROM LS_LABEL_PRESET WHERE PRESET_NM = ?",
                Long.class, CODE_PREFIX + "-PRESET");
        assertThat(id).isNotNull();
        return id;
    }

    private String presetEventTypeCd(long presetId) {
        return jdbc.queryForObject("SELECT EVNT_TYPE_CD FROM LS_LABEL_PRESET WHERE PRESET_ID = ?",
                String.class, presetId);
    }

    private Map<String, Object> evntTypeRow(String code) {
        return jdbc.queryForMap("SELECT * FROM ls_evnt_type WHERE evnt_type_cd = ?", code);
    }
}
