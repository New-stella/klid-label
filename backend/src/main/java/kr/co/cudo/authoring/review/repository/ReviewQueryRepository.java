package kr.co.cudo.authoring.review.repository;

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
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.entity.QLsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.QLsTaskAssignment;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.BlankTextPredicate;
import kr.co.cudo.authoring.common.util.SortAllowlist;
import kr.co.cudo.authoring.review.dto.ReviewSearchCondition;
import kr.co.cudo.authoring.user.entity.QLsAcntUser;
import kr.co.cudo.authoring.video.entity.QLsDataRaw;
import kr.co.cudo.authoring.video.entity.QLsDataIngest;
import kr.co.cudo.authoring.video.repository.IngestSourceLink;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 검수목록(SCR-REVIEW-001, {@code GET /v1/reviews}) 전용 QueryDSL 리포지토리.
 *
 * <p><b>왜 JPQL {@code @Query} 를 대체하는가</b>: 구 {@code ReviewRepository.searchByStatus} 는 JPQL 에
 * 정적 {@code ORDER BY s.updDt DESC} 를 박아 두어, Spring Data 가 {@code Pageable} 의 {@code Sort} 를
 * 뒤에 이어 붙여도 <b>1차 정렬이 바뀌지 않았다</b>(FE 의 '제출일' 정렬 헤더가 동작하지 않던 원인).
 * 정렬을 파라미터로 받으려면 쿼리 조립을 코드로 가져와야 한다.
 *
 * <p><b>설계 제약</b>:
 * <ul>
 *   <li><b>화이트리스트 상시 적용(HIGH-2)</b> — {@code PENDING/IN_REVIEW/APPROVED/REJECTED} 외의
 *       배치/작업 상태({@code ASSIGNED/BATCH_QUEUED/PROCESSING/COMPLETED/FAILED})가 검수 목록에
 *       새는 것을 막는 <b>보안 경계</b>다. 목록·count·집계가 모두 {@link #buildWhere} 하나를 통과하므로
 *       경로가 갈라질 구조적 여지가 없다.</li>
 *   <li><b>행 증식 원천 차단</b> — 검색어 대상인 {@code LS_DATA_INGEST}/{@code LS_TASK_ALTMNT}/
 *       {@code LS_ACNT_USER} 를 <b>조인하지 않고</b> 상관 {@code EXISTS} 로만 참조한다.
 *       {@code FROM LS_RAW_DATA_STATUS} 단일 테이블이라 결과가 영상 1건=1행으로 고정되고
 *       {@code totalElements} 가 실제 건수와 어긋날 수 없다.</li>
 *   <li><b>검색어는 DB 단계에서(HIGH-1)</b> — "페이지만큼 가져온 뒤 메모리에서 거르기" 는 반환 건수와
 *       {@code totalElements} 를 동시에 깨뜨린다(다음 페이지 누락·중복). 조건은 목록과 count 양쪽에
 *       동일하게 적용된다.</li>
 *   <li><b>tie-break 강제(HIGH-4)</b> — {@code LS_RAW_DATA_STATUS} 는 배치가 초 단위로 여러 건을
 *       일괄 갱신해 <b>동일 {@code UPD_DT} 가 흔하다</b>. DB 는 동순위 행의 순서를 보장하지 않으므로
 *       PK({@code RAW_DATA_ID})를 마지막 정렬 항목으로 강제해 전순서를 만든다.</li>
 *   <li><b>안전 바인딩(CWE-89)</b> — 검색어는 {@code \ % _} 이스케이프 후 {@code like(..., '\')} 로
 *       바인딩하고, 정렬 키는 명시 화이트리스트({@code switch})로만 해석한다. 문자열 연결로 쿼리를
 *       만드는 지점이 없다.</li>
 * </ul>
 *
 * <p><b>인덱스 커버리지 (V124 주석의 단정 정정 — 후속 실측 과제)</b>: 복합 인덱스
 * {@code IX_LS_RAW_DATA_STATUS_STTS_UPD (DATA_STTS_CD, UPD_DT DESC)} 가
 * "{@code ORDER BY UPD_DT DESC} 까지 인덱스 순서로 함께 해소한다"는 서술은
 * <b>단일 {@code status} 필터일 때만</b> 성립한다.
 * <ul>
 *   <li>화면 기본 진입({@code status} 미지정 = {@code DATA_STTS_CD IN (4종)})은 선행 컬럼 값별로
 *       정렬 구간이 나뉘어 그룹 간 병합이 필요하므로 별도 Sort 노드가 붙을 가능성이 높다.</li>
 *   <li>tie-break 컬럼({@code RAW_DATA_ID})은 그 인덱스에 없어 2차 정렬은 어떤 경우에도 인덱스만으로
 *       해소되지 않는다.</li>
 * </ul>
 * 실행계획 실측({@code EXPLAIN ANALYZE}, 운영 규모)과 그에 따른 인덱스 조정은 후속 과제다.
 * V124 파일 자체는 이미 적용된 마이그레이션이라 <b>주석만 고쳐도 Flyway 체크섬이 바뀌어</b> 기존
 * DB 기동이 검증 실패로 막히므로 손대지 않고, 정정 내용을 쿼리 소유자인 이 클래스에 남긴다.
 */
@Repository
public class ReviewQueryRepository {

    private static final char ESCAPE_CHAR = '\\';

    /**
     * 정렬 항목 개수 상한(CWE-770) — {@link SortAllowlist#REVIEW} 에서 <b>파생</b>한다.
     *
     * <p>상수를 하드코딩하면 allowlist 확장 시 수동 동기화가 필요해 두 정의가 조용히 어긋난다
     * (컨트롤러는 4건을 허용하는데 리포지토리가 3건에서 400 을 던지는 식).
     */
    private static final int MAX_SORT_ORDERS = SortAllowlist.maxOrders(SortAllowlist.REVIEW);

    @PersistenceContext(unitName = "control")
    private EntityManager entityManager;

    /**
     * 조건 + 페이징으로 검수 대상 상태 row 를 조회한다.
     *
     * @param condition 검색 조건 (null 이면 기본 조건)
     * @param pageable  페이징/정렬 (정렬 키는 호출 측이 allowlist 로 검증·매핑한 엔티티 필드명)
     */
    public Page<LsRawDataStatus> search(ReviewSearchCondition condition, Pageable pageable) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);
        QLsRawDataStatus status = QLsRawDataStatus.lsRawDataStatus;

        BooleanBuilder where = buildWhere(status, effective(condition), true);

        List<LsRawDataStatus> content = queryFactory
                .selectFrom(status)
                .where(where)
                .orderBy(orderSpecifiers(status, pageable.getSort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        JPAQuery<Long> countQuery = queryFactory
                .select(status.count())
                .from(status)
                .where(where);

        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }

    /**
     * 검수 상태 4종별 건수 집계 — KPI 카드({@code GET /v1/reviews/summary})용.
     *
     * <p><b>필터는 목록과 동일하되 {@code status} 만 제외</b>한다 — KPI 카드 자체가 status 선택지이므로,
     * 이미 status 로 좁혀진 집합 위에서 4종을 세면 항상 1개 카드만 non-zero 가 된다(작업목록 summary 가
     * {@code workStatus} 만 제외하는 것과 대칭). 조건 조립은 {@link #buildWhere} 를 재사용하므로 검색어
     * LIKE 이스케이프·최신 배정 판정이 목록과 갈라지지 않는다.
     *
     * <p><b>4회 count 가 아니라 쿼리 1회</b>다. 버킷 판정식은 {@link ReviewRepository#REVIEW_STATUS_WHITELIST}
     * 를 순회해 만들므로 필터(WHERE)와 집계(CASE)가 같은 상수 하나를 공유한다(HIGH-5 — 하드코딩 CASE 는
     * 두 번째 정의가 되어 드리프트를 부른다).
     *
     * <p>{@code COUNT(*)} 와 버킷 합을 대조해 <b>누락뿐 아니라 중복 분류(겹침)까지</b> fail-closed 로
     * 검출한다({@code GROUP BY} 방식으로는 겹침이 보이지 않는다).
     *
     * @return 화이트리스트 4종이 모두 채워진 맵 (매칭 0건인 상태는 0)
     */
    public Map<String, Long> countByStatus(ReviewSearchCondition condition) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);
        QLsRawDataStatus status = QLsRawDataStatus.lsRawDataStatus;

        BooleanBuilder where = buildWhere(status, effective(condition), false);

        NumberExpression<Long> totalCount = status.count();
        Map<String, NumberExpression<Long>> bucketExpressions = new LinkedHashMap<>();
        List<Expression<?>> selects = new ArrayList<>();
        selects.add(totalCount);
        for (String code : ReviewRepository.REVIEW_STATUS_WHITELIST) {
            NumberExpression<Long> bucket = bucketCount(status, code);
            bucketExpressions.put(code, bucket);
            selects.add(bucket);
        }

        Tuple row = queryFactory
                .select(selects.toArray(new Expression<?>[0]))
                .from(status)
                .where(where)
                .fetchOne();

        // 4종을 먼저 0 으로 채운다 — 결과가 0건이면 PostgreSQL 의 SUM 은 NULL 이므로
        // 여기서 명시적으로 메우지 않으면 필드가 비거나 언박싱 NPE 가 난다(HIGH-6).
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String code : ReviewRepository.REVIEW_STATUS_WHITELIST) {
            counts.put(code, 0L);
        }
        if (row == null) {
            return counts;
        }
        long bucketSum = 0L;
        for (Map.Entry<String, NumberExpression<Long>> entry : bucketExpressions.entrySet()) {
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

    /** 해당 상태에 속하는 행 수 — {@code SUM(CASE WHEN DATA_STTS_CD = :code THEN 1 ELSE 0 END)}. */
    private NumberExpression<Long> bucketCount(QLsRawDataStatus status, String code) {
        return new CaseBuilder()
                .when(status.dataSttsCd.eq(code))
                .then(1L)
                .otherwise(0L)
                .sum();
    }

    private static long nullToZero(Long value) {
        return value != null ? value : 0L;
    }

    /**
     * 버킷 합 ≠ 전체 건수 = 화이트리스트(WHERE)와 버킷(CASE) 정의가 어긋났다는 뜻이다. 조용히 넘기면
     * KPI 카드 합과 총건수가 갈라지고, 최악의 경우 화이트리스트 밖 상태가 집계에 섞인다.
     *
     * <p>내부 정합성 위반이며 사용자 입력으로 도달할 수 없다(메시지에 입력값을 담지 않는다, CWE-209).
     */
    private static IllegalStateException bucketCoverageViolation(long total, long bucketSum) {
        return new IllegalStateException(
                "검수목록 KPI 집계의 상태 버킷이 전체 건수를 덮지 못합니다 — "
                        + "REVIEW_STATUS_WHITELIST 정의를 확인하세요. total=" + total + ", bucketSum=" + bucketSum);
    }

    private static ReviewSearchCondition effective(ReviewSearchCondition condition) {
        return condition != null ? condition : ReviewSearchCondition.defaults();
    }

    // ------------------------------------------------------------------ where

    /**
     * 조건 조립 <b>단일 지점</b> — 목록/count/집계가 모두 이 메서드를 통과한다.
     *
     * @param includeStatus {@code status} 필터 적용 여부. KPI 집계는 4종을 모두 세야 하므로 이 조건만
     *                      빼고 나머지({@code q})는 목록과 완전히 동일하게 적용한다.
     */
    private BooleanBuilder buildWhere(QLsRawDataStatus status, ReviewSearchCondition condition,
                                      boolean includeStatus) {
        BooleanBuilder where = new BooleanBuilder();

        // 보안 경계 — 어떤 조합에서도 먼저 걸린다(HIGH-2).
        where.and(status.dataSttsCd.in(ReviewRepository.REVIEW_STATUS_WHITELIST));

        if (includeStatus) {
            String statusFilter = condition.statusFilter();
            if (statusFilter != null) {
                // 화이트리스트와의 교집합 — 밖의 값(예: PROCESSING)을 지정하면 빈 결과가 된다(기존 계약).
                where.and(status.dataSttsCd.eq(statusFilter));
            }
        }
        if (condition.q() != null) {
            // Locale.ROOT 고정 — 기본 로케일(예: tr_TR)에서 'I' → 'ı' 로 변환돼 DB lower() 결과와
            // 어긋나면 매칭이 조용히 실패한다(온프렘 배포 로케일은 환경변수에 좌우된다).
            String pattern = "%" + escapeLike(condition.q().toLowerCase(Locale.ROOT)) + "%";
            where.and(videoNameLike(status, pattern).or(workerNameLike(status, pattern)));
        }
        return where;
    }

    /**
     * 영상명 부분일치 — 화면 표시명(관제 인입 {@code LS_DATA_INGEST.CCTV_NM}, 없으면
     * {@code VMS_CCTV_ID} 폴백) 기준으로 검색한다({@code ReviewResponse} 의 cctvName 결정 규칙과 동일).
     * CCTV 명이 있는 영상은 화면에 보이지 않는 {@code VMS_CCTV_ID} 로 매칭되지 않는다.
     *
     * <p>구 소스({@code MNG_RESOURCE_CCTV})는 V167 로 제거됐다. 영상↔인입 연결 규칙(파생영상
     * {@code ORGNL_RAW_SN} 1단계 폴백)은 {@link IngestSourceLink#matchesSourceOf} 단일 진실원이다.
     *
     * <p>"CCTV 명이 비었는가"는 표시측({@code ReviewService.lookupCctvNames} 의 {@code isBlank()})과
     * <b>같은 의미</b>여야 한다 — 어긋나면 탭/개행만 있는 CCTV 명처럼 "화면에 보이는 값으로 검색해도
     * 안 나오는" 영상이 생긴다({@link #blankAsJava} 참조).
     *
     * <p><b>채널 술어({@code InternalWorkScope.internal})를 붙이지 않는다 — 의도된 것이다</b>(ADR-058).
     * 이 상관 서브쿼리의 바깥 조회는 검수 상태 표({@code LS_RAW_DATA_STATUS})에서 출발하는데, 그 행은
     * 배정 시점에 생기고 포털에는 배정·검수가 없어 <b>영영 생기지 않는다</b> — 포털 자산은 구조적으로
     * 도달하지 못한다. 이미 걸러지는 자리에 술어를 더하면 저 조인이 하는 일이 가려진다.
     * 「일관성」을 이유로 붙이지 말 것.
     */
    private BooleanExpression videoNameLike(QLsRawDataStatus status, String pattern) {
        QLsDataRaw raw = QLsDataRaw.lsDataRaw;
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
                        blankAsJava(ingest.cctvNm).not())
                .exists()
                .not();

        return JPAExpressions.selectOne()
                .from(raw)
                .where(raw.rawSn.eq(status.rawDataId),
                        cctvNameMatches.or(displayNameIsCctvId
                                .and(raw.vmsCctvId.lower().like(pattern, ESCAPE_CHAR))))
                .exists();
    }

    /** 작업자명 부분일치 — 최신 LABELER 배정 작업자의 이름({@code LS_ACNT_USER.USER_NM}) 기준. */
    private BooleanExpression workerNameLike(QLsRawDataStatus status, String pattern) {
        QLsAcntUser user = QLsAcntUser.lsAcntUser;
        return latestLabelerMatches(status, assignment -> JPAExpressions.selectOne()
                .from(user)
                .where(user.userNo.eq(assignment.userNo), user.userNm.lower().like(pattern, ESCAPE_CHAR))
                .exists());
    }

    /**
     * <b>최신</b> LABELER 배정 1건이 주어진 조건을 만족하는지 — 작업자명 검색용.
     *
     * <p>화면에 표시되는 작업자는 최신 배정 1건({@code REG_DT DESC, ASSIGNMENT_ID DESC})이므로 검색도
     * 그 1건만 본다(과거 배정 이력에 걸려 "검색한 작업자와 다른 작업자가 표시되는" 불일치 방지 —
     * 표시 측 tie-break 는 {@code ReviewService.lookupLabelerByVideo} 가 같은 기준으로 맞춘다).
     * "더 최신 배정이 존재하지 않음"을 NOT EXISTS 로 표현해 정확히 1건을 지목하며, 전체가 EXISTS 라
     * 행 증식이 없다.
     */
    private BooleanExpression latestLabelerMatches(
            QLsRawDataStatus status, Function<QLsTaskAssignment, BooleanExpression> userCondition) {
        QLsTaskAssignment assignment = QLsTaskAssignment.lsTaskAssignment;
        QLsTaskAssignment newer = new QLsTaskAssignment("newerAssignment");

        BooleanExpression newerExists = JPAExpressions.selectOne()
                .from(newer)
                .where(newer.rawDataId.eq(status.rawDataId),
                        newer.taskTypeCd.eq(LsTaskAssignment.TASK_LABELER),
                        newer.regDt.gt(assignment.regDt)
                                .or(newer.regDt.eq(assignment.regDt)
                                        .and(newer.assignmentId.gt(assignment.assignmentId))))
                .exists();

        return JPAExpressions.selectOne()
                .from(assignment)
                .where(assignment.rawDataId.eq(status.rawDataId),
                        assignment.taskTypeCd.eq(LsTaskAssignment.TASK_LABELER),
                        userCondition.apply(assignment),
                        newerExists.not())
                .exists();
    }

    /**
     * Java {@code String.isBlank()} 와 <b>동일한 판정</b>을 SQL 로 표현한다 — 판정 구현은 단일 원천
     * {@link BlankTextPredicate} 에 위임한다(작업목록 {@code TaskBoardQueryRepository} 도 같은 것을 쓴다).
     */
    private static BooleanExpression blankAsJava(StringExpression text) {
        return BlankTextPredicate.isBlankAsJava(text);
    }

    /** LIKE 특수문자({@code \ % _}) 이스케이프 — 와일드카드 주입으로 필터가 무력화되는 것을 막는다. */
    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    // ---------------------------------------------------------------- order by

    /**
     * 정렬 조립 — 허용 필드만 해석하고 <b>전순서(total order)를 보장</b>해 페이지 경계에서 행 중복/누락이
     * 생기지 않게 한다(HIGH-4). {@code RAW_DATA_ID} 는 유니크 PK 이므로,
     * <ul>
     *   <li>요청 정렬에 {@code rawDataId} 가 <b>없으면</b> 마지막에 {@code RAW_DATA_ID DESC} 를 append 하고
     *       (작업목록의 {@code RAW_SN DESC} 와 방향을 맞춘다),</li>
     *   <li>요청 정렬에 {@code rawDataId} 가 <b>있으면</b> 그 지점에서 순서가 확정되므로 append 하지 않는다.</li>
     * </ul>
     *
     * <p>정렬 미지정이면 {@code UPD_DT DESC} — 구 JPQL 의 정적 {@code ORDER BY} 와 동일한 기본 정렬이라
     * 기존 호출의 결과 순서가 변하지 않는다(R8 / HIGH-3).
     *
     * <p>정렬 키·개수는 컨트롤러의 {@code SortAllowlist} 가 이미 검증했지만, 리포지토리에서도 미허용 키와
     * 개수 초과를 400 으로 fail-closed 처리하고 동일 필드 중복을 제거해, 어떤 호출 경로에서도 임의
     * 프로퍼티나 무제한 {@code ORDER BY} 가 쿼리에 닿지 않게 한다.
     */
    private OrderSpecifier<?>[] orderSpecifiers(QLsRawDataStatus status, Sort sort) {
        List<OrderSpecifier<?>> orders = new ArrayList<>();
        Set<String> appliedFields = new LinkedHashSet<>();

        if (sort != null && sort.isSorted()) {
            int seen = 0;
            for (Sort.Order order : sort) {
                if (++seen > MAX_SORT_ORDERS) {
                    throw new CustomException(ErrorCode.INVALID_INPUT, "정렬 기준이 너무 많습니다.");
                }
                ComparableExpressionBase<?> path = switch (order.getProperty()) {
                    case "updDt" -> status.updDt;
                    case "rawDataId" -> status.rawDataId;
                    case "dataSttsCd" -> status.dataSttsCd;
                    default -> throw new CustomException(ErrorCode.INVALID_INPUT, "지원하지 않는 정렬 기준입니다.");
                };
                if (appliedFields.add(order.getProperty())) {
                    orders.add(order.isAscending() ? path.asc() : path.desc());
                }
            }
        }
        if (orders.isEmpty()) {
            orders.add(status.updDt.desc());
        }
        if (!appliedFields.contains("rawDataId")) {
            orders.add(status.rawDataId.desc());
        }
        return orders.toArray(new OrderSpecifier<?>[0]);
    }
}
