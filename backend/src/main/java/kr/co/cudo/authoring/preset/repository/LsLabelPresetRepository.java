package kr.co.cudo.authoring.preset.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsLabelPresetRepository extends JpaRepository<LsLabelPreset, Long> {

    /**
     * 프리셋 + 코드 목록을 fetch join 으로 한 번에 조회한다(코드 컬렉션 N+1 방지).
     * <p>코드의 라벨 마스터 join 은 서비스에서 labelId 일괄 조회(배치)로 별도 처리한다.
     */
    @Query("SELECT DISTINCT p FROM LsLabelPreset p LEFT JOIN FETCH p.codes ORDER BY p.presetId DESC")
    List<LsLabelPreset> findAllWithCodes();

    boolean existsByPresetNm(String presetNm);

    boolean existsByPresetNmAndPresetIdNot(String presetNm, Long presetId);

    /**
     * 이벤트 타입에 매핑된 프리셋 조회. UNIQUE 제약상 0 또는 1건이 보장된다.
     *
     * @param eventTypeCd 이벤트 타입 코드 (예: EVT_FALL)
     */
    Optional<LsLabelPreset> findByEventTypeCd(String eventTypeCd);
}
