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

    /**
     * 그 이벤트유형에 이미 프리셋이 있는지 — 생성 입구의 409 판정.
     *
     * <p>프리셋에 이름이 없어져(V17) 사용자가 중복을 눈으로 알아채기 어려우므로 입구에서 막는다.
     * 다만 사전 조회만으로는 동시 요청 경합이 남으므로 저장 시점의 UNIQUE 위반도 409 로 변환한다.
     */
    boolean existsByEventTypeCd(String eventTypeCd);

    /** 수정 입구의 409 판정 — 자기 자신은 제외하고 본다. */
    boolean existsByEventTypeCdAndPresetIdNot(String eventTypeCd, Long presetId);

    /**
     * 이벤트유형에 매핑된 프리셋 조회. UNIQUE 제약상 0 또는 1건이 보장된다.
     *
     * @param eventTypeCd 이벤트유형 코드 (예: EV01000101)
     */
    Optional<LsLabelPreset> findByEventTypeCd(String eventTypeCd);
}
