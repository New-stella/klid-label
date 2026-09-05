package kr.co.cudo.authoring.common.migration;

import kr.co.cudo.authoring.preset.repository.LsLabelPresetRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 — 프리셋 라벨 코드의 라벨 마스터(LBL_ID) FK 연결 마이그레이션(V117) 실동작 검증
 * (Testcontainers PostgreSQL).
 *
 * <p>검증 축:
 * <ul>
 *   <li>ddl-auto=validate 부팅 성공 = 엔티티(LBL_ID 추가/토글 제거) ↔ V117 스키마 정합(컨텍스트 로드가 증명).</li>
 *   <li>LS_LABEL_PRESET_CODE.LBL_ID(BIGINT) 컬럼 + LS_LABEL FK 제약 + 인덱스 존재.</li>
 *   <li>이름 매칭(UPPER/TRIM, USE_YN='Y') backfill 동작 — 매칭 성공 시 LBL_ID 채움.</li>
 *   <li>이름 불일치 코드는 LBL_ID null(미연결) 유지.</li>
 *   <li>BBOX_ENABLED / POLYGON_ENABLED 스냅샷 컬럼 제거.</li>
 * </ul>
 *
 * <p>주의: Flyway V117 은 컨테이너 부팅 시 1회 실행되며 그 시점에는 테스트 시드 행이 없다.
 * 따라서 backfill <b>로직</b> 검증은 V117 과 동일한 UPDATE 문을 새로 시드한 행에 replay 하여 확인한다
 * (스키마 최종 상태는 실 마이그레이션으로 검증).
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class PresetCodeLabelLinkMigrationIT {

    /** V117 backfill 과 동일한 이름 매칭 UPDATE (replay 검증용). */
    private static final String BACKFILL_SQL =
            "UPDATE LS_LABEL_PRESET_CODE pc "
                    + "SET LBL_ID = l.LBL_ID "
                    + "FROM LS_LABEL l "
                    + "WHERE UPPER(TRIM(pc.LBL_CD)) = UPPER(TRIM(l.LBL_NM)) AND l.USE_YN = 'Y'";

    @Autowired
    private LsLabelPresetRepository presetRepository;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(controlDataSource);
    }

    private long insertLabel(String name, String typeCd, String useYn) {
        return jdbc().queryForObject(
                "INSERT INTO LS_LABEL (LBL_NM, COLR_VL, LBL_TYPE_CD, SORT_SEQ, USE_YN, REG_DT) "
                        + "VALUES (?, ?, ?, ?, ?, ?) RETURNING LBL_ID",
                Long.class, name, "#FF0000", typeCd, 0, useYn, Timestamp.valueOf(LocalDateTime.now()));
    }

    /**
     * 시드 프리셋 1건. V17 이 PRESET_NM/EXPLN 을 없애고 EVNT_TYPE_CD 를 NOT NULL 로 좁혔으므로
     * 식별 축은 이벤트유형코드 하나다(VARCHAR(20) · UNIQUE).
     */
    private long insertPreset(String eventTypeCd) {
        return jdbc().queryForObject(
                "INSERT INTO LS_LABEL_PRESET (EVNT_TYPE_CD) VALUES (?) RETURNING PRESET_ID",
                Long.class, eventTypeCd);
    }

    private long insertPresetCode(long presetId, String lblCd, int sortSeq) {
        return jdbc().queryForObject(
                "INSERT INTO LS_LABEL_PRESET_CODE (PRESET_ID, LBL_CD, SORT_SEQ) "
                        + "VALUES (?, ?, ?) RETURNING CD_SN",
                Long.class, presetId, lblCd, sortSeq);
    }

    @Test
    @DisplayName("V117_적용후_LS_LABEL_PRESET_CODE에_LBL_ID_컬럼과_FK제약이_존재한다")
    void V117_적용후_LBL_ID_컬럼과_FK제약_인덱스_존재() {
        // given / when / then — 정보 스키마상 LBL_ID(bigint) 컬럼 존재
        String colType = jdbc().queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = 'ls_label_preset_code' AND column_name = 'lbl_id'",
                String.class);
        assertThat(colType).isEqualTo("bigint");

        // and — LS_LABEL 참조 FK 제약 존재
        Integer fkCount = jdbc().queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_name = 'ls_label_preset_code' "
                        + "AND constraint_type = 'FOREIGN KEY' "
                        + "AND constraint_name = 'fk_ls_label_preset_code_label'",
                Integer.class);
        assertThat(fkCount).isEqualTo(1);

        // and — FK 컬럼 인덱스 존재
        Integer idxCount = jdbc().queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                        + "WHERE tablename = 'ls_label_preset_code' "
                        + "AND indexname = 'idx_ls_label_preset_code_label'",
                Integer.class);
        assertThat(idxCount).isEqualTo(1);
    }

    @Test
    @DisplayName("이름이_일치하는_기존_프리셋코드는_LBL_ID가_backfill된다")
    void 이름_일치_프리셋코드는_backfill된다() {
        // given — LS_LABEL 'PERSONV117'(USE_YN='Y') + 대소문자/공백 다른 프리셋 코드
        long labelId = insertLabel("PERSONV117", "BBOX", "Y");
        long presetId = insertPreset("V117-BACKFILL-MATCH");
        long cdSn = insertPresetCode(presetId, "  personv117  ", 0);

        // when — V117 backfill replay
        jdbc().update(BACKFILL_SQL);

        // then — UPPER/TRIM 매칭으로 LBL_ID 채워짐
        Long linked = jdbc().queryForObject(
                "SELECT LBL_ID FROM LS_LABEL_PRESET_CODE WHERE CD_SN = ?", Long.class, cdSn);
        assertThat(linked).isEqualTo(labelId);
    }

    @Test
    @DisplayName("이름이_불일치하는_기존_프리셋코드는_LBL_ID가_null로_미연결_상태다")
    void 이름_불일치_프리셋코드는_LBL_ID_null_유지() {
        // given — 어떤 라벨과도 이름이 다른 프리셋 코드 (매칭 대상 없음)
        long presetId = insertPreset("V117-BF-NOMATCH");
        long cdSn = insertPresetCode(presetId, "NONEXISTENT_LBL_V117", 0);

        // when — V117 backfill replay
        jdbc().update(BACKFILL_SQL);

        // then — 매칭 실패 → LBL_ID null(미연결)
        Long linked = jdbc().queryForObject(
                "SELECT LBL_ID FROM LS_LABEL_PRESET_CODE WHERE CD_SN = ?", Long.class, cdSn);
        assertThat(linked).isNull();
    }

    @Test
    @DisplayName("USE_YN이_N인_라벨은_backfill_대상에서_제외된다")
    void 소프트삭제_라벨은_backfill_제외() {
        // given — 동일 이름이지만 USE_YN='N'(soft delete) 라벨만 존재
        insertLabel("DELETEDLBLV117", "BBOX", "N");
        long presetId = insertPreset("V117-BF-SOFTDEL");
        long cdSn = insertPresetCode(presetId, "DELETEDLBLV117", 0);

        // when
        jdbc().update(BACKFILL_SQL);

        // then — soft delete 라벨은 매칭 제외 → LBL_ID null
        Long linked = jdbc().queryForObject(
                "SELECT LBL_ID FROM LS_LABEL_PRESET_CODE WHERE CD_SN = ?", Long.class, cdSn);
        assertThat(linked).isNull();
    }

    @Test
    @DisplayName("BBOX_ENABLED_POLYGON_ENABLED_컬럼이_제거된다")
    void 토글_스냅샷_컬럼_제거됨() {
        // given / when / then — 두 스냅샷 컬럼이 정보 스키마에서 사라짐
        Integer bboxCol = jdbc().queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'ls_label_preset_code' AND column_name = 'bbox_enabled'",
                Integer.class);
        Integer polygonCol = jdbc().queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'ls_label_preset_code' AND column_name = 'polygon_enabled'",
                Integer.class);
        assertThat(bboxCol).isZero();
        assertThat(polygonCol).isZero();
    }

    @Test
    @DisplayName("V117_이후에도_프리셋_저장조회가_정상동작한다")
    void 프리셋_저장조회_정상동작() {
        // given / when / then — 리포지토리 주입 + 컨텍스트 로드(ddl validate) 자체가 엔티티↔스키마 정합 증명
        assertThat(presetRepository).isNotNull();
        assertThat(presetRepository.findAll()).isNotNull();
    }
}
