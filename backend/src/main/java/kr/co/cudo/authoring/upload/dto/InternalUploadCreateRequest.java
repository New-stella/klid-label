package kr.co.cudo.authoring.upload.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 내부 업로드(REVIEWER TUS) 세션 생성 요청 — <b>관제 인입 29컬럼 재현용 JSON 바디</b> (Phase 3).
 *
 * <h3>왜 헤더가 아니라 JSON 바디인가</h3>
 * <p>구 구현은 TUS {@code Upload-Metadata} 헤더(base64, 상한 1KB)로 7키를 받았다. 이 Phase 의 목적은
 * <b>관제가 보내는 값 29종을 그대로 재현</b>하는 것인데, 관제일지({@code MNTR_CN VARCHAR(4000)}) 하나만
 * base64 로 실어도 1KB 를 넘긴다. 헤더 상한을 늘리는 대신 바디로 받는다.
 *
 * <p>TUS 1.0 의 creation 확장은 본래 <b>빈 바디</b>를 기대하지만, 우리는
 * {@code creation-with-upload}(POST 바디에 첫 청크를 싣는 확장)를 구현하지 않으므로 POST 바디는 비어
 * 있다 — 충돌하지 않는다. 그래서 {@code Tus-Extension} 에 {@code creation-with-upload} 를
 * <b>광고하지 않는다</b>(광고하면 표준 클라이언트가 첫 청크를 바디에 실어 보내 이 계약이 깨진다).
 * {@code Upload-Length} 는 TUS 헤더 그대로다.
 *
 * <h3>값 조달 — 입력 우선, 비운 키만 ffprobe (설계 §6-2)</h3>
 * <p>기술메타 12종({@code vdoLenSec}·{@code fps}·{@code frmeCnt}·{@code wdth}·{@code vrtc}·{@code resl}·
 * {@code asprtRt}·{@code vdoCdc}·{@code fileFmt}·{@code fileSz}·{@code bit}·{@code pxl})은 <b>선택</b>이다.
 * 채운 키는 그 값이 인입 행에 그대로 실리고, <b>비운 키만</b> 적재 후
 * {@code VideoMetaService}(관제 인입값 우선, 없는 키만 ffprobe — 폴백은 키 단위)가 채운다. 이 규칙은
 * 이미 존재하므로 여기서 재구현하지 않는다 — 인입 행에 값을 실어 보내면 그대로 성립한다.
 *
 * <h3>검증(CWE-20) — 길이·범위는 {@code LS_DATA_INGEST}(V147) DDL 과 1:1</h3>
 * <p>세션 생성 단에서 fail-fast 하지 않으면 <b>완료 시점 INSERT</b> 에서 값 초과로 500 이 나고 0바이트
 * 임시 파일이 남는다(구 {@code cctvId} 실사고와 동형). 특히 {@code vmsClipId} 는 <b>저장 파일명이
 * 되므로</b> 경로 문자·상위참조를 허용하지 않는다(CWE-22) — DDL 은 128자지만 파일명 allowlist
 * (영문·숫자·{@code _}·{@code -} 64자)가 더 좁으며 그쪽이 이긴다.
 *
 * @param fileName        표시용 원본 파일명 — <b>확장자만</b> 실제로 쓰인다(저장명은 {@code {clipId}.{ext}})
 * @param vmsClipId       VMS 클립 아이디 (인입 UK · 저장 파일명)
 * @param cctvId          VMS CCTV 아이디 ({@code VMS_CCTV_ID})
 * @param srcType         출처유형({@link LsDataIngest#UPLOAD_SRC_TYPES}) — 미지정 시
 *                        {@link LsDataIngest#SRC_TYPE_USER_ULD}. {@code AUGMENTED} 는 저작도구 파생
 *                        전용이라 인입 입력면에서 제외한다
 * @param lclgvCd         지방자치단체코드 — 관제 완료통지 페이로드 {@code lclgv_cd}(required)의 출처
 * @param shtDt           촬영일시
 * @param fileFmt         파일형식 — 미지정 시 파일 확장자
 * @param vdoCdc          영상코덱
 * @param fileSz          파일크기(바이트) — 미지정 시 {@code Upload-Length}
 * @param lclgvNm         지방자치단체명(지자체명)
 * @param vdoLenSec       영상길이(초)
 * @param fps             프레임재생속도
 * @param frmeCnt          프레임수
 * @param asprtRt         종횡비 표기(예 16:9)
 * @param wdth            영상 너비(px)
 * @param vrtc            영상 세로(px)
 * @param resl            해상도 표기(예 1920x1080)
 * @param bit             비트값 = 색심도 표기(예 24bit). 비트레이트가 아니다
 * @param pxl             화소 표기(예 4K)
 * @param wgs84Lat        WGS84 위도
 * @param wgs84Lot        WGS84 경도
 * @param ogCd            기관코드
 * @param cctvNm          CCTV명
 * @param cctvHgt         CCTV 설치 높이(m)
 * @param mainSurvPanAng  주감시방향값(도)
 * @param evntId          이벤트 아이디(예 ABA_0001) — 이벤트<b>유형</b>코드가 아니다
 * @param evntNm          이벤트명
 * @param mntrCn          관제일지 내용
 * @param vrfcEvntTypeCd  검증이벤트유형 — 외부 VLM 검증 API 의 {@code event_type}
 *                        ({@link LsDataIngest#VRFC_EVNT_TYPES} 6종). <b>선택</b>이며 미지정이면
 *                        인입 행에 null 로 남는다. 이벤트<b>유형</b>코드({@code EVNT_TYPE_CD})와
 *                        다른 값이라 서로 유도하지 않는다
 */
public record InternalUploadCreateRequest(

        @NotBlank(message = "파일명은 필수입니다.")
        @Size(max = 255, message = "파일명은 255자를 넘을 수 없습니다.")
        String fileName,

        @NotBlank(message = "영상 클립 ID 는 필수입니다.")
        @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$",
                message = "영상 클립 ID 는 영문·숫자·'_'·'-' 조합 1~64자만 허용됩니다.")
        String vmsClipId,

        @NotBlank(message = "CCTV ID 는 필수입니다.")
        @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$",
                message = "CCTV ID 는 영문·숫자·'_'·'-' 조합 1~64자만 허용됩니다.")
        String cctvId,

        String srcType,

        @NotBlank(message = "지자체코드는 필수입니다.")
        @Pattern(regexp = "^[0-9]{1,10}$", message = "지자체코드는 숫자 1~10자리만 허용됩니다.")
        String lclgvCd,

        LocalDateTime shtDt,

        // 빈 문자열은 <미지정>과 같게 본다 — 서비스가 "" 를 미지정으로 취급하는데 여기서만 400 을
        //   내면 FE 가 빈 입력을 그대로 보낼 때 이유 없는 400 이 된다(계약 통일).
        @Pattern(regexp = "^$|^[A-Za-z0-9]{1,20}$", message = "파일형식은 영문·숫자 1~20자만 허용됩니다.")
        String fileFmt,

        @Size(max = 20, message = "영상코덱은 20자를 넘을 수 없습니다.")
        String vdoCdc,

        @PositiveOrZero(message = "파일크기는 0 이상이어야 합니다.")
        Long fileSz,

        // ★ 상한은 LS_DATA_INGEST.LCLGV_NM(V172 — 표준도메인 명V100)과 1:1 이어야 한다. 200 으로
        //   두면 101~200 자가 검증을 통과했다가 완료 시점 INSERT 에서 초과로 500 이 난다(이 DTO 의
        //   fail-fast 취지 자체가 그것을 막는 것이다).
        @Size(max = 100, message = "지자체명은 100자를 넘을 수 없습니다.")
        String lclgvNm,

        @PositiveOrZero(message = "영상길이는 0 이상이어야 합니다.")
        @Digits(integer = 10, fraction = 0, message = "영상길이는 정수 10자리까지만 허용됩니다.")
        BigDecimal vdoLenSec,

        @Pattern(regexp = "^[0-9]{1,7}(\\.[0-9]{1,2})?$",
                message = "프레임재생속도는 숫자(소수점 2자리까지)만 허용됩니다.")
        String fps,

        @PositiveOrZero(message = "프레임수는 0 이상이어야 합니다.")
        @Digits(integer = 10, fraction = 0, message = "프레임수는 정수 10자리까지만 허용됩니다.")
        BigDecimal frmeCnt,

        @Size(max = 20, message = "종횡비는 20자를 넘을 수 없습니다.")
        String asprtRt,

        @PositiveOrZero(message = "영상 너비는 0 이상이어야 합니다.")
        @Digits(integer = 10, fraction = 0, message = "영상 너비는 정수 10자리까지만 허용됩니다.")
        BigDecimal wdth,

        @PositiveOrZero(message = "영상 세로는 0 이상이어야 합니다.")
        @Digits(integer = 10, fraction = 0, message = "영상 세로는 정수 10자리까지만 허용됩니다.")
        BigDecimal vrtc,

        @Size(max = 20, message = "해상도는 20자를 넘을 수 없습니다.")
        String resl,

        @Size(max = 20, message = "비트값은 20자를 넘을 수 없습니다.")
        String bit,

        @Size(max = 20, message = "화소는 20자를 넘을 수 없습니다.")
        String pxl,

        @DecimalMin(value = "-90.0", message = "위도는 -90 ~ 90 범위여야 합니다.")
        @DecimalMax(value = "90.0", message = "위도는 -90 ~ 90 범위여야 합니다.")
        @Digits(integer = 3, fraction = 7, message = "위도는 소수점 7자리까지만 허용됩니다.")
        BigDecimal wgs84Lat,

        @DecimalMin(value = "-180.0", message = "경도는 -180 ~ 180 범위여야 합니다.")
        @DecimalMax(value = "180.0", message = "경도는 -180 ~ 180 범위여야 합니다.")
        @Digits(integer = 3, fraction = 7, message = "경도는 소수점 7자리까지만 허용됩니다.")
        BigDecimal wgs84Lot,

        @Size(max = 20, message = "기관코드는 20자를 넘을 수 없습니다.")
        String ogCd,

        @Size(max = 300, message = "CCTV명은 300자를 넘을 수 없습니다.")
        String cctvNm,

        @PositiveOrZero(message = "CCTV 높이는 0 이상이어야 합니다.")
        @Digits(integer = 3, fraction = 1, message = "CCTV 높이는 소수점 1자리까지만 허용됩니다.")
        BigDecimal cctvHgt,

        @Min(value = 0, message = "주감시방향은 0 ~ 360 범위여야 합니다.")
        @Max(value = 360, message = "주감시방향은 0 ~ 360 범위여야 합니다.")
        Integer mainSurvPanAng,

        @Size(max = 50, message = "이벤트 ID 는 50자를 넘을 수 없습니다.")
        String evntId,

        @Size(max = 200, message = "이벤트명은 200자를 넘을 수 없습니다.")
        String evntNm,

        @Size(max = 4000, message = "관제일지는 4000자를 넘을 수 없습니다.")
        String mntrCn,

        // ★ 길이·형식 제약을 @Pattern 리터럴로 적지 않는다 — 허용값이 6종 enum 이라 목록 자체가
        //   판정이고, 그 목록의 단일 진실원은 LsDataIngest.VRFC_EVNT_TYPES 다(아래 @AssertTrue).
        //   여기에 정규식을 또 두면 두 목록이 갈라져 한쪽만 통과하는 값이 생긴다(srcType 실사고 동형).
        String vrfcEvntTypeCd
) {

    /**
     * 출처유형 — 미지정(null·공백)이면 내부 업로드 기본값({@link LsDataIngest#SRC_TYPE_USER_ULD}).
     *
     * <p>관제 재현이 목적이라 화면에서 고를 수 있게 열어 두되, 값 자체는
     * {@link LsDataIngest#UPLOAD_SRC_TYPES} allowlist 를 통과한 것만 온다
     * ({@link #isSrcTypeAllowed}). 이 축을 벗어난 값이 인입에 들어가면 적재 시 조용히 null 이 되어
     * 파생 판별·화면 표시가 미정의가 된다.
     */
    public String srcTypeOrDefault() {
        return StringUtils.hasText(srcType) ? srcType : LsDataIngest.SRC_TYPE_USER_ULD;
    }

    /**
     * 출처유형 allowlist 검증 — <b>단일 진실원은 {@link LsDataIngest#UPLOAD_SRC_TYPES}</b> 다 (M4).
     *
     * <h3>왜 {@code @Pattern} 리터럴이 아니라 {@code @AssertTrue} 인가</h3>
     * <p>구 구현은 허용 목록을 <b>두 곳</b>에 뒀다: 이 DTO 의 정규식 리터럴과
     * {@code LsDataIngest.ALLOWED_SRC_TYPES}. 어노테이션 인자는 컴파일 상수여야 해서 집합을 참조할
     * 수 없기 때문인데, 그 결과 두 목록이 갈라져도 아무것도 깨지지 않는다(한쪽만 통과하는 값이
     * 조용히 생긴다 — 실제로 {@code AUGMENTED} 가 그랬다). 검증을 메서드로 옮기면 집합을 직접
     * 참조할 수 있어 리터럴 중복이 사라진다.
     *
     * <p>{@link #srcTypeOrDefault()} 를 판정 대상으로 삼으므로 <b>빈 값은 기본값으로 통과</b>한다
     * (미지정과 빈 문자열의 계약을 통일 — FE 가 빈 입력을 {@code ""} 로 보내도 400 이 아니다).
     */
    @jakarta.validation.constraints.AssertTrue(message = "지원하지 않는 출처유형입니다.")
    public boolean isSrcTypeAllowed() {
        return LsDataIngest.UPLOAD_SRC_TYPES.contains(srcTypeOrDefault());
    }

    /**
     * 검증이벤트유형 — 정규화된 값 또는 <b>미지정({@code null})</b> (@req R5).
     *
     * <p>정규화(trim + 소문자)는 {@link LsDataIngest#normalizeVrfcEvntType} 한 곳이 소유한다.
     * 검증도 저장도 <b>이 결과</b>에 대해 하므로 표기 변형으로 검증을 우회할 수 없다.
     *
     * <p>빈 값이 {@code null} 인 것은 계약이다 — 빈 문자열을 그대로 저장하면 소비 시점(VLM 위탁)이
     * "판정 없음"과 구분하지 못한다.
     */
    public String vrfcEvntTypeCdOrNull() {
        return LsDataIngest.normalizeVrfcEvntType(vrfcEvntTypeCd);
    }

    /**
     * 검증이벤트유형 allowlist 검증 — <b>단일 진실원은 {@link LsDataIngest#VRFC_EVNT_TYPES}</b> 다
     * (@req R5).
     *
     * <p>{@link #isSrcTypeAllowed} 와 같은 이유로 {@code @Pattern} 리터럴이 아니라
     * {@code @AssertTrue} 다 — 어노테이션 인자는 컴파일 상수라 집합을 참조할 수 없어, 정규식으로
     * 적으면 목록이 두 곳에 생기고 갈라진다.
     *
     * <p><b>미지정은 통과</b>한다 — 선택 입력이다. 값이 없는 업로드는 소비 시점(VLM 위탁)에서
     * 건너뛰며, 그것이 정상 경로다(관제 인입분도 대부분 아직 null 이다).
     *
     * <p>위반 메시지에 <b>입력 원문을 담지 않는다</b> — 이 메시지는 응답·로그로 흘러가므로
     * 원문을 실으면 로그 인젝션·정보 노출이 된다(CWE-117/209).
     */
    @jakarta.validation.constraints.AssertTrue(message = "지원하지 않는 검증이벤트유형입니다.")
    public boolean isVrfcEvntTypeAllowed() {
        String normalized = vrfcEvntTypeCdOrNull();
        return normalized == null || LsDataIngest.VRFC_EVNT_TYPES.contains(normalized);
    }
}
