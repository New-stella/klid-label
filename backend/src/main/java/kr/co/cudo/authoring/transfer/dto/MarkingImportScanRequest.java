package kr.co.cudo.authoring.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.transfer.ImportSourcePolicy;

/**
 * 마킹 산출물 폴더 검사 요청 (API-216).
 *
 * <h3>★ 라벨링 완료 갈래의 요청에 종류 항목을 더하지 않고 <b>따로</b> 만든다</h3>
 * <p>두 갈래는 방향이 반대다 — 그쪽은 라벨링이 끝난 결과를 받아 검수만 하고, 이쪽은 시작점만 받아
 * 앞 단계를 전부 밟는다. ADR-053 이 <b>계약을 합치지 않으며 화면에서만 갈래를 고른다</b>고 정했다.
 * 한 요청에 종류 항목을 두면 그 항목의 값에 따라 나머지 항목의 필수 여부가 갈리고, 어느 조합이
 * 유효한지가 계약이 아니라 코드에만 남는다.
 *
 * @param folderPath 훑을 폴더의 위치. 허용된 저장소 범위 밖이면 거부한다(판정은
 *                   {@link ImportSourcePolicy} 한 곳)
 * @design DOMAIN-017
 * @design ADR-053
 * @design API-216
 */
@Schema(description = "마킹 산출물 폴더 검사 요청")
public record MarkingImportScanRequest(

        @Schema(description = "훑을 폴더의 위치", example = "/nas-storage/handover/marking-20260706")
        @NotBlank(message = "폴더 경로는 필수입니다.")
        @Size(max = ImportSourcePolicy.FOLDER_PATH_MAX, message = "폴더 경로가 허용 길이를 넘습니다.")
        String folderPath
) {
}
