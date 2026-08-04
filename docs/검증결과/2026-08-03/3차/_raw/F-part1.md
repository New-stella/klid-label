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
