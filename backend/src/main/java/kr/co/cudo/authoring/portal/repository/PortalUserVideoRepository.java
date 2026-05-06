package kr.co.cudo.authoring.portal.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.portal.entity.LsPortalUserVideo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Phase 11 — 포털 영상 메타 Repository.
 *
 * 보안 (CWE-639 IDOR):
 *  - findByPortalVideoSnAndPortalUserNo : 본인 사용자 영상만 반환 (Optional.empty 시 403/404 분기).
 *  - findByPortalUserNo : 본인 업로드 목록만 페이징 조회.
 *  - findById 직접 호출 금지 (다른 사용자 영상 노출 위험).
 */
@ControlRepo
public interface PortalUserVideoRepository extends JpaRepository<LsPortalUserVideo, Long> {

    Page<LsPortalUserVideo> findByPortalUserNoOrderByRegisteredAtDesc(String portalUserNo, Pageable pageable);

    Optional<LsPortalUserVideo> findByPortalVideoSnAndPortalUserNo(Long portalVideoSn, String portalUserNo);
}
