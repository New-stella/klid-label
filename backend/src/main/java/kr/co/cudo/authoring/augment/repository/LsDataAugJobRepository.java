package kr.co.cudo.authoring.augment.repository;

import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 증강 외부 위탁 작업 리포지토리 (LS_DATA_AUG_JOB, V140).
 *
 * <p>웹훅 수신부(A2)가 {@code job_id}/{@code request_id} 로 역조회할 수 있도록 두 축 모두 노출한다.
 * Phase 8-A 에서 <b>만료 스윕</b>(비종결 job 회수)용 후보 조회 + 원자 클레임을 추가했다.
 */
@ControlRepo
public interface LsDataAugJobRepository extends JpaRepository<LsDataAugJob, Long> {

    List<LsDataAugJob> findByDataAugSnOrderByJobSeqAsc(Long dataAugSn);

    Optional<LsDataAugJob> findByIdempotencyKey(String idempotencyKey);

    Optional<LsDataAugJob> findByExternalJobId(String externalJobId);

    /**
     * 만료 스윕 후보 — 비종결({@code RECEIVED}/{@code RUNNING})인 채 {@code cutoff} 이전부터
     * <b>갱신이 멈춘</b> job (Phase 8-A).
     *
     * <p>기준을 {@code REG_DT}(총 경과)가 아니라 {@code MDFCN_DT}(마지막 갱신)로 잡는 이유:
     * 외부가 {@code RUNNING} 웹훅으로 진행을 계속 알리는 <b>정상 장기 작업</b>은 살아 있는 것이므로
     * 회수 대상이 아니다. 반대로 취소·400 거부·무응답으로 <b>갱신이 끊긴</b> 행만 골라낸다.
     *
     * @return {@code (AUG_JOB_SN, DATA_AUG_SN)} 앵커 목록. 실제 회수는 {@link #claimExpired} 로
     *         <b>원자 클레임에 성공한 건만</b> 수행해야 한다(2노드 중복 회수 방지).
     */
    @Query(value = """
            SELECT j.AUG_JOB_SN, j.DATA_AUG_SN
              FROM LS_DATA_AUG_JOB j
             WHERE j.JOB_STTS_CD IN ('RECEIVED', 'RUNNING')
               AND j.MDFCN_DT < :cutoff
             ORDER BY j.MDFCN_DT ASC
             LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findExpirableAnchors(@Param("cutoff") LocalDateTime cutoff,
                                        @Param("limit") int limit);

    /**
     * 만료 <b>원자 클레임</b> — 비종결 job 을 사유와 함께 {@code FAILED} 로 종결한다 (Phase 8-A).
     *
     * <p>상태 전이 자체가 클레임이다. PostgreSQL 은 UPDATE 시 행 락을 얻은 뒤 <b>갱신된 최신
     * 버전으로 WHERE 를 재평가</b>하므로, Active-Active 두 노드가 같은 job 을 동시에 노려도
     * 한쪽만 1행을 얻는다(별도 잠금 컬럼·Quartz 클러스터링에 의존하지 않는다).
     *
     * <p>{@code JOB_STTS_CD IN ('RECEIVED','RUNNING')} 조건은 그 사이 웹훅이 도착해 정상 종결된
     * 경우 클레임을 포기하게 한다 — 늦게 온 성공을 만료가 덮어쓰지 않는다(fail-safe).
     * 파라미터 바인딩만 사용한다(CWE-89 표면 없음).
     *
     * @return 클레임에 성공한 행 수(0 또는 1)
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE LS_DATA_AUG_JOB
               SET JOB_STTS_CD = 'FAILED',
                   ERR_CD = :errorCode,
                   ERR_MSG_CN = :errorMessage,
                   MDFCN_DT = :now
             WHERE AUG_JOB_SN = :augJobSn
               AND JOB_STTS_CD IN ('RECEIVED', 'RUNNING')
               AND MDFCN_DT < :cutoff
            """, nativeQuery = true)
    int claimExpired(@Param("augJobSn") Long augJobSn,
                     @Param("cutoff") LocalDateTime cutoff,
                     @Param("errorCode") String errorCode,
                     @Param("errorMessage") String errorMessage,
                     @Param("now") LocalDateTime now);

    /**
     * <b>제출 ACK 원자 클레임</b> — 202 로 받은 외부 job_id 를 적재한다 (Phase C-3 논블로킹 제출).
     *
     * <h3>왜 조건부 UPDATE 인가 (지각 신호 상태 강등 금지 · 2노드)</h3>
     * <p>제출이 논블로킹이 되면 <b>벤더 콜백이 우리 ACK 기록보다 먼저</b> 도착할 수 있다(저지연 벤더·목).
     * 콜백은 이미 {@code RUNNING}/{@code SUCCEEDED}/{@code FAILED} 로 전이시키고 job_id 도 채웠는데,
     * 뒤늦은 ACK 기록이 엔티티를 무조건 덮어쓰면 <b>SUCCEEDED 를 RECEIVED 로 강등</b>시켜 롤업이
     * 영원히 보류된다(구 {@code LsDataAugJob#markAccepted} 는 상태를 무조건 RECEIVED 로 되돌린다).
     *
     * <p>그래서 "아직 아무 신호도 받지 않은 선기록 상태"({@code RECEIVED} + {@code OTSD_JOB_ID IS NULL})
     * 에서만 클레임한다. PostgreSQL 은 UPDATE 시 행 락을 얻고 <b>최신 버전으로 WHERE 를 재평가</b>하므로
     * Active-Active 2노드에서도 한쪽만 1행을 얻는다.
     *
     * @return 클레임에 성공한 행 수(0 = 이미 콜백/다른 노드가 선점 → 기록하지 않는다)
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE LS_DATA_AUG_JOB
               SET OTSD_JOB_ID = :externalJobId,
                   MDFCN_DT = :now
             WHERE AUG_JOB_SN = :augJobSn
               AND JOB_STTS_CD = 'RECEIVED'
               AND OTSD_JOB_ID IS NULL
            """, nativeQuery = true)
    int claimSubmitAck(@Param("augJobSn") Long augJobSn,
                       @Param("externalJobId") String externalJobId,
                       @Param("now") LocalDateTime now);

    /**
     * <b>제출 실패 원자 클레임</b> — 위탁 호출이 확정 실패(4xx/타임아웃/서킷/빈 응답)했음을 종결 기록한다.
     *
     * <p>{@link #claimSubmitAck} 과 같은 술어를 쓴다. 우리 쪽 타임아웃이지만 <b>요청은 실제로 도달해</b>
     * 벤더가 이미 콜백을 보낸 경우, 무조건 FAILED 로 덮으면 살아 있는 job 을 죽여 정상 산출물이 멱등
     * 흡수로 버려진다. 아직 아무 신호도 없는 선기록 행만 실패로 종결한다(fail-safe).
     *
     * @return 클레임에 성공한 행 수(0 = 그 사이 콜백이 선점 → 강등하지 않는다)
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE LS_DATA_AUG_JOB
               SET JOB_STTS_CD = 'FAILED',
                   ERR_CD = :errorCode,
                   ERR_MSG_CN = :errorMessage,
                   MDFCN_DT = :now
             WHERE AUG_JOB_SN = :augJobSn
               AND JOB_STTS_CD = 'RECEIVED'
               AND OTSD_JOB_ID IS NULL
            """, nativeQuery = true)
    int claimSubmitFailure(@Param("augJobSn") Long augJobSn,
                           @Param("errorCode") String errorCode,
                           @Param("errorMessage") String errorMessage,
                           @Param("now") LocalDateTime now);
}
