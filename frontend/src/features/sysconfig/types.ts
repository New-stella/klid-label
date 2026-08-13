// 시스템 설정 도메인 타입

export const ConfigKey = {
  BATCH_INTERVAL_SEC: 'BATCH_INTERVAL_SEC',
  BATCH_CONCURRENCY: 'BATCH_CONCURRENCY',
  // Phase 1: YOLO 추론 파라미터 (BE ConfigKeys 와 1:1 매핑)
  YOLO_CONF_THRESHOLD: 'YOLO_CONF_THRESHOLD',
  YOLO_IMGSZ: 'YOLO_IMGSZ',
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
}

export type ConfigMap = Record<string, number>;

/**
 * 문자열 값 그대로의 설정 맵 (R11).
 *
 * `ConfigMap` 은 `Number()` 변환에 실패한 값을 **버린다**(숫자 설정만 다루던 시절의 규칙). 연동
 * 주소는 문자열이라 그 경로로는 화면에 도달하지 못하므로 원문 맵을 따로 둔다.
 */
export type ConfigStringMap = Record<string, string>;
