package kr.co.cudo.authoring.portal.upload;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 포털 채널 증강 요청 — <b>공용 증강 원장</b>({@code LS_DATA_AUG}) 위의 포털 스코프 조회.
 *
 * <h3>왜 관제 증강 리포지토리를 쓰지 않는가</h3>
 * <p>같은 엔티티를 읽는 리포지토리가 증강 도메인에도 있다. 그쪽은 <b>관제 채널 축</b>이고 여기는
 * <b>포털 채널 축</b>이라, 포털 스코프 술어를 그쪽에 심지 않는다 — 심으면 관제 조회가 채널 축을
 * 의식해야 한다. 포털 프레임 원장이 이미 같은 규약을 쓴다({@link PortalUploadFrameRepository}).
 *
 * <h3>소유자 스코프는 <b>부모 영상</b>에서 온다</h3>
 * <p>증강 행 자체에는 채널·소유자 개념이 없다. 그래서 모든 조회가 {@code 증강 행 → 대표 프레임 →
 * 부모 영상} 을 타고 올라가 <b>출처 판별자 + 소유자</b> 두 축을 함께 건다. 「소유자가 비어 있으면
 * 통과」 같은 완화를 두면 관제 증강이 포털 채널로 샌다.
 *
 * <p>식별자는 추측 가능한 시퀀스라 소유자 미검증 조회 창구를 두지 않는다(CWE-639).
 *
 * @design API-232
 * @design API-233
 * @design ADR-058
 */
@ControlRepo
public interface PortalAugmentRepository extends JpaRepository<LsDataAug, Long> {

    /**
     * 포털 자산 판별 — 출처 판별자 + 소유자. 중첩 {@code exists} 라 조인이 행을 증식시키지 않는다.
     * (엔티티 사이에 연관 매핑이 없어 명시 조인 대신 상관 서브쿼리를 쓴다.)
     */
    String OWNER_SCOPE = " exists (select 1 from LsDataSrc s where s.srcSn = a.srcSn"
            + " and exists (select 1 from LsDataRaw r where r.rawSn = s.rawSn"
            + " and r.srcType = :srcType and r.portalUserNo = :owner))";

    /**
     * 본인이 낸 요청 한 페이지 — <b>요청 일시 내림차순 고정</b>.
     *
     * <p>정렬을 파라미터로 받지 않는다. 받으면 정렬 키 허용 목록을 따로 유지해야 하는데, 이 목록에는
     * 최근 것부터 보는 것 말고 다른 순서가 필요하지 않다. 같은 밀리초 동률에서 순서가 흔들리지
     * 않도록 식별자 내림차순을 tie-breaker 로 둔다(페이징이 행을 건너뛰거나 중복시키지 않게).
     */
    @Query(value = "select a from LsDataAug a where" + OWNER_SCOPE
            + " order by a.regDt desc, a.dataAugSn desc",
            countQuery = "select count(a) from LsDataAug a where" + OWNER_SCOPE)
    Page<LsDataAug> findPageByOwner(@Param("owner") String portalUserNo,
                                    @Param("srcType") String srcType,
                                    Pageable pageable);

    /**
     * 본인이 낸 요청 단건. <b>남의 요청과 없는 요청은 모두 {@code empty}</b> 이며, 두 경우를 응답에서
     * 가르지 않는 것은 존재 여부가 상태코드로 드러나지 않게 하기 위함이다(응답 통일은 호출부 몫).
     */
    @Query("select a from LsDataAug a where a.dataAugSn = :augSn and" + OWNER_SCOPE)
    Optional<LsDataAug> findByOwner(@Param("augSn") Long augSn,
                                    @Param("owner") String portalUserNo,
                                    @Param("srcType") String srcType);
}
