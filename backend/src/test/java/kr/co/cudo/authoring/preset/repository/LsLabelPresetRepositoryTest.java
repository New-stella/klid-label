package kr.co.cudo.authoring.preset.repository;

import kr.co.cudo.authoring.preset.entity.LsLabelPreset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V15 마이그레이션 — LS_LABEL_PRESET.EVNT_TYPE_CD UNIQUE 제약 + findByEventTypeCd 동작 검증.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class LsLabelPresetRepositoryTest {

    @Autowired
    private LsLabelPresetRepository repository;

    @Test
    @DisplayName("findByEventTypeCd_매핑된_프리셋_반환")
    void findMappedPreset() {
        LsLabelPreset saved = repository.saveAndFlush(LsLabelPreset.create(
                "낙상 표준", "fall", List.of("PERSON"), "EVT_FALL"));

        Optional<LsLabelPreset> found = repository.findByEventTypeCd("EVT_FALL");

        assertThat(found).isPresent();
        assertThat(found.get().getPresetId()).isEqualTo(saved.getPresetId());
        assertThat(found.get().codeValues()).containsExactly("PERSON");
    }

    @Test
    @DisplayName("findByEventTypeCd_미매핑이면_빈_Optional")
    void findUnmappedReturnsEmpty() {
        Optional<LsLabelPreset> result = repository.findByEventTypeCd("EVT_UNDEFINED_XYZ");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("동일_이벤트는_프리셋_1개만_허용_UNIQUE_위반시_예외")
    void duplicateEventThrowsConstraint() {
        repository.saveAndFlush(LsLabelPreset.create("A", "", List.of("PERSON"), "EVT_VIOLENCE"));

        assertThatThrownBy(() ->
                repository.saveAndFlush(LsLabelPreset.create("B", "", List.of("PERSON"), "EVT_VIOLENCE")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("eventTypeCd_null은_여러_프리셋_허용_미매핑_프리셋_N개")
    void nullEventAllowsMultiple() {
        repository.saveAndFlush(LsLabelPreset.create("미매핑1", "", List.of("PERSON"), null));
        repository.saveAndFlush(LsLabelPreset.create("미매핑2", "", List.of("VEHICLE"), null));

        long unmappedCount = repository.findAll().stream()
                .filter(p -> p.getEventTypeCd() == null)
                .count();

        assertThat(unmappedCount).isGreaterThanOrEqualTo(2L);
    }
}
