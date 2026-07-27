package kr.co.cudo.authoring.assignment.repository;

import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsTaskEventLogRepository extends JpaRepository<LsTaskEventLog, Long> {

    /**
     * 영상(RAW_DATA_ID) 단위 이벤트 로그 시간순 조회.
     * SCR-TASK-003 작업 이력 화면의 통합 타임라인 응답에 사용.
     */
    List<LsTaskEventLog> findByRawDataIdOrderByOcrnDtAsc(Long rawDataId);

    /**
     * 영상의 최신 특정 이벤트 1건 (예: 마지막 APPROVE — 검수자 실측 조회용, D-ISSUE-41).
     * 동일 시각 tie 는 EVNT_ID DESC 로 결정한다.
     */
    Optional<LsTaskEventLog> findFirstByRawDataIdAndEventTypeCdOrderByOcrnDtDescEventSeqDesc(
            Long rawDataId, String eventTypeCd);
}
