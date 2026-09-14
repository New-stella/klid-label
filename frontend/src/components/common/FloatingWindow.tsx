// 비모달 창 — 뒤 화면을 막지 않고 떠 있는 창. [@design UI-156]
//
// ★Modal·Drawer 와 무엇이 다른가
//   백드롭을 두지 않고 포커스를 가두지 않는다. 창이 떠 있는 동안에도 뒤 화면의 캔버스·타임라인·
//   우측 탭을 그대로 조작해야 하는 작업(화면에서 근거를 골라 담기)이 있기 때문이다. 그래서
//   `aria-modal=false` 이며, 이 값은 표시용이 아니라 <b>동작 계약</b>이다 — 라벨링 화면의 단축키
//   억제 판정(`busyPolicy.hasOpenModalDialog`)이 `aria-modal="true"` 만 보고 막으므로, 이 창이
//   떠 있어도 뒤 화면 단축키가 살아 있다. 값을 true 로 바꾸면 그 판정이 이 창까지 막아
//   「뒤 화면을 조작할 수 있다」는 사양이 조용히 깨진다.
//
// ★접기는 언마운트가 아니다
//   접힘·근거 지정 중에는 본문을 <b>숨기기만</b> 한다(hidden). 언마운트하면 창 안에서 입력하던
//   값이 사라지는데, 접기의 목적이 바로 「값을 둔 채 잠깐 뒤 화면을 보는 것」이다.
//
// a11y: 제목을 접근성 이름으로 쓰고, 아이콘만 있는 조작 버튼에는 이름을 준다. 끌기를 대신하는
//   수단으로 「잠시 접기」·「크게」/「원래 크기」를 둔다(마우스 끌기만으로는 조작할 수 없는
//   사용자를 막지 않는다). Esc 는 창 안에 포커스가 있을 때만 닫기를 요청한다.

import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
} from 'react';
import { createPortal } from 'react-dom';
import { Maximize2, Minimize2, Minus, X } from 'lucide-react';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

import { FloatingWindowLayoutProvider } from './floatingWindowContext';
import {
  clampSize,
  resolveInitialRect,
  writeStoredRect,
  type WindowRect,
  type WindowSize,
} from './floatingWindowPosition';

export interface FloatingWindowProps {
  open: boolean;
  /** 제목 표시줄 제목. 창의 접근성 이름으로도 쓴다. */
  title: string;
  /** 제목 옆 부제. 폭이 좁아 칸을 쌓는 상태에서는 자리를 위해 감춘다. */
  subtitle?: string;
  /** 저장되지 않은 변경 여부 — 참이면 제목 표시줄에 미저장 표시를 보인다. */
  dirty?: boolean;
  /** 제목 표시줄 미저장 표시 문구(쓰는 화면이 정한다). */
  dirtyLabel?: string;
  /** 제목 표시줄 오른쪽, 조작 버튼 왼쪽에 놓을 조각(도움말 토글 등). */
  titleBarExtra?: ReactNode;
  /** 「잠시 접기」로 숨긴 상태. */
  folded?: boolean;
  onFoldChange?: (folded: boolean) => void;
  /** 접기 버튼을 둘지 — 접기 대신 다른 방식으로 숨기는 화면은 끈다. */
  foldable?: boolean;
  maximized?: boolean;
  onMaximizeChange?: (maximized: boolean) => void;
  defaultSize?: WindowSize;
  minSize?: WindowSize;
  /** 위치·크기를 브라우저에 기억할 때 쓰는 구분 이름. 없으면 기억하지 않는다. */
  storageKey?: string;
  /** 이 값이 바뀌면 창을 앞으로 가져온다(포커스를 옮긴다). */
  focusRequestedAt?: number;
  /** 이 폭 미만이면 본문이 칸을 위아래로 쌓는다(본문은 context 로 읽는다). */
  stackBelowWidth?: number;
  /** 닫기 요청 — 미저장 확인은 이 요청을 받은 쪽이 거친다. */
  onRequestClose: () => void;
  footer?: ReactNode;
  children: ReactNode;
  'data-testid'?: string;
}

const DEFAULT_SIZE: WindowSize = { width: 1440, height: 810 };
const DEFAULT_MIN_SIZE: WindowSize = { width: 560, height: 640 };
/** 「크게」 상태에서 화면 가장자리에 남기는 여백(px). */
const MAXIMIZED_MARGIN = 8;

function viewportSize(): WindowSize {
  if (typeof window === 'undefined') return { width: 1280, height: 800 };
  return { width: window.innerWidth, height: window.innerHeight };
}

export function FloatingWindow({
  open,
  title,
  subtitle,
  dirty = false,
  dirtyLabel,
  titleBarExtra,
  folded = false,
  onFoldChange,
  foldable = true,
  maximized = false,
  onMaximizeChange,
  defaultSize = DEFAULT_SIZE,
  minSize = DEFAULT_MIN_SIZE,
  storageKey,
  focusRequestedAt = 0,
  stackBelowWidth = 0,
  onRequestClose,
  footer,
  children,
  'data-testid': testId,
}: FloatingWindowProps) {
  const rootRef = useRef<HTMLDivElement>(null);
  const [rect, setRect] = useState<WindowRect | null>(null);

  // 창을 열 때 한 번 위치를 정한다 — 기억된 값이 쓸 만하면 그것, 아니면 기본 위치.
  //   ⚠ 매 렌더가 아니라 «열림 전환»에만 정한다. 렌더마다 다시 풀면 끌어 옮긴 위치가 되돌아간다.
  useLayoutEffect(() => {
    if (!open) {
      setRect(null);
      return;
    }
    setRect((prev) =>
      prev ?? resolveInitialRect({ storageKey, defaultSize, minSize, viewport: viewportSize() }),
    );
    // defaultSize·minSize 는 호출부가 리터럴로 넘기는 값이라 신원이 매 렌더 바뀐다 —
    // 목록에 넣으면 위 «열림 전환에만» 규칙이 깨지므로 open 과 저장 키만 본다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, storageKey]);

  /** 「크게」는 저장하지 않는다 — 되돌릴 크기를 잃지 않도록 표시 시점에만 계산한다. */
  const shownRect = useMemo<WindowRect | null>(() => {
    if (rect === null) return null;
    if (!maximized) return rect;
    const vp = viewportSize();
    return {
      x: MAXIMIZED_MARGIN,
      y: MAXIMIZED_MARGIN,
      width: Math.max(minSize.width, vp.width - MAXIMIZED_MARGIN * 2),
      height: Math.max(minSize.height, vp.height - MAXIMIZED_MARGIN * 2),
    };
  }, [rect, maximized, minSize.width, minSize.height]);

  const persist = useCallback(
    (next: WindowRect) => {
      if (storageKey !== undefined) writeStoredRect(storageKey, next);
    },
    [storageKey],
  );

  /** 끌기·크기조절 공통 — 포인터가 움직이는 동안 창 위에서만 계산하고, 놓을 때 기억한다. */
  const startPointerSession = useCallback(
    (
      e: ReactPointerEvent<HTMLElement>,
      compute: (base: WindowRect, dx: number, dy: number) => WindowRect,
    ) => {
      if (shownRect === null || maximized) return;
      e.preventDefault();
      const base = shownRect;
      const startX = e.clientX;
      const startY = e.clientY;
      let latest = base;
      const move = (ev: PointerEvent) => {
        latest = compute(base, ev.clientX - startX, ev.clientY - startY);
        setRect(latest);
      };
      const up = () => {
        window.removeEventListener('pointermove', move);
        window.removeEventListener('pointerup', up);
        persist(latest);
      };
      window.addEventListener('pointermove', move);
      window.addEventListener('pointerup', up);
    },
    [shownRect, maximized, persist],
  );

  const onTitleBarPointerDown = useCallback(
    (e: ReactPointerEvent<HTMLDivElement>) => {
      // 조작 버튼 위에서 시작한 포인터는 끌기가 아니다(누르려던 버튼이 눌리지 않는다).
      if ((e.target as HTMLElement).closest('button') !== null) return;
      startPointerSession(e, (base, dx, dy) => ({
        ...base,
        x: base.x + dx,
        // 제목 표시줄이 화면 위로 넘어가면 창을 되찾을 수단이 사라진다 — 위쪽만 잠근다.
        y: Math.max(0, base.y + dy),
      }));
    },
    [startPointerSession],
  );

  const onResizePointerDown = useCallback(
    (e: ReactPointerEvent<HTMLElement>) => {
      startPointerSession(e, (base, dx, dy) => ({
        ...base,
        ...clampSize(
          { width: base.width + dx, height: base.height + dy },
          minSize,
          viewportSize(),
        ),
      }));
    },
    [startPointerSession, minSize],
  );

  const hidden = folded;

  // 열림·앞으로 가져오기 — 창 자신에게 포커스를 준다. 포커스를 가두지는 않는다(비모달).
  useEffect(() => {
    if (!open || hidden) return;
    rootRef.current?.focus();
  }, [open, hidden, focusRequestedAt]);

  if (!open || shownRect === null) return null;

  const stacked = stackBelowWidth > 0 && shownRect.width < stackBelowWidth;

  const node = (
    // Esc 를 <b>창 안에 포커스가 있을 때만</b> 듣기 위해 대화상자 컨테이너에 키 핸들러를 둔다.
    // 문서 전역에 걸면 뒤 화면에서 누른 Esc(캔버스 도구 취소)가 이 창을 닫아 버린다 — 그 범위
    // 차이가 이 창의 사양이라 규칙보다 우선한다. 컨테이너 자신은 tabIndex=-1 로 포커스를 받고
    // 안쪽 조작은 전부 버튼·입력이라 키보드 접근성이 줄지 않는다.
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions
    <div
      ref={rootRef}
      role="dialog"
      // ★비모달 — 뒤 화면을 막지 않는다는 계약이다(위 주석 참조). true 로 바꾸지 말 것.
      aria-modal="false"
      aria-label={title}
      tabIndex={-1}
      hidden={hidden}
      aria-hidden={hidden || undefined}
      data-testid={testId}
      data-stacked={stacked ? 'true' : 'false'}
      onKeyDown={(e) => {
        // 창 안에 포커스가 있을 때만 닫기를 요청한다 — 문서에 걸면 뒤 화면에서 누른 Esc 가
        // 이 창을 닫아, 캔버스 도구 취소(Esc)를 누른 사람이 창을 잃는다.
        if (e.key !== 'Escape') return;
        e.stopPropagation();
        onRequestClose();
      }}
      className={cn(
        'fixed z-50 flex flex-col overflow-hidden rounded-lg border border-gray-400 bg-white shadow-lg outline-hidden',
        hidden && 'pointer-events-none',
      )}
      style={{
        left: shownRect.x,
        top: shownRect.y,
        width: shownRect.width,
        height: shownRect.height,
      }}
    >
      {/* 제목 표시줄 — 끌어 옮기는 손잡이이자 조작 버튼 자리. */}
      <div
        className="flex h-11 shrink-0 cursor-move items-center gap-2 border-b border-gray-200 bg-gray-50 pl-3.5 pr-2"
        onPointerDown={onTitleBarPointerDown}
        data-testid="floating-window-titlebar"
      >
        <span className="shrink-0 whitespace-nowrap text-body-md font-bold text-gray-900">
          {title}
        </span>
        {subtitle !== undefined && !stacked && (
          // 제목 표시줄 배경이 gray-50 이라 보조 글자는 60단 이상이어야 AA(4.5:1)를 넘는다
          // (gray-500 은 그 위에서 4.13:1 — 전역 대비 가드가 잡는다).
          <span className="min-w-0 truncate text-caption text-gray-600">{subtitle}</span>
        )}
        <span className="flex-1" />
        {dirty && dirtyLabel !== undefined && (
          <span
            data-testid="floating-window-dirty"
            className="inline-flex shrink-0 items-center gap-1 whitespace-nowrap text-caption font-semibold text-warning-700"
          >
            <span aria-hidden="true" className="h-2 w-2 rounded-full bg-warning-500" />
            {dirtyLabel}
          </span>
        )}
        {titleBarExtra}
        {foldable && (
          <button
            type="button"
            aria-label="잠시 접기"
            title="잠시 접기"
            data-testid="floating-window-fold"
            onClick={() => onFoldChange?.(true)}
            className={cn(
              'inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-sm text-gray-600 hover:bg-gray-100',
              KRDS_FOCUS,
            )}
          >
            <Minus className="h-4 w-4" aria-hidden="true" />
          </button>
        )}
        <button
          type="button"
          aria-label={maximized ? '원래 크기' : '크게'}
          title={maximized ? '원래 크기' : '크게'}
          data-testid="floating-window-maximize"
          onClick={() => onMaximizeChange?.(!maximized)}
          className={cn(
            'inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-sm text-gray-600 hover:bg-gray-100',
            KRDS_FOCUS,
          )}
        >
          {maximized ? (
            <Minimize2 className="h-4 w-4" aria-hidden="true" />
          ) : (
            <Maximize2 className="h-4 w-4" aria-hidden="true" />
          )}
        </button>
        <button
          type="button"
          aria-label="닫기"
          title="닫기"
          data-testid="floating-window-close"
          onClick={onRequestClose}
          className={cn(
            'inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-sm text-gray-600 hover:bg-gray-100',
            KRDS_FOCUS,
          )}
        >
          <X className="h-4 w-4" aria-hidden="true" />
        </button>
      </div>

      <FloatingWindowLayoutProvider value={{ width: shownRect.width, stacked }}>
        <div className="flex min-h-0 flex-1 flex-col">{children}</div>
      </FloatingWindowLayoutProvider>

      {footer}

      {/* 크기조절 손잡이 — 끌 수 없는 사용자를 위한 대체 수단은 제목 표시줄의 「크게」다. */}
      {!maximized && (
        <span
          aria-hidden="true"
          data-testid="floating-window-resize"
          onPointerDown={onResizePointerDown}
          className="absolute bottom-0 right-0 h-4 w-4 cursor-nwse-resize"
        >
          {/* 모서리 표식은 토큰 색(중립 400)의 테두리로 그린다 — 시안의 빗금 무늬를 흉내내려고
              인라인 hex 그라데이션을 쓰면 토큰 밖 색이 소스에 박힌다. */}
          <span className="absolute bottom-1 right-1 block h-2.5 w-2.5 border-b-2 border-r-2 border-gray-400" />
        </span>
      )}
    </div>
  );

  return createPortal(node, document.body);
}
