package kr.co.cudo.authoring.batch.retry;

import jakarta.persistence.LockModeType;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 배치 재시도 대기 Repository (B2).
 *
 * <p>{@code klid_at} (Control) DB 의 {@code LS_BAT_RTY_WTNG} 에 매핑된다. 2노드 Active-Active
 * 정합을 위해 폴링 클레임은 조건부 원자 UPDATE({@link #claimAtomically})로 수행한다.
 */
@ControlRepo
public interface LsBatRtyWtngRepository extends JpaRepository<LsBatRtyWtng, Long> {

    Optional<LsBatRtyWtng> findByRawSn(Long rawSn);

    /**
     * 동일 rawSn 행을 {@link LockModeType#PESSIMISTIC_WRITE}(SELECT … FOR UPDATE)로 잠금 조회한다
     * (CWE-362 — 동시 최초 등록 후 증가 처리 직렬화).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM LsBatRtyWtng q WHERE q.rawSn = :rawSn")
    Optional<LsBatRtyWtng> findByRawSnForUpdate(@Param("rawSn") Long rawSn);

    /**
     * CWE-362 — 동시 최초 등록(find-or-create) UK 경쟁을 예외 없이 흡수하는 원자 upsert.
     *
     * <p>{@code RAW_SN} 이 없으면 PENDING 초기 행(RTY_NMTM=0)을 INSERT 하고, 이미 존재하면
     * {@code ON CONFLICT DO NOTHING} 으로 무시한다. 두 노드가 동시에 호출해도 한쪽만 INSERT 되고
     * 다른 쪽은 조용히 무시되어 {@code DataIntegrityViolationException} 이 발생하지 않는다.
     *
     * @return 영향 행수 (1 = 신규 INSERT, 0 = 이미 존재)
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "INSERT INTO LS_BAT_RTY_WTNG "
            + "(RAW_SN, RTY_NMTM, MAX_RTY_NMTM, STTS_CD, REG_DT, MDFCN_DT) "
            + "VALUES (:rawSn, 0, :maxRtyNmtm, 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) "
            + "ON CONFLICT (RAW_SN) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("rawSn") Long rawSn, @Param("maxRtyNmtm") int maxRtyNmtm);

    /** 재시도 대상 polling — STTS_CD=PENDING + RTY_PRNMNT_DT <= now, 가장 오래된 순. */
    List<LsBatRtyWtng> findBySttsCdAndRtyPrnmntDtLessThanEqualOrderByRtyPrnmntDtAsc(
            String sttsCd, LocalDateTime cutoff, Pageable pageable);

    /**
     * CWE-362 — 2노드 동시 폴링 시 이중 재시도 차단용 조건부 원자 CAS UPDATE.
     *
     * <p>{@code STTS_CD='PENDING'} 인 행만 {@code 'RETRYING'} 으로 전이시킨다. DB 가 동일 row 의
     * 동시 UPDATE 를 직렬화하므로 정확히 1개 노드만 영향 행수 1 을 받고 나머지는 0 을 받는다.
     *
     * @return 영향 행수 (1 = claim 성공, 0 = 다른 노드가 이미 클레임)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE LsBatRtyWtng q SET q.sttsCd = 'RETRYING', q.mdfcnDt = :now "
            + "WHERE q.batRtySn = :batRtySn AND q.sttsCd = 'PENDING'")
    int claimAtomically(@Param("batRtySn") Long batRtySn, @Param("now") LocalDateTime now);

    void deleteByRawSn(Long rawSn);

    /**
     * 수동 재처리(reprocess) 전용 — RETRYING(다른 주체가 처리 중) 행은 보존하고 그 외 상태 행만 삭제한다.
     *
     * <p>CWE-362 — {@code clear(rawSn)}(무조건 삭제)는 자동 폴러가 방금 클레임한 RETRYING 부기를 파괴할 수
     * 있다. 수동 재처리는 이미 RAW FAILED→PROCESSING 원자 클레임으로 소유권을 획득한 뒤 이 메서드로
     * 유휴(PENDING/EXHAUSTED) 대기 행만 리셋한다.
     *
     * @return 삭제 행수
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM LsBatRtyWtng q WHERE q.rawSn = :rawSn AND q.sttsCd <> 'RETRYING'")
    int deleteIdleByRawSn(@Param("rawSn") Long rawSn);
}
