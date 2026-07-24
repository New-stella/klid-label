package kr.co.cudo.authoring.common.migration;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
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
 * Phase 1 — 촬영환경(영상 단위)·개인정보(프레임 단위) 수동입력 메타 컬럼 신설(V130) 실동작 검증
 * (Testcontainers PostgreSQL, Flyway migrate + {@code ddl-auto=validate} 부팅).
 *
 * <p>검증 축:
 * <ul>
 *   <li>ddl-auto=validate 부팅 성공 = 신규 6컬럼 ↔ 엔티티 매핑 정합(컨텍스트 로드 자체가 증명).</li>
 *   <li>정보 스키마상 신규 컬럼 물리명/타입/크기가 표준 확정값과 일치 —
 *       WTHR_NM=varchar(20)(표준도메인 명V20), DAY_NGT_CD/SESN_CD=varchar(20), *_INCL_YN=char(1).</li>
 *   <li>6컬럼 전부 NULL 허용(미입력/파생폴백 구분) — save 후 기본 null.</li>
 *   <li>값 적재 후 재조회 시 유지(@Column 읽기 매핑 정합).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class ManualEnvPrivacyMetaMigrationIT {

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private LsDataSrcRepository lsDataSrcRepository;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @PersistenceContext
    private EntityManager em;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(controlDataSource);
    }

    private record ColSpec(String table, String column, String dataType, int length, boolean nullable) {}

    /** V130 신규 컬럼 표준 확정값(정보 스키마 소문자). YN 도메인은 프로젝트 표준(V85) CHAR(1). */
    private static final List<ColSpec> NEW_COLUMNS = List.of(
            new ColSpec("ls_data_raw", "wthr_nm", "character varying", 20, true),
            new ColSpec("ls_data_raw", "day_ngt_cd", "character varying", 20, true),
            new ColSpec("ls_data_raw", "sesn_cd", "character varying", 20, true),
            new ColSpec("ls_data_src", "anony_incl_yn", "character", 1, true),
            new ColSpec("ls_data_src", "psdo_incl_yn", "character", 1, true),
            new ColSpec("ls_data_src", "prvc_incl_yn", "character", 1, true));

    @Test
    @DisplayName("V130_적용후_ddl_auto_validate_기동_성공")
    void V130_적용후_ddl_auto_validate_기동_성공() {
        // given / when / then — ddl-auto=validate 하에 컨텍스트 로드됨 = 신규 6컬럼 ↔ 엔티티 정합
        assertThat(videoRepository).isNotNull();
        assertThat(lsDataSrcRepository).isNotNull();

        // 정보 스키마상 물리명/타입/크기/NULL 허용이 표준 확정값과 일치
        for (ColSpec spec : NEW_COLUMNS) {
            Map<String, Object> meta = jdbc().queryForMap(
                    "SELECT data_type, character_maximum_length, is_nullable "
                            + "FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                    spec.table(), spec.column());
            assertThat(meta.get("data_type"))
                    .as("%s.%s 타입", spec.table(), spec.column())
                    .isEqualTo(spec.dataType());
            assertThat(((Number) meta.get("character_maximum_length")).intValue())
                    .as("%s.%s 길이", spec.table(), spec.column())
                    .isEqualTo(spec.length());
            assertThat(meta.get("is_nullable"))
                    .as("%s.%s NULL 허용", spec.table(), spec.column())
                    .isEqualTo("YES");
        }
    }

    @Test
    @DisplayName("LsDataRaw_촬영환경_3컬럼_저장_조회_null_기본")
    void LsDataRaw_촬영환경_3컬럼_저장_조회_null_기본() {
        // given — 영상 적재(신규 촬영환경 컬럼은 미입력 상태)
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-ENV-META", "CCTV-ENV", "EVT_FALL", "LGV01", "ANONY",
                "/nas/raw/env-meta.mp4", LocalDateTime.now(), 30);
        videoRepository.saveAndFlush(raw);
        Long rawSn = raw.getRawSn();

        // then — 미입력 기본은 null (파생 폴백 구분)
        assertThat(raw.getWthrNm()).isNull();
        assertThat(raw.getDayNgtCd()).isNull();
        assertThat(raw.getSesnCd()).isNull();

        // when — 값 적재 후 재조회
        jdbc().update("UPDATE ls_data_raw SET wthr_nm = ?, day_ngt_cd = ?, sesn_cd = ? WHERE raw_sn = ?",
                "맑음", "DAY", "SUMMER", rawSn);
        em.clear();

        // then — @Column 읽기 매핑 정합 (값 유지)
        LsDataRaw reloaded = videoRepository.findById(rawSn).orElseThrow();
        assertThat(reloaded.getWthrNm()).isEqualTo("맑음");
        assertThat(reloaded.getDayNgtCd()).isEqualTo("DAY");
        assertThat(reloaded.getSesnCd()).isEqualTo("SUMMER");
    }

    @Test
    @DisplayName("LsDataSrc_개인정보_3컬럼_저장_조회_null_기본")
    void LsDataSrc_개인정보_3컬럼_저장_조회_null_기본() {
        // given — 프레임 적재(신규 개인정보 컬럼은 미입력 상태)
        LsDataSrc src = LsDataSrc.create(999_001L, 0L, "/nas/frames/raw/999001/0.jpg", LocalDateTime.now());
        lsDataSrcRepository.saveAndFlush(src);
        Long srcSn = src.getSrcSn();

        // then — 미입력 기본은 null
        assertThat(src.getAnonyInclYn()).isNull();
        assertThat(src.getPsdoInclYn()).isNull();
        assertThat(src.getPrvcInclYn()).isNull();

        // when — 값 적재 후 재조회 (CHAR(1) 왕복)
        jdbc().update("UPDATE ls_data_src SET anony_incl_yn = ?, psdo_incl_yn = ?, prvc_incl_yn = ? WHERE src_sn = ?",
                "Y", "N", "Y", srcSn);
        em.clear();

        // then — @Column 읽기 매핑 정합 (CHAR(1) 패딩/트림 없이 값 유지)
        LsDataSrc reloaded = lsDataSrcRepository.findById(srcSn).orElseThrow();
        assertThat(reloaded.getAnonyInclYn()).isEqualTo("Y");
        assertThat(reloaded.getPsdoInclYn()).isEqualTo("N");
        assertThat(reloaded.getPrvcInclYn()).isEqualTo("Y");
    }
}
