package kr.co.cudo.authoring.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.transfer.ImportSourcePolicy;

import java.util.List;

/**
 * 외부 산출물 적재 요청(API-206).
 *
 * <h3>확인 기록은 차단 사유를 해제하지 않는다</h3>
 * <p>{@link #acknowledgedWarnings} 는 <b>사람이 무엇을 보고 진행했는지</b>를 남기는 감사 기록이며,
 * 적재 가능 여부의 판정은 하지 않는다. 판정은 검사 응답의 <b>적재 가능 여부 값</b>이 소유하고 서버가
 * 적재 시점에 같은 근거로 다시 계산한다. 알림 목록에는 적재를 막는 사유와 막지 않는 사유가 함께 담기므로,
 * 이 기록이 있어도 막는 사유가 남아 있으면 적재되지 않는다.
 *
 * <h3>비식별 지정은 필수이고 기본은 원본이다</h3>
 * <p>잘못 고르면 비식별되지 않은 화면이 학습데이터로 나가고 승인 이후에는 되돌릴 수단이 사실상 없다.
 * 그래서 값이 없으면 <b>원본</b>으로 본다(fail-closed) — 원본으로 보면 승인이 보류될 뿐이지만,
 * 비식별 완료로 잘못 보면 그 보류가 서지 않는다.
 *
 * @param folderPath          적재할 산출물 폴더의 위치(필수)
 * @param videoPath           원본 영상 파일의 위치. 비우면 프레임과 라벨만 적재한다
 * @param deidentified        이 산출물이 비식별이 끝난 것인지 여부
 * @param acknowledgedWarnings 검사에서 나온 알림 가운데 확인했음을 알리는 기록(감사용)
 * @design API-206
 * @design AC-046
 */
@Schema(description = "외부 산출물 적재 요청")
public record ImportCreateRequest(

        @Schema(description = "적재할 산출물 폴더의 위치", example = "/nas-storage/handover/00000073")
        @NotBlank(message = "산출물 폴더 경로는 필수입니다.")
        @Size(max = ImportSourcePolicy.FOLDER_PATH_MAX, message = "산출물 폴더 경로가 허용 길이를 넘습니다.")
        String folderPath,

        @Schema(description = "원본 영상 파일의 위치(선택)")
        @Size(max = ImportSourcePolicy.VIDEO_PATH_MAX, message = "원본 영상 경로가 허용 길이를 넘습니다.")
        String videoPath,

        @Schema(description = "이 산출물이 비식별이 끝난 것인지 여부. 비우면 원본으로 본다.")
        Boolean deidentified,

        @Schema(description = "확인한 알림 사유 코드 — 감사 기록일 뿐 차단 사유를 해제하지 않는다.")
        List<String> acknowledgedWarnings) {

    /** 비식별 지정 — 값이 없으면 <b>원본</b>이다(fail-closed). */
    public boolean deidentifiedOrDefault() {
        return Boolean.TRUE.equals(deidentified);
    }

    /** 확인 기록 — 없으면 빈 목록. */
    public List<String> acknowledgedOrEmpty() {
        return acknowledgedWarnings == null ? List.of() : acknowledgedWarnings;
    }
}
