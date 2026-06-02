package kr.co.cudo.authoring.preset.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsLabelPresetRepository extends JpaRepository<LsLabelPreset, Long> {

    List<LsLabelPreset> findAllByOrderByPresetIdDesc();

    boolean existsByPresetNm(String presetNm);

    boolean existsByPresetNmAndPresetIdNot(String presetNm, Long presetId);

    /**
     * 이벤트 타입에 매핑된 프리셋 조회. UNIQUE 제약상 0 또는 1건이 보장된다.
     *
     * @param eventTypeCd 이벤트 타입 코드 (예: EVT_FALL)
     */
    Optional<LsLabelPreset> findByEventTypeCd(String eventTypeCd);
}
