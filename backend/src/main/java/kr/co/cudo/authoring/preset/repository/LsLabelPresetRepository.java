package kr.co.cudo.authoring.preset.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@ControlRepo
public interface LsLabelPresetRepository extends JpaRepository<LsLabelPreset, Long> {

    List<LsLabelPreset> findAllByOrderByPresetIdDesc();

    boolean existsByName(String name);

    boolean existsByNameAndPresetIdNot(String name, Long presetId);
}
