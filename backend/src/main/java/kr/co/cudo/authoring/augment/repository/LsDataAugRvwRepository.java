package kr.co.cudo.authoring.augment.repository;

import kr.co.cudo.authoring.augment.entity.LsDataAugRvw;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsDataAugRvwRepository extends JpaRepository<LsDataAugRvw, Long> {

    Optional<LsDataAugRvw> findFirstByDataAugSnOrderByRegDtDesc(Long dataAugSn);

    /** 가장 최근 검수 row (응답 빌드용 — findFirstByDataAugSnOrderByRegDtDesc 의 alias). */
    default Optional<LsDataAugRvw> findLatestByDataAugSn(Long dataAugSn) {
        return findFirstByDataAugSnOrderByRegDtDesc(dataAugSn);
    }

    /** 영상 단위(DATA_RAW_SN) 검수 상태별 조회. */
    List<LsDataAugRvw> findAllByDataRawSnAndRvwSttsCd(Long dataRawSn, String rvwSttsCd);
}
