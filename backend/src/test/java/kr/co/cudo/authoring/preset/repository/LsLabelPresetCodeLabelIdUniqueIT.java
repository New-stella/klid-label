package kr.co.cudo.authoring.preset.repository;

import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset.LabelCodeSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V119 — 프리셋 labelId 중복 방지 부분 유니크 인덱스(UK_LS_LABEL_PRESET_CODE_LBLID)의 DB 레벨 실증.
 *
 * <p>이슈 1(HIGH, 동시성): 동일 프리셋에 대한 동시 PUT 두 건이 같은 labelId 를 중복 저장하는 것을
 * 인메모리 dedup 만으로는 막지 못한다. 본 테스트는 dedup 을 우회해(raw JDBC INSERT) 같은
 * (PRESET_ID, LBL_ID) 두 번째 행이 DB 에서 원자적으로 거부됨을 증명한다 — 실 경합의 결정적 대체 검증.
 *
 * <p>시드(라벨·프리셋+1코드)는 별도 트랜잭션으로 커밋해 raw JDBC 커넥션이 FK 대상을 볼 수 있게 한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class LsLabelPresetCodeLabelIdUniqueIT {

    @Autowired
    private LsLabelRepository labelRepository;
    @Autowired
    private LsLabelPresetRepository presetRepository;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;

    private Long seededPresetId;
    private Long seededLabelId;

    LsLabelPresetCodeLabelIdUniqueIT(
            @Qualifier("controlDataSource") DataSource dataSource,
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    @AfterEach
    void cleanup() {
        if (seededPresetId != null) {
            jdbc.update("DELETE FROM LS_LABEL_PRESET_CODE WHERE PRESET_ID = ?", seededPresetId);
            jdbc.update("DELETE FROM LS_LABEL_PRESET WHERE PRESET_ID = ?", seededPresetId);
        }
        if (seededLabelId != null) {
            jdbc.update("DELETE FROM LS_LABEL WHERE LBL_ID = ?", seededLabelId);
        }
    }

    @Test
    @DisplayName("동일_프리셋에_같은_labelId_코드_중복INSERT는_부분유니크인덱스로_거부된다")
    void duplicateLabelIdRejectedByPartialUniqueIndex() {
        // given: 라벨 1건 + labelId 연결 코드 1건을 가진 프리셋을 커밋해 둔다.
        txTemplate.executeWithoutResult(status -> {
            LsLabel label = labelRepository.saveAndFlush(
                    LsLabel.create("DUP_" + System.nanoTime(), "#FF0000", "BBOX", 0, "tester"));
            seededLabelId = label.getLabelId();
            LsLabelPreset preset = presetRepository.saveAndFlush(LsLabelPreset.createWithOptions(
                    "dupIndex " + System.nanoTime(), "", List.of(new LabelCodeSpec(seededLabelId, null)), null));
            seededPresetId = preset.getPresetId();
        });

        // when/then: 같은 (PRESET_ID, LBL_ID) 두 번째 행을 raw JDBC 로 넣으면 DB 가 거부한다.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO LS_LABEL_PRESET_CODE (PRESET_ID, LBL_ID, SORT_SEQ) VALUES (?, ?, ?)",
                seededPresetId, seededLabelId, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
