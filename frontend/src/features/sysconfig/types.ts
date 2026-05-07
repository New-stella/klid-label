// 시스템 설정 도메인 타입

export const ConfigKey = {
  FFMPEG_THREADS: 'FFMPEG_THREADS',
  FFMPEG_OUTPUT_FPS: 'FFMPEG_OUTPUT_FPS',
  BATCH_INTERVAL_SEC: 'BATCH_INTERVAL_SEC',
  BATCH_CONCURRENCY: 'BATCH_CONCURRENCY',
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
