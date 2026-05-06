package kr.co.cudo.authoring.user.repository.dto;

public record WorkerWithTaskCount(
        Long userNo,
        String userId,
        String userNm,
        String userEmail,
        Long activeTaskCount
) {
}
