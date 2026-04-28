# Frontend Performance Rules

프론트엔드 코드 생성 및 리뷰 시 아래 성능 규칙을 항상 확인합니다.
React(Next.js)와 Vue 모두 적용됩니다.

## 번들 크기 기준

| 항목 | 기준 | 위반 시 |
|------|------|--------|
| 초기 JS 번들 | 200KB 이하 (gzip) | 코드 스플리팅 검토 |
| 단일 청크 | 50KB 이하 | 분리 검토 |
| 이미지 단일 파일 | 200KB 이하 | WebP 변환 + 압축 |

## 코드 스플리팅

- 라우트 단위 dynamic import 필수
- 모달, 드로어 등 초기 화면에 불필요한 컴포넌트는 lazy load

```typescript
// React — 라우트 단위 lazy loading
const OrderPage = lazy(() => import('@/pages/OrderPage'));
const ProductPage = lazy(() => import('@/pages/ProductPage'));

// 조건부 렌더링되는 무거운 컴포넌트
const HeavyChart = lazy(() => import('@/components/HeavyChart'));

// Vue — defineAsyncComponent
const OrderPage = defineAsyncComponent(() => import('@/views/OrderView.vue'));
```

### 금지 패턴
- 최상위 index.ts에서 모든 컴포넌트 re-export (트리쉐이킹 방해)
- 사용하지 않는 라이브러리 전체 import (`import _ from 'lodash'` → `import debounce from 'lodash/debounce'`)

## 이미지 최적화

- `<img>` 태그에 `loading="lazy"` 필수 (above-the-fold 제외)
- Next.js: `next/image` 사용 필수 (`<img>` 직접 사용 금지)
- 이미지 포맷: WebP 우선, PNG/JPG는 최후 수단
- `width`, `height` 명시 필수 (CLS 방지)

```tsx
// Next.js
import Image from 'next/image';
<Image src="/hero.webp" width={800} height={400} alt="..." priority />  // above-the-fold
<Image src="/product.webp" width={300} height={300} alt="..." />         // lazy (기본값)

// Vue / 일반 HTML
<img src="/product.webp" loading="lazy" width="300" height="300" alt="..." />
```

## 렌더링 최적화

### React
- `React.memo`: Props가 자주 바뀌지 않는 리스트 아이템에만 적용
- `useMemo`: 계산 비용이 높은 경우에만 (단순 필터링/정렬에 남용 금지)
- `useCallback`: 자식 컴포넌트에 함수 prop 전달 시에만

```typescript
// 올바른 useMemo 사용 — 실제로 비싼 계산
const sortedOrders = useMemo(
  () => orders.sort((a, b) => b.totalPrice - a.totalPrice),
  [orders]
);

// 남용 — 단순 조회는 불필요
const userName = useMemo(() => user.name, [user]); // X
```

### Vue
- `v-memo`: 자주 렌더링되는 리스트에서 불변 항목에 적용
- `computed`: 파생 데이터에 필수 사용 (메서드 대신)
- `shallowRef` / `shallowReactive`: 대용량 데이터 객체에 검토

## Core Web Vitals 기준

| 지표 | Good | Poor |
|------|------|------|
| LCP (최대 콘텐츠 렌더링) | 2.5초 이하 | 4초 초과 |
| CLS (레이아웃 안정성) | 0.1 이하 | 0.25 초과 |
| INP (상호작용 응답성) | 200ms 이하 | 500ms 초과 |

### LCP 개선
- `priority` 이미지 (above-the-fold) 프리로드
- 폰트: `font-display: swap` 설정

### CLS 개선
- 이미지/비디오에 `width`, `height` 명시 또는 `aspect-ratio` 설정
- 동적으로 삽입되는 콘텐츠(광고, 배너) 영역 사전 확보

## API 호출 최적화

- 중복 요청 방지: React Query/SWR의 deduplication 활용
- 목록 조회: 무한 스크롤 또는 페이지네이션 필수 (전체 조회 금지)
- Debounce: 검색 입력 등 연속 이벤트에 300ms 적용

```typescript
// 검색 debounce
const debouncedSearch = useDebouncedCallback((keyword: string) => {
  searchProducts(keyword);
}, 300);
```

## 금지 패턴

- `import * as Icons from 'react-icons'` — 전체 아이콘 라이브러리 import
- 인라인 객체/함수를 JSX prop으로 직접 전달 (매 렌더마다 새 참조 생성)
- `useEffect` 안에서 무한 루프 유발 (deps 배열 누락 또는 객체 직접 비교)
- CSS-in-JS 라이브러리에서 동적 스타일을 매 렌더마다 재계산
- 폰트 파일 CDN 의존 (FOUT 발생) → 로컬 폰트 + `font-display: swap`
