package kr.co.cudo.authoring.assignment.repository;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

@ControlRepo
public interface LsRawDataStatusRepository extends JpaRepository<LsRawDataStatus, Long> {

    /**
     * 영상(raw data) ID 리스트로 검수 상태 row 일괄 조회.
     * 증강 요청 시 검수 완료(APPROVED) 영상만 통과시키는 검증에 사용.
     * row 가 없는 영상은 미검수로 간주 (호출 측에서 차집합 계산).
     */
    List<LsRawDataStatus> findByRawDataIdIn(Collection<Long> rawDataIds);
}
