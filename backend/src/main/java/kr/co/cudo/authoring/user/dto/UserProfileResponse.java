package kr.co.cudo.authoring.user.dto;

import kr.co.cudo.authoring.user.entity.LsAcntUser;

public record UserProfileResponse(
        Long userNo,
        String userId,
        String userNm,
        String userEmail,
        String role,
        String channel
) {
    public static UserProfileResponse of(LsAcntUser user, String role, String channel) {
        return new UserProfileResponse(
                user.getUserNo(),
                user.getUserId(),
                user.getUserNm(),
                user.getUserEmlAddr(),
                role,
                channel
        );
    }
}
