package kr.co.cudo.authoring.dataset.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * outbox {@code PAYLOAD}(동결 스냅샷 JSON) 역직렬화 DTO.
 *
 * <p>Phase 2 {@code DatasetVideoMetaSnapshotService.toPayload()} 가 직렬화한 비식별 메타 컬럼형 JSON 을
 * 그대로 매핑한다. 알 수 없는 필드는 무시(전방 호환). 관리 컬럼(activeYn·wthrNm·regDt·regId)은 페이로드에
 * 없으므로 복제 시 워커가 기본값(activeYn='Y', regDt=now)으로 채운다.
 *
 * <p>보안: 페이로드는 비식별 메타(경로/좌표/코드값)만 포함하고 PII·토큰·원본 비-비식별 이미지를 담지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MetaReplicationPayload(
        Long rawSn,
        String snpshtHash,
        Long orgnlRawSn,
        String vmsClipId,
        String vmsCctvId,
        String rawFilePathNm,
        LocalDateTime shtDt,
        Integer vdoLenSec,
        String lclgvCd,
        String prvcYn,
        String prvcTypeCd,
        String deIdentYn,
        String aiCrtYn,
        String evntTypeCd,
        String cctvNm,
        BigDecimal wgs84Lat,
        BigDecimal wgs84Lot,
        String sidoNm,
        String sggNm,
        String fileFmt,
        String evntNm,
        String vdoCdc,
        BigDecimal fps,
        Long bitRt,
        BigDecimal asprtRt,
        String resl,
        Integer vdoWdth,
        Integer vdoHgt,
        Long fileSz,
        String dayNgtCd,
        String sesnCd,
        LocalDateTime rvwCmplDt
) {
}
