# CO-20260831-빌드채널-internal을-control로

**빌드 채널 값 `internal` 을 `control` 로 개명한다**

| 항목 | 값 |
|---|---|
| 작성일 | 2026-08-31 |
| 대상 | 프론트엔드 (`frontend/`) — `klid-web-implementer` |
| 설계 진실원 | `INT-013` v12 · `ADR-012` v10 |
| 설계반영 | 🎨 **선반영 불요** — 아래 §6 참조 |
| 공유기반 영향 | 없음 (`backend/**`·`db/migration`·`deploy/**` 무관 — 실측 0건) |

## §1 배경 — 왜 바꾸나

`internal` 이라는 낱말이 **이 저장소에서 이미 다른 뜻으로 쓰이고 있다.**

인증 채널 축에 토큰 클레임 `channel = INTERNAL` 이 있고, 관리자 부트스트랩 게이트가 그 값으로
포털 사용자를 400 으로 막는다. 즉 **같은 낱말이 「인증 채널」과 「빌드 채널」 두 축을 가리킨다.**

두 축은 대체로 같은 값을 갖지만 **개념이 다르다** — 인증 채널은 *"이 사용자가 어디서 들어왔나"*,
빌드 채널은 *"이 산출물이 어느 향으로 만들어졌나"* 다. 한 낱말이 둘을 가리키면 다음 사람이
**한쪽 값을 다른 쪽 판정에 쓰는** 사고를 낸다.

관제 채널을 가리키는 말은 이 저장소 전반에서 **「관제」** 이고 배포 향 설정도 `control` 을 쓴다
(설치 시점 배포 향 값). 빌드 채널만 `internal` 로 갈려 있었다.

## §2 변경 요지

`VITE_BUILD_CHANNEL` 의 **값**을 `internal` → `control` 로 바꾼다. 기본값도 `control` 이 된다.

⚠ **키 이름은 그대로 둘로 유지한다** — `VITE_BUILD_CHANNEL`(빌드타임에 굳는 값) ·
`KLID_DEPLOY_FLAVOR`(설치 시점에 정하는 값). **값만** 같게 맞춘다.
합치면 「빌드에 굳은 값」과 「현장에서 고치는 값」이 구분되지 않는다.

## §3 도메인별 변경 상세 — 프론트엔드 (`frontend/`)

### 대상 (실측)

| 파일 | 무엇 |
|---|---|
| `src/lib/buildChannel.ts` | `BUILD_CHANNELS` 배열 원소 · `DEFAULT_BUILD_CHANNEL` · 주석의 채널 호칭 |
| `src/vite-env.d.ts` | `VITE_BUILD_CHANNEL` 주석 3줄의 값 표기 |
| `package.json` | `build:internal` 스크립트 — **스크립트 이름과 값 둘 다** |
| 테스트 5파일 | `vi.stubEnv('VITE_BUILD_CHANNEL', 'internal')` — `tokenHandoff` · `useAuthStorePortalChannel` · `PortalLayout` · `remoteMount` · `buildChannel` |
| `src/lib/__tests__/buildChannel.test.ts` | `expect(DEFAULT_BUILD_CHANNEL).toBe('internal')` |

`buildChannel` 을 소비하는 나머지(`PortalLayout`·`guards`·`remoteMount`·`tokenHandoff`·`useAuthStore`)는
**`isPortalEmbedChannel()` 함수만 부르므로 변경 대상이 아니다.**

### ★★ 절대 건드리면 안 되는 것 — 같은 낱말, 다른 축

**`'internal'` 은 라벨 조회 캐시 키로도 쓰인다.** 빌드 채널과 **아무 관계가 없다.**

```
[...LABEL_KEYS.byFrame(srcSn, 0), portalMode ? 'portal' : 'internal']
```

출현 지점(실측 7곳): `features/label/hooks/useLabels.ts` · `features/label/hooks/useUpdateLabels.ts`(주석 포함 2곳) ·
`features/label/hooks/__tests__/useUpdateLabels.test.tsx` · `features/label/__tests__/SaveCommitButton.test.tsx` ·
`features/label/__tests__/LabelingPageDeidentReport.test.tsx` · `features/label/__tests__/labelBusySingleSource.test.tsx` ·
`features/label/__tests__/useUpdateLabels.invalidate.test.tsx`

⚠ **전역 치환하면 라벨 캐시가 조용히 깨진다** — 무효화 키가 어긋나 **저장 후 화면이 갱신되지 않는**
형태로 나타나고, 타입 오류도 테스트 실패도 나지 않을 수 있다(문자열이라 무엇을 넣어도 통과).
이 저장소는 「문자열 일괄 치환」으로 반복해 사고를 냈다(`ADR-042` 의 「듀얼 데이터소스」가
포털 축과 무관한데 함께 치환될 뻔한 것이 최근 사례다).

⇒ **`sed -i 's/internal/control/g'` 류를 쓰지 말 것.** 파일별로 판정해 고친다.

### 불변

- **동작이 바뀌면 안 된다.** 값의 철자만 바뀌고 어느 채널이 무엇을 하는지는 그대로다
- `isPortalEmbedChannel()` 의 판정 대상은 `'portal'` 이라 **이 개명의 영향을 받지 않는다** —
  그래도 기본값이 바뀌므로 회귀 가드로 확인할 것
- **미설정·오타·미지의 값이 기본값으로 떨어지는 fail-closed 동작 유지**

### ★ 반입 매체 영향 — 없다 (실측)

`deploy/` · `.github/` 전체에 `VITE_BUILD_CHANNEL`·`BUILD_CHANNEL` **0건**이다.
프론트 빌드 단계가 넘기는 것은 `VITE_API_BASE_URL`·`VITE_TOKEN_INGRESS`·`VITE_DEV_LOGIN_ENABLED`·
`VITE_DEV_UPLOAD_ENABLED`·`VITE_CONTROL_LOGIN_URL`·`VITE_PORTAL_LOGIN_URL` 여섯뿐이다.

⇒ 매체는 그 값을 안 넘기므로 **기본값을 탄다.** 기본값이 `control` 이 되면 매체가 만드는 산출물은
관제향이고 **그게 맞다.**

⚠ 다만 그 결과 **암묵 기본값에 의존하는 상태**가 된다. 포털향 매체를 만들 때 그 자리에
`VITE_BUILD_CHANNEL=portal` 을 명시하는 줄이 필요하며 **그건 반입 담당(메인 세션)이 넣기로 합의**돼 있다.
**이 CO 가 끝나면 값 이름을 통보해야 한다.**

### ★ 하지 말 것

- **`vite.config.ts` MF 배선 금지** (슬라이스 2)
- **인계 창구·`x-access-token` 금지** (슬라이스 3b)
- **`backend/**`·`deploy/**` 금지** — 매체 쪽은 메인 세션 lane 이다
- **라벨 캐시 키 `'internal'` 금지** (위 ★★)

## §4 영향·리스크

- **최대 리스크는 라벨 캐시 키 오치환**이다 — 조용히 깨지고 테스트도 안 잡는다
- 기본값이 바뀌므로 `VITE_BUILD_CHANNEL` 을 안 넘기는 모든 빌드의 채널 표기가 바뀐다.
  **동작은 그대로**이나(관제향이 기본인 것은 종전과 같다) 값이 달라진다
- 메인 세션과 충돌 지점 없음 — `frontend/**` 단독

## §5 검증

- 프론트 전건 통과 + **라벨 캐시 키가 `'internal'` 그대로임을 회귀 가드로 고정**할 것
  (개명이 그 축을 건드리지 않았다는 증거가 남아야 한다)
- 빌드/테스트는 QA 에 위임해 실측 판정

## §6 관련 설계 ITEM

**설계 선반영 불요 — ITEM 을 고치지 않는다.**

`INT-013` v12 는 채널을 **「내부 채널 / 포털 채널」이라는 개념어**로 서술하고 **환경변수 값 문자열을
본문에 담지 않는다.** `ADR-012` v10 도 같다. 즉 이 개명은 **설계가 규정한 개념을 바꾸지 않고
구현의 값 표기만 바꾸는 것**이라, 고칠 ITEM 이 없다.

⚠ 만약 구현 중 ITEM 이 값 문자열을 담고 있는 것이 발견되면 **코드를 조용히 틀지 말고 보고하라** —
그때는 Phase 3.6 으로 되돌아가 ITEM 을 먼저 고친다.

## §7 구현 로그

- 2026-08-31 CO 작성. 착수 조건(반입 매체 도커 검증 완료) 충족 확인 후 시작.
- 2026-08-31 Phase 4 구현 완료(`klid-web-implementer`). 수정 12파일 + 신규 회귀 가드 1파일
  (`features/label/__tests__/labelCacheKeyChannelSuffix.test.tsx`).
  `sed` 전역 치환을 쓰지 않고 **파일별 판정 + 출현 횟수 단언**으로 편집.
  ★ **PM 이 준 §3 대상표에 누락이 있었다** — 실측 정정: `stubEnv` 대상은 「5파일」이 아니라
  **7파일 10곳**이고(`portalMountBasename` 1 · `portalEmbedGuardNotice` 3 · `tokenHandoff` 2 ·
  `useAuthStore` 1 · `PortalLayout` 1 · `remoteMount` 1 · `buildChannel` 1), `frontend/.env.example`
  2줄도 표에 없었다. 구현자가 잡아 전부 반영했다.
  ⚠ **구 값 스텁 1건은 의도적으로 남긴다** — `buildChannel.test.ts:53` 의 폐기값 가드
  (「구 값이 더 이상 유효값이 아니다」를 값 축으로 단언).
- 2026-08-31 Phase 5.5 독립 QA(`klid-qa-verifier`) **`pass_with_notes`**.
  실측 **522파일 / 4,375건 GREEN**(79.30s) · lint 0 errors(변경·신규 파일 지적 0건) ·
  **빌드 3종 성공**(`build` · **`build:control`** · `build:portal`) + `build:internal` 부재 확인.
  **변이 5종 직접 재현**(대상 14파일/99건, 베이스라인 99 passed) 후 `shasum -c` 로 바이트 원복 확인.
  ★★ **MUT A1 이 이 CO 의 핵심 결과다** — 라벨 **조회 키만** 바꿨을 때 **기존 테스트 99건이 전부
    초록이고 신규 가드 파일만 2건 FAIL** 했다. 즉 그 가드가 없었으면 이 결함이 그대로 통과한다.
    가드가 동어반복이 아님도 확인됐다(로컬 배열이 아니라 `renderHook` 으로 프로덕션을 실행해
    `QueryCache` 에 실제로 들어간 키를 읽고, 무효화는 spy 로 잡아 조회 키와 대조한다).
  ★ **「커버리지가 조용히 뒤바뀐다」 추론이 코드에서 성립함을 실증** — 스텁 하나를 구 값으로
    되돌려도 **10건 전부 초록**이다(`readChannel` 이 미지 값을 기본값으로 떨어뜨려 결과가 같다).
    ⇒ 누락 여부는 실행으로 판정할 수 없고 **구조적 전수 계수로만** 가려진다. 독립 계수 결과
    **비의도 누락 0건**.
  ★ **동작 무변경 증명** — 구/신 판정함수 차분 **14입력 불일치 0**. 제어 흐름은 HEAD 와 바이트 동일하고
    값 상수 2개만 바뀌었다.
  ★ **설계 ITEM 미수정 판단 확인** — 서버 직접 조회로 `INT-013` v12 · `ADR-012` v10 전문에
    `VITE_BUILD_CHANNEL`·`internal`·`control` 값 리터럴 **각 0건**.
  ★ **개명값의 외부 정합 확인** — `deploy/onprem/docs/04-configuration.md:438` 의
    `KLID_DEPLOY_FLAVOR` = `control`(관제 연동) | `portal`(포털 연동) 과 **같은 어휘**다.
  ⚠ **QA 가 잡은 것은 코드 결함이 아니라 구현자 보고 수치 오기 2건이다** —
    ①「7파일 8곳」이라 했으나 실측 **10곳**(코드는 10곳 모두 정확히 개명돼 있었다)
    ②변이 실패 건수 「B 5 · C 3」이라 했으나 실측 **B 13 · C 5**(방향은 일치, 스코프 미기재로 재현 대조 불가).
    이 CO 의 판정이 **「누락이 실제로 0인가」라는 계수에 걸려 있어** 근거 수치가 틀린 것은 기록해 둔다.
- 2026-08-31 Phase 5.9 노하우 3건을 `klid-web-implementer` 에 반영.

## §8 후속

- ★ **반입 담당(메인 세션)에 확정 값 통보** — `control` / `portal`, 기본 `control`.
  포털향 매체에 `VITE_BUILD_CHANNEL=portal` 을 **명시하는 줄**이 필요하다. 넣지 않으면 기본값으로
  빌드돼 **포털에 관제향 산출물이 오류 없이 올라간다** — `INT-013` 인라인 스펙이 경고한 형태로,
  머리 영역이 겹치고 하위 화면이 전부 없는 화면이 된다.
- **스크립트 이름이 바뀌었다**: `npm run build:internal` → **`build:control`**.
  레포 전체 grep 상 프론트 바깥 참조 0건(`deploy/`·`.github/` 포함)이나 **외부 CI·런북에 있으면 함께** 고칠 것.
- **[별건] `INT-013` 의 채널 호칭** — 값 리터럴은 없으나 `INT-013` 은 「내부 채널」로,
  `ADR-012` v10 은 「관제 채널」로 불러 **두 ITEM 의 호칭이 갈려 있다.** 이 CO 의 동기(같은 낱말 과부하)와
  같은 방향의 잔여다. **이 CO 범위 밖**(§6 이 ITEM 미수정을 명시 결정) — 별건 정합 라운드에서 판단할 것.
- **[별건] `ADR-012` 가 `stale=true`** (`stale_reason`: `INT-013` 의 인라인 스펙 변경). 이번 작업
  이전부터의 상태다. 정합 라운드 대상.
