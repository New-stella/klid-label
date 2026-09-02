package kr.co.cudo.authoring.stats.repository;

import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.InternalWorkScope;
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

    /**
     * 자동 생성 라벨 판정식 — {@code autoLabelRate} 를 내는 <b>모든</b> 쿼리가 이 조각 하나만 쓴다.
     * 별칭 {@code l} 인 {@link kr.co.cudo.authoring.batch.entity.LsDataLbl} 에 대해 평가된다.
     *
     * <p><b>왜 조각을 공유하나</b>: 같은 이름의 지표를 전체 통계(SCR-STAT-002)와 작업자 통계
     * (SCR-STAT-001)가 각자 판정하면 한쪽만 고쳤을 때 두 화면이 다른 값을 낸다. 실제로 그랬다 —
     * 작업자 통계는 {@code REG_USER_NO IS NULL} 프록시로 세고 있었다. 새 소비자가 생기면 자기
     * 판정을 쓰지 말고 이 상수를 참조한다.
     *
     * <p><b>왜 등록자 프록시가 아니라 자동 생성 플래그인가</b>: 등록자를 남기지 않고 만들어지는
     * 라벨이 자동 생성 외에도 있다 — 버전 롤백 복원({@code LsDataLbl.createRestored})은
     * {@code REG_USER_NO} 를 채우지 않으므로 사람이 그렸던 라벨까지 자동으로 오분류된다.
     * 영속된 자동 생성 플래그는 {@code LS_DATA_LBL.AUTO_LBL_YN} 하나뿐이다.
     *
     * <p><b>V6 — EXISTS 서브쿼리가 컬럼 술어가 됐다</b>: 판정 축이 {@code LS_DATA_LBL_AI_INFO} 에
     * 있던 시절에는 그 테이블의 {@code DATA_LBL_SN} 에 UNIQUE 가 없어(한 라벨에 여러 행 가능) JOIN 이
     * <b>분모를 중복 계상</b>했고, 그래서 EXISTS 여야 했다. 흡수 후에는 라벨 1건 = 값 1개라 중복
     * 계상 자체가 성립하지 않는다. 판정 결과는 같다(V6 헤더 「결정적 규칙」 — 다중 행에서 'Y' 우선 채택).
     *
     * <p><b>앞뒤 {@code \s} 는 의도된 것이다</b>: 텍스트 블록은 각 줄의 <b>후행 공백을 제거</b>하므로
     * {@code "... WHERE " + 상수} 처럼 이어 붙이면 {@code WHEREEXISTS} 가 되어 JPQL 파싱이 깨진다.
     * 이 상수가 스스로 앞뒤 공백을 보장해 호출부가 공백을 신경 쓰지 않게 한다.
     */
    String AUTO_LABEL_PREDICATE = """
            \sl.autoLblYn = 'Y'\s""";

    /**
     * 진행 중(= 아직 완료되지 않은) 작업 판정식 — {@code inProgress} 를 내는 <b>모든</b> 쿼리가 이
     * 조각 하나만 쓴다. 별칭 {@code s} 인 {@link kr.co.cudo.authoring.assignment.entity.LsRawDataStatus}
     * 에 대해 평가된다.
     *
     * <p><b>왜 조각을 공유하나</b>: {@link #AUTO_LABEL_PREDICATE} 와 같은 이유다. 같은 이름의 지표를
     * 전체 통계(SCR-STAT-002)와 작업자 통계(SCR-STAT-001)가 각자 판정하면 두 화면이 같은 작업자에게
     * 다른 숫자를 낸다. 실제로 그랬다 — 작업자 통계는 {@code ASSIGNED + IN_REVIEW} 를 <b>열거</b>해
     * 세고 있어서 {@code PENDING}/{@code BATCH_QUEUED}/{@code PROCESSING}/{@code REJECTED}/
     * {@code FAILED} 가 완료에도 진행에도 안 잡혔다(특히 <b>반려</b>는 전체 통계에서는 진행 중인데
     * 작업자 통계에서는 어디에도 없었다).
     *
     * <p><b>왜 열거가 아니라 부정형인가</b>: 이 저장소에서 검수 완료로 쓰이는 상태값은
     * {@code APPROVED} 하나다({@code LsRawDataStatus.STTS_COMPLETED} 로 전이하는 코드가 없다).
     * 진행 상태를 열거하면 <b>새 상태값이 생길 때 화면에서 조용히 사라지므로</b> 부정형이 fail-safe 다.
     * 반려도 작업자가 다시 손봐야 하는 건이라 진행 중에 포함하는 것이 사용자 관점에 맞는다.
     *
     * <p>앞뒤 {@code \s} 가 의도된 것인 이유는 {@link #AUTO_LABEL_PREDICATE} 주석과 같다.
     */
    String IN_PROGRESS_PREDICATE = """
            \ss.dataSttsCd <> 'APPROVED'\s""";

    /**
     * 영상(LS_DATA_RAW) 의 EVNT_TYPE_CD 별 건수. NULL 코드는 제외.
     *
     * <p><b>채널 축을 명시한다</b>(ADR-058) — 지금까지 이 집계가 포털 업로드 자산을 집지 않은 것은
     * 막아서가 아니라 <b>포털 자산에 이벤트 유형이 채워지지 않는다는 우연</b> 때문이었다. 그 값이 채워지는
     * 순간 관제 대시보드 분포에 남의 자산이 섞이는데, 오류가 아니라 <b>건수만 늘어</b> 조용히 틀린다.
     * 판정은 채널 판별의 단일 소유자 {@link InternalWorkScope#INTERNAL_JPQL} 로만 한다
     * (별칭 규약 {@code LsDataRaw = r}).
     */
    @Query("""
            SELECT r.evntTypeCd AS code, COUNT(r) AS cnt
              FROM LsDataRaw r
             WHERE r.evntTypeCd IS NOT NULL
            """ + InternalWorkScope.INTERNAL_JPQL + """
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
     *
     * <p><b>채널 축을 명시한다</b> — 근거는 {@link #countVideoByEventType()} 주석과 같다(같은 우연에
     * 기대고 있었다). 폐기 프레임 술어는 여기 붙이지 않는다 — 이 집계는 <b>전체 기준</b> 축이라 폐기를
     * 빼면 수집 상황을 알 수 없어진다(위 섹션 주석의 R4 규칙).
     */
    @Query("""
            SELECT r.evntTypeCd AS code, COUNT(s) AS cnt
              FROM LsDataSrc s
              JOIN LsDataRaw r ON s.rawSn = r.rawSn
             WHERE r.evntTypeCd IS NOT NULL
            """ + InternalWorkScope.INTERNAL_JPQL + """
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

    // ------------------------------------------------------------------
    // 검수완료(APPROVED) 한정 집계 — "이미지/영상 학습데이터" 카드용.
    //
    // 위 countCumulativeFrames()/countVideoByEventType() 은 검수 여부와 무관한 "전체 기준"
    // 이며 의미를 그대로 유지한다. 아래 3종만 LS_RAW_DATA_STATUS 로 좁힌다.
    //
    // ★INNER JOIN 필수: LS_RAW_DATA_STATUS 행은 배정 시점에 lazy 생성되므로 LS_DATA_RAW 전건과
    //   1:1 이 아니다. 상태 행이 없는(=배정 전) 영상이 approved 집계에서 제외되는 것이 요구되는
    //   동작이다. LEFT JOIN 으로 바꾸면 미배정 영상이 학습데이터로 계상된다.
    //
    // ★N+1 금지: 각 메서드는 단일 COUNT/GROUP BY 1 회다. LS_DATA_SRC 는 목표 규모 10만행이라
    //   영상 순회 건별 조회는 허용되지 않는다. 조인 키 RAW_SN 은 IX_LS_DATA_SRC_RAW(V4) 로,
    //   상태 조인은 LS_RAW_DATA_STATUS PK(RAW_DATA_ID) 로 각각 뒷받침된다.
    //
    // ★상태 문자열은 서버 상수(LsRawDataStatus.STTS_APPROVED)만 바인딩한다 — 사용자 입력을
    //   받지 않으며 @Param 바인딩이라 문자열 연결이 없다(CWE-89).
    //
    // ★★폐기 프레임 제외는 "프레임 단위" 집계에만 붙는다 (R4 — 2026-08-17 확정).
    //   이 화면 수치는 "학습데이터로 확정된 분량"을 뜻하는데, 학습데이터 산출
    //   (DatasetExportTxService → LsDataSrcRepository.findNotDiscardedByRawSnOrderByFrameNoAsc)과
    //   데이터마트 뷰(v_completed_frame 의 COALESCE(dscd_yn,'N') <> 'Y')가 폐기 프레임을 구조적으로
    //   빼므로, 같은 기준이 아니면 화면 숫자가 실제 산출 분량보다 크다.
    //
    //   판정은 집계마다 새로 쓰지 않고 밖으로 나가는 모든 조회가 공유하는 단일 조각
    //   LsDataSrcRepository.NOT_DISCARDED 하나만 붙인다(별칭 규약 LsDataSrc = s).
    //
    //   ⚠ 붙이는 집계 / 붙이지 않는 집계가 명확히 갈린다 — "일관성"을 이유로 통일하지 말 것:
    //     · 붙인다 — countFramesByDataSttsCd(프레임 카드 주 수치)
    //                countFrameByEventTypeAndStatus(그 카드와 짝인 프레임 단위 이벤트 분포)
    //     · 붙이지 않는다 — countVideoByEventTypeAndStatus 등 <영상 단위> 집계.
    //       폐기는 프레임 축이라 영상 건수를 바꾸지 않는다(프레임이 폐기돼도 그 영상은 승인된 영상이다).
    //     · 붙이지 않는다 — countCumulativeFrames()/countFrameByEventType() 등 <전체 기준> 집계.
    //       그쪽은 "수집한 전체 분량"이라는 다른 축이고, 폐기를 빼면 수집 상황을 알 수 없어진다.
    // ------------------------------------------------------------------

    /**
     * 지정 검수 상태 영상에 속한 프레임(LS_DATA_SRC) 총 건수. 상태 행이 없는 영상은 자연 제외.
     *
     * <p>"이미지 학습데이터" 카드의 주 수치이므로 <b>폐기 프레임을 제외</b>한다(위 섹션 주석의 R4 규칙).
     * 폐기를 포함한 전체 분량은 {@link #countCumulativeFrames()} 가 담당하며 두 값은 다른 축이다.
     */
    @Query("""
            SELECT COUNT(s)
              FROM LsDataSrc s
              JOIN LsRawDataStatus st ON st.rawDataId = s.rawSn
             WHERE st.dataSttsCd = :status
            """ + LsDataSrcRepository.NOT_DISCARDED)
    long countFramesByDataSttsCd(@Param("status") String status);

    /**
     * 지정 검수 상태 영상의 이벤트 유형별 <b>영상</b> 건수. NULL 코드는 제외.
     *
     * <p><b>폐기 프레임 술어를 붙이지 않는다</b> — 폐기는 프레임 축이라 영상 건수를 바꾸지 않는다
     * (프레임 일부가 폐기돼도 그 영상은 여전히 승인된 영상 1건이다). 이 쿼리에는 {@code LsDataSrc}
     * 조인 자체가 없다.
     *
     * <p><b>채널 술어({@link InternalWorkScope#INTERNAL_JPQL})도 붙이지 않는다 — 의도된 것이다</b>(ADR-058).
     * {@code LsRawDataStatus} 조인이 <b>구조적으로</b> 배제한다: 그 행은 배정 시점에 생기는데 포털에는
     * 배정·검수가 없어 <b>영영 생기지 않는다</b>. 이미 걸러지는 자리에 술어를 더하면 읽는 사람이 저 조인이
     * 하는 일을 못 읽게 되고 조회 계획만 무거워진다. 「일관성」을 이유로 붙이지 말 것.
     */
    @Query("""
            SELECT r.evntTypeCd AS code, COUNT(r) AS cnt
              FROM LsDataRaw r
              JOIN LsRawDataStatus st ON st.rawDataId = r.rawSn
             WHERE st.dataSttsCd = :status
               AND r.evntTypeCd IS NOT NULL
             GROUP BY r.evntTypeCd
            """)
    List<CountRow> countVideoByEventTypeAndStatus(@Param("status") String status);

    /**
     * 지정 검수 상태 영상의 이벤트 유형별 <b>프레임</b> 건수. NULL 코드는 제외.
     *
     * <p>{@link #countFramesByDataSttsCd(String)} 카드와 <b>짝으로 같은 화면에 나가는 분포</b>라
     * <b>폐기 프레임을 제외</b>한다(위 섹션 주석의 R4 규칙). 한쪽만 붙이면 카드 합계와 분포 합계가
     * 어긋나 화면이 자기모순을 보인다.
     *
     * <p><b>술어 위치</b>: {@code GROUP BY} <b>앞</b>에 붙인다 — 뒤에 붙이면 문법 오류다. 그래서 이
     * 쿼리만 텍스트 블록을 둘로 나눠 사이에 조각을 끼운다.
     *
     * <p><b>채널 술어({@link InternalWorkScope#INTERNAL_JPQL})는 붙이지 않는다 — 의도된 것이다</b>(ADR-058).
     * 근거는 {@link #countVideoByEventTypeAndStatus(String)} 주석과 같다({@code LsRawDataStatus} 조인의
     * 구조적 배제).
     */
    @Query("""
            SELECT r.evntTypeCd AS code, COUNT(s) AS cnt
              FROM LsDataSrc s
              JOIN LsDataRaw r ON s.rawSn = r.rawSn
              JOIN LsRawDataStatus st ON st.rawDataId = r.rawSn
             WHERE st.dataSttsCd = :status
               AND r.evntTypeCd IS NOT NULL
            """ + LsDataSrcRepository.NOT_DISCARDED + """
             GROUP BY r.evntTypeCd
            """)
    List<CountRow> countFrameByEventTypeAndStatus(@Param("status") String status);

    /**
     * 특정 사용자의 라벨링(작업) 상태별 카운트.
     * LS_TASK_ALTMNT(LABELER) ⨝ LS_RAW_DATA_STATUS on RAW_DATA_ID.
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
     *   <li>{@code inProgress}     — 배정됐고 <b>아직 완료되지 않은</b> 건수 = APPROVED 가 아닌 상태 전부.</li>
     * </ul>
     *
     * <p>{@code inProgress} 판정은 {@link #IN_PROGRESS_PREDICATE} 로만 한다(판정 근거는 그 상수 주석 참조).
     */
    @Query("""
            SELECT u.userNo AS userId,
                   u.userNm AS name,
                   SUM(CASE WHEN s.dataSttsCd IN ('APPROVED','IN_REVIEW','REJECTED') THEN 1 ELSE 0 END) AS labeled,
                   0L AS reviewed,
                   SUM(CASE WHEN s.dataSttsCd = 'APPROVED' THEN 1 ELSE 0 END) AS approvedCount,
                   SUM(CASE WHEN s.dataSttsCd = 'REJECTED' THEN 1 ELSE 0 END) AS rejectedCount,
                   SUM(CASE WHEN""" + IN_PROGRESS_PREDICATE + """
                        THEN 1 ELSE 0 END) AS inProgress
              FROM LsAcntUser u, LsTaskAssignment a, LsRawDataStatus s
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
     * 사용자별 라벨 총 수 + 자동 생성 라벨 수 — workers 표의 {@code autoLabelRate} 분모/분자.
     *
     * <p>자동 여부는 {@link #AUTO_LABEL_PREDICATE} 로만 판정한다(판정 근거는 그 상수 주석 참조).
     *
     * <p><b>N+1 금지</b>: 작업자마다 도는 대신 GROUP BY 로 전 작업자를 한 번에 집계하고 서비스
     * 레이어가 Map 으로 합친다({@link #countReviewerByUser()} 와 동일 패턴). 조인 키는
     * {@code IX_LS_DATA_SRC_RAW(RAW_SN)} + {@code IX_LS_DATA_LBL_SRC(SRC_SN)} 로 뒷받침된다.
     */
    @Query("""
            SELECT a.userNo AS code,
                   COUNT(l) AS totalCnt,
                   SUM(CASE WHEN """ + AUTO_LABEL_PREDICATE + """
                        THEN 1 ELSE 0 END) AS autoCnt
              FROM LsTaskAssignment a
              JOIN LsDataSrc s ON s.rawSn = a.rawDataId
              JOIN LsDataLbl l ON l.srcSn = s.srcSn
             WHERE a.taskTypeCd = 'LABELER'
             GROUP BY a.userNo
            """)
    List<WorkerLabelCountRow> countLabelsByWorker();

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
     * SCR-STAT-001 — 특정 작업자(LABELER) 의 <b>진행 중</b>(= 아직 완료되지 않은) 배정 건수.
     *
     * <p>판정은 {@link #IN_PROGRESS_PREDICATE} 로만 한다 — {@link #findWorkerStats()}(전체 통계 화면)
     * 와 <b>같은 조각</b>이라 두 화면의 {@code inProgress} 가 갈리지 않는다. 구 판정은 서비스 레이어에서
     * {@code ASSIGNED + IN_REVIEW} 를 열거해 더하는 방식이었고, 그래서 반려된 작업이 전체 통계에서는
     * 진행 중으로 잡히는데 작업자 통계에서는 어디에도 안 잡혔다.
     *
     * <p>상태별 카운트({@link #countWorkerTaskByStatus(Long)})에서 빼서 계산하지 않는 이유도 같다 —
     * 빼기로 유도하면 그것이 곧 <b>두 번째 판정</b>이 되어 다시 갈릴 수 있다.
     */
    @Query("""
            SELECT COUNT(s)
              FROM LsTaskAssignment a, LsRawDataStatus s
             WHERE a.userNo = :userNo
               AND a.taskTypeCd = 'LABELER'
               AND a.rawDataId = s.rawDataId
               AND""" + IN_PROGRESS_PREDICATE + """
            """)
    long countInProgressForWorker(@Param("userNo") Long userNo);

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
     * SCR-STAT-001 — 작업자 배정 raw 의 라벨 중 자동 생성 라벨 수.
     *
     * <p>판정은 {@link #AUTO_LABEL_PREDICATE} 로만 한다 — {@link #countLabelsByWorker()}(전체 통계
     * 화면) 와 <b>같은 조각</b>이라 두 화면의 {@code autoLabelRate} 가 갈리지 않는다.
     * 구 판정 {@code REG_USER_NO IS NULL} 프록시는 폐기했다: 등록자를 남기지 않는 생성 경로가
     * 자동 생성 외에도 있어(버전 롤백 복원) 사람이 그린 라벨을 자동으로 세고 있었다.
     */
    @Query("""
            SELECT COUNT(l)
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE """ + AUTO_LABEL_PREDICATE + """
               AND s.rawSn IN (
                   SELECT a.rawDataId FROM LsTaskAssignment a
                    WHERE a.userNo = :userNo
                      AND a.taskTypeCd = 'LABELER'
             )
            """)
    long countAutoLabelsForWorker(@Param("userNo") Long userNo);

    /**
     * SCR-STAT-001 — 작업자 배정 raw 중 <b>검수완료(APPROVED)</b> 영상에 달린 라벨 수
     * ({@code approvedLabelCount} — 학습데이터로 확정된 분량). @design API-056
     *
     * <p>{@link #countLabelsForWorker(Long)} 와 <b>다른 축</b>이다 — 그쪽은 "배정된 전체 분량"이고
     * 이쪽은 "산출물로 확정된 분량"이다. 같은 라벨 집합에서 출발해 <b>검수 상태 + 프레임 폐기여부</b>로
     * 두 번 좁히므로 항상 {@code approvedLabelCount <= labelCount} 다. 두 값은 짝이며 어느 하나를 다른
     * 하나에서 유도하지 않는다(비율은 서버가 내려주지 않으므로 화면도 만들지 않는다).
     *
     * <p><b>★INNER JOIN 필수</b>: {@code LS_RAW_DATA_STATUS} 행은 배정 시점에 lazy 생성되어
     * {@code LS_DATA_RAW} 전건과 1:1 이 아니다. LEFT JOIN 으로 바꾸면 상태 행이 없는(=미배정) 영상의
     * 라벨이 학습데이터로 계상된다 — 위 검수완료 한정 집계 3종과 동일한 규칙이다.
     *
     * <p><b>★폐기 프레임 제외 (R4)</b>: 학습데이터 산출
     * ({@code DatasetExportTxService} → {@code LsDataSrcRepository.findNotDiscardedByRawSnOrderByFrameNoAsc})
     * 과 데이터마트 뷰({@code v_completed_frame} 의 {@code COALESCE(dscd_yn,'N') <> 'Y'})가 폐기 프레임을
     * <b>구조적으로 제외</b>하므로, "확정된 분량"이라 서술한 이 값도 같은 기준이어야 한다. 그러지 않으면
     * 화면 숫자가 실제 산출 분량보다 크다(도달 경로: 라벨 저장 → 미승인 상태에서 그 프레임 폐기 → 승인).
     * 판정은 <b>술어를 재구현하지 않고</b> 밖으로 나가는 모든 조회가 공유하는 단일 조각
     * {@link LsDataSrcRepository#NOT_DISCARDED} 를 붙인다 — 별칭 {@code s} 가 그 조각의 규약
     * ({@code LsDataSrc} = {@code s})과 일치한다.
     *
     * <p><b>최신 버전 기준이다 (2026-08-17 확정)</b>: 대상 테이블 {@code LS_DATA_LBL} 은 <b>라이브 작업본</b>
     * 이며 라벨 1건 = 행 1건이다(버전 차원 컬럼이 없다). 승인 스냅샷은 별개 테이블
     * {@code LS_LABEL_VERSION.LABEL_PAYLOAD}(JSON)에 있고 이 집계는 그것을 <b>조인하지 않는다</b>.
     * 따라서 재승인으로 버전이 쌓여도 개수는 최신 상태 하나만 센다. ⚠ 여기에 버전 스냅샷을 조인·합산하면
     * 같은 라벨이 버전 수만큼 중복 계상되므로 <b>추가하지 말 것</b>.
     *
     * <p>상태 문자열은 서버 상수({@code LsRawDataStatus.STTS_APPROVED})만 {@code @Param} 으로
     * 바인딩한다(문자열 연결 없음 — CWE-89).
     */
    @Query("""
            SELECT COUNT(l)
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
              JOIN LsRawDataStatus st ON st.rawDataId = s.rawSn
             WHERE st.dataSttsCd = :status
               AND s.rawSn IN (
                   SELECT a.rawDataId FROM LsTaskAssignment a
                    WHERE a.userNo = :userNo
                      AND a.taskTypeCd = 'LABELER'
             )
            """ + LsDataSrcRepository.NOT_DISCARDED)
    long countApprovedLabelsForWorker(@Param("userNo") Long userNo, @Param("status") String status);

    /**
     * SCR-STAT-001 — 최근 N 일간 작업자 일별 완료 row (APPROVED 상태 영상 기준).
     * UPD_DT 가 APPROVED 로 전이된 시점이라고 가정 (review.transitionTo() 가 updDt 갱신).
     *
     * <p><b>Java 측 그룹화(이 파일의 날짜 그룹화 4곳 공통 — 근거는 여기에만 적는다):</b>
     * raw 행을 그대로 반환하고 서비스 레이어 Java 측에서 DateTimeFormatter + groupingBy 로
     * 'YYYY-MM-DD' 키를 만든다. 데이터량은 단일 사용자/30일 윈도 → 수십 ~ 수백 행이라 메모리 부담 없음.
     *
     * <p>⚠ <b>구 근거 폐기(2026-08-28)</b> — <i>"JPQL FUNCTION(TO_CHAR,...) 가 MariaDB 에 없어 실행
     * 실패하므로"</i>. 그건 1차 MariaDB 시절 서술이고 이 프로젝트는 <b>PostgreSQL 확정</b>이다
     * (ADR-010 전환 · 포털 DB 도 PostgreSQL 이며 이기종 분리 빌드는 기각 — INT-009).
     * 즉 TO_CHAR 를 쓸 수 있으나 <b>Java 측 키 생성을 그대로 둔다</b> — 이미 정상 동작하고 바꿔서 얻는
     * 것이 없다. 이 서술을 "이기종 대비"로 읽고 방언 중립 제약을 새로 세우지 말 것.
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
     * SCR-STAT-002 — 최근 N 일간 <b>전체(모든 작업자)</b> 일별 검수 완료 row.
     *
     * <p>{@link #findDailyCompletionForWorker(Long, java.time.LocalDateTime)} 와 달리
     * <b>LS_TASK_ALTMNT 조인이 없다</b> — 전체 구축 현황 차트는 작업자 귀속과 무관한
     * "검수 완료 건수"를 세기 때문이다. 그 결과 <b>배정 이력이 없는 승인 영상도 포함</b>되며,
     * 이것이 같은 화면의 {@code approvedVideoCount}(= APPROVED 상태 행 수)와 차트 합계를
     * 같은 원천으로 묶는 조건이다. 배정 조인을 추가하면 카드와 차트가 어긋난다.
     *
     * <p>완료 시각의 기준은 {@code LS_RAW_DATA_STATUS.UPD_DT}(APPROVED 전이 시점)이며
     * 대시보드 "최근 완료 영상" 정렬과 동일 축이다.
     *
     * <p>Java 측 그룹화: 일별 그룹화 키는 raw 행만 반환해
     * 서비스 레이어에서 'YYYY-MM-DD' 로 묶는다(작업자 경로와 동일 방식 · 근거는 위 참조). 데이터량은
     * 30일 윈도우의 승인 건수라 목표 규모(영상 5,000건)에서도 수백 행 수준이다.
     */
    @Query("""
            SELECT s.updDt AS updDt
              FROM LsRawDataStatus s
             WHERE s.dataSttsCd = 'APPROVED'
               AND s.updDt >= :since
            """)
    List<DailyRawRow> findDailyCompletionAll(@Param("since") java.time.LocalDateTime since);

    /**
     * SCR-STAT-001 — 최근 N 개월 작업자 월별 완료/반려 raw row.
     *
     * <p>Java 측 그룹화: 서비스 레이어에서 'YYYY-MM' 키로 GROUP BY 하면서
     * dataSttsCd 에 따라 completed/rejected 분기(근거는 위 참조).
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
     * <p>Java 측 그룹화: 키 생성은 서비스 레이어가 하고 여기서는 raw 행만 반환한다(근거는 위 참조).
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
        /** 배정됐고 아직 완료(APPROVED)되지 않은 건수. */
        long getInProgress();
    }

    /** 사용자별 라벨 총 수 + 자동 생성 라벨 수 projection ({@code autoLabelRate} 분모/분자). */
    interface WorkerLabelCountRow {
        Long getCode();
        long getTotalCnt();
        long getAutoCnt();
    }

    /**
     * SCR-STAT-001 일별 완료 raw row projection.
     * <p>서비스 레이어에서 Java DateTimeFormatter 로 'YYYY-MM-DD' 키로 묶어 카운트한다.
     * (키 생성을 Java 측이 맡으므로 여기서는 raw 행만 반환한다 — 근거는 이 파일의 날짜 그룹화 첫 메서드 javadoc.)
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
