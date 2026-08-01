package kr.co.cudo.authoring.dev.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * [개발/검수 전용] 영상 업로드 + <b>관제 테이블 INSERT → 실제 픽업 배치</b> 트리거 요청 (Phase 3).
 *
 * <p>Phase 3 이전에는 이 요청이 {@code LS_DATA_RAW} 를 직접 만들었다. 이제는 <b>관제 공유 테이블</b>
 * ({@code MNG_CLIP_MASTER} / {@code MNG_CLIP_EVNT_LST})에 dev 전용 행을 넣고, 운영 픽업 배치
 * ({@code ControlTrainingVideoScanJob → TrainingVideoIngestService.scanAndIngest()})가 그 행을 집어
 * 적재하게 한다. 따라서 이 DTO 의 필드는 <b>관제 두 테이블의 컬럼</b>과 1:1 대응한다.
 *
 * <p><b>신규 필드는 전부 optional</b> — 미전송 시 서버가 기본값을 채운다(하위호환). 기존 6필드
 * ({@code vmsClipId}·{@code cctvId}·{@code eventTypeCd}·{@code localGovCd}·{@code prvcTypeCd}·
 * {@code capturedAt})의 <b>이름은 유지</b>한다.
 *
 * <p><b>⚠ {@code wthrCd}(날씨)는 받지 않는다</b> (2026-07-31 사용자 확정) — 날씨는 관제에서 받지 않고
 * 저작도구 수동 입력({@code EnvironmentMetaService})이 유일한 원천이다. 필드를 두면 관제 경유로 추정값이
 * 새는 경로가 생긴다.
 *
 * <p><b>⚠ {@code vdoLenSec} 단위</b> — 이 요청은 <b>초</b>를 받는다. 관제 {@code VDO_LEN_SEC} 의 실측
 * 단위는 <b>ms</b> 라 서비스가 관제 테이블에 넣을 때 ×1000 하고, 적재({@code TrainingVideoIngestTx})가
 * 다시 ÷1000 해 초로 복원한다. 미전송 시 ffprobe 실측(초)을 사용한다.
 *
 * <p>보안 가드(서비스 레이어에서 2차 검증):
 * <ul>
 *   <li>{@code vmsClipId}(=관제 {@code CLIP_ID}) 는 <b>파일명이 된다</b> — {@code ^CLP-[A-Za-z0-9]{1,26}$}
 *       allowlist 로 경로 순회 문자(`/`, `..`)와 길이 초과를 1차 차단한다(CWE-22).</li>
 *   <li>모든 문자열 필드에 길이/패턴 상한 — 관제 컬럼 길이 초과 INSERT 를 사전 거절한다(CWE-20).</li>
 *   <li>관제 INSERT 는 네이티브 <b>파라미터 바인딩</b>만 사용한다(CWE-89).</li>
 * </ul>
 */
@Schema(description = "[개발/검수 전용] 영상 업로드 + 관제 테이블 INSERT → 실제 픽업 배치 트리거 메타데이터")
public record AutolabelTestRequest(
        @Schema(description = """
                관제 CLIP_ID (LS_DATA_RAW.VMS_CLIP_ID 멱등키). 미전송 시 서버가 `CLP-`+UUID(30자 이내)로 생성한다.
                저장 파일명이 되므로 `CLP-` 접두 + 영문/숫자만 허용한다(경로 순회 차단).""",
                example = "CLP-a1b2c3d4e5f6")
        @Pattern(regexp = "^CLP-[A-Za-z0-9]{1,26}$",
                message = "vmsClipId 는 CLP- 접두 + 영문/숫자 1~26자(총 30자 이내)만 허용됩니다.")
        String vmsClipId,

        @Schema(description = "VMS_CCTV_ID — MNG_RESOURCE_CCTV 에 등록되어 있어야 함",
                example = "CCTV-001", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "cctvId 는 필수입니다.")
        @Pattern(regexp = "^[A-Za-z0-9_-]{1,30}$", message = "cctvId 는 영문/숫자/-/_ 1~30자만 허용됩니다.")
        String cctvId,

        @Schema(description = """
                관제 마스터(MNG_EX_EVNT_TYPE) 상세 이벤트 코드 — 형식 EVxxxxxxxx (EV + 숫자 8자리).
                MNG_CLIP_EVNT_LST 복합 PK 의 두 번째 컬럼(EVNT_TYPE_CD)으로 INSERT 된다.
                형식만 @Pattern 으로 1차 가드하고, 실제 관제 등록 여부는 서비스(EventTypeService)에서 검증한다(미등록 400).""",
                example = "EV02000201", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "eventTypeCd 는 필수입니다.")
        @Pattern(regexp = "^EV[0-9]{8}$",
                message = "이벤트 타입 코드 형식이 올바르지 않습니다 (EV + 숫자 8자리).")
        String eventTypeCd,

        @Schema(description = "지자체 코드 (관제 LCLGV_CD)", example = "1168000000",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "localGovCd 는 필수입니다.")
        @Pattern(regexp = "^[0-9]{1,10}$", message = "localGovCd 는 숫자 1~10자리만 허용됩니다.")
        String localGovCd,

        @Schema(description = """
                개인정보 유형 (관제 MNG_CLIP_EVNT_LST.PRVC_TYPE_CD). 미전송 시 null 로 INSERT 되며,
                적재 시 ControlClipMetaResolver 가 fail-closed 폴백(PRVC)을 적용한다.""",
                example = "ANONY")
        @Size(max = 20, message = "prvcTypeCd 는 20자 이하만 허용됩니다.")
        String prvcTypeCd,

        @Schema(description = "촬영 시각 (ISO-8601 Instant) — 관제 SHT_DT", example = "2024-05-01T12:00:00Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "capturedAt 는 필수입니다.")
        Instant capturedAt,

        @Schema(description = "관제 EVNT_ID (두 테이블 복합 PK 의 첫 컬럼). 미전송 시 서버가 생성한다.",
                example = "DEV-a1b2c3d4e5")
        @Pattern(regexp = "^[A-Za-z0-9_-]{1,50}$", message = "evntId 는 영문/숫자/-/_ 1~50자만 허용됩니다.")
        String evntId,

        @Schema(description = "관제 CLIP_TYPE_CD (복합 PK 의 둘째 컬럼). 미전송 시 ORIGINAL", example = "ORIGINAL")
        @Size(max = 20, message = "clipTypeCd 는 20자 이하만 허용됩니다.")
        String clipTypeCd,

        @Schema(description = "관제 FILE_NM. 미전송 시 업로드 원본 파일명(정규화)", example = "sample.mp4")
        @Size(max = 256, message = "fileNm 은 256자 이하만 허용됩니다.")
        String fileNm,

        @Schema(description = "관제 FILE_FMT. 미전송 시 업로드 파일 확장자", example = "mp4")
        @Size(max = 10, message = "fileFmt 는 10자 이하만 허용됩니다.")
        String fileFmt,

        @Schema(description = "관제 FILE_SZ(byte). 미전송 시 실제 업로드 파일 크기", example = "10485760")
        @PositiveOrZero(message = "fileSz 는 0 이상이어야 합니다.")
        Long fileSz,

        @Schema(description = """
                영상 길이(<b>초</b>). 미전송 시 ffprobe 실측값. 관제 VDO_LEN_SEC 실측 단위는 ms 라
                INSERT 시 ×1000 되고, 적재 시 ÷1000 으로 초 복원된다.""", example = "137")
        @PositiveOrZero(message = "vdoLenSec 는 0 이상이어야 합니다.")
        Integer vdoLenSec,

        @Schema(description = "관제 CLIP_STTS_CD. 미전송 시 mediainfo_complete", example = "mediainfo_complete")
        @Size(max = 20, message = "clipSttsCd 는 20자 이하만 허용됩니다.")
        String clipSttsCd,

        @Schema(description = "관제 JOB_DMND_YN(학습용 지정). 미전송 시 Y — 픽업 배치 대상이 되려면 Y 여야 한다.",
                example = "Y")
        @Pattern(regexp = "^[YN]$", message = "jobDmndYn 은 Y 또는 N 만 허용됩니다.")
        String jobDmndYn,

        @Schema(description = "관제 JOB_DMND_PRNMNT_YN(학습용 지정 예약). 미전송 시 N", example = "N")
        @Pattern(regexp = "^[YN]$", message = "jobDmndPrnmntYn 은 Y 또는 N 만 허용됩니다.")
        String jobDmndPrnmntYn,

        @Schema(description = "관제 CRT_TYPE — 0=중계서버 생성, 1=수동 생성. 미전송 시 0", example = "0")
        @Min(value = 0, message = "crtType 은 0 또는 1 만 허용됩니다.")
        @Max(value = 1, message = "crtType 은 0 또는 1 만 허용됩니다.")
        Integer crtType,

        @Schema(description = "관제 CRT_DT(생성 일자). 미전송 시 현재 시각", example = "2024-05-01T12:00:00Z")
        Instant crtDt,

        @Schema(description = "관제 ULD_CMPT_DT(업로드 완료 일시). 미전송 시 현재 시각",
                example = "2024-05-01T12:00:00Z")
        Instant uldCmptDt,

        @Schema(description = "관제 EVNT_NM(이벤트명). 미전송 시 null", example = "쓰러짐")
        @Size(max = 200, message = "evntNm 은 200자 이하만 허용됩니다.")
        String evntNm,

        @Schema(description = "관제 SESN_CD(계절 코드). 미전송 시 null", example = "봄")
        @Size(max = 10, message = "sesnCd 는 10자 이하만 허용됩니다.")
        String sesnCd,

        @Schema(description = "관제 HR_TYPE_CD(시간 유형 코드). 미전송 시 null", example = "DAY")
        @Size(max = 10, message = "hrTypeCd 는 10자 이하만 허용됩니다.")
        String hrTypeCd,

        @Schema(description = "관제 IDNTF_YN(비식별화 처리 여부). 미전송 시 null", example = "N")
        @Pattern(regexp = "^[YNF]$", message = "idntfYn 은 Y/N/F 만 허용됩니다.")
        String idntfYn,

        @Schema(description = "관제 CLCT_PATH(수집 경로). 미전송 시 null")
        @Size(max = 255, message = "clctPath 는 255자 이하만 허용됩니다.")
        String clctPath,

        @Schema(description = "관제 CLCT_SRC(수집 출처). 미전송 시 null")
        @Size(max = 255, message = "clctSrc 는 255자 이하만 허용됩니다.")
        String clctSrc
) {

    /**
     * 필수 6필드만 받는 축약 생성자 — 나머지 관제 컬럼은 서버 기본값으로 채워진다.
     * FE 현행 전송 형태(6필드)와 테스트 가독성을 위해 유지한다.
     */
    public AutolabelTestRequest(String vmsClipId, String cctvId, String eventTypeCd,
                                String localGovCd, String prvcTypeCd, Instant capturedAt) {
        this(vmsClipId, cctvId, eventTypeCd, localGovCd, prvcTypeCd, capturedAt,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }
}
