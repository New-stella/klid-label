// [개발/검수 전용] dev 파일 업로드 도메인 타입 (BE: /api/v1/dev/upload)
//
// BE: kr.co.cudo.authoring.dev.dto.AutolabelTestRequest / AutolabelTestResponse 와 1:1 미러.
// 운영(prd) 환경에서도 파일 업로드가 기본 ON 이라 본 타입은 local/dev/stg/prd 전 채널에서 사용된다.
// 노출 여부는 BE 토글 `authoring.dev.upload.enabled`(prd 기본 true)가 정한다.

/** 개인정보 유형 — LsDataRaw.PRVC_TYPE_CD 와 매핑. */
export const PrvcType = {
  ANONY: 'ANONY',
  PRVC: 'PRVC',
  PSDO: 'PSDO',
} as const;
export type PrvcType = (typeof PrvcType)[keyof typeof PrvcType];

/**
 * 오토라벨 테스트 메타데이터 (dev 업로드 단순화 — 운영 시나리오 1:1 고정 플로우).
 *
 * BE record `AutolabelTestRequest` 와 동일한 필드 + 검증.
 * - vmsClipId / cctvId: 영문/숫자/-/_ 1~64자
 * - localGovCd: 숫자 1~10자리
 * - eventTypeCd: 관제 마스터 상세 EV-코드 (예 `EV02000201`). 화면 select 는 카테고리(9종)를
 *   고르게 하고 제출 시 그 카테고리의 대표 EV-코드(`memberCodes[0]`)를 전송한다 — 테스트 영상이
 *   실제 영상처럼 EV-코드를 갖도록 해 오토라벨 프리셋 매칭(EV-코드→categoryKey→프리셋) 흐름 검증.
 * - capturedAt: ISO-8601 (`Date.toISOString()` 형식)
 *
 * dev 업로드는 업로드 → 비식별(무조건) → MARKING_READY 정지의 고정 플로우라 단계 토글
 * (enabledStages)·마킹 직접 수행(manualMarking) 같은 분기 필드를 전송하지 않는다.
 *
 * `durationSec` 는 BE 가 ffprobe 로 업로드된 영상 파일에서 자동 추출하므로 FE 가 전송하지 않는다.
 */
export interface AutolabelTestMeta {
  vmsClipId: string;
  cctvId: string;
  /** 관제 상세 EV-코드 (EV + 숫자 8자리). */
  eventTypeCd: string;
  localGovCd: string;
  prvcTypeCd: PrvcType;
  /** ISO-8601 Instant (예: `2026-05-12T10:00:00Z`) */
  capturedAt: string;
}

/** 업로드 + 파이프라인 트리거 응답. */
export interface AutolabelTestResult {
  /** LS_DATA_RAW.RAW_SN — 생성된 영상 식별자 */
  rawSn: number;
  /** 저장된 파일의 storage 기준 상대 경로 (절대 경로 미노출) */
  savedFilePath: string;
  /** 파이프라인 상태 — 항상 PROCESSING (비동기 시작, REGISTERED/ACCEPTED 분기 폐지) */
  pipelineStatus: string;
  /** 트리거 시각 epoch millis */
  startedAt: number;
}
