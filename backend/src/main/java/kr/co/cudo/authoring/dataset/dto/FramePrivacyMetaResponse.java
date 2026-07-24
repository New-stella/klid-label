package kr.co.cudo.authoring.dataset.dto;

/**
 * Phase 3 — 프레임 개인정보 메타(익명/가명/개인정보 포함여부) 조회·저장 응답.
 *
 * <p>각 필드는 <b>수동 저장값 우선, 미저장 시 파생값(프리필)</b>로 계산된 <b>유효값</b>이다(effective value).
 * 프리필 파생 원천(현행 로직): pseudonymity=prvcTypeCd==PSDO, privacyIncluded=prvcYn,
 * anonymity=영상 개인정보 유형(ANONY) 파생.
 *
 * <p><b>anonymity 주의(★#1)</b>: 저장한 anonymity 는 화면 표시·기록(라벨러 판단)용이며 학습데이터 export 의
 * {@code image.anonymity}/{@code video.anonymity} 는 산출 종류(ExportKind: 원본=N/비식별=Y)로 결정되어
 * <b>이 수동값이 export 를 덮지 않는다</b>. pseudonymity/privacyIncluded 만 export 에 수동 우선 반영된다.
 *
 * @param srcSn           프레임 PK
 * @param anonymity       익명여부 유효값(Y/N, 미판정 시 파생) — export 미반영(표시·기록용)
 * @param pseudonymity    가명여부 유효값(Y/N) — export 수동 우선 반영
 * @param privacyIncluded 개인정보 포함여부 유효값(Y/N) — export 수동 우선 반영
 */
public record FramePrivacyMetaResponse(
        Long srcSn,
        String anonymity,
        String pseudonymity,
        String privacyIncluded
) {
}
