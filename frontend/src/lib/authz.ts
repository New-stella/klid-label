import { Role } from '@/lib/api/types';

/**
 * 역할 인가 판정 — 화면이 「이 역할이 저 자리에 들어갈 수 있는가」를 묻는 **유일한 창구**.
 *
 * <h3>왜 여기 계층이 또 있나 — 구조적으로 사본이다</h3>
 * 인가의 1차 원천은 서버다. 서버는 역할 사이의 포함 관계를 자기 안에 선언해 두고 요청마다
 * 그것으로 판정하지만, 그 선언은 브라우저로 내려오지 않는다. 우리에게 오는 것은 토큰에 실린
 * 역할 **값 하나**뿐이라, 화면이 「관리자에게 검수자 버튼을 그릴지」를 스스로 정하려면 포함
 * 관계를 이쪽에도 적어 두는 수밖에 없다. 즉 이 파일은 없앨 수 있는 중복이 아니라 **채널의
 * 한계에서 나오는 사본**이다.
 *
 * 사본이 지울 수 없는 것이면 비용을 줄이는 쪽으로 간다. 그래서 두 가지를 지킨다.
 *
 * 1. **계층 선언은 이 파일에만 둔다.** 화면·훅·스토어·라우터가 각자 포함 관계를 다시 적으면
 *    한 곳만 고쳤을 때 화면마다 다른 인가가 되고, 그때는 어느 쪽이 사양인지 알 방법이 없다.
 *    바꿀 일이 생기면 {@link ROLE_INHERITS} 한 곳을 고친다.
 * 2. **서버 축과 갈리면 드러나게 한다.** 서버 역할 목록·상속 선언과 이 파일을 직접 대조하는
 *    계약 시험이 있다(`src/test/roleHierarchyContract.test.ts`). 서버가 역할을 늘리거나 포함
 *    관계를 바꾸면 그 시험이 깨져 사본이 낡았다는 사실이 즉시 보인다.
 *
 * <h3>화면 판정은 편의이지 방어선이 아니다</h3>
 * 여기서 참이 나온다고 서버가 허락하는 것이 아니고, 거짓이 나온다고 서버가 막아 주는 것도
 * 아니다. 화면이 버튼을 그리지 않는데 서버는 열려 있다면 그것은 서버 결함이며, 이 파일을
 * 느슨하게 해서 맞출 일이 아니다.
 *
 * @design ADR-055
 * @design ROLE-004
 * @design AC-125
 */

/**
 * 역할 상속 선언 — **계층의 단일 진실원**. 키가 값의 권한을 직접 물려받는다.
 *
 * 한 줄뿐인 것이 의도다. 넓히면 인가가 새어 나간다.
 *
 * - **작업자를 값으로 넣지 않는다.** 작업자에게만 열린 자리가 실재한다(검수 제출 등). 넣으면
 *   관리자·검수자가 그 자리에 흘러들고, 그 화면들이 「누가 하는 일인지」를 구분하지 못하게 된다.
 * - **포털 회원을 넣지 않는다.** 채널이 다르다. 채널 격리는 역할과 별개 축이라 계층으로
 *   뚫려서는 안 된다.
 */
export const ROLE_INHERITS: Readonly<Partial<Record<Role, readonly Role[]>>> = Object.freeze({
  [Role.ADMIN]: Object.freeze([Role.REVIEWER]),
});

/**
 * 이 역할이 물려받는 역할 전부(전이 폐포, **자기 자신 제외**).
 *
 * 지금 계층은 한 단계뿐이라 결과가 직접 상속과 같지만, 단계가 늘어도 판정이 따라오도록 폐포로
 * 계산한다. 순환이 선언되더라도 방문 집합이 무한 루프를 막는다.
 */
export function inheritedRoles(role: Role): Role[] {
  const reached = new Set<Role>();
  const pending: Role[] = [...(ROLE_INHERITS[role] ?? [])];
  while (pending.length > 0) {
    const next = pending.shift() as Role;
    if (next === role || reached.has(next)) continue;
    reached.add(next);
    pending.push(...(ROLE_INHERITS[next] ?? []));
  }
  return [...reached];
}

/**
 * `actual` 역할이 `required` 역할에게 열린 자리에 들어갈 수 있는가.
 *
 * 「같거나 물려받는가」다 — 관리자는 검수자 자리에 들어가고, 작업자 자리에는 들어가지 못한다.
 *
 * @param actual   현재 사용자의 역할. 미부여(`null`/`undefined`)면 거짓(fail-closed)
 * @param required 그 자리가 요구하는 역할. 없으면 거짓(fail-closed)
 */
export function roleSatisfies(
  actual: Role | null | undefined,
  required: Role | null | undefined,
): boolean {
  if (!actual || !required) return false;
  if (actual === required) return true;
  return inheritedRoles(actual).includes(required);
}

/**
 * 여러 역할 중 **하나라도** 만족하면 참. 메뉴 항목·라우트 가드처럼 허용 목록을 갖는 자리용.
 *
 * 빈 목록은 「아무에게도 열려 있지 않다」로 읽어 거짓을 돌려준다 — 목록을 비우는 실수가 전면
 * 개방으로 이어지지 않게 한다.
 */
export function roleSatisfiesAny(
  actual: Role | null | undefined,
  required: readonly Role[] | null | undefined,
): boolean {
  if (!actual || !required || required.length === 0) return false;
  return required.some((r) => roleSatisfies(actual, r));
}

/**
 * 토큰이 실어 온 값이 우리가 아는 역할인가.
 *
 * 서버가 우리가 모르는 역할을 새로 내보내기 시작할 수 있다. 그 값을 **역할로 인정하지는
 * 않지만**(모르는 값에 권한을 줄 수는 없다) 그 사실 하나로 인증 전체를 무효로 만들지도 않는다
 * — 그 판단은 스토어의 토큰 해석기가 한다.
 */
export function isKnownRole(value: unknown): value is Role {
  return typeof value === 'string' && (Object.values(Role) as string[]).includes(value);
}
