package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * {@code LS_DATA_LBL} 조회·삭제.
 *
 * <h3>V6 — AI 메타 조인이 사라졌다</h3>
 * 자동 여부·신뢰도·출처는 구 {@code LS_DATA_LBL_AI_INFO} 에 있어 이 인터페이스의 상당수 쿼리가
 * {@code EXISTS (SELECT 1 FROM LsDataLblAiInfo ai WHERE ai.dataLblSn = l.lblSn ...)} 서브쿼리를
 * 달고 있었다. 흡수 후에는 전부 <b>같은 행의 컬럼 술어</b>다.
 *
 * <p>⚠ 판정 결과는 흡수 전과 같다 — 구 술어는 "AI 정보 행이 하나라도 {@code AUTO_LBL_YN='Y'} 인가"
 * 였고, 마이그레이션이 다중 행에서 <b>'Y' 행을 우선</b> 채택하므로 컬럼 술어가 같은 집합을 고른다
 * (V6 헤더 「결정적 규칙」).
 *
 * @req R4
 */
@ControlRepo
public interface LsDataLblRepository extends JpaRepository<LsDataLbl, Long>, LsDataLblRepositoryCustom {

    List<LsDataLbl> findBySrcSn(Long srcSn);

    /**
     * 프레임(srcSn) 집합에 속한 모든 라벨을 단일 IN 쿼리로 일괄 조회.
     * <p>SCR-REVIEW-002 검수 화면 일괄 조회용 — N+1 회피.
     * 빈 컬렉션 입력 시 빈 결과 반환 (default).
     */
    List<LsDataLbl> findBySrcSnIn(Collection<Long> srcSns);

    /**
     * 주어진 프레임(srcSn) 집합 중 라벨이 1건 이상 존재하는 srcSn 만 DISTINCT 로 조회 — 단일 쿼리(N+1 금지).
     * <p>R5 프레임 strip 의 SAVED(연두) 상태 판정용. 형제 프레임별 라벨 존재 여부를 프레임 수만큼
     * 개별 COUNT 하지 않고 IN 절 1회로 라벨 보유 프레임 집합을 얻는다. 파라미터 바인딩({@code :srcSns})만
     * 사용 — 문자열 연결 없음(CWE-89 무관). 빈 컬렉션 입력 시 빈 결과.
     */
    @Query("SELECT DISTINCT l.srcSn FROM LsDataLbl l WHERE l.srcSn IN :srcSns")
    List<Long> findDistinctSrcSnsWithLabelIn(@Param("srcSns") Collection<Long> srcSns);

    /**
     * 프레임(srcSn) 의 자동/수동 라벨 조회.
     *
     * <p>{@code autoLblYn='N'} 은 <b>{@code 'N'} 인 라벨</b>만이 아니라 <b>{@code 'Y'} 가 아닌 전부</b>
     * (= {@code null} 포함)를 뜻한다 — 흡수 전 술어가 "AI 정보 행이 없거나 Y 가 아님" 이었기 때문이다.
     */
    @Query("""
            SELECT l
              FROM LsDataLbl l
             WHERE l.srcSn = :srcSn
               AND (
                    (:autoLblYn = 'Y' AND l.autoLblYn = 'Y')
                    OR
                    (:autoLblYn = 'N' AND (l.autoLblYn IS NULL OR l.autoLblYn <> 'Y'))
               )
            """)
    List<LsDataLbl> findBySrcSnAndAutoLblYn(@Param("srcSn") Long srcSn, @Param("autoLblYn") String autoLblYn);

    /** {@link #findBySrcSnAndAutoLblYn} 와 <b>같은 술어</b>의 카운트. 두 곳이 갈리면 목록과 개수가 어긋난다. */
    @Query("""
            SELECT COUNT(l)
              FROM LsDataLbl l
             WHERE l.srcSn = :srcSn
               AND (
                    (:autoLblYn = 'Y' AND l.autoLblYn = 'Y')
                    OR
                    (:autoLblYn = 'N' AND (l.autoLblYn IS NULL OR l.autoLblYn <> 'Y'))
               )
            """)
    long countBySrcSnAndAutoLblYn(@Param("srcSn") Long srcSn, @Param("autoLblYn") String autoLblYn);

    /**
     * 페이지 단위 batch lookup — 영상(rawSn) 별 라벨 총개수.
     * LS_DATA_LBL.SRC_SN → LS_DATA_SRC.SRC_SN → LS_DATA_SRC.RAW_SN 조인.
     * Object[]: [Long rawSn, Long count]. N+1 방지를 위해 IN 절 batch.
     */
    @Query("""
            SELECT s.rawSn, COUNT(l)
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE s.rawSn IN :rawSns
             GROUP BY s.rawSn
            """)
    List<Object[]> countLabelsByRawSnIn(@Param("rawSns") Collection<Long> rawSns);

    /**
     * 영상(rawSn)에 속한 모든 프레임의 라벨 조회 (auto + manual).
     * LS_DATA_LBL.SRC_SN → LS_DATA_SRC.SRC_SN → LS_DATA_SRC.RAW_SN 조인.
     */
    @Query("""
            SELECT l
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE s.rawSn = :rawSn
             ORDER BY l.lblSn ASC
            """)
    List<LsDataLbl> findAllByRawSn(@Param("rawSn") Long rawSn);

    /**
     * 영상(rawSn)의 모든 라벨을 AI 메타와 함께 조회 — 오토라벨 결과 화면.
     *
     * <p><b>V6 이전에는 LATERAL 조인이었다</b>: 자동 여부·신뢰도가 {@code LS_DATA_LBL_AI_INFO} 에 있고
     * 한 라벨에 그 행이 여럿일 수 있어, 라벨당 <b>최신 행 1건</b>을 고르는 LATERAL 서브쿼리가 필요했다.
     * 흡수 후에는 같은 행의 컬럼이라 조인도 행 선택도 없다(마이그레이션이 그 선택을 1회 수행해 결과를
     * 고정했다 — V6 헤더 「결정적 규칙」).
     *
     * <p>{@code autoLblYn} 은 {@code 'Y'} 일 때만 내보내고 그 외({@code 'N'}·{@code null})는
     * {@code null} 로 둔다 — 구 LATERAL 이 {@code AUTO_LBL_YN='Y'} 행만 매칭하고 나머지는 LEFT JOIN
     * miss 로 {@code null} 을 냈던 계약을 그대로 유지한다({@code confScore} 도 같은 조건에서만 실린다).
     * 파라미터 바인딩({@code :rawSn})만 사용 — 문자열 연결 없음(CWE-89 회귀 방지).
     */
    @Query(value = """
            SELECT l.LBL_SN AS lblSn,
                   l.LBL_NM AS labelNm,
                   CASE WHEN l.AUTO_LBL_YN = 'Y' THEN l.AUTO_LBL_YN END AS autoLblYn,
                   CASE WHEN l.AUTO_LBL_YN = 'Y' THEN l.CONF_SCORE END  AS confScore
              FROM LS_DATA_LBL l
              JOIN LS_DATA_SRC s ON l.SRC_SN = s.SRC_SN
             WHERE s.RAW_SN = :rawSn
             ORDER BY l.LBL_SN ASC
            """, nativeQuery = true)
    List<AutoLabelInfoProjection> findAutoLabelInfoByRawSn(@Param("rawSn") Long rawSn);

    /**
     * 영상(rawSn)에 속한 모든 프레임의 자동 라벨(autoLblYn='Y')을 일괄 삭제.
     * 오토라벨링 테스트 재실행 시 idempotent 보장 용도.
     */
    @Modifying
    @Transactional(value = "controlTransactionManager")
    @Query("DELETE FROM LsDataLbl l WHERE l.srcSn IN " +
            "(SELECT s.srcSn FROM LsDataSrc s WHERE s.rawSn = :rawSn) " +
            "AND l.autoLblYn = 'Y'")
    void deleteByRawSnAutoLbl(@Param("rawSn") Long rawSn);

    /**
     * 프레임(srcSn) 단위 자동 라벨(autoLblYn='Y') 의 LBL_SN 목록.
     * <p>Phase 3 — YOLO 온라인 수동 트리거 재실행 idempotency 용. 자동 라벨만 대상이며 수동 라벨은
     * 목록에서 제외되어 절대 삭제되지 않는다. 고정 리터럴 'Y' 만 사용
     * (외부 입력 없음 — CWE-89 무관, 파라미터 바인딩 {@code :srcSn} 만).
     */
    @Query("""
            SELECT l.lblSn
              FROM LsDataLbl l
             WHERE l.srcSn = :srcSn
               AND l.autoLblYn = 'Y'
            """)
    List<Long> findAutoLblSnsBySrcSn(@Param("srcSn") Long srcSn);

    /**
     * 영상(rawSn)에서 <b>지정 출처의 자동 라벨이 이미 적재된 프레임</b>(SRC_SN) 목록 — 재실행 멱등 판정.
     *
     * <p>배치 재시도는 파이프라인을 선두부터 전부 다시 돌린다({@code BatchRetryQuartzJob} →
     * {@code BatchOrchestrator.process}). 오토라벨 단계가 "이미 했는지"를 보지 않으면 재시도마다 같은
     * 프레임에 자동 라벨이 <b>중복 적재</b>된다(2배·3배…).
     *
     * <p><b>단일 쿼리</b>로 프레임 집합을 한 번에 얻는다 — 프레임마다 존재 여부를 묻는 N+1 을 만들지
     * 않는다. 파라미터 바인딩({@code :rawSn}, {@code :lblSrcCd})만 사용(CWE-89). 결과는 식별자뿐이라
     * PII 를 싣지 않는다.
     *
     * <p>⚠ 이 판정은 <b>삭제-후-재삽입의 근거가 아니다</b>. 자동 라벨은 사람이 이미 수정했을 수 있어
     * ({@code TrackInterpolationStep} 의 보간 산출물과 달리) 지우면 작업 결과가 파괴된다 — 호출부는
     * 반드시 <b>추론·적재를 건너뛰는</b> 방향으로만 쓴다.
     *
     * <p>V6 — 구현이 구 {@code LsDataLblAiInfoRepository} 에서 여기로 옮겨 왔다. 판정 축이 라벨 행의
     * 컬럼이 되어 {@code DATA_RAW_SN} 사본 대신 {@code LS_DATA_SRC} 조인으로 영상에 도달한다.
     *
     * @param lblSrcCd {@code LsDataLbl.SRC_YOLO} / {@code SRC_SAM2}
     * @req R1
     */
    @Query("""
            SELECT DISTINCT l.srcSn
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE s.rawSn = :rawSn
               AND l.lblSrcCd = :lblSrcCd
            """)
    List<Long> findDistinctSrcSnsByRawSnAndLblSrcCd(@Param("rawSn") Long rawSn,
                                                    @Param("lblSrcCd") String lblSrcCd);

    /**
     * 영상(rawSn)에 <b>자동 생성 라벨</b>이 있는가 — 존재 확인용(조기 종료). [@design AC-051] [@design API-043]
     *
     * <p>판정 술어는 {@code AUTO_LBL_YN='Y'} <b>이면서</b> {@code LBL_SRC_CD} 가 오토라벨 출처인 라벨이다.
     * 사람이 그린 라벨은 두 컬럼이 모두 {@code null} 이라 걸리지 않는다 — "라벨이 있는가" 로 판정하면
     * 작업자가 라벨을 하나 그린 순간 오토라벨 산출물이 있는 것으로 오산입된다. 판정의 단일 원천과
     * 출처 목록은 {@code AutolabelPresence} 가 소유하며, 여기서 출처 문자열을 복제하지 않는다
     * (호출자가 목록을 넘긴다).
     *
     * <p><b>존재 확인은 {@code pageable} 로 한 건만 읽는다</b> — 영상 하나에 프레임이 수천이라
     * 전건 카운트를 센 뒤 0 과 비교하면 "있다"를 확인하는 데 전량을 훑는다. 카운트가 필요한 호출부는
     * {@link #countAutoLabelByRawSn} 를 쓴다.
     *
     * <p>파라미터 바인딩({@code :rawSn}, {@code :lblSrcCds})만 사용(CWE-89). 결과는 식별자뿐이라 PII 를
     * 싣지 않는다. {@code LS_DATA_LBL} 에는 {@code RAW_SN} 컬럼이 없어 {@code LS_DATA_SRC} 조인으로
     * 영상에 도달한다.
     *
     * @param lblSrcCds 오토라벨 출처 코드 집합 — 비어 있으면 JPQL {@code IN ()} 이 되므로 호출자가 보장한다
     */
    @Query("""
            SELECT l.lblSn
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE s.rawSn = :rawSn
               AND l.autoLblYn = 'Y'
               AND l.lblSrcCd IN :lblSrcCds
            """)
    List<Long> findAutoLabelLblSnsByRawSn(@Param("rawSn") Long rawSn,
                                          @Param("lblSrcCds") Collection<String> lblSrcCds,
                                          Pageable pageable);

    /**
     * 영상(rawSn)의 자동 생성 라벨 건수 — 판정 근거를 로그에 남기는 호출부용.
     * 술어는 {@link #findAutoLabelLblSnsByRawSn} 와 <b>같다</b>(둘이 갈리면 "있다는데 0건"이 된다).
     */
    @Query("""
            SELECT COUNT(l)
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE s.rawSn = :rawSn
               AND l.autoLblYn = 'Y'
               AND l.lblSrcCd IN :lblSrcCds
            """)
    long countAutoLabelByRawSn(@Param("rawSn") Long rawSn,
                               @Param("lblSrcCds") Collection<String> lblSrcCds);

    /**
     * 영상(rawSn)에 속한 모든 프레임의 라벨(자동+수동 전체)을 일괄 삭제 (R1 v1.14 — 비식별 신고 시).
     * <p>1건씩 삭제 금지(수천 건 가능) — 단일 DELETE…WHERE SRC_SN IN(서브쿼리) 로 처리.
     * 호출 전 ATTR_VAL 자식 row 를 먼저 삭제해 FK 고아를 방지한다
     * (AI 메타는 V6 흡수로 같은 행이라 별도 선삭제가 필요 없다).
     */
    @Modifying
    @Transactional(value = "controlTransactionManager")
    @Query("DELETE FROM LsDataLbl l WHERE l.srcSn IN " +
            "(SELECT s.srcSn FROM LsDataSrc s WHERE s.rawSn = :rawSn)")
    void deleteAllByRawSn(@Param("rawSn") Long rawSn);

    /**
     * 영상(rawSn) 의 총 라벨 개수 — 단일 COUNT 쿼리 (N+1 금지).
     * LS_DATA_LBL.SRC_SN → LS_DATA_SRC.SRC_SN → LS_DATA_SRC.RAW_SN 조인.
     */
    @Query("""
            SELECT COUNT(l)
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE s.rawSn = :rawSn
            """)
    long countByRawSn(@Param("rawSn") Long rawSn);

    /**
     * 영상(rawSn) 의 <b>라벨을 가진 프레임 수</b> — 단일 COUNT DISTINCT 쿼리.
     *
     * <p>구현은 전 프레임·전 라벨을 메모리에 적재한 뒤 distinct 로 세었다(CWE-770). 대용량 영상에서
     * 힙을 고갈시키므로 DB 집계로 대체한다.
     */
    @Query("""
            SELECT COUNT(DISTINCT l.srcSn)
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE s.rawSn = :rawSn
            """)
    long countLabeledFramesByRawSn(@Param("rawSn") Long rawSn);

    /**
     * D-ISSUE-04 — 영상(rawSn)에 라벨이 <b>1건이라도</b> 있는지 여부 (검수 승인 사전 게이트).
     *
     * <p>{@code COUNT} 나 전체 fetch 가 아니라 {@code EXISTS} 로 첫 행에서 즉시 종료한다(프레임/라벨 수와
     * 무관한 단일 쿼리 — N+1·풀스캔 금지). 프레임이 0건인 영상은 라벨도 0건이므로 이 판정 하나로
     * "프레임 부재"까지 함께 걸러진다. 파라미터 바인딩({@code :rawSn})만 사용 — CWE-89 표면 없음.
     */
    @Query(value = """
            SELECT EXISTS (
                   SELECT 1
                     FROM LS_DATA_LBL l
                     JOIN LS_DATA_SRC s ON l.SRC_SN = s.SRC_SN
                    WHERE s.RAW_SN = :rawSn
            )
            """, nativeQuery = true)
    boolean existsAnyByRawSn(@Param("rawSn") Long rawSn);

    /**
     * 영상(rawSn) 의 클래스(라벨명)별 라벨 분포 — 단일 GROUP BY 쿼리 (N+1 금지).
     * <p>결과 Object[]: [Long labelId, String labelNm, Long count]. labelId 는 null 가능
     * (V32 이전 라벨) — caller 가 0L 폴백 처리. count DESC 정렬.
     */
    @Query("""
            SELECT l.labelId, l.labelNm, COUNT(l)
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE s.rawSn = :rawSn
             GROUP BY l.labelId, l.labelNm
             ORDER BY COUNT(l) DESC
            """)
    List<Object[]> aggregateClassDistribution(@Param("rawSn") Long rawSn);

    /**
     * 영상(rawSn) 의 신뢰도 보유 라벨에 대한 [합계, 개수] — 평균 계산용 (단일 쿼리).
     *
     * <p>V6 — 신뢰도가 라벨 행의 컬럼이 되어 구 {@code DATA_RAW_SN} 사본 대신 {@code LS_DATA_SRC}
     * 조인으로 영상에 도달한다. 결과 Object[]: [BigDecimal sum, Long cnt].
     * 신뢰도 보유 라벨이 0건이면 sum=null, cnt=0.
     */
    @Query("""
            SELECT SUM(l.confScore), COUNT(l)
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE s.rawSn = :rawSn
               AND l.confScore IS NOT NULL
            """)
    Object[] aggregateConfidenceSum(@Param("rawSn") Long rawSn);

    /**
     * 영상(rawSn) 의 신뢰도 구간별 라벨 개수 — 단일 GROUP BY 쿼리 (N+1 금지).
     * <p>구간: high(&gt;=0.9) / mid(0.7~0.9) / low(&lt;0.7). 결과 Object[]: [String bucket, Long count].
     */
    @Query("""
            SELECT CASE
                       WHEN l.confScore >= 0.9 THEN 'high'
                       WHEN l.confScore >= 0.7 THEN 'mid'
                       ELSE 'low'
                   END,
                   COUNT(l)
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE s.rawSn = :rawSn
               AND l.confScore IS NOT NULL
             GROUP BY CASE
                       WHEN l.confScore >= 0.9 THEN 'high'
                       WHEN l.confScore >= 0.7 THEN 'mid'
                       ELSE 'low'
                   END
            """)
    List<Object[]> aggregateConfidenceBuckets(@Param("rawSn") Long rawSn);

    /**
     * 영상(rawSn) 의 저신뢰(임계 미만) 라벨을 프레임 정보와 함께 조회 — 단일 조인 쿼리.
     *
     * <p>결과 Object[]: [Long srcSn, Integer frameNo, BigDecimal confScore]. confScore ASC(낮은 순).
     * 동일 프레임에 여러 저신뢰 라벨이 있으면 각각 반환되므로 caller 가 프레임 단위로 중복 제거한다.
     */
    @Query("""
            SELECT s.srcSn, s.frameNo, l.confScore
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE s.rawSn = :rawSn
               AND l.confScore IS NOT NULL
               AND l.confScore < :threshold
             ORDER BY l.confScore ASC
            """)
    List<Object[]> findLowConfidenceFrames(@Param("rawSn") Long rawSn,
                                           @Param("threshold") java.math.BigDecimal threshold);

    /**
     * 영상(rawSn)에 속한 자동 트랙 라벨(BBOX/POLYGON) 중 trackId 가 있는 row 만 조회. — 트랙 보간.
     * <p>보간 대상 정의:
     * <ul>
     *   <li>{@code AUTO_LBL_YN='Y'} — 자동 라벨링 결과만</li>
     *   <li>{@code LBL_TYPE_CD IN ('BBOX','POLYGON')} — BBOX 선형 + POLYGON polyshape 보간 대상.
     *       SEGMENT/MASK/SKELETON 은 보간 범위 외(TrackInterpolationStep 이 타입별로 라우팅).
     *       (POLYLINE 은 현재 DB 코드값 미도입 — 필요 시 화이트리스트에 추가)</li>
     *   <li>{@code TRACK_ID IS NOT NULL} — 트래커 저신뢰 detection 은 보간 대상 외</li>
     * </ul>
     * <p>같은 영상의 모든 트랙을 단일 IN 쿼리로 가져와 N+1 회피. 타입 화이트리스트는 고정 리터럴이라
     * 외부 입력이 섞이지 않는다(CWE-89 무관 — 파라미터 바인딩 {@code :rawSn} 만 사용).
     * <p>메서드명은 하위호환 위해 유지(BBOX 전용 시절 이름). 실제 반환은 BBOX+POLYGON.
     */
    @Query("""
            SELECT l
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE s.rawSn = :rawSn
               AND l.autoLblYn = 'Y'
               AND l.lblTypeCd IN ('BBOX', 'POLYGON')
               AND l.trackId IS NOT NULL
            """)
    List<LsDataLbl> findAutoBboxWithTrackId(@Param("rawSn") Long rawSn);

    /**
     * {@link #findAutoBboxWithTrackId} 의 <b>단일 트랙 한정</b> 변형 — 트랙 병합 후 병합 트랙(toTrackId)만
     * 재보간(락 유지시간 단축)하기 위한 후보 조회. 보간 대상 정의(AUTO_LBL_YN='Y' + BBOX/POLYGON +
     * TRACK_ID NOT NULL)는 전체 경로와 동일하며 {@code AND l.trackId = :trackId} 로 <b>DB 레벨</b>에서
     * 한정한다(in-memory 필터 금지 — {@code IX_LS_DATA_LBL_TRCK_ID} 활용). 전체 경로 메서드는 무변경.
     * 파라미터 바인딩({@code :rawSn}, {@code :trackId})만 사용 — 문자열 연결 없음(CWE-89 무관).
     */
    @Query("""
            SELECT l
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE s.rawSn = :rawSn
               AND l.autoLblYn = 'Y'
               AND l.lblTypeCd IN ('BBOX', 'POLYGON')
               AND l.trackId = :trackId
            """)
    List<LsDataLbl> findAutoBboxByRawSnAndTrackId(@Param("rawSn") Long rawSn,
                                                  @Param("trackId") String trackId);

    /**
     * 영상(rawSn) 내 특정 트랙(trackId)에 속한 모든 라벨(자동+수동, 전 타입) 조회 — 트랙 병합.
     * <p>{@link #findAutoBboxWithTrackId}(자동 BBOX/POLYGON 한정)와 달리 존재 확인·겹침 계산·
     * trackId 재지정을 위해 <b>수동/SEGMENT/SKELETON 을 포함한 전체</b>를 반환한다. 보간 산출물
     * 구분은 caller 가 {@link #findInterpolatedLblSnsByRawSn} 와 대조해 수행한다.
     * 파라미터 바인딩({@code :rawSn}, {@code :trackId})만 사용 — 문자열 연결 없음(CWE-89 무관).
     */
    @Query("""
            SELECT l
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE s.rawSn = :rawSn
               AND l.trackId = :trackId
            """)
    List<LsDataLbl> findByRawSnAndTrackId(@Param("rawSn") Long rawSn, @Param("trackId") String trackId);

    /**
     * 영상(rawSn) 내 특정 트랙(trackId) 라벨 중 <b>프레임 번호(LS_DATA_SRC.FRAME_NO)가
     * {@code fromFrameNo} 이상</b>인 라벨을 프레임 오름차순으로 조회 — R4 트랙 삭제 / R5 트랙 split.
     *
     * <p>{@code FRAME_NO} 는 LS_DATA_LBL 에 없고 LS_DATA_SRC 에만 있으므로 JOIN 으로 범위를 필터한다
     * (자동+수동+보간 전 타입 포함). 삭제는 반환된 라벨의 {@code LBL_SN} 으로 bulk delete 하고,
     * split 은 반환 라벨의 {@code TRCK_ID} 를 새 트랙으로 재지정한다.
     * 파라미터 바인딩({@code :rawSn}, {@code :trackId}, {@code :fromFrameNo})만 사용 — 문자열 연결 없음(CWE-89 무관).
     */
    @Query("""
            SELECT l
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE s.rawSn = :rawSn
               AND l.trackId = :trackId
               AND s.frameNo >= :fromFrameNo
             ORDER BY s.frameNo ASC
            """)
    List<LsDataLbl> findByRawSnAndTrackIdFromFrameNo(@Param("rawSn") Long rawSn,
                                                     @Param("trackId") String trackId,
                                                     @Param("fromFrameNo") Long fromFrameNo);

    /**
     * 영상(rawSn) 내 사용 중인 모든 트랙 ID(DISTINCT, NULL 제외) — R5 트랙 split 의 새 트랙 ID 채번용.
     * <p>split 은 반환 목록에서 정수로 파싱 가능한 값의 최댓값+1 을 새 트랙 ID 로 부여해 영상 내 유니크를
     * 보장한다. 파라미터 바인딩({@code :rawSn})만 사용 — 문자열 연결 없음(CWE-89 무관).
     */
    @Query("""
            SELECT DISTINCT l.trackId
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE s.rawSn = :rawSn
               AND l.trackId IS NOT NULL
            """)
    List<String> findDistinctTrackIdsByRawSn(@Param("rawSn") Long rawSn);

    /**
     * 영상(rawSn) 의 기존 보간 생성 라벨({@code LBL_SRC_CD='INTERPOLATE'}) 의 LBL_SN 목록.
     * <p>트랙 보간 재실행 시 idempotent 보장용 — 기존 보간 row 를 삭제 후 재삽입하기 위해 대상 PK 를 먼저 조회한다.
     * 고정 리터럴 'INTERPOLATE' 만 사용(외부 입력 없음 — CWE-89 무관).
     * <p>V6 — 이 술어가 AI 정보 테이블에서 라벨 행으로 옮겨 왔고, {@code IX_LS_DATA_LBL_LBL_SRC_CD}
     * 가 구 {@code IDX_LS_DATA_LBL_AI_INFO_SRC_CD} 를 승계해 풀스캔을 막는다.
     */
    @Query("""
            SELECT l.lblSn
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE s.rawSn = :rawSn
               AND l.lblSrcCd = 'INTERPOLATE'
            """)
    List<Long> findInterpolatedLblSnsByRawSn(@Param("rawSn") Long rawSn);

    /**
     * {@link #findInterpolatedLblSnsByRawSn} 의 <b>트랙 집합 한정</b> 변형 — 트랙 병합 후 stale 보간
     * 산출물을 병합 관련 트랙({@code {fromTrackId, toTrackId}})만 정리하기 위한 삭제 대상 조회.
     * {@code AND l.trackId IN :trackIds} 로 <b>DB 레벨</b>에서 한정한다(in-memory 필터 금지).
     *
     * <p><b>from+to 양쪽을 넘겨야 하는 이유</b>: 병합은 reassignTrack(toTrackId)을 <b>원 키프레임에만</b>
     * 적용하므로 fromTrackId 로 생성됐던 기존 INTERPOLATE 산출물 row 는 trackId 가 여전히 fromTrackId
     * 인 채 남는다. toTrackId 만 지우면 이 fromTrackId 보간 산출물이 <b>고아로 영구 잔존</b>(유령 라벨·
     * 카운트 부풀림·검수 스냅샷 오염)한다. 전체 경로 메서드는 무변경. 빈 컬렉션 입력 시 빈 결과.
     * 고정 리터럴 'INTERPOLATE' + 파라미터 바인딩({@code :rawSn}, {@code :trackIds})만 — CWE-89 무관.
     */
    @Query("""
            SELECT l.lblSn
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE s.rawSn = :rawSn
               AND l.trackId IN :trackIds
               AND l.lblSrcCd = 'INTERPOLATE'
            """)
    List<Long> findInterpolatedLblSnsByRawSnAndTrackId(@Param("rawSn") Long rawSn,
                                                       @Param("trackIds") Collection<String> trackIds);
}
