package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsDeidentProcLogRepository extends JpaRepository<LsDeidentProcLog, Long> {

    List<LsDeidentProcLog> findAllByDataRawSnOrderByReqDtDesc(Long rawSn);

    /**
     * 영상 목록(Phase 2) — rawSn 집합에 대해 각 DATA_RAW_SN 별 최신 procLog 1행을 단일 IN 쿼리로 조회한다
     * (N+1 회피). "최신"은 PROC_LOG_SN(IDENTITY 증가) 최대값 기준 — 재비식별 등으로 다중행이면 가장 최근
     * 삽입된 1행만 반환한다. rawSns 가 비면 빈 리스트를 반환해 불필요한 쿼리를 막는다.
     */
    default List<LsDeidentProcLog> findLatestByDataRawSnIn(Collection<Long> rawSns) {
        if (rawSns == null || rawSns.isEmpty()) {
            return List.of();
        }
        return findLatestByDataRawSnInInternal(rawSns);
    }

    @Query("SELECT p FROM LsDeidentProcLog p WHERE p.dataRawSn IN :rawSns "
            + "AND p.procLogSn IN (SELECT MAX(p2.procLogSn) FROM LsDeidentProcLog p2 "
            + "WHERE p2.dataRawSn IN :rawSns GROUP BY p2.dataRawSn)")
    List<LsDeidentProcLog> findLatestByDataRawSnInInternal(@Param("rawSns") Collection<Long> rawSns);

    /**
     * Phase 2 보강 (DEV_FIX H-3) — 동일 externalJobId 재인계 시 upsert 대상 행 조회.
     */
    Optional<LsDeidentProcLog> findByExternalJobId(String externalJobId);

    // 5A carry-over(Phase 5C) — 데이터마트 뷰 V_COMPLETED_VIDEO(비식별 경로 lateral join)는
    //   ORDER BY REQ_DT DESC, PROC_LOG_SN DESC 로 최신 성공 1건을 고른다. 앱 조회가 REQ_DT DESC 만이면
    //   동일 REQ_DT(재비식별 등) 시 뷰와 다른 행을 골라 비식별 경로가 불일치할 수 있다. 2차 키(PROC_LOG_SN
    //   DESC, IDENTITY 증가라 결정적)를 추가해 뷰와 정확히 같은 행을 선택하도록 정렬을 정합시킨다.
    @Query("SELECT p FROM LsDeidentProcLog p WHERE p.dataRawSn = :rawSn AND p.procSttsCd = '" + LsDeidentProcLog.SUCCEEDED + "' ORDER BY p.reqDt DESC, p.procLogSn DESC")
    List<LsDeidentProcLog> findSuccessHistory(@Param("rawSn") Long rawSn, PageRequest pageable);

    /**
     * Phase 2 (UC018) — KPST 폴링 잡 대상 조회: WAITING/POLLING 상태의 위탁 건.
     *
     * <p><b>상한 필수</b>(B-ISSUE-82): 폴링 대상은 외부 HTTP 호출을 유발하므로 무제한 조회는 한 틱이
     * 무한정 길어지는 자원 소진 경로다(OWASP API4). 호출자는 {@code Pageable} 로 틱당 처리 상한과
     * 정렬(오래 대기한 건 우선)을 반드시 지정한다 — 무제한 오버로드는 두지 않는다.
     */
    List<LsDeidentProcLog> findByPollSttsCdIn(List<String> pollSttsCds, org.springframework.data.domain.Pageable pageable);

    /**
     * B-ISSUE-82 — 폴링 대상 <b>원자 클레임</b>(리스 방식). 이 UPDATE 로 1행을 얻은 노드만 폴링한다.
     *
     * <p><b>왜 필요한가</b>: 배포는 2노드 Active-Active 인데 Quartz 클러스터링({@code isClustered})이
     * 기본 꺼져 있어 같은 트리거가 양 노드에서 발화한다. 클레임이 없으면 두 노드가 같은 위탁 건을 동시에
     * 폴링해 외부 호출·시도 카운트·완료 처리가 중복된다.
     *
     * <p><b>왜 조건부 UPDATE 인가</b>({@code FOR UPDATE SKIP LOCKED} 대신): PostgreSQL 은 UPDATE 시
     * 행 락을 얻은 뒤 <b>갱신된 최신 버전으로 WHERE 를 재평가</b>하므로 한쪽만 1행을 얻는다
     * ({@code LsDataAugJobRepository#claimExpired} 와 동일한 이 리포의 정본 패턴). {@code SKIP LOCKED}
     * 는 <b>트랜잭션이 열려 있는 동안만</b> 배타성이 유지되는데, 폴링은 외부 HTTP·파일 I/O 를 포함해
     * 트랜잭션 밖에서 수행되므로 락으로는 보호 구간을 덮을 수 없다. 반면 리스는 커밋된 시각
     * ({@code POLL_LAST_DT})으로 배타성을 표현해 트랜잭션 경계와 무관하게 동작한다.
     *
     * <p><b>리스</b>: {@code POLL_LAST_DT} 가 {@code leaseCutoff} 이후면 다른 노드가 방금 집어간 것이므로
     * 0행. 호출자는 리스 길이를 폴링 주기보다 짧게 잡아, <b>단일 노드에서는 매 틱 그대로 다시 클레임</b>
     * 되도록 한다(기존 폴링 주기 불변). 크래시로 리스가 방치돼도 만료되면 자동 회수되므로 stuck 상태를
     * 만들지 않는다(별도 잠금 컬럼·회수 잡 불요).
     *
     * <p>{@code POLL_STTS_CD IN ('WAITING','POLLING')} 은 fail-closed 가드다 — 그 사이 완료
     * (DOWNLOADED)/종결(FAILED)됐으면 클레임하지 않는다. 파라미터 바인딩만 사용(CWE-89 표면 없음).
     *
     * @return 클레임에 성공한 행 수(0 또는 1)
     */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE LS_DEIDENT_PROC_LOG
               SET POLL_LAST_DT = :now,
                   MDFCN_DT = :now
             WHERE PROC_LOG_SN = :procLogSn
               AND POLL_STTS_CD IN ('WAITING', 'POLLING')
               AND (POLL_LAST_DT IS NULL OR POLL_LAST_DT < :leaseCutoff)
            """, nativeQuery = true)
    int claimForPoll(@Param("procLogSn") Long procLogSn,
                     @Param("leaseCutoff") java.time.LocalDateTime leaseCutoff,
                     @Param("now") java.time.LocalDateTime now);

    /**
     * B-ISSUE-82 — 다운로드 완료 처리의 <b>원자 클레임 겸 멱등 가드</b>. 1행을 얻은 호출만 완료 후처리
     * (프레임 attach·Y 전이·락 해제·알림)를 수행한다.
     *
     * <p>완료 전이 자체를 클레임으로 삼기 때문에 두 가지가 동시에 성립한다:
     * <ol>
     *   <li><b>상호배제</b> — 이 UPDATE 가 잡은 행 락은 완료 트랜잭션이 커밋될 때까지 유지되므로, 같은
     *       procLog 를 노린 다른 노드는 커밋을 기다렸다가 갱신된 값으로 WHERE 를 재평가해 0행을 받는다.
     *       즉 프레임 재추출이 두 번 실행될 창이 없다.</li>
     *   <li><b>멱등</b> — 이미 {@code DOWNLOADED} 인 건에 대한 재호출은 0행이라 후처리를 건너뛴다.</li>
     * </ol>
     *
     * <p>완료 후처리가 실패해 트랜잭션이 롤백되면 이 클레임도 함께 롤백되어 재폴링 대상으로 남는다
     * (fail-safe — 폴링 오케스트레이터의 terminal 종결 경로는 그대로 동작).
     * 파라미터 바인딩만 사용(CWE-89 표면 없음).
     *
     * @return 클레임에 성공한 행 수(0 또는 1)
     */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE LS_DEIDENT_PROC_LOG
               SET POLL_STTS_CD = 'DOWNLOADED',
                   PROC_STTS_CD = 'SUCCEEDED',
                   DE_IDNTF_FILE_PATH_NM = :deidFilePath,
                   RSPNS_DT = :now,
                   MDFCN_DT = :now
             WHERE PROC_LOG_SN = :procLogSn
               AND POLL_STTS_CD IN ('WAITING', 'POLLING')
            """, nativeQuery = true)
    int claimDownloadCompletion(@Param("procLogSn") Long procLogSn,
                                @Param("deidFilePath") String deidFilePath,
                                @Param("now") java.time.LocalDateTime now);

    default Optional<LsDeidentProcLog> findLatestSuccessByDataRawSn(Long rawSn) {
        if (rawSn == null) return Optional.empty();
        List<LsDeidentProcLog> hits = findSuccessHistory(rawSn, PageRequest.of(0, 1));
        return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
    }

    /**
     * 해상도 파생 백필 전용 — 성공 이력의 비식별 결과 경로를 새 비식별 저장소 경로로 교체한다
     * (E-ISSUE-21 파일 이관 후 DB 반영). 파생 RAW 에만 적용되며 파일 복사·검증 성공 이후 호출된다.
     *
     * <p><b>M-7</b>: <b>최신 SUCCEEDED 이력 1건만</b> 갱신한다. 구 구현은
     * {@code WHERE DATA_RAW_SN=? AND PROC_STTS_CD='SUCCEEDED'} 라 재비식별로 성공 이력이 2건 이상이면
     * 과거 이력까지 새 경로로 덮어써 이력이 소실됐다. 조회측
     * ({@link #findLatestSuccessByDataRawSn})과 동일하게 {@code REQ_DT DESC} 최신 1건을 타깃한다.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = """
            UPDATE LS_DEIDENT_PROC_LOG
               SET DE_IDNTF_FILE_PATH_NM = :filePath
             WHERE PROC_LOG_SN = (
                    SELECT p.PROC_LOG_SN
                      FROM LS_DEIDENT_PROC_LOG p
                     WHERE p.DATA_RAW_SN = :rawSn
                       AND p.PROC_STTS_CD = 'SUCCEEDED'
                     ORDER BY p.REQ_DT DESC, p.PROC_LOG_SN DESC
                     LIMIT 1)
            """, nativeQuery = true)
    int updateSuccessDeidFilePath(@org.springframework.data.repository.query.Param("rawSn") Long rawSn,
                                  @org.springframework.data.repository.query.Param("filePath") String filePath);
}
