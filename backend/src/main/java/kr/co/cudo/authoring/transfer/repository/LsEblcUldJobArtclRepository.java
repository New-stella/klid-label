package kr.co.cudo.authoring.transfer.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.transfer.entity.LsEblcUldJobArtcl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 일괄업로드작업항목 원장 접근 — <b>일꾼이 항목을 집어 가는 자리</b>다.
 *
 * <h3>집기는 조건부 UPDATE 여야 한다 (CWE-362)</h3>
 * <p>2노드 Active-Active 이고 한 노드 안에서도 일꾼이 여럿이다. 「대기 항목을 조회한 뒤 처리중으로
 * 바꾼다」로 하면 <b>둘 다 통과</b>해 같은 영상이 두 번 적재된다. DB 가 직렬화하는 단일 UPDATE 로
 * <b>영향 행수 1을 받은 쪽만</b> 소유권을 갖게 한다. 조회는 후보를 고르는 용도일 뿐이고 소유권을
 * 주지 않는다.
 *
 * <h3>마감도 조건부다</h3>
 * <p>되돌리기 잡이 「처리 중인 채로 오래 멈춘」 항목을 대기로 되돌리는데, 그 직후 원래 일꾼이 살아
 * 돌아와 결과를 쓰면 <b>이미 다른 일꾼이 집어 간 항목</b>을 덮어쓴다. 그래서 마감은 자기가 아직
 * 처리중일 때만 쓴다.
 *
 * @design DOMAIN-017
 * @design ERD-032
 * @design API-218
 * @design AC-1033
 */
@ControlRepo
public interface LsEblcUldJobArtclRepository extends JpaRepository<LsEblcUldJobArtcl, Long> {

    /**
     * 다음에 집을 후보 — 대기 항목을 일련번호 순으로 본다.
     *
     * <p>순서를 고정하는 이유는 두 노드가 같은 순서로 접근하게 해 경합을 줄이기 위해서다. 이 조회는
     * <b>소유권을 주지 않는다</b> — 소유권은 {@link #claim} 이 준다.
     */
    @Query("SELECT a FROM LsEblcUldJobArtcl a "
            + "WHERE a.eblcUldJobSn = :jobSn AND a.artclSttsCd = 'PENDING' "
            + "ORDER BY a.eblcUldJobArtclSn ASC")
    List<LsEblcUldJobArtcl> findPendingCandidates(@Param("jobSn") Long jobSn, Pageable pageable);

    /**
     * 항목 하나를 <b>원자적으로</b> 집는다.
     *
     * @return 1이면 이 호출이 소유권을 얻었다. 0이면 다른 쪽이 먼저 집었거나 이미 끝난 항목이다
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE LsEblcUldJobArtcl a SET a.artclSttsCd = 'PROCESSING', a.bgngDt = :now, a.mdfcnDt = :now "
            + "WHERE a.eblcUldJobArtclSn = :artclSn AND a.artclSttsCd = 'PENDING'")
    int claim(@Param("artclSn") Long artclSn, @Param("now") LocalDateTime now);

    /**
     * 처리 중이라는 신호를 갱신한다 — 오래 걸리는 항목이 <b>멈춘 것으로 오인</b>되어 되돌려지지 않게 한다.
     *
     * <p>큰 영상 하나의 복사가 되돌리기 임계를 넘으면, 그 사이 다른 일꾼이 같은 항목을 집어 같은
     * 영상을 두 번 적재하려 든다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE LsEblcUldJobArtcl a SET a.mdfcnDt = :now "
            + "WHERE a.eblcUldJobArtclSn = :artclSn AND a.artclSttsCd = 'PROCESSING'")
    int heartbeat(@Param("artclSn") Long artclSn, @Param("now") LocalDateTime now);

    /** 적재 성공으로 마감한다 — 자기가 아직 처리중일 때만. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE LsEblcUldJobArtcl a SET a.artclSttsCd = 'SUCCESS', a.rawSn = :rawSn, "
            + "a.failRsn = null, a.cmptnDt = :now, a.mdfcnDt = :now "
            + "WHERE a.eblcUldJobArtclSn = :artclSn AND a.artclSttsCd = 'PROCESSING'")
    int markSuccess(@Param("artclSn") Long artclSn, @Param("rawSn") Long rawSn,
                    @Param("now") LocalDateTime now);

    /**
     * 끝내지 못한 채 마감한다 — 실패든 건너뜀이든.
     *
     * <p>영상 일련번호를 함께 받는 이유는 <b>이미 들어와 있어서 건너뛴</b> 경우에 무엇과 부딪혔는지를
     * 사람이 되짚을 수 있어야 하기 때문이다. 그 밖의 사유에서는 비어 있다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE LsEblcUldJobArtcl a SET a.artclSttsCd = :status, a.rawSn = :rawSn, "
            + "a.failRsn = :reason, a.cmptnDt = :now, a.mdfcnDt = :now "
            + "WHERE a.eblcUldJobArtclSn = :artclSn AND a.artclSttsCd = 'PROCESSING'")
    int markUnfinished(@Param("artclSn") Long artclSn, @Param("status") String status,
                       @Param("rawSn") Long rawSn, @Param("reason") String reason,
                       @Param("now") LocalDateTime now);

    /**
     * 처리 중인 채로 오래 멈춘 항목을 <b>다시 집을 수 있게</b> 되돌린다.
     *
     * <p>노드가 처리 도중 다시 뜨면 그 항목은 아무도 손대지 않는 채 처리중으로 남는다. 되돌리지 않으면
     * 그 작업은 영원히 끝나지 않는다. 다시 집힌 횟수를 함께 올려 <b>영원히 맴돌지 않게</b> 한다.
     *
     * <p>임계를 짧게 잡으면 <b>지금 돌고 있는 처리</b>를 빼앗아 같은 영상이 두 번 적재되므로, 임계는
     * 설정에 하한을 두고 일꾼은 처리 중 신호를 갱신한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE LsEblcUldJobArtcl a SET a.artclSttsCd = 'PENDING', a.rtryNmtm = a.rtryNmtm + 1, "
            + "a.bgngDt = null, a.mdfcnDt = :now "
            + "WHERE a.artclSttsCd = 'PROCESSING' AND a.mdfcnDt < :threshold AND a.rtryNmtm < :maxRetry")
    int reclaimStale(@Param("threshold") LocalDateTime threshold, @Param("maxRetry") int maxRetry,
                     @Param("now") LocalDateTime now);

    /**
     * 다시 집을 횟수를 다 쓴 항목을 실패로 마감한다.
     *
     * <p>되돌리기만 있고 이 마감이 없으면 <b>영원히 처리중과 대기 사이를 오가는</b> 항목이 남아 작업이
     * 끝나지 않는다(AC-1033).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE LsEblcUldJobArtcl a SET a.artclSttsCd = 'FAILED', a.failRsn = :reason, "
            + "a.cmptnDt = :now, a.mdfcnDt = :now "
            + "WHERE a.artclSttsCd = 'PROCESSING' AND a.mdfcnDt < :threshold AND a.rtryNmtm >= :maxRetry")
    int failExhausted(@Param("threshold") LocalDateTime threshold, @Param("maxRetry") int maxRetry,
                      @Param("reason") String reason, @Param("now") LocalDateTime now);

    /** 아직 끝나지 않은 항목 수 — 0 이면 작업을 종결할 수 있다. */
    @Query("SELECT COUNT(a) FROM LsEblcUldJobArtcl a "
            + "WHERE a.eblcUldJobSn = :jobSn AND a.artclSttsCd IN ('PENDING', 'PROCESSING')")
    long countUnfinished(@Param("jobSn") Long jobSn);

    /** 실패했거나 건너뛴 항목 수 — 종결 상태를 완료로 할지 실패로 할지 가른다. */
    @Query("SELECT COUNT(a) FROM LsEblcUldJobArtcl a "
            + "WHERE a.eblcUldJobSn = :jobSn AND a.artclSttsCd IN ('FAILED', 'SKIPPED')")
    long countFailed(@Param("jobSn") Long jobSn);

    /** 건별 결과 — 일련번호 순. 수가 많을 수 있어 상한까지만 담는다(CWE-770). */
    List<LsEblcUldJobArtcl> findByEblcUldJobSnOrderByEblcUldJobArtclSnAsc(Long jobSn, Pageable pageable);

    /** 건별 결과(상태로 거른다) — 위쪽 집계는 언제나 전체 기준이며 이 필터의 영향을 받지 않는다. */
    List<LsEblcUldJobArtcl> findByEblcUldJobSnAndArtclSttsCdOrderByEblcUldJobArtclSnAsc(
            Long jobSn, String artclSttsCd, Pageable pageable);

    /** 전체 항목 수 — 담긴 목록이 잘렸는지 판정한다. */
    long countByEblcUldJobSn(Long jobSn);

    /** 상태별 항목 수 — 거른 목록이 잘렸는지 판정한다. */
    long countByEblcUldJobSnAndArtclSttsCd(Long jobSn, String artclSttsCd);
}
