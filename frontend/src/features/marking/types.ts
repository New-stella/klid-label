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
  markingMode: MarkingMode;
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
