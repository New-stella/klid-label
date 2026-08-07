package kr.co.cudo.authoring.controlnotify.debounce;

import jakarta.persistence.LockModeType;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 관제 수정 통지 디바운스 누적 Repository (Phase 9-C).
 *
 * <p>{@code klid_at}(Control) DB 의 {@code LS_MON_NOTI_ACML} 에 매핑된다. 2노드 Active-Active 에서
 * 같은 윈도우가 두 번 flush 되지 않도록 클레임은 조건부 원자 UPDATE({@link #claimForFlush})로 수행한다
 * ({@code LsBatRtyWtngRepository#claimAtomically}·{@code LsDatasetExportRepository#claimStalePending} 동일 패턴).
 */
@ControlRepo
public interface LsMonNotiAcmlRepository extends JpaRepository<LsMonNotiAcml, Long> {

    /** 영상별 윈도우 행 — 진단·테스트 정리용(전체 조회 금지 규칙에 따라 키 조건을 반드시 건다). */
    List<LsMonNotiAcml> findByRawSn(Long rawSn);

    /**
     * 이 영상에 열린 축적 윈도우(PENDING 또는 임차 중인 FLUSHING)가 존재하는가.
     *
     * <p>Phase 7a-2b — 재승인 폴백 판정 전용({@code ReviewService#approve}). 상태 무관 존재 확인이라
     * {@code STTS_CD} 조건을 걸지 않는다 — FLUSHING(다른 노드가 막 클레임한 상태)도 "축적분이 있다"는
     * 사실은 동일하다.
     */
    boolean existsByRawSn(Long rawSn);

    /**
     * CWE-362 — 두 노드가 동시에 같은 영상의 <b>첫 수정</b>을 축적할 때의 find-or-create 경쟁을 예외 없이
     * 흡수하는 원자 upsert. 부분 유니크 인덱스({@code UK_LMNA_RAW_PENDING})를 추론 대상으로 삼아
     * "열린 윈도우가 없을 때만" INSERT 한다.
     *
     * <p>{@code REG_DT}/{@code MDFCN_DT} 를 DB 기본값({@code CURRENT_TIMESTAMP})이 아니라 애플리케이션
     * 시각으로 바인딩한다 — 만료·임차 판정을 모두 JVM 시계로 하므로 DB 세션 시간대/클럭 차이가 섞이면
     * 윈도우가 즉시 만료되거나 영영 만료되지 않는다.
     *
     * @return 영향 행수 (1 = 새 윈도우 개시, 0 = 이미 열린 윈도우 존재)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO LS_MON_NOTI_ACML
                   (RAW_SN, STTS_CD, EXPORT_RPRCS_YN, CHG_DTL_CN, REG_DT, MDFCN_DT)
            VALUES (:rawSn, 'PENDING', 'N', '{}', :now, :now)
            ON CONFLICT (RAW_SN) WHERE STTS_CD = 'PENDING' DO NOTHING
            """, nativeQuery = true)
    int insertPendingIfAbsent(@Param("rawSn") Long rawSn, @Param("now") LocalDateTime now);

    /**
     * 열린 윈도우를 {@link LockModeType#PESSIMISTIC_WRITE}(SELECT … FOR UPDATE)로 잠금 조회한다.
     *
     * <p>축적은 read-modify-write(JSON 머지)라, 잠그지 않으면 같은 영상의 동시 수정 중 한쪽 변경이
     * lost update 로 사라진다(CWE-362).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM LsMonNotiAcml a WHERE a.rawSn = :rawSn AND a.sttsCd = 'PENDING'")
    Optional<LsMonNotiAcml> findPendingForUpdate(@Param("rawSn") Long rawSn);

    /**
     * flush 후보(앵커) — ①만료된 열린 윈도우 ②임차가 끊긴 {@code FLUSHING} 잔재(클레임 노드 사망).
     *
     * <p>②를 함께 훑는 것이 <b>축적분 유실 방지</b>의 핵심이다. 클레임 직후 노드가 죽으면 그 행은
     * FLUSHING 으로 남는데, 이 축이 없으면 아무도 집지 못해 통지·재생성이 영구 소실된다.
     *
     * <p>오래된 순 + {@code LIMIT} — 잔재가 대량으로 쌓여도 한 tick 이 무한정 길어지지 않는다
     * (무제한 조회 금지, OWASP API4). 파라미터 바인딩만 사용(CWE-89 표면 없음).
     *
     * <h3>Phase 7a-2 — 재검토 표시(REVLT_YN='Y')가 선 영상은 후보에서 제외한다(보류)</h3>
     * <p>{@code NOT EXISTS} 서브쿼리로 {@code LS_RAW_DATA_STATUS.REVLT_YN='Y'} 인 영상의 윈도우를 걸러낸다
     * — 윈도우는 <b>삭제되지 않고 그대로 남아</b> 계속 축적되며(유실 아님), 재승인으로 표시가 해제되면
     * (이미 {@code REG_DT} 가 만료 기준을 넘긴 지 오래이므로) <b>바로 다음 tick</b>에 후보로 잡혀 그 사이
     * 쌓인 변경 전부를 실어 flush 된다. 두 테이블 모두 같은 {@code klid_at}(Control) 스키마라 native
     * 조인에 추가 데이터소스 배선이 필요 없다. 판정은 <b>이 SELECT 시점의 최신값</b>이라 2노드 어느
     * 쪽이 tick 을 돌려도 같은 결과를 본다(공유 DB 단일 진실원).
     */
    @Query(value = """
            SELECT a.NOTI_ACML_SN
              FROM LS_MON_NOTI_ACML a
             WHERE ((a.STTS_CD = 'PENDING'  AND a.REG_DT   <= :windowCutoff)
                OR  (a.STTS_CD = 'FLUSHING' AND a.MDFCN_DT <= :leaseCutoff))
               AND NOT EXISTS (
                     SELECT 1 FROM LS_RAW_DATA_STATUS s
                      WHERE s.RAW_DATA_ID = a.RAW_SN AND s.REVLT_YN = 'Y'
                   )
             ORDER BY a.REG_DT ASC
             LIMIT :limit
            """, nativeQuery = true)
    List<Long> findFlushableAnchors(@Param("windowCutoff") LocalDateTime windowCutoff,
                                    @Param("leaseCutoff") LocalDateTime leaseCutoff,
                                    @Param("limit") int limit);

    /**
     * flush <b>원자 클레임</b> — 조건을 만족하는 행만 {@code FLUSHING} 으로 전이시키고 임차 시각을 갱신한다.
     *
     * <p>DB 가 동일 row 의 동시 UPDATE 를 직렬화하므로 정확히 한 노드만 영향 행수 1 을 받는다. 후보
     * 조회와 클레임 사이에 상태가 바뀌었으면(다른 노드가 이미 가져갔거나 새 수정이 들어와 임차가
     * 갱신됐으면) 0 행이 되어 이중 flush 가 발생하지 않는다.
     *
     * <h3>Phase 7a-2b — 재검토 표시(REVLT_YN='Y') 재확인은 UPDATE 문 자체에도 건다</h3>
     * <p>{@link #findFlushableAnchors} 의 후보 SELECT 는 표시가 없는 순간의 스냅샷일 뿐이다. 그 SELECT
     * 이후·이 UPDATE 이전 사이에 다른 트랜잭션이 표시를 세우면(검수 완료 영상을 사람이 막 수정), 후보에는
     * 이미 뽑혀 있는 채로 클레임만 통과해 <b>검수자가 아직 보지 않은 내용이 flush 된다</b> — 이 흐름
     * 전체가 막으려던 바로 그 상황이다. 그래서 클레임 자체를 조건부 원자 UPDATE 로 만들어, 표시가 선
     * 행은 여기서도 실패(영향 행수 0)하게 한다. {@code findFlushableAnchors} 와 동일한 {@code NOT EXISTS}
     * 서브쿼리이며 같은 {@code klid_at}(Control) 스키마라 추가 데이터소스 배선이 필요 없다.
     *
     * @return 영향 행수 (1 = 클레임 성공, 0 = 다른 노드가 이미 클레임했거나 그 사이 재검토 표시가 섰음)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE LS_MON_NOTI_ACML AS a
               SET STTS_CD = 'FLUSHING', MDFCN_DT = :now
             WHERE a.NOTI_ACML_SN = :notiAcmlSn
               AND ((a.STTS_CD = 'PENDING'  AND a.REG_DT   <= :windowCutoff)
                OR  (a.STTS_CD = 'FLUSHING' AND a.MDFCN_DT <= :leaseCutoff))
               AND NOT EXISTS (
                     SELECT 1 FROM LS_RAW_DATA_STATUS s
                      WHERE s.RAW_DATA_ID = a.RAW_SN AND s.REVLT_YN = 'Y'
                   )
            """, nativeQuery = true)
    int claimForFlush(@Param("notiAcmlSn") Long notiAcmlSn,
                      @Param("windowCutoff") LocalDateTime windowCutoff,
                      @Param("leaseCutoff") LocalDateTime leaseCutoff,
                      @Param("now") LocalDateTime now);

    /**
     * 발송 완료 윈도우 제거 — 클레임한 노드만 호출한다. {@code FLUSHING} 인 행만 지워, 혹시 남은
     * 열린 윈도우(발송 중 들어온 새 수정)를 실수로 지우지 않는다.
     *
     * @return 삭제 행수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM LsMonNotiAcml a WHERE a.notiAcmlSn = :notiAcmlSn AND a.sttsCd = 'FLUSHING'")
    int deleteFlushed(@Param("notiAcmlSn") Long notiAcmlSn);
}
