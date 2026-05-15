package kr.co.cudo.authoring.stats.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 대시보드 통계 전용 집계 쿼리 (Phase 12 — SCR-DASH-001).
 *
 * <p>JPA 단일 Repository 에 다목적 쿼리를 모아두기보다 통계 도메인 한정으로 분리한다.
 * 실제 엔티티 CRUD 는 다른 Repository (VideoRepository, LsDataSrcRepository 등) 가 담당하고,
 * 본 Repository 는 GROUP BY/COUNT 계산만 수행한다.
 *
 * <p>Repository 의 베이스 엔티티는 임의로 LsDataRaw 를 지정한다 — 실제 메서드는 모두 JPQL @Query 로
 * 명시되므로 JpaRepository 의 기본 CRUD 는 사용되지 않는다.
 */
@ControlRepo
public interface StatsQueryRepository extends JpaRepository<LsDataRaw, Long> {

    /** 영상(LS_DATA_RAW) 의 EVNT_TYPE_CD 별 건수. NULL 코드는 제외. */
    @Query("""
            SELECT r.evntTypeCd AS code, COUNT(r) AS cnt
              FROM LsDataRaw r
             WHERE r.evntTypeCd IS NOT NULL
             GROUP BY r.evntTypeCd
            """)
    List<CountRow> countVideoByEventType();

    /**
     * 이벤트 유형별 프레임(이미지) 카운트.
     *
     * <p>LS_DATA_SRC ⨝ LS_DATA_RAW on (RAW_SN) — 영상 1건당 N프레임을 모두 합산하여
     * "이미지 데이터 개수" 카드의 분포 단위를 영상이 아닌 프레임으로 맞춘다.
     *
     * <p>LsDataSrc 와 LsDataRaw 간 관계는 객체 참조가 아닌 ID 참조(rawSn)이므로
     * JPQL 의 명시적 ON 절을 사용한다 (Hibernate 5.1+ ad-hoc JOIN).
     */
    @Query("""
            SELECT r.evntTypeCd AS code, COUNT(s) AS cnt
              FROM LsDataSrc s
              JOIN LsDataRaw r ON s.rawSn = r.rawSn
             WHERE r.evntTypeCd IS NOT NULL
             GROUP BY r.evntTypeCd
            """)
    List<CountRow> countFrameByEventType();

    /**
     * 검수 워크플로우 상태별 카운트 (PENDING / IN_REVIEW / APPROVED / REJECTED).
     * V34 이후 LS_PJT_DATA_STTS 의 PK 는 단일 RAW_DATA_ID.
     */
    @Query("""
            SELECT s.dataSttsCd AS code, COUNT(s) AS cnt
              FROM LsPjtDataStts s
             GROUP BY s.dataSttsCd
            """)
    List<CountRow> countByDataSttsCd();

    /** 누적 키프레임 (LS_DATA_SRC) 총 건수. */
    @Query("SELECT COUNT(s) FROM LsDataSrc s")
    long countCumulativeFrames();

    /**
     * 특정 사용자의 라벨링(작업) 상태별 카운트.
     * LS_PJT_USER_AUTHRT(LABELER) ⨝ LS_PJT_DATA_STTS on RAW_DATA_ID.
     */
    @Query("""
            SELECT s.dataSttsCd AS code, COUNT(s) AS cnt
              FROM LsPjtUserAuthrt a, LsPjtDataStts s
             WHERE a.userNo = :userNo
               AND a.taskTypeCd = 'LABELER'
               AND a.rawDataId = s.rawDataId
             GROUP BY s.dataSttsCd
            """)
    List<CountRow> countMyTaskByStatus(@Param("userNo") Long userNo);

    /**
     * 작업자별 통계 행 — SCR-STAT-002 전체 구축 현황 의 'workers' 표 데이터.
     *
     * <p>집계 정책:
     * <ul>
     *   <li>{@code labeled}        — 해당 사용자가 LABELER 로 배정된 LS_PJT_DATA_STTS 중
     *       APPROVED/IN_REVIEW/REJECTED 합계 (작업 진행한 영상 수).</li>
     *   <li>{@code reviewed}       — 별도 조회 (REVIEWER 배정 record 수, 서비스 레이어에서 합산).</li>
     *   <li>{@code approvedCount}  — APPROVED 만 카운트 → approvalRate 분자.</li>
     *   <li>{@code rejectedCount}  — REJECTED 만 카운트 → approvalRate 분모(approved+rejected).</li>
     * </ul>
     */
    @Query("""
            SELECT u.userNo AS userId,
                   u.userNm AS name,
                   SUM(CASE WHEN s.dataSttsCd IN ('APPROVED','IN_REVIEW','REJECTED') THEN 1 ELSE 0 END) AS labeled,
                   0L AS reviewed,
                   SUM(CASE WHEN s.dataSttsCd = 'APPROVED' THEN 1 ELSE 0 END) AS approvedCount,
                   SUM(CASE WHEN s.dataSttsCd = 'REJECTED' THEN 1 ELSE 0 END) AS rejectedCount
              FROM MngAcctUser u, LsPjtUserAuthrt a, LsPjtDataStts s
             WHERE u.userNo = a.userNo
               AND a.taskTypeCd = 'LABELER'
               AND a.rawDataId = s.rawDataId
             GROUP BY u.userNo, u.userNm
             ORDER BY SUM(CASE WHEN s.dataSttsCd IN ('APPROVED','IN_REVIEW','REJECTED') THEN 1 ELSE 0 END) DESC
            """)
    List<WorkerStatRow> findWorkerStats();

    /**
     * 사용자별 REVIEWER 배정 record 수 — workers 표의 {@code reviewed} 컬럼.
     * 결과 Map 형태로 합치는 작업은 서비스 레이어에서 수행.
     */
    @Query("""
            SELECT a.userNo AS code, COUNT(a) AS cnt
              FROM LsPjtUserAuthrt a
             WHERE a.taskTypeCd = 'REVIEWER'
             GROUP BY a.userNo
            """)
    List<UserCountRow> countReviewerByUser();

    /**
     * 코드(=GROUP BY 대상) + 건수 를 담는 단일 인터페이스 projection.
     * 이벤트 코드 / 상태 코드 모두 동일 형태라 공용으로 사용한다.
     */
    interface CountRow {
        String getCode();
        long getCnt();
    }

    /** userNo 키 + 건수 projection. */
    interface UserCountRow {
        Long getCode();
        long getCnt();
    }

    /** 작업자 통계 행 projection. */
    interface WorkerStatRow {
        Long getUserId();
        String getName();
        long getLabeled();
        long getReviewed();
        long getApprovedCount();
        long getRejectedCount();
    }
}
