package kr.co.cudo.authoring.portal.dto;

import java.util.Map;

/**
 * 포털 이벤트 어노테이션 Load·저장 응답.
 *
 * <p>본문은 <b>내부 원장과 같은 구조체를 그대로</b> 담는다. 키별로 펴지 않는 이유는 산출 문서와
 * 모양이 갈리면 내보낼 때마다 재조립이 필요하고 중첩 구조가 무너지기 때문이다.
 *
 * @param rawSn      영상 PK
 * @param annotation 이벤트 어노테이션 본문. 없으면 {@code null}(지어내지 않는다)
 * @param overridden 본인 오버레이가 원본을 가린 값인지. 본인이 올린 자산은 항상 거짓
 * @design API-236
 * @design API-237
 */
public record PortalEventAnnotationResponse(Long rawSn, Map<String, Object> annotation,
                                            boolean overridden) {
}
