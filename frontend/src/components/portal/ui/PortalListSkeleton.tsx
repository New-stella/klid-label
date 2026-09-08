/**
 * 포털 채널 목록 로딩 자리표시자.
 *
 * <h3>왜 «불러오는 중…» 한 줄로 두지 않나</h3>
 * 글 한 줄은 **아무것도 없는 화면과 모양이 거의 같다.** 자리표시자는 «올 것이 있고 그것이 목록
 * 모양이다» 를 형태로 먼저 말해, 결과가 붙는 순간 화면이 튀지 않는다(레이아웃 이동도 함께 준다).
 *
 * <h3>보조기술에는 숨긴다</h3>
 * 회색 상자는 읽을 내용이 없다 — `aria-hidden` 으로 빼고, «불러오는 중» 이라는 사실은 호출부가
 * `role="status"` 문구로 따로 알린다. 자리표시자 자체를 읽히게 두면 빈 항목 N 개가 낭독된다.
 *
 * @design DS-002
 */

import { cn } from '@/lib/cn';

interface PortalListSkeletonProps {
  /** 그릴 줄 수. 실제로 올 건수를 모르므로 «목록이다» 를 말할 만큼만 둔다. */
  rows?: number;
  className?: string;
}

export function PortalListSkeleton({ rows = 3, className }: PortalListSkeletonProps) {
  return (
    <div className={cn('flex flex-col gap-inline', className)} aria-hidden>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="h-12 animate-pulse rounded-tile bg-gray-100" />
      ))}
    </div>
  );
}
