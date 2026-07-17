package kr.co.cudo.authoring.portal.dto;

import kr.co.cudo.authoring.portal.entity.LsPortalUld;
import kr.co.cudo.authoring.portal.entity.LsPortalUldFrme;

import java.time.LocalDateTime;
import java.util.List;

/**
 * V107 포털 업로드 자산 상세 응답 — 마스터 + 프레임 요약 목록.
 *
 * <p>Entity 직접 노출 금지 — 저장 경로 등 내부 필드는 제외한다(CWE-209).
 * {@code failRsnCn} 은 FAILED 상태에서만 채워지는 실패 사유(예외 클래스 단순명만 — 내부 경로/PII 미포함).
 */
public record PortalUploadDetailResponse(
        Long uldSn,
        String uldTypeCd,
        String orgnlFileNm,
        Long fileSz,
        String mimeTypeNm,
        String uldSttsCd,
        Integer frmeCnt,
        Double vdoLenSec,
        Double fps,
        LocalDateTime regDt,
        LocalDateTime mdfcnDt,
        List<PortalUploadFrameResponse> frames,
        String failRsnCn
) {

    public static PortalUploadDetailResponse of(LsPortalUld uld, List<LsPortalUldFrme> frames) {
        return new PortalUploadDetailResponse(
                uld.getUldSn(),
                uld.getUldTypeCd(),
                uld.getOrgnlFileNm(),
                uld.getFileSz(),
                uld.getMimeTypeNm(),
                uld.getUldSttsCd(),
                uld.getFrmeCnt(),
                uld.getVdoLenSec(),
                uld.getFps(),
                uld.getRegDt(),
                uld.getMdfcnDt(),
                frames.stream().map(PortalUploadFrameResponse::from).toList(),
                uld.getFailRsnCn());
    }
}
