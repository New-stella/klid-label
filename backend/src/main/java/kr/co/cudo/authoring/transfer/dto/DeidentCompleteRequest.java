package kr.co.cudo.authoring.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.transfer.ImportSourcePolicy;

/**
 * 비식별 완료 기록 요청(API-215).
 *
 * <p>이 요청은 비식별을 <b>수행하지 않는다</b>. 외부에서 이미 비식별한 산출물이 어디 있는지를 알리는
 * 것이고, 그 산출물이 실제로 존재하는지 확인한 뒤에만 기록이 성립한다.
 *
 * @param deidentifiedFolderPath 외부에서 비식별한 산출물이 놓인 폴더의 위치
 * @design API-215
 */
@Schema(description = "비식별 완료 기록 요청")
public record DeidentCompleteRequest(

        @Schema(description = "외부에서 비식별한 산출물이 놓인 폴더의 위치",
                example = "/nas-storage/handover/00000073-deid")
        @NotBlank(message = "비식별 산출물 폴더 경로는 필수입니다.")
        @Size(max = ImportSourcePolicy.FOLDER_PATH_MAX,
                message = "비식별 산출물 폴더 경로가 허용 길이를 넘습니다.")
        String deidentifiedFolderPath) {
}
