package kr.co.cudo.authoring.portal.dto;

import kr.co.cudo.authoring.portal.entity.LsPortalUld;

import java.time.LocalDateTime;

/**
 * V107 포털 업로드 자산 응답 1행 (목록/업로드 결과 공용).
 *
 * <p>Entity 직접 노출 금지 정책 준수 — 저장 경로(FILE_PATH_NM) 등 내부 경로는 노출하지 않는다(CWE-209).
 * {@code frmeSn} 은 이미지(FRME_NO=0) 프레임의 식별자로, 업로드 직후 이미지 서빙
 * ({@code GET /v1/portal/uploads/frames/{frmeSn}/image}) 진입점이다. 목록 응답에서는 null 일 수 있다.
 * {@code failRsnCn} 은 FAILED 상태에서만 채워지는 실패 사유(예: "프레임 추출 실패: ...")이며,
 * markFailed 사유 문자열은 예외 클래스 단순명만 담아 내부 경로/PII 를 노출하지 않는다(CWE-209).
 */
public record PortalUploadResponse(
        Long uldSn,
        String uldTypeCd,
        String orgnlFileNm,
        Long fileSz,
        String mimeTypeNm,
        String uldSttsCd,
        Integer frmeCnt,
        Long frmeSn,
        LocalDateTime regDt,
        String failRsnCn
) {

    /** 목록용 — 프레임 식별자 없이 마스터 필드만. */
    public static PortalUploadResponse from(LsPortalUld uld) {
        return of(uld, null);
    }

    /** 업로드 결과용 — 대표 프레임(이미지 FRME_NO=0) 식별자 포함. */
    public static PortalUploadResponse of(LsPortalUld uld, Long frmeSn) {
        return new PortalUploadResponse(
                uld.getUldSn(),
                uld.getUldTypeCd(),
                uld.getOrgnlFileNm(),
                uld.getFileSz(),
                uld.getMimeTypeNm(),
                uld.getUldSttsCd(),
                uld.getFrmeCnt(),
                frmeSn,
                uld.getRegDt(),
                uld.getFailRsnCn());
    }
}
