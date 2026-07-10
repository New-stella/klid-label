package kr.co.cudo.authoring.augment.repository;

import kr.co.cudo.authoring.augment.entity.LsDataAugRvw;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsDataAugRvwRepository extends JpaRepository<LsDataAugRvw, Long> {

    Optional<LsDataAugRvw> findFirstByDataAugSnOrderByRegDtDesc(Long dataAugSn);

    /**
     * 잡 카드 completedAt 산출용 — 여러 DATA_AUG_SN 의 검수 row 를 일괄 조회(N+1 회피).
     * 호출 측에서 DATA_AUG_SN 별 최신 RVW_DT 를 집계한다. 파라미터 바인딩만 사용(CWE-89).
     */
    @Query("SELECT r FROM LsDataAugRvw r WHERE r.dataAugSn IN :dataAugSns")
    List<LsDataAugRvw> findByDataAugSnIn(@Param("dataAugSns") Collection<Long> dataAugSns);

    /** 가장 최근 검수 row (응답 빌드용 — findFirstByDataAugSnOrderByRegDtDesc 의 alias). */
    default Optional<LsDataAugRvw> findLatestByDataAugSn(Long dataAugSn) {
        return findFirstByDataAugSnOrderByRegDtDesc(dataAugSn);
    }

    /** 영상 단위(DATA_RAW_SN) 검수 상태별 조회. */
    List<LsDataAugRvw> findAllByDataRawSnAndRvwSttsCd(Long dataRawSn, String rvwSttsCd);
}
