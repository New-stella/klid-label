package kr.co.cudo.authoring.portal.upload;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 포털 업로드 라벨 — <b>공용 라벨 원장</b>({@code LS_DATA_LBL}) 위의 포털 스코프 조회·삭제 (ADR-058 흡수).
 *
 * <h3>소유자는 새 컬럼이 아니라 원장의 등록자 칸이다</h3>
 * <p>흡수 전 전용 표의 소유자 컬럼은 라벨 원장의 {@code REG_USER_NO} 로 간다(V28 이 그 칸을 문자
 * 100자로 넓혔다). 포털 라벨에서 이 값은 <b>인가 판정의 키</b>라 반드시 채워진다.
 *
 * <h3>★ 소유자만으로 스코프를 삼지 않는다</h3>
 * <p>등록자 칸은 내부 채널 작업자도 쓴다. 그래서 모든 술어가 <b>부모 영상이 그 사용자의 포털 자산</b>
 * 임을 함께 확인한다 — 값이 우연히 겹쳐도 채널이 섞이지 않는다.
 *
 * <h3>⚠ 데이터마트 오버레이와 혼동 금지</h3>
 * <p>포털에는 라벨이 두 축이다. 여기는 <b>본인이 올린 자산</b> 위의 라벨이라 원장에 앉지만,
 * 데이터마트 영상 위의 오버레이는 <b>흡수하지 않고 전용 표로 남는다</b> — 그 표의 존재 이유가
 * 「저장해도 원본을 고치지 않는다」이고, 원장에 합치면 그 저장이 원본을 덮어쓰기 때문이다.
 *
 * @design ADR-058
 * @design ERD-028
 */
@ControlRepo
public interface PortalUploadLabelRepository extends JpaRepository<LsDataLbl, Long> {

    /** 프레임이 <b>이 사용자의 포털 자산</b>에 속하는지 확인하는 상관 EXISTS. */
    String FRAME_OWNER_SCOPE = " and exists (select 1 from LsDataSrc f, LsDataRaw r"
            + " where f.srcSn = l.srcSn and r.rawSn = f.rawSn"
            + " and r.srcType = :srcType and r.portalUserNo = :owner)";

    /** 프레임 라벨 목록(소유자 스코프, 식별자 오름차순 — 저장 순서 보존). */
    @Query("select l from LsDataLbl l where l.srcSn = :srcSn and l.regUserNo = :owner"
            + FRAME_OWNER_SCOPE + " order by l.lblSn asc")
    List<LsDataLbl> findAllByFrameAndOwner(@Param("srcSn") Long srcSn,
                                           @Param("owner") String portalUserNo,
                                           @Param("srcType") String srcType);

    /**
     * 프레임 라벨 전체 삭제(소유자 스코프) — 전체교체 저장 대비. 단일 벌크 DELETE 로 왕복을 1회로 줄이고,
     * 소유자·채널 조건을 WHERE 에 강제해 남의 라벨 삭제를 차단한다(IDOR).
     *
     * <p>{@code clearAutomatically} 로 벌크 삭제 후 영속성 컨텍스트를 비워 뒤이은 저장의 stale 참조를 막는다.
     *
     * @return 삭제 건수
     */
    @Modifying(clearAutomatically = true)
    @Query("delete from LsDataLbl l where l.srcSn = :srcSn and l.regUserNo = :owner"
            + " and exists (select 1 from LsDataSrc f, LsDataRaw r"
            + " where f.srcSn = l.srcSn and r.rawSn = f.rawSn"
            + " and r.srcType = :srcType and r.portalUserNo = :owner)")
    int deleteAllByFrameAndOwner(@Param("srcSn") Long srcSn,
                                 @Param("owner") String portalUserNo,
                                 @Param("srcType") String srcType);

    /** 자산 전체 라벨(소유자 스코프) — 내보내기 대비. 프레임별 N+1 을 만들지 않기 위한 일괄 조회다. */
    @Query("select l from LsDataLbl l where l.regUserNo = :owner"
            + " and exists (select 1 from LsDataSrc f, LsDataRaw r"
            + " where f.srcSn = l.srcSn and r.rawSn = f.rawSn and f.rawSn = :rawSn"
            + " and r.srcType = :srcType and r.portalUserNo = :owner)"
            + " order by l.lblSn asc")
    List<LsDataLbl> findAllByAssetAndOwner(@Param("rawSn") Long rawSn,
                                           @Param("owner") String portalUserNo,
                                           @Param("srcType") String srcType);
}
