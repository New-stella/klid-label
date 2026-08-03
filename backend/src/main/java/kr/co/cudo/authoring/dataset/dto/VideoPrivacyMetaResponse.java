package kr.co.cudo.authoring.dataset.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 영상 단위 개인정보(익명·가명·개인정보 포함여부) 조회/저장 응답.
 *
 * <p>각 값은 <b>수동 저장값이 있으면 그 값({@code MANUAL})</b>, 없으면 <b>비식별 산출물 기본상수</b>
 * ({@code ExportPrivacyPolicy.DEID_DEFAULT_*} — 익명 Y / 가명 N / 개인정보포함 N)를 프리필한 값이며
 * 출처({@code MANUAL}/{@code DERIVED})를 항목별로 함께 반환한다.
 *
 * <p><b>왜 상수를 BE 가 내려주나</b>: 화면이 기본상수를 자체 하드코딩하면 BE 정책이 바뀔 때 조용히
 * 드리프트한다(소비자가 생산자의 판정을 재유도하면 어긋난다 — 이 프로젝트의 반복 결함 패턴).
 * 프리필 값과 출처를 함께 내려 화면은 표시만 하게 한다.
 *
 * <p><b>주의(FE 계약)</b>: {@code DERIVED} 프리필을 그대로 PUT 으로 되돌려 보내면 상수가 수동값으로
 * <b>승격</b>된다. 사용자가 직접 고르지 않은 필드는 {@code null} 로 전송해야 한다.
 *
 * @param rawSn                 영상 PK
 * @param anonymity             익명정보 포함여부(Y/N)
 * @param pseudonymity          가명정보 포함여부(Y/N)
 * @param privacyIncluded       개인정보 포함여부(Y/N)
 * @param anonymitySource       익명 출처(MANUAL/DERIVED)
 * @param pseudonymitySource    가명 출처(MANUAL/DERIVED)
 * @param privacyIncludedSource 개인정보 포함 출처(MANUAL/DERIVED)
 */
@Schema(description = "영상 개인정보 메타(수동값 우선, 없으면 비식별 기본상수 프리필)")
public record VideoPrivacyMetaResponse(
        @Schema(description = "영상 PK") Long rawSn,
        @Schema(description = "익명정보 포함여부(Y/N)") String anonymity,
        @Schema(description = "가명정보 포함여부(Y/N)") String pseudonymity,
        @Schema(description = "개인정보 포함여부(Y/N)") String privacyIncluded,
        @Schema(description = "익명 출처(MANUAL/DERIVED)") String anonymitySource,
        @Schema(description = "가명 출처(MANUAL/DERIVED)") String pseudonymitySource,
        @Schema(description = "개인정보 포함 출처(MANUAL/DERIVED)") String privacyIncludedSource
) {

    /** 검수자/작업자가 수동 저장한 값. */
    public static final String SOURCE_MANUAL = "MANUAL";
    /** 저장값이 없어 비식별 기본상수로 프리필한 값. */
    public static final String SOURCE_DERIVED = "DERIVED";
}
