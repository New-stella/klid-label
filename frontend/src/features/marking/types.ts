export const MarkingMode = {
  AUTO: 'AUTO',
  MANUAL: 'MANUAL',
} as const;
export type MarkingMode = (typeof MarkingMode)[keyof typeof MarkingMode];

export const MarkingStatus = {
  PENDING: 'PENDING',
  VLM_REQUESTED: 'VLM_REQUESTED',
  VLM_COMPLETED: 'VLM_COMPLETED',
} as const;
export type MarkingStatus = (typeof MarkingStatus)[keyof typeof MarkingStatus];

export interface MarkItem {
  frameIndex: number;
  timestamp: string | null;
}

export interface MarkingRequest {
  // 이벤트명(eventName)은 더 이상 요청에 포함하지 않는다 — 서버가 영상의
  // evntTypeCd 에서 자동 소싱한다(API-047 계약 변경).
  // BE 계약(MarkingRequest.mode, @NotBlank)에 맞춘 요청 필드명.
  // 응답 DTO(MarkingResponse)는 `markingMode` 로 내려오므로 요청/응답 필드명이 다른 점에 주의.
  mode: MarkingMode;
  intervalFrames?: number;
  marks?: MarkItem[];
}

export interface MarkingResponse {
  markingSn: number;
  rawSn: number;
  eventName: string;
  markingMode: MarkingMode;
  intervalFrames: number | null;
  videoPath: string;
  marks: MarkItem[];
  status: MarkingStatus;
  createdAt: string;
  /**
   * 후속 배치가 실제로 시작됐는지 (DEV_FIX H11). BE MarkingResponse.batchTriggered 와 1:1.
   * null = 판정 불가(구 서버/브리지 미실행). false 면 배치가 시작되지 않았으므로 성공 문구를 쓰면 안 된다.
   */
  batchTriggered?: boolean | null;
  /** 배치 미시작 사유(고정 문구). batchTriggered=false 일 때만 채워진다. */
  batchSkipReason?: string | null;
}
