package kr.co.cudo.authoring.dataset.export;

/**
 * 학습데이터 export JSON 의 <b>개인정보 3필드({@code anonymity} · {@code pseudonymity} ·
 * {@code privacy_included}) 단일 판정기</b>.
 *
 * <h3>★ 확정 정책 (2026-08-03 사용자 확정 — 구 정책 전면 반전)</h3>
 * <table border="1">
 *   <caption>산출종류별 값</caption>
 *   <tr><th>필드</th><th>ORIGINAL(원천)</th><th>DEIDENTIFIED(비식별)</th></tr>
 *   <tr><td>{@code anonymity}</td><td>{@code null}</td><td>수동값 → 미입력 시 {@code Y}</td></tr>
 *   <tr><td>{@code pseudonymity}</td><td>{@code null}</td><td>수동값 → 미입력 시 {@code N}</td></tr>
 *   <tr><td>{@code privacy_included}</td><td>{@code null}</td><td>수동값 → 미입력 시 {@code N}</td></tr>
 * </table>
 *
 * <p><b>근거</b>: 원천영상은 <b>비식별 처리 전</b>이라 "익명인가/가명인가/개인정보가 남았나"라는 판정이
 * 성립하지 않는다 — 판정하지 않았다는 사실을 {@code null} 로 표현한다(값을 지어내지 않는다). 판정이 실제로
 * 의미 있는 것은 <b>비식별 산출물</b>이며, 그 판정은 사람이 화면에서 수동 입력한다. 수동값이 없을 때만
 * "전체가 비식별된 산출물"이라는 기본 가정(Y/N/N)을 쓴다.
 *
 * <h3>수동값 원천 — 블록마다 자기 입도의 축을 읽는다 (2026-08-03 확정)</h3>
 * <ul>
 *   <li>{@code video} 블록({@code VideoMetaMapper}) ← <b>영상 단위</b> 수동값
 *       ({@code LS_DATA_RAW.ANONY_INCL_YN}/{@code PSDO_INCL_YN}/{@code PRVC_INCL_YN}, V161)</li>
 *   <li>{@code image} 블록({@code NiaJsonBuilder}) ← <b>프레임 단위</b> 수동값
 *       ({@code LS_DATA_SRC.ANONY_INCL_YN}/{@code PSDO_INCL_YN}/{@code PRVC_INCL_YN}, V130)</li>
 * </ul>
 * 두 블록은 <b>같은 판정기(이 클래스)</b>를 쓰되 <b>서로 다른 입도의 원천</b>을 읽는다. 판정 로직 자체를
 * 복제하지 않는다는 원칙은 그대로다(복제하면 한쪽만 갱신돼 어긋난다 — 이 프로젝트의 반복 결함 패턴).
 *
 * <h3>★ 폐기된 구 정책과 그 경위 (되돌리지 말 것)</h3>
 * <p>이 프로젝트는 "철회된 정책 재시도"가 반복 사고 패턴이므로 경위를 남긴다.
 * <ol>
 *   <li><b>구 정책(2026-07-31)</b>: {@code ORIGINAL} = 개인정보 있음({@code anonymity=N}, 가명·개인정보는
 *       영상 {@code PRVC_TYPE_CD}/{@code PRVC_YN} 파생) + <b>프레임 수동 override 는 ORIGINAL 에만</b> 허용,
 *       {@code DEIDENTIFIED} 는 수동값을 <b>무시</b>하고 상수(Y/N/N) 고정. <b>지금은 정확히 반대다.</b></li>
 *   <li><b>왜 그때 DEIDENTIFIED 에서 override 를 막았나</b>: 프레임 수동값을 {@code image} 에만 태우고
 *       {@code video} 는 태울 원천이 없어, 같은 {@code deid/0000.json} 안에서
 *       {@code video.privacy_included="N"} / {@code image.privacy_included="Y"} 로 <b>모순</b>이 났다
 *       (적대검증 실행 재현). 그 모순의 실체는 <b>"원천이 없어서 기본값인 video" vs "사실인 image"</b>의
 *       충돌이었지 "입도가 다른 두 사실"의 충돌이 아니었다.</li>
 *   <li><b>왜 이제 풀어도 되나</b>: 영상 단위 저장소(V161)가 생겨 <b>두 블록 모두 사람이 입력한 사실</b>을
 *       읽는다. 따라서 {@code video.privacy_included=Y} / {@code image.privacy_included=N} 은 모순이 아니라
 *       <b>"영상 어딘가엔 개인정보가 있지만 이 프레임엔 없다"</b>는 정상적인 서로 다른 입도의 사실이다.
 *       구 주석의 "DEIDENTIFIED override 를 풀면 모순이 재발한다"는 억제 근거는 <b>함께 폐기</b>된다
 *       (그 주석이 스스로 적어 둔 해소 조건 — "영상 단위 메타 저장소 신설" — 이 충족됐다).</li>
 *   <li><b>{@code PRVC_TYPE_CD}/{@code PRVC_YN} 파생의 소멸</b>: {@code ORIGINAL} 이 {@code null} 이 되면서
 *       이 두 컬럼은 export 개인정보 3필드의 입력이 <b>아니다</b>. 죽은 파라미터를 남기지 않기 위해 구
 *       {@code pseudonymity(kind, prvcTypeCd)} / {@code privacyIncluded(kind, prvcYn)} 시그니처를 제거했다.
 *       ⚠ 단 이 컬럼들은 여전히 <b>비식별 대상 판정</b>({@code needsDeidentify})에 쓰이므로 컬럼 자체가
 *       죽은 것은 아니다. (2026-08-03 DEV_FIX 2차 정정 — 구 문장은 "프레임 개인정보 메타 GET 프리필에도
 *       쓰인다"고 적었으나 같은 라운드에 그 프리필이 {@code PRVC_TYPE_CD} 파생을 버리고 이 클래스의
 *       비식별 기본상수를 참조하도록 바뀌면서 <b>거짓이 됐다</b>: {@code FramePrivacyMetaService} 는
 *       이제 영상 행을 읽지 않는다.)</li>
 * </ol>
 */
public final class ExportPrivacyPolicy {

    /** 비식별 산출물 기본값 — 익명정보 포함여부. 수동 판정이 없을 때만 적용. */
    public static final String DEID_DEFAULT_ANONYMITY = "Y";
    /** 비식별 산출물 기본값 — 가명정보 포함여부. */
    public static final String DEID_DEFAULT_PSEUDONYMITY = "N";
    /** 비식별 산출물 기본값 — 개인정보 포함여부. */
    public static final String DEID_DEFAULT_PRIVACY_INCLUDED = "N";

    private ExportPrivacyPolicy() {
    }

    /**
     * 익명정보 포함여부 최종값 — {@code ORIGINAL}=null / {@code DEIDENTIFIED}=수동값 우선, 미입력 시 {@code Y}.
     *
     * @param manualYn 해당 블록 축의 수동값(video=LS_DATA_RAW / image=LS_DATA_SRC, null/blank=미입력)
     */
    public static String resolveAnonymity(ExportKind kind, String manualYn) {
        return resolve(kind, manualYn, DEID_DEFAULT_ANONYMITY);
    }

    /** 가명정보 포함여부 최종값 — {@code ORIGINAL}=null / {@code DEIDENTIFIED}=수동값 우선, 미입력 시 {@code N}. */
    public static String resolvePseudonymity(ExportKind kind, String manualYn) {
        return resolve(kind, manualYn, DEID_DEFAULT_PSEUDONYMITY);
    }

    /** 개인정보 포함여부 최종값 — {@code ORIGINAL}=null / {@code DEIDENTIFIED}=수동값 우선, 미입력 시 {@code N}. */
    public static String resolvePrivacyIncluded(ExportKind kind, String manualYn) {
        return resolve(kind, manualYn, DEID_DEFAULT_PRIVACY_INCLUDED);
    }

    /**
     * 공통 판정 — {@code ORIGINAL} 은 판정하지 않고(null), {@code DEIDENTIFIED} 는 수동값 우선 + 기본값 폴백.
     * blank 는 CHAR(1) 공백 패딩까지 미입력으로 간주한다.
     */
    private static String resolve(ExportKind kind, String manualYn, String deidDefault) {
        if (kind != ExportKind.DEIDENTIFIED) {
            return null;
        }
        return (manualYn == null || manualYn.isBlank()) ? deidDefault : manualYn.trim();
    }
}
