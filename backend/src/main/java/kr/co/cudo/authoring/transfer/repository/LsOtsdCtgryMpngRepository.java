package kr.co.cudo.authoring.transfer.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.transfer.entity.LsOtsdCtgryMpng;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 외부분류대응({@code LS_OTSD_CTGRY_MPNG}) 조회·저장.
 *
 * <h3>조회는 두 축 모두 상한이 있다</h3>
 * <p>목록 조회는 {@link Pageable} 을 강제하고, 검사 경로의 대응 해석은 <b>그 산출물에 실제로 등장한
 * 분류 코드 집합</b>으로 좁힌 {@code IN} 조회다. 전체 조회를 두지 않는 이유는 대응 표가 산출물을
 * 가져올수록 단조 증가하는 표이기 때문이다(CWE-770).
 *
 * <h3>미확정 판정은 <b>사용 중인 대응</b>만 본다</h3>
 * <p>해제된 대응({@code USE_YN='N'})은 그 분류를 다시 <b>처음 보는 분류</b>로 되돌린다(AC-043).
 * 그래서 해석 조회는 사용여부를 조건에 넣고, 목록 조회만 해제분을 선택적으로 함께 보여 준다.
 *
 * @design DOMAIN-017
 * @design ERD-031
 * @design API-209
 */
@ControlRepo
public interface LsOtsdCtgryMpngRepository extends JpaRepository<LsOtsdCtgryMpng, Long> {

    /**
     * 한 축의 대응 1건 — 확정({@code POST})이 중복을 가리는 축이다.
     *
     * <p>해제된 행도 함께 찾는다. 유일 제약 {@code (MPNG_KND_CD, OTSD_CTGRY_CD)} 가 사용여부를
     * 포함하지 않으므로, 해제된 행을 못 보고 새로 넣으면 제약 위반으로 500 이 된다.
     */
    Optional<LsOtsdCtgryMpng> findByMpngKndCdAndOtsdCtgryCd(String mpngKndCd, String otsdCtgryCd);

    /** 산출물에 등장한 분류 코드들 중 <b>지금 쓰는</b> 대응이 있는 것만. */
    List<LsOtsdCtgryMpng> findByMpngKndCdAndUseYnAndOtsdCtgryCdIn(
            String mpngKndCd, String useYn, Collection<String> otsdCtgryCds);

    /**
     * 목록 조회 — 종류·사용여부로 좁힌다. 두 조건 모두 {@code null} 이면 전 건(페이지 단위)이다.
     *
     * <p>연결 대상이 비어 있는 행도 <b>감추지 않는다</b> — 그 행은 "무엇에 걸려야 하는지 아직 정하지
     * 못한 대응"이라 사람이 봐야 하는 것이고, 감추면 화면에서 사라진 채 남는다.
     */
    @Query("SELECT m FROM LsOtsdCtgryMpng m "
            + "WHERE (:mpngKndCd IS NULL OR m.mpngKndCd = :mpngKndCd) "
            + "AND (:useYn IS NULL OR m.useYn = :useYn) "
            + "ORDER BY m.mpngKndCd ASC, m.otsdCtgryCd ASC")
    Page<LsOtsdCtgryMpng> search(@Param("mpngKndCd") String mpngKndCd,
                                 @Param("useYn") String useYn,
                                 Pageable pageable);
}
