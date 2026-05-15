package kr.co.cudo.authoring.assignment.repository;

import kr.co.cudo.authoring.assignment.entity.LsPjtTaskEventLog;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@ControlRepo
public interface LsPjtTaskEventLogRepository extends JpaRepository<LsPjtTaskEventLog, Long> {

    /**
     * 영상(RAW_DATA_ID) 단위 이벤트 로그 시간순 조회.
     * SCR-TASK-003 작업 이력 화면의 통합 타임라인 응답에 사용.
     */
    List<LsPjtTaskEventLog> findByRawDataIdOrderByOccurredAtAsc(Long rawDataId);
}
