package kr.co.cudo.authoring.label.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.label.entity.LsLabel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 라벨 마스터 Repository (klid_system 공유 DB — Control 데이터소스).
 *
 * <p>비즈니스 로직 금지 — 조회/저장 메서드만 정의한다.
 * <p>V34 이후 PJT_ID 컬럼 제거 — LABEL_NM 이 전역 UNIQUE.
 */
@ControlRepo
public interface LsLabelRepository extends JpaRepository<LsLabel, Long> {

    /** 활성(USE_YN='Y') 라벨을 SORT_SEQ ASC 로 조회. */
    List<LsLabel> findByUseYnOrderBySortSeqAsc(String useYn);

    /** create 검증용 — 동일 이름(활성/비활성 무관) 존재 여부. */
    boolean existsByLabelNm(String labelNm);

    /** update 검증용 — 자기 자신을 제외한 동일 이름 존재 여부. */
    boolean existsByLabelNmAndLabelIdNot(String labelNm, Long labelId);

    /**
     * Phase 6 (AutoLabel preset 매핑) — 자동 라벨링 단계에서 ai-server 가 반환한 라벨명을
     * LS_LABEL 마스터로 매핑할 때 사용. 대소문자 무시 + USE_YN 필터.
     */
    Optional<LsLabel> findByLabelNmIgnoreCaseAndUseYn(String labelNm, String useYn);

    /**
     * 프리셋 코드 join·검증용 — labelId 집합을 활성(USE_YN) 필터로 일괄 조회한다(N+1 방지).
     * 반환에 없는 id 는 미존재 또는 soft delete(USE_YN='N') 를 의미한다.
     */
    List<LsLabel> findByLabelIdInAndUseYn(Collection<Long> labelIds, String useYn);
}
