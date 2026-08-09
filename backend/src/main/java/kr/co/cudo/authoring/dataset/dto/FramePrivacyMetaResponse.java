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
 * <p><b>★ 출처 병기({@code *Source}) — 영상 축과 동일 계약</b>: 각 값이 사람이 직접 고른 저장값인지
 * ({@code MANUAL}) 프리필된 기본상수인지({@code DERIVED}) 항목별로 함께 내려준다. 값만 내려주면
 * 화면이 프리필 상수를 그대로 PUT 으로 되돌려 보낼 때 <b>기본상수가 사람의 판정으로 승격</b>되는데,
 * 서버는 그 값이 사용자가 고른 것인지 프리필을 되돌려받은 것인지 구분할 수 없어 막지 못한다.
 * 영상 축({@link VideoPrivacyMetaResponse})이 이미 같은 이유로 출처를 병기하고 있었고, 프레임 축만
 * 빠져 있으면 <b>같은 승격 경로가 프레임 축에 그대로 남는다</b>.
 *
 * <p><b>주의(FE 계약)</b>: {@code DERIVED} 프리필을 그대로 PUT 으로 되돌려 보내면 상수가 수동값으로
 * 승격된다. 사용자가 직접 고르지 않은 필드는 {@code null} 로 전송해야 한다(영상 축과 동일).
 *
 * <p><b>출처 어휘는 복제하지 않는다</b> — {@code MANUAL}/{@code DERIVED} 문자열의 단일 원천은
 * {@link VideoPrivacyMetaResponse#SOURCE_MANUAL}/{@link VideoPrivacyMetaResponse#SOURCE_DERIVED} 이며
 * 두 축이 같은 상수를 참조한다. 여기서 리터럴을 다시 선언하면 한쪽만 바뀌어 어긋난다(프리필 상수를
 * {@code ExportPrivacyPolicy} 한 곳에서만 읽는 것과 같은 원칙).
 *
 * @param srcSn                 프레임 PK
 * @param anonymity             익명여부 유효값(Y/N, 미판정 시 파생) — deid export image 블록에 반영
 * @param pseudonymity          가명여부 유효값(Y/N) — deid export image 블록에 반영
 * @param privacyIncluded       개인정보 포함여부 유효값(Y/N) — deid export image 블록에 반영
 * @param anonymitySource       익명 출처(MANUAL/DERIVED)
 * @param pseudonymitySource    가명 출처(MANUAL/DERIVED)
 * @param privacyIncludedSource 개인정보 포함 출처(MANUAL/DERIVED)
 */
public record FramePrivacyMetaResponse(
        Long srcSn,
        String anonymity,
        String pseudonymity,
        String privacyIncluded,
        String anonymitySource,
        String pseudonymitySource,
        String privacyIncludedSource
) {
}
