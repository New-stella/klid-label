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
  /**
   * 작업자가 고른 **검증 이벤트 질문**의 일련번호 — 선택 항목. [@design API-047] [@design SCREEN-006]
   *
   * 목록의 출처는 <b>영상 단건 조회 응답</b>(`VideoDetail.vrfcEvntQuestions`)이다. 관리 화면
   * 경로(`/v1/manage/…`)는 검수자 전용이라 작업자에게 403 이므로 부르지 않는다.
   *
   * ★ 서버는 어긋난 선택값을 <b>거부가 아니라 그 유형의 첫 번째 질문으로 교정</b>한다(400 아님).
   *   그리고 <b>교정 결과를 응답으로 돌려주지 않는다</b> — 화면이 그것을 표시하려 하지 말 것.
   * 고를 질문이 없으면 이 필드를 싣지 않는다(값을 지어내지 않는다).
   */
  vrfcEvntQstnSn?: number;
  /**
   * 작업자가 고른 **검증 이벤트 유형 코드** — 선택 필드. [@design API-047] [@design SCREEN-006]
   *
   * ★ <b>관제 인입에서 유형을 수신한 영상에서는 이 필드를 싣지 않는다</b>. 그런 영상은 화면이
   * 유형 선택을 아예 노출하지 않으며(고를 수 있는 유형 목록이 빈 배열로 온다), 서버도 관제
   * 인입을 1순위로 삼아 이 값을 쓰지 않는다. 두 조달처가 맞붙는 상태를 화면에서부터 만들지 않는다.
   *
   * 값이 있으면 외부 시계열 위탁의 묘사 축 유형과 질문 목록 조달의 유형이 함께 이 값을 탄다 —
   * 그 영상이 시계열 메타를 하나도 받지 못하던 상태가 이 값 하나로 풀린다.
   */
  vrfcEvntTypeCd?: string;
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
