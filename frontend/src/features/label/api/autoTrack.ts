// 온디맨드 자동 추적 API 클라이언트.
//
// BE: POST /v1/frames/{srcSn}/yolo-track — 정렬된 프레임 시퀀스를 추론 서버 트래커로 프록시해
//     프레임별 객체 검출 + 트래커 객체 ID 를 받는다. **DB 저장은 하지 않는다**(화면이 결과를
//     작업본에 올리고 확정은 라벨 저장으로 한다).
//
// ★ 시작 객체를 싣지 않는다 — 기존 추적(`sam2-track`)은 사용자가 고른 객체(trackId·prevPolygon·
//   label)를 전파하는 경로라 그 셋이 필수지만, 이 경로는 **아무 객체도 고르지 않은 상태**에서
//   프레임 구간만으로 실행된다. 요청 본문은 `srcSn` + `nextSrcSns` 둘뿐이며, 여기에 시작 객체
//   필드를 더하면 화면이 없는 계약을 만들어 보내는 것이 된다.
//
// 보안: srcSn 은 path(axios 자동 인코딩), 프레임 목록은 body. IDOR·본인 배정 검증·입력 상한은
//       BE 책임이며 FE 상한은 그 상한을 넘겨 400 을 받는 왕복을 없애는 보조 방어다.
//
// @design API-123, SCREEN-005, UC-034

import { apiClient } from '@/lib/api/client';

import { SAM2_TRACK_CHUNK_SIZE } from '../api';

/**
 * 뒤따르는 프레임 상한 — **기존 추적 경로와 같은 값**이다. 두 경로 모두 BE 요청 record 의
 * `@Size(max = 50)`(CWE-770 안전상한)에서 오므로 리터럴을 새로 적지 않고 그 상수를 참조한다.
 *
 * ⚠ 기존 추적처럼 청크로 쪼개 이어붙이지 않는다 — 이 경로는 요청마다 트래커 상태가 리셋되고
 *   객체 ID 도 다시 매겨지므로, 청크를 이어붙이면 **서로 다른 객체가 같은 ID 로 합쳐진다**.
 *   상한을 넘는 구간은 잘라 보내고 그 사실을 사용자에게 알린다(조용히 버리지 않는다).
 */
export const AUTO_TRACK_MAX_NEXT_FRAMES = SAM2_TRACK_CHUNK_SIZE;

/** BE `YoloTrackResponseDto.Detected` 와 1:1. */
export interface AutoTrackDetection {
  /** 추론 서버가 돌려준 검출 클래스명(COCO 영문명). */
  label: string;
  /** [x1, y1, x2, y2] (image px). */
  points: number[] | null;
  /** 신뢰도 0.0~1.0 (null 가능). */
  score: number | null;
  /** 트래커가 부여한 객체 ID — null 이면 트랙 없는 단발 검출. */
  trackId: number | null;
  /**
   * 라벨 마스터 PK — **서버가 AI 검출 클래스 축으로 해석해 실어 준 값**이다.
   * 대응 마스터가 없거나 그 클래스에 매핑이 지정돼 있지 않으면 null 이다.
   *
   * ⚠ 화면은 이 값을 **그대로** 저장 payload 에 싣는다. 검출 클래스명으로 마스터를 다시 찾지
   *   않는다 — 그 해석은 서버가 하며, 화면이 다시 판정하면 같은 규칙이 두 곳에 생겨 한쪽이 낡는다.
   */
  labelId: number | null;
}

/** BE `YoloTrackResponseDto.FrameDetections` 와 1:1. */
export interface AutoTrackFrame {
  srcSn: number;
  /** 시퀀스 내 순서(0-base, 0 = 시작 프레임). */
  frameIndex: number;
  detections: AutoTrackDetection[] | null;
}

/** BE `YoloTrackResponseDto` 와 1:1. */
export interface AutoTrackResponse {
  frames: AutoTrackFrame[] | null;
}

/**
 * 온디맨드 자동 추적 실행. 시작 프레임(srcSn) + 뒤따르는 프레임 구간만으로 실행된다.
 *
 * @param nextSrcSns 뒤따르는 프레임 PK(정렬됨). {@link AUTO_TRACK_MAX_NEXT_FRAMES} 를 넘는 뒤쪽은
 *                   잘라 보낸다(호출측이 절단 사실을 안내한다).
 */
export function requestAutoTrack(
  srcSn: number,
  nextSrcSns: readonly number[],
): Promise<AutoTrackResponse> {
  return apiClient
    .post<AutoTrackResponse>(`/frames/${srcSn}/yolo-track`, {
      srcSn,
      nextSrcSns: nextSrcSns.slice(0, AUTO_TRACK_MAX_NEXT_FRAMES),
    })
    .then((r) => r.data);
}
