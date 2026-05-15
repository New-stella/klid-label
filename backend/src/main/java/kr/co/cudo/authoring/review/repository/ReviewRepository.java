package kr.co.cudo.authoring.review.repository;

import kr.co.cudo.authoring.assignment.entity.LsPjtDataStts;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 검수 워크플로우 전용 LS_PJT_DATA_STTS 조회.
 * (CRUD 자체는 LsPjtDataSttsRepository 사용; 본 리포는 페이징/검색 메서드 제공)
 *
 * <p>V34 이후 LS_PJT_DATA_STTS 의 PK 는 단일 RAW_DATA_ID — Pk(pjtId, rawDataId) 복합키 제거.
 */
@ControlRepo
public interface ReviewRepository extends JpaRepository<LsPjtDataStts, Long> {

    /**
     * VIDEO_ID(=RAW_DATA_ID) 단위 검수 상태 조회.
     * PJT_ID 제거 후 RAW_DATA_ID 는 PK 이므로 단건만 존재한다.
     */
    Optional<LsPjtDataStts> findByRawDataId(Long rawDataId);

    /**
     * 검수 워크플로우 상태별 페이징 조회 (REVIEWER 의 검수 목록 화면용).
     * status 가 null/빈 문자열이면 전체, 아니면 해당 상태만 (PENDING/IN_REVIEW/APPROVED/REJECTED).
     */
    @Query("""
            SELECT s FROM LsPjtDataStts s
             WHERE (:status IS NULL OR :status = '' OR s.dataSttsCd = :status)
             ORDER BY s.updDt DESC
            """)
    Page<LsPjtDataStts> searchByStatus(@Param("status") String status, Pageable pageable);
}
