# 12. 검수 · 작업 배정

> 출처: CLAUDE.md(작업 배정·작업 단위·완료 통지), R2 KLID-AT-SS-007, 코드(`review/`, `assignment/`)
> 관련: [13 버전관리](13-version-control.md) · [15 관제서버 통지](15-control-notify.md)

화면: `KLID-AT-SC-012`(작업 목록 `/task`), `SC-013`(작업 배정 `/task/assign`, REVIEWER), `SC-018`(검수 목록 `/review`), `SC-019`(검수 상세 `/review/:id`).

## 12.1 작업 배정

- **REVIEWER가 WORKER에게 영상 단위 배정** (ADMIN 권한이 REVIEWER에 통합)
- `LS_TASK_ASSIGNMENT` INSERT (`TASK_TYPE_CD='LABELER'`), 재배정 시 `LS_TASK_ASSIGN_HISTORY` 기록
- 배정 이력 조회·재배정 권한도 REVIEWER 보유
- **배정 진입 동선 2곳**: ①작업 배정 화면(SC-013 `/task/assign`) ②**영상 목록(SC-007 `/video/completed`)의 행/일괄 "배정" 버튼**(REVIEWER 전용, 마킹 전 배정 정책 유지). 두 경로 모두 동일 작업자 선택 모달(`AssignModal`)·동일 배정 API(`POST /v1/assignments`) 재사용 → [05](05-video-management.md) §5.5.1
- **두 화면 배정 시나리오 정합**: 배정자 표시·배정/재배정 토글·재배정 시 현재 배정자 사전선택·완료 영상 재배정 차단·성공 후 즉시 갱신을 작업 목록과 동일하게 적용. 영상 목록은 배정정보를 `GET /v1/videos`(VideoSummaryResponse) 응답으로 받으며, 산출 기준(현재 활성 LABELER 배정 1건)은 `TaskBoardService`와 동일
- 코드: `assignment/AssignmentController`, `TaskBoardController`, FE `pages/VideoListPage.tsx`·`features/task/components/AssignModal.tsx`

## 12.2 검수 (단일 승인)

- **검수 완료 = 작업 완료**: REVIEWER가 `APPROVED` 처리 → `LsRawDataStatus.dataSttsCd` `COMPLETED` 전이
- v1의 1차/2차 다단계 검수 없음 (REVIEWER 단일 승인으로 의도적 변경)
- 검수 상세에서 라벨 승인/반려, 라벨 diff 비교, 버전 롤백 → [13](13-version-control.md)
- 코드: `review/ReviewController`, `ReviewService`, `ReviewStateMachine`

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
