package kr.co.cudo.authoring.common.web;

import kr.co.cudo.authoring.common.util.SortAllowlist;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Map;

/**
 * 외부 노출 정렬 키 → 엔티티 필드 매핑 유틸 (CWE-20 입력 검증).
 *
 * <p>FE 가 보내는 정렬 키(예: {@code capturedAt})는 엔티티 실제 필드명({@code shtDt})과 다를 수 있다.
 * {@link Pageable#getSort()} 가 임의 프로퍼티를 그대로 담아 Spring Data 로 직행하면
 * {@code PropertyReferenceException} 이 발생해 500 으로 노출된다 (정보 누출/안정성 저하).
 *
 * <p>본 유틸은 <b>명시적 allowlist(Map)</b> 로만 정렬 키를 엔티티 필드로 변환하며,
 * allowlist 밖의 키는 조용히 제거(drop)한다. 변환 결과 유효한 정렬 항목이 하나도 없으면
 * 호출 측이 지정한 기본 정렬로 폴백한다 — 어떤 경우에도 PropertyReferenceException → 500 이
 * 발생하지 않는다 (fail-secure).
 *
 * <h2>정렬 항목 <b>개수</b> 상한 (CWE-770 / OWASP API4)</h2>
 * <p>allowlist 는 키 <b>종류</b>만 검증하므로, 종류만 맞으면 개수는 무제한이었다. Spring 의
 * {@code SortHandlerMethodArgumentResolver} 는 반복 {@code sort} 파라미터를 <b>전부</b> 수집하므로
 * {@code ?sort=capturedAt,desc&sort=regDt,asc&…} 를 요청 한도까지 쌓으면 매 요청이 서로 다른
 * {@code ORDER BY}(=서로 다른 HQL)가 되어 Hibernate 쿼리 플랜 캐시가 대형 플랜으로 오염되고
 * PG 플래너 CPU 도 동반 상승한다(인증 사용자 1명이 노드를 흔들 수 있다).
 *
 * <p>따라서 판정·상한 정책은 {@link SortAllowlist#resolveLenient(Sort, Map, Sort)} <b>하나에 위임</b>한다
 * (개수 상한 초과 → 기본 정렬 폴백 + WARN, 동일 엔티티 필드 중복 → 첫 지정만, 미등록 키 → 무시).
 * 같은 판정을 두 유틸이 각자 구현하면 상한·중복 정책이 갈라져 드리프트가 생기기 때문이다.
 *
 * <p><b>관용(lenient) 모드를 쓰는 이유</b>: 이 유틸의 유일한 사용처인 영상 처리 현황 목록
 * ({@code GET /v1/videos})은 지금까지 미등록 키·과다 항목에도 200 을 돌려줬다. 400 으로 바꾸면
 * FE 가 URL 에 보존·재전송하는 정렬 키(북마크·뒤로가기)를 달고 진입했을 때 목록 전체가 죽는다
 * (하위호환 파손). 그래서 <b>거부하지 않고 기본 정렬로 폴백</b>한다 — 쿼리 플랜 오염 차단 효과는
 * 400 과 동일하다.
 *
 * <p>stateless 유틸 — 인스턴스화 금지.
 */
public final class SortFieldMapper {

    private SortFieldMapper() {
    }

    /**
     * Pageable 의 Sort 를 allowlist 기준으로 엔티티 필드로 변환한다.
     *
     * <p>정렬 항목 수가 상한(allowlist 고유 엔티티 필드 수)을 넘으면 <b>전체</b>를 {@code defaultSort}
     * 로 폴백한다 — 앞 N 개만 부분 적용하면 사용자가 예측할 수 없는 순서가 되므로 배제한다.
     *
     * @param pageable     원본 Pageable (page/size 는 보존)
     * @param allowlist    외부 정렬 키 → 엔티티 필드명 매핑 (이 Map 에 없는 키는 무시)
     * @param defaultSort  변환 결과 정렬 항목이 하나도 없거나 개수 상한을 넘을 때 적용할 기본 정렬
     * @return page/size 는 동일하고 Sort 만 안전하게 매핑/폴백된 Pageable
     */
    public static Pageable apply(Pageable pageable, Map<String, String> allowlist, Sort defaultSort) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                SortAllowlist.resolveLenient(pageable.getSort(), allowlist, defaultSort));
    }

    /**
     * 정렬 키 집합을 allowlist 로 변환한다. allowlist 에 없는 키는 결과에서 제외되고, 같은 엔티티
     * 필드를 가리키는 키가 여러 번 오면 첫 지정만 살린다. 유효 항목이 없거나 항목 수가 상한
     * ({@link SortAllowlist#maxOrders(Map)})을 넘으면 {@link Sort#unsorted()} 를 반환한다
     * (호출부의 기본 정렬로 폴백된다).
     */
    public static Sort mapSort(Sort source, Map<String, String> allowlist) {
        return SortAllowlist.resolveLenient(source, allowlist, Sort.unsorted());
    }
}
