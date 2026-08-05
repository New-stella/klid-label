package kr.co.cudo.authoring.video.migration;

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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V167 <b>CCTV 평면값 백필</b> 실동작 검증 (Testcontainers PostgreSQL).
 *
 * <h3>왜 필요한가</h3>
 * <p>실측(246 dev) {@code LS_DATA_RAW} 57행 / {@code MNG_RESOURCE_CCTV} 13행 /
 * {@code LS_DATA_INGEST} <b>0행</b>. 조회 경로를 인입으로 바꾸는 순간 기존 영상 전부가
 * {@code VMS_CCTV_ID} 폴백으로 떨어져 <b>화면 영상명이 이름에서 ID 로 격하</b>된다.
 * 백필이 그것을 막는다.
 *
 * <p>⚠ 이 백필은 <b>테스트 기간 동안 기존 데이터를 잃지 않기 위한 1회성 조치</b>이지 항구적
 * 설계가 아니다(사용자 지시 2026-08-04). 따라서 이 테스트가 고정하는 것은 "백필이 옳다"가 아니라
 * <b>백필이 넘지 말아야 할 선</b>이다 — 파생 제외 · 멱등 · 관제 실수신분 미침범 · 개인정보 3필드
 * 미기재 · sentinel 로 식별 가능. 정리 시점에 sentinel 로 이 행들만 지울 수 있어야 한다.
 *
 * <h3>검증 방식</h3>
 * <p>{@code V164EvntTypeBackfillIT} 와 같은 관례로 <b>배포되는 마이그레이션 파일 원본을 다시
 * 실행</b>한다(테스트가 SQL 을 복제하면 파일이 바뀌어도 통과하는 흉내가 된다).
 * 컨텍스트 기동 시 이미 V167 이 적용돼 4종 마스터가 없으므로, 재실행 전에 <b>V2/V62 원문 DDL 로
 * 스크래치 마스터를 만들고</b> 종료 시 DROP 한다 — 실제 배포 순서(마스터 존재 → 백필 → DROP)를
 * 그대로 재현하는 것이 이 테스트의 목적이다.
 *
 * <p>단, 파일 전체를 실행하면 마스터를 DROP 해 버려 이어지는 검증이 불가능하고 인덱스/주석까지
 * 재실행된다. 그래서 <b>백필 INSERT 문 하나만</b> 파일에서 잘라내 실행한다(문장 경계는
 * {@code ON CONFLICT (VMS_CLIP_ID) DO NOTHING;} 로 특정).
 */
@SpringBootTest
@ActiveProfiles("local")
class V167CctvBackfillIT {

    private static final String MIGRATION = "db/migration/V167__drop_mng_clip_cctv_localgov_tables.sql";
    private static final String CLIP_PREFIX = "V167-BACKFILL-IT-";
    private static final String BACKFILL_MARKER = "[V167-BACKFILL]";

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc;
    private String runId;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        runId = String.valueOf(System.nanoTime());
        createScratchMasters();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM ls_data_ingest WHERE vms_clip_id LIKE ?", CLIP_PREFIX + "%");
        jdbc.update("DELETE FROM ls_data_raw WHERE vms_clip_id LIKE ?", CLIP_PREFIX + "%");
        dropScratchMasters();
    }

    @Test
    @DisplayName("백필_후_기존_원본영상은_CCTV명을_유지한다")
    void backfillPreservesCctvNameForExistingOriginals() throws IOException {
        // given — 인입이 0행이던 기존 원본 영상 + 마스터에 등록된 CCTV
        String cctvId = "CCTV-BF-" + runId;
        long rawSn = seedOrigin("KEEP", cctvId);
        seedCctvMaster(cctvId, "동대문구 회기로 CCTV");
        assertThat(ingestCountOf(rawSn)).isZero();

        // when
        runBackfill();

        // then — 인입 행이 생기고 CCTV 명·좌표가 마스터에서 이관된다(이름→ID 격하 방지)
        Map<String, Object> row = ingestRowOf(rawSn);
        assertThat(row).isNotNull();
        assertThat(row.get("cctv_nm")).isEqualTo("동대문구 회기로 CCTV");
        assertThat(row.get("wgs84_lat")).isNotNull();

        // then — NOT NULL 컬럼이 전부 실측값에서 도출됐다(지어낸 값 없음)
        assertThat(row.get("vms_clip_id")).isEqualTo(CLIP_PREFIX + "KEEP-" + runId);
        assertThat(row.get("vms_cctv_id")).isEqualTo(cctvId);
        assertThat(row.get("src_type")).isEqualTo("ORIGINAL");
        assertThat(row.get("rcptn_dt")).isNotNull();

        // then — ★폴링 대상이 되면 안 된다(PENDING 이면 폴러가 이미 적재된 영상을 재적재 시도)
        assertThat(row.get("prcs_stts_cd")).isEqualTo("DONE");

        // then — ★관제 실수신분과 구분 가능해야 한다(원장 오염 추적용 sentinel)
        assertThat((String) row.get("err_msg")).startsWith(BACKFILL_MARKER);
    }

    @Test
    @DisplayName("백필은_파생영상에_인입행을_만들지_않는다")
    void backfillSkipsDerivedVideos() throws IOException {
        // given — 원본 + 그 파생. 파생은 부모 폴백으로 이름을 얻으므로 자기 인입 행이 필요 없고,
        //   만들면 "파생은 자기 인입 행이 없다" 불변식이 깨져 개인정보 3필드 CASE 판정이 무의미해진다.
        String cctvId = "CCTV-BF-" + runId;
        long parent = seedOrigin("PARENT", cctvId);
        long derived = seedDerived("DERIV", cctvId, parent);
        seedCctvMaster(cctvId, "강남구 테헤란로 CCTV");

        // when
        runBackfill();

        // then
        assertThat(ingestCountOf(parent)).isEqualTo(1);
        assertThat(ingestCountOf(derived)).isZero();
    }

    @Test
    @DisplayName("백필은_멱등하다")
    void backfillIsIdempotent() throws IOException {
        // given
        String cctvId = "CCTV-BF-" + runId;
        long rawSn = seedOrigin("IDEM", cctvId);
        seedCctvMaster(cctvId, "송파구 CCTV");

        // when — 재실행·부분 실패 후 재개를 모사해 3회 실행
        runBackfill();
        runBackfill();
        runBackfill();

        // then — 중복 행이 쌓이지 않는다(NOT EXISTS + ON CONFLICT DO NOTHING)
        assertThat(ingestCountOf(rawSn)).isEqualTo(1);
    }

    @Test
    @DisplayName("백필은_개인정보_3필드를_직접_채우지_않고_DB_DEFAULT_에_맡긴다 (2026-08-04)")
    void backfillLeavesPrivacyFieldsNull() throws IOException {
        // given — ★ 구 기대("전부 null") 폐기: V170 이 LS_DATA_INGEST 3컬럼에 fail-closed DEFAULT
        //   ('N'/'N'/'Y')를 걸었고, 백필 INSERT 가 이 컬럼을 <지정하지 않으므로> DB 가 채운다.
        //   백필 코드가 값을 지어내지 않는다는 원래 취지는 유지된다(SQL 에 이 컬럼이 없다).
        //   결과값은 fail-closed 방향(개인정보 있음)이라 과소 신고가 아니다.
        String cctvId = "CCTV-BF-" + runId;
        long rawSn = seedOrigin("PRIV", cctvId);
        seedCctvMaster(cctvId, "마포구 CCTV");

        // when
        runBackfill();

        // then
        Map<String, Object> row = ingestRowOf(rawSn);
        assertThat(row.get("anony_incl_yn")).isEqualTo("N");
        assertThat(row.get("psdo_incl_yn")).isEqualTo("N");
        assertThat(row.get("prvc_incl_yn")).isEqualTo("Y");
    }

    @Test
    @DisplayName("이미_인입행이_있는_영상은_백필이_덮어쓰지_않는다")
    void backfillDoesNotOverwriteRealControlRows() throws IOException {
        // given — 관제가 실제로 보낸 인입 행이 이미 있는 영상. 위조본으로 덮으면 원장이 오염된다.
        String cctvId = "CCTV-BF-" + runId;
        long rawSn = seedOrigin("REAL", cctvId);
        seedCctvMaster(cctvId, "마스터 이름");
        jdbc.update("INSERT INTO ls_data_ingest "
                        + "(raw_sn, vms_clip_id, vms_cctv_id, vdo_file_nm, raw_file_path_nm, "
                        + " src_type, rcptn_dt, prcs_stts_cd, cctv_nm) "
                        + "VALUES (?, ?, ?, 'real.mp4', '/nas/real.mp4', 'RELAY', now(), 'DONE', ?)",
                rawSn, CLIP_PREFIX + "REAL-ING-" + runId, cctvId, "관제가 보낸 이름");

        // when
        runBackfill();

        // then — 행 수 불변 + 관제 값 보존 + sentinel 없음(관제 실수신분임이 유지된다)
        assertThat(ingestCountOf(rawSn)).isEqualTo(1);
        Map<String, Object> row = ingestRowOf(rawSn);
        assertThat(row.get("cctv_nm")).isEqualTo("관제가 보낸 이름");
        assertThat(row.get("err_msg")).isNull();
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * V172 가 인입 컬럼을 표준 물리명으로 개명한 뒤에도 <b>배포 파일 원문</b>을 재생하기 위한 매핑.
     *
     * <h3>왜 필요한가</h3>
     * <p>이 테스트는 <b>지금 스키마</b>(V172 적용 후) 위에서 V167 파일을 다시 실행한다. 그런데 V167 은
     * 자기가 쓰이던 시점의 물리명({@code PROC_STTS_CD}·{@code RGN_NM})으로 쓰여 있고 <b>과거
     * 마이그레이션은 수정하지 않는다</b>(Flyway 체크섬·이력 불변). 실제 배포에서는 V167 이 V172
     * <b>앞</b>에 돌아 문제가 없지만, 재생은 순서를 거스르므로 이름만 현재 스키마로 옮겨준다.
     *
     * <p>SQL <b>구조</b>(대상 컬럼 집합·술어·ON CONFLICT·sentinel)는 그대로 파일에서 온다 — 이 테스트가
     * 고정하려는 "백필이 넘지 말아야 할 선"은 이름 치환에 영향받지 않는다.
     */
    private static final Map<String, String> V172_RENAMES =
            Map.of("PROC_STTS_CD", "PRCS_STTS_CD", "RGN_NM", "LCLGV_NM");

    /** 배포되는 V167 파일에서 <b>백필 INSERT 문만</b> 잘라 실행한다(마스터 DROP 은 하지 않는다). */
    private void runBackfill() throws IOException {
        String sql = new String(new ClassPathResource(MIGRATION).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        int start = sql.indexOf("INSERT INTO LS_DATA_INGEST");
        assertThat(start).as("V167 에 백필 INSERT 문이 있어야 한다").isNotNegative();
        int end = sql.indexOf("ON CONFLICT (VMS_CLIP_ID) DO NOTHING;", start);
        assertThat(end).as("백필 INSERT 의 문장 끝(ON CONFLICT ... ;)을 찾을 수 있어야 한다").isNotNegative();
        String statement = sql.substring(start, end + "ON CONFLICT (VMS_CLIP_ID) DO NOTHING;".length());
        for (Map.Entry<String, String> rename : V172_RENAMES.entrySet()) {
            statement = statement.replaceAll("\\b" + rename.getKey() + "\\b", rename.getValue());
        }
        jdbc.execute(statement);
    }

    private long seedOrigin(String suffix, String cctvId) {
        return seedRaw(suffix, cctvId, null);
    }

    private long seedDerived(String suffix, String cctvId, long parentRawSn) {
        return seedRaw(suffix, cctvId, parentRawSn);
    }

    private long seedRaw(String suffix, String cctvId, Long orgnlRawSn) {
        String clipId = CLIP_PREFIX + suffix + "-" + runId;
        jdbc.update("""
                INSERT INTO ls_data_raw
                    (vms_clip_id, vms_cctv_id, prvc_type_cd, prvc_yn, de_ident_yn,
                     raw_file_path_nm, data_stts_cd, reg_dt, evnt_type_cd, orgnl_raw_sn, src_type)
                VALUES (?, ?, 'PRVC', 'N', 'N', '/nas/v167/clip.mp4', 'COMPLETED',
                        CURRENT_TIMESTAMP, 'INTRUSION', ?, 'ORIGINAL')
                """, clipId, cctvId, orgnlRawSn);
        return jdbc.queryForObject(
                "SELECT raw_sn FROM ls_data_raw WHERE vms_clip_id = ?", Long.class, clipId);
    }

    private void seedCctvMaster(String cctvId, String cctvNm) {
        jdbc.update("INSERT INTO public.MNG_RESOURCE_CCTV "
                        + "(VMS_CCTV_ID, CCTV_NM, WGS84_LAT, WGS84_LOT, USE_YN) "
                        + "VALUES (?, ?, 37.5665000, 126.9780000, 'Y')", cctvId, cctvNm);
    }

    private long ingestCountOf(long rawSn) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ls_data_ingest WHERE raw_sn = ?", Long.class, rawSn);
        return n == null ? 0L : n;
    }

    private Map<String, Object> ingestRowOf(long rawSn) {
        return jdbc.queryForMap("SELECT * FROM ls_data_ingest WHERE raw_sn = ?", rawSn);
    }

    /** V2/V62 원문과 같은 DDL 로 스크래치 마스터 생성 — 실제 배포 시점(마스터 생존)을 재현한다. */
    private void createScratchMasters() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS public.MNG_RESOURCE_CCTV (
                    VMS_CCTV_ID     VARCHAR(64)     NOT NULL,
                    CCTV_NM         VARCHAR(255),
                    SHT_ADDR        VARCHAR(500),
                    OG_NM           VARCHAR(255),
                    WGS84_LAT       DECIMAL(10,7),
                    WGS84_LOT       DECIMAL(10,7),
                    RESOLUTION      VARCHAR(32),
                    USE_YN          VARCHAR(1)      NOT NULL DEFAULT 'Y',
                    PRIMARY KEY (VMS_CCTV_ID)
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS public.MNG_EX_LOCAL_GOV (
                    LCLGV_CD        VARCHAR(32)     NOT NULL,
                    SIDO_NM         VARCHAR(64),
                    SGG_NM          VARCHAR(64),
                    USE_YN          VARCHAR(1)      NOT NULL DEFAULT 'Y',
                    PRIMARY KEY (LCLGV_CD)
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS public.MNG_CLIP_MASTER (
                    EVNT_ID       VARCHAR(50)   NOT NULL,
                    CLIP_TYPE_CD  VARCHAR(20)   NOT NULL,
                    CLIP_ID       VARCHAR(50),
                    LCLGV_CD      VARCHAR(20),
                    FILE_NM       VARCHAR(256),
                    FILE_PATH     VARCHAR(1000),
                    FILE_FMT      VARCHAR(10),
                    VDO_LEN_SEC   INT,
                    CLIP_STTS_CD  VARCHAR(20),
                    CRT_DT        TIMESTAMP,
                    ULD_CMPT_DT   TIMESTAMP,
                    JOB_DMND_YN   VARCHAR(1),
                    VMS_CCTV_ID   VARCHAR(30),
                    PRIMARY KEY (EVNT_ID, CLIP_TYPE_CD)
                )""");
    }

    private void dropScratchMasters() {
        jdbc.execute("DROP TABLE IF EXISTS public.MNG_CLIP_MASTER");
        jdbc.execute("DROP TABLE IF EXISTS public.MNG_RESOURCE_CCTV");
        jdbc.execute("DROP TABLE IF EXISTS public.MNG_EX_LOCAL_GOV");
    }
}
