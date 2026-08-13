# 12. 검수 · 작업 배정

> 출처: CLAUDE.md(작업 배정·작업 단위·완료 통지), R2 KLID-AT-SS-007, 코드(`review/`, `assignment/`)
> 관련: [13 버전관리](13-version-control.md) · [15 관제서버 통지](15-control-notify.md)

화면: `KLID-AT-SC-012`(작업 목록 `/task` — 배정 동선 포함), `SC-018`(검수 목록 `/review`), `SC-019`(검수 상세 `/review/:id`). (구 `SC-013` 작업 배정 전용 페이지 `/task/assign`는 진입점 없는 중복으로 2026-06-17 deprecated·코드 제거 — 배정은 작업 목록/영상 목록에 통합 → [04 화면·IA](04-screens-ia.md))

## 12.1 작업 배정

- **REVIEWER가 WORKER에게 영상 단위 배정** (ADMIN 권한이 REVIEWER에 통합)
- `LS_TASK_ASSIGNMENT` INSERT (`TASK_TYPE_CD='LABELER'`), 재배정 시 `LS_TASK_ASSIGN_HISTORY` 기록
- 배정 이력 조회·재배정 권한도 REVIEWER 보유
- **배정 진입 동선 2곳**: ①**작업 목록(SC-012 `/task`)** — `UNASSIGNED`('미배정') 상태 필터 + 행/일괄 "배정" 버튼 ②**영상 목록(SC-007 `/video/completed`)의 행/일괄 "배정" 버튼**(REVIEWER 전용, 마킹 전 배정 정책 유지). 두 경로 모두 동일 작업자 선택 모달(`AssignModal`)·동일 배정 API(`POST /v1/assignments`) 재사용 → [05](05-video-management.md) §5.5.1. (구 작업 배정 전용 페이지 `/task/assign`는 deprecated)
- **두 화면 배정 시나리오 정합**: 배정자 표시·배정/재배정 토글·재배정 시 현재 배정자 사전선택·완료 영상 재배정 차단·성공 후 즉시 갱신을 작업 목록과 동일하게 적용. 영상 목록은 배정정보를 `GET /v1/videos`(VideoSummaryResponse) 응답으로 받으며, 산출 기준(현재 활성 LABELER 배정 1건)은 `TaskBoardService`와 동일
- 코드: `assignment/AssignmentController`, `TaskBoardController`, FE `pages/VideoListPage.tsx`·`features/task/components/AssignModal.tsx`

### 12.1.1 배정 목록 조회 API (`GET /v1/assignments`) — 서버 필터 (2026-07-30)

작업 목록 화면(SC-012)의 **WORKER 시각**이 쓰는 엔드포인트다. 화면 동작·문구는 [04 화면·IA](04-screens-ia.md) §4.2 노트 참조.

| 파라미터 | 필수 | 값 | 비고 |
|---------|:----:|----|------|
| `q` | 선택 | ≤100자 | 영상명(표시명)·작업자명 부분일치. LIKE 이스케이프 후 바인딩 |
| `workStatus` | 선택 | `PENDING`\|`IN_PROGRESS`\|`REVIEW_PENDING`\|`COMPLETED`\|`REJECTED` | 미등록 값 400. 빈 값/공백은 '필터 미적용' |
| `eventTypeCd` | 선택 | ≤20자 | 옵션 API 가 내려준 값(그룹 대표코드)을 그대로 재전송하면 매칭됨. **비대표 코드(구 북마크)도 그룹 전체로 확장돼 매칭**된다 → §12.1.2 |
| `workerId` | 선택 | 양수 | REVIEWER 전용. **WORKER 요청에서는 무시**(403 아님) |
| `page`/`size`/`sort` | 선택 | 기본 `regDt,desc` | 정렬 allowlist: `regDt`/`assignedAt`/`rawDataId`/`videoId`/`assignmentId`/`id` — **미등록 키 400(strict)** |

- **필터는 현재 페이지가 아니라 전체 데이터셋 기준**이며 `totalElements` 도 필터 결과 기준이다. 신규 파라미터는 전부 optional·기본값 없음이라 **하나도 보내지 않으면 변경 전과 동일한 응답**이다(하위호환 회귀 가드: `ListApiBackwardCompatibilityIT`).
- **`IN_PROGRESS`(작업중) 판정 = 그 영상에 라벨 저장 이력(`LS_DATA_LBL_HSTRY`, 종류별 건수 합 > 0)이 존재**. 필터 WHERE 와 응답 `status` 가 **같은 표현식**에서 나오므로 "작업중으로 걸렀는데 목록엔 대기로 표시" 가 생기지 않는다. 판별식은 데이터마트 뷰 `V_COMPLETED_LABEL_CHANGE`(V139)와 동일하며 `SaveHistoryChangeViewParityIT` 가 결박한다.
  - ⚠ **알려진 한계(수용됨)**: 트랙 분할·트랙 병합·객체 속성 저장은 이력을 남기지 않아 그 작업만 한 영상은 '대기'로 표시된다. 이력 도입 이전 레거시 작업분도 동일(백필 없음).
- **인가 축과 필터 축을 분리**한다 — WORKER 면 토큰 subject 로 조회 범위를 고정하고 요청의 `workerId` 는 **읽지 않는다**(CWE-639 IDOR).

### 12.1.2 배정 이벤트유형 옵션 API (`GET /v1/assignments/event-types`) — 신규 (2026-07-30)

- 본인(REVIEWER 는 전체/특정 작업자) 배정 **전체**에 실재하는 `EVNT_TYPE_CD` 를 **표시명 그룹의 대표코드**로 접어(2026-08-05, [18 §18.4.1](18-database.md)) 중복 없이 오름차순 반환한다. 목록(`GET /v1/tasks/board/event-types`)과 **같은 판정기**(`EventTypeFilterSupport`)를 공유해 접기 규칙이 두 화면에서 갈라지지 않는다. 목록과 **같은 조건**을 쓰되 **자기 축(`eventTypeCd`)만 제외**하므로 옵션에서 고른 값으로 필터하면 0건일 수 없다.
- **미등록·비규격 코드는 원문을 유지**한다 — 접을 수 없다고 버리면 그 코드의 영상이 필터로 도달 불가능해진다(실제로 `INTRUSION` 같은 비규격 코드가 인입 중).
- **절단(`truncated`)은 접은 뒤 판정**한다 — 접기 전에 자르면 잘린 구간에만 있던 그룹이 통째로 사라지고, 접은 결과가 상한 이하인데도 `truncated=true` 가 나가는 오안내가 생긴다. 스캔 상한은 `max + 1` 이 아니라 `max + (노출 그룹 멤버 코드 수) + 1` 이다(접기로 줄어드는 최대 개수만큼 여유를 둬야 절단이 드러난다).
- **필터도 그룹 전체를 본다** — 대표코드든 비대표코드(그룹 도입 이전 북마크)든 `EventTypeFilterSupport.matchCodesFor`가 그룹 전체 EV-코드로 확장해 조회·KPI 집계를 같은 집합으로 센다. **WORKER 인가 축은 그대로**다(그룹 필터로도 본인 배정분만 조회).
- 응답은 `GET /v1/tasks/board/event-types` 와 **동일 형태** `{ items: string[], truncated: boolean }`(FE 공용 컴포넌트). 페이징 없음 — 상한 500 으로 무제한 조회를 막고 초과 시 `truncated=true` 로 알린다.
- 인가는 목록과 동일(WORKER 의 `workerId` 무시). "그 값이 존재한다" 자체가 정보이므로 범위가 목록과 같아야 한다.
- **영구 병합이 아니다** — 관제가 유형별 이름을 보내거나 운영자가 관리 화면에서 표시명을 지정하면 그 유형만 자기 이름을 얻어 그룹이 자동으로 쪼개진다(캐시 무효화로 즉시 반영).
- 코드: `assignment/controller/AssignmentController`·`TaskBoardController`, `assignment/service/AssignmentService`·`TaskBoardService`·`EventTypeFilterSupport`, `assignment/repository/AssignmentQueryRepository`·`TaskBoardQueryRepository`, `assignment/domain/AssignmentWorkStatus`, FE `features/task/boardParams.ts`·`hooks/useAssignmentEventTypes.ts`·`pages/TaskListPage.tsx`

## 12.2 검수 (단일 승인)

- **검수 완료 = 작업 완료**: REVIEWER가 `APPROVED` 처리 → `LsRawDataStatus.dataSttsCd` `COMPLETED` 전이
- v1의 1차/2차 다단계 검수 없음 (REVIEWER 단일 승인으로 의도적 변경)
- 검수 상세에서 라벨 승인/반려, 라벨 diff 비교, 버전 롤백 → [13](13-version-control.md)
- 코드: `review/ReviewController`, `ReviewService`, `ReviewStateMachine`

### 12.2.0 검수 상세(SC-019) 우측 패널 — 3탭 구조 (2026-08-08)

우측 패널이 **객체 / 메타 / 이슈** 3개 탭으로 전환된다. 구 구현이 5개 패널을 세로로 나열하던 것을 화면 사양(`SCREEN-019`)의 탭 구성에 맞춘 것이며, **패널의 기능은 하나도 줄지 않았다**(전부 어느 탭 안에 그대로 있다).

| 탭 | 내용 |
|----|------|
| **객체**(기본) | 객체 목록(카테고리 트리) + 선택 객체 속성 + 검수 메모(이 화면 전용) |
| 메타 | 이벤트 어노테이션 + 시계열 메타 + 영상 기술 정보 — **읽기 전용**(확정은 승인 시 자동 동결에 위임) |
| 이슈 | 검수자↔작업자 통합 이슈 스레드(문의·반려 이력) → [21](21-issue-channel.md) |

- **기본 탭은 '객체'** — 라벨링 화면(SC-005) 우측 패널의 기본 탭과 같다(두 화면의 진입 화면이 갈리면 검수자가 매번 다시 찾는다). 탭 순서·라벨·시각 클래스도 라벨링 화면 패턴을 그대로 쓴다.
- **탭은 표시만 전환한다** — 객체 선택(`selectedLabelId`)·검수 의견·pending 이슈는 `useReviewSelectionStore` 가 소유하므로 **탭을 옮겼다 돌아와도 선택이 유지**되고 캔버스와의 양방향 동기화도 끊기지 않는다.
- **'이슈' 탭에 미해소 문의 건수 배지** — 패널이 모두 보이던 구조에서는 문의 건수가 항상 눈에 들어왔는데 탭으로 접으면 다른 탭을 보는 동안 그 신호가 사라진다. 건수 자체는 이슈 탭 안이 계속 소유하고 배지는 알림일 뿐이다.
- **접근성**: WAI-ARIA Tabs 패턴 — `role="tablist"/"tab"/"tabpanel"`, `aria-selected`·`aria-controls`↔`aria-labelledby` 상호 연결, **←/→/Home/End 방향키 이동 + roving tabIndex**(선택 탭만 `0`, 나머지 `-1`). 탭 버튼은 KRDS 최소 터치 타깃(44px)을 만족한다.
- **스크롤은 각 tabpanel 이 갖는다** — `aside` 전체가 스크롤되면 탭 목록이 위로 밀려 나가 다른 탭으로 갈 수단이 사라진다.
- **승인·반려는 헤더 단독**이며 탭 안에 두지 않는다(종전 확정 유지 — 같은 액션이 두 곳에 있으면 활성 조건·진행 표시 판정이 갈린다).
- **★'객체' 탭의 자동/수동 표시가 V6(라벨 AI 정보 흡수)부터 정확해졌다** — 이 화면이 쓰는 `GET /v1/reviews/{videoId}/frames` 는 흡수 전 AI 정보를 **아예 조달하지 않아** 자동 라벨도 전부 "수동"·신뢰도 공란으로 보였다. 지금은 실제 값이 나가므로 같은 영상에서 표시가 **"수동 라벨" → "자동 라벨"** 로 바뀐 것이 정상이다(응답 스키마는 불변, 값만 정확해졌다). 근거·불변 경로 목록은 [11](11-ai-assisted.md) 참조
- 코드: `features/review/components/ReviewSidePanelTabs.tsx`, `pages/ReviewPage.tsx`

### 12.2.0-a 프레임 썸네일 스트립 — 캔버스 위 + 접기/펼치기 (2026-08-08)

검수 상세(SC-019)의 프레임 썸네일 스트립이 **화면 최하단(footer)에서 캔버스 바로 위**로 옮겨졌다. 화면 사양이 규정한 배치이며, 상단 프레임 이동 바 바로 아래 행에 놓여 자기 높이만 차지한다(캔버스가 남은 공간을 갖는다).

- **접기/펼치기 토글 신설, 기본은 펼침** — 스트립은 사양상 화면 구성 요소이므로 첫 진입에 감춰져 있으면 안 된다.
- **접으면 썸네일 목록이 DOM 에서 빠진다** — 시각적으로만 감추면 보이지 않는 버튼에 Tab 포커스가 들어가 갇힌다. 접혀도 **진행률 바·프레임 카운터·토글은 남아** 현재 위치를 알 수 있고 다시 펼칠 수단이 유지된다. 다시 펼치면 접힌 동안 이동한 프레임이 보이도록 스크롤이 따라온다.
- **토글은 썸네일 툴바 바깥에 둔다** — 툴바 안에서 ←/→ 는 항목 간 포커스 이동에 쓰이는 키인데 이 툴바는 그 키를 **프레임 이동**에 쓴다. 토글을 안에 두면 토글에 포커스가 있을 때 방향키가 프레임을 바꿔 버린다. 접힌 상태에서도 툴바 컨테이너는 남아 **방향키 프레임 이동 경로는 유지**된다.
- **프레임 이동 경로는 여전히 하나로 수렴한다** — 상단 프레임 이동 바와 썸네일 스트립이 같은 이동 핸들러를 쓴다(옮기고 접기를 더하면서 표면이 갈리지 않았다).
- 코드: `features/review/components/FrameTimeline.tsx`, `pages/ReviewPage.tsx`

### 12.2.1 상태 전이 (LS_RAW_DATA_STATUS.DATA_STTS_CD)

| From | To | 트리거 | 비고 |
|------|----|--------|------|
| ASSIGNED | PENDING | WORKER submit | 최초 검수 제출 |
| PENDING | IN_REVIEW | REVIEWER start | 검수 시작 |
| IN_REVIEW | APPROVED | REVIEWER approve | 승인 → 버전 스냅샷 적층 |
| IN_REVIEW | REJECTED | REVIEWER reject | 반려 (사유 LS_DATA_ISSUE) |
| REJECTED | PENDING | WORKER submit | 수정 후 재제출 |
| **APPROVED** | **PENDING** | **WORKER submit** | **재검수 재제출 — 검수완료 후 수정→재검수→재승인 허용. 동일 작업 ID(RAW_SN) 유지·버전업 아님. 재승인 시 변경분에 새 APPROVED 스냅샷 적층(diff/롤백 활성화)** |

- 불허: PENDING → APPROVED 직행(409 INVALID_INPUT), APPROVED → IN_REVIEW/REJECTED 직행(409 CONFLICT — 재검수는 PENDING 재제출부터).
- `COMPLETED`(STTS_COMPLETED)는 배치 파이프라인 상태이며 검수 종결값으로 쓰이지 않는다. 검수 종결 시 영속 상태는 `APPROVED`이고, DTO 표시 계층에서만 APPROVED→"COMPLETED" 라벨로 매핑한다.

### ★ 12.2.2 「한번이라도 검수 완료」 판정 — `ReviewApprovalGate.hasEverApproved` (P2b, 2026-08-11 사용자 확정, 구속)

위 표의 `APPROVED → PENDING`(재검수 재제출) 전이 때문에, **지금 상태**(`isApproved`)만 보는 판정에는 승인 이력이 있는 영상이 재제출 구간에서 "미승인"으로 보이는 창이 열린다. 그런데 이 창에서 비식별 누락 신고를 받아 재비식별하거나 프레임을 폐기·복원하면, 이미 산출되어 외부로 나간 회차의 이미지·마스킹과 데이터마트가 가리키는 최신 영상 파일이 어긋난다 — 데이터마트를 그 회차로 되돌리면 신고로 걷어낸 개인정보가 되살아나거나, 없던/있던 프레임이 뒤바뀐다.

그래서 판정을 **지금 상태에서 이력으로** 옮긴다 — `ReviewApprovalGate.hasEverApproved(rawSn)`.

- **판정 = 승인 동결 스냅샷 존재 OR 승인 감사 존재**(fail-closed OR). 둘 다 append-only라 "있었다"가 지워지지 않는다. OR로 묶는 이유는 동결 스냅샷 테이블(`LS_DATASET_VIDEO_META`)이 나중에 신설돼, 그 이전에 승인되고 백필 전에 재제출된 영상은 스냅샷 행이 0건일 수 있어서다 — 그 경우는 승인 감사 로그(`LS_TASK_EVENT_LOG` `EVENT_APPROVE`)가 뒤를 받친다.
- **소비처 2곳** — 둘 다 이 게이트 하나만 재사용하고 판정을 복제하지 않는다.
  - 비식별 누락 신고 접수(412) → [08 §8.4](08-deidentification.md)
  - 프레임 폐기·복원(400) → [10 §10.7.1](10-labeling.md)
- **응답 코드가 서로 다른 것은 의도된 비대칭이다** — 412는 해소되면 되는 일시 조건(신고), 400은 되돌아가지 않는 영구 조건(폐기 차단)이다.
- 영상 상세 응답(`VideoDetailResponse.everApproved`)에 이 판정값을 내려준다 — 화면이 신고 버튼·폐기 토글을 **미리 비활성화 + 툴팁**으로 사유를 알리기 위해서다. ⚠ `reviewSttsCd`(현재 상태)와 **다른 축**이라, 화면이 `reviewSttsCd`로 대신 판정하면 재검수 재제출 구간에서 버튼이 다시 열린다.
- 영상 단위 확정 저장(§13.8.2)이 프레임마다 이 판정을 반복하므로(최대 프레임 수 회) 요청 스코프 캐시(`hasEverApprovedCached`)로 감싼다 — 그 루프는 영상 전 프레임 행 락을 보유한 상태라 지연이 곧 락 보유 시간이다.

## 12.3 완료 → 통지

```
검수 승인(APPROVED)
  → 버전 스냅샷 저장 (LS_LABEL_VERSION) → [13]
  → 관제서버 TASK_COMPLETED 통지 발행 → [15]
```

## 12.4 검수 완료 후 수정

- 동일 작업 ID(`RAW_SN`) 유지, 버전 업/새 ID 발급 안 함
- 라벨/메타 수정 시마다 `TASK_MODIFIED` 통지 (수정 요약만) → [15](15-control-notify.md)
- 수신측(관제서버)은 마지막 상태로 갱신

## 12.5 권한 경계

- WORKER: 본인 배정 영상만 라벨 수정·검수 제출. 본인 외 프레임 편집 403(IDOR, `LabelAccessGuard`)
- REVIEWER: 전체 배정·검수·재배정

## 12.6 관련 데이터 (DB)

`LS_TASK_ASSIGNMENT`(배정), `LS_TASK_ASSIGN_HISTORY`(재배정 이력), `LS_TASK_EVENT_LOG`(이벤트 로그), `LS_RAW_DATA_STATUS`(작업 상태), `LS_DATA_ISSUE`(품질 이슈 — V57부터 문의(INQUIRY) 타입·상태 확장), `LS_ISSUE_COMMENT`(댓글 스레드). → [18](18-database.md).

> 반려 사유는 V57부터 작업자 문의와 **통합 이슈 스레드**로 양방향 소통 가능 (등록→답변→해소) — 상세는 [21 이슈 소통 채널](21-issue-channel.md).
