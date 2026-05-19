# ENTITY 노드 정의 — 저작도구 책임 도메인 객체

> `klid_system` 공유 DB 의 기존 테이블 + LS_DATA_LBL 3개 컬럼 추가만 사용 (신규 테이블 없음 원칙). 관제서버가 학습데이터를 외부로 전달할 때 동일 DB 의 학습데이터 테이블을 직접 조회한다.

## ENT-01. LS_DATA_RAW (영상 원본)
```json
{
  "type": "ENTITY",
  "title": "LS_DATA_RAW (영상 원본)",
  "content": "**Aggregate Root** — 자동 처리 파이프라인의 시작점.\n\n**핵심 속성**\n- `RAW_SN` (PK)\n- `VMS_CCTV_ID`\n- `EVENT_TYPE_CD` (침수/화재/쓰러짐/폭력/교통사고/유괴)\n- `STATUS` (PENDING/FRAME_EXTRACT/.../COMPLETED/FAILED)\n- `DATA_STTS_CD`\n- `CAPTURED_AT`\n- `DE_IDNTF_YN` (Y/N/F)\n- `CREATED_AT`, `UPDATED_AT`\n\n**관계**\n- 1:N `LS_DATA_SRC`\n- 1:1 `LS_RAW_DATA_STATUS`\n- 1:N `LS_TASK_ASSIGNMENT`\n- 1:N `LS_DATA_AUG`",
  "attrs": {"aggregate": "Video"},
  "_handle": "ent-data-raw"
}
```

## ENT-02. LS_DATA_SRC (프레임)
```json
{
  "type": "ENTITY",
  "title": "LS_DATA_SRC (프레임)",
  "content": "FFmpeg 로 추출한 프레임. frameNo 인덱스.\n\n**핵심 속성**: `SRC_SN`(PK), `RAW_SN`(FK), `FRAME_NO`, `IMG_PATH`, `DEIDENT_IMG_PATH`, `CAPTURED_AT`\n\n**관계**: 1:N `LS_DATA_LBL`",
  "attrs": {"aggregate": "Video"},
  "_handle": "ent-data-src"
}
```

## ENT-03. LS_DATA_LBL (라벨)
```json
{
  "type": "ENTITY",
  "title": "LS_DATA_LBL (라벨) — 본 사업에서 3컬럼 추가",
  "content": "프레임에 부착된 단일 라벨. 본 사업에서 3개 컬럼만 추가.\n\n**핵심 속성**\n- `LBL_SN` (PK)\n- `SRC_SN` (FK)\n- `LABEL_ID` (FK → LS_LABEL)\n- `LBL_TYPE_CD` (BBOX/POLYGON/SEGMENT/TRACK)\n- `LBL_SRC_CD` (MANUAL/AUTO_YOLO/AUTO_SAM2)\n- `COORDINATES` (JSON; BBox: x,y,w,h / Polygon: points / Mask: RLE)\n- `CONFIDENCE`\n- `TRACK_ID`\n\n**중요**: Mass Assignment 방어를 위해 record DTO + autoLblYn 무시. 좌표 점 1000개 한도.\n\n**관계**\n- 1:1 `LS_DATA_LBL_AI_INFO` (AI 메타)\n- 1:N `LS_DATA_LBL_ATTR_VAL` (속성 값)",
  "attrs": {"aggregate": "Video"},
  "_handle": "ent-data-lbl"
}
```

## ENT-04. LS_LABEL (라벨 마스터)
```json
{
  "type": "ENTITY",
  "title": "LS_LABEL (라벨 마스터)",
  "content": "**Aggregate Root** — CVAT-Like 라벨 풀.\n\n**핵심 속성**: `LABEL_ID`(PK), `LABEL_NAME`, `COLOR`(#RRGGBB), `CATEGORY`, `PARENT_LABEL_ID`, `ACTIVE_YN`\n\n**관계**: 1:N `LS_LABEL_ATTR`",
  "attrs": {"aggregate": "LabelMaster"},
  "_handle": "ent-label-master"
}
```

## ENT-05. LS_LABEL_ATTR (라벨 속성 정의)
```json
{
  "type": "ENTITY",
  "title": "LS_LABEL_ATTR (라벨 속성 정의)",
  "content": "라벨 마스터에 종속된 추가 속성 정의.\n\n**핵심 속성**: `ATTR_ID`(PK), `LABEL_ID`(FK), `ATTR_NAME`, `ATTR_TYPE`(TEXT/NUMBER/ENUM), `ENUM_VALUES`(JSON), `REQUIRED_YN`",
  "attrs": {"aggregate": "LabelMaster"},
  "_handle": "ent-label-attr"
}
```

## ENT-06. LS_DATA_META (시계열·외부 메타)
```json
{
  "type": "ENTITY",
  "title": "LS_DATA_META (시계열 및 외부 메타데이터)",
  "content": "본 도구 ai-server 외부의 VLM 호출로 생성된 시계열 메타 + 외부 시스템 인계 메타가 함께 적재된다.\n\n**핵심 속성**\n- `META_SN` (PK)\n- `RAW_SN` 또는 `SRC_SN` (시계열 단위 식별)\n- `META_KEY`, `META_VALUE` (K/V)\n- `META_SRC_CD` (VLM 호출 결과 / EXTERNAL 인계)\n- `CREATED_AT`, `UPDATED_AT`",
  "attrs": {"aggregate": "Video"},
  "_handle": "ent-data-meta"
}
```

## ENT-07. LS_DATA_META_REVIEW (메타 검토 상태)
```json
{
  "type": "ENTITY",
  "title": "LS_DATA_META_REVIEW (메타 검토 상태)",
  "content": "VLM 자동 생성 메타 또는 외부 시스템 인계 메타에 대해 REVIEWER 검토 상태 관리.\n\n**핵심 속성**\n- `RVW_SN` (PK)\n- `META_SN` (FK)\n- `META_TYPE_CD` ('VLM' | 'EXTERNAL')\n- `RVW_STTS_CD` ('AUTO_GENERATED' | 'PENDING' | 'APPROVED' | 'REJECTED')\n- `REVIEWER_USER_NO`\n- `REVIEWED_AT`",
  "attrs": {"aggregate": "Video"},
  "_handle": "ent-data-meta-review"
}
```

## ENT-08. LS_RAW_DATA_STATUS (검수 상태 / @Version)
```json
{
  "type": "ENTITY",
  "title": "LS_RAW_DATA_STATUS (검수 상태 / 낙관적 잠금)",
  "content": "**핵심 속성**\n- `STATUS_SN` (PK)\n- `RAW_SN` (FK)\n- `STATUS` (PENDING/ASSIGNED/IN_REVIEW/APPROVED/REJECTED)\n- `REVIEW_COMPLETED_AT`\n- `REVIEWER_USER_NO`\n- `@Version VERSION` (CWE-362 Race Condition 방어)",
  "attrs": {"aggregate": "Review"},
  "_handle": "ent-raw-data-status"
}
```

## ENT-09. LS_TASK_ASSIGNMENT (작업 배정)
```json
{
  "type": "ENTITY",
  "title": "LS_TASK_ASSIGNMENT (작업 배정)",
  "content": "**핵심 속성**: `ASGN_SN`(PK), `RAW_DATA_ID`(FK), `USER_NO`, `TASK_TYPE_CD`(LABELER/REVIEWER), `STATUS`(ASSIGNED/IN_PROGRESS/COMPLETED), `ASSIGNED_AT`\n**UK**: (RAW_DATA_ID, USER_NO, TASK_TYPE_CD)\n**관계**: 1:N `LS_TASK_ASSIGN_HISTORY`",
  "attrs": {"aggregate": "Assignment"},
  "_handle": "ent-task-assignment"
}
```

## ENT-10. LS_DATA_ISSUE (검수 이슈)
```json
{
  "type": "ENTITY",
  "title": "LS_DATA_ISSUE (검수 이슈 코멘트)",
  "content": "**핵심 속성**: `ISSUE_SN`(PK), `RAW_SN`, `SRC_SN`, `LBL_SN`(선택), `REVIEWER_USER_NO`, `CONTENT`, `STATUS`(OPEN/RESOLVED), `CREATED_AT`, `RESOLVED_AT`",
  "attrs": {"aggregate": "Review"},
  "_handle": "ent-data-issue"
}
```

## ENT-11. LS_LABEL_VERSION + LS_DATA_LBL_HSTRY (Gitea 커밋 메타)
```json
{
  "type": "ENTITY",
  "title": "LS_LABEL_VERSION + LS_DATA_LBL_HSTRY (Gitea 커밋 메타)",
  "content": "라벨 저장 시 Gitea 커밋 hash 를 기록하여 버전 관리.\n\n**핵심 속성**: `VERSION_SN`(PK), `RAW_SN`(FK), `COMMIT_HASH`, `COMMIT_MESSAGE_KR`(한글 + frame·변화 카운트 enrichment), `COMMITTER_USER_NO`, `COMMITTED_AT`",
  "attrs": {"aggregate": "Version"},
  "_handle": "ent-label-version"
}
```

## ENT-12. LS_DATA_AUG + AUG_RVW + AUG_LBL_MAP (증강 묶음)
```json
{
  "type": "ENTITY",
  "title": "LS_DATA_AUG + LS_DATA_AUG_RVW + LS_DATA_AUG_LBL_MAP",
  "content": "**LS_DATA_AUG**: `AUG_SN`(PK), `RAW_SN`(FK), `AUG_TYPE_CD`(WINTER/NIGHT/RAIN/RESOLUTION), `STATUS`(REQUESTED/GENERATED/ACCEPTED/REJECTED), `REQUESTED_AT`\n\n**LS_DATA_AUG_RVW** (증강 결과 검수): `RVW_SN`, `AUG_SN`, `VERDICT`, `REVIEWER_USER_NO`, `REVIEWED_AT`\n\n**LS_DATA_AUG_LBL_MAP** (라벨 무결성): `MAP_SN`, `AUG_SN`, `ORIGIN_LBL_SN`, `AUGMENTED_LBL_SN`",
  "attrs": {"aggregate": "Augment"},
  "_handle": "ent-data-aug"
}
```

## ENT-13. LS_LABEL_PRESET + LS_LABEL_PRESET_CODE
```json
{
  "type": "ENTITY",
  "title": "LS_LABEL_PRESET + LS_LABEL_PRESET_CODE (라벨 프리셋)",
  "content": "**Aggregate Root** — 이벤트 타입 1:1 매핑. clone 지원.\n\n**LS_LABEL_PRESET**: `PRESET_ID`(PK), `PRESET_NAME`, `EVENT_TYPE_CD`(UK), `OWNER_USER_NO`\n**LS_LABEL_PRESET_CODE**: `PRESET_CODE_ID`, `PRESET_ID`, `LABEL_ID`, `ORDER_NO`",
  "attrs": {"aggregate": "Preset"},
  "_handle": "ent-label-preset"
}
```

## ENT-14. LS_PORTAL_USER_VIDEO (포털 사용자 영상)
```json
{
  "type": "ENTITY",
  "title": "LS_PORTAL_USER_VIDEO (포털 사용자 업로드 영상)",
  "content": "포털 회원이 TUS 로 업로드한 본인 영상. 본인만 접근 가능.\n\n**핵심 속성**: `PORTAL_VIDEO_SN`(PK), `USER_NO`, `RAW_SN`(FK, 자동 라벨링 진입 후 매핑), `UPLOAD_SIZE`, `UPLOAD_STATUS`(UPLOADING/COMPLETED), `RETENTION_UNTIL`, `UPLOADED_AT`",
  "attrs": {"aggregate": "PortalUser"},
  "_handle": "ent-portal-user-video"
}
```

## ENT-15. LS_DEIDENT_REPORT + LS_BATCH_PROC_LOG (배치 보조)
```json
{
  "type": "ENTITY",
  "title": "LS_DEIDENT_REPORT + LS_BATCH_PROC_LOG (비식별 이력 + 배치 단계별 로그)",
  "content": "**LS_DEIDENT_REPORT**: `DEIDENT_SN`(PK), `RAW_SN`, `SRC_SN`, `RESULT_CD`(SUCCESS/FAILED), `BLUR_OBJECTS_JSON`, `OPTIONS_JSON`, `PROCESSED_AT`\n\n**LS_BATCH_PROC_LOG**: `LOG_SN`(PK), `RAW_SN`, `STAGE_CD`(FRAME_EXTRACT/YOLO/SAM2/DEIDENTIFY/VLM_TIMESERIES), `STATUS_CD`(STARTED/SUCCESS/FAILED), `RETRY_CNT`, `ERR_MSG`, `STARTED_AT`, `ENDED_AT`",
  "attrs": {"aggregate": "Batch"},
  "_handle": "ent-batch-aux"
}
```

---

## 등록 순서

USECASE 등록 후, COMPONENT/INTERFACE 등록 전.

권장: ent-data-raw → ent-data-src → ent-data-lbl → ent-label-master → ent-label-attr → ent-data-meta → ent-data-meta-review → ent-raw-data-status → ent-task-assignment → ent-data-issue → ent-label-version → ent-data-aug → ent-label-preset → ent-portal-user-video → ent-batch-aux
