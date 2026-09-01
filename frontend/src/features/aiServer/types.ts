// AI 장비 노드 원장 도메인 타입 — BE: /api/v1/manage/ai-servers
//
// [@design API-226] [@design API-227] [@design API-228] [@design API-229] [@design API-230]
// [@design ADR-046] [@design ADR-057]
//
// ⚠ 필드명은 BE DTO(`AiSrvrResponse`·`AiSrvrLoadResponse`) 와 **철자까지 같다**. 화면에서 읽기
//   좋은 이름으로 갈아 끼우지 않는다 — 그 매핑표가 두 번째 진실원이 되어, 한쪽만 갱신되는 순간
//   조용히 `undefined` 가 흐른다(이 저장소에 같은 형태의 실사고가 있었다 — `sysconfig/types.ts`
//   의 `configKey`/`configVl` 주석 참조).

/** 장비 유형 — 부하를 세는 축이 유형마다 다르므로 한 표에 섞지 않는다. */
export const AiSrvrType = {
  /** 추론(탐지·분할·추적) — 동기 호출이라 상대 장비의 큐 길이가 곧 부하다. */
  INFERENCE: 'INFERENCE',
  /** 외부 시계열 분석 — 위탁 후 콜백이라 큐를 볼 수 없어 우리 원장의 미결 위탁 수를 센다. */
  TIMESERIES: 'TIMESERIES',
} as const;
export type AiSrvrType = (typeof AiSrvrType)[keyof typeof AiSrvrType];

/**
 * 장비 상태 넷.
 *
 * ★ `UNAVAILABLE`(이용불가)과 `DISABLED`(비활성)를 같은 것으로 보이게 하지 말 것 —
 *   전자는 **상태점검 연속 실패로 기계가 배제**한 것이고 후자는 **사람이 내려 둔** 것이다.
 *   운영자가 손을 써야 하는 대상이 서로 다르다.
 */
export const AiSrvrStatus = {
  /** 가용 — 신규 배정과 호출을 모두 받는다. */
  AVAILABLE: 'AVAILABLE',
  /** 이용불가 — 상태점검 연속 실패로 자동 배제됐다. 복귀는 연속 성공으로 한다. */
  UNAVAILABLE: 'UNAVAILABLE',
  /** 정비중 — 신규 배정만 막고 진행 중인 배정은 끝까지 간다. */
  DRAINING: 'DRAINING',
  /** 비활성 — 관리자가 목록에서 내려 둔 상태. */
  DISABLED: 'DISABLED',
} as const;
export type AiSrvrStatus = (typeof AiSrvrStatus)[keyof typeof AiSrvrStatus];

/** 용도 — 장비 안에서 실행 슬롯이 갈려 있어 합쳐 보이지 않는다. */
export const AiSrvrUsageType = {
  BATCH: 'BATCH',
  INTERACTIVE: 'INTERACTIVE',
} as const;
export type AiSrvrUsageType = (typeof AiSrvrUsageType)[keyof typeof AiSrvrUsageType];

/**
 * 용도별 부하 관측값.
 *
 * ⚠ **관측된 적 없는 용도는 목록에 아예 없다**(BE 주석). 「부하 0」이 아니라 「모름」이므로
 *   화면에서 0 으로 채우지 않는다 — 채우면 가장 한가한 장비로 오해한다.
 */
export interface AiSrvrLoad {
  usgTypeCd: AiSrvrUsageType;
  prcsNocs: number;
  wtngNocs: number;
  /** 실효 부하 = 처리중 + 대기. 노드를 고를 때 실제로 보는 값이다. */
  effectiveLoad: number;
  /** 우리가 관측한 시각(상대 장비의 시계가 아니다). */
  chckDt: string | null;
}

/** 장비 한 행 — BE `AiSrvrResponse` 와 1:1. */
export interface AiSrvr {
  srvrId: string;
  srvrNm: string | null;
  srvrAddr: string;
  srvrTypeCd: AiSrvrType;
  srvrSttsCd: AiSrvrStatus;
  /** 최근 상태점검 시각. 한 번도 점검하지 않았으면 비어 있다. */
  chckDt: string | null;
  chckFailNocs: number;
  chckScsNocs: number;
  regDt: string | null;
  mdfrId: string | null;
  mdfcnDt: string | null;
  loads: AiSrvrLoad[];
}

export interface AiSrvrCreateRequest {
  srvrId: string;
  srvrNm?: string;
  srvrAddr: string;
  srvrTypeCd: AiSrvrType;
}

/** 부분 수정 — 보내지 않은 항목은 그대로 둔다. 유형·상태는 이 창구가 받지 않는다. */
export interface AiSrvrUpdateRequest {
  srvrNm?: string;
  srvrAddr?: string;
}

export const AI_SRVR_TYPE_LABEL: Record<AiSrvrType, string> = {
  [AiSrvrType.INFERENCE]: '추론',
  [AiSrvrType.TIMESERIES]: '외부 시계열 분석',
};

export const AI_SRVR_STATUS_LABEL: Record<AiSrvrStatus, string> = {
  [AiSrvrStatus.AVAILABLE]: '가용',
  [AiSrvrStatus.UNAVAILABLE]: '이용불가',
  [AiSrvrStatus.DRAINING]: '정비중',
  [AiSrvrStatus.DISABLED]: '비활성',
};

export const AI_SRVR_USAGE_LABEL: Record<AiSrvrUsageType, string> = {
  [AiSrvrUsageType.BATCH]: '일괄 처리',
  [AiSrvrUsageType.INTERACTIVE]: '화면 요청',
};

/**
 * 현재 상태에서 고를 수 있는 목표 상태 — BE `AiSrvrStatus.canTransitionTo` 의 **화면용 사본**.
 *
 * ★ 판정의 진실원은 서버다. 이 표는 «고를 수 없는 것을 눌러 보게 하지 않는다»는 편의일 뿐이고,
 *   서버가 409 로 거부하면 그 판정이 이긴다. 그래서 표를 **좁히지 않는다** — 좁히면 서버가
 *   허용하는 조작을 화면이 먼저 막아, 사본이 서버보다 엄격한 두 번째 진실원이 된다.
 * ⚠ 같은 상태로의 전이는 서버가 거부하므로(no-op 은 전이가 아니다) 자기 자신은 어느 줄에도 없다.
 */
export const AI_SRVR_ALLOWED_TRANSITIONS: Record<AiSrvrStatus, readonly AiSrvrStatus[]> = {
  [AiSrvrStatus.AVAILABLE]: [
    AiSrvrStatus.UNAVAILABLE,
    AiSrvrStatus.DRAINING,
    AiSrvrStatus.DISABLED,
  ],
  [AiSrvrStatus.UNAVAILABLE]: [AiSrvrStatus.AVAILABLE, AiSrvrStatus.DISABLED],
  [AiSrvrStatus.DRAINING]: [AiSrvrStatus.AVAILABLE, AiSrvrStatus.DISABLED],
  [AiSrvrStatus.DISABLED]: [AiSrvrStatus.AVAILABLE],
};
