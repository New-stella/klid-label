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

    /**
     * 그 영상에 특정 이벤트가 <b>한 번이라도</b> 기록됐는지 (P2b — "한번이라도 검수 완료" 판정의 2순위 근거).
     *
     * <p>{@code LsTaskEventLog.approve}/{@code approveWithoutLabel} 이 승인 시 <b>항상</b>
     * {@code EVENT_APPROVE} 를 남기고 이 테이블은 append-only 라, 승인 동결 스냅샷이 없는 옛 영상
     * (V97 이전 승인)도 이 축으로 잡힌다. 최신 1건 조회({@code findFirst...})와 달리 <b>존재만</b>
     * 확인하므로 정렬·엔티티 적재가 없다.
     */
    boolean existsByRawDataIdAndEventTypeCd(Long rawDataId, String eventTypeCd);
}
