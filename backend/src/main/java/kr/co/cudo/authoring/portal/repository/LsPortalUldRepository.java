package kr.co.cudo.authoring.portal.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.portal.entity.LsPortalUld;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 포털 업로드 저장소. 모든 조회는 PORTAL_USER_NO 로 소유자 스코프하여 IDOR 를 방지한다.
 * 파생 쿼리(파라미터 바인딩)만 사용 — 문자열 연결 금지.
 */
@ControlRepo
public interface LsPortalUldRepository extends JpaRepository<LsPortalUld, Long> {

    /** 소유자 검증 포함 단건 조회(다른 사용자의 업로드는 반환하지 않음). */
    Optional<LsPortalUld> findByUldSnAndPortalUserNo(Long uldSn, String portalUserNo);

    /** 소유자 목록 조회(최신순). */
    Page<LsPortalUld> findByPortalUserNoOrderByRegDtDesc(String portalUserNo, Pageable pageable);

    /** 소유자 + 업로드 유형(IMAGE/VIDEO) 필터 목록 조회(최신순). */
    Page<LsPortalUld> findByPortalUserNoAndUldTypeCdOrderByRegDtDesc(
            String portalUserNo, String uldTypeCd, Pageable pageable);
}
