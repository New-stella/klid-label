package kr.co.cudo.authoring.video.repository;

import java.time.LocalDateTime;

/**
 * 영상(rawSn) 별 최신 내보내기 요약 Projection.
 *
 * <p>LS_PJT_DATA_STTS(영상↔프로젝트 매핑) JOIN LS_DATA_SET(프로젝트 단위 내보내기 이력) 결과를
 * 영상 단위로 집계한 것. 한 영상이 여러 프로젝트에 매핑되어 있을 수 있고, 각 프로젝트가 여러
 * export 이력을 가질 수 있으므로 status IN ('COMPLETED','FAILED') 인 가장 최근 1건만 노출한다.
 *
 * <p>읽기 전용 — 신규 테이블/컬럼 없음.
 */
public interface VideoExportProjection {
    Long getRawSn();
    String getExportSttsCd();
    LocalDateTime getExportedAt();
    String getErrorMessage();
}
