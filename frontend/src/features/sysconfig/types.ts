// 시스템 설정 도메인 타입

export const ConfigKey = {
  BATCH_INTERVAL_SEC: 'BATCH_INTERVAL_SEC',
  BATCH_CONCURRENCY: 'BATCH_CONCURRENCY',
  // Phase 1: YOLO 추론 파라미터 (BE ConfigKeys 와 1:1 매핑)
  //   ⚠ 구 키 `YOLO_IMGSZ`(추론 입력 해상도)는 폐지됐다 — 추론 서버가 입력 크기를 640 으로
  //     고정해 쓰므로 값을 바꿔도 결과가 달라지지 않는, 조정되는 척하는 설정이었다.
  //     되살리려면 추론 서버가 요청값을 실제로 쓰도록 먼저 고칠 것. (BE ConfigKeys javadoc)
  YOLO_CONF_THRESHOLD: 'YOLO_CONF_THRESHOLD',
  YOLO_IOU: 'YOLO_IOU',
  // FEAT-007: 라벨링 정밀도 — 경계 세밀함 (Douglas-Peucker epsilon, DECIMAL)
  POLYGON_SIMPLIFY_TOLERANCE: 'POLYGON_SIMPLIFY_TOLERANCE',
  // R9: 비식별 옵션 (BE ConfigKeys 와 1:1 매핑 — dotted 소문자 키)
  //   ⚠ 이 세 키는 점(.)이 들어간다. react-hook-form 의 필드 이름으로 그대로 쓰면 점을
  //     중첩 객체 경로로 해석하므로, 폼에서는 점 없는 별칭을 쓰고 전송 시점에만 이 키로 매핑한다.
  KPST_DEID_MASKING_TYPE: 'kpst.deid.masking-type',
  KPST_DEID_MASKING_RANGE: 'kpst.deid.masking-range',
  KPST_DEID_DB_SAVE: 'kpst.deid.db-save',
  // R11: 연동 서버 주소 4종 (STRING).
  //   ⚠ 키 이름이 BE 애플리케이션 속성명과 **같다**. 별도 키명을 만들면 매핑표가 두 번째
  //     진실원이 되어, 한쪽만 갱신되는 순간 화면에서 바꾼 주소가 엉뚱한 연동에 반영된다.
  //   ⚠ 값이 문자열이라 `useConfigs`(숫자 변환)로는 읽히지 않는다 → `useConfigStrings` 를 쓴다.
  //   ⚠ 비식별 키가 `kpst.deid.base-url` 인 것은 **의도**다. 구 키
  //     `authoring.integration.deidentify.base-url` 은 주입 대상 0건인 빈을 구동해 실효가 없었다.
  // ADR-050: 시계열 위탁 전체 건너뛰기 (BOOLEAN 'true'/'false' + STRING 사유).
  //   ⚠ 위 비식별 키와 같은 이유로 dotted 다 — 폼 필드 이름으로 그대로 쓰지 말고 점 없는
  //     별칭을 쓴 뒤 전송 시점에만 이 키로 매핑한다.
  //   ⚠ 값이 문자열이라 `useConfigs`(숫자 변환)로는 읽히지 않는다 → `useConfigStrings` 를 쓴다.
  BATCH_VLM_SKIP_BY_DEFAULT: 'batch.vlm.skip-by-default',
  BATCH_VLM_SKIP_BY_DEFAULT_REASON: 'batch.vlm.skip-by-default-reason',
  KPST_DEID_BASE_URL: 'kpst.deid.base-url',
  INTEGRATION_AI_SERVER_BASE_URL: 'authoring.integration.ai-server.base-url',
  VLM_CLIENT_URL: 'vlm.client.url',
  CONTROL_NOTIFY_URL: 'authoring.control-notify.url',
} as const;
export type ConfigKey = (typeof ConfigKey)[keyof typeof ConfigKey];

/**
 * BE 응답(ConfigResponse) 1:1 매핑.
 *
 * R3-2 근본 원인: 이전에는 `{ key, value }` 로 가정했으나 BE 는 `configKey` / `configVl`(문자열) 을
 * 반환한다. 필드명이 어긋나 GET 응답이 항상 undefined 로 매핑되어 슬라이더가 저장값을 잃고
 * 기본값으로 폴백했다(저장 안 됨으로 오인). BE DTO 와 동일 필드명으로 정렬한다.
 *
 * configVl 은 varchar 이므로 문자열이며(예: "2", "0.5"), 소비처에서 Number 로 변환한다.
 */
export interface ConfigItem {
  configKey: ConfigKey | string;
  configVl: string;
  configTypeCd?: string;
  expln?: string;
  mdfrId?: string;
  mdfcnDt?: string;
}

export interface ConfigUpdateRequest {
  key: ConfigKey | string;
  /** R11 이전에는 숫자만 있었다. 연동 주소는 문자열이라 두 타입을 모두 받는다. */
  value: number | string;
  /**
   * R11 — 관리자 단기 유효창 토큰. 연동 주소 4종을 저장할 때만 필요하다.
   *
   * ⚠ 브라우저 저장소(localStorage/sessionStorage)에 두지 않는다. 화면 상태로만 들고 있다가
   *   요청 헤더로 실어 보낸다 — 저장소에 두면 XSS 한 번으로 유효창이 통째로 넘어간다.
   */
  adminSessionToken?: string;
}

/**
 * R11 — 관리자 단기 유효창.
 *
 * `expiresAt` 은 **표시용**이다. 유효성 판정은 서버가 저장 요청마다 다시 한다(만료 시각이 토큰
 * 서명 대상 안에 들어 있어 클라이언트가 늘릴 수 없다).
 */
export interface AdminSession {
  token: string;
  /** ISO-8601 UTC */
  expiresAt: string;
}

/**
 * BE 응답(AiDefaultsResponse) 1:1 매핑 — `GET /v1/ai-defaults`.
 *
 * 값 두 개뿐이며 운영 메타(수정자·수정일시)는 담기지 않는다. 저장값이 없거나 숫자로 해석되지
 * 않는 항목은 응답에서 **생략**되므로 optional 이고, 소비처는 자체 기본값으로 폴백한다.
 */
export interface AiDefaults {
  /** 인식 민감도 초기값 — 정수 백분율(예: 25). 화면에서 /100 변환해 사용. */
  confThreshold?: number;
  /** 경계 세밀함 초기값 — 실수(Douglas-Peucker epsilon px). */
  simplifyTolerance?: number;
  /**
   * 작업 종류별 **대기 예산** — 화면이 AI 추론을 얼마나 기다릴지의 진실원.
   *
   * ★ 추론이 실제로 얼마나 걸리는지는 서버 설정(호출 상한·재시도 횟수·백오프)이 정하고 운영
   *   중에 바뀐다. 화면이 그 값을 상수로 베껴 두면 서버 예산이 바뀔 때 화면만 조용히 어긋나고,
   *   «화면이 더 짧은» 방향이면 **정상 동작이 «AI 실패» 로 보인다**.
   *
   * 종류·필드 모두 **생략 가능**하다 — 못 받은 항목은 화면이 폴백을 쓴다(계약을 아직 내려주지
   * 않는 서버 형상에서도 종전대로 동작해야 하므로). 계산·검증은 `features/label/aiBudget` 이
   * 단독으로 판정하며 이 타입은 수신 형태만 선언한다.
   */
  waitBudgets?: AiWaitBudgets;
}

/**
 * 작업 종류별 대기 예산의 수신 형태.
 *
 * ⚠ 종류 키는 엔드포인트 의미를 따른다 — `autolabel`(`/autolabel`) · `segment`(`/sam2-segment`) ·
 *   `sam2Track`(`/sam2-track`) · `autoTrack`(`/yolo-track`).
 */
export interface AiWaitBudgets {
  autolabel?: AiWaitBudgetItem;
  segment?: AiWaitBudgetItem;
  sam2Track?: AiWaitBudgetItem;
  autoTrack?: AiWaitBudgetItem;
}

/** 한 종류의 예산. 제한시간(초) = min(baseSec + perFrameSec × 프레임수, ceilingSec). */
export interface AiWaitBudgetItem {
  /** 고정 비용(초) — 프레임 수와 무관한 몫. */
  baseSec?: number;
  /** 프레임 1건당 가산분(초) — 프레임을 훑지 않는 작업은 0. */
  perFrameSec?: number;
  /** 한 요청이 넘지 말아야 할 절대 상한(초). */
  ceilingSec?: number;
}

export type ConfigMap = Record<string, number>;

/**
 * 문자열 값 그대로의 설정 맵 (R11).
 *
 * `ConfigMap` 은 `Number()` 변환에 실패한 값을 **버린다**(숫자 설정만 다루던 시절의 규칙). 연동
 * 주소는 문자열이라 그 경로로는 화면에 도달하지 못하므로 원문 맵을 따로 둔다.
 */
export type ConfigStringMap = Record<string, string>;

/**
 * BOOLEAN 설정값(문자열 원문) → 참/거짓 — **이 판정의 단일 지점**.
 *
 * ★ 저장 형식은 `'true'`/`'false'` 문자열이다. 화면마다 `=== 'true'` 를 다시 적으면 대소문자·공백
 * 처리가 갈리고, 한쪽만 고쳐지는 순간 같은 설정이 화면마다 다르게 보인다.
 *
 * ⚠ **행이 없으면 거짓**이다 — 응답에는 DB 에 행이 있는 키만 담기므로, 한 번도 저장한 적 없는
 * 키는 여기에 없는 것이 정상이고 그 상태가 곧 꺼짐이다.
 */
export function isConfigOn(value: string | undefined | null): boolean {
  return value?.trim().toLowerCase() === 'true';
}
