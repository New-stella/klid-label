package kr.co.cudo.authoring.label.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.label.entity.LsLabel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 라벨 마스터 Repository (klid_system 공유 DB — Control 데이터소스).
 *
 * <p>비즈니스 로직 금지 — 조회/저장 메서드만 정의한다.
 * <p>V34 이후 PJT_ID 컬럼 제거 — NAME 이 전역 UNIQUE.
 */
@ControlRepo
public interface LsLabelRepository extends JpaRepository<LsLabel, Long> {

    /** 활성(USE_YN='Y') 라벨을 SORT_NO ASC 로 조회. */
    List<LsLabel> findByUseYnOrderBySortNoAsc(String useYn);

    /** create 검증용 — 동일 이름(활성/비활성 무관) 존재 여부. */
    boolean existsByName(String name);

    /** update 검증용 — 자기 자신을 제외한 동일 이름 존재 여부. */
    boolean existsByNameAndLabelIdNot(String name, Long labelId);
}
