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

export interface ConfigItem {
  key: ConfigKey | string;
  value: number;
  updatedAt?: string;
}

export interface ConfigUpdateRequest {
  key: ConfigKey | string;
  value: number;
}

export type ConfigMap = Record<string, number>;
