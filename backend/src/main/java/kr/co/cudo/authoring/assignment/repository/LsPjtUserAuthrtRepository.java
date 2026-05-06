package kr.co.cudo.authoring.assignment.repository;

import kr.co.cudo.authoring.assignment.entity.LsPjtUserAuthrt;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

@ControlRepo
public interface LsPjtUserAuthrtRepository extends JpaRepository<LsPjtUserAuthrt, Long> {

    Page<LsPjtUserAuthrt> findByUserNoAndTaskTypeCd(Long userNo, String taskTypeCd, Pageable pageable);

    Page<LsPjtUserAuthrt> findByTaskTypeCd(String taskTypeCd, Pageable pageable);

    boolean existsByUserNoAndTaskTypeCdAndRawDataId(Long userNo, String taskTypeCd, Long rawDataId);

    @Query("SELECT COUNT(a) FROM LsPjtUserAuthrt a WHERE a.userNo = :userNo AND a.taskTypeCd = 'LABELER'")
    long countActiveTasksByUserNo(Long userNo);
}
