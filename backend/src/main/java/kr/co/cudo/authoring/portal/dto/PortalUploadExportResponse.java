package kr.co.cudo.authoring.portal.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase 4 — 포털 업로드 자산 내보내기(export) 응답. 자산 메타 + 프레임 + 프레임별 라벨을 담은
 * 자기완결 JSON 문서로, {@code Content-Disposition: attachment} 로 다운로드된다.
 *
 * <p>AC6 데이터마트 무접촉: 포털 3종 테이블(ULD/FRME/LBL)만으로 구성한다(타 도메인 미참조).
 * 라벨은 업로드 단위 일괄 조회 후 프레임별로 그룹핑해 N+1 을 회피한다. 직렬화는 Jackson 표준
 * (수동 문자열 조립 금지)으로 수행한다.
 */
public record PortalUploadExportResponse(
        Long uldSn,
        String uldTypeCd,
        String orgnlFileNm,
        Long fileSz,
        String mimeTypeNm,
        String uldSttsCd,
        Integer frmeCnt,
        LocalDateTime regDt,
        List<Frame> frames
) {

    /** 프레임 1건 + 해당 프레임의 라벨 목록. */
    public record Frame(
            Long uldFrmeSn,
            Integer frmeNo,
            List<Label> labels
    ) {}

    /** 라벨 1건 — 내부 컬럼 제외, 좌표는 파싱된 중첩 배열. */
    public record Label(
            Long uldLblSn,
            String lblTypeCd,
            String label,
            List<List<Double>> points
    ) {}
}
