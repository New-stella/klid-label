package kr.co.cudo.authoring.common.migration;

import kr.co.cudo.authoring.video.entity.MngClipEvntLst;
import kr.co.cudo.authoring.video.entity.MngClipMaster;
import kr.co.cudo.authoring.video.repository.MngClipEvntLstRepository;
import kr.co.cudo.authoring.video.repository.MngClipMasterRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Phase 1 — 관제 클립 stub 스키마를 LogiCraft <b>ERD-024</b>(관제 공유 클립 ERD, 2026-06-10 관제 DB
 * 직접 조회로 확정) 전체 컬럼으로 정합한 V147 검증 (Testcontainers PostgreSQL).
 *
 * <p>본 Phase 는 <b>동작 변화 0</b> 이고 "읽을 수 있는 컬럼"만 늘린다. 따라서 검증 축은 네 가지다.
 * <ol>
 *   <li>stub 이 ERD-024 전체 컬럼을 갖는다 (마스터 16 / 이벤트리스트 12).</li>
 *   <li>추가 컬럼의 타입·길이가 ERD-024 정본과 정확히 일치하고 <b>전부 nullable</b> 이다
 *       (실 관제 테이블에 기존 행이 있어도 NOT NULL 로 실패하지 않게).</li>
 *   <li>V62/V63 이 만든 <b>기존 컬럼 스펙이 하나도 변경되지 않았다</b> — 관제 소유 테이블 보호
 *       (docs/관제팀-공유테이블-변경금지-가이드.md §3 보호 컬럼 회귀 가드).</li>
 *   <li>기존 학습용 픽업 쿼리와 엔티티 매핑(ddl-auto=validate)이 그대로 동작한다.</li>
 * </ol>
 *
 * <p>시드는 {@code E24IT-*} 접두로 격리하고 매 테스트 전후 정리해 다른 통합테스트를 오염시키지 않는다.
 */
@SpringBootTest
@ActiveProfiles("local")
class MngClipStubErd024AlignmentIT {

    private static final String MIGRATION_PATH = "db/migration/V147__align_mng_clip_stub_with_erd024.sql";
    private static final String CCTV_ID = "CCTV-001";

    /** ERD-024 정본 — MNG_CLIP_MASTER 16컬럼 (컬럼명 → "data_type:length", length 미적용은 null). */
    private static final Map<String, String> MASTER_SPEC = new LinkedHashMap<>();
    /** ERD-024 정본 — MNG_CLIP_EVNT_LST 12컬럼. */
    private static final Map<String, String> EVNT_LST_SPEC = new LinkedHashMap<>();

    static {
        // V62 기존 13컬럼 (보호 대상 — 스펙 변경 0건 회귀 가드)
        MASTER_SPEC.put("evnt_id", "character varying:50");
        MASTER_SPEC.put("clip_type_cd", "character varying:20");
        MASTER_SPEC.put("clip_id", "character varying:50");
        MASTER_SPEC.put("lclgv_cd", "character varying:20");
        MASTER_SPEC.put("file_nm", "character varying:256");
        MASTER_SPEC.put("file_path", "character varying:1000");
        MASTER_SPEC.put("file_fmt", "character varying:10");
        MASTER_SPEC.put("vdo_len_sec", "integer:null");
        MASTER_SPEC.put("clip_stts_cd", "character varying:20");
        MASTER_SPEC.put("crt_dt", "timestamp without time zone:null");
        MASTER_SPEC.put("uld_cmpt_dt", "timestamp without time zone:null");
        MASTER_SPEC.put("job_dmnd_yn", "character varying:1");
        MASTER_SPEC.put("vms_cctv_id", "character varying:30");
        // V147 추가 3컬럼
        MASTER_SPEC.put("file_sz", "bigint:null");
        MASTER_SPEC.put("job_dmnd_prnmnt_yn", "character varying:1");
        MASTER_SPEC.put("crt_type", "integer:null");

        // V63 기존 3컬럼 (보호 대상)
        EVNT_LST_SPEC.put("evnt_id", "character varying:50");
        EVNT_LST_SPEC.put("evnt_type_cd", "character varying:20");
        EVNT_LST_SPEC.put("sht_dt", "timestamp without time zone:null");
        // V147 추가 9컬럼
        EVNT_LST_SPEC.put("evnt_nm", "character varying:200");
        EVNT_LST_SPEC.put("lclgv_cd", "character varying:20");
        EVNT_LST_SPEC.put("sesn_cd", "character varying:10");
        EVNT_LST_SPEC.put("wthr_cd", "character varying:10");
        EVNT_LST_SPEC.put("hr_type_cd", "character varying:10");
        EVNT_LST_SPEC.put("prvc_type_cd", "character varying:20");
        EVNT_LST_SPEC.put("idntf_yn", "character varying:1");
        EVNT_LST_SPEC.put("clct_path", "character varying:255");
        EVNT_LST_SPEC.put("clct_src", "character varying:255");
    }

    /** V147 이 새로 추가하는 컬럼(= nullable 이어야 하는 대상). */
    private static final List<String> ADDED_MASTER_COLUMNS =
            List.of("file_sz", "job_dmnd_prnmnt_yn", "crt_type");
    private static final List<String> ADDED_EVNT_LST_COLUMNS =
            List.of("evnt_nm", "lclgv_cd", "sesn_cd", "wthr_cd", "hr_type_cd",
                    "prvc_type_cd", "idntf_yn", "clct_path", "clct_src");

    @Autowired
    private MngClipMasterRepository clipMasterRepository;

    @Autowired
    private MngClipEvntLstRepository clipEvntLstRepository;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanup();
        jdbc.update("INSERT INTO MNG_RESOURCE_CCTV (VMS_CCTV_ID, CCTV_NM, USE_YN) VALUES (?, ?, 'Y') "
                + "ON CONFLICT (VMS_CCTV_ID) DO NOTHING", CCTV_ID, "CCTV-테스트-001");
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM LS_DATA_RAW WHERE VMS_CLIP_ID LIKE 'E24IT-CLIP-%'");
        jdbc.update("DELETE FROM MNG_CLIP_EVNT_LST WHERE EVNT_ID LIKE 'E24IT-EVT-%'");
        jdbc.update("DELETE FROM MNG_CLIP_MASTER WHERE EVNT_ID LIKE 'E24IT-EVT-%'");
    }

    /** public 스키마 한정 컬럼 메타 조회 (컬럼명 → "data_type:length"). */
    private Map<String, String> columnSpecs(String table) {
        Map<String, String> specs = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT column_name, data_type, character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                """, table)) {
            Object len = row.get("character_maximum_length");
            specs.put((String) row.get("column_name"),
                    row.get("data_type") + ":" + (len == null ? "null" : ((Number) len).intValue()));
        }
        return specs;
    }

    private String nullability(String table, String column) {
        return jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """, String.class, table, column);
    }

    @Test
    @DisplayName("stub_에_ERD024_마스터_전체컬럼이_존재한다")
    void stub_에_ERD024_마스터_전체컬럼이_존재한다() {
        // given / when
        Map<String, String> actual = columnSpecs("mng_clip_master");

        // then — ERD-024 마스터 16컬럼 정확히 일치(누락도 잉여도 없음)
        assertThat(actual.keySet())
                .as("MNG_CLIP_MASTER 는 ERD-024 전체 16컬럼")
                .containsExactlyInAnyOrderElementsOf(MASTER_SPEC.keySet());
    }

    @Test
    @DisplayName("stub_에_ERD024_이벤트리스트_전체컬럼이_존재한다")
    void stub_에_ERD024_이벤트리스트_전체컬럼이_존재한다() {
        // given / when
        Map<String, String> actual = columnSpecs("mng_clip_evnt_lst");

        // then — ERD-024 이벤트리스트 12컬럼 정확히 일치
        assertThat(actual.keySet())
                .as("MNG_CLIP_EVNT_LST 는 ERD-024 전체 12컬럼")
                .containsExactlyInAnyOrderElementsOf(EVNT_LST_SPEC.keySet());
    }

    @Test
    @DisplayName("추가된_컬럼의_타입과_길이가_ERD024와_일치한다")
    void 추가된_컬럼의_타입과_길이가_ERD024와_일치한다() {
        // given
        Map<String, String> master = columnSpecs("mng_clip_master");
        Map<String, String> evntLst = columnSpecs("mng_clip_evnt_lst");

        // when / then — 추가 컬럼은 ERD-024 타입·길이 그대로이며 전부 nullable
        for (String col : ADDED_MASTER_COLUMNS) {
            assertThat(master.get(col)).as("mng_clip_master.%s 타입·길이", col)
                    .isEqualTo(MASTER_SPEC.get(col));
            assertThat(nullability("mng_clip_master", col))
                    .as("mng_clip_master.%s 는 nullable(실 관제 테이블 기존 행 보호)", col)
                    .isEqualTo("YES");
        }
        for (String col : ADDED_EVNT_LST_COLUMNS) {
            assertThat(evntLst.get(col)).as("mng_clip_evnt_lst.%s 타입·길이", col)
                    .isEqualTo(EVNT_LST_SPEC.get(col));
            assertThat(nullability("mng_clip_evnt_lst", col))
                    .as("mng_clip_evnt_lst.%s 는 nullable", col)
                    .isEqualTo("YES");
        }
    }

    @Test
    @DisplayName("기존_컬럼의_타입과_길이가_변경되지_않았다")
    void 기존_컬럼의_타입과_길이가_변경되지_않았다() {
        // given — V62/V63 이 만든 기존 컬럼(= 관제팀 보호 컬럼) 스펙
        Map<String, String> master = columnSpecs("mng_clip_master");
        Map<String, String> evntLst = columnSpecs("mng_clip_evnt_lst");

        // when / then — 추가분을 제외한 기존 컬럼 스펙이 그대로다(ALTER TYPE/DROP/RENAME 0건)
        MASTER_SPEC.forEach((col, spec) -> {
            if (!ADDED_MASTER_COLUMNS.contains(col)) {
                assertThat(master.get(col)).as("보호 컬럼 mng_clip_master.%s 무변경", col).isEqualTo(spec);
            }
        });
        EVNT_LST_SPEC.forEach((col, spec) -> {
            if (!ADDED_EVNT_LST_COLUMNS.contains(col)) {
                assertThat(evntLst.get(col)).as("보호 컬럼 mng_clip_evnt_lst.%s 무변경", col).isEqualTo(spec);
            }
        });

        // then — 복합 PK 도 그대로 (EVNT_ID, CLIP_TYPE_CD) / (EVNT_ID, EVNT_TYPE_CD)
        assertThat(primaryKeyColumns("mng_clip_master")).containsExactly("evnt_id", "clip_type_cd");
        assertThat(primaryKeyColumns("mng_clip_evnt_lst")).containsExactly("evnt_id", "evnt_type_cd");
    }

    private List<String> primaryKeyColumns(String table) {
        return jdbc.queryForList("""
                SELECT a.attname
                FROM pg_index i
                JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
                WHERE i.indrelid = ('public.' || ?)::regclass AND i.indisprimary
                ORDER BY array_position(i.indkey, a.attnum)
                """, String.class, table);
    }

    @Test
    @DisplayName("마이그레이션_재실행시_멱등하다")
    void 마이그레이션_재실행시_멱등하다() throws Exception {
        // given — 최초 적용 후 스펙
        Map<String, String> masterBefore = columnSpecs("mng_clip_master");
        Map<String, String> evntLstBefore = columnSpecs("mng_clip_evnt_lst");
        String sql = new String(new ClassPathResource(MIGRATION_PATH).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        // when — 동일 마이그레이션을 두 번 더 수동 적용
        assertThatCode(() -> {
            jdbc.execute(sql);
            jdbc.execute(sql);
        }).doesNotThrowAnyException();

        // then — 스키마가 전혀 달라지지 않는다(ADD COLUMN 은 존재 시 no-op)
        assertThat(columnSpecs("mng_clip_master")).isEqualTo(masterBefore);
        assertThat(columnSpecs("mng_clip_evnt_lst")).isEqualTo(evntLstBefore);
    }

    @Test
    @DisplayName("엔티티_매핑이_validate를_통과한다")
    void 엔티티_매핑이_validate를_통과한다() {
        // given / when / then — ddl-auto=validate 하에 컨텍스트가 로드됨 = 추가 매핑 컬럼 ↔ DDL 정합
        assertThat(clipMasterRepository).isNotNull();
        assertThat(clipEvntLstRepository).isNotNull();
    }

    @Test
    @DisplayName("기존_학습용_픽업_쿼리가_그대로_동작한다")
    void 기존_학습용_픽업_쿼리가_그대로_동작한다() {
        // given — 학습용 지정(JOB_DMND_YN='Y') 미적재 클립 1건 + 추가 컬럼 값 세팅
        jdbc.update("""
                INSERT INTO MNG_CLIP_MASTER
                    (EVNT_ID, CLIP_TYPE_CD, CLIP_ID, LCLGV_CD, FILE_NM, FILE_PATH, FILE_FMT,
                     VDO_LEN_SEC, CLIP_STTS_CD, CRT_DT, JOB_DMND_YN, VMS_CCTV_ID,
                     FILE_SZ, JOB_DMND_PRNMNT_YN, CRT_TYPE)
                VALUES (?, 'ORIGINAL', ?, '11110', 'c.mp4', ?, 'mp4',
                        30000, 'mediainfo_complete', ?, 'Y', ?, ?, 'N', 0)
                """, "E24IT-EVT-1", "E24IT-CLIP-1", "./storage/raw/seed/e24it.mp4",
                LocalDateTime.now(), CCTV_ID, 123_456_789L);
        jdbc.update("""
                INSERT INTO MNG_CLIP_EVNT_LST
                    (EVNT_ID, EVNT_TYPE_CD, SHT_DT, EVNT_NM, LCLGV_CD, SESN_CD, WTHR_CD,
                     HR_TYPE_CD, PRVC_TYPE_CD, IDNTF_YN, CLCT_PATH, CLCT_SRC)
                VALUES (?, 'INTRUSION', ?, '침입', '11110', 'SPRING', 'CLEAR',
                        'DAY', 'ANONY', 'N', '/nas/clct', 'VMS')
                """, "E24IT-EVT-1", LocalDateTime.now());

        // when
        List<MngClipMaster> found =
                clipMasterRepository.findIngestCandidatesByJobDmndYn("Y", PageRequest.of(0, 100));
        List<MngClipEvntLst> events =
                clipEvntLstRepository.findByEvntIdInOrderByEvntIdAscEvntTypeCdAsc(List.of("E24IT-EVT-1"));

        // then — 픽업 쿼리 회귀 없음 + 새 컬럼이 엔티티로 읽힌다
        MngClipMaster clip = found.stream()
                .filter(c -> "E24IT-CLIP-1".equals(c.getClipId())).findFirst().orElseThrow();
        assertThat(clip.getFilePath()).isEqualTo("./storage/raw/seed/e24it.mp4");
        assertThat(clip.getFileSz()).isEqualTo(123_456_789L);
        assertThat(clip.getJobDmndPrnmntYn()).isEqualTo("N");
        assertThat(clip.getCrtType()).isZero();

        assertThat(events).hasSize(1);
        MngClipEvntLst event = events.get(0);
        assertThat(event.getEvntNm()).isEqualTo("침입");
        assertThat(event.getLclgvCd()).isEqualTo("11110");
        assertThat(event.getSesnCd()).isEqualTo("SPRING");
        assertThat(event.getWthrCd()).isEqualTo("CLEAR");
        assertThat(event.getHrTypeCd()).isEqualTo("DAY");
        assertThat(event.getPrvcTypeCd()).isEqualTo("ANONY");
        assertThat(event.getIdntfYn()).isEqualTo("N");
        assertThat(event.getClctPath()).isEqualTo("/nas/clct");
        assertThat(event.getClctSrc()).isEqualTo("VMS");
    }
}
