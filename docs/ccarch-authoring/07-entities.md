# ENTITY 노드 정의 — 저작도구 책임 도메인 객체

> `klid_system` 공유 DB 의 기존 테이블 + LS_DATA_LBL 3개 컬럼 추가만 사용 (신규 테이블 없음 원칙). 관제서버가 학습데이터를 외부로 전달할 때 동일 DB 의 학습데이터 테이블을 직접 조회한다.
>
> **ccarch v1 attrs 규약 (이 파일의 핵심)** — ENTITY 노드 1건은 가이드의 `usedInArtifacts` 에 따라 5종 시스템 산출물(CLASS_DESIGN / ENTITY_RELATIONSHIP_MODEL / DATABASE_DESIGN / DATABASE_TABLE / DATA_MIGRATION_DESIGN)의 source 가 된다. `severity=ERROR` 필드 누락 시 산출물 추출이 차단되므로, 본 파일의 각 ENTITY 는 아래 attrs 키를 함께 등록한다.
>
> | 산출물 | 충족 키(ERROR) | 본 파일 채움 정책 |
> |---|---|---|
> | CLASS_DESIGN | `node.title`, `attrs.classId` | `attrs.classId` = `attrs.tableId` 동일값(테이블명) |
> | ENTITY_RELATIONSHIP_MODEL | `node.title`, `attrs.entityId`, `attrs.attributes`, `attrs.primaryKey` | 본 파일에서 채움 |
> | DATABASE_DESIGN | `node.title`, `attrs.tableId`, `attrs.columns`, `attrs.primaryKeyColumns` | 본 파일에서 채움 (`attributes` 와 `columns` 는 동일 컬럼 메타 재사용) |
> | DATABASE_TABLE | `node.title(scriptName)`, `attrs.scriptId`, `attrs.databaseId`, `attrs.tableId` | **추후 작업** — DB 생성 스크립트(Flyway) 별도 매핑 시 보강 |
> | DATA_MIGRATION_DESIGN | `attrs.purpose`, `targetSystems`, `targetData`, `dataMapping` | **추후 작업** — 운영 전환 시 보강 |
>
> `relationships`/`notNullConstraints` 는 WARN severity 라 누락해도 추출은 가능하지만, 가독성·감리 추적성을 위해 권장 채움.
> 컬럼 메타 객체 스펙: `{"name": "...", "type": "...", "nullable": true|false, "comment": "..."}` (가이드 미강제 — 통일을 위해 본 폴더 규약).

## ENT-01. LS_DATA_RAW (영상 원본)
```json
{
  "type": "ENTITY",
  "title": "LS_DATA_RAW (영상 원본)",
  "content": "**Aggregate Root** — 자동 처리 파이프라인의 시작점. PRVC_TYPE_CD 가 PRVC/PSDO 면 비식별 위탁, ANONY 면 원본만 저장. DATA_STTS_CD 가 상태 전이(PENDING → FRAME_EXTRACT → … → COMPLETED/FAILED) 의 단일 진실.\n\n**관계**\n- 1:N `LS_DATA_SRC` (프레임)\n- 1:1 `LS_RAW_DATA_STATUS` (검수 상태)\n- 1:N `LS_TASK_ASSIGNMENT` (배정)\n- 1:N `LS_DATA_AUG` (증강)\n- 1:N `LS_DATA_META` (시계열·외부 메타)",
  "attrs": {
    "aggregate": "Video",
    "classId": "LS_DATA_RAW",
    "entityId": "LS_DATA_RAW",
    "tableId": "LS_DATA_RAW",
    "primaryKey": ["RAW_SN"],
    "primaryKeyColumns": ["RAW_SN"],
    "synonym": "영상 원본",
    "attributes": [
      {"name": "RAW_SN", "type": "BIGINT", "nullable": false, "comment": "PK / AUTO_INCREMENT"},
      {"name": "VMS_CLIP_ID", "type": "VARCHAR(128)", "nullable": false, "comment": "관제 클립 식별자 (UK)"},
      {"name": "VMS_CCTV_ID", "type": "VARCHAR(64)", "nullable": false, "comment": "CCTV 식별자"},
      {"name": "EVNT_TYPE_CD", "type": "VARCHAR(32)", "nullable": true, "comment": "침수/화재/쓰러짐/폭력/교통사고/유괴"},
      {"name": "LCLGV_CD", "type": "VARCHAR(32)", "nullable": true, "comment": "지자체 코드"},
      {"name": "PRVC_TYPE_CD", "type": "VARCHAR(16)", "nullable": false, "comment": "PRVC/PSDO/ANONY"},
      {"name": "DE_IDNTF_YN", "type": "VARCHAR(1)", "nullable": false, "comment": "Y/N/F (비식별 처리 결과)"},
      {"name": "FILE_PATH", "type": "VARCHAR(500)", "nullable": false, "comment": "원본 영상 경로"},
      {"name": "CAPTURED_AT", "type": "TIMESTAMP", "nullable": true, "comment": "촬영 시각"},
      {"name": "DATA_STTS_CD", "type": "VARCHAR(32)", "nullable": false, "comment": "파이프라인 상태"},
      {"name": "REG_DT", "type": "TIMESTAMP", "nullable": false, "comment": "DEFAULT CURRENT_TIMESTAMP"},
      {"name": "UPD_DT", "type": "TIMESTAMP", "nullable": true}
    ],
    "columns": [
      {"name": "RAW_SN", "type": "BIGINT", "nullable": false, "comment": "PK / AUTO_INCREMENT"},
      {"name": "VMS_CLIP_ID", "type": "VARCHAR(128)", "nullable": false, "comment": "UK"},
      {"name": "VMS_CCTV_ID", "type": "VARCHAR(64)", "nullable": false},
      {"name": "EVNT_TYPE_CD", "type": "VARCHAR(32)", "nullable": true},
      {"name": "LCLGV_CD", "type": "VARCHAR(32)", "nullable": true},
      {"name": "PRVC_TYPE_CD", "type": "VARCHAR(16)", "nullable": false},
      {"name": "DE_IDNTF_YN", "type": "VARCHAR(1)", "nullable": false, "comment": "DEFAULT 'N'"},
      {"name": "FILE_PATH", "type": "VARCHAR(500)", "nullable": false},
      {"name": "CAPTURED_AT", "type": "TIMESTAMP", "nullable": true},
      {"name": "DATA_STTS_CD", "type": "VARCHAR(32)", "nullable": false, "comment": "DEFAULT 'PENDING'"},
      {"name": "REG_DT", "type": "TIMESTAMP", "nullable": false},
      {"name": "UPD_DT", "type": "TIMESTAMP", "nullable": true}
    ],
    "relationships": [
      {"to": "LS_DATA_SRC", "cardinality": "1:N", "via": "RAW_SN"},
      {"to": "LS_RAW_DATA_STATUS", "cardinality": "1:1", "via": "RAW_SN"},
      {"to": "LS_TASK_ASSIGNMENT", "cardinality": "1:N", "via": "RAW_SN→RAW_DATA_ID"},
      {"to": "LS_DATA_AUG", "cardinality": "1:N", "via": "RAW_SN"},
      {"to": "LS_DATA_META", "cardinality": "1:N", "via": "RAW_SN"}
    ],
    "notNullConstraints": ["RAW_SN", "VMS_CLIP_ID", "VMS_CCTV_ID", "PRVC_TYPE_CD", "DE_IDNTF_YN", "FILE_PATH", "DATA_STTS_CD", "REG_DT"]
  },
  "_handle": "ent-data-raw"
}
```

## ENT-02. LS_DATA_SRC (프레임)
```json
{
  "type": "ENTITY",
  "title": "LS_DATA_SRC (프레임)",
  "content": "FFmpeg 로 추출한 프레임. frameNo 인덱스로 영상 내 순서 식별. 원본 경로와 비식별 경로가 동시 저장된다.\n\n**관계**: 1:N `LS_DATA_LBL`",
  "attrs": {
    "aggregate": "Video",
    "classId": "LS_DATA_SRC",
    "entityId": "LS_DATA_SRC",
    "tableId": "LS_DATA_SRC",
    "primaryKey": ["SRC_SN"],
    "primaryKeyColumns": ["SRC_SN"],
    "synonym": "프레임",
    "attributes": [
      {"name": "SRC_SN", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "RAW_SN", "type": "BIGINT", "nullable": false, "comment": "FK → LS_DATA_RAW"},
      {"name": "FRAME_NO", "type": "INT", "nullable": false, "comment": "프레임 순번"},
      {"name": "IMG_PATH", "type": "VARCHAR(500)", "nullable": false, "comment": "원본 이미지 경로"},
      {"name": "DEIDENT_IMG_PATH", "type": "VARCHAR(500)", "nullable": true, "comment": "비식별 이미지 경로 (PRVC/PSDO 인 경우만)"},
      {"name": "CAPTURED_AT", "type": "TIMESTAMP", "nullable": true}
    ],
    "columns": [
      {"name": "SRC_SN", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "RAW_SN", "type": "BIGINT", "nullable": false, "comment": "FK"},
      {"name": "FRAME_NO", "type": "INT", "nullable": false},
      {"name": "IMG_PATH", "type": "VARCHAR(500)", "nullable": false},
      {"name": "DEIDENT_IMG_PATH", "type": "VARCHAR(500)", "nullable": true},
      {"name": "CAPTURED_AT", "type": "TIMESTAMP", "nullable": true}
    ],
    "relationships": [
      {"to": "LS_DATA_RAW", "cardinality": "N:1", "via": "RAW_SN"},
      {"to": "LS_DATA_LBL", "cardinality": "1:N", "via": "SRC_SN"}
    ],
    "notNullConstraints": ["SRC_SN", "RAW_SN", "FRAME_NO", "IMG_PATH"]
  },
  "_handle": "ent-data-src"
}
```

## ENT-03. LS_DATA_LBL (라벨)
```json
{
  "type": "ENTITY",
  "title": "LS_DATA_LBL (라벨) — 본 사업에서 3컬럼 추가",
  "content": "프레임에 부착된 단일 라벨. **본 사업에서 3개 컬럼 추가** (LBL_SRC_CD, CONFIDENCE, LABEL_ID 등 마이그레이션 V20/V32 참조). Mass Assignment 방어를 위해 record DTO + autoLblYn 무시. 좌표 점 1000개 한도.\n\n**관계**\n- N:1 `LS_DATA_SRC`\n- N:1 `LS_LABEL` (라벨 마스터)\n- 1:1 `LS_DATA_LBL_AI_INFO` (AI 메타)\n- 1:N `LS_DATA_LBL_ATTR_VAL` (속성 값)",
  "attrs": {
    "aggregate": "Video",
    "classId": "LS_DATA_LBL",
    "entityId": "LS_DATA_LBL",
    "tableId": "LS_DATA_LBL",
    "primaryKey": ["LBL_SN"],
    "primaryKeyColumns": ["LBL_SN"],
    "synonym": "라벨",
    "attributes": [
      {"name": "LBL_SN", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "SRC_SN", "type": "BIGINT", "nullable": false, "comment": "FK → LS_DATA_SRC"},
      {"name": "LABEL_ID", "type": "BIGINT", "nullable": true, "comment": "FK → LS_LABEL (본 사업 V32 추가)"},
      {"name": "LBL_TYPE_CD", "type": "VARCHAR(16)", "nullable": false, "comment": "BBOX/POLYGON/SEGMENT/TRACK"},
      {"name": "LBL_SRC_CD", "type": "VARCHAR(32)", "nullable": true, "comment": "MANUAL/AUTO_YOLO/AUTO_SAM2 (본 사업 V20 추가)"},
      {"name": "LABEL", "type": "VARCHAR(255)", "nullable": false, "comment": "라벨 표시명"},
      {"name": "POINTS_JSON", "type": "LONGTEXT", "nullable": true, "comment": "좌표 — BBox: x,y,w,h / Polygon: points / Mask: RLE"},
      {"name": "CONFIDENCE", "type": "DECIMAL(5,4)", "nullable": true, "comment": "AI 추론 신뢰도 (본 사업 추가)"},
      {"name": "TRCK_ID", "type": "VARCHAR(64)", "nullable": true, "comment": "SAM2 트랙 ID"},
      {"name": "REG_USER_NO", "type": "BIGINT", "nullable": true},
      {"name": "REG_DT", "type": "TIMESTAMP", "nullable": false}
    ],
    "columns": [
      {"name": "LBL_SN", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "SRC_SN", "type": "BIGINT", "nullable": false},
      {"name": "LABEL_ID", "type": "BIGINT", "nullable": true},
      {"name": "LBL_TYPE_CD", "type": "VARCHAR(16)", "nullable": false},
      {"name": "LBL_SRC_CD", "type": "VARCHAR(32)", "nullable": true},
      {"name": "LABEL", "type": "VARCHAR(255)", "nullable": false},
      {"name": "POINTS_JSON", "type": "LONGTEXT", "nullable": true},
      {"name": "CONFIDENCE", "type": "DECIMAL(5,4)", "nullable": true},
      {"name": "TRCK_ID", "type": "VARCHAR(64)", "nullable": true}
    ],
    "relationships": [
      {"to": "LS_DATA_SRC", "cardinality": "N:1", "via": "SRC_SN"},
      {"to": "LS_LABEL", "cardinality": "N:1", "via": "LABEL_ID"}
    ],
    "notNullConstraints": ["LBL_SN", "SRC_SN", "LBL_TYPE_CD", "LABEL", "REG_DT"]
  },
  "_handle": "ent-data-lbl"
}
```

## ENT-04. LS_LABEL (라벨 마스터)
```json
{
  "type": "ENTITY",
  "title": "LS_LABEL (라벨 마스터)",
  "content": "**Aggregate Root** — CVAT-Like 라벨 풀. 부모-자식 트리 (PARENT_LABEL_ID), 활성/비활성 토글.\n\n**관계**: 1:N `LS_LABEL_ATTR`, 1:N `LS_DATA_LBL`",
  "attrs": {
    "aggregate": "LabelMaster",
    "classId": "LS_LABEL",
    "entityId": "LS_LABEL",
    "tableId": "LS_LABEL",
    "primaryKey": ["LABEL_ID"],
    "primaryKeyColumns": ["LABEL_ID"],
    "synonym": "라벨 마스터",
    "attributes": [
      {"name": "LABEL_ID", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "LABEL_NAME", "type": "VARCHAR(100)", "nullable": false, "comment": "라벨 표시명 (UK)"},
      {"name": "COLOR", "type": "VARCHAR(7)", "nullable": true, "comment": "#RRGGBB"},
      {"name": "CATEGORY", "type": "VARCHAR(50)", "nullable": true},
      {"name": "PARENT_LABEL_ID", "type": "BIGINT", "nullable": true, "comment": "자기참조 FK"},
      {"name": "ACTIVE_YN", "type": "VARCHAR(1)", "nullable": false, "comment": "DEFAULT 'Y'"}
    ],
    "columns": [
      {"name": "LABEL_ID", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "LABEL_NAME", "type": "VARCHAR(100)", "nullable": false},
      {"name": "COLOR", "type": "VARCHAR(7)", "nullable": true},
      {"name": "CATEGORY", "type": "VARCHAR(50)", "nullable": true},
      {"name": "PARENT_LABEL_ID", "type": "BIGINT", "nullable": true},
      {"name": "ACTIVE_YN", "type": "VARCHAR(1)", "nullable": false}
    ],
    "relationships": [
      {"to": "LS_LABEL", "cardinality": "1:N", "via": "PARENT_LABEL_ID", "comment": "자기참조"},
      {"to": "LS_LABEL_ATTR", "cardinality": "1:N", "via": "LABEL_ID"},
      {"to": "LS_DATA_LBL", "cardinality": "1:N", "via": "LABEL_ID"}
    ],
    "notNullConstraints": ["LABEL_ID", "LABEL_NAME", "ACTIVE_YN"]
  },
  "_handle": "ent-label-master"
}
```

## ENT-05. LS_LABEL_ATTR (라벨 속성 정의)
```json
{
  "type": "ENTITY",
  "title": "LS_LABEL_ATTR (라벨 속성 정의)",
  "content": "라벨 마스터에 종속된 추가 속성 정의 (예: 차량 색상, 의류 종류).",
  "attrs": {
    "aggregate": "LabelMaster",
    "classId": "LS_LABEL_ATTR",
    "entityId": "LS_LABEL_ATTR",
    "tableId": "LS_LABEL_ATTR",
    "primaryKey": ["ATTR_ID"],
    "primaryKeyColumns": ["ATTR_ID"],
    "synonym": "라벨 속성 정의",
    "attributes": [
      {"name": "ATTR_ID", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "LABEL_ID", "type": "BIGINT", "nullable": false, "comment": "FK → LS_LABEL"},
      {"name": "ATTR_NAME", "type": "VARCHAR(100)", "nullable": false},
      {"name": "ATTR_TYPE", "type": "VARCHAR(16)", "nullable": false, "comment": "TEXT/NUMBER/ENUM"},
      {"name": "ENUM_VALUES", "type": "JSON", "nullable": true, "comment": "ATTR_TYPE='ENUM' 일 때만"},
      {"name": "REQUIRED_YN", "type": "VARCHAR(1)", "nullable": false, "comment": "DEFAULT 'N'"}
    ],
    "columns": [
      {"name": "ATTR_ID", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "LABEL_ID", "type": "BIGINT", "nullable": false},
      {"name": "ATTR_NAME", "type": "VARCHAR(100)", "nullable": false},
      {"name": "ATTR_TYPE", "type": "VARCHAR(16)", "nullable": false},
      {"name": "ENUM_VALUES", "type": "JSON", "nullable": true},
      {"name": "REQUIRED_YN", "type": "VARCHAR(1)", "nullable": false}
    ],
    "relationships": [
      {"to": "LS_LABEL", "cardinality": "N:1", "via": "LABEL_ID"}
    ],
    "notNullConstraints": ["ATTR_ID", "LABEL_ID", "ATTR_NAME", "ATTR_TYPE", "REQUIRED_YN"]
  },
  "_handle": "ent-label-attr"
}
```

## ENT-06. LS_DATA_META (시계열·외부 메타)
```json
{
  "type": "ENTITY",
  "title": "LS_DATA_META (시계열 및 외부 메타데이터)",
  "content": "본 도구 ai-server 외부의 VLM 호출로 생성된 시계열 메타 + 외부 시스템 인계 메타가 함께 적재된다. 영상/프레임 단위 K/V 저장.\n\n**관계**: 1:1 `LS_DATA_META_REVIEW` (검토 상태)",
  "attrs": {
    "aggregate": "Video",
    "classId": "LS_DATA_META",
    "entityId": "LS_DATA_META",
    "tableId": "LS_DATA_META",
    "primaryKey": ["META_SN"],
    "primaryKeyColumns": ["META_SN"],
    "synonym": "시계열 및 외부 메타",
    "attributes": [
      {"name": "META_SN", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "RAW_SN", "type": "BIGINT", "nullable": true, "comment": "영상 단위 메타 시 사용 (FK → LS_DATA_RAW)"},
      {"name": "SRC_SN", "type": "BIGINT", "nullable": true, "comment": "프레임 단위 메타 시 사용 (FK → LS_DATA_SRC)"},
      {"name": "META_KEY", "type": "VARCHAR(100)", "nullable": false, "comment": "(RAW_SN, META_KEY) UK"},
      {"name": "META_VALUE", "type": "TEXT", "nullable": true},
      {"name": "META_SRC_CD", "type": "VARCHAR(32)", "nullable": false, "comment": "VLM/EXTERNAL"},
      {"name": "REG_DT", "type": "TIMESTAMP", "nullable": false},
      {"name": "UPD_DT", "type": "TIMESTAMP", "nullable": true}
    ],
    "columns": [
      {"name": "META_SN", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "RAW_SN", "type": "BIGINT", "nullable": true},
      {"name": "SRC_SN", "type": "BIGINT", "nullable": true},
      {"name": "META_KEY", "type": "VARCHAR(100)", "nullable": false},
      {"name": "META_VALUE", "type": "TEXT", "nullable": true},
      {"name": "META_SRC_CD", "type": "VARCHAR(32)", "nullable": false},
      {"name": "REG_DT", "type": "TIMESTAMP", "nullable": false}
    ],
    "relationships": [
      {"to": "LS_DATA_RAW", "cardinality": "N:1", "via": "RAW_SN"},
      {"to": "LS_DATA_SRC", "cardinality": "N:1", "via": "SRC_SN"},
      {"to": "LS_DATA_META_REVIEW", "cardinality": "1:1", "via": "META_SN"}
    ],
    "notNullConstraints": ["META_SN", "META_KEY", "META_SRC_CD", "REG_DT"]
  },
  "_handle": "ent-data-meta"
}
```

## ENT-07. LS_DATA_META_REVIEW (메타 검토 상태)
```json
{
  "type": "ENTITY",
  "title": "LS_DATA_META_REVIEW (메타 검토 상태)",
  "content": "VLM 자동 생성 메타 또는 외부 시스템 인계 메타에 대해 REVIEWER 검토 상태 관리. AUTO_GENERATED → PENDING → APPROVED|REJECTED 상태 전이.",
  "attrs": {
    "aggregate": "Video",
    "classId": "LS_DATA_META_REVIEW",
    "entityId": "LS_DATA_META_REVIEW",
    "tableId": "LS_DATA_META_REVIEW",
    "primaryKey": ["RVW_SN"],
    "primaryKeyColumns": ["RVW_SN"],
    "synonym": "메타 검토 상태",
    "attributes": [
      {"name": "RVW_SN", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "META_SN", "type": "BIGINT", "nullable": false, "comment": "FK → LS_DATA_META"},
      {"name": "META_TYPE_CD", "type": "VARCHAR(16)", "nullable": false, "comment": "VLM/EXTERNAL"},
      {"name": "RVW_STTS_CD", "type": "VARCHAR(32)", "nullable": false, "comment": "AUTO_GENERATED/PENDING/APPROVED/REJECTED"},
      {"name": "REVIEWER_USER_NO", "type": "BIGINT", "nullable": true},
      {"name": "REVIEWED_AT", "type": "TIMESTAMP", "nullable": true}
    ],
    "columns": [
      {"name": "RVW_SN", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "META_SN", "type": "BIGINT", "nullable": false},
      {"name": "META_TYPE_CD", "type": "VARCHAR(16)", "nullable": false},
      {"name": "RVW_STTS_CD", "type": "VARCHAR(32)", "nullable": false},
      {"name": "REVIEWER_USER_NO", "type": "BIGINT", "nullable": true},
      {"name": "REVIEWED_AT", "type": "TIMESTAMP", "nullable": true}
    ],
    "relationships": [
      {"to": "LS_DATA_META", "cardinality": "N:1", "via": "META_SN"}
    ],
    "notNullConstraints": ["RVW_SN", "META_SN", "META_TYPE_CD", "RVW_STTS_CD"]
  },
  "_handle": "ent-data-meta-review"
}
```

## ENT-08. LS_RAW_DATA_STATUS (검수 상태 / @Version)
```json
{
  "type": "ENTITY",
  "title": "LS_RAW_DATA_STATUS (검수 상태 / 낙관적 잠금)",
  "content": "검수 워크플로우 상태 단일 진실. **CWE-362 Race Condition 방어** — @Version 으로 낙관적 잠금. PENDING → ASSIGNED → IN_REVIEW → APPROVED|REJECTED.",
  "attrs": {
    "aggregate": "Review",
    "classId": "LS_RAW_DATA_STATUS",
    "entityId": "LS_RAW_DATA_STATUS",
    "tableId": "LS_RAW_DATA_STATUS",
    "primaryKey": ["STATUS_SN"],
    "primaryKeyColumns": ["STATUS_SN"],
    "synonym": "검수 상태",
    "attributes": [
      {"name": "STATUS_SN", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "RAW_SN", "type": "BIGINT", "nullable": false, "comment": "FK → LS_DATA_RAW (UK)"},
      {"name": "STATUS", "type": "VARCHAR(32)", "nullable": false, "comment": "PENDING/ASSIGNED/IN_REVIEW/APPROVED/REJECTED"},
      {"name": "REVIEW_COMPLETED_AT", "type": "TIMESTAMP", "nullable": true},
      {"name": "REVIEWER_USER_NO", "type": "BIGINT", "nullable": true},
      {"name": "VERSION", "type": "BIGINT", "nullable": false, "comment": "@Version (낙관적 잠금)"}
    ],
    "columns": [
      {"name": "STATUS_SN", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "RAW_SN", "type": "BIGINT", "nullable": false},
      {"name": "STATUS", "type": "VARCHAR(32)", "nullable": false},
      {"name": "REVIEW_COMPLETED_AT", "type": "TIMESTAMP", "nullable": true},
      {"name": "REVIEWER_USER_NO", "type": "BIGINT", "nullable": true},
      {"name": "VERSION", "type": "BIGINT", "nullable": false}
    ],
    "relationships": [
      {"to": "LS_DATA_RAW", "cardinality": "1:1", "via": "RAW_SN"}
    ],
    "notNullConstraints": ["STATUS_SN", "RAW_SN", "STATUS", "VERSION"]
  },
  "_handle": "ent-raw-data-status"
}
```

## ENT-09. LS_TASK_ASSIGNMENT (작업 배정)
```json
{
  "type": "ENTITY",
  "title": "LS_TASK_ASSIGNMENT (작업 배정)",
  "content": "REVIEWER 가 WORKER 에게 라벨링/검수 배정. UK (RAW_DATA_ID, USER_NO, TASK_TYPE_CD) — 동일 영상에 동일 사용자가 동일 역할로 중복 배정 불가.\n\n**관계**: 1:N `LS_TASK_ASSIGN_HISTORY`",
  "attrs": {
    "aggregate": "Assignment",
    "classId": "LS_TASK_ASSIGNMENT",
    "entityId": "LS_TASK_ASSIGNMENT",
    "tableId": "LS_TASK_ASSIGNMENT",
    "primaryKey": ["ASSIGNMENT_ID"],
    "primaryKeyColumns": ["ASSIGNMENT_ID"],
    "synonym": "작업 배정",
    "attributes": [
      {"name": "ASSIGNMENT_ID", "type": "BIGINT", "nullable": false, "comment": "PK / AUTO_INCREMENT"},
      {"name": "USER_NO", "type": "BIGINT", "nullable": false, "comment": "배정 대상 사용자"},
      {"name": "RAW_DATA_ID", "type": "BIGINT", "nullable": false, "comment": "FK → LS_DATA_RAW.RAW_SN"},
      {"name": "TASK_TYPE_CD", "type": "VARCHAR(32)", "nullable": false, "comment": "LABELER/REVIEWER"},
      {"name": "REG_USER_NO", "type": "BIGINT", "nullable": false, "comment": "배정한 사용자(REVIEWER)"},
      {"name": "REG_DT", "type": "TIMESTAMP", "nullable": false}
    ],
    "columns": [
      {"name": "ASSIGNMENT_ID", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "USER_NO", "type": "BIGINT", "nullable": false},
      {"name": "RAW_DATA_ID", "type": "BIGINT", "nullable": false},
      {"name": "TASK_TYPE_CD", "type": "VARCHAR(32)", "nullable": false},
      {"name": "REG_USER_NO", "type": "BIGINT", "nullable": false},
      {"name": "REG_DT", "type": "TIMESTAMP", "nullable": false}
    ],
    "relationships": [
      {"to": "LS_DATA_RAW", "cardinality": "N:1", "via": "RAW_DATA_ID"},
      {"to": "LS_TASK_ASSIGN_HISTORY", "cardinality": "1:N", "via": "ASSIGNMENT_ID"}
    ],
    "notNullConstraints": ["ASSIGNMENT_ID", "USER_NO", "RAW_DATA_ID", "TASK_TYPE_CD", "REG_USER_NO", "REG_DT"]
  },
  "_handle": "ent-task-assignment"
}
```

## ENT-10. LS_DATA_ISSUE (검수 이슈)
```json
{
  "type": "ENTITY",
  "title": "LS_DATA_ISSUE (검수 이슈 코멘트)",
  "content": "REVIEWER 가 라벨링 결과에 대해 남기는 이슈 코멘트. 라벨 단위(LBL_SN) 또는 프레임 단위(SRC_SN) 또는 영상 단위(RAW_SN). OPEN → RESOLVED.",
  "attrs": {
    "aggregate": "Review",
    "classId": "LS_DATA_ISSUE",
    "entityId": "LS_DATA_ISSUE",
    "tableId": "LS_DATA_ISSUE",
    "primaryKey": ["ISSUE_SN"],
    "primaryKeyColumns": ["ISSUE_SN"],
    "synonym": "검수 이슈",
    "attributes": [
      {"name": "ISSUE_SN", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "RAW_SN", "type": "BIGINT", "nullable": true},
      {"name": "SRC_SN", "type": "BIGINT", "nullable": true},
      {"name": "LBL_SN", "type": "BIGINT", "nullable": true},
      {"name": "REVIEWER_USER_NO", "type": "BIGINT", "nullable": false},
      {"name": "CONTENT", "type": "TEXT", "nullable": false},
      {"name": "STATUS", "type": "VARCHAR(16)", "nullable": false, "comment": "OPEN/RESOLVED"},
      {"name": "REG_DT", "type": "TIMESTAMP", "nullable": false},
      {"name": "RESOLVED_AT", "type": "TIMESTAMP", "nullable": true}
    ],
    "columns": [
      {"name": "ISSUE_SN", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "RAW_SN", "type": "BIGINT", "nullable": true},
      {"name": "SRC_SN", "type": "BIGINT", "nullable": true},
      {"name": "LBL_SN", "type": "BIGINT", "nullable": true},
      {"name": "REVIEWER_USER_NO", "type": "BIGINT", "nullable": false},
      {"name": "CONTENT", "type": "TEXT", "nullable": false},
      {"name": "STATUS", "type": "VARCHAR(16)", "nullable": false},
      {"name": "REG_DT", "type": "TIMESTAMP", "nullable": false}
    ],
    "relationships": [
      {"to": "LS_DATA_RAW", "cardinality": "N:1", "via": "RAW_SN"},
      {"to": "LS_DATA_SRC", "cardinality": "N:1", "via": "SRC_SN"},
      {"to": "LS_DATA_LBL", "cardinality": "N:1", "via": "LBL_SN"}
    ],
    "notNullConstraints": ["ISSUE_SN", "REVIEWER_USER_NO", "CONTENT", "STATUS", "REG_DT"]
  },
  "_handle": "ent-data-issue"
}
```

## ENT-11. LS_LABEL_VERSION (Gitea 커밋 메타)
```json
{
  "type": "ENTITY",
  "title": "LS_LABEL_VERSION (Gitea 커밋 메타)",
  "content": "라벨 저장 시 Gitea 커밋 hash 를 기록하여 버전 관리. diff/롤백/감리 추적의 기준. 한글 커밋 메시지 + frame·변화 카운트 enrichment.",
  "attrs": {
    "aggregate": "Version",
    "classId": "LS_LABEL_VERSION",
    "entityId": "LS_LABEL_VERSION",
    "tableId": "LS_LABEL_VERSION",
    "primaryKey": ["VERSION_SN"],
    "primaryKeyColumns": ["VERSION_SN"],
    "synonym": "라벨 버전 (Gitea 커밋 메타)",
    "attributes": [
      {"name": "VERSION_SN", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "RAW_SN", "type": "BIGINT", "nullable": false, "comment": "FK → LS_DATA_RAW"},
      {"name": "COMMIT_HASH", "type": "VARCHAR(64)", "nullable": false, "comment": "Gitea 커밋 SHA"},
      {"name": "COMMIT_MESSAGE_KR", "type": "TEXT", "nullable": true, "comment": "한글 커밋 메시지 + enrichment"},
      {"name": "COMMITTER_USER_NO", "type": "BIGINT", "nullable": false},
      {"name": "COMMITTED_AT", "type": "TIMESTAMP", "nullable": false}
    ],
    "columns": [
      {"name": "VERSION_SN", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "RAW_SN", "type": "BIGINT", "nullable": false},
      {"name": "COMMIT_HASH", "type": "VARCHAR(64)", "nullable": false},
      {"name": "COMMIT_MESSAGE_KR", "type": "TEXT", "nullable": true},
      {"name": "COMMITTER_USER_NO", "type": "BIGINT", "nullable": false},
      {"name": "COMMITTED_AT", "type": "TIMESTAMP", "nullable": false}
    ],
    "relationships": [
      {"to": "LS_DATA_RAW", "cardinality": "N:1", "via": "RAW_SN"}
    ],
    "notNullConstraints": ["VERSION_SN", "RAW_SN", "COMMIT_HASH", "COMMITTER_USER_NO", "COMMITTED_AT"]
  },
  "_handle": "ent-label-version"
}
```

## ENT-12. LS_DATA_AUG + AUG_RVW + AUG_LBL_MAP (증강 묶음)
```json
{
  "type": "ENTITY",
  "title": "LS_DATA_AUG (증강 묶음 Aggregate Root)",
  "content": "외부 생성형 AI 시스템으로의 증강 요청 + 결과 인계 + 검수 + 라벨 무결성 매핑. 본 ENTITY 1건이 LS_DATA_AUG / LS_DATA_AUG_RVW / LS_DATA_AUG_LBL_MAP 3개 테이블의 Aggregate Root 를 나타낸다.\n\n**자식 테이블**\n- `LS_DATA_AUG_RVW` — 증강 결과 검수 (VERDICT)\n- `LS_DATA_AUG_LBL_MAP` — 원본↔증강 라벨 매핑",
  "attrs": {
    "aggregate": "Augment",
    "classId": "LS_DATA_AUG",
    "entityId": "LS_DATA_AUG",
    "tableId": "LS_DATA_AUG",
    "primaryKey": ["AUG_SN"],
    "primaryKeyColumns": ["AUG_SN"],
    "synonym": "증강 묶음",
    "attributes": [
      {"name": "AUG_SN", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "RAW_SN", "type": "BIGINT", "nullable": false, "comment": "FK → LS_DATA_RAW"},
      {"name": "AUG_TYPE_CD", "type": "VARCHAR(16)", "nullable": false, "comment": "WINTER/NIGHT/RAIN/RESOLUTION"},
      {"name": "STATUS", "type": "VARCHAR(16)", "nullable": false, "comment": "REQUESTED/GENERATED/ACCEPTED/REJECTED"},
      {"name": "REQUEST_ID", "type": "VARCHAR(64)", "nullable": false, "comment": "외부 시스템 idempotency 키"},
      {"name": "REQUESTED_AT", "type": "TIMESTAMP", "nullable": false}
    ],
    "columns": [
      {"name": "AUG_SN", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "RAW_SN", "type": "BIGINT", "nullable": false},
      {"name": "AUG_TYPE_CD", "type": "VARCHAR(16)", "nullable": false},
      {"name": "STATUS", "type": "VARCHAR(16)", "nullable": false},
      {"name": "REQUEST_ID", "type": "VARCHAR(64)", "nullable": false},
      {"name": "REQUESTED_AT", "type": "TIMESTAMP", "nullable": false}
    ],
    "relationships": [
      {"to": "LS_DATA_RAW", "cardinality": "N:1", "via": "RAW_SN"},
      {"to": "LS_DATA_AUG_RVW", "cardinality": "1:1", "via": "AUG_SN", "comment": "자식 (Aggregate 내부)"},
      {"to": "LS_DATA_AUG_LBL_MAP", "cardinality": "1:N", "via": "AUG_SN", "comment": "자식 (Aggregate 내부)"}
    ],
    "notNullConstraints": ["AUG_SN", "RAW_SN", "AUG_TYPE_CD", "STATUS", "REQUEST_ID", "REQUESTED_AT"]
  },
  "_handle": "ent-data-aug"
}
```

## ENT-13. LS_LABEL_PRESET + LS_LABEL_PRESET_CODE
```json
{
  "type": "ENTITY",
  "title": "LS_LABEL_PRESET (라벨 프리셋 Aggregate Root)",
  "content": "**Aggregate Root** — 이벤트 타입 1:1 매핑. WORKER 가 이벤트별로 표시할 라벨 풀을 미리 묶음. clone 지원.\n\n**자식 테이블**: `LS_LABEL_PRESET_CODE` — 프리셋에 포함되는 라벨 ID 목록 + 순서",
  "attrs": {
    "aggregate": "Preset",
    "classId": "LS_LABEL_PRESET",
    "entityId": "LS_LABEL_PRESET",
    "tableId": "LS_LABEL_PRESET",
    "primaryKey": ["PRESET_ID"],
    "primaryKeyColumns": ["PRESET_ID"],
    "synonym": "라벨 프리셋",
    "attributes": [
      {"name": "PRESET_ID", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "PRESET_NAME", "type": "VARCHAR(100)", "nullable": false},
      {"name": "EVNT_TYPE_CD", "type": "VARCHAR(32)", "nullable": false, "comment": "UK — 이벤트 타입 당 1 프리셋"},
      {"name": "OWNER_USER_NO", "type": "BIGINT", "nullable": false},
      {"name": "REG_DT", "type": "TIMESTAMP", "nullable": false}
    ],
    "columns": [
      {"name": "PRESET_ID", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "PRESET_NAME", "type": "VARCHAR(100)", "nullable": false},
      {"name": "EVNT_TYPE_CD", "type": "VARCHAR(32)", "nullable": false},
      {"name": "OWNER_USER_NO", "type": "BIGINT", "nullable": false},
      {"name": "REG_DT", "type": "TIMESTAMP", "nullable": false}
    ],
    "relationships": [
      {"to": "LS_LABEL_PRESET_CODE", "cardinality": "1:N", "via": "PRESET_ID", "comment": "자식 (Aggregate 내부)"},
      {"to": "LS_LABEL", "cardinality": "N:M (via LS_LABEL_PRESET_CODE)", "via": "PRESET_ID/LABEL_ID"}
    ],
    "notNullConstraints": ["PRESET_ID", "PRESET_NAME", "EVNT_TYPE_CD", "OWNER_USER_NO", "REG_DT"]
  },
  "_handle": "ent-label-preset"
}
```

## ENT-14. LS_PORTAL_USER_VIDEO (포털 사용자 영상)
```json
{
  "type": "ENTITY",
  "title": "LS_PORTAL_USER_VIDEO (포털 사용자 업로드 영상)",
  "content": "포털 회원이 TUS 로 업로드한 본인 영상. **본인만 접근 가능** (수평 권한 격리 / CWE-863). 자동 라벨링 진입 후 LS_DATA_RAW 에 매핑 (RAW_SN). 보존 기간(RETENTION_UNTIL) 경과 시 삭제.",
  "attrs": {
    "aggregate": "PortalUser",
    "classId": "LS_PORTAL_USER_VIDEO",
    "entityId": "LS_PORTAL_USER_VIDEO",
    "tableId": "LS_PORTAL_USER_VIDEO",
    "primaryKey": ["PORTAL_VIDEO_SN"],
    "primaryKeyColumns": ["PORTAL_VIDEO_SN"],
    "synonym": "포털 사용자 영상",
    "attributes": [
      {"name": "PORTAL_VIDEO_SN", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "USER_NO", "type": "BIGINT", "nullable": false, "comment": "포털 회원 식별자"},
      {"name": "RAW_SN", "type": "BIGINT", "nullable": true, "comment": "FK → LS_DATA_RAW (자동 라벨링 진입 후 매핑)"},
      {"name": "UPLOAD_SIZE", "type": "BIGINT", "nullable": true},
      {"name": "UPLOAD_STATUS", "type": "VARCHAR(16)", "nullable": false, "comment": "UPLOADING/COMPLETED"},
      {"name": "RETENTION_UNTIL", "type": "TIMESTAMP", "nullable": true, "comment": "보존 만료"},
      {"name": "UPLOADED_AT", "type": "TIMESTAMP", "nullable": false}
    ],
    "columns": [
      {"name": "PORTAL_VIDEO_SN", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "USER_NO", "type": "BIGINT", "nullable": false},
      {"name": "RAW_SN", "type": "BIGINT", "nullable": true},
      {"name": "UPLOAD_SIZE", "type": "BIGINT", "nullable": true},
      {"name": "UPLOAD_STATUS", "type": "VARCHAR(16)", "nullable": false},
      {"name": "RETENTION_UNTIL", "type": "TIMESTAMP", "nullable": true},
      {"name": "UPLOADED_AT", "type": "TIMESTAMP", "nullable": false}
    ],
    "relationships": [
      {"to": "LS_DATA_RAW", "cardinality": "1:1", "via": "RAW_SN", "comment": "자동 라벨링 진입 후 매핑"}
    ],
    "notNullConstraints": ["PORTAL_VIDEO_SN", "USER_NO", "UPLOAD_STATUS", "UPLOADED_AT"]
  },
  "_handle": "ent-portal-user-video"
}
```

## ENT-15. LS_DEIDENT_REPORT + LS_BATCH_PROC_LOG (배치 보조)
```json
{
  "type": "ENTITY",
  "title": "LS_DEIDENT_REPORT (비식별 이력 Aggregate Root)",
  "content": "비식별 위탁 결과 이력 + 배치 단계별 진행 로그. 본 ENTITY 1건이 LS_DEIDENT_REPORT / LS_BATCH_PROC_LOG 두 테이블의 책임 영역(배치 운영 감사)을 나타낸다.\n\n**자식/연관**: LS_BATCH_PROC_LOG (단계별 로그)",
  "attrs": {
    "aggregate": "Batch",
    "classId": "LS_DEIDENT_REPORT",
    "entityId": "LS_DEIDENT_REPORT",
    "tableId": "LS_DEIDENT_REPORT",
    "primaryKey": ["DEIDENT_SN"],
    "primaryKeyColumns": ["DEIDENT_SN"],
    "synonym": "비식별 처리 이력",
    "attributes": [
      {"name": "DEIDENT_SN", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "RAW_SN", "type": "BIGINT", "nullable": false, "comment": "FK → LS_DATA_RAW"},
      {"name": "SRC_SN", "type": "BIGINT", "nullable": true, "comment": "FK → LS_DATA_SRC (프레임 단위 처리 시)"},
      {"name": "RESULT_CD", "type": "VARCHAR(16)", "nullable": false, "comment": "SUCCESS/FAILED"},
      {"name": "BLUR_OBJECTS_JSON", "type": "JSON", "nullable": true, "comment": "처리된 객체 좌표 목록"},
      {"name": "OPTIONS_JSON", "type": "JSON", "nullable": true, "comment": "비식별 옵션"},
      {"name": "PROCESSED_AT", "type": "TIMESTAMP", "nullable": false}
    ],
    "columns": [
      {"name": "DEIDENT_SN", "type": "BIGINT", "nullable": false, "comment": "PK"},
      {"name": "RAW_SN", "type": "BIGINT", "nullable": false},
      {"name": "SRC_SN", "type": "BIGINT", "nullable": true},
      {"name": "RESULT_CD", "type": "VARCHAR(16)", "nullable": false},
      {"name": "BLUR_OBJECTS_JSON", "type": "JSON", "nullable": true},
      {"name": "OPTIONS_JSON", "type": "JSON", "nullable": true},
      {"name": "PROCESSED_AT", "type": "TIMESTAMP", "nullable": false}
    ],
    "relationships": [
      {"to": "LS_DATA_RAW", "cardinality": "N:1", "via": "RAW_SN"},
      {"to": "LS_DATA_SRC", "cardinality": "N:1", "via": "SRC_SN"}
    ],
    "notNullConstraints": ["DEIDENT_SN", "RAW_SN", "RESULT_CD", "PROCESSED_AT"]
  },
  "_handle": "ent-batch-aux"
}
```

---

## 등록 순서

USECASE 등록 후, COMPONENT/INTERFACE 등록 전.

권장: ent-data-raw → ent-data-src → ent-data-lbl → ent-label-master → ent-label-attr → ent-data-meta → ent-data-meta-review → ent-raw-data-status → ent-task-assignment → ent-data-issue → ent-label-version → ent-data-aug → ent-label-preset → ent-portal-user-video → ent-batch-aux

## ccarch 산출물 자동 추출 활용

위 15개 ENTITY 등록 후 `mcp__ccarch__ccarch_get_system_artifact` 로 다음 5종 산출물을 자동 추출할 수 있다. attrs ERROR 필드를 본 파일에서 모두 채워 두었으므로 추출 시 차단 없음.

| 산출물 코드 | 본 파일 충족 상태 | 보강 잔여 |
|---|---|---|
| `CLASS_DESIGN` (전체 클래스 설계서) | ✅ ERROR 충족 (classId/title) | `attributes`/`operations` 는 WARN — 본 파일 채워둠 / `sequenceDiagram`/`classDiagram` 은 INFO (미충족) |
| `ENTITY_RELATIONSHIP_MODEL` (엔티티 관계 모형 기술서) | ✅ ERROR 충족 (entityId/title/attributes/primaryKey) | `relationships`/`notNullConstraints` 는 WARN — 본 파일 채워둠 / `synonym` 은 INFO — 본 파일 채워둠 |
| `DATABASE_DESIGN` (데이터베이스 설계서) | ✅ ERROR 충족 (tableId/title/columns/primaryKeyColumns) | `capacity`/`maxRows`/`retentionPeriod` 은 WARN — **추후 운영 환경에서 보강** |
| `DATABASE_TABLE` (데이터베이스 테이블) | ⚠️ ERROR 일부 미충족 (`scriptId`/`databaseId` 미보유) | **별도 작업**: Flyway 스크립트 V0~V42 별 매핑은 13-databases.md 의 DATABASE 노드와 함께 보강 |
| `DATA_MIGRATION_DESIGN` (데이터 전환 및 초기데이터 설계서) | ⚠️ ERROR 미충족 (`purpose`/`targetSystems`/`targetData`/`dataMapping` 미보유) | **별도 작업**: 운영 전환 시점에 별도 ENTITY 또는 attrs 보강. 본 도구는 신규 테이블 0건 정책이라 마이그레이션 범위가 LS_DATA_LBL 3컬럼 추가 한도 |

12-upload-plan.md 의 검증 단계 F-3 에서 위 매핑을 실제 추출 결과로 확인한다.
