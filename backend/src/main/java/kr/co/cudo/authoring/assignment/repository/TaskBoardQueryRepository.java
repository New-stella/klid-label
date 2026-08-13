package kr.co.cudo.authoring.assignment.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Expression;
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
import kr.co.cudo.authoring.assignment.domain.BoardWorkStatus;
import kr.co.cudo.authoring.assignment.dto.TaskBoardSearchCondition;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.entity.QLsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.QLsTaskAssignment;
import kr.co.cudo.authoring.augment.repository.DerivativeWorkEligibility;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.BlankTextPredicate;
import kr.co.cudo.authoring.common.util.LikeEscape;
import kr.co.cudo.authoring.common.util.SortAllowlist;
import kr.co.cudo.authoring.user.entity.QLsAcntUser;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.entity.QLsDataRaw;
import kr.co.cudo.authoring.video.entity.QLsDataIngest;
import kr.co.cudo.authoring.video.repository.IngestSourceLink;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 작업목록(SCR-TASK-001, {@code GET /v1/tasks/board}) 전용 QueryDSL 리포지토리.
 *
 * <p><b>정렬 정책(R1)</b>: 구 {@code VideoRepository.findBoardOrderByStatusPriority} 의 상태 우선순위
 * {@code ORDER BY CASE}(반려&gt;검수대기&gt;배정&gt;미배정&gt;완료)는 폐기됐다. 정렬은 시간축 단일
 * (기본 {@code REG_DT DESC})이고 우선순위는 {@code workStatus} 필터로 표현한다.
 *
 * <p><b>설계 제약</b>:
 * <ul>
 *   <li><b>행 증식 원천 차단(HIGH-1)</b> — {@code LS_TASK_ASSIGNMENT} 는 한 영상에 여러 행(재배정
 *       누적)이 존재할 수 있고, {@code LS_DATA_INGEST}/{@code LS_ACNT_USER} 도 조인 대상이다.
 *       따라서 <b>어떤 조인도 사용하지 않고</b> 모든 조건을 상관 서브쿼리({@code EXISTS})로만 표현한다.
 *       {@code FROM LS_DATA_RAW} 단일 테이블이라 결과 행이 영상 1건=1행으로 고정되고
 *       {@code totalElements} 가 실제 영상 수와 어긋날 수 없다.</li>
 *   <li><b>목록/count 조건 단일 관리(HIGH-2)</b> — 하나의 {@link BooleanBuilder} 를 목록·count 쿼리가
 *       공유한다. 조건이 갈라질 구조적 여지가 없다.</li>
 *   <li><b>표시 상태와 필터의 동치(HIGH-3)</b> — {@code workStatus} 조건은 {@link BoardWorkStatus} 가
 *       보유한 필드(배정 요구/상태 화이트리스트/제외 집합/null 허용)만 읽어 조립한다. 화면 뱃지를
 *       만드는 {@link BoardWorkStatus#of} 와 단일 원천을 공유한다.</li>
 *   <li><b>tie-break 강제(HIGH-10)</b> — 어떤 정렬 조합에도 전순서를 보장한다. 요청 정렬에
 *       {@code rawSn} 이 없으면 {@code RAW_SN DESC} 를 마지막에 append 하고, 있으면 유니크 PK 인
 *       {@code rawSn} 지점에서 순서가 확정되므로 append 하지 않는다. 어느 쪽이든 페이지 경계에서
 *       행 중복/누락이 발생하지 않는다.</li>
 *   <li><b>안전 바인딩(CWE-89)</b> — 검색어는 {@code %}/{@code _}/{@code \} 이스케이프 후
 *       {@code like(..., '\')} 로 바인딩하고, 정렬 키는 명시 화이트리스트({@code switch})로만 해석한다.
 *       문자열 연결로 쿼리를 만드는 지점이 없다.</li>
 * </ul>
 */
@Repository
public class TaskBoardQueryRepository {

    private static final char ESCAPE_CHAR = '\\';

    @PersistenceContext(unitName = "control")
    private EntityManager entityManager;

    /**
     * 조건 + 페이징으로 작업목록 대상 영상을 조회한다.
     *
     * @param condition 검색 조건 (null 불가 — 호출 측에서 기본값 조립)
     * @param pageable  페이징/정렬 (정렬 키는 호출 측이 allowlist 로 검증·매핑한 엔티티 필드명)
     */
    public Page<LsDataRaw> search(TaskBoardSearchCondition condition, Pageable pageable) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);
        QLsDataRaw raw = QLsDataRaw.lsDataRaw;

        BooleanBuilder where = buildWhere(raw, condition);

        List<LsDataRaw> content = queryFactory
                .selectFrom(raw)
                .where(where)
                .orderBy(orderSpecifiers(raw, pageable.getSort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        JPAQuery<Long> countQuery = queryFactory
                .select(raw.count())
                .from(raw)
                .where(where);

        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }

    /**
     * 워크플로 상태(5종)별 건수 집계 — KPI 카드({@code GET /v1/tasks/board/summary})용.
     *
     * <p><b>필터는 목록과 동일하되 {@code workStatus} 만 제외</b>한다 — KPI 카드 자체가 workStatus
     * 선택지이므로, 이미 workStatus 로 좁혀진 집합 위에서 5종을 세면 항상 1개 카드만 non-zero 가 된다.
     * 조건 조립은 {@link #buildWhere(QLsDataRaw, TaskBoardSearchCondition, boolean)} 를 그대로
     * 재사용하므로 검색어 LIKE 이스케이프·최신 배정 판정 같은 로직이 목록과 갈라지지 않는다(HIGH-12).
     *
     * <p><b>5회 count 가 아니라 쿼리 1회</b>다. 버킷 판정식은 {@link BoardWorkStatus} 상수를 순회하며
     * {@link #workStatusPredicate} 로 생성하므로, 필터/표시/집계 세 경로가 같은 조건 정의를 공유한다
     * (HIGH-1 — 하드코딩 CASE 는 세 번째 구현이 되어 드리프트를 부른다).
     *
     * <p><b>왜 {@code GROUP BY CASE} 가 아니라 조건부 집계({@code SUM(CASE WHEN ... )})인가</b> —
     * 버킷 판정식은 배정/상태 row 를 <b>상관 서브쿼리</b>로 참조한다(조인 행 증식 차단, HIGH-1).
     * PostgreSQL 은 {@code GROUP BY} 대상 표현식 안의 서브쿼리가 바깥 컬럼을 참조하면
     * {@code ERROR: subquery uses ungrouped column ... from outer query}(SQLSTATE 42803)로 거부한다.
     * 조건부 집계는 같은 판정식을 SELECT 절에서 쓰므로 이 제약에 걸리지 않고, 라운드트립도 1회로 동일하다.
     * 덤으로 {@code COUNT(*)} 와 버킷 합을 대조할 수 있어 <b>버킷 누락뿐 아니라 중복 분류(겹침)까지</b>
     * 검출된다({@code GROUP BY} 방식으로는 겹침을 볼 수 없다).
     *
     * @return 5종 전부가 채워진 맵 (매칭 0건인 상태는 0)
     */
    public Map<BoardWorkStatus, Long> countByWorkStatus(TaskBoardSearchCondition condition) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);
        QLsDataRaw raw = QLsDataRaw.lsDataRaw;

        BooleanBuilder where = buildWhere(raw, condition, false);

        NumberExpression<Long> totalCount = raw.count();
        Map<BoardWorkStatus, NumberExpression<Long>> bucketExpressions = new EnumMap<>(BoardWorkStatus.class);
        List<Expression<?>> selects = new ArrayList<>();
        selects.add(totalCount);
        for (BoardWorkStatus ws : BoardWorkStatus.values()) {
            NumberExpression<Long> bucket = bucketCount(raw, ws);
            bucketExpressions.put(ws, bucket);
            selects.add(bucket);
        }

        Tuple row = queryFactory
                .select(selects.toArray(new Expression<?>[0]))
                .from(raw)
                .where(where)
                .fetchOne();

        // 5종을 먼저 0 으로 채운다 — 결과가 0건이면 SUM 은 NULL 이므로 여기서 명시적으로 메워야 한다(HIGH-9).
        Map<BoardWorkStatus, Long> counts = new EnumMap<>(BoardWorkStatus.class);
        for (BoardWorkStatus ws : BoardWorkStatus.values()) {
            counts.put(ws, 0L);
        }
        if (row == null) {
            return counts;
        }
        long bucketSum = 0L;
        for (Map.Entry<BoardWorkStatus, NumberExpression<Long>> entry : bucketExpressions.entrySet()) {
            long value = nullToZero(row.get(entry.getValue()));
            counts.put(entry.getKey(), value);
            bucketSum += value;
        }
        long total = nullToZero(row.get(totalCount));
        if (bucketSum != total) {
            throw bucketCoverageViolation(total, bucketSum);
        }
        return counts;
    }

    /** 해당 상태에 속하는 행 수 — {@code SUM(CASE WHEN <상태 판정식> THEN 1 ELSE 0 END)}. */
    private NumberExpression<Long> bucketCount(QLsDataRaw raw, BoardWorkStatus ws) {
        return new CaseBuilder()
                .when(workStatusPredicate(raw, ws))
                .then(1L)
                .otherwise(0L)
                .sum();
    }

    private static long nullToZero(Long value) {
        return value != null ? value : 0L;
    }

    /**
     * 이벤트유형 코드 distinct 목록 — 셀렉트 옵션({@code GET /v1/tasks/board/event-types})용.
     *
     * <p>적용 필터는 <b>배치 상태 축({@code status}) 하나뿐</b>이다({@link TaskBoardSearchCondition#statusOnly()}).
     * {@code EVNT_TYPE_CD} 가 null/공백인 행은 명시적으로 제외해 셀렉트박스에 빈 옵션이 새지 않게 한다.
     *
     * <p><b>값도 {@code trim} 해서 반환</b>한다 — 목록 필터 입력({@code eventTypeCd})은
     * {@link TaskBoardSearchCondition} 에서 trim 된 뒤 {@code eq} 로 비교되므로, 옵션만 원본(예:
     * 선행 공백이 섞인 {@code " EVT-FIRE"})을 내보내면 <b>사용자가 고른 옵션으로 필터했을 때 0건</b>이
     * 나온다. trim 후 distinct 이므로 공백만 다른 코드는 하나의 옵션으로 합쳐진다.
     *
     * @param limit 반환 상한 (호출 측이 초과 감지를 위해 상한+1 을 넘길 수 있다) — 무제한 조회 차단(OWASP API4)
     */
    public List<String> findDistinctEventTypes(TaskBoardSearchCondition condition, int limit) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);
        QLsDataRaw raw = QLsDataRaw.lsDataRaw;

        BooleanBuilder where = buildWhere(raw, condition.statusOnly(), false);
        StringExpression trimmedEventType = raw.evntTypeCd.trim();
        where.and(raw.evntTypeCd.isNotNull());
        where.and(trimmedEventType.ne(""));

        // SELECT DISTINCT 는 ORDER BY 대상이 select 목록에 있어야 하므로 동일 표현식으로 정렬한다.
        return queryFactory
                .select(trimmedEventType)
                .distinct()
                .from(raw)
                .where(where)
                .orderBy(trimmedEventType.asc())
                .limit(limit)
                .fetch();
    }

    /**
     * 버킷 합 ≠ 전체 건수 = {@link BoardWorkStatus} 조건이 (배정 유무 × 상태 코드) 공간을 빠짐없이·
     * 겹치지 않게 덮지 못한다는 뜻(누락이면 합&lt;전체, 겹침이면 합&gt;전체). 상수가 추가/변경되는 순간
     * 즉시 드러나도록 fail-closed 로 막는다 — 조용히 넘기면 KPI 카드 합과 총건수가 갈라진다.
     *
     * <p>내부 정합성 위반이며 사용자 입력으로 도달할 수 없다(메시지에 입력값을 담지 않는다, CWE-209).
     */
    private static IllegalStateException bucketCoverageViolation(long total, long bucketSum) {
        return new IllegalStateException(
                "작업목록 KPI 집계의 상태 버킷이 전체 건수를 덮지 못합니다 — "
                        + "BoardWorkStatus 조건 정의를 확인하세요. total=" + total + ", bucketSum=" + bucketSum);
    }

    // ------------------------------------------------------------------ where

    private BooleanBuilder buildWhere(QLsDataRaw raw, TaskBoardSearchCondition condition) {
        return buildWhere(raw, condition, true);
    }

    /**
     * 조건 조립 <b>단일 지점</b> — 목록/count/집계/옵션 조회가 모두 이 메서드를 통과한다.
     *
     * @param includeWorkStatus {@code workStatus} 필터 적용 여부. KPI 집계는 5종을 모두 세야 하므로
     *                          이 조건만 빼고 나머지({@code status}/{@code q}/{@code eventTypeCd}/
     *                          {@code workerId})는 목록과 완전히 동일하게 적용한다(HIGH-12).
     */
    private BooleanBuilder buildWhere(QLsDataRaw raw, TaskBoardSearchCondition condition,
                                      boolean includeWorkStatus) {
        BooleanBuilder where = new BooleanBuilder();

        // 파생영상 등재 게이트 — 미검수 파생은 작업 대상이 아니다(판정은 단일 원천에 위임).
        // 필터가 아니라 <가시 범위> 이므로 목록·count·KPI 집계·이벤트유형 옵션 <전부>에 걸린다.
        // 그래서 여기(조건 조립 단일 지점)에만 붙인다 — 호출부마다 붙이면 한 곳이 빠져 샌다.
        where.and(DerivativeWorkEligibility.eligible(raw));

        String batchStatus = condition.batchStatusFilter();
        if (batchStatus != null) {
            where.and(raw.dataSttsCd.eq(batchStatus));
        }
        if (condition.unassignedOnly()) {
            // 미배정 가상 status — 배치 상태 무관, LABELER 배정이 없는 영상만.
            where.and(labelerExists(raw).not());
        }
        if (includeWorkStatus) {
            condition.workStatusFilter().ifPresent(ws -> where.and(workStatusPredicate(raw, ws)));
        }

        Set<String> eventTypeCodes = condition.eventTypeMatchCodes();
        if (!eventTypeCodes.isEmpty()) {
            // 입력은 TaskBoardSearchCondition 에서 trim 되고 옵션 목록(findDistinctEventTypes)도 trim 된
            // 값을 내보내므로, 비교 대상 컬럼도 trim 해 세 지점의 의미를 통일한다. 그렇지 않으면
            // EVNT_TYPE_CD 에 공백이 섞인 행(" EVT-FIRE")은 셀렉트박스의 옵션(EVT-FIRE)을 골라도
            // 0건이 되어 UI 로 도달할 수 없다. 기존 exact 매칭 결과의 상위집합이라 하위호환도 유지된다.
            //
            // [req: R6] 비교 대상은 <표시명 그룹 전체 코드>다 — 옵션이 대표코드로 접혔으므로 단일 코드
            //   동등비교로 두면 대표코드로 필터할 때 그룹의 나머지 코드 영상이 통째로 누락된다.
            //   확장이 없는 조건(리포지토리 직접 호출)은 집합 크기가 1 이라 종전 eq 와 동치다.
            //   값은 전부 파라미터 바인딩된다(CWE-89) — 집합 크기는 마스터 그룹 크기로 유계다.
            where.and(raw.evntTypeCd.trim().in(eventTypeCodes));
        }
        if (condition.workerId() != null) {
            where.and(latestLabelerMatches(raw, a -> a.userNo.eq(condition.workerId())));
        }
        if (condition.q() != null) {
            // Locale.ROOT 고정 — 기본 로케일(예: tr_TR)에서 'I' → 'ı' 로 변환돼 DB lower() 결과와
            // 어긋나면 매칭이 조용히 실패한다(온프렘 배포 로케일은 환경변수에 좌우된다).
            String pattern = "%" + escapeLike(condition.q().toLowerCase(Locale.ROOT)) + "%";
            where.and(videoNameLike(raw, pattern).or(workerNameLike(raw, pattern)));
        }
        return where;
    }

    /**
     * {@code workStatus} 필터 — {@link BoardWorkStatus} 가 보유한 조건 필드만 읽어 SQL 로 옮긴다
     * (Java 판정은 {@link BoardWorkStatus#matches}). 상태 row 는 상관 서브쿼리로만 참조해
     * 조인 행 증식이 발생하지 않는다.
     *
     * <p><b>지원 조합만 변환하고 나머지는 fail-fast</b>: 아래 두 조합은 현재 enum 상수에 존재하지
     * 않으며, SQL 로 옮길 정확한 대응물도 없다(상태 row 유무를 추가로 물어야 해 조건이 갈라진다).
     * 조용히 넓은 결과를 반환하면 필터 결과와 화면 뱃지가 어긋나므로(HIGH-3), 상수가 추가되는
     * 순간 즉시 드러나도록 {@link IllegalStateException} 으로 막는다.
     * <ul>
     *   <li>{@code statusIn} 지정 + {@code matchesNullStatus=true} — {@code EXISTS} 는 상태 row 가
     *       없는 행을 포함할 수 없다.</li>
     *   <li>{@code matchesNullStatus=false} + {@code statusIn} 미지정 — "상태 row 가 존재"라는
     *       조건을 별도로 요구하게 된다.</li>
     * </ul>
     */
    private BooleanExpression workStatusPredicate(QLsDataRaw raw, BoardWorkStatus ws) {
        BooleanExpression predicate = ws.requiresLabeler()
                ? labelerExists(raw)
                : labelerExists(raw).not();

        if (!ws.statusIn().isEmpty()) {
            if (ws.matchesNullStatus()) {
                throw unsupportedWorkStatus(ws);
            }
            // 지정 코드 집합에 해당하는 상태 row 가 존재 (row 없음 = null 은 자동 제외).
            return predicate.and(statusExists(raw, ws.statusIn()));
        }
        if (!ws.matchesNullStatus()) {
            throw unsupportedWorkStatus(ws);
        }
        if (!ws.statusNotIn().isEmpty()) {
            // 제외 코드 집합에 해당하는 상태 row 가 없음.
            //   - 상태 row 자체가 없는 행(null) → NOT EXISTS 성립 → 포함 (matchesNullStatus=true 와 동치)
            //   - ASSIGNED/미지 코드 → NOT EXISTS 성립 → 포함
            return predicate.and(statusExists(raw, ws.statusNotIn()).not());
        }
        // 상태 코드 제약 없음 — 배정 유무만으로 결정(UNASSIGNED).
        return predicate;
    }

    /** 내부 정합성 위반(= enum 상수 추가 시 SQL 변환 미대응) — 사용자 입력으로 도달할 수 없다. */
    private static IllegalStateException unsupportedWorkStatus(BoardWorkStatus ws) {
        return new IllegalStateException(
                "workStatus 필터 조건을 SQL 로 변환할 수 없습니다 — BoardWorkStatus." + ws.name()
                        + " 의 조건 조합이 지원되지 않습니다.");
    }

    private BooleanExpression statusExists(QLsDataRaw raw, java.util.Collection<String> codes) {
        QLsRawDataStatus status = QLsRawDataStatus.lsRawDataStatus;
        return JPAExpressions.selectOne()
                .from(status)
                .where(status.rawDataId.eq(raw.rawSn), status.dataSttsCd.in(codes))
                .exists();
    }

    /** LABELER 배정 존재 여부 (재배정 이력이 여러 건이어도 EXISTS 라 행이 증식되지 않는다). */
    private BooleanExpression labelerExists(QLsDataRaw raw) {
        QLsTaskAssignment assignment = QLsTaskAssignment.lsTaskAssignment;
        return JPAExpressions.selectOne()
                .from(assignment)
                .where(assignment.rawDataId.eq(raw.rawSn),
                        assignment.taskTypeCd.eq(LsTaskAssignment.TASK_LABELER))
                .exists();
    }

    /**
     * <b>최신</b> LABELER 배정 1건이 주어진 조건을 만족하는지 — 작업자 필터(workerId/작업자명)용.
     *
     * <p>화면에 표시되는 작업자는 최신 배정 1건({@code REG_DT DESC, ASSIGNMENT_ID DESC})이므로,
     * 필터도 그 1건만 본다(과거 배정 이력에 걸려 "필터한 작업자와 다른 작업자가 표시되는" 불일치 방지).
     * "더 최신 배정이 존재하지 않음"을 NOT EXISTS 로 표현해 정확히 1건을 지목하며, 전체는 EXISTS 라
     * 행 증식이 없다(HIGH-1).
     */
    private BooleanExpression latestLabelerMatches(
            QLsDataRaw raw, java.util.function.Function<QLsTaskAssignment, BooleanExpression> userCondition) {
        QLsTaskAssignment assignment = QLsTaskAssignment.lsTaskAssignment;
        QLsTaskAssignment newer = new QLsTaskAssignment("newerAssignment");

        BooleanExpression newerExists = JPAExpressions.selectOne()
                .from(newer)
                .where(newer.rawDataId.eq(raw.rawSn),
                        newer.taskTypeCd.eq(LsTaskAssignment.TASK_LABELER),
                        newer.regDt.gt(assignment.regDt)
                                .or(newer.regDt.eq(assignment.regDt)
                                        .and(newer.assignmentId.gt(assignment.assignmentId))))
                .exists();

        return JPAExpressions.selectOne()
                .from(assignment)
                .where(assignment.rawDataId.eq(raw.rawSn),
                        assignment.taskTypeCd.eq(LsTaskAssignment.TASK_LABELER),
                        userCondition.apply(assignment),
                        newerExists.not())
                .exists();
    }

    /**
     * 영상명 부분일치 — 화면 표시명(관제 인입 {@code LS_DATA_INGEST.CCTV_NM}, 없으면
     * {@code VMS_CCTV_ID} 폴백) 기준으로 검색한다. CCTV 명이 있는 영상은 화면에 보이지 않는
     * {@code VMS_CCTV_ID} 로 매칭되지 않는다.
     *
     * <p>구 소스({@code MNG_RESOURCE_CCTV})는 V167 로 제거됐다. 영상↔인입 연결 규칙(파생영상
     * {@code ORGNL_RAW_SN} 1단계 폴백)은 {@link IngestSourceLink#matchesSourceOf} 단일 진실원이며,
     * 그 덕에 <b>작업목록에 함께 노출되는 파생영상도 부모의 CCTV 명으로 검색된다</b>.
     *
     * <p>"CCTV 명이 비었는가" 판정은 표시측({@code CctvDisplayNamePolicy} 의 Java 공백 판정)과
     * <b>같은 의미</b>여야 한다. 구 구현은 SQL {@code trim(cctvNm) <> ''} 였는데
     * {@code trim()} 은 공백문자(U+0020)만 제거하므로 {@code CCTV_NM='\t'} 같은 값이 <b>화면에는
     * {@code VMS_CCTV_ID} 로 표시되면서 검색에서는 그 축이 열리지 않아</b> "보이는 값으로 검색해도
     * 안 나오는" 영상이 생겼다(검수목록에서 이미 통일한 것과 같은 불일치). 판정은 단일 원천
     * {@link BlankTextPredicate} 에 위임한다 — 검수목록 {@code ReviewQueryRepository} 도 같은 것을 쓴다.
     */
    private BooleanExpression videoNameLike(QLsDataRaw raw, String pattern) {
        QLsDataIngest ingest = QLsDataIngest.lsDataIngest;

        BooleanExpression cctvNameMatches = JPAExpressions.selectOne()
                .from(ingest)
                .where(IngestSourceLink.matchesSourceOf(ingest, raw),
                        ingest.cctvNm.lower().like(pattern, ESCAPE_CHAR))
                .exists();

        BooleanExpression displayNameIsCctvId = JPAExpressions.selectOne()
                .from(ingest)
                .where(IngestSourceLink.matchesSourceOf(ingest, raw),
                        ingest.cctvNm.isNotNull(),
                        BlankTextPredicate.isBlankAsJava(ingest.cctvNm).not())
                .exists()
                .not();

        return cctvNameMatches.or(displayNameIsCctvId.and(raw.vmsCctvId.lower().like(pattern, ESCAPE_CHAR)));
    }

    /** 작업자명 부분일치 — 최신 LABELER 배정 작업자의 이름(LS_ACNT_USER.USER_NM) 기준. */
    private BooleanExpression workerNameLike(QLsDataRaw raw, String pattern) {
        QLsAcntUser user = QLsAcntUser.lsAcntUser;
        return latestLabelerMatches(raw, assignment -> JPAExpressions.selectOne()
                .from(user)
                .where(user.userNo.eq(assignment.userNo), user.userNm.lower().like(pattern, ESCAPE_CHAR))
                .exists());
    }

    /**
     * LIKE 특수문자({@code \ % _}) 이스케이프 — 와일드카드 주입으로 필터가 무력화되는 것을 막는다.
     *
     * <p>규칙 자체는 {@link LikeEscape} 가 단일 원천으로 보유한다 — 배정 목록
     * ({@code AssignmentQueryRepository}) 이 같은 규칙을 써야 하는데 사본을 만들면 한쪽만 바뀌어도
     * 드러나지 않기 때문이다. 여기서는 호출부를 바꾸지 않기 위해 위임만 한다.
     */
    private static String escapeLike(String raw) {
        return LikeEscape.escape(raw);
    }

    // ---------------------------------------------------------------- order by

    /**
     * 정렬 항목 개수 상한(CWE-770) — {@link SortAllowlist#TASK_BOARD} 에서 <b>파생</b>한다.
     *
     * <p>상수를 하드코딩하면 allowlist 확장 시 수동 동기화가 필요해 두 정의가 조용히 어긋난다
     * (컨트롤러는 4건을 허용하는데 리포지토리가 3건에서 400 을 던지는 식). 검수목록
     * {@code ReviewQueryRepository.MAX_SORT_ORDERS} 도 같은 방식으로 파생시킨다.
     *
     * <p>가시성이 package-private 인 이유: 파생값이 allowlist 와 일치함을 테스트가 직접 고정한다
     * ({@code TaskBoardSortOrderLimitTest}).
     */
    static final int MAX_SORT_ORDERS = SortAllowlist.maxOrders(SortAllowlist.TASK_BOARD);

    /**
     * 정렬 조립 — 허용 필드만 해석하고 <b>전순서(total order)를 보장</b>해 페이지 경계에서 행 중복/누락이
     * 생기지 않게 한다(HIGH-10). {@code RAW_SN} 은 유니크 PK 이므로,
     * <ul>
     *   <li>요청 정렬에 {@code rawSn} 이 <b>없으면</b> 마지막에 {@code RAW_SN DESC} 를 append 하고,</li>
     *   <li>요청 정렬에 {@code rawSn} 이 <b>있으면</b>(예: {@code sort=videoId,desc&sort=regDt,asc})
     *       그 지점에서 이미 순서가 확정되므로 append 하지 않는다 — 이때 {@code rawSn} 은 마지막 정렬
     *       항목이 아닐 수 있으나, 뒤따르는 항목은 비교에 도달하지 못해 안정성에는 영향이 없다.</li>
     * </ul>
     *
     * <p>정렬 키·개수는 컨트롤러의 {@code SortAllowlist} 가 이미 검증했지만, 리포지토리에서도 미허용 키와
     * 개수 초과를 400 으로 fail-closed 처리하고 동일 필드 중복을 제거해, 어떤 호출 경로에서도 임의
     * 프로퍼티나 무제한 {@code ORDER BY} 가 쿼리에 닿지 않게 한다.
     */
    private OrderSpecifier<?>[] orderSpecifiers(QLsDataRaw raw, Sort sort) {
        List<OrderSpecifier<?>> orders = new ArrayList<>();
        Set<String> appliedFields = new LinkedHashSet<>();

        if (sort != null && sort.isSorted()) {
            int seen = 0;
            for (Sort.Order order : sort) {
                if (++seen > MAX_SORT_ORDERS) {
                    throw new CustomException(ErrorCode.INVALID_INPUT, "정렬 기준이 너무 많습니다.");
                }
                ComparableExpressionBase<?> path = switch (order.getProperty()) {
                    case "regDt" -> raw.regDt;
                    case "shtDt" -> raw.shtDt;
                    case "rawSn" -> raw.rawSn;
                    default -> throw new CustomException(ErrorCode.INVALID_INPUT, "지원하지 않는 정렬 기준입니다.");
                };
                if (appliedFields.add(order.getProperty())) {
                    orders.add(order.isAscending() ? path.asc() : path.desc());
                }
            }
        }
        if (orders.isEmpty()) {
            orders.add(raw.regDt.desc());
        }
        if (!appliedFields.contains("rawSn")) {
            orders.add(raw.rawSn.desc());
        }
        return orders.toArray(new OrderSpecifier<?>[0]);
    }
}
