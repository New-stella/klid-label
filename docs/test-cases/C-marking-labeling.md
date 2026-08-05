# C. 마킹 + 라벨링 — 테스트 케이스

> **299 케이스**(표 행 실측 — **폐기 행 포함**, 행을 지우지 않으므로. 변경 이력·현황 요약 표는 제외) · 계층: unit / integration / security · 우선순위 P0(Critical)~P2 · [← README](README.md) ※ 카운트 = `grep -cE '^\| ~*TC-'`(ID 취소선 폐기 행 포함, 2026-08-05 머지 회차 7 정정 · 회차 11 에서 294→299, 라벨 이력 작성자 표시명 계약변경 TC-LABEL-158~162 신설)

## 변경 이력

| 회차 | 일자 | 정정 | 신규 | 폐기 | 요약 |
|:--:|---|--:|--:|--:|---|
| 1 | 2026-07-30 | 212건 | 61건 | 2건 | 07-25 1차 검증 이후 Phase 4/6/7 + 좌표정책 일원화(6d1b3703)·라벨 보존 정책 반전(b0647c4c)·마킹 활성 1건(41b0504d)·`/deid-image` 신설(27977e72)·포털 SAM2 원본 전송 차단(3630558d) 반영. **비식별 신고 = 라벨 보존 + 조회 게이트 412**(구 "전량 삭제 + 스냅샷" 폐기), **AI 검출 좌표 = clamp/퇴화 스킵**(구 "음수 400 all-or-nothing" 폐기), **신고 게이트 판정 = 자기 rawSn 행 하나**(조상/자손 전파 도입 후 철회 — 재도입 금지). 근거 `file:line` **212건 전량 재확인**(그중 기대결과·전제가 실제로 바뀐 건 33건, 나머지는 라인 드리프트 정정). **신설 `GET /v1/frames/{srcSn}/deid-image` 7건 + `Cache-Control: no-store` C 소관 3경로**(TC-LABEL-141~149) 포함 |
| 2 | 2026-08-03 | 1건 | 0건 | 0건 | **결정 3 의 파급만 반영**(케이스 신설·폐기 없음, BE 무변경). 라벨링 화면의 라벨 선택 표면이 좌측 상시 패널 → **라벨 선택 모달**로 바뀌면서 목록 소스가 **라벨 마스터 전체**로 못 박혔다 → **C-6 절 머리말에 "프리셋은 오토라벨링 전용, 수동 라벨링 선택 목록 아님" 경계 명시**(FE 상세는 [H-3 하위 절](H-frontend-e2e.md) TC-FE-279~292) |
| 3 | 2026-08-03 | 78건 | 2건 | 0건 | **근거 `file:line` 전수 재확인 회차** — 273행 전량 대조. 라인 드리프트 57건 정정(마킹 15·라벨/오토라벨 8·비식별프레임서빙 9·SAM2 23·YOLO트랙 1 — 대부분 리팩터링·메서드 재배치로 인한 위치 이동, 동작 자체는 불변) + 기대결과 정정 1건(TC-SAM2-23: `Sam2TrackService` 의 ai 응답 폴리곤 검증은 `Sam2CoordinateValidator` 가 `INVALID_INPUT`(400)을 던지며 `EXTERNAL_API_ERROR`(502) 아님 — 구 기재 오류 정정, "정점부족" 조건은 이 경로에 없음도 명시). `Sam2TrackRequest.java`/`YoloTrackRequest.java` 동명이인 basename 정합(TC-SAM2-16~20 → `label/Sam2TrackRequest.java` 명시). `FrameImageController.java` 초과 라인(TC-LABEL-141/143/148) 은 리팩터링으로 판정이 `FrameImageService`/`FrameImageLookupService` 로 이동한 결과였음을 확인해 정정. **⊕ 같은 날 후속 — 코드 수정에 따른 재정정 2건 + 신규 2건**: ①`Sam2TrackService` 응답 폴리곤에 **최소 정점 수(3) 검증을 추가**(위반 502 `EXTERNAL_API_ERROR`) → 본 회차에서 적었던 *"정점부족 조건은 이 경로에 없음"* 은 **폐기**하고 TC-SAM2-23 을 "좌표 형식 축(400)" 으로 좁힘 + **TC-SAM2-34**(정점<3 → 502)·**TC-SAM2-35**(요청 축 1점 클릭 허용 / 요청 400 ↔ 응답 502 분리 회귀 가드) 신설. 규칙은 `Sam2CoordinateValidator.validateResponseMinPoints` 로 분리 — 공용 `validatePolygon` 에 넣으면 **SAM2 클릭 프롬프트(1점)가 400 으로 죽는다.** ②`Sam2SegmentService` 의 **미호출 dead code `resolveSafe` 삭제** + 없는 보호를 주장하던 클래스 javadoc 을 실제 보호 지점(`FrameImageEncoder` 위임)으로 정정 → TC-SAM2-06 재정정. 두 변경으로 `Sam2SegmentService`/`Sam2TrackService` 라인이 다시 이동해 SAM2 근거 18건 재대조 |
| 4 | 2026-08-03 | 68건 | 3건 | 0건 | **테스트케이스 전수 검증 3차 회차**(`docs/검증결과/2026-08-03/3차/`, 실행 2026-08-04 KST) — 6파트 병렬 실동작 검증(풀스택 curl+DB+로그) 후 병합. 근거 `file:line` 드리프트 정정 68건(마킹 1·라벨CRUD/신고 25·오토라벨 인접 0·SAM2 4·TRACK/PRESET 17·기타 21 — V163 영상 개인정보 메타 도입으로 `DeidentReportService` 등이 +20~30줄 밀린 것이 다수). **HIGH 신규 결함 2건**: `C-ISSUE-22`(라벨 저장 경로에 신고 게이트 없음 — 작업락 6h 만료 후 "조회 412 ↔ 저장 200" 비대칭, `items:[]` PUT 으로 전량 삭제 재현) · `C-ISSUE-61`(존재하지 않는 `id` 를 붙이면 좌표 경계 상한 400 검증이 통째로 우회 — 프레임 밖 좌표가 실제 DB 적재까지 재현). 회귀 가드 케이스 **3건 신설**: TC-LABEL-150(C-ISSUE-61/62 우회 재현, 현재 상태 FAIL) · TC-SAM2-36/37(track mock 게이트 정상 동작 확인, 1차 HIGH C-ISSUE-81 해소 실증). 케이스 수 **275 → 278**. 이슈 전문은 `docs/검증결과/2026-08-03/3차/ISSUES.md` "## C클러스터" 참조 |
| 5 | 2026-08-04 | 6건 | 1건 | 0건 | **3차 HIGH 결함 2건 수정 반영**(`C-ISSUE-22` · `C-ISSUE-61/62`, BE 변경 — `LabelService.bulkUpsert`). ①**라벨 저장 경로에 비식별 신고 게이트 추가** — `accessGuard.requireNotUnderDeidentReport` 를 **락 검사보다 먼저** 호출해 `DE_IDNTF_YN='F'` 면 **락 유무와 무관하게 412**(구 기재 "저장 경로에는 게이트가 없고 작업락이 유일 방어"는 **폐기**). TC-LABEL-12 정정 + **TC-LABEL-12a 신설**(빈 배열 저장 412 + 기존 라벨 보존). ②**신규/기존 판정 술어 단일화** — 사전검증이 `idIndex` 적재 이후로 이동하고 저장 분기와 같은 `isNewLabel` 을 공유해, 미존재 `id` 로 좌표 경계 상한·`MAX_POINTS` 상한을 우회하던 창이 닫힘 → **TC-LABEL-150 FAIL → PASS 로 정정**. 라인 드리프트 정정 TC-LABEL-113~117. ③**코드리뷰 보강분(같은 날 후속)** — `isNewLabel` 의 세 번째 동치클래스(**타 프레임에 실재하는 id**)와 **혼합 배열 원자성**이 미검증이라 회귀 가드 **2건 신설**(TC-LABEL-150a/150b). 케이스 수 **279 → 281**. 회귀 가드 실행: `LabelSaveGuardsIT` **18/18 PASS**(신규 2건은 `isNewLabel` 을 구 술어 `id==null` 로 되돌리면 FAIL 하는 것까지 확인 — 비어있는 가드 아님) |
| 6 | 2026-08-04 | 1건 | 3건 | 0건 | **3차 HIGH 결함 `B-ISSUE-42`(1차 `B-ISSUE-102` 이월) 수정 반영**(BE 변경 — `DeidentReportService.verifyDeidentArtifact`). 비식별 신고 resolve 의 mtime 시간조건이 `mtime.isAfter(reportTime.minusSeconds(60))` 라 **클럭스큐 관용이 감산 방향으로 열려 있었다** — 신고시각보다 최대 60초 <과거>인 파일, 즉 신고를 유발한 그 옛 비식별본까지 통과시켜 **재비식별 없이 게이트가 열렸다**(CWE-359: 라벨 조회·export·스트리밍이 한꺼번에 재개방). 상수 `CLOCK_SKEW_TOLERANCE_SECONDS` 를 **제거**하고 같은 메서드의 procLog 비교가 이미 쓰던 **엄격 비교 `mtime.isAfter(reportTime)`** 로 통일. 가산 방향 관용(`+60초`)도 채택하지 않았다 — 신고 직후 즉시 재비식별한 정상 건을 근거 없이 60초간 거부하는 오탐이 되기 때문. **경계값(mtime == 신고시각) = 거부**로 명시 고정. TC-LABEL-105 기대결과 정정(구 기재 "mtime 은 60초 클럭스큐 관용" **폐기**) + 회귀 가드 **3건 신설**(TC-LABEL-105a 감산 창 재현 409 · 105b 경계값 409 · 105c 신고+1초 정상 통과). 케이스 수 **281 → 284**. 실행 증거: `DeidentReportServiceTest` **51/51 PASS**(수정 전 신설 3건 중 2건 FAIL = 비어있는 가드 아님), `kr.co.cudo.authoring.label.*` 회귀 전건 PASS |
| 7 | 2026-08-04 | 0건 | 4건 | 0건 | **6회차(B-ISSUE-42) 코드리뷰 MEDIUM 2건 보강** — BE **무변경**(테스트·문서만). ①**procLog 분기 경계 미검증**: `verifyDeidentArtifact` 는 독립된 두 시각 비교를 OR 로 묶는데(`procTime.isAfter` :586 / `mtime.isAfter` :607), 6회차가 고정한 건 mtime 쪽뿐이라 **6회차가 주장한 "mtime·procLog 모두 엄격 비교로 통일"이 procLog 쪽에서 회귀 무방비**였다 → mtime 을 신고−10분으로 고정해 procLog 분기만 노출시키는 헬퍼(`stubArtifactWithProcTime`, `stubArtifactWithMtime` 의 정확한 대칭)로 **TC-LABEL-105d(동일 시각 409) · 105e(+1초 통과)** 신설. **실측 결과 procLog 경계는 mtime 경계와 대칭(동일 시각 = 거부)** 이며 비대칭 없음. ②**mtime 비교의 파일시스템 정밀도 경계 미검증**: 기존 mtime 케이스는 전부 초 정렬 신고시각 + 초 단위 오프셋(±30s/±1s)이라 FS 절삭이 서브초 경계를 어느 쪽으로 미는지 고정하지 못했다 → **FS 정밀도 실측 선행**(개발 환경 `java.io.tmpdir` = **APFS**, `setLastModifiedTime` 왕복이 **나노초 완전 보존**: +1ms→`.001`, +1ns→`.000000001`) 후, 환경별 플래키를 피하려 **저장된 mtime 을 되읽어 판정 일치를 검증하는 적응형** **TC-LABEL-105f**(신고+1ms — 보존 FS 면 통과 / 초 절삭 FS 면 409 fail-closed) + **정밀도 무관 단정** **TC-LABEL-105g**(신고−1ms — 절삭이 '신고 이전'을 '이후'로 뒤집지 않음)를 신설. 케이스 수 **284 → 288**. 실행 증거: `DeidentReportServiceTest` **55/55 PASS**(51→55), `kr.co.cudo.authoring.label.*` **595/595 PASS**(56 클래스). **비어있는 가드 아님 — 4건 전부 뮤테이션으로 개별 입증**: procLog 를 `!isBefore` 로 완화 → **105d 단독 FAIL** / procLog 를 `isAfter(+1분)` 로 과엄격화 → **105e 단독 FAIL** / mtime 비교를 **초 단위 절삭**으로 뭉갬 → **105f 단독 FAIL**(다른 어떤 케이스도 못 잡음) / mtime 을 구 감산 관용 `minusSeconds(60)` 으로 되돌림 → **105a·105b·105g 3건 FAIL** |
| 8 | 2026-08-04 | 1건(TC-LABEL-90) | 0건 | 1건(TC-LABEL-97) | **비식별 누락 신고의 개인정보 3필드 리셋 폐기**(사용자 확정, 구속) — 라벨 보존 정책과 같은 취지로 개인정보 판정도 보존한다. 구 정책(프레임 축·영상 축 3필드 NULL 리셋)은 폐기 표기로 보존하며 보존 검증은 B 카탈로그 TC-DEID-058 이 담당. ⚠ 개인정보 메타 PUT **412 게이트는 유지**(근거만 교체) ⚠ **머지 합류(2026-08-05)로 회차 번호 4 → 8 재부여** — main 의 회차 4~7 과 겹쳤다. |
| 9 | 2026-08-05 | 1건(TC-LABEL-127) | 4건(TC-LABEL-154~157) | 0건 | **비식별 누락 신고 단계 구분 + 재개 지점 분기**(사용자 확정, 구속 · V171). 마킹 단계 신고에 **`MARKING_READY` 제한**(412) 추가 — 라벨링 단계는 **배치 단계와 무관하게 접수**(제한이 새면 "검수 완료 후 신고"가 막힌다). 해소 후 재개는 신고 단계로 갈린다 — MARKING=마킹부터 다시 / LABELING=프레임 이미지만 재추출(마킹 유지 · **라벨 좌표 보존**). BE 상세는 B [TC-DEID-094~109](B-batch-deidentify.md), FE 신고 버튼은 [H TC-FE-311~314](H-frontend-e2e.md). ⚠ 신규 4건은 **문서 마지막 번호(149) 다음**인 `TC-LABEL-150~153`(→ 머지 후 **`TC-LABEL-154~157` 로 재부여**) 을 쓴다 — 최초 초안은 `137~140` 을 썼으나 같은 파일의 **C-ISSUE-41 좌표 clamp 4행이 이미 그 번호를 점유**하고 있었고, TC-LABEL-16·~~TC-LABEL-70~~ 이 그 번호를 참조 중이라 인용이 모호해졌다(기존 행은 건드리지 않고 신규만 재번호) ⚠ **머지 합류(2026-08-05)로 회차 번호 5 → 9 재부여** — main 의 회차 4~7 과 겹쳤다. |
| 10 | 2026-08-05 | 0건 | 0건 | 1건(TC-LABEL-125) | **회차 4 누락 보정 — 같은 뿌리의 stale 1건 추가 폐기.** 회차 4 가 개인정보 3필드 리셋 폐기(`5cf4f778`)를 반영하면서 `TC-LABEL-97` 만 폐기 표기하고, **리셋을 전제로 한 감사 케이스 `TC-LABEL-125`("개인정보 리셋 행 단위 감사")를 남겨 뒀다** — 리셋이 없어져 감사 대상 자체가 존재하지 않으므로 폐기한다(대체 케이스 신설 없음 — 보존 검증은 B [TC-DEID-058](B-batch-deidentify.md), 라벨·3필드 보존은 TC-LABEL-90, 감사행 미생성은 `DeidentReportServiceResetIT` 가 이미 담당). ⚠ `PRIVACY_META_RESET` 이벤트 타입·팩토리는 **과거 행 판독용 존치**(신규 발생 0). 폐기 표기는 행을 남기므로 **총계 불변(폐기 표기는 행을 남긴다 — 머지 후 실측 294)** ⚠ **머지 합류(2026-08-05)로 회차 번호 6 → 10 재부여** — main 의 회차 4~7 과 겹쳤다. |
| 11 | 2026-08-05 | 0건 | 5건(TC-LABEL-158~162) | 0건 | **라벨 이력 작성자 표시명(사번→이름) 계약 변경** — `GET /v1/frames/{srcSn}/label-history` 응답의 `actor` 가 사번(`REG_ID`)이라 화면에 "2001"·"1001" 이 찍히던 결함 수정. **`actorName`(표시명, `LS_ACNT_USER.USER_NM`) 신규 추가**, `actor` 는 하위호환으로 계속 사번을 담는다(값 의미 불변). 해석은 공용 헬퍼 `user/service/UserNameResolver.java`(페이지 사번을 모아 `findByUserNoIn` 1회, N+1 금지) — 비숫자/미존재/`REG_ID` null 은 예외 아닌 `actorName=null`(조회는 계속 200). FE 는 이름이 없으면 사번으로 폴백(`resolveDisplayName`), 사번·이름 둘 다 없으면(시스템 이력 행) "시스템" 표시. ⚠ **버전목록·롤백 응답의 동형 `authorName`/`registeredUserName` 계약 변경은 D 카탈로그**(`VersionService`) 소관 — [TC-VERSION-018~022 · TC-DIFF-030~031](D-review-version-notify.md) 참조 |
| 12 | 2026-08-05 | 3건 | 0건 | 0건 | **사번→표시명 판정의 `UserNameResolver` 수렴(순수 리팩토링)에 따른 근거 `file:line` 드리프트 정정** — 동작·기대결과 변경 없음. `resolveAllByNo`/`resolveOneByNo`(USER_NO 축) 신설로 배치조회 실행부가 `query()` 로 분리되고 단건조회 로직이 `resolveOneByNo` 로 이동해 `TC-LABEL-159/160/161` 3건 정정 |

> **이 파일의 판정 기준**: 루트 `CLAUDE.md` 의 ★ 구속 정책이 정본이다. 특히 ①"파생영상은 비식별 신고 체계 바깥 — 양방향 무관"
> ②"신고 게이트 판정 범위 = 자기 rawSn 행 하나(조상/자손 전파 폐기)" ③"신고 시 라벨 보존 + 조회 차단(412)"
> ④"오토라벨 좌표 = 경계 clamp · 퇴화 스킵" 은 **확정 정책이므로 결함으로 재분류하지 않는다.**
> "파생 경유 열람"(부모 신고 구간에 파생본으로 라벨·프레임을 보는 것)은 위 정책의 필연적 귀결이며 **케이스로 쓰지 않는다.**

---

## C-1. TC-MARK — 마킹 (자동/수동)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|---|---|---|---|---|---|:--:|---|
| TC-MARK-01 | AUTO 마킹 정상 생성 | 배정 WORKER, deidY, MARKING_READY, evntType 존재, 활성 마킹 0건 | mode=AUTO, intervalFrames=30 | 201, marks 배열, STTS=PENDING, MarkingCompletedEvent 발행 | integration | P1 | MarkingService.java |
| TC-MARK-02 | MANUAL 마킹 정상 생성 | 위 동일 | mode=MANUAL, marks=[{0,"00:00"}] | 201, marks 직렬화, fps pin | integration | P1 | MarkingService.java |
| TC-MARK-03 | mode 누락(blank) | 인증됨 | mode="" | 400 @NotBlank | unit | P1 | MarkingRequest.java |
| TC-MARK-04 | mode 비AUTO/MANUAL | precondition 통과 | mode="XYZ" | 400 INVALID_INPUT | unit | P1 | MarkingService.java |
| TC-MARK-05 | AUTO intervalFrames null | AUTO | intervalFrames=null | 400 "1 이상" | unit | P1 | MarkingService.java |
| TC-MARK-06 | AUTO intervalFrames 0/음수(경계) | AUTO | 0,-1 | 400 | unit | P1 | MarkingService.java |
| TC-MARK-07 | MANUAL marks 빈/null | MANUAL | marks=[] | 400 "marks 필수" | unit | P1 | MarkingService.java |
| TC-MARK-08 | AUTO durationSec null(해석 실패 backstop) | AUTO, 길이 미상 | autoDurationSec=null | 400 "영상 길이 확인 불가" | unit | P1 | MarkingService.java |
| TC-MARK-09 | AUTO durationSec 0/음수(경계) | AUTO | durationSec=0 | 400(퇴화 방지) | unit | P1 | MarkingService.java |
| TC-MARK-10 | generateAutoMarks off-by-one | dur=10s,fps=30,interval=30 | totalFrames=300 | frameIndex<300만(끝경계 미포함) | unit | P2 | MarkingService.java |
| TC-MARK-11 | 분수 fps 반올림(29.97) | AUTO | fps=29.97 | Math.round(dur×fps), mm:ss. **1차 PARTIAL(B-ISSUE-27)** — 29.97 직접 계산 테스트 여전히 부재 | unit | P2 | MarkingService.java |
| TC-MARK-12 | fps 미상 → 30 폴백 | fps 미적재 | resolveFps=30 | 30 폴백, 무회귀 | unit | P2 | MarkingService.java |
| TC-MARK-13 | fps pin 저장(TOCTOU) | AUTO/MANUAL | 생성 | LsMarking.fps 저장(추출이 재조회 안 함) | unit | P1 | LsMarking.java |
| TC-MARK-14 | 인가: 미인증 actor=null | — | actor=null | 401(존재확인 전) | unit | P0 | MarkingGuards.java |
| TC-MARK-15 | 인가: 미배정 WORKER(IDOR) | WORKER 타 영상 | rawSn 미배정 | 403(미존재도 403 — 존재 은닉) | security | P0 | MarkingGuards.java |
| TC-MARK-16 | 인가: REVIEWER 전체 허용 | REVIEWER | 임의 rawSn | 통과 | unit | P1 | MarkingGuards.java |
| TC-MARK-17 | 프리컨디션: 영상 미존재 | 인가 통과 | raw=null | 404 | unit | P1 | MarkingGuards.java |
| TC-MARK-18 | 프리컨디션: 비식별 미완료 | deIdntfYn≠Y | — | 412 | unit | P0 | MarkingGuards.java |
| TC-MARK-19 | 프리컨디션: MARKING_READY 아님(재마킹 차단) | PROCESSING/COMPLETED | — | 412. ※"반려 후 재마킹" 동선은 존재하지 않음(2026-07-27 확정) — 결함 아님 | unit | P1 | MarkingGuards.java |
| TC-MARK-20 | 프리컨디션: evntTypeCd 미지정 | blank | — | 400 | unit | P1 | MarkingGuards.java |
| TC-MARK-21 | 프로브-이전 사전확인(리소스) | 미배정 WORKER AUTO | — | precheck 403, ffprobe 미트리거 | security | P1 | MarkingService.java |
| TC-MARK-22 | 프리체크 REQUIRES_NEW 격리 | AUTO | — | readonly REQUIRES_NEW 즉시 반납 | integration | P2 | MarkingPrecheckReader.java |
| TC-MARK-23 | persist 이중화 방어 | 사전확인 후 상태변경 | — | persist 에서 인가·프리컨디션·활성중복 규칙 재강제(동일 헬퍼) | integration | P1 | MarkingService.java |
| TC-MARK-24 | 이벤트명 자동소싱(API-047) | eventName 없음 | — | raw.evntTypeCd 사용 | unit | P2 | MarkingService.java |
| TC-MARK-25 | LsMarking.createAuto 검증 | — | rawSn null/eventName blank/interval≤0/videoPath null | IllegalArgumentException | unit | P2 | LsMarking.java |
| TC-MARK-26 | LsMarking.createManual 검증 | — | rawSn null/eventName blank/videoPath null | IllegalArgumentException | unit | P2 | LsMarking.java |
| TC-MARK-27 | markVlmRequested PENDING만 전이 | STTS=PENDING | — | true, VLM_REQUESTED | unit | P1 | LsMarking.java |
| TC-MARK-28 | markVlmRequested 이미 완료 no-op | VLM_COMPLETED | — | false(역행 차단) | unit | P1 | LsMarking.java |
| TC-MARK-29 | 배치브릿지: 배치단계 PROCESSING/COMPLETED 스킵 | LsDataRaw.dataSttsCd=COMPLETED | AFTER_COMMIT | 재트리거 스킵 + reason=STAGE_ALREADY_RUN | integration | P0 | MarkingBatchBridge.java |
| TC-MARK-30 | 배치브릿지: 비식별 미완료 스킵 | deIdntfYn≠Y | — | 트리거 스킵 + reason=NOT_DEIDENTIFIED | integration | P1 | MarkingBatchBridge.java |
| TC-MARK-31 | 배치브릿지: row 부재 시 생성 | 미배정 REVIEWER 직접 마킹 | tx1 claim false | tx2 tryCreateBatchQueuedRow 신규 생성 후 트리거 | integration | P1 | MarkingBatchBridge.java |
| TC-MARK-32 | 배치브릿지: 동시 2노드 유니크 경합 | 동시 이벤트 | DataIntegrityViolation | 1건만 트리거(나머지 조용히 스킵) | integration | P0 | MarkingBatchBridge.java |
| TC-MARK-33 | 배치브릿지: 영상 미존재 스킵 | rawOpt empty | — | 스킵 + reason=VIDEO_NOT_FOUND | integration | P2 | MarkingBatchBridge.java |
| TC-MARK-34 | 로그 인젝션 방어 | CR/LF 값 | — | sanitize 개행 제거(:127, :136 적용) | security | P2 | MarkingBatchBridge.java |
| TC-MARK-35 | 컨트롤러 권한 매핑 | PORTAL_USER 토큰 | — | 403(REVIEWER/WORKER만) | security | P1 | MarkingController.java |
| TC-MARK-36 | 활성 마킹 중복 409(순차) (신규) | rawSn 에 STTS=PENDING 마킹 존재 | 같은 rawSn 재요청 | 409 CONFLICT "이미 진행 중인 마킹" — ffprobe·쓰기 이전 1선 거부. **⚠ 3차 실측 정정(2026-08-03)**: 가드 평가 순서(`MarkingGuards` 상단 Javadoc, ④MARKING_READY가 ⑥활성마킹중복보다 선행)상 이 409는 **재요청 시점에 raw.data_stts_cd 가 여전히 MARKING_READY 일 때만** 도달한다. 실배선 스택(mock-server 응답 수 ms)에서는 최초 마킹의 AFTER_COMMIT 배치가 매우 빠르게 raw 를 PROCESSING 이후 단계로 밀어내, **진짜 순차 재요청(수 ms~수십 ms 지연)은 거의 항상 412(PRECONDITION_FAILED "이미 처리된 영상은 재마킹할 수 없습니다")로 귀결**됨을 재현 확인(raw143 실측: 1차 201 → 2차(3ms 후) 412, raw_sn 143 data_stts_cd=FAILED로 이미 전이). 409 은 **진짜 동시(레이스) 요청**에서만 안정적으로 재현됨(raw142 실측: 동시 2요청 → 1×201 + 1×409, TC-MARK-37과 동일 메커니즘) — C-ISSUE-05 참조 | integration | P0 | MarkingGuards.java |
| TC-MARK-37 | 활성 마킹 동시 요청 → DB 유니크 최종 방어 (신규) | 동시 3요청(서로 미커밋 행 미관측) | 동시 POST ×3 | 1건 201 · 나머지 409(DataIntegrityViolation→CONFLICT). PG tx abort 라 같은 tx 재시도 없음 | integration | P0 | MarkingService.java · V142__*.sql |
| TC-MARK-38 | "활성" 정의 = PENDING/VLM_REQUESTED (신규) | 종결(VLM_COMPLETED/VLM_FAILED) 마킹만 존재 | 재마킹 요청 | 중복 가드 통과(단, TC-MARK-19 배치단계 가드는 별개). `LsMarking.ACTIVE_STATUSES` ↔ V142 인덱스 술어 **문자 일치** | unit | P1 | LsMarking.java · V142__*.sql |
| TC-MARK-39 | 프리체크 단계 활성중복 판정 (신규) | 활성 마킹 존재 + AUTO | — | precheck 에서 409 → **ffprobe 미트리거** | integration | P1 | MarkingPrecheckReader.java |
| TC-MARK-40 | MANUAL frameIndex 음수 400 (신규 · C-ISSUE-01) | MANUAL | frameIndex=-5 | 400 @Min(0). ※구 결함: `marks` 에 `@Valid` 가 없어 `@NotNull` 조차 미발화했음 | security | P1 | MarkItem.java · MarkingRequest.java |
| TC-MARK-41 | MANUAL 중복 frameIndex 400 (신규 · C-ISSUE-01) | MANUAL | [{0},{0}] | 400 "중복된 마킹 시점" | unit | P1 | MarkingService.java |
| TC-MARK-42 | MANUAL frameIndex 상한 초과 400 (신규 · C-ISSUE-01) | dur·fps 해석 성공 | frameIndex=999999999 | 400 "영상 길이를 벗어난 마킹 시점". 상한=round(dur×fps)+ceil(fps) **배타** — 1초 마진(fps 불일치·정수초 절단 보정) | unit | P1 | MarkingService.java |
| TC-MARK-43 | MANUAL 길이 미상 → 상한만 skip (신규) | VDO_LEN_SEC·메타 모두 부재 | frameIndex=999999 | 통과(상한 skip) + WARN 로그. **중복·하한 검증은 유지**(전부 스킵 금지) | unit | P1 | MarkingService.java |
| TC-MARK-44 | timestamp 형식 위반 400 (신규) | MANUAL | timestamp="99:99" / "-1:00" | 400 @Pattern(`^\d{1,4}:[0-5]\d(:\d{1,3})?$`), null 은 허용 | unit | P2 | MarkItem.java |
| TC-MARK-45 | marks 개수 상한 20000 (신규, CWE-770) | MANUAL | 20001건 | 400 @Size | security | P2 | MarkingRequest.java |
| TC-MARK-46 | 배치 미트리거 사유 응답 반영 (신규) | 검수 소유 상태(PENDING/IN_REVIEW/APPROVED/REJECTED) 영상 마킹 | — | 201 + 응답에 batchTriggered=false·reason 동반(무음 스킵 제거). 스레드로컬은 요청 시작 시 begin()으로 초기화 | integration | P1 | MarkingService.java · MarkingBatchBridge.java |

> C-1 부수: 마킹 단계 비식별 누락 신고(`POST /v1/videos/{rawSn}/deident-report`)는 C-2 의 TC-LABEL-127~130 · **150~153**(V171 — `MARKING_READY` 제한 + 단계별 재개) 참조. FE 신고 버튼은 [H-frontend-e2e](H-frontend-e2e.md) 참조.

---

## C-2. TC-LABEL — 라벨 CRUD / full-replace / 마스터 / 온라인 오토라벨 / 비식별신고

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|---|---|---|---|---|---|:--:|---|
| TC-LABEL-01 | 프레임 라벨 조회 정상 | 배정 WORKER, 신고 구간 아님 | GET labels | 200, siblings+aiInfo+lsLabel enrich + `labelVersion` 포함 | integration | P1 | LabelService.java |
| TC-LABEL-02 | 조회 IDOR: 타 영상 프레임 | WORKER 미배정 | — | 403 | security | P0 | LabelAccessGuard.java |
| TC-LABEL-03 | 조회: 미인증 | actor=null | — | 401 | security | P1 | LabelAccessGuard.java |
| TC-LABEL-04 | 조회: 프레임 미존재 | srcSn 없음/null | — | 404 | unit | P1 | LabelAccessGuard.java |
| TC-LABEL-05 | frameImageType RAW(REVIEWER+raw) | REVIEWER | raw=true | "RAW" 반환 | unit | P2 | LabelService.java |
| TC-LABEL-06 | frameImageType DEID(WORKER raw무시) | WORKER | raw=true | "DEID" 강제 | security | P1 | LabelService.java |
| TC-LABEL-07 | bulkUpsert 신규 INSERT(수동) | 배정 | id=null,source=MANUAL | AUTO_LBL_YN='N', ADDED 이력 | integration | P1 | LabelService.java |
| TC-LABEL-08 | bulkUpsert 기존 UPDATE | id 지정 | 좌표 변경 | UPDATE, before/after 스냅샷 | integration | P1 | LabelService.java |
| TC-LABEL-09 | full-replace: 빠진 라벨 삭제 | 기존 3건 | items 2건만 | 나머지 1건 실삭제(DELETED 이력 + before 스냅샷) | integration | P0 | LabelService.java |
| TC-LABEL-10 | full-replace: 빈 items=전량 삭제 | 기존 존재 | items=[] | 프레임 전체 삭제 | integration | P0 | LabelService.java |
| TC-LABEL-11 | 삭제 순서 FK고아 방지 | 자식 존재 | 삭제 | ATTR_VAL→AI_INFO→LBL 순 bulk delete | integration | P0 | LabelService.java |
| TC-LABEL-12 | 작업락 시 저장 차단 (정정 2026-08-04 · C-ISSUE-22 수정 반영) | isRawLocked | — | **신고와 무관한 락**(트랙 병합 등 일시적 충돌)이면 409 CONFLICT. ⚠ 구 기재 *"저장 경로에는 신고 게이트가 없고 작업락이 유일 방어 → 락 만료 후 조회 412 ↔ 저장 200 비대칭"* 은 **폐기** — `bulkUpsert` 진입부가 `accessGuard.requireNotUnderDeidentReport` 를 **락 검사보다 먼저** 호출한다. 따라서 `DE_IDNTF_YN='F'` 인 영상은 **락 유무와 무관하게 412**(응답 코드가 잠금 상태를 알려주는 오라클 제거)이고, 409 는 신고가 아닌 락에만 남는다 | integration | P0 | LabelService.java(신고 게이트 · 락 409) |
| TC-LABEL-12a | 신고 구간 저장 412 + 기존 라벨 보존 (신규 2026-08-04 · C-ISSUE-22 회귀 가드) | `DE_IDNTF_YN='F'`, 작업락 **0건**(6h 만료 sweep 이후), 기존 라벨 2건 | PUT `/v1/frames/{srcSn}/labels` `{"items":[]}` | **412** "비식별 재처리 대기 중인 영상입니다…"(행위 중립 문구) + **기존 라벨 2건 DB 존치**(full-replace 전량 삭제 미발생). 저장 응답에 좌표가 실리지 않으므로 412 열람 차단 우회도 닫힘(CWE-359) | security | P0 | LabelService.java · LabelAccessGuard.java · LabelSaveGuardsIT |
| TC-LABEL-13 | dedupById 동일 id 중복 | 같은 id 2개 | — | last-value-wins | unit | P2 | LabelService.java |
| TC-LABEL-14 | 좌표 검증: 빈 points | — | points=[] | 400 (DTO `@NotEmpty` 가 먼저 발화 — 서비스 메시지는 도달 불가) | unit | P1 | LabelItemDto.java · LabelService.java |
| TC-LABEL-15 | 좌표 검증: [x,y] 형식 위반 | — | pair size≠2 / pair=null | 400. ⚠ **원소 null(`[[null,5],[10,10]]`)은 여전히 500** — 2-튜플 경로에 null 가드 부재(언박싱 NPE). 1차 C-ISSUE-21 **미해소 이월**(3차 재확인, 대조군 SKELETON 경로 `:805-811` 은 400) | unit | P1 | LabelService.java |
| TC-LABEL-16 | 좌표 검증: 음수 | — | x=-1 | 400 "0 이상". ※**사용자 저장 경로 한정** — AI 검출 응답은 clamp 정책(TC-LABEL-137) | security | P0 | LabelService.java |
| TC-LABEL-17 | 좌표 검증: 0 허용(경계) | — | x=0,y=0 | 통과 | unit | P2 | LabelService.java |
| TC-LABEL-18 | 신규 라벨 >1000점 차단(DoS) | id=null | 1001점 | 400(CWE-770). 경계 1000점은 통과 | security | P0 | LabelService.java |
| TC-LABEL-19 | 기존 라벨 >1000점 simplify | id 지정 | 1001점 | 400 아님, capPoints(Douglas-Peucker) ≤1000 | unit | P1 | LabelService.java |
| TC-LABEL-20 | labelId 미존재 | 부재 | — | 404 | unit | P1 | LabelService.java |
| TC-LABEL-21 | labelId USE_YN='N' → **신규 부여에만** 409 (정정) | 비활성 마스터 | id=null 로 비활성 labelId 부여 | 409. **기존 라벨이 같은 labelId 를 유지하는 저장은 통과**(C-ISSUE-25 수정 — 구 "요청 전체 labelId 검사"는 프레임 저장 영구 차단이었다) | unit | P1 | LabelService.java |
| TC-LABEL-22 | Mass Assignment: autoLblYn 무시 | 요청 autoLblYn='Y' | UPDATE | 무시(응답전용 — `ls_data_lbl` 에 컬럼 자체 없음, AI_INFO row 존재로 파생) | security | P1 | LabelService.java |
| TC-LABEL-23 | provenance AUTO_YOLO→AI_INFO | id=null,AUTO_YOLO | confScore | AUTO_LBL_YN='Y'+AI_INFO 행 생성 | integration | P1 | LabelService.java |
| TC-LABEL-24 | provenance 화이트리스트 위반 | source="HACK" | — | 400 @Pattern(MANUAL/AUTO_YOLO/AUTO_SAM2) | security | P1 | LabelItemDto.java |
| TC-LABEL-25 | confScore 범위 초과 | 1.5 | — | 400 @DecimalMax(1.0) | unit | P2 | LabelItemDto.java |
| TC-LABEL-26 | items >500 상한(DoS) | 501건 | — | 400 @Size | security | P1 | LabelBulkUpsertRequest.java |
| TC-LABEL-27 | 무변경 재저장 이력 미발행(R7) | 동일 좌표 | — | UPDATED 이력·통지 미발행 | integration | P1 | LabelService.java |
| TC-LABEL-28 | R7 정규화 비교(5 vs 5.0) | 표현만 다름 | — | 무변경 판정 | unit | P2 | LabelService.java |
| TC-LABEL-29 | R7 레거시 3포맷 흡수 | 객체배열/평탄 | — | 정규화 후 동일 판정 | unit | P2 | LabelService.java |
| TC-LABEL-30 | R7 손상 JSON fail-safe | 파싱 실패 | — | '변경됨' 처리(이력 유실 방지). ※전용 회귀 테스트 여전히 부재(C-ISSUE-23) | unit | P2 | LabelService.java |
| TC-LABEL-31 | TASK_MODIFIED: APPROVED만 | APPROVED, 변경 有 | — | 변경 종류별 이벤트 발행 + `exportRegenerated=true` | integration | P1 | LabelService.java |
| TC-LABEL-32 | TASK_MODIFIED: 검수전 미발행 | PENDING/ASSIGNED | — | 미발행 | integration | P1 | LabelService.java |
| TC-LABEL-33 | 이력 조회 IDOR | WORKER 타 프레임 | — | 403 | security | P1 | LabelService.java |
| TC-LABEL-34 | 이력 페이지 상한 클램프 | size=500 | — | 100 클램프 | unit | P2 | LabelService.java |
| TC-LABEL-35 | 이력 임의 sort 무시(500 차단) | ?sort=badfield | — | 서버 고정 정렬(REG_DT DESC, LBL_HSTRY_SN DESC) | unit | P1 | LabelService.java |
| TC-LABEL-36 | 동시 저장 lost update **차단** (정정 · C-ISSUE-21) | 두 세션이 같은 프레임 편집 | stale `labelVersion` 첨부 저장 | **409 CONFLICT** — 구 동작(무경고 침묵 삭제) 폐기. 프레임 행 비관적 락 + **스칼라 프로젝션**으로 락 획득 시점 DB 값 CAS(1차 캐시 우회) | integration | P0 | LabelService.java |
| TC-LABEL-37 | 삭제 감사 로깅 PII 미출력 | 대량 삭제 | — | 카운트+버전만 로그(좌표·PII 없음) | security | P2 | LabelService.java |
| TC-LABEL-40 | 마스터 생성 정상 | REVIEWER | name/color/type | 201 | integration | P1 | LabelMasterService.java |
| TC-LABEL-41 | 마스터 근사중복(대소문+공백) | 활성 "car" | " Car " | 409 | security | P0 | LabelMasterService.java |
| TC-LABEL-42 | 마스터 동시 생성 DB유니크 | 경합 | — | V120 UK_LS_LABEL_NM_CI 차단 → 409 | integration | P0 | LabelMasterService.java · LsLabelRepository.java |
| TC-LABEL-43 | dtctTypeCd allowlist 위반 | "human" | — | 400(CocoClasses 80종) | security | P0 | LabelMasterService.java |
| TC-LABEL-44 | dtctTypeCd 활성 중복 매핑 | person 재매핑 | — | 409(1 COCO 클래스 = 1 활성 라벨) | unit | P1 | LabelMasterService.java |
| TC-LABEL-45 | dtctTypeCd 미매핑 해제 허용 | null/blank | — | null 저장 | unit | P2 | LabelMasterService.java |
| TC-LABEL-46 | color 소문자 hex 거부 | "#ffffff" | — | 400 @Pattern(대문자만) | unit | P2 | LabelMasterRequest.java |
| TC-LABEL-47 | type allowlist | "FOO" | — | 400 @Pattern(BBOX/POLYGON/POINT/SKELETON) | unit | P2 | LabelMasterRequest.java |
| TC-LABEL-48 | sortNo 음수 | -1 | — | 400 @Min | unit | P2 | LabelMasterRequest.java |
| TC-LABEL-49 | 마스터 수정 미존재 | 없음 | — | 404 | unit | P1 | LabelMasterService.java |
| TC-LABEL-50 | 마스터 수정 근사중복(자기제외) | 타 라벨명 충돌 | — | 409 | unit | P1 | LabelMasterService.java |
| TC-LABEL-51 | 마스터 삭제 soft delete | — | DELETE | USE_YN='N'(hard delete 없음) | integration | P1 | LabelMasterService.java |
| TC-LABEL-52 | 관리 권한: WORKER POST 차단 | WORKER | — | 403 | security | P0 | LabelMasterController.java |
| TC-LABEL-53 | 조회 권한: WORKER/PORTAL 허용 | 인증 | GET | 200 | security | P2 | LabelMasterController.java |
| TC-LABEL-54 | findLabelIdByDtctType null/blank | — | null | Optional.empty | unit | P2 | LabelMasterService.java |
| TC-LABEL-55 | findLabelIdByDtctType 활성 유니크 최대1 | 활성 매핑 | trim 매칭 | 최대 1건, NonUniqueResult 없음 | integration | P1 | LsLabelRepository.java |
| TC-LABEL-60 | autolabel BBOX 정상 | 배정 WORKER | POST autolabel | 200 좌표만(미저장, lblSn=null) | integration | P1 | AutolabelOnlineService.java |
| TC-LABEL-61 | autolabel IDOR | WORKER 미배정 | — | 403 | security | P0 | AutolabelOnlineService.java |
| TC-LABEL-62 | autolabel 작업락 | isRawLocked | — | 409 | integration | P0 | AutolabelOnlineService.java |
| TC-LABEL-63 | autolabel inFlight 중복 | 진행 중 재요청 | — | 409, finally 락해제 | integration | P1 | AutolabelOnlineService.java |
| TC-LABEL-64 | resolveDetectClasses 매핑 0건 게이팅 | 매핑 없음 | — | ai 미호출, 0건+NO_MAPPED 안내 | integration | P0 | AutolabelOnlineService.java |
| TC-LABEL-65 | resolveDetectClasses 화이트리스트 교집합 | [person,hack] | mapped={person} | person만 전달, hack drop(WARN) | security | P0 | AutolabelOnlineService.java |
| TC-LABEL-66 | resolveDetectClasses 우회 시도 | 미매핑 강제 | — | 교집합만(FE 불신) | security | P0 | AutolabelOnlineService.java |
| TC-LABEL-67 | autolabel mock 응답 차단 | ai mock=true | — | 좌표 미반환, detectedCount=0 + MOCK 메시지 | integration | P0 | AutolabelOnlineService.java |
| TC-LABEL-68 | 검출 좌표 개수 ≠4 → all-or-nothing 400 (정정) | ai 응답 3좌표 | — | 400. **형식 위반만** 전체 거부(부분 반환 금지) | unit | P1 | DetectionBoxNormalizer.java · AutolabelOnlineService.java |
| TC-LABEL-69 | 검출 좌표 NaN/Infinity → 400 (정정) | NaN | — | 400. **유한성 가드가 clamp 이전**(`NaN<0`=false 라 음수검사를 통과해 역직렬화 500 유발하던 회귀 차단) | security | P1 | DetectionBoxNormalizer.java |
| ~~TC-LABEL-70~~ | ~~validateBbox 음수/순서위반 400~~ | ~~x2≤x1~~ | ~~400~~ | **[폐기 2026-07-30]** 좌표정책 반전(6d1b3703) — 음수는 400 이 아니라 **경계 clamp**, 순서 역전·퇴화는 **해당 검출만 스킵**(400 아님). 대체: TC-LABEL-137(clamp)·TC-LABEL-138(퇴화 스킵). `AutolabelOnlineService.validateBbox` 는 제거됨 | — | — | (구 AutolabelOnlineService.java — 현재 부재) |
| TC-LABEL-71 | 검출 0건 SAM 스킵 | empty | — | reCheckLock 후 빈 결과 | unit | P2 | AutolabelOnlineService.java |
| TC-LABEL-72 | POLYGON maxBoxes 상한 | 검출>maxBoxes | — | limit까지만 SAM + truncated 안내 | integration | P1 | AutolabelOnlineService.java |
| TC-LABEL-73 | POLYGON wall-clock 예산 소진 | 예산 초과 | — | truncate+부분반환 | unit | P1 | AutolabelOnlineService.java |
| TC-LABEL-74 | POLYGON 박스별 실패 스킵 | SAM null/mock | — | 성공분만, skipped 안내(비-mock 실패도 고지) | integration | P2 | AutolabelOnlineService.java |
| TC-LABEL-75 | POLYGON bulkhead 429 즉시전파 | TOO_MANY_REQUESTS | — | fail-fast 전파(스킵 흡수 금지) | security | P1 | AutolabelOnlineService.java |
| TC-LABEL-76 | POLYGON TOCTOU 중간 잠금/신고 | 배치중 신고→잠금 | — | 409(락) 또는 412(신고), 좌표 미반환 | integration | P0 | AutolabelOnlineService.java |
| TC-LABEL-77 | YOLO bulkhead 초과 429 | 동시 상한 초과 | — | 429 | integration | P1 | AutolabelOnlineService.java |
| TC-LABEL-78 | ai-server 호출 실패 502 | RuntimeException | — | EXTERNAL_API_ERROR(스택·경로 미노출) | security | P1 | AutolabelOnlineService.java |
| TC-LABEL-79 | AutolabelRequest conf 범위 | 0.9 | — | 400 @DecimalMax(0.80) | unit | P2 | AutolabelRequest.java |
| TC-LABEL-80 | classes 100개 초과 | 101개 | — | 400 @Size | unit | P2 | AutolabelRequest.java |
| TC-LABEL-90 | 신고 정상 — **라벨 보존** (정정) | 배정 WORKER, 비파생, 비식별 산출물 有 | POST `/v1/labels/{srcSn}/deident-report` | 201 · **라벨 삭제 0건 · LS_LABEL_VERSION 스냅샷 미생성** · 작업락 + `DE_IDNTF_YN='F'` · **개인정보 3필드도 보존**(2026-08-04 리셋 폐기). 구 정책("전체 라벨 삭제 + `SAVE_REASON='DEIDENT_REPORT'` 비활성 스냅샷")은 **폐기**(2026-07-27 사용자 확정) | integration | P0 | DeidentReportService.java |
| TC-LABEL-91 | 신고 reason 누락 | reason="" | — | 400(컨트롤러 @Valid 우회 호출도 서비스 백스톱) | unit | P1 | DeidentReportService.java |
| TC-LABEL-92 | 신고 reason 1000자 초과 | 1001자 | — | 400 @Size | unit | P2 | DeidentReportRequest.java |
| TC-LABEL-93 | 신고 IDOR(srcSn 경로) | WORKER 미배정 | — | 403 | security | P0 | DeidentReportService.java |
| TC-LABEL-94 | 신고 이미 잠금 | isRawLocked | — | 409(중복신고 차단) | integration | P1 | DeidentReportService.java |
| TC-LABEL-95 | 신고 PII TOCTOU(FOR UPDATE) | 동시 증강 콜백 | — | `findByRawSnForUpdate` 로 부모 RAW 행 잠금 → 직렬화 | integration | P0 | DeidentReportService.java |
| TC-LABEL-96 | 신고 동시 락 유니크 | 동시 신고 | DataIntegrityViolation | 409 | integration | P0 | DeidentReportService.java |
| ~~TC-LABEL-97~~ | ~~신고 개인정보 3필드 리셋~~ | ~~프레임 존재~~ | ~~`resetPrivacyMetaByRawSn`~~ | **[폐기 2026-08-04]** ★정책 반전(사용자 확정) — 신고는 개인정보 3필드를 **리셋하지 않고 보존**한다(라벨 보존 정책과 같은 취지). stale 우려는 신고 구간 export 보류 + 해제 시 재산출이 담당. 대체: TC-DEID-058(보존 확인) | — | — | (구 DeidentReportService.java — 현재 부재) |
| TC-LABEL-98 | 신고 APPROVED→TASK_MODIFIED (정정) | APPROVED | — | **`META_UPDATED`** 통지. 구 `LABEL_DELETED` 는 라벨 보존 정책과 모순이라 폐기 | integration | P1 | DeidentReportService.java |
| ~~TC-LABEL-99~~ | ~~신고 라벨 0건 스킵~~ | ~~라벨 없음~~ | ~~스냅샷/삭제/이력 스킵~~ | **[폐기 2026-07-30]** 라벨 보존 정책 반전(b0647c4c)으로 신고 경로에 **라벨 스냅샷·삭제·삭제이력 분기 자체가 없어짐** — "0건이면 스킵"할 대상이 존재하지 않는다. 대체: TC-LABEL-124(라벨 무변경 확인) | — | — | (구 DeidentReportService.java — 현재 부재) |
| TC-LABEL-100 | resolve 미인증 | actor=null | — | 401 | unit | P1 | DeidentReportService.java |
| TC-LABEL-101 | resolve 신고 미존재 | rprtSn 없음 | — | 404 | unit | P1 | DeidentReportService.java |
| TC-LABEL-102 | resolve IDOR | WORKER 타 영상 | — | 403 | security | P0 | DeidentReportService.java |
| TC-LABEL-103 | resolve OPEN 아님 | RESOLVED | — | 409 | unit | P1 | DeidentReportService.java |
| TC-LABEL-104 | resolve 산출물 미검증 gate | procLog 없음/경로 blank/무결성 실패 | — | 409, OPEN·락·'F' 유지(fail-closed). 무결성 판정은 `DeidentArtifactIntegrity` **단일 지점**(18바이트 스텁 통과 회귀 차단) | integration | P0 | DeidentReportService.java · DeidentArtifactIntegrity.java |
| TC-LABEL-105 | resolve 시간조건(신고 이후 재비식별) | procLog·mtime 모두 신고 이전 | — | 409. **mtime·procLog 모두 엄격 비교**(`isAfter(reportTime)`) — 구 기재 "mtime 은 60초 클럭스큐 관용"은 **폐기**(B-ISSUE-42, 감산 관용이 신고 직전 옛 산출물을 통과시킴) | integration | P0 | DeidentReportService.java |
| TC-LABEL-105a | resolve mtime 감산 스큐 창(구 결함 재현) | mtime = 신고시각−30초(구 60초 관용 창 안), procLog 는 신고 이전 | — | 409. 재비식별 없이 게이트가 열리지 않는다(CWE-359 회귀 가드) | unit | P0 | DeidentReportService.java · DeidentReportServiceTest.java |
| TC-LABEL-105b | resolve mtime 경계값(동일 시각) | mtime == 신고시각(초 정렬), procLog 는 신고 이전 | — | **409(거부)** — 동일 시각 파일은 신고 시점에 이미 존재하던 산출물이라 교체 증거가 아니다(fail-closed) | unit | P0 | DeidentReportService.java · DeidentReportServiceTest.java |
| TC-LABEL-105c | resolve mtime 신고 직후 교체 | mtime = 신고시각+1초, procLog 는 신고 이전 | — | 200/RESOLVED + `'F'→'Y'` + 락 해제 — 관용 제거가 정상 즉시 재비식별을 오탐 거부하지 않음 | unit | P0 | DeidentReportService.java · DeidentReportServiceTest.java |
| TC-LABEL-105d | resolve **procLog 경계값(동일 시각)** | procTime == 신고시각(나노초까지 동일), **mtime 은 신고−10분으로 고정**해 procLog 분기 단독 노출 | — | **409(거부)** — mtime 경계(105b)와 **대칭**. 동일 시각의 성공 이력은 신고 시점에 이미 존재하던 그 비식별본이라 '신고 이후 재비식별' 증거가 아니다(fail-closed) | unit | P0 | DeidentReportService.java · DeidentReportServiceTest.java |
| TC-LABEL-105e | resolve procLog 신고 직후 완료 | procTime = 신고시각+1초, mtime 은 신고 이전 | — | 200/RESOLVED + `'F'→'Y'` + 락 해제 — 105d 와 짝지어 **경계가 정확히 '동일 시각'에 있음**을 고정(기존 통과 케이스는 +1시간이라 경계 미증명) | unit | P0 | DeidentReportService.java · DeidentReportServiceTest.java |
| TC-LABEL-105f | resolve mtime **FS 정밀도 경계(신고 직후 서브초)** | 의도 mtime = 신고시각+1ms. 저장 결과는 파일시스템 정밀도에 좌우됨(실측: 개발 환경 `java.io.tmpdir`=**APFS**, `setLastModifiedTime` 왕복 **나노초 완전 보존**) | — | **저장된 mtime 을 되읽어 그 값 기준으로 판정 일치**를 검증(고정 기대값 금지 — ext3/HFS+ 등 초 절삭 FS 에서 플래키). 서브초 보존 FS → 200/RESOLVED / 초 절삭 FS → 저장값이 신고시각과 동일 초로 내려앉아 **409(fail-closed)**. 절삭이 **내림**임(올림이면 오탐 통과)도 단정 | unit | P0 | DeidentReportService.java · DeidentReportServiceTest.java |
| TC-LABEL-105g | resolve mtime FS 정밀도 경계(신고 **직전** 서브초) | 의도 mtime = 신고시각−1ms | — | **정밀도와 무관하게 항상 409** — 보존 FS 면 그대로 신고 이전, 초 절삭 FS 면 한 초 더 과거로 내려앉아 역시 신고 이전. 절삭이 '신고 이전 파일'을 '이후'로 **뒤집지 않음**을 고정(CWE-359 오탐 통과 차단) | unit | P0 | DeidentReportService.java · DeidentReportServiceTest.java |
| TC-LABEL-106 | resolve 정상+'F'→'Y' 복원 | 검증 통과 | — | RESOLVED+락해제+'Y'(FOR UPDATE 잠금 하) | integration | P0 | DeidentReportService.java |
| TC-LABEL-107 | resolve 배치상태 역행 금지 | COMPLETED/APPROVED 신고 | — | DATA_STTS_CD 유지(CWE-664) | integration | P1 | DeidentReportService.java |
| TC-LABEL-108 | listReports status allowlist | "X" | — | 400. ※소문자 "resolved" 는 컨트롤러 `@Pattern` 이 먼저 400(서비스 `toUpperCase` 관용은 도달 불가) | unit | P2 | DeidentReportService.java |
| TC-LABEL-109 | listReports 기본 OPEN | status=null | — | OPEN 필터 | unit | P2 | DeidentReportService.java |
| TC-LABEL-110 | labelVersion 불일치 409 (신규 · C-ISSUE-21) | 조회 시 labelVersion=0 수신 후 타 세션이 저장(→1) | PUT labels with labelVersion=0 | 409 "다른 사용자가 먼저 저장했습니다" — 라벨 미변경 | integration | P0 | LabelService.java |
| TC-LABEL-111 | labelVersion 미첨부 = 검사 skip (신규, 하위호환) | FE 미반영 클라이언트 | labelVersion 없음 | 200 저장(기존 동작 불변) | integration | P1 | LabelBulkUpsertRequest.java · LabelService.java |
| TC-LABEL-112 | 무변경 저장은 버전 미증가 (신규) | 동일 세트 재전송 | — | changes 비어 있음 → `bumpLabelVersionIn` 미호출, 응답 labelVersion 동일(타 세션 토큰 무효화 안 함) | integration | P1 | LabelService.java |
| TC-LABEL-113 | 좌표 상한 초과 신규 라벨 400 (신규 · C-ISSUE-22) | 1280x720 프레임 | points=[[999999,888888]] | 400 "좌표가 이미지 경계를 벗어났습니다 (…, 이미지=1280x720)" — **클램프 아님**(사용자 확정) | security | P0 | LabelService.java |
| TC-LABEL-114 | 경계값 x==width 허용 (신규) | 1280x720 | [[1280,720]] | 200 통과(우/하단 끝 정상 좌표 — `Sam2SegmentService` 외부응답 기준과 동일) | unit | P2 | LabelService.java |
| TC-LABEL-115 | 레거시 out-of-bounds 라벨 무변경 재저장 허용 (신규) | 기존 라벨이 이미 경계 밖 | 좌표 그대로 재전송 | 200 — 프레임 전체 저장이 영구 차단되는 회귀 방지(MAX_POINTS 와 동일 정책). ⚠ 면제 조건은 **그 프레임에 실재하는 id** 일 때뿐(`isNewLabel` 단일 술어 — TC-LABEL-150) | integration | P1 | LabelService.java |
| TC-LABEL-116 | 기존 라벨을 경계 밖으로 이동 → 400 (신규) | 기존 라벨 | 좌표를 실제로 경계 밖으로 변경 | 400(pointsEqual 불일치 시 상한 강제) | security | P1 | LabelService.java |
| TC-LABEL-117 | 치수 측정 실패 시 상한만 skip (신규) | 프레임 이미지 부재·손상 | 큰 좌표 | 200(상한 skip) + WARN + `label.bounds.skipped{reason}` 메트릭. 하한·형식 검증은 유지 | integration | P1 | LabelService.java · FrameBoundsResolver.java |
| TC-LABEL-118 | SKELETON v=0 키포인트 상한 제외 (신규) | SKELETON | [0,0,0] 자리표시자 | 상한 검사 제외(통과) | unit | P2 | LabelService.java |
| TC-LABEL-119 | 비활성 마스터 기존 참조 유지 저장 허용 (신규 · C-ISSUE-25) | 프레임에 USE_YN='N' labelId 참조 라벨 존재 | id 지정 + 같은 labelId 재전송 | 200 — 프레임의 다른 라벨 수정도 정상. 구 동작(프레임 전체 409 영구 차단) 폐기 | integration | P0 | LabelService.java |
| TC-LABEL-120 | 비활성 마스터를 기존 라벨에 새로 부여 → 409 (신규) | 기존 라벨의 labelId 를 비활성 값으로 변경 | id 지정 + labelId 변경 | 409 — "id 붙이면 통과" 우회 차단 | security | P0 | LabelService.java |
| TC-LABEL-121 | 신고 구간 라벨 조회 412 (신규) | `DE_IDNTF_YN='F'` | GET `/v1/frames/{srcSn}/labels` | **412** PRECONDITION_FAILED, **역할 무관**(REVIEWER 포함). 인가(403/404) **이후** 평가 | security | P0 | LabelService.java · LabelAccessGuard.java |
| TC-LABEL-122 | 신고 구간 라벨 이력 412 (신규) | 동일 | GET `/v1/frames/{srcSn}/label-history` | 412 — 이력 `chgDtlCn` 에 before/after 좌표 전문이 실려 게이트 우회가 되던 창 차단 | security | P0 | LabelService.java |
| TC-LABEL-123 | 게이트 판정 = 자기 rawSn 행 하나 (신규 · 구속) | 부모 `'F'`, 파생본(ORGNL_RAW_SN non-null) `'Y'` | 파생 프레임 라벨 조회 | **200** — 조상 체인을 타고 올라가지 않는다. 조상/자손 전파는 도입 후 철회(재도입 금지). "파생 경유 열람"은 확정 정책의 귀결이며 결함 아님 | security | P0 | DeidentReportGate.java |
| TC-LABEL-124 | 신고 후 라벨 무변경 확인 (신규) | 라벨 N건 보유 프레임 | 신고 접수 | `LS_DATA_LBL` 건수 불변 · `LS_LABEL_VERSION` 신규 행 없음 · 라벨셋 버전 bump 없음 | integration | P0 | DeidentReportService.java |
| ~~TC-LABEL-125~~ | ~~개인정보 리셋 행 단위 감사~~ | ~~3필드 보유 프레임 M건~~ | ~~신고 접수~~ | **[폐기 2026-08-05]** ★2026-08-04 정책 반전(사용자 확정, 커밋 `5cf4f778`)으로 **리셋 자체가 폐기**돼 감사 대상이 존재하지 않는다 — 신고는 개인정보 3필드를 리셋하지 않고 **보존**한다(위 ~~TC-LABEL-97~~ 과 같은 뿌리. 회차 4 가 97 만 처리하고 이 행을 남겨 stale 이 됐다). `PRIVACY_META_RESET` 이벤트 타입·팩토리·봉투 포맷은 **과거 행 판독용으로 존치**(신규 발생 0) — 삭제 금지. 대체: **[TC-DEID-058](B-batch-deidentify.md)**(보존 확인) · **TC-LABEL-90**(신고 정상 — 라벨·3필드 보존) · 감사행 미생성 확인은 ~~TC-DEID-052~~ 폐기 표기가 지목한 `DeidentReportServiceResetIT.신고해도_개인정보_리셋_감사이력이_생기지_않는다` | — | — | (구 DeidentReportService.java — 현재 부재) |
| TC-LABEL-126 | resolve 후 게이트 자동 해제 + 보존 라벨 재사용 (신규) | 신고 → resolve 성공 | 라벨 조회 재시도 | 200, **신고 전과 동일한 라벨** 반환(별도 복원 API 없음) | integration | P0 | DeidentReportService.java · LabelAccessGuard.java |
| TC-LABEL-127 | 마킹 단계 rawSn 신고 정상 (정정) | 배정 WORKER, 비파생, `DE_IDNTF_YN='Y'`, **배치 단계 `MARKING_READY`** | POST `/v1/videos/{rawSn}/deident-report` | 201. 부수효과 5종이 srcSn 경로와 **동일**(`doReport` 공용 본체). 통지의 `srcSn=null`(영상 단위). **V171 — 신고 단계 `MARKING` 저장 + `MARKING_READY` 아니면 412**(TC-LABEL-150) | integration | P0 | DeidentReportController.java · DeidentReportService.java |
| TC-LABEL-128 | rawSn 신고 — 파생영상 412 (신규 · 구속) | `ORGNL_RAW_SN` non-null | 신고 요청 | **412**. 안내는 사실만("파생영상이라 이 화면에서 재비식별 요청 불가") — **원본으로 유도하지 않고 부모 rawSn 도 미노출**. 신고 행 미생성 + REVIEWER 알림 없음 + 사유는 sanitize 후 WARN 감사로그 | security | P0 | DeidentReportService.java |
| TC-LABEL-129 | rawSn 신고 — 비식별 미수행 412 (신규) | `DE_IDNTF_YN='N'`/null(PENDING 영상) | 신고 요청 | **412**. 판정은 `LsDataRaw.hasDeidentArtifact()` 단일 원천 — **이미 `'F'` 인 영상은 통과**해 기존 409(재비식별 진행 중) 경로 유지 | security | P0 | DeidentReportService.java |
| TC-LABEL-130 | rawSn 신고 인가 축 = verifyRawAccess (신규) | WORKER 미배정 rawSn | 신고 요청 | 403 — 영상 조회 **이전**에 평가(미인가자에게 존재 여부 미노출) | security | P0 | DeidentReportService.java · LabelAccessGuard.java |
| TC-LABEL-131 | srcSn 경로도 파생/미수행 412 (신규) | 파생 프레임 · 비식별 미수행 프레임 | POST `/v1/labels/{srcSn}/deident-report` | 412 — 두 진입점이 `doReport` 로 수렴해 갈라질 수 없음(차이는 인가 축 + 통지 srcSn 뿐) | integration | P0 | DeidentReportService.java |
| TC-LABEL-132 | resolve 시 DeidentGateReopenedEvent 항상 발행 (신규) | 미승인 영상 신고 → resolve | — | 승인 여부 무관 발행(보류된 VLM 시계열 위탁 재개용). 미발행 시 시계열 메타 영구 결손 | integration | P0 | DeidentReportService.java |
| TC-LABEL-133 | DeidentReportResolvedEvent 는 APPROVED만 (신규) | 미승인 영상 resolve | — | 미발행(불필요한 v1 export 생성 방지). APPROVED 면 발행 → export 재산출·관제 재통지 | integration | P1 | DeidentReportService.java |
| TC-LABEL-134 | 신고·해소 시 스트림 메타 캐시 무효화 (신규) | 재생 중 신고 | — | `evictAfterCommit(rawSn)` — 대상은 **이 영상 하나**(파생 캐시 미접촉) | integration | P1 | DeidentReportService.java |
| TC-LABEL-135 | 오토라벨 신고 구간 차단 순서 (신규) | 신고로 잠긴 영상 / 락 없이 `'F'` | POST autolabel | 잠김이면 **409**(기존 규약 보존), 락 없이 `'F'` 면 **412**(라벨 계열 관례). 작업락 판정이 먼저 | security | P0 | AutolabelOnlineService.java |
| TC-LABEL-136 | 검출 좌표 상한 clamp (신규 · C-ISSUE-41) | 1280x720 | ai 응답 x2=1300 | 1280 으로 clamp 후 반환(400 아님) | integration | P1 | DetectionBoxNormalizer.java |
| TC-LABEL-154 | ★마킹 단계 신고는 `MARKING_READY` 에서만 접수 (신규 · 구속 · V171) | 배치 단계가 `PROCESSING`/`COMPLETED`/`FAILED` | POST `/v1/videos/{rawSn}/deident-report` | **412** + 부수효과 0. 이 상태에는 프레임·라벨이 아직 없어 재마킹이 파괴할 작업 결과가 없다는 것이 제한의 근거다. 거부 문구는 배치 단계를 노출하지 않는다(CWE-209) | security | P0 | DeidentReportService.java(`requireMarkingStageAllowed`) · B [TC-DEID-094/095](B-batch-deidentify.md) ⚠ **ID 충돌 정정(2026-08-05 머지)** — 구 `TC-LABEL-150`. main 이 같은 번호대(`TC-LABEL-150`/`150a`/`150b`, C-ISSUE-61/62 우회 차단)를 먼저 점유했다. |
| TC-LABEL-155 | 라벨링 단계 신고는 배치 단계 제한을 받지 않음 (신규) | 검수 완료 영상(배치 단계 `COMPLETED`) | POST `/v1/labels/{srcSn}/deident-report` | **201** — 마킹 제한이 라벨링으로 새면 "검수 완료 후 신고"라는 정상 동선이 막힌다 | integration | P0 | DeidentReportControllerTest.labelReportUnaffectedByBatchStage ⚠ **ID 충돌 정정(2026-08-05 머지)** — 구 `TC-LABEL-151`. main 이 같은 번호대(`TC-LABEL-150`/`150a`/`150b`, C-ISSUE-61/62 우회 차단)를 먼저 점유했다. |
| TC-LABEL-156 | ★해소 후 재개 지점이 신고 단계로 갈린다 (신규 · 구속 · V171) | `DCLR_STP_CD`=MARKING / LABELING | resolve 성공 | **MARKING** → 배치 단계 `MARKING_READY` 되감기 + 활성 마킹 종결(= 마킹부터 다시) · **LABELING** → 프레임 이미지만 재추출(마킹 유지 · **라벨 좌표 보존**, `SRC_SN` 불변) | integration | P0 | DeidentStageResumeService · B [TC-DEID-099/100](B-batch-deidentify.md) ⚠ **ID 충돌 정정(2026-08-05 머지)** — 구 `TC-LABEL-152`. main 이 같은 번호대(`TC-LABEL-150`/`150a`/`150b`, C-ISSUE-61/62 우회 차단)를 먼저 점유했다. |
| TC-LABEL-157 | 라벨링 단계 재개 후 기존 라벨이 그대로 살아있다 (신규) | 라벨 N건 보유 프레임 → 신고 → resolve | 라벨 조회 | 프레임 이미지만 새 비식별본으로 교체되고 `LS_DATA_LBL` 행·좌표는 **불변**. 재추출이 `LS_DATA_SRC` 를 **dirty-update** 하므로 라벨 FK(`SRC_SN`)가 끊기지 않는다 | integration | P0 | DeidentFrameAttacher.attachDeidentFrames(refreshExisting=true) · B [TC-DEID-100/105](B-batch-deidentify.md) ⚠ **ID 충돌 정정(2026-08-05 머지)** — 구 `TC-LABEL-153`. main 이 같은 번호대(`TC-LABEL-150`/`150a`/`150b`, C-ISSUE-61/62 우회 차단)를 먼저 점유했다. |
| TC-LABEL-137 | 검출 좌표 음수 clamp (신규 · C-ISSUE-41) | ai 응답 x1=-1.57 | — | 0 으로 clamp 후 **정상 반환**. 구 동작(음수 1건 → 프레임 전체 400, 실측 5프레임 중 4프레임 실패) 폐기 | integration | P0 | DetectionBoxNormalizer.java · AutolabelOnlineService.java |
| TC-LABEL-138 | 퇴화 박스 검출 단위 스킵 (신규 · C-ISSUE-41) | clamp 후 x2≤x1 또는 y2≤y1 | — | **해당 검출만** 제외 + WARN, 같은 프레임의 정상 검출은 반환(400 아님) | integration | P0 | DetectionBoxNormalizer.java · AutolabelOnlineService.java |
| TC-LABEL-139 | bounds 미상 시 하한만 clamp (신규) | 치수 측정 실패 | 경계 초과 좌표 | 상한 없음(`Double.MAX_VALUE`)으로 취급, 하한 0 clamp 만 적용 — 정상 작업 전면 차단 방지 | unit | P1 | DetectionBoxNormalizer.java · AutolabelOnlineService.java |
| TC-LABEL-140 | SAM 폴리곤은 clamp 미적용(거부 유지) (신규) | POLYGON 경로 SAM 응답에 음수 | — | 그 박스만 스킵(부분 성공). **BBOX=clamp / SAM 폴리곤=거부** 는 원천 특성에 맞는 정합 상태이며 비대칭 결함 아님 | unit | P1 | AutolabelOnlineService.java |
| TC-LABEL-141 | `/deid-image` 정상 200 (신규) | 배정 WORKER, `DE_IDNTF_SRC_FILE_PATH_NM` 실재 | GET `/v1/frames/{srcSn}/deid-image` | 200 image/jpeg 또는 image/png · Content-Length · `X-Content-Type-Options: nosniff` · Content-Disposition 은 **srcSn + MIME 파생 확장자로만** 조립(파일명 유래 문자열 미사용 — CWE-113) | integration | P1 | FrameImageController.java · FrameImageService.java |
| TC-LABEL-142 | `/deid-image` 원본 폴백 **없음** → 404 (신규) | `DE_IDNTF_SRC_FILE_PATH_NM` null/blank 또는 파일 부재 | 동일 | **404** "비식별 이미지 파일이 존재하지 않습니다" — `SRC_FILE_PATH_NM` 이 채워져 있어도 **원본을 서빙하지 않는다**(메서드가 `getSrcFilePathNm()` 를 참조조차 하지 않음). verdict BLANK/MISSING/NOT_REGULAR_FILE/REALPATH_FAILED 전부 404 로 수렴 | security | P0 | FrameImageService.java |
| TC-LABEL-143 | `/deid-image` 권한 범위가 `/image` 와 다름 (정정 2026-08-03) | PORTAL_USER 토큰(`channel=PORTAL`) | GET `/deid-image` vs GET `/image` | **양쪽 모두 403.** `SecurityConfig` 의 채널 격리(`/v1/portal/**` 외 `/v1/**` = `CHANNEL_INTERNAL`)가 `@PreAuthorize` **도달 이전에** 차단하기 때문 — 실동작 2회차 실측(1차 C-ISSUE-62 · 3차 C-ISSUE-63). 역할 집합 차이(`/deid-image`=REVIEWER,WORKER / `/image`=+PORTAL_USER)는 **애노테이션 계층에만 존재**하며 `/image` 의 `PORTAL_USER` 는 현 배선상 **도달 불가 표기**다. 구 기대값("`/image` = 통과")은 **폐기** | security | P0 | FrameImageController.java · SecurityConfig(채널 격리) |
| TC-LABEL-144 | `/deid-image` 신고 구간 412 + 평가 순서 고정 (신규) | `DE_IDNTF_YN='F'` | 미배정 WORKER / 배정 WORKER 각각 요청 | 미배정=**403**(인가 먼저), 배정=**412**. 순서는 ①인가(`verifyAndGet`) → ②신고게이트 → ③경로해석 고정 — 게이트를 앞에 두면 미배정자가 412/404 차이로 프레임 존재를 탐색할 수 있음 | security | P0 | FrameImageLookupService.java · FrameImageService.java |
| TC-LABEL-145 | `/deid-image` 심링크 TOCTOU 차단 (신규) | 판정(`toRealPath`) 통과 후 open 직전 최종 컴포넌트를 원본 프레임 심링크로 교체 | 동일 | 링크를 따라가지 않고 실패 → **404**(fail-closed). 크기·스트림 모두 `LinkOption.NOFOLLOW_LINKS` 로 읽어 판정 대상과 응답 대상이 어긋나지 않음(CWE-367/59) | security | P0 | FrameImageService.java |
| TC-LABEL-146 | `/deid-image` 비식별 서브트리 밖 경로 403 (신규) | `DE_IDNTF_SRC_FILE_PATH_NM` 이 deid base 밖 | 동일 | **403** "허용되지 않은 이미지 경로입니다"(verdict default). 경로 판정은 `StorageSubtreePolicy.verifyDeidentifiedFile` **단일 판정기**에 위임 — 컨트롤러/서비스에서 재구현하지 않음 | security | P0 | FrameImageService.java |
| TC-LABEL-147 | `/deid-image` 응답 `Cache-Control: no-store` (신규) | 정상 200 | 응답 헤더 검사 | `Cache-Control: no-store`. 검증자(ETag/Last-Modified)가 없어 `no-cache` 로는 대역폭 이득 없이 디스크 캐시 잔존만 남으므로 `no-store` 로 통일 — 캐시된 마스킹 실패 이미지가 412 게이트를 우회해 재노출되는 창 차단(CWE-359/525) | security | P0 | FrameImageService.java |
| TC-LABEL-148 | `/v1/frames/{srcSn}/image` 응답 `no-store` (정정 2026-08-03) | 정상 200 | 응답 헤더 검사 | `Cache-Control: no-store` — 캐시 재사용 시 신고 게이트가 매 요청 재평가되지 않아 무력화된다. ⚠ 구 사유 서술("이 경로는 비식별 판정 없이 **원본 프레임**을 서빙하므로")은 **폐기** — 2026-07-30 확정 정책으로 이 경로도 `serveBySrcSn`→`serveFrame` 공용 판정기를 타 **기본 DEID** 를 서빙한다(REVIEWER `raw=true` 만 원본). 실측: WORKER 요청 Content-Length = 비식별 파일 크기, 응답 `frameImageType=DEID` | security | P0 | FrameImageService.java · (serveBySrcSn) · (serveFrame 정책) |
| TC-LABEL-149 | `/v1/videos/{rawSn}/frames/{frameNo}/image` 응답 `no-store` (신규) | 정상 200(REVIEWER raw=true 포함) | 응답 헤더 검사 | `Cache-Control: no-store`. 이 경로도 `verifyRawAccess`(IDOR) → 신고게이트(412) 통과 후 서빙되므로 매 요청 재평가가 성립해야 함 | security | P0 | VideoController.java · FrameImageService.java |
| TC-LABEL-150 | 존재하지 않는 `id` 로 좌표 상한·점개수 상한 우회 차단 (신규 3차 · **2026-08-04 수정 반영 → PASS**) | 320x240 프레임, 그 프레임에 없는 `id`(예: 99999999) | ①`{"id":99999999, points:[[999999,888888],…]}` ②`{"id":99999998, points: 1500점}` | **①400**(TC-113 과 동일 — 그 프레임에 없는 id 는 저장 분기가 **신규 라벨로 생성**하므로 상한 검증 대상이다) · **②400**(TC-113 의 `MAX_POINTS_PER_LABEL` 1000 상한). 판정 술어는 저장 분기와 **동일한 단일 술어** `isNewLabel(item, idIndex)`(= `id==null || !idIndex.containsKey(id)`)로 통일됐고, 사전검증이 `idIndex` 적재 **이후**로 옮겨져 성립한다. 대조: `USE_YN` 축(TC-120)의 `isNewLabelAssignment` 도 같은 술어를 재사용한다. ⚠ 구 기재 *"3차 실측 = ①②모두 200(우회 성립) → FAIL"* 은 **폐기**(C-ISSUE-61/62 수정 완료) | security | P0 | LabelService.java(사전 idIndex) · (사전검증 술어) · (저장 분기 동일 술어) · (isNewLabel) · LabelSaveGuardsIT |
| TC-LABEL-150a | **타 프레임에 실재하는** id 로 좌표 상한 우회 차단 (신규 2026-08-04 · C-ISSUE-61/62 회귀 가드) | 같은 영상의 프레임 A·B. A 에 정상 라벨 1건 존재, B 는 64x48 | B 저장 요청에 **A 의 실재 라벨 id** + B 경계 밖 좌표 | **400** — `idIndex` 는 `findBySrcSn(B)` 로만 채워지므로 A 의 id 는 "이 프레임에 없는 id"=신규로 판정되어 상한이 강제된다(판정 축은 "id 실재 여부"가 아니라 "**이 프레임에** 실재하는가"). 추가 확인: **A 의 라벨이 수정·이관·삭제되지 않음**(`SRC_SN`·좌표 불변) + B 에 미적재. TC-150(ghost id)이 못 덮는 세 번째 동치클래스 | security | P0 | LabelService.java(isNewLabel) · LabelSaveGuardsIT |
| TC-LABEL-150b | 혼합 배열 all-or-nothing (신규 2026-08-04 · 부분 저장 방지 회귀 가드) | 64x48 프레임, 기존 라벨 1건 | 한 요청에 ①기존 라벨(정상 좌표) ②신규 정상 좌표 ③ghost-id + 경계 밖 좌표 를 **같은 배열**로 전송 | **400** + **정상 아이템(②)도 미저장**, 기존 라벨 좌표 불변, **labelVersion 미증가**. 성립 근거는 2중 — ①좌표 사전 검증 루프가 **어떤 쓰기보다 먼저** 전 아이템을 훑어 차단 ②`bulkUpsert` 가 단일 `@Transactional` 이라 전체 롤백(부분 저장 창 없음) | integration | P0 | LabelService.java(사전검증 루프) · (@Transactional) · LabelSaveGuardsIT |
| TC-LABEL-158 | ★라벨 이력 `actorName` 표시명 신규 추가 (신규 · 2026-08-05, 계약변경) | 저장 이벤트 1건, 작성자 사번 존재 | GET `/v1/frames/{srcSn}/label-history` | 응답에 **`actorName`**(`LS_ACNT_USER.USER_NM` 표시명) 신규 추가. `actor` 는 **하위호환으로 계속 사번을 담는다**(이름으로 바꿔치기 금지) — 이름 자리에 사번이 찍히던 결함의 회귀 가드 | integration | P1 | LabelHistoryResponse.java · LabelService.java · LabelHistoryActorNameTest.actorNameIsResolvedAndActorStaysUserNo |
| TC-LABEL-159 | 라벨 이력 작성자 다수 섞여도 사용자 조회 1회 (신규) | 이력 5건·작성자 3명(중복 포함) | GET `/v1/frames/{srcSn}/label-history` | `userRepository.findByUserNoIn` 정확히 1회, 중복 제거된 사번만 전달(N+1 금지) | integration | P1 | LabelService.java · UserNameResolver.java · LabelHistoryActorNameTest.userLookupIsBatchedIntoSingleQuery |
| TC-LABEL-160 | 라벨 이력 — 비숫자 사번은 예외 없이 이름 null (신규) | `REG_ID` 비숫자(VARCHAR, 레거시/외부 채널) | GET `/v1/frames/{srcSn}/label-history` | 예외 없이 200, `actorName=null`·`actor`=원값 유지, 파싱 가능 사번이 하나도 없으면 사용자 조회 자체 미실행(불필요 쿼리 제거) | unit | P1 | UserNameResolver.java · LabelHistoryActorNameTest.nonNumericUserNoYieldsNullName |
| TC-LABEL-161 | 라벨 이력 — 사용자 마스터 미존재 사번은 이름 null·조회 200 (신규) | 퇴사·계정 삭제로 마스터에 없음 | GET `/v1/frames/{srcSn}/label-history` | 예외 없이 200, `actorName=null` — 과거 작성자가 삭제·변경돼도 이력 조회 자체는 살아있다 | integration | P1 | UserNameResolver.java · LabelHistoryActorNameTest.unknownUserNoYieldsNullNameButStillReturns |
| TC-LABEL-162 | 라벨 이력 — 시스템 이력행(`REG_ID` null)은 사번·이름 모두 null (신규) | `REG_ID` 미기록 이력(트랙 삭제 등) | GET `/v1/frames/{srcSn}/label-history` | `actor=null`·`actorName=null`, 사용자 조회 미실행 | unit | P2 | LabelHistoryActorNameTest.systemRowHasNullActorAndName |

> **`no-store` 5경로 중 C 소관은 3경로**다(TC-LABEL-147/148/149). 나머지 2경로는 `GET /v1/videos/{rawSn}/stream`(B 담당,
> `VideoStreamService.java:286-296`)·`GET /v1/portal/frames/{srcSn}/image`(F 담당, `PortalLabelService.java:455`).
> **포털 업로드 자산(`GET /v1/portal/uploads/frames/{uldFrmeSn}/image`)은 이 통일 대상이 아니다** — 포털 사용자 **본인이
> 업로드한 자산**(`LS_PORTAL_ULD_FRME`)이라 비식별 처리 대상이 아니고 `LS_DATA_RAW.DE_IDNTF_YN` 라이프사이클 자체가 없다
> (ADR-013 예외, 내부 파이프라인·데이터마트와 완전 분리 — `PortalUploadService.java:195-201`). 5경로 전수 확인 시 이 경로를
> "누락"으로 세지 말 것.

---

## C-3. TC-SAM2 — SAM2 분할 / 트랙 프록시

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|---|---|---|---|---|---|:--:|---|
| TC-SAM2-01 | segment 정상 | 배정 | points 또는 box | 200 폴리곤+신뢰도(미저장). **클릭 프롬프트 `points` 는 1 점이 정상 입력**(공용 검증기에 최소 정점 수를 넣으면 이 경로가 400 으로 죽는다 — 회귀 가드) | integration | P1 | Sam2SegmentService.java · Sam2CoordinateValidator.java |
| TC-SAM2-02 | segment path/body srcSn 불일치 | 불일치 | — | 400(CWE-345) | security | P1 | LabelController.java |
| TC-SAM2-03 | points/box 배타 위반(둘 다) | points+box | — | 400 @AssertTrue | unit | P1 | Sam2SegmentRequest.java |
| TC-SAM2-04 | points/box 배타 위반(둘 다 빈) | 둘 다 null | — | 400 @AssertTrue | unit | P1 | Sam2SegmentRequest.java |
| TC-SAM2-05 | segment IDOR | WORKER 미배정 | — | 403 | security | P0 | Sam2SegmentService.java |
| TC-SAM2-06 | segment 경로순회 차단 (정정) | ".." | — | 400/403. **`Sam2SegmentService` 는 경로를 조립하지 않는다** — `FrameImageEncoder.resolveFrameImageForInference` 에 위임하며 차단은 그쪽 `resolveSafe` 가 수행한다. (정정: 구 기재의 로컬 `resolveSafe`(구 :198)는 호출부 0건 dead code 였고 클래스 javadoc 이 없는 보호를 주장했다 → **메서드 삭제 + javadoc 을 위임 지점으로 정정**) | security | P0 | Sam2SegmentService.java · (javadoc — 위임 명시) · FrameImageEncoder.java |
| TC-SAM2-07 | segment 이미지 미존재 | 파일 없음 | — | 404 | unit | P1 | Sam2SegmentService.java |
| TC-SAM2-08 | segment 이미지 크기 초과 | >maxImageBytes | — | 413 PAYLOAD_TOO_LARGE | integration | P1 | Sam2SegmentService.java |
| TC-SAM2-09 | segment mock→빈 폴리곤+메시지 | ai mock=true | — | empty, MOCK 메시지(FE 자동적용 차단) | integration | P0 | Sam2SegmentService.java · LabelController.java |
| TC-SAM2-10 | segment 폴리곤 정점<3 | 2점 | — | 502 | unit | P1 | Sam2SegmentService.java |
| TC-SAM2-11 | segment 좌표 경계초과 | x>imgWidth | — | 502(외부응답 불신). ※이미지 경계 상한은 **segment 전용** — track·오토라벨은 의도적으로 미적용(별건 **C-ISSUE-82 ③**. 정정 2026-08-03: 구 기재 "C-ISSUE-61"은 `GET /v1/deident-reports` 정렬 키 이슈로 무관한 오참조였다). **score 클램프도 segment 전용** — segment 만 `clampScore` [0,1]·4자리 반올림(:242-246)이고 track 은 ai 원본값을 그대로 싣는다(실측 `score:0.9834924936294556`) | security | P1 | Sam2SegmentService.java |
| TC-SAM2-12 | segment simplifyTolerance 범위 | 60 | — | 400 @DecimalMax(50) | unit | P2 | Sam2SegmentRequest.java |
| TC-SAM2-13 | segment 단순화 3점 미만→원본유지 | simplify 2점 | — | 원본 유지 | unit | P2 | Sam2SegmentService.java |
| TC-SAM2-14 | track 정상 POLYGON | 배정 | nextSrcSns 순회 | 200 프레임별 폴리곤(미저장) | integration | P1 | Sam2TrackService.java |
| TC-SAM2-15 | track path/body srcSn 불일치 | 불일치 | — | 400 | security | P1 | LabelController.java |
| TC-SAM2-16 | track nextSrcSns 50 초과(경계) | 51개 | — | 400 @Size(max=50). ※FE 가 무제한 전송해 "추적 실패"로 보이던 계약 버그의 서버측 상한 | security | P0 | label/Sam2TrackRequest.java |
| TC-SAM2-17 | track nextSrcSns 빈 | [] | — | 400 @NotEmpty | unit | P1 | label/Sam2TrackRequest.java |
| TC-SAM2-18 | track prevPolygon <3점 | 2점 | — | 400 @Size(min=3) | unit | P1 | label/Sam2TrackRequest.java |
| TC-SAM2-19 | track prevPolygon >1000점 | 1001점 | — | 400 @Size(max=1000) | unit | P2 | label/Sam2TrackRequest.java |
| TC-SAM2-20 | track trackId 64자 초과 | 65자 | — | 400 @Size(max=64) | security | P1 | label/Sam2TrackRequest.java |
| TC-SAM2-21 | track IDOR 시작+후속 각각 | WORKER 미배정 후속 | — | 후속도 403(AI 호출 이전) | security | P0 | Sam2TrackService.java |
| TC-SAM2-22 | track 후속 프레임 미존재 (근거 정정) | nextSrcSn 없음 | — | 404. **정정 2026-08-03**: 실제 404 는 루프 선두의 인가 검사(`accessGuard.verifyAccess` → `LabelAccessGuard.verifyAndGet` 의 `findById` orElseThrow)가 낸다 — 실측 메시지가 `"프레임을 찾을 수 없습니다."`(가드 문구)이고 `:102` 의 `"후속 프레임을 찾을 수 없습니다: {id}"` 가 **아니다**. 즉 구 근거 `:101-102` 는 도달하지 않는 방어심층이다 | unit | P1 | Sam2TrackService.java · LabelAccessGuard.java (도달 불가 방어심층: Sam2TrackService.java) |
| TC-SAM2-23 | track ai 응답 폴리곤 **좌표 형식** 검증 (재정정) | 음수/비유한/[x,y] 아님 | — | **400 INVALID_INPUT** — 좌표 형식 규칙은 `Sam2CoordinateValidator.validatePolygon` 이 담당하며 그대로 400 이다. ※구 기재의 "정점부족(<3점) 조건은 이 경로에 없음"은 **폐기** — 코드 수정으로 최소 정점 수 검증이 추가됐고 그 위반은 **502**다(TC-SAM2-34). 한 호출 안에서 두 축이 공존하며 정점 수를 먼저 판정한다 | security | P1 | Sam2TrackService.java · Sam2CoordinateValidator.java |
| TC-SAM2-24 | track BBOX 외접박스 산출 | shape=BBOX | — | [[minX,minY],[maxX,maxY]] | unit | P2 | Sam2TrackService.java |
| TC-SAM2-25 | track 퇴화 bbox 프레임 스킵 | 폭/높이<1px | — | 해당 프레임만 스킵(전체 추적 미중단). ※이 스킵은 **BBOX 형태에서만** 동작 — POLYGON 형태의 퇴화(정점<3)는 응답 검증(TC-SAM2-34)이 502 로 막는다 | unit | P2 | Sam2TrackService.java |
| TC-SAM2-26 | track shape 기본 POLYGON | shape=null | — | POLYGON 정규화 | unit | P2 | Sam2TrackService.java |
| TC-SAM2-27 | track ai 호출 실패 502 | Exception | — | EXTERNAL_API_ERROR | security | P1 | Sam2TrackService.java |
| TC-SAM2-28 | track trackId 로그 sanitize (보강) | CRLF | — | **CRLF 는 요청 단계에서 400 으로 먼저 차단**된다(`@Pattern("^[A-Za-z0-9._:-]+$")`) — 실측 `{"message":"trackId: 트랙 ID 는 영숫자와 . _ : - 만 사용할 수 있습니다.","errorCode":"INVALID_INPUT"}` 400. `LogSanitizer` 정제(:139-140, :171)는 **심층방어**이며 CWE-117 의 1차 방어선이 아니다(패턴이 완화되면 그때 유일한 방어가 된다) | security | P2 | label/Sam2TrackRequest.java · Sam2TrackService.java |
| TC-SAM2-29 | segment 신고 구간 412 (신규) | `DE_IDNTF_YN='F'` | POST sam2-segment | **412** — 파일을 **읽기도 전에** 차단(전송 후 폐기가 아님) | security | P0 | Sam2SegmentService.java · FrameImageEncoder.java |
| TC-SAM2-30 | track 신고 구간 412 (신규) | 동일 | POST sam2-track | 412 — 시작·후속 프레임 인코딩이 모두 `encodeFrame` 경유 | security | P0 | Sam2TrackService.java · FrameImageEncoder.java |
| TC-SAM2-31 | 게이트 없는 base64 오버로드 부재 (신규, 구조 단언) | — | 소스 스캔 | `FrameImageEncoder` 에 `encodeToBase64(String)` public 쌍둥이가 **존재하지 않음**. 경로 문자열 진입점 없이 `LsDataSrc` 를 받는 메서드만 public — 포털 SAM2 가 원본 픽셀을 ai-server 로 보내던 경로 차단(3630558d) | security | P0 | FrameImageEncoder.java |
| TC-SAM2-32 | 게이트 없는 해석기는 패키지 전용 (신규, 구조 단언) | — | 소스 스캔 | `resolveFrameImageWithoutGate` 는 package-private. **정정 2026-08-03**: 유일한 소비자 `FrameBoundsResolver` 는 `kr.co.cudo.authoring.label.service` 로 `FrameImageEncoder` 와 **동일 패키지**다(구 기재 "패키지 외 소비자"는 사실 오류 — 패키지 밖 소비자는 0건이라는 것이 이 케이스의 단언이다). 그 소비자는 **치수만** 읽고 픽셀을 밖으로 내보내지 않음. ⚠ 프로덕션 javadoc `FrameImageEncoder.java:86` 도 같은 오기("유일한 패키지 외부 소비자")를 갖고 있다 | security | P1 | FrameImageEncoder.java · FrameBoundsResolver.java |
| TC-SAM2-33 | 비식별 우선 폴백 경로 해석 (신규) | 해상도 파생 프레임(`SRC_FILE_PATH_NM`=null) | segment/track/autolabel | 400 "이미지 경로가 비어있습니다" 가 아니라 비식별 경로로 해석되어 정상 추론 | integration | P1 | FrameImageEncoder.java |
| TC-SAM2-34 | track ai 응답 폴리곤 **정점<3** (신규) | ai-server 가 2점 이하 폴리곤 반환(좌표 자체는 유효) | POST sam2-track | **502 EXTERNAL_API_ERROR** — 클라이언트 입력 오류가 아니라 외부 시스템이 잘못 준 것이라 400 은 의미가 틀리다(segment 응답 검증 TC-SAM2-10 과 동일 규약). 단순화(Douglas-Peucker)는 결과가 3점 미만이면 **원본을 그대로 반환**하므로 뒤에서 걸러지지 않아 여기서 막지 않으면 퇴화 폴리곤이 응답에 실려 라벨로 저장된다(CWE-20) | security | P1 | Sam2TrackService.java · Sam2CoordinateValidator.java |
| TC-SAM2-35 | 요청 축/응답 축 분리 (신규, 회귀 가드) | ①segment `points` 1점(클릭 프롬프트) ②track `prevPolygon` 2점 | ①POST sam2-segment ②POST sam2-track | ①**정상 200** — 공용 `validatePolygon` 에 최소 정점 수를 넣으면 클릭 분할이 400 으로 죽으므로 그 규칙은 응답 전용 메서드로 분리돼 있어야 한다 ②**400** `@Size(min=3)`(Bean Validation) — 요청 축은 400, 응답 축은 502 로 섞이지 않는다 | security | P0 | Sam2CoordinateValidator.java · Sam2SegmentService.java · label/Sam2TrackRequest.java |
| TC-SAM2-36 | **track mock→해당 프레임 제외 + 전량 안내** (신규 · C-ISSUE-81 대응) | ai-server 가 mock 응답(`mock=true` 또는 `source≠"model"`) | POST sam2-track, 전 프레임 mock | **200 + `tracked:[]` + `message="AI 모델 미로드 — 결과 신뢰 불가"`**. ai-server `_mock_track`/`_prev_polygon_fallback` 은 **시드 폴리곤을 그대로 복사**하고 `score=0.9` 를 부여하므로 거르지 않으면 "N 프레임 추적"이 "시드 N개 복제"로 둔갑하고 FE 저신뢰 분기에도 안 걸린다(CWE-345). ⚠ 판정은 **긍정 증명**(`AiMockMeta.untrusted` = `mock \|\| !"model".equals(source)`) — `mock()` 만 보면 필드 생략 JSON 이 primitive 기본값 `false` 로 fail-open 된다. mock 플래그는 FE-facing DTO 로 노출하지 않고 `Sam2TrackOutcome` 으로만 컨트롤러에 전달 | security | P0 | Sam2TrackService.java · common/client/dto/Sam2TrackResponse.java · common/client/dto/AiMockMeta.java · label/dto/Sam2TrackOutcome.java · LabelController.java |
| TC-SAM2-37 | **track 일부 프레임만 mock → 실결과 유지 + 부분 안내** (신규) | 후속 N 프레임 중 일부만 mock | 동일 | **200 + 실모델 프레임만 `tracked`** + `message=`{@code AutolabelResponse.POLYGON_PARTIAL_MOCK_MESSAGE}. 전량 실패로 처리하면 정상 추적분까지 버려지고, 안내를 생략하면 구멍 난 추적을 사용자가 모른다. 전 프레임 실모델이면 `message=null`(과차단 회귀 가드). ⚠ mock 프레임도 **전파(`currentPolygon`)는 이어간다** — mock 폴리곤은 입력 시드와 동일해 새 좌표가 유입되지 않으며 다음 프레임에서 실모델이 회복할 수 있다. FE 는 청크(50) 중 첫 안내를 보존해 최종 반환 | security | P0 | Sam2TrackService.java · label/dto/Sam2TrackOutcome.java · frontend `features/label/api.ts` · `hooks/useSam2Track.ts` |

> **경계(설계상 정당 — 케이스 아님)**: 배치 `YoloAutolabelStep`·`Sam2SegmentStep` 은 이 인코더를 지나지 않고 **원본** 프레임을
> ai-server 로 보낸다. 배치 입력은 정책상 항상 원본이라 신고 게이트를 붙여도 새로 보호되는 픽셀이 없다.
> "모든 전송이 `FrameImageEncoder` 를 통과한다"고 단언하는 케이스를 쓰지 말 것.

---

## C-4. TC-KEYPOINT — 17-keypoint COCO SKELETON

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|---|---|---|---|---|---|:--:|---|
| TC-KEYPOINT-01 | SKELETON 정상 저장 | lblTypeCd=SKELETON | 17개 [x,y,v] | 200, 삼중값 직렬화 | integration | P1 | LabelService.java |
| TC-KEYPOINT-02 | 개수≠17 | — | 16개 | 400 | unit | P1 | LabelService.java |
| TC-KEYPOINT-03 | 삼중값 크기≠3 | — | [x,y]만 | 400 | unit | P1 | LabelService.java |
| TC-KEYPOINT-04 | v null 원소(NPE 방어) | — | [10,20,null] | 400(fail-secure, 언박싱 이전) | security | P1 | LabelService.java |
| TC-KEYPOINT-05 | v 범위 밖 | — | v=3 | 400 "0/1/2 중 하나" | unit | P1 | LabelService.java |
| TC-KEYPOINT-06 | v=0 좌표 0 허용(경계) | — | [0,0,0] | 통과 | unit | P2 | LabelService.java |
| TC-KEYPOINT-07 | 음수 좌표(v>0) | — | x=-1,v=2 | 400 | unit | P1 | LabelService.java |
| TC-KEYPOINT-08 | KeypointSerializer toJson | 정상 리스트 | — | [[x,y,v],...] 결정적 | unit | P2 | KeypointSerializer.java |
| TC-KEYPOINT-09 | fromJson 배열 아님 | 비배열 | — | IllegalArgumentException | unit | P2 | KeypointSerializer.java |
| TC-KEYPOINT-10 | fromJson 삼중값 크기위반 | size≠3 | — | IAE | unit | P2 | KeypointSerializer.java |
| TC-KEYPOINT-11 | fromJson 숫자아님 | 문자열 원소 | — | IAE | unit | P2 | KeypointSerializer.java |
| TC-KEYPOINT-12 | fromJson 빈/[] | "[]" | — | 빈 리스트 | unit | P2 | KeypointSerializer.java |
| TC-KEYPOINT-13 | SKELETON R7 비교 폴백 | 삼중값 무변경 | — | 2-튜플 파서 거부 → raw 숫자배열 폴백 비교 | unit | P2 | LabelService.java |

---

## C-5. TC-TRACK — 트랙 보간 / 좌표변환 / MASK↔RLE / YOLO 트랙 프록시

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|---|---|---|---|---|---|:--:|---|
| TC-TRACK-01 | BBOX 보간 정상 | 2 키프레임 | 사이 프레임 | linearInterpolate 채움 | unit | P1 | batch/interpolation/TrackInterpolator.java |
| TC-TRACK-02 | 빈 키프레임 | [] | — | 빈 결과 | unit | P2 | TrackInterpolator.java |
| TC-TRACK-03 | 단일 키프레임 | 1개 | — | 그 프레임만 | unit | P2 | TrackInterpolator.java |
| TC-TRACK-04 | outside 마커 종료 | outside=true | — | 즉시 종료, 미포함 | unit | P1 | TrackInterpolator.java |
| TC-TRACK-05 | 다음이 outside | next.outside | — | 현재만 저장 후 종료 | unit | P2 | TrackInterpolator.java |
| TC-TRACK-06 | 마지막 이후 propagate 없음 | 마지막 키프레임 | — | 이후 미채움 | unit | P2 | TrackInterpolator.java |
| TC-TRACK-07 | 동일 frame 중복 뒤값 우선 | span≤0 | — | LinkedHashMap 덮어쓰기 | unit | P2 | TrackInterpolator.java |
| TC-TRACK-08 | totalFrames 음수 | -1 | — | IllegalArgumentException | unit | P1 | TrackInterpolator.java |
| TC-TRACK-09 | keyframes null | null | — | NullPointerException | unit | P1 | TrackInterpolator.java |
| TC-TRACK-10 | 폴리쉐이프 보간 정점수 다름 | 다른 정점 | — | PolyshapeMatcher 대응쌍 재사용 | unit | P1 | TrackInterpolator.java |
| TC-TRACK-11 | 폴리쉐이프 closed/open | closed=true/false | — | 폐/개곡선 매칭 | unit | P2 | TrackInterpolator.java |
| TC-TRACK-12 | rotate 중심 기준 | angle,center | — | 회전행렬 적용 | unit | P2 | CoordinateTransformer.java |
| TC-TRACK-13 | rotate points/center null | null | — | IAE. ※(2026-08-04 정정) "전용 단위테스트 부재(C-ISSUE-81)" 비고는 사실이 아님 — `CoordinateTransformerTest.java` 에 `rotateRejectsNullPoints`/`rotateRejectsNullCenter`/`rotateChecksPointsGuardBeforeCenterGuard` 3건 존재·통과(1차 회차에서 이미 지적됐으나 카탈로그 본문 미반영 상태였음, 이번에 정정) | unit | P2 | CoordinateTransformer.java · CoordinateTransformerTest.java |
| TC-TRACK-14 | scale/translate | sx,sy/dx,dy | — | 좌표 변환 | unit | P2 | CoordinateTransformer.java |
| TC-TRACK-15 | maskToRle 첫픽셀 on | flat[0]=true | — | 맨앞 0-run 삽입 | unit | P1 | MaskRleConverter.java |
| TC-TRACK-16 | maskToRle 빈/null | null | — | int[0] | unit | P2 | MaskRleConverter.java |
| TC-TRACK-17 | maskToRle >1M 픽셀(DoS) | 초과 | — | IAE | security | P1 | MaskRleConverter.java |
| TC-TRACK-18 | maskToRle 비직사각형 | row 폭 불일치 | — | IAE | unit | P2 | MaskRleConverter.java |
| TC-TRACK-19 | rleToMask width/height≤0 | 0 | — | IAE | unit | P2 | MaskRleConverter.java |
| TC-TRACK-20 | rleToMask >1M | 초과 | — | IAE | security | P1 | MaskRleConverter.java |
| TC-TRACK-21 | rleToMask 길이합 초과 | rle 합>total | — | IAE | unit | P1 | MaskRleConverter.java |
| TC-TRACK-22 | rleToMask null | null | — | IAE | unit | P2 | MaskRleConverter.java |
| TC-TRACK-23 | round-trip mask→rle→mask | 임의 mask | — | 원본 복원(멱등) | unit | P2 | MaskRleConverter.java |
| TC-TRACK-24 | yolo-track path/body 불일치 | 불일치 | — | 400 | security | P1 | LabelController.java |
| TC-TRACK-25 | yolo-track nextSrcSns 50 초과 | 51개 | — | 400 @Size(max=50) | security | P0 | YoloTrackRequest.java |
| TC-TRACK-26 | yolo-track nextSrcSns 빈 | [] | — | 400 @NotEmpty | unit | P1 | YoloTrackRequest.java |
| TC-TRACK-27 | yolo-track 좌표 clamp (신규 · C-ISSUE-41) | 경계 초과/음수 검출 | — | 이미지 경계로 clamp 후 반환(400 아님). 구 동작(음수 즉시 400)은 폐기된 정책의 잔재였음 | integration | P0 | YoloTrackService.java · DetectionBoxNormalizer.java |
| TC-TRACK-28 | yolo-track NaN/Infinity 400 (신규) | ai 응답 NaN | — | 400. 구 구현은 `NaN<0`=false 로 **검증을 통과해 응답 DTO 에 NaN 이 실렸음** | security | P0 | YoloTrackService.java · DetectionBoxNormalizer.java |
| TC-TRACK-29 | yolo-track 퇴화 박스 검출 단위 스킵 (신규) | 50프레임 시퀀스 중 1건 퇴화 | — | 해당 검출만 스킵 + WARN, **시퀀스 전체 폐기 없음** | integration | P0 | YoloTrackService.java |
| TC-TRACK-30 | yolo-track 교차 영상 혼입 차단 (신규) | nextSrcSns 에 타 rawSn 프레임 | — | 400 — 다른 영상 프레임이 같은 트래커 clipId 세션에 흘러 틀린 trackId 를 반환하던 무결성 결함 차단 | security | P1 | YoloTrackService.java |
| TC-TRACK-31 | 보간 진실원 단일 확인 (신규, 구조 단언) | — | 소스 스캔 | 프로덕션 보간은 `batch/interpolation/TrackInterpolator` 만 사용. 동명이인 `common/util/TrackInterpolator` 는 **여전히 잔존**하며 어떤 프로덕션 코드도 import 하지 않음(C-ISSUE-82 미해소 — dead code) | unit | P2 | batch/interpolation/TrackInterpolator.java · common/util/TrackInterpolator.java |

---

## C-6. TC-PRESET — 라벨 프리셋

> 패키지 이동: `label/service/PresetService` → **`preset/service/PresetService`**. 근거 경로 전량 정정.
>
> **★ 소비처 경계(2026-08-03 재확인)**: 프리셋은 **오토라벨링 전용**이다. 같은 날 신설된 라벨링 화면의 **라벨 선택 모달**(`LabelPickerModal` → [TC-FE-286](H-frontend-e2e.md))은
> 목록 소스로 **라벨 마스터(`LS_LABEL`) 전체**를 쓰며 **프리셋 API 를 호출하지 않는다.** "수동 라벨링에서 프리셋을 고른다"는 기대결과를 만들지 말 것.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|---|---|---|---|---|---|:--:|---|
| TC-PRESET-01 | 생성 정상(labelId 기반) | 유효 labelIds | — | 저장, 코드 문자열(LBL_CD) 미저장 | integration | P1 | preset/service/PresetService.java |
| TC-PRESET-02 | 생성 이벤트타입 무효 | 미유효 | — | 400 | unit | P1 | PresetService.java |
| TC-PRESET-03 | 생성 이벤트타입 빈값 허용 | "" | — | 통과 | unit | P2 | PresetService.java |
| TC-PRESET-04 | 생성 이름 중복 | existsByPresetNm | — | 409 | unit | P1 | PresetService.java |
| TC-PRESET-05 | 생성 labelId 미존재/비활성 | 잘못된 labelId | — | 400 "존재하지 않거나 비활성 라벨입니다" | unit | P1 | PresetService.java |
| TC-PRESET-06 | 생성 이벤트 유니크 경합 | 동시 동일 이벤트 | DataIntegrityViolation | 409 | integration | P0 | PresetService.java |
| TC-PRESET-07 | 수정 미존재 | 없음 | — | 404 | unit | P1 | PresetService.java |
| TC-PRESET-08 | 수정 이름 중복(자기제외) | 타 프리셋명 | — | 409 | unit | P1 | PresetService.java |
| TC-PRESET-09 | 수정 이벤트 유니크 위반 | saveAndFlush | DataIntegrityViolation | 409 | integration | P1 | PresetService.java |
| TC-PRESET-10 | 삭제 멱등 | 미존재 id | — | no-op | unit | P2 | PresetService.java |
| TC-PRESET-11 | 복제 이름 시퀀스 | "X" 존재 | — | "X (복사본)" / "X (복사본 2)" | unit | P2 | PresetService.java |
| TC-PRESET-12 | 복제 이벤트 미상속 | 원본 이벤트 有 | — | 이벤트 null(충돌 회피) | unit | P1 | PresetService.java |
| TC-PRESET-13 | 복제 이름 50회 초과 실패 | 대량 복사본 | — | 409 | unit | P2 | PresetService.java |
| TC-PRESET-14 | 코드뷰 미연결(labelId null) | labelId=null | — | linked=false, legacy 코드 노출(오류 아님) | unit | P1 | PresetService.java |
| TC-PRESET-15 | 코드뷰 마스터 미존재/soft delete | 비활성 labelId | — | linked=false(자동 생성/삭제 없음) | unit | P1 | PresetService.java |
| TC-PRESET-16 | 코드뷰 형태 마스터 파생 | 연결됨 | LBL_TYPE_CD | bbox/polygon 을 마스터에서 파생(프리셋 개별 토글 불가) | unit | P1 | PresetService.java |
| TC-PRESET-17 | 부분유니크 labelId 중복 방지 | 같은 labelId 2회 | — | `UK_LS_LABEL_PRESET_CODE_LBLID` 차단 | integration | P1 | (LsLabelPresetCodeLabelIdUniqueIT) |
| TC-PRESET-18 | N+1 회피 배치 조회 | 다수 코드 | list() | labelId IN 1회 join | integration | P2 | PresetService.java |

---

## 불확실 항목 · 정책 확정 현황

| # | 항목 | 07-25 상태 | 현재(07-30) |
|:-:|---|---|---|
| #2 | 마킹 단계 rawSn 신고 미구현 | 미구현 | **해소** — `POST /v1/videos/{rawSn}/deident-report` 신설(TC-LABEL-127~130) |
| #9 | 동시 저장 Race(락 미도입) | 미확정 | **확정·해소** — 프레임 행 비관적 락 + `labelVersion` 낙관적 CAS(선택 필드), 불일치 409(TC-LABEL-36/110~112) |
| #10 | 좌표 이미지 경계 초과 저장 검증 부재 | 미확정 | **확정·해소** — 사용자 저장 경로는 **400 거부**(클램프 아님, TC-LABEL-113~118) / AI 검출 응답 경로는 **clamp**(TC-LABEL-136~139). 두 축의 정책이 다른 것이 정상 |
| — | C-ISSUE-23 (R7 손상 JSON 회귀 테스트) | LOW 갭 | 미해소 — TC-LABEL-30 비고 유지 |
| — | C-ISSUE-81 (MaskRle/Coordinate 가드 테스트 0%) | MEDIUM 갭 | 미해소 — TC-TRACK-13 비고 유지 |
| — | C-ISSUE-82 (동명이인 dead TrackInterpolator) | LOW | 미해소 — TC-TRACK-31 로 명시 |

> 상세: [UNCERTAINTIES.md](UNCERTAINTIES.md)
