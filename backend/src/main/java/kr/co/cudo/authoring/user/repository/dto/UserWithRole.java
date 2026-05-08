package kr.co.cudo.authoring.user.repository.dto;

import kr.co.cudo.authoring.user.entity.MngAcctUser;

/**
 * 사용자 마스터 + 권한 코드 단일 쿼리 결과 (N+1 방지).
 * MNG_ACCT_USER_AUTHRT 가 여러 건일 경우 우선순위 정렬(REVIEWER > WORKER > PORTAL_USER) 후 첫 행을 사용한다.
 */
public record UserWithRole(MngAcctUser user, String authrtCd) {
}
