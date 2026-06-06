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
  eventName: string;
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
