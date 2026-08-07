# 배치 G — 진입·개발 축 (SCREEN-001/002/003/004/027)

## 요약
- 발견: ERR 9 / STALE 3 / GAP 6 / CONFLICT 2 (총 20건, 일부 중복 분류 포함)
- 가장 오염도가 높은 화면은 **SCREEN-027**(오토라벨 테스트/개발 업로드) — 2026-08-06 공통 컴포넌트 교체 이후 정의서가 갱신되지 않아 절반 이상의 서술이 현재 코드와 어긋난다.
- SCREEN-001/002/003/004는 골격(라우트·역할 분기·필드 바인딩)은 대체로 정확하나, **개발 전용 화면의 운영 노출 차단 조건**(SCREEN-004)과 **가드 컴포넌트 명명**(SCREEN-003)에 실제와 다른 서술이 있다.

## SCREEN-001/002/003 의 성격 판정
| SCREEN | 실제 라우트 | 판정(화면 / 가드 사양) | 근거 |
|---|---|---|---|
| SCREEN-001 | `/ingress` | **화면** — 실제 마운트된 페이지(스피너→분기, 에러 배너 렌더링 로직 보유) | `router/index.tsx`(`{ path: '/ingress', element: <SessionIngressPage /> }`) · `features/auth/SessionIngressPage.tsx` |
| SCREEN-002 | `/role-claim` | **화면** — `AuthenticatedGuard` 로 감싼 실제 폼 페이지 | `router/index.tsx`(`{ path: '/role-claim', element: <AuthenticatedGuard>...RoleClaimPage</AuthenticatedGuard> }`) · `pages/RoleClaimPage.tsx` |
| SCREEN-003 | `/forbidden` | **화면** — 가드 밖에서 직접 매칭되는 실제 페이지(가드 거부 시 도착지) | `router/index.tsx`(`{ path: '/forbidden', element: <ForbiddenPage /> }`) · `components/common/ForbiddenPage.tsx` |

세 화면 모두 "가드 동작을 화면으로 표현한 것"이 아니라 **참조 구현(테스트베드 FE, `upload-ui/frontend`)에 실제로 마운트된 라우트/컴포넌트**다. 다만 **납품 FE(`klid-label-frontend`)에는 이 세 라우트가 전혀 없다** — `routes/index.tsx`(납품)에는 `/dev/login`→`/dashboard` 직행 흐름뿐이고 `/ingress`·`/role-claim`·`/forbidden` 자체가 부재한다(`RequireAuth.tsx`/`RequireRole.tsx`가 각각 `/dev/login`, `/dashboard` 로 단순 리다이렉트할 뿐 별도 화면을 렌더링하지 않음). 이는 정의서 품질 문제라기보다 **두 구현체 간 반영 시점 격차**이므로 별도 분류로 두되, LogiCraft 정의서가 "구현됨(progress 100%)"이라 표기한 대상이 어느 구현체 기준인지 모호하다는 점만 [GAP] 로 남긴다(아래 GAP-G15).

---

## SCREEN-001 (세션 인계 진입 화면)

### [ERR-G01] DEV 빌드 가드 서술이 실제 판정 함수보다 좁다
- **위치**: sections[1] "② 인증 실패 안내" > description
- **정의서 서술**: "DEV 빌드는 /dev/login으로 이동하고, 운영은 detectChannel 기준 상위 시스템(...)으로 redirect"
- **실제**: 분기 조건은 `import.meta.env.DEV` 단독이 아니라 `isDevLoginEnabled()` = `import.meta.env.DEV || VITE_DEV_LOGIN_ENABLED === 'true'`. 즉 **prod 빌드라도 빌드타임 플래그를 켜면 `/dev/login` 으로 보낸다**(폐쇄망 bring-up 용도, 기본 OFF).
- **근거**: `features/auth/SessionIngressPage.tsx(handleAuthFailure)` · `lib/devLogin.ts(isDevLoginEnabled)`
- **조치 제안**: "DEV 빌드는" → "`isDevLoginEnabled()`(DEV 빌드 또는 `VITE_DEV_LOGIN_ENABLED=true` 빌드타임 플래그)가 참이면"으로 정정.
- **확신도**: high

### [GAP-G02] `VITE_DEV_TOKEN` 환경변수 자동 주입 경로 미서술
- **위치**: sections[0] "① 세션 확인 (로딩)" > description
- **정의서 서술**: "resolveToken(URL ?token= / localStorage[klid-jwt-token] / cookie[klid_jwt] 전략)으로 토큰 수령"
- **실제**: `resolveToken` 3-경로 외에, **DEV 빌드에서만** 토큰이 하나도 없으면 `import.meta.env.VITE_DEV_TOKEN` 값을 대체 토큰으로 사용하는 4번째 경로가 있다. 이 경로가 있어야 관제서버 미연결 상태에서도 `/ingress` 진입만으로 세션이 성립하는 개발 시나리오가 성립한다.
- **근거**: `features/auth/SessionIngressPage.tsx`(`if (!token && import.meta.env.DEV) { const devToken = import.meta.env.VITE_DEV_TOKEN ...}`)
- **조치 제안**: sections[0] description에 "[개발 전용] `VITE_DEV_TOKEN` 환경변수로 토큰 자동 주입" 1문장 추가.
- **확신도**: high

---

## SCREEN-002 (역할 클레임 화면)

### [GAP-G03] 제출 payload에 `resolveHandoffUser()`(userId/userNm) 동봉이 누락
- **위치**: sections[2] "권한 부여 폼" > description
- **정의서 서술**: "제출 시 POST /v1/auth/role-claim 호출" — role·adminPassword 외 다른 필드 언급 없음
- **실제**: 제출 시 `mutation.mutate({ role, adminPassword, ...resolveHandoffUser() })` — 관제서버가 localStorage(`userId`/`userNm`)로 인계한 표시용 사용자 정보를 **함께 전송**하며, BE가 이를 사용자 마스터(LS_ACNT_USER) 자동등록에 사용한다. 제3자가 정의서만 보고 구현하면 이 필드를 빠뜨려 사용자 자동등록 기능이 성립하지 않는다.
- **근거**: `pages/RoleClaimPage.tsx(handleSubmit)` · `features/auth/tokenIngress.ts(resolveHandoffUser)`
- **조치 제안**: description에 "관제서버가 localStorage(userId/userNm)로 인계한 표시용 사용자 정보를 요청에 동봉 — BE가 사용자 마스터 자동등록에 사용" 추가.
- **확신도**: high

### [GAP-G04] 403(화이트리스트 밖 역할) 에러 케이스 미서술
- **위치**: sections[2] "권한 부여 폼" > description
- **정의서 서술**: "실패 시 status 별 에러 메시지(401 패스워드 불일치·409 이미 부여·429 시도 초과·기타 일반)"
- **실제**: 401/409/429 외에 **403**("해당 역할은 자가 부여할 수 없습니다. 검수자에게 권한 부여를 요청하세요.") 분기가 별도로 존재한다. 현재 UI는 WORKER/REVIEWER만 노출해 정상 동선에서 도달하지 않지만, BE 화이트리스트가 바뀌거나 요청을 직접 변조하면 도달 가능한 케이스다.
- **근거**: `pages/RoleClaimPage.tsx(toUserMessage)`
- **조치 제안**: 에러 케이스 목록에 "403 화이트리스트 밖 역할" 추가.
- **확신도**: medium

---

## SCREEN-003 (접근 거부 화면)

### [ERR-G05] "PortalGuard" 는 실존하는 가드 컴포넌트가 아니다
- **위치**: sections[0] "접근 거부 안내 hero" > description (2건, main/static_renders 양쪽에 동일 서술)
- **정의서 서술**: "role 가드(RoleGuard/ChannelGuard/PortalGuard) 위반 시 /forbidden 으로 navigate"
- **실제**: 프로덕션 코드에는 `RoleGuard`와 `ChannelGuard` 두 컴포넌트만 존재한다(`router/guards.tsx`). "PortalGuard"라는 이름의 컴포넌트는 **테스트 파일 안에서 지역적으로 정의된 헬퍼**(`router/__tests__/portalGuard.test.tsx`)일 뿐 프로덕션 라우터에 존재하지 않는다. 실제 포털 라우트는 `PortalRoute` 헬퍼가 `ChannelGuard(channel="PORTAL")` 로 `RoleGuard(allow=[PORTAL_USER])`를 감싸는 **합성**으로 구현된다(`router/index.tsx(PortalRoute)`) — 별도 3번째 가드 클래스가 아니다.
- **근거**: `router/guards.tsx`(RoleGuard·ChannelGuard·AuthenticatedGuard 3종만 export) · `router/index.tsx(PortalRoute, InternalRoute)` · `router/__tests__/portalGuard.test.tsx`(로컬 테스트 전용 함수)
- **조치 제안**: "RoleGuard/ChannelGuard/PortalGuard" → "RoleGuard 또는 ChannelGuard(포털 라우트는 ChannelGuard+RoleGuard 합성)"로 정정. 제3자가 "PortalGuard"라는 별도 컴포넌트를 새로 만들게 유도하는 서술이라 구현 오류 유발 가능성이 크다.
- **확신도**: high

---

## SCREEN-004 (개발용 로그인 화면)

### [ERR-G06] "운영 미노출"의 실제 보장 기제가 정의서보다 훨씬 복잡하다
- **위치**: `purpose` 필드 + sections[0] "DEV 안내 헤더" > description
- **정의서 서술**: "라우터(index.tsx)가 import.meta.env.DEV 가드로 prod 빌드에서 마운트 자체를 차단하므로 운영에서는 노출되지 않음"
- **실제**: 3중 구조다.
  1. FE 라우트 노출은 `isDevLoginEnabled()` = `DEV || VITE_DEV_LOGIN_ENABLED==='true'` — **prod 빌드도 플래그를 켜면 라우트가 번들에 포함**된다(폐쇄망 bring-up 지원 목적, 기본 OFF).
  2. BE `/v1/dev/tokens` 엔드포인트 자체가 `@ConditionalOnProperty(authoring.dev.login.enabled=true)` 로 조건부 빈 등록 — 꺼져 있으면 FE에서 라우트가 열려도 호출이 404/미존재로 실패한다.
  3. `DevToggleProfileGuard` 가 **prd·stg 프로파일에서 `authoring.dev.login.enabled=true` 조합이면 기동 자체를 `IllegalStateException` 으로 실패시킨다**(fail-closed) — `/v1/dev/tokens`가 permitAll·무인증 임의 권한 토큰 발급 엔드포인트라 운영에서 켜지면 인증 체계 전체가 우회되기 때문.
  정의서는 이 중 (1)의 절반(순수 DEV 빌드 케이스)만 서술하고, 의도적으로 지원하는 "prod 빌드 + 플래그" 조합과 BE의 이중 방어(런타임 토글 + 프로파일 fail-fast)를 전혀 언급하지 않는다. 이 화면은 보안 등급이 높은 화면이므로 제3자가 정의서만으로 구현하면 방어선 2개(BE 조건부 등록, 프로파일 fail-fast)를 누락하기 쉽다.
- **근거**: `lib/devLogin.ts(isDevLoginEnabled)` · `backend .../dev/controller/DevTokenController.java`(`@RequestMapping("/v1/dev")` + `@ConditionalOnProperty(authoring.dev.login.enabled)`) · `backend .../dev/DevToggleProfileGuard.java(verify)` · `backend .../dev/DevToggleStartupWarner.java`
- **조치 제안**: purpose/description을 "① FE: DEV 빌드 또는 `VITE_DEV_LOGIN_ENABLED=true` 빌드플래그로만 라우트 포함 ② BE: `authoring.dev.login.enabled=true` 일 때만 `/v1/dev/tokens` 빈 등록(fail-closed 기본 OFF) ③ prd/stg 프로파일에서 ②가 true 면 기동 자체를 거부"로 재작성.
- **확신도**: high

---

## SCREEN-027 (오토라벨 테스트 화면 — 개발)

이 화면은 `change_summary`(2026-07-30)에 "API-043 참조 변경에 따른 stale이며 화면 내용 자체는 변경 없음"이라 기록돼 있으나, 실제로는 **2026-08-06 공통 컴포넌트 교체(FileInput 신설, 507→349줄 분할) 시점에 화면 구성이 크게 달라졌고 정의서가 그 변화를 전혀 반영하지 않았다.** 아래 개별 항목 외에 이 change_summary 자체가 [CONFLICT] 로 기록될 근거다(G15).

### [STALE-G07] "실행 단계 선택 (4종 토글)" 컴포넌트가 통째로 폐기됨
- **위치**: sections[0] "파일 + 메타 입력 폼" > components 中 "실행 단계 선택 (4종 토글)" (Checkbox, 옵션 4개: 프레임 추출/비식별/YOLO/SAM2)
- **정의서 서술**: "기본 모두 ON, 미선택 단계는 건너뜀"
- **실제**: 현재 dev 업로드는 **고정 플로우**다 — 업로드 → 비식별(무조건) → `MARKING_READY` 정지. 단계 토글 필드(`enabledStages`) 자체가 요청 DTO에서 제거됐고 화면에도 체크박스가 없다.
- **근거**: `pages/dev/DevAutolabelTestPage.tsx`(컴포넌트 상단 주석 "단계 토글/마킹 직접 수행 분기는 없으며") · `backend .../dev/dto/AutolabelTestRequest.java`(클래스 javadoc "enabledStages ... 같은 분기 필드는 두지 않는다") · `features/dev/types.ts(AutolabelTestMeta)`(동일 주석)
- **조치 제안**: 해당 컴포넌트 항목 삭제, description을 "업로드 → 비식별(무조건 실행) → 마킹 대기(MARKING_READY) 정지 — 고정 플로우, 단계 선택 없음"으로 교체.
- **확신도**: high

### [ERR-G08] `eventTypeCd` 는 "SFR 6종 화이트리스트"가 아니라 관제 마스터 EV-코드다
- **위치**: sections[0] > components 中 "eventTypeCd"(Select, 옵션 EVT_FALL/EVT_VIOLENCE/EVT_ACCIDENT/EVT_ABNORMAL/EVT_FLOOD/EVT_FIRE)
- **정의서 서술**: "SFR 6종 화이트리스트 select (EVENT_TYPES SoT)"
- **실제**: BE는 이 필드를 `^EV[0-9]{8}$` 형식(이벤트유형마스터 `LS_EVNT_TYPE` 등록 코드, 예 `EV02000201`=쓰러짐)으로 검증하며, 정의서가 나열한 `EVT_FALL` 등 6개 값은 이 정규식과 전혀 형식이 다르다(모두 400 거부됨). FE도 고정 6종 select가 아니라 `useEventTypes()` 로 **관제 이벤트 카테고리 9종을 동적으로 조회**해 사용자가 카테고리를 고르면 그 카테고리의 대표 EV-코드(`memberCodes[0]`)를 전송한다.
- **근거**: `backend .../dev/dto/AutolabelTestRequest.java`(`@Pattern(regexp = "^EV[0-9]{8}$")`) · `pages/dev/DevAutolabelTestPage.tsx`(`useEventTypes()`, `selectedEventCode = opt?.memberCodes[0]`) · `features/dev/types.ts`(주석 "관제 마스터 상세 EV-코드... 카테고리(9종)")
- **조치 제안**: 옵션 목록을 삭제하고 "이벤트유형마스터(LS_EVNT_TYPE) 조회 API 기반 카테고리(9종) select — 선택 시 해당 카테고리의 대표 EV-코드(형식 `EV`+숫자 8자리)를 전송"으로 전면 재작성.
- **확신도**: high

### [STALE-G09] `prvcTypeCd` 라디오 라벨이 폐기된 선택적 비식별 정책을 그대로 담고 있다
- **위치**: sections[0] > components 中 "prvcTypeCd"(RadioGroup, 옵션 "ANONY (원본만 저장)"/"PRVC (비식별 처리 대상)"/"PSDO (비식별 처리 대상)")
- **정의서 서술**: 위 라벨 그대로 — ANONY는 비식별 미대상, PRVC·PSDO만 비식별 대상이라는 구 정책을 그대로 반영
- **실제**: 프로젝트 CLAUDE.md에 구속 정책으로 명시된 대로 **비식별은 전체 영상에 무조건 자동 실행**되며(구 'PRVC/PSDO만' 게이팅은 폐지) 이 라디오 값은 순수 표시용이다. 실제 코드 라벨은 "ANONY (비식별 미적용)"/"PRVC (개인정보 포함)"/"PSDO (가명 정보)"이고 3개 모두에 "표시용 — 비식별은 선두 무조건 실행"이라는 힌트가 붙어 있어, 선택값이 처리 여부에 영향을 주지 않음을 명시한다.
- **근거**: `features/dev/components/devUploadForm.ts(PRVC_OPTIONS)` · 프로젝트 CLAUDE.md "비식별 호출 조건" 절("전체 영상 비식별 후 마킹... 게이팅 폐지 — 무조건 자동 실행")
- **조치 제안**: 라벨을 실제 문구로 교체 + "선택값은 표시용이며 비식별 실행 여부에 영향 없음" 명시. 이 항목은 폐기된 확정 정책이 정의서에만 화석처럼 남아있는 사례라 특히 우선순위 높게 정정.
- **확신도**: high

### [GAP-G10] 이 라우트에는 정의서에 없는 두 번째 대형 섹션(TUS 재개 업로드 패널)이 존재한다
- **위치**: SCREEN-027 전체(spec의 sections는 2개뿐: "파일 + 메타 입력 폼", "실행 결과 + 파이프라인 상태")
- **정의서 서술**: 없음 — 두 섹션 외 다른 UI 요소 서술 전무
- **실제**: `DevAutolabelTestPage`는 위 폼 Card 아래에 `<TusUploadPanel />` 을 **인라인 렌더링**한다. 이 패널은 "TUS 재개 가능 업로드 (대용량) — 관제 인입 재현" 이라는 별도 Card로, 다음을 포함한다: 파일 선택(FileInput), 4개 fieldset(`IdentityFieldset`/`LocationFieldset`/`EventFieldset`/`TechnicalMetaFieldset`), 진행률 바, 시작/일시정지/재개/취소 버튼, 완료·에러 상태 배너. `EventFieldset` 안에는 프로젝트 CLAUDE.md가 2026-08-06 확정한 "검증이벤트유형 6종 프리셋 + 직접 입력(센티넬 `__manual__`, 서버는 목록이 아니라 형식만 검증)" 정책을 구현한 `VrfcEvntTypeField` 가 들어있다. 정의서 어디에도 이 패널·필드셋 4종·재개 업로드 컨트롤이 언급되지 않는다 — 화면의 절반 가까운 실제 UI 표면이 정의서에서 누락된 상태다.
- **근거**: `pages/dev/DevAutolabelTestPage.tsx`(`<TusUploadPanel />` 렌더) · `features/upload/components/TusUploadPanel.tsx` · `features/upload/components/TusMetaFieldsets.tsx`(`VrfcEvntTypeField`, `VRFC_MANUAL_OPTION = '__manual__'`)
- **조치 제안**: 신규 섹션 "TUS 재개 가능 업로드 (관제 인입 재현)" 추가 — 파일 입력, Identity/Location/Event/TechnicalMeta 4 fieldset(특히 검증이벤트유형 6종+직접입력 필드), 진행률·일시정지/재개/취소 컨트롤을 모두 명세.
- **확신도**: high

### [ERR-G11] "단계별 진행 칩"은 실제로 렌더링되지 않는다
- **위치**: sections[1] "실행 결과 + 파이프라인 상태" > components 中 "단계별 진행 칩"(List, `videoDetail.stages[].name·status·progress%`, triggers_api API-043)
- **정의서 서술**: "videoDetail.stages 있으면 단계별 칩(name·status·progress%) 나열"
- **실제**: 결과 카드(`AutolabelResultCard`)는 rawSn 칩·파이프라인 상태 텍스트·저장 파일 경로·프레임 수·트리거 시각·(마킹 대기/실패 시) 상태 배너·하단 링크 2개만 렌더링한다. `stages` prop 자체를 컴포넌트에 전달하지 않으며, 단계별 name/status/progress%를 나열하는 UI가 코드에 존재하지 않는다.
- **근거**: `features/dev/components/AutolabelResultCard.tsx`(props: `result/status/frameCount/reachedTerminal/reachedMarkingReady` — `stages` 없음) · `pages/dev/DevAutolabelTestPage.tsx`(해당 컴포넌트 호출부에 stages 미전달)
- **조치 제안**: "단계별 진행 칩" 컴포넌트 항목 삭제.
- **확신도**: high

### [ERR-G12] Polling 종료(terminal) 조건이 실제와 다르다 — COMPLETED 가 아니라 MARKING_READY
- **위치**: sections[1] > description
- **정의서 서술**: "status가 COMPLETED/FAILED 도달 시 polling 중단"
- **실제**: dev 업로드는 고정 플로우(업로드→비식별→`MARKING_READY` 정지)라 **이 경로에서 영상 상태가 COMPLETED 로 가는 일이 없다.** 실제 terminal 조건은 `status === 'MARKING_READY' || status === 'FAILED'` 이며, `MARKING_READY` 도달 시 "마킹 대기" 안내 배너를 띄우고 polling을 멈춘다.
- **근거**: `pages/dev/DevAutolabelTestPage.tsx`(`reachedMarkingReady = videoDetail?.status === 'MARKING_READY'`, `reachedTerminal = reachedMarkingReady || videoDetail?.status === 'FAILED'`, 상단 주석 "업로드 경로에서 영상은 COMPLETED 로 가지 않는다")
- **조치 제안**: "COMPLETED/FAILED" → "MARKING_READY(마킹 대기)/FAILED"로 정정 + MARKING_READY 도달 시의 안내 배너 컴포넌트 추가 서술.
- **확신도**: high

### [ERR-G13] "영상 상세 보기 → /video/{rawSn}" 링크가 실제와 다르다
- **위치**: sections[1] > components 中 "영상 상세 보기 →"(Link, `/video/{rawSn}` 이동)
- **정의서 서술**: "/video/{rawSn} 이동 (react-router Link)"
- **실제**: 결과 카드 하단에는 두 개의 링크가 있다 — "마킹 화면으로 이동 →"(`/marking/{rawSn}`), "영상 목록 보기 →"(`/video/completed`). `/video/{rawSn}` 형태의 개별 영상 상세 링크는 존재하지 않는다. 고정 플로우가 MARKING_READY에서 멈추므로 다음 동선은 "상세 보기"가 아니라 "마킹하러 가기"로 설계가 바뀐 것으로 보인다.
- **근거**: `features/dev/components/AutolabelResultCard.tsx`(`<Link to={\`/marking/${result.rawSn}\`}>` · `<Link to="/video/completed">`)
- **조치 제안**: 링크 항목을 "마킹 화면으로 이동(`/marking/{rawSn}`)" + "영상 목록 보기(`/video/completed`)" 2건으로 교체.
- **확신도**: high

### [GAP-G14] 이중 게이팅(FE 빌드플래그 + BE 런타임 토글)이 서술되지 않음
- **위치**: `purpose` 필드
- **정의서 서술**: "개발/검수 용 오토라벨 테스트 화면... 비운영 용도. 접근: REVIEWER."
- **실제**: SCREEN-004(G06)와 동일한 이중 구조다. ① FE: `isDevUploadEnabled()`(DEV 빌드 또는 `VITE_DEV_UPLOAD_ENABLED=true`)가 아니면 라우트/청크 자체가 번들에서 빠진다. ② BE: `/v1/dev/autolabel-test` 는 `@ConditionalOnProperty(authoring.dev.upload.enabled=true)` 로 조건부 빈 등록(기본 false)이며, 추가로 `@PreAuthorize("hasRole('REVIEWER')")`. 다만 이 토글은 SCREEN-004의 `authoring.dev.login.enabled`와 달리 `DevToggleProfileGuard`(prd/stg 기동 거부) 대상이 **아니다** — 인증 우회 경로가 아니라 REVIEWER 인가 게이트 뒤에 있기 때문(코드 주석에 명시적 근거 있음). "접근: REVIEWER" 한 줄로는 이 두 겹의 토글 구조와 SCREEN-004와의 비대칭(왜 이 화면은 프로파일 fail-fast 대상이 아닌지)이 전혀 드러나지 않는다.
- **근거**: `router/index.tsx`(`isDevUploadEnabled()` 가드 + `InternalRoute allow={internalReviewerOnly}`) · `backend .../dev/controller/DevAutolabelTestController.java`(`@ConditionalOnProperty(authoring.dev.upload.enabled)`, `@PreAuthorize("hasRole('REVIEWER')")`) · `backend .../dev/DevToggleProfileGuard.java`(javadoc "dev 업로드 토글은 대상이 아니다 — REVIEWER 인가 게이트 뒤에 있어 인증 우회 경로가 아니다")
- **조치 제안**: purpose에 "FE: `VITE_DEV_UPLOAD_ENABLED` 빌드플래그 ② BE: `authoring.dev.upload.enabled` 런타임 토글(기본 OFF) + REVIEWER 인가"를 명시.
- **확신도**: high

### [CONFLICT-G15] `change_summary`의 "화면 내용 자체는 변경 없음" 주장이 현재 코드와 상충
- **위치**: item.change_summary(2026-07-30), item.implementation.progress(100)
- **정의서 서술**: "CASCADE-ONLY stale 해소(API-043 참조 필드 변경) — 내용 재확인 결과 변경 없음, 최소 갱신으로 stale만 해소."
- **실제**: 위 G07~G13 7건이 보여주듯 화면 구성(단계 토글 삭제, 이벤트유형 필드 전면 교체, TUS 패널 신설, 결과 카드 단계 칩 제거, terminal 조건 변경, 링크 대상 변경)이 대규모로 달라졌다. 2026-07-30 재확인 시점에는 맞았을 수 있으나, 이후(2026-08-06) 리팩토링을 반영하지 못한 채 `stale: false`, `implementation.progress: 100` 으로 표시돼 있어 — 이 정의서를 신뢰하는 제3자는 "재확인까지 마친 최신 상태"로 오인하게 된다.
- **근거**: item.change_summary 원문 · 위 G07~G13 각 근거 파일 일체
- **조치 제안**: 정의서 재검증 트리거를 API-043 필드 변경 같은 참조 필드 diff뿐 아니라, 참조 컴포넌트(`DevAutolabelTestPage`/`TusUploadPanel`)의 실제 소스 변경도 함께 감지하도록 stale 판정 절차를 보강. 단기적으로는 이 정의서를 즉시 재작성 대상으로 표시.
- **확신도**: high
