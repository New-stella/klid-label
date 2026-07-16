package kr.co.cudo.authoring.video.repository;

import jakarta.persistence.LockModeType;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
     * <p>증강 콜백은 부모의 {@code DE_IDNTF_YN='Y'} 를 확인한 뒤 증강본을 {@code MARKING_READY}(스트리밍
     * 가능)로 확정한다. 이 확인~커밋 사이에 동시 비식별 신고({@code DeidentReportService.report})가 부모를
     * {@code 'F'} 로 전이시키면, 신고로 노출본으로 되돌아간 부모에서 파생된 PII 증강본이 스트리밍되는 사고가
     * 났다(CWE-359). 신고 경로는 부모 RAW 행을 {@code markDeidentified('F')} 로 UPDATE 하므로, 본 행 잠금이
     * 신고의 UPDATE 와 같은 row 에서 경합한다. 증강 tx 가 먼저 잠그면 {@code 'Y'} 를 고정한 채 커밋할 때까지
     * 신고 UPDATE 가 직렬화되고, 신고가 먼저 {@code 'F'} 를 커밋하면 증강은 잠금 획득 후 {@code 'F'} 를 읽고
     * 생성을 보류한다. 잠금은 caller {@code @Transactional} 종료까지 유지된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM LsDataRaw r WHERE r.rawSn = :rawSn")
    Optional<LsDataRaw> findByRawSnForUpdate(@Param("rawSn") Long rawSn);

    Page<LsDataRaw> findAllByOrderByRegDtDesc(Pageable pageable);

    Page<LsDataRaw> findAllByDataSttsCdOrderByRegDtDesc(String dataSttsCd, Pageable pageable);

    /**
     * dataSttsCd 필터 — 정렬은 Pageable 의 Sort 로 위임한다 (정적 OrderBy 미적용).
     *
     * <p>{@code findAllByDataSttsCdOrderByRegDtDesc} 는 정적 {@code regDt DESC} 가 Pageable Sort 보다
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

    /**
     * 미배정(UNASSIGNED) 영상 목록 — 배치 상태 무관, LABELER 배정이 없는 모든 영상.
     *
     * <p>SCR-TASK-002 배정 전용 화면에서 신규 업로드(PENDING)·실패(FAILED) 영상까지 포함해
     * 배정 가능한 모든 미배정 영상을 노출하기 위한 용도(상태 조건 제거). FE 는 batchStatus 뱃지로
     * 상태를 구분 표시한다. LS_TASK_ASSIGNMENT 에 TASK_TYPE_CD='LABELER' row 가 없는 영상을
     * NOT EXISTS 로 필터링한다. 파라미터 바인딩만 사용 — SQL Injection 방어 (CWE-89).
     * 정렬은 Pageable 의 Sort 로 위임.
     */
    @Query("SELECT v FROM LsDataRaw v WHERE NOT EXISTS (SELECT 1 FROM LsTaskAssignment a " +
            "WHERE a.rawDataId = v.rawSn AND a.taskTypeCd = 'LABELER')")
    Page<LsDataRaw> findUnassigned(Pageable pageable);

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
}
