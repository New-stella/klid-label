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
 * 배치 단계 토글 키 — BE `AutolabelTestRequest.STAGE_*` 와 1:1 매핑.
 *
 * 신 파이프라인 순서: DEIDENTIFY(선두·무조건, OFF 시 skip) → FRAME_EXTRACT(마킹 위치 추출)
 * → YOLO(원본 기준 탐지/트래킹) → SAM2(YOLO bbox 힌트 세그). 키 자체는 BE 계약 호환을 위해 유지하며,
 * 표시 순서는 화면(STAGE_LABELS)에서 신 순서로 정렬한다.
 */
export const STAGE_KEYS = {
  FRAME_EXTRACT: 'FRAME_EXTRACT',
  DEIDENTIFY: 'DEIDENTIFY',
  YOLO: 'YOLO',
  SAM2: 'SAM2',
} as const;
export type StageKey = (typeof STAGE_KEYS)[keyof typeof STAGE_KEYS];

/**
 * 단계 토글 맵 — 키: FRAME_EXTRACT/DEIDENTIFY/YOLO/SAM2, 값: ON/OFF.
 *
 * BE 는 누락 키를 {@code true} 로 처리한다. FE 는 항상 4개 키를 전송해 의도를 명확히 한다.
 */
export type EnabledStages = Record<StageKey, boolean>;

/**
 * 오토라벨 테스트 메타데이터.
 *
 * BE record `AutolabelTestRequest` 와 동일한 필드 + 검증.
 * - vmsClipId / cctvId: 영문/숫자/-/_ 1~64자
 * - localGovCd: 숫자 1~10자리
 * - capturedAt: ISO-8601 (`Date.toISOString()` 형식)
 * - enabledStages: 4단계 ON/OFF 토글. 누락 시 BE 가 모두 true 처리 (back-compat).
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
  /** 단계 ON/OFF 토글 — 누락 시 BE 가 모두 실행 (선택 필드). */
  enabledStages?: EnabledStages;
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
