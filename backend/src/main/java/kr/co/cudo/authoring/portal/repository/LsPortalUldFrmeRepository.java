package kr.co.cudo.authoring.portal.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.portal.entity.LsPortalUldFrme;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 포털 업로드 프레임 저장소. 프레임 자체에는 소유자 컬럼이 없으므로, 소유자 검증은
 * 상위 업로드({@code LsPortalUldRepository.findByUldSnAndPortalUserNo})로 스코프한 뒤
 * {@code uldSn} 경유로 조회한다. 파생 쿼리(파라미터 바인딩)만 사용.
 */
@ControlRepo
public interface LsPortalUldFrmeRepository extends JpaRepository<LsPortalUldFrme, Long> {

    Optional<LsPortalUldFrme> findByUldFrmeSn(Long uldFrmeSn);

    /** 업로드 소유 검증 후 uldSn 경유 단건 조회(프레임이 해당 업로드 소속인지 확인). */
    Optional<LsPortalUldFrme> findByUldFrmeSnAndUldSn(Long uldFrmeSn, Long uldSn);

    /** 업로드의 프레임 목록(순번 오름차순, 페이징). */
    Page<LsPortalUldFrme> findByUldSnOrderByFrmeNoAsc(Long uldSn, Pageable pageable);

    /** 업로드의 전체 프레임(순번 오름차순) — export/처리 파이프라인용. */
    List<LsPortalUldFrme> findByUldSnOrderByFrmeNoAsc(Long uldSn);
}
