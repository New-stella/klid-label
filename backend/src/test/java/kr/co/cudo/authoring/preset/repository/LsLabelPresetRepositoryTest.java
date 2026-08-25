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
 * LS_LABEL_PRESET.EVNT_TYPE_CD 의 UNIQUE + NOT NULL(V17) 제약과 조회 메서드 동작 검증.
 *
 * <p>V17 이후 프리셋은 이벤트유형 1건에 대응하며 이벤트 미연결 프리셋은 존재할 수 없다 —
 * 그런 행은 어느 영상에도 매칭되지 않아 오토라벨에 아무 기여를 하지 않는 죽은 행이다.
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
        LsLabelPreset saved = repository.saveAndFlush(
                LsLabelPreset.create(List.of("PERSON"), "EVT_FALL"));

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
        repository.saveAndFlush(LsLabelPreset.create(List.of("PERSON"), "EVT_VIOLENCE"));

        assertThatThrownBy(() ->
                repository.saveAndFlush(LsLabelPreset.create(List.of("PERSON"), "EVT_VIOLENCE")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("이벤트유형이_비어있는_프리셋은_NOT_NULL_제약으로_저장되지_않는다")
    void nullEventIsRejectedByNotNull() {
        // V17 이 NOT NULL 로 좁혔다 — 구 동작(미매핑 프리셋 N개 허용)은 폐기됐다.
        assertThatThrownBy(() ->
                repository.saveAndFlush(LsLabelPreset.create(List.of("PERSON"), null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("existsByEventTypeCd_는_그_이벤트에_프리셋이_있는지_알려준다")
    void existsByEventTypeCdDetectsDuplicate() {
        repository.saveAndFlush(LsLabelPreset.create(List.of("PERSON"), "EVT_EXISTS_CHK"));

        assertThat(repository.existsByEventTypeCd("EVT_EXISTS_CHK")).isTrue();
        assertThat(repository.existsByEventTypeCd("EVT_NOT_THERE")).isFalse();
    }

    @Test
    @DisplayName("existsByEventTypeCdAndPresetIdNot_는_자기_자신을_중복으로_보지_않는다")
    void existsExcludingSelfIgnoresOwnRow() {
        LsLabelPreset saved = repository.saveAndFlush(
                LsLabelPreset.create(List.of("PERSON"), "EVT_SELF_CHK"));

        assertThat(repository.existsByEventTypeCdAndPresetIdNot("EVT_SELF_CHK", saved.getPresetId()))
                .as("자기 자신은 제외해야 매핑을 유지한 수정이 409 로 막히지 않는다")
                .isFalse();
        assertThat(repository.existsByEventTypeCdAndPresetIdNot("EVT_SELF_CHK", saved.getPresetId() + 1))
                .isTrue();
    }
}
