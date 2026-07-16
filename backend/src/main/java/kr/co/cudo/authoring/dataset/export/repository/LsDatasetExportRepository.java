package kr.co.cudo.authoring.dataset.export.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.dataset.export.entity.LsDatasetExport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
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
     * 영상(rawSn)의 <b>지정 상태 집합(IN)</b> export 중 최신(최대 버전) 1건.
     *
     * <p>멱등 baseline(직전 <b>SUCCEEDED+PARTIAL</b> 해시) 조회용 — 상태 IN 필터를 쿼리 레벨에서
     * 적용해, 최신 export 가 FAILED/PENDING 이어도 그 이전의 실제 성공/부분 산출 해시를 정확히 찾는다
     * ("최신 1건 후 필터" 방식의 재산출 폭증 버그 방지). PARTIAL 을 baseline 에 포함하는 이유:
     * 원천 이미지가 지속 부재해 매번 PARTIAL 로 마감되는 영상을 무수정 재승인할 때, contentHash 가
     * 직전 PARTIAL 과 같으면 재산출해도 같은 PARTIAL 결과라 무의미하므로 skip 시켜 버전 무한 채번 +
     * 이미지 파일 무한 재복사(디스크 누적)를 막는다. FAILED(written==0)는 재시도 유도를 위해 제외한다.
     * 파생 쿼리 파라미터 바인딩만 사용(CWE-89 표면 없음).
     */
    Optional<LsDatasetExport> findFirstByDataRawSnAndExportSttsCdInOrderByExportVerNoDesc(
            Long rawSn, Collection<String> exportSttsCds);

    /**
     * stale PENDING 회수용 — 지정 상태이며 {@code REG_DT} 가 cutoff 이전인 export 를 조회한다.
     *
     * <p>파일 쓰기/상태 마감 전 프로세스 크래시로 {@code PENDING} 에 영구 고착된 잔재를 주기 sweeper 가
     * 회수(FAILED 마감)하는 데 사용한다. 파생 쿼리 파라미터 바인딩만 사용(CWE-89 표면 없음).
     */
    List<LsDatasetExport> findByExportSttsCdAndRegDtBefore(String exportSttsCd, LocalDateTime cutoff);
}
