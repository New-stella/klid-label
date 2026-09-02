/**
 * 포털 업로드 영상 마킹의 <b>순수 계산</b> — 지점 산출·환산·요청 조립.
 * [@design SCREEN-045] [@design API-240]
 *
 * <h3>왜 통신 함수와 갈라 두나</h3>
 * 이 저장소에는 「판정 함수와 HTTP 함수를 한 파일에 두었다가 그 파일을 모듈째 모의해 판정이
 * 통째로 `undefined` 가 된」 전례가 있다. 여기 있는 것은 전부 순수 함수이고 통신은
 * `markingApi` 가 갖는다 — 화면 시험이 통신만 모의해도 계산은 그대로 살아 있어야 한다.
 *
 * <h3>서버가 진실원이고 여기 있는 것은 미리보기다</h3>
 * 자동 지점은 저장할 때 <b>서버가 다시 산출</b>한다(요청에는 간격만 실린다). 화면이 같은 규칙으로
 * 한 번 더 계산하는 것은 「저장하기 전에 무엇이 뽑힐지 보여 준다」는 사양 때문이며, 그래서 규칙이
 * 서버와 같아야 한다 — 총 프레임 수는 `round(길이 × 초당프레임)`, 지점은 0 부터 간격만큼 더해 가며
 * 총 프레임 수 <b>미만</b>까지, 표시용 시각은 `frameIndex / fps` 의 <b>정수부</b>를 `mm:ss` 로 적는다.
 * ⚠ 이 규칙을 「보기 좋게」 바꾸지 말 것 — 화면이 예고한 장수와 실제로 뽑히는 장수가 갈린다.
 *
 * <h3>추출 장수 상한은 여기 두지 않는다</h3>
 * 상한은 서버 설정값이고 화면에 전달하는 경로가 아직 없다. 값을 여기 베껴 두면 <b>두 번째
 * 진실원</b>이 되어 설정이 바뀌는 순간 화면이 거짓을 말한다. 그래서 상한은 <b>알면 넘겨받고</b>
 * (아래 `capApplied`) 모르면 아무 주장도 하지 않는다.
 */
import { PortalMarkingMode } from './markingTypes';
import type { MarkItem, PortalMarkingSaveRequest } from './markingTypes';

/**
 * 자동 간격 기본값(프레임 수). 관제 채널 자동 마킹의 기본값과 같은 값이라 두 채널의 자동 마킹이
 * 같은 눈금에서 시작한다. 사용자가 바꾼 값은 그 영상의 마킹에만 쓰이고 운영 기본값을 바꾸지 않는다.
 */
export const PORTAL_AUTO_INTERVAL_DEFAULT_FRAMES = 300;

/**
 * 타임라인·목록에 <b>그리는</b> 지점의 상한. 저장에는 아무 영향이 없다 — 자동은 지점 배열을
 * 보내지 않고 간격만 보내므로, 여기서 솎아 낸 것이 요청에 실릴 수 없다.
 *
 * ⚠ 이 값은 서버의 <b>추출 장수 상한과 무관</b>하다. 간격을 1 처럼 잡으면 긴 영상에서 지점이
 *   수만 개가 되어 화면이 멈추기 때문에 두는 표시 한도이며, 안내에 적는 장수는 언제나
 *   {@link autoMarkCount} 가 낸 <b>정확한 값</b>이다(솎아 낸 수가 아니다).
 */
export const AUTO_MARK_RENDER_LIMIT = 600;

/** `mm:ss` 표시용 시각. 서버와 같은 규칙(정수 초, 두 자리 채움)을 쓴다. */
export function formatMarkTimestamp(totalSec: number): string {
  const safe = Number.isFinite(totalSec) && totalSec > 0 ? Math.floor(totalSec) : 0;
  const mm = String(Math.floor(safe / 60)).padStart(2, '0');
  const ss = String(safe % 60).padStart(2, '0');
  return `${mm}:${ss}`;
}

/**
 * 영상의 총 프레임 수. 서버와 같은 규칙이다 — 길이(초)를 <b>정수로 반올림한 뒤</b> 초당 프레임 수를
 * 곱해 다시 반올림한다. 길이나 초당 프레임 수를 모르면 0.
 *
 * ⚠ 길이를 먼저 정수로 반올림하는 것은 서버가 그렇게 하기 때문이다(원장의 길이를 `round` 해
 *   정수 초로 다룬다). 소수 그대로 곱하면 경계에서 장수가 1 씩 갈린다.
 */
export function totalFrameCount(durationSec: number | null | undefined, fps: number): number {
  if (durationSec === null || durationSec === undefined) return 0;
  if (!Number.isFinite(durationSec) || durationSec <= 0) return 0;
  if (!Number.isFinite(fps) || fps <= 0) return 0;
  return Math.round(Math.round(durationSec) * fps);
}

/** 자동 방식으로 뽑히는 <b>정확한</b> 지점 수. 상한 절단은 반영하지 않는다(그건 서버가 판정한다). */
export function autoMarkCount(totalFrames: number, intervalFrames: number): number {
  if (totalFrames <= 0) return 0;
  if (!Number.isInteger(intervalFrames) || intervalFrames < 1) return 0;
  return Math.ceil(totalFrames / intervalFrames);
}

/** 자동 지점 미리보기 결과. */
export interface AutoMarkPreview {
  /** 화면에 그릴 지점. 수가 표시 한도를 넘으면 전 구간에 고르게 퍼지도록 솎아 낸 것이다. */
  marks: MarkItem[];
  /** 실제로 뽑히는 정확한 지점 수 — 안내 문구는 언제나 이 값을 쓴다. */
  count: number;
  /** 표시 한도에 걸려 그림만 솎였는지 여부. 저장 결과와는 무관하다. */
  renderSampled: boolean;
}

/**
 * 자동 방식의 지점 미리보기.
 *
 * 표시 한도를 넘으면 앞에서 자르지 않고 <b>전 구간에 고르게</b> 솎는다 — 앞쪽만 남기면 타임라인이
 * 영상 앞부분만 덮는 것처럼 보여, 자동의 목적(전 구간 균등 덮기)과 정반대로 읽힌다.
 */
export function buildAutoMarks(
  durationSec: number | null | undefined,
  fps: number,
  intervalFrames: number,
): AutoMarkPreview {
  const totalFrames = totalFrameCount(durationSec, fps);
  const count = autoMarkCount(totalFrames, intervalFrames);
  if (count === 0) return { marks: [], count: 0, renderSampled: false };

  const at = (i: number): MarkItem => {
    const frameIndex = i * intervalFrames;
    return { frameIndex, timestamp: formatMarkTimestamp(frameIndex / fps) };
  };

  if (count <= AUTO_MARK_RENDER_LIMIT) {
    return { marks: Array.from({ length: count }, (_, i) => at(i)), count, renderSampled: false };
  }
  const marks = Array.from({ length: AUTO_MARK_RENDER_LIMIT }, (_, i) =>
    at(Math.floor((i * count) / AUTO_MARK_RENDER_LIMIT)),
  );
  return { marks, count, renderSampled: true };
}

/** 간격(프레임 수)이 이 영상에서 몇 초에 해당하는지. 초당 프레임 수를 모르면 `null`. */
export function intervalSeconds(intervalFrames: number, fps: number | null | undefined): number | null {
  if (fps === null || fps === undefined) return null;
  if (!Number.isFinite(fps) || fps <= 0) return null;
  if (!Number.isInteger(intervalFrames) || intervalFrames < 1) return null;
  return intervalFrames / fps;
}

/** 절단 판정 결과 — 상한을 모르면 `known: false` 이며 아무 주장도 하지 않는다. */
export interface CapOutcome {
  known: boolean;
  truncated: boolean;
  /** 실제로 뽑히는 장수. 상한을 모르면 요청한 수 그대로다. */
  effectiveCount: number;
  requestedCount: number;
}

/**
 * 추출 장수 상한을 요청 지점 수에 적용한다.
 *
 * ⚠ `maxFrames` 를 <b>모를 때가 정상</b>이다 — 그 값을 화면에 전달하는 경로가 아직 없다. 모르면
 *   `known:false` 로 돌려주고, 화면은 절단을 예고하지 않는다(지어내지 않는다). 그 대신 저장
 *   응답이 잘림 여부와 잘리기 전 수를 함께 돌려주므로 사실이 사후에라도 유실되지 않는다.
 */
export function capApplied(requestedCount: number, maxFrames: number | null | undefined): CapOutcome {
  const requested = Math.max(0, requestedCount);
  if (maxFrames === null || maxFrames === undefined || !Number.isFinite(maxFrames) || maxFrames < 1) {
    return { known: false, truncated: false, effectiveCount: requested, requestedCount: requested };
  }
  const cap = Math.floor(maxFrames);
  if (requested <= cap) {
    return { known: true, truncated: false, effectiveCount: requested, requestedCount: requested };
  }
  return { known: true, truncated: true, effectiveCount: cap, requestedCount: requested };
}

/**
 * 저장 요청 본문 조립의 <b>단일 진입점</b>.
 *
 * ★ 방식에 쓰이지 않는 칸은 아예 만들지 않는다 — 자동에 지점 배열을 실으면 사용자가 고르지도
 *   않은 값이 요청에 들어가고, 수동에 간격을 실으면 서버가 쓰지 않는 값이 기록으로 남는다.
 * ★ 호출부가 본문을 직접 만들지 말 것. 도구가 늘 때마다 같은 칸을 빠뜨리는 것을 막으려고
 *   이 자리를 하나로 뒀다(라벨 생성 payload 를 한 헬퍼로 모은 것과 같은 관례).
 */
export function buildMarkingSaveRequest(
  mode: PortalMarkingMode,
  intervalFrames: number,
  marks: MarkItem[],
): PortalMarkingSaveRequest {
  if (mode === PortalMarkingMode.AUTO) {
    return { mode: PortalMarkingMode.AUTO, interval: intervalFrames };
  }
  return { mode: PortalMarkingMode.MANUAL, marks };
}
