package kr.co.cudo.authoring.review.repository;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 검수 워크플로우 전용 LS_RAW_DATA_STATUS 조회.
 * (CRUD 자체는 LsRawDataStatusRepository 사용; 목록 검색/정렬/집계는
 * {@link ReviewQueryRepository} 가 QueryDSL 로 담당한다)
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
     *
     * <p><b>단일 원천</b>: {@link ReviewQueryRepository} 가 목록·count·KPI 집계의 WHERE 와 집계 버킷
     * ({@code CASE WHEN}) 을 모두 이 상수에서 파생시킨다. 어느 한쪽만 하드코딩하면 배치/작업 상태가
     * 집계에 섞이거나 {@code COUNT(*)} 와 버킷 합이 갈라진다.
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

}
