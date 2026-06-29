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
 * [개발/검수 전용] 오토라벨 테스트 업로드 이벤트 옵션 — EVT_ 코드 6 종.
 *
 * 운영 이벤트 타입은 관제 마스터(카테고리/EV-코드)로 전환됐으나, dev 업로드 엔드포인트
 * (`AutolabelTestRequest`)는 BE 검증 패턴 `^EVT_(FALL|VIOLENCE|ACCIDENT|ABNORMAL|FLOOD|FIRE)$`
 * 를 그대로 유지한다(미변경 BE 계약). 따라서 dev 화면은 본 고정 EVT_ 코드를 dev 전용 fixture 로
 * 로컬 정의해 전송한다 — 운영 이벤트 타입(관제 마스터 전환)과 분리한다.
 *
 * FE 입력은 화이트리스트 select 로 노출해 임의 문자열 주입을 1차 차단한다(BE 가 2차 검증).
 */
export const DEV_EVENT_TYPES = [
  { code: 'EVT_FALL', label: '쓰러짐' },
  { code: 'EVT_VIOLENCE', label: '폭력' },
  { code: 'EVT_ACCIDENT', label: '교통사고' },
  { code: 'EVT_ABNORMAL', label: '이상행동(유괴)' },
  { code: 'EVT_FLOOD', label: '침수' },
  { code: 'EVT_FIRE', label: '산불' },
] as const;

export type EventTypeCd = (typeof DEV_EVENT_TYPES)[number]['code'];

export const EventTypeCd = Object.freeze(
  DEV_EVENT_TYPES.reduce<Record<EventTypeCd, EventTypeCd>>((acc, e) => {
    acc[e.code] = e.code;
    return acc;
  }, {} as Record<EventTypeCd, EventTypeCd>),
);

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
