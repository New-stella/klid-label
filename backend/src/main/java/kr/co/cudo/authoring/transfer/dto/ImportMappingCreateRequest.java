package kr.co.cudo.authoring.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.transfer.entity.LsOtsdCtgryMpng;

import java.util.List;

/**
 * 분류 대응 확정 요청 — 여러 건을 한 번에 보낼 수 있다.
 *
 * <h3>사람이 고른 것만 저장된다</h3>
 * <p>이 요청에는 "추천대로 알아서 확정" 같은 통로가 없다. 연결 대상은 항상 명시되어야 하며, 서버가
 * 이름 유사도로 대신 채우지 않는다 — 짐작으로 연결하면 다른 분류로 저장되고 저장된 뒤에는 어느 것이
 * 짐작이었는지 구분할 수 없다.
 *
 * <h3>덮어쓰기는 의도를 함께 보내야 한다</h3>
 * <p>같은 종류·같은 분류에 대한 대응은 하나뿐이다. 이미 있는 것을 바꾸려면 {@code overwrite} 를
 * 함께 보내야 하고, 그러지 않으면 거부한다(이미 그 대응으로 적재된 결과가 있을 수 있다).
 *
 * @design API-210
 */
@Schema(description = "분류 대응 확정 요청")
public record ImportMappingCreateRequest(

        @Schema(description = "확정할 대응 목록")
        @NotEmpty(message = "확정할 대응이 없습니다.")
        @Size(max = ImportMappingCreateRequest.MAX_ITEMS,
                message = "한 번에 보낼 수 있는 대응 수를 넘었습니다.")
        @Valid List<Item> items,

        @Schema(description = "이미 있는 대응을 바꾸려는 의도인지 여부", defaultValue = "false")
        Boolean overwrite) {

    /**
     * 한 요청에 담을 수 있는 대응 수 상한 — 무제한 본문으로 트랜잭션이 길어지는 것을 막는다(CWE-770).
     * 산출물 하나가 쓰는 분류 수는 열 단위이므로 실사용을 제약하지 않는다.
     */
    public static final int MAX_ITEMS = 200;

    /** 덮어쓰기 의도(미지정이면 거짓 — 기존 대응을 조용히 바꾸지 않는다). */
    public boolean overwriteOrDefault() {
        return Boolean.TRUE.equals(overwrite);
    }

    /**
     * 대응 1건.
     *
     * @param kind         {@code LABEL} 또는 {@code EVNT_TYPE}
     * @param externalCode 외부 산출물이 준 분류 식별 문자열
     * @param externalName 외부 산출물이 준 표시 이름(선택) — 나중에 어떤 근거로 연결했는지 되짚는 데 쓴다
     * @param labelId      연결할 저작도구 라벨. 종류가 라벨일 때만 채운다
     * @param evntTypeCd   연결할 이벤트 유형 코드. 종류가 이벤트 유형일 때만 채운다
     */
    @Schema(description = "확정할 대응 1건")
    public record Item(

            @Schema(description = "대응 종류", allowableValues = {"LABEL", "EVNT_TYPE"})
            @NotBlank(message = "대응 종류는 필수입니다.")
            @Pattern(regexp = "LABEL|EVNT_TYPE", message = "대응 종류가 올바르지 않습니다.")
            String kind,

            @Schema(description = "외부 산출물이 준 분류 식별 문자열")
            @NotBlank(message = "외부 분류 식별 문자열은 필수입니다.")
            @Size(max = LsOtsdCtgryMpng.OTSD_CTGRY_CD_MAX,
                    message = "외부 분류 식별 문자열이 허용 길이를 넘습니다.")
            String externalCode,

            @Schema(description = "외부 산출물이 준 표시 이름")
            @Size(max = LsOtsdCtgryMpng.OTSD_CTGRY_NM_MAX,
                    message = "외부 분류 표시 이름이 허용 길이를 넘습니다.")
            String externalName,

            @Schema(description = "연결할 저작도구 라벨(종류가 LABEL 일 때)")
            Long labelId,

            @Schema(description = "연결할 이벤트 유형 코드(종류가 EVNT_TYPE 일 때)")
            @Size(max = 20, message = "이벤트 유형 코드가 허용 길이를 넘습니다.")
            String evntTypeCd) {

        /** 라벨 축인가. */
        public boolean isLabelKind() {
            return LsOtsdCtgryMpng.MPNG_KND_LABEL.equals(kind);
        }
    }
}
