package kr.co.cudo.authoring.review.repository;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 검수 워크플로우 전용 LS_RAW_DATA_STATUS 조회.
 * (CRUD 자체는 LsRawDataStatusRepository 사용; 본 리포는 페이징/검색 메서드 제공)
 *
 * <p>V34 이후 LS_RAW_DATA_STATUS 의 PK 는 단일 RAW_DATA_ID — Pk(pjtId, rawDataId) 복합키 제거.
 */
@ControlRepo
public interface ReviewRepository extends JpaRepository<LsRawDataStatus, Long> {

    /**
     * 검수 목록에 노출 가능한 검수 워크플로우 상태 화이트리스트.
     *
     * <p>라벨러 검수 제출 시 {@code ASSIGNED/REJECTED/APPROVED → PENDING} 전이로 검수 워크플로우에
     * 진입한다(ReviewStateMachine·ReviewService#submit 근거). 따라서 REVIEWER 의 검수 목록에는
     * {@code PENDING/IN_REVIEW/APPROVED/REJECTED} 만 노출되어야 하며, 배치/작업 상태
     * ({@code ASSIGNED/BATCH_QUEUED/PROCESSING/COMPLETED/FAILED})는 검수 대상이 아니므로 제외한다.
     * 하드코딩 문자열 대신 {@link LsRawDataStatus} 상수로 구성해 상태 코드와 값 일치를 컴파일 타임에 고정한다.
     * (JPQL {@code @Query} 문자열에는 상수 인젝션이 불가하므로 컬렉션 파라미터 {@code :whitelist} 로 바인딩한다.)
     */
    List<String> REVIEW_STATUS_WHITELIST = List.of(
            LsRawDataStatus.STTS_PENDING,
            LsRawDataStatus.STTS_IN_REVIEW,
            LsRawDataStatus.STTS_APPROVED,
            LsRawDataStatus.STTS_REJECTED);

    /**
     * VIDEO_ID(=RAW_DATA_ID) 단위 검수 상태 조회.
     * PJT_ID 제거 후 RAW_DATA_ID 는 PK 이므로 단건만 존재한다.
     */
    Optional<LsRawDataStatus> findByRawDataId(Long rawDataId);

    /**
     * 검수 워크플로우 상태별 페이징 조회 (REVIEWER 의 검수 목록 화면용).
     *
     * <p>검수 워크플로우 화이트리스트({@code :whitelist})를 <b>상시</b> 적용해 배치/작업 상태
     * ({@code PROCESSING/BATCH_QUEUED/ASSIGNED/COMPLETED/FAILED}) row 가 검수 목록에 새어드는 것을 차단한다.
     * {@code status} 를 지정하면 화이트리스트와의 <b>교집합</b>만 반환한다 — 화이트리스트 밖 값
     * (예: PROCESSING)을 지정하면 빈 결과가 되어 누출이 원천 봉쇄된다.
     * 파라미터 바인딩만 사용하므로 SQL Injection(CWE-89) 위험이 없다.
     */
    @Query("""
            SELECT s FROM LsRawDataStatus s
             WHERE s.dataSttsCd IN :whitelist
               AND (:status IS NULL OR :status = '' OR s.dataSttsCd = :status)
             ORDER BY s.updDt DESC
            """)
    Page<LsRawDataStatus> searchByStatus(@Param("status") String status,
                                         @Param("whitelist") Collection<String> whitelist,
                                         Pageable pageable);

    /**
     * 검수 목록 진입점 — 검수 워크플로우 화이트리스트를 상시 적용한다(호출 측 시그니처 보존).
     * {@code status} 가 null/빈 문자열이면 화이트리스트 전체(PENDING/IN_REVIEW/APPROVED/REJECTED)를 반환한다.
     */
    default Page<LsRawDataStatus> searchByStatus(String status, Pageable pageable) {
        return searchByStatus(status, REVIEW_STATUS_WHITELIST, pageable);
    }
}
