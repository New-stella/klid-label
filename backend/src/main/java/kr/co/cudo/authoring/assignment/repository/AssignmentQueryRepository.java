package kr.co.cudo.authoring.assignment.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.core.types.dsl.StringExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kr.co.cudo.authoring.assignment.domain.AssignmentWorkStatus;
import kr.co.cudo.authoring.assignment.dto.AssignmentSearchCondition;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.entity.QLsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.QLsTaskAssignment;
import kr.co.cudo.authoring.augment.repository.DerivativeWorkEligibility;
import kr.co.cudo.authoring.video.repository.InternalWorkScope;
import kr.co.cudo.authoring.batch.entity.QLsDataSrc;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.BlankTextPredicate;
import kr.co.cudo.authoring.common.util.ControlCharNormalizer;
import kr.co.cudo.authoring.common.util.LikeEscape;
import kr.co.cudo.authoring.common.util.SortAllowlist;
import kr.co.cudo.authoring.user.entity.QLsAcntUser;
import kr.co.cudo.authoring.version.entity.QLsDataLblHstry;
import kr.co.cudo.authoring.video.entity.QLsDataRaw;
import kr.co.cudo.authoring.video.entity.QLsDataIngest;
import kr.co.cudo.authoring.video.repository.IngestSourceLink;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 배정 목록(WORKER 작업목록, {@code GET /v1/assignments}) 전용 QueryDSL 리포지토리.
 *
 * <p><b>설계 제약</b>:
 * <ul>
 *   <li><b>행 증식 원천 차단</b> — 검색·필터 대상인 {@code LS_DATA_RAW}/{@code LS_DATA_INGEST}/
 *       {@code LS_ACNT_USER}/{@code LS_RAW_DATA_STATUS}/{@code LS_DATA_SRC}+{@code LS_DATA_LBL_HSTRY} 를
 *       <b>조인하지 않고</b> 상관 {@code EXISTS} 로만 참조한다. {@code FROM LS_TASK_ALTMNT} 단일
 *       테이블이라 결과가 배정 1건=1행으로 고정되고 {@code totalElements} 가 어긋날 수 없다.</li>
 *   <li><b>목록/count 조건 단일 관리</b> — 하나의 {@link BooleanBuilder} 를 목록·count 가 공유한다.
 *       조건이 갈라질 구조적 여지가 없다.</li>
 *   <li><b>N+1 금지</b> — '작업중' 판정 근거인 라벨 저장 이력은 행마다 재조회하지 않고 목록 쿼리의
 *       <b>프로젝션으로 함께</b> 가져온다(아래 HIGH-3 참조). 페이지당 쿼리는 목록 1 + count 1 이다.</li>
 *   <li><b>필터와 표시의 동치(HIGH-3)</b> — {@code workStatus} 의 WHERE 절과 응답에 실리는
 *       {@code hasSaveHistory} 는 <b>같은 표현식 인스턴스</b>({@link #hasSaveHistoryExists})에서 나온다.
 *       필터로 걸러온 행이 화면에서 다른 상태로 표시될 수 없다.</li>
 *   <li><b>인가와 필터의 분리(CWE-639)</b> — {@link AssignmentSearchCondition#selfUserNo()} 가 있으면
 *       그 값 하나로 {@code USER_NO} 를 고정하고 {@code workerIdFilter} 는 <b>읽지 않는다</b>.
 *       두 값을 OR/폴백으로 합치는 지점이 없다.</li>
 *   <li><b>tie-break 강제</b> — 배정은 일괄 생성(한 요청에 여러 영상)이라 {@code REG_DT} 동값이 흔하다.
 *       PK({@code ASSIGNMENT_ID})를 마지막 정렬 항목으로 강제해 페이지 경계에서 행 중복/누락이
 *       생기지 않게 한다.</li>
 *   <li><b>안전 바인딩(CWE-89)</b> — 검색어는 {@link LikeEscape} 로 이스케이프 후
 *       {@code like(..., ESCAPE_CHAR)} 로 바인딩하고, 정렬 키는 명시 화이트리스트({@code switch})로만
 *       해석한다. 문자열 연결로 쿼리를 만드는 지점이 없다.</li>
 * </ul>
 */
@Repository
public class AssignmentQueryRepository {

    /**
     * 정렬 항목 개수 상한(CWE-770) — {@link SortAllowlist#ASSIGNMENT} 에서 <b>파생</b>한다.
     * 하드코딩하면 allowlist 확장 시 컨트롤러와 리포지토리의 상한이 조용히 어긋난다.
     */
    static final int MAX_SORT_ORDERS = SortAllowlist.maxOrders(SortAllowlist.ASSIGNMENT);

    @PersistenceContext(unitName = "control")
    private EntityManager entityManager;

    /**
     * 조회 결과 1행 — 배정 엔티티 + 그 영상의 <b>사용자 라벨 저장 이력</b> 존재 여부.
     *
     * <p>{@code hasSaveHistory} 를 응답까지 실어 나르는 이유는 표시 상태를 만들 때 <b>다시 조회하지
     * 않기</b> 위해서다. 재조회하면 (a) N+1 이 생기고 (b) WHERE 와 다른 시점/조건으로 판정될 수 있다.
     */
    public record AssignmentRow(LsTaskAssignment assignment, boolean hasSaveHistory) {
    }

    /**
     * 조건 + 페이징으로 LABELER 배정을 조회한다.
     *
     * @param condition 검색 조건 (null 불가 — 호출 측에서 인가 축을 채워 조립)
     * @param pageable  페이징/정렬 (정렬 키는 호출 측이 allowlist 로 검증·매핑한 엔티티 필드명)
     */
    public Page<AssignmentRow> search(AssignmentSearchCondition condition, Pageable pageable) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);
        QLsTaskAssignment assignment = QLsTaskAssignment.lsTaskAssignment;

        BooleanBuilder where = buildWhere(assignment, condition, true);
        NumberExpression<Integer> hasSaveHistoryFlag = hasSaveHistoryFlag(assignment);

        List<Tuple> tuples = queryFactory
                .select(assignment, hasSaveHistoryFlag)
                .from(assignment)
                .where(where)
                .orderBy(orderSpecifiers(assignment, pageable.getSort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        List<AssignmentRow> content = new ArrayList<>(tuples.size());
        for (Tuple tuple : tuples) {
            Integer flag = tuple.get(hasSaveHistoryFlag);
            content.add(new AssignmentRow(tuple.get(assignment), flag != null && flag != 0));
        }

        JPAQuery<Long> countQuery = queryFactory
                .select(assignment.count())
                .from(assignment)
                .where(where);

        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }

    /**
     * 이벤트유형 코드 distinct 목록 — 셀렉트 옵션({@code GET /v1/assignments/event-types})용.
     *
     * <p><b>목록과 같은 조건을 쓰되 자기 축({@code eventTypeCd})만 뺀다</b>({@code includeEventType=false}).
     * 다른 축(인가·검색어·워크플로 상태)을 반영하지 않으면 "옵션엔 있는데 고르면 0건" 이 되고, 반대로
     * 자기 축까지 반영하면 하나를 고르는 순간 나머지 선택지가 사라져 되돌아갈 수 없다.
     * 조건 조립은 {@link #buildWhere} 를 그대로 재사용하므로 인가 강제·LIKE 이스케이프·'작업중' 판정이
     * 목록과 갈라질 수 없다.
     *
     * <p><b>인가 축이 반드시 함께 적용된다</b> — 옵션은 "그 값이 존재한다"는 사실 자체가 정보이므로,
     * WORKER 요청이면 {@code selfUserNo} 로 고정된 조건이 그대로 실린다(CWE-639).
     *
     * <p>{@code EVNT_TYPE_CD} 가 null/공백인 영상은 제외하고 값은 <b>필터 입력과 같은 규칙</b>
     * ({@link ControlCharNormalizer} — 제어문자 제거 + trim)으로 정규화해 반환한다. 목록 필터
     * ({@code eventTypeCd})도 같은 규칙으로 정규화된 입력을 같은 표현식과 비교하므로
     * (위 {@link #eventTypeMatches}) 옵션을 그대로 다시 보내면 <b>데이터와 무관하게</b> 매칭된다.
     * 한쪽만 {@code trim} 이면 관제 유래 코드에 제어문자(탭 등)가 섞였을 때 "옵션엔 있는데 0건" 이 된다.
     *
     * <p>배정을 구동 테이블로 두고 영상을 PK 로 결합한다(theta join). 결과가 distinct 코드값이라
     * 조인 행 증식이 결과에 영향을 주지 않으며, 바깥 별칭을 기본 별칭과 분리해
     * {@link #buildWhere} 내부 상관 서브쿼리({@code lsDataRaw})와 충돌하지 않게 한다.
     *
     * @param limit 반환 상한 (호출 측이 초과 감지를 위해 상한+1 을 넘길 수 있다) — 무제한 조회 차단(OWASP API4)
     */
    public List<String> findDistinctEventTypes(AssignmentSearchCondition condition, int limit) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);
        QLsTaskAssignment assignment = QLsTaskAssignment.lsTaskAssignment;
        QLsDataRaw raw = new QLsDataRaw("eventTypeRaw");

        BooleanBuilder where = buildWhere(assignment, condition, false);
        StringExpression normalizedEventType = ControlCharNormalizer.normalizedAsJava(raw.evntTypeCd);

        // SELECT DISTINCT 는 ORDER BY 대상이 select 목록에 있어야 하므로 동일 표현식으로 정렬한다.
        return queryFactory
                .select(normalizedEventType)
                .distinct()
                .from(assignment, raw)
                .where(where,
                        raw.rawSn.eq(assignment.rawDataId),
                        raw.evntTypeCd.isNotNull(),
                        normalizedEventType.ne(""))
                .orderBy(normalizedEventType.asc())
                .limit(limit)
                .fetch();
    }

    // ------------------------------------------------------------------ where

    /**
     * 조건 조립 <b>단일 지점</b> — 목록·count·이벤트유형 옵션이 모두 이 메서드를 통과한다.
     *
     * @param includeEventType 이벤트유형 축을 포함할지 — 옵션 조회는 <b>자기 축</b>이라 제외한다
     *                         (포함하면 하나를 고른 뒤 다른 선택지가 사라진다).
     */
    private BooleanBuilder buildWhere(QLsTaskAssignment assignment, AssignmentSearchCondition condition,
                                      boolean includeEventType) {
        BooleanBuilder where = new BooleanBuilder();
        where.and(assignment.taskTypeCd.eq(LsTaskAssignment.TASK_LABELER));

        // 파생영상 등재 게이트 — 미검수 파생은 작업 대상이 아니다(판정은 단일 원천에 위임).
        // 상관 EXISTS 한 겹만 추가한다 — 이 리포지토리의 "행 증식 원천 차단"(조인 금지) 제약을 지킨다.
        // 조건 조립 단일 지점에만 붙여 목록·count·이벤트유형 옵션이 같은 가시 범위를 공유하게 한다.
        where.and(DerivativeWorkEligibility.eligibleByRawSn(assignment.rawDataId));

        // 채널 축 — 포털 자산은 관제 배정 대상이 아니다(ADR-058 흡수). 위 파생 게이트는 파생만
        // 배제하므로 포털 업로드 원본을 막지 못한다. 같은 이유로 상관 EXISTS 한 겹만 더한다.
        where.and(InternalWorkScope.internalByRawSn(assignment.rawDataId));

        if (condition.selfUserNo() != null) {
            // 인가 축 — 이 분기에서는 workerIdFilter 를 참조하지 않는다(CWE-639).
            where.and(assignment.userNo.eq(condition.selfUserNo()));
        } else if (condition.workerIdFilter() != null) {
            where.and(assignment.userNo.eq(condition.workerIdFilter()));
        }

        condition.workStatusFilter().ifPresent(ws -> where.and(workStatusPredicate(assignment, ws)));

        if (includeEventType && !condition.eventTypeMatchCodes().isEmpty()) {
            where.and(eventTypeMatches(assignment, condition.eventTypeMatchCodes()));
        }
        if (condition.q() != null) {
            // Locale.ROOT 고정 — 기본 로케일(예: tr_TR)에서 'I' → 'ı' 로 변환돼 DB lower() 결과와
            // 어긋나면 매칭이 조용히 실패한다(온프렘 배포 로케일은 환경변수에 좌우된다).
            String pattern = LikeEscape.contains(condition.q().toLowerCase(Locale.ROOT));
            where.and(videoNameLike(assignment, pattern).or(workerNameLike(assignment, pattern)));
        }
        return where;
    }

    /**
     * {@code workStatus} 필터 — {@link AssignmentWorkStatus} 가 보유한 조건만 SQL 로 옮긴다.
     *
     * <p>검수 단계(REVIEW_PENDING/COMPLETED/REJECTED)에는 <b>저장 이력 절을 붙이지 않는다</b>. 붙이면
     * 표시 상태({@link AssignmentWorkStatus#of})와 어긋날 뿐 아니라, 조건이 겹쳐 결과가 조용히 줄어든다.
     */
    private BooleanExpression workStatusPredicate(QLsTaskAssignment assignment, AssignmentWorkStatus ws) {
        if (!ws.statusIn().isEmpty()) {
            return statusExists(assignment, ws.statusIn());
        }
        // 기저 상태 — "검수 단계 코드의 상태 row 가 없음"(= ASSIGNED · 미지 코드 · row 자체 없음).
        BooleanExpression base = statusExists(assignment, AssignmentWorkStatus.reviewStageCodes()).not();
        Boolean requiresSaveHistory = ws.requiresSaveHistory();
        if (requiresSaveHistory == null) {
            // 내부 정합성 위반(= 상수 추가 시 SQL 변환 미대응). 사용자 입력으로 도달할 수 없다.
            throw new IllegalStateException(
                    "workStatus 필터 조건을 SQL 로 변환할 수 없습니다 — AssignmentWorkStatus." + ws.name()
                            + " 은 상태 코드도 저장 이력 조건도 지정하지 않았습니다.");
        }
        BooleanExpression hasSaveHistory = hasSaveHistoryExists(assignment);
        return requiresSaveHistory ? base.and(hasSaveHistory) : base.and(hasSaveHistory.not());
    }

    /** 지정 코드 집합에 해당하는 워크플로 상태 row 존재 여부 (row 없음 = 자동 제외). */
    private BooleanExpression statusExists(QLsTaskAssignment assignment, Collection<String> codes) {
        QLsRawDataStatus status = QLsRawDataStatus.lsRawDataStatus;
        return JPAExpressions.selectOne()
                .from(status)
                .where(status.rawDataId.eq(assignment.rawDataId), status.dataSttsCd.in(codes))
                .exists();
    }

    /**
     * 그 영상에 <b>사용자의 라벨 저장 이력</b>이 있는가 — '작업중' 판정의 <b>단일 원천</b>(R5).
     *
     * <p><b>왜 {@code LS_DATA_LBL}(라벨 존재)이 아닌가</b> — 배치 파이프라인(YOLO/SAM2/트랙보간)이
     * 배정 <b>이전에</b> {@code AUTO_LBL_YN='Y'} 라벨을 적재하고, 배정 대상은 배치가 끝난
     * ({@code COMPLETED}) 영상이다. 라벨 존재로 판정하면 배정 직후 전 영상이 '작업중' 이 되고
     * '대기' 에는 "AI 가 아무것도 못 찾은 영상" 만 남는다. 반대로 {@code AUTO_LBL_YN <> 'Y'} 로
     * 거르면, 오토라벨을 수정해도 {@code AUTO_LBL_YN} 이 'Y' 로 유지되는 정책({@code LabelService}
     * 의 기존 UPDATE 규칙) 때문에 실제 작업자가 '대기' 로 남는 반대 방향 오분류가 생긴다.
     *
     * <p><b>이력을 남기는 경로(실측)</b> — 배치 오토라벨 스텝은 이력을 남기지 않으므로 "사람이 손을
     * 댔는가" 에 가깝지만, <b>사용자 저장 경로가 전부 기록하는 것은 아니다</b>:
     * <ul>
     *   <li>기록함 — {@code LabelService.bulkUpsert}(라벨 저장) · {@code TrackEditService} 의
     *       <b>삭제</b> 경로({@code recordDeletionHistory})</li>
     *   <li>기록 안 함 — {@code TrackEditService.doSplit}(트랙 분할) · {@code TrackMergeService.doMerge}
     *       (트랙 병합) · {@code LabelAttrValueService.upsert}(객체 속성 저장)</li>
     * </ul>
     *
     * <p><b>알려진 한계(사용자 확정 — 현재 상태로 수용, 2026-07-30)</b>: 위 '기록 안 함' 작업만 수행한
     * 작업자는 목록에서 <b>'대기'</b> 로 표시된다. 이력 도입 이전의 레거시 작업분도 동일하다(백필 없음).
     * 이 경로들에 이력 기록을 추가하는 것은 데이터마트 뷰({@code V_COMPLETED_LABEL_CHANGE})와 관제
     * 계약에 영향을 주므로 별건이다 — 여기서 판별식을 넓혀 우회하지 말 것.
     *
     * <p><b>저장 이벤트 판별</b>: 같은 테이블에 라벨 델타가 없는 감사 이벤트(변경 0건 롤백, 그리고
     * <b>과거에</b> 적재된 개인정보 메타 리셋 — 2026-08-04 리셋 폐기로 신규 발생은 없으나 기존 행은
     * 남아 있다)도 있으므로 <b>종류별 건수 합 &gt; 0</b> 인 행만 센다({@link #labelDeltaExists}).
     * 이는 데이터마트 뷰 {@code V_COMPLETED_LABEL_CHANGE}(V139)가 쓰는 판별식과 동일하다 — 이 테이블에는
     * 이벤트 유형 컬럼이 없고 건수가 유일한 구조적 판별자다. 두 판별식의 드리프트는
     * {@code SaveHistoryChangeViewParityIT} 가 결과 비교로 결박한다.
     *
     * <p>조인 대신 중첩 {@code EXISTS} 를 쓰는 이유는 배정 1건이 프레임·이력 수만큼 증식하는 것을
     * 원천 차단하기 위해서다(첫 매칭에서 단락 평가되므로 이력이 많아도 비용이 커지지 않는다).
     */
    private BooleanExpression hasSaveHistoryExists(QLsTaskAssignment assignment) {
        QLsDataSrc frame = QLsDataSrc.lsDataSrc;
        QLsDataLblHstry history = QLsDataLblHstry.lsDataLblHstry;
        return JPAExpressions.selectOne()
                .from(frame)
                .where(frame.rawSn.eq(assignment.rawDataId),
                        JPAExpressions.selectOne()
                                .from(history)
                                .where(history.srcSn.eq(frame.srcSn), labelDeltaExists(history))
                                .exists())
                .exists();
    }

    /**
     * 이 저장 이벤트가 <b>라벨 본문을 실제로 바꿨는가</b> — 종류별 건수 중 하나라도 양수.
     *
     * <p>데이터마트 뷰 {@code V_COMPLETED_LABEL_CHANGE}(V139)의
     * {@code COALESCE(ADD_CNT,0)+COALESCE(MDFCN_CNT,0)+COALESCE(DEL_CNT,0) > 0} 과 <b>동치</b>다 —
     * 건수는 음수가 될 수 없으므로 OR 분해와 합 &gt; 0 이 같고, 컬럼은 {@code NOT NULL DEFAULT 0}(V115)라
     * null 이 존재할 수 없다(뷰의 {@code COALESCE} 는 방어적 표기).
     *
     * <p>별도 메서드로 노출하는 이유는 두 판별식의 동치를 <b>테스트로 결박</b>하기 위해서다
     * ({@code SaveHistoryChangeViewParityIT} — 뷰 술어가 바뀌면 실패한다). 여기서 조건을 바꾸려면
     * 뷰(관제 데이터마트 계약)도 함께 바꿔야 한다.
     */
    static BooleanExpression labelDeltaExists(QLsDataLblHstry history) {
        return history.addCnt.gt(0)
                .or(history.mdfcnCnt.gt(0))
                .or(history.delCnt.gt(0));
    }

    /**
     * 저장 이력 존재 여부의 <b>프로젝션</b> 형태 — WHERE 와 완전히 같은 표현식을 SELECT 절에서 재사용한다.
     *
     * <p>{@code exists} 를 그대로 select 목록에 두는 대신 {@code CASE WHEN ... THEN 1 ELSE 0 END} 로
     * 감싼다(HQL 의 select 절 boolean 지원 편차 회피 — KPI 집계도 같은 방식이다).
     */
    private NumberExpression<Integer> hasSaveHistoryFlag(QLsTaskAssignment assignment) {
        return new CaseBuilder()
                .when(hasSaveHistoryExists(assignment))
                .then(1)
                .otherwise(0);
    }

    /**
     * 이벤트 유형 필터 — 컬럼을 <b>옵션 목록과 같은 규칙</b>({@link ControlCharNormalizer})으로 정규화한
     * 뒤 비교한다.
     *
     * <p>비교 축이 옵션과 어긋나면(예: 여기만 {@code trim}) 제어문자가 섞인 코드에서 "옵션에서 골랐는데
     * 0건" 이 되어 UI 로 도달할 수 없는 데이터가 생긴다. 입력({@code eventTypeCd})은 이미
     * {@code AssignmentSearchCondition} 이 같은 규칙으로 정규화해 넘겨준다.
     *
     * <p>[req: R6] 비교 대상은 <b>표시명 그룹 전체 코드</b>다 — 옵션이 대표코드로 접혔으므로 단일 코드
     * 동등비교로 두면 대표코드로 필터할 때 그룹의 나머지 코드 배정이 통째로 누락된다. 확장이 없는
     * 조건은 집합 크기가 1 이라 종전 {@code eq} 와 동치이며, 값은 전부 파라미터 바인딩된다(CWE-89).
     */
    private BooleanExpression eventTypeMatches(QLsTaskAssignment assignment, Collection<String> eventTypeCds) {
        QLsDataRaw raw = QLsDataRaw.lsDataRaw;
        return JPAExpressions.selectOne()
                .from(raw)
                .where(raw.rawSn.eq(assignment.rawDataId),
                        ControlCharNormalizer.normalizedAsJava(raw.evntTypeCd).in(eventTypeCds))
                .exists();
    }

    /**
     * 영상명 부분일치 — <b>화면 표시명</b>(관제 인입 {@code LS_DATA_INGEST.CCTV_NM}, 없으면
     * {@code VMS_CCTV_ID} 폴백) 기준. 표시명 해석은 응답을 만드는
     * {@code AssignmentService.lookupCctvNameByVideo} 와 같은 규칙이어야 한다 — 그렇지 않으면 "화면에
     * 보이는 값으로 검색했는데 안 나오는" 영상이 생긴다.
     * 공백 판정은 표시측 Java {@code isBlank()} 와 동치인 {@link BlankTextPredicate} 에 위임한다.
     *
     * <p>구 소스({@code MNG_RESOURCE_CCTV})는 V167 로 제거됐다. 영상↔인입 연결 규칙(파생영상
     * {@code ORGNL_RAW_SN} 1단계 폴백)은 {@link IngestSourceLink#matchesSourceOf} 단일 진실원이며,
     * 그 덕에 <b>작업 목록에 함께 노출되는 파생영상도 부모의 CCTV 명으로 검색된다</b>.
     */
    private BooleanExpression videoNameLike(QLsTaskAssignment assignment, String pattern) {
        QLsDataRaw raw = QLsDataRaw.lsDataRaw;
        QLsDataIngest ingest = QLsDataIngest.lsDataIngest;

        BooleanExpression cctvNameMatches = JPAExpressions.selectOne()
                .from(ingest)
                .where(IngestSourceLink.matchesSourceOf(ingest, raw),
                        ingest.cctvNm.lower().like(pattern, LikeEscape.ESCAPE_CHAR))
                .exists();

        BooleanExpression displayNameIsCctvId = JPAExpressions.selectOne()
                .from(ingest)
                .where(IngestSourceLink.matchesSourceOf(ingest, raw),
                        ingest.cctvNm.isNotNull(),
                        BlankTextPredicate.isBlankAsJava(ingest.cctvNm).not())
                .exists()
                .not();

        return JPAExpressions.selectOne()
                .from(raw)
                .where(raw.rawSn.eq(assignment.rawDataId),
                        cctvNameMatches.or(displayNameIsCctvId
                                .and(raw.vmsCctvId.lower().like(pattern, LikeEscape.ESCAPE_CHAR))))
                .exists();
    }

    /**
     * 작업자명 부분일치 — 이 행에 배정된 작업자({@code LS_ACNT_USER.USER_NM}) 기준.
     * 목록의 행 자체가 배정이므로 "최신 배정" 같은 추가 판정이 필요 없다(작업목록/board 와의 차이).
     */
    private BooleanExpression workerNameLike(QLsTaskAssignment assignment, String pattern) {
        QLsAcntUser user = QLsAcntUser.lsAcntUser;
        return JPAExpressions.selectOne()
                .from(user)
                .where(user.userNo.eq(assignment.userNo),
                        user.userNm.lower().like(pattern, LikeEscape.ESCAPE_CHAR))
                .exists();
    }

    // ---------------------------------------------------------------- order by

    /**
     * 정렬 조립 — 허용 필드만 해석하고 <b>전순서(total order)를 보장</b>한다.
     *
     * <p>{@code ASSIGNMENT_ID} 는 유니크 PK 이므로, 요청 정렬에 없으면 마지막에
     * {@code ASSIGNMENT_ID DESC} 를 append 하고 있으면 그 지점에서 순서가 확정되므로 append 하지 않는다.
     *
     * <p>정렬 키·개수는 컨트롤러의 {@code SortAllowlist} 가 이미 검증했지만, 리포지토리에서도
     * fail-closed 로 400 처리하고 동일 필드 중복을 제거해, 어떤 호출 경로에서도 임의 프로퍼티나
     * 무제한 {@code ORDER BY} 가 쿼리에 닿지 않게 한다.
     */
    private OrderSpecifier<?>[] orderSpecifiers(QLsTaskAssignment assignment, Sort sort) {
        List<OrderSpecifier<?>> orders = new ArrayList<>();
        Set<String> appliedFields = new LinkedHashSet<>();

        if (sort != null && sort.isSorted()) {
            int seen = 0;
            for (Sort.Order order : sort) {
                if (++seen > MAX_SORT_ORDERS) {
                    throw new CustomException(ErrorCode.INVALID_INPUT, "정렬 기준이 너무 많습니다.");
                }
                ComparableExpressionBase<?> path = switch (order.getProperty()) {
                    case "regDt" -> assignment.regDt;
                    case "rawDataId" -> assignment.rawDataId;
                    case "assignmentId" -> assignment.assignmentId;
                    default -> throw new CustomException(ErrorCode.INVALID_INPUT, "지원하지 않는 정렬 기준입니다.");
                };
                if (appliedFields.add(order.getProperty())) {
                    orders.add(order.isAscending() ? path.asc() : path.desc());
                }
            }
        }
        if (orders.isEmpty()) {
            orders.add(assignment.regDt.desc());
        }
        if (!appliedFields.contains("assignmentId")) {
            orders.add(assignment.assignmentId.desc());
        }
        return orders.toArray(new OrderSpecifier<?>[0]);
    }
}
