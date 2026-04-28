# Frontend Coding Style Rules (공통)

이 규칙은 모든 TypeScript 프론트엔드 코드에 자동 적용됩니다.
프레임워크별 규칙은 별도 파일을 참고합니다: `component.md` (React), `vue.md` (Vue)

## TypeScript 규칙
- `strict: true` 필수 (tsconfig.json)
- `any` 사용 금지 → `unknown` 또는 구체적 타입 사용
- `as` 타입 단언 최소화 → 타입 가드 또는 제네릭 활용
- interface 우선 사용 (type alias는 유니온/인터섹션에만)
- Optional chaining(`?.`), Nullish coalescing(`??`) 적극 활용
- enum 대신 `as const` 객체 사용

```typescript
// WRONG
const status: any = response.data;
enum Status { ACTIVE = 'ACTIVE', INACTIVE = 'INACTIVE' }

// CORRECT
const status: unknown = response.data;
const STATUS = { ACTIVE: 'ACTIVE', INACTIVE: 'INACTIVE' } as const;
type Status = typeof STATUS[keyof typeof STATUS];
```

## 네이밍 (공통)
- 컴포넌트 파일: PascalCase (`OrderList.tsx`, `OrderList.vue`)
- 유틸 파일: camelCase (`formatDate.ts`)
- 타입/인터페이스: PascalCase (`OrderResponse`)
- 상수: UPPER_SNAKE_CASE (`MAX_PAGE_SIZE`)
- CSS 클래스: kebab-case 또는 Tailwind 유틸리티

## 불변성 (Critical)
- 상태 변경 시 항상 새 객체/배열 생성, 직접 변경(mutation) 금지

```typescript
// WRONG
items.push(item);           // mutation!
user.name = newName;        // mutation!

// CORRECT
const newItems = [...items, item];
const updatedUser = { ...user, name: newName };
```

## 공통 금지 패턴
- `document.getElementById()` 등 직접 DOM 조작 → 프레임워크 ref 사용
- index를 key/`:key`로 사용 (리스트가 변하는 경우)
- `console.log` 커밋 금지
- `!important` CSS 금지 (불가피한 경우 사유 주석)
- 하드코딩된 API URL → 환경변수 사용
