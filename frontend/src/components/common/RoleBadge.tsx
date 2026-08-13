import { AlertTriangle } from 'lucide-react';

import { cn } from '@/lib/cn';
import type { Role } from '@/lib/api/types';

/**
 * 역할 배지 (UI-110).
 *
 * 사용자의 역할(검수자/작업자/포털 사용자/미배정)을 표시한다.
 *
 * ⚠ **StatusBadge(UI-014)와 합치지 말 것** — 그쪽은 작업·배치 워크플로 *상태* 축(16종
 *   매핑)이고 이쪽은 *역할* 축이다. 의미 축이 달라 별도 컴포넌트로 둔다(UI-110 description).
 *
 * 색상 대비(WCAG 실측): 검수자 6.09:1(AA) · 작업자 10.01:1(AAA) · 미배정 8.43:1(AAA) ·
 * 포털 11.08:1(AAA). 모든 variant 가 역할명 텍스트를 함께 표시하므로 색상 단독으로
 * 의미를 전달하지 않으며, 미배정만 경고 아이콘을 추가로 병기한다.
 */
export interface RoleBadgeProps {
  /** 역할 코드. `null`(또는 미지정)은 **미배정**을 뜻한다. */
  role: Role | null | undefined;
  /** 기본 매핑 라벨을 덮어쓸 때만 지정. */
  label?: string;
  className?: string;
}

interface RoleConfig {
  label: string;
  className: string;
}

// 미배정 키 — `role` 이 null/undefined 일 때 쓰는 내부 센티넬.
const UNASSIGNED = 'UNASSIGNED';

const ROLE_CONFIG: Record<string, RoleConfig> = {
  REVIEWER: { label: '검수자', className: 'bg-primary-50 text-primary-600' },
  WORKER: { label: '작업자', className: 'bg-secondary-50 text-secondary-700' },
  PORTAL_USER: { label: '포털 사용자', className: 'bg-gray-50 text-gray-800 border border-border' },
  [UNASSIGNED]: { label: '미배정', className: 'bg-warning-50 text-warning-700' },
};

export function RoleBadge({ role, label, className }: RoleBadgeProps) {
  const key = role ?? UNASSIGNED;
  // 타입 밖의 값(구 응답·목 데이터)이 들어와도 배지를 비우지 않는다 — 미배정으로 떨어뜨리면
  // "역할이 없다"는 거짓을 말하게 되므로, 알 수 없는 코드는 원문을 중립 톤으로 노출한다.
  const config = ROLE_CONFIG[key] ?? {
    label: String(key),
    className: 'bg-gray-100 text-gray-700',
  };
  const isUnassigned = key === UNASSIGNED;

  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-label font-semibold',
        config.className,
        className,
      )}
    >
      {isUnassigned && <AlertTriangle className="h-3 w-3 shrink-0" aria-hidden="true" />}
      {label ?? config.label}
    </span>
  );
}

export default RoleBadge;
