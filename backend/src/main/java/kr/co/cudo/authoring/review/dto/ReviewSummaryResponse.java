package kr.co.cudo.authoring.review.dto;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;

import java.util.List;
import java.util.Map;

/**
 * 검수목록(SCR-REVIEW-001) KPI 카드 집계 — {@code GET /v1/reviews/summary} 응답.
 *
 * <p><b>불변식</b>: {@code total == pending + inReview + approved + rejected}. 집계 대상이 검수
 * 워크플로 화이트리스트({@code ReviewRepository.REVIEW_STATUS_WHITELIST}) 4종으로 한정되고 버킷도
 * 같은 4종이라 구조적으로 성립한다(배치/작업 상태는 어느 버킷에도 들어가지 않는다).
 *
 * <p>★<b>{@code excludedCount} 는 이 불변식의 항이 아니다</b> — 제외분은 {@code total} 에서도 이미 빠져
 * 있어 <b>별개 축</b>이다. 합에 더하면 불변식이 깨진다. [@design ADR-069] [@design API-138]
 *
 * <p>★<b>「제외됨 N건」의 진실원은 이 집계 창구 하나다</b> — 목록 응답({@code GET /v1/reviews})에는 그
 * 키를 두지 않는다. 같은 숫자를 두 창구가 각각 계산하면 한쪽만 조건이 바뀌어도 드러나지 않는다.
 *
 * <p><b>스냅샷 의미</b>: 목록({@code GET /v1/reviews})과 별도 요청이므로 두 호출 사이에 검수 상태가
 * 바뀌면 카드와 목록이 미세하게 어긋날 수 있다. 대시보드성 KPI 라 강한 정합성을 요구하지 않으며,
 * 각 값은 <b>조회 시점 스냅샷</b>이다.
 *
 * @param total         필터 결과 총건수 (4종 합계와 동일)
 * @param pending       검수 대기 — {@code PENDING}
 * @param inReview      검수 진행중 — {@code IN_REVIEW}
 * @param approved      승인 — {@code APPROVED}
 * @param rejected      반려 — {@code REJECTED}
 * @param excludedCount 제외됨 — 같은 필터 범위 안에서 화면 목록에서 뺀 영상 건수.
 *                      <b>위 합의 항이 아니며</b> 0건이어도 실린다
 */
public record ReviewSummaryResponse(
        long total,
        long pending,
        long inReview,
        long approved,
        long rejected,
        long excludedCount
) {

    /**
     * 이 DTO 가 필드로 매핑하는 상태 코드 — {@code ReviewRepository.REVIEW_STATUS_WHITELIST} 와
     * <b>같은 집합이어야 한다</b>.
     *
     * <p>리포지토리는 화이트리스트 상수를 순회해 버킷을 만드는데 이 DTO 는 4필드를 고정으로 갖는다.
     * 화이트리스트에 5번째 상태가 추가되면 리포지토리의 {@code bucketSum == total} 대조는 그대로
     * 통과하고(예외·로그 없음) 응답 {@code total} 만 목록 {@code totalElements} 보다 작아진다 —
     * "터지는" 게 아니라 <b>조용히 과소집계</b>된다. 그 드리프트를 드러내기 위한 목록이며,
     * {@link #of(Map)} 이 이 목록 밖 상태의 건수를 fail-fast 로 거부한다.
     */
    public static final List<String> MAPPED_STATUSES = List.of(
            LsRawDataStatus.STTS_PENDING,
            LsRawDataStatus.STTS_IN_REVIEW,
            LsRawDataStatus.STTS_APPROVED,
            LsRawDataStatus.STTS_REJECTED);

    /**
     * 상태 코드별 집계 결과에서 응답을 조립한다 — {@code total} 은 4종 합으로 파생돼 불변식이
     * 구조적으로 성립한다.
     *
     * <p>맵에 없는 상태는 0 으로 채운다. 리포지토리는 조건부 집계로 4종을 항상 채우지만, 맵이 비거나
     * 일부 키가 없는 호출(0건 조회 · 테스트)에도 5개 필드가 모두 응답에 실리도록 방어한다(HIGH-6).
     *
     * <p><b>미매핑 상태 fail-fast</b>: {@link #MAPPED_STATUSES} 밖 상태에 건수가 있으면 그 건수는 어느
     * 필드에도 실리지 못해 {@code total} 이 목록 총건수보다 조용히 작아진다. 그런 집계는 KPI 로서
     * 틀린 값이므로 조용히 반환하지 않고 즉시 드러낸다(내부 정합성 위반 — 사용자 입력으로 도달할 수
     * 없으며 메시지에 입력값을 담지 않는다, CWE-209).
     *
     * <p>★{@code excludedCount} 는 <b>버킷이 아니라 별개 축</b>이라 {@code total} 과 4종 합 어디에도
     * 들어가지 않는다({@code total} 은 제외분을 이미 뺀 집합의 크기다). 그래서 별도 인자로 받으며,
     * 이 값을 합에 더하는 순간 「버킷 합 = 전체 건수」 불변식이 깨진다. [@design ADR-069]
     *
     * @param excludedCount 같은 필터 범위 안에서 센 제외 건수 (0건이어도 응답에 실린다)
     */
    public static ReviewSummaryResponse of(Map<String, Long> counts, long excludedCount) {
        verifyMappedCoverage(counts);
        long pending = count(counts, LsRawDataStatus.STTS_PENDING);
        long inReview = count(counts, LsRawDataStatus.STTS_IN_REVIEW);
        long approved = count(counts, LsRawDataStatus.STTS_APPROVED);
        long rejected = count(counts, LsRawDataStatus.STTS_REJECTED);
        return new ReviewSummaryResponse(pending + inReview + approved + rejected,
                pending, inReview, approved, rejected, excludedCount);
    }

    /** 매핑되지 않은 상태에 건수가 실려 있으면(=화이트리스트 확장 후 DTO 미갱신) 즉시 실패시킨다. */
    private static void verifyMappedCoverage(Map<String, Long> counts) {
        if (counts == null) {
            return;
        }
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            long value = entry.getValue() != null ? entry.getValue() : 0L;
            if (value != 0L && !MAPPED_STATUSES.contains(entry.getKey())) {
                throw new IllegalStateException(
                        "검수목록 KPI 집계에 응답 DTO 가 매핑하지 않는 상태가 포함되었습니다 — "
                                + "ReviewSummaryResponse 필드와 REVIEW_STATUS_WHITELIST 정의를 확인하세요. "
                                + "status=" + entry.getKey());
            }
        }
    }

    private static long count(Map<String, Long> counts, String status) {
        if (counts == null) {
            return 0L;
        }
        Long value = counts.get(status);
        return value != null ? value : 0L;
    }
}
