package kr.co.cudo.authoring.dataset.export;

/**
 * 학습데이터 export JSON 의 <b>개인정보 3필드({@code anonymity} · {@code pseudonymity} ·
 * {@code privacy_included}) 단일 판정기</b>.
 *
 * <h3>★ 확정 정책 (2026-08-04 사용자 확정 — 원천 축 전환)</h3>
 * <table border="1">
 *   <caption>블록·산출종류별 값</caption>
 *   <tr><th>블록</th><th>필드</th><th>ORIGINAL(원천)</th><th>DEIDENTIFIED(비식별)</th></tr>
 *   <tr><td rowspan="3">{@code video}<br>(영상 단위)</td>
 *       <td>{@code anonymity}</td><td rowspan="3"><b>관제 인입값</b>(그대로)</td>
 *       <td>수동값 → 미입력 시 {@code Y}</td></tr>
 *   <tr><td>{@code pseudonymity}</td><td>수동값 → 미입력 시 {@code N}</td></tr>
 *   <tr><td>{@code privacy_included}</td><td>수동값 → 미입력 시 {@code N}</td></tr>
 *   <tr><td rowspan="3">{@code image}<br>(프레임 단위)</td>
 *       <td>{@code anonymity}</td><td><b>정책 상수 {@code N}</b></td>
 *       <td>수동값 → 미입력 시 {@code Y}</td></tr>
 *   <tr><td>{@code pseudonymity}</td><td><b>정책 상수 {@code N}</b></td><td>수동값 → 미입력 시 {@code N}</td></tr>
 *   <tr><td>{@code privacy_included}</td><td><b>정책 상수 {@code Y}</b></td><td>수동값 → 미입력 시 {@code N}</td></tr>
 * </table>
 * <p><b>파생영상(증강·해상도)은 두 블록 모두 원천 축이 {@code null}</b> 이다 — 상수도 넣지 않는다.
 *
 * <h3>왜 원천에 값을 채우는가 — 왕복(round-trip) 근거</h3>
 * <p>export JSON 은 <b>다시 읽혀 적재되는 자산</b>이 된다(사용자 확정 2026-08-04:
 * <i>"차후에 데이터마트를 업로드하는 기능(json 읽어서 넣기)도 생길 예정"</i>). 원천 산출물의 3필드가
 * {@code null} 이면 재적재 시 <b>"판정하지 않았다"와 "값이 유실됐다"를 구분할 수 없다</b>. 따라서
 * 원천에도 값을 싣는다. 값의 조달 방식이 블록마다 다른 것은 아래와 같이 <b>실재하는 데이터가 다르기
 * 때문</b>이다.
 *
 * <h3>두 블록의 원천값은 "같아 보여도 출처가 다르다" — 통합하지 말 것</h3>
 * <ul>
 *   <li>{@code video} 원천 ← <b>관제가 실제로 판정해 보낸 값</b>
 *       ({@code LS_DATA_INGEST.*_INCL_YN}, V166 신설 · V170 이 fail-closed DB DEFAULT
 *       {@code N}/{@code N}/{@code Y} 부여 → 신규 인입에는 null 이 남지 않는다).</li>
 *   <li>{@code image} 원천 ← <b>정책 상수</b>({@link #ORGNL_DEFAULT_ANONYMITY} 등 = {@code N}/{@code N}/{@code Y}).
 *       <b>프레임 단위 원천 판정 데이터가 이 시스템에 존재하지 않기 때문</b>이다.</li>
 * </ul>
 * <p>⚠ <b>"값이 같으니 합치자"로 통합하지 말 것</b> — 관제가 특정 영상에 {@code PRVC_INCL_YN='N'} 을
 * 보내면 {@code video} 는 {@code N}, {@code image} 는 상수 {@code Y} 로 <b>갈린다</b>. 이는 모순이 아니라
 * <b>입도가 다른 사실</b>이다(아래 §입도 참조).
 *
 * <h3>★ 프레임 축에 DB 컬럼을 만들지 않는 이유 (구조적 충돌 — 실측)</h3>
 * <p>{@code LS_DATA_SRC} 의 3필드(V130)는 <b>한 벌인데 두 축이 공유</b>한다 — {@code image} 블록은
 * 원천·비식별 산출 모두 같은 컬럼을 읽는다. 여기에 fail-closed DB DEFAULT 를 걸면 <b>비식별의
 * "미입력" 상태가 사라져</b> {@link #DEID_DEFAULT_ANONYMITY}({@code Y})가 영영 적용되지 않고 비식별
 * {@code anonymity} 가 {@code Y}→{@code N} 으로 <b>뒤집힌다</b>. 그래서 프레임 축 원천값은 스키마가
 * 아니라 <b>이 클래스의 상수</b>로 둔다. ★ 원천 분기는 <b>프레임 수동값을 읽지 않는다</b> —
 * {@code LS_DATA_SRC} 의 수동값은 <b>비식별 축 판정</b>(작업자 수동입력)이라 원천에 실으면 축이 섞인다.
 *
 * <h3>수동값 원천 — 블록마다 자기 입도의 축을 읽는다 (2026-08-03 확정, 유지)</h3>
 * <ul>
 *   <li>{@code video} 블록({@code VideoMetaMapper}) ← <b>영상 단위</b> 수동값
 *       ({@code LS_DATA_RAW.ANONY_INCL_YN}/{@code PSDO_INCL_YN}/{@code PRVC_INCL_YN}, V163)</li>
 *   <li>{@code image} 블록({@code NiaJsonBuilder}) ← <b>프레임 단위</b> 수동값
 *       ({@code LS_DATA_SRC.ANONY_INCL_YN}/{@code PSDO_INCL_YN}/{@code PRVC_INCL_YN}, V130)</li>
 * </ul>
 * 두 블록은 <b>같은 판정기(이 클래스)</b>를 쓰되 <b>서로 다른 입도의 원천</b>을 읽는다. 판정 로직 자체를
 * 복제하지 않는다는 원칙은 그대로다(복제하면 한쪽만 갱신돼 어긋난다 — 이 프로젝트의 반복 결함 패턴).
 * 상수 또한 이 클래스에만 두며 화면 프리필({@code VideoPrivacyMetaService}·{@code FramePrivacyMetaService})은
 * <b>참조</b>만 한다(2026-08-03 에 상수 복제로 화면↔export 가 어긋난 사고의 재발 방지).
 *
 * <h3>★ 폐기된 구 정책과 그 경위 (되돌리지 말 것)</h3>
 * <p>이 프로젝트는 "철회된 정책 재시도"가 반복 사고 패턴이므로 경위를 남긴다.
 * <ol>
 *   <li><b>구 정책 ①(2026-07-31)</b>: {@code ORIGINAL} = 개인정보 있음({@code anonymity=N}, 가명·개인정보는
 *       영상 {@code PRVC_TYPE_CD}/{@code PRVC_YN} 파생) + <b>프레임 수동 override 는 ORIGINAL 에만</b> 허용,
 *       {@code DEIDENTIFIED} 는 수동값을 <b>무시</b>하고 상수(Y/N/N) 고정. <b>폐기.</b></li>
 *   <li><b>구 정책 ②(2026-08-03)</b>: {@code ORIGINAL} = <b>전부 {@code null}</b>(판정하지 않음).
 *       근거는 "원천영상은 비식별 처리 전이라 판정이 성립하지 않는다"였다. <b>폐기</b> — 관제가 원천
 *       판정을 <b>무조건 채워 보내며</b>(2026-08-04 사용자 확정) 그 통로가 V166 으로 실재하게 됐고,
 *       export JSON 이 재적재되는 왕복 자산이 되면서 {@code null} 이 "유실"과 구분되지 않기 때문이다.
 *       <b>단 구 정책 ②의 "값을 지어내지 않는다"는 조항은 존치된다</b> — 관제가 보내지 않은 필드는
 *       {@code video} 블록에서 여전히 {@code null} 이다(상수로 메우지 않는다).</li>
 *   <li><b>{@code DEIDENTIFIED} 는 두 번의 반전에도 불변</b>이다 — 수동값 우선 + 기본상수(Y/N/N).
 *       왜 2026-08-03 에 수동 override 억제가 풀렸는지는 아래 각주 참조.</li>
 *   <li><b>2026-08-03 억제 해소의 근거(유지)</b>: 영상 단위 저장소(V163)가 생겨 <b>두 블록 모두 사람이
 *       입력한 사실</b>을 읽는다. 따라서 {@code video.privacy_included=Y} / {@code image.privacy_included=N} 은
 *       모순이 아니라 <b>"영상 어딘가엔 개인정보가 있지만 이 프레임엔 없다"</b>는 정상적인 서로 다른
 *       입도의 사실이다.</li>
 *   <li><b>{@code PRVC_TYPE_CD}/{@code PRVC_YN} 파생의 소멸(유지)</b>: 이 두 컬럼은 export 개인정보
 *       3필드의 입력이 <b>아니다</b>(원천값은 관제 인입/정책 상수에서 온다). ⚠ 단 여전히
 *       <b>비식별 대상 판정</b>({@code needsDeidentify})에 쓰이므로 컬럼 자체가 죽은 것은 아니다.</li>
 * </ol>
 */
public final class ExportPrivacyPolicy {

    // ---------------------------------------------------------------- 이관 경로 원천 축 (DOMAIN-017)

    /**
     * 외부 산출물 이관({@code SRC_TYPE='IMPORTED'})의 <b>원천 축 익명정보 포함여부</b>가 보관되는
     * 메타 열쇠.
     *
     * <h3>왜 메타에 두는가</h3>
     * <p>원천 축 3필드의 착지 컬럼은 <b>관제 수신 원장</b>({@code LS_DATA_INGEST.*_INCL_YN}) 하나뿐인데,
     * 이관 경로는 그 원장을 <b>거치지 않는다</b>(ADR-048 — 저작도구가 자기 판단으로 수신 원장에 행을
     * 넣으면 그 원장이 더 이상 "관제가 보낸 것"을 뜻하지 않게 된다). 그래서 산출물이 준 원문을
     * {@code LS_DATA_META} 에 보관하고 산출 시점에 이 축이 그것을 읽는다. 새 컬럼을 만들지 않는 이유도
     * 같다 — 인입 축과 이관 축이 서로 다른 컬럼을 가지면 원천 판정 자리가 둘이 된다.
     *
     * <p>열쇠 문자열을 <b>읽는 쪽인 여기</b>에 두는 것은 의도다. 쓰는 쪽(이관)과 읽는 쪽(산출)에 각각
     * 선언하면 같은 문자열이 두 벌이 되어, 한쪽만 바뀌면 값이 조용히 사라진다.
     */
    public static final String IMPORT_SOURCE_ANONYMITY_KEY = "import.video.anonymity";
    /** @see #IMPORT_SOURCE_ANONYMITY_KEY */
    public static final String IMPORT_SOURCE_PSEUDONYMITY_KEY = "import.video.pseudonymity";
    /** @see #IMPORT_SOURCE_ANONYMITY_KEY */
    public static final String IMPORT_SOURCE_PRIVACY_INCLUDED_KEY = "import.video.privacy_included";

    /**
     * 이관 경로의 <b>원천 축 조달</b> — 관제 인입값 대신 메타에 보관된 산출물 원문을 싣는다.
     *
     * <p>돌려주는 값은 {@code sourceExists=true} 다. 이관 영상은 파생영상이 아니라 <b>원천 영상 자체</b>가
     * 있는 경우이므로, {@code image} 블록의 원천 상수({@link #ORGNL_DEFAULT_ANONYMITY} 등)도 종전대로
     * 실려야 한다. 여기서 {@link SourcePrivacyMeta#NONE} 을 돌려주면 {@code image} 블록이 통째로
     * {@code null} 이 되어 <b>파생영상과 구분되지 않는다</b>.
     *
     * <p>세 값이 모두 {@code null} 이어도 {@code true} 다 — "산출물이 그 값을 안 줬다"와 "원천이 아예
     * 없다"는 다른 사실이고, 앞의 경우에도 값을 지어내지 않는다.
     *
     * <p>⚠ 산출물은 <b>프레임 축</b> 3필드도 함께 준다. 그 값은 이 <b>원천 축</b> 판정에 쓰지 않는다 —
     * 확인한 표본에서 프레임 축 값이 비식별 축 기본값과 같은 모양이라, 원천 축에 실으면 "원천 영상인데
     * 익명처리를 거쳤다"는 성립할 수 없는 산출이 나온다. 프레임 축 값이 어디에 착지하는지는
     * {@link #importedFrameValuesLandOnDeidentAxis(boolean)} 이 단독으로 정한다.
     *
     * @param anonyInclYn     보관된 {@code video.anonymity} 원문(없으면 {@code null})
     * @param psdoInclYn      보관된 {@code video.pseudonymity} 원문
     * @param prvcInclYn      보관된 {@code video.privacy_included} 원문
     * @design DOMAIN-017
     * @design DFEAT-057
     * @design ADR-048
     */
    public static SourcePrivacyMeta importedSource(String anonyInclYn, String psdoInclYn,
                                                   String prvcInclYn) {
        return SourcePrivacyMeta.ofImport(anonyInclYn, psdoInclYn, prvcInclYn);
    }

    /**
     * 이관 산출물이 <b>프레임마다 준</b> 익명·가명·개인정보 포함여부가 우리 <b>비식별 축 컬럼</b>
     * ({@code LS_DATA_SRC.ANONY_INCL_YN}·{@code PSDO_INCL_YN}·{@code PRVC_INCL_YN})에 착지하는가 —
     * <b>이 분기의 단독 소유 지점</b>이다(ERD-031 프레임 행 절).
     *
     * <h3>왜 축이 갈리는가</h3>
     * <ul>
     *   <li><b>비식별이 끝난 것으로 지정해 가져온 경우</b> — 그 판정은 이미 비식별을 마친 화면에 대한
     *       것이라 우리 비식별 축과 뜻이 같다. 그래서 컬럼에 그대로 적재하고, 산출 시점에는
     *       {@link #resolveImageAnonymity} 등의 비식별 분기가 <b>수동값 자리</b>로 그것을 읽는다.</li>
     *   <li><b>원본이라고 지정해 가져온 경우</b> — 그 판정은 <b>원천 축</b>의 사실인데, 프레임 축 원천에는
     *       착지 컬럼이 없다(이 클래스의 §"프레임 축에 DB 컬럼을 만들지 않는 이유" 참조 — 컬럼이 한 벌인데
     *       두 축이 공유해, 원천값을 그 자리에 넣으면 비식별의 "미입력"이 사라져 비식별 산출값이 뒤집힌다).
     *       그래서 컬럼에 넣지 않고 메타에 원문으로 보관한다.</li>
     * </ul>
     *
     * <h3>판정을 여기 한 곳에만 두는 이유</h3>
     * <p>이 저장소는 개인정보 3필드의 판정·상수를 복제했다가 <b>화면이 보는 값과 산출물에 실리는 값이
     * 갈린</b> 사고를 이미 겪었다(2026-08-03). 착지 자리 분기도 같은 축의 판정이므로 이관 쪽에 다시
     * 적지 않고 이 메서드를 부른다 — 한쪽만 바뀌면 값이 조용히 어긋난다.
     *
     * <p>⚠ 이 판정은 <b>어느 자리에 담는가</b>만 정한다. 값 자체는 산출물이 준 것을 그대로 쓰며, 없거나
     * 우리 저장 형식으로 옮길 수 없는 값은 지어내지 않고 <b>적재 기본값</b>을 따른다(ERD-031).
     *
     * @param importedAsDeidentified 가져올 때 사람이 지정한 값 — 참이면 비식별 완료본
     * @design DOMAIN-017
     * @design ERD-031
     */
    public static boolean importedFrameValuesLandOnDeidentAxis(boolean importedAsDeidentified) {
        return importedAsDeidentified;
    }

    /** 비식별 산출물 기본값 — 익명정보 포함여부. 수동 판정이 없을 때만 적용. */
    public static final String DEID_DEFAULT_ANONYMITY = "Y";
    /** 비식별 산출물 기본값 — 가명정보 포함여부. */
    public static final String DEID_DEFAULT_PSEUDONYMITY = "N";
    /** 비식별 산출물 기본값 — 개인정보 포함여부. */
    public static final String DEID_DEFAULT_PRIVACY_INCLUDED = "N";

    /**
     * 원천 산출물 <b>프레임({@code image}) 블록</b> 상수 — 익명정보 포함여부.
     *
     * <p>원천 영상은 비식별 처리 <b>전</b> 이라 "익명처리를 거쳤다"는 주장을 할 근거가 없다 → {@code N}.
     * {@code LS_DATA_INGEST} 의 DB DEFAULT(V170)와 <b>같은 fail-closed 값</b>이며, 두 곳이 어긋나면
     * 같은 문서의 video/image 가 근거 없이 갈리므로 값을 함께 바꾼다.
     */
    public static final String ORGNL_DEFAULT_ANONYMITY = "N";
    /** 원천 산출물 {@code image} 블록 상수 — 가명정보 포함여부({@code N}). */
    public static final String ORGNL_DEFAULT_PSEUDONYMITY = "N";
    /**
     * 원천 산출물 {@code image} 블록 상수 — 개인정보 포함여부({@code Y}, fail-closed).
     *
     * <p>원천 프레임에는 마스킹되지 않은 얼굴·번호판이 남아 있는 것이 기본 상태다. {@code N} 으로 보면
     * 학습데이터가 <b>과소 신고</b>된다(CWE-359).
     */
    public static final String ORGNL_DEFAULT_PRIVACY_INCLUDED = "Y";

    private ExportPrivacyPolicy() {
    }

    // ---------------------------------------------------------------- video 블록 (영상 단위)

    /**
     * {@code video} 블록 익명정보 포함여부 — 원천=관제 인입값 / 비식별=수동값 우선(미입력 시 {@code Y}).
     *
     * @param kind          산출 종류
     * @param videoManualYn <b>비식별 축</b> 영상 단위 수동값({@code LS_DATA_RAW}, null/blank=미입력)
     * @param source        <b>원천 축</b> 관제 인입값 묶음(파생영상·인입 부재면 {@link SourcePrivacyMeta#NONE})
     */
    public static String resolveVideoAnonymity(ExportKind kind, String videoManualYn,
                                               SourcePrivacyMeta source) {
        return (kind == ExportKind.ORIGINAL)
                ? sourceValue(source, SourcePrivacyMeta::anonyInclYn)
                : deidValue(videoManualYn, DEID_DEFAULT_ANONYMITY);
    }

    /** {@code video} 블록 가명정보 포함여부 — 원천=관제 인입값 / 비식별=수동값 우선(미입력 시 {@code N}). */
    public static String resolveVideoPseudonymity(ExportKind kind, String videoManualYn,
                                                  SourcePrivacyMeta source) {
        return (kind == ExportKind.ORIGINAL)
                ? sourceValue(source, SourcePrivacyMeta::psdoInclYn)
                : deidValue(videoManualYn, DEID_DEFAULT_PSEUDONYMITY);
    }

    /** {@code video} 블록 개인정보 포함여부 — 원천=관제 인입값 / 비식별=수동값 우선(미입력 시 {@code N}). */
    public static String resolveVideoPrivacyIncluded(ExportKind kind, String videoManualYn,
                                                     SourcePrivacyMeta source) {
        return (kind == ExportKind.ORIGINAL)
                ? sourceValue(source, SourcePrivacyMeta::prvcInclYn)
                : deidValue(videoManualYn, DEID_DEFAULT_PRIVACY_INCLUDED);
    }

    // ---------------------------------------------------------------- image 블록 (프레임 단위)

    /**
     * {@code image} 블록 익명정보 포함여부 — 원천=정책 상수 / 비식별=수동값 우선(미입력 시 {@code Y}).
     *
     * <p>★ 원천 분기는 {@code frameManualYn} 을 <b>읽지 않는다</b> — 그 값은 비식별 축 판정이다.
     *
     * @param frameManualYn <b>비식별 축</b> 프레임 단위 수동값({@code LS_DATA_SRC}, null/blank=미입력)
     * @param source        원천 영상 존재 여부 판정용(파생영상이면 상수도 싣지 않고 {@code null})
     */
    public static String resolveImageAnonymity(ExportKind kind, String frameManualYn,
                                               SourcePrivacyMeta source) {
        return (kind == ExportKind.ORIGINAL)
                ? sourceConstant(source, ORGNL_DEFAULT_ANONYMITY)
                : deidValue(frameManualYn, DEID_DEFAULT_ANONYMITY);
    }

    /** {@code image} 블록 가명정보 포함여부 — 원천=정책 상수({@code N}) / 비식별=수동값 우선(미입력 시 {@code N}). */
    public static String resolveImagePseudonymity(ExportKind kind, String frameManualYn,
                                                  SourcePrivacyMeta source) {
        return (kind == ExportKind.ORIGINAL)
                ? sourceConstant(source, ORGNL_DEFAULT_PSEUDONYMITY)
                : deidValue(frameManualYn, DEID_DEFAULT_PSEUDONYMITY);
    }

    /** {@code image} 블록 개인정보 포함여부 — 원천=정책 상수({@code Y}) / 비식별=수동값 우선(미입력 시 {@code N}). */
    public static String resolveImagePrivacyIncluded(ExportKind kind, String frameManualYn,
                                                     SourcePrivacyMeta source) {
        return (kind == ExportKind.ORIGINAL)
                ? sourceConstant(source, ORGNL_DEFAULT_PRIVACY_INCLUDED)
                : deidValue(frameManualYn, DEID_DEFAULT_PRIVACY_INCLUDED);
    }

    // ---------------------------------------------------------------- 공통 판정

    /**
     * 원천 축 <b>관제 인입값</b> — 원천 영상이 없으면(파생영상) {@code null}, 관제가 안 보냈으면
     * {@code null}(<b>값을 지어내지 않는다</b> — 여기에 상수 폴백을 넣으면 구 정책 ①로 되돌아간다).
     */
    private static String sourceValue(SourcePrivacyMeta source,
                                      java.util.function.Function<SourcePrivacyMeta, String> field) {
        if (source == null || !source.sourceExists()) {
            return null;
        }
        return normalize(field.apply(source));
    }

    /** 원천 축 <b>정책 상수</b> — 원천 영상이 없으면(파생영상) 상수도 싣지 않고 {@code null}. */
    private static String sourceConstant(SourcePrivacyMeta source, String orgnlDefault) {
        return (source != null && source.sourceExists()) ? orgnlDefault : null;
    }

    /** 비식별 축 — 수동값 우선, 미입력(null/blank)이면 기본상수. blank 는 CHAR(1) 공백 패딩까지 포함. */
    private static String deidValue(String manualYn, String deidDefault) {
        String normalized = normalize(manualYn);
        return (normalized == null) ? deidDefault : normalized;
    }

    /** null/blank → null, 그 외 trim. CHAR(1) 공백 패딩을 미입력으로 보는 기준은 두 축이 동일하다. */
    private static String normalize(String yn) {
        return (yn == null || yn.isBlank()) ? null : yn.trim();
    }
}
