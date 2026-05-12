// [개발/검수 전용] 오토라벨 테스트 도메인 타입 (BE: /api/v1/dev/autolabel-test)
//
// BE: kr.co.cudo.authoring.dev.dto.AutolabelTestRequest / AutolabelTestResponse 와 1:1 미러.
// 운영(prd) 환경에서는 endpoint 가 부재하므로 본 타입은 dev/local/stg 채널에서만 사용된다.

/** 개인정보 유형 — LsDataRaw.PRVC_TYPE_CD 와 매핑. */
export const PrvcType = {
  ANONY: 'ANONY',
  PRVC: 'PRVC',
  PSDO: 'PSDO',
} as const;
export type PrvcType = (typeof PrvcType)[keyof typeof PrvcType];

/**
 * 이벤트 타입 코드 — SFR 6종 + 화이트리스트.
 *
 * BE 검증 패턴 `^[A-Z][A-Z0-9_]{1,31}$` 를 만족하는 값만 허용. FE 입력 화면에서는
 * 화이트리스트 select 로 노출하여 임의 문자열 주입을 1차 차단한다 (XSS·SQL Injection
 * 방어 — BE 가 본 검증을 수행하므로 FE 는 UX 가드 용도).
 */
export const EventTypeCd = {
  EVT_FALL: 'EVT_FALL',
  EVT_VIOLENCE: 'EVT_VIOLENCE',
  EVT_ACCIDENT: 'EVT_ACCIDENT',
  EVT_ABNORMAL: 'EVT_ABNORMAL',
  EVT_FLOOD: 'EVT_FLOOD',
  EVT_FIRE: 'EVT_FIRE',
} as const;
export type EventTypeCd = (typeof EventTypeCd)[keyof typeof EventTypeCd];

/**
 * 오토라벨 테스트 메타데이터.
 *
 * BE record `AutolabelTestRequest` 와 동일한 필드 + 검증.
 * - vmsClipId / cctvId: 영문/숫자/-/_ 1~64자
 * - localGovCd: 숫자 1~10자리
 * - durationSec: 1~7200
 * - capturedAt: ISO-8601 (`Date.toISOString()` 형식)
 */
export interface AutolabelTestMeta {
  vmsClipId: string;
  cctvId: string;
  eventTypeCd: EventTypeCd | string;
  localGovCd: string;
  prvcTypeCd: PrvcType;
  durationSec: number;
  /** ISO-8601 Instant (예: `2026-05-12T10:00:00Z`) */
  capturedAt: string;
}

/** 업로드 + 파이프라인 트리거 응답. */
export interface AutolabelTestResult {
  /** LS_DATA_RAW.RAW_SN — 생성된 영상 식별자 */
  rawSn: number;
  /** 저장된 파일의 storage 기준 상대 경로 (절대 경로 미노출) */
  savedFilePath: string;
  /** 파이프라인 상태 — PROCESSING (비동기 시작) 또는 ACCEPTED */
  pipelineStatus: string;
  /** 트리거 시각 epoch millis */
  startedAt: number;
}
