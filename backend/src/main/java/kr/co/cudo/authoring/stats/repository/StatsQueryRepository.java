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
     * V34 이후 LS_RAW_DATA_STATUS 의 PK 는 단일 RAW_DATA_ID.
     */
    @Query("""
            SELECT s.dataSttsCd AS code, COUNT(s) AS cnt
              FROM LsRawDataStatus s
             GROUP BY s.dataSttsCd
            """)
    List<CountRow> countByDataSttsCd();

    /** 누적 키프레임 (LS_DATA_SRC) 총 건수. */
    @Query("SELECT COUNT(s) FROM LsDataSrc s")
    long countCumulativeFrames();

    /**
     * 특정 사용자의 라벨링(작업) 상태별 카운트.
     * LS_TASK_ASSIGNMENT(LABELER) ⨝ LS_RAW_DATA_STATUS on RAW_DATA_ID.
     */
    @Query("""
            SELECT s.dataSttsCd AS code, COUNT(s) AS cnt
              FROM LsTaskAssignment a, LsRawDataStatus s
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
     *   <li>{@code labeled}        — 해당 사용자가 LABELER 로 배정된 LS_RAW_DATA_STATUS 중
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
              FROM MngAcctUser u, LsTaskAssignment a, LsRawDataStatus s
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
              FROM LsTaskAssignment a
             WHERE a.taskTypeCd = 'REVIEWER'
             GROUP BY a.userNo
            """)
    List<UserCountRow> countReviewerByUser();

    /**
     * SCR-STAT-001 — 특정 작업자(LABELER) 의 검수 상태별 카운트.
     * <p>{@link #countMyTaskByStatus(Long)} 와 동일 쿼리지만 의도 분리를 위해 별도 메서드로 둔다.
     */
    @Query("""
            SELECT s.dataSttsCd AS code, COUNT(s) AS cnt
              FROM LsTaskAssignment a, LsRawDataStatus s
             WHERE a.userNo = :userNo
               AND a.taskTypeCd = 'LABELER'
               AND a.rawDataId = s.rawDataId
             GROUP BY s.dataSttsCd
            """)
    List<CountRow> countWorkerTaskByStatus(@Param("userNo") Long userNo);

    /**
     * SCR-STAT-001 — 작업자에게 LABELER 로 배정된 raw 의 LsDataSrc 에 달린 모든 LsDataLbl 총 수.
     * 분모로 사용 (autoLabelRate, labelCount).
     */
    @Query("""
            SELECT COUNT(l)
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE s.rawSn IN (
                   SELECT a.rawDataId FROM LsTaskAssignment a
                    WHERE a.userNo = :userNo
                      AND a.taskTypeCd = 'LABELER'
             )
            """)
    long countLabelsForWorker(@Param("userNo") Long userNo);

    /**
     * SCR-STAT-001 — 작업자 배정 raw 의 라벨 중 자동 라벨(regUserNo IS NULL) 수.
     * <p>createManual() 는 regUserNo 를 설정하고 createAutoBbox()/createAutoPolygon() 은 설정하지 않으므로
     * regUserNo IS NULL 을 "자동 라벨" 프록시로 사용한다 (LsDataLblAiInfo 조인 비용 회피).
     */
    @Query("""
            SELECT COUNT(l)
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE l.regUserNo IS NULL
               AND s.rawSn IN (
                   SELECT a.rawDataId FROM LsTaskAssignment a
                    WHERE a.userNo = :userNo
                      AND a.taskTypeCd = 'LABELER'
             )
            """)
    long countAutoLabelsForWorker(@Param("userNo") Long userNo);

    /**
     * SCR-STAT-001 — 최근 N 일간 작업자 일별 완료 row (APPROVED 상태 영상 기준).
     * UPD_DT 가 APPROVED 로 전이된 시점이라고 가정 (review.transitionTo() 가 updDt 갱신).
     *
     * <p><b>dialect 호환성:</b> 일별 그룹화는 JPQL FUNCTION(TO_CHAR,...) 가 MariaDB 에 없어
     * 실행 실패하므로, raw 행을 그대로 반환하고 서비스 레이어 Java 측에서 DateTimeFormatter +
     * groupingBy 로 'YYYY-MM-DD' 키를 만든다. 데이터량은 단일 사용자/30일 윈도 → 수십 ~ 수백 행이라
     * 메모리 부담 없음.
     */
    @Query("""
            SELECT s.updDt AS updDt
              FROM LsTaskAssignment a, LsRawDataStatus s
             WHERE a.userNo = :userNo
               AND a.taskTypeCd = 'LABELER'
               AND a.rawDataId = s.rawDataId
               AND s.dataSttsCd = 'APPROVED'
               AND s.updDt >= :since
            """)
    List<DailyRawRow> findDailyCompletionForWorker(@Param("userNo") Long userNo,
                                                   @Param("since") java.time.LocalDateTime since);

    /**
     * SCR-STAT-001 — 최근 N 개월 작업자 월별 완료/반려 raw row.
     *
     * <p>dialect 호환성: TO_CHAR 제거. 서비스 레이어에서 'YYYY-MM' 키로 GROUP BY 하면서
     * dataSttsCd 에 따라 completed/rejected 분기.
     */
    @Query("""
            SELECT s.updDt AS updDt, s.dataSttsCd AS dataSttsCd
              FROM LsTaskAssignment a, LsRawDataStatus s
             WHERE a.userNo = :userNo
               AND a.taskTypeCd = 'LABELER'
               AND a.rawDataId = s.rawDataId
               AND s.dataSttsCd IN ('APPROVED','REJECTED')
               AND s.updDt >= :since
            """)
    List<MonthlyRawRow> findMonthlyForWorker(@Param("userNo") Long userNo,
                                             @Param("since") java.time.LocalDateTime since);

    /**
     * SCR-STAT-001 — 최근 N 개월 작업자 라벨 timestamps raw row.
     *
     * <p>월별 라벨 수 컬럼용. {@link #countLabelsForWorker(Long)} 와 동일하게
     * 작업자에게 LABELER 로 배정된 raw 의 모든 LsDataLbl (자동+수동) 을 대상으로
     * regDt timestamp 만 반환한다. 서비스 레이어에서 'YYYY-MM' 키로 GROUP BY.
     *
     * <p>dialect 호환성: JPQL FUNCTION(TO_CHAR,...) 가 MariaDB 미지원이라 raw 행 반환.
     * 데이터량: 단일 사용자/12개월 윈도 → 라벨 timestamp 만 select 이므로 N+1 없음.
     */
    @Query("""
            SELECT l.regDt AS regDt
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE l.regDt >= :since
               AND s.rawSn IN (
                   SELECT a.rawDataId FROM LsTaskAssignment a
                    WHERE a.userNo = :userNo
                      AND a.taskTypeCd = 'LABELER'
             )
            """)
    List<LabelTimestampRow> findMonthlyLabelTimestampsForWorker(@Param("userNo") Long userNo,
                                                                @Param("since") java.time.LocalDateTime since);

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

    /**
     * SCR-STAT-001 일별 완료 raw row projection.
     * <p>서비스 레이어에서 Java DateTimeFormatter 로 'YYYY-MM-DD' 키로 묶어 카운트한다.
     * (TO_CHAR JPQL FUNCTION 이 MariaDB 미지원이라 dialect 호환을 위해 raw 행을 반환.)
     */
    interface DailyRawRow {
        java.time.LocalDateTime getUpdDt();
    }

    /**
     * SCR-STAT-001 월별 완료/반려 raw row projection.
     * <p>서비스 레이어에서 'YYYY-MM' 키로 묶고 dataSttsCd 에 따라 completed/rejected 분기.
     */
    interface MonthlyRawRow {
        java.time.LocalDateTime getUpdDt();
        String getDataSttsCd();
    }

    /**
     * SCR-STAT-001 라벨 등록 시각 raw row projection.
     * <p>서비스 레이어에서 'YYYY-MM' 키로 묶어 월별 라벨 수 카운트.
     */
    interface LabelTimestampRow {
        java.time.LocalDateTime getRegDt();
    }
}
