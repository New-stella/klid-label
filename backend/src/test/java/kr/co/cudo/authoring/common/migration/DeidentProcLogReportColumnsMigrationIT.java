package kr.co.cudo.authoring.common.migration;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.dto.KpstDeidentReportSummary;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V184 — 비식별 처리 결과 리포트 컬럼 6종 실동작 검증 (Testcontainers PostgreSQL, Flyway migrate 후 부팅).
 * [req: R14]
 *
 * <p><b>왜 정보 스키마를 직접 대조하나</b>: 이 프로젝트의 {@code ddl-auto=validate} 는 실제로 동작하지
 * 않는다({@code JpaBuilderConfig} 가 {@code spring.jpa.hibernate.*} 를 EMF 에 넘기지 않아 없는 컬럼을
 * 매핑해도 기동이 성공한다). 따라서 "기동 성공"을 엔티티↔DDL 정합의 증거로 삼을 수 없다.
 *
 * <p>검증 축: 표준도메인 정합(수N10 = NUMERIC(10) · 시각 = timestamp) · 경로 컬럼 폭(1000, 기존 두
 * 경로 컬럼과 동일) · NULL 허용 + DEFAULT 없음(백필하지 않는다는 정책과 세트) · 값 왕복.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class DeidentProcLogReportColumnsMigrationIT {

    @Autowired
    private LsDeidentProcLogRepository procLogRepository;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private kr.co.cudo.authoring.video.repository.VideoRepository videoRepository;

    /** LS_DEIDENT_PROC_LOG.DATA_RAW_SN 은 LS_DATA_RAW 로 FK 가 걸려 있어 부모 행이 먼저 있어야 한다. */
    private Long seedRaw(String clipId) {
        kr.co.cudo.authoring.video.entity.LsDataRaw raw =
                kr.co.cudo.authoring.video.entity.LsDataRaw.createFromIngest(
                        clipId, "CCTV-V184", "EVT", "11680",
                        kr.co.cudo.authoring.video.entity.LsDataRaw.PRVC_TYPE_PRVC,
                        "/nas/raw/" + clipId + ".mp4", LocalDateTime.now(), 30);
        return videoRepository.saveAndFlush(raw).getRawSn();
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(controlDataSource);
    }

    private Map<String, Object> columnMeta(String column) {
        return jdbc().queryForMap(
                "SELECT data_type, numeric_precision, character_maximum_length, is_nullable, column_default "
                        + "FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                "ls_deident_proc_log", column);
    }

    @Test
    @DisplayName("V184_검출집계_3컬럼이_NUMERIC10_NULL허용_DEFAULT없음으로_생성된다")
    void countColumnsMatchStandardDomain() {
        for (String column : new String[] {"face_dtct_cnt", "noplt_dtct_cnt", "frme_cnt"}) {
            Map<String, Object> meta = columnMeta(column);
            // 표준도메인 '수N10'(공통표준용어 프레임수 = FRME_CNT) = NUMERIC(10). 임의 크기 금지(감리 지적 사유).
            assertThat(meta.get("data_type")).as(column).isEqualTo("numeric");
            assertThat(((Number) meta.get("numeric_precision")).intValue()).as(column).isEqualTo(10);
            // NULL = "리포트를 못 받았다". DEFAULT 0 을 두면 <0건 검출>과 구분되지 않는다.
            assertThat(meta.get("is_nullable")).as(column).isEqualTo("YES");
            assertThat(meta.get("column_default")).as(column).isNull();
        }
    }

    @Test
    @DisplayName("V184_처리시각_2컬럼이_timestamp_NULL허용으로_생성된다")
    void timeColumnsMatchStandardDomain() {
        for (String column : new String[] {"prcs_bgng_dt", "prcs_end_dt"}) {
            Map<String, Object> meta = columnMeta(column);
            // 표준도메인 '연월일시분초D' — 같은 테이블의 기존 시각 컬럼(REQ_DT/RSPNS_DT)과 동일 타입.
            assertThat(meta.get("data_type")).as(column).isEqualTo("timestamp without time zone");
            assertThat(meta.get("is_nullable")).as(column).isEqualTo("YES");
            assertThat(meta.get("column_default")).as(column).isNull();
        }
    }

    @Test
    @DisplayName("V184_리포트파일경로명은_기존_경로컬럼과_같은_VARCHAR1000이다")
    void reportPathColumnWidthMatchesSiblingPathColumns() {
        int reportWidth = ((Number) columnMeta("rpt_file_path_nm").get("character_maximum_length")).intValue();
        int originWidth = ((Number) columnMeta("orgnl_file_path_nm").get("character_maximum_length")).intValue();
        int deidWidth = ((Number) columnMeta("de_idntf_file_path_nm").get("character_maximum_length")).intValue();

        // 값은 벤더가 회신한 <절대경로>다. 300(표준도메인 명V300)으로 잡으면 긴 NAS 경로에서
        // INSERT 가 DB 오류(500)로 샌다 — 같은 테이블의 두 경로 컬럼이 이미 1000 인 선례를 승계한다.
        assertThat(reportWidth).isEqualTo(1000);
        assertThat(reportWidth).isEqualTo(originWidth).isEqualTo(deidWidth);
    }

    @Test
    @DisplayName("리포트_집계값이_저장_조회로_왕복되고_미조회_회차는_null로_남는다")
    void reportValuesRoundTrip() {
        // given — 리포트를 받은 회차 1건 + 못 받은 회차 1건(백필하지 않는다).
        Long rawSn = seedRaw("CLIP-V184-ROUNDTRIP");
        LsDeidentProcLog withReport =
                LsDeidentProcLog.request(rawSn, "req-a", "/nas/raw/a.mp4", "batch");
        withReport.recordReport(new KpstDeidentReportSummary(
                12L, 3L, 5400L,
                LocalDateTime.of(2026, 8, 11, 10, 0, 0),
                LocalDateTime.of(2026, 8, 11, 10, 5, 30),
                "/nas/raw/a.mp4"));
        LsDeidentProcLog withoutReport =
                LsDeidentProcLog.request(rawSn, "req-b", "/nas/raw/b.mp4", "batch");

        Long withId = procLogRepository.saveAndFlush(withReport).getProcLogSn();
        Long withoutId = procLogRepository.saveAndFlush(withoutReport).getProcLogSn();
        em.clear();

        // then
        LsDeidentProcLog loaded = procLogRepository.findById(withId).orElseThrow();
        assertThat(loaded.getFaceDtctCnt()).isEqualTo(12L);
        assertThat(loaded.getNoPltDtctCnt()).isEqualTo(3L);
        assertThat(loaded.getFrmeCnt()).isEqualTo(5400L);
        assertThat(loaded.getPrcsBgngDt()).isEqualTo(LocalDateTime.of(2026, 8, 11, 10, 0, 0));
        assertThat(loaded.getPrcsEndDt()).isEqualTo(LocalDateTime.of(2026, 8, 11, 10, 5, 30));
        assertThat(loaded.getRptFilePathNm()).isEqualTo("/nas/raw/a.mp4");

        LsDeidentProcLog legacy = procLogRepository.findById(withoutId).orElseThrow();
        assertThat(legacy.getFaceDtctCnt()).isNull();
        assertThat(legacy.getFrmeCnt()).isNull();
        assertThat(legacy.getRptFilePathNm()).isNull();
    }

    @Test
    @DisplayName("컬럼_폭_상한인_1000자_경로도_잘리지_않고_저장된다")
    void maxWidthPathIsStored() {
        String path = "/nas/" + "a".repeat(KpstDeidentReportSummary.MAX_REPORT_FILE_PATH_LEN - 5);
        assertThat(path).hasSize(KpstDeidentReportSummary.MAX_REPORT_FILE_PATH_LEN);

        LsDeidentProcLog log =
                LsDeidentProcLog.request(seedRaw("CLIP-V184-MAXPATH"), "req-c", "/nas/raw/c.mp4", "batch");
        log.recordReport(new KpstDeidentReportSummary(0L, 0L, 1L, null, null, path));
        Long id = procLogRepository.saveAndFlush(log).getProcLogSn();
        em.clear();

        assertThat(procLogRepository.findById(id).orElseThrow().getRptFilePathNm()).isEqualTo(path);
    }
}
