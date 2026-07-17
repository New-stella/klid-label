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

    /** 소유자 목록 조회(명시적 최신순) — Phase 1 IT 호환용. */
    Page<LsPortalUld> findByPortalUserNoOrderByRegDtDesc(String portalUserNo, Pageable pageable);

    /** 소유자 + 업로드 유형(IMAGE/VIDEO) 필터 목록 조회(명시적 최신순) — Phase 1 IT 호환용. */
    Page<LsPortalUld> findByPortalUserNoAndUldTypeCdOrderByRegDtDesc(
            String portalUserNo, String uldTypeCd, Pageable pageable);
}
