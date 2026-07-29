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
     */
    @Query(value = """
            SELECT a.NOTI_ACML_SN
              FROM LS_MON_NOTI_ACML a
             WHERE (a.STTS_CD = 'PENDING'  AND a.REG_DT   <= :windowCutoff)
                OR (a.STTS_CD = 'FLUSHING' AND a.MDFCN_DT <= :leaseCutoff)
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
     * @return 영향 행수 (1 = 클레임 성공, 0 = 다른 노드가 이미 클레임)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE LsMonNotiAcml a SET a.sttsCd = 'FLUSHING', a.mdfcnDt = :now "
            + "WHERE a.notiAcmlSn = :notiAcmlSn AND ("
            + "  (a.sttsCd = 'PENDING'  AND a.regDt   <= :windowCutoff)"
            + "  OR (a.sttsCd = 'FLUSHING' AND a.mdfcnDt <= :leaseCutoff))")
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
