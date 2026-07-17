package kr.co.cudo.authoring.portal.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.portal.entity.LsPortalUldLbl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 포털 업로드 라벨 리포지토리 (control DB).
 * <p>
 * 모든 조회/삭제는 PORTAL_USER_NO 소유자 스코프로 강제(IDOR 차단). 파라미터 바인딩
 * 파생 쿼리만 사용한다. replace-all 저장 패턴을 위해 프레임 단위 삭제를 제공한다.
 */
@ControlRepo
public interface LsPortalUldLblRepository extends JpaRepository<LsPortalUldLbl, Long> {

    /** 프레임 라벨 목록(소유자 스코프). */
    List<LsPortalUldLbl> findAllByUldFrmeSnAndPortalUserNo(Long uldFrmeSn, String portalUserNo);

    /**
     * 프레임 라벨 전체 삭제(소유자 스코프) — replace-all 저장 대비. 삭제 건수 반환.
     * <p>Phase 1 DB리뷰 MED-4 — 파생 {@code deleteAllBy...}(select-then-remove N회) 대신 단일
     * 벌크 {@code @Modifying} DELETE 로 왕복을 1회로 줄인다. 소유자 스코프(PORTAL_USER_NO)를
     * WHERE 에 강제해 타 사용자 라벨 삭제를 차단한다(IDOR). {@code clearAutomatically} 로 벌크
     * 삭제 후 영속성 컨텍스트를 비워 뒤이은 saveAll 의 stale 참조를 방지한다.
     */
    @Modifying(clearAutomatically = true)
    @Query("delete from LsPortalUldLbl l "
            + "where l.uldFrmeSn = :uldFrmeSn and l.portalUserNo = :portalUserNo")
    int deleteAllByUldFrmeSnAndPortalUserNo(@Param("uldFrmeSn") Long uldFrmeSn,
                                            @Param("portalUserNo") String portalUserNo);

    /** 업로드 전체 라벨(소유자 스코프) — export/조회 대비. */
    List<LsPortalUldLbl> findAllByUldSnAndPortalUserNo(Long uldSn, String portalUserNo);

    // ===== Phase 1 IT 호환 파생 메서드 =====

    /** 프레임 단위 소유자 라벨 조회(최신순). */
    List<LsPortalUldLbl> findByUldFrmeSnAndPortalUserNoOrderByRegDtDesc(
            Long uldFrmeSn, String portalUserNo);

    /** 업로드 단위 소유자 라벨 조회(export 대비, 최신순). */
    List<LsPortalUldLbl> findByUldSnAndPortalUserNoOrderByRegDtDesc(
            Long uldSn, String portalUserNo);

    /** 프레임 단위 소유자 라벨 전체 삭제(replace-all 저장 전 정리용) — 삭제 건수 반환. */
    @Modifying
    @Transactional(value = "controlTransactionManager")
    long deleteByUldFrmeSnAndPortalUserNo(Long uldFrmeSn, String portalUserNo);
}
