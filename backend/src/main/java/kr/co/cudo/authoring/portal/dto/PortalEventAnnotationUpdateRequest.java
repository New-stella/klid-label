package kr.co.cudo.authoring.portal.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 포털 이벤트 어노테이션 저장 요청.
 *
 * <p>본문은 <b>객체</b>여야 한다 — 배열·스칼라는 Jackson 역직렬화에서 걸려 400 이 된다.
 * 구조를 강제하지 않는 이유는 내부 원장이 그렇기 때문이다(포맷이 바뀌어도 마이그레이션 없이 흡수).
 *
 * @param annotation 이벤트 어노테이션 본문
 * @design API-237
 */
public record PortalEventAnnotationUpdateRequest(
        @NotNull(message = "annotation 은 필수입니다.") Map<String, Object> annotation
) {
}
