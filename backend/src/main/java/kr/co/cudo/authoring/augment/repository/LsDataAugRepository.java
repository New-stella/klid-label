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
     * 원본 영상(RAW_SN)에 속한 전체 증강 row 조회 — 증강 결과 상태 집계(/{jobId}/result)용.
     *
     * <p>LS_DATA_AUG.SRC_SN 은 원본 영상의 대표프레임(LS_DATA_SRC.SRC_SN)이므로, 원본 RAW_SN 의
     * 프레임 SRC_SN 집합에 속하는 증강 row 를 서브쿼리로 환원한다. 파라미터 바인딩만 사용(CWE-89).
     */
    @Query("SELECT a FROM LsDataAug a WHERE a.srcSn IN "
            + "(SELECT s.srcSn FROM LsDataSrc s WHERE s.rawSn = :rawSn)")
    List<LsDataAug> findByOriginalRawSn(@Param("rawSn") Long rawSn);

    // 폐기 이력 — 구 findBySrcSnInAndAugProcSttsCdIn(활성 중복 1선 가드 전용 조회)은 제거됐다
    // (2026-07-31). 유일한 호출부였던 AugmentRequestService 의 중복 차단이 정책 폐기로 사라졌고,
    // DB 최종 방어였던 UK_LS_DATA_AUG_ACTVTN 도 V153 에서 DROP 됐다. 호출부 없는 조회를 남겨두면
    // "아직 중복을 막고 있다" 는 사라진 계약을 코드가 계속 주장한다.

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

    /**
     * 만료 스윕 후보 ② — <b>job 행이 한 건도 없는</b> 장기 PENDING 외부 증강 (적대검증 2차 MEDIUM-2).
     *
     * <h3>왜 job 축만으로는 부족한가</h3>
     * <p>기존 스윕은 {@code LS_DATA_AUG_JOB} 만 훑는다. 그런데 위탁 0건 실패 롤업이 예외로 끝나면
     * (DB 순단·커넥션 고갈·커밋 문맥 결함) <b>PENDING + job 0건</b>이 남는다. 이 고아 PENDING 을
     * 깨울 주체는 어디에도 없다 — 어떤 회수기도 집지 못하는 영구 고착이다.
     *
     * <h3>★ 비식별 신고 구간({@code 'F'})도 회수 대상이다 (2026-07-29)</h3>
     * <p>구 구현은 신고 구간을 "정책 보류(정상 대기)"로 보고 제외했다. 그 전제(신고 해제 이벤트가
     * 보류분을 재개한다)는 폐기됐고 재개 리스너도 삭제됐으므로, 제외하면 깨울 주체 없는 PENDING 고착만
     * 남는다. 신고와 무관하게 회수해 실패로 확정한다.
     *
     * <h3>정상 대기 오회수 방지 (fail-safe)</h3>
     * <ul>
     *   <li><b>위탁 직후 짧은 창</b>(job 선기록 전)은 {@code REG_DT < :cutoff}(무갱신 경과 임계, 최소 5분)로
     *       배제한다 — 위탁은 수 초~수십 초라 임계에 걸리지 않는다.</li>
     *   <li>해상도 파생({@code RESL_*})은 외부 위탁 대상이 아니므로 유형 allowlist 로 제외한다.</li>
     * </ul>
     *
     * <p>중복 회수(2노드 Active-Active)는 별도 클레임 컬럼 없이 인계 경로의 잠금 + 멱등 앵커
     * (증강 행 {@code FOR UPDATE} → non-PENDING skip)로 직렬화된다. 파라미터 바인딩만 사용(CWE-89).
     */
    @Query(value = """
            SELECT a.DATA_AUG_SN
              FROM LS_DATA_AUG a
             WHERE a.AUG_PROC_STTS_CD = :pendingStatus
               AND a.AUG_TYPE_CD IN (:externalAugTypes)
               AND a.REG_DT < :cutoff
               AND NOT EXISTS (SELECT 1 FROM LS_DATA_AUG_JOB j WHERE j.DATA_AUG_SN = a.DATA_AUG_SN)
             ORDER BY a.REG_DT ASC
             LIMIT :limit
            """, nativeQuery = true)
    List<Long> findOrphanPendingAugSns(@Param("pendingStatus") String pendingStatus,
                                       @Param("externalAugTypes") Collection<String> externalAugTypes,
                                       @Param("cutoff") java.time.LocalDateTime cutoff,
                                       @Param("limit") int limit);
}
