package kr.co.cudo.authoring.eventtype.policy;

/**
 * 이벤트유형 <b>표시명 해석 규칙의 단일 진실원</b> — 4단 폴백.
 *
 * <pre>
 *   표시명 = COALESCE(운영자 표시명, 관제 수신 유형명, 카테고리명, 유형코드)
 * </pre>
 *
 * <h3>왜 4단인가 (원래 구조 + 어노테이션 계약)</h3>
 * <ul>
 *   <li><b>운영자 표시명</b>({@code OPTR_INDCT_NM}) — 사람이 정한 값이 가장 세다. 관리 API 전용이라
 *       관제가 덮어쓰지 않고, <b>비우면 아래 단계로 자연 복귀</b>한다(관제 원본 유실 없음).</li>
 *   <li><b>관제 수신 유형명</b>({@code EVNT_NM}) — 관제가 인입으로 보내주는 유형별 이름.</li>
 *   <li><b>카테고리명</b>({@code LS_EVNT_CTGRY.EVNT_CTGRY_NM}) — 관제 마스터에는 <b>유형별 이름이
 *       애초에 없었고</b> 사람이 읽는 이름은 카테고리 레벨에만 있었다. 그래서 같은 카테고리의
 *       유형들이 같은 이름으로 보이는 것은 <b>결함이 아니라</b> "아직 고유 이름이 없어 카테고리명으로
 *       표시 중"이라는 정상 상태다. 관제가 특정 유형에 이름을 보내면 <b>그 유형만</b> 갈라진다.</li>
 *   <li><b>유형코드</b> — 마지막 폴백(예외 금지 · 빈 화면 금지).</li>
 * </ul>
 *
 * <h3>★ 이 판정을 복제하지 말 것 (Critical)</h3>
 * <p>필터 옵션 · 코드-라벨 맵 · 관리 화면 · <b>승인 시점 동결</b>({@code DatasetMetaSourceRepository}
 * → export {@code event_name})이 전부 같은 결과를 내야 한다. 한 곳만 갱신되면 화면과 산출물이
 * 조용히 갈라진다(이 저장소의 반복 결함 패턴). Java 경로는 {@link #resolve}, native SQL 경로는
 * {@link #SQL_COALESCE} 를 <b>재사용</b>한다. 정적 가드: {@code EvntTypeMasterTableRemovalTest}.
 */
public final class EventTypeDisplayNamePolicy {

    /**
     * native SQL 용 폴백 식 — 별칭이 유형 {@code et} · 카테고리 {@code ec} 로 고정된 쿼리에서 쓴다.
     *
     * <p>{@code NULLIF(TRIM(...), '')} 로 감싸는 이유: 공백만 채워진 값은 "값 있음"이 아니다(관제
     * 수신값·수기 입력 모두 그럴 수 있다). Java 쪽 {@link #resolve} 와 같은 기준이다.
     */
    public static final String SQL_COALESCE = """
            COALESCE(
                NULLIF(TRIM(et.OPTR_INDCT_NM), ''),
                NULLIF(TRIM(et.EVNT_NM), ''),
                NULLIF(TRIM(ec.EVNT_CTGRY_NM), ''),
                et.EVNT_TYPE_CD)
            """;

    /**
     * 표시명 해석(Java 경로).
     *
     * @param optrIndctNm 운영자 표시명(1순위)
     * @param evntNm      관제 수신 유형명(2순위)
     * @param ctgryNm     카테고리명(3순위)
     * @param evntTypeCd  유형코드(최종 폴백)
     * @return 표시명. 모두 비어 있으면 유형코드(그것도 null 이면 null)
     */
    public static String resolve(String optrIndctNm, String evntNm, String ctgryNm, String evntTypeCd) {
        String value = firstNonBlank(optrIndctNm, evntNm, ctgryNm);
        return value != null ? value : evntTypeCd;
    }

    /** 표시명이 <b>고유 이름</b>에서 왔는가(= 카테고리명·코드 폴백이 아닌가). 화면 안내용. */
    public static boolean hasOwnName(String optrIndctNm, String evntNm) {
        return firstNonBlank(optrIndctNm, evntNm) != null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return null;
    }

    private EventTypeDisplayNamePolicy() {
    }
}
