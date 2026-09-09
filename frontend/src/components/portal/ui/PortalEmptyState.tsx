/**
 * 포털 채널 빈 상태 — 그림 · 무슨 일인지 · **다음에 무엇을 하면 되는지**.
 *
 * <h3>왜 한 줄로 끝내지 않나</h3>
 * *"저장한 작업이 없습니다."* 만 있는 화면은 사실만 말하고 이용자를 그 자리에 세워 둔다. 포털
 * 채널은 특히 그렇다 — **영상을 고르는 목록이 이 배포본에 없어서**(포털이 자기 화면에서 제공한다)
 * 어디로 가야 목록이 채워지는지가 화면 안에 드러나지 않는다. 그래서 설명 한 줄을 규정으로 둔다.
 * [@design SCREEN-028] [@design SCREEN-044]
 *
 * <h3>조작은 있을 때만 둔다</h3>
 * 갈 곳이 이 배포본 안에 있으면(증강 → 내 업로드) 조작을 함께 두고, **갈 곳이 바깥이면 두지
 * 않는다**(내 작업 → 포털 자기 화면). 누를 수 없는 자리를 만들면 빈 상태가 또 한 번 막다른 길이 된다.
 *
 * <h3>보조기술</h3>
 * 조회가 끝나고 결과가 0건이라는 것은 **방금 일어난 일**이라 `role="status"` 로 알린다
 * (`alert` 가 아니다 — 끼어들 만큼 급한 소식이 아니다).
 *
 * @design DS-002
 */

import type { LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';

import { cn } from '@/lib/cn';
import { PORTAL_SURFACE } from '@/components/portal/ui/portalControl';

interface PortalEmptyStateProps {
  /** 그림 한 개. 라벨이 이미 말하는 것을 되풀이하지 않는 축의 글리프를 고른다. */
  icon: LucideIcon;
  /** 무슨 일인지 — 사실 한 줄. */
  title: string;
  /** 다음에 무엇을 하면 되는지 — 이 자리가 이 부품의 존재 이유다. */
  description?: ReactNode;
  /** 갈 곳이 이 배포본 안에 있을 때만 둔다. */
  action?: ReactNode;
  className?: string;
  'data-testid'?: string;
}

export function PortalEmptyState({
  icon: Icon,
  title,
  description,
  action,
  className,
  'data-testid': testId,
}: PortalEmptyStateProps) {
  return (
    <div
      role="status"
      data-testid={testId}
      className={cn(
        PORTAL_SURFACE,
        // 표면은 흰색이 기본이나 빈 상태는 «아직 아무것도 없다» 를 면으로도 말한다(DS-002 50 단계).
        'flex flex-col items-center gap-in-component bg-gray-50 px-in-component py-group text-center',
        className,
      )}
    >
      {/* 획 1.5 — 옆 글자가 500~600 이라 그보다 가늘게 두면 그림만 떠 보인다. */}
      <Icon className="size-10 text-gray-400" strokeWidth={1.5} aria-hidden />
      <div className="flex flex-col gap-tight">
        <p className="text-title-sm text-gray-800">{title}</p>
        {description !== undefined && (
          <p className="max-w-prose text-pretty text-body-sm text-gray-600">{description}</p>
        )}
      </div>
      {action}
    </div>
  );
}
