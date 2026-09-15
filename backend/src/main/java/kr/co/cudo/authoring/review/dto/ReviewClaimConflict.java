package kr.co.cudo.authoring.review.dto;

/**
 * 검수 시작이 <b>남의 점유</b>로 거절될 때 오류 응답 본문에 함께 나가는 정보.
 *
 * <p>싣는 것은 <b>표시 이름 하나</b>다. 사용자 식별자·역할·소속은 요청자가 알 필요가 없고, 거절
 * 응답을 조직도 조회 창구로 만들면 안 된다(CWE-359).
 *
 * <p>같은 409 라도 사유가 셋이다 — 남의 점유 / 동시 시작 경합 / 받아들일 수 없는 상태. 이 본문이
 * 채워지는 것은 <b>첫 번째</b>뿐이고 나머지 둘은 본문 없이 문구로만 구분된다.
 *
 * @design API-013
 */
public record ReviewClaimConflict(String reviewingUserName) {
}
