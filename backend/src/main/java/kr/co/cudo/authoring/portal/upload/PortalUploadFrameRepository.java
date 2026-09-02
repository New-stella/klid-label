package kr.co.cudo.authoring.portal.upload;

import jakarta.persistence.LockModeType;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 포털 업로드 프레임 — <b>공용 프레임 원장</b>({@code LS_DATA_SRC}) 위의 포털 스코프 조회 (ADR-058 흡수).
 *
 * <p>흡수 전 전용 표에는 없던 컬럼(비식별 경로·프레임 설명·폐기 표시)을 원장이 이미 갖고 있어
 * <b>신설 컬럼 없이</b> 그대로 앉는다. 대신 프레임 행 자체에는 소유자 개념이 없으므로, 모든 사용자
 * 진입점은 <b>부모 영상의 출처·소유자를 함께 확인</b>하는 메서드만 쓴다 — 식별자는 추측 가능한
 * 시퀀스라 소유자 미검증 조회는 IDOR 전제를 무너뜨린다(CWE-639).
 *
 * <p>같은 엔티티를 읽는 리포지토리가 배치 도메인에도 있다. 그쪽은 내부 파이프라인 축이고 여기는
 * 포털 채널 축이라, <b>포털 스코프 술어를 그쪽에 심지 않는다</b>(심으면 내부 조회가 채널 축을
 * 의식해야 한다).
 *
 * @design ADR-058
 * @design ERD-028
 */
@ControlRepo
public interface PortalUploadFrameRepository extends JpaRepository<LsDataSrc, Long> {

    /** 부모 영상이 <b>이 사용자의 포털 자산</b>인지 확인하는 상관 EXISTS — 조인을 만들지 않아 행이 증식하지 않는다. */
    String OWNER_SCOPE = " and exists (select 1 from LsDataRaw r"
            + " where r.rawSn = f.rawSn and r.srcType = :srcType and r.portalUserNo = :owner)";

    /** 프레임 단건 + 소유자 스코프. */
    @Query("select f from LsDataSrc f where f.srcSn = :srcSn" + OWNER_SCOPE)
    Optional<LsDataSrc> findByOwner(@Param("srcSn") Long srcSn,
                                    @Param("owner") String portalUserNo,
                                    @Param("srcType") String srcType);

    /**
     * 프레임 단건 + 소유자 스코프 + 비관적 쓰기 락 — 라벨 전체교체(PUT)의 삭제→저장을 프레임 스코프로
     * 직렬화한다. 같은 프레임에 대한 병렬 저장이 여기서 순차화되어 중복·유실이 생기지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from LsDataSrc f where f.srcSn = :srcSn" + OWNER_SCOPE)
    Optional<LsDataSrc> findByOwnerForUpdate(@Param("srcSn") Long srcSn,
                                             @Param("owner") String portalUserNo,
                                             @Param("srcType") String srcType);

    /** 자산 프레임 목록(순번 오름차순, 페이징) + 소유자 스코프. */
    @Query("select f from LsDataSrc f where f.rawSn = :rawSn" + OWNER_SCOPE + " order by f.frameNo asc")
    Page<LsDataSrc> findPageByAssetAndOwner(@Param("rawSn") Long rawSn,
                                            @Param("owner") String portalUserNo,
                                            @Param("srcType") String srcType,
                                            Pageable pageable);

    /**
     * 자산의 <b>대표(첫) 프레임</b> — 증강 요청이 원장에 남길 기준 프레임이다.
     *
     * <p>소유권을 검증하지 않는다 — 부르는 자리가 <b>이미 자산 소유권을 확인한 뒤</b>이고, 프레임을
     * 전부 읽어 첫 건만 쓰는 낭비를 피하기 위해 한 건만 가져온다. 사용자 요청 진입점에서 소유권 확인
     * 없이 직접 쓰지 말 것.
     */
    Optional<LsDataSrc> findFirstByRawSnOrderByFrameNoAscSrcSnAsc(Long rawSn);

    /**
     * 소유권 미검증 원시 조회 — 프레임 추출 러너·내보내기 등 <b>부모 소유권을 이미 확인한</b> 경로 전용.
     * <b>사용자 요청 진입점에서 직접 쓰지 말 것.</b>
     */
    List<LsDataSrc> findAllByRawSnOrderByFrameNoAsc(Long rawSn);
}
