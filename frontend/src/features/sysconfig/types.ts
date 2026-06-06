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
  value: number;
}

export type ConfigMap = Record<string, number>;
