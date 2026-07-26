package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsDataSrcRepository extends JpaRepository<LsDataSrc, Long> {

    List<LsDataSrc> findByRawSnOrderByFrameNoAsc(Long rawSn);

    /** 프레임 청크 순회(대용량 다운스케일 — MEDIUM)용 페이징 조회. FRAME_NO 오름차순. */
    org.springframework.data.domain.Page<LsDataSrc> findByRawSnOrderByFrameNoAsc(
            Long rawSn, org.springframework.data.domain.Pageable pageable);

    Optional<LsDataSrc> findByRawSnAndFrameNo(Long rawSn, Integer frameNo);

    long countByRawSn(Long rawSn);

    /**
     * 영상별 첫 프레임의 SRC_SN 을 한 번에 조회 (N+1 회피).
     *
     * <p>LS_DATA_SRC.SRC_SN 은 IDENTITY 로 발급되며, FRAME_EXTRACT 단계가 FRAME_NO 오름차순으로
     * insert 하므로 동일 RAW_SN 내에서 MIN(SRC_SN) 은 FRAME_NO=0 의 row 와 동치이다.
     * 결과는 {@code [rawSn, firstSrcSn]} Object 배열 리스트. 빈 인자는 빈 결과를 반환한다.
     */
    @Query("select s.rawSn as rawSn, min(s.srcSn) as firstSrcSn "
            + "from LsDataSrc s where s.rawSn in :rawSns group by s.rawSn")
    List<Object[]> findFirstSrcSnGroupedByRawSn(@Param("rawSns") Collection<Long> rawSns);

    /**
     * 프레임 SRC_SN → 원본영상 RAW_SN 역매핑 일괄 조회 (N+1 회피).
     *
     * <p>증강 잡 카드(영상 단위 그룹)에서 LS_DATA_AUG.SRC_SN(대표프레임) 을 원본영상 RAW_SN 으로
     * 환원하기 위한 용도. 결과는 {@code [srcSn, rawSn]} Object 배열 리스트. 빈 인자는 빈 결과.
     */
    @Query("select s.srcSn as srcSn, s.rawSn as rawSn "
            + "from LsDataSrc s where s.srcSn in :srcSns")
    List<Object[]> findRawSnBySrcSnIn(@Param("srcSns") Collection<Long> srcSns);

    /**
     * 영상별 프레임 개수를 한 번에 조회 (N+1 회피).
     *
     * <p>TaskBoardService.list 의 page.map 람다에서 각 row 마다 countByRawSn(...) 을 호출하면
     * 페이지 size 만큼 SELECT COUNT 쿼리가 발생한다 (size 20 기준 20회). 이를 단일 GROUP BY 쿼리로
     * 통합하여 페이지당 1회로 축소한다. 결과는 {@code [rawSn, frameCount]} Object 배열 리스트.
     * 프레임이 0건인 영상은 결과에 포함되지 않으므로 caller 가 0L 폴백 처리해야 한다.
     */
    @Query("select s.rawSn as rawSn, count(s) as frameCount "
            + "from LsDataSrc s where s.rawSn in :rawSns group by s.rawSn")
    List<Object[]> countByRawSnsGrouped(@Param("rawSns") Collection<Long> rawSns);

    /**
     * Phase 3 #5 — 비식별 누락 신고 처리 시 해당 영상 전체 프레임의 개인정보 3필드(익명/가명/개인정보 포함여부)를
     * NULL 로 초기화(파생 폴백 복귀)한다. 재비식별 후 stale '개인정보 없음' 오표기(CWE-359)를 방지한다.
     *
     * <p>{@code clearAutomatically} 미지정 — 신고 트랜잭션이 이 뒤에 부모 RAW 를 dirty-update(markDeidentified)
     * 하므로 영속성 컨텍스트를 비우면 안 된다(기존 라벨 bulk delete 와 동일 정책). 벌크 JPQL 로 즉시 flush 된다.
     *
     * @return 초기화된 프레임 수
     */
    @Modifying
    @Query("update LsDataSrc s set s.anonyInclYn = null, s.psdoInclYn = null, s.prvcInclYn = null, "
            + "s.updDt = CURRENT_TIMESTAMP where s.rawSn = :rawSn")
    int resetPrivacyMetaByRawSn(@Param("rawSn") Long rawSn);

    /**
     * 해상도 파생 백필 전용 — 이관된 비식별 프레임 경로를 반영하고 원본 경로를 <b>NULL(원본 부재)</b> 로
     * 정정한다(E-ISSUE-21 파일 이관 + E-ISSUE-41 정책 A). 파일 복사·검증 성공 이후에만 호출된다.
     *
     * <p>{@code clearAutomatically} 미지정 — 같은 트랜잭션에서 다른 엔티티 dirty-update 를 유실시키지
     * 않기 위함(기존 {@link #resetPrivacyMetaByRawSn} 와 동일 정책).
     */
    @Modifying
    @Query("update LsDataSrc s set s.deIdntfSrcFilePathNm = :deidPath, s.srcFilePathNm = null, "
            + "s.updDt = CURRENT_TIMESTAMP where s.srcSn = :srcSn")
    int relocateDerivativeFramePath(@Param("srcSn") Long srcSn, @Param("deidPath") String deidPath);
}
