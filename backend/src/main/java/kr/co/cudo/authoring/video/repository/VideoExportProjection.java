package kr.co.cudo.authoring.video.repository;

import java.time.LocalDateTime;

/**
 * 영상(rawSn) 별 최신 내보내기 요약 Projection.
 *
 * <p>과거 LS_RAW_DATA_STATUS JOIN LS_DATA_SET(프로젝트 단위 내보내기 이력) 집계 결과를 표현했으나,
 * V34(PJT_ID 제거) 이후 영상별 export 매핑이 사문화되고 V86 에서 LS_DATA_SET 이 삭제되어(Export 는
 * 저작도구 범위 외) 현재 소스 테이블은 없다. API 응답 계약(exportStatus 등) 호환을 위한 Projection 형태만
 * 유지하며, {@code findLatestExportsByRawSns} stub 이 항상 빈 결과를 반환하므로 실제로 채워지지 않는다.
 *
 * <p>읽기 전용 — 신규 테이블/컬럼 없음.
 */
public interface VideoExportProjection {
    Long getRawSn();
    String getExportSttsCd();
    LocalDateTime getExportedAt();
    String getErrorMessage();
}
