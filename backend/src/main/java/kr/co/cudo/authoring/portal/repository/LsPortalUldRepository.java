package kr.co.cudo.authoring.portal.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.portal.entity.LsPortalUld;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 포털 업로드 마스터 리포지토리 (control DB).
 * <p>
 * 소유자 스코프 파생 쿼리만 노출 — 타 사용자 자산 접근(IDOR) 차단. 문자열 연결/자유양식
 * 쿼리 없이 파라미터 바인딩 파생 메서드만 사용한다.
 */
@ControlRepo
public interface LsPortalUldRepository extends JpaRepository<LsPortalUld, Long> {

    /** 소유자 검증 겸 단건 조회 — uldSn 만으로 접근 금지. */
    Optional<LsPortalUld> findByUldSnAndPortalUserNo(Long uldSn, String portalUserNo);

    /** 소유자 업로드 목록(페이징). */
    Page<LsPortalUld> findAllByPortalUserNo(String portalUserNo, Pageable pageable);

    /** 소유자 업로드 목록 + 타입 필터(페이징). */
    Page<LsPortalUld> findAllByPortalUserNoAndUldTypeCd(
            String portalUserNo, String uldTypeCd, Pageable pageable);

    // ===== 내부 파이프라인 전용(프레임 추출 러너/스윕 잡) — 사용자 요청 진입점에서 직접 사용 금지 =====

    /**
     * UPLOADED → PROCESSING 원자 전이(러너 진입 시점). UPLOADED 인 행만 전이한다.
     *
     * @return 전이된 행 수(1 이면 본 러너가 처리 책임, 0 이면 삭제됐거나 이미 처리 중)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE LsPortalUld u SET u.uldSttsCd = 'PROCESSING', u.mdfcnDt = :now "
            + "WHERE u.uldSn = :uldSn AND u.uldSttsCd = 'UPLOADED'")
    int transitionToProcessing(@Param("uldSn") Long uldSn, @Param("now") LocalDateTime now);

    /**
     * PROCESSING → READY 원자 전이(추출 완료 시점). PROCESSING 인 행만 전이한다.
     *
     * <p>스윕 잡이 고착 판정으로 이미 FAILED 시킨 자산을 완료 커밋이 READY 로 되살리는 부활을
     * 차단한다(adversarial #1). affectedRows==0 이면 러너는 자신이 쓴 프레임 파일을 정리하고 종료한다.
     *
     * @return 전이된 행 수(1 이면 완료 반영, 0 이면 이미 FAILED/삭제 — 부활 금지)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE LsPortalUld u SET u.uldSttsCd = 'READY', u.vdoLenSec = :vdoLenSec, u.fps = :fps, "
            + "u.frmeCnt = :frmeCnt, u.failRsnCn = null, u.mdfcnDt = :now "
            + "WHERE u.uldSn = :uldSn AND u.uldSttsCd = 'PROCESSING'")
    int transitionToReady(@Param("uldSn") Long uldSn,
                          @Param("vdoLenSec") Double vdoLenSec,
                          @Param("fps") Double fps,
                          @Param("frmeCnt") Integer frmeCnt,
                          @Param("now") LocalDateTime now);

    /**
     * 지정 상태 집합에 속할 때만 FAILED 전이(조건부, 멱등). READY/이미 FAILED 인 행은 덮지 않는다.
     *
     * <p>러너 실패 처리(statuses=[PROCESSING])와 스윕 고착 처리(statuses=[UPLOADED,PROCESSING])가
     * 공유한다. READY 를 FAILED 로 덮는 역방향 전이(adversarial #1 역케이스)를 차단한다.
     *
     * @return 전이된 행 수(1 이면 실패 반영, 0 이면 대상 상태 아님)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE LsPortalUld u SET u.uldSttsCd = 'FAILED', u.failRsnCn = :reason, u.mdfcnDt = :now "
            + "WHERE u.uldSn = :uldSn AND u.uldSttsCd IN :statuses")
    int failIfInStatus(@Param("uldSn") Long uldSn,
                       @Param("reason") String reason,
                       @Param("statuses") List<String> statuses,
                       @Param("now") LocalDateTime now);

    /**
     * PROCESSING 진행 중 하트비트 — mdfcnDt 만 갱신(adversarial #2). 장시간 정상 추출이 스윕의
     * {@code findStuck} cutoff 대상에서 자연 제외되도록 러너가 N프레임마다 호출한다.
     *
     * @return 갱신된 행 수(1 이면 여전히 PROCESSING, 0 이면 삭제/전이됨 — 러너 중단 신호)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE LsPortalUld u SET u.mdfcnDt = :now "
            + "WHERE u.uldSn = :uldSn AND u.uldSttsCd = 'PROCESSING'")
    int touchProcessing(@Param("uldSn") Long uldSn, @Param("now") LocalDateTime now);

    /**
     * 고착 자산 스캔 — 주어진 상태로 {@code cutoff} 이전부터 갱신이 멈춘 업로드(영구 로딩 방지).
     * 러너 하트비트({@link #touchProcessing})가 mdfcnDt 를 갱신하므로 진행 중 정상 추출은 제외된다.
     */
    @Query("SELECT u FROM LsPortalUld u WHERE u.uldSttsCd IN :statuses AND u.mdfcnDt < :cutoff")
    List<LsPortalUld> findStuck(@Param("statuses") List<String> statuses,
                                @Param("cutoff") LocalDateTime cutoff);

    // ===== 보존기간 만료 자동 삭제 배치 전용 — 사용자 요청 진입점에서 직접 사용 금지 =====

    /**
     * 보존기간이 만료된 <b>READY</b> 자산 후보. @design DFEAT-055, AC-036, AC-037
     *
     * <p>기준점은 "자산 {@code REG_DT} 와 그 자산 라벨 {@code MAX(REG_DT)} 중 늦은 쪽"이며, 그것이
     * cutoff 보다 이르다는 조건을 <b>두 술어의 곱</b>으로 표현한다 — 등록일이 cutoff 이전이고,
     * cutoff 이후에 저장된 라벨이 하나도 없다. 작업 중이면 라벨 저장이 기준점을 계속 밀어내므로
     * 자동으로 후보에서 빠진다.
     *
     * <p>★ {@code 'READY'} 는 <b>리터럴</b>이다(AC-036). {@code PROCESSING} 자산을 지우면 프레임 추출
     * 러너와 경쟁해 파일·DB 불일치가 난다 — 상태를 파라미터로 받으면 호출자 실수 한 번에 그 사고가 난다.
     */
    @Query("select u from LsPortalUld u where u.uldSttsCd = 'READY' and u.regDt < :cutoff "
            + "and not exists (select l.uldLblSn from LsPortalUldLbl l "
            + "                where l.uldSn = u.uldSn and l.regDt >= :cutoff)")
    List<LsPortalUld> findExpiredReady(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 보존기간이 만료된 <b>FAILED</b> 자산 후보. @design DFEAT-055, AC-036, AC-037
     *
     * <p>기준점은 FAILED 전이 시각({@code MDFCN_DT})이고 보존기간은 READY 축과 <b>다른 설정 키</b>다 —
     * 두 축은 같은 시각에 등록됐어도 독립적으로 판정된다(AC-037 and_examples[1]).
     * {@code 'FAILED'} 리터럴 고정 이유는 {@link #findExpiredReady} 와 같다.
     */
    @Query("select u from LsPortalUld u where u.uldSttsCd = 'FAILED' and u.mdfcnDt < :cutoff")
    List<LsPortalUld> findExpiredFailed(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 만료 READY 자산을 <b>조건부</b> 삭제한다(FRME/LBL 은 DB FK {@code ON DELETE CASCADE}). @design AC-036
     *
     * <p>★ 후보 조회에서 이미 판정했더라도 <b>삭제문 자체에 상태·만료 조건을 다시 건다</b> —
     * 조회~삭제 사이에 라벨이 새로 저장되면 더는 만료가 아니고, 그 창에서 지우면 방금 한 작업이
     * 비가역으로 사라진다. 상태 리터럴을 다시 거는 것은 {@code PROCESSING} 유입을 막는 마지막 방벽이다.
     *
     * @return 삭제된 행 수(0 이면 타 노드 선점 또는 조건 해제 — 이 노드는 아무것도 지우지 않았다)
     */
    @Modifying(clearAutomatically = true)
    @Query("delete from LsPortalUld u where u.uldSn = :uldSn and u.uldSttsCd = 'READY' "
            + "and u.regDt < :cutoff "
            + "and not exists (select l.uldLblSn from LsPortalUldLbl l "
            + "                where l.uldSn = u.uldSn and l.regDt >= :cutoff)")
    int deleteExpiredReady(@Param("uldSn") Long uldSn, @Param("cutoff") LocalDateTime cutoff);

    /**
     * 만료 FAILED 자산을 <b>조건부</b> 삭제한다. 조건 재확인 이유는 {@link #deleteExpiredReady} 와 같다.
     * @design AC-036, AC-037
     *
     * @return 삭제된 행 수(0 이면 타 노드 선점 또는 조건 해제)
     */
    @Modifying(clearAutomatically = true)
    @Query("delete from LsPortalUld u where u.uldSn = :uldSn and u.uldSttsCd = 'FAILED' "
            + "and u.mdfcnDt < :cutoff")
    int deleteExpiredFailed(@Param("uldSn") Long uldSn, @Param("cutoff") LocalDateTime cutoff);

    /** 소유자 목록 조회(명시적 최신순) — Phase 1 IT 호환용. */
    Page<LsPortalUld> findByPortalUserNoOrderByRegDtDesc(String portalUserNo, Pageable pageable);

    /** 소유자 + 업로드 유형(IMAGE/VIDEO) 필터 목록 조회(명시적 최신순) — Phase 1 IT 호환용. */
    Page<LsPortalUld> findByPortalUserNoAndUldTypeCdOrderByRegDtDesc(
            String portalUserNo, String uldTypeCd, Pageable pageable);
}
