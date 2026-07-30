package kr.co.cudo.authoring.common.util;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 정렬 키 화이트리스트 — 외부 노출 정렬 키를 엔티티 필드로 <b>명시 매핑</b>해, 미등록 키가 절대
 * 쿼리에 닿지 않게 한다 (CWE-20 입력 검증 / CWE-89 정렬 인젝션).
 *
 * <p>{@link org.springframework.data.domain.Sort} 는 임의 프로퍼티 문자열을 그대로 담기 때문에,
 * 검증 없이 Spring Data/QueryDSL 로 넘기면
 * <ul>
 *   <li>{@code PropertyReferenceException} → 500 + 내부 필드명 노출(CWE-209), 또는</li>
 *   <li>FE 에 노출하지 않는 내부 컬럼(원본 파일 경로 등)으로 정렬해 값의 순서로부터 내용을 추론</li>
 * </ul>
 * 하는 문제가 생긴다. allowlist 에는 <b>FE 가 실제로 보여주는 컬럼</b>만 등록한다.
 *
 * <h2>두 가지 모드 — 엔드포인트의 <b>변경 전 동작</b>에 맞춰 고른다</h2>
 * <table border="1">
 *   <caption>모드별 미등록 키 처리</caption>
 *   <tr><th>모드</th><th>미등록 키 / 개수 초과</th><th>사용처</th><th>근거</th></tr>
 *   <tr>
 *     <td>{@link #resolve(Sort, Map, Sort)} (strict)</td>
 *     <td>{@link ErrorCode#INVALID_INPUT}(400) 으로 거부</td>
 *     <td>작업목록 {@code GET /v1/tasks/board}(+{@code /summary}, {@code /event-types})</td>
 *     <td>이 엔드포인트는 변경 전에도 {@code Pageable} 을 받아 잘못된 키면 Spring Data 가
 *         {@code PropertyReferenceException}(500) 을 던졌다 — 원래 200 이 아니었으므로
 *         400 은 하위호환 파손이 아니라 개선이다.</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #resolveLenient(Sort, Map, Sort)} (lenient)</td>
 *     <td>무시하고 {@code fallback} 기본 정렬로 폴백 (200 유지) + WARN 로그</td>
 *     <td>검수목록 {@code GET /v1/reviews}</td>
 *     <td>변경 전 이 엔드포인트는 {@code sort} 를 <b>받지도 않고 버렸다</b> — 즉 어떤 값이 붙어도
 *         항상 200 이었다. 400 으로 바꾸면 FE 가 URL 에 보존·재전송하는 다른 화면의 정렬 키
 *         (북마크·뒤로가기)를 달고 진입했을 때 목록 전체가 죽는다(R8/AC-8 하위호환).</td>
 *   </tr>
 * </table>
 *
 * <p><b>모드가 갈려도 보안 목적은 동일</b>하다 — 두 모드 모두 <b>allowlist 매핑으로만</b> 정렬을
 * 해석하므로 미등록 키가 쿼리에 반영되는 경로는 어느 쪽에도 없다. 갈리는 것은 "거부할 것인가,
 * 무시할 것인가" 뿐이다.
 *
 * <p>{@link kr.co.cudo.authoring.common.web.SortFieldMapper}(영상 처리 현황 목록
 * {@code GET /v1/videos} 전용 래퍼)도 <b>이 유틸의 lenient 모드에 위임</b>한다 — 미등록 키 drop 정책이
 * 같고, 개수 상한·중복 제거를 두 곳에서 각자 구현하면 정책이 갈라지기 때문이다.
 *
 * <p>stateless 유틸 — 인스턴스화 금지.
 */
public final class SortAllowlist {

    private static final Logger log = LoggerFactory.getLogger(SortAllowlist.class);

    /** 로그에 남길 정렬 키 길이 상한 — 과대 입력으로 로그가 폭주하지 않게 자른다. */
    private static final int LOG_KEY_MAX_LENGTH = 64;

    /**
     * 작업목록(GET /v1/tasks/board) 정렬 allowlist — 외부 키 → {@code LsDataRaw} 필드.
     * 값 집합은 {@code regDt}(등록일) / {@code shtDt}(촬영일시) / {@code rawSn}(영상 ID) 세 컬럼뿐이며
     * 모두 FE 목록에 노출되는 값이다.
     */
    public static final Map<String, String> TASK_BOARD = Map.of(
            "regDt", "regDt",
            "capturedAt", "shtDt",
            "shtDt", "shtDt",
            "rawSn", "rawSn",
            "videoId", "rawSn");

    /**
     * 배정 목록(GET /v1/assignments) 정렬 allowlist — 외부 키 → {@code LsTaskAssignment} 필드.
     *
     * <p>값 집합은 {@code regDt}(배정일) / {@code rawDataId}(영상 ID) / {@code assignmentId}(배정 ID)
     * 세 컬럼뿐이며 모두 응답 필드로 노출된다. {@code assignedAt}·{@code videoId}·{@code id} 는
     * 응답 DTO({@code AssignmentResponse.Item})가 그대로 alias 한 이름이라 함께 등록한다 — FE 는
     * 화면에 보이는 이름으로 정렬을 요청할 수 있어야 한다.
     *
     * <p><b>{@code regDt} 는 반드시 포함</b>한다 — 컨트롤러 {@code @PageableDefault} 의 기본 정렬이라
     * 빠지면 <b>파라미터 없는 기존 호출이 전부 400</b> 이 된다.
     *
     * <p>이 allowlist 는 <b>{@link #resolve(Sort, Map, Sort)}(strict 모드)와 함께</b> 쓴다 — 이
     * 엔드포인트는 변경 전에도 {@code Pageable} 을 받아 잘못된 키면 Spring Data 가 500 을 던졌으므로
     * 400 은 하위호환 파손이 아니라 개선이다(위 모드 표 — 작업목록과 같은 근거, 검수목록의 lenient
     * 정책과 통일하지 않는다).
     */
    public static final Map<String, String> ASSIGNMENT = Map.of(
            "regDt", "regDt",
            "assignedAt", "regDt",
            "rawDataId", "rawDataId",
            "videoId", "rawDataId",
            "assignmentId", "assignmentId",
            "id", "assignmentId");

    /**
     * 검수목록(GET /v1/reviews) 정렬 allowlist — 외부 키 → {@code LsRawDataStatus} 필드.
     *
     * <p>값 집합은 {@code updDt}(제출일=최종 갱신일) / {@code rawDataId}(영상 ID) /
     * {@code dataSttsCd}(검수 상태) 세 컬럼뿐이며 모두 FE 목록에 노출된다. {@code submittedAt} 은
     * 응답 DTO({@code ReviewResponse#submittedAt})가 {@code updDt} 를 그대로 alias 한 것이므로
     * 같은 필드로 매핑한다 — FE 는 화면에 보이는 이름으로 정렬을 요청할 수 있어야 한다.
     *
     * <p>이 allowlist 는 <b>{@link #resolveLenient(Sort, Map, Sort)}(관용 모드)와 함께</b> 쓴다 —
     * 검수목록은 변경 전 {@code sort} 를 받지도 않아 어떤 값이든 200 이었기 때문이다(위 모드 표).
     */
    public static final Map<String, String> REVIEW = Map.of(
            "submittedAt", "updDt",
            "updDt", "updDt",
            "videoId", "rawDataId",
            "status", "dataSttsCd");

    /**
     * 영상 처리 현황 목록(GET /v1/videos) 정렬 allowlist — 외부 키 → {@code LsDataRaw} 필드.
     *
     * <p>7 개 외부 키가 고유 엔티티 필드 4개({@code shtDt}/{@code regDt}/{@code mdfcnDt}/{@code rawSn})를
     * 가리킨다 — {@code capturedAt}·{@code createdAt}·{@code id} 는 FE 표시명 alias 다.
     *
     * <p>이 allowlist 는 {@code SortFieldMapper}(=이 유틸의 {@link #resolveLenient} 위임)와 함께 쓴다 —
     * 영상 목록은 변경 전에도 미등록 키·과다 항목에 200 을 돌려줬기 때문이다(위 모드 표).
     *
     * <p><b>컨트롤러에 사본을 두지 않는다</b>: 사본을 두면 테스트가 사본만 검증해 컨트롤러 확장 시
     * 드리프트를 놓친다(실제로 {@code SortFieldMapperTest} 가 "동형" 사본을 검증하고 있었다).
     */
    public static final Map<String, String> VIDEO = Map.of(
            "capturedAt", "shtDt",
            "shtDt", "shtDt",
            "regDt", "regDt",
            "createdAt", "regDt",
            "updatedAt", "mdfcnDt",
            "rawSn", "rawSn",
            "id", "rawSn");

    private SortAllowlist() {
    }

    /**
     * 정렬 키를 allowlist 로 변환한다.
     *
     * <p><b>개수 상한 + 중복 제거(CWE-770 / OWASP API4)</b>: Spring 의 정렬 파라미터 resolver 는 반복
     * {@code sort} 파라미터를 <b>전부</b> 수집하므로, 키 종류만 검증하면 {@code {필드} × {방향}} 순열을
     * 요청 헤더 한도까지 쌓아 매 요청마다 서로 다른 {@code ORDER BY}(=서로 다른 HQL)를 만들 수 있다.
     * Hibernate 쿼리 플랜 캐시가 오염되고 DB 플래너 CPU 도 동반 상승하므로,
     * <ul>
     *   <li>정렬 항목 수를 allowlist 의 <b>고유 엔티티 필드 수</b>로 제한하고(초과 시 400),</li>
     *   <li>같은 엔티티 필드가 여러 번 오면 <b>첫 지정만</b> 살린다(뒤 항목은 어차피 정렬에 영향이 없다).</li>
     * </ul>
     *
     * @param raw       요청 정렬 (null/미지정 가능)
     * @param allowlist 외부 정렬 키 → 엔티티 필드명
     * @param fallback  정렬 미지정 시 적용할 기본 정렬
     * @return 매핑된 정렬 (정렬 미지정이면 {@code fallback})
     * @throws CustomException allowlist 에 없는 정렬 키가 포함되거나 항목 수가 상한을 넘은 경우 (400)
     */
    public static Sort resolve(Sort raw, Map<String, String> allowlist, Sort fallback) {
        if (raw == null || raw.isUnsorted()) {
            return fallback;
        }
        int limit = maxOrders(allowlist);
        List<Sort.Order> orders = new ArrayList<>();
        Set<String> appliedFields = new LinkedHashSet<>();
        int seen = 0;
        for (Sort.Order order : raw) {
            if (++seen > limit) {
                // CWE-209 — 입력 개수/상한을 메시지에 담지 않는다(내부 정책 추론 차단).
                throw new CustomException(ErrorCode.INVALID_INPUT, "정렬 기준이 너무 많습니다.");
            }
            String entityField = allowlist.get(order.getProperty());
            if (entityField == null) {
                // CWE-209 — 입력값/내부 필드명을 응답 메시지에 담지 않는다(반사 출력·구조 추론 차단).
                throw new CustomException(ErrorCode.INVALID_INPUT, "지원하지 않는 정렬 기준입니다.");
            }
            if (appliedFields.add(entityField)) {
                orders.add(new Sort.Order(order.getDirection(), entityField));
            }
        }
        return orders.isEmpty() ? fallback : Sort.by(orders);
    }

    /**
     * 정렬 키를 allowlist 로 변환하되, <b>해석할 수 없는 요청은 거부하지 않고 기본 정렬로 폴백</b>한다
     * (관용 모드 — 위 클래스 주석의 모드 표 참조).
     *
     * <p><b>변경 전에도 200 이던 엔드포인트 전용</b>이다. 정렬을 아예 읽지 않던 API 에 정렬 지원을
     * 추가하면서 미등록 키를 400 으로 만들면, FE 가 URL 쿼리스트링에 보존·재전송하는 다른 화면의
     * 정렬 키(북마크·뒤로가기)를 달고 진입했을 때 목록 전체가 죽는다(R8/AC-8 하위호환 파손).
     *
     * <p>동작:
     * <ul>
     *   <li>정렬 미지정 → {@code fallback}</li>
     *   <li>정렬 항목 수가 상한 초과 → <b>전체</b>를 {@code fallback} 으로 폴백(부분 적용하지 않는다 —
     *       "요청의 앞 N 개만 적용" 은 사용자가 예측할 수 없는 순서를 만든다). 쿼리 플랜 오염
     *       (CWE-770)은 폴백으로 동일하게 차단된다.</li>
     *   <li>미등록 키 → 그 항목만 무시하고 나머지 허용 키로 정렬. 남는 항목이 없으면 {@code fallback}</li>
     * </ul>
     *
     * <p>폴백 사실은 WARN 으로 남겨 오타 진단이 가능하게 하되, 로그에 들어가는 사용자 입력은
     * {@link LogSanitizer} 로 정제한다(CWE-117 Log Injection).
     *
     * @param raw       요청 정렬 (null/미지정 가능)
     * @param allowlist 외부 정렬 키 → 엔티티 필드명
     * @param fallback  해석 결과가 비었을 때 적용할 기본 정렬
     * @return 매핑된 정렬 (해석 불가/미지정이면 {@code fallback})
     */
    public static Sort resolveLenient(Sort raw, Map<String, String> allowlist, Sort fallback) {
        if (raw == null || raw.isUnsorted()) {
            return fallback;
        }
        int limit = maxOrders(allowlist);
        List<Sort.Order> orders = new ArrayList<>();
        Set<String> appliedFields = new LinkedHashSet<>();
        int seen = 0;
        for (Sort.Order order : raw) {
            if (++seen > limit) {
                // 입력 키를 진단용으로 남기되 LogSanitizer 로 정제한다(CWE-117 Log Injection).
                log.warn("[Sort] too many sort orders — fell back to default sort firstDroppedKey={}",
                        LogSanitizer.sanitize(order.getProperty(), LOG_KEY_MAX_LENGTH));
                return fallback;
            }
            String entityField = allowlist.get(order.getProperty());
            if (entityField == null) {
                log.warn("[Sort] unsupported sort key ignored key={}",
                        LogSanitizer.sanitize(order.getProperty(), LOG_KEY_MAX_LENGTH));
                continue;
            }
            if (appliedFields.add(entityField)) {
                orders.add(new Sort.Order(order.getDirection(), entityField));
            }
        }
        return orders.isEmpty() ? fallback : Sort.by(orders);
    }

    /**
     * 정렬 항목 개수 상한 — allowlist 가 가리키는 고유 엔티티 필드 수(그 이상은 의미가 없다).
     *
     * <p>리포지토리가 자체 상한을 하드코딩하면 allowlist 확장 시 수동 동기화가 필요해 드리프트가
     * 생기므로, 상한은 이 메서드에서만 파생시킨다.
     */
    public static int maxOrders(Map<String, String> allowlist) {
        return (int) allowlist.values().stream().distinct().count();
    }

    /** {@link #resolve(Sort, Map, Sort)} 결과로 Pageable 의 Sort 만 교체한다 (page/size 는 보존). */
    public static Pageable apply(Pageable pageable, Map<String, String> allowlist, Sort fallback) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                resolve(pageable.getSort(), allowlist, fallback));
    }
}
