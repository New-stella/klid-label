package kr.co.cudo.authoring.common.web;

import kr.co.cudo.authoring.common.util.SortAllowlist;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SortFieldMapper} — 정렬 키 allowlist 매핑 + <b>개수 상한</b>(CWE-770 / OWASP API4) 검증.
 *
 * <p>이 유틸의 사용처는 영상 처리 현황 목록({@code GET /v1/videos}) 한 곳이며 <b>관용(lenient)</b> 정책이다
 * — 미등록 키·과다 항목을 400 으로 거부하지 않고 기본 정렬로 폴백한다(하위호환). 본 테스트는 그 관용
 * 계약(=지금까지 200 이던 호출이 계속 200)과 새로 추가된 개수 상한을 함께 고정한다.
 */
class SortFieldMapperTest {

    /**
     * {@code VideoController.VIDEO_SORT_ALLOWLIST} 의 <b>실물</b>(사본 금지 — 사본을 검증하면 컨트롤러
     * allowlist 가 5번째 고유 필드로 늘어나도 상한 드리프트를 아무도 못 잡는다).
     */
    private static final Map<String, String> ALLOWLIST = SortAllowlist.VIDEO;

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "regDt");

    @Test
    @DisplayName("allowlist_등록키는_엔티티_필드로_매핑되고_방향이_보존된다")
    void mapsAllowedKeyToEntityField() {
        Sort mapped = SortFieldMapper.mapSort(Sort.by(Sort.Direction.DESC, "capturedAt"), ALLOWLIST);

        assertThat(mapped).containsExactly(new Sort.Order(Sort.Direction.DESC, "shtDt"));
    }

    @Test
    @DisplayName("미등록_정렬키는_기존대로_조용히_무시된다")
    void unknownKeyIsSilentlyIgnored() {
        // given: 미등록 키 1개 + 등록 키 1개
        Sort raw = Sort.by(Sort.Order.asc("notAField"), Sort.Order.desc("regDt"));

        // when
        Sort mapped = SortFieldMapper.mapSort(raw, ALLOWLIST);

        // then: 예외 없이 등록 키만 살아남는다(400 금지 — 하위호환 회귀 가드)
        assertThat(mapped).containsExactly(new Sort.Order(Sort.Direction.DESC, "regDt"));
    }

    @Test
    @DisplayName("미등록_정렬키만_보내면_기본정렬로_폴백된다")
    void allUnknownKeysFallBackToDefault() {
        Pageable applied = SortFieldMapper.apply(
                PageRequest.of(0, 20, Sort.by("notAField")), ALLOWLIST, DEFAULT_SORT);

        assertThat(applied.getSort()).isEqualTo(DEFAULT_SORT);
    }

    @Test
    @DisplayName("동일_엔티티필드_중복_정렬키는_한_번만_적용된다")
    void duplicateEntityFieldAppliedOnce() {
        // given: capturedAt 과 shtDt 는 같은 엔티티 필드(shtDt)를 가리킨다
        Sort raw = Sort.by(Sort.Order.desc("capturedAt"), Sort.Order.asc("shtDt"),
                Sort.Order.asc("id"), Sort.Order.desc("rawSn"));

        // when
        Sort mapped = SortFieldMapper.mapSort(raw, ALLOWLIST);

        // then: 첫 지정만 살아 shtDt DESC, rawSn ASC 두 항목
        assertThat(mapped).containsExactly(
                new Sort.Order(Sort.Direction.DESC, "shtDt"),
                new Sort.Order(Sort.Direction.ASC, "rawSn"));
    }

    @Test
    @DisplayName("정렬_기준_개수가_상한을_넘으면_기본정렬로_폴백된다")
    void tooManyOrdersFallBackToDefaultSort() {
        // given: 전부 allowlist 등록 키지만 개수가 상한(고유 엔티티 필드 4개)을 초과
        int limit = SortAllowlist.maxOrders(ALLOWLIST);
        List<Sort.Order> orders = new ArrayList<>();
        for (int i = 0; i <= limit; i++) {
            orders.add(i % 2 == 0 ? Sort.Order.asc("capturedAt") : Sort.Order.desc("regDt"));
        }

        // when
        Pageable applied = SortFieldMapper.apply(
                PageRequest.of(2, 20, Sort.by(orders)), ALLOWLIST, DEFAULT_SORT);

        // then: 앞 N 개 부분 적용이 아니라 전체를 기본 정렬로 폴백(예측 불가한 순서 방지)
        assertThat(applied.getSort()).isEqualTo(DEFAULT_SORT);
        assertThat(applied.getPageNumber()).isEqualTo(2);
        assertThat(applied.getPageSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("정렬_기준을_대량으로_보내도_ORDER_BY로_전개되지_않는다")
    void massiveSortOrdersNeverReachQuery() {
        // given: 요청 헤더 한도까지 쌓은 대량 정렬(쿼리 플랜 캐시 오염 시도 — CWE-770)
        List<Sort.Order> orders = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            orders.add(i % 2 == 0 ? Sort.Order.asc("regDt") : Sort.Order.desc("capturedAt"));
        }

        // when
        Sort mapped = SortFieldMapper.mapSort(Sort.by(orders), ALLOWLIST);

        // then: 한 항목도 통과하지 않는다(호출부 기본 정렬로 폴백)
        assertThat(mapped.isUnsorted()).isTrue();
    }

    @Test
    @DisplayName("상한은_allowlist_고유_엔티티필드_수에서_파생된다")
    void limitDerivedFromAllowlist() {
        // 상한을 유틸에 하드코딩하면 allowlist 확장 시 드리프트가 생긴다 — 파생 단일 원천 고정.
        // 실물 allowlist(SortAllowlist.VIDEO)의 현재 고유 필드 수를 기록한다 — 컨트롤러가 5번째 고유
        // 필드를 추가하면 이 단언이 RED 가 되어 상한 변화를 의식적으로 확인하게 된다.
        assertThat(SortAllowlist.maxOrders(ALLOWLIST)).isEqualTo(4);

        List<Sort.Order> atLimit = List.of(Sort.Order.desc("shtDt"), Sort.Order.asc("regDt"),
                Sort.Order.desc("updatedAt"), Sort.Order.asc("rawSn"));
        assertThat(SortFieldMapper.mapSort(Sort.by(atLimit), ALLOWLIST)).hasSize(4);
    }

    @Test
    @DisplayName("정렬_미지정이면_기본정렬이_적용되고_page_size는_보존된다")
    void unsortedUsesDefaultSort() {
        Pageable applied = SortFieldMapper.apply(PageRequest.of(3, 50), ALLOWLIST, DEFAULT_SORT);

        assertThat(applied.getSort()).isEqualTo(DEFAULT_SORT);
        assertThat(applied.getPageNumber()).isEqualTo(3);
        assertThat(applied.getPageSize()).isEqualTo(50);
    }
}
