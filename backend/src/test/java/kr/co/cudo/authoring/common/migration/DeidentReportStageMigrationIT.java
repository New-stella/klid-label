package kr.co.cudo.authoring.common.migration;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kr.co.cudo.authoring.label.entity.LsDeidentReport;
import kr.co.cudo.authoring.label.repository.LsDeidentReportRepository;
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
 * V171 — 비식별 누락 신고 <b>단계 컬럼</b>({@code LS_DEIDENT_REPORT.DCLR_STP_CD}) 실동작 검증
 * (Testcontainers PostgreSQL, Flyway migrate 후 부팅).
 *
 * <p><b>왜 정보 스키마를 직접 대조하나</b>: 이 프로젝트의 {@code ddl-auto=validate} 는 실제로 동작하지
 * 않는다({@code JpaBuilderConfig} 가 {@code spring.jpa.hibernate.*} 를 EMF 에 넘기지 않아 없는 컬럼을
 * 매핑해도 기동이 성공한다). 따라서 "기동 성공"을 엔티티↔DDL 정합의 증거로 삼을 수 없다.
 *
 * <p>검증 축: 표준도메인 정합(코드값 = {@code 코드V20} = VARCHAR(20)) · NULL 허용(단계 미상 = 레거시) ·
 * DEFAULT 없음(백필하지 않는다는 정책과 세트) · 값 왕복.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class DeidentReportStageMigrationIT {

    @Autowired
    private LsDeidentReportRepository reportRepository;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private kr.co.cudo.authoring.video.repository.VideoRepository videoRepository;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(controlDataSource);
    }

    /** LS_DEIDENT_REPORT.DATA_RAW_SN 은 LS_DATA_RAW 로 FK 가 걸려 있어 부모 행이 먼저 있어야 한다. */
    private Long seedRaw(String clipId) {
        kr.co.cudo.authoring.video.entity.LsDataRaw raw =
                kr.co.cudo.authoring.video.entity.LsDataRaw.createFromIngest(
                        clipId, "CCTV-V171", "EVT", "11680",
                        kr.co.cudo.authoring.video.entity.LsDataRaw.PRVC_TYPE_PRVC,
                        "/nas/raw/" + clipId + ".mp4", LocalDateTime.now(), 30);
        return videoRepository.saveAndFlush(raw).getRawSn();
    }

    @Test
    @DisplayName("V171_DCLR_STP_CD가_VARCHAR20_NULL허용_DEFAULT없음으로_생성된다")
    void V171_DCLR_STP_CD가_VARCHAR20_NULL허용_DEFAULT없음으로_생성된다() {
        Map<String, Object> meta = jdbc().queryForMap(
                "SELECT data_type, character_maximum_length, is_nullable, column_default "
                        + "FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                "ls_deident_report", "dclr_stp_cd");

        // 표준도메인 '코드V20'(사업표준도메인.csv:28) = VARCHAR(20). 임의 크기 금지(감리 지적 사유).
        assertThat(meta.get("data_type")).isEqualTo("character varying");
        assertThat(((Number) meta.get("character_maximum_length")).intValue()).isEqualTo(20);
        // NULL = 단계 미상(레거시). DEFAULT 를 두면 "미상"이 사라져 재개 이벤트를 오발행하게 된다.
        assertThat(meta.get("is_nullable")).isEqualTo("YES");
        assertThat(meta.get("column_default")).isNull();
    }

    @Test
    @DisplayName("신고_단계값이_저장_조회로_왕복되고_레거시행은_null로_남는다")
    void 신고_단계값이_저장_조회로_왕복되고_레거시행은_null로_남는다() {
        // given — 단계를 명시한 신고 2건 + 단계 미상(레거시 형상) 1건
        Long rawSn = seedRaw("CLIP-V171-ROUNDTRIP");
        LsDeidentReport marking = reportRepository.saveAndFlush(
                LsDeidentReport.createReport(rawSn, 100L, "마킹 중 발견", LsDeidentReport.STAGE_MARKING));
        LsDeidentReport labeling = reportRepository.saveAndFlush(
                LsDeidentReport.createReport(rawSn, 100L, "라벨링 중 발견", LsDeidentReport.STAGE_LABELING));
        LsDeidentReport legacy = reportRepository.saveAndFlush(
                LsDeidentReport.createReport(rawSn, 100L, "단계 미상"));
        em.clear();

        // then — CHAR 패딩/트림 없이 값 왕복, 레거시는 null 유지(백필하지 않는다)
        assertThat(reportRepository.findById(marking.getRprtSn()).orElseThrow().getDclrStpCd())
                .isEqualTo("MARKING");
        assertThat(reportRepository.findById(labeling.getRprtSn()).orElseThrow().getDclrStpCd())
                .isEqualTo("LABELING");
        assertThat(reportRepository.findById(legacy.getRprtSn()).orElseThrow().getDclrStpCd())
                .isNull();
    }

    @Test
    @DisplayName("resolve_원자클레임은_OPEN일때만_1행을_반환한다")
    void resolve_원자클레임은_OPEN일때만_1행을_반환한다() {
        Long rawSn = seedRaw("CLIP-V171-CLAIM");
        LsDeidentReport rep = reportRepository.saveAndFlush(
                LsDeidentReport.createReport(rawSn, 100L, "사유", LsDeidentReport.STAGE_MARKING));
        em.clear();

        // 첫 클레임 성공(1행) → 두 번째는 이미 RESOLVED 라 0행(동시 resolve 상호배제).
        int first = reportRepository.claimResolve(rep.getRprtSn(),
                LsDeidentReport.REPORT_OPEN, LsDeidentReport.REPORT_RESOLVED, LocalDateTime.now());
        int second = reportRepository.claimResolve(rep.getRprtSn(),
                LsDeidentReport.REPORT_OPEN, LsDeidentReport.REPORT_RESOLVED, LocalDateTime.now());
        em.clear();

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        LsDeidentReport reloaded = reportRepository.findById(rep.getRprtSn()).orElseThrow();
        assertThat(reloaded.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_RESOLVED);
        assertThat(reloaded.getResolvedDt()).isNotNull();
    }
}
