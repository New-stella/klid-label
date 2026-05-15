package kr.co.cudo.authoring.video.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@ControlRepo
public interface VideoRepository extends JpaRepository<LsDataRaw, Long> {

    Optional<LsDataRaw> findByVmsClipId(String vmsClipId);

    Page<LsDataRaw> findAllByOrderByRegDtDesc(Pageable pageable);

    Page<LsDataRaw> findAllByDataSttsCdOrderByRegDtDesc(String dataSttsCd, Pageable pageable);

    /** 개발 전용: DATA_STTS_CD 기준 가장 오래된 1건 (REG_DT 오름차순). */
    Optional<LsDataRaw> findFirstByDataSttsCdOrderByRegDtAsc(String dataSttsCd);

    /** 개발 전용: DATA_STTS_CD 기준 전체 목록. */
    List<LsDataRaw> findAllByDataSttsCd(String dataSttsCd);

    @Modifying
    @Transactional("controlTransactionManager")
    @Query("UPDATE LsDataRaw r SET r.dataSttsCd = :status, r.updDt = CURRENT_TIMESTAMP WHERE r.rawSn = :rawSn")
    void updateStatus(@Param("rawSn") Long rawSn, @Param("status") String status);

    /**
     * 영상(rawSn) 별 최신 내보내기 요약.
     *
     * <p>V34 (PJT_ID 제거) 이후 LS_DATA_SET 에 영상(RAW_DATA_ID) 매핑 컬럼이 없으므로
     * 영상별 export 매핑은 더 이상 의미가 없다. 본 메서드는 항상 빈 결과를 반환한다.
     *
     * <p>향후 LS_DATA_SET 에 RAW_DATA_ID 매핑 컬럼이 추가되면 이 쿼리를 복원해야 한다.
     */
    default List<VideoExportProjection> findLatestExportsByRawSns(Collection<Long> rawSns) {
        if (rawSns == null || rawSns.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.emptyList();
    }

    /**
     * 페이지의 rawSn 들에 대해 (rawSn, cctvNm, vmsCctvId) 를 한 번에 조회 (N+1 회피).
     *
     * <p>FE WORKER/REVIEWER 작업 목록 영상명 컬럼에 표시할 CCTV 명을 일괄 lookup 하기 위한 용도.
     * LS_DATA_RAW LEFT JOIN MNG_RESOURCE_CCTV 로 결합한다. MNG_RESOURCE_CCTV 시드가 없는 환경
     * (또는 매핑이 끊긴 영상) 에서는 cctvNm 이 null 로 반환된다.
     *
     * <p>반환 행: {@code [Long rawSn, String cctvNm, String vmsCctvId]}.
     * 호출 측에서 cctvNm 이 null/blank 일 때 vmsCctvId 로 폴백한다.
     */
    default List<Object[]> findCctvNamesByRawSns(Collection<Long> rawSns) {
        if (rawSns == null || rawSns.isEmpty()) {
            return Collections.emptyList();
        }
        return findCctvNamesByRawSnsInternal(rawSns);
    }

    @Query(value = """
            SELECT r.RAW_SN AS rawSn,
                   c.CCTV_NM AS cctvNm,
                   r.VMS_CCTV_ID AS vmsCctvId
            FROM LS_DATA_RAW r
            LEFT JOIN MNG_RESOURCE_CCTV c ON c.VMS_CCTV_ID = r.VMS_CCTV_ID
            WHERE r.RAW_SN IN (:rawSns)
            """, nativeQuery = true)
    List<Object[]> findCctvNamesByRawSnsInternal(@Param("rawSns") Collection<Long> rawSns);
}
