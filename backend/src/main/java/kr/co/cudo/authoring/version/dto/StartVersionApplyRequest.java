package kr.co.cudo.authoring.version.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 영상 단위 「시작 버전 선택」 요청 — 어느 산출 버전 상태에서 작업을 시작할지.
 *
 * <p>{@code versionNo} 는 {@code LS_DATASET_EXPORT.OUTPUT_VER_NO}(=관제가 픽업하는 산출 폴더
 * {@code v1}·{@code v2})와 같은 번호다. 그 영상에 <b>실재하는 번호인지</b>는 서비스가 대조하며,
 * 여기서는 형식(필수·1 이상)만 강제한다.
 *
 * @design D4
 * @req R6
 */
public record StartVersionApplyRequest(
        @NotNull @Min(1) Integer versionNo
) {
}
