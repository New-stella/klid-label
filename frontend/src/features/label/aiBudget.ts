// AI 추론 **대기 예산**의 단일 판정기 — 요청 제한시간 · 분할 단위 · 작업잠금 상한이 전부 여기서 나온다.
//
// ─────────────────────────────────────────────────────────────────────────────
// ★ 왜 이 파일이 있나 (화면이 상수를 들고 있으면 안 되는 이유)
//
//   추론이 실제로 얼마나 걸리는지는 **서버가 안다** — 호출 상한·재시도 횟수·백오프가 전부 서버
//   설정이고 운영 중에 바뀐다. 화면이 그 값을 자기 상수로 베껴 두면, 서버 예산이 바뀌는 순간
//   화면만 조용히 어긋난다. 어긋나는 방향이 «화면이 더 짧다» 이면 **정상 동작이 «AI 실패» 로
//   보이고**, 사용자는 같은 일을 다시 시켜 중복 추론으로 자원 소모가 곱해진다.
//
//   그래서 예산의 진실원은 서버 응답(`GET /v1/ai-defaults`)이고, 이 파일은 그것을 **받아 계산만**
//   한다. 화면·훅·API 클라이언트는 숫자를 직접 알지 못한다.
//
// ─────────────────────────────────────────────────────────────────────────────
// ★ 계산식
//
//   제한시간(초) = min(baseSec + perFrameSec × 프레임수, ceilingSec)
//
//   - `baseSec`     고정 비용(프레임을 훑지 않는 앞뒤 작업 — 권한·게이트 조회, 이미지 인코딩, 대기)
//   - `perFrameSec` 프레임 수에 비례하는 몫(프레임을 훑지 않는 작업은 0)
//   - `ceilingSec`  한 요청이 넘지 말아야 할 절대 상한
//
// ★ 상한을 넘으면 «기다린다» 가 아니라 «나눠 보낸다»
//
//   근거는 "영원히 기다린다" 가 아니다 — **경로 중 가장 작은 상한이 실제 상한**이다. 브라우저·
//   프록시·게이트웨이 중 어디든 우리보다 짧은 상한을 가진 구간이 있으면 한 요청이 길수록 그
//   구간에서 끊길 확률이 커지고, 끊기는 순간 **그 요청 안에서 이미 끝낸 프레임까지 통째로 버려진다**.
//   나눠 보내면 잃는 것이 마지막 조각 하나로 줄고(앞 조각의 성공분은 이미 손에 있다),
//   부수 효과로 진행 표시가 더 자주 갱신된다.
//
// ─────────────────────────────────────────────────────────────────────────────
// ⚠ JSON 필드명은 서버 담당과 **같은 확정 문구**에서 각자 도출한 것이다. 종류 키
//   (`autolabel`/`segment`/`track`/`autoTrack`)는 그 문구가 정하지 않아 이 쪽이 엔드포인트
//   의미에 맞춰 골랐다(`/autolabel` · `/sam2-segment` · `/sam2-track` · `/yolo-track`).
//   서버가 다른 키로 내려주면 **조용히 폴백으로 떨어진다**(값이 틀리는 것이 아니라 «못 받은 것»이
//   되어 현행 동작이 유지된다) — 그래도 계약이 어긋난 것이므로 맞춰야 한다.

import type { BusyKind } from '@/stores/useLabelStore';

/** 대기 예산을 갖는 작업 종류 — 확정 문구가 정한 넷. */
export type AiWaitKind = 'autolabel' | 'segment' | 'sam2Track' | 'autoTrack';

/** 작업 종류 하나의 대기 예산(초 단위 — 서버 응답 그대로의 단위). */
export interface AiWaitBudget {
  /** 고정 비용(초). 프레임 수와 무관한 몫. 0 이 정당하다. */
  baseSec: number;
  /** 프레임 1건당 가산분(초). 프레임을 훑지 않는 작업은 0. */
  perFrameSec: number;
  /** 한 요청이 넘지 말아야 할 절대 상한(초). */
  ceilingSec: number;
}

/** 서버 응답의 부분 수신을 그대로 표현한다 — 종류도 필드도 빠질 수 있다. */
export type AiWaitBudgetInput = Partial<Record<AiWaitKind, Partial<AiWaitBudget> | undefined>>;

/**
 * 작업잠금 fail-safe 에 얹는 여유(ms).
 *
 * ★ 잠금 상한은 **요청 제한시간보다 반드시 길어야 한다**. 짧으면 요청이 성공해도 잠금이 먼저 풀려
 *   결과가 **조용히 폐기**되고 사용자는 성공도 실패도 못 본다(이 라운드가 고치는 원래 결함).
 *   제한시간이 먼저 터져야 «실패했다» 는 안내가 뜬다.
 *
 * ⚠ 값 자체에는 큰 의미가 없다 — 순서만 지키면 된다. 요청은 자기 제한시간에서 스스로 끊기므로
 *   이 fail-safe 는 «그 밖의 이유로 멈춘 경우» 를 위한 최후 장치일 뿐이며 정상 경로에서는 뜨지 않는다.
 */
export const AI_WAIT_BUSY_GRACE_MS = 15_000;

/**
 * AI 가 아닌 작업(저장·불러오기)의 잠금 상한(ms).
 *
 * 추론 예산 축이 아니므로 종전 공용 값을 그대로 쓴다 — 억지로 추론 예산을 끌어오면 화면이 없는
 * 계약을 만들어 쓰는 것이 된다.
 */
export const NON_AI_BUSY_MAX_MS = 5 * 60 * 1000;

/**
 * 서버 값을 못 받았을 때의 폴백 예산.
 *
 * ★ **짧게 잡으면 원래 결함이 그대로 돌아온다** — 정상 동작이 «AI 실패» 로 보이고, 서버는 계속
 *   돌며, 사용자는 같은 일을 다시 시켜 자원 소모가 곱해진다. 그래서 폴백은 넉넉한 쪽으로 잡는다.
 *
 * ★ 값은 **서버의 도출 규칙을 그대로 옮긴 것**이다(`AiWaitBudgetPolicy`). 폴백이 서버와 다른
 *   계산에서 나오면 «서버 값을 못 받았을 때만 동작이 달라지는» 재현하기 어려운 차이가 생긴다.
 *   재료(전부 서버 설정에서 도출된 값):
 *     · 호출 1회 최악 183초 = 호출당 상한 60초 × 3회 시도 + 백오프(1초 + 2초)
 *     · 온라인 경로 블로킹 상한 70초 · 폴리곤 배치 예산 60초 · **추적 요청 예산 240초**
 *     · 고정 부대비용 10초 · 프레임당 부대비용 2초
 *
 *   | 종류 | baseSec | perFrameSec | 도출 |
 *   |---|---|---|---|
 *   | autolabel | 140 | 0 | 70(블로킹 상한) + 60(폴리곤 배치) + 10. 이 경로는 스스로 70초에 잘라 183초까지 가지 않는다 |
 *   | segment | 193 | 0 | 183(호출 최악) + 10. 상한 없는 대기라 재시도 체인이 통째로 돈다 |
 *   | sam2Track | 252 | 0 | 10 + 240(요청 예산) + 2. 프레임 루프 전체가 요청 단위 예산 안에서 돈다 |
 *   | autoTrack | 252 | 0 | 위와 같은 예산·같은 도출(시작 프레임도 같은 루프 안에 있다) |
 *
 * ★ **추적 두 경로의 프레임당 몫이 0 인 것은 오타가 아니다.** 서버가 프레임 루프 전체에 요청 단위
 *   시간 예산을 두면서, 요청 하나가 언제 끝나는지가 **프레임 수와 무관**해졌다. 그래서 화면은
 *   서버 상한(요청당 50프레임)까지 한 번에 싣고, 예산이 다하면 서버가 «어디까지 했는지»를 응답에
 *   담아 돌려주므로 그 지점부터 이어 보낸다(부분 결과 계약 — `api.ts` 의 추적 함수들).
 *   ⚠ 여기를 옛 값(프레임당 재시도 체인 전체)으로 되돌리면 50프레임이 **50번**으로 쪼개지고
 *     조각마다 시작 프롬프트를 다시 심어 추적 품질까지 떨어진다.
 *
 * ⚠ 상한 300초는 서버 기본값이며 운영자가 바꿀 수 있다 — 그래서 **화면이 아니라 서버가** 소유한다.
 * ⚠ 이 표는 **폴백**이지 사양이 아니다. 분할 단위를 키우고 싶으면 서버가 `perFrameSec` 을 낮추면
 *   되고, 화면은 고치지 않는다(그것이 이 설계의 목적이다).
 */
export const AI_WAIT_BUDGET_FALLBACK: Record<AiWaitKind, AiWaitBudget> = {
  autolabel: { baseSec: 140, perFrameSec: 0, ceilingSec: 300 },
  segment: { baseSec: 193, perFrameSec: 0, ceilingSec: 300 },
  sam2Track: { baseSec: 252, perFrameSec: 0, ceilingSec: 300 },
  autoTrack: { baseSec: 252, perFrameSec: 0, ceilingSec: 300 },
};

/**
 * 라벨링 작업 종류 → 예산 종류.
 *
 * 저장·불러오기는 추론이 아니라 예산 축이 **없다**(null). 억지로 매핑하면 화면이 없는 계약을
 * 만들어 쓰는 것이 된다.
 */
export function busyKindWaitKind(kind: BusyKind): AiWaitKind | null {
  switch (kind) {
    case 'AI_DETECT':
      return 'autolabel';
    case 'AI_SEGMENT':
      return 'segment';
    case 'AI_TRACK':
      return 'sam2Track';
    case 'AI_AUTO_TRACK':
      return 'autoTrack';
    default:
      return null;
  }
}

/**
 * 서버가 내려준 예산. 모듈 지역 캐시다.
 *
 * ★ 왜 모듈 캐시인가 — 실제 요청을 보내는 곳(`api.ts` 의 평범한 함수들)은 React 컨텍스트 밖이라
 *   훅으로 값을 읽을 수 없다. 대신 함수 시그니처마다 예산을 인자로 흘려보내면 호출부가 한 곳만
 *   빠뜨려도 그 경로가 조용히 옛 상수로 돌아간다(이 저장소가 반복해 겪은 «판정이 여러 곳에
 *   흩어져 한쪽만 갱신되는» 결함 계열). 발행은 {@link publishAiWaitBudgets} 한 곳에서만 한다.
 */
let published: AiWaitBudgetInput | undefined;

/** 서버 응답의 예산을 발행한다. `undefined` 를 넣으면 폴백으로 돌아간다. */
export function publishAiWaitBudgets(budgets: AiWaitBudgetInput | undefined): void {
  published = budgets;
}

/** 발행분을 지운다(테스트 격리용 — 한 테스트가 발행한 값이 다음 테스트로 새지 않게). */
export function resetAiWaitBudgets(): void {
  published = undefined;
}

/**
 * 그 종류의 유효 예산 — **필드 단위로** 서버 값과 폴백을 섞는다.
 *
 * ⚠ 종류 단위로만 섞으면 부분 응답(필드 하나만 온 경우)의 나머지가 `undefined` → 0 으로 해석돼
 *   제한시간이 0(=즉시 끊김)이 된다. 필드 단위여야 «못 받은 것» 이 «0» 으로 둔갑하지 않는다.
 */
export function aiWaitBudget(kind: AiWaitKind): AiWaitBudget {
  const fallback = AI_WAIT_BUDGET_FALLBACK[kind];
  const given = published?.[kind];
  if (!given) return fallback;
  return {
    // 고정 비용은 0 이 정당하다(고정 비용 없음). 음수·비유한만 배제한다.
    baseSec: acceptable(given.baseSec, 0) ? given.baseSec : fallback.baseSec,
    perFrameSec: acceptable(given.perFrameSec, 0) ? given.perFrameSec : fallback.perFrameSec,
    // 상한 0 은 «모든 요청을 즉시 끊는다» 라 사고다 — 최소 1초.
    ceilingSec: acceptable(given.ceilingSec, 1) ? given.ceilingSec : fallback.ceilingSec,
  };
}

/**
 * 요청 하나의 제한시간(ms).
 *
 * @param frameCount 이 요청이 실을 프레임 수(프레임을 훑지 않는 작업은 생략).
 *
 * ⚠ 입력을 신뢰하지 않는다 — 음수·NaN·무한대는 0건으로 친다. 그대로 곱하면 제한시간이 NaN
 *   (=무제한 대기) 이거나 음수(=즉시 끊김)가 된다.
 */
export function aiWaitTimeoutMs(kind: AiWaitKind, frameCount = 0): number {
  const { baseSec, perFrameSec, ceilingSec } = aiWaitBudget(kind);
  const frames = safeFrameCount(frameCount);
  const sec = Math.min(baseSec + perFrameSec * frames, ceilingSec);
  // 상한이 고정분보다 작게 내려와도 «즉시 끊김» 이 되지 않게 바닥을 둔다.
  return Math.max(Math.round(sec * 1000), 1_000);
}

/**
 * 한 요청에 실을 프레임 수 — **상한 안에 들어가는 만큼**.
 *
 * @param hardMax 서버가 요청당 허용하는 프레임 수 상한(`@Size(max=…)`). 이보다 크게 나눠 보내면 400 이다.
 *
 * 프레임당 비용이 0 이면 나눌 이유가 없으므로 서버 상한까지 그대로 보낸다.
 * 한 건도 들어가지 않는 예산이어도 **최소 1건**은 보낸다 — 0 을 돌려주면 아무 것도 보내지 못하거나
 * 무한 루프가 된다.
 */
export function aiWaitChunkSize(kind: AiWaitKind, hardMax: number): number {
  const cap = Math.max(1, Math.floor(hardMax));
  const { baseSec, perFrameSec, ceilingSec } = aiWaitBudget(kind);
  if (perFrameSec <= 0) return cap;
  const fits = Math.floor((ceilingSec - baseSec) / perFrameSec);
  return Math.min(cap, Math.max(1, fits));
}

/**
 * 그 작업의 작업잠금(busy) fail-safe 상한(ms).
 *
 * @param frameCount 전체 대상 프레임 수(분할 전송이면 **묶음 전체**의 프레임 수).
 * @param hardMax    서버 요청당 프레임 상한 — 분할 단위 계산에 쓴다. 생략하면 분할하지 않는 작업.
 *
 * ★ 분할 전송의 잠금 구간은 요청 하나가 아니라 **묶음 전체**다. 요청 하나 기준으로 잡으면 두 번째
 *   조각에서 잠금이 먼저 풀려 결과가 조용히 폐기된다.
 */
export function aiWaitBusyMaxMs(kind: BusyKind, frameCount = 0, hardMax?: number): number {
  const waitKind = busyKindWaitKind(kind);
  if (waitKind === null) return NON_AI_BUSY_MAX_MS;
  const frames = safeFrameCount(frameCount);
  if (hardMax === undefined || frames === 0) {
    return aiWaitTimeoutMs(waitKind, frames) + AI_WAIT_BUSY_GRACE_MS;
  }
  const chunk = aiWaitChunkSize(waitKind, hardMax);
  const chunkCount = Math.max(1, Math.ceil(frames / chunk));
  return aiWaitTimeoutMs(waitKind, chunk) * chunkCount + AI_WAIT_BUSY_GRACE_MS;
}

/**
 * **진행이 한 번 관측될 때마다** 다시 세는 잠금 상한(ms) — 「요청 하나 + 여유」.
 *
 * ★ 왜 필요한가 — 서버가 요청 하나의 시간 예산을 다 쓰면 그때까지의 결과를 돌려주고, 화면은 남은
 *   프레임을 **이어 보낸다**. 몇 번 이어 보낼지는 미리 알 수 없어 처음부터 큰 상한을 잡을 수 없고
 *   (그러면 fail-safe 가 무의미해진다), 그렇다고 두면 이어 보내는 도중에 잠금이 먼저 풀려
 *   **서버는 잘 돌고 있는데 결과가 버려진다**. 그래서 상한을 «진행이 멈춘 뒤부터» 재는 쪽으로 바꾼다.
 *
 * @param hardMax 서버 요청당 프레임 상한. 주면 그 상한 안의 **조각 하나** 기준으로 계산한다.
 */
export function aiWaitBusyRenewMs(kind: BusyKind, hardMax?: number): number {
  const waitKind = busyKindWaitKind(kind);
  if (waitKind === null) return NON_AI_BUSY_MAX_MS;
  const frames = hardMax === undefined ? 0 : aiWaitChunkSize(waitKind, hardMax);
  return aiWaitTimeoutMs(waitKind, frames) + AI_WAIT_BUSY_GRACE_MS;
}

/** 숫자이고 유한하며 하한 이상인가 — 문자열·null·NaN·무한대를 모두 배제한다. */
function acceptable(v: unknown, min: number): v is number {
  return typeof v === 'number' && Number.isFinite(v) && v >= min;
}

function safeFrameCount(n: number): number {
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : 0;
}
