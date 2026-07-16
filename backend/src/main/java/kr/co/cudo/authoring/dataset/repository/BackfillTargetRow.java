package kr.co.cudo.authoring.dataset.repository;

import java.time.LocalDateTime;

/**
 * 백필 대상 영상 프로젝션 — 배포 이전 APPROVED 였으나 활성 스냅샷이 없는 영상 1건.
 *
 * @see LsDatasetVideoMetaRepository#findApprovedWithoutActiveSnapshot()
 */
public interface BackfillTargetRow {

    /** 대상 영상 PK(= LS_RAW_DATA_STATUS.RAW_DATA_ID). */
    Long getRawSn();

    /** 과거 APPROVED 전이 시각(LS_RAW_DATA_STATUS.UPD_DT) — 소급 RVW_CMPL_DT 로 사용. */
    LocalDateTime getApprovedAt();
}
