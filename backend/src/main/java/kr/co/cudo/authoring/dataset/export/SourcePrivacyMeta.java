package kr.co.cudo.authoring.dataset.export;

/**
 * export 산출의 <b>원천(ORIGINAL) 축</b> 입력 — "원천 영상이 존재하는가" + 관제가 인입 행에 실어 보낸 판정.
 *
 * <h3>왜 별도 타입인가</h3>
 * <p>같은 타입({@code String} Y/N) 3개를 메서드 인자로 줄줄이 넘기면 순서가 바뀌어도 컴파일러가 잡지
 * 못한다(익명↔가명↔개인정보가 조용히 뒤바뀐다 = 개인정보 오신고). 원천 축 값은 리포지토리 → 컨텍스트
 * → 매퍼로 3단계를 이동하므로 묶어서 옮긴다. 또 <b>비식별 축 수동값과 인자로 섞이지 않게</b> 타입으로
 * 갈라 둔다({@code String} 끼리는 뒤바뀌어도 컴파일된다).
 *
 * <h3>{@link #sourceExists} — 판정의 1차 분기</h3>
 * <ul>
 *   <li>{@code true} = <b>원본 영상</b>. {@code video} 블록은 아래 인입값을, {@code image} 블록은
 *       정책 상수({@code ExportPrivacyPolicy.ORGNL_DEFAULT_*})를 싣는다.</li>
 *   <li>{@code false} = <b>파생영상(증강·해상도) 또는 영상 행 부재</b>. 두 블록 모두 {@code null} —
 *       <b>상수도 넣지 않는다</b>. 파생은 부모의 <b>비식별본</b>으로 만들어져 "비식별 처리 전 원천"이라는
 *       대상 자체가 없다(결손이 아니라 정상). 파생의 개인정보 판정은 <b>비식별 축</b>이며 그 값은
 *       생성 시점에 {@code LsDataRaw.copyPrivacyMetaFrom(parent)} 로 자기 행에 계승돼 있다.</li>
 * </ul>
 *
 * <h3>값의 출처</h3>
 * <ul>
 *   <li>{@code LS_DATA_INGEST.ANONY_INCL_YN}/{@code PSDO_INCL_YN}/{@code PRVC_INCL_YN}
 *       (V166 신설 · V170 이 fail-closed DB DEFAULT {@code N}/{@code N}/{@code Y} 부여).</li>
 *   <li>조달 규칙(파생 제외 · LATERAL 단건 보장)은 {@code IngestSourceLink} 한 곳이 소유한다 —
 *       여기서 재유도하지 않는다.</li>
 * </ul>
 *
 * <p>{@code null} 필드는 <b>"관제가 보내지 않았다"</b>를 뜻하며 그대로 {@code null} 로 산출된다 —
 * 앱이 상수를 지어내 채우지 않는다. V170 이후 신규 인입에는 DB DEFAULT 가 있어 null 이 남지 않으며,
 * null 이 남는 것은 V166 이전 레거시 행 또는 관제가 명시적으로 NULL 을 송신한 경우뿐이다.
 */
public record SourcePrivacyMeta(boolean sourceExists,
                                String anonyInclYn, String psdoInclYn, String prvcInclYn) {

    /** 원천 영상이 없다 — 파생영상 · 영상 행 부재. 두 블록 모두 {@code null} 로 산출된다. */
    public static final SourcePrivacyMeta NONE = new SourcePrivacyMeta(false, null, null, null);

    /**
     * 원본 영상의 관제 인입 판정 — 세 값이 모두 null 이어도 <b>"원천 영상은 존재한다"</b>는 사실은
     * 유지된다({@code image} 블록 상수는 그대로 실린다. 값 미수신과 원천 부재는 다른 사실이다).
     */
    public static SourcePrivacyMeta ofIngest(String anonyInclYn, String psdoInclYn, String prvcInclYn) {
        return new SourcePrivacyMeta(true, anonyInclYn, psdoInclYn, prvcInclYn);
    }
}
