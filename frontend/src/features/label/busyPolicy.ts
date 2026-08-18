// Phase 3 DEV_FIX — 라벨링 busy(장시간 작업)의 **표시·취소 정책 단일 소스**.
//
// 여기로 모으는 이유: 작업명 문구가 3곳(useBusyTask / BusyOverlay / LabelingPage)에, ESC 취소가
// 3곳(BusyOverlay / OverlayLayer / useLabelingShortcuts)에 각각 복붙돼 있었다. 이 프로젝트에서
// 반복된 결함 계열(같은 판정이 여러 곳에 흩어져 한쪽만 갱신되며 조용히 드리프트)과 같은 형태라
// 판정·문구를 한 곳에만 둔다.

import {
  isEditBlockedState,
  useLabelStore,
  type BusyKind,
  type BusyState,
} from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

/**
 * 작업 base 이름 — **사용자 노출 문구의 단일 소스**. 모델명(YOLO/SAM/SAM2)은 쓰지 않는다(R6).
 * 접미사·어투는 각 표시부가 붙인다(아래 파생 상수들).
 */
export const BUSY_KIND_NAME: Record<BusyKind, string> = {
  AI_DETECT: 'AI 탐지',
  AI_SEGMENT: 'AI 분할',
  AI_TRACK: 'AI 추적',
  // 선택한 객체 하나를 따라가는 'AI 추적' 과 **이름이 겹치지 않게** 구분한다(확정 사양 SCREEN-005).
  AI_AUTO_TRACK: 'AI 자동 추적',
  SAVE: '저장',
  LOAD: '불러오기',
};

/**
 * 진행 오버레이 문구를 만드는 **어미 규칙**. 입력은 base 이름 하나뿐이라, 이름을 바꾸면 문구도
 * 반드시 따라간다(완성 문구를 리터럴로 들고 있으면 base 만 바뀌고 오버레이는 옛 값으로 남는다).
 *
 * 저장/불러오기는 '진행 중' 어투가 어색해 어미만 다르게 붙인다. '~기'(명사형) 이름은 '~는 중'
 * 으로 활용한다(불러오기 → 불러오는 중).
 */
export const BUSY_KIND_PROGRESS_RULE: Record<BusyKind, (name: string) => string> = {
  AI_DETECT: (name) => `${name} 진행 중`,
  AI_SEGMENT: (name) => `${name} 진행 중`,
  AI_TRACK: (name) => `${name} 진행 중`,
  AI_AUTO_TRACK: (name) => `${name} 진행 중`,
  SAVE: (name) => `${name} 중`,
  LOAD: (name) => `${name.replace(/기$/, '는')} 중`,
};

/** 진행 오버레이 문구. 전부 {@link BUSY_KIND_NAME} + {@link BUSY_KIND_PROGRESS_RULE} 파생이다. */
export const BUSY_KIND_PROGRESS_LABEL: Record<BusyKind, string> = Object.fromEntries(
  (Object.keys(BUSY_KIND_NAME) as BusyKind[]).map((kind) => [
    kind,
    BUSY_KIND_PROGRESS_RULE[kind](BUSY_KIND_NAME[kind]),
  ]),
) as Record<BusyKind, string>;

/**
 * 취소 안내 문구. 조사(을/를)가 이름마다 달라 종류별로 확정한다.
 *
 * ★ 취소는 **요청 중단 + 결과 미반영** 둘 다다. 확정 사양에 따라 AI 요청은 취소 시 서버로 가는
 * 요청 자체를 끊는다(`useBusyTask` 가 중단 신호를 실어 보낸다) — 화면 안에서만 버리면 서버는
 * 계속 돌아 추론 자원을 물고 있는다.
 * ⚠ 그렇다고 «서버가 즉시 멈춘다» 고 단정하지도 않는다 — 중단 신호를 받은 서버가 어느 지점에서
 * 실제로 손을 떼는지는 서버 몫이다. 문구는 화면이 확실히 아는 것(요청을 끊었다·결과를 쓰지
 * 않는다)까지만 말한다.
 */
export const BUSY_KIND_CANCELLED_MESSAGE: Record<BusyKind, string> = {
  AI_DETECT: `${BUSY_KIND_NAME.AI_DETECT}를 취소했습니다. 요청을 중단하고 결과는 반영하지 않습니다.`,
  AI_SEGMENT: `${BUSY_KIND_NAME.AI_SEGMENT}을 취소했습니다. 요청을 중단하고 결과는 반영하지 않습니다.`,
  AI_TRACK: `${BUSY_KIND_NAME.AI_TRACK}을 취소했습니다. 요청을 중단하고 결과는 반영하지 않습니다.`,
  AI_AUTO_TRACK: `${BUSY_KIND_NAME.AI_AUTO_TRACK}을 취소했습니다. 요청을 중단하고 결과는 반영하지 않습니다.`,
  SAVE: `${BUSY_KIND_NAME.SAVE}을 취소했습니다. 요청을 중단하고 결과는 반영하지 않습니다.`,
  LOAD: `${BUSY_KIND_NAME.LOAD}를 취소했습니다. 요청을 중단하고 결과는 반영하지 않습니다.`,
};

/**
 * **대기 상한 초과** 안내 문구 — 취소와는 다른 사건이다.
 *
 * ★ 종전에는 이 자리가 **무음**이었다. 상한(공용 5분)이 터지면 진행 표시만 풀리고 결과는 조용히
 * 폐기돼, 사용자는 **성공도 실패도 못 본 채** 화면이 원래대로 돌아오는 것만 봤다. 그러면 같은
 * 일을 다시 시키거나(중복 추론) 결과가 반영된 줄 알고 넘어간다.
 *
 * ⚠ 취소 문구와 합치지 말 것 — «내가 멈춘 것» 과 «시스템이 못 기다린 것» 은 사용자가 취할 다음
 * 행동이 다르다(전자는 아무 것도, 후자는 재시도 또는 구간 축소).
 */
export const BUSY_KIND_TIMEOUT_MESSAGE: Record<BusyKind, string> = {
  AI_DETECT: `${BUSY_KIND_NAME.AI_DETECT}가 대기 시간을 넘겨 중단됐습니다. 잠시 후 다시 시도해 주세요.`,
  AI_SEGMENT: `${BUSY_KIND_NAME.AI_SEGMENT}이 대기 시간을 넘겨 중단됐습니다. 잠시 후 다시 시도해 주세요.`,
  AI_TRACK: `${BUSY_KIND_NAME.AI_TRACK}이 대기 시간을 넘겨 중단됐습니다. 추적 구간을 줄여 다시 시도해 주세요.`,
  AI_AUTO_TRACK: `${BUSY_KIND_NAME.AI_AUTO_TRACK}이 대기 시간을 넘겨 중단됐습니다. 추적 구간을 줄여 다시 시도해 주세요.`,
  SAVE: `${BUSY_KIND_NAME.SAVE}이 대기 시간을 넘겨 중단됐습니다. 잠시 후 다시 시도해 주세요.`,
  LOAD: `${BUSY_KIND_NAME.LOAD}가 대기 시간을 넘겨 중단됐습니다. 잠시 후 다시 시도해 주세요.`,
};

/**
 * 대기 상한 초과를 사용자에게 알린다 — **무음 폐기 금지**.
 *
 * 안내를 여기 한 곳에 두는 이유는 이 파일의 머리말과 같다: 문구·판정이 호출부마다 흩어지면
 * 한쪽만 갱신되며 조용히 갈라진다.
 */
export function notifyBusyTimeout(kind: BusyKind): void {
  useUiStore.getState().pushToast({
    // 사용자가 시킨 일이 끝나지 못했다 — 정보(info)가 아니라 경고다.
    variant: 'warning',
    message: BUSY_KIND_TIMEOUT_MESSAGE[kind],
  });
}

/**
 * 진행 오버레이 지연 표시 창(ms). 이 시간을 넘긴 작업만 오버레이를 띄운다.
 * 즉시 그리기(클릭마다 짧은 요청)에서 오버레이가 깜빡이면 화면이 고장난 것처럼 보인다(AC7).
 */
export const BUSY_OVERLAY_DELAY_MS = 300;

/**
 * 진행 오버레이가 **실제로 화면에 떠 있는가**.
 *
 * 이 판정은 UX 정책의 경계다(Phase 3 DEV_FIX D3/D4):
 *  - 오버레이가 보이면 → 사용자가 이미 상태를 보고 있다. 입력을 무시해도 "무음 소실"이 아니고,
 *    취소도 시각적으로 즉시 드러나므로 별도 안내가 필요 없다.
 *  - 오버레이가 안 보이면(지연 창 <300ms) → 화면에 아무 단서가 없다. 조작 누적을 허용하고,
 *    취소는 반드시 안내한다.
 */
export function isBusyOverlayVisible(busy: BusyState | null, now: number = Date.now()): boolean {
  return busy !== null && now - busy.startedAt >= BUSY_OVERLAY_DELAY_MS;
}

/**
 * 진행 오버레이가 **이 프레임 화면에** 떠 있는가 — 지연 창 + **프레임 스코프**를 함께 본다.
 *
 * ⚠ 스코프를 빼면 안 된다(NF-3): 오버레이의 실제 렌더 조건은 프레임 스코프 판정
 * (`isEditBlockedState(state, srcSn)`)이라, 다른 프레임의 작업이 도는 동안에는 화면에 오버레이가
 * 없다. 그때 캔버스만 "오버레이가 떠 있다"고 판정하면 클릭이 **아무 단서 없이 무시**된다
 * (무음 드롭). 두 판정기가 서로 다른 축을 보던 이 프로젝트의 반복 결함 계열이다.
 */
export function isBusyOverlayShownFor(
  state: { busy: BusyState | null },
  srcSn?: number,
  now: number = Date.now(),
): boolean {
  return isEditBlockedState(state, srcSn) && isBusyOverlayVisible(state.busy, now);
}

/**
 * 지금 열려 있는 모달(dialog)이 있는가.
 *
 * 공통 `Modal` 은 portal 로 body 에 `role="dialog" aria-modal="true"` 를 렌더하므로 DOM 한 곳만
 * 보면 된다 — 화면마다 늘어나는 모달 상태 플래그를 일일이 prop 으로 넘기면 하나만 빠져도
 * 조용히 드리프트한다(이 파일이 막으려는 바로 그 형태).
 */
export function hasOpenModalDialog(): boolean {
  if (typeof document === 'undefined') return false;
  return document.querySelector('[role="dialog"][aria-modal="true"]') !== null;
}

/**
 * ESC 취소 **단일 진입점**(오버레이 / 캔버스 / 전역 단축키 3곳이 이것만 호출한다).
 *
 * - 진행 중 작업이 없으면 아무 것도 하지 않고 false — 같은 ESC 에 여러 리스너가 반응해도
 *   취소·안내는 1회다(멱등).
 * - **오버레이가 뜨기 전(지연 창)의 취소는 반드시 안내한다**(D4). 이 구간에는 화면에 아무
 *   표시가 없어, 안내가 없으면 사용자는 취소된 줄 모르고 결과를 계속 기다린다.
 *
 * @param cancel 실제 취소 동작(기본: store busy 취소). 오버레이처럼 자기 취소 핸들러를 가진
 *               호출부가 그 계약을 유지할 수 있도록 주입받는다.
 * @returns 진행 중 작업을 실제로 취소했으면 true.
 */
export function handleBusyEscape(cancel: () => void = defaultCancel): boolean {
  const busy = useLabelStore.getState().busy;
  // 취소 자체는 항상 위임한다 — busy 가 없으면 store 취소가 no-op 이라 부작용이 없다.
  cancel();
  if (busy === null) return false;
  if (!isBusyOverlayVisible(busy)) {
    useUiStore.getState().pushToast({
      variant: 'info',
      message: BUSY_KIND_CANCELLED_MESSAGE[busy.kind],
    });
  }
  return true;
}

function defaultCancel(): void {
  useLabelStore.getState().cancelBusy();
}
