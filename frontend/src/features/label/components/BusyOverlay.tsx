// Phase 3 — 진행 오버레이. 무엇이 진행 중인지 캔버스 위에 보이고, 거기서 바로 취소한다.
//
// 설계 포인트:
//  - **지연 표시**(BUSY_OVERLAY_DELAY_MS): 즉시 그리기처럼 짧은 작업은 매 클릭 깜빡이면 안 된다(AC7).
//  - **백드롭이 pointer 이벤트를 흡수**한다 — 시각적 차단(오버레이)과 물리적 차단(입력 차단)이
//    어긋나면 "막힌 것처럼 보이는데 눌리는" 화면이 된다.
//  - **문구에 모델명(YOLO/SAM/SAM2) 미노출**(R6) + 내부 경로·식별자·좌표 미노출(정보 노출 방지).
//  - **취소 시맨틱을 오도하지 않는다** — 취소는 도착 결과를 버리는 데서 끝나지 않는다. 추론
//    작업은 요청을 끊고 **서버에도 취소를 알려** 실제로 중단시킨다(요청에 실은 취소 식별자로
//    별도 취소 요청을 보낸다 — 연결을 끊는 것만으로는 이 스택에서 서버가 멈추지 않는다).
//    반면 저장·불러오기는 서버 취소 대상 경로가 아니라 결과 폐기까지다. 그렇게 적는다.

import { useEffect, useRef, useState } from 'react';

import { Spinner } from '@/components/common/Spinner';
import type { BusyKind } from '@/stores/useLabelStore';

import {
  BUSY_KIND_PROGRESS_LABEL,
  BUSY_OVERLAY_DELAY_MS,
  handleBusyEscape,
  hasOpenModalDialog,
} from '../busyPolicy';

// 문구·지연 창·ESC 취소 판정은 모두 busyPolicy 단일 소스에서 온다. 여기서 다시 정의하면
// 화면마다 다른 문구/다른 경계가 생긴다(이 화면이 겪었던 드리프트).
export { BUSY_OVERLAY_DELAY_MS };

export interface BusyOverlayProps {
  /** 진행 중 작업 종류. null 이면 렌더하지 않는다(진행 중 아님 또는 다른 프레임의 작업). */
  kind: BusyKind | null;
  /** 작업 시작 시각(ms epoch). 지연 표시·경과 시간의 기준. */
  startedAt?: number;
  /**
   * 이 실행의 **대기 상한**(ms). 경과 시간 옆에 «/ 최대 N초» 로 함께 보여준다.
   *
   * ★ 왜 필요한가 — 대기가 분 단위로 늘어나면 «몇 초 경과» 만으로는 **끝을 가늠할 수 없다**.
   *   사용자는 언제까지 기다려야 하는지 모른 채 취소할지 말지를 정해야 한다.
   * ⚠ 모르면 **생략한다**. 임의의 값을 지어내면 화면이 거짓 끝을 약속하게 된다.
   */
  limitMs?: number;
  /** 취소 — 진행 중인 요청을 중단하고 도착 결과를 폐기한 뒤 즉시 편집으로 복귀시킨다. */
  onCancel: () => void;
}

export function BusyOverlay({ kind, startedAt, limitMs, onCancel }: BusyOverlayProps) {
  const [visible, setVisible] = useState(false);
  const [elapsedSec, setElapsedSec] = useState(0);
  const cancelRef = useRef<HTMLButtonElement | null>(null);
  const rootRef = useRef<HTMLDivElement | null>(null);
  const panelRef = useRef<HTMLDivElement | null>(null);
  // 최신 onCancel 을 네이티브 리스너가 참조하도록 미러링(리스너는 visible 에만 재구독).
  const cancelHandlerRef = useRef(onCancel);
  cancelHandlerRef.current = onCancel;

  // 지연 표시 + 경과 시간. **작업이 바뀔 때마다(startedAt) 지연을 다시 센다** — 그렇지 않으면
  // 앞 작업에서 켜진 오버레이가 뒤 작업의 짧은 요청에도 그대로 남아 지연 표시가 무력화된다.
  useEffect(() => {
    setVisible(false);
    setElapsedSec(0);
    if (kind === null) return;
    const begin = startedAt ?? Date.now();
    const tick = () => setElapsedSec(Math.max(0, Math.floor((Date.now() - begin) / 1000)));
    // 이미 지연 창을 넘긴 작업(다른 프레임에서 돌아온 경우 등)은 즉시 노출한다.
    const remain = Math.max(0, BUSY_OVERLAY_DELAY_MS - (Date.now() - begin));
    let interval: ReturnType<typeof setInterval> | undefined;
    const timer = setTimeout(() => {
      setVisible(true);
      tick();
      interval = setInterval(tick, 1000);
    }, remain);
    // 타이머 누수 금지 — 해제·프레임 전환·언마운트 어느 경로로 끝나도 둘 다 정리한다.
    return () => {
      clearTimeout(timer);
      if (interval !== undefined) clearInterval(interval);
    };
  }, [kind, startedAt]);

  // 오버레이가 뜨면 취소 버튼으로 포커스를 옮긴다(키보드 사용자가 즉시 취소 가능).
  // 포커스 트랩은 만들지 않는다 — Tab 으로 계속 화면을 빠져나갈 수 있어야 한다(WCAG 2.1.2).
  //
  // ⚠ **모달이 열려 있으면 포커스를 가져가지 않는다**(D2). 이 오버레이는 z-20 이고 공통 Modal 은
  //   z-50 portal 이라, 모달 뒤의 **보이지 않는** 취소 버튼으로 포커스가 넘어간다: 모달의 Tab
  //   트랩이 무력화되고, Enter/Space 로 보이지 않는 "작업 취소" 가 눌려 진행 중인 저장이 폐기된다.
  //   (모달이 닫히면 Modal 이 직전 포커스를 복원하고, 사용자는 Tab 으로 취소 버튼에 도달할 수
  //   있으므로 키보드 트랩은 생기지 않는다.)
  useEffect(() => {
    if (visible && !hasOpenModalDialog()) cancelRef.current?.focus();
  }, [visible]);

  // 백드롭이 캔버스로 가는 포인터 이벤트를 흡수한다(시각적 차단 = 물리적 차단) + ESC 취소.
  // 상태 영역(role=status)에 JSX 핸들러를 다는 대신 네이티브 리스너로 붙인다 — 비상호작용
  // 요소에 상호작용을 부여하지 않으면서(a11y) 실제 이벤트 흐름을 정확히 끊는다.
  useEffect(() => {
    const el = rootRef.current;
    if (!visible || el === null) return;
    const absorb = (e: Event) => {
      // 오버레이 자체 컨트롤(취소 버튼)의 이벤트는 통과시킨다 — 여기서 끊으면 버튼이 죽는다.
      if (panelRef.current?.contains(e.target as Node)) return;
      e.stopPropagation();
    };
    // ESC 취소는 화면 공통 단일 헬퍼를 거친다(M4) — 취소 판정·안내 정책이 여기 따로 살면
    // 캔버스/전역 단축키 쪽과 조용히 갈라진다. 실제 취소 동작은 상위가 준 핸들러에 위임한다.
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') handleBusyEscape(() => cancelHandlerRef.current());
    };
    const types = ['click', 'mousedown', 'mouseup', 'mousemove', 'wheel'];
    types.forEach((t) => el.addEventListener(t, absorb));
    el.addEventListener('keydown', onKeyDown);
    return () => {
      types.forEach((t) => el.removeEventListener(t, absorb));
      el.removeEventListener('keydown', onKeyDown);
    };
  }, [visible]);

  if (kind === null || !visible) return null;

  // 상한을 모르면 «끝» 을 말하지 않는다 — 지어낸 값은 거짓 약속이 된다.
  // 0·음수·비유한 값도 상한으로 치지 않는다(«최대 0초» 는 안내가 아니라 오류다).
  const limitSec =
    typeof limitMs === 'number' && Number.isFinite(limitMs) && limitMs > 0
      ? Math.round(limitMs / 1000)
      : null;

  // 스크림은 UI 크롬이 아니라 모달 배경이라 라이트에서도 어둡게 둔다.
  // 색은 공통 Modal·Drawer 의 backdrop 관례(bg-black/50)를 그대로 따른다.
  return (
    <div
      ref={rootRef}
      data-testid="busy-overlay"
      role="status"
      aria-live="polite"
      aria-busy="true"
      className="absolute inset-0 z-20 flex items-center justify-center bg-black/50"
    >
      <div
        ref={panelRef}
        className="flex max-w-sm flex-col items-center gap-3 rounded-lg border border-gray-200 bg-white px-6 py-5 text-center shadow-lg"
      >
        {/* 스피너는 장식 — 상태 문구가 이미 role=status 로 읽히므로 중복 안내를 만들지 않는다. */}
        <span aria-hidden="true">
          <Spinner size="lg" />
        </span>
        <p className="text-body-md font-semibold text-gray-900">{BUSY_KIND_PROGRESS_LABEL[kind]}</p>
        {/* 경과 초는 매초 갱신된다 — role=status(aria-live) 리전의 자식으로 두면 최대 5분=300회가
            스크린리더로 낭독된다(WCAG). 시각 정보로만 남기고 라이브 리전에서는 제외한다.
            작업명은 위 문단에 그대로 있어 "무엇이 진행 중인지"는 계속 낭독된다. */}
        <p aria-hidden="true" data-testid="busy-overlay-elapsed" className="text-caption text-gray-500">
          {limitSec === null ? `${elapsedSec}초 경과` : `${elapsedSec}초 / 최대 ${limitSec}초`}
        </p>
        <button
          ref={cancelRef}
          type="button"
          data-testid="busy-overlay-cancel"
          onClick={onCancel}
          className="rounded-lg border border-gray-300 bg-white px-4 py-1.5 text-caption text-gray-700 transition-colors hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-primary-500"
        >
          작업 취소
        </button>
        <p className="text-caption text-gray-500">
          취소하면 요청을 중단하고 결과를 반영하지 않은 채 편집을 계속합니다.
        </p>
      </div>
    </div>
  );
}
