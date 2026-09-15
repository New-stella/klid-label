// 온디맨드 자동 추적 API 클라이언트.
//
// BE: POST /v1/frames/{srcSn}/yolo-track (포털 채널은 POST /v1/portal/frames/{srcSn}/yolo-track — 본문·응답 동일) — 정렬된 프레임 시퀀스를 추론 서버 트래커로 프록시해
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
// @design API-123, API-254, SCREEN-005, SCREEN-029, UC-034

import { aiFramePath } from '@/lib/api/aiRoutes';
import { apiClient } from '@/lib/api/client';

import { aiWaitTimeoutMs } from '../aiBudget';
import { AI_REQUEST_ID_HEADER, SAM2_TRACK_MAX_FRAMES_PER_REQUEST } from '../api';

/**
 * 뒤따르는 프레임 상한 — **기존 추적 경로와 같은 값**이다. 두 경로 모두 BE 요청 record 의
 * `@Size(max = 50)`(CWE-770 안전상한)에서 오므로 리터럴을 새로 적지 않고 그 상수를 참조한다.
 *
 * ⚠ **화면이 스스로 나누지 않는다** — 이 경로는 요청마다 트래커 상태가 리셋되고 객체 ID 도 다시
 *   매겨지므로, 나눌수록 같은 객체가 여러 트랙으로 흩어진다. 그래서 **상한까지 한 번에** 싣고,
 *   상한을 넘는 구간은 잘라 보내며 그 사실을 사용자에게 알린다(조용히 버리지 않는다).
 *   예산이 프레임 수를 흡수하므로(`aiBudget` 의 `perFrameSec = 0`) 한 번에 실어도 대기가 프레임
 *   수에 비례해 늘지 않는다.
 *
 * ⚠⚠ 서버가 요청 하나의 시간 예산을 다 쓰면 **그때까지의 결과 + 이어 보낼 지점**을 돌려준다
 *   (`truncated`/`resume`). 그 경우에만 {@link requestAutoTrackAll} 이 이어 보내며, 이어 보낸
 *   조각의 객체 번호는 앞 조각과 **합치지 않는다**(같은 번호라도 같은 객체라는 근거가 없다).
 */
export const AUTO_TRACK_MAX_NEXT_FRAMES = SAM2_TRACK_MAX_FRAMES_PER_REQUEST;

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

/** BE `YoloTrackResponseDto.FrameDetections` 와 1:1(+ 화면쪽 조각 표식). */
export interface AutoTrackFrame {
  srcSn: number;
  /** 시퀀스 내 순서(0-base, 0 = 시작 프레임). */
  frameIndex: number;
  detections: AutoTrackDetection[] | null;
  /**
   * 이 프레임을 가져온 **요청 조각 번호**(0 = 첫 요청). 서버 응답에 없는 **화면쪽 표식**이며
   * {@link requestAutoTrackAll} 이 붙인다.
   *
   * ★ 왜 필요한가 — 요청마다 트래커가 리셋돼 **객체 번호가 다시 매겨진다**. 조각이 다르면 번호가
   *   같아도 같은 객체라는 근거가 없으므로, 이 표식이 없으면 서로 다른 객체가 한 트랙으로 합쳐져
   *   그대로 저장된다(`utils/autoTrackResult` 가 이 값으로 묶음을 가른다).
   */
  trackSegment?: number;
}

/**
 * 이어 보내기 값 — **다음 요청 본문에 그대로 옮겨 담는다**(필드명이 요청과 같다).
 *
 * `srcSn` 은 아직 처리하지 않은 **첫** 프레임이며, 그 요청에서 트래커 리셋 프레임이 된다.
 */
export interface AutoTrackResume {
  srcSn: number;
  nextSrcSns: number[];
}

/**
 * BE `YoloTrackResponseDto` 와 1:1.
 *
 * ★ **응답 메시지(ApiResponse.message)를 읽지 않는다** — 이 경로는 문구를 싣지 않기 때문이다.
 *   서버가 예산 절단을 문구로 알리던 동작은 폐기됐고, 남은 몫은 아래 `truncated`/`resume` 라는
 *   구조화된 값으로만 말한다(화면은 그 값으로 실행 전체 기준의 남은 수를 센다).
 *
 *   ⚠ 기존 추적(`sam2-track`) 경로와 «다른 처리» 가 아니다 — 그쪽 통로에는 **mock 안내**가 오므로
 *   그것을 전부 모으고(절단 여부로 거르지 않는다), 이 경로는 그 mock 안내조차 없어 결과적으로 모을
 *   것이 없다. 두 경로 모두 **판단은 구조화된 값으로만** 한다는 점에서 같은 규칙이다.
 */
export interface AutoTrackResponse {
  frames: AutoTrackFrame[] | null;
  /**
   * 요청 단위 시간 예산이 다해 **처리하지 못한 프레임이 남았는가**(200 · 취소가 아니다).
   * 이 값을 읽지 않으면 남은 프레임의 검출이 **조용히 사라진다**.
   */
  truncated?: boolean;
  /** 이어 보낼 요청 값. `truncated` 가 아니면 null 이다. */
  resume?: AutoTrackResume | null;
}

/**
 * 온디맨드 자동 추적 실행. 시작 프레임(srcSn) + 뒤따르는 프레임 구간만으로 실행된다.
 *
 * @param nextSrcSns 뒤따르는 프레임 PK(정렬됨). {@link AUTO_TRACK_MAX_NEXT_FRAMES} 를 넘는 뒤쪽은
 *                   잘라 보낸다(호출측이 절단 사실을 안내한다).
 * @param signal     취소 신호. 화면이 취소하면 서버로 가는 요청 자체를 끊는다 — 화면 안에서만
 *                   폐기하면 서버는 프레임 순회를 끝까지 돌며 추론 자원을 물고 있는다.
 * @param portal     포털 채널이면 포털 전용 창구를 부른다(경로 조립은 {@link aiFramePath} 한 곳).
 */
export function requestAutoTrack(
  srcSn: number,
  nextSrcSns: readonly number[],
  signal?: AbortSignal,
  requestId?: string,
  portal = false,
): Promise<AutoTrackResponse> {
  const frames = nextSrcSns.slice(0, AUTO_TRACK_MAX_NEXT_FRAMES);
  return apiClient
    .post<AutoTrackResponse>(
      aiFramePath(portal, srcSn, 'yolo-track'),
      { srcSn, nextSrcSns: frames },
      {
        // ★ 종전에는 제한시간을 전혀 싣지 않아 공용 기본값(30초)이 걸려 있었다 — 서버가 프레임을
        //   순회하며 프레임마다 추론을 돌리는(`YoloTrackService` 의 프레임 루프) 경로라, 구간이
        //   조금만 길어져도 **정상 처리 중에 «AI 실패»** 로 보였다.
        // ⚠ 예산은 **실제로 보내는 프레임 수**로 계산한다. 잘려 나갈 프레임까지 넣으면 보내지도
        //   않는 일에 제한시간을 주게 된다.
        timeout: aiWaitTimeoutMs('autoTrack', frames.length),
        signal,
        // 취소 식별자 — 없으면 헤더를 붙이지 않는다(빈 값은 서버가 형식 위반으로 버린다).
        headers: requestId ? { [AI_REQUEST_ID_HEADER]: requestId } : undefined,
      },
    )
    .then((r) => r.data);
}

/**
 * 이어 보내기 도중 요청 하나가 **실패**했음을 나타내는 오류.
 *
 * ★ 왜 필요한가 — 서버가 예산 때문에 잘라 보내면 화면이 이어 보내는데, 그 도중 한 요청이 실패하면
 *   **이미 받아 둔 앞 조각의 검출까지 함께 사라진다**(누적한 지역 변수가 예외와 함께 버려진다).
 *   서버가 이미 계산해 돌려준 결과를 버리고 사용자에게 같은 일을 다시 시키는 것이라, 이 라운드가
 *   없애려던 「조용한 폐기 + 중복 재실행」이 그대로 재발한다. 그래서 실패해도 **성공분을 실어**
 *   던지고 호출측이 그것을 살린다(롤백하지 않는다).
 *
 * ⚠ 기존 추적(`sam2-track`) 경로의 {@link import('../api').Sam2TrackChunkError} 와 **같은 모양**이다 —
 *   두 경로가 부분 실패를 다르게 다루면 한쪽만 고쳐지며 조용히 갈라진다.
 */
export class AutoTrackPartialError extends Error {
  /**
   * 실패 시점까지 받은 결과. `frames` 는 성공 확정분이고, `truncated`/`resume` 는 **손대지 못한
   * 구간**을 그대로 가리킨다(감추지 않는다 — 첫 요청부터 실패했으면 `frames` 는 빈 목록이다).
   */
  readonly partial: AutoTrackResponse;
  /** 정상 응답을 받은 요청 수(= 실패한 요청의 조각 번호). */
  readonly completedRequests: number;

  constructor(cause: unknown, partial: AutoTrackResponse, completedRequests: number) {
    super('AI 자동 추적 일부 구간 실패', { cause });
    this.name = 'AutoTrackPartialError';
    this.partial = partial;
    this.completedRequests = completedRequests;
  }
}

/** 이어 보내기 실행 옵션. */
export interface AutoTrackRunOptions {
  /** 취소 신호 — **모든 조각**에 싣고, 취소된 뒤에는 다음 조각을 보내지 않는다. */
  signal?: AbortSignal;
  /** 서버 취소 식별자 — 모든 조각에 같은 값을 실어 취소 한 번이 전부를 끊게 한다. */
  requestId?: string;
  /**
   * (**처리한** 프레임 수, 전체 시퀀스 수) — 응답 하나마다 호출된다(이어 보내는 중에도 갱신).
   * 전체 수는 시작 프레임을 포함한 시퀀스 길이다(서버가 시작 프레임도 같은 루프에서 훑는다).
   */
  onProgress?: (done: number, total: number) => void;
  /**
   * 포털 채널이면 포털 전용 창구로 보낸다. **이어 보내는 조각 전부**가 같은 창구로 가야 한다 —
   * 첫 조각만 포털로 보내면 이어 보내기가 내부 창구에서 403 으로 끊긴다.
   */
  portal?: boolean;
}

/**
 * 자동 추적을 **끝까지** 실행한다 — 서버가 예산 때문에 잘라 보내면 이어 보낸다.
 *
 * ★ `truncated`/`resume` 를 읽지 않으면 남은 프레임의 검출이 **조용히 사라진다**. 판단은 이 두
 *   필드로만 하며 안내 문구를 파싱하지 않는다.
 *
 * ⚠ **진행이 0 인 응답이 올 수 있다**(예산이 한 프레임도 담지 못하는 설정). 그때 `resume` 은 방금
 *   보낸 요청과 같아서 그대로 되보내면 **무한 재요청**이 된다. 그래서 «남은 프레임이 줄었는가» 를
 *   확인하고, 줄지 않으면 멈춘 뒤 못 끝냈다는 사실을 결과에 담아 돌려준다(감추지 않는다).
 *
 * ⚠ 이어 보낸 조각의 프레임에는 조각 번호({@link AutoTrackFrame.trackSegment})를 붙인다 — 트래커가
 *   리셋돼 객체 번호가 다시 매겨지므로, 붙이지 않으면 서로 다른 객체가 한 트랙으로 합쳐진다.
 */
export async function requestAutoTrackAll(
  srcSn: number,
  nextSrcSns: readonly number[],
  opts: AutoTrackRunOptions = {},
): Promise<AutoTrackResponse> {
  const { signal, requestId, onProgress, portal = false } = opts;
  const frames: AutoTrackFrame[] = [];

  let curSrcSn = srcSn;
  let curNext = nextSrcSns.slice(0, AUTO_TRACK_MAX_NEXT_FRAMES);
  // 시퀀스 = [시작 프레임] + 후속. 서버도 같은 시퀀스를 한 루프에서 훑는다.
  const total = 1 + curNext.length;
  // 아직 처리하지 않은 시퀀스 길이 — 이 값이 줄어드는 것이 «진행» 의 정의다.
  let remaining = total;
  let segment = 0;

  for (;;) {
    // 취소 뒤에는 **다음 요청을 만들지 않는다** — 사용자가 멈춘 뒤 서버가 또 도는 것을 막는다.
    if (signal?.aborted) {
      throw new DOMException('사용자가 자동 추적을 취소했습니다', 'AbortError');
    }
    let res: AutoTrackResponse;
    try {
      res = await requestAutoTrack(curSrcSn, curNext, signal, requestId, portal);
    } catch (err) {
      // ★ 부분 실패 — 앞 조각에서 이미 받은 검출을 실어 던진다. 여기서 그냥 던지면 서버가 이미
      //   계산해 돌려준 최대 50프레임의 검출이 통째로 버려지고, 화면은 실패 안내만 띄운다.
      //   못 한 구간(이 요청이 실으려던 값)도 그대로 담아 «어디부터 안 됐는지» 를 감추지 않는다.
      throw new AutoTrackPartialError(
        err,
        {
          frames: [...frames],
          truncated: true,
          resume: { srcSn: curSrcSn, nextSrcSns: [...curNext] },
        },
        segment,
      );
    }
    for (const frame of res.frames ?? []) {
      // 조각 표식은 화면이 붙인다(서버 응답에 없는 값이다). 첫 조각은 0 이라 종전과 같다.
      frames.push(segment === 0 ? frame : { ...frame, trackSegment: segment });
    }

    const resume = autoTrackResumeOf(res);
    const left = resume === null ? 0 : 1 + resume.nextSrcSns.length;
    if (resume !== null && left >= remaining) {
      // 진행이 없다 — 되보내면 같은 응답이 무한히 돌아온다. 못 끝냈다는 사실을 그대로 돌려준다.
      onProgress?.(total - remaining, total);
      return { frames, truncated: true, resume };
    }
    remaining = left;
    onProgress?.(total - remaining, total);
    if (resume === null) {
      return { frames, truncated: false, resume: null };
    }
    segment += 1;
    curSrcSn = resume.srcSn;
    curNext = resume.nextSrcSns;
  }
}

/**
 * 응답의 이어 보내기 값을 **쓸 수 있을 때만** 돌려준다(못 쓰면 null → 여기서 멈춘다).
 *
 * ⚠ 값을 지어내지 않는다 — 시작 프레임이 없으면 이어 보낼 자리를 알 수 없고, 화면이 임의로
 *   고르면 엉뚱한 구간을 다시 훑는다.
 */
function autoTrackResumeOf(res: AutoTrackResponse): AutoTrackResume | null {
  if (!res.truncated) return null;
  const resume = res.resume;
  if (!resume) return null;
  const { srcSn, nextSrcSns } = resume;
  if (typeof srcSn !== 'number' || !Number.isFinite(srcSn)) return null;
  return { srcSn, nextSrcSns: Array.isArray(nextSrcSns) ? nextSrcSns : [] };
}
