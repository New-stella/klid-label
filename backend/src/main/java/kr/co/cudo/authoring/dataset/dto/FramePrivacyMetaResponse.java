package kr.co.cudo.authoring.dataset.dto;

/**
 * Phase 3 — 프레임 개인정보 메타(익명/가명/개인정보 포함여부) 조회·저장 응답.
 *
 * <p>각 필드는 <b>수동 저장값 우선, 미저장 시 파생값(프리필)</b>로 계산된 <b>유효값</b>이다(effective value).
 * 프리필 파생 원천(현행 로직): pseudonymity=prvcTypeCd==PSDO, privacyIncluded=prvcYn,
 * anonymity=영상 개인정보 유형(ANONY) 파생.
 *
 * <p><b>export 반영 범위(2026-08-03 확정)</b>: 저장한 3필드는 학습데이터 export JSON 의 <b>image 블록</b>에
 * 실리되 <b>비식별(deid) 산출물에만</b> 반영된다 — 원천(orgnl) 산출물은 비식별 처리 전이라 판정 자체를 하지
 * 않으므로 3필드가 모두 null 이다({@code ExportPrivacyPolicy}). 구 정책("ORIGINAL 만 수동 우선,
 * anonymity 는 export 미반영")은 폐기됐다. video 블록은 <b>영상 단위</b> 수동값
 * ({@code /v1/videos/&#123;rawSn&#125;/privacy-meta})을 읽는 별개 축이다.
 *
 * @param srcSn           프레임 PK
 * @param anonymity       익명여부 유효값(Y/N, 미판정 시 파생) — deid export image 블록에 반영
 * @param pseudonymity    가명여부 유효값(Y/N) — deid export image 블록에 반영
 * @param privacyIncluded 개인정보 포함여부 유효값(Y/N) — deid export image 블록에 반영
 */
public record FramePrivacyMetaResponse(
        Long srcSn,
        String anonymity,
        String pseudonymity,
        String privacyIncluded
) {
}
