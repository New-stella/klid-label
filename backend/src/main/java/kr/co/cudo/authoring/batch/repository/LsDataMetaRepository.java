package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsDataMetaRepository extends JpaRepository<LsDataMeta, Long> {

    List<LsDataMeta> findByRawSn(Long rawSn);

    /** Phase 4 — 외부 학습데이터 API: META_TYPE_CD(RAW/DEID) 별 메타 조회. */
    List<LsDataMeta> findByRawSnAndMetaTypeCd(Long rawSn, String metaTypeCd);

    /**
     * Phase 5 hotfix — V26 트리플 UK 도입 후 동일 (rawSn, metaKey) 에 RAW/DEID 두 행이
     * 공존 가능. 호출 측은 META_TYPE_CD 를 명시하여 결정적 lookup 을 보장해야 한다.
     */
    Optional<LsDataMeta> findByRawSnAndMetaKeyAndMetaTypeCd(Long rawSn, String metaKey, String metaTypeCd);
}
