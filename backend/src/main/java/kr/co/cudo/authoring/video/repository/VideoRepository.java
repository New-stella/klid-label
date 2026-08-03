package kr.co.cudo.authoring.video.repository;

import jakarta.persistence.LockModeType;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@ControlRepo
public interface VideoRepository extends JpaRepository<LsDataRaw, Long> {

    Optional<LsDataRaw> findByVmsClipId(String vmsClipId);

    /**
     * 부모 RAW 행을 {@link LockModeType#PESSIMISTIC_WRITE}(SELECT … FOR UPDATE)로 잠금 조회한다
     * (HIGH #1 — 증강본 비식별 게이트 TOCTOU 차단).
     *
     * <p>증강 콜백은 부모의 {@code DE_IDNTF_YN='Y'} 를 확인한 뒤 증강본을 {@code COMPLETED}(라벨 복사 완료·
     * 작업목록 노출)로 확정한다. 이 확인~커밋 사이에 동시 비식별 신고({@code DeidentReportService.report})가 부모를
     * {@code 'F'} 로 전이시키면, 신고로 노출본으로 되돌아간 부모에서 파생된 PII 증강본이 스트리밍되는 사고가
     * 났다(CWE-359). 신고 경로는 부모 RAW 행을 {@code markDeidentified('F')} 로 UPDATE 하므로, 본 행 잠금이
     * 신고의 UPDATE 와 같은 row 에서 경합한다. 증강 tx 가 먼저 잠그면 {@code 'Y'} 를 고정한 채 커밋할 때까지
     * 신고 UPDATE 가 직렬화되고, 신고가 먼저 {@code 'F'} 를 커밋하면 증강은 잠금 획득 후 {@code 'F'} 를 읽고
     * 생성을 보류한다. 잠금은 caller {@code @Transactional} 종료까지 유지된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM LsDataRaw r WHERE r.rawSn = :rawSn")
    Optional<LsDataRaw> findByRawSnForUpdate(@Param("rawSn") Long rawSn);

    /**
     * S7 — 비식별 처리 코드({@code DE_IDNTF_YN}) 단일 컬럼 projection.
     *
     * <p>신고 구간 판정({@code DeidentReportGate.isUnderDeidentReport})이 매 조회마다 호출하므로 전체 row
     * fetch 를 피한다(PK 인덱스 lookup + 1컬럼). 값이 NULL 인 행은 빈 Optional 로 온다(=통과).
     *
     * <p><b>판정은 자기 행 하나로 끝난다</b> — 파생영상은 원본의 신고와 무관하게 다루는 것이 확정 정책
     * (2026-07-29)이라 {@code ORGNL_RAW_SN} 을 함께 읽어 조상으로 올라가지 않는다(게이트 javadoc 참조).
     */
    @Query("SELECT r.deIdntfYn FROM LsDataRaw r WHERE r.rawSn = :rawSn")
    Optional<String> findDeIdntfYnByRawSn(@Param("rawSn") Long rawSn);

    /**
     * 배치 단계 상태({@code DATA_STTS_CD}) 단일 컬럼 projection (B-ISSUE-101).
     *
     * <p>수동 재처리 클레임({@code BatchTransitionService.tryClaimReprocessFromFailed})이 RAW 클레임
     * 0행의 <b>원인을 구분</b>하는 데 쓴다 — "남이 방금 선점(PROCESSING)" 과 "애초에 FAILED 가 아님"은
     * 다른 사건인데, 이를 구분하지 않고 작업상태 컬럼으로 폴백하면 상호배제가 깨진다(상세는 그 메서드).
     * 전체 row fetch 없이 PK 인덱스 lookup + 1컬럼만 읽는다.
     */
    @Query("SELECT r.dataSttsCd FROM LsDataRaw r WHERE r.rawSn = :rawSn")
    Optional<String> findDataSttsCdByRawSn(@Param("rawSn") Long rawSn);

    /**
     * 수동 배치 재처리 클레임용 조건부 원자 전이 (CWE-362, check-and-set).
     *
     * <p>배치 단계 상태(DATA_STTS_CD)가 {@code fromStatus}(FAILED)일 때만 {@code toStatus}(PROCESSING)로
     * 전이한다. 단일 SQL UPDATE 라 DB 가 동시 호출을 직렬화하므로, 수동 재기동(REVIEWER)과 자동 재시도
     * 폴러가 동일 rawSn 에 동시에 접근해도 정확히 1건만 영향 행수 1 을 받아 파이프라인이 이중 실행되지 않는다.
     * LS_DATA_RAW 는 {@code @Version} 이 없어 낙관적 잠금 충돌이 없다.
     *
     * @return 영향 행수 (1=클레임 성공, 0=FAILED 아님/이미 클레임됨)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE LsDataRaw r SET r.dataSttsCd = :toStatus, r.mdfcnDt = CURRENT_TIMESTAMP "
            + "WHERE r.rawSn = :rawSn AND r.dataSttsCd = :fromStatus")
    int claimReprocessFromFailed(@Param("rawSn") Long rawSn,
                                 @Param("fromStatus") String fromStatus,
                                 @Param("toStatus") String toStatus);

    /**
     * 수동 배치 재처리 클레임 <b>보상 롤백</b> (DEV_FIX H10) — PROCESSING → FAILED 조건부 원자 전이.
     *
     * <p>{@link #claimReprocessFromFailed} 로 FAILED→PROCESSING 을 선점했으나 이어지는
     * {@code BatchOrchestrator.process()} 가 검수 소유 작업 상태를 만나 {@code SKIPPED} 로 즉시 반환하면,
     * 파이프라인은 한 건도 실행되지 않고 {@code markRawDataFailed}/{@code markRawDataCompleted} 도 타지
     * 않아 <b>배치 단계 상태가 PROCESSING 으로 영구 고착</b>된다(이후 재처리는 stage/work 어느 쪽도 FAILED
     * 가 아니라 영구 409). 이를 막기 위해 클레임을 걸었던 호출자가 SKIPPED 를 받으면 본 메서드로 원상복구한다.
     *
     * <p>조건부(현재 PROCESSING 일 때만)라 그 사이 다른 주체가 상태를 바꿨으면 0행으로 안전하게 포기한다.
     *
     * @return 영향 행수 (1=보상 성공, 0=이미 다른 상태)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE LsDataRaw r SET r.dataSttsCd = :toStatus, r.mdfcnDt = CURRENT_TIMESTAMP "
            + "WHERE r.rawSn = :rawSn AND r.dataSttsCd = :fromStatus")
    int compensateReprocessClaim(@Param("rawSn") Long rawSn,
                                 @Param("fromStatus") String fromStatus,
                                 @Param("toStatus") String toStatus);

    /**
     * ffprobe 역류 back-fill — {@code LS_DATA_RAW.VDO_LEN_SEC} 가 비어 있을 때(NULL 또는 ≤0)만 초 단위
     * 길이로 채운다. 관제가 준 유효값(≥1)은 WHERE 가드로 보존한다(override 금지).
     *
     * <p><b>배경</b>: 주 적재 경로(관제 스캔, {@code TrainingVideoIngestTx})는 {@code MNG_CLIP_MASTER
     * .VDO_LEN_SEC} 가 NULL 이거나 1초 미만이면 VDO_LEN_SEC 를 채우지 못한다. 적재 직후 이미 수행되는
     * ffprobe({@code AsyncVideoMetaRunner} → {@link kr.co.cudo.authoring.video.service.VideoMetaService})
     * 의 duration 결과를 초로 환산해 역류시켜 데이터 정합을 맞춘다.
     *
     * <p><b>단일 컬럼 조건부 UPDATE(엔티티 load-modify-save 아님)인 이유</b>: {@code LS_DATA_RAW} 는
     * {@code @Version}/{@code @DynamicUpdate} 가 없어 엔티티 저장 시 <b>전체 컬럼</b>을 덮어쓴다. 본 back-fill
     * 은 적재 직후 선두 비식별({@code DeidentifyStep})과 <b>동시</b> 실행되므로(같은 {@code VideoIngestedEvent},
     * {@code @Async batchAsyncExecutor}), 엔티티 전체 저장을 쓰면 비식별이 방금 커밋한 {@code DE_IDENT_YN='Y'}/
     * {@code DATA_STTS_CD} 를 stale 스냅샷으로 되돌릴 수 있다(lost update, CWE-362 → PII 재노출 위험).
     * {@code VDO_LEN_SEC} + {@code MDFCN_DT} 만 SET 하는 조건부 UPDATE 는 그 컬럼들을 건드리지 않아 안전하며,
     * DB row 잠금이 동시 UPDATE 를 직렬화한다({@link #claimReprocessFromFailed}/{@link #updateStatus} 와
     * 동일한 검증된 패턴). "비어 있을 때만" 가드도 WHERE 에서 DB 가 원자 판정한다.
     *
     * @param rawSn 대상 영상 PK
     * @param sec   ffprobe 로 산출한 초 단위 길이(≥1, 호출자 보장)
     * @return 영향 행수 (1=back-fill 됨, 0=이미 유효값 보유/row 부재)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE LsDataRaw r SET r.durationSec = :sec, r.mdfcnDt = CURRENT_TIMESTAMP "
            + "WHERE r.rawSn = :rawSn AND (r.durationSec IS NULL OR r.durationSec <= 0)")
    int backfillDurationSecIfBlank(@Param("rawSn") Long rawSn, @Param("sec") int sec);

    Page<LsDataRaw> findAllByOrderByRegDtDesc(Pageable pageable);

    /**
     * 영상 처리 현황(GET /v1/videos) 전용 — 원본 RAW 만 조회(파생 RAW 제외, R1).
     *
     * <p>증강/해상도 파생 RAW 는 {@code ORGNL_RAW_SN} 이 원본을 가리키므로(NOT NULL), 처리 현황 목록에는
     * {@code ORGNL_RAW_SN IS NULL} 인 원본만 노출한다. 파생물은 증강 이력 화면(GET /v1/augments)에서만 본다.
     * 정렬은 Pageable 의 Sort 로 위임한다({@link #findAll(Pageable)} 대체). 작업 목록 쿼리
     * ({@link kr.co.cudo.authoring.assignment.repository.TaskBoardQueryRepository})에는 이 필터를
     * 적용하지 않는다 — 파생물도 배정·검수 대상이므로 작업 목록에는 유지된다(R2).
     */
    Page<LsDataRaw> findAllByOrgnlRawSnIsNull(Pageable pageable);

    /** 파생영상 목록(원본 1건 기준) — 파생 확정 상태 조회(E-ISSUE-24)용. */
    List<LsDataRaw> findAllByOrgnlRawSnOrderByRawSnAsc(Long orgnlRawSn);

    /**
     * 영상 처리 현황 — 배치 상태(dataSttsCd) 필터 + 원본 RAW 만(파생 RAW 제외, R1).
     * {@link #findAllByDataSttsCd(String, Pageable)} 의 원본전용 변형. 정렬은 Pageable Sort 로 위임.
     */
    Page<LsDataRaw> findAllByDataSttsCdAndOrgnlRawSnIsNull(String dataSttsCd, Pageable pageable);

    /**
     * 영상 처리 현황 — 검수 상태 필터 조합 + 원본 RAW 만(파생 RAW 제외, R1).
     *
     * <p>{@link #findAllWithReviewStatus}(포털/데이터마트 조회와 공유) 를 건드리지 않기 위한 처리 현황 전용
     * 쿼리다. {@code ORGNL_RAW_SN IS NULL} 로 파생물을 제외하고 나머지 조건은 동일하게 유지한다.
     *
     * <p><b>정렬은 Pageable 의 Sort 로 위임</b>한다 — 정적 {@code ORDER BY v.regDt DESC} 를 JPQL 에
     * 두면 그것이 1차 정렬로 고정돼 호출자가 준 Sort 가 보조 정렬로만 밀린다(같은 함정을
     * {@link #findAllByDataSttsCd} 주석이 설명한다). 대시보드 "최근 완료 영상"은 검수 완료 시각
     * ({@code s.updDt}) 으로 1차 정렬해야 하므로 조인 alias 를 정렬 대상으로 열어둔다.
     *
     * <p><b>하위호환</b>: 정렬 미지정 호출은 {@link #withDefaultRegDtDesc} 가 기존과 동일한
     * {@code regDt DESC} 를 채워 넣는다 — 영상 처리 현황/증강 요청 화면의 기본 순서는 불변이다.
     *
     * <p>파라미터 바인딩만 사용 — SQL Injection 방어(CWE-89). 정렬 키는 호출 측
     * ({@code VideoController}) 이 {@link kr.co.cudo.authoring.common.util.SortAllowlist#VIDEO_WITH_REVIEW_STATUS}
     * 로 검증·매핑한 값만 넘어온다(문자열 연결로 ORDER BY 를 만드는 지점 없음).
     */
    default Page<LsDataRaw> findOriginalsWithReviewStatus(String dataSttsCd,
                                                          String reviewStatusCd,
                                                          Pageable pageable) {
        return findOriginalsWithReviewStatusInternal(dataSttsCd, reviewStatusCd, withDefaultRegDtDesc(pageable));
    }

    @Query("SELECT v FROM LsDataRaw v JOIN LsRawDataStatus s ON s.rawDataId = v.rawSn " +
            "WHERE v.orgnlRawSn IS NULL " +
            "AND (:dataSttsCd IS NULL OR v.dataSttsCd = :dataSttsCd) " +
            "AND (:reviewStatusCd IS NULL OR s.dataSttsCd = :reviewStatusCd)")
    Page<LsDataRaw> findOriginalsWithReviewStatusInternal(@Param("dataSttsCd") String dataSttsCd,
                                                          @Param("reviewStatusCd") String reviewStatusCd,
                                                          Pageable pageable);

    /**
     * 정렬이 지정되지 않은 Pageable 에 기존 기본 정렬({@code regDt DESC})을 채운다.
     *
     * <p>JPQL 에서 정적 {@code ORDER BY} 를 걷어낸 대가로, 정렬 미지정 호출은 ORDER BY 가 아예 없는
     * 쿼리가 되어 순서가 비결정적이 된다. 기본 순서 보장은 이 지점 한 곳에서만 한다.
     *
     * <p>{@code unpaged} Pageable 은 page/size 를 읽을 수 없어 그대로 통과시킨다 — 현재 호출자는
     * 모두 paged 이며, unpaged 로 부를 경우 호출자가 Sort 를 직접 지정해야 한다.
     */
    private static Pageable withDefaultRegDtDesc(Pageable pageable) {
        if (pageable == null || !pageable.isPaged() || pageable.getSort().isSorted()) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "regDt"));
    }

    /**
     * dataSttsCd 필터 — 정렬은 Pageable 의 Sort 로 위임한다 (정적 OrderBy 미적용).
     *
     * <p>정적 {@code OrderByRegDtDesc} 파생 메서드는 정적 {@code regDt DESC} 가 Pageable Sort 보다
     * 우선해 외부 정렬 키(예: capturedAt→shtDt)가 보조 정렬로만 밀린다. 본 메서드는 컨트롤러가
     * allowlist 로 검증·매핑한 Sort 를 1차 정렬로 적용하기 위해 사용한다.
     */
    Page<LsDataRaw> findAllByDataSttsCd(String dataSttsCd, Pageable pageable);

    /**
     * 영상 목록 + 검수 상태 필터(LS_RAW_DATA_STATUS) 조합 조회.
     *
     * <p>증강 요청 화면(SCR-AUG-001)에서 검수 완료(APPROVED) 영상만 노출하기 위한 용도.
     * 검수 상태는 영상 레코드와 별도 테이블에 저장되어 있으므로 명시적 JPQL JOIN ON 으로 결합한다
     * (Hibernate 6 HHH90003004 — 객체 참조 없는 implicit join 경고 회피).
     *
     * <p>INNER JOIN 특성상 LS_RAW_DATA_STATUS row 가 없는 영상(검수 미시작)은
     * reviewStatusCd 필터가 지정된 호출에서 자동 제외된다.
     *
     * <p>두 필터 모두 null 일 수 있으며 각각 조건부로 적용된다 (AND 결합).
     * 파라미터 바인딩만 사용 — SQL Injection 방어 (CWE-89).
     */
    @Query("SELECT v FROM LsDataRaw v JOIN LsRawDataStatus s ON s.rawDataId = v.rawSn " +
            "WHERE (:dataSttsCd IS NULL OR v.dataSttsCd = :dataSttsCd) " +
            "AND (:reviewStatusCd IS NULL OR s.dataSttsCd = :reviewStatusCd) " +
            "ORDER BY v.regDt DESC")
    Page<LsDataRaw> findAllWithReviewStatus(@Param("dataSttsCd") String dataSttsCd,
                                            @Param("reviewStatusCd") String reviewStatusCd,
                                            Pageable pageable);

    /**
     * 미배정(UNASSIGNED) 영상 목록 — 지정 배치 상태(dataSttsCd)이면서 LABELER 배정이 없는 영상만.
     *
     * <p>SCR-TASK-002 배정 전용 화면에서 작업자가 아직 배정되지 않은 영상만 노출하는 용도.
     * LS_TASK_ASSIGNMENT 에 TASK_TYPE_CD='LABELER' row 가 없는 영상을 NOT EXISTS 로 필터링한다.
     * 파라미터 바인딩만 사용 — SQL Injection 방어 (CWE-89). 정렬은 Pageable 의 Sort 로 위임.
     */
    @Query("SELECT v FROM LsDataRaw v WHERE v.dataSttsCd = :dataSttsCd " +
            "AND NOT EXISTS (SELECT 1 FROM LsTaskAssignment a " +
            "WHERE a.rawDataId = v.rawSn AND a.taskTypeCd = 'LABELER')")
    Page<LsDataRaw> findUnassignedByDataSttsCd(@Param("dataSttsCd") String dataSttsCd, Pageable pageable);

    /** 개발 전용: DATA_STTS_CD 기준 가장 오래된 1건 (REG_DT 오름차순). */
    Optional<LsDataRaw> findFirstByDataSttsCdOrderByRegDtAsc(String dataSttsCd);

    /** 개발 전용: DATA_STTS_CD 기준 전체 목록. */
    List<LsDataRaw> findAllByDataSttsCd(String dataSttsCd);

    @Modifying
    @Transactional("controlTransactionManager")
    @Query("UPDATE LsDataRaw r SET r.dataSttsCd = :status, r.mdfcnDt = CURRENT_TIMESTAMP WHERE r.rawSn = :rawSn")
    void updateStatus(@Param("rawSn") Long rawSn, @Param("status") String status);

    /**
     * 영상(rawSn) 별 최신 내보내기 요약.
     *
     * <p>V34 (PJT_ID 제거) 이후 export 소스 테이블(LS_DATA_SET)에 영상(RAW_DATA_ID) 매핑 컬럼이
     * 없어 영상별 export 매핑은 사문화됐고, V86 에서 해당 테이블은 삭제됐다(Export 는 저작도구 범위 외).
     * 본 메서드는 API 응답 계약(exportStatus 등) 호환을 위해 stub 으로 남되 항상 빈 결과를 반환한다.
     */
    default List<VideoExportProjection> findLatestExportsByRawSns(Collection<Long> rawSns) {
        if (rawSns == null || rawSns.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.emptyList();
    }

    /**
     * 페이지의 rawSn 들에 대해 (rawSn, cctvNm, vmsCctvId) 를 한 번에 조회 (N+1 회피).
     *
     * <p>FE WORKER/REVIEWER 작업 목록 영상명 컬럼에 표시할 CCTV 명을 일괄 lookup 하기 위한 용도.
     * LS_DATA_RAW LEFT JOIN MNG_RESOURCE_CCTV 로 결합한다. MNG_RESOURCE_CCTV 시드가 없는 환경
     * (또는 매핑이 끊긴 영상) 에서는 cctvNm 이 null 로 반환된다.
     *
     * <p>반환 행: {@code [Long rawSn, String cctvNm, String vmsCctvId]}.
     * 호출 측에서 cctvNm 이 null/blank 일 때 vmsCctvId 로 폴백한다.
     */
    default List<Object[]> findCctvNamesByRawSns(Collection<Long> rawSns) {
        if (rawSns == null || rawSns.isEmpty()) {
            return Collections.emptyList();
        }
        return findCctvNamesByRawSnsInternal(rawSns);
    }

    @Query(value = """
            SELECT r.RAW_SN AS rawSn,
                   c.CCTV_NM AS cctvNm,
                   r.VMS_CCTV_ID AS vmsCctvId
            FROM LS_DATA_RAW r
            LEFT JOIN MNG_RESOURCE_CCTV c ON c.VMS_CCTV_ID = r.VMS_CCTV_ID
            WHERE r.RAW_SN IN (:rawSns)
            """, nativeQuery = true)
    List<Object[]> findCctvNamesByRawSnsInternal(@Param("rawSns") Collection<Long> rawSns);

    /**
     * 페이지의 rawSn 들에 대해 (rawSn, eventName, eventTypeCd) 를 한 번에 조회 (N+1 회피).
     *
     * <p>FE 검수/작업 목록의 이벤트 컬럼에 표시할 이벤트 정보를 일괄 lookup 하기 위한 용도.
     * 현 단계에서는 {@code EVNT_TYPE_CD} 값을 eventName/eventTypeCd 양쪽에 동일하게 반환한다
     * (VideoSummaryResponse 와 동일한 정책 — 코드값 fallback). 향후 이벤트 마스터 테이블이
     * 추가되면 JOIN 으로 한글명을 가져오도록 확장 가능하다.
     *
     * <p>반환 행: {@code [Long rawSn, String eventName, String eventTypeCd]}.
     * 영상 메타가 없거나 EVNT_TYPE_CD 가 null 이면 호출 측에서 키가 누락된 채 반환된다.
     */
    default List<Object[]> findEventInfoByRawSns(Collection<Long> rawSns) {
        if (rawSns == null || rawSns.isEmpty()) {
            return Collections.emptyList();
        }
        return findEventInfoByRawSnsInternal(rawSns);
    }

    @Query(value = """
            SELECT r.RAW_SN AS rawSn,
                   r.EVNT_TYPE_CD AS eventName,
                   r.EVNT_TYPE_CD AS eventTypeCd
            FROM LS_DATA_RAW r
            WHERE r.RAW_SN IN (:rawSns)
            """, nativeQuery = true)
    List<Object[]> findEventInfoByRawSnsInternal(@Param("rawSns") Collection<Long> rawSns);

    /**
     * A-6 — 지정 파일 경로를 <b>다른 행</b>이 참조하고 있는지 센다(공유 파일 오삭제 방지).
     *
     * <p>구 경로 규약({@code videos/resolution/{parent}/{preset}.mp4})은 {@code (부모, 프리셋)} 만으로
     * 키잉돼 <b>같은 파일을 여러 파생 RAW 가 공유</b>했다(실측 5건 공유). 이관 후 구 파일을 지울 때 다른
     * 파생이 아직 그 경로를 가리키고 있으면 삭제해선 안 된다.
     *
     * <p>{@code LS_DATA_RAW.RAW_FILE_PATH_NM} 과 {@code LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM}
     * (스트리밍·데이터마트가 읽는 비식별 영상 경로) 양쪽을 모두 센다. 파라미터 바인딩만 사용(CWE-89).
     */
    @Query(value = """
            SELECT (SELECT COUNT(*) FROM LS_DATA_RAW r
                     WHERE r.RAW_FILE_PATH_NM = :filePath AND r.RAW_SN <> :rawSn)
                 + (SELECT COUNT(*) FROM LS_DEIDENT_PROC_LOG l
                     WHERE l.DE_IDNTF_FILE_PATH_NM = :filePath AND l.DATA_RAW_SN <> :rawSn)
            """, nativeQuery = true)
    long countOtherReferencesToFilePath(@Param("filePath") String filePath, @Param("rawSn") Long rawSn);

    /**
     * <b>유예 삭제 안전 조건</b> — 지정 파일 경로를 <b>아직 어느 행이라도</b> 참조하고 있는지 센다.
     *
     * <p>유예 삭제(grace period) 스윕은 이관 시점이 아니라 <b>삭제 직전</b>에 안전을 재판정한다.
     * 삭제 조건은 ①DB 가 이미 새 경로를 가리킨다(=이 경로 참조 0) ②다른 행도 참조하지 않는다 이며,
     * 두 조건은 "총 참조 수 0" 하나로 동치다. 이관 시점에 통과했더라도 그 사이 롤백·재작성으로 다시
     * 참조가 생겼을 수 있으므로 <b>삭제 직전 재확인</b>이 필요하다.
     *
     * <p>영상 경로 2곳({@code LS_DATA_RAW.RAW_FILE_PATH_NM},
     * {@code LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM}) 뿐 아니라 <b>프레임 경로</b>
     * ({@code LS_DATA_SRC.SRC_FILE_PATH_NM}/{@code DE_IDNTF_SRC_FILE_PATH_NM}) 도 센다 —
     * 유예 대기 목록에는 프레임 파일이 포함되므로 프레임 참조를 빼면 아직 서빙 중인 프레임을 지울 수 있다.
     * 파라미터 바인딩만 사용(CWE-89).
     */
    @Query(value = """
            SELECT (SELECT COUNT(*) FROM LS_DATA_RAW r
                     WHERE r.RAW_FILE_PATH_NM = :filePath)
                 + (SELECT COUNT(*) FROM LS_DEIDENT_PROC_LOG l
                     WHERE l.DE_IDNTF_FILE_PATH_NM = :filePath)
                 + (SELECT COUNT(*) FROM LS_DATA_SRC s
                     WHERE s.SRC_FILE_PATH_NM = :filePath
                        OR s.DE_IDNTF_SRC_FILE_PATH_NM = :filePath)
            """, nativeQuery = true)
    long countReferencesToFilePath(@Param("filePath") String filePath);

    /**
     * 감사 대상 프레임 커서 조회 — 비식별 경로가 있는 프레임을 {@code SRC_SN} 오름차순으로 페이징한다.
     *
     * <p><b>H-4</b>: 감사 판정 자체는 SQL 이 아니라 {@code StorageSubtreePolicy.verifyDeidentifiedFile}
     * (서빙과 동일 판정기)이 수행한다. SQL 로 근사(문자열 POSITION)하면 ①base 무검증 ②세그먼트가 아닌
     * 부분일치 ③파일시스템 무검증 3중 비동치가 생겨 "영향 없음" 주장을 입증할 수 없다. 여기서는
     * <b>행만</b> 넘긴다.
     *
     * <p><b>A-3 (뷰 게이트와의 동치)</b>: 빈 문자열/공백 경로는 <b>결측</b>으로 취급해 감사 입력에서
     * 제외한다({@code TRIM(...) <> ''}). V133 뷰 게이트와 {@link #findRawSnsExcludedByFrameViewGate}
     * 가 빈 문자열을 결측(정상 통과)으로 다루는데 감사만 이를 포함시켜 {@code BLANK} 를 위반으로
     * 집계하면, H-4 가 없앴다는 "SQL 근사 vs 코드 판정" 비동치가 <b>입력 단계</b>에 그대로 남는다.
     * 결측은 PII 노출이 아니라 데이터 결측이므로 위반 집계 대상이 아니다.
     *
     * <p>반환 행: {@code [Long srcSn, Long rawSn, String deidPath]}.
     */
    @Query(value = """
            SELECT s.SRC_SN, s.RAW_SN, s.DE_IDNTF_SRC_FILE_PATH_NM
            FROM LS_DATA_SRC s
            WHERE s.SRC_SN > :cursor
              AND s.DE_IDNTF_SRC_FILE_PATH_NM IS NOT NULL
              AND TRIM(s.DE_IDNTF_SRC_FILE_PATH_NM) <> ''
            ORDER BY s.SRC_SN
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findDeidFramePathsAfter(@Param("cursor") Long cursor, @Param("limit") int limit);

    /**
     * G-2 (D-ISSUE-46 정책 C) — 현행 {@code V_COMPLETED_FRAME} 게이트로 <b>뷰에서 제외되는</b> rawSn 목록.
     *
     * <p>게이트는 결함 형태(원본 경로를 비식별 경로로 노출 = 두 값이 <b>동일</b>)에 한정한다(M-1).
     * 비식별 경로 결측({@code DEID IS NULL})은 PII 노출이 아니라 데이터 결측이므로 제외 대상이 아니며,
     * 뷰에도 종전대로 노출된다. 빈 문자열은 코드({@code isBlank})와 동일하게 결측으로 취급한다.
     *
     * <p>반환 행: {@code [Long rawSn, Long frameCount]} — APPROVED 영상만(뷰 노출 조건과 동일).
     */
    @Query(value = """
            SELECT s.RAW_SN, COUNT(*) AS frameCount
            FROM LS_DATA_SRC s
            WHERE EXISTS (
                    SELECT 1 FROM LS_RAW_DATA_STATUS st
                     WHERE st.RAW_DATA_ID = s.RAW_SN AND st.DATA_STTS_CD = 'APPROVED')
              AND s.SRC_FILE_PATH_NM IS NOT NULL
              AND TRIM(s.SRC_FILE_PATH_NM) <> ''
              AND s.DE_IDNTF_SRC_FILE_PATH_NM IS NOT NULL
              AND TRIM(s.DE_IDNTF_SRC_FILE_PATH_NM) <> ''
              AND s.DE_IDNTF_SRC_FILE_PATH_NM = s.SRC_FILE_PATH_NM
            GROUP BY s.RAW_SN
            ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> findRawSnsExcludedByFrameViewGate();

    /**
     * 해상도 파생 백필 전용 — <b>파생 RAW 에 한해</b> 영상 파일 경로를 새 비식별 저장소 경로로 교체한다.
     * {@code ORGNL_RAW_SN IS NOT NULL} 조건으로 원본 영상 경로는 절대 변경될 수 없다(원본 보존 원칙).
     */
    @Modifying
    @Query(value = """
            UPDATE LS_DATA_RAW
               SET RAW_FILE_PATH_NM = :filePath
             WHERE RAW_SN = :rawSn
               AND ORGNL_RAW_SN IS NOT NULL
            """, nativeQuery = true)
    int updateDerivativeVideoPath(@Param("rawSn") Long rawSn, @Param("filePath") String filePath);

    /**
     * E-ISSUE-23 — 확정 실패한 파생 RAW 고아 행 삭제. <b>파생(ORGNL_RAW_SN NOT NULL) + FAILED</b>
     * 두 조건을 SQL 조건으로 함께 걸어, 삭제 직전 상태가 바뀐 행(정상 확정으로 전이)은 조건 불일치로
     * 0건 삭제된다(경합 안전).
     *
     * <p><b>M-5 (TOCTOU)</b>: "프레임 없음" 조건도 <b>DELETE 문 자체</b>에 {@code NOT EXISTS} 로 건다.
     * 호출측의 사전 {@code countByRawSn} 검사만으로는 검사~삭제 사이에 커밋된 프레임 INSERT 를 놓쳐
     * ({@code LS_DATA_SRC.RAW_SN} 에 FK 가 없어 DB 도 막아주지 않는다) RAW 없는 고아 프레임·라벨이 남는다.
     * 단일 문장 안에서 조건을 평가하면 그 창이 닫힌다.
     */
    @Modifying
    @Query(value = """
            DELETE FROM LS_DATA_RAW
             WHERE RAW_SN = :rawSn
               AND ORGNL_RAW_SN IS NOT NULL
               AND DATA_STTS_CD = 'FAILED'
               AND NOT EXISTS (SELECT 1 FROM LS_DATA_SRC s WHERE s.RAW_SN = :rawSn)
            """, nativeQuery = true)
    int deleteFailedDerivative(@Param("rawSn") Long rawSn);
}
