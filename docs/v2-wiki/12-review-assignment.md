# 12. 검수 · 작업 배정

> 출처: CLAUDE.md(작업 배정·작업 단위·완료 통지), R2 KLID-AT-SS-007, 코드(`review/`, `assignment/`)
> 관련: [13 버전관리](13-version-control.md) · [15 관제서버 통지](15-control-notify.md)

화면: `KLID-AT-SC-012`(작업 목록 `/task`), `SC-013`(작업 배정 `/task/assign`, REVIEWER), `SC-018`(검수 목록 `/review`), `SC-019`(검수 상세 `/review/:id`).

## 12.1 작업 배정

- **REVIEWER가 WORKER에게 영상 단위 배정** (ADMIN 권한이 REVIEWER에 통합)
- `LS_TASK_ASSIGNMENT` INSERT (`TASK_TYPE_CD='LABELER'`), 재배정 시 `LS_TASK_ASSIGN_HISTORY` 기록
- 배정 이력 조회·재배정 권한도 REVIEWER 보유
- 코드: `assignment/AssignmentController`, `TaskBoardController`

## 12.2 검수 (단일 승인)

- **검수 완료 = 작업 완료**: REVIEWER가 `APPROVED` 처리 → `LsRawDataStatus.dataSttsCd` `COMPLETED` 전이
- v1의 1차/2차 다단계 검수 없음 (REVIEWER 단일 승인으로 의도적 변경)
- 검수 상세에서 라벨 승인/반려, 라벨 diff 비교, 버전 롤백 → [13](13-version-control.md)
- 코드: `review/ReviewController`, `ReviewService`

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
