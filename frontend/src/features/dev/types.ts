// [개발/검수 전용] 오토라벨 테스트 도메인 타입 (BE: /api/v1/dev/autolabel-test)
//
// BE: kr.co.cudo.authoring.dev.dto.AutolabelTestRequest / AutolabelTestResponse 와 1:1 미러.
// 운영(prd) 환경에서는 endpoint 가 부재하므로 본 타입은 dev/local/stg 채널에서만 사용된다.

import {
  EVENT_TYPES,
  type EventTypeCode,
} from '@/constants/eventTypes';

/** 개인정보 유형 — LsDataRaw.PRVC_TYPE_CD 와 매핑. */
export const PrvcType = {
  ANONY: 'ANONY',
  PRVC: 'PRVC',
  PSDO: 'PSDO',
} as const;
export type PrvcType = (typeof PrvcType)[keyof typeof PrvcType];

/**
 * 이벤트 타입 코드 — SoT 6 종.
 *
 * BE 검증 패턴 `^EVT_(FALL|VIOLENCE|ACCIDENT|ABNORMAL|FLOOD|FIRE)$` 를 만족하는 값만 허용.
 * FE 입력 화면에서는 화이트리스트 select 로 노출하여 임의 문자열 주입을 1차 차단한다
 * (XSS·SQL Injection 방어 — BE 가 본 검증을 수행하므로 FE 는 UX 가드 용도).
 *
 * SoT: {@code @/constants/eventTypes} 의 {@code EVENT_TYPES}.
 */
export const EventTypeCd = Object.freeze(
  EVENT_TYPES.reduce<Record<EventTypeCode, EventTypeCode>>((acc, e) => {
    acc[e.code] = e.code;
    return acc;
  }, {} as Record<EventTypeCode, EventTypeCode>),
);
export type EventTypeCd = EventTypeCode;

/**
 * 오토라벨 테스트 메타데이터 (dev 업로드 단순화 — 운영 시나리오 1:1 고정 플로우).
 *
 * BE record `AutolabelTestRequest` 와 동일한 필드 + 검증.
 * - vmsClipId / cctvId: 영문/숫자/-/_ 1~64자
 * - localGovCd: 숫자 1~10자리
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
  eventTypeCd: EventTypeCd | string;
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
