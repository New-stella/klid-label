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
     * 검수 워크플로우 상태별 카운트 (PENDING / IN_REVIEW / APPROVED / REJECTED).
     * LS_PJT_DATA_STTS 는 (PJT_ID, RAW_DATA_ID) 복합 PK 라 동일 영상이 여러 PJT 에 매핑돼도 각각 1 건씩 계산된다.
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
     * LS_PJT_USER_AUTHRT(LABELER) ⨝ LS_PJT_DATA_STTS on (PJT_ID, RAW_DATA_ID).
     */
    @Query("""
            SELECT s.dataSttsCd AS code, COUNT(s) AS cnt
              FROM LsPjtUserAuthrt a, LsPjtDataStts s
             WHERE a.userNo = :userNo
               AND a.taskTypeCd = 'LABELER'
               AND a.pjtId = s.id.pjtId
               AND a.rawDataId = s.id.rawDataId
             GROUP BY s.dataSttsCd
            """)
    List<CountRow> countMyTaskByStatus(@Param("userNo") Long userNo);

    /**
     * 코드(=GROUP BY 대상) + 건수 를 담는 단일 인터페이스 projection.
     * 이벤트 코드 / 상태 코드 모두 동일 형태라 공용으로 사용한다.
     */
    interface CountRow {
        String getCode();
        long getCnt();
    }
}
