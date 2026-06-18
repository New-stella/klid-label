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
}
