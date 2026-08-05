package kr.co.cudo.authoring.dataset.export.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.dataset.export.entity.LsDatasetExport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * {@code LS_DATASET_EXPORT} 저장소 (control 데이터소스).
 *
 * <p>버전 도출(영상별 누적 export 건수)과 최신 버전 조회를 제공한다. 모든 조회는 파생 쿼리
 * 메서드로 파라미터 바인딩되어 SQL Injection(CWE-89) 표면이 없다.
 */
@ControlRepo
public interface LsDatasetExportRepository extends JpaRepository<LsDatasetExport, Long> {

    /** 영상(rawSn)별 누적 export 건수 — 다음 버전 = count + 1 도출용. */
    long countByDataRawSn(Long rawSn);

    /** 영상(rawSn)의 전체 export 이력 — 재시도 시도 이력(RTY_NMTM) 합산·검증용. */
    List<LsDatasetExport> findByDataRawSn(Long rawSn);

    /** 같은 영상의 같은 버전 존재 여부 — 재산출 중복 방어(UK 사전 확인). */
    boolean existsByDataRawSnAndExportVerNo(Long rawSn, int exportVerNo);

    /** 영상(rawSn)의 최신(최대 버전) export 1건. */
    Optional<LsDatasetExport> findFirstByDataRawSnOrderByExportVerNoDesc(Long rawSn);

    /**
     * 영상(rawSn)의 <b>지정 상태 집합(IN)</b> export 중 최신(최대 버전) 1건.
     *
     * <p>멱등 baseline(직전 <b>SUCCEEDED+PARTIAL</b> 해시) 조회용 — 상태 IN 필터를 쿼리 레벨에서
     * 적용해, 최신 export 가 FAILED/PENDING 이어도 그 이전의 실제 성공/부분 산출 해시를 정확히 찾는다
     * ("최신 1건 후 필터" 방식의 재산출 폭증 버그 방지). PARTIAL 을 baseline 에 포함하는 이유:
     * 원천 이미지가 지속 부재해 매번 PARTIAL 로 마감되는 영상을 무수정 재승인할 때, contentHash 가
     * 직전 PARTIAL 과 같으면 재산출해도 같은 PARTIAL 결과라 무의미하므로 skip 시켜 버전 무한 채번 +
     * 이미지 파일 무한 재복사(디스크 누적)를 막는다. FAILED(written==0)는 재시도 유도를 위해 제외한다.
     * 파생 쿼리 파라미터 바인딩만 사용(CWE-89 표면 없음).
     */
    Optional<LsDatasetExport> findFirstByDataRawSnAndExportSttsCdInOrderByExportVerNoDesc(
            Long rawSn, Collection<String> exportSttsCds);

    /**
     * stale PENDING 회수 <b>후보</b>(앵커) — {@code REG_DT} 가 cutoff 이전인 PENDING export 의 PK 목록.
     *
     * <p>파일 쓰기/상태 마감 전 프로세스 크래시로 {@code PENDING} 에 영구 고착된 잔재를 주기 sweeper 가
     * 회수(FAILED 마감)하는 데 사용한다. 실제 회수는 {@link #claimStalePending} 으로 <b>원자 클레임에
     * 성공한 건만</b> 수행해야 한다(2노드 중복 회수 방지).
     *
     * <p>오래된 순 + {@code LIMIT} — 잔재가 대량으로 쌓여도 한 tick 이 무한정 길어지지 않는다
     * (무제한 조회 금지, OWASP API4). 파라미터 바인딩만 사용(CWE-89 표면 없음).
     */
    @Query(value = """
            SELECT e.OUTPUT_SN
              FROM LS_DATASET_EXPORT e
             WHERE e.OUTPUT_STTS_CD = 'PENDING'
               AND e.REG_DT < :cutoff
             ORDER BY e.REG_DT ASC
             LIMIT :limit
            """, nativeQuery = true)
    List<Long> findStalePendingAnchors(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /**
     * stale PENDING 회수 <b>원자 클레임</b> — PENDING 인 행만 FAILED 로 마감한다 (Phase 9-B).
     *
     * <p>구 구현("조회 후 엔티티 setter")은 2노드 Active-Active 에서 같은 행을 각자 FAILED 로 두 번 쓰는
     * 이중 쓰기였다. Quartz 클러스터링({@code isClustered})은 기본 <b>꺼져</b> 있어 잡 단위 배타성을
     * 기대할 수 없으므로, 상태 전이 자체를 조건부 UPDATE 로 만들어 DB 레벨에서 한쪽만 1행을 얻게 한다
     * ({@link #claimForRetry}·{@code LsDataAugJobRepository#claimExpired} 와 동일 패턴).
     *
     * <p>{@code OUTPUT_STTS_CD = 'PENDING'} + {@code REG_DT < :cutoff} 는 fail-safe 가드다 — 그 사이
     * 산출이 정상 마감(SUCCEEDED/PARTIAL)됐으면 회수가 이를 덮어쓰지 않는다.
     * 파라미터 바인딩만 사용(CWE-89 표면 없음).
     *
     * @return 클레임에 성공한 행 수(0 또는 1)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE LS_DATASET_EXPORT
               SET OUTPUT_STTS_CD = 'FAILED'
             WHERE OUTPUT_SN = :exportSn
               AND OUTPUT_STTS_CD = 'PENDING'
               AND REG_DT < :cutoff
            """, nativeQuery = true)
    int claimStalePending(@Param("exportSn") Long exportSn, @Param("cutoff") LocalDateTime cutoff);

    /**
     * D-ISSUE-04(b) — <b>실패 export 회수 후보</b>(재시도 앵커 행) 목록.
     *
     * <p>승인 후 export 는 AFTER_COMMIT {@code @Async} 로 승인 트랜잭션 <b>밖</b>에서 돌기 때문에 실패해도
     * 승인이 롤백되지 않고, 지금까지는 <b>재시도 경로가 아예 없어</b> FAILED 레코드만 남고 데이터마트에
     * {@code OUTPUT_PATH_NM} 이 NULL 인 행이 영구히 남았다(실측: rawSn=13 프레임 11·라벨 35 인데 export
     * 실패 → 승인 게이트로도 못 잡는 유형). 이 쿼리가 주기 회수 잡의 대상 선정을 담당한다.
     *
     * <p>선정 규칙:
     * <ul>
     *   <li>영상별 <b>최신</b>(최대 OUTPUT_VER_NO) export 가 {@code FAILED} 일 것 — 이후 성공/부분 산출이
     *       있으면 이미 회복된 것이므로 제외. 진행 중(PENDING)이 최신이면 대상이 아니다(승인 경로 러너와의
     *       동시 산출 회피).</li>
     *   <li>마지막 활동({@code RTY_DT}, 없으면 {@code REG_DT})이 {@code :cutoff} 이전일 것 — 방금 실패했거나
     *       방금 재시도를 트리거한 건은 잠시 둔다(일시 장애 진정 대기 + 재시도 간 최소 간격).</li>
     *   <li>마지막 성공/부분 산출 이후 <b>누적 재시도 횟수({@code SUM(RTY_NMTM)}) &lt; :maxAttempts</b>.</li>
     * </ul>
     *
     * <h3>DEV_FIX(H7①) — 왜 "FAILED 행 수" 가 아니라 RTY_NMTM 합인가</h3>
     * 재시도가 항상 FAILED 행을 만들지는 않는다: 프레임/활성 메타 부재(NO_INPUT)는 레코드를 INSERT 하지
     * 않고 early return 하고, 버전 채번 소진도 행이 없으며, {@code AsyncDatasetExportRunner} 는 예외를
     * 삼킨다. 그래서 구 카운트는 이 유형에서 <b>영원히 고정</b>돼 {@code max-attempts} 가 무효였고 주기마다
     * 무한 재시도됐다. 이제 클레임 시점에 무조건 증가하는 {@code RTY_NMTM} 을 합산하므로, 산출이 어떤
     * 방식으로 실패하든 상한이 실제로 걸린다. 재시도가 새 FAILED 행을 만들어 앵커가 바뀌어도, 합산 범위가
     * "마지막 성공 이후 전 행"이라 누적치가 유지된다(리셋되지 않는다).
     *
     * <p>파라미터 바인딩만 사용(CWE-89 표면 없음).
     *
     * <p>⚠ <b>SELECT 절 순서는 계약이다</b> — 호출자({@code DatasetExportFailureRecoverer})가
     * {@code anchor[0]}=exportSn · {@code anchor[1]}=rawSn 으로 <b>위치 인덱스</b>로 읽는다. 순서를
     * 바꾸면 컴파일 에러 없이 두 식별자가 뒤바뀐다(V173 컬럼 rename 시에도 순서를 그대로 보존했다).
     *
     * @return {@code [exportSn, dataRawSn]} 배열 목록 (오래된 실패 우선, 최대 {@code limit} 건).
     *         실제 재시도 실행 전 {@link #claimForRetry} 로 <b>원자 클레임에 성공한 건만</b> 트리거해야 한다.
     */
    @Query(value = """
            SELECT e.OUTPUT_SN, e.DATA_RAW_SN
              FROM LS_DATASET_EXPORT e
              JOIN (SELECT DATA_RAW_SN, MAX(OUTPUT_VER_NO) AS MAX_VER
                      FROM LS_DATASET_EXPORT
                     GROUP BY DATA_RAW_SN) m
                ON m.DATA_RAW_SN = e.DATA_RAW_SN
               AND m.MAX_VER = e.OUTPUT_VER_NO
             WHERE e.OUTPUT_STTS_CD = 'FAILED'
               AND COALESCE(e.RTY_DT, e.REG_DT) < :cutoff
               AND (SELECT COALESCE(SUM(f.RTY_NMTM), 0)
                      FROM LS_DATASET_EXPORT f
                     WHERE f.DATA_RAW_SN = e.DATA_RAW_SN
                       AND f.OUTPUT_VER_NO > COALESCE((SELECT MAX(g.OUTPUT_VER_NO)
                                                         FROM LS_DATASET_EXPORT g
                                                        WHERE g.DATA_RAW_SN = e.DATA_RAW_SN
                                                          AND g.OUTPUT_STTS_CD IN ('SUCCEEDED', 'PARTIAL')), 0)
                   ) < :maxAttempts
             ORDER BY COALESCE(e.RTY_DT, e.REG_DT) ASC
             LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findRetryableFailedAnchors(@Param("cutoff") LocalDateTime cutoff,
                                              @Param("maxAttempts") int maxAttempts,
                                              @Param("limit") int limit);

    /**
     * D-ISSUE-04(b) DEV_FIX(H7①/H7③) — 재시도 <b>원자 클레임</b>. 성공(1행)했을 때만 재산출을 트리거한다.
     *
     * <p>한 문장이 두 가지를 동시에 한다:
     * <ol>
     *   <li><b>시도 이력 기록</b>: {@code RTY_NMTM +1}, {@code RTY_DT = now}. 이후 산출이 FAILED 행을
     *       남기든(일반 실패) 아무 행도 남기지 않든(NO_INPUT·채번 소진·예외 삼킴) 시도는 반드시 남는다.</li>
     *   <li><b>중복 산출 차단</b>: {@code RTY_DT} 가 {@code :claimCutoff} 이후면 이미 누군가 집어간 것이므로
     *       0행. PostgreSQL 은 UPDATE 시 행 락을 얻은 뒤 <b>갱신된 최신 버전으로 WHERE 를 재평가</b>하므로,
     *       두 노드가 같은 앵커를 동시에 노려도 한쪽만 1행을 얻는다. 이는 Quartz {@code isClustered}
     *       설정(현재 기본 false)에 의존하지 않는 DB 레벨 보장이다.</li>
     * </ol>
     *
     * <p>{@code OUTPUT_STTS_CD = 'FAILED'} 조건은 그 사이 다른 경로(승인 재산출 등)가 상태를 바꿨으면
     * 클레임을 포기하게 한다(fail-closed). 파라미터 바인딩만 사용(CWE-89 표면 없음).
     *
     * @param exportSn    클레임 대상 앵커 행(최신 FAILED)
     * @param maxAttempts 마지막 성공 이후 누적 시도 상한 — 앵커 단독 카운트로도 한 번 더 방어
     * @param claimCutoff 이 시각 이후에 클레임된 행은 재클레임하지 않는다(= now - 재시도 유예)
     * @param now         클레임 시각
     * @return 클레임에 성공한 행 수(0 또는 1)
     */
    @Modifying
    @Query(value = """
            UPDATE LS_DATASET_EXPORT
               SET RTY_NMTM = RTY_NMTM + 1,
                   RTY_DT = :now
             WHERE OUTPUT_SN = :exportSn
               AND OUTPUT_STTS_CD = 'FAILED'
               AND RTY_NMTM < :maxAttempts
               AND (RTY_DT IS NULL OR RTY_DT < :claimCutoff)
            """, nativeQuery = true)
    int claimForRetry(@Param("exportSn") Long exportSn,
                      @Param("maxAttempts") int maxAttempts,
                      @Param("claimCutoff") LocalDateTime claimCutoff,
                      @Param("now") LocalDateTime now);
}
