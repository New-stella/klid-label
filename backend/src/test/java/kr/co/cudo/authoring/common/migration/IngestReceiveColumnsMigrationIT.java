package kr.co.cudo.authoring.common.migration;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V166/V170 — {@code LS_DATA_INGEST} 수신 컬럼 4종 신설 + <b>원천 개인정보 3컬럼 fail-closed DB
 * DEFAULT</b> 실동작 검증 (Testcontainers PostgreSQL, Flyway migrate 후 부팅).
 *
 * <h3>왜 정보 스키마를 직접 대조하나</h3>
 * <p>이 프로젝트의 {@code ddl-auto=validate} 는 실제로 동작하지 않는다({@code JpaBuilderConfig} 가
 * {@code spring.jpa.hibernate.*} 를 EMF 에 넘기지 않아 <b>없는 컬럼을 매핑해도 기동이 성공</b>한다).
 * 따라서 "기동 성공"을 엔티티↔DDL 정합의 증거로 삼을 수 없으므로 신규 컬럼의 물리명·타입·크기·
 * NULL 허용을 {@code information_schema} 로 1:1 대조한다
 * ({@code VideoPrivacyMetaMigrationIT}(V163)와 동일 골격).
 *
 * <h3>검증 축</h3>
 * <ol>
 *   <li><b>신설 4종</b>({@code EVNT_TYPE_CD} + 원천 개인정보 3필드)의 타입·크기·nullable·DEFAULT 없음</li>
 *   <li><b>{@code LS_DATA_RAW} 무변경</b> — 이 마이그레이션은 작업 대상 마스터에 컬럼을 만들지 않는다
 *       (관제가 준 읽기 전용 사실은 인입이 단일 진실원이며 조회 시 조인으로 읽는다)</li>
 *   <li><b>선존 비식별 축 3컬럼 보존</b> — 이름이 같아 섞기 쉬운 지점이라 별도로 고정한다</li>
 *   <li><b>조인 폴백 성립</b> — {@code COALESCE(ORGNL_RAW_SN, RAW_SN)} 로 원본/파생이 모두 해석된다</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class IngestReceiveColumnsMigrationIT {

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @PersistenceContext
    private EntityManager em;

    /** V166 이 신설한 원천 개인정보 3컬럼(여부 = CHAR(1)). */
    private static final List<String> NEW_PRIVACY_COLUMNS =
            List.of("anony_incl_yn", "psdo_incl_yn", "prvc_incl_yn");

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(controlDataSource);
    }

    private Map<String, Object> columnMeta(String table, String column) {
        return jdbc().queryForMap(
                "SELECT data_type, character_maximum_length, numeric_precision, numeric_scale,"
                        + " is_nullable, column_default"
                        + " FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                table, column);
    }

    private boolean columnExists(String table, String column) {
        Long count = jdbc().queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns"
                        + " WHERE table_name = ? AND column_name = ?", Long.class, table, column);
        return count != null && count > 0;
    }

    @Test
    @DisplayName("V166_인입_EVNT_TYPE_CD가_코드표준_VARCHAR20_NULL허용으로_생성된다")
    void V166_인입_EVNT_TYPE_CD가_생성된다() {
        Map<String, Object> meta = columnMeta("ls_data_ingest", "evnt_type_cd");

        assertThat(meta.get("data_type")).isEqualTo("character varying");
        // 코드값 표준도메인(코드V20). 선존 LS_DATA_RAW.EVNT_TYPE_CD·MNG_CLIP_EVNT_LST 와 같은 길이라
        // 어느 경로로 채워지든 절단이 없다.
        assertThat(((Number) meta.get("character_maximum_length")).intValue()).isEqualTo(20);
        // 관제가 채우기 전까지 null — 그동안 적재 경로가 EVNT_ID 해석으로 폴백한다(과도기).
        assertThat(meta.get("is_nullable")).isEqualTo("YES");
        assertThat(meta.get("column_default")).isNull();
    }

    @Test
    @DisplayName("V166_인입_원천개인정보_3컬럼이_CHAR1_NULL허용으로_생성된다")
    void V166_인입_원천개인정보_3컬럼이_생성된다() {
        for (String column : NEW_PRIVACY_COLUMNS) {
            Map<String, Object> meta = columnMeta("ls_data_ingest", column);

            assertThat(meta.get("data_type")).as("ls_data_ingest.%s 타입(여부 = CHAR(1))", column)
                    .isEqualTo("character");
            assertThat(((Number) meta.get("character_maximum_length")).intValue())
                    .as("ls_data_ingest.%s 길이", column).isEqualTo(1);
            assertThat(meta.get("is_nullable")).as("ls_data_ingest.%s NULL 허용", column).isEqualTo("YES");
        }
    }

    @Test
    @DisplayName("V170_원천개인정보_3컬럼에_fail_closed_DEFAULT가_걸려있다")
    void V170_원천개인정보_3컬럼에_DEFAULT가_걸려있다() {
        // then — 원천 영상은 비식별 처리 <전> 이라 개인정보가 남아 있는 것이 기본 상태다(PRVC=Y).
        //   익명·가명은 "처리를 거쳤다"는 주장이라 근거 없이 Y 로 볼 수 없다(N).
        assertThat((String) columnMeta("ls_data_ingest", "anony_incl_yn").get("column_default"))
                .as("익명정보 포함여부 DEFAULT").startsWith("'N'");
        assertThat((String) columnMeta("ls_data_ingest", "psdo_incl_yn").get("column_default"))
                .as("가명정보 포함여부 DEFAULT").startsWith("'N'");
        assertThat((String) columnMeta("ls_data_ingest", "prvc_incl_yn").get("column_default"))
                .as("개인정보 포함여부 DEFAULT").startsWith("'Y'");
    }

    @Test
    @DisplayName("인입_개인정보_3필드는_DB_DEFAULT_로_채워진다")
    void 인입_개인정보_3필드는_DB_DEFAULT_로_채워진다() {
        // given / when — 관제가 3컬럼을 <지정하지 않고> INSERT 한 경우(내부 업로드 통로도 동일 형상이다)
        String clipId = "CLIP-DEF-" + System.nanoTime();
        jdbc().update("""
                INSERT INTO ls_data_ingest
                    (vms_clip_id, vms_cctv_id, vdo_file_nm, raw_file_path_nm, src_type,
                     rcptn_dt, prcs_stts_cd)
                VALUES (?, 'CCTV-DEF', 'd.mp4', '/nas/raw/d.mp4', 'RELAY', now(), 'PENDING')
                """, clipId);

        // then — 원천 축에 null 이 생기지 않는다(export 원천 판정이 "판정 없음"으로 비지 않는다).
        Map<String, Object> row = jdbc().queryForMap(
                "SELECT anony_incl_yn, psdo_incl_yn, prvc_incl_yn FROM ls_data_ingest WHERE vms_clip_id = ?",
                clipId);
        assertThat(row.get("anony_incl_yn")).isEqualTo("N");
        assertThat(row.get("psdo_incl_yn")).isEqualTo("N");
        assertThat(row.get("prvc_incl_yn")).isEqualTo("Y");
    }

    @Test
    @DisplayName("관제가_명시한_값은_DEFAULT_를_덮어쓴다")
    void 관제가_명시한_값은_DEFAULT_를_덮어쓴다() {
        // given / when — 관제가 실제 판정을 실어 보낸 경우
        String clipId = "CLIP-EXP-" + System.nanoTime();
        jdbc().update("""
                INSERT INTO ls_data_ingest
                    (vms_clip_id, vms_cctv_id, vdo_file_nm, raw_file_path_nm, src_type,
                     rcptn_dt, prcs_stts_cd, anony_incl_yn, psdo_incl_yn, prvc_incl_yn)
                VALUES (?, 'CCTV-EXP', 'e.mp4', '/nas/raw/e.mp4', 'RELAY', now(), 'PENDING',
                        'Y', 'Y', 'N')
                """, clipId);

        // then — DEFAULT 는 미지정일 때만 적용된다. 관제 판정이 정본이다.
        Map<String, Object> row = jdbc().queryForMap(
                "SELECT anony_incl_yn, psdo_incl_yn, prvc_incl_yn FROM ls_data_ingest WHERE vms_clip_id = ?",
                clipId);
        assertThat(row.get("anony_incl_yn")).isEqualTo("Y");
        assertThat(row.get("psdo_incl_yn")).isEqualTo("Y");
        assertThat(row.get("prvc_incl_yn")).isEqualTo("N");
    }

    @Test
    @DisplayName("파생영상은_DEFAULT_와_무관하게_원천축이_null_이다")
    void 파생영상은_DEFAULT_와_무관하게_원천축이_null_이다() {
        // given — 부모 인입 행은 DEFAULT 로 3필드가 모두 채워져 있다.
        String clipId = "CLIP-DRV-" + System.nanoTime();
        LsDataRaw parent = videoRepository.saveAndFlush(LsDataRaw.createFromIngest(
                clipId, "CCTV-DRV", "EVT_FALL", "LGV01", "PRVC",
                "/nas/raw/drv.mp4", LocalDateTime.now(), 30));
        jdbc().update("""
                INSERT INTO ls_data_ingest
                    (vms_clip_id, vms_cctv_id, vdo_file_nm, raw_file_path_nm, src_type,
                     rcptn_dt, prcs_stts_cd, raw_sn)
                VALUES (?, 'CCTV-DRV', 'drv.mp4', '/nas/raw/drv.mp4', 'RELAY', now(), 'DONE', ?)
                """, clipId, parent.getRawSn());
        LsDataRaw derived = videoRepository.saveAndFlush(
                LsDataRaw.createFromResolution(parent, "/nas/resl/480p.mp4", "RESL_480P"));
        em.flush();

        // when / then — 파생은 부모의 <비식별본>으로 만들어져 원천 영상 자체가 없다. DEFAULT 가 부모
        //   행을 채웠어도 파생에는 물려주지 않는다(결손이 아니라 정상).
        Map<String, Object> row = jdbc().queryForMap("""
                SELECT CASE WHEN r.ORGNL_RAW_SN IS NULL THEN i.PRVC_INCL_YN ELSE NULL END AS prvc_incl_yn
                  FROM LS_DATA_RAW r
                  LEFT JOIN LS_DATA_INGEST i ON i.RAW_SN = COALESCE(r.ORGNL_RAW_SN, r.RAW_SN)
                 WHERE r.RAW_SN = ?
                """, derived.getRawSn());
        assertThat(row.get("prvc_incl_yn")).isNull();
    }

    @Test
    @DisplayName("V166은_LS_DATA_RAW에_컬럼을_만들지_않는다")
    void V166은_LS_DATA_RAW에_컬럼을_만들지_않는다() {
        // ★ 설계 확정: 관제가 준 <읽기 전용 사실>을 작업 대상 마스터(LS_DATA_RAW — 상태 전이·라벨링·
        //   검수가 붙는 가변 테이블)에 복사하지 않는다. 복사하면 같은 값이 두 곳에 생기고, 수정될 일이
        //   없는 값에 대해 이중 저장소를 유지하게 된다. 조회는 인입 조인으로 한다.
        // ("rgn_nm" 은 V172 로 인입에서 lclgv_nm 이 됐다 — 두 이름 모두 RAW 에 없어야 한다)
        for (String column : List.of("cctv_nm", "evnt_nm", "rgn_nm", "lclgv_nm", "file_fmt",
                "wgs84_lat", "wgs84_lot",
                "sou_anony_incl_yn", "sou_psdo_incl_yn", "sou_prvc_incl_yn")) {
            assertThat(columnExists("ls_data_raw", column))
                    .as("ls_data_raw.%s — 이 마이그레이션은 RAW 에 컬럼을 만들지 않는다", column)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("선존_비식별축_3컬럼은_V166이_건드리지_않아_그대로_남아있다")
    void 선존_비식별축_3컬럼은_그대로_남아있다() {
        // ★★ 이름이 <완전히 같아> 섞기 쉬운 지점이다. LS_DATA_RAW 의 3컬럼(V163)은 <비식별 영상에
        //   대한 사람의 수동 판정>이고, V166 이 만든 동명 3컬럼은 LS_DATA_INGEST 에 있는 <원천 영상
        //   (비식별 전)에 대한 관제 수신 판정>이다. V166 이 선존 컬럼을 rename·drop 했다면 사람이
        //   입력한 판정이 전부 소실된다(비가역).
        for (String column : NEW_PRIVACY_COLUMNS) {
            Map<String, Object> meta = columnMeta("ls_data_raw", column);
            assertThat(meta.get("data_type")).as("ls_data_raw.%s (V163 선존 — 비식별 축)", column)
                    .isEqualTo("character");
            assertThat(((Number) meta.get("character_maximum_length")).intValue()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("V147_선존_이름_포맷_좌표_컬럼은_인입에_이미_있어_재신설_대상이_아니다")
    void V147_선존_컬럼은_재신설_대상이_아니다() {
        // 이 6종은 <관제가 이미 보내고 있는> 값이다. 저작도구가 그 값을 쓰지 않고 MNG_* 마스터를
        // 조인하던 것이 결함이며, V166 은 여기에 아무것도 추가하지 않는다.
        assertThat(((Number) columnMeta("ls_data_ingest", "cctv_nm").get("character_maximum_length")).intValue())
                .isEqualTo(300);
        assertThat(((Number) columnMeta("ls_data_ingest", "evnt_nm").get("character_maximum_length")).intValue())
                .isEqualTo(200);
        // V172 로 물리명·길이가 표준(LCLGV_NM · 명V100)으로 정정됐다 — 구 rgn_nm(200) 아님.
        assertThat(((Number) columnMeta("ls_data_ingest", "lclgv_nm").get("character_maximum_length")).intValue())
                .isEqualTo(100);
        assertThat(((Number) columnMeta("ls_data_ingest", "file_fmt").get("character_maximum_length")).intValue())
                .isEqualTo(20);
        for (String column : List.of("wgs84_lat", "wgs84_lot")) {
            Map<String, Object> meta = columnMeta("ls_data_ingest", column);
            assertThat(meta.get("data_type")).as("ls_data_ingest.%s (V147 선존)", column).isEqualTo("numeric");
            // 좌표D10 = 정수 3 + 소수 7.
            assertThat(((Number) meta.get("numeric_precision")).intValue()).isEqualTo(10);
            assertThat(((Number) meta.get("numeric_scale")).intValue()).isEqualTo(7);
        }
    }

    @Test
    @DisplayName("파생영상도_ORGNL_RAW_SN_폴백으로_부모_인입행에_도달한다")
    void 파생영상도_폴백조인으로_부모_인입행에_도달한다() {
        // given — 인입 행이 있는 원본 + 그 파생영상
        String clipId = "CLIP-JOIN-" + System.nanoTime();
        LsDataRaw parent = videoRepository.saveAndFlush(LsDataRaw.createFromIngest(
                clipId, "CCTV-JOIN", "EVT_FALL", "LGV01", "PRVC",
                "/nas/raw/join.mp4", LocalDateTime.now(), 30));
        jdbc().update("""
                INSERT INTO ls_data_ingest
                    (vms_clip_id, vms_cctv_id, vdo_file_nm, raw_file_path_nm, src_type,
                     rcptn_dt, prcs_stts_cd, raw_sn, cctv_nm, anony_incl_yn)
                VALUES (?, 'CCTV-JOIN', 'join.mp4', '/nas/raw/join.mp4', 'RELAY',
                        now(), 'DONE', ?, '유성구 지하차도 3번', 'N')
                """, clipId, parent.getRawSn());
        LsDataRaw derived = videoRepository.saveAndFlush(
                LsDataRaw.createFromResolution(parent, "/nas/resl/720p.mp4", "RESL_720P"));
        em.flush();

        // when — Phase 3 에서 쓸 조회 패턴. 파생은 자기 인입 행이 없으므로 부모 행으로 폴백한다.
        //   파생 깊이가 1 로 고정(확정 정책)이라 1단계 폴백이면 충분하다(재귀 불필요).
        Map<String, Object> row = jdbc().queryForMap("""
                SELECT i.CCTV_NM AS cctv_nm,
                       CASE WHEN r.ORGNL_RAW_SN IS NULL THEN i.ANONY_INCL_YN ELSE NULL END AS anony_incl_yn
                  FROM LS_DATA_RAW r
                  LEFT JOIN LS_DATA_INGEST i ON i.RAW_SN = COALESCE(r.ORGNL_RAW_SN, r.RAW_SN)
                 WHERE r.RAW_SN = ?
                """, derived.getRawSn());

        // then — 이름은 부모 인입 값이 그대로 유효하다(파생도 같은 CCTV·장소·이벤트).
        assertThat(row.get("cctv_nm")).isEqualTo("유성구 지하차도 3번");
        // then — ★개인정보 원천 축은 파생에서 null 이다. 파생은 부모의 <비식별본>으로 만들어진 것이라
        //   "비식별 처리 전 원천"이라는 대상 자체가 없다. 파생의 개인정보 판정은 비식별 축(자기 행,
        //   copyPrivacyMetaFrom 로 부모에서 계승)에서 오므로 결손이 아니다.
        assertThat(row.get("anony_incl_yn")).as("파생은 원천 축을 물려받지 않는다").isNull();

        // then — 원본은 자기 인입 행을 그대로 본다(폴백이 원본을 망가뜨리지 않는다).
        Map<String, Object> parentRow = jdbc().queryForMap("""
                SELECT CASE WHEN r.ORGNL_RAW_SN IS NULL THEN i.ANONY_INCL_YN ELSE NULL END AS anony_incl_yn
                  FROM LS_DATA_RAW r
                  LEFT JOIN LS_DATA_INGEST i ON i.RAW_SN = COALESCE(r.ORGNL_RAW_SN, r.RAW_SN)
                 WHERE r.RAW_SN = ?
                """, parent.getRawSn());
        assertThat(parentRow.get("anony_incl_yn")).isEqualTo("N");
    }
}
