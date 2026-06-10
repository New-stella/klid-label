package kr.co.cudo.authoring.common.client.dto;

/**
 * KPST {@code POST /delete_project_id} 응답 DTO.
 *
 * <p>규격: {@code { "result":"success", "message":"Project '279' deleted successfully" }}
 * (22-deid-solution-api.md §22.3.6). {@code result} 가 {@code success} 가 아니면 실패로 처리한다.
 */
public record KpstDeleteResponse(
        String result,
        String message
) {
}
