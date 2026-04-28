# Component Rules

React 컴포넌트 작성 시 아래 규칙을 항상 적용합니다.

## 컴포넌트 분류

| 분류 | 역할 | 위치 | 예시 |
|------|------|------|------|
| Page | 라우트 진입점, 데이터 fetch | `app/` | `app/orders/page.tsx` |
| Feature | 도메인 로직 포함 컴포넌트 | `components/{domain}/` | `OrderList`, `CartSummary` |
| Common | 재사용 UI 컴포넌트 | `components/common/` | `Button`, `Modal`, `Input` |
| Layout | 페이지 구조 | `components/layout/` | `Header`, `Sidebar` |

## 컴포넌트 크기 기준

- 한 파일 200줄 이하 권장, 400줄 초과 시 분리 필수
- JSX return 50줄 초과 시 하위 컴포넌트로 추출
- Props 7개 초과 시 객체로 그룹화하거나 컴포넌트 분리 검토

## 컴포넌트 파일 구조 (순서)

1. import (외부 → 내부 → 스타일)
2. 타입 정의 (interface Props)
3. 컴포넌트: 3-1. 훅 → 3-2. 핸들러 → 3-3. 조건부 렌더링 (early return) → 3-4. JSX

## 조건부 렌더링

- early return으로 로딩/에러/빈 상태 처리 (`if (isLoading) return <Skeleton />`)
- 간단한 조건은 `&&` 연산자 (`{isAdmin && <AdminPanel />}`)
- 삼항은 간단한 경우만 허용
- 중첩 삼항 금지

## 에러 처리

- 페이지 단위로 Error Boundary 배치 (Next.js: `app/{feature}/error.tsx`)
- 폼 검증: zod + react-hook-form 조합

## 접근성 (a11y) 기본 규칙

- 클릭 가능한 요소: `button` 또는 `a` 태그 사용 (`div onClick` 금지)
- 이미지: `alt` 속성 필수
- 폼 필드: `label`과 연결 필수 (`htmlFor`)
- 모달: 포커스 트래핑, ESC 닫기
- 색상만으로 정보 전달 금지 (아이콘/텍스트 병행)
