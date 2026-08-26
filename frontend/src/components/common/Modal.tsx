import { useEffect, useRef, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

export interface ModalProps {
  open: boolean;
  onClose: () => void;
  title?: ReactNode;
  description?: ReactNode;
  children?: ReactNode;
  footer?: ReactNode;
  closeOnBackdrop?: boolean;
  closeOnEsc?: boolean;
  size?: 'sm' | 'md' | 'lg' | 'xl';
  ariaLabel?: string;
  /**
   * 닫기(X) 버튼 표시 여부 — 기본 true. false 면 숨겨서 **강제 확인이 필요한 흐름**에 쓴다.
   * 숨겨도 ESC·포커스 트랩은 유지되므로 키보드 접근성이 깨지지 않는다.
   */
  showCloseButton?: boolean;
}

const sizeClass = {
  sm: 'max-w-sm',
  md: 'max-w-lg',
  lg: 'max-w-3xl',
  xl: 'max-w-5xl',
} as const;

const FOCUSABLE_SELECTOR =
  'a[href], area[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), button:not([disabled]), [tabindex]:not([tabindex="-1"])';

export function Modal({
  open,
  onClose,
  title,
  description,
  children,
  footer,
  closeOnBackdrop = true,
  closeOnEsc = true,
  size = 'md',
  ariaLabel,
  showCloseButton = true,
}: ModalProps) {
  const dialogRef = useRef<HTMLDivElement>(null);
  const lastActiveRef = useRef<HTMLElement | null>(null);

  // ESC 닫기
  useEffect(() => {
    if (!open || !closeOnEsc) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onClose();
      }
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [open, closeOnEsc, onClose]);

  // 포커스 트랩 + 복귀
  useEffect(() => {
    if (!open) return;
    lastActiveRef.current = document.activeElement as HTMLElement | null;
    const root = dialogRef.current;
    if (root) {
      const first = root.querySelector<HTMLElement>(FOCUSABLE_SELECTOR);
      (first ?? root).focus();
    }
    const trap = (e: KeyboardEvent) => {
      if (e.key !== 'Tab' || !root) return;
      const focusables = Array.from(
        root.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR),
      ).filter((el) => !el.hasAttribute('disabled'));
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
    // 백드롭은 dialog 내부의 ESC 핸들러와 닫기 버튼으로 키보드 닫기를 보장하므로
    // 클릭만 부가적인 종료 수단으로 제공한다.
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      onClick={(e) => {
        if (closeOnBackdrop && e.target === e.currentTarget) onClose();
      }}
      data-testid="modal-backdrop"
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-label={typeof title === 'string' ? title : ariaLabel}
        tabIndex={-1}
        className={cn(
          // ★뷰포트 높이 상한 + 본문 내부 스크롤 (2026-08-08 — 실측 결함 해소).
          //  내용이 긴 모달(단축키 도움말 등)은 이 상한이 없으면 세로로 그대로 자라 **상·하단이
          //  뷰포트 밖으로 잘리고**, 자신도 조상도 스크롤 컨테이너가 아니라 잘린 내용에
          //  도달할 방법이 아예 없었다(1440x900 에서 dialog top=-140.5 실측 — 잘린 상단에
          //  제목과 닫기 버튼이 함께 들어가 마우스로 닫을 수단이 화면에 없었다).
          //  상한은 고정 px 이 아니라 뷰포트 기준(100vh - 백드롭 p-4 상하 2rem)이라
          //  해상도가 달라져도 같은 결함이 재발하지 않는다.
          //  제목·설명·푸터는 shrink-0 으로 고정하고 본문만 스크롤한다 — 닫기(X) 버튼이
          //  dialog 기준 absolute 라 항상 화면 안에 남는다.
          // 음영은 DS-001 토큰 3단(sm/md/lg) 중 오버레이용 최상단 `lg` 를 쓴다 —
          // 그 위 단계(xl)는 토큰에 없어 Tailwind 기본값(순수 검정 기반)으로 폴백해
          // KRDS 음영색(rgba(14,21,40,…))과 어긋난다.
          // 모서리는 시안 `.lightbox-box` 의 `--radius-lg`(8px) = borderRadius 토큰 `lg`.
          // 토큰은 sm/md/lg/full 4단뿐이라 그 위 단은 Tailwind 기본값(12px)으로 폴백한다 —
          // 음영과 같은 성질의 조용한 이탈이라 함께 토큰 안으로 되돌렸다.
          'relative flex max-h-[calc(100vh-2rem)] w-full flex-col rounded-lg bg-white p-6 shadow-lg outline-hidden',
          sizeClass[size],
        )}
      >
        {showCloseButton && (
          <button
            type="button"
            onClick={onClose}
            aria-label="닫기"
            className={cn(
              // 색 단계는 시안 `.lightbox-close` 를 따른다 — 평상시 중립 600(#58616A),
              // hover 시 표면 50 + 글자 900. 구 400 은 흰 배경 위 3.08:1 이라 닫기 아이콘이
              // 흐렸고(600 은 6.30:1), hover 도 한 단 진한 표면이라 시안과 어긋나 있었다.
              'absolute right-2 top-2 inline-flex h-11 w-11 items-center justify-center rounded-md text-gray-600 transition-colors hover:bg-gray-50 hover:text-gray-900',
              KRDS_FOCUS,
            )}
          >
            <X className="h-4 w-4" aria-hidden="true" />
          </button>
        )}
        {title && (
          <h2 className="mb-2 shrink-0 text-section-title text-gray-900">{title}</h2>
        )}
        {/* 설명 타이포는 시안 `.dlg-desc` — `.t-body-md`(17/400) + 색 --n-7(gray-700).
            ⚠ 구 `text-sub`(14/400) + gray-500 로 되돌리지 말 것: DS-001 Do's 가 "본문 17px 이상"을
              규정하고, 이 자리는 다이얼로그의 본문 문단이라 보조 캡션 크기가 아니다. */}
        {description && <p className="mb-4 shrink-0 text-body-md text-gray-700">{description}</p>}
        {/* min-h-0 이 없으면 flex 아이템의 자동 최소 크기가 콘텐츠 높이라 overflow 가 발동하지 않는다. */}
        <div className="min-h-0 flex-1 overflow-y-auto">{children}</div>
        {footer && <div className="mt-6 shrink-0 flex justify-end gap-2">{footer}</div>}
      </div>
    </div>
  );

  return createPortal(node, document.body);
}
