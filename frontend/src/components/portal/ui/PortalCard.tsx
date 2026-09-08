/**
 * 포털 채널 카드 표면 — 제목 줄 + 본문.
 *
 * 관제 공통 `Card` 를 쓰지 않는다(관제향 불변 구속). 형태 축도 갈린다 — DS-002 는 표면을
 * `rounded-surface`(16) 로 두고 **그림자를 기본으로 걸지 않는다**(평면이 출발점).
 *
 * 제목 우측에는 두 자리가 있다.
 *  - `count` — 건수·범위처럼 제목을 보조하는 **텍스트**(조작 아님). 예: "24건 중 1-20".
 *  - `action` — 이 카드가 소유한 **조작 하나**. 목록 전체에 걸리는 것만 둔다(행별 조작은 행이 갖는다).
 *
 * @design DS-002
 * @design SCREEN-033
 */

import type { ReactNode } from 'react';

import { cn } from '@/lib/cn';
import { PORTAL_SURFACE } from '@/components/portal/ui/portalControl';

interface PortalCardProps {
  /** 카드 제목. 지정하지 않으면 제목 줄 자체를 그리지 않는다. */
  title?: string;
  /** 제목을 보조하는 텍스트(건수·범위). 조작이 아니다. */
  count?: ReactNode;
  /** 카드가 소유한 조작 하나. */
  action?: ReactNode;
  /** 랜드마크 이름 — 지정하면 `<section aria-label>` 이 된다. */
  ariaLabel?: string;
  className?: string;
  bodyClassName?: string;
  children: ReactNode;
}

export function PortalCard({
  title,
  count,
  action,
  ariaLabel,
  className,
  bodyClassName,
  children,
}: PortalCardProps) {
  return (
    <section aria-label={ariaLabel} className={cn(PORTAL_SURFACE, 'overflow-hidden', className)}>
      {title !== undefined && (
        <div className="flex flex-wrap items-center justify-between gap-inline border-b border-gray-200 px-in-component py-dense">
          {/* 굵기 상한 600 — 위계는 크기와 색이 만든다. */}
          <h2 className="text-title-sm text-gray-900">{title}</h2>
          <div className="flex items-center gap-inline">
            {count !== undefined && <span className="text-caption text-gray-500">{count}</span>}
            {action}
          </div>
        </div>
      )}
      <div className={cn('p-in-component', bodyClassName)}>{children}</div>
    </section>
  );
}
