package kr.co.cudo.authoring.portal.dto;

import kr.co.cudo.authoring.portal.upload.PortalUploadAsset;

import java.time.LocalDateTime;

/**
 * V107 포털 업로드 자산 응답 1행 (목록/업로드 결과 공용).
 *
 * <p>Entity 직접 노출 금지 정책 준수 — 저장 경로(FILE_PATH_NM) 등 내부 경로는 노출하지 않는다(CWE-209).
 * {@code frmeSn} 은 이미지(FRME_NO=0) 프레임의 식별자로, 업로드 직후 이미지 서빙
 * ({@code GET /v1/portal/uploads/frames/{frmeSn}/image}) 진입점이다. 목록 응답에서는 null 일 수 있다.
 * {@code failRsnCn} 은 FAILED 상태에서만 채워지는 실패 사유(예: "프레임 추출 실패: ...")이며,
 * markFailed 사유 문자열은 예외 클래스 단순명만 담아 내부 경로/PII 를 노출하지 않는다(CWE-209).
 *
 * <p>{@code expiresAt} 은 보존기간 만료 예정 시각이다. @design AC-1070, DFEAT-055
 * <b>저장되지 않는 파생값</b>으로 조회 시점의 보존기간 설정값(READY 는
 * {@code portal.upload.retention-days}, FAILED 는 {@code portal.upload.failed-retention-days})으로
 * 매번 재계산된다 — 설정이 바뀌면 다음 조회부터 값이 달라지므로 <b>클라이언트는 캐시하지 말 것</b>.
 * 삭제 대상이 아닌 상태({@code PROCESSING})이거나 보존기간 설정이 없으면 {@code null}.
 * ⚠ <b>마킹 대기({@code UPLOADED})에도 이제 값이 실린다</b> — 등록일 기산 보존기간이 적용된다(AC-1070).
 * 판정은 {@code PortalRetentionPolicy} 한 곳에서만 한다(재유도 금지).
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
        String failRsnCn,
        LocalDateTime expiresAt
) {

    /** 목록용 — 프레임 식별자·만료 예정 시각 없이 자산 필드만. */
    public static PortalUploadResponse from(PortalUploadAsset uld) {
        return of(uld, null);
    }

    /**
     * 업로드 결과용 — 대표 프레임(이미지 FRME_NO=0) 식별자 포함.
     *
     * <p>업로드 <b>직후</b> 응답이라 만료 예정 시각은 싣지 않는다({@code null}) — 방금 등록한 자산의
     * 만료는 목록·상세 조회에서 고지한다(응답 하나 만들자고 설정 조회를 끼워 넣지 않는다).
     */
    public static PortalUploadResponse of(PortalUploadAsset uld, Long frmeSn) {
        return of(uld, frmeSn, null);
    }

    /** 목록용 — 만료 예정 시각 포함({@code PortalRetentionPolicy} 판정 결과). */
    public static PortalUploadResponse of(PortalUploadAsset uld, Long frmeSn, LocalDateTime expiresAt) {
        return new PortalUploadResponse(
                uld.uldSn(),
                uld.uldTypeCd(),
                uld.orgnlFileNm(),
                uld.fileSz(),
                uld.mimeTypeNm(),
                uld.uldSttsCd(),
                uld.frmeCnt(),
                frmeSn,
                uld.regDt(),
                uld.failRsnCn(),
                expiresAt);
    }
}
