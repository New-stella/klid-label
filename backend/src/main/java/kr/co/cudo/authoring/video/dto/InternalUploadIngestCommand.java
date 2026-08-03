package kr.co.cudo.authoring.video.dto;

import kr.co.cudo.authoring.video.entity.LsDataIngest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 내부 업로드(REVIEWER TUS)가 {@code LS_DATA_INGEST} 에 남기는 <b>인입 수신값</b> 커맨드.
 *
 * <h3>왜 관제 수신 29컬럼만 담는가 (구조적 도달 불가)</h3>
 * <p>{@code LS_DATA_INGEST} 37컬럼 중 <b>저작도구 운영 8컬럼</b>({@code RCPTN_SN}·{@code RCPTN_DT}·
 * {@code PROC_STTS_CD}·{@code RAW_SN}·{@code RTY_CNT}·{@code PRCS_DT}·{@code NEXT_RTRY_DT}·
 * {@code ERR_MSG})은 <b>인입 폴링 상태머신의 소유</b>다. 이 커맨드에 두면 업로드 경로가 상태머신을
 * 직접 세팅할 수 있게 되므로 아예 필드를 두지 않는다 — 값은 DB DEFAULT
 * ({@code RCPTN_DT}/{@code RTY_CNT}) 또는 writer 의 고정값({@code PROC_STTS_CD='PENDING'})이 채운다.
 *
 * <h3>Phase 1 에서 실제로 채우는 값</h3>
 * <p>{@link #ofInternalUpload}가 채우는 10개({@code vmsClipId}·{@code vmsCctvId}·{@code vdoFileNm}·
 * {@code rawFilePathNm}·{@code srcType}·{@code shtDt}·{@code vdoLenSec}·{@code fileSz}·
 * {@code fileFmt}·{@code lclgvCd})뿐이고 나머지 기술메타·CCTV 제원은 null 이다. 관제 인입 경로도
 * 관제가 안 보낸 값은 null 로 두므로(대용값 금지) 여기서도 <b>추측해 채우지 않는다</b> — 영상 기술메타는
 * 적재 후 ffprobe back-fill({@code VideoMetaService})이, 나머지 수동입력분은 Phase 3 의 FE 폼이 채운다.
 *
 * @param vmsClipId        VMS 클립 아이디 (UK — 인입 멱등키). 파일명이 되므로 allowlist 검증 대상
 * @param vmsCctvId        VMS CCTV 아이디
 * @param vdoFileNm        동영상 파일명 — <b>실제 저장 파일명</b>({@code {clipId}.{ext}})
 * @param rawFilePathNm    원시 파일 경로명 — 인입 영역으로 이동을 마친 절대경로
 * @param srcType          출처유형 — 내부 업로드는 {@link LsDataIngest#SRC_TYPE_USER_ULD}
 * @param shtDt            촬영일시(미상이면 null — 대용값 금지)
 * @param vdoLenSec        영상길이(초)
 * @param fileSz           파일크기(바이트)
 * @param fileFmt          파일형식(확장자)
 * @param vdoCdc           영상코덱
 * @param fps              프레임재생속도
 * @param frmCnt           프레임수
 * @param wdth             영상 너비(px)
 * @param vrtc             영상 세로(px)
 * @param resl             해상도 표기
 * @param asprtRt          종횡비 표기
 * @param bit              비트값(색심도 표기 — 비트레이트 아님)
 * @param pxl              화소 표기
 * @param ogCd             기관코드
 * @param cctvNm           CCTV명
 * @param cctvHgt          CCTV 설치 높이(m)
 * @param mainSurvPanAng   주감시방향값(도)
 * @param wgs84Lat         WGS84 위도
 * @param wgs84Lot         WGS84 경도
 * @param rgnNm            지역명
 * @param evntId           이벤트 아이디(식별자형 — 이벤트유형코드 아님)
 * @param evntNm           이벤트명
 * @param mntrCn           관제일지 내용
 * @param lclgvCd          지방자치단체코드
 */
public record InternalUploadIngestCommand(
        String vmsClipId,
        String vmsCctvId,
        String vdoFileNm,
        String rawFilePathNm,
        String srcType,
        LocalDateTime shtDt,
        BigDecimal vdoLenSec,
        Long fileSz,
        String fileFmt,
        String vdoCdc,
        String fps,
        BigDecimal frmCnt,
        BigDecimal wdth,
        BigDecimal vrtc,
        String resl,
        String asprtRt,
        String bit,
        String pxl,
        String ogCd,
        String cctvNm,
        BigDecimal cctvHgt,
        Integer mainSurvPanAng,
        BigDecimal wgs84Lat,
        BigDecimal wgs84Lot,
        String rgnNm,
        String evntId,
        String evntNm,
        String mntrCn,
        String lclgvCd
) {

    /**
     * 내부 업로드 인입 커맨드 — Phase 1 이 실제로 아는 값만 채우고 나머지는 null 로 둔다.
     *
     * <p>{@code srcType} 은 호출자가 고르지 못하게 <b>여기서 고정</b>한다. 출처유형은 "누가 만들었나"의
     * 경계축이라 오판 비용이 크고, 내부 관리 화면 업로드는 정의상 {@code USER_ULD} 하나뿐이다.
     */
    public static InternalUploadIngestCommand ofInternalUpload(
            String vmsClipId,
            String vmsCctvId,
            String vdoFileNm,
            String rawFilePathNm,
            LocalDateTime shtDt,
            BigDecimal vdoLenSec,
            Long fileSz,
            String fileFmt,
            String lclgvCd) {
        return new InternalUploadIngestCommand(
                vmsClipId, vmsCctvId, vdoFileNm, rawFilePathNm,
                LsDataIngest.SRC_TYPE_USER_ULD, shtDt, vdoLenSec, fileSz, fileFmt,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, lclgvCd);
    }
}
