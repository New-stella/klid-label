import { cn } from '@/lib/cn';
import { RoleBadge } from '@/components/common/RoleBadge';
import { ROLE_COLOR, ROLE_COLOR_FALLBACK, ROLE_LABEL } from '@/lib/roleDisplay';
import type { Role } from '@/lib/api/types';

/**
 * 「지금 로그인한 사람의 역할」 배지 — 상단 헤더(GNB)와 접근 거부 화면이 함께 쓴다.
 * [@design SHELL-001] [@design SCREEN-003]
 *
 * <h3>세 가지를 구분한다</h3>
 * - **역할이 있다** → 그 역할 이름을 범주 구분색으로 보인다(`@/lib/roleDisplay`).
 * - **역할이 아직 없다** → **미배정**으로 보이고 경고 아이콘을 함께 둔다. 넷 중 하나로 임의로
 *   채우지 않는다 — 구 구현은 역할이 없으면 작업자로 기본값을 채워, 역할을 아직 받지 못한
 *   사람에게 **사실과 다른 역할**을 보여줬다. 접근이 거부된 자리에서 그 표시는 왜 막혔는지를
 *   오히려 흐린다. 역할 없음은 정상 상태가 아니라 조치가 필요한 상태이고 색만으로는 그 차이가
 *   전달되지 않으므로 아이콘을 병기한다.
 * - **모르는 값이다** → 비우지 않고 받은 값을 그대로 중립 색으로 노출한다. 「모르는 역할」과
 *   「역할 없음」은 다른 사실이라 같은 표시로 합치지 않는다.
 *
 * <h3>미배정만 다른 컴포넌트를 쓰는 이유</h3>
 * 미배정 표시(라벨·경고 아이콘·경고 톤)는 `RoleBadge`(사용자 관리 화면의 배지 사양)가 이미
 * 갖고 있다. 그것을 여기에 다시 적으면 같은 사실이 또 두 곳에 적히므로 **재사용**한다. 반면
 * 역할이 있을 때의 색은 두 축이 서로 다르다 — 이쪽은 범주 구분색(우열 없는 분류), 그쪽은
 * semantic 토큰으로 대비를 따로 실측한 화면 사양이다. 그래서 있는 역할까지 `RoleBadge` 로
 * 넘기지 않는다(넘기면 이 두 화면의 역할 색이 통째로 바뀐다).
 */
export interface CurrentRoleBadgeProps {
  /** 현재 사용자의 역할. `null`/`undefined` 는 **미배정**을 뜻한다. */
  role: Role | null | undefined;
  className?: string;
}

/** 두 화면이 공유하는 배지 치수 — 헤더와 접근 거부 카드가 같은 크기로 보여야 한다. */
const BADGE_SHAPE = 'inline-flex items-center px-2.5 py-1 rounded-full text-label font-semibold';

export function CurrentRoleBadge({ role, className }: CurrentRoleBadgeProps) {
  if (!role) {
    // 미배정 — 라벨·경고 아이콘·경고 톤은 배지 사양(RoleBadge)이 소유한다. 치수만 맞춘다.
    return <RoleBadge role={null} className={cn('px-2.5 py-1', className)} />;
  }
  return (
    <span className={cn(BADGE_SHAPE, ROLE_COLOR[role] ?? ROLE_COLOR_FALLBACK, className)}>
      {ROLE_LABEL[role] ?? role}
    </span>
  );
}

export default CurrentRoleBadge;
