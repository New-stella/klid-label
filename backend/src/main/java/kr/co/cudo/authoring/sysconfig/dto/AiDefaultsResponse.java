package kr.co.cudo.authoring.sysconfig.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * AI 정밀도 기본값 — 라벨링 화면의 AI 도구 슬라이더 초기값 (API-193).
 *
 * <p>노출은 <b>화면이 실제로 쓰는 값</b>뿐이다. 마지막 수정자·수정일시 같은 운영 메타는 담지 않는다 —
 * 이 응답은 검수자 전용이 아니라 작업자도 읽으므로, 운영 파라미터 전량과 검수자 계정 식별자를
 * 함께 실어 보내던 {@code GET /v1/manage/configs} 를 그대로 열어주지 않기 위해 신설됐다.
 *
 * <p>저장값이 없거나 숫자로 해석되지 않는 항목은 {@code null} 로 두어 직렬화에서 <b>생략</b>된다
 * ({@code NON_NULL}). 화면이 자체 기본값으로 대체하므로 서버가 상수를 지어내지 않는다.
 *
 * <h3>대기 예산은 <b>생략되지 않는다</b></h3>
 * <p>위 두 값과 달리 {@code waitBudgets} 는 저장값이 없어도 서버가 <b>도출</b>해 항상 싣는다. 이 값은
 * 슬라이더 프리필이 아니라 <b>요청 제한시간의 근거</b>라, 없으면 화면이 자기 상수로 되돌아가고
 * 그 순간 "서버 예산이 바뀌면 조용히 어긋난다"는 원래 결함이 되살아나기 때문이다.
 *
 * @param confThreshold     인식 민감도 초기값(정수 백분율 — 화면에서 /100 변환)
 * @param simplifyTolerance 경계 세밀함 초기값(실수, Douglas-Peucker epsilon px)
 * @param waitBudgets       온디맨드 AI 추론 4종의 대기 예산(서버 도출 — 항상 존재)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiDefaultsResponse(
        Integer confThreshold,
        Double simplifyTolerance,
        AiWaitBudgets waitBudgets
) {
}
