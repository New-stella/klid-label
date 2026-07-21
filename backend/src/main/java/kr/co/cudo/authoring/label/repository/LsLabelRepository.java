package kr.co.cudo.authoring.label.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.label.entity.LsLabel;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 라벨 마스터 Repository (klid_system 공유 DB — Control 데이터소스).
 *
 * <p>비즈니스 로직 금지 — 조회/저장 메서드만 정의한다.
 * <p>V34 이후 PJT_ID 컬럼 제거 — LBL_NM 이 전역 UNIQUE. V120 이후 활성(USE_YN='Y') 라벨은
 * 대소문자+공백 무시(LOWER(TRIM)) 기준으로도 유일하다(부분 유니크 인덱스 UK_LS_LABEL_NM_CI).
 */
@ControlRepo
public interface LsLabelRepository extends JpaRepository<LsLabel, Long> {

    /** 활성(USE_YN='Y') 라벨을 SORT_SEQ ASC 로 조회. */
    List<LsLabel> findByUseYnOrderBySortSeqAsc(String useYn);

    /** create 검증용 — 동일 이름(활성/비활성 무관) 존재 여부. (테스트 존재확인용으로 유지) */
    boolean existsByLabelNm(String labelNm);

    /**
     * create 검증용 (V120) — 활성 라벨 중 대소문자+공백 무시(LOWER(TRIM)) 근사중복 존재 여부.
     * 부분 유니크 인덱스와 동일한 정규화 비교로, 저장 전에 CONFLICT 를 조기 판정한다.
     */
    @Query("SELECT COUNT(l) > 0 FROM LsLabel l "
            + "WHERE LOWER(TRIM(l.labelNm)) = LOWER(TRIM(:name)) AND l.useYn = :useYn")
    boolean existsActiveByNormalizedName(@Param("name") String name, @Param("useYn") String useYn);

    /**
     * update 검증용 (V120) — 자기 자신(labelId)을 제외한 활성 근사중복 존재 여부.
     */
    @Query("SELECT COUNT(l) > 0 FROM LsLabel l "
            + "WHERE LOWER(TRIM(l.labelNm)) = LOWER(TRIM(:name)) AND l.useYn = :useYn "
            + "AND l.labelId <> :labelId")
    boolean existsActiveByNormalizedNameExcludingId(@Param("name") String name,
                                                    @Param("useYn") String useYn,
                                                    @Param("labelId") Long labelId);

    /**
     * 활성 라벨을 정규화(LOWER(TRIM)) 이름으로 조회 — labelId ASC 정렬.
     * 방어적 top-1 을 위해 {@link Pageable} 로 결과 수를 제한해 호출한다.
     */
    @Query("SELECT l FROM LsLabel l "
            + "WHERE LOWER(TRIM(l.labelNm)) = LOWER(TRIM(:name)) AND l.useYn = :useYn "
            + "ORDER BY l.labelId ASC")
    List<LsLabel> findActiveByNormalizedName(@Param("name") String name,
                                             @Param("useYn") String useYn,
                                             Pageable pageable);

    /**
     * Phase 6 (AutoLabel preset 매핑) — 자동 라벨링 단계에서 ai-server 가 반환한 라벨명을
     * LS_LABEL 마스터로 매핑할 때 사용. 대소문자+공백 무시 + USE_YN 필터.
     *
     * <p>방어적 top-1: 인덱스 이전 레거시 근사중복 등으로 다중 결과가 나와도 예외 없이
     * 최소 labelId 1건만 반환한다(belt-and-suspenders — 크래시 차단).
     */
    default Optional<LsLabel> findByLabelNmIgnoreCaseAndUseYn(String labelNm, String useYn) {
        List<LsLabel> found = findActiveByNormalizedName(labelNm, useYn, PageRequest.of(0, 1));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /**
     * 프리셋 코드 join·검증용 — labelId 집합을 활성(USE_YN) 필터로 일괄 조회한다(N+1 방지).
     * 반환에 없는 id 는 미존재 또는 soft delete(USE_YN='N') 를 의미한다.
     */
    List<LsLabel> findByLabelIdInAndUseYn(Collection<Long> labelIds, String useYn);
}
