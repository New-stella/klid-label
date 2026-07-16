package kr.co.cudo.authoring.augment.repository;

import jakarta.persistence.LockModeType;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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
     * 증강 잡 카드(영상 단위 그룹) 페이징의 1차 조회 — distinct 대표프레임 SRC_SN 을
     * 그룹 최초 요청시각(MIN REG_DT) 최신순으로 페이징한다.
     *
     * <p>LS_DATA_AUG 는 (SRC_SN × AUG_TYPE_CD) per-row 이므로 영상 1건이 여러 row 를 갖는다.
     * 잡 카드 페이징은 영상 그룹 단위여야 하므로 여기서 그룹 키(SRC_SN)만 먼저 페이징하고,
     * 실제 row 는 {@link #findBySrcSnIn} 으로 2차 조회한다. 파라미터 바인딩만 사용(CWE-89).
     *
     * <p><b>tie-break 필수(correctness):</b> REG_DT DEFAULT 는 PostgreSQL {@code CURRENT_TIMESTAMP}
     * (트랜잭션 시작 시각 고정)라, 한 번의 증강 요청(단일 @Transactional)으로 적재된 여러 영상의
     * 모든 행이 동일 REG_DT 를 갖는다. MIN(REG_DT) 만으로 정렬하면 그룹 간 동률이 광범위하게
     * 발생해 OFFSET 페이징에서 페이지 경계 중복/누락(REVIEWER 워크리스트 누락)이 생긴다.
     * 그룹 키 {@code srcSn} 을 2차 정렬로 추가해 순서를 결정적(deterministic)으로 고정한다.
     */
    @Query(value = "SELECT a.srcSn FROM LsDataAug a GROUP BY a.srcSn ORDER BY MIN(a.regDt) DESC, a.srcSn DESC",
            countQuery = "SELECT COUNT(DISTINCT a.srcSn) FROM LsDataAug a")
    Page<Long> findDistinctSrcSnGroupsOrderByMinRegDtDesc(Pageable pageable);

    /** 그룹 페이징 2차 조회 — 페이지에 포함된 SRC_SN 들의 전체 증강 row 를 일괄 로드(N+1 회피). */
    @Query("SELECT a FROM LsDataAug a WHERE a.srcSn IN :srcSns")
    List<LsDataAug> findBySrcSnIn(@Param("srcSns") Collection<Long> srcSns);

    /**
     * Phase 4 — webhook race 흡수용 멱등 키 조회.
     * UNIQUE 제약 (uk_aug_idempotency_key) 위반 후 재조회 경로에서 사용.
     */
    Optional<LsDataAug> findByIdempotencyKey(String idempotencyKey);

    /**
     * 증강 콜백 재전송 멱등 앵커 — 외부 작업 ID(OTSD_JOB_ID) 조회.
     * UNIQUE 제약 (uk_aug_external_job_id) 위반(동시/오배송 재전송) 후 재조회 경로에서 사용.
     */
    Optional<LsDataAug> findByExternalJobId(String externalJobId);

    /**
     * 증강 콜백 처리 진입점 — 대상 증강 행을 {@link LockModeType#PESSIMISTIC_WRITE}(SELECT … FOR UPDATE)로
     * 잠금 조회한다 (MED #2 — 중복 콜백 레이스 차단).
     *
     * <p>1차 멱등 앵커(non-PENDING → skip)는 read-then-act 였다. 서로 다른 {@code otsd_job_id} 를 가진
     * 동시 콜백(TxA/TxB)이 둘 다 PENDING 을 관측하고 통과하면 이중 영상이 생성됐고(2차 UNIQUE 앵커는
     * job_id 가 다르면 미발동), 이 창을 닫기 위해 같은 {@code data_aug_sn} 을 행 잠금으로 직렬화한다.
     * 선행 트랜잭션이 PENDING→ACCEPTED 로 커밋할 때까지 후행은 대기하고, 잠금 획득 후 non-PENDING 을
     * 관측해 멱등 skip 한다. 잠금은 caller {@code @Transactional} 종료까지 유지된다.
     *
     * <p><b>락 순서(데드락 회피):</b> 콜백 경로는 항상 증강 행(본 메서드) → 부모 RAW 행
     * ({@code VideoRepository.findByRawSnForUpdate})의 일관된 순서로만 잠근다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM LsDataAug a WHERE a.dataAugSn = :dataAugSn")
    Optional<LsDataAug> findByDataAugSnForUpdate(@Param("dataAugSn") Long dataAugSn);
}
