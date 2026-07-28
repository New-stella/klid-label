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
}
