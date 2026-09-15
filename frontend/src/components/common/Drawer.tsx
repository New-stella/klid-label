import { useEffect, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { getPortalOverlayRoot } from '@/lib/portalOverlayRoot';

export interface DrawerProps {
  open: boolean;
  onClose: () => void;
  side?: 'left' | 'right';
  title?: ReactNode;
  children?: ReactNode;
  footer?: ReactNode;
  width?: string;
  closeOnBackdrop?: boolean;
  closeOnEsc?: boolean;
  ariaLabel?: string;
  /**
   * 닫기(X) 버튼 표시 여부 — 기본 true. false 면 숨긴다.
   * 숨겨도 ESC·포커스 트랩은 유지되므로 키보드 접근성이 깨지지 않는다.
   */
  showCloseButton?: boolean;
}

const FOCUSABLE_SELECTOR =
  'a[href], area[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), button:not([disabled]), [tabindex]:not([tabindex="-1"])';

export function Drawer({
  open,
  onClose,
  side = 'right',
  title,
  children,
  footer,
  width = '400px',
  closeOnBackdrop = true,
  closeOnEsc = true,
  ariaLabel,
  showCloseButton = true,
}: DrawerProps) {
  const ref = useRef<HTMLDivElement>(null);
  const lastActiveRef = useRef<HTMLElement | null>(null);
  // 슬라이드 진입 — 첫 페인트 뒤에 transform 을 풀어야 transition 이 실제로 재생된다.
  // rAF 가 없는 환경(구형 테스트 러너 등)에서는 즉시 진입시켜 **패널이 화면 밖에 남는
  // 실패 모드**를 만들지 않는다(애니메이션 미재생 < 패널 미표시).
  const [entered, setEntered] = useState(false);

  useEffect(() => {
    if (!open) {
      setEntered(false);
      return;
    }
    if (typeof requestAnimationFrame !== 'function') {
      setEntered(true);
      return;
    }
    const id = requestAnimationFrame(() => setEntered(true));
    return () => cancelAnimationFrame(id);
  }, [open]);

  useEffect(() => {
    if (!open || !closeOnEsc) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [open, closeOnEsc, onClose]);

  useEffect(() => {
    if (!open) return;
    lastActiveRef.current = document.activeElement as HTMLElement | null;
    const root = ref.current;
    if (root) {
      const first = root.querySelector<HTMLElement>(FOCUSABLE_SELECTOR);
      (first ?? root).focus();
    }
    const trap = (e: KeyboardEvent) => {
      if (e.key !== 'Tab' || !root) return;
      const focusables = Array.from(
        root.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR),
      );
      if (focusables.length === 0) {
        e.preventDefault();
        return;
      }
      const first = focusables[0]!;
      const last = focusables[focusables.length - 1]!;
      const active = document.activeElement as HTMLElement | null;
      if (e.shiftKey && active === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && active === last) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', trap);
    return () => {
      document.removeEventListener('keydown', trap);
      lastActiveRef.current?.focus?.();
    };
  }, [open]);

  if (!open) return null;

  const node = (
    // ESC + 닫기 버튼으로 키보드 닫기를 보장하므로 백드롭은 보조 수단.
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions
    <div
      data-testid="drawer-backdrop"
      className="fixed inset-0 z-50 bg-black/50"
      onClick={(e) => {
        if (closeOnBackdrop && e.target === e.currentTarget) onClose();
      }}
    >
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-label={typeof title === 'string' ? title : ariaLabel}
        tabIndex={-1}
        style={{ width }}
        className={cn(
          // max-w-full: width 가 고정 px 이라 좁은 뷰포트(모바일)에서 화면을 넘길 수 있다.
          // 상한을 두면 같은 값으로 데스크톱은 그대로, 좁은 폭에서만 전체 폭으로 접힌다.
          // 음영은 DS-001 토큰 3단(sm/md/lg) 중 오버레이용 최상단 `lg` 를 쓴다 —
          // 그 위 단계(xl)는 토큰에 없어 Tailwind 기본값으로 폴백한다(Modal 과 동일).
          'absolute top-0 flex h-full max-w-full flex-col bg-white shadow-lg outline-hidden',
          'transition-transform duration-200 ease-out motion-reduce:transition-none',
          side === 'left' ? 'left-0' : 'right-0',
          entered
            ? 'translate-x-0'
            : side === 'left'
              ? '-translate-x-full'
              : 'translate-x-full',
        )}
      >
        {/* 머리말·꼬리말은 shrink-0 으로 고정하고 본문만 스크롤한다 — 닫기(X) 버튼이
            항상 화면 안에 남는다(Modal 과 동일 처방). */}
        <div className="flex shrink-0 items-center justify-between border-b border-gray-100 px-6 py-4">
          {title && <h2 className="text-section-title text-gray-900">{title}</h2>}
          {showCloseButton && (
            <button
              type="button"
              onClick={onClose}
              aria-label="닫기"
              className={cn(
                'inline-flex h-11 w-11 items-center justify-center rounded-md text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600',
                KRDS_FOCUS,
              )}
            >
              <X className="h-4 w-4" aria-hidden="true" />
            </button>
          )}
        </div>
        {/* ★min-h-0 이 없으면 flex 아이템의 자동 최소 크기가 콘텐츠 높이라 overflow 가
            발동하지 않는다 — 항목이 많으면 본문이 그대로 자라 아래가 잘리고 스크롤도
            불가능해진다(Modal 에서 실측된 결함과 같은 원인·같은 처방). */}
        <div className="min-h-0 flex-1 overflow-y-auto p-6">{children}</div>
        {footer && (
          <div className="shrink-0 border-t border-gray-100 px-6 py-4">{footer}</div>
        )}
      </div>
    </div>
  );

  // 덧띄움은 앵커 «안»에 붙인다 — `document.body` 직하면 포털 채널에서 스타일 격리 범위
  // 밖으로 떨어진다(근거 전문은 `lib/portalOverlayRoot`). [@design INT-013]
  return createPortal(node, getPortalOverlayRoot());
}
