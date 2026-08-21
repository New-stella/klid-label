package kr.co.cudo.authoring.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.transfer.ImportSourcePolicy;

/**
 * 산출물 폴더 검사 요청.
 *
 * <h3>경로를 주소줄이 아니라 본문으로 받는다</h3>
 * <p>경로에는 구분자와 공백이 섞이고, 주소줄에 실으면 접근 기록과 중간 경유지에 그대로 남는다.
 * 이 요청은 아무것도 저장하지 않으므로 여러 번 보내도 결과가 같다(POST 를 쓰지만 부작용이 없다).
 *
 * <p>길이 상한은 여기서 <b>1차</b>로만 거른다 — 실제 판정은 {@link ImportSourcePolicy} 가 하며,
 * 두 곳이 같은 상한 상수를 본다.
 *
 * @param folderPath 검사할 산출물 폴더의 위치(필수)
 * @param videoPath  원본 영상 파일의 위치. 산출물에 영상이 없으면 비워 둔다
 * @design API-205
 */
@Schema(description = "외부 산출물 폴더 검사 요청")
public record ImportScanRequest(

        @Schema(description = "검사할 산출물 폴더의 위치", example = "/nas-storage/handover/00000073")
        @NotBlank(message = "산출물 폴더 경로는 필수입니다.")
        @Size(max = ImportSourcePolicy.FOLDER_PATH_MAX, message = "산출물 폴더 경로가 허용 길이를 넘습니다.")
        String folderPath,

        @Schema(description = "원본 영상 파일의 위치(선택)")
        @Size(max = ImportSourcePolicy.VIDEO_PATH_MAX, message = "원본 영상 경로가 허용 길이를 넘습니다.")
        String videoPath) {
}
