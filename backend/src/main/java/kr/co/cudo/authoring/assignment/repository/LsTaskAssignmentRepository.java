package kr.co.cudo.authoring.assignment.repository;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

@ControlRepo
public interface LsTaskAssignmentRepository extends JpaRepository<LsTaskAssignment, Long> {

    // 배정 목록 페이징 조회(구 findByUserNoAndTaskTypeCd / findByTaskTypeCd)는
    // AssignmentQueryRepository.search 로 일원화됐다. 필터·정렬·인가가 한 조립 지점을 통과해야
    // 목록과 count 가 갈라지지 않으므로, 조건 없이 전체를 페이징하던 파생 메서드는 두지 않는다.

    boolean existsByUserNoAndTaskTypeCdAndRawDataId(Long userNo, String taskTypeCd, Long rawDataId);

    @Query("SELECT COUNT(a) FROM LsTaskAssignment a WHERE a.userNo = :userNo AND a.taskTypeCd = 'LABELER'")
    long countActiveTasksByUserNo(Long userNo);

    /**
     * 페이지 단위 batch lookup — LABELER 배정의 (rawDataId → userNo) 매핑.
     * 한 영상에 다수 배정이 있으면 가장 최근(REG_DT DESC) 1건만 사용. N+1 방지.
     */
    List<LsTaskAssignment> findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(String taskTypeCd,
                                                                          Collection<Long> rawDataIds);
}
