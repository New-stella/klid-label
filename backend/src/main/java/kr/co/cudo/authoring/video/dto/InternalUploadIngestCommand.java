package kr.co.cudo.authoring.video.dto;

import kr.co.cudo.authoring.video.entity.LsDataIngest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 내부 업로드(REVIEWER TUS)가 {@code LS_DATA_INGEST} 에 남기는 <b>인입 수신값</b> 커맨드.
 *
 * <h3>왜 관제 수신 30컬럼만 담는가 (구조적 도달 불가)</h3>
 * <p>{@code LS_DATA_INGEST} 37컬럼 중 <b>저작도구 운영 8컬럼</b>({@code RCPTN_SN}·{@code RCPTN_DT}·
 * {@code PRCS_STTS_CD}·{@code RAW_SN}·{@code RTY_CNT}·{@code PRCS_DT}·{@code NXTM_RTRY_DT}·
 * {@code ERR_MSG})은 <b>인입 폴링 상태머신의 소유</b>다. 이 커맨드에 두면 업로드 경로가 상태머신을
 * 직접 세팅할 수 있게 되므로 아예 필드를 두지 않는다 — 값은 DB DEFAULT
 * ({@code RCPTN_DT}/{@code RTY_CNT}) 또는 writer 의 고정값({@code PRCS_STTS_CD='PENDING'})이 채운다.
 *
 * <h3>30컬럼 전량을 화면 입력으로 채운다</h3>
 * <p>업로드 폼이 관제 수신 30컬럼을 그대로 재현하므로 {@link #builder()} 로 전 필드를 싣는다.
 * <b>여전히 추측해 채우지 않는다</b> — 사용자가 비운 기술메타 키는 null 로 두고, 적재 후
 * {@code VideoMetaService}(관제 인입값 우선, 없는 키만 ffprobe — 폴백은 <b>키 단위</b>)가 그 키만 채운다.
 *
 * <p>30개 위치 인자를 손으로 나열하면 같은 타입(String 20여 개)이 조용히 뒤바뀌므로
 * <b>빌더로만 조립</b>한다.
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
 * @param frmeCnt           프레임수
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
 * @param lclgvNm          지방자치단체명(지자체명)
 * @param evntId           이벤트 아이디(식별자형 — 이벤트유형코드 아님)
 * @param evntNm           이벤트명
 * @param mntrCn           관제일지 내용
 * @param lclgvCd          지방자치단체코드
 * @param vrfcEvntTypeCd   검증이벤트유형코드 — 외부 VLM 검증 API 의 {@code event_type}
 *                         ({@link LsDataIngest#VRFC_EVNT_TYPES} 6종). 이벤트<b>유형</b>코드
 *                         ({@code EVNT_TYPE_CD})와 다른 값이며, 미지정이면 null 이다(선택 입력).
 *                         값은 {@link LsDataIngest#normalizeVrfcEvntType} 로 정규화한 뒤 싣는다
 * @req R5
 */
@lombok.Builder
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
        BigDecimal frmeCnt,
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
        String lclgvNm,
        String evntId,
        String evntNm,
        String mntrCn,
        String lclgvCd,
        String vrfcEvntTypeCd
) {
}
