# E2E Testing Rules

Playwright 기반 E2E 테스트 작성 시 아래 규칙을 적용합니다.

## 프로젝트 감지 기준

| 감지 기준 | 판정 |
|-----------|------|
| `playwright.config.ts` 존재 | Playwright 설정됨 |
| `package.json`에 `@playwright/test` | Playwright 의존성 있음 |
| `e2e/` 또는 `tests/` 디렉토리에 `.spec.ts` | E2E 테스트 존재 |

## 폴더 구조

```
e2e/
├── specs/                    # 테스트 파일 ({feature}.spec.ts)
├── pages/                    # Page Object ({Feature}Page.ts)
├── fixtures/                 # 커스텀 fixture ({feature}.fixture.ts)
└── playwright.config.ts
```

## playwright.config.ts 기본 설정

```typescript
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e/specs',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: [['html', { open: 'never' }], ['list']],
  use: {
    baseURL: process.env.BASE_URL || 'http://localhost:3000',
    trace: 'on-first-retry',
    screenshot: process.env.E2E_CAPTURE ? 'on' : 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:3000',
    reuseExistingServer: !process.env.CI,
  },
});
```

환경별 baseURL은 `.env.local` / `.env.development` / `.env.staging`에서 `BASE_URL` 설정.

## Page Object Model (POM)

- 각 페이지/주요 컴포넌트마다 Page Object 생성
- Page Object는 선택자와 액션을 캡슐화
- 테스트 코드에 선택자 직접 사용 금지 → Page Object를 통해 접근
- 메서드는 사용자 행위 단위로 정의 (`login`, `addToCart` — `click`, `fill` 아님)
- 생성자에서 Locator 정의, `goto()` 메서드로 페이지 이동

## 선택자 우선순위 (Critical)

| 우선순위 | 선택자 | 사용 시점 |
|:--------:|--------|----------|
| 1 | `getByRole()` | 버튼, 링크, 텍스트필드 등 시맨틱 요소 |
| 2 | `getByText()`, `getByLabel()` | 텍스트/라벨로 식별 가능한 요소 |
| 3 | `getByPlaceholder()` | 입력 필드 |
| 4 | `getByTestId()` | 위 선택자로 불가능한 경우 (data-testid 추가) |
| 5 | CSS/XPath | 최후 수단 (레거시 코드 등 불가피한 경우만) |

- CSS 선택자/구현 세부사항 의존 금지 (`.btn-primary`, `[class*="styled-component"]`)
- `data-testid`: kebab-case, 페이지 내 고유, 목록은 컨테이너 레벨에 부여

## 대기 전략

- Playwright auto-waiting 활용 (기본) — `expect(locator).toBeVisible()` 등
- API 응답 대기: `waitForResponse()` 사용
- `waitForTimeout()` / `setTimeout` 사용 금지 (Flaky 원인)
- 동적 콘텐츠: `expect(locator).toHaveCount(n)` 으로 렌더링 완료 확인

## 테스트 작성 규칙

- 테스트 파일: `{feature}.spec.ts` (kebab-case)
- `test.describe` 블록: 한글 기능명 / `test()` 이름: 한글 서술형
- 기본: 각 테스트는 독립적 — 다른 테스트 결과에 의존 금지
- `test.beforeEach`에서 Page Object 초기화 + 페이지 이동
- Assertion은 `expect` API, 한 테스트에 5개 이하 권장

### 순차 의존 플로우 (`test.describe.serial`)

로그인 → 생성 → 확인 → 삭제처럼 앞 단계 결과가 다음 단계의 전제 조건인 경우 사용.

| 기준 | 독립 (`test.describe`) | 순차 (`test.describe.serial`) |
|------|:---------------------:|:----------------------------:|
| 테스트 간 의존성 | 없음 | 있음 (앞 단계 결과 필요) |
| 브라우저 | 매 테스트마다 새로 생성 | 하나를 공유 |
| 실패 시 | 다른 테스트에 영향 없음 | 이후 테스트 자동 스킵 |
| 동시 실행 | 가능 | 불가 (순차만) |
| 사용 시점 | 단일 페이지/기능 검증 | 유저 플로우 전체 검증 |

- 기본은 독립 테스트 — 순차 의존 플로우만 `test.describe.serial` 사용
- serial: `test.beforeAll`로 page 생성, `test.afterAll`로 page 닫기
- 순차 플로우를 한 테스트에 전부 넣지 말고 각 단계를 별도 테스트로 분리

### 인증이 필요한 테스트

`base.extend<AuthFixtures>`로 커스텀 fixture 생성 → `authenticatedPage`로 사용.

## API Mocking

- `page.route()`로 설정 (Playwright 네이티브 선호)
- 응답 형식은 `ApiResponse<T>` 래퍼 준수
- 에러 케이스도 Mock으로 테스트 (4xx, 5xx 응답)
- 실제 서버 테스트는 별도 프로젝트(`integration`)로 분리

## 네이밍

| 대상 | 패턴 | 예시 |
|------|------|------|
| 테스트 파일 | `{feature}.spec.ts` | `order-list.spec.ts` |
| Page Object | `{Feature}Page.ts` | `OrderListPage.ts` |
| Fixture | `{feature}.fixture.ts` | `auth.fixture.ts` |
| 테스트 데이터 | `test-data.ts` | `test-data.ts` |

## 금지 패턴

- `test.describe` 안에서 순서 의존 → `test.describe.serial` 사용
- `waitForTimeout()` / 하드코딩 타임아웃
- Page Object 없이 선택자 직접 사용
- CSS 클래스명 기반 선택 (스타일 변경 시 깨짐)
- 테스트 데이터 하드코딩 → fixture로 관리
- 순차 플로우를 한 테스트에 전부 넣기 → serial로 분리

## CI/CD 통합

- CI: headless 모드, `retries: 2`, `workers: 1`
- 실패 시 스크린샷 + 트레이스를 아티팩트로 저장
