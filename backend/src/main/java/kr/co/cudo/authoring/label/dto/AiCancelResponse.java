package kr.co.cudo.authoring.label.dto;

/**
 * AI 추론 취소 결과.
 *
 * @param cancelled 실제로 진행 중인 추론을 끊었으면 {@code true}.
 *                  <b>이미 끝났거나·이미 취소했거나·다른 노드에서 처리 중</b>이면 {@code false} 다 —
 *                  셋을 구분해 알려주지 않는다(응답이 «그 요청이 살아 있는가» 를 알려주는 오라클이
 *                  되지 않게 한다, CWE-209). 화면 입장에서는 어느 쪽이든 «더 기다릴 필요 없음» 으로
 *                  같으므로 구분이 필요하지도 않다.
 */
public record AiCancelResponse(boolean cancelled) {
}
