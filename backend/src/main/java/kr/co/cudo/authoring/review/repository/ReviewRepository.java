package kr.co.cudo.authoring.review.repository;

import kr.co.cudo.authoring.assignment.entity.LsPjtDataStts;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 검수 워크플로우 전용 LS_PJT_DATA_STTS 조회.
 * (CRUD 자체는 LsPjtDataSttsRepository 사용; 본 리포는 RAW_DATA_ID 기준 검색을 위한 별도 메서드 제공)
 */
@ControlRepo
public interface ReviewRepository extends JpaRepository<LsPjtDataStts, LsPjtDataStts.Pk> {

    /**
     * VIDEO_ID(=RAW_DATA_ID) 단위 검수 상태 조회. 동일 RAW_DATA_ID 가 여러 PJT 에 매핑될 수 있으므로 List.
     * Phase 7 워크플로우에서는 단건만 사용 — 호출자에서 단건 보장 검증.
     */
    List<LsPjtDataStts> findByIdRawDataId(Long rawDataId);
}
