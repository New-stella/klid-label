package kr.co.cudo.authoring.version.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsLabelVersionRepository extends JpaRepository<LsLabelVersion, Long> {

    List<LsLabelVersion> findByDataSrcSnOrderByRegDtDesc(Long dataSrcSn);

    List<LsLabelVersion> findByDataRawSnAndDataSrcSnAndActiveYn(
            Long dataRawSn, Long dataSrcSn, String activeYn);

    Optional<LsLabelVersion> findByGiteaCmtHash(String giteaCmtHash);

    int countByDataRawSnAndDataSrcSn(Long dataRawSn, Long dataSrcSn);

    // Phase 7 — rawSn 단위 활용 (영상 전체 버전 트래킹)
    Optional<LsLabelVersion> findByDataRawSnAndActiveYn(Long dataRawSn, String activeYn);

    Page<LsLabelVersion> findAllByDataRawSnOrderByVersionNoDesc(Long dataRawSn, Pageable pageable);

    Optional<LsLabelVersion> findFirstByDataRawSnOrderByVersionNoDesc(Long dataRawSn);
}
