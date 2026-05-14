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
     * 영상(rawSn) 별 최신 내보내기 요약 — LS_PJT_DATA_STTS join LS_DATA_SET.
     *
     * <p>각 rawSn 에 대해 COMPLETED/FAILED 상태의 export 중 가장 최근(EXPORT_SN DESC) 1건만 반환.
     * exportedAt 은 FAILED 일 때 null 일 수 있어 EXPORT_SN 기준이 안전하다.
     *
     * <p>빈 컬렉션이면 빈 결과를 반환 (default 구현에서 short-circuit).
     */
    default List<VideoExportProjection> findLatestExportsByRawSns(Collection<Long> rawSns) {
        if (rawSns == null || rawSns.isEmpty()) {
            return Collections.emptyList();
        }
        return findLatestExportsByRawSnsInternal(rawSns);
    }

    @Query(value = """
            SELECT ds.RAW_DATA_ID                       AS rawSn,
                   d.EXPORT_STTS_CD                     AS exportSttsCd,
                   d.EXPORTED_AT                        AS exportedAt,
                   d.ERROR_MESSAGE                      AS errorMessage
            FROM LS_PJT_DATA_STTS ds
            JOIN LS_DATA_SET d
              ON d.PJT_ID = ds.PJT_ID
             AND d.EXPORT_STTS_CD IN ('COMPLETED', 'FAILED')
            WHERE ds.RAW_DATA_ID IN (:rawSns)
              AND d.EXPORT_SN = (
                  SELECT MAX(d2.EXPORT_SN)
                  FROM LS_DATA_SET d2
                  JOIN LS_PJT_DATA_STTS ds2
                    ON ds2.PJT_ID = d2.PJT_ID
                  WHERE ds2.RAW_DATA_ID = ds.RAW_DATA_ID
                    AND d2.EXPORT_STTS_CD IN ('COMPLETED', 'FAILED')
              )
            """, nativeQuery = true)
    List<VideoExportProjection> findLatestExportsByRawSnsInternal(@Param("rawSns") Collection<Long> rawSns);

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
