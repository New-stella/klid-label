package kr.co.cudo.authoring.external.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import kr.co.cudo.authoring.video.entity.LsDataRaw;

import java.time.LocalDateTime;

/**
 * Phase 4 — 외부 학습데이터 API 응답 영상 메타.
 *
 * <p>시스템 식별자(rawSn, vmsClipId, cctvId)와 영상 자체의 객관 메타만 노출한다.
 * 등록자(REG_USER_NO)·신고자·업로더 등 <b>사용자 식별 정보(CWE-359 Privacy Violation)는
 * 절대 포함하지 않는다</b>. record 컴포넌트 추가 시에도 동일 원칙을 유지할 것.
 */
@Schema(description = "외부 학습데이터 API 영상 메타 — 사용자 식별 정보 미포함")
public record VideoInfo(
        @Schema(description = "원시 영상 PK") Long rawSn,
        @Schema(description = "VMS 클립 식별자") String vmsClipId,
        @Schema(description = "CCTV 식별자") String cctvId,
        @Schema(description = "이벤트 유형 코드") String eventTypeCd,
        @Schema(description = "지자체 코드") String localGovCd,
        @Schema(description = "개인정보 유형 코드 (ANONY/PRVC/PSDO)") String privacyTypeCd,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @Schema(description = "촬영 시각 (서버 로컬타임)") LocalDateTime capturedAt,
        @Schema(description = "영상 길이(초)") Integer durationSec
) {
    public static VideoInfo from(LsDataRaw raw) {
        return new VideoInfo(
                raw.getRawSn(),
                raw.getVmsClipId(),
                raw.getVmsCctvId(),
                raw.getEvntTypeCd(),
                raw.getLclgvCd(),
                raw.getPrvcTypeCd(),
                raw.getCapturedAt(),
                raw.getDurationSec()
        );
    }
}
