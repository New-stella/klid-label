// vitest-axe 매처의 타입 확장.
//
// ⚠ 패키지가 스스로 들고 있는 `Vi` 네임스페이스 확장은 vitest 1.x 의 단언 타입에 닿지 않아
//   `tsc` 가 `toHaveNoViolations` 를 모른다 — **시험만 초록이고 `npm run build` 가 빨개진다**
//   (`npm run test` 는 vite 변환을 써 타입 오류를 잡지 못한다). 그래서 여기서 직접 확장한다.
import type { AxeMatchers } from 'vitest-axe/matchers';

declare module 'vitest' {
  // 제네릭 인자는 vitest 의 `Assertion<T>` 시그니처를 맞추기 위한 것이라 본문에서 쓰이지 않는다.
  // eslint-disable-next-line @typescript-eslint/no-unused-vars, @typescript-eslint/no-explicit-any
  interface Assertion<T = any> extends AxeMatchers {}
  interface AsymmetricMatchersContaining extends AxeMatchers {}
}
