package kr.co.cudo.authoring.portal.upload;

import java.time.LocalDateTime;

/**
 * 포털 업로드 자산 <b>읽기 모델</b> — 공용 원장 여러 표에 흩어진 값을 한 덩어리로 모은 것(ADR-058).
 *
 * <p>흡수 전에는 이 모양이 전용 표 한 행이었다. 지금은 영상 원장 한 행 + 메타 원장 몇 행 +
 * 프레임 원장 행 수로 조립되며, 조립 규칙은 {@code PortalUploadAssetRepository} 한 곳이 소유한다.
 *
 * <p>⚠ <b>엔티티가 아니다</b> — 이 레코드로 저장하지 않는다. 쓰기는 전부 원장별 통로를 거친다.
 *
 * @param uldSn        자산 식별자 = {@code LS_DATA_RAW.RAW_SN}. 창구·화면·이벤트의 이름은 흡수 뒤에도
 *                     그대로 두기로 확정됐다(개명하면 계약이 동시에 깨지는데 얻는 동작이 없다)
 * @param portalUserNo 소유자 — 포털 인가의 키이자 보존기간 삭제 판별자 셋 중 하나
 * @param uldTypeCd    자산 종류. <b>보관하지 않고</b> 매체 유형에서 판정한다
 * @param orgnlFileNm  원본 파일명(표시용)
 * @param filePathNm   저장 경로 — 응답에 싣지 않는다(CWE-209)
 * @param fileSz       파일 크기(byte)
 * @param mimeTypeNm   업로드 시 확정한 매체 유형
 * @param uldSttsCd    업로드 처리 상태. <b>메타 행이 없으면 {@code UPLOADED}</b>
 * @param vdoLenSec    영상 길이(초). 밀리초 정밀도가 있으면 그것에서 온다
 * @param fps          초당 프레임 수
 * @param frmeCnt      프레임 수 — 보관하지 않고 <b>프레임 원장 행을 세어</b> 얻는다
 * @param failRsnCn    처리 실패 사유. 폭을 넘겨 잘렸으면 원문 길이가 함께 적혀 있다
 * @param regDt        자산 등록 일시 — 보존기간(READY 축) 기준점 한쪽
 * @param sttsChgDt    상태 마지막 변경 일시 — 방치 판정과 보존기간(FAILED 축)의 기준점
 * @design ADR-058
 * @design ERD-028
 */
public record PortalUploadAsset(
        Long uldSn,
        String portalUserNo,
        String uldTypeCd,
        String orgnlFileNm,
        String filePathNm,
        Long fileSz,
        String mimeTypeNm,
        String uldSttsCd,
        Double vdoLenSec,
        Double fps,
        Integer frmeCnt,
        String failRsnCn,
        LocalDateTime regDt,
        LocalDateTime sttsChgDt
) {

    /** 라벨링이 가능한 상태인가. */
    public boolean isReady() {
        return PortalUploadLedger.STATUS_READY.equals(uldSttsCd);
    }

    /** 후처리 진행 중인가 — 이 상태에서는 사용자 삭제를 막는다(러너와 파일·행이 경합한다). */
    public boolean isProcessing() {
        return PortalUploadLedger.STATUS_PROCESSING.equals(uldSttsCd);
    }
}
