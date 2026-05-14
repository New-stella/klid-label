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

    Optional<LsDataMeta> findByRawSnAndMetaKey(Long rawSn, String metaKey);
}
