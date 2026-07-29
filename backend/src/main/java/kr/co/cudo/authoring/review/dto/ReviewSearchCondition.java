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
 * @param status 검수 워크플로 상태 코드(BE 코드만 수용 — FE 코드 역매핑은 FE 책임)
 * @param q      검색어 — 영상명(CCTV 명, 없으면 VMS_CCTV_ID 폴백)·작업자명 부분일치
 */
public record ReviewSearchCondition(String status, String q) {

    public ReviewSearchCondition {
        q = normalize(q);
    }

    /** 필터 없는 기본 조건. */
    public static ReviewSearchCondition defaults() {
        return new ReviewSearchCondition(null, null);
    }

    /**
     * 실제로 적용할 상태 필터 — {@code null}/빈 문자열이면 필터를 걸지 않는다(구 JPQL 과 동일 의미).
     */
    public String statusFilter() {
        return (status == null || status.isEmpty()) ? null : status;
    }

    /** 검색어만 남기고 상태 축을 제거한 조건 — KPI 집계({@code summary})용. */
    public ReviewSearchCondition searchOnly() {
        return new ReviewSearchCondition(null, q);
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
