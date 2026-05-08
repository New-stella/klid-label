package kr.co.cudo.authoring.assignment.repository;

import kr.co.cudo.authoring.assignment.entity.LsPjtUserAuthrt;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

@ControlRepo
public interface LsPjtUserAuthrtRepository extends JpaRepository<LsPjtUserAuthrt, Long> {

    Page<LsPjtUserAuthrt> findByUserNoAndTaskTypeCd(Long userNo, String taskTypeCd, Pageable pageable);

    Page<LsPjtUserAuthrt> findByTaskTypeCd(String taskTypeCd, Pageable pageable);

    boolean existsByUserNoAndTaskTypeCdAndRawDataId(Long userNo, String taskTypeCd, Long rawDataId);

    @Query("SELECT COUNT(a) FROM LsPjtUserAuthrt a WHERE a.userNo = :userNo AND a.taskTypeCd = 'LABELER'")
    long countActiveTasksByUserNo(Long userNo);

    /**
     * 페이지 단위 batch lookup — LABELER 배정의 (rawDataId → userNo) 매핑.
     * 한 영상에 다수 배정이 있으면 가장 최근(REG_DT DESC) 1건만 사용. N+1 방지.
     */
    List<LsPjtUserAuthrt> findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(String taskTypeCd,
                                                                         Collection<Long> rawDataIds);
}
