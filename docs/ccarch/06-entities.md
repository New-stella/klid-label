# ENTITY 노드 정의

> ccarch type: `ENTITY` / 필수: `title`, `content` / 옵션: `aggregate`
> 본 사업 저작도구의 핵심 도메인 객체. 모두 `klid_system` 공유 DB의 기존 테이블 + LS_DATA_LBL 3개 컬럼 추가만 사용한다 (신규 테이블 없음 원칙).

## ENT-01. LS_DATA_RAW (영상 원본)

```json
{
  "type": "ENTITY",
  "title": "LS_DATA_RAW (영상 원본)",
  "content": "**Aggregate Root**: 영상 한 건의 본체. 자동 처리 파이프라인의 시작점.\n\n**핵심 속성**\n- `RAW_SN` (PK)\n- `VMS_CCTV_ID` — 출처 CCTV\n- `EVENT_TYPE_CD` — 이벤트 코드 (침수/화재/쓰러짐/폭력/교통사고/유괴)\n- `PRVC_TYPE_CD` — 개인정보 처리 분류 (ANONY/PRVC/PSDO) → 비식별 호출 분기\n- `STATUS` — 파이프라인 상태(PENDING/FRAME_EXTRACT/.../COMPLETED/FAILED)\n- `DATA_STTS_CD` — 데이터 상태\n- `CAPTURED_AT` — 촬영 시점\n- `DE_IDNTF_YN` — 비식별 처리 결과(Y/N/F)\n- `CREATED_AT`, `UPDATED_AT`\n\n**관계**\n- 1:N `LS_DATA_SRC` (프레임)\n- 1:1 `LS_RAW_DATA_STATUS` (검수 상태)\n- 1:N `LS_TASK_ASSIGNMENT` (배정)\n- 1:N `LS_DATA_AUG` (증강)\n\n**구현**: backend video 도메인 — `Video` 또는 `LsDataRaw` 엔티티",
  "attrs": {"aggregate": "Video"},
  "_handle": "ent-data-raw"
}
```

## ENT-02. LS_DATA_SRC (프레임)

```json
{
  "type": "ENTITY",
  "title": "LS_DATA_SRC (프레임)",
  "content": "영상에서 추출한 단일 프레임. FFmpeg로 추출되며 frameNo 인덱스를 가진다.\n\n**핵심 속성**\n- `SRC_SN` (PK)\n- `RAW_SN` (FK → LS_DATA_RAW)\n- `FRAME_NO` — 영상 내 프레임 순번\n- `IMG_PATH` — 원본 이미지 경로\n- `DEIDENT_IMG_PATH` — 비식별 이미지 경로 (null 가능)\n- `CAPTURED_AT`\n\n**관계**: 1:N `LS_DATA_LBL` (라벨)\n\n**구현**: backend label 도메인 / FE labelStore",
  "attrs": {"aggregate": "Video"},
  "_handle": "ent-data-src"
}
```

## ENT-03. LS_DATA_LBL (라벨)

```json
{
  "type": "ENTITY",
  "title": "LS_DATA_LBL (라벨) — 3컬럼 추가",
  "content": "프레임에 부착된 단일 라벨(BBox/Polygon/Mask). 본 사업에서 3개 컬럼만 추가(`autoLblYn`, `confidence`, `trackId` 또는 유사).\n\n**핵심 속성**\n- `LBL_SN` (PK)\n- `SRC_SN` (FK → LS_DATA_SRC)\n- `LABEL_ID` (FK → LS_LABEL 마스터)\n- `LBL_TYPE_CD` — BBOX / POLYGON / SEGMENT / TRACK\n- `LBL_SRC_CD` — MANUAL / AUTO_YOLO / AUTO_SAM2 / INTERPOLATED\n- `COORDINATES` — JSON 좌표 (BBox: x,y,w,h / Polygon: points / Mask: RLE)\n- `CONFIDENCE` — 0~1 (자동 라벨)\n- `TRACK_ID` — BoT-SORT 추적 ID\n- `CREATED_AT`, `UPDATED_AT`\n\n**관계**\n- 1:1 `LS_DATA_LBL_AI_INFO` (자동 라벨의 AI 메타)\n- 1:N `LS_DATA_LBL_ATTR_VAL` (속성 값)\n\n**중요**: Mass Assignment 방어를 위해 record DTO + autoLblYn 무시. 좌표 점 1000개 한도 (LabelPointSerializer)\n\n**구현**: backend label 도메인 — `Label` 엔티티 + LabelService",
  "attrs": {"aggregate": "Video"},
  "_handle": "ent-data-lbl"
}
```

## ENT-04. LS_LABEL (라벨 마스터)

```json
{
  "type": "ENTITY",
  "title": "LS_LABEL (라벨 마스터)",
  "content": "**Aggregate Root** — CVAT-Like 라벨 풀 (Phase 13, Flyway V31~V37).\n\n**핵심 속성**\n- `LABEL_ID` (PK)\n- `LABEL_NAME`\n- `COLOR` — #RRGGBB\n- `CATEGORY`\n- `PARENT_LABEL_ID` — 라벨 계층\n- `ACTIVE_YN`\n\n**관계**: 1:N `LS_LABEL_ATTR` (속성 정의)\n\n**구현**: backend label 도메인 — `LabelMaster` 엔티티",
  "attrs": {"aggregate": "LabelMaster"},
  "_handle": "ent-label-master"
}
```

## ENT-05. LS_LABEL_ATTR (라벨 속성 정의)

```json
{
  "type": "ENTITY",
  "title": "LS_LABEL_ATTR (라벨 속성 정의)",
  "content": "라벨 마스터에 종속된 추가 속성 정의 (예: 차종, 차량 색상).\n\n**핵심 속성**\n- `ATTR_ID` (PK)\n- `LABEL_ID` (FK → LS_LABEL)\n- `ATTR_NAME`\n- `ATTR_TYPE` — TEXT / NUMBER / ENUM\n- `ENUM_VALUES` — JSON\n- `REQUIRED_YN`\n\n**구현**: backend label 도메인 — `LabelAttr` 엔티티",
  "attrs": {"aggregate": "LabelMaster"},
  "_handle": "ent-label-attr"
}
```

## ENT-06. LS_DATA_META (시계열 메타)

```json
{
  "type": "ENTITY",
  "title": "LS_DATA_META (시계열 메타데이터)",
  "content": "프레임 단위로 외부 시스템이 생성한 자연어 설명·객체/행동 인식·환경 조건.\n\n**핵심 속성**\n- `META_SN` (PK)\n- `SRC_SN` (FK → LS_DATA_SRC)\n- `DESCRIPTION` — 프레임 자연어 설명\n- `OBJECTS_JSON` — 감지 객체 목록(JSON)\n- `ENVIRONMENT_JSON` — 시간/장소/날씨 등\n- `META_SRC_CD` — VLM_EXTERNAL / MANUAL\n- `CREATED_AT`, `UPDATED_AT`\n\n**책임 경계(V1.7)**: 자동 생성은 외부 책임, 본 도구는 검토·수정만.\n\n**구현**: backend meta 도메인",
  "attrs": {"aggregate": "Video"},
  "_handle": "ent-data-meta"
}
```

## ENT-07. LS_RAW_DATA_STATUS (검수 상태)

```json
{
  "type": "ENTITY",
  "title": "LS_RAW_DATA_STATUS (검수 상태 / @Version)",
  "content": "영상별 검수 상태를 추적하며 낙관적 잠금으로 동시성 보호.\n\n**핵심 속성**\n- `STATUS_SN` (PK)\n- `RAW_SN` (FK)\n- `STATUS` — PENDING / ASSIGNED / IN_REVIEW / APPROVED / REJECTED\n- `REVIEW_COMPLETED_AT` — 검수 완료 일시\n- `REVIEWER_USER_NO`\n- `@Version VERSION` — 낙관적 잠금 (CWE-362 Race Condition 방어)\n- `UPDATED_AT`\n\n**구현**: backend review 도메인 — `LsRawDataStatus` 엔티티",
  "attrs": {"aggregate": "Review"},
  "_handle": "ent-raw-data-status"
}
```

## ENT-08. LS_TASK_ASSIGNMENT (작업 배정)

```json
{
  "type": "ENTITY",
  "title": "LS_TASK_ASSIGNMENT (작업 배정)",
  "content": "**핵심 속성**\n- `ASGN_SN` (PK)\n- `RAW_DATA_ID` (FK → LS_DATA_RAW)\n- `USER_NO` (작업자/검수자)\n- `TASK_TYPE_CD` — LABELER / REVIEWER\n- `STATUS` — ASSIGNED / IN_PROGRESS / COMPLETED\n- `ASSIGNED_AT`\n- **UK**: (RAW_DATA_ID, USER_NO, TASK_TYPE_CD)\n\n**관계**: 1:N `LS_TASK_ASSIGN_HISTORY` (재배정 이력)\n\n**구현**: backend assignment 도메인 — `TaskAssignment` 엔티티",
  "attrs": {"aggregate": "Assignment"},
  "_handle": "ent-task-assignment"
}
```

## ENT-09. LS_DATA_ISSUE (검수 이슈)

```json
{
  "type": "ENTITY",
  "title": "LS_DATA_ISSUE (검수 이슈 코멘트)",
  "content": "**핵심 속성**\n- `ISSUE_SN` (PK)\n- `RAW_SN`, `SRC_SN`, `LBL_SN` (선택)\n- `REVIEWER_USER_NO`\n- `CONTENT` — 이슈 내용\n- `STATUS` — OPEN / RESOLVED\n- `CREATED_AT`, `RESOLVED_AT`\n\n**구현**: backend review 도메인 — `LsDataIssue`",
  "attrs": {"aggregate": "Review"},
  "_handle": "ent-data-issue"
}
```

## ENT-10. LS_LABEL_VERSION (라벨 버전 = Gitea 커밋)

```json
{
  "type": "ENTITY",
  "title": "LS_LABEL_VERSION + LS_DATA_LBL_HSTRY (Gitea 커밋 메타)",
  "content": "라벨 저장 시 Gitea 커밋 hash를 기록하여 버전 관리.\n\n**핵심 속성**\n- `VERSION_SN` (PK)\n- `RAW_SN` (FK)\n- `COMMIT_HASH` — Gitea 커밋 hash\n- `COMMIT_MESSAGE_KR` — 한글 커밋 메시지 (frame·변화 카운트 enrichment)\n- `COMMITTER_USER_NO`\n- `COMMITTED_AT`\n\n**구현**: backend version 도메인 — `LabelVersion` 엔티티",
  "attrs": {"aggregate": "Version"},
  "_handle": "ent-label-version"
}
```

## ENT-11. LS_DATA_AUG (증강 작업)

```json
{
  "type": "ENTITY",
  "title": "LS_DATA_AUG + LS_DATA_AUG_RVW + LS_DATA_AUG_LBL_MAP",
  "content": "검수 완료 영상에 대한 증강 요청·결과·라벨 매핑.\n\n**LS_DATA_AUG**\n- `AUG_SN` (PK)\n- `RAW_SN` (FK)\n- `AUG_TYPE_CD` — WINTER / NIGHT / RAIN / RESOLUTION\n- `STATUS` — REQUESTED / GENERATED / ACCEPTED / REJECTED\n- `REQUESTED_AT`\n\n**LS_DATA_AUG_RVW** — 증강 결과 검수\n- `RVW_SN`, `AUG_SN`, `VERDICT`, `REVIEWER_USER_NO`, `REVIEWED_AT`\n\n**LS_DATA_AUG_LBL_MAP** — 증강 전·후 라벨 매핑 (라벨 무결성)\n- `MAP_SN`, `AUG_SN`, `ORIGIN_LBL_SN`, `AUGMENTED_LBL_SN`\n\n**구현**: backend augment 도메인",
  "attrs": {"aggregate": "Augment"},
  "_handle": "ent-data-aug"
}
```

## ENT-12. LS_LABEL_PRESET (프리셋)

```json
{
  "type": "ENTITY",
  "title": "LS_LABEL_PRESET + LS_LABEL_PRESET_CODE (라벨 프리셋)",
  "content": "**Aggregate Root** — 이벤트 타입 1:1 매핑. clone 지원.\n\n**LS_LABEL_PRESET**\n- `PRESET_ID` (PK)\n- `PRESET_NAME`\n- `EVENT_TYPE_CD` — 6종 이벤트 (UK)\n- `OWNER_USER_NO`\n\n**LS_LABEL_PRESET_CODE** — 프리셋 내 라벨 코드\n- `PRESET_CODE_ID`, `PRESET_ID`, `LABEL_ID`, `ORDER_NO`\n\n**구현**: backend preset 도메인",
  "attrs": {"aggregate": "Preset"},
  "_handle": "ent-label-preset"
}
```

## ENT-13. LS_PORTAL_USER_VIDEO (포털 사용자 영상)

```json
{
  "type": "ENTITY",
  "title": "LS_PORTAL_USER_VIDEO (포털 사용자 업로드 영상)",
  "content": "포털 회원이 TUS로 업로드한 본인 영상. 본인만 접근 가능.\n\n**핵심 속성**\n- `PORTAL_VIDEO_SN` (PK)\n- `USER_NO` — 포털 회원\n- `RAW_SN` (FK → LS_DATA_RAW, 자동 라벨링 진입 후 매핑)\n- `UPLOAD_SIZE`\n- `UPLOAD_STATUS` — UPLOADING / COMPLETED\n- `RETENTION_UNTIL` — 다운로드 기한\n- `UPLOADED_AT`\n\n**구현**: backend portal 도메인 — `PortalUserVideo`",
  "attrs": {"aggregate": "PortalUser"},
  "_handle": "ent-portal-user-video"
}
```

## ENT-14. LS_DEIDENT_REPORT (비식별 처리 리포트)

```json
{
  "type": "ENTITY",
  "title": "LS_DEIDENT_REPORT (비식별 처리 이력)",
  "content": "프레임 단위 비식별 처리 결과를 기록.\n\n**핵심 속성**\n- `DEIDENT_SN` (PK)\n- `RAW_SN`, `SRC_SN`\n- `RESULT_CD` — SUCCESS / FAILED / SKIPPED(ANONY)\n- `BLUR_OBJECTS_JSON` — 처리된 영역 메타\n- `OPTIONS_JSON` — 사용된 비식별 옵션\n- `PROCESSED_AT`\n\n**구현**: backend video 도메인 보조 또는 별도",
  "attrs": {"aggregate": "Video"},
  "_handle": "ent-deident-report"
}
```

## ENT-15. LS_BATCH_PROC_LOG (배치 처리 로그)

```json
{
  "type": "ENTITY",
  "title": "LS_BATCH_PROC_LOG (배치 단계별 로그)",
  "content": "**핵심 속성**\n- `LOG_SN` (PK)\n- `RAW_SN`\n- `STAGE_CD` — FRAME_EXTRACT / YOLO / SAM2 / DEIDENTIFY / TRACK_INTERPOLATION / VLM_META\n- `STATUS_CD` — STARTED / SUCCESS / FAILED\n- `RETRY_CNT`\n- `ERR_MSG`\n- `STARTED_AT`, `ENDED_AT`\n\n**구현**: backend batch 도메인 — `BatchProcLog`",
  "attrs": {"aggregate": "Batch"},
  "_handle": "ent-batch-log"
}
```

---

## 등록 순서

ENTITY는 USECASE 등록 후 일괄 등록. COMPONENT/INTERFACE에서 `DEPENDS_ON → ENTITY`로 참조하므로 그보다 먼저.

권장: ent-data-raw → ent-data-src → ent-data-lbl → ent-label-master → ent-label-attr → ent-data-meta → ent-raw-data-status → ent-task-assignment → ent-data-issue → ent-label-version → ent-data-aug → ent-label-preset → ent-portal-user-video → ent-deident-report → ent-batch-log
