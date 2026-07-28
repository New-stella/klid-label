package kr.co.cudo.authoring.augment.dto;

/**
 * 증강/파생 결과 화면의 프레임 쌍 1건 — FE {@code AugmentFramePair} 계약 정합.
 *
 * <p><b>파일 경로를 담지 않는다(CWE-209/359)</b>. 좌/우 모두 <b>비식별</b> 이미지 서빙 API
 * ({@code GET /v1/frames/{srcSn}/deid-image}) 경로만 반환하며, 실제 서빙 시점에 그 엔드포인트가
 * 인가·비식별 신고 게이트·경로 검증을 다시 수행한다.
 *
 * @param srcSn        선택 단위 = <b>파생</b> 프레임 PK
 * @param frameNo      프레임 순번(FRM_NO) — 부모/파생 조인 키
 * @param originalUrl  좌: 부모(원본 영상)의 <b>비식별</b> 프레임 이미지 API 경로
 * @param augmentedUrl 우: 파생(리스케일) 프레임 이미지 API 경로. 산출 실패 시 null
 */
public record AugmentFramePairResponse(
        Long srcSn,
        Long frameNo,
        String originalUrl,
        String augmentedUrl
) {
}
