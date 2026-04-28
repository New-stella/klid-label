# State Management Rules

상태 관리 및 API 통신 규칙입니다. React와 Vue 모두 적용됩니다.

## 상태 분류

### React 프로젝트
| 분류 | 도구 | 예시 |
|------|------|------|
| 서버 상태 | React Query (TanStack Query) | API 응답 데이터, 목록, 상세 |
| 클라이언트 상태 | Zustand | 사이드바 열림, 테마, 장바구니 |
| 폼 상태 | React Hook Form + Zod | 입력값, 유효성 검증 |
| URL 상태 | Next.js searchParams | 필터, 페이징, 정렬 |

### 선택 기준
- API 데이터 → React Query (캐싱, 재검증 자동)
- UI 상태 (모달, 토글) → `useState`
- 여러 컴포넌트 공유 클라이언트 상태 → Zustand
- URL 반영 필요 (뒤로가기 유지) → searchParams

## 서버 상태 (React Query)

### Query Key Factory 패턴
```typescript
const ORDER_KEYS = {
  all: ['orders'] as const,
  lists: () => [...ORDER_KEYS.all, 'list'] as const,
  list: (params: OrderParams) => [...ORDER_KEYS.lists(), params] as const,
  details: () => [...ORDER_KEYS.all, 'detail'] as const,
  detail: (id: number) => [...ORDER_KEYS.details(), id] as const,
};
```

### 규칙
- Query Key는 도메인별 상수 객체로 관리 (`ORDER_KEYS`, `PRODUCT_KEYS`)
- 커스텀 훅으로 감싸서 사용 (컴포넌트에서 직접 `useQuery` 호출 금지)
- Mutation `onSuccess`에서 관련 쿼리 무효화 (`invalidateQueries`)
- 에러 처리는 Error Boundary에 위임

## 클라이언트 상태 (Zustand)

- Store는 도메인 단위로 분리 (`useCartStore`, `useAuthStore`)
- 불변성 유지 (spread 연산자로 새 객체 생성)
- Store에 API 호출 로직 넣지 않음 → React Query에 위임
- selector로 필요한 값만 구독: `useCartStore(state => state.items)` (전체 store 구독 금지)

## API 클라이언트

- API URL은 환경변수로 관리 (`NEXT_PUBLIC_API_URL` / `VITE_API_URL`)
- 도메인별 API 모듈 분리 (`order.ts`, `product.ts`)
- 응답 타입 제네릭 명시, 에러는 `ApiError` 커스텀 에러로 변환
- `ApiResponse<T>` 래퍼에서 `result.data` 추출하여 반환

## 환경변수

- `NEXT_PUBLIC_` (Next.js) / `VITE_` (Vite/Vue): 브라우저 접근 가능 (민감 정보 금지)
- 서버 전용 변수: 접두사 없이 사용
- `.env.local`은 `.gitignore`에 포함

---

## Vue 프로젝트 상태 관리 (Pinia)

### 상태 분류
| 분류 | 도구 | 예시 |
|------|------|------|
| 서버 상태 | Composable + API 모듈 | API 응답 데이터, 목록, 상세 |
| 클라이언트 상태 | Pinia | 사이드바 열림, 테마, 장바구니 |
| 폼 상태 | VeeValidate + Zod | 입력값, 유효성 검증 |
| URL 상태 | Vue Router (query) | 필터, 페이징, 정렬 |

### Pinia 규칙
- Setup Store 문법 사용 (Composition API 스타일): `defineStore('name', () => { ... })`
- Store는 도메인 단위로 분리
- Store에 API 호출 로직 넣지 않음 → Composable에 위임
- 불변성 유지 (spread 연산자로 새 배열/객체 생성)
- `storeToRefs()`로 반응형 유지하며 구조 분해 (직접 구조분해 시 반응성 소실)
- actions는 직접 구조 분해 OK

### Vue 환경변수
- `import.meta.env.VITE_API_URL`로 접근
- `VITE_` 접두사 없는 변수는 브라우저에서 접근 불가 (서버 전용)
