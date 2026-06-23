package kr.co.cudo.authoring.assignment.repository;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * @return 영향 행수 (1=전이 성공/이번 호출이 트리거 권한 획득, 0=이미 진행 중이거나 row 없음)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE LsRawDataStatus s SET s.dataSttsCd = :queuedStatus, s.updDt = CURRENT_TIMESTAMP "
            + "WHERE s.rawDataId = :rawSn AND s.dataSttsCd NOT IN :skipStatuses")
    int transitionToBatchQueuedIfNotSkipped(@Param("rawSn") Long rawSn,
                                            @Param("queuedStatus") String queuedStatus,
                                            @Param("skipStatuses") Collection<String> skipStatuses);
}
