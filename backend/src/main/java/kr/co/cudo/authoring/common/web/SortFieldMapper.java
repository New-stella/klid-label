package kr.co.cudo.authoring.common.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;
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
 * <p>stateless 유틸 — 인스턴스화 금지.
 */
public final class SortFieldMapper {

    private SortFieldMapper() {
    }

    /**
     * Pageable 의 Sort 를 allowlist 기준으로 엔티티 필드로 변환한다.
     *
     * @param pageable     원본 Pageable (page/size 는 보존)
     * @param allowlist    외부 정렬 키 → 엔티티 필드명 매핑 (이 Map 에 없는 키는 무시)
     * @param defaultSort  변환 결과 정렬 항목이 하나도 없을 때 적용할 기본 정렬
     * @return page/size 는 동일하고 Sort 만 안전하게 매핑/폴백된 Pageable
     */
    public static Pageable apply(Pageable pageable, Map<String, String> allowlist, Sort defaultSort) {
        Sort mapped = mapSort(pageable.getSort(), allowlist);
        Sort effective = mapped.isSorted() ? mapped : defaultSort;
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), effective);
    }

    /**
     * 정렬 키 집합을 allowlist 로 변환한다. allowlist 에 없는 키는 결과에서 제외된다.
     * 유효 항목이 없으면 {@link Sort#unsorted()} 를 반환한다.
     */
    public static Sort mapSort(Sort source, Map<String, String> allowlist) {
        if (source == null || source.isUnsorted()) {
            return Sort.unsorted();
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (Sort.Order order : source) {
            String entityField = allowlist.get(order.getProperty());
            if (entityField != null) {
                orders.add(new Sort.Order(order.getDirection(), entityField));
            }
            // allowlist 밖 키는 무시 (drop) — PropertyReferenceException 회피
        }
        return orders.isEmpty() ? Sort.unsorted() : Sort.by(orders);
    }
}
