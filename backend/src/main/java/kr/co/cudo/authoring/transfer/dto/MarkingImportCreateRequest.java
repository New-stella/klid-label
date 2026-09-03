package kr.co.cudo.authoring.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.transfer.ImportSourcePolicy;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 마킹 산출물 일괄 적재 요청 (API-217).
 *
 * <h3>사람이 지정하는 값만 담는다</h3>
 * <p>마킹 문서와 영상 파일 이름에서 얻을 수 있는 것은 요청에 두지 않는다. 특히 <b>영상 식별자는
 * 입력받지 않는다</b> — 영상 파일 이름에서 얻으므로 사람이 지정하면 파일과 어긋난 값이 들어온다.
 *
 * <h3>비고 항목을 이벤트 유형으로 쓰지 않는다</h3>
 * <p>마킹 문서의 비고는 사람이 적은 일반 문장이라 유형 코드가 아니다. 그래서 이벤트 유형은 여기서
 * <b>사람이 지정</b>하며, 짐작으로 채우지 않는다.
 *
 * <h3>형식을 여기서도 보고 적재 직전에도 본다</h3>
 * <p>여기서 걸러 주면 사람이 곧바로 알 수 있고, 적재 직전 판정({@code MarkingImportIngestValidator})은
 * 화면을 거치지 않는 호출에도 같은 규칙이 서게 한다. 두 겹인 것은 중복이 아니라 <b>서버가 화면을
 * 신뢰하지 않는다</b>는 원칙의 결과다.
 *
 * @param folderPath 적재할 폴더의 위치 — 검사에 쓴 것과 같은 값이다
 * @param meta       항목마다 같은 값으로 붙는 지정값
 * @param targets    적재할 마킹 문서의 <b>이름</b> 목록. 비우면 검사에서 적재할 수 있다고 나온 항목을
 *                   모두 적재한다. 여기 담긴 이름이라도 적재할 수 없는 상태면 건너뛴다
 * @design DOMAIN-017
 * @design API-217
 * @design DFEAT-060
 */
@Schema(description = "마킹 산출물 일괄 적재 요청")
public record MarkingImportCreateRequest(

        @Schema(description = "적재할 폴더의 위치", example = "/nas-storage/handover/marking-20260706")
        @NotBlank(message = "폴더 경로는 필수입니다.")
        @Size(max = ImportSourcePolicy.FOLDER_PATH_MAX, message = "폴더 경로가 허용 길이를 넘습니다.")
        String folderPath,

        @Schema(description = "항목마다 같은 값으로 붙는 지정값")
        @NotNull(message = "지정값은 필수입니다.")
        @Valid
        Meta meta,

        @Schema(description = "적재할 마킹 문서 이름 목록 (비우면 적재 가능한 항목 전부)")
        @Size(max = TARGETS_MAX, message = "한 번에 지정할 수 있는 항목 수를 넘습니다.")
        List<String> targets
) {

    /**
     * 한 요청이 이름으로 지정할 수 있는 항목 수 상한 (CWE-770).
     *
     * <p>목록은 이름을 그대로 비교하는 데 쓰이므로, 상한이 없으면 요청 하나로 임의 크기의 문자열
     * 집합을 서버 메모리에 올릴 수 있다.
     */
    public static final int TARGETS_MAX = 5_000;

    /**
     * 마킹 문서와 영상 파일 이름에서 얻을 수 없어 <b>사람이 지정하는</b> 값.
     *
     * @param eventTypeCd 이벤트 유형 코드. ⚠ 마킹 문서의 비고를 파싱한 값이 아니다
     * @param localGovCd  지자체 코드
     * @param cctvId      카메라 식별자 — 한 폴더에 여러 카메라가 섞여 있으면 폴더를 나눠 따로 올린다
     * @param prvcTypeCd  개인정보 유형. 「미상」은 선택지에 없다 — 사람이 직접 고르므로 모른다고
     *                    답할 자리가 아니고, 열어 두면 화면이 값을 안 보냈을 때 조용히 미상으로 적재된다
     * @param capturedAt  촬영 시각(선택). 마킹 문서에 없으므로 지정하지 않으면 비워 둔다 — 대용값 금지
     */
    @Schema(description = "항목마다 같은 값으로 붙는 지정값")
    public record Meta(

            @Schema(description = "이벤트 유형 코드", example = "EV01000101")
            @NotBlank(message = "이벤트 유형 코드는 필수입니다.")
            @Pattern(regexp = "^EV[0-9]{8}$", message = "이벤트 유형 코드 형식이 올바르지 않습니다.")
            String eventTypeCd,

            @Schema(description = "지자체 코드", example = "4113500000")
            @NotBlank(message = "지자체 코드는 필수입니다.")
            @Pattern(regexp = "^[0-9]{1,10}$", message = "지자체 코드 형식이 올바르지 않습니다.")
            String localGovCd,

            @Schema(description = "카메라 식별자", example = "CCTV_0001")
            @NotBlank(message = "카메라 식별자는 필수입니다.")
            @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "카메라 식별자 형식이 올바르지 않습니다.")
            String cctvId,

            @Schema(description = "개인정보 유형", allowableValues = {"ANONY", "PRVC", "PSDO"})
            @NotBlank(message = "개인정보 유형은 필수입니다.")
            @Pattern(regexp = "^(ANONY|PRVC|PSDO)$", message = "개인정보 유형이 올바르지 않습니다.")
            String prvcTypeCd,

            @Schema(description = "촬영 시각(선택)")
            LocalDateTime capturedAt
    ) {
    }
}
