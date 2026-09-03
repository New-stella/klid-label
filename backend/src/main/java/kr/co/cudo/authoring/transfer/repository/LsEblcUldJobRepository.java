package kr.co.cudo.authoring.transfer.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.transfer.entity.LsEblcUldJob;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 일괄업로드작업 원장 접근.
 *
 * <h3>집계는 <b>읽어서 더하지</b> 않는다</h3>
 * <p>성공·실패 건수는 여러 일꾼이 동시에 올린다. 엔티티를 읽어 {@code +1} 해 저장하면 두 일꾼이 같은
 * 값을 읽고 같은 값을 써서 <b>한 건이 사라진다</b>(lost update — CWE-362). 그래서 증가는 DB 가
 * 직렬화하는 단일 UPDATE 로만 한다.
 *
 * @design DOMAIN-017
 * @design ERD-032
 * @design API-217
 * @design API-218
 */
@ControlRepo
public interface LsEblcUldJobRepository extends JpaRepository<LsEblcUldJob, Long> {

    /**
     * 성공 건수를 하나 올린다 — 진행중인 작업에만.
     *
     * <p>이미 종결된 작업의 건수를 올리면 사람이 본 최종 집계가 나중에 바뀐다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE LsEblcUldJob j SET j.scsNocs = j.scsNocs + 1, j.mdfcnDt = :now "
            + "WHERE j.eblcUldJobSn = :jobSn AND j.jobSttsCd = 'RUNNING'")
    int incrementSuccess(@Param("jobSn") Long jobSn, @Param("now") LocalDateTime now);

    /** 실패(건너뜀 포함) 건수를 하나 올린다 — 진행중인 작업에만. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE LsEblcUldJob j SET j.failNocs = j.failNocs + 1, j.mdfcnDt = :now "
            + "WHERE j.eblcUldJobSn = :jobSn AND j.jobSttsCd = 'RUNNING'")
    int incrementFailure(@Param("jobSn") Long jobSn, @Param("now") LocalDateTime now);

    /**
     * 첫 항목이 시작된 시각을 <b>비어 있을 때만</b> 적는다.
     *
     * <p>조건을 두지 않으면 항목이 하나 끝날 때마다 시작 시각이 앞으로 밀려, 사람이 보는 소요 시간이
     * 언제나 마지막 항목의 것이 된다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE LsEblcUldJob j SET j.bgngDt = :now, j.mdfcnDt = :now "
            + "WHERE j.eblcUldJobSn = :jobSn AND j.bgngDt IS NULL")
    int markStartedIfAbsent(@Param("jobSn") Long jobSn, @Param("now") LocalDateTime now);

    /**
     * 작업을 종결한다 — <b>진행중일 때만</b>. 두 일꾼이 마지막 항목을 동시에 끝내도 한 번만 종결된다.
     *
     * @param status 종결 상태 — 실패가 없으면 완료, 남았으면 실패
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE LsEblcUldJob j SET j.jobSttsCd = :status, j.cmptnDt = :now, j.mdfcnDt = :now "
            + "WHERE j.eblcUldJobSn = :jobSn AND j.jobSttsCd = 'RUNNING'")
    int finishIfRunning(@Param("jobSn") Long jobSn, @Param("status") String status,
                        @Param("now") LocalDateTime now);

    /**
     * 아직 진행중인 작업 — 노드가 다시 떴을 때 <b>이어서 처리할</b> 대상을 찾는다.
     *
     * <p>오래된 것부터 본다. 새 작업이 계속 들어오는 동안 먼저 멈춘 작업이 뒤로 밀려 굶지 않게 한다.
     */
    @Query("SELECT j FROM LsEblcUldJob j WHERE j.jobSttsCd = 'RUNNING' ORDER BY j.eblcUldJobSn ASC")
    List<LsEblcUldJob> findRunning(Pageable pageable);
}
