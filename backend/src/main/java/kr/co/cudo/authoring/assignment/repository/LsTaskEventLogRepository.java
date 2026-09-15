package kr.co.cudo.authoring.assignment.repository;

import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /**
     * 주어진 영상들에 대해 <b>지정한 종류들 중 가장 마지막 이벤트 1건씩</b>을 <b>쿼리 한 번</b>에 돌려준다.
     *
     * <p>검수 점유 판정의 조달 창구다. 점유 관련 종류(검수 시작·승인·반려)를 함께 넘기면, 영상마다
     * 그 셋 중 마지막 한 건이 돌아오므로 <b>「검수 시작 뒤에 승인·반려가 있었는가」를 같은 결과로
     * 판정</b>할 수 있다 — 돌아온 종류가 검수 시작이면 그 뒤에 종결 이벤트가 없다는 뜻이다. 조건을
     * 두 번 조회로 나누면 그 사이에 승인이 끼어들어 판정이 어긋난다.
     *
     * <p><b>목록 화면이 행마다 이 메서드를 부르면 안 된다</b>(N+1). 페이지의 영상 식별자를 모아
     * 한 번에 넘긴다. 영상 기준 인덱스 {@code IX_LS_TASK_EVNT_LOG_RAW} 가 이 조회를 받친다.
     *
     * <p><b>「마지막」의 기준이 발생일시가 아니라 이벤트 PK 인 이유</b> — 이 원장은 append-only 이고
     * PK 가 채번 순서(=적재 순서)로 증가한다. 발생일시는 객체를 만든 시각이라 적재 순서와 미세하게
     * 어긋날 수 있고, 같은 시각에 두 건이 들어오면 순서가 정해지지 않는다. PK 기준은 언제나 단일한
     * 답을 준다. (시간순 <b>표시</b>는 {@link #findByRawDataIdOrderByOcrnDtAsc} 가 따로 담당한다.)
     *
     * @param rawDataIds  조회 대상 영상 식별자 — 비어 있으면 호출하지 않는다(IN () 은 유효한 SQL 이 아니다)
     * @param eventTypeCds 마지막 1건을 고를 후보 종류들
     * @design ADR-067
     * @design ERD-014
     */
    @Query("""
            SELECT e FROM LsTaskEventLog e
             WHERE e.rawDataId IN :rawDataIds
               AND e.eventTypeCd IN :eventTypeCds
               AND e.eventSeq = (
                     SELECT MAX(latest.eventSeq) FROM LsTaskEventLog latest
                      WHERE latest.rawDataId = e.rawDataId
                        AND latest.eventTypeCd IN :eventTypeCds)
            """)
    List<LsTaskEventLog> findLatestOfTypesByRawDataIds(@Param("rawDataIds") Collection<Long> rawDataIds,
                                                       @Param("eventTypeCds") Collection<String> eventTypeCds);
}
