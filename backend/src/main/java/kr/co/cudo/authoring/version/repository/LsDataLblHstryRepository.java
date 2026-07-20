package kr.co.cudo.authoring.version.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.version.entity.LsDataLblHstry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@ControlRepo
public interface LsDataLblHstryRepository extends JpaRepository<LsDataLblHstry, Long> {

    /** 프레임 단위 버전 목록 — 최신순. */
    List<LsDataLblHstry> findBySrcSnOrderByRegDtDesc(Long srcSn);

    /**
     * Phase 2 — 프레임 단위 변경 이력 페이징 조회.
     * <p>정렬은 {@link Pageable} 의 sort 로 전달한다(서비스에서 REG_DT DESC, LBL_HSTRY_SN DESC
     * 동시각 보정 tiebreaker 를 부여). CWE-770: 페이지 크기 상한은 컨트롤러에서 100 으로 클램프.
     */
    Page<LsDataLblHstry> findBySrcSn(Long srcSn, Pageable pageable);

}
