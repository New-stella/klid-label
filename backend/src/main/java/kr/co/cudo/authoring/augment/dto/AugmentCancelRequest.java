package kr.co.cudo.authoring.augment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Size;

/**
 * 증강 취소 요청 바디 — {@code POST /v1/augments/{id}/cancel}.
 *
 * <h3>★ 필드는 {@code reason} 하나뿐이다 (S8 — Mass Assignment, CWE-915 / OWASP API3:2023)</h3>
 * <p>{@code localTerminal}/{@code force}/{@code status} 같은 필드를 받으면 <b>요청 바디로 종결 판정을
 * 조작</b>해 이미 종결된 job 에 재취소를 유발할 수 있다({@code AugmentCancelCommand.localTerminal} 은
 * 클라이언트가 외부 호출을 개시할지 정하는 값이라 특히 위험하다). 그 판정은 전부 <b>서버가 DB 를
 * 잠그고 직접</b> 내린다({@code AugmentCancelTxService}).
 *
 * <p>{@link JsonIgnoreProperties}{@code (ignoreUnknown = true)} 로 알 수 없는 필드는 조용히 무시한다
 * (Spring Boot 기본값과 같은 방향이지만, 전역 설정이 바뀌어도 이 DTO 의 계약이 흔들리지 않도록
 * 명시한다 — 400 이 아니라 무시가 맞다. 미지 필드를 오류로 만들면 FE 버전 차이로 취소가 막힌다).
 *
 * <p>본문 자체가 optional 이다({@code required = false}) — 사유 없는 취소가 정상 동선이다.
 *
 * @param reason 취소 사유(선택, ≤500 — 외부 계약 §4.6 {@code reason} 상한과 동일).
 *               원문은 외부로 중계되고 로그에는 <b>길이만</b> 남는다(CWE-117/209).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AugmentCancelRequest(
        @Size(max = 500, message = "취소 사유는 최대 500자까지 입력 가능합니다.")
        String reason
) {
}
