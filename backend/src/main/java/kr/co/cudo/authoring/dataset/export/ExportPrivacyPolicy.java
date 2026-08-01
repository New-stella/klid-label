package kr.co.cudo.authoring.dataset.export;

import kr.co.cudo.authoring.video.entity.LsDataRaw;

/**
 * 학습데이터 export JSON 의 <b>개인정보 3필드({@code anonymity} · {@code pseudonymity} ·
 * {@code privacy_included}) 산출종류 기본값 단일 판정기</b>.
 *
 * <p>export 는 {@code {RAW_SN}/v{n}/orgnl|deid/} <b>두 벌</b>로 나가는데, 두 벌은 같은 프레임을 담아도
 * 개인정보 관점에서 서로 다른 산출물이다 — <b>원천은 개인정보가 있고, 비식별본은 없다</b>
 * (2026-07-31 사용자 확정). 관제팀 확인 결과 관제서버가 개인정보유형을 채워 보내지 않으므로,
 * 입력이 없는 원천영상은 "개인정보가 있고 익명처리되지 않은 것"으로, 비식별 처리된 산출물은
 * "전체가 비식별된 것"으로 본다.
 *
 * <table border="1">
 *   <caption>산출종류별 기본값</caption>
 *   <tr><th>필드</th><th>ORIGINAL</th><th>DEIDENTIFIED</th></tr>
 *   <tr><td>{@code anonymity}</td><td>{@code N}</td><td>{@code Y}</td></tr>
 *   <tr><td>{@code pseudonymity}</td><td>영상 파생({@code PRVC_TYPE_CD=='PSDO'})</td><td>{@code N}</td></tr>
 *   <tr><td>{@code privacy_included}</td><td>영상 파생({@code PRVC_YN})</td><td>{@code N}</td></tr>
 * </table>
 *
 * <p><b>왜 한 곳인가</b>: 이 판정은 {@code image} 블록({@code NiaJsonBuilder})과 {@code video} 블록
 * ({@code VideoMetaMapper}) 두 곳에서 필요하다. 각자 구현하면 한쪽만 갱신돼 <b>같은 문서 안에서
 * {@code video.privacy_included=Y} / {@code image.privacy_included=N} 처럼 모순</b>이 생긴다
 * (이 프로젝트의 반복 결함 패턴 — "판정 로직 복제"). 기본값도, 수동 override 적용 규칙도 여기서만 정한다.
 *
 * <h3>★ 수동 override 적용 범위 — {@code ORIGINAL} 만 (2026-07-31 확정)</h3>
 * 프레임 수동값({@code LS_DATA_SRC.ANONY_INCL_YN}/{@code PSDO_INCL_YN}/{@code PRVC_INCL_YN})은
 * <b>{@code ORIGINAL} 산출물에만</b> 반영하고 {@code DEIDENTIFIED} 산출물에서는 <b>무시</b>한다
 * ({@code anonymity=Y}·{@code pseudonymity=N}·{@code privacy_included=N} 고정).
 *
 * <p><b>근거</b>: 수동값은 <b>프레임 단위</b>라 {@code image} 블록에만 태울 수 있고, {@code video} 블록은
 * <b>영상 단위</b>라 태울 원천이 없다. override 를 두 산출물 모두에 허용했더니 프레임 1건에
 * {@code PRVC_INCL_YN='Y'} 하나만 저장해도 같은 {@code deid/0000.json} 안에서
 * {@code video.privacy_included="N"} / {@code image.privacy_included="Y"} 로 <b>모순</b>이 났다
 * (적대검증 실행 재현, 2026-07-31 — {@code anonymity} 축도 동일). 비식별본은 정의상 "전체가 비식별된 산출물"
 * 이므로 세 값이 모두 상수라, 여기서 override 를 막으면 두 블록의 입력이 일치해 모순이 사라진다.
 *
 * <p><b>해소 조건(되돌릴 시점)</b>: <b>영상 단위 개인정보 메타 설정 화면 + 별도 저장소</b>가 생겨
 * {@code video} 블록도 같은 수동 원천을 읽게 되면(포털=원본 / 관제=비식별·증강 축 분리 포함),
 * 그때 {@code DEIDENTIFIED} 에도 override 를 <b>제대로</b> 배선할 수 있다. 그 전까지는 이 억제를 풀지 말 것 —
 * 푸는 즉시 위 모순이 재발한다(회귀 가드: {@code NiaJsonBuilderTest} 의 video/image 정합 테스트).
 *
 * <p><b>남아 있는 한계(구조적, 축별로 발생 시점이 다름)</b>: {@code ORIGINAL} 은 {@code image} 가 프레임
 * 수동값을, {@code video} 가 영상 메타({@code PRVC_TYPE_CD}/{@code PRVC_YN})를 읽어 <b>입력 자체가 다르다</b>.
 * 따라서 라벨러가 프레임 수동값을 저장하면 {@code ORIGINAL} 에서는 여전히 두 블록이 어긋날 수 있다.
 * 단 이 한계의 시점은 축마다 다르다 — {@code pseudonymity}/{@code privacy_included} 는 이 override 가
 * 이번 변경 이전부터 있었으므로 <b>변경 전에도 존재하던 구조적 한계</b>다. 반면 {@code anonymity} 는
 * 변경 전 {@code NiaJsonBuilder.buildImage} 가 kind 값만 반환해({@code ORIGINAL} 은 항상
 * {@code image.anonymity="N"}) 프레임 수동값을 전혀 반영하지 않았으므로 {@code video}/{@code image} 가
 * 항상 일치했다 — 이 축의 불일치는 <b>이번 변경(2026-07-31, {@code ORIGINAL} 에 수동 override 도입)으로
 * 신규 발생</b>한 것이다. 두 축 모두 위 "영상 단위 저장소" 신설로만 해소되며, 그때까지는 <b>원본 산출물에
 * 한해</b> 허용한다(2026-07-31 사용자 결정 — 라벨러의 실제 판단을 산출물에 반영하는 쪽을 우선).
 */
public final class ExportPrivacyPolicy {

    private static final String YES = "Y";
    private static final String NO = "N";

    private ExportPrivacyPolicy() {
    }

    /** 익명정보 포함여부 기본값 — 원본=N / 비식별=Y. */
    public static String anonymity(ExportKind kind) {
        return (kind == ExportKind.ORIGINAL) ? NO : YES;
    }

    /**
     * 가명정보 포함여부 기본값 — 비식별 산출물은 무조건 {@code N}, 원본은 영상 개인정보유형에서 파생.
     *
     * @param prvcTypeCd 영상 개인정보 처리 유형 ({@code ANONY}/{@code PRVC}/{@code PSDO}, null 허용)
     */
    public static String pseudonymity(ExportKind kind, String prvcTypeCd) {
        if (kind == ExportKind.DEIDENTIFIED) {
            return NO;
        }
        return LsDataRaw.PRVC_TYPE_PSDO.equals(prvcTypeCd) ? YES : NO;
    }

    /**
     * 개인정보 포함여부 기본값 — 비식별 산출물은 무조건 {@code N}, 원본은 영상 {@code PRVC_YN} 그대로.
     *
     * @param prvcYn 영상 개인정보 포함여부 ({@code Y}/{@code N}, null 허용 — 미상은 null 유지)
     */
    public static String privacyIncluded(ExportKind kind, String prvcYn) {
        return (kind == ExportKind.DEIDENTIFIED) ? NO : prvcYn;
    }

    /**
     * 익명정보 포함여부 최종값 — {@code ORIGINAL} 은 프레임 수동값 우선, {@code DEIDENTIFIED} 는 기본값 고정.
     *
     * @param manualYn 프레임 수동값({@code LS_DATA_SRC.ANONY_INCL_YN}, null/blank=미입력)
     */
    public static String resolveAnonymity(ExportKind kind, String manualYn) {
        return applyManual(kind, manualYn, anonymity(kind));
    }

    /**
     * 가명정보 포함여부 최종값 — {@code ORIGINAL} 은 프레임 수동값 우선, {@code DEIDENTIFIED} 는 기본값 고정.
     *
     * @param manualYn 프레임 수동값({@code LS_DATA_SRC.PSDO_INCL_YN}, null/blank=미입력)
     */
    public static String resolvePseudonymity(ExportKind kind, String manualYn, String prvcTypeCd) {
        return applyManual(kind, manualYn, pseudonymity(kind, prvcTypeCd));
    }

    /**
     * 개인정보 포함여부 최종값 — {@code ORIGINAL} 은 프레임 수동값 우선, {@code DEIDENTIFIED} 는 기본값 고정.
     *
     * @param manualYn 프레임 수동값({@code LS_DATA_SRC.PRVC_INCL_YN}, null/blank=미입력)
     */
    public static String resolvePrivacyIncluded(ExportKind kind, String manualYn, String prvcYn) {
        return applyManual(kind, manualYn, privacyIncluded(kind, prvcYn));
    }

    /**
     * 수동 override 적용 규칙 — 클래스 주석의 "★ 수동 override 적용 범위" 참조.
     *
     * <p>{@code DEIDENTIFIED} 는 수동값을 <b>보지 않는다</b>(프레임 단위 수동값을 video 블록이 태울 수 없어
     * 같은 문서 안에서 모순이 나기 때문). blank 는 CHAR(1) 공백 패딩까지 미입력으로 간주한다.
     */
    private static String applyManual(ExportKind kind, String manualYn, String defaultValue) {
        if (kind == ExportKind.DEIDENTIFIED) {
            return defaultValue;
        }
        return (manualYn == null || manualYn.isBlank()) ? defaultValue : manualYn;
    }
}
