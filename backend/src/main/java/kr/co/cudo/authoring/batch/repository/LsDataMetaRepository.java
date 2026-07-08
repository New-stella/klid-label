package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsDataMetaRepository extends JpaRepository<LsDataMeta, Long> {

    List<LsDataMeta> findByRawSn(Long rawSn);

    Optional<LsDataMeta> findByRawSnAndMetaKey(Long rawSn, String metaKey);

    /** 다건 metaKey 를 IN 절 1회로 일괄 조회 (VLM 콜백 results 배치 upsert — N+1 제거). */
    List<LsDataMeta> findByRawSnAndMetaKeyIn(Long rawSn, Collection<String> metaKeys);
}
