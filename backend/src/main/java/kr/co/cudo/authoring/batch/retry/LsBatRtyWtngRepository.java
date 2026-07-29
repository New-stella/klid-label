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

    /**
     * B-ISSUE-83 — <b>stale RETRYING 회수 후보</b>(앵커) 목록.
     *
     * <p>{@code RETRYING} 은 폴링 노드가 클레임한 "처리 중" 표시이고, {@code PENDING} 복귀는
     * {@code BatchRetryQuartzJob.execute} 가 <b>정상적으로 예외를 받을 때만</b> 일어난다. 그 노드가
     * 처리 도중 죽으면(kill -9 · OOM · 순단) 아무도 복귀시키지 않아 그 항목은 <b>영구 RETRYING</b> 으로
     * 남고 해당 영상의 재시도가 조용히 멈춘다(2노드 Active-Active 라 남은 노드도 집지 못한다).
     *
     * <p>정상 처리 중인 항목을 뺏지 않도록 <b>마지막 갱신({@code MDFCN_DT})이 임계를 넘긴</b> 행만
     * 후보로 삼는다({@code claimAtomically} 가 클레임 시각을 MDFCN_DT 에 찍는다). 오래된 순 +
     * {@code LIMIT} 으로 한 tick 이 무한정 길어지지 않게 한다(무제한 조회 금지, OWASP API4).
     * 파라미터 바인딩만 사용(CWE-89 표면 없음).
     *
     * <p><b>인덱스 미추가 판단(Phase 9 QA)</b>: {@code ORDER BY MDFCN_DT} 는 기존
     * {@code IDX_LBRW_STATUS(STTS_CD, RTY_PRNMNT_DT)} 에 실리지 않아 정렬이 인덱스를 타지 않는다.
     * 다만 {@code RETRYING} 은 폴링 노드가 처리 도중에만 잠깐 걸치는 과도 상태(정상 흐름은 곧
     * {@code PENDING} 복귀 또는 성공 삭제로 빠짐)라 동시 존재 행 수가 항상 적고, 필터
     * {@code STTS_CD='RETRYING'} 로 이미 후보 집합이 작게 좁혀진 뒤의 인메모리 정렬이라 비용이
     * 무시할 만하다. 쓰기 경로(INSERT/매 폴링 UPDATE)에 인덱스 유지 비용만 추가하는 것이 손해라
     * 인덱스를 새로 만들지 않는다 — 운영에서 RETRYING 적체가 실제로 관측되면 그때
     * {@code IDX_LBRW_STATUS_MDFCN(STTS_CD, MDFCN_DT)} 를 별도 마이그레이션으로 추가한다.
     */
    @Query(value = """
            SELECT q.BAT_RTY_SN
              FROM LS_BAT_RTY_WTNG q
             WHERE q.STTS_CD = 'RETRYING'
               AND q.MDFCN_DT <= :cutoff
             ORDER BY q.MDFCN_DT ASC
             LIMIT :limit
            """, nativeQuery = true)
    List<Long> findStaleRetryingAnchors(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /**
     * B-ISSUE-83 — stale RETRYING 을 <b>PENDING 으로 원자 복귀</b>시킨다(재시도 상한 이내인 건만).
     *
     * <p>{@code STTS_CD='RETRYING' AND MDFCN_DT <= :cutoff} 를 UPDATE 조건에 그대로 실어 fail-safe 로
     * 재판정한다 — 후보 조회와 회수 사이에 그 노드가 되살아나 정상 복귀시켰거나 다른 노드가 이미
     * 회수했으면 0 행이 되어 덮어쓰지 않는다({@code claimAtomically} 와 동일 CAS 패턴).
     *
     * <p><b>죽은 시도도 1회로 계상한다</b>({@code RTY_NMTM + 1}) — 계상하지 않으면 같은 항목에서
     * 계속 죽는 노드가 상한 없이 영원히 부활시킨다(무한 재시도). 상한에 도달한 건은 이 쿼리의
     * {@code RTY_NMTM < MAX_RTY_NMTM} 조건에서 걸러져 {@link #exhaustStaleRetrying} 가 종결한다.
     *
     * @return 영향 행수 (1 = 회수 성공, 0 = 타 노드가 이미 처리했거나 상한 초과)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE LsBatRtyWtng q SET q.sttsCd = 'PENDING', q.rtyNmtm = q.rtyNmtm + 1, "
            + "q.rtyPrnmntDt = :nextAt, q.mdfcnDt = :now "
            + "WHERE q.batRtySn = :batRtySn AND q.sttsCd = 'RETRYING' AND q.mdfcnDt <= :cutoff "
            + "AND q.rtyNmtm < q.maxRtyNmtm")
    int reclaimStaleRetrying(@Param("batRtySn") Long batRtySn,
                             @Param("cutoff") LocalDateTime cutoff,
                             @Param("nextAt") LocalDateTime nextAt,
                             @Param("now") LocalDateTime now);

    /**
     * B-ISSUE-83 — 재시도 상한에 도달한 stale RETRYING 을 {@code EXHAUSTED} 로 종결한다(무한 부활 금지).
     *
     * <p>{@link #reclaimStaleRetrying} 와 조건이 상호배타({@code RTY_NMTM >= MAX_RTY_NMTM})라 한 행이
     * 두 쿼리에 모두 걸리지 않는다. 삭제가 아니라 소진 마킹이라 이력이 남는다
     * ({@code BatchRetryQueue#enqueueIfRetryable} 의 상한 처리와 동일 종결 상태).
     *
     * @return 영향 행수 (1 = 종결, 0 = 타 노드가 이미 처리했거나 상한 이내)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE LsBatRtyWtng q SET q.sttsCd = 'EXHAUSTED', q.rtyPrnmntDt = NULL, q.mdfcnDt = :now "
            + "WHERE q.batRtySn = :batRtySn AND q.sttsCd = 'RETRYING' AND q.mdfcnDt <= :cutoff "
            + "AND q.rtyNmtm >= q.maxRtyNmtm")
    int exhaustStaleRetrying(@Param("batRtySn") Long batRtySn,
                             @Param("cutoff") LocalDateTime cutoff,
                             @Param("now") LocalDateTime now);

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
