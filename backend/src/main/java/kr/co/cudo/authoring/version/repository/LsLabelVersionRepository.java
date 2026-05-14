package kr.co.cudo.authoring.version.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsLabelVersionRepository extends JpaRepository<LsLabelVersion, Long> {

    List<LsLabelVersion> findByDataSrcSnOrderByRegDtDesc(Long dataSrcSn);

    List<LsLabelVersion> findByPjtSnAndDataRawSnAndDataSrcSnAndActiveYn(
            Long pjtSn, Long dataRawSn, Long dataSrcSn, String activeYn);

    Optional<LsLabelVersion> findByGiteaCmtHash(String giteaCmtHash);

    int countByPjtSnAndDataRawSnAndDataSrcSn(Long pjtSn, Long dataRawSn, Long dataSrcSn);
}
