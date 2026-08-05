package kr.co.cudo.authoring.label.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.label.entity.LsDeidentReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

@ControlRepo
public interface LsDeidentReportRepository extends JpaRepository<LsDeidentReport, Long> {

    /**
     * 신고 해소 <b>원자 클레임</b> (CWE-362, V171) — {@code OPEN → RESOLVED} 조건부 UPDATE.
     *
     * <h3>왜 필요한가 (2노드 Active-Active)</h3>
     * <p>구 {@code resolveManually} 는 {@code findById} → 상태 문자열 비교 → 엔티티 전이의
     * <b>read-then-write</b> 라, 두 노드가 동시에 같은 신고를 해소하면 <b>둘 다 OPEN 을 관측</b>해 통과하고
     * 재개(마킹 되감기 / 프레임 재추출)가 2회 기동된다. 프레임 재추출은 ffmpeg 를 프레임 수만큼 도는
     * 무거운 작업이라 중복 실행이 그대로 자원 낭비 + 파일 동시 쓰기 경합이 된다.
     *
     * <p>따라서 판정과 전이를 <b>단일 조건부 UPDATE</b>(check-and-set)로 통합하고, 영향행수 1 을 받은
     * <b>클레임 성공자만</b> 재개 이벤트를 발행한다. 선례:
     * {@code BatchTransitionService#tryClaimReprocessFromFailed}.
     *
     * <p>{@code flushAutomatically=true} — 같은 트랜잭션에서 로드된 엔티티의 미반영 변경을 먼저 flush 해
     * UPDATE 가 최신 상태 위에서 평가되게 한다. {@code clearAutomatically} 는 쓰지 않는다: 호출자는 이
     * UPDATE 이후 엔티티의 상태 필드를 다시 읽지 않으며(이미 읽어둔 rawSn·단계만 사용), 컨텍스트를 비우면
     * 같은 트랜잭션의 다른 관리 엔티티(RAW 등)까지 detach 되어 dirty checking 이 깨진다.
     *
     * @return 영향행수 — {@code 1} 이면 이번 호출이 해소 권한을 획득, {@code 0} 이면 이미 다른 주체가 처리
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE LsDeidentReport r
               SET r.reportSttsCd = :toStatus,
                   r.resolvedDt   = :now,
                   r.mdfcnDt      = :now
             WHERE r.deidentReportSn = :rprtSn
               AND r.reportSttsCd    = :fromStatus
            """)
    int claimResolve(@Param("rprtSn") Long rprtSn,
                     @Param("fromStatus") String fromStatus,
                     @Param("toStatus") String toStatus,
                     @Param("now") java.time.LocalDateTime now);

    /**
     * 신고 상태(REPORT_STTS_CD) 기준 페이징 조회 (G-1 — REVIEWER 신고 관리 목록).
     * 정렬은 caller 가 Pageable 로 지정 (기본 reportDt DESC).
     */
    Page<LsDeidentReport> findByReportSttsCd(String reportSttsCd, Pageable pageable);

    /**
     * 사용자 신고 REPORT_STTS_CD 기준 조회 (Phase 3 - OPEN 신고 일괄 RESOLVED 전이용).
     */
    List<LsDeidentReport> findAllByDataRawSnAndReportSttsCd(Long rawSn, String reportSttsCd);

    /**
     * 영상별 사용자 신고 이력 (REPORT_DT DESC 정렬). FE 신고 이력 표시용.
     */
    List<LsDeidentReport> findAllByDataRawSnOrderByReportDtDesc(Long rawSn);

    // ============================================================
    // 기존 호출자 호환 (테스트가 사용하던 별칭)
    // ============================================================

    default List<LsDeidentReport> findAllByRawSnOrderByRprtDtDesc(Long rawSn) {
        return findAllByDataRawSnOrderByReportDtDesc(rawSn);
    }
}
