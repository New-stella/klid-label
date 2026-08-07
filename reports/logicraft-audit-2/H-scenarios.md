# 배치 H — 시나리오 전수 (UC 21 / AC 21 / TEST 5)

판정 기준: "이 프로젝트를 전혀 모르는 제3자가 이 시나리오를 읽고 정확히 구현·검증할 수 있는가" + "지금도 사실인가(폐기된 구 정책을 말하고 있지 않은가)".
근거는 `CLAUDE.md(절 제목)` 또는 `파일명(심볼명)` 형식. 원본 JSON 47건 전수를 읽었다(미검토 없음). LogiCraft ITEM 은 읽기 전용으로만 다뤘다(수정 없음).

## 요약

- 발견: **STALE 3** / **ERR 3** / **GAP 12** / **LINK 10건(개별 UC) + 1 시스템성** / **COVER 7화면(제안 대상) 중 3건은 링크로 해결, 4건은 신규 UC 필요**
- 전수 커버: UC 21/21, AC 21/21, TEST 5/5 — 미검토 없음.
- 최중대 발견
  1. **[GAP-H01]** UC-018(영상 적재)·UC-019(이벤트 마킹) — 저작도구 배치 파이프라인의 두 핵심 유스케이스가 **AC 0건**이다(21개 AC 중 어느 것도 derived_from_use_cases 에 UC-018/UC-019 를 담지 않음).
  2. **[LINK-H01]** UC-016 → `SCREEN-008`, UC-022 → `SCREEN-015` — 두 화면 모두 **파일시스템에 존재하지 않는 폐기 화면**이며, 실제 기능은 각각 `SCREEN-032`(비식별 신고 관리)·`SCREEN-005`(라벨링 캔버스 시계열 메타 패널)로 이관되어 있다.
  3. **[STALE-H01]** UC-019 본문이 "마킹 결과(이벤트명+영상경로+marks 배열)는 VLM 콜백으로 전달"이라 서술 — `CLAUDE.md` 가 명시적으로 폐기한 구 서술이 그대로 남아 있다. 실제는 `frame_policy`(frame_selected/frame_interval) + `framerate` + `event_type` 조합이다.

---

## 1. UC 별 판정

### UC-001 증강 영상 생성 요청 — **현행성: 정확**
ADR-044(중복요청 차단 폐기)·prompt 5필드 필수·파생 깊이 1 고정(400)·신고구간 412 구분을 모두 정확히 반영. `CLAUDE.md("증강 = 새 영상")` 절과 완전 정합.
- **related_screens 제안**: `SCREEN-022`(증강 요청 화면, route=`/augment`) — 근거: `screen_spec/_raw/SCREEN-022.json` purpose. 확신도: high.
- **[GAP-H02]** `alternate_flows: []` — 그러나 본문(description)에는 "[거부 조건] ①파생본 400 ②신고구간 412"가 서술되어 있다. 구조화된 `alternate_flows` 필드에 이 두 케이스가 옮겨지지 않아, 필드만 기계적으로 읽는 소비자(예: 테스트 생성기)는 이 거부 조건을 놓친다. 확신도: high.
- **[GAP-H03]** `covered_by_acceptances: []` — 실제로는 AC-001 이 `derived_from_use_cases: [UC-001]`로 이 UC 를 커버한다. 역방향 링크 미기록(아래 시스템성 LINK 항목 참조).

### UC-002 증강 결과 수신·등록 — **현행성: 정확**
파생 비디오 실제 복사, 부모 게이트가 `'N'`만 차단(`'F'`는 통과), 식별자 유일화(DATA_AUG_SN 기반) 등 `CLAUDE.md("증강 = 새 영상")` 최신 정책과 완전 일치.
- **related_screens 제안**: `SCREEN-023`(증강 결과 화면, route=`/augment/result/:jobId`) — REVIEWER 가 등록된 결과를 확인하는 화면. 확신도: high.
- **[ERR-H01]** `actor: "검수자(REVIEWER)"` — 그러나 `main_flow` 7단계 전부 actor 가 "시스템" 또는 명시 없음(외부 서비스 콜백 트리거)이며 REVIEWER 가 수행하는 스텝이 하나도 없다. UC-010(활용 검수)과 역할이 섞인 것으로 보인다. actor 는 "생성형AI서비스[외부]" 또는 "시스템"이 맞다. 확신도: medium.

### UC-003 해상도 변경 수행 — **현행성: 정확**
ADR-018/ADR-023 반영(업스케일 허용, 좌표 재계산 적재, 저장모델 통합) — `CLAUDE.md("해상도 변경(SFR-06-03)")` 절과 완전 일치.
- **related_screens 제안**: `SCREEN-022`(증강 요청 화면과 동일 UI 내 해상도 탭으로 추정) — 확신도: low(화면 본문 미확인, 별도 화면일 가능성 배제 못함).
- **[GAP-H04]** `alternate_flows`에 "대상 프리셋 전부 스킵"·"일부 실패"만 있고, **AC-003 이 이미 명시한** "파생본 대상 요청 → 400(파생 깊이 1 고정)"·"신고구간(DE_IDNTF_YN='F')은 차단하지 않고 생성"이 빠져 있다. UC 가 AC 보다 얕다 — AC 근거: `acceptance/_raw/AC-003.json`. 확신도: high.

### UC-004 객체 자동 추적 — **현행성: 정확**
ADR-040(추적 출력 형태=선택 객체 형태 고정) 반영, IDOR 차단·좌표 검증 서술 정확.
- **related_screens 제안**: `SCREEN-005`(라벨링 캔버스 화면) — 근거: sam2-track 은 라벨링 캔버스 도구. 확신도: high.

### UC-005 객체 외곽 경계 자동 밀착 — **현행성: 정확**
Sam2SegmentService 구현 정합, mock 자동적용 차단 서술 정확.
- **related_screens 제안**: `SCREEN-005` — 확신도: high.

### UC-006 라벨링 정밀도 조절 — **현행성: 정확**
영속 설정(POLYGON_SIMPLIFY_TOLERANCE) vs AI Tool 모달 1회성 override 이원 메커니즘을 정확히 구분.
- **related_screens 제안**: `SCREEN-025`(시스템 설정 화면, 영속 설정) + `SCREEN-005`(AI Tool 모달, 1회성 조절) — 확신도: medium(025 본문 직접 확인은 안 했으나 route=`/manage/settings`로 정합).

### UC-007 라벨 버전 저장·이력 추적 — **현행성: 정확**
ADR-033(변경이력 모델 재정의) 반영, SAVE_REASON_CD='APPROVED' 시점만 스냅샷 생성한다는 2계층 구분 정확.
- **related_screens 제안**: `SCREEN-005`(히스토리 패널, `HistoryPanel.tsx`) — 근거: `screen_spec/_raw/SCREEN-010.json`이 "버전 비교·복구 기능은 라벨링 캔버스(SCREEN-005)의 히스토리 패널로 통합됐다"고 명시. 확신도: high.

### UC-008 버전 비교·복구 — **현행성: 정확(최신)**
2026-08-05 확정(단일 선택=작업본 비교, 비교축 6종, 롤백=재활성)을 정확히 반영. `CLAUDE.md("버전 히스토리 단일 선택 = 현재 작업본과 비교")` 절과 완전 일치.
- **related_screens 제안**: `SCREEN-005`(히스토리 패널) — 근거: `screen_spec/_raw/SCREEN-010.json`("버전 비교·복구 기능은 SCREEN-005 히스토리 패널로 통합됐고 진입점이던 SCREEN-009 '버전관리로 이동' 버튼도 제거됐다"). **확신도: high(직접 근거 확보).**

### UC-009 검수 완료·수정 통지 — **현행성: 정확**
export SUCCEEDED 이후 발송, ver_expln optional 비대칭 등 최신 정책 정확 반영.
- **related_screens 제안**: `SCREEN-019`(검수 상세 화면) — 근거: TEST-004 step 1/2 `screen_ref="SCREEN-019"`. 확신도: high.

### UC-010 증강 영상 활용 여부 검수 — **현행성: 정확(최신)**
ADR-045(두 상태축 분리) 반영 — 웹훅 소유 `AUG_PROC_STTS_CD` vs 리뷰 축 `RVW_STTS_CD` 분리, 등재 게이트, 유예 7일 폐기·복구까지 완전 정합.
- **related_screens 제안**: `SCREEN-023`(증강 결과 화면) — 근거: TEST-003 step 5 `screen_ref="SCREEN-023"`. 확신도: high.

### UC-011 비식별 처리 요청 — **현행성: 정확**
논블로킹 제출, 미결 스위퍼, 자동 재시도 큐 폐기(수동 재비식별 안내) 등 최신 정책 정확.
- **related_screens 제안**: `SCREEN-009`(영상 상세, "재비식별 요청" 버튼) — 근거: `screen_spec/_raw/SCREEN-009.json`("헤더 카드 우측 '재비식별 요청' 버튼"). `SCREEN-007`(영상 목록, 배치 단계 표시)도 보조 후보. 확신도: high(009) / medium(007).

### UC-013 비식별 옵션 설정 — **현행성: 불명 — 내용 빈약(GAP)**
- **[GAP-H05]** `main_flow` 2단계뿐, `alternate_flows: []`, 옵션 항목 자체가 미정의 — AC-013 자신도 notes 에 "구체 옵션 항목 미정의(서브에이전트 GAPS) — 옵션 스펙 확정 시 시나리오 보강 필요"라고 자인한다. 제3자가 무엇을 구현해야 하는지 알 수 없다. 확신도: high(자체 인정).
- **related_screens 제안**: `SCREEN-025`(시스템 설정 화면, route=`/manage/settings`) — 확신도: medium.

### UC-016 비식별 처리 상태·이력 확인 — **현행성: 정확(최신, 매우 상세)**
2026-08-04/08-05 확정 다수(라벨·개인정보 3필드 보존, 412/404 차단 범위, 신고 단계별 재개, 파생영상 예외) 전부 정확 반영. 21건 중 서술 품질이 가장 높다.
- **[LINK-H01] related_screens 결함**: 현재 `[SCREEN-009, SCREEN-008]` — `SCREEN-008`은 파일시스템에 존재하지 않는다(이미 확인된 사실). `SCREEN-032`(비식별 신고 관리 화면, route=`/manage/deident-reports`)가 정확히 이 UC 의 접수·해소 관리 화면이다 — 근거: `screen_spec/_raw/SCREEN-032.json` purpose 가 UC-016 본문과 거의 동일한 문장(라벨 보존·3필드 보존·구 정책 폐기 서술)을 담고 있다. **제안: `[SCREEN-009, SCREEN-032]`로 교체.** 확신도: high.
- 이 UC 는 `covered_by_acceptances: [AC-016]`을 유일하게 정확히 채운 사례다(모범).

### UC-018 영상 적재 — **현행성: 정확(최신 반전 반영)**
ADR-042(적재 주체 반전 — 관제가 LS_DATA_INGEST 에 직접 INSERT) 정확 반영. `CLAUDE.md("배치 파이프라인")` 절과 일치.
- related_screens 이미 `[SCREEN-007]`로 정확히 연결됨(양호).
- **[GAP-H01] AC 0건**: 21개 AC 어디에도 `derived_from_use_cases`에 UC-018 이 없다. 관제 인입 → 폴링 적재라는 배치 파이프라인 진입점 전체가 인수기준 없이 방치돼 있다(TEST-001 이 통합시험으로 커버하나 AC 레벨 검증 가능한 단언은 없음). 확신도: high.

### UC-019 이벤트 마킹 (자동/수동) — **현행성: 부분 폐기 서술 잔존(STALE)**
- **[STALE-H01]** description: "마킹 결과(이벤트명+영상경로+marks 배열)는 VLM 콜백으로 전달"
  - **실제(현행 정책)**: `CLAUDE.md("★VLM·KPST 위탁은 논블로킹 제출이다")` 및 `("frame_policy 는 마킹에서 도출한다")` 절이 이 서술을 명시적으로 폐기한다 — "⚠ 구 서술 '이벤트명 + 영상경로 + marks 배열을 VLM 에 전달' 은 **폐기** — verify 규격에 그 필드들이 없다". 실제로는 마킹에서 `frame_policy`(수동=`frame_selected`+`selected_frames`, 자동/부재=`frame_interval`) + `framerate`(FPS 아님, `FRME_INTV_NOCS` 추출 간격) + `event_type`(관제 인입값 그대로, 검증·차단 없음) 3요소만 도출되어 `POST /v1/videovlm/verify`로 위탁된다. "VLM 콜백"이라는 표현도 부정확하다 — 마킹→VLM 방향은 **위탁(제출)**이고 콜백은 VLM→저작도구 방향(`POST /v1/vlm/callback`)이다. 방향이 뒤바뀌어 있다.
  - **근거**: `CLAUDE.md(★VLM·KPST 위탁은 논블로킹 제출이다)` · `CLAUDE.md(frame_policy 는 마킹에서 도출한다)` · `VlmTimeseriesStep`(진실원, UC-022 본문이 정확히 인용)
  - **조치 제안**: description 을 "마킹 완료 시 이벤트 마킹 결과에서 frame_policy(수동=선택 프레임 목록/자동=간격)와 event_type(관제 인입값)이 도출되어 VLM 시계열 위탁(UC-022)의 논블로킹 제출 입력이 된다"로 정정.
  - **확신도**: high.
- **[GAP-H01] AC 0건**: UC-019 도 AC 미보유. 자동/수동 마킹, intervalFrames 기본값(300), 단축키 동작, 신고 접수·해소 등 핵심 동작에 대한 검증 가능한 단언이 전무하다. 확신도: high.
- related_screens 이미 `[SCREEN-006]`로 정확히 연결됨.

### UC-021 라벨 편집·임시저장 — **현행성: 정확**
ADR-033(full-replace + 저장이벤트 되돌리기) 반영, 2계층 분리(임시저장 vs 검수승인 버전) 정확.
- related_screens 이미 `[SCREEN-005]`로 정확히 연결됨.

### UC-022 VLM 시계열 메타 검토 — **현행성: 대부분 정확하나 세부 규격 누락(GAP)**
콜백 경로(`POST /v1/vlm/callback`)·논블로킹 제출·ACK/콜백 이중 임계·신고구간 보류 등 최신 정책 정확 반영.
- **[LINK-H01] related_screens 결함**: 현재 `[SCREEN-015]` — 파일시스템에 존재하지 않는 폐기 화면. **TEST-002 자신이** "메타 검토는 라벨링 캔버스(SC-005) 시계열 메타 패널에서 수행"이라고 명시한다. **제안: `[SCREEN-005]`로 교체.** description 의 "메타 검토 화면(SCR-AUTO-002)" 표현도 함께 정정 필요(SCR-AUTO-002 는 존재하지 않는 화면 ID). 확신도: high.
- **[GAP-H06]** `POST /v1/videovlm/verify` 엔드포인트명, `results={accuracy, description}` 객체 응답 구조, `event_type` 무조건 위탁(벤더 응답이 판정)·`framerate`="몇 프레임당 1장" 의미가 본문에 전혀 언급되지 않는다. `CLAUDE.md("★외부 VLM 위탁은 verify 다")` 절이 구속하는 세부 규격인데 UC-022 는 "논블로킹 제출/ACK/콜백 창"이라는 상위 메커니즘만 서술하고 엔드포인트·페이로드 규격은 UC-019(위탁 측)에도 UC-022(수신 측)에도 없다 — 제3자가 verify 요청 바디를 재현할 수 없다. 확신도: high.
- **[GAP-H07]** 전용 AC 부재 — `covered_by_acceptances: []`이며, 21개 AC 중 UC-022 를 `derived_from_use_cases`로 갖는 것은 AC-024(UC-021 과 묶어 매우 포괄적인 문장) 뿐이다. verify 규격·event_type 통과 정책에 대한 세밀한 AC 가 없다.

### UC-023 검수 승인·반려 — **현행성: 정확**
"승인 연쇄 — 순서 보강"(버전 스냅샷→export 재생성→SUCCEEDED 후 통지) 정확 반영. related_screens `[SCREEN-018, SCREEN-019]` 정확.

### UC-024 포털 라벨 작업 (조회·수정·다운로드) — **현행성: 정확(최신 — SAM2 예외 철회 반영)**
2026-08-06 후속 정리(SAM2 포털 노출 예외 철회, API-130/131 deprecated 전이)까지 정확히 반영된 가장 최신 UC 중 하나.
- **related_screens 제안**: `SCREEN-029`(description 에 "/portal/label/:id" 명시) — **확신도: low-medium** — 이유: `.staging/screen_spec/_raw/` 디렉터리에 SCREEN-028/029/033/034 파일 자체가 존재하지 않아(로컬 스냅샷 미포함) 이 세션에서 직접 검증 불가. 포털 화면이 별도 export 대상에서 누락된 것인지, 실제 미등록인지 미확인 — **미확인으로 명시**.
- **[GAP-H01] AC 0건**: 21개 AC 어디에도 UC-024 를 커버하는 항목이 없다. 다만 UC-024 자신이 "R1 기능 요구사항 대응 없어 R2 유스케이스명세서 문서 범위 제외"라고 명시하므로 의도된 제외일 가능성이 있다 — 우선순위 낮음으로 하향.

### UC-027 포털 자산 업로드·수동 라벨링 — **현행성: 부분 모순(ERR)**
ADR-013 예외 신설 기능 서술은 정확(파일 제한, TUS, LS_PORTAL_* 4테이블 분리).
- **[ERR-H02]** brownfield.notes 및 description 이 "화면: 포털 업로드·포털 업로드 라벨링 — 둘 다 LogiCraft screen_spec 미등록(별도 등록 작업 대상)"이라고 서술하나, **바로 아래 `related_screens` 필드에는 이미 `[SCREEN-033, SCREEN-034]`가 채워져 있다** — 본문과 구조화 필드가 서로 모순된다(등록됐다는 필드값 vs 미등록이라는 서술). 어느 쪽이 최신인지 이 세션에서 화면 파일 실재를 확인할 수 없어(위 UC-024 항목과 동일 사유) 판단 보류. **조치 제안**: description 의 "screen_spec 미등록" 문구를 실제 SCREEN-033/034 등록 여부에 맞춰 정정. 확신도: medium.
- **[GAP-H08] AC 0건**: 21개 AC 중 UC-027 을 커버하는 것이 없다. 신규(should priority) 기능인데 all-or-nothing 이미지 업로드, TUS 재개, 본인 자산 격리(403) 등 검증 가능한 단언이 전혀 없다. 확신도: high.

### UC-028 라벨 클래스·속성 정의 관리 — **현행성: 대체로 정확하나 화면 ID 오류(ERR)**
LS_LABEL/LS_LABEL_ATTR CRUD, LBL_ID FK join 즉시반영, 미연결 표시 정책 모두 `CLAUDE.md("라벨 프리셋 = 라벨 마스터 단일 진실원 참조")` 절과 정합.
- **[ERR-H03]** description: "1차 관리자매뉴얼 §4.1.3/§4.1.4 갭을 해소하는 관리 화면(**SC-036**, /manage/labels, REVIEWER 전용)". 그러나 `/manage/labels` 라우트를 가진 실제 활성 화면은 **`SCREEN-035`**("라벨 관리 화면")이다 — `SCREEN-036`은 존재하지 않는다(파일시스템 확인). 번호 드리프트로 보인다.
- **related_screens 제안**: 현재 공란 → **`[SCREEN-035]`로 채움.** 확신도: high(route 일치로 확정).
- **[GAP-H09] AC 0건**: 21개 AC 중 UC-028 커버 없음. 확신도: high.

---

## 2. AC 별 판정

AC-001~016(및 AC-016)은 대체로 given/when/then + and_examples 구조가 치밀하고 DB/API 레벨로 검증 가능하다. 아래는 개별 이슈만 기재하고, 문제 없는 항목은 "정확"으로 짧게 처리한다.

| AC | 판정 | 비고 |
|---|---|---|
| AC-001 | 정확 | UC-001 과 완전 정합 |
| AC-002 | 정확 | UC-002 파생 실복사·부모게이트 정확 반영 |
| AC-003 | 정확(UC-003 보다 상세) | 파생깊이1·신고구간 허용을 UC-003 이 놓친 것을 AC-003 이 보유(위 GAP-H04 참조) |
| AC-004 | 정확 | 간결하나 충분 |
| AC-005 | 정확 | @AssertTrue 배타검증 등 구체적 |
| AC-006 | 정확 | |
| AC-007 | 정확 | 멱등(동일 payload 재승인 시 새버전 미생성) 명시 양호 |
| AC-008 | 정확(최신) | 2026-08-05 확정 6축 비교·롤백 재활성 정확 반영 |
| AC-009 | 정확 | ver_expln 비대칭까지 정확 |
| AC-010 | 정확(최신) | ADR-045 두 축 분리 정확 |
| AC-011 | 정확 | |
| AC-013 | **[GAP-H05로 이미 자인]** | notes 에 스스로 "옵션 항목 미정의" 명시 — UC-013 과 동일 문제 |
| AC-016 | 정확(가장 상세) | UC-016 과 동일하게 최신·상세 |
| AC-017 | **[GAP-H10]** | statement "적재된 산불 영상에 객체 라벨·환경/이벤트 메타가 부여되어 학습데이터로 가공됨" — 검증 가능한 단언이 아니다(구체 API/DB/상태코드 없음, and_examples 없음, error case 없음). AC-001~016 대비 품질 격차 큼. verification_method=manual_test 뿐. |
| AC-018 | **[GAP-H10]** | 위와 동일 패턴("화이트리스트 외 유형은 거부된다"고만 하고 거부 응답 코드·검증 지점 불명) |
| AC-019 | **[GAP-H10]** | "OPEN→RESOLVED로 수동 비식별화 처리되며"만 있고 구체 상태 코드·API 없음. UC-016/AC-016 이 이미 훨씬 정밀하게 같은 내용을 다루므로 **중복+저품질**로 보임 |
| AC-020 | **[GAP-H10]** | 동일 패턴 |
| AC-021 | **[GAP-H10]** | 동일 패턴 |
| AC-022 | **[GAP-H10]** | "승인 시 COMPLETED 전이"는 맞으나 AC-022 자체가 UC-023/AC 수준 정밀도에 못 미침(구체 SQL 없음) |
| AC-023 | **[GAP-H10]** | 동일 패턴 |
| AC-024 | **[GAP-H10]** | 동일 패턴, 또한 UC-022(VLM verify 세부규격)를 사실상 유일하게 "커버"하는 AC 인데 그 세부규격을 전혀 담지 않음(위 GAP-H07 과 동일 근본원인) |

**[GAP-H10] 종합**: AC-017~024(8건, 전부 `diff_summary: "보완요청 SFR-11-0N 신규 AC"`)는 SFR-11 계열 보완요청에 대응해 일괄 생성된 것으로 보이며, AC-001~016 대비 구조적으로 "요구사항 존재 확인" 수준에 머물러 있다 — `and_examples`(부정 케이스) 전무, 구체 DB/API 검증 지점 전무, `verification_method` 전부 `manual_test`. 이 8건이 실제로 검증 가능하려면 각 UC(UC-021/UC-011/UC-002/UC-022/UC-023) 의 이미 존재하는 정밀 AC(AC-007~AC-011/AC-022 등)로 흡수·중복 제거하거나, 최소한 구체적 given/when/then 을 보강해야 한다. 확신도: high.

---

## 3. TEST 별 판정

| TEST | 판정 |
|---|---|
| TEST-001 | 정확 — UC-018/UC-011 파이프라인 happy path, DB SQL 검증 지점 구체적 |
| TEST-002 | 정확(최신) — 논블로킹 제출·ACK 왕복 구분을 정확히 반영, "구 표기 폐기했다"는 자기 정정까지 명시 |
| TEST-003 | 대체로 정확, **[ERR-H04]** 아래 참조 |
| TEST-004 | 정확 — 알려진 미구현 이슈(B1/B2)를 스스로 정직하게 표기 |
| TEST-005 | **[STALE-H02]** 아래 참조 |

### [ERR-H04] TEST-003 step1 screen_ref 불일치
- **원문**: seq1(증강 위탁 요청) `"screen_ref": "SCREEN-023"`, 그러나 같은 스텝의 `expected` 텍스트는 "화면(SC-022 증강 요청→SC-023 결과): 종류 선택 + 생성 조건 입력 후 위탁"이라고 적어 **요청 액션은 SCREEN-022**에서 일어난다고 스스로 말한다.
- **실제**: `screen_spec/_raw/SCREEN-022.json` route=`/augment`("증강 요청 화면"), `SCREEN-023` route=`/augment/result/:jobId`("증강 결과 화면"). 위탁 요청(seq1)은 022, 결과 확인·채택(seq5)은 023 이어야 한다.
- **조치 제안**: seq1 의 `screen_ref` 를 `"SCREEN-022"`로 정정.
- **확신도**: medium(필드 하나의 오기로 보이며 exercises_screens 전체 목록엔 두 화면이 이미 포함돼 실질 영향은 제한적).

### [STALE-H02] TEST-005 notes 의 UC-024 상태 오기재
- **원문**: "관련 활성 UC 없음 — KLID-AT-UC-024(포털 라벨 작업)는 R1 범위 외 결번·deprecated라 covers_use_cases 비움."
- **실제(현행 정책)**: UC-024 는 **`status: "approved"`**이며, 그 자신의 brownfield.notes 가 "2026-07-30 재활성 — SCREEN-029 active와의 불일치 확인되어 deprecated에서 approved로 복원"이라고 명시한다. TEST-005 의 notes 는 이 재활성 이전 상태를 그대로 인용하고 있다 — **폐기된 것은 UC-024 가 아니라 TEST-005 의 이 서술이다.**
- **근거**: `use_case/_raw/UC-024.json`(brownfield.notes, status=approved) vs `test_scenario/_raw/TEST-005.json`(notes)
- **조치 제안**: TEST-005 notes 정정 + `covers_use_cases`에 `"UC-024"` 추가 검토(단, UC-024 자신이 "R1 문서 범위 제외"를 명시하므로 통합/시스템 시험 대상 편입 여부는 관제 협의 사안과 별개로 판단 필요).
- **확신도**: high.

---

## 4. 링크 복구 제안표

| UC | 현재 related_screens | 제안 | 근거 | 확신도 |
|---|---|---|---|---|
| UC-001 | [] | SCREEN-022 | screen_spec route=/augment | high |
| UC-002 | [] | SCREEN-023 | screen_spec route=/augment/result/:jobId | high |
| UC-003 | [] | SCREEN-022(추정) | 증강/해상도 통합 UI 로 추정, 미확인 | low |
| UC-004 | [] | SCREEN-005 | sam2-track 은 라벨링 캔버스 도구 | high |
| UC-005 | [] | SCREEN-005 | sam2-segment 는 라벨링 캔버스 도구(단축키 G) | high |
| UC-006 | [] | SCREEN-025 + SCREEN-005 | 영속설정=025, 1회성 override=005(AI Tool 모달) | medium |
| UC-007 | [] | SCREEN-005 | SCREEN-010 이 "버전관리 기능은 SCREEN-005 히스토리 패널로 통합"이라 명시 | high |
| UC-008 | [] | SCREEN-005 | 위와 동일 근거(SCREEN-010.json 명시적 진술) | high |
| UC-009 | [] | SCREEN-019 | TEST-004 screen_ref | high |
| UC-010 | [] | SCREEN-023 | TEST-003 step5 screen_ref | high |
| UC-011 | [] | SCREEN-009(+SCREEN-007 보조) | SCREEN-009 "재비식별 요청" 버튼 명시 | high |
| UC-013 | [] | SCREEN-025 | route=/manage/settings | medium |
| UC-016 | [SCREEN-009, **SCREEN-008(존재하지 않음)**] | [SCREEN-009, **SCREEN-032**] | SCREEN-032 purpose 가 UC-016 본문과 거의 동일 | high |
| UC-022 | [**SCREEN-015(존재하지 않음)**] | [**SCREEN-005**] | TEST-002 자신이 "SC-005 시계열 메타 패널에서 수행" 명시 | high |
| UC-024 | [] | SCREEN-029(추정) | UC 본문 "/portal/label/:id" 명시하나 로컬 스냅샷에 화면 파일 부재로 미확인 | low(미확인) |
| UC-027 | [SCREEN-033, SCREEN-034] | 현행 유지, 단 description 모순 정정 필요 | 위 ERR-H02 | medium |
| UC-028 | [] | **SCREEN-035** | route=/manage/labels 일치, 본문의 "SC-036"은 미존재 화면(ERR-H03) | high |

**시스템성 LINK 이슈 — `covered_by_acceptances` 역방향 링크 공백**: UC-016 을 제외한 UC 20건 전부가 `covered_by_acceptances: []`로 비어 있으나, 그중 다수(UC-001~011, UC-013, UC-021~024, UC-027 일부)는 실제로 대응 AC 가 `derived_from_use_cases`로 존재한다(예: AC-001↔UC-001). 역방향 필드를 채우지 않으면 UC 문서만 단독으로 열람할 때 인수기준 존재 여부를 알 수 없다. 확신도: high(구조적 패턴, 전수 확인 완료).

---

## 5. 커버리지 공백 — 화면 25건 중 19건 미커버 분석

25건 목록(활성): 001~007, 009~012, 018~027, 030~032, 035.
현재 related_screens 로 커버되는 화면(수정 전, 존재하는 화면만 카운트): SCREEN-005, 006, 007, 009, 018, 019 = **6건** → 미커버 **19건**(프롬프트 수치와 정합 확인됨).

### 5-1. 링크 수정만으로 해소되는 화면(신규 UC 불필요)
위 4절 표를 전량 반영하면 다음이 추가로 커버된다: 001(UC-004,005,007,008,021 등 이미 SCREEN-005 커버 강화) / 022,023(UC-001,002,010) / 025(UC-006,013) / 032(UC-016 재연결) / 035(UC-028). **즉 프롬프트가 지목한 7화면 중 022/023(증강)·032(비식별신고)·035(라벨관리) 3건은 "누락된 UC"가 아니라 "링크 안 된 기존 UC"였다.**

### 5-2. 진짜 신규 UC 가 필요한 화면

| 대상 화면 | 제안 UC 제목 | 목표(goal) | 우선순위 |
|---|---|---|---|
| SCREEN-012 (작업 목록 화면, /task) | 작업 목록 조회·필터링 | WORKER 는 본인 배정, REVIEWER 는 검수완료 전체 작업을 시간축 정렬 + 이벤트유형 필터 + KPI 카드(전체/미배정/작업중/검수요청/반려)로 조회하고 라벨링·마킹 화면으로 진입한다 | should — `CLAUDE.md("목록 화면 정렬·필터 정책")` 절이 구속 규칙까지 두고 있는데 대응 UC 가 없다 |
| SCREEN-024 (사용자 관리 화면, /manage/users) | 사용자 계정·권한 관리 | REVIEWER 가 WORKER/REVIEWER 계정을 조회·상태 변경하고 역할을 부여·회수한다 | should — `CLAUDE.md("역할 정의")` 절 존재하나 이를 수행하는 UC 없음 |
| SCREEN-025 (시스템 설정 화면, /manage/settings) | (부분) 시스템 설정 관리 — 확장형 | UC-006/UC-013 로 부분 커버되나, 두 UC 는 각각 폴리곤 단순화·비식별 옵션 1개씩만 다룬다. 화면이 다루는 전체 설정 키 범위(Caffeine 캐시 TTL 등)를 포괄하는 상위 UC 필요 여부 재검토 | low — 링크만으로 부분 해소되므로 신규보다 통합 검토 권고 |
| SCREEN-026 (프리셋 관리 화면, /manage/presets) | 라벨 프리셋 관리 | REVIEWER 가 이벤트유형별 라벨 프리셋(LS_LABEL_PRESET_CODE)을 CRUD 하고 라벨 마스터(LBL_ID)와 연결한다 | should — `CLAUDE.md("라벨 프리셋 = 라벨 마스터 단일 진실원 참조")` 절 존재하나 프리셋 CRUD 자체의 UC 없음(UC-028 은 마스터/속성만 다룸) |
| SCREEN-011 (대시보드 화면, /dashboard) | 대시보드 현황 조회 | REVIEWER/WORKER 가 진행 현황 요약을 조회한다 | low |
| SCREEN-020 (작업자 통계 화면, /stat) | 작업자 통계 조회 | 작업자별 처리량·품질 통계를 조회한다 | low |
| SCREEN-021 (전체 구축 현황 화면, /stat/overall) | 전체 구축 현황 조회 | 산출 목표(이미지 10만장/영상 5,000건) 대비 진행 현황을 조회한다 | low |
| SCREEN-030/031 (공지 목록/상세) | 공지사항 조회 | 단순 CRUD 성격 — DDD 룰 기준으로도 별도 UC 없이 API 문서만으로 충분할 수 있음 | low |

SCREEN-001~004(세션 인계/역할 클레임/접근거부/dev 로그인), SCREEN-010(폐기 확인됨, 위 근거 참조), SCREEN-027(dev 전용 오토라벨 테스트)은 시스템/인프라성 화면이라 업무 UC 신설 대상에서 제외 권고.

---

## 미검토

없음 — UC 21건(UC-001~011,013,016,018,019,021~024,027,028), AC 21건(AC-001~011,013,016~024), TEST 5건(TEST-001~005) 전수를 원본 JSON 으로 직접 읽었다. 다만 아래는 **이 세션 내에서 검증 수단이 없어 "미확인"으로 명시**한 항목이다:
- SCREEN-028/029/033/034(포털 화면) 파일이 `.staging/screen_spec/_raw/`에 존재하지 않아 UC-024/UC-027 의 related_screens 제안(SCREEN-029) 및 UC-027 의 ERR-H02(screen_spec 등록 여부 모순)를 화면 본문으로 직접 확정하지 못했다.
- UC-003(해상도 변경)의 화면 연결(SCREEN-022 추정)은 화면 본문 내 해상도 탭 존재 여부를 직접 확인하지 못해 확신도 low 로 표기했다.
