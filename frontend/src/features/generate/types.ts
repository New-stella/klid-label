// 배경영상 생성 요청 도메인 타입 (V1.5 — 외부 생성형 AI 시스템 전달용 인터페이스).
//
// SCR-GEN-001 BackgroundGenerateModal에서 영상 행 액션 ✨로 진입.
// 저작도구는 요청만 수행하고 모델 학습/생성은 외부 시스템 책임.

export const BackgroundGenType = {
  WILDFIRE: 'WILDFIRE',
  FLOOD: 'FLOOD',
} as const;
export type BackgroundGenType =
  (typeof BackgroundGenType)[keyof typeof BackgroundGenType];

export interface RequestBackgroundGenerateRequest {
  /** 원본 영상 ID */
  videoId: number;
  /** 선택된 단일 프레임 srcSn (FrameGrid12 단일 라디오) */
  srcSn: number;
  /** 산불/침수 — allowlist (FE 라디오 + BE 강제) */
  genType: BackgroundGenType;
}

export interface RequestBackgroundGenerateResponse {
  jobId: number;
}

export interface BackgroundGenerateJob {
  jobId: number;
  videoId: number;
  cctvName: string;
  srcSn: number;
  frameNo?: number;
  genType: BackgroundGenType;
  requestedAt: string;
}
