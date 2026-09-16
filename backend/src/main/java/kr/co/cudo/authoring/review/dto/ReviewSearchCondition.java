package kr.co.cudo.authoring.review.dto;

/**
 * 검수목록({@code GET /v1/reviews}, {@code GET /v1/reviews/summary}) 서버 검색 조건.
 *
 * <p>모든 필터는 <b>선택(optional)</b> 이며 서로 AND 로 결합된다. 조건이 하나도 없으면 검수 워크플로
 * 화이트리스트({@code PENDING/IN_REVIEW/APPROVED/REJECTED})만 적용된 전체 목록이 된다.
 *
 * <p><b>R8 하위호환 — {@code status} 는 정규화하지 않는다</b>: 구 JPQL 조건
 * ({@code :status IS NULL OR :status = '' OR s.dataSttsCd = :status})의 의미를 <b>글자 그대로</b>
 * 보존한다.
 * <ul>
 *   <li>{@code null} / 빈 문자열 → 필터 미적용(전체)</li>
 *   <li>그 밖의 값 → {@code trim} 도 대소문자 정규화도 하지 않고 그대로 동등 비교. 화이트리스트 밖
 *       값(예: {@code PROCESSING})이나 공백이 섞인 값은 교집합이 공집합이라 <b>빈 결과(200)</b> 가 된다
 *       — 400 이 아니다(기존 계약).</li>
 * </ul>
 * 여기서 trim 을 넣으면 {@code status="  "} 가 "빈 결과"에서 "전체"로 바뀌어 동작이 달라진다.
 *
 * <p>{@code q} 는 신규 파라미터라 하위호환 제약이 없어, 다른 목록 화면과 동일하게 공백만 입력을
 * {@code null}(필터 미적용)로 정규화한다.
 *
 * @param status       검수 워크플로 상태 코드(BE 코드만 수용 — FE 코드 역매핑은 FE 책임)
 * @param q            검색어 — 영상명(CCTV 명, 없으면 VMS_CCTV_ID 폴백)·작업자명 부분일치
 * @param excludedOnly <b>제외분만 보기</b>. {@code null}·{@code false} 면 제외 표시가 선 영상을 뺀
 *                     기본 목록이고, {@code true} 면 제외된 영상만 남는다. 값역은 이 두 갈래뿐이며
 *                     섞어 보는 갈래를 두지 않는다 — 섞이면 어느 것이 제외분인지 행마다 구분해야 한다.
 *                     [@design ADR-069] [@design API-008]
 */
public record ReviewSearchCondition(String status, String q, Boolean excludedOnly) {

    public ReviewSearchCondition {
        q = normalize(q);
    }

    /**
     * 제외 축을 지정하지 않는 기존 호출 형태 — 보내지 않던 호출의 결과가 달라지지 않는다.
     *
     * <p>정규 생성자에 인자를 하나 더하면서도 <b>기존 두 인자 호출을 그대로 둔다</b>. 호출부를 전부
     * 고치면 그 커밋의 diff 가 넓어져 「추가만」인지 확인하기 어려워진다.
     */
    public ReviewSearchCondition(String status, String q) {
        this(status, q, null);
    }

    /** 필터 없는 기본 조건. */
    public static ReviewSearchCondition defaults() {
        return new ReviewSearchCondition(null, null, null);
    }

    /**
     * 「제외분만 보기」가 켜져 있는가 — {@code null} 을 {@code false} 로 읽는 단일 지점.
     *
     * <p>호출부가 {@code Boolean.TRUE.equals(...)} 를 각자 적으면 한 곳만 {@code != null} 로 적어도
     * 드러나지 않는다(그 순간 「보내지 않음」이 「제외분만」이 된다).
     */
    public boolean excludedOnlyOn() {
        return Boolean.TRUE.equals(excludedOnly);
    }

    /**
     * 실제로 적용할 상태 필터 — {@code null}/빈 문자열이면 필터를 걸지 않는다(구 JPQL 과 동일 의미).
     */
    public String statusFilter() {
        return (status == null || status.isEmpty()) ? null : status;
    }

    /**
     * 검색어만 남기고 상태 축과 제외 축을 제거한 조건 — KPI 집계({@code summary})용.
     *
     * <p>집계 창구는 <b>「제외분을 뺀 버킷 4종」과 「제외됨 건수」를 함께</b> 돌려주므로 요청이 제외 축을
     * 고를 자리가 없다. 두 값은 리포지토리 호출부가 갈래를 명시해 세며, 여기서 축을 남기면 그 명시와
     * 조건 객체가 두 번째 진실원이 된다. [@design ADR-069] [@design API-138]
     */
    public ReviewSearchCondition searchOnly() {
        return new ReviewSearchCondition(null, q, null);
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
