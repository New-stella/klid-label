package kr.co.cudo.authoring.portal.repository;

import jakarta.persistence.LockModeType;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.portal.entity.LsPortalUldFrme;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * V107 포털 업로드 프레임 리포지토리 (control DB).
 * <p>
 * 프레임 자체에는 소유자 컬럼이 없으므로, 서비스 진입점 조회는 반드시 부모 업로드(LS_PORTAL_ULD)의
 * PORTAL_USER_NO 를 조인 검증하는 {@code ...Owner...} 메서드만 사용한다. uldSn 은 추측 가능한
 * 시퀀스라 소유자 미검증 조회는 IDOR 전제를 붕괴시킨다. Aggregate 간 참조는 ID 로만 유지하고,
 * 소유권 검증은 JPQL {@code exists} 서브쿼리(파라미터 바인딩)로 수행한다.
 */
@ControlRepo
public interface LsPortalUldFrmeRepository extends JpaRepository<LsPortalUldFrme, Long> {

    /** 프레임 단건 + 소유자 스코프 검증 — 부모 업로드가 해당 사용자 소유일 때만 반환. */
    @Query("select f from LsPortalUldFrme f "
            + "where f.uldFrmeSn = :uldFrmeSn "
            + "and exists (select 1 from LsPortalUld u "
            + "            where u.uldSn = f.uldSn and u.portalUserNo = :portalUserNo)")
    Optional<LsPortalUldFrme> findByUldFrmeSnAndOwner(@Param("uldFrmeSn") Long uldFrmeSn,
                                                      @Param("portalUserNo") String portalUserNo);

    /**
     * 프레임 단건 + 소유자 스코프 검증 + 비관적 쓰기 락(PESSIMISTIC_WRITE) — 라벨 전체교체(PUT)의
     * delete→saveAll 을 동일 프레임 스코프로 직렬화한다(HIGH #1 동시 PUT 경합).
     * <p>같은 uldFrmeSn 에 대한 병렬 PUT 은 이 락에서 순차화되어 마지막 요청 결과로 수렴하며,
     * delete 와 insert 사이에 다른 트랜잭션이 끼어들어 중복/유실이 발생하는 것을 차단한다.
     * 락 범위는 프레임 행 1건으로 제한(자산 전체 락 회피).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from LsPortalUldFrme f "
            + "where f.uldFrmeSn = :uldFrmeSn "
            + "and exists (select 1 from LsPortalUld u "
            + "            where u.uldSn = f.uldSn and u.portalUserNo = :portalUserNo)")
    Optional<LsPortalUldFrme> findByUldFrmeSnAndOwnerForUpdate(@Param("uldFrmeSn") Long uldFrmeSn,
                                                               @Param("portalUserNo") String portalUserNo);

    /** 업로드 프레임 목록(순번 오름차순, 페이징) + 소유자 스코프 검증. */
    @Query("select f from LsPortalUldFrme f "
            + "where f.uldSn = :uldSn "
            + "and exists (select 1 from LsPortalUld u "
            + "            where u.uldSn = f.uldSn and u.portalUserNo = :portalUserNo) "
            + "order by f.frmeNo asc")
    Page<LsPortalUldFrme> findAllByUldSnAndOwnerOrderByFrmeNo(@Param("uldSn") Long uldSn,
                                                             @Param("portalUserNo") String portalUserNo,
                                                             Pageable pageable);

    /**
     * 소유권 미검증 원시 조회 — 프레임 추출 러너/배치 등 내부 파이프라인 및 DB 진실 검증 전용.
     * <p><b>경고: 사전 소유권 검증 필수 — 사용자 요청 서비스 진입점에서 직접 사용 금지.</b>
     */
    List<LsPortalUldFrme> findAllByUldSnOrderByFrmeNo(Long uldSn);
}
