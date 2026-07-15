package kr.co.cudo.authoring.dataset.export.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.dataset.export.entity.LsDatasetExport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * {@code LS_DATASET_EXPORT} 저장소 (control 데이터소스).
 *
 * <p>버전 도출(영상별 누적 export 건수)과 최신 버전 조회를 제공한다. 모든 조회는 파생 쿼리
 * 메서드로 파라미터 바인딩되어 SQL Injection(CWE-89) 표면이 없다.
 */
@ControlRepo
public interface LsDatasetExportRepository extends JpaRepository<LsDatasetExport, Long> {

    /** 영상(rawSn)별 누적 export 건수 — 다음 버전 = count + 1 도출용. */
    long countByDataRawSn(Long rawSn);

    /** 같은 영상의 같은 버전 존재 여부 — 재산출 중복 방어(UK 사전 확인). */
    boolean existsByDataRawSnAndExportVerNo(Long rawSn, int exportVerNo);

    /** 영상(rawSn)의 최신(최대 버전) export 1건. */
    Optional<LsDatasetExport> findFirstByDataRawSnOrderByExportVerNoDesc(Long rawSn);

    /**
     * 영상(rawSn)의 <b>지정 상태</b> export 중 최신(최대 버전) 1건.
     *
     * <p>멱등 판정 키(직전 SUCCEEDED 해시) 조회용 — 상태 필터를 쿼리 레벨에서 적용해, 최신 export 가
     * FAILED/PENDING 이어도 그 이전의 실제 SUCCEEDED 해시를 정확히 찾는다("최신 1건 후 필터" 방식의
     * 재산출 폭증 버그 방지). 파생 쿼리 파라미터 바인딩만 사용(CWE-89 표면 없음).
     */
    Optional<LsDatasetExport> findFirstByDataRawSnAndExportSttsCdOrderByExportVerNoDesc(
            Long rawSn, String exportSttsCd);
}
