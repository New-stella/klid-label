package kr.co.cudo.authoring.augment.repository;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsDataAugRepository extends JpaRepository<LsDataAug, Long> {

    /**
     * 증강 4종 묶음 조회 (AUG_TYPE_CD 알파벳 정렬: NIGHT, RAIN, RESOLUTION, WINTER).
     * UI 에서는 화면에서 4종 ENUM 순서로 재정렬하여 표시.
     */
    List<LsDataAug> findBySrcSnOrderByAugTypeCd(Long srcSn);

    /**
     * REVIEWER 의 증강 검수 화면용 — srcSn 미지정 시 전체 페이징 조회.
     * 최신순(REGISTERED_AT DESC)으로 정렬.
     */
    Page<LsDataAug> findAllByOrderByRegisteredAtDesc(Pageable pageable);

    /**
     * Phase 4 — webhook race 흡수용 멱등 키 조회.
     * UNIQUE 제약 (uk_aug_idempotency_key) 위반 후 재조회 경로에서 사용.
     */
    Optional<LsDataAug> findByIdempotencyKey(String idempotencyKey);
}
