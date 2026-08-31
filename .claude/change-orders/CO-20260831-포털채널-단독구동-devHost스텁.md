| 항목 | 값 |
|---|---|
| CO 식별자 | CO-20260831-포털채널-단독구동-devHost스텁 |
| 제목 | 포털 채널 산출물을 포털 Host 없이 단독 구동·검증할 수 있게 한다 (dev 전용 스텁 Host) + dev 로그인 역할 프리셋을 빌드 채널별로 가른다 |
| 대상 도메인 | 프론트 전용 — SCREEN-004(개발용 로그인) · INT-013(포털 임베딩) |
| 구현 상태 | 📝 작성 |
| LogiCraft 설계반영 | ⏳ 대기 |
| 생성일 | 2026-08-31 |

---

## §1 배경

2026-08-31 cudo_246(dev)에 **포털향 저작도구를 별도 compose 프로젝트(`klid-portal`)로 올렸다.** BE 18085 / FE 13005 이며 FE 는 `klid-frontend:dev` 이미지에 `VITE_BUILD_CHANNEL=portal` 만 주입한 형상이다.

그런데 **13005 에서 아무것도 할 수 없다.** 사용자 보고 원문 — *"13005 로 진입하면 아무것도 할 수 있는게 없네 … 13005에는 정상으로 테스트 할수 있는 방법이 필요해 보여"*. 원인은 두 겹이고 둘 다 실측으로 확인했다.

### 원인 ① — 라우터 기준 경로가 마운트 경로로 물려 있다

`router/index.tsx` 마지막 줄이 `createBrowserRouter(routes, { basename: resolveRouterBasename() })` 이고, `lib/remoteMount.resolveRouterBasename()` 은 포털 채널에서 `PORTAL_MOUNT_BASENAME`(`/workspace/authoring`)을 돌려준다. 그래서 `http://192.168.102.246:13005/` 는 **어떤 라우트에도 매칭되지 않아 빈 화면**이 된다.

지금 유일하게 들어가지는 주소는 `http://192.168.102.246:13005/workspace/authoring/dev/login` 이다(curl 200 실측). 이건 알고 있어야만 들어갈 수 있는 주소이고, 아무 안내도 없다.

**이것은 결함이 아니라 설계대로다** — 포털 Host 가 우리를 그 경로 아래에 마운트하기 때문이다(`INT-013` 「진입점·마운트·서빙 규약」). 문제는 **Host 가 없는 단독 구동을 아무도 상정하지 않았다**는 점이다.

### 원인 ② — 토큰 인계 창구가 없으면 토큰이 요청에 실리지 않는다

`CO-20260831-포털인계창구-어댑터도입`(PR #161 의 `342cfc95`)이 포털 채널의 토큰 획득을 `features/auth/tokenHandoff.ts` 뒤로 추상화했다.

- `portalTokenHandoff.getAccessToken()` 은 `hostGateway?.getAccessToken() ?? null` 이다.
- **창구가 없으면 스토어로 폴백하지 않는다.** 이건 의도된 fail-closed 이고 포털이 비협상 조건으로 제시한 지점이다 — *"폴백하면 막으려던 죽은 토큰이 장애 상황에서만 되살아난다"*(커밋 메시지).
- `stores/useAuthStore.ts` 의 `persistToken`/`readToken` 도 포털 채널에서 즉시 반환해 **브라우저 저장소를 쓰지 않는다.**

그 결과 246 처럼 포털 Host 가 없는 환경에서는 `/dev/login` 으로 토큰을 발급받아 스토어에 넣어도, `lib/api/client.ts` 의 인터셉터가 `getAccessToken()` 에 물으면 `null` 이라 **`Authorization` 헤더가 비어 나가고 전부 401** 이다.

즉 **PR #161 머지로 13005 는 이전보다 더 막혔다.**

### 원인이 아닌 것 (다시 조사하지 말 것)

- **백엔드는 정상이다.** `POST http://127.0.0.1:18085/api/v1/dev/tokens {"role":"PORTAL_USER","channel":"PORTAL"}` → **201** 실측.
- **인증 흐름 배선도 이미 완결돼 있다.** `/dev/login` → `navigate('/ingress')` → `SessionIngressPage` 가 `claims.channel === 'PORTAL'` 이면 `/portal` 로 보낸다(`SessionIngressPage.tsx:127`).
- 포털 채널의 **화면 4종 라우트도 정상 등록**돼 있다(`router/index.tsx` 의 `/portal` 트리 — index · `label/:id` · `uploads` · `uploads/:uldSn/label`).

### 함께 처리하는 두 번째 요구

사용자 원문 — *"13000 에는 그럼 이제 포털 로그인 같은거 없어도 될것 같고"*. 포털이 자기 배포본(13005)을 갖게 됐으므로, 관제향(13000)의 dev 로그인에 남아 있는 `PORTAL_USER` 선택지는 **그 배포에서 쓰이지 않는 경로**다.

두 요구는 **한 축**이다 — 채널이 갈렸으니 각 배포가 자기 채널의 진입 수단만 갖는 것이 맞고, 그렇게 하면 13005 에는 `PORTAL_USER` 만 남아 ①의 해법과도 맞물린다.

## §2 변경 요지

프론트엔드만 바꾼다. 백엔드·MF 배선·헤더 스킴은 손대지 않는다.

1. **dev 전용 스텁 Host 를 붙인다** — 포털 채널이면서 dev 노출 조건일 때만, 이미 있는 등록 창구 `registerHostTokenHandoff(gateway)` 에 가짜 Host 를 등록해 `TokenHandoffGateway` 4종을 구현한다. 새 우회로를 만들지 않고 **실제 포털 Host 가 쓸 계약 그대로**를 쓴다.
2. **기준 경로 밖 진입을 마운트 경로로 보낸다** — 같은 dev 조건에서만, `http://…:13005/` 로 들어와도 화면이 뜨게 한다.
3. **dev 로그인 역할 프리셋을 빌드 채널로 가른다** — control 채널은 ADMIN·REVIEWER·WORKER, portal 채널은 PORTAL_USER.

셋 다 **운영 산출물에는 들어가지 않는다.**

## §3 도메인별 변경 상세

> 이 절이 `klid-web-implementer` 에 `change_detail` 로 그대로 전달된다.

### 프론트 (SCREEN-004 · INT-013) — `klid-web-implementer`

**대상 파일·심볼**

| 파일 | 역할 |
|---|---|
| `frontend/src/features/auth/tokenHandoff.ts` | 등록 창구 `registerHostTokenHandoff` · 계약 `TokenHandoffGateway` · 상수 `HOST_HANDOFF_METHOD_NAMES` — **읽어서 쓴다. 이 파일의 기존 동작을 바꾸지 않는다** |
| `frontend/src/main.tsx` | 문서를 소유하는 **단독 구동 진입점**. 스텁 등록·경로 보정을 다는 자리 |
| `frontend/src/remote/AuthoringRemote.tsx` | **Host 안에서 실행되는 Remote 진입점 — 절대 건드리지 않는다** |
| `frontend/src/features/auth/DevLoginPage.tsx` | `ROLE_PRESETS` 채널 분기 |
| `frontend/src/lib/buildChannel.ts` | `isPortalEmbedChannel()` — 재사용만 |
| `frontend/src/lib/remoteMount.ts` | `PORTAL_MOUNT_BASENAME` — 재사용만 |
| `frontend/src/lib/devLogin.ts` | `isDevLoginEnabled()` — dev 노출 판정 |

**변경 ① — dev 전용 스텁 Host**

- 새 모듈을 하나 둔다(예 `features/auth/devHostStub.ts`). 이 모듈이 `TokenHandoffGateway` 4종(`getAccessToken` · `refresh` · `onUnauthorized` · `notifyActivity`)을 구현하고 `registerHostTokenHandoff()` 로 자신을 등록한다.
- **★등록 지점은 `main.tsx` 다 — `AuthoringApp` 이나 `AuthoringRemote` 가 아니다.** 근거: `main.tsx` 는 「문서를 소유한 단독 진입점」이고 `AuthoringRemote.tsx` 는 「진짜 Host 안에서 실행되는 진입점」이다. 스텁을 `main.tsx` 에만 달면 **진짜 Host 안에서는 구조적으로 활성화될 수 없다** — 조건 검사에 기대지 않고 배치만으로 보장된다. `main.tsx` 가 `vite:preloadError` 핸들러를 「문서를 소유한 진입점만 달 수 있다」는 이유로 이미 그렇게 가르고 있으니 그 관례를 따른다.
- **토큰 보관은 스텁이 자기 것으로 한다.** `useAuthStore` 가 포털 채널에서 저장소를 쓰지 않는 결정은 **뒤집지 말 것**. 구조가 "저작도구가 저장소를 읽는다"가 아니라 **"Host 가 토큰을 갖고 창구로 준다"** 여야 한다. 새로고침 후에도 다시 로그인하지 않게 하려면 스텁이 **자기 키**로 보관한다(앱의 `LOCAL_STORAGE_TOKEN_KEY` 를 재사용하지 말 것 — 재사용하면 "앱이 저장소를 안 쓴다"는 불변식이 흐려진다). 키 이름에 dev 임이 드러나게 한다.
- **`/dev/login` 이 발급한 토큰이 스텁에 들어가야 한다.** 현재 `DevLoginPage` 는 `useAuthStore.setTokenAndClaims(token)` 를 부르고 포털 채널에서는 그것이 저장소에 남지 않는다. 스텁을 씨앗토큰으로 채우는 경로를 만든다(예 스텁이 `seed(token)` 을 노출하고 dev 로그인이 포털 채널일 때 호출). **경로는 구현자가 정하되, `tokenHandoff.ts` 의 공개 계약을 넓히지 않는다.**
- `refresh()` 는 보관 중인 토큰을 그대로 돌려주면 된다(스텁은 재발급 능력이 없다). `onUnauthorized()` 는 보관 토큰을 버리고 dev 로그인으로 보내는 정도가 적절하다. `notifyActivity()` 는 no-op 이되, **네 메서드가 전부 함수로 존재해야 한다** — `isGateway()` 가 4종 전부를 `typeof === 'function'` 으로 검사해 하나라도 빠지면 등록이 거부된다.

**변경 ② — 기준 경로 밖 진입 보정**

- 같은 dev 조건에서, 현재 경로가 `PORTAL_MOUNT_BASENAME` 로 시작하지 않으면 그 아래로 보낸다. 자리는 **라우터가 만들어지기 전**이어야 한다(`main.tsx`).
- 경로 값은 `lib/remoteMount` 의 상수를 **import 해서 쓴다.** 문자열을 복제하지 말 것 — 그 모듈의 존재 이유가 "계약값을 한 곳에만 둔다" 이다.
- ⚠ **`remoteMount.ts` 가 basename 을 런타임 입력에서 받지 말라고 못박고 있다**(오픈 리다이렉트 표면). 그 금지를 어기지 말 것 — 이동 목적지는 **빌드타임 상수에서만** 나와야 하고, 쿼리스트링·`document.referrer`·Host 전달값 같은 런타임 입력이 목적지에 섞이면 안 된다.
- ⚠ **`INT-013` 의 「이탈 분기 네 곳」과 다른 축이다.** 그건 인증·권한 실패 시 **화면 이동 대신 제자리 안내**를 그리라는 규정이고(포털 채널에서 지켜야 한다), 이건 **앱이 시작되기도 전의 문서 수준 경로 보정**이다. 그 규정을 되돌리는 것으로 오해하지 말 것.

**변경 ③ — dev 로그인 역할 프리셋 채널 분기**

- `ROLE_PRESETS` 4종을 빌드 채널로 가른다: **control 채널 → ADMIN · REVIEWER · WORKER** / **portal 채널 → PORTAL_USER**.
- 판정은 `isPortalEmbedChannel()` **재사용**. `import.meta.env` 를 다시 읽지 말 것(`buildChannel.ts` 주석이 두 벌 판정 금지를 명시한다).
- **기본 선택 역할**이 각 채널에서 유효한 값이어야 한다(control 에서 PORTAL_USER 가 기본이면 첫 화면부터 깨진다).
- `VITE_BUILD_CHANNEL` 값이 `internal` → `control` 로 개명됐다(`dfe25e56`). **낡은 `internal` 문자열을 새로 쓰지 말 것.**

**변경 ④ — 라우트를 채널로 가른다 (2026-08-31 사용자 추가 확정)**

> ★ **설계 변경이 필요 없다 — `INT-013` 이 이미 규정하고 있다.** 본문의 *"공통 소스는 한 벌로 두고 **산출만 채널별로 가른다** — 그러면 ①**반대 채널 화면이 산출물에 섞이지 않고** ②채널마다 다른 팔레트를 산출 시점에 정할 수 있으며 ③**채널을 가르는 지점이 진입점·라우팅·셸 세 곳으로 좁혀진다**"* 가 그것이다. 즉 이 항목은 신규 사양이 아니라 **사양은 있는데 구현이 따라오지 않은 갭**을 닫는 것이다.

- **현재 상태(실측)**: `router/index.tsx` 의 `/portal` 트리가 **조건 없이** 등록된다(`path: '/portal'`, 채널 분기 0건). 두 채널 빌드가 서로의 화면을 전부 싣고 있다.
- **control 채널** → `/portal` 트리(포털 홈·포털 라벨링·포털 업로드·포털 업로드 라벨링)를 뺀다.
- **portal 채널** → 내부 화면 트리를 뺀다.
- ⚠ **공용 진입 경로는 양쪽에 남긴다** — `/ingress`(`SessionIngressPage` 가 채널 클레임으로 갈라 보내므로 두 채널 모두 필요), `/dev/login`(dev 조건), 오류 화면. `/role-claim` 은 내부 채널 전용 흐름이라(포털 사용자는 발급 시점에 역할이 있다) control 로 볼 근거가 있으나 **단정하지 말고 근거를 확인한 뒤 판단하라.**

★★ **여기가 이 항목의 진짜 어려움이다 — "못 가게 하는 것"과 "번들에서 빠지는 것"은 다르다.**

`isPortalEmbedChannel()` 은 **매 호출마다 `import.meta.env` 를 다시 읽는 함수**다(테스트에서 `vi.stubEnv` 로 값을 바꿔가며 검증하려고 일부러 그렇게 뒀다 — `buildChannel.ts` 주석). 함수 호출은 번들러가 정적으로 접을 수 없으므로, 그 판정만으로 라우트 배열을 가르면 **이동은 막히지만 반대 채널 화면 코드는 번들에 그대로 남는다.** 그러면 `INT-013` 의 ① (*"반대 채널 화면이 산출물에 섞이지 않는다"*)이 달성되지 않는다.

- 판정을 두 벌로 만드는 것은 이 저장소가 반복해서 경고해 온 실패 형태다(`buildChannel.ts` 주석 — *"사본을 두면 두 번째 진실원이 된다"*). **같은 환경변수 키 하나를 원천으로 두되 산출 시점에 접히는 형태**를 함께 두는 쪽이 맞다.
- **측정으로 증명하라.** 두 채널로 각각 운영 빌드를 돌려, **control 산출물에 포털 화면 식별자가 0건**이고 **portal 산출물에 내부 화면 식별자가 0건**임을 grep 결과로 제시한다. 접히지 않았다면 "가르긴 했는데 안 빠졌다"는 상태이며 그건 미완이다.
- ⚠ 접기가 끝내 안 되면 **조용히 넘어가지 말고** 그 사실과 원인을 보고하라. 라우팅 차단(이동 불가)까지만 달성한 상태로 회수하되, 번들 분리는 슬라이스 2 로 남긴다.

**수용기준(④)**: ①control 빌드에서 `/portal` 로 이동 불가 ②portal 빌드에서 내부 화면으로 이동 불가 ③공용 진입 경로(`/ingress`·오류 화면)는 양쪽 정상 ④두 산출물의 반대 채널 화면 식별자 grep **0건**(또는 미달성 시 사유 보고) ⑤기존 라우팅 테스트 전건 GREEN.

**변경 ⑤ — 온프렘 패키징을 채널 2벌로 가른다 (2026-08-31 사용자 확정 · 독립 QA 발견)**

> ★ **이건 변경④가 만든 파손을 닫는 것이라 같은 CO 에 둔다.** QA 는 별건 CO 를 권했으나, 원인과 수정이 **같은 커밋으로 나가야 안전**하다 — 변경④만 머지되고 이 수정이 뒤따르는 창이 생기면 그 사이 반입된 매체는 포털 화면이 0건이다.

- **파손 내용**: 온프렘 패키징이 `npm run build` 를 **`VITE_BUILD_CHANNEL` 없이 1회만** 돌리고, 레포 전체에 `build:portal`/`build:control` 사용처가 **0건**이다(QA 실측 — `20-build-frontend.sh:71` · `build-from-source.sh:262`). 변경④ 이후 그 산출물에는 `/portal` 라우트가 하나도 없으므로 **포털향 설치본에 포털 화면이 0건**이 된다. 설치 시점 `KLID_DEPLOY_FLAVOR=portal` 은 런타임 설정만 렌더할 뿐 **빌드 시점에 굳은 채널을 바꾸지 못한다.**
- ⚠ **변경④ 이전에는 단일 산출물이 `/portal` 을 갖고 있어 포털향 설치도 동작했다.** 이번 변경이 만든 잠재 파손이다.
- ★ **문서는 이미 이것을 규정하고 있었고 스크립트만 안 따라왔다** — `deploy/onprem/docs/04-configuration.md` §D-4 가 *"화면 산출물(dist)도 향마다 따로 만든다 … 다른 향으로 만든 dist 에 이 선언만 바꾸면 머리 영역이 겹치고 화면이 뜨지 않는다"* 라고 못박고 있다. 라우터 갭과 **같은 형태**다(사양은 있는데 구현이 없다).
- **방향(사용자 확정)**: 패키징이 **두 채널을 모두 빌드해 매체에 함께 싣고**, 설치 스크립트가 `KLID_DEPLOY_FLAVOR` 로 배치할 것을 고른다. 매체를 두 종류로 가르는 안은 **기각** — 매체는 이미 수 GB 이고 프론트 증분은 관제 약 2.2MB / 포털 약 1.2MB 로 무시할 수준인데, 매체를 가르면 반입·검수·자료실 분할 절차가 통째로 두 배가 된다(3GB 파일 제한 때문에 이미 분할돼 있다).
- **★fail-closed 필수**: 향이 가리키는 dist 가 없으면 **조용히 다른 것을 깔지 말고 즉시 실패**한다. 잘못된 향을 까는 것은 오류 없이 조용히 어긋나는 실패이고, 문서가 지목한 증상(*"머리 영역이 겹치고 화면이 뜨지 않는다"*)이 현장에서 원인 추적이 가장 어려운 형태다. 허용값(`control`|`portal`) 판정은 `render-frontend-config.sh` 에 이미 있으므로 **복제하지 말고 어긋나지 않게** 한다.
- **기본값 불변**: `${KLID_DEPLOY_FLAVOR:-control}` 유지(현재 반입 매체가 관제향).
- **수용기준**: ①패키징 실행 시 두 dist 산출 ②관제 dist 에 `PortalHomePage` 청크 0건·`DashboardPage` 1건, 포털 dist 는 그 반대 ③없는 향·없는 dist 로 설치 시 즉시 실패 ④관제향 설치 동선 무변경(이미 나간 런북이 틀리지 않아야 한다) ⑤빌드 시간 2배가 되는 사실을 로그·문서에 명시.

**불변 (건드리면 안 되는 것)**

- `tokenHandoff.ts` 의 `portalTokenHandoff` 가 **창구 없을 때 스토어로 폴백하지 않는** 성질. 스텁은 창구를 *제공*하는 것이지 폴백을 되살리는 것이 아니다.
- `useAuthStore` 가 포털 채널에서 브라우저 저장소를 쓰지 않는 성질(`persistToken`/`readToken` 의 채널 분기).
- `resolveRouterBasename()` 이 내부 채널에서 `undefined` 를 돌려주는 성질 — 여기에 값이 새면 **관제향 전 화면이 404** 다. 회귀 가드 `lib/__tests__/remoteMount.test.ts` · `router/__tests__/portalMountBasename.test.tsx`.
- `AuthoringRemote.tsx` 와 `AuthoringApp` 의 현재 구조.
- 기존 회귀 가드 13건(Bearer 주입·저장소 보관 고정)과 `features/auth/__tests__/tokenHandoff.test.ts` · `lib/api/__tests__/clientTokenHandoff.test.ts` · `stores/__tests__/useAuthStorePortalChannel.test.ts` 는 **한 줄도 고치지 않고 통과해야 한다.**

**주의**

- ★★**운영 산출물에 인증 우회 표면을 남기지 않는 것이 이 CO 의 최우선 수용기준이다.** 스텁은 임의 토큰을 요청에 실어 주는 물건이라, 운영 번들에 남으면 그 자체가 인증 우회 경로다.
  - `router/index.tsx` 의 `if (isDevLoginEnabled()) { ... }` 블록이 이미 「dev 조건 + dead-code 제거」 관례를 쓰고 있으니 그것을 따른다.
  - ⚠ 다만 `isDevLoginEnabled()` 는 `readRuntimeConfig('VITE_DEV_LOGIN_ENABLED')` 도 보므로 **순수 빌드타임 상수가 아니고, 그 자체로는 번들러가 dead-code 로 못 지운다.** 그 사실을 실제로 확인하고, 필요하면 **빌드타임 상수 조건을 함께 걸어** 스텁 모듈이 운영 번들에 굳지 않게 한다(예: 빌드타임 판정으로 import 자체를 감싸고, 런타임 판정은 그 안에서 다시 본다).
  - 온프렘 빌드가 `VITE_DEV_LOGIN_ENABLED=true` 를 **기본 주입**한다는 사실(`devLogin.ts` 주석 — `build-from-source.sh` · `20-build-frontend.sh` 두 곳)을 반드시 감안할 것. 즉 **그 플래그만으로 게이팅하면 온프렘 산출물에 스텁이 들어간다.**
  - 검증은 주장하지 말고 **실측**하라 — 운영 빌드(`npm run build` 계열)를 실제로 돌려 산출물에서 스텁 식별자·dev 토큰 키가 **0건**임을 grep 으로 보여라.
- 포털 채널 dev 빌드에서 스텁이 등록되지 않으면 증상이 **401 로만** 드러나 원인 추적이 어렵다. 등록 성공/실패를 콘솔에 남기되 **토큰 값 자체는 절대 싣지 말 것**(`tokenHandoff.ts` 의 `registerHostTokenHandoff` 실패 로그가 이미 그 관례를 따른다 — 키 이름만 남긴다).
- ⚠ **246 은 Vite dev 서버(`target: dev`)로 뜨고 소스 바인드 마운트가 없다** — 소스를 고쳐도 이미지 재빌드 전까지 화면이 안 바뀐다. 로컬 확인 시 HMR 을 기대하지 말 것.

**수용기준**

1. `VITE_BUILD_CHANNEL=portal` + dev 조건에서 `http://<host>:13005/` 로 진입하면 마운트 경로 아래 화면이 뜬다(빈 화면이 아니다).
2. 같은 조건에서 `/dev/login` 의 역할 선택지가 **PORTAL_USER 하나**다.
3. 그 화면에서 토큰을 발급하면 `/portal` 홈이 뜨고, 이후 API 요청에 `Authorization` 헤더가 실려 **401 이 아니다**.
4. 새로고침해도 다시 로그인하지 않는다(스텁이 자기 보관분을 되읽는다).
5. 채널 미지정(=control) 빌드에서 `/dev/login` 의 역할 선택지가 **ADMIN · REVIEWER · WORKER 셋**이고 PORTAL_USER 가 없다.
6. 채널 미지정 빌드에서 라우터 기준 경로가 여전히 `undefined` 이고 경로 보정이 동작하지 않는다(관제향 무영향).
7. **운영 빌드 산출물에 스텁이 없다** — grep 0건으로 실측 제시.
8. 기존 프론트 테스트 전건 GREEN, 기존 가드 파일 무수정.

### 공유기반 선처리

**해당 없음.** `common/` · `batch/` · `db/migration/` · 백엔드 진입점 어느 것도 건드리지 않는다. `frontend/src/main.tsx` 는 프론트 진입점이라 `klid-web-implementer` 담당이다.

## §4 영향·리스크

- ⚠ **변경④(2라운드 추가)의 귀결 — §4 의 「가시 변화는 dev 로그인 선택지뿐」은 이제 낡았다.** 관제 산출물에서 PORTAL 채널 클레임 토큰이 `/ingress` 를 타면 `navigate('/portal')` 하는데 그 라우트가 없어 **404** 로 떨어진다(이전에는 포털 홈이 떴다). 변경④의 의도된 귀결이지 결함이 아니다.
- **하위호환**: 관제향(control 채널) 산출물은 **동작 불변**이어야 한다. 라우터 basename · 토큰 인계(`internalTokenHandoff`) · 저장소 사용 전부 그대로다. 유일한 사용자 가시 변화는 dev 로그인에서 PORTAL_USER 선택지가 사라지는 것뿐이다.
- **백엔드·관제 계약면**: 영향 0. API 호출·응답·통지 어느 것도 바뀌지 않는다.
- **되돌리기**: 프론트 커밋 revert + 246 재빌드로 원복. DB·설정 변경이 없어 되돌림이 깨끗하다.
- **리스크 (가장 큰 것)**: **스텁이 운영 산출물에 새는 것.** 그러면 임의 역할 토큰을 요청에 실을 수 있는 경로가 운영에 열린다. 온프렘 빌드가 `VITE_DEV_LOGIN_ENABLED=true` 를 기본 주입한다는 점 때문에 이 위험이 이론적이지 않다 — 수용기준 7 을 실측으로 닫아야 한다.
- **부차 리스크**: 경로 보정이 조건을 넓게 잡으면 **포털 Host 안에서도 발동해** Host 문서를 우리 경로로 끌고 갈 수 있다. 그래서 등록·보정 지점을 `main.tsx` 로 한정하는 것이 설계의 핵심이다.

## §5 검증

- **자동**: 프론트 전건(`npm test` 계열) GREEN. 신규 단언 — 스텁 게이트웨이가 `HOST_HANDOFF_METHOD_NAMES` 4종을 만족해 `registerHostTokenHandoff` 가 `true` 를 돌려주는지 · 채널별 프리셋 집합 · 경로 보정이 control 채널에서 동작하지 않는지 · 운영 빌드 산출물 grep 0건.
- **수동(246 실배포)**: 이 CO 의 종착점은 246 이다. `docker compose -p klid-portal -f docker-compose.portal.yml build/up` 후 위 수용기준 1~4 를 브라우저로 밟는다. 동시에 13000 에서 수용기준 5~6 을 확인한다.
- **테스트케이스 카탈로그**: `docs/test-cases/` 는 **케이스 표가 동결**돼 있다(2026-08-27 확정). 케이스 행을 추가하지 않는다. 이 CO 는 정책 반전이 아니라 dev 편의 추가이므로 회차 서술도 두지 않는다.

## §6 관련 설계 ITEM  ★구현 전에 먼저 확정한다

| ITEM | 타입 | 무엇을 어떻게 | 근거 |
|---|---|---|---|
| `INT-013` | integration_point | 「채널 산출물은 향마다 따로 만든다」 절 옆에 **Host 없는 단독 구동**을 사양으로 추가한다 — ①Host 가 없으면 인계 창구가 없어 토큰을 얻지 못하고(폴백하지 않는 것이 규정이므로), ②마운트 경로가 기준 경로라 그 밖으로 들어오면 화면이 없다. 그래서 **개발 환경에 한해** 가짜 Host 가 같은 계약(`getAccessToken`·`refresh`·`onUnauthorized`·`notifyActivity`)으로 창구를 제공하고 기준 경로 밖 진입을 마운트 경로로 보낸다. **운영 산출물에는 포함되지 않는다.** 계약을 새로 만들지 않고 기존 창구를 그대로 쓰는 것이 요점 — 그래야 실제 Host 계약 위반이 개발 단계에서 드러난다 | 현재 사양은 Host 가 항상 있다고 전제한다. 그 전제가 없는 형상(별도 배포된 포털향을 단독 확인)이 실재하는데 사양에 자리가 없다 |
| `SCREEN-004` | screen_spec | 「토큰 발급 폼」의 역할 선택 선택지가 **빌드 채널에 따라 갈린다**로 바꾼다 — 관제 채널 산출물은 ADMIN·REVIEWER·WORKER, 포털 채널 산출물은 PORTAL_USER. 채널마다 배포 대상이 다르므로 그 배포에서 쓸 수 없는 역할을 내보이지 않는다 | 현재 본문은 4종을 한 목록으로 열거한다. 채널이 갈린 뒤로는 각 배포에서 절반이 쓰이지 않는 선택지가 된다 |

**cascade 예상 하위**: `AC-*`(SCREEN-004 는 `covered_by_acceptances` 가 비어 있어 없을 가능성이 높다) · `SHELL-002`(포털 셸 — 머리 영역 축이라 이번 변경과 무관할 것으로 보이나 대조 필요) · `EXTSYS-006`(INT-013 의 외부 시스템) · `DOMAIN-013`. **leaf 를 「변경 없음」으로 단정하지 말고 실제 대조 후 판정할 것.**

**확정 (2026-08-31 Phase 3.6 회수):**

| ITEM | 버전 | 무엇을 했나 |
|---|---|---|
| `INT-013` | v12 → **v13** | 인라인 스펙에 「Host 없이 단독으로 띄우는 경우 — 개발 환경 한정」 절 신설. 막히는 두 축(기준 경로 밖 진입 · 인계 창구 부재)을 표로 규정하고, 임시 창구가 실제 Host 와 **같은 네 이름**을 쓸 것 · 이동 목적지가 산출 시점 상수에서만 올 것 · 임시 창구가 자기 토큰을 보관할 것 · **단독 구동 진입점에만 둘 것** · **운영 산출물 미포함**(폐쇄망 반입 산출이 개발용 로그인 노출 값을 기본으로 켜서 만든다는 함정 포함)을 명시 |
| `SCREEN-004` | v14 → **v15** | 역할 선택지를 빌드 채널로 가름(관제=ADMIN·REVIEWER·WORKER / 포털=PORTAL_USER). 함께 **토큰 보관 자리도 채널에 따라 갈림**을 명시 — 구 서술이 저장소 보관을 무조건으로 적어 포털 채널에서 사실과 달랐다 |
| `API-153` | v4 → **v5** | **cascade 에서 발견한 기존 드리프트 정정.** `role` enum 이 `REVIEWER·WORKER·PORTAL_USER` 뿐이라 **ADMIN 이 빠져** 있었다(2026-08-28 관리자 역할 신설 때 따라오지 못한 것). 코드가 진실원임을 실측 확인 후 정정 — `DevTokenRequest.allowableValues` 에 ADMIN 실재 · `DevTokenService` 가 `case ADMIN, REVIEWER, WORKER -> INTERNAL` 로 채널을 가르고 `DEFAULT_USER_NO_ADMIN="9001"`. 기본 사용자번호·채널 조합도 함께 명시 |

**대조 후 변경 없음 (5건)** — 「변경 없음」으로 단정하지 않고 본문을 실제로 열어 판정했다:

| ITEM | 판정 근거 |
|---|---|
| `DOMAIN-013` | 본문이 *"상세 규약(진입점 이름·마운트 경로·번들 서빙·인계 창구)은 INT-013 이 소유하므로 복제하지 않고 가리키기만 했다"* 로 **의도적으로 위임**하고 있다. 이번 신설분도 그 상세에 속한다 |
| `ADR-012` | 결정 기록이며 이번 변경은 **결정을 바꾸지 않는다** — 개발 환경용 대역(代役)을 두는 것이지 인계 수단 결정을 뒤집는 것이 아니다. 이 저장소는 ADR 본문을 사후 편집하지 않는 관례를 따른다 |
| `EXTSYS-006` | 마찬가지로 인계·규약 축을 `ADR-012`·`INT-013` 에 위임한다고 본문에 명시 |
| `SHELL-002` | 머리 영역 축이라 이번 변경과 접점이 없다(관련 낱말 검색 0건) |
| `MOD-040` | 코드 모듈 **등록 정보**(이름·경로·소속)뿐이고 서술이 여전히 정확하다 |

⚠ `DOMAIN-013`·`ADR-012` 는 `INT-013` 변경으로 **서버 stale 표식이 섰다**(각각 `depends_on`·`references`). 대조 결과 내용 변경이 불필요하다고 판정했으므로 표식은 남겨 둔다 — 지우려고 본문을 건드리지 않는다.

**미처리 — 사용자 결정 필요 (1건)**

| ITEM | 사유 |
|---|---|
| `SD-020` (screen_design) | SCREEN-004 의 **고충실 시안 렌더 파일**(`main.html`)이라, 역할 선택지가 3개+안내로 바뀌면 시안이 낡는다. 렌더 재생성·재게시는 별도 축(`mc-logi-screen-design`)이고 이 CO 범위 밖이라 **손대지 않았다.** 서버 stale 표식 있음 |

**검증 방법** — 한글 손상 위험 때문에 MCP 도구 호출 대신 **HTTP 직접 호출**(`lc.py`)로 썼다. 사람이 기준선을 옮겨 적는 단계가 없어 전사 손상이 원천 차단된다. 그 위에 ①기대본 바이트 대조 ②기준선 대비 `replace`/`delete` opcode 0건(순수 가산) ③변경된 최상위 키 목록 ④알려진 오타 grep 0건 ⑤희귀 음절 79개 문맥 확인을 모두 통과했다.

## §7 구현 로그

| 일시 | 도메인 | 에이전트 | 결과 | QA | 커밋 |
|---|---|---|---|---|---|
| 2026-08-31 | 프론트 (1라운드) | `klid-web-implementer` | ✅ 스텁 Host + 경로 보정 + dev 로그인 채널 분기. 신규 34건 · 변이 6/6 사망 | — | 미커밋 |
| 2026-08-31 | 프론트 (2라운드) | `klid-web-implementer` | ✅ 라우트 채널 분기 + 번들 분리. 신규 22건 · 변이 6/6 사망 · 포털 번들 125→19 청크(−44%) | ✅ `pass_with_notes` (`klid-qa-verifier`) | 미커밋 |
| 2026-08-31 | 프론트 (3라운드) | `klid-web-implementer` | ✅ **QA issue①(routeAccess IA 유출) 해소.** `vite.config.ts` 의 `treeshake.moduleSideEffects` 로 사슬 3모듈 선언 — 소스(`routeAccess.ts`·`Lnb.tsx`·`AppLayout.tsx`) **diff 0줄**. portal `menu:{group:` **19→0** · control **19 유지**. 신규 10건 · 변이 4/4 사망 | (2라운드 QA 승계) | 미커밋 |

### 독립 QA 회수 (2026-08-31, `klid-qa-verifier`)

**판정 `pass_with_notes`.** 실측 재실행 결과가 구현 주장과 일치했다 — 테스트 527파일/4,432건 GREEN · lint 0 errors · 4형상 빌드 성공 · AC7 8식별자 × 4형상 **전부 0건** · 라우트 양방향 누출 0 · 불변 10파일 `git diff` 빈 목록. 청크 수만 미세 불일치(QA 실측 control 124 / portal 18 vs 구현 주장 125 / 19 — 집계 방식 차이, 판정 무영향).

**★최우선 수용기준(운영 산출물 인증 우회 표면 0) 충족이며 근거가 강하다.** 게이트가 `import.meta.env.DEV` 인데 Vite 는 `vite build` 에서 `--mode development` 를 줘도 그 값을 `false` 로 굳힌다(QA 실측). 즉 스텁은 **모든 `vite build` 에서 구조적으로 제외**되고 `NODE_ENV=development` 를 명시해야만 들어가는데, 온프렘 스크립트 두 곳 어디에도 그 지정이 없다. 음성 대조군(`NODE_ENV=development`)에서 같은 grep 이 5/1/4/2 건 + 전용 청크를 잡아내 **검사기가 눈멀지 않았음**을 증명했다.

**1라운드 노하우 철회 판정 — 2라운드가 옳다.** 번들에 한글은 **원문 UTF-8 로 나오고** `\uXXXX` 는 0건이다(`grep -al '대시보드' dist-control` = 2파일). 따라서 한글 grep 은 유효하며 무효화된 근거는 없다.

**QA 가 잡은 미해결 4건** (severity 순):

| # | 심각도 | 무엇 | 처리 |
|---|---|---|---|
| 1 | medium | **포털 산출물에 내부 IA 가 통째로 남는다** — `lib/routeAccess.ts` 의 메뉴 19건 한글 라벨(`연동 서버 주소`·`위험 액션` 등)·그룹 8종·전 내부 경로·역할 허용목록. 원인은 그 모듈 최상단 `Object.freeze` 가 부수효과로 판정되는 것. ⚠ **구현 에이전트 자기보고가 과소했다** — *"경로 문자열 8건"·"메뉴 라벨은 함께 남지 않는다"* 는 사실과 다르다. **화면 코드·내부 셸은 실제 0건**이라 코드 축은 달성 | 후속 |
| 2 | medium | **★온프렘 포털향 설치본에 포털 화면이 0건이 된다** — 패키징이 `npm run build` 를 `VITE_BUILD_CHANNEL` 없이 1회만 돌리고 레포 전체에 `build:portal`/`build:control` 사용처가 0건이다. 설치 시점 `KLID_DEPLOY_FLAVOR=portal` 은 런타임 config 만 렌더할 뿐 빌드타임 채널을 못 바꾼다. **변경④ 이전에는 단일 산출물이 `/portal` 을 갖고 있어 동작했다** — 이번 변경이 만든 잠재 파손이다. **구현 에이전트가 보고하지 않았다** | **별건 CO 필수** |
| 3 | low | CO §4 가 낡음 (관제 산출물에서 PORTAL 토큰이 `/ingress` → `/portal` 404) | **이 커밋에서 반영 완료** |
| 4 | low | `router/index.tsx` dev 로그인 블록 **기존 주석이 사실오류** — *"prod 빌드에서 DevLoginPage 청크 자체가 포함되지 않는다"* 는 거짓(`dist-default` 에 1건 실재). 범위 밖이라 미수정 판단은 정당 | 후속 |

**QA 가 명시한 blind spots** — 「0건」만 적지 않고 안 본 층을 함께 밝혔다: 브라우저 런타임 E2E 미수행(AC1~AC4) · 246 실배포 미확인 · 실제 포털 Host 경유 미검증(Host 부재로 구조적 불가) · playwright e2e 미실행 · backend/ai-server/mock-server 회귀 미실행(프론트 전용이라 대상 아님) · **CSS 산출물에 내부 화면 클래스가 남는지 미확인** · 난독화로 이름이 사라지는 내부 심볼 미집계.

**미반영·보류 항목**:

- **PR #161 의 키트 반영 대기가 아직 회수되지 않았다.** 그 PR 은 링크드 워크트리에서 작업돼 `docs/design/**`·`docs/screen-design/**` 에 쓰지 않았고 IMPREC 도 기록하지 않았다. 서버 ITEM 은 이미 갱신됐으며(`ADR-012` v10 · `INT-013` v12 · `INT-009` v12 · `EXTSYS-006` v11 · `ERD-026` v6 · `DFEAT-043` v15 · `UC-024` v21 · `NFR-012` v5 외), **`NFR-012` 는 전역 타입이라 14개 도메인 키트 전부에 실려 있다.** 이 CO 와 별개로 `/mc-logi-implement-kit` SYNC 가 필요하다.
