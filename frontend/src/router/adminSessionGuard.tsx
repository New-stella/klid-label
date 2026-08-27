import { ReactNode, useRef } from 'react';
import { Navigate, useLocation } from 'react-router-dom';

import { isAdminSessionOpen } from '@/features/adminSession/store';

/** 관리자 페이지 진입(게이트) 화면의 주소 — 이 값의 단일 지점이다. */
export const ADMIN_GATE_PATH = '/admin';

/** 되돌아갈 화면을 알 수 없을 때 보낼 관리자 페이지 기본 화면. */
export const ADMIN_DEFAULT_PATH = '/admin/users';

/** 진입 게이트가 `/admin` 으로 넘길 때 싣는 이동 상태. */
export interface AdminGateLocationState {
  /** 원래 열려던 관리 화면의 주소(쿼리 포함). */
  from?: string;
}

/**
 * 관리자 페이지 **진입** 게이트. [@design SCREEN-040] [@design ADR-046]
 *
 * <h3>막는 것은 «진입»이지 «머무름»이 아니다</h3>
 * 유효창 없이 관리 화면 주소를 열면 그 화면 대신 진입 화면이 뜬다. 그러나 <b>일단 들어온 뒤에
 * 유효창이 끝났다고 화면 밖으로 쫓아내지는 않는다</b> — 조회는 검수자 권한만으로 되므로 보고
 * 있던 값이 사라질 이유가 없고, 만료는 「저장이 막히고 재확인을 받는다」로 나타난다.
 *
 * <p>그래서 판정을 <b>첫 렌더 한 번</b>만 하고 그 결과를 이 마운트 동안 유지한다. 매 렌더 판정
 * 하면 다음 두 가지가 깨진다:
 * <ul>
 *   <li>패스워드 교체 성공 화면 — 교체가 성공하면 <b>방금 쓴 유효창까지 무효</b>가 되는데,
 *       그때 쫓아내면 성공 안내를 아무도 못 본다. 사용자는 값이 잘못 저장된 줄 알고 되돌리려 든다.</li>
 *   <li>연동 주소 화면 — 사양이 「확인을 마치지 않은 상태에서도 현재 값은 보이고 저장만 막힌다」
 *       고 규정한다.</li>
 * </ul>
 *
 * <h3>진입 화면 자신에게는 걸지 않는다</h3>
 * `/admin` 은 확인만 맡는 자리다. 거기에 이 가드를 걸면 유효창이 없을 때 자기 자신으로 무한히
 * 되돌아간다.
 *
 * <h3>역할 가드를 대체하지 않는다</h3>
 * 유효창은 인가에 <b>가산</b>된다. 이 가드는 항상 역할 가드 <b>안쪽</b>에 놓여, 검수자가 아닌
 * 사용자는 패스워드를 알더라도 관리 화면에 닿지 못한다.
 */
export function AdminSessionGuard({ children }: { children: ReactNode }) {
  const location = useLocation();
  // 첫 렌더 시점의 판정을 이 마운트 동안 고정한다(위 javadoc 참조).
  const admitted = useRef<boolean | null>(null);
  if (admitted.current === null) {
    admitted.current = isAdminSessionOpen();
  }

  if (!admitted.current) {
    const from = `${location.pathname}${location.search}`;
    return (
      <Navigate
        to={ADMIN_GATE_PATH}
        replace
        state={{ from } satisfies AdminGateLocationState}
      />
    );
  }

  return <>{children}</>;
}
