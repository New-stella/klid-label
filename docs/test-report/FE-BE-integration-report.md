# FE↔BE 라이브 연동 검증 리포트

- 일시: 2026-05-07
- 환경: macOS · MariaDB 10.11(127.0.0.1:3307) · BE Spring Boot 3.3 (8080) · FE Vite 5(5174)
- 검증자: ui-tester (Playwright MCP)

## 환경 시작/정리

| 항목 | 결과 |
|------|------|
| DB 초기화 (klid_system, portal) | OK |
| BE bootRun 시작 | 6.45초, Started AuthoringApplication |
| Flyway 마이그레이션 | 11/11 success (V11까지) |
| FE 시작 | 본 프로젝트 frontend가 5173에 떠 있지 않아 별도로 5174에 기동 |
| BE health (/api/actuator/health) | 200(인증된 호출 기준) — 비인증 시 503은 show-details 정책 |
| BE 종료 | OK (kill 후 process 사라짐) |

### 중요 사전 발견 — 5173은 본 프로젝트 FE 아님
- 사용자가 띄워둔 5173 dev server는 `/Users/ck/Documents/workspace/klid-la-test-v0/mock` 디렉토리의 별도 mock 프로토타입. `<title>학습데이터 저작도구 (목업)</title>`, MSW 워커 활성, 검수자 자동 로그인 — BE와 무관.
- 본 프로젝트(`klid-la/frontend`)는 dev server가 떠 있지 않아 `--host` 옵션으로 5174에 기동 후 검증함.

## REVIEWER 시나리오 (INTERNAL)

| # | 페이지 | 결과 | 메모 |
|---|--------|------|------|
| 1 | /ingress?token=… | OK → /video/completed | redirect 즉시, 콘솔 에러 0 |
| 2 | /video/completed | OK | DataTable + 검색폼(텍스트/이벤트유형/기간) + 페이지네이션 정상. 0건(시드 없음) |
| 3 | /dashboard | LNB에서 노출되지 않음(설계상 REVIEWER LNB 메뉴에 dashboard 없음) — 라우터에는 등록되어 있음 |
| 4 | /task | OK | DataTable 노출, 0건 |
| 5 | /task/assign | (미점검 — UI/UX 영향 없음) |
| 6 | /manage/users | 페이지 OK · API 실패 | "사용자 목록을 불러올 수 없습니다" alert. BE `GET /v1/users?page=&size=` endpoint 미구현 (List 부재, `/me /workers /{userNo}`만 존재) |
| 7 | /manage/settings | OK | FFmpeg/배치/위험 액션 3섹션 정상. 헬스 카드만 BE `/v1/manage/health` 미구현으로 "불러올 수 없습니다" |
| 8 | /review | 페이지 OK · API 실패 | DataTable 정상 렌더, BE `/v1/reviews?page=&size=` 응답 형식/매핑 이슈로 alert |
| 9 | /augment | OK | 4종 체크박스(겨울/야간/비/해상도) + 폼 정상. 이력 카드만 alert |
| 10 | /export | OK | 데이터셋/형식(COCO·YOLO·영상+CoT)/NAS 경로 입력 정상. 데이터셋 목록만 alert |

GNB: 영상/작업관리/통계/관리. LNB: 영상·작업관리·검수·통계·데이터·관리 6그룹 노출. 사용자 메뉴 라벨 "검수자" 정상.

## WORKER 시나리오 (INTERNAL)

| # | 페이지 | 결과 |
|---|--------|------|
| 1 | /ingress?token=… | OK → /video/completed. 사용자 메뉴 "작업자" |
| 2 | LNB 메뉴 차이 | OK — REVIEWER에 있던 검수/관리/증강/내보내기/배정 메뉴가 모두 비노출 (가드 동작) |
| 3 | /manage/users 직접 진입 | /forbidden redirect 정상 (RoleGuard) |
| 4 | /review 직접 진입 | /forbidden redirect 정상 (RoleGuard) |

## PORTAL_USER 시나리오 (PORTAL)

| # | 페이지 | 결과 |
|---|--------|------|
| 1 | /ingress?token=… | OK → /portal redirect, PortalLayout 노출 |
| 2 | /portal 콘텐츠 | 첫 1초에는 정상 — 헤더 "AI 학습데이터 포털"·"포털" 라벨, 요약 KPI 2개(업로드/라벨링), 1.업로드(파일 선택 버튼)·2.라벨링 카드, "내 업로드" 목록, 안내문 모두 노출 |
| 3 | /portal 2초 후 | 500 화면 — `TypeError: uploads.filter is not a function` (BE Page 객체 → FE 배열 기대 불일치) |
| 4 | /video/completed 직접 진입 | /forbidden redirect 정상 (ChannelGuard) |

## 콘솔/네트워크

- **콘솔 에러(영구)**: favicon.ico 404 1건만 (무관). 나머지는 BE 응답 형식 mismatch로 인한 React 렌더 에러.
- **BE 401**: 0건 — JWT 토큰(HS256, secret `dev-secret-change-me-please-32bytes-minimum`)으로 모든 요청 인증 통과.
- **BE 200 응답이지만 FE 처리 실패한 endpoint**:
  - `GET /v1/portal/uploads` — BE는 Spring Page 객체 반환, FE는 배열 기대 → PortalHomePage TypeError.
  - `GET /v1/users?page=&size=` — BE에 list endpoint 미구현 (404).
  - `GET /v1/manage/health` — BE 미구현 (404).
  - `GET /v1/reviews?page=&size=`·`/v1/augments?page=&size=`·`/v1/exports/datasets` — BE 응답 또는 미구현으로 alert.

## 식별된 이슈

1. **/v1/portal/uploads 응답 형식 불일치 (Severity: High)** — BE가 페이징 객체를 반환하지만 FE PortalHomePage는 `uploads.filter()`로 배열 가정. 결과: PORTAL_USER 메인 페이지가 2초 후 500 폴백으로 깨짐. 첫 1초만 보면 정상이라 e2e에서 race로 검출되기 쉬움.
2. **/v1/users (REVIEWER 사용자 관리) list endpoint 부재** — UserController에 `/me /workers /{userNo}`만 있고 페이징 list 미구현. UserManagePage가 항상 alert.
3. **/v1/manage/health endpoint 부재** — SystemSettingsPage 헬스 카드 항상 "불러올 수 없습니다".
4. **/v1/reviews · /v1/augments · /v1/exports/datasets** — 응답 형식 또는 미구현 (콘솔 에러 발생).
5. **VideoStatusPage** — 첫 시도(IPv6 listen 시) `/video/status` 자동 진입 시 `Cannot read properties of undefined (reading 'length')` 에러 발생. `GET /v1/batch/status` 응답 `{items: []}`은 정상이나 다른 필드를 length 참조 — 회귀 가능. (재시도 시 `/video/completed`에서 시작되어 재현 안 됨, 환경 의존성 있음.)
6. **REVIEWER LNB에 /dashboard 미노출** — 라우터에는 등록되어 있으나 메뉴에 항목이 없어 사용자가 도달 불가. 의도적인지 확인 필요.

## 잘 동작하는 항목

- JWT Ingress flow (URL token → 채널·역할 분기 → 메인 진입) — REVIEWER/WORKER/PORTAL 모두 OK.
- RoleGuard / ChannelGuard — 권한 없는 페이지 직접 접근 시 /forbidden redirect 정상.
- AppLayout(GNB+LNB) 역할별 메뉴 분기 — REVIEWER 6그룹·WORKER 4그룹.
- /video/completed · /task · /manage/settings · /augment · /export · /review (페이지 셸) 렌더 정상.
- BE→DB 연결, Flyway 마이그레이션, JWT 검증 필터, Vite 프록시(/api → :8080) 모두 정상.

## 환경 정리
- BE 8080 종료: OK (kill, process 미존재 확인).
- FE 5174 종료: OK.
- 5173 mock dev server (사용자 환경): 그대로 유지.
- DB: klid_system / portal 시드 상태 (사용자 1001/1002 + 프로젝트 1) 잔존.
