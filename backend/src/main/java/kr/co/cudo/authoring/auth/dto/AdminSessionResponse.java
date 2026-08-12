package kr.co.cudo.authoring.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 관리자 단기 유효창 개시 응답 (R11).
 *
 * <p>{@code expiresAt} 은 <b>화면 표시용</b>이다. 남은 시간을 보여주기 위한 값일 뿐,
 * <b>유효성 판정은 서버가 소유</b>한다 — 서버는 저장 요청마다 토큰 서명과 만료를 다시 본다.
 * 클라이언트가 이 값을 늘려도 서버 판정은 달라지지 않는다(만료 시각이 서명 대상 안에 있다).
 */
@Schema(description = "관리자 단기 유효창 개시 응답")
public record AdminSessionResponse(
        @Schema(description = "관리자 세션 토큰 — 연동 주소 저장 시 X-Admin-Session 헤더로 전송한다.")
        String token,

        @Schema(description = "만료 시각(UTC). 화면의 남은 시간 표시에 쓰인다.")
        Instant expiresAt
) {
}
