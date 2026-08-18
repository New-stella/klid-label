package kr.co.cudo.authoring.assignment.repository;

import jakarta.persistence.LockModeType;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsRawDataStatusRepository extends JpaRepository<LsRawDataStatus, Long> {

    /**
     * 영상(raw data) ID 리스트로 검수 상태 row 일괄 조회.
     * 증강 요청 시 검수 완료(APPROVED) 영상만 통과시키는 검증에 사용.
     * row 가 없는 영상은 미검수로 간주 (호출 측에서 차집합 계산).
     */
    List<LsRawDataStatus> findByRawDataIdIn(Collection<Long> rawDataIds);

    /**
     * 재배정 가드용 <b>공유 잠금(SELECT ... FOR SHARE) 조회</b> (CWE-362, DEV_FIX H4-b).
     *
     * <p>기존 재배정 가드는 작업 상태를 {@code findById} 로 <b>읽기만</b> 했고 이후 UPDATE 는
     * {@code LS_TASK_ALTMNT}(다른 row)에만 일어나, 두 row 가 잠금을 공유하지 않아
     * "가드가 상태를 읽음 → 다른 tx 가 approve 커밋 → reassign 커밋" 순서에서 <b>검수 승인된 영상이
     * 재배정</b>됐다. {@code LS_TASK_ALTMNT} 의 {@code @Version} 은 assignment 버전만 올리므로 이 창을
     * 닫지 못하고, 2노드 Active-Active 라 JVM 락도 무효다.
     *
     * <p>따라서 가드가 상태 row 를 {@code PESSIMISTIC_READ}(PostgreSQL {@code FOR SHARE})로 잠근 채
     * 트랜잭션 종료까지 보유한다. 동시 {@code approve}(엔티티 UPDATE)는 이 잠금과 충돌해 대기하므로
     * 두 트랜잭션이 DB 수준에서 직렬화된다 — 승인이 먼저 커밋되면 가드가 APPROVED 를 관측해 409 로 거부하고,
     * 재배정이 먼저 커밋되면 승인은 그 뒤에 진행된다(어느 순서든 "승인 후 재배정"은 발생하지 않는다).
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT s FROM LsRawDataStatus s WHERE s.rawDataId = :rawSn")
    Optional<LsRawDataStatus> findByRawDataIdForShare(@Param("rawSn") Long rawSn);

    /**
     * 배치 트리거 멱등성 보장용 조건부 원자 전이 (check-and-set).
     *
     * <p>작업 상태(DATA_STTS_CD)가 {@code skipStatuses}(BATCH_QUEUED/PROCESSING/COMPLETED 등)에
     * 속하지 않을 때만 BATCH_QUEUED 로 전이한다. 단일 SQL UPDATE 라 DB 가 동시 호출을 직렬화하므로,
     * 동일 rawSn 에 대해 두 마킹 브리지가 동시에 호출해도 정확히 1건만 영향 행수 1 을 받고 나머지는 0 을
     * 받는다(D2 이중 마킹 레이스 차단). AFTER_COMMIT 컨텍스트에서 직접 호출되는 dirty-write 와 달리
     * 본 UPDATE 는 즉시 커밋되어 영속이 보장된다(D1 비영속 결함 차단).
     *
     * <p><b>주의(@Version 미증가)</b>: 본 메서드는 벌크 UPDATE 라 {@code @Version}(VER) 을 증가시키지
     * 않는다. 따라서 엔티티 패스 writer(낙관적 잠금에 의존하는 동시 변경자)와 같은 row 를 동시에 다룰 수
     * 있는 상태(예: 검수 윈도 IN_REVIEW)에서는 호출하지 말 것. 현 상태 흐름상 배치 큐잉 윈도와 검수 윈도는
     * 비중첩이라 충돌하지 않는다.
     *
     * <p>참고: 배치 <b>단계 전이</b>는 이 가정에 의존하지 않도록 {@link #transitionByBatchIfNotBlocked}
     * ({@code UPDATE VERSIONED} + 검수 소유 상태 차단)로 분리되어 있다(B-ISSUE-03).
     *
     * @return 영향 행수 (1=전이 성공/이번 호출이 트리거 권한 획득, 0=이미 진행 중이거나 row 없음)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE LsRawDataStatus s SET s.dataSttsCd = :queuedStatus, s.updDt = CURRENT_TIMESTAMP "
            + "WHERE s.rawDataId = :rawSn AND s.dataSttsCd NOT IN :skipStatuses")
    int transitionToBatchQueuedIfNotSkipped(@Param("rawSn") Long rawSn,
                                            @Param("queuedStatus") String queuedStatus,
                                            @Param("skipStatuses") Collection<String> skipStatuses);

    /**
     * 배치 상태 전이용 조건부 원자 전이 (B-ISSUE-03, check-and-set).
     *
     * <p>작업 상태(DATA_STTS_CD)가 <b>검수 소유 상태</b>({@code blockedStatuses} —
     * PENDING/IN_REVIEW/APPROVED/REJECTED)가 아닐 때만 전이한다. 기존 배치 경로는 현재 상태와 무관하게 무조건 UPDATE 해
     * {@code APPROVED → PROCESSING → ASSIGNED} 로 검수 승인을 조용히 지웠다.
     *
     * <p><b>@Version 정합(UPDATE VERSIONED)</b>: 일반 벌크 UPDATE 는 {@code @Version}(VER)을 증가시키지
     * 않아, 같은 row 를 엔티티 패스로 다루는 검수 writer 의 낙관적 잠금을 무력화한다(lost update).
     * 배치 윈도와 검수 윈도는 PENDING 구간에서 겹칠 수 있으므로(작업자 제출 직후 배치 재실행),
     * {@code UPDATE VERSIONED} 로 버전을 함께 증가시켜 동시 엔티티 writer 가 충돌을 감지하게 한다.
     * 이로써 {@link #transitionToBatchQueuedIfNotSkipped} Javadoc 이 전제한 "윈도 비중첩" 가정에
     * 의존하지 않는다.
     *
     * @return 영향 행수 (1=전이 성공, 0=검수 소유 상태라 skip 또는 row 없음)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE VERSIONED LsRawDataStatus s SET s.dataSttsCd = :toStatus, s.updDt = CURRENT_TIMESTAMP "
            + "WHERE s.rawDataId = :rawSn AND s.dataSttsCd NOT IN :blockedStatuses")
    int transitionByBatchIfNotBlocked(@Param("rawSn") Long rawSn,
                                      @Param("toStatus") String toStatus,
                                      @Param("blockedStatuses") Collection<String> blockedStatuses);

    /**
     * 수동 배치 재처리 클레임용 조건부 원자 전이 (CWE-362, check-and-set).
     *
     * <p>작업 상태(DATA_STTS_CD)가 {@code fromStatus}(FAILED)일 때만 {@code toStatus}(PROCESSING)로
     * 전이한다. 단일 SQL UPDATE 라 DB 가 동시 호출을 직렬화하므로, 동일 rawSn 에 수동 재기동/자동 폴러가
     * 동시에 접근해도 정확히 1건만 영향 행수 1 을 받고 나머지는 0 을 받는다(이중 파이프라인 실행 차단).
     *
     * <p><b>주의(@Version 미증가)</b>: 벌크 UPDATE 라 {@code @Version}(VER)을 증가시키지 않는다. FAILED
     * 상태는 라벨링/검수 윈도와 비중첩이라 낙관적 잠금 writer 와 충돌하지 않는다
     * ({@link #transitionToBatchQueuedIfNotSkipped} 와 동일 제약).
     *
     * @return 영향 행수 (1=클레임 성공, 0=FAILED 아님/이미 다른 주체가 클레임/row 없음)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE LsRawDataStatus s SET s.dataSttsCd = :toStatus, s.updDt = CURRENT_TIMESTAMP "
            + "WHERE s.rawDataId = :rawSn AND s.dataSttsCd = :fromStatus")
    int claimReprocessFromFailed(@Param("rawSn") Long rawSn,
                                 @Param("fromStatus") String fromStatus,
                                 @Param("toStatus") String toStatus);
}
