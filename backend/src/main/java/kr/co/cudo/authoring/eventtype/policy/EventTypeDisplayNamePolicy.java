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
     * 표시명이 <b>어느 단계에서 왔는가</b> — 관리 화면이 "이 이름이 지금 어디서 오고 있는지"를
     * 원본 필드로 다시 판정하지 않도록 서버가 채택 단계를 직접 알려준다. @design API-185, API-186
     *
     * <p>화면이 {@code optrIndctNm}/{@code evntNm}/{@code evntCtgryNm} 을 보고 폴백을 재현하면 그것이
     * 곧 <b>두 번째 판정</b>이 되어 이 클래스와 갈릴 수 있다(이 저장소의 반복 결함 패턴).
     *
     * <p>{@link #wireValue()} 가 응답에 실리는 값이며 <b>소문자</b>다.
     */
    public enum Source {
        /** 운영자 표시명({@code OPTR_INDCT_NM}) 채택. */
        OPERATOR,
        /** 관제 수신 유형명({@code EVNT_NM}) 채택. */
        CONTROL,
        /** 카테고리명({@code LS_EVNT_CTGRY.EVNT_CTGRY_NM}) 채택 — 유형 고유 이름이 아직 없다는 뜻이다. */
        CATEGORY,
        /** 유형코드({@code EVNT_TYPE_CD}) 최종 폴백. */
        CODE;

        /** 응답에 싣는 표기 — 소문자 고정(계약). */
        public String wireValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * 표시명 + 채택 단계.
     *
     * @param dsplNm 해석된 표시명(모든 후보가 비어 있으면 null)
     * @param source 채택 단계(never null)
     */
    public record Resolved(String dsplNm, Source source) {
    }

    /**
     * 표시명 해석(Java 경로).
     *
     * <p>{@link #resolveWithSource} 에 위임한다 — 판정 로직은 한 벌만 존재한다.
     *
     * @param optrIndctNm 운영자 표시명(1순위)
     * @param evntNm      관제 수신 유형명(2순위)
     * @param ctgryNm     카테고리명(3순위)
     * @param evntTypeCd  유형코드(최종 폴백)
     * @return 표시명. 모두 비어 있으면 유형코드(그것도 null 이면 null)
     */
    public static String resolve(String optrIndctNm, String evntNm, String ctgryNm, String evntTypeCd) {
        return resolveWithSource(optrIndctNm, evntNm, ctgryNm, evntTypeCd).dsplNm();
    }

    /**
     * 표시명 해석 + <b>채택 단계</b>. 인자·순서는 {@link #resolve} 와 같다.
     *
     * <p>후보가 모두 비어 있으면 표시명이 null 이어도 단계는 {@link Source#CODE} 다 — 폴백 사슬의
     * 마지막까지 내려간 것은 사실이고, 화면은 "코드까지 내려왔다"를 그대로 안내하면 된다.
     */
    public static Resolved resolveWithSource(String optrIndctNm, String evntNm,
                                             String ctgryNm, String evntTypeCd) {
        String operator = trimToNull(optrIndctNm);
        if (operator != null) {
            return new Resolved(operator, Source.OPERATOR);
        }
        String control = trimToNull(evntNm);
        if (control != null) {
            return new Resolved(control, Source.CONTROL);
        }
        String category = trimToNull(ctgryNm);
        if (category != null) {
            return new Resolved(category, Source.CATEGORY);
        }
        return new Resolved(evntTypeCd, Source.CODE);
    }

    /** 표시명이 <b>고유 이름</b>에서 왔는가(= 카테고리명·코드 폴백이 아닌가). 화면 안내용. */
    public static boolean hasOwnName(String optrIndctNm, String evntNm) {
        return firstNonBlank(optrIndctNm, evntNm) != null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    /**
     * "값 있음" 판정의 단일 기준 — 공백만 채워진 값은 값이 아니다({@link #SQL_COALESCE} 의
     * {@code NULLIF(TRIM(...), '')} 와 같은 기준).
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private EventTypeDisplayNamePolicy() {
    }
}
