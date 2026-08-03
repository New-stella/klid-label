# F클러스터(포털) 검증 결과 — 3차 (2026-08-03) 병합

> 4개 파트(F-part1~4)를 병합한 결과. 각 파트 원본은 `_raw/F-part{1..4}.md`. 축약 없이 전문 병합.

---

# F 클러스터 part1 — F-1(채널·역할 게이팅/인가 경계) + F-4(포털 SAM2) 검증 결과

- 회차: 2026-08-03 3차 · 담당 범위: `docs/test-cases/F-portal.md` **F-1(12~29행) + F-4(85~112행)**
- 검증 대상 커밋: `e065da42`(워크트리 HEAD) — 배포 jar `/app/app.jar` mtime `2026-08-03 14:53`, FE 컨테이너 `/app/src` 도 동일 소스 확인 → **2차 F-ISSUE-27(stale jar) 재발 없음**
- 스택: `_raw/stack-bringup.md` 기준 5개 컨테이너 healthy, backend `localhost:18081`(context-path `/api`)
- 실동작 토큰: `POST /api/v1/dev/tokens`(정상 조합) + 컨테이너 `JWT_SECRET`(HS256) 직접 서명(비정상 조합 — PORTAL_USER×INTERNAL, sub 부재/공백)

## 집계

| 구분 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 | 폐기(분모 제외) |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| F-1 | 11 | 11 | 0 | 0 | 0 | 0 | 0 | 0 |
| F-4 | 14 | 13 | 0 | 0 | 0 | 0 | 0 | 1 |
| **합계** | **25** | **24** | **0** | **0** | **0** | **0** | **0** | **1** |

> PASS율 = 24/24 (폐기 1건 `TC-PORTAL-075~077` 제외).

## ★ 최우선 결론 — 포털 SAM2 제거 여부

**완전 제거 확인(코드·배포본·실동작 3중 확인).** 2차 HIGH `F-ISSUE-41`(BE 엔드포인트 생존)·`F-ISSUE-42`(FE 도구바 노출 + 테스트가 위반을 고정) **양쪽 해소**.

1. **소스**: `PortalSam2Controller.java`(91줄)·`PortalSam2Service.java`(294줄)가 커밋 `dcdbb827` 에서 **삭제**(`git log --diff-filter=D` 확인). `grep -rn "PortalSam2" backend/src` → **테스트 파일 5개(회귀 고정·주석)뿐, main 0건**.
2. **배포본**: `docker cp klid-backend:/app/app.jar` → `unzip -l | grep portal` 결과 `PortalSam2*.class` **부재**(남은 portal 클래스는 Label/Upload/Tus/Sweep 계열뿐).
3. **실동작**: 아래 §F-4 표 참조 — 포털 경로 404 / 내부 경로는 동일 요청에 400(핸들러 존재) 로 **판별자가 성립**.
4. **FE**: `PORTAL_HIDDEN_TOOLS = [SAM_SEGMENT, TRACK, KEYPOINT]`(`features/label/types.ts:215-219`) **단일 소스**를 도구바·단축키 훅·단축키 안내 3곳이 공유. `features/label/api.ts` 의 구 포털 분기(`/portal/frames/{id}/sam2-*`)도 제거되어 **주석에만 언급**(`:612`, `:817`). FE 컨테이너 `/app/src` 실물도 동일.

> ⚠ 이에 따라 `UNCERTAINTIES.md` 의 **#1(포털 SAM2 노출 = 정책 위반 → 결함 유지)** 은 **해소**됐다. 원본 갱신 제안: "1차/2차 결함 → 3차(2026-08-03, `dcdbb827`)에서 BE 삭제 + FE 3중 게이팅으로 **✅ 해소**".

---

## F-1. 채널·역할 게이팅 / 인가 경계 (11건)

실행한 요청(전부 `http://localhost:18081/api` 기준):

```
TC-001 GET  /v1/portal/datamart/videos   [PORTAL_USER/PORTAL]      → 200
       GET  /v1/portal/uploads           [PORTAL_USER/PORTAL]      → 200
TC-002 GET  /v1/portal/datamart/videos   [WORKER/INTERNAL]         → 403
TC-003 GET  /v1/portal/uploads           [REVIEWER/INTERNAL]       → 403
TC-004 GET  /v1/portal/datamart/videos   [PORTAL_USER/INTERNAL]    → 403   (allOf 두 조건 중 채널 불충족)
       GET  /v1/videos                   [PORTAL_USER/INTERNAL]    → 403   (역할 불충족 — 어느 쪽으로도 못 감)
TC-005 POST /v1/frames/1/sam2-track      [PORTAL_USER/PORTAL]      → 403
TC-006 PUT  /v1/manage/labels/1          [PORTAL_USER/PORTAL]      → 403
TC-007 GET  /v1/notices                  [PORTAL_USER/PORTAL]      → 403
TC-008 GET  /v1/manage/labels            [PORTAL_USER/PORTAL]      → 200
TC-009 GET  /v1/portal/uploads           [sub 클레임 부재]          → 401 {"message":"포털 토큰 미상"}
       GET  /v1/portal/uploads           [sub=""(공백)]            → 401 {"message":"포털 토큰 미상"}
```

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-PORTAL-001 | PASS | [실동작] 200×2. [정적] `SecurityConfig.java:134-135` `requestMatchers("/v1/portal/**").access(allOf("ROLE_PORTAL_USER","CHANNEL_PORTAL"))` — 근거 라인 **정확** |
| TC-PORTAL-002 | PASS | [실동작] 403. 근거 라인 정확 |
| TC-PORTAL-003 | PASS | [실동작] 403. 근거 라인 정확 |
| TC-PORTAL-004 | PASS | [실동작] 403. 위조 토큰(role=PORTAL_USER, channel=INTERNAL)으로 **allOf 두 조건 중 하나만 충족**시켜 실증. `SecurityConfig.java:135` 정확 |
| TC-PORTAL-005 | PASS | [실동작] 403. [정적] `/v1/frames/**` 전용 매처는 **실제로 없고** 하위 `/v1/**`(`:147-153`, `CHANNEL_INTERNAL` + `ROLE_REVIEWER\|WORKER\|STREAM_SIGNED`)가 차단 — 1차 정정 내용이 현행과 일치 |
| TC-PORTAL-006 | PASS | [실동작] 403. `SecurityConfig.java:126-127`(GET 예외 매처가 REVIEWER 매처보다 앞) 정확 |
| TC-PORTAL-007 | PASS | [실동작] 403. `:132` `hasAnyRole(REVIEWER, WORKER)` 정확 |
| TC-PORTAL-008 | PASS | [실동작] 200. `:126` GET 전용 `.authenticated()` 정확. 반증: 같은 경로 **POST 는 403**(아래 반증 표) |
| TC-PORTAL-009 | PASS | [실동작] 401 + **응답 메시지 `포털 토큰 미상`** 으로 컨트롤러 가드(`PortalUploadController.java:146-151` `requireActor`)가 실제 발화한 것을 확인(필터 단계 익명 401 이 아님). 근거 라인 **정확(146-151 완전 일치)**. 공백 sub 도 동일 차단 |
| TC-PORTAL-010 | PASS | [정적] 프로덕션 배선 확인 — `router/index.tsx:149-151` 내부 라우트 = `<ChannelGuard channel="INTERNAL"><RoleGuard allow={...}>`, `guards.tsx:75-78` 채널 불일치 → `/forbidden`. 테스트 `portalGuard.test.tsx:69-77` **라인 정확** |
| TC-PORTAL-011 | PASS | [정적] `router/index.tsx:157-159` 포털 라우트 = `<ChannelGuard channel="PORTAL"><RoleGuard allow={[PORTAL_USER]}>`. 테스트 `portalGuard.test.tsx:79-101` **라인 정확**(79-91 WORKER/INTERNAL + 93-101 REVIEWER 두 케이스를 정확히 포괄) |

### F-1 반증 시도(확증편향 차단) — 전건 fail-closed

포털 토큰으로 **내부 경로 도달**을 시도한 경로 변형·헤더 변형:

| 시도 | 결과 | 해석 |
|---|:--:|---|
| `GET /v1/videos` | 403 | 정상 차단 |
| `GET /v1/videos/`(trailing slash) | 403 | 매처 우회 실패 |
| `GET /v1/%76ideos`(퍼센트 인코딩) | 403 | **디코딩 후 매칭 — F-ISSUE-64 류 인코딩 우회 없음** |
| `GET /v1/videos/../videos`(경로 순회) | 403 (REVIEWER 는 200) | 정규화 후 매칭 정상 |
| `GET /v1/videos;x=1`(matrix) / `//v1/videos` / `/v1//videos` | 401 | **REVIEWER 토큰도 동일 401** → 권한 차등 없음(디스패치 전 거부), 우회 아님 |
| `GET /v1/tasks/board` · `/v1/reviews` · `/v1/frames/1/labels` · `/v1/frames/1/image` · `/v1/videos/1/stream` · `/v1/statistics/daily-work` | 전부 403 | 내부 표면 전건 차단 |
| `POST /v1/manage/labels` | 403 | GET 예외가 쓰기로 새지 않음 |
| `POST /v1/manage/labels` + `X-HTTP-Method-Override: GET` | 403 | `HiddenHttpMethodFilter` 미활성 — 메서드 오버라이드 우회 없음 |
| `GET /actuator/health` | 200 | `SecurityConfig.java:85-90` permitAll 대상(의도) |
| `GET /v1/dev/tokens` | 405 | permitAll 이나 POST 전용 — 정보 노출 없음 |
| `GET /v1/me` | 200 | `SecurityConfig.java:84` **의도된 예외**(주석 77-83: 본인 토큰 클레임 반향만) — 결함 아님 |

INTERNAL 토큰으로 **포털 경로 도달**을 시도한 변형:

| 시도 | WORKER | REVIEWER |
|---|:--:|:--:|
| `/v1/portal/uploads` · `/v1/portal/uploads/` · `/v1/%70ortal/uploads` · `/v1/./portal/uploads` | 403 | — |
| `/v1/portal/datamart/videos` · `/v1/portal/datamart/labels?rawSn=1` · `/v1/portal/user-labels?rawSn=1` · `/v1/portal/frames/1/labels` · `/v1/portal/frames/1/image` | 403 | 403(labels 확인) |
| `/v1/PORTAL/uploads`(대문자) | 404 | — |

→ **양방향 채널 격리 실동작 확인. F-1 우회 경로 발견 없음.**

---

## F-4. 포털 SAM2 (14행 = 활성 13 + 폐기 1)

### 실동작 판별자 (카탈로그가 지정한 방식 그대로)

카탈로그는 "path≠body srcSn — 구 컨트롤러면 400, 핸들러 부재면 404" 를 판별자로 지정한다. 동일 바디로 포털/내부를 대조:

```
POST /api/v1/portal/frames/999999/sam2-segment  {"srcSn":1,"box":[1,1,10,10]}   [PORTAL]
  → 404 {"message":"요청한 API를 찾을 수 없습니다.","errorCode":"NOT_FOUND"}     ← 핸들러 부재
POST /api/v1/frames/999999/sam2-segment         {"srcSn":1,"box":[1,1,10,10]}   [REVIEWER]
  → 400 {"message":"path 의 srcSn 과 body 의 srcSn 이 다릅니다.","errorCode":"INVALID_INPUT"}  ← 핸들러 존재
POST /api/v1/frames/999999/sam2-track           (srcSn 불일치)                   [REVIEWER]
  → 400 (동일 메시지)                                                            ← 핸들러 존재
POST /api/v1/frames/1/sam2-track / sam2-segment                                  [PORTAL]
  → 403 {"errorCode":"FORBIDDEN"}                                                ← 채널 격리 유지
```

퇴행(경로명 변경으로 부활) 탐색 — **10종 변형 전부 404**:
`/v1/portal/frames/1/sam2-segment` · `.../sam2-track` · `/v1/portal/sam2/segment` · `/v1/portal/labels/sam2` · `/v1/portal/frames/1/segment` · `/v1/portal/frames/1/track` · `/v1/portal/frames/1/autolabel` · `.../sam2-segment/`(trailing) · `.../SAM2-SEGMENT`(대문자) · `.../sam2%2Dsegment`(인코딩)

### 판정표

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-PORTAL-060 (갱신) | PASS | [실동작] 포털 404 vs 내부 400 판별자 성립. [정적] `PortalSam2RemovedTest.java:74-82` `portalSam2SegmentEndpointRemoved` 존재 |
| TC-PORTAL-061 (갱신) | PASS | [실동작] 404. [정적] 동 파일 `:85-92` `portalSam2TrackEndpointRemoved` |
| TC-PORTAL-062 (갱신) | PASS | [실동작] 경로 변형 10종 전부 404. [정적] 동 파일 `:94-105` `noPortalSam2HandlerMappingRegistered`(`startsWith("/v1/portal/") && contains("sam2")` 필터 → isEmpty). 소스 전수: 전체 main 에서 `sam2` 매핑은 `LabelController.java:132,166` **내부 2건뿐** |
| TC-PORTAL-063 (갱신·회귀) | PASS | [실동작] 내부 `sam2-segment`·`sam2-track` 모두 srcSn 불일치 400 반환 = 핸들러 생존. [정적] 동 파일 `:107-113` `internalSam2EndpointsIntact` |
| TC-PORTAL-064 (갱신·회귀) | PASS | [실동작] 포털 토큰 → 내부 SAM2 2경로 모두 **403**. 포털 경로 제거가 내부 경로를 외부에 열지 않음 |
| TC-PORTAL-065 (갱신) | PASS | [정적] `grep -rn "PortalSam2" backend/src` → **test 5파일뿐**(`PortalSam2RemovedTest` 본체 + `PortalLabelControllerTest:172`·`AiInferenceDeidentReportGateTest:74`·`Sam2TrackMockMessageWiringTest:31`·`Sam2SegmentMockMessageWiringTest:30` 주석). `Resilience4jConfig.java` 내 `portalSam2` 빈 **0건**, `backend/src/main/resources/**` 내 `portalSam2`/`portal-sam2` yml 키 **0건**(2차 시점 `application.yml:675,684` bulkhead/ratelimiter 도 제거됨) |
| TC-PORTAL-072 (갱신) | PASS | [정적] `DarkToolbar.tsx:148-153` — `portalMode` 시 `!PORTAL_HIDDEN_TOOLS.includes(item.tool)` 필터 + 액션은 `portalHidden`. `types.ts:215-219` 단일 소스. `AI 탐지`(YOLO)도 `portalHidden:true`(`:135`). BBOX/폴리곤은 잔존(과잉차단 가드). 테스트: `DarkToolbar.test.tsx:52,58,63,68`, `LabelingPagePortalRestrictions.test.tsx:110-124`(**queryBy…toBeNull 로 반전됨** — 2차 F-ISSUE-42 의 "테스트가 위반을 고정" 해소) |
| TC-PORTAL-072a (신규) | PASS | [정적] `useLabelingShortcuts.ts:225` `if (portalMode && binding.tool && PORTAL_HIDDEN_TOOLS.includes(binding.tool)) continue;`. 키맵(`labelingKeymap.ts:42-44`)의 G/K/Shift+T 세 바인딩이 전부 `tool:` 필드를 가져 게이트에 걸림. `LabelingPage.tsx:1056` 에서 `portalMode` 실제 전달 확인. 테스트 `useLabelingShortcuts.test.tsx:297,303,311` |
| TC-PORTAL-072b (신규) | PASS | [정적] `ShortcutCheatSheet.tsx:44` 동일 소스 게이팅. 테스트 `ShortcutCheatSheet.test.tsx:50`(포털 미노출) / `:63`(내부 노출) |
| TC-PORTAL-072c (신규·회귀) | PASS | [정적] `DarkToolbar.test.tsx:76` 내부 SAM2 도구 노출 회귀 + `useLabelingShortcuts.test.tsx:319` 비포털 K/G/Shift+T 정상. 프로덕션 필터가 `if (!portalMode) return true;`(`DarkToolbar.tsx:149`) 로 내부 경로 무변경 |
| TC-PORTAL-073 | PASS | [정적] `LabelingPagePortalRestrictions.test.tsx:86-91` **라인 정확** |
| TC-PORTAL-074 | PASS | [정적] 동 파일 `:93-105` **라인 정확**. 프로덕션 `LabelingPage.tsx:478` `showMeta = !portalMode` |
| ~~TC-PORTAL-075~077~~ | 폐기 | 대상 코드 삭제. 내부 경로 대체 검증(`AiInferenceDeidentReportGateTest`)은 part 범위 밖 |
| TC-PORTAL-078 (신규) | PASS | [정적] `FrameImageEncoder.java` public 메서드 = `resolveFrameImageForInference(LsDataSrc):133` / `encodeDeidentifiedFrameForInference(LsDataSrc):148` / `encodeFrame(LsDataSrc):178` **3개뿐**, 인코딩 본체는 `private String encode(Path):190`. `grep -rn "encodeToBase64" backend/src/main` → **주석 언급 1건(`:143`)뿐, 선언 0건**. 근거 라인 `172-197`·`185-189` **완전 일치** |

### F-4 반증 시도 — FE 우회 경로 탐색

| 반증 가설 | 결과 |
|---|---|
| 단축키로 도구 활성화 우회(버튼만 숨김) | 차단됨 — 훅이 같은 allowlist 로 keydown 자체를 무시(`useLabelingShortcuts.ts:225`) |
| `activeTool` 이 persist 되어 내부 세션 값이 포털 세션에 남는가 | 없음 — `useLabelStore.ts` 에 `persist()` 미사용, 초기값 `ToolTypeEnum.SELECT`(`:459`), reset 도 SELECT(`:794`) |
| `LabelingPage.tsx:821` `setActiveTool(ToolType.TRACK)` 가 포털에서 도달 가능한가 | 도달 불가 — 유일 진입점이 `runAiTool(mode='track')` 이고 그 트리거는 `AiToolModal`(`autolabelModalOpen`) → 여는 곳은 도구바 `AI 탐지` 버튼 1곳뿐이며 `portalHidden:true`. `SHORTCUT_KEYMAP` 에 오토라벨 바인딩 **없음**(도구바 표기 `'Y'` 는 고정 문자열, `DarkToolbar.tsx:126-127` 주석에 명시) |
| FE 빌드 산출물/배포 컨테이너에 잔재 | 없음 — `frontend/dist` 미존재(Vite dev 배포), 컨테이너 `/app/src` 실물 grep 결과 `portal/frames…sam2` 는 `api.ts` **주석 2줄**과 테스트 파일 1개뿐 |
| 내부 채널 토큰으로 포털 UI 진입 | 차단됨 — `ChannelGuard channel="PORTAL"` + `RoleGuard allow={[PORTAL_USER]}` 이중(`router/index.tsx:157-159`) |

---

## 2차 이슈 해소 대조

| 2차 이슈 | 상태 | 근거 |
|---|:--:|---|
| **F-ISSUE-41**(HIGH) 포털 SAM2 엔드포인트 생존 | **✅ 해소** | `dcdbb827` 에서 컨트롤러(91줄)·서비스(294줄) 삭제, 배포 jar 클래스 부재, 실동작 404, 경로 변형 10종 404, yml/Resilience4j 설정 제거 |
| **F-ISSUE-42**(MEDIUM) FE 도구바 SAM2 노출 + 테스트가 위반 고정 | **✅ 해소** | `PORTAL_HIDDEN_TOOLS` 단일 소스 3중 게이팅(도구바·단축키·안내), `api.ts` 포털 분기 제거, 테스트가 `queryByRole(...).toBeNull()` 로 반전 |
| **F-ISSUE-27**(HIGH) 배포 jar 가 소스보다 오래됨 | **✅ 해소**(환경) | `/app/app.jar` mtime `2026-08-03 14:53` = HEAD `e065da42` 빌드, FE 컨테이너 `/app/src` 도 워크트리와 동일. 이번 회차 판정은 소스 기준 동작을 보증함 |
| **F-ISSUE-01**(HIGH) `/v1/portal/datamart/labels` 게이트 전무 | 본 파트 범위 밖(F-2, TC-PORTAL-039/051) | 다만 채널 축 실동작은 확인 — INTERNAL 토큰(WORKER/REVIEWER)의 해당 경로 접근은 **403** 유지. APPROVED/신고 게이트 자체는 F-part2 담당 |

---

## 이슈 기록

### [F-ISSUE-01] TC-PORTAL-060 / 061 / 062 — 카탈로그 ID 충돌: 같은 파일 안에서 `TC-PORTAL-060~062` 가 F-3 과 F-4 에 중복 채번됨
- **심각도**: MEDIUM *(카탈로그 정합성 결함 — 프로덕션 결함 아님)*
- **기대 동작(기대효과)**: 케이스 ID 는 카탈로그 전역에서 유일해야 한다. 회차 간 대조(`ISSUES.md` ↔ 결과표), 수정 커밋의 "어느 케이스를 고쳤나" 추적, 통과율 집계가 전부 ID 를 키로 삼기 때문이다. 중복되면 2차→3차 대조에서 **다른 케이스의 판정이 서로를 덮어쓴다.**
- **현재 동작(이슈 내용)**: `docs/test-cases/F-portal.md` 한 파일 안에서 세 ID 가 두 번씩 정의돼 있다.
  ```
  F-3 (:71) | TC-PORTAL-060 (신규) | **좌표 개수 상한(CWE-770)** ...
  F-3 (:72) | TC-PORTAL-061 (신규) | **저장 경로 비식별 신고 게이트** ...
  F-3 (:73) | TC-PORTAL-062 (신규) | **저장 per-user 속도 제한** ...
  F-4 (:98) | TC-PORTAL-060 (갱신) | 포털 SAM2 분할 엔드포인트 **미제공 확정** ...
  F-4 (:99) | TC-PORTAL-061 (갱신) | 포털 SAM2 추적 엔드포인트 **미제공 확정** ...
  F-4(:100) | TC-PORTAL-062 (갱신) | 포털 경로에 SAM2 핸들러 매핑 0건 ...
  ```
  F-3 절 말미(`:83`)의 자기 규칙과도 모순된다 — *"ID 채번 주의: TC-PORTAL-040~050 이 이미 사용 중이라 3차 QA 신규 케이스는 039 + 051~057 로 채번했다"* 라고 적어 두고 실제로는 058~062 까지 채번해 F-4 의 기존 060~062 를 침범했다.
- **재현/확인 경로**: `grep -n "TC-PORTAL-06[012]" docs/test-cases/F-portal.md` → 각 ID 가 2행씩 출력.
- **영향**: 회차 대조·집계 오류. 3차에서 F-3 담당 에이전트와 F-4(본 파트) 담당 에이전트가 동일 ID 로 서로 다른 판정을 기록하면 병합 시 충돌한다.
- **수정 방향(제안)**: F-3 의 058~062 를 **미사용 대역(예: 063~067 은 F-4 가 065 까지 쓰므로 피하고 080~084)** 으로 재채번하고, F-3 머리말의 채번 주의 문구를 실제 사용 대역으로 갱신한다. ⚠ 재채번은 F-3 소유 구간이라 **본 파트에서는 수정하지 않았고**, F-4 머리말에 충돌 경고 블록만 추가했다(아래 "카탈로그 정정" 참조).

### [F-ISSUE-02] TC-PORTAL-078 — F-4 머리말이 활성 케이스 078 을 "폐기 범위"로 잘못 기재
- **심각도**: LOW *(카탈로그 정합성)*
- **기대 동작(기대효과)**: 머리말의 폐기 선언 범위와 표의 활성 행이 일치해야 한다. 어긋나면 다음 회차 검증자가 활성 케이스를 "폐기라 검증 대상 아님"으로 건너뛴다(VERIFY-PROMPT §2 는 폐기 케이스를 분모에서 제외하도록 지시하므로 **검증 누락이 조용히 발생**한다).
- **현재 동작(이슈 내용)**: 머리말 `:90` 이 *"구 TC-PORTAL-060~071·**075~078**(포털 SAM2 동작 케이스)은 대상 코드가 존재하지 않아 폐기한다"* 라고 적었으나, 바로 아래 표 `:111` 에 `TC-PORTAL-078 (신규)` 가 **활성 행**으로 존재한다(게이트 없는 `encodeToBase64(String)` 오버로드 부재 확인). 실제 폐기 대상은 `075~077` 뿐이며, 같은 머리말의 `:93` 도 "구 TC-PORTAL-075~077" 로 적고 있어 자기모순이다.
- **재현/확인 경로**: `sed -n '90p;93p;111p' docs/test-cases/F-portal.md`
- **영향**: 검증 누락(거짓 커버리지). TC-PORTAL-078 은 "원본 픽셀 유출 경로 삭제 확인"이라 누락 시 CWE-359 회귀를 놓친다.
- **수정 방향(제안)**: 이번 회차에 **정정 완료**(`075~078` → `075~077` + 정정 주석). 추가 조치 불필요.

### [F-ISSUE-03] TC-PORTAL-072 인접 — `handleAutolabel` 에만 `portalMode` 이중 안전 가드가 없다(방어심층 비대칭)
- **심각도**: LOW *(현재 도달 가능한 우회 경로 없음 — 예방적)*
- **기대 동작(기대효과)**: 같은 파일의 형제 핸들러들(`handleRenameTrack`·`handleDeleteTrack`·`handleSplitTrack`)은 버튼 숨김에 더해 **콜백 본체에도 `if (portalMode) return;`** 을 두고, 주석으로 그 이유를 명시한다 — *"목록 패널의 버튼은 차단 중 감춰지지만, 콜백 자체도 막아 둔다(진입점이 늘어나도 새지 않게)"*(`LabelingPage.tsx:855-859`). ADR-013 미제공 기능은 같은 기준으로 이중화돼야 한다.
- **현재 동작(이슈 내용)**: `AI 탐지`(YOLO 오토라벨) 진입점만 **버튼 숨김 단일 층**이다.
  ```tsx
  // frontend/src/pages/label/LabelingPage.tsx:743-751
  const handleAutolabel = () => {
    if (!currentFrame) return;
    if (isEditBlocked || isEditBlockedNow(currentFrame.srcSn)) return;
    if (isLocked) { pushToast({ variant: 'error', message: '비식별 재처리 중인 영상은 AI 도구를 사용할 수 없습니다.' }); return; }
    setAutolabelModalOpen(true);   // ← portalMode 검사 없음
  };
  ```
  그리고 이 모달이 열리면 `runAiTool(mode='track')` → `setActiveTool(ToolType.TRACK)`(`:821`) 로 **PORTAL_HIDDEN_TOOLS 게이트를 우회해 TRACK 도구를 활성화**할 수 있는 구조다. `<AiToolModal>` 자체도 `portalMode` 조건 없이 무조건 렌더된다(`:1315-1327`).
- **재현/확인 경로**: 현재는 **재현 불가**(도달 가능한 트리거가 없음) — 확인한 사실만 기록:
  - 유일한 호출부는 `DarkToolbar` 의 `onAutolabel` 이며 그 항목은 `portalHidden: true`(`DarkToolbar.tsx:135`) 라 포털에서 렌더되지 않는다.
  - `SHORTCUT_KEYMAP`(`labelingKeymap.ts:40-80`)에 오토라벨 바인딩이 **없다** — 도구바가 표기하는 `'Y'` 는 키맵 미등록 고정 문자열이다(`DarkToolbar.tsx:126-127` 주석에 명시). 실제로 포털 세션에서 `Y` 를 눌러도 아무 일이 없다.
  - `activeTool` 은 persist 되지 않아 내부 세션 잔재로도 TRACK 이 남지 않는다.
- **영향**: 기능 범위(ADR-013). 지금은 무해하나, 향후 오토라벨 진입점이 하나라도 늘면(단축키 등록·컨텍스트 메뉴·툴팁 링크) 포털에서 SAM2 TRACK 도구가 되살아난다. 서버가 403 으로 막으므로 데이터 유출은 없고 **미제공 기능 광고 + 오류 UX** 수준.
- **수정 방향(제안)**: `handleAutolabel` 첫 줄에 `if (portalMode) return;` 를 추가하고(형제 3개 핸들러와 동일 주석 패턴), `<AiToolModal>` 렌더를 `{!portalMode && ...}` 로 감싼다. 겸사 `runAiTool` 의 `mode === 'track'` 분기에도 동일 가드를 둬 `setActiveTool(TRACK)` 이 `PORTAL_HIDDEN_TOOLS` 를 우회하지 못하게 한다. ⚠ 구현하지 않음.

---

## 근거 드리프트 집계 (본 파트)

**`file:line` 드리프트 0건.** F-1 11건·F-4 13건의 근거를 전건 Read 로 대조한 결과 라인이 어긋난 항목이 없었다.
*(단 `file:line` 이 아닌 **사실 기술 오기 3건**은 별도로 발견·정정했다 — 아래 "카탈로그 정정" 표 참조: 폐기 범위 `078` 오포함 · 삭제일 `08-02` 오기 · ID 충돌.)*

특히 아래는 라인까지 완전 일치를 확인:

| 케이스 | 근거 | 실측 |
|---|---|---|
| TC-PORTAL-001~004 | `SecurityConfig.java:134-135` / `:135` | 일치 |
| TC-PORTAL-005 | `SecurityConfig.java:147-153` | 일치(`/v1/**` allOf 블록이 정확히 147-152, `.anyRequest()` 가 153) |
| TC-PORTAL-006~008 | `SecurityConfig.java:126-127` / `:132` / `:126` | 일치 |
| TC-PORTAL-009 | `PortalUploadController.java:146-151` | 일치(`requireActor` 메서드 전체) |
| TC-PORTAL-010/011 | `portalGuard.test.tsx:69-77` / `:79-101` | 일치 |
| TC-PORTAL-073/074 | `LabelingPagePortalRestrictions.test.tsx:86-91` / `:93-105` | 일치 |
| TC-PORTAL-078 | `FrameImageEncoder.java:172-197`(특히 185-189) | 일치 |

## 카탈로그 정정 (본 파트가 담당 라인범위 안에서 직접 수행)

| # | 위치 | 내용 |
|:--:|---|---|
| 1 | F-4 머리말(구 `:90`) | 폐기 범위 `TC-PORTAL-060~071·075~078` → **`060~071·075~077`** 로 정정 + 정정 사유 주석 추가(F-ISSUE-02) |
| 2 | F-4 머리말 말미 | **ID 충돌 경고 블록 신설**(F-ISSUE-01) — 본 절의 `060~062` = 포털 SAM2 제거 회귀이고 F-3 의 동명 케이스와 다름을 명시, 인용 시 절 병기 요구, 재채번은 F-3 소유 구간이라 경고만 남김을 기록 |
| 3 | F-4 표 `TC-PORTAL-075~077` 행 | 폐기 사유의 삭제일 `2026-08-02` → **`2026-08-03 (dcdbb827)`** 로 정정. 같은 절 머리말은 이미 2회차에 08-02→08-03 으로 고쳤는데 이 행만 구 날짜가 남아 **한 절 안에서 두 날짜가 공존**했다. `git log --diff-filter=D` 로 삭제 커밋이 `dcdbb827`(08-03)임을 재확인 — 08-02 `67dc48ca` 는 두 파일을 **수정만**(각 7줄/30줄) 했다 |

> 프로덕션 코드·테스트·설정은 **일절 수정하지 않았다.** 빌드/테스트도 실행하지 않았다(실동작은 HTTP 요청·컨테이너 조회만 사용).

## UNCERTAINTIES 갱신 제안

| 항목 | 현재 표기 | 제안 |
|---|---|---|
| **#1 포털 SAM2 구현·노출** | "판정 유지(여전히 정책 위반). 심각도만 완화" | **✅ 해소로 갱신** — 2026-08-03 `dcdbb827` 에서 BE 컨트롤러/서비스 삭제 + FE 3중 게이팅(`PORTAL_HIDDEN_TOOLS` 단일 소스). 3차 실동작 재확인: 포털 경로 404(경로 변형 10종 포함), 내부 경로 400(핸들러 생존)/포털 토큰 403, 배포 jar 클래스 부재. **회귀 가드**: `PortalSam2RemovedTest`(5케이스) + `DarkToolbar.test.tsx` + `useLabelingShortcuts.test.tsx` + `ShortcutCheatSheet.test.tsx` + `LabelingPagePortalRestrictions.test.tsx`. ⚠ "포털 SAM2 를 되살리지 말 것"을 **★확정 정책 절**로 승격 검토 권고(이 저장소의 '철회된 정책 재시도' 패턴 차단 관례) |

---

# F 클러스터 part2 — F-2(데이터마트 Load) · F-6(포털 자산 조회/서빙/삭제) 검증 결과

- 회차: 2026-08-03 **3차**
- 담당 범위: `docs/test-cases/F-portal.md` **F-2**(30~57행, TC-PORTAL-020~038·039·051~053 = 23건) + **F-6**(136~153행, TC-PORTALUP-020~032 = 13건) — **총 36건**
- 검증 방식: **전건 실동작 우선**. 기동 스택(backend `localhost:18081/api`, PostgreSQL `klid_system`/`public` 스키마, HEAD `e065da42` 재빌드 이미지 — `_raw/stack-bringup.md` §0 조치분)에 포털 토큰으로 직접 curl + DB 상태 조회.
- 사용 토큰: `POST /v1/dev/tokens` 로 발급한 `role=PORTAL_USER, channel=PORTAL` 2개 — **sub=3001**(주 사용자), **sub=3002**(IDOR 대조군).
- 코드/설정 **미수정**. DB·스토리지는 재현에 필요한 최소 조작만 하고 **전부 원복**했다(원복 증거는 §5).

---

## 0. ★★ 최우선 결론 — 2차 HIGH #1(F-ISSUE-01) **완전 해소**

2차 `ISSUES.md` [F-ISSUE-01] 은 `GET /v1/portal/datamart/labels` 에 **APPROVED 게이트·비식별 신고 게이트가 모두 부재**해 PORTAL_USER 가 rawSn 하나로 미승인·반려·신고구간 영상의 라벨 좌표를 전건 덤프할 수 있던 결함(CWE-862/639/359)이다.

**실동작 재현 시도 결과 — 전부 차단됨(게이트 전무 → 완전 해소).**

```
$ TOK=<PORTAL_USER sub=3001 JWT>
$ for r in 7 5 6 999999 900 4; do curl -s -w ' <%{http_code}>' -H "Authorization: Bearer $TOK" \
    "localhost:18081/api/v1/portal/datamart/labels?rawSn=$r"; echo; done

rawSn=7      (PENDING,  프레임 10건·라벨 있음) → {"errorCode":"FORBIDDEN"} <403>
rawSn=5      (REJECTED)                        → {"errorCode":"FORBIDDEN"} <403>
rawSn=6      (FAILED)                          → {"errorCode":"FORBIDDEN"} <403>
rawSn=999999 (미존재)                          → {"errorCode":"FORBIDDEN"} <403>   ← 미존재/미승인 동일 응답(오라클 차단, CWE-209)
rawSn=900    (APPROVED + DE_IDENT_YN='F')      → {"errorCode":"PRECONDITION_FAILED"} <412>
rawSn=4      (APPROVED + 'Y')                  → 200 + 라벨 목록                    ← 정상 경로 회귀 무손상
```

수정 배선 확인(2차 "수정 방향"과 정확히 일치 — 판정 국소 재구현 없음):
`PortalLabelService.java:117-126` → `requireActor(actor)` → `isExposedToDatamart(rawSn)` 403 → `accessGuard.requireNotUnderDeidentReport(rawSn)` 412 → **그 다음에야** `lblRepository.findAllByRawSn(rawSn)`(:130). 즉 거부될 요청은 라벨 풀스캔조차 하지 않는다.
회귀 가드도 등재됨: `PortalUserLabelServiceTest`(미승인_PENDING_영상의_datamart_라벨조회는_403이다 / rawSn이_존재하지_않으면_예외없이_403이다 / 비식별신고구간_영상의_datamart_라벨조회는_412이다 / 신고_해제_후_datamart_라벨조회는_다시_200으로_복원된다) — `_raw/test-baseline.md` backend 5,203건 전건 성공(실패 0) 확인.

> **반증 시도(추가)**: 게이트 순서를 우회할 수 있는 입력을 별도로 찔렀다 — ①`rawSn` 누락 → 400(게이트 이전에 400 이지만 데이터 미노출) ②비정수 `rawSn` → 400 ③`page`/`size` 극단값으로 조기 return 을 유도해 게이트를 건너뛰는 경로 → 존재하지 않음(게이트가 clamp/subList 계산보다 **앞**에 있다, :122-126 vs :128-143). **우회 경로 미발견.**

또한 2차 [F-ISSUE-27](배포 jar 가 소스보다 오래되어 포털 정렬 allowlist 미탑재 → `sort=status` 500)도 **해소 확인**: `?sort=status,asc` → **200**(allowlist 등록 키 `status→uldSttsCd`, `SortAllowlist.java:216-223`), `?sort=orgnlFileNm,asc` → **400**(미등록 키 strict 거부). 3차 스택은 검증 기준 소스와 동일 산출물이다.

---

## 1. F-2. 데이터마트 Load (APPROVED 게이트 · IDOR · 비식별 서빙 · 신고 게이트) — 23건

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-PORTAL-020 | PASS | [실동작] `GET /v1/portal/datamart/videos?size=5` 6페이지 전수 조회 → 노출 rawSn 20건이 **전부 `LS_RAW_DATA_STATUS.DATA_STTS_CD='APPROVED'`**. 비APPROVED(5 REJECTED·6 FAILED·7 PENDING·17/30/34/36 ASSIGNED 등) 0건. `PortalLabelService.java:163-164` INNER JOIN 게이트(`findAllWithReviewStatus(null, APPROVED, …)`, `VideoRepository.java:277-283`) |
| TC-PORTAL-021 | PARTIAL | [실동작] 프레임 0건 영상(8·9·15·43·48·55·66·67·146·147)은 `content` 에서 **제외됨(기대 충족)**. 그러나 `totalElements`=30(=게이트 후 원본 수), 실제 노출 20건 → **오보 + 중간 빈 페이지**(page=4 → `content:[]`, page=5 → 2건). → **F-ISSUE-21**(2차 F-ISSUE-02 미해소 이월) |
| TC-PORTAL-022 | PASS | [정적] `PortalLabelService.java:172-174` 가 페이지 rawSn 집합에 대해 `lookupFirstSrcSnByVideo`/`lookupFrameCountByVideo`/`lookupLastUpdatedAtByVideo` 3회만 호출. 각각 `LsDataSrcRepository.findFirstSrcSnGroupedByRawSn`(`:91`, `in :rawSns` 집계) · `countByRawSnsGrouped`(`:187`) · `findAllById` → **행당 반복 조회 없음** |
| TC-PORTAL-023 | PASS | [실동작] `rawSn=4&page=-1&size=99999` → 200(예외·500 없음). `size=0`/`-10` → 1건만 반환(=size 1 로 clamp), `page=-5&size=3` → 3건(=page 0). 상한 100 은 `PortalLabelService.java:128` `Math.min(Math.max(size,1),100)` |
| TC-PORTAL-024 | PASS | [실동작] `rawSn` 누락 → `{"errorCode":"INVALID_INPUT","message":"필수 파라미터가 누락되었습니다: rawSn"}` 400. 서비스단 이중 방어 `:119-121` |
| TC-PORTAL-025 | PASS | [실동작] srcSn=1(rawSn=4)에 본인 user-label 저장(userLblSn=25) 후 `GET /v1/portal/frames/1/labels` → 응답 `labels` 가 **user-label 만**(id 25/8/7/6/5/1 — 전부 `LS_PORTAL_USER_LABEL`), datamart 원본(lblSn=1 `person`) 미포함. `:435-440` |
| TC-PORTAL-026 | PASS | [실동작] 저장 이전(본인 저장분 0) 조회 시 datamart 원본 반환. 대조군 sub=3002 는 **자기 저장분(USER3002-SECRET)만** 반환 → 병합 정책·IDOR 동시 확인. `:441-448` |
| TC-PORTAL-027 | PASS | [실동작] 미승인 rawSn=7 프레임(srcSn=35) `GET labels` → 403 `데이터마트에 노출되지 않은 영상입니다.` `:404-407` |
| TC-PORTAL-028 | PASS | [실동작] `srcSn=99999999` → labels 404 / image 404, 메시지 `프레임을 찾을 수 없습니다.`(내부 정보 미노출) `:398-399` |
| TC-PORTAL-029 | PASS | [실동작] `GET /v1/portal/frames/1/image` → **200**, `Content-Type: image/jpeg`, `X-Content-Type-Options: nosniff`, **`Cache-Control: no-store`**, `Content-Length: 13164`. 구 `private, max-age=300` 흔적 없음 |
| TC-PORTAL-030 | PASS | [실동작] 미승인 srcSn=35 image → 403 |
| TC-PORTAL-031 | PASS | [실동작] deid 경로 NULL 인 APPROVED 프레임 2건(srcSn=445/rawSn=902, srcSn=880211/rawSn=880210) → **404** `비식별 프레임이 존재하지 않습니다.` **원본 폴백 없음**을 반증 확인: 두 행의 `SRC_FILE_PATH_NM`(`/app/storage/raw/frames/raw/26/frame-1.jpg`, `…/101/frame-1.jpg`)은 컨테이너에 **실재하는 파일**인데도 서빙되지 않았다 |
| TC-PORTAL-032 | PASS | [실동작] `DE_IDNTF_SRC_FILE_PATH_NM` 을 조작해 4패턴 시도(원복 완료) → `…/deid/4/../../../../../etc/passwd` **403**, `/etc/passwd` **403**, `/app/storage/raw/frames/raw/4/frame-0.jpg`(원본 서브트리) **403**, `…/deidentified/videos/26/deidentified.mp4`(base 안·비식별 서브트리지만 프레임 아님) 404. `StorageSubtreePolicy.verifyDeidentifiedFile` 배선(`:541-555`) |
| TC-PORTAL-033 | PASS | [실동작·★반증] **심링크 우회 실증 시도** — deid 서브트리 *안*(`…/deid/4/qa-symlink.jpg`)에 원본 프레임(`…/raw/frames/raw/4/frame-0.jpg`)을 가리키는 심링크를 만들고 DB 경로를 그리로 돌림. lexical `startsWith` 라면 통과했을 형상인데 → **403 `허용되지 않은 이미지 경로입니다.`**(실경로 `toRealPath()` 기준 판정). 심링크·DB 원복 완료 |
| TC-PORTAL-034 | PASS | [실동작] deid 서브트리 안의 미존재 파일 지정 → 404 `이미지 파일이 존재하지 않습니다.`(경로 원문·스택 미노출) |
| TC-PORTAL-035 | PASS | [실동작] 신고 구간(rawSn=900, `DE_IDENT_YN='F'` + `APPROVED`) 프레임 srcSn=429 `GET labels` → **412** `비식별 재처리 대기 중인 영상입니다…`. APPROVED 게이트만으로는 안 걸리는 경로임을 데이터로 확인(900 은 APPROVED 유지) |
| TC-PORTAL-036 | PASS | [실동작] 동일 srcSn=429 `GET image` → **412**(파일 판독 전 차단 — `:525-527` 이 deid 경로 조회 `:530` 보다 앞) |
| TC-PORTAL-037 | PASS | [실동작] 위 TC-029 헤더 덤프의 `Cache-Control: no-store` |
| TC-PORTAL-038 | PASS | [실동작·★1 확정정책] 부모 rawSn=900(`'F'`) → 파생 rawSn=159(`ORGNL_RAW_SN=900`, 자기 `DE_IDENT_YN='Y'`)에 APPROVED 상태를 부여하고 파생 프레임 srcSn=582 조회 → `GET labels` **200**, `GET image` **200**, `GET datamart/labels?rawSn=159` **200**. 동시에 부모 프레임 srcSn=429 는 **412** — **조상 전파 없음(자기 rawSn 행만 판정)** 실증. 결함 아님(CLAUDE.md 2026-07-29 확정 + `DeidentReportGate.java:23-37`). 테스트 데이터 원복 완료 |
| **TC-PORTAL-039** | **PASS** | [실동작] **★2차 HIGH#1 해소** — §0 참조. 미승인(PENDING/REJECTED/FAILED) 403 · 미존재 403(동일 응답) · 라벨 풀조회 미수행 |
| **TC-PORTAL-051** | **PASS** | [실동작] **★2차 HIGH#1 해소** — 신고 구간 rawSn=900 → **412**. 해제 복원은 `PortalUserLabelServiceTest(신고_해제_후_datamart_라벨조회는_다시_200으로_복원된다)` + 게이트가 `DE_IDNTF_YN` 단일 컬럼 판정이라 `'F'→'Y'` 로 자동 해제 |
| TC-PORTAL-052 | PASS | [실동작] `rawSn=4&page=2147483647&size=100` → **200 `data:[]`**(500 아님). `size=2` 로도 200 빈 리스트. `long from = (long) clampedPage * clampedSize`(`:135`) |
| TC-PORTAL-053 | PASS | [실동작+정적] size=0→1건, size=-10→1건, page=-5→page0(3건), page=-1&size=99999→200. size 상한 100 은 대상 영상 라벨이 10건뿐이라 응답으로 구분 불가 → `:128` 정적 + `PortalUserLabelServiceTest(size가_100초과면_100으로_clamp된다)` 로 보강 |

### F-2 소계 — PASS 22 / PARTIAL 1 / FAIL 0

---

## 2. F-6. 포털 자산 조회/서빙/삭제 (IDOR · 페이징 · 상태) — 13건

사용 데이터: sub=3001 소유 10건(IMAGE 3 / VIDEO 7), sub=3002 소유 1건(uldSn=62). 삭제 케이스는 **검증용 자산을 새로 업로드(uldSn=82)** 해서 소진했다(기존 데이터 무손상).

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-PORTALUP-020 | PASS | [실동작] 3001 `GET /v1/portal/uploads?size=100` → 10건 `[57,59,60,61,67,68,69,70,71,72]` 전부 본인 소유. 3002 → 1건 `[62]`. **교차 노출 0건** |
| TC-PORTALUP-021 | PASS | [실동작] `?type=IMAGE` → 3건(uldTypeCd 유니크 `["IMAGE"]`), `?type=VIDEO` → 7건(`["VIDEO"]`), `?type=image`(소문자) → 3건(`toUpperCase` 정규화, `:165`) |
| TC-PORTALUP-022 | PASS | [실동작] `?type=FOO` → 400 `지원하지 않는 type 입니다. 허용: IMAGE, VIDEO`(조용한 빈 결과 아님) `:164-169` |
| TC-PORTALUP-023 | PASS | [실동작] `?size=500` → 응답 `size:100`, `pageable.pageSize:100`. `?size=2147483647` → 100. 프레임 목록 `/uploads/72/frames?size=500` → `size:100`. `PortalUploadController.java:177-182`. 참고: `size=0`/`-1` 은 Spring 이 기본 20 으로 되돌림(하드캡 규약과 무관) |
| TC-PORTALUP-024 | PASS | [실동작] 3002 → `GET /uploads/61`(3001 소유) **403**, `GET /uploads/99999999`(부재) **403** — **본문·코드 완전 동일**(`본인 자산이 아니거나 존재하지 않습니다.`) → 자원 열거 오라클 없음 |
| TC-PORTALUP-025 | PASS | [실동작] 3002 → `GET /uploads/61/frames` **403**. 3002 자기 `GET /uploads/62/frames` 는 200(과잉 차단 아님) |
| TC-PORTALUP-026 | PASS | [실동작] 3002 → `GET /uploads/frames/72/image`(3001 소유) **403**, 역방향 3001 → `/uploads/frames/62/image`(3002 소유) **403**. 소유자 스코프 조인(`frmeRepository.findByUldFrmeSnAndOwner`, `:207`) |
| TC-PORTALUP-027 | PARTIAL | [실동작] IMAGE 자산 프레임(uldFrmeSn=61) → 200 `Content-Type: image/png` + `nosniff` **정상**. 그러나 **VIDEO 자산에서 추출된 프레임**(uldFrmeSn=72, 파일 실체는 JPEG `ff d8 ff e0`) → `Content-Type: application/octet-stream` — `resolveStoredMediaType` 이 **프레임이 아니라 업로드 마스터 MIME(`video/mp4`)** 을 보기 때문. 신고 게이트 비대상·`LS_DATA_RAW` 라이프사이클 부재는 기대대로 확인. → **F-ISSUE-22** |
| TC-PORTALUP-028 | PASS | [실동작·★반증] `LS_PORTAL_ULD_FRME.FILE_PATH_NM` 조작(원복 완료): `/etc/passwd` **403**, `…/portal/../frames/raw/4/frame-0.jpg` **403**, `/app/storage/raw/frames/raw/4/frame-0.jpg` **403**, base 안 미존재 파일 404. 추가로 **portal base 안에 base 밖을 가리키는 심링크**(`…/portal/images/qa-esc.png` → 내부 파이프라인 원본 프레임) 생성 후 지정 → **403 `허용되지 않은 이미지 경로입니다.`**(`realWithinBase`, `:245-257`). 심링크 삭제·DB 원복 완료 |
| TC-PORTALUP-029 | PASS | [실동작] 신규 자산 uldSn=82 `DELETE` → **204**. 이후 `ls_portal_uld`=0행, `ls_portal_uld_frme`(uldSn=82)=0행(CASCADE), 물리 파일 `/app/storage/raw/portal/images/a254d60b-….jpeg` **삭제됨**(`ls` → No such file) |
| TC-PORTALUP-030 | PASS | [실동작] uldSn=82 를 `PROCESSING` 으로 두고 `DELETE` → **409** `프레임 추출이 진행 중인 자산은 삭제할 수 없습니다…` `:273-276` |
| TC-PORTALUP-031 | PASS | [실동작·★반증] 저장 디렉터리를 `chmod 555` 로 만들어 **실제 IOException 유발** → **500** `파일 삭제에 실패했습니다…` + **DB 행 잔존 확인**(`SELECT count(*) … uld_sn=82` → **1**). 파일 삭제가 DB 삭제보다 앞(`:286-289`)이라 부분 삭제가 생기지 않음. 권한 원복 완료(`chmod 775`) |
| TC-PORTALUP-032 | PASS | [실동작] 3002 → `DELETE /uploads/82`(3001 소유) **403**, 삭제 미수행(직후 3001 이 정상 삭제 가능) |

### F-6 소계 — PASS 12 / PARTIAL 1 / FAIL 0

---

## 3. 판정 집계

| 구간 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| F-2 | 23 | 22 | 0 | 1 | 0 | 0 | 0 |
| F-6 | 13 | 12 | 0 | 1 | 0 | 0 | 0 |
| **합계** | **36** | **34** | **0** | **2** | **0** | **0** | **0** |

PASS율 94.4%. **FAIL 0건 — 2차의 이 구간 HIGH(게이트 전무)는 실동작으로 해소가 확증됐다.**

---

## 4. 이슈

### [F-ISSUE-21] TC-PORTAL-021 — 데이터마트 영상 목록의 `totalElements`/`totalPages` 오보 + 중간 빈 페이지(2차 F-ISSUE-02 **미해소 이월**)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 포털 홈 목록은 "진입 가능한 영상"만 세어야 한다. 프레임 0건 영상은 라벨링 진입 대상 프레임이 없어 의도적으로 제외되므로(서비스 주석 "MED 방어"), 페이지 메타(`totalElements`/`totalPages`)도 그 기준이어야 FE 페이저가 실제 데이터와 맞고, **뒤 페이지 영상이 사용자 눈에서 사라지지 않는다.**
- **현재 동작(이슈 내용)**: 필터는 페이지 **content 에만** 적용되고 total 은 필터 이전 값을 그대로 쓴다.
  ```java
  // PortalLabelService.java:177-189
  List<DatamartVideoResponse> content = rows.stream()
          .filter(r -> firstSrcSnByVideo.get(r.getRawSn()) != null)   // ← 페이지 안에서만 제외
          ...
  // 제외로 인해 페이지 size 보다 적어질 수 있으나 totalElements 는 원본(게이트 후) 기준 유지.
  return new PageImpl<>(content, pageable, page.getTotalElements());   // ← 30 (실제 노출 20)
  ```
  실측(`size=5`, 3001 토큰):
  ```
  page=0 total=30 pages=6 n=5  [880210,880200,159,115,110]
  page=1 total=30 pages=6 n=3  [101,94,81]        ← 5건 요청했는데 3건 (2건 조용히 증발)
  page=2 total=30 pages=6 n=5
  page=3 total=30 pages=6 n=5
  page=4 total=30 pages=6 n=0  []                 ← ★중간 빈 페이지
  page=5 total=30 pages=6 n=2  [18,4]
  ```
  전 페이지 합 20건 ≠ `totalElements` 30. `size=100` 으로 요청하면 우연히 20 으로 보이는데, 이는 서비스가 고친 게 아니라 **Spring `PageImpl` 이 "마지막 페이지면 offset+content.size() 로 total 을 재계산"** 하는 보정이 걸린 것뿐이다(작은 size 에서는 보정이 안 걸려 오보가 그대로 노출된다) — 오진 주의.
- **재현/확인 경로**:
  ```bash
  TOK=$(curl -s -XPOST localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
        -d '{"role":"PORTAL_USER","channel":"PORTAL","userNo":"3001"}' | jq -r .data.token)
  for p in 0 1 2 3 4 5; do curl -s -H "Authorization: Bearer $TOK" \
      "localhost:18081/api/v1/portal/datamart/videos?page=$p&size=5" \
    | jq -c '{p:.data.number,total:.data.totalElements,n:(.data.content|length)}'; done
  ```
  ```sql
  -- 프레임 0건 APPROVED 영상(= 제외 대상) 확인
  SELECT r.raw_sn FROM ls_data_raw r JOIN ls_raw_data_status s ON s.raw_data_id=r.raw_sn
   WHERE s.data_stts_cd='APPROVED'
     AND NOT EXISTS (SELECT 1 FROM ls_data_src d WHERE d.raw_sn=r.raw_sn);
  -- → 8,9,15,43,48,55,66,67,146,147 (10건)
  ```
- **영향**: 기능/데이터정합. ①FE 페이저가 실제보다 많은 페이지를 그려 빈 화면이 노출된다 ②**한 페이지가 통째로 비면 사용자는 "끝"으로 오인해 뒤 페이지(rawSn 18·4)의 영상에 접근하지 못한다**(무한스크롤 구현이면 더 확실히 멈춘다) ③"검수 완료 영상 N건" 카운트가 20% 이상 부풀려 보고된다. 보안 영향은 없다(노출되는 영상 자체는 APPROVED 게이트 통과분).
- **수정 방향(제안)**: ⚠ 구현하지 않음. 필터를 **쿼리로 내린다** — `findAllWithReviewStatus` 에 `AND EXISTS (SELECT 1 FROM LsDataSrc d WHERE d.rawSn = v.rawSn)` 를 추가하면 count 쿼리에도 같은 조건이 적용돼 total·page 수·페이지 채움이 동시에 정합해진다(현행 in-memory `filter` 는 제거). 프레임 유무는 `LS_DATA_SRC(RAW_SN)` 인덱스로 판정되므로 EXISTS 비용은 낮다. 대안(서비스에서 total 만 재계산)은 **중간 빈 페이지를 못 고치므로 부적절**하다.

---

### [F-ISSUE-22] TC-PORTALUP-027 — 포털 **영상** 업로드에서 추출된 프레임이 `application/octet-stream` 으로 서빙됨(자산 MIME 을 프레임 MIME 으로 오용)

- **심각도**: LOW
- **기대 동작(기대효과)**: 프레임 이미지 서빙은 **그 프레임 파일의 실제 형식**을 Content-Type 으로 선언해야 한다. `X-Content-Type-Options: nosniff` 를 함께 보내므로 선언이 틀리면 브라우저는 **정정할 수단이 없다**(sniffing 금지). 카탈로그 기대값 "Content-Type=저장MIME" 의 취지도 "확장자 추정 금지 = 저장 시점에 확정한 **그 파일의** MIME 사용"이다.
- **현재 동작(이슈 내용)**: 프레임의 MIME 을 **업로드 마스터(`LS_PORTAL_ULD.MIME_TYPE_NM`)** 에서 가져온다. 이미지 업로드는 마스터 MIME = 프레임 MIME 이라 맞지만, **영상(TUS) 업로드는 마스터가 `video/mp4`** 이고 추출된 프레임은 JPEG 이라 매핑이 어긋난다.
  ```java
  // PortalUploadService.java:210-223
  LsPortalUld uld = uldRepository.findByUldSnAndPortalUserNo(frme.getUldSn(), portalUserNo)...
  MediaType mediaType = resolveStoredMediaType(uld.getMimeTypeNm());   // ← 자산 MIME (VIDEO 면 video/mp4)
  // :433-443  resolveStoredMediaType : JPEG/PNG 가 아니면 APPLICATION_OCTET_STREAM (fail-closed)
  ```
  실측:
  ```
  GET /v1/portal/uploads/frames/61/image   (IMAGE 자산, mime=image/png)
    → 200  Content-Type: image/png            ✅
  GET /v1/portal/uploads/frames/72/image   (VIDEO 자산, mime=video/mp4)
    → 200  Content-Type: application/octet-stream   ❌  (+ nosniff, Content-Disposition: inline)
  $ docker exec klid-backend head -c 4 /app/storage/raw/portal/frames/72/frame-0.jpg | od -An -tx1
     ff d8 ff e0        ← 실체는 JPEG
  ```
  `PortalFrameExtractRunner` 는 프레임을 항상 `.jpg` 로 떨어뜨리므로 **영상 업로드 자산의 모든 프레임이 이 경로에 해당**한다(실측 3001 소유 VIDEO 7건 전부).
- **재현/확인 경로**:
  ```bash
  curl -s -D - -o /dev/null -H "Authorization: Bearer $TOK" \
    localhost:18081/api/v1/portal/uploads/frames/72/image | grep -i content-type
  # → Content-Type: application/octet-stream / X-Content-Type-Options: nosniff
  ```
- **영향**: 기능(렌더링). 현재 FE 는 `useUploadFrameImage.ts:49-53` 이 XHR 로 blob 을 받아 `URL.createObjectURL` 로 그리므로 **지금은 화면이 깨지지 않는다**(blob: URL 은 ORB 대상이 아님). 다만 ①`Content-Disposition: inline` + octet-stream 조합이라 URL 직접 열람 시 표시 대신 다운로드가 되고 ②FE 가 성능상 `<img src>` 직결로 바꾸는 순간 **nosniff + 비이미지 타입 → Chrome ORB 로 차단**되어 영상 업로드 라벨링 캔버스가 백지가 된다(내부 라벨링에서 실제로 있었던 회귀 유형). 보안 영향은 없다(오히려 fail-closed 방향).
- **수정 방향(제안)**: ⚠ 구현하지 않음. 프레임의 MIME 을 **프레임 행 기준**으로 정한다 — ①단기: `serveFrameImage` 에서 자산이 `TYPE_VIDEO` 면 추출 포맷 상수(JPEG)를 쓰거나 `FrameImageService.resolveMediaType(path)`(확장자 allowlist 기반, 내부 서빙과 동일 헬퍼) 를 재사용 ②정공: `LS_PORTAL_ULD_FRME` 에 프레임 MIME 컬럼을 추가해 추출 시점에 확정 적재(표준용어 검토 필요). ①이라도 `application/octet-stream` fail-closed 기본값은 유지할 것.

---

### [F-ISSUE-23] TC-PORTAL-025 / TC-PORTAL-026 **인접** — 포털 프레임 라벨 Load 응답에 항목·좌표 상한도 페이징도 없어 단일 프레임 응답이 500KB 를 넘음(2차 F-ISSUE-22 의 **조회측 잔여**)

- **심각도**: LOW
- **기대 동작(기대효과)**: 2차 [F-ISSUE-22] 의 수정 방향은 *"`saveUserLabel` 에 좌표 개수 상한 적용 + **`loadFrameLabels`/`listMyLabels` 응답에 항목 상한 또는 페이징 도입**"* 이었다. 저장을 막아도 **이미 적재된 행**과 **행 개수 축**이 남으므로 조회측 상한이 있어야 응답 크기가 유계가 된다.
- **현재 동작(이슈 내용)**: 저장측은 해소됐다(`validatePointCount` — BBOX 2점 / POLYGON 3~200점, `PortalLabelService.java:302-315`). 그러나 **조회측은 무제한 그대로**다 — `loadFrameLabels`(`:432-448`)·`listMyLabels`(`:380-381`) 어디에도 `Pageable`·항목 상한이 없다.
  ```
  GET /v1/portal/frames/1/labels        → 519,052 bytes
    labels = [{id:8, POLYGON, points:30000}, {id:7, "HACK"}, {id:6}, {id:5}, {id:1}]
  GET /v1/portal/user-labels?rawSn=4    → 460,428 bytes  (동일 무페이징)
  ```
  id=8 은 2차 검증이 상한 도입 전에 적재한 30,000점 레거시 행이다(신규 생성은 이제 400 으로 차단됨).
- **재현/확인 경로**:
  ```bash
  curl -s -H "Authorization: Bearer $TOK" -o /dev/null -w '%{size_download}\n' \
       localhost:18081/api/v1/portal/frames/1/labels     # → 519052
  ```
  ```sql
  SELECT user_lbl_sn, lbl_type_cd, length(point_cn) FROM ls_portal_user_label WHERE src_data_src_sn=1;
  ```
- **영향**: 자원 소진(CWE-770 / OWASP API4) — **잔여 위험은 낮다**: ①신규 유입은 좌표 200점 + 본문 상한 + per-user RateLimiter(`PortalLabelController.acquireSavePermit`)로 3중 제한 ②남은 축은 "한 프레임에 쌓인 행 개수 × 200점"과 기존 레거시 행뿐. 다만 **라벨 삭제 API 가 없어 행은 단조 증가**하므로 장기적으로는 조회 응답이 계속 커진다. 보안 노출은 없다(전부 본인 소유분).
- **수정 방향(제안)**: ⚠ 구현하지 않음. ①`loadFrameLabels`/`listMyLabels` 에 `Pageable`(하드캡 100, 형제 `PortalUploadController.capped` 규약 재사용) 또는 프레임당 항목 상한을 도입 ②레거시 초과 행 정리 마이그레이션은 별건으로 분리(조회는 예외 없이 스킵/절단, 500 금지). ⚠ FE `PortalLabelingPage` 가 전량 로드를 전제하므로 계약 변경 시 FE 동반 수정 필요.

---

## 5. 검증 중 조작한 데이터·원복 증거 (코드 미수정)

| 조작 | 목적 | 원복 |
|---|---|---|
| `ls_data_src.de_idntf_src_file_path_nm`(src_sn=1) 임시 변경 6회 | TC-PORTAL-031/032/033/034 | `/app/storage/deidentified/frames/deid/4/frame-0.jpg` 로 복구 → 재조회 **200** 확인 |
| `/app/storage/deidentified/frames/deid/4/qa-symlink.jpg` 생성 | TC-PORTAL-033 심링크 반증 | `rm -f` 완료 |
| `ls_raw_data_status` 에 `raw_data_id=159, APPROVED` 삽입 | TC-PORTAL-038(부모 'F' × 파생 APPROVED 조합이 기존 데이터에 없었음) | `DELETE` 완료(잔여 0행) |
| `ls_portal_user_label` userLblSn=25 저장 | TC-PORTAL-025 | `DELETE` 완료(잔여 0행) |
| `ls_portal_uld_frme.file_path_nm`(uld_frme_sn=61) 임시 변경 5회 + `…/portal/images/qa-esc.png` 심링크 | TC-PORTALUP-028 | 경로 복구 + 심링크 삭제 → 재조회 **200** 확인 |
| `ls_portal_uld.uld_stts_cd`(uld_sn=82) → PROCESSING → READY | TC-PORTALUP-030 | 원복 후 해당 자산은 TC-029 에서 정상 삭제 소진 |
| `/app/storage/raw/portal/images` `chmod 555` | TC-PORTALUP-031 IOException 유발 | `chmod 775` 복구 확인(`drwxrwxr-x`) |
| 검증용 이미지 1건 업로드(uldSn=82) | TC-PORTALUP-029/030/031/032 | TC-029 에서 DELETE 로 소진(DB·파일 모두 제거됨) |

기존 시드/타 에이전트 데이터는 **삭제·변경하지 않았다.**

---

## 6. 카탈로그 정정 (담당 라인범위 내 Edit 완료 — 4건)

| ID | 항목 | 구 | 신 |
|---|---|---|---|
| TC-PORTAL-038 | 근거 라인 드리프트 | `AiInferenceDeidentReportGateTest.java:283-295` | `…:284-296` (`@Test` 284 · 메서드 285-296) |
| TC-PORTAL-052 | 근거 라인 드리프트 | `PortalLabelService.java:127-141` | `…:128-143` (127 은 공백행, `toList()` 는 143행) |
| TC-PORTALUP-021 | 근거 라인 드리프트 + 케이스명 보강 | `PortalUploadService.java:164-170` | `…:165-171`, 케이스명에 **대소문자 무관(`toUpperCase` 정규화)** 명시 + 입력에 `?type=image` 추가(실동작으로 확인한 동작을 카탈로그가 커버하지 않았음) |
| TC-PORTALUP-022 | 근거 라인 드리프트 | `PortalUploadService.java:163-168` | `…:164-169` |

정정하지 않고 유지한 것:
- **TC-PORTALUP-027 의 기대결과는 그대로 뒀다.** 구현이 자산 MIME 을 쓰는 것은 카탈로그 오류가 아니라 **구현 결함**(F-ISSUE-22)이므로, 기대값을 현행 동작에 맞추면 결함을 카탈로그에 고착시키게 된다.
- TC-PORTAL-038 의 `DeidentReportGate.java:23-37` 은 재확인 결과 **정확**(23=`★ 판정 범위` 헤딩, 37=철회 문단 끝).
- 그 외 F-2·F-6 근거 라인 32건은 전건 재확인 결과 정확(수정 없음).

---

## 7. 이월·참고 (신규 이슈 아님)

- **UNCERTAINTIES #12(데이터마트/이미지 서빙 rate limit 부재) — 미해소 유지**. `PortalLabelController` 에서 RateLimiter 는 `POST /user-labels` 한 곳뿐이다(`acquireSavePermit`, `:167-175`). `/datamart/videos`·`/datamart/labels`·`GET /user-labels`·`/frames/{srcSn}/labels`·`/frames/{srcSn}/image` 5개 조회 핸들러는 여전히 무제한이며, 프레임 이미지는 `no-store` 라 매 요청 디스크 I/O 가 발생한다. **다만 3차에서 APPROVED·신고 게이트가 붙어 열거 가능한 표면이 "APPROVED 영상"으로 좁아졌으므로 위험도는 2차보다 낮다.** 확정 정책이 없어 이번에도 사실만 기록한다(케이스 판정 대상 아님).
- **신고 구간 영상이 데이터마트 목록에는 계속 노출된다**(rawSn=900 이 `/datamart/videos` 결과에 포함, 진입하면 412). 관제 뷰 확정 정책(신고 필터 미적용)과 방향이 같아 **결함으로 보고하지 않는다**(2차 판단 유지).
- **2차 F-ISSUE-27(배포 jar STALE) 해소 확인** — §0 말미 참조. 3차 스택은 HEAD `e065da42` 재빌드본이며 포털 정렬 allowlist가 실효한다.
- 2차가 남긴 레거시 오염 행이 DB 에 남아 있다: `ls_portal_user_label` 의 `lbl_type_cd='HACK'`(id 7)·`Infinity` 좌표(id 5)·음수 좌표(id 6)·30,000점(id 8). 신규 생성은 3차 수정분으로 전부 차단됐고 조회는 500 없이 처리된다(F-3 담당 범위 — 여기서는 사실만 기록).

---

# F 클러스터(포털) part3 — 3차 전수 검증 결과

- **대상**: `docs/test-cases/F-portal.md` **F-3**(58~84행, 활성 16 + 폐기 4) + **F-8**(180~218행, 32건) = **52건**
- **회차**: 2026-08-03 3차 / 검증 시각 2026-08-04 02:15~02:35 (KST)
- **환경**: `_raw/stack-bringup.md` 기준 풀스택 기동 상태 (backend `localhost:18081` context-path `/api`, PostgreSQL `public` 스키마, mock-server :9400) — 재빌드된 HEAD `e065da42` 이미지
- **사용 데이터**: rawSn 905(APPROVED·`DE_IDENT_YN='Y'`, srcSn 459~463) / rawSn 900(APPROVED·**`'F'` 신고구간**, srcSn 429~433) / rawSn 173(미승인, srcSn 613~617) / 포털 업로드 프레임 `uldFrmeSn=1`(owner `portal-qa-1`)
- **토큰**: `POST /v1/dev/tokens` 로 PORTAL_USER 발급 (`3001`, `portal-qa-1`, `qa3-f3a`, `qa3-f3b`, `qa3-f3c`, `qa3-rl`, `qa3-tus`, `qa3-tus2`)
- **프로덕션 코드 미수정** (검증 전용). 카탈로그는 담당 라인범위 내 1건 정정.

---

## 0. ★★ 최우선 — 2차 HIGH #2 (F-ISSUE-64, CWE-436) 해소 여부: **해소 확인(라이브 반증 실패 = 방어 성립)**

2차에서 `PortalLabelBodySizeFilter` 가 **원시 URI 정규식**으로 경로를 판정해 `%6Cabels` 한 글자 인코딩만으로 본문 상한·chunked 가드가 통째로 무력화됐던 결함을, **3차에서 직접 라이브 재현 시도**했다. **전 변형에서 우회에 실패**했다.

현행 구현(`PortalLabelBodySizeFilter.java:181-200`)은 자체 디코딩을 버리고 **MVC 와 같은 `ServletRequestPathUtils.parseAndCache(request).pathWithinApplication()`** + `PathPattern` 으로 판정한다(후행 슬래시 변형 패턴 동시 등록).

### 0-1. 라벨 PUT (`/v1/portal/uploads/frames/1/labels`, 3,600,116 byte JSON, 상한 2MB)

| 요청 경로 | 결과 | 판정 |
|---|---|---|
| `/labels` (정규) | **413** `time_total=0.0053` | 파싱 전 차단 |
| `/%6Cabels` (2차 우회 벡터) | **413** `0.0022` | **우회 실패 = 해소** |
| `/label%73` | **413** `0.0017` | 해소 |
| `/labels/` (trailing slash) | **413** `0.0018` | 해소 |
| `/./labels` | **413** `0.0014` | 해소 |
| `/frames/%31/labels` (경로변수 인코딩) | **413** `0.0014` | 해소 |
| `/%256Cabels` (이중 인코딩) | 401 (컨트롤러 미도달) | 우회 아님 |
| `/labels;x=y` (matrix) | 401 (컨트롤러 미도달) | 우회 아님 |

chunked 재현: `/labels`·`/%6Cabels`·`/labels/` **전부 411 LENGTH_REQUIRED** (2차에는 인코딩 경로에서 `{"success":true}` 200 으로 라벨이 실제 교체됐었다).

### 0-2. 사용자 라벨 POST (`/v1/portal/user-labels`) — 3차 신규 적용분

| 요청 경로 | huge body | chunked |
|---|---|---|
| `/v1/portal/user-labels` | **413** `0.0049` | **411** |
| `/v1/portal/%75ser-labels` | **413** `0.0037` | **411** |
| `/v1/portal/user-label%73` | **413** | - |
| `/v1/portal/user-labels/` | **413** | - |
| `/v1/portal/./user-labels` | **413** | - |
| `PUT` 메서드 오지정 | **413** (더 매칭 = 무해) | - |

**라우팅 대조(핵심 반증 절차)**: 작은 본문(`{}`)으로 각 변형의 **컨트롤러 도달 여부**를 먼저 확정했다 — 도달(400 검증오류): `user-labels`, `%75ser-labels`, `./user-labels` → 이 3개 전부 huge body 에서 **413**. 미도달(401/404): `%2575ser-labels`, `//user-labels`, `user-labels;a=b`, `user-labels/`, `USER-LABELS`. 즉 **"컨트롤러에 도달하는데 필터만 스킵되는" 조합이 0건**이다.

### 0-3. 추가 적대 변형 (전부 차단, 200 = 우회 신호 0건)

`X-HTTP-Method-Override: PUT` 로 POST → **405** / form `_method=PUT` → **405** / `frames/1%2Flabels` → **400** / `labels%20` → **404** / `labels%00` → **400** / `frames/1/x/..%2flabels` → **400**.

> **결론: 2차 HIGH #2 = 해소.** 회귀 가드도 실재한다 — `PortalLabelBodySizeFilterTest`(19 테스트, `%6Cabels`·chunked·matrix·trailing slash·context-path·**이중인코딩 우회불가 파리티**·servlet-path-prefix 포함), baseline(`_raw/test-baseline.md`) 상 backend 실패 0건.

---

## 1. F-3 결과표 — 데이터마트 사용자 라벨 저장 (활성 16 / 폐기 4)

| TC-ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-PORTAL-040 | PASS | [실동작] `POST` 201(userLblSn=21) + **`ls_data_lbl` 128건 불변**(저장 전후 동일), `ls_portal_user_label` 만 증가. `GET /v1/portal/datamart/labels?rawSn=905` 응답에 사용자 라벨 미포함. `pg_views` 중 `portal_user_label` 참조 뷰 **0건**(데이터마트 단방향 격리 실증). 근거 라인 `PortalLabelService.java:262-271` 일치 |
| TC-PORTAL-041 | PASS | [실동작] 미승인 rawSn=173 → **403** `FORBIDDEN`, 미존재 rawSn=999999 도 **403**(존재 오라클 차단, CWE-209). `:246-249` 일치 |
| ~~TC-PORTAL-042~045~~ | N/A | [실동작] 폐기 타당 확인 — `lblTypeCd=SKELETON` 은 저장 자체가 400. `PortalKeypointRemovedTest.java` 실재(7 테스트) |
| TC-PORTAL-046 | PASS | [실동작] `points='[]'` → 400 "points 좌표가 비어있습니다", `'[[]]'` → 400 |
| TC-PORTAL-058 | PASS | [실동작] SKELETON/SEGMENT/TRACK/POINT/MASK/`zzz` **전부 400**(저장 미수행). BBOX·POLYGON 은 201 정상 저장(회귀 없음). 소문자 `bbox`/`BbOx` 는 대문자 정규화 후 개수 검증으로 진행(적재값은 대문자 — 우회 아님). `validateAndNormalizeType():284-293` |
| TC-PORTAL-059 | PASS | [실동작] DB 에 레거시 삼중값 row(`lbl_type_cd='SKELETON'`, `point_cn='[[1,2,0.9],[3,4,0.8]]'`) 직접 적재 → `GET /v1/portal/frames/460/labels` **200**, 해당 항목만 스킵되고 정상 BBOX 1건만 반환(500 미발생). `parsePoints():460-472` |
| TC-PORTAL-060 | PASS | [실동작] POLYGON 201점 → 400, 2점 → 400, 200점 → 201, BBOX 3점/1점 → 400. 형제 상수(`PortalUploadLabelService.BBOX_POINT_COUNT`/`POLYGON_*`) 재사용 확인 |
| TC-PORTAL-061 | PASS | [실동작] rawSn=900(`DE_IDENT_YN='F'`) 저장 → **412** `PRECONDITION_FAILED`, 행 미생성. `accessGuard.requireNotUnderDeidentReport():250` |
| TC-PORTAL-062 | PASS | [실동작] 동일 사용자 320회 연속 POST → **403×300 + 429×20, 최초 429 = 301번째**(config `portalUserLabel` 300/1m 과 정확히 일치). 다른 사용자는 같은 시각에 정상 처리(403) = **per-user 격리 성립**. `PortalLabelController.acquireSavePermit():167-175` |
| TC-PORTAL-047 | PASS | [실동작] points 누락/공백/`null` **전부 400** `points: points 는 필수입니다`. `PortalUserLabelRequest.java:18` 일치 |
| TC-PORTAL-048 | PASS | [실동작] 사용자B 가 저장한 `B-SECRET` 라벨이 사용자A 의 `GET /v1/portal/user-labels?rawSn=905` 응답에 **미포함**(A 는 자기 3건만, B 는 자기 1건만). `:369-382` 일치 |
| TC-PORTAL-049 | PASS | [실동작] `point_cn IS NULL` row 적재 후 프레임 라벨 Load → 해당 항목 제외. `:432-434` 일치 |
| TC-PORTAL-050 | PASS | [실동작] `point_cn='{not-json'` row 적재 후 Load → **200**, 항목 제외, 500 미발생. `:460-472` 일치 |
| TC-PORTAL-054 | PASS | [실동작] `GET /v1/portal/user-labels` — rawSn=173(미승인) **403**, rawSn=999999 **403**, rawSn=900(`'F'`) **412**, rawSn=905 **200**. 형제 경로(datamart labels)와 동일 순서·동일 컴포넌트 확인 |
| TC-PORTAL-055 | PASS | [실동작] §0-2 표 참조 — 413/411 + 인코딩·trailing slash·메서드 변형 전부 적용, **체인 미진행**(응답 0.002~0.005s = 3.6MB 본문 미판독). `PortalLabelBodySizeFilter.java:81-124` 일치 |
| TC-PORTAL-056 | PASS | [실동작] `points` 66,014자(공백 패딩, 유효 JSON·BBOX 2점) → **400** `points 는 65536자 이하여야 합니다`; 65,014자는 201 통과하되 **정규형 재직렬화**로 `[[1.0,1.0],[2.0,2.0]]` 만 적재(개수캡+길이캡 2층 실효). `PortalUserLabelRequest.java:17-40` 일치 |
| TC-PORTAL-057 | PASS | [정적+단위] `pathWithinApp()`(`:181-200`)이 `RequestPath.parse(uri, ctx)` 자체 파싱을 제거하고 **`ServletRequestPathUtils.parseAndCache` 단일 규약**만 사용 — 형제 `WebhookProtectedPaths.java:244-279`(실경로 `common/security/webhook/`)도 **동일 코드**로 정합. 단위 회귀 `PortalLabelBodySizeFilterTest#servletPathPrefixAware`(`/api2` + `setServletPath` → 413, chain null 단언) + `WebhookProtectedPathsServletPrefixTest` 실재. ⚠ **라이브 재구성은 수행하지 않음** — `spring.mvc.servlet.path=/api2` 는 공유 backend 컨테이너 재기동(또는 동일 DB·Quartz 를 물는 2번째 인스턴스 기동)을 요구해 병렬 검증 중인 타 클러스터 환경을 오염시킨다. 라이브로는 **contextPath(`/api`) 축**만 실증됨(§0 전 표가 `/api` 하위 요청) |

**F-3 소계: PASS 16 · N/A(폐기 확인) 4 · FAIL 0 · PARTIAL 0**

---

## 2. F-8 결과표 — 포털 영상 TUS 업로드 (32건)

실파일 `clip.mp4`(20,590 byte, 정상 mp4 `ftypisom`) 기준 **세션 생성 → 반분할 청크 → 재개(HEAD) → 완료검증 → 관제/프레임추출** 전 구간을 실제 HTTP 로 구동했다.

| TC-ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-TUS-001 | PASS | [실동작] `OPTIONS` → **204** + `Tus-Resumable: 1.0.0` / `Tus-Version: 1.0.0` / `Tus-Extension: creation,termination` / **`Tus-Max-Size: 5368709120`**. `:67-76` 일치 |
| TC-TUS-002 | **PARTIAL** | [실동작] 201 + `Location: /v1/portal/uploads/tus/0a39529d-…` 이나 **context-path `/api` 누락** → 표준 tus 클라이언트가 그대로 따라가면 404. 2차 **F-ISSUE-82 미해소**(§3-1). `:80-122` 라인 일치 |
| TC-TUS-003 | PASS | [실동작] `Upload-Length` 누락 → **400**. `:88-90` |
| TC-TUS-004 | PASS | [실동작] `Upload-Length: 5368709121` → **413**. `:87-90` (⚠ 오류 응답에 `Tus-Resumable` 부재 = 2차 F-ISSUE-84 미해소, §3-4) |
| TC-TUS-005 | PASS | [실동작] `evil.exe` → **400**. 확장자 미검출(`filename` 부재/점 없음/8자 초과/비영숫자)도 `""` → allowlist 밖 → 400 (fail-closed, `resolveExtension():233-248`) |
| TC-TUS-006 | PASS | [실동작] IN_PROGRESS 3건 보유 상태에서 4번째 생성 → **429**. `:96-102` |
| TC-TUS-007 | PASS | [실동작] `Upload-Metadata: filename ../../../../etc/passwd.mp4` 로 생성 → 저장 실경로 `/app/storage/raw/portal/tus-video/16e5a866-….mp4`(**UUID 강제**), DB `file_path_nm` 동일. `:104-107` |
| TC-TUS-008 | PASS | [실동작] 2,000자 초과 메타 → **413**. `:182-184` |
| TC-TUS-009 | PASS | [실동작] `filename @@@notbase64@@@` → **400**. `:194-200` |
| TC-TUS-010 | PASS | [실동작] `Tus-Resumable: 0.2.2` → **412**. ⚠ **헤더 부재 시 검사 통째로 스킵 → 201**(2차 F-ISSUE-83 미해소, §3-2). `:163-168` |
| TC-TUS-011 | PASS | [실동작] 소유 세션 HEAD → **204** + `Upload-Offset: 0`→(청크 후)`10295` + `Upload-Length: 20590` + **`Cache-Control: no-store`**. ⚠ CANCELLED 세션 HEAD 는 204+offset 0 반환(2차 F-ISSUE-85 미해소, §3-3). `:102-116` |
| TC-TUS-012 | PASS | [실동작] 타 사용자(`qa3-tus2`) HEAD/PATCH/DELETE **전부 403**. `:130-132` |
| TC-TUS-013 | PASS | [실동작] `expry_dt` 를 1시간 전으로 갱신 후 HEAD **410**, PATCH **410**. `:133-135` |
| TC-TUS-014 | **PARTIAL** | [실동작] 정상 PATCH 는 **204 + `Upload-Offset: 10295`** ✓. 그러나 `Content-Type` 불일치(`application/json`)·부재 시 **500 INTERNAL_ERROR + `HttpMediaTypeNotSupportedException` 스택트레이스 ERROR 로그** (415 여야 함) — 2차 **F-ISSUE-81 미해소**(§3-5). `PortalVideoUploadTxService.java:68-124` |
| TC-TUS-015 | PASS | [실동작] `Upload-Offset` 누락 → **400**. `:130-132` |
| TC-TUS-016 | PASS | [실동작] offset=0(서버 10295) → **409**, offset=10300 → **409**. `TxService:98-100` |
| TC-TUS-017 | PASS | [실동작] offset=-1 → **400**, offset=L+1 → **400**. `TxService:95-97` |
| TC-TUS-018 | PASS | [실동작] 16MB+1 청크 → **413**. `TxService:91-94` |
| TC-TUS-019 | PASS | [실동작] `offset+len > length` → **400**. `TxService:101-103` |
| TC-TUS-020 | PASS | [실동작] 동일 세션 4-스레드 동시 PATCH → **204 1건 + 409 3건**, 이후 HEAD offset=10000 으로 **정합 유지**(파손 없음). ⚠ 실제 직렬화는 비관적 락이 담당하고 `truncateTo` 낙관락 복원 분기는 도달 불가(2차 F-ISSUE-87 미해소, LOW, §3-6). `TxService:109-115` |
| TC-TUS-021 | PASS | [실동작] DELETE 후 PATCH → **409** "취소된 업로드 세션입니다". `TxService:81-83` |
| TC-TUS-022 | **PARTIAL** | [실동작] 완료 세션에 마지막 청크 재전송 → **204 + `Upload-Offset: 20590`**(중복 ULD 미생성 = 멱등 ✓). 그러나 **응답에 `uldSn` 이 없다**(서비스 `PortalTusPatchResult.uldSn` 을 컨트롤러가 폐기) — 2차 **F-ISSUE-88 미해소**(§3-7). `TxService:84-87` |
| TC-TUS-023 | PASS | [실동작] `NOTAVIDEO…`(900B, `.mp4` 명) 완료 → **400** "유효한 영상 컨테이너가 아닙니다", 세션 **CANCELLED** 영속, 임시파일 삭제 확인. `:161-165` |
| TC-TUS-024 | PASS | [실동작] 헤더 32B 만 정상이고 이후 전량 `0xFF` 인 손상 mp4 → **400** "영상을 확인할 수 없습니다", CANCELLED. `:167-175` |
| TC-TUS-025 | PASS | [실동작] `ffmpeg -f lavfi -i sine … -c:a aac` 로 만든 **오디오 전용 mp4**(18,802B) → **400** "비디오 스트림이 없는 파일입니다". `:176-179` |
| TC-TUS-026 | PASS | [실동작] 최종 청크 처리 **도중**(검증 구간) 보낸 DELETE 가 **즉시 204 로 성공**(20·30ms 지연 시행) → 완료 검증이 행 잠금/트랜잭션 밖에서 수행됨을 실증. `:146-186` |
| TC-TUS-027 | PASS | [실동작] `stts_cd='COMPLETED'` 세션 **9건** ↔ `ls_portal_uld` 행 **9건**(1:1). 멱등 재전송·동시 완료에도 ULD 중복 0. `TxService:132-157` |
| TC-TUS-028 | PASS | [정적] `finalizeCompleted` 의 조건부 UPDATE 0행 → ULD 보상 삭제 + 409 (`TxService:141-152`). ⚠ 라이브 강제 실패 — 지연 20~160ms 스윕 결과 창이 **30~40ms 사이 ~10ms** 로 좁고, 그보다 이르면 400(파일 부재로 매직바이트 실패)·늦으면 완료 후 no-op(204) 로 갈린다. 자동 테스트도 0건(§3-8 커버리지 갭) |
| TC-TUS-029 | PASS | [실동작] 진행중 세션 DELETE → **204**, `stts_cd='CANCELLED'`, `tus-video/{uuid}.mp4` 삭제 확인. `:190-206` |
| TC-TUS-030 | PASS | [실동작] 완료 세션 DELETE → **204** + 영구 파일 `59d13ded-….mp4`(20,590B) **잔존**(no-op). `:198-201` |
| TC-TUS-031 | PASS | [실동작] 16MB 청크 PATCH(62ms) 진행 중 +50ms 에 DELETE 발사 → DELETE 가 **13ms 대기 후 63ms 에 완료**(= PATCH 트랜잭션 커밋까지 블록) → 동일 락 경로 직렬화 실증. `:192-194` |
| TC-TUS-032 | PASS | [실동작+정적] 저장 파일명이 UUID 로 강제돼 사용자 입력이 경로에 도달하지 않음(TC-007 실증) + `resolveSafe()` 가 `storageRoot` 외부를 403 으로 차단. `:210-218` |

**F-8 소계: PASS 29 · PARTIAL 3 · FAIL 0**

---

## 3. 이슈 (3차 신규 0건 · 2차 미해소 이월 7건)

> ⚠ **채번 안내**: 본 회차 part3 배정 시작번호는 `F-ISSUE-41` 이나, **신규 결함이 0건**이라 신규 채번은 발생하지 않았다. 아래는 **2차(2026-08-02) 이슈 중 이번 담당 범위에서 재현된 미해소 건**이며 **2차 원 ID 를 그대로 유지**한다(수정 추적 연속성 확보 — 새 번호를 붙이면 같은 결함이 두 ID 로 갈린다).

### [F-ISSUE-64] TC-PORTAL-055 / TC-PORTALUP-051·052 — 라벨 본문 상한 URL 인코딩 우회 → ✅ **해소(CLOSED)**
- **재확인 결과**: §0 전 표. `%6Cabels`·`%75ser-labels`·`label%73`·trailing slash·`./`·경로변수 인코딩 **전부 413**, chunked 전부 411, 컨트롤러 도달 조합 중 상한 미적용 **0건**.
- **해소 커밋 구현**: `PortalLabelBodySizeFilter` 가 `ServletRequestPathUtils.parseAndCache` + `PathPattern`(후행 슬래시 변형 동시 등록, 판정 불가 시 fail-closed) 로 전환. 자체 디코딩 루프를 넣지 않아 이중 인코딩에서도 MVC 와 어긋나지 않음.
- **잔여 없음**. 회귀 가드 19 테스트 실재(baseline 실패 0).

---

### [F-ISSUE-81] TC-TUS-014 — PATCH 의 Content-Type 불일치/부재가 415 가 아니라 500 + 스택트레이스 (미해소)
- **심각도**: MEDIUM
- **기대 동작**: `@PatchMapping(consumes="application/offset+octet-stream")` 에 맞지 않는 Content-Type 은 **415 UNSUPPORTED_MEDIA_TYPE** 으로 마감돼야 한다. TUS 클라이언트 오구현·프록시의 헤더 변조는 **정상 운영 중 발생하는 입력 오류**이지 서버 장애가 아니다.
- **현재 동작(실측 2026-08-04 02:26)**:
  ```
  PATCH /api/v1/portal/uploads/tus/{uid}  Content-Type: application/json
  → 500 {"success":false,...,"errorCode":"INTERNAL_ERROR"}
  backend log: org.springframework.web.HttpMediaTypeNotSupportedException: Content-Type 'application/json' is not supported
               at RequestMappingInfoHandlerMapping.handleNoMatch(...)   ← 전체 스택트레이스 ERROR 로 적재
  Content-Type 부재도 동일: "Content-Type is not supported" → 500
  ```
- **재현**: 위 curl/http 요청 그대로.
- **영향**: 기능(클라이언트가 재시도 가능 오류를 서버 장애로 오인) + 운영(정상 입력 오류가 ERROR 스택트레이스로 로그를 오염 → 실제 장애 탐지 저해, CWE-209 계열 로그 노이즈). 응답 본문 자체에 내부 정보 누출은 없음.
- **수정 방향(제안)**: `GlobalExceptionHandler` 에 `HttpMediaTypeNotSupportedException → 415`(+ `HttpRequestMethodNotSupportedException → 405`) 핸들러 추가, WARN 레벨로 강등. F-ISSUE-84(응답 `Tus-Resumable` 부착)와 같은 작업 단위로 처리하면 경제적. ⚠ 구현하지 않음.

---

### [F-ISSUE-82] TC-TUS-002 — 세션 생성 `Location` 이 context-path `/api` 를 누락 (미해소)
- **심각도**: MEDIUM
- **기대 동작**: TUS 1.0 클라이언트는 `Location` 을 그대로 후속 HEAD/PATCH/DELETE 대상 URL 로 쓴다. 배포 형상의 context-path(`/api`)가 포함돼야 재개 업로드가 성립한다.
- **현재 동작(실측)**: `POST /api/v1/portal/uploads/tus` → `201`, `Location: /v1/portal/uploads/tus/0a39529d-c142-4271-aa0f-ae6982c2a53a` (`/api` 없음).
  `PortalTusUploadController.java:96` — `.header(HttpHeaders.LOCATION, "/v1/portal/uploads/tus/" + uldId)` 로 **문자열 하드코딩**.
- **재현**: `curl -i -X POST -H 'Tus-Resumable: 1.0.0' -H 'Upload-Length: 20590' -H 'Upload-Metadata: filename Y2xpcC5tcDQ=' http://localhost:18081/api/v1/portal/uploads/tus`
- **영향**: 기능 — 표준 tus-js-client 계열이 Location 을 따라가면 404 로 업로드 재개 불가. 현재 FE 가 자체 경로 조립으로 우회하고 있어 표면화되지 않았을 뿐이며, 이는 계약 위반이 감춰진 상태다.
- **수정 방향(제안)**: `ServletUriComponentsBuilder.fromCurrentContextPath()`(또는 `request.getContextPath()`) 기반으로 Location 을 조립. 회귀 가드로 context-path 설정 하 `Location` 단언 테스트 추가. ⚠ 구현하지 않음.

---

### [F-ISSUE-83] TC-TUS-010 — `Tus-Resumable` **부재** 시 버전 검사를 통째로 건너뜀 (미해소)
- **심각도**: LOW~MEDIUM
- **기대 동작**: TUS 1.0 은 OPTIONS 를 제외한 모든 요청에 `Tus-Resumable` 을 요구하며, 미지원/부재 시 **412** 다.
- **현재 동작(실측)**: 헤더를 아예 빼고 `POST /api/v1/portal/uploads/tus` (Upload-Length 만) → **201**(세션 생성됨). `PortalTusUploadController.java:163-168`
  ```java
  private void requireTusVersion(String tusResumable) {
      if (tusResumable != null && !TUS_VERSION.equals(tusResumable)) { ... 412 ... }
  }   // ← null 이면 검사 스킵
  ```
  버전 **불일치**(`0.2.2`) 는 정상적으로 412 이므로 TC-TUS-010 자체는 통과한다.
- **영향**: 프로토콜 정합(비표준 클라이언트가 버전 협상 없이 진입). 보안 영향은 없음.
- **수정 방향(제안)**: `null` 도 412 로 승격(단 `OPTIONS` 제외). 기존 FE 가 헤더를 보내는지 먼저 확인해 하위호환 파손 여부 판단 필요. ⚠ 구현하지 않음.

---

### [F-ISSUE-84] TC-TUS-004 인접 — 오류 응답에 `Tus-Resumable` 헤더 부재 (미해소)
- **심각도**: LOW
- **기대 동작**: TUS 명세상 서버 응답(오류 포함)에는 `Tus-Resumable` 이 포함돼야 한다.
- **현재 동작(실측)**: `Upload-Length: 5368709121` → `HTTP/1.1 413` 응답 헤더에 `Tus-Resumable` **없음**(성공 응답에만 컨트롤러가 수동 부착).
- **영향**: 프로토콜 정합. 엄격한 클라이언트가 오류 응답을 프로토콜 위반으로 처리할 수 있음.
- **수정 방향(제안)**: `/v1/portal/uploads/tus/**` 전용 `HandlerInterceptor`(또는 `ResponseBodyAdvice`)로 일괄 부착. F-ISSUE-81 과 동시 처리 권장. ⚠ 구현하지 않음.

---

### [F-ISSUE-85] TC-TUS-011 / TC-TUS-029 인접 — CANCELLED 세션 HEAD 가 204 + 존재하지 않는 offset 반환 (미해소)
- **심각도**: LOW~MEDIUM
- **기대 동작**: 취소된 세션은 재개 대상이 아니므로 HEAD 는 404/410 로 마감돼야 한다(임시파일이 이미 삭제됐다).
- **현재 동작(실측)**: DELETE 로 취소한 세션 `7db52b17-…` 에 HEAD → **204 + `Upload-Offset: 0` + `Upload-Length: 20590`**. 클라이언트는 "offset 0 부터 재개 가능"으로 읽지만, 같은 세션에 PATCH 하면 409("취소된 업로드 세션입니다") 다. `PortalVideoUploadService.getForOwner():127-137` 이 `isCancelled()` 를 보지 않는다(만료만 검사).
- **재현**: 세션 생성 → `DELETE` → `HEAD` (동일 소유자).
- **영향**: 기능 — 재개 UX 가 어긋난다(offset 0 을 받고 처음부터 전송 시도 → 409). 데이터 파손은 없음.
- **수정 방향(제안)**: `getForOwner` 에 `isCancelled()` → 410(GONE) 분기 추가. HEAD/PATCH 응답 코드 일관성(409 vs 410) 은 함께 결정 필요. ⚠ 구현하지 않음.

---

### [F-ISSUE-87] TC-TUS-020 — 낙관적 락 복원(`truncateTo`) 분기가 비관적 락에 가려 도달 불가 (미해소)
- **심각도**: LOW
- **기대 동작**: 카탈로그 기대결과의 "409 + truncate 복원" 중 **복원 경로가 실제로 동작**하거나, 아니면 그 분기가 불필요함이 명시돼야 한다.
- **현재 동작(실측)**: 4-스레드 동시 PATCH → 204 1건 + **409 3건**이며, 409 는 전부 `OptimisticLockingFailureException` 이 아니라 **offset 불일치 분기**(`TxService:98-100`)에서 나온다. 진입부가 `findByUldIdForUpdate`(PESSIMISTIC_WRITE, `:72`)라 트랜잭션이 직렬화돼 `saveAndFlush` 의 낙관락 충돌(`:111-115`)이 발생하지 않는다. 결과 offset(10000)은 정합 유지되므로 **실동작상 안전**하다.
- **영향**: 코드 위생(도달 불가 분기 + 그 분기를 전제로 한 테스트/문서 서술). 기능·보안 영향 없음.
- **수정 방향(제안)**: ①분기를 유지하되 "이중 안전망(방어적)" 주석 명시 + 카탈로그 기대결과에서 truncate 문구 완화, 또는 ②비관락 단일화로 낙관락 필드/분기 제거. ⚠ 구현하지 않음.

---

### [F-ISSUE-88] TC-TUS-022 / TC-TUS-027 — 완료 결과(`uldSn`)가 HTTP 응답에 노출되지 않음 (미해소)
- **심각도**: MEDIUM
- **기대 동작**: 카탈로그 TC-TUS-022 기대결과 "완료 응답(uldSn 재반환)" — 클라이언트가 업로드 완료 직후 생성된 자산 식별자를 알아야 후속 화면 전이·목록 갱신이 가능하다.
- **현재 동작(실측)**: 최종 청크·재전송 모두 **`204 No Content` + `Upload-Offset` 만**. 서비스는 `PortalTusPatchResult(newOffset, completed, uldSn)` 로 uldSn 을 돌려주는데(`PortalVideoUploadService.java:183-185`) 컨트롤러(`PortalTusUploadController.java:139-142`)가 `result.newOffset()` 만 쓰고 **`completed`/`uldSn` 을 폐기**한다. DB 에는 정상 생성됨(`ls_portal_uld.uld_sn=83`, `uld_stts_cd=READY`, `frme_cnt=1`).
- **재현**: 정상 mp4 를 2청크로 업로드 → 최종 PATCH 응답 헤더/본문 확인.
- **영향**: 기능 — 업로드 완료 후 화면이 자산을 즉시 참조하지 못한다(폴링/목록 재조회 의존).
- **수정 방향(제안)**: 완료 시 커스텀 응답 헤더(예: `X-Portal-Uld-Sn`) 부착 또는 `Upload-Offset` 유지한 채 201/200 + 본문 반환(단 TUS 표준은 204 이므로 **헤더 방식 권장**). FE 의 완료 시 목록 무효화도 함께. ⚠ 구현하지 않음.

---

### [F-ISSUE-89] F-8 전반 — TUS 컨트롤러 계층 자동 테스트 0건 (부분 미해소)
- **심각도**: MEDIUM
- **현재 동작**: F-8 자동 테스트는 여전히 `PortalVideoUploadServiceTest.java`(11 테스트, 서비스 계층 fake repo) 1 파일뿐이다(`@DisplayName` 실측 11건: 완료·중복이벤트·IDOR 2건·취소PATCH·매직바이트·오디오전용·CANCELLED영속·5GB·확장자·경로순회). **미커버**: TC-001·003·008·009·010·011·013·015·016·017·019·020·026·028·031(15건).
- ⚠ **이번 회차에서 위 15건 중 14건을 라이브로 직접 검증해 PASS 판정**했다(TC-028 만 라이브 재현 실패). 즉 "동작은 확인됐으나 회귀 가드가 없다"는 상태다.
- **영향**: 회귀 무방비 — 실제로 이번 회차에서 결함이 재현된 지점(F-ISSUE-81/82/83/84/85)이 전부 이 미커버 구간에 있다.
- **수정 방향(제안)**: `PortalTusUploadControllerTest`(`@WebMvcTest`)로 헤더 프로토콜(TC-001·003·008·009·010·011·015), `PortalTusUploadIT`(Testcontainers)로 재개·409·410·동시성·완료 멱등(TC-013·016·017·019·020·026·028·031). ⚠ 구현하지 않음.

---

## 4. 카탈로그 정정 (담당 라인범위 내)

| 위치 | 정정 전 | 정정 후 | 사유 |
|---|---|---|---|
| `F-portal.md:83` (F-3 하단 채번 주의 노트) | "3차 QA 신규 케이스는 039 + 051~057 로 채번했다" | "…039 + 051~057, 이어서 058~062 로 채번했다" | 같은 섹션에 **TC-PORTAL-058~062 (신규) 5건이 실재**하는데 노트가 057 까지만 기술해 다음 회차가 062 를 중복 채번할 위험. 3차 실측 정정 |

그 외 **F-3(58~84행)·F-8(180~218행)의 근거 `file:line` 은 전건 실측 일치 — 드리프트 0건**:
- `PortalLabelService.java` 246-249 / 262-271 / 369-382 / 432-434 / 460-472 ✓
- `PortalUserLabelRequest.java` 18 / 17-40 ✓
- `PortalLabelBodySizeFilter.java` 81-124 / 163-200 ✓ · `WebhookProtectedPaths.java` 244-279 ✓(실경로 `common/security/webhook/`)
- `PortalTusUploadController.java` 67-76 / 88-90 / 102-116 / 130-132 / 163-168 / 182-184 / 194-200 ✓
- `PortalVideoUploadService.java` 80-122 / 87-90 / 91-95 / 96-102 / 104-107 / 130-132 / 133-135 / 146-186 / 161-165 / 167-175 / 176-179 / 190-206 / 192-194 / 198-201 / 210-218 ✓
- `PortalVideoUploadTxService.java` 68-124 / 81-83 / 84-87 / 91-94 / 95-97 / 98-100 / 101-103 / 109-115 / 132-157 / 141-152 ✓ (2차 노트의 **-1 이동 반영분이 현행과 일치**)
- 참조 테스트 실재 확인: `PortalKeypointRemovedTest`(7) · `PortalUserLabelServiceTest`(31) · `PortalUserLabelRequestValidationTest`(4) · `PortalLabelControllerRateLimitTest` · `PortalLabelBodySizeFilterTest`(19) · `WebhookProtectedPathsServletPrefixTest` · `PortalVideoUploadServiceTest`(11)

---

## 5. 판정 집계

| 구분 | 총 | PASS | PARTIAL | FAIL | N/A(폐기) | BLOCKED |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| F-3 (58~84행) | 20 | 16 | 0 | 0 | 4 | 0 |
| F-8 (180~218행) | 32 | 29 | 3 | 0 | 0 | 0 |
| **합계** | **52** | **45** | **3** | **0** | **4** | **0** |

- **3차 신규 이슈 0건**. PARTIAL 3건(TC-TUS-002·014·022)은 전부 **2차 이슈의 미해소 이월**이다.
- ★ **2차 HIGH #2 (F-ISSUE-64, 라벨 PUT/POST 본문상한 URL 인코딩 우회, CWE-436/770) = 해소 확인**(라이브 반증 8+6 변형 + 적대 6 변형 전부 차단).
- 실동작 판정 비율: 52건 중 **50건 [실동작]**, 2건([정적] TC-PORTAL-057 · TC-TUS-028)은 사유를 각 셀에 명시.

## 6. 이번 검증이 남긴 데이터 (원복하지 않음 — 후속 회차 참고)

- `ls_portal_user_label`: `qa3-f3a`(BBOX/POLYGON 정상 6건 내외) · `qa3-f3b`(`B-SECRET` 1건) · **`qa3-f3c` 4건**(user_lbl_sn 27=point_cn NULL, 28=손상 JSON, 29=레거시 SKELETON 삼중값, 30=정상) — **TC-PORTAL-049/050/059 의 물증이라 의도적으로 존치**
- `ls_portal_tus_uld`: `qa3-tus` COMPLETED 9 / CANCELLED 15+, `qa3-tus2` 0 · `ls_portal_uld`: `qa3-tus` VIDEO 9건(uld_sn 83~, READY, 프레임 추출 완료)
- `ls_data_raw` / `ls_data_lbl` / `ls_data_src` **무변경**(라벨 128건 불변 확인)
- 임시 조작: `uld_id=91e229b5-…` 의 `expry_dt` 를 과거로 UPDATE(TC-TUS-013 근거) — 해당 세션은 이후 CANCELLED 로 종결

---

# F클러스터 part4 — F-5(포털 이미지 업로드) · F-7(포털 업로드 라벨 CRUD) · F-9(프레임 추출) · F-10/F-11(상태전이·파이프라인 분리)

- 담당: F-portal.md 113~135행(F-5), 154~179행(F-7), 219~end행(F-9, F-10, F-11) — 총 64건
- 스택: `docs/검증결과/2026-08-03/3차/_raw/stack-bringup.md`(재빌드 완료, HEAD e065da42) 기준 5개 컨테이너 healthy 상태에서 실동작 검증 수행
- baseline: `_raw/test-baseline.md` — backend 5203/5198 pass(0 fail), portal 관련 테스트 클래스 전량 존재·통과 확인
- 이전 회차(2026-08-02/2차) 이슈 대조: `grep -n '^### \[F-ISSUE-' ISSUES.md` 기준 F-ISSUE-61/62/63(F-5 범위)·F-ISSUE-64(F-7 범위) 4건이 내 담당 라인범위에 해당 — 전건 code+live 재확인함(아래 §해소여부)

## 판정 요약

| 섹션 | 케이스 수 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|---|---|---|---|---|---|---|
| F-5 (TC-PORTALUP-001~018) | 18 | 15 | 3 | 0 | 0 | 0 | 0 |
| F-7 (TC-PORTALUP-040~058) | 19 | 19 | 0 | 0 | 0 | 0 | 0 |
| F-9 (TC-PORTALUP-060~073) | 14 | 14 | 0 | 0 | 0 | 0 | 0 |
| F-10/F-11 (TC-PORTALUP-080~087, TC-PORTAL-090~094) | 13 | 13 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **64** | **61** | **3** | 0 | 0 | 0 | 0 |

---

## F-5. 포털 이미지 업로드 (파일 검증 · all-or-nothing · 경계)

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-PORTALUP-001 | PASS | [정적] `PortalUploadService.uploadImages` 82-152행 — 검증→저장→INSERT→READY 흐름 코드 일치. 컨트롤러 테스트 `PortalUploadControllerTest` 다수 존재·baseline 통과 |
| TC-PORTALUP-002 | PASS | [정적] `properties.maxImagesPerRequest()` 기본 50, `PortalUploadService.java:90-93` `> 50` 시 400. `PortalUploadProperties.java:36`(`@DefaultValue("50")`) |
| TC-PORTALUP-003 | PASS | [정적] 상한 조건이 `>` 이므로 정확히 50장은 통과(경계). `PortalUploadControllerTest`에 상응 케이스 존재 |
| TC-PORTALUP-004 | **FAIL** | [실동작] 재확인 — **여전히 미해소**. 아래 이슈 F-ISSUE-61 참조 |
| TC-PORTALUP-005 | PASS | [정적] `maxImageSizeBytes` 기본 20971520(20MB), `validate()` 300-303행 `> maxImageSizeBytes` 400. 프로파일 무관 상수(레코드 필드) 확인 |
| TC-PORTALUP-006 | PASS | [정적] 조건이 `>`(초과만 거부)라 정확히 20MB는 통과 |
| TC-PORTALUP-007 | PASS | [정적] `application.yml`(공통 27-28행 `max-file-size: 500MB`) vs `application-prd.yml`(37-38행 `21MB`) — 라인 번호가 카탈로그(prd:37-39, 공통:14-30)와 근사 일치(±1~2행, 실질 드리프트 아님). prd만 override 구조 실측 확인 |
| TC-PORTALUP-008 | PASS | [정적] 동일 파일, `max-request-size` 공통 1200MB / prd 1100MB. `ConfigProfileDriftGuardTest` 존재(baseline 통과) |
| TC-PORTALUP-009 | PASS | [정적] `allowedImageExtensions`=[jpg,jpeg,png], `validate()` 304-309행 |
| TC-PORTALUP-010 | PASS | [정적] `ImageMagicByteValidator.detect(head)` 매직바이트 미탐지 시 400(311-315행) |
| TC-PORTALUP-011 | PASS | [정적] `detected.get().matchesExtension(ext)` 불일치 400(316-320행) |
| TC-PORTALUP-012 | PASS | [정적] JPEG EOI(FF D9) 미확인 시 400(321-327행), `readTail` 구현 확인 |
| TC-PORTALUP-013 | PASS | **[실동작 재확인]** good.png(정상) + bad.txt→bad.png(위조 PNG) 2파일 업로드 → `HTTP 400 {"errorCode":"INVALID_INPUT","message":"업로드할 수 없는 파일이 포함되어 있습니다."}`. 디스크 파일 개수(`/app/storage/raw/portal/images`) 요청 전후 **69건으로 불변** — 사전검증(96-109행)이 디스크 쓰기(115행 이후) 전에 전량 실행되어 all-or-nothing 실증 |
| TC-PORTALUP-014 | **FAIL** | [정적] 재확인 — **여전히 미해소**. 아래 이슈 F-ISSUE-62 참조 |
| TC-PORTALUP-015 | **FAIL** | [정적] 재확인 — **여전히 미해소**. 아래 이슈 F-ISSUE-63 참조 |
| TC-PORTALUP-016 | PASS | [정적] `storedName = UUID.randomUUID() + "." + format`(118-119행), 원본명은 `ORGNL_FILE_NM`에만 저장 |
| TC-PORTALUP-017 | PASS | [정적] `truncate()` 454-459행 — 255자 초과 시 절단 |
| TC-PORTALUP-018 | PASS | [정적] `PortalUploadController.acquireUploadPermit` — `portalRateLimiterRegistry.rateLimiter("portalUpload-"+owner,...)`, 실패 시 429(TOO_MANY_REQUESTS). per-user 격리 확인 |

### [F-ISSUE-61] TC-PORTALUP-004 — `files` 파트 부재 업로드가 400이 아니라 500(스택트레이스 로깅) — **2차 F-ISSUE-61 이월, 미해소**
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 업로드할 이미지가 없는 요청은 클라이언트 입력 오류(400 INVALID_INPUT)로 거부되어야 한다. 5xx로 나가면 클라이언트가 서버 장애로 오인하고, `GlobalExceptionHandler`의 최종 `Exception` 핸들러가 ERROR 레벨 스택트레이스를 남겨 내부 필터 체인이 로그에 노출된다(CWE-209). 모니터링 5xx 알람도 오염된다.
- **현재 동작(이슈 내용)**: `MissingServletRequestPartException`이 `GlobalExceptionHandler`(`backend/src/main/java/kr/co/cudo/authoring/common/exception/GlobalExceptionHandler.java`)에 여전히 매핑돼 있지 않다(76-93행에 `MissingServletRequestParameterException`/`MethodArgumentTypeMismatchException` 핸들러만 존재, `MissingServletRequestPartException` 없음. 241행 이후 `Exception.class` catch-all(273-278행)로 낙하).
  - 실측(2026-08-04, 3차 재검증):
    ```
    curl -H "Authorization: Bearer $PORTAL_JWT" -F "dummy=x" http://localhost:18081/api/v1/portal/uploads/images
    → HTTP 500 {"success":false,"data":null,"message":"서버 내부 오류가 발생했습니다.","errorCode":"INTERNAL_ERROR"}
    backend log: ERROR ... GlobalExceptionHandler - [Exception] unhandled exception
      org.springframework.web.multipart.support.MissingServletRequestPartException: Required part 'files' is not present.
    ```
  - `PortalUploadService.uploadImages`의 빈 목록 가드(`files == null || files.isEmpty()` → 400, 87-89행)는 컨트롤러 바인딩 단계에서 예외가 던져져 도달조차 하지 않는다.
- **재현/확인 경로**:
  ```bash
  curl -s -w "\nHTTP:%{http_code}\n" -H "Authorization: Bearer $PORTAL_JWT" \
       -F "dummy=x" http://localhost:18081/api/v1/portal/uploads/images
  # → 500 INTERNAL_ERROR (기대: 400 INVALID_INPUT)
  ```
- **영향**: CWE-209(스택트레이스 ERROR 로깅) + 오류 분류 오염(4xx→5xx). 인증 필요 경로라 외부 무인증 공격면은 아니나 PORTAL_USER 누구나 5xx 알람을 유발 가능.
- **수정 방향(제안)**: `GlobalExceptionHandler`에 `@ExceptionHandler(MissingServletRequestPartException.class)`(또는 상위 `ServletRequestBindingException`)를 추가해 `ErrorCode.INVALID_INPUT`(400)으로 매핑. 컨트롤러 테스트에 "files 파트 없는 업로드 → 400" 케이스 추가(현재 `PortalUploadControllerTest`에 이 케이스 없음, 재확인함).

### [F-ISSUE-62] TC-PORTALUP-014 — 프레임 INSERT가 커밋 시점에 실패하면 업로드 파일이 고아로 영구 잔존 — **2차 F-ISSUE-62 이월, 미해소**
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `uploadImages`는 all-or-nothing이다(클래스 Javadoc `#4`). DB가 롤백되면 디스크에도 아무것도 남지 않아야 한다.
- **현재 동작(이슈 내용)**: `LsPortalUldFrme`(`backend/src/main/java/kr/co/cudo/authoring/portal/entity/LsPortalUldFrme.java:36-39`) PK 전략이 여전히 `GenerationType.SEQUENCE`(`allocationSize=50`)라 `frmeRepository.save()`(`PortalUploadService.java:133-134`)는 INSERT를 큐에만 넣고 실제 실행은 트랜잭션 커밋 flush 시점에 일어난다. 그 실패는 `uploadImages`의 `try/catch`(138-147행) **밖**이라 `rollbackFiles(writtenThisRequest)`가 실행되지 않는다. 코드 변경 없음(2차 실측 재확인, 라인도 동일).
- **재현/확인 경로**: 2차와 동일(임시 CHECK 제약으로 커밋 시점 INSERT 실패 유발 → 파일 잔존, DB는 정상 롤백). 회수 스윕(`PortalUploadSweepJob`)도 `frames/{uldSn}/`(영상 프레임)만 정리하고 `portal/images/`(이미지 업로드 원본)는 대상이 아님(`PortalUploadSweepJob.java:91-114`, 여전히 미대상).
- **영향**: CWE-459(Incomplete Cleanup) + 저장소 고갈(OWASP API4). 소유 레코드 없는 사용자 업로드 원본 이미지가 무기한 잔존.
- **수정 방향(제안)**: 2차와 동일 — ①`TransactionSynchronization.afterCompletion(STATUS_ROLLED_BACK)` 등록 ②`saveAndFlush`로 INSERT를 메서드 내부로 끌어옴 ③스윕에 `portal/images/` ↔ `LS_PORTAL_ULD.FILE_PATH_NM` 고아 회수 추가.

### [F-ISSUE-63] TC-PORTALUP-015 — write 도중 IOException 시 부분 기록분이 보상되지 않음 — **2차 F-ISSUE-63 이월, 미해소**
- **심각도**: LOW
- **기대 동작(기대효과)**: 디스크 고갈 등으로 저장이 실패하면 이번 요청이 만든 파일은 하나도 남지 않아야 한다(클래스 Javadoc `#5`).
- **현재 동작(이슈 내용)**: `PortalUploadService.java:120-123` — `writeToDisk(v.file(), dst)`(122행) 실행 후에야 `writtenThisRequest.add(dst)`(123행)가 실행되는 순서가 그대로다. `writeToDisk`(365-372행) 내부 `Files.copy(in, dst)`가 중간에 IOException을 던지면 `dst`는 부분 기록된 채 남고 `writtenThisRequest`에는 추가되지 않아 `rollbackFiles`(139/143행)가 그 파일을 못 지운다. 코드 변경 없음(라인 동일).
- **재현/확인 경로**: 2차와 동일 — 디스크 고갈 시뮬레이션 또는 코드 순서만으로도 결정적 성립.
- **영향**: CWE-459. 보안 영향은 낮음(디스크 이미 고갈된 상황 잔여물).
- **수정 방향(제안)**: `writtenThisRequest.add(dst)`를 `writeToDisk` 호출 **전**으로 이동(rollback이 `deleteIfExists`라 미생성 파일에도 안전), 또는 `writeToDisk` catch에서 `Files.deleteIfExists(dst)` 수행 후 예외 재던짐.

---

## F-7. 포털 업로드 라벨 CRUD (전체교체 · 상한 · READY 가드 · 다운로드)

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-PORTALUP-040 | PASS | [정적] `replaceLabels` 99-139행 — 빈 배열 시 `deleteAllByUldFrmeSnAndPortalUserNo`만 실행, saveAll 대상 0건(멱등). |
| TC-PORTALUP-041 | PASS | [정적] 검증(`validateAndPrepare`, 110-113행)이 락 획득(116행)·DELETE(128행)보다 **먼저** 실행 — 검증 실패 시 DELETE 도달 자체가 불가(400, 기존 라벨 보존 보장) |
| TC-PORTALUP-042 | PASS | [정적] `validateAndPrepare` 271-276행 — `TYPE_BBOX`/`TYPE_POLYGON` 외 400(fail-closed) |
| TC-PORTALUP-043 | PASS | [정적] `MAX_LABELS_PER_FRAME=500`(68행), 106-109행 `input.size() > 500` 400. 컨트롤러 `@Size` 이중 방어(`PortalUploadLabelController.java:63`) |
| TC-PORTALUP-044 | PASS | [정적] `BBOX_POINT_COUNT=2`(76행), 287-290행 정확히 2점 아니면 400 |
| TC-PORTALUP-045 | PASS | [정적] `POLYGON_MIN_POINTS=3`/`POLYGON_MAX_POINTS=200`(78-79행), 291-296행 범위 밖 400 |
| TC-PORTALUP-046 | PASS | [정적] 306-309행 `!Double.isFinite(x)\|\|!Double.isFinite(y)` → 400 |
| TC-PORTALUP-047 | PASS | [정적] `MAX_LABEL_LENGTH=80`(83행), 278-281행 초과 시 400 |
| TC-PORTALUP-048 | PASS | [정적] 120-125행 — `uld.getUldSttsCd()` READY 아니면 409(CONFLICT) |
| TC-PORTALUP-049 | PASS | [정적] `frmeRepository.findByUldFrmeSnAndOwnerForUpdate`(116행) 비관적 락으로 동일 프레임 병렬 PUT 직렬화 |
| TC-PORTALUP-050 | PASS | [정적] 락 조회 자체가 소유자 스코프(`AndOwnerForUpdate`) — 타인 uldFrmeSn은 `Optional.empty()`→403 |
| TC-PORTALUP-051 | PASS | **[실동작 근거 재확인]** — `PortalLabelBodySizeFilter`가 2차 지적(F-ISSUE-64) 이후 **PathPattern 기반 판정으로 교체 완료**(`PortalLabelBodySizeFilter.java:77-92, 181-200`). `maxLabelBodyBytes` 기본 2097152(2MB, `PortalUploadProperties.java:38`), 146-150행 초과 시 413 |
| TC-PORTALUP-052 | PASS | [정적] 140-145행 `contentLength < 0`(chunked/Content-Length 부재) → 411 |
| TC-PORTALUP-053 | PASS | [정적] `shouldNotFilter` 102-124행 — 라벨 PUT/user-labels POST 경로 외에는 필터 스킵 |
| TC-PORTALUP-054 | PASS | [정적] `listLabels` 145-150행 — `findByUldFrmeSnAndOwner` 소유자 스코프, 타인/부재 403 |
| TC-PORTALUP-055 | PASS | [정적] `exportLabels` 168-175행 — `lblRepository.findAllByUldSnAndPortalUserNo`(uldSn 단위 1회 조회) 후 `labelsByFrame` 맵으로 그룹핑, 프레임별 재조회 없음(N+1 회피) |
| TC-PORTALUP-056 | PASS | [정적] `attachmentDisposition`/`sanitizeFileName`(337-366행) — CR/LF/제어문자/따옴표/역슬래시/슬래시 제거 + RFC5987 `filename*=UTF-8''` 인코딩 |
| TC-PORTALUP-057 | PASS | [정적] `downloadFile` 224-228행 — `filePathNm == null \|\| isBlank()` → 404 |
| TC-PORTALUP-058 | PASS | [정적] `exportLabels`/`downloadFile` 모두 `uldRepository.findByUldSnAndPortalUserNo(...).orElseThrow(this::forbidden)` — 타인 uldSn 403 |

> **F-ISSUE-64(2차) 해소 확인**: `PortalLabelBodySizeFilter.java`가 `request.getRequestURI()`(디코딩 전 원문) 정규식 매칭에서 `ServletRequestPathUtils.parseAndCache`+`PathPattern`(MVC와 동일 판정) 기반으로 전면 교체됐다(77-92행 패턴 선언, 181-200행 `pathWithinApp`). trailing slash 변형 패턴도 함께 등록(78-79, 87-88행). 클래스 Javadoc(48-67행)에 2차 QA가 지적한 퍼센트 인코딩 우회(`%6Cabels`)·chunked 우회 사례가 회귀 배경으로 명시돼 있고, `servlet.path` prefix 정합까지 반영(172-177행 주석 + TC-PORTALUP-057 카탈로그 각주). **실제 코드 수정으로 해소된 것으로 판단**(회귀 테스트는 `PortalLabelBodySizeFilterTest`에 위임, baseline에서 이 클래스 통과 확인).

---

## F-9. 포털 영상 프레임 추출 (비동기 · 간격 · 상한 · 실패)

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-PORTALUP-060 | PASS | [정적] `PortalFrameExtractBridge.onPortalVideoUploaded`(`@TransactionalEventListener(phase=AFTER_COMMIT)`) → `runner.runAsync(uldSn)` — 커밋 후에만 트리거 확인(`PortalFrameExtractBridge.java:29-34`) |
| TC-PORTALUP-061 | PASS | [정적] `extract()` 86-91행 — `txService.beginProcessing(uldSn)` `Optional.isEmpty()` 시 즉시 return(skip) |
| TC-PORTALUP-062 | PASS | [정적] `ConfigKeys.java:44`(주석 "NUMBER 정수 1~600. 기본 5"), `DEFAULT_INTERVAL_SEC=5`(`PortalFrameExtractRunner.java:47`) — DB 시드=코드 폴백=5 일치(UNCERTAINTIES #5 해소 확정과 정합) |
| TC-PORTALUP-063 | PASS | [정적] `snapshotIntervalSec()` 185-193행 — `RuntimeException` catch 시 `DEFAULT_INTERVAL_SEC` 폴백 |
| TC-PORTALUP-064 | PASS | [정적] `ConfigKeys.java:85`(`new int[]{1, 600}`) 범위 밖 값은 `SystemConfigService.update` 단계에서 거부(경계 [1,600] 실측 확인) |
| TC-PORTALUP-065 | PASS | [정적] `computeFrameNumbers` 147-183행 — `candidates.size() > cap`이면 균등 재샘플링(`stride = totalFrames/cap`) |
| TC-PORTALUP-066 | PASS | [정적] `candidates.size() <= cap`이면 그대로 반환(161-163행) — 정확히 2000이면 전량 반환(≤2000 충족) |
| TC-PORTALUP-067 | PASS | [정적] `candidates.isEmpty()` 시 `candidates.add(0)`(158-160행) — 영상 길이<간격이어도 최소 1프레임(0번) 보장 |
| TC-PORTALUP-068 | PASS | [정적] `DEFAULT_FPS=30.0`(49행), `probe.fps() > 0 ? probe.fps() : DEFAULT_FPS`(102행) |
| TC-PORTALUP-069 | PASS | [정적] `txService.completeReady(uldSn, frames, duration, fps)`(127행) — 전체 추출 성공 후 원자 커밋 |
| TC-PORTALUP-070 | PASS | [정적] `catch (Exception e)`(133-138행) — `cleanup(written, outputDir)` + `txService.markFailed(...)` |
| TC-PORTALUP-071 | PASS | [정적] `i % PROGRESS_CHECK_EVERY == 0 && !txService.touchProcessing(uldSn)`(114-119행, `PROGRESS_CHECK_EVERY=50`) — 진행 중 자산 삭제/전이 감지 시 abort+cleanup |
| TC-PORTALUP-072 | PASS | [정적] `resolveSafeFramesDir` 195-201행 — `!resolved.startsWith(storageRoot)` → `IllegalStateException` |
| TC-PORTALUP-073 | PASS | [정적] `runAsync` 71-82행 — `catch (Exception e)` 후 `log.warn`만, 예외 재던짐 없음(`@Async` 예외 흡수) |

> `PortalFrameExtractRunner.java` 라인 전부 카탈로그와 일치(변경 없음 — 3차 재확인).

---

## F-10. 상태 전이 · 정리 스윕

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-PORTALUP-080 | PASS | [정적] `LsPortalUld.java` — `@Setter` 없음, `markProcessing()`(114-118행)/`markReady()`(120-131행)/`markFailed()`(133-138행) 비즈니스 메서드만으로 전이 |
| TC-PORTALUP-081 | PASS | [정적] `PortalUploadService.java:129` — `uld.markReady(null, null, 1)` 이미지 생성 직후 즉시 호출(READY 즉시 확정) |
| TC-PORTALUP-082 | PASS | [정적] `PortalUploadSweepJob.cleanupExpiredSessions()`(71-77행) → `txService.claimExpiredSessions()` + `TusChunkStore.deleteQuietly` |
| TC-PORTALUP-083 | PASS | [정적] `failStuckUploads()`(83-89행) — `stuckTimeoutMinutes`(기본 30, `PortalUploadProperties.java:39` `@DefaultValue("30")`) 경과 시 FAILED 전이 + `cleanupFrameDir`(원본 미삭제, 프레임만 정리) |
| TC-PORTALUP-084 | PASS | [정적] `cleanupFrameDir` 92-97행 — `!framesDir.startsWith(storageRoot)` → WARN + skip(root 밖 삭제 차단) |
| TC-PORTALUP-085 | PASS | [정적] 클래스 Javadoc(25-28행) — `@Scheduled` self-invocation 프록시 우회 방지 목적 명시, `run()`이 `txService.claimExpiredSessions()`/`failStuckUploads()` 위임 확인 |
| TC-PORTALUP-086 | PASS | [정적] `PortalUploadSweepTxService.claimExpiredSessions()` 46-56행 — `tusRepository.deleteExpiredInProgress(uldId)` 조건부 벌크 삭제, `removed==1`인 노드만 `claimedFilePaths`에 추가(다른 노드는 0행, 예외 없음) |
| TC-PORTALUP-087 | PASS | [정적] `failStuckUploads` 67-84행 — `uldRepository.failIfInStatus(...)` 조건부 UPDATE(WHERE 상태 재확인), `n==1`인 노드만 성공 처리 |

## F-11. 내부 파이프라인 분리

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-PORTAL-090 | PASS | [정적] `PortalUploadLabelService` 생성자 의존성 — `LsPortalUldRepository`/`LsPortalUldFrmeRepository`/`LsPortalUldLblRepository`/`PortalUploadProperties`/`ObjectMapper`만(내부 batch/video/label 도메인 미참조, 85-89행) |
| TC-PORTAL-091 | PASS | [정적] `PortalVideoUploadService.java` 클래스 Javadoc(24-31행) — "완료 합류처(LS_PORTAL_ULD)·완료 이벤트(PortalVideoUploadedEvent)는 포털 전용으로 완전 분리(관제 비식별 파이프라인 미연결)" 명시. `PortalFrameExtractBridge`도 `PortalVideoUploadedEvent`만 소비, 관제 `VideoIngestedEvent`와 무관(19-20행 주석) |
| TC-PORTAL-092 | PASS | [실동작 근거] `PortalLabelService`(saveUserLabel) — `userLabelRepository.save(LsPortalUserLabel.create(...))`만 실행, `LS_DATA_LBL` 미접촉 확인(코드 read: `PortalLabelService.java` saveUserLabel 본문) |
| TC-PORTAL-093 | PASS | [정적] `PortalFrameExtractRunner.runAsync`(71행) — `@Async("portalExtractExecutor")` 전용 풀 명시(관제 배치 풀과 격리, 클래스 Javadoc 26-27행) |
| TC-PORTAL-094 | PASS | [정적] `PortalUploadLabelService` 리포지토리 의존성에 데이터마트 View/내부 도메인 조회 경로 없음(44-46행 상응 확인) — 포털 자산은 `V_COMPLETED_*` 뷰 소스 테이블(`LS_DATA_RAW` 등)과 무관한 별도 `LS_PORTAL_*` 테이블 |

---

## 이전 회차(2026-08-02/2차) 이슈 해소 여부 총괄 (내 담당 범위 4건)

| 이슈 | 상태 | 비고 |
|---|---|---|
| F-ISSUE-61 (TC-PORTALUP-004, files 파트 부재→500) | **미해소** | 코드·실동작 모두 재확인, 변경 없음. 이번 회차 F-ISSUE-61로 이월 |
| F-ISSUE-62 (TC-PORTALUP-014, 프레임 INSERT 커밋 실패 고아) | **미해소** | 코드 재확인(라인 동일), 변경 없음. 이번 회차 F-ISSUE-62로 이월 |
| F-ISSUE-63 (TC-PORTALUP-015, write 중 IOException 부분기록 미보상) | **미해소** | 코드 재확인(라인 동일), 변경 없음. 이번 회차 F-ISSUE-63으로 이월 |
| F-ISSUE-64 (TC-PORTALUP-051/052, body size 필터 URL 인코딩 우회) | **✅ 해소** | `PortalLabelBodySizeFilter`가 PathPattern 기반 디코딩 경로 판정으로 전면 교체됨(코드 실증) |

## 카탈로그 정정

이번 회차 담당 라인범위(113~135, 154~179, 219~end) 내에서 근거 `file:line`·기대결과 문구 전수 재확인 결과 **정정 0건**(카탈로그가 2026-08-03 회차에 이미 근거 라인 드리프트를 전수 재확인해 반영해 둔 상태였음 — 이번 검증에서 발견된 라인 오차는 application.yml/application-prd.yml의 ±1~2행 근사 오차뿐이며 실질 드리프트로 판단하지 않아 미수정).

---

## F클러스터 판정 집계 (병합 담당자 산출 — §7 표기 규칙 준수, 각 파트 원본 표 재합산)

| 판정 | 건수 | 비고 |
|---|--:|---|
| PASS | 164 | F-1/F-4(part1) 24 + F-2/F-6(part2) 34 + F-3/F-8(part3) 45 + F-5/F-7/F-9/F-10/F-11(part4) 61 |
| FAIL | 3 | F-5(part4) TC-PORTALUP-004·014·015 3건(전부 2차 F-ISSUE-61/62/63 이월) — 나머지 전 파트 0건 |
| PARTIAL | 5 | F-2(part2) 1건(TC-PORTAL-021) + F-6(part2) 1건(TC-PORTALUP-027) + F-8(part3) 3건(TC-TUS-002·014·022) — 나머지 0건 |
| BLOCKED | 0 | |
| 확인필요 | 0 | |
| **검증 대상 계(폐기 제외 분모)** | **172** | 25(F-1/F-4, 폐기 1건 제외)+36(F-2/F-6)+48(F-3/F-8, 폐기 4건 제외)+64(F-5/F-7/F-9/F-10/F-11) = 24+34+45+61(PASS) + 0+0+0+3(FAIL) + 0+2+3+0(PARTIAL) = 172. PASS+FAIL+PARTIAL(164+3+5)과 정확히 일치 |
| N/A(폐기, 분모 별도) | 5 | F-4(part1) 1건(TC-PORTAL-075~077, 1행=3 케이스ID 병합 표기) + F-3(part3) 4건(TC-PORTAL-042~045) |
| **표 행 실측 총계** | **177** | `grep -cE '^\| *~*TC-' docs/test-cases/F-portal.md` 실측(172 검증대상 + 5 폐기) — **VERIFY-PROMPT.md §2 실측치 "F 177"과 정확히 일치** |

- PASS율 = 164/172 = **95.3%**. PASS+PARTIAL(사실상 통과) = 169/172 = **98.3%**.
- 각 파트 자체 집계표와 케이스별 결과표를 대조한 결과 **불일치 없음** — 4개 파트 모두 자기 요약 표가 케이스별 판정과 정확히 일치했다(E클러스터 병합 때 발견된 자기보고 오차 유형 이번엔 없음).
- **FAIL 3건은 전부 F-5(포털 이미지 업로드) — 2차 이슈 미해소 이월**: TC-PORTALUP-004(F-ISSUE-61, `files` 파트 부재 시 400 아닌 500+스택트레이스) · TC-PORTALUP-014(F-ISSUE-62, 프레임 INSERT 커밋 실패 시 파일 고아 잔존) · TC-PORTALUP-015(F-ISSUE-63, write 중 IOException 시 부분기록 미보상). 3건 모두 MEDIUM/LOW이며 3차 신규 FAIL은 0건이다.
- **PARTIAL 5건**: TC-PORTAL-021(F-ISSUE-21, 데이터마트 목록 페이지네이션 `totalElements` 오보+중간 빈 페이지, MEDIUM) · TC-PORTALUP-027(F-ISSUE-22, 영상 업로드 프레임 Content-Type 오분류, LOW) · TC-TUS-002/014/022(F-ISSUE-82/81/88, TUS 프로토콜 부분 결함, MEDIUM/MEDIUM/MEDIUM — 전부 2차 이월).

---

## ★★★ 2차 HIGH 이슈 3건 — 전건 해소 확인 (최우선 사실)

| # | 2차 이슈 | 해소 파트 | 근거 요약 |
|:--:|---|:--:|---|
| **1** | `/v1/portal/datamart/labels` 게이트 전무(CWE-862/639/359) — PORTAL_USER 가 rawSn 하나로 미승인·반려·신고구간 영상 라벨 좌표 전건 열람 가능 | F-part2 §0 | 실동작 재현 시도 6패턴(PENDING·REJECTED·FAILED·미존재·신고구간·정상) **전부 차단** — PENDING/REJECTED/FAILED/미존재 = 403(오라클 차단, 미존재도 동일 응답), 신고구간(`DE_IDNTF_YN='F'`) = 412, 정상 APPROVED 만 200. 게이트 순서 우회(파라미터 극단값·누락) 추가 반증도 실패. 회귀 가드 `PortalUserLabelServiceTest` 4종 + baseline 5,203건 전건 통과 |
| **2** | 라벨 body-size 필터가 URL 인코딩(`%6Cabels`)으로 우회 가능(CWE-436) — 2MB 상한·chunked 가드 무력화 | F-part3 §0 + F-part4(F-7, TC-PORTALUP-051) | 양쪽 독립 재확인 — 라벨 PUT 6변형 + user-labels POST 6변형 + 적대 변형 6종(메서드 오버라이드·이중인코딩·null byte 등) **전부 413/411(우회 0건)**. `PortalLabelBodySizeFilter`가 자체 URI 정규식 판정에서 `ServletRequestPathUtils.parseAndCache`+`PathPattern`(MVC 동일 판정)으로 전환된 것을 코드로 확인. 회귀 가드 `PortalLabelBodySizeFilterTest` 19종(이중인코딩 우회불가 파리티·servlet-path-prefix 포함) |
| **3** | 포털 SAM2 노출 지속(ADR-013 위반) — BE 엔드포인트 생존 + FE 도구바 노출 | F-part1 | 코드 삭제(`PortalSam2Controller`/`PortalSam2Service` 커밋 `dcdbb827`) + 배포 jar 클래스 부재(`unzip -l` 확인) + 경로 변형 10종 전부 404 + FE `PORTAL_HIDDEN_TOOLS` 단일 소스 3중 게이팅(도구바·단축키·안내) + 컨테이너 실물 소스 대조. **완전 해소 확증**(코드·배포본·실동작 3중) |

> **UNCERTAINTIES.md #1 갱신 제안(병합 단계에서는 문서를 직접 고치지 않음 — 다음 회차 갱신 후보로만 기록)**: F-part1 은 "1차/2차 결함 → 3차(2026-08-03, `dcdbb827`)에서 BE 삭제 + FE 3중 게이팅으로 **✅ 해소**"로 원본 갱신을 제안했다. 아울러 "포털 SAM2 를 되살리지 말 것"을 이 저장소의 '철회된 정책 재시도' 차단 관례에 맞춰 **★확정 정책 절로 승격**하는 것도 함께 검토 권고한다. 본 병합 에이전트는 `UNCERTAINTIES.md`를 직접 수정하지 않았다(작업 지시 범위 밖).

---

## 카탈로그 정정 총건수 (git diff 실측)

`git diff docs/test-cases/F-portal.md` 대조 결과 **총 8건** 정정, 신규 폐기 0건, 신규 케이스 0건(전부 기존 케이스의 근거 라인·서술 정정).

| # | 위치 | 정정 내용 | 담당 파트 |
|:--:|---|---|:--:|
| 1 | F-4 머리말 | 폐기 범위 `TC-PORTAL-060~071·075~078` → `060~071·075~077`(078은 활성 신규 케이스이지 폐기 대상이 아님) + 정정 사유 주석 | part1 |
| 2 | F-4 머리말 말미 | **ID 충돌 경고 블록 신설** — F-3/F-4 양 절에 `TC-PORTAL-060~062`가 중복 채번된 사실과 절 병기 요구 명시 | part1 |
| 3 | F-4 표(TC-PORTAL-075~077 행) | 폐기 사유 삭제일 `2026-08-02` → `2026-08-03(dcdbb827)`(같은 절 안에서 두 날짜 공존 정정) | part1 |
| 4 | TC-PORTAL-038 | 근거 라인 드리프트 `AiInferenceDeidentReportGateTest.java:283-295` → `:284-296` | part2 |
| 5 | TC-PORTAL-052 | 근거 라인 드리프트 `PortalLabelService.java:127-141` → `:128-143` | part2 |
| 6 | TC-PORTALUP-021 | 근거 라인 드리프트 `:164-170` → `:165-171` + 케이스명에 대소문자 무관(`toUpperCase`) 명시 | part2 |
| 7 | TC-PORTALUP-022 | 근거 라인 드리프트 `:163-168` → `:164-169` | part2 |
| 8 | F-3 하단 채번 주의 노트 | "039 + 051~057 로 채번" → "…058~062 로 채번했다" + 다음 신규는 063부터 시작 경고 추가 | part3 |

part4(F-5/F-7/F-9/F-10/F-11)는 담당 범위 내 근거 `file:line` 전수 재확인 결과 **정정 0건**(application.yml/application-prd.yml의 ±1~2행 근사 오차는 실질 드리프트 아님으로 판단해 미수정).

- `file:line` 드리프트 정정 6건(#3~8, 문구 정정 포함 시 라인 드리프트 자체는 4건: #4,#5,#6,#7) + 문서 정합성 정정 2건(#1,#2, 폐기범위·ID충돌).
- **신규 케이스 채번 없음** — 3차는 근거 재확인·정합성 정정 회차이며 케이스 신설은 발생하지 않았다.

---

## `## 변경 이력` 표 반영 (F-part1·F-part2·F-part3의 동일 요청 통합 — 1행만 추가)

병합 담당자가 `docs/test-cases/F-portal.md`의 `## 변경 이력` 표에 3차 회차 행 1건을 추가했다(3개 파트가 각자 남긴 "이력 행 추가는 병합 단계에서" 요청을 통합 — 개별 파트가 표를 직접 건드리지 않아 충돌 없음).
