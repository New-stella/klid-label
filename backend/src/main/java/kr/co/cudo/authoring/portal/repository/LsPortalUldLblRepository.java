package kr.co.cudo.authoring.portal.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.portal.entity.LsPortalUldLbl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 포털 업로드 라벨 저장소. 모든 조회/삭제는 PORTAL_USER_NO 로 소유자 스코프하여
 * IDOR 를 방지한다. 파생 쿼리(파라미터 바인딩)만 사용 — 문자열 연결 금지.
 */
@ControlRepo
public interface LsPortalUldLblRepository extends JpaRepository<LsPortalUldLbl, Long> {

    /** 프레임 단위 소유자 라벨 조회(최신순). */
    List<LsPortalUldLbl> findByUldFrmeSnAndPortalUserNoOrderByRegDtDesc(
            Long uldFrmeSn, String portalUserNo);

    /** 업로드 단위 소유자 라벨 조회(export 대비, 최신순). */
    List<LsPortalUldLbl> findByUldSnAndPortalUserNoOrderByRegDtDesc(
            Long uldSn, String portalUserNo);

    /** 프레임 단위 소유자 라벨 전체 삭제(replace-all 저장 전 정리용). */
    @Modifying
    @Transactional(value = "controlTransactionManager")
    long deleteByUldFrmeSnAndPortalUserNo(Long uldFrmeSn, String portalUserNo);
}
