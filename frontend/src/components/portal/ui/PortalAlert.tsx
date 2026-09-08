/**
 * 포털 채널 안내 배너 — 제목 한 줄 + 설명, 선택적 조작.
 *
 * 관제 공통 `Alert` 를 쓰지 않는다(관제향 불변 구속). 이 배너는 포털 화면이 **"이 경로가 무엇이
 * 아닌지"** 를 맨 위에서 먼저 알리는 자리로도 쓰인다 — 포털 경로에는 자동 라벨링·검수·버전 관리가
 * 없는데 화면에 그 사실이 없으면 이용자가 «올려두면 알아서 라벨이 붙겠지» 로 기다리게 된다.
 *
 * ★**색만으로 뜻을 전하지 않는다** — 종류마다 아이콘이 함께 붙는다(DS `do_rule`).
 *
 * @design DS-002
 * @design SCREEN-033
 */

import type { ReactNode } from 'react';
import { AlertTriangle, CircleAlert, Info } from 'lucide-react';

import { cn } from '@/lib/cn';

export type PortalAlertTone = 'info' | 'error' | 'warning';

interface PortalAlertProps {
  tone?: PortalAlertTone;
  title: string;
  description?: ReactNode;
  /** 배너가 소유한 조작(예: 「다시 시도」). */
  action?: ReactNode;
  /**
   * `alert` 로 두면 보조기술이 즉시 읽는다 — **방금 일어난 일**에만 쓴다.
   * 화면에 늘 서 있는 안내를 `alert` 로 두면 진입할 때마다 끼어든다.
   */
  live?: boolean;
  className?: string;
  /**
   * 시험이 **배너 자체**를 집을 수 있게 하는 자리.
   * ⚠ 안쪽 글에 걸지 말 것 — `role="alert"` 는 이 바깥 요소가 갖는다.
   */
  'data-testid'?: string;
}

/** 아이콘은 획을 글자 굵기에 맞춘다 — 500 옆이라 1.5. */
const TONE = {
  info: {
    Icon: Info,
    box: 'border-info-200 bg-info-50',
    icon: 'text-info-600',
    title: 'text-info-800',
    desc: 'text-info-800/90',
  },
  error: {
    Icon: CircleAlert,
    box: 'border-danger-200 bg-danger-50',
    icon: 'text-danger-600',
    title: 'text-danger-800',
    desc: 'text-danger-800/90',
  },
  warning: {
    Icon: AlertTriangle,
    box: 'border-warning-200 bg-warning-50',
    icon: 'text-warning-700',
    title: 'text-warning-800',
    desc: 'text-warning-800/90',
  },
} as const;

export function PortalAlert({
  tone = 'info',
  title,
  description,
  action,
  live = false,
  className,
  'data-testid': testId,
}: PortalAlertProps) {
  const t = TONE[tone];
  const { Icon } = t;
  return (
    <div
      role={live ? 'alert' : undefined}
      data-testid={testId}
      className={cn(
        'flex items-start gap-inline rounded-tile border p-dense',
        t.box,
        className,
      )}
    >
      <Icon className={cn('mt-0.5 size-5 shrink-0', t.icon)} strokeWidth={1.5} aria-hidden />
      <div className="flex min-w-0 flex-col gap-tight">
        <span className={cn('text-body-sm font-medium', t.title)}>{title}</span>
        {description !== undefined && (
          <span className={cn('text-caption text-pretty', t.desc)}>{description}</span>
        )}
        {action !== undefined && <div className="mt-tight flex gap-inline">{action}</div>}
      </div>
    </div>
  );
}
