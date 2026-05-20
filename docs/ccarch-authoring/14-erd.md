# ERD 노드 정의 — 저작도구 도메인 ERD

> ccarch v1 가이드의 `ERD` 노드 1건으로 저작도구 책임 도메인의 엔티티 관계 모형을 등록한다. 자동 ID 코드 **ERD**.
>
> **요구 필드**: `title`, `content`
> **선택 필드**: `diagramSource` (Mermaid ER / PlantUML / dbdiagram.io), `scope`
>
> **자동 추출**: ENTITY 노드 기반 `ENTITY_RELATIONSHIP_MODEL` 산출물(엔티티 관계 모형 기술서)은 ERD 노드 없이도 ENTITY 의 `attrs.relationships`/`primaryKey`/`attributes` 로 자동 추출된다. 본 ERD 노드는 **시각화 다이어그램 본체**를 보관하는 용도(감리·발주처 검토용 PNG/SVG 원본 소스).

## ERD-01. authoring-domain-erd (저작도구 도메인 ERD)

```json
{
  "type": "ERD",
  "title": "저작도구 도메인 ERD (15 ENTITY)",
  "content": "**범위**: 07-entities.md 의 15개 ENTITY (Aggregate 9개) — 저작도구 책임 도메인 전체. 관제서버 마스터(MNG_*), Quartz(QRTZ_*), 공통 코드(CM_CODE) 는 본 ERD 에서 회색 외부 의존으로만 표시하고 카디널리티는 그리지 않는다.\n\n**Aggregate 분류**\n| Aggregate | 포함 엔티티 | Root |\n|---|---|---|\n| Video | LS_DATA_RAW, LS_DATA_SRC, LS_DATA_LBL, LS_DATA_META, LS_DATA_META_REVIEW | LS_DATA_RAW |\n| Review | LS_RAW_DATA_STATUS, LS_DATA_ISSUE | LS_RAW_DATA_STATUS |\n| Assignment | LS_TASK_ASSIGNMENT | LS_TASK_ASSIGNMENT |\n| LabelMaster | LS_LABEL, LS_LABEL_ATTR | LS_LABEL |\n| Version | LS_LABEL_VERSION | LS_LABEL_VERSION |\n| Augment | LS_DATA_AUG (+ AUG_RVW + AUG_LBL_MAP) | LS_DATA_AUG |\n| Preset | LS_LABEL_PRESET (+ PRESET_CODE) | LS_LABEL_PRESET |\n| PortalUser | LS_PORTAL_USER_VIDEO | LS_PORTAL_USER_VIDEO |\n| Batch | LS_DEIDENT_REPORT (+ BATCH_PROC_LOG) | LS_DEIDENT_REPORT |\n\n**카디널리티 핵심**\n- LS_DATA_RAW 1:N LS_DATA_SRC, LS_DATA_RAW 1:1 LS_RAW_DATA_STATUS, LS_DATA_RAW 1:N LS_TASK_ASSIGNMENT, LS_DATA_RAW 1:N LS_DATA_AUG, LS_DATA_RAW 1:N LS_DATA_META\n- LS_DATA_SRC 1:N LS_DATA_LBL\n- LS_LABEL 1:N LS_DATA_LBL (V32 추가 FK)\n- LS_LABEL 1:N LS_LABEL_ATTR\n- LS_LABEL N:M LS_LABEL_PRESET (via LS_LABEL_PRESET_CODE)\n- LS_DATA_META 1:1 LS_DATA_META_REVIEW\n- LS_PORTAL_USER_VIDEO 1:1 LS_DATA_RAW (자동 라벨링 진입 후 매핑, 그 전에는 RAW_SN NULL)\n\n**렌더링**: `attrs.diagramSource` 에 Mermaid ER 문법으로 저장. Markdown 뷰어/Gitea/GitHub 에서 자동 렌더링 가능. PNG/SVG 추출은 `mmdc` (Mermaid CLI) 사용.",
  "attrs": {
    "scope": "저작도구 책임 LS_* 테이블 (07-entities.md ENT-01~15)",
    "diagramFormat": "mermaid-er",
    "diagramSource": "erDiagram\n    LS_DATA_RAW ||--o{ LS_DATA_SRC : has\n    LS_DATA_RAW ||--|| LS_RAW_DATA_STATUS : tracks\n    LS_DATA_RAW ||--o{ LS_TASK_ASSIGNMENT : assigned\n    LS_DATA_RAW ||--o{ LS_DATA_AUG : augmented\n    LS_DATA_RAW ||--o{ LS_DATA_META : metadata\n    LS_DATA_RAW ||--o{ LS_LABEL_VERSION : committed\n    LS_DATA_RAW ||--o{ LS_DEIDENT_REPORT : deidentified\n    LS_DATA_RAW ||--o| LS_PORTAL_USER_VIDEO : uploaded_from\n\n    LS_DATA_SRC ||--o{ LS_DATA_LBL : labels\n\n    LS_DATA_LBL }o--|| LS_LABEL : refers_to\n    LS_DATA_LBL ||--o{ LS_DATA_LBL_ATTR_VAL : attr_values\n    LS_DATA_LBL ||--o| LS_DATA_LBL_AI_INFO : ai_info\n\n    LS_LABEL ||--o{ LS_LABEL_ATTR : defines\n    LS_LABEL ||--o{ LS_LABEL : parent_child\n    LS_LABEL_PRESET ||--o{ LS_LABEL_PRESET_CODE : contains\n    LS_LABEL ||--o{ LS_LABEL_PRESET_CODE : included_in\n\n    LS_DATA_META ||--o| LS_DATA_META_REVIEW : reviewed\n\n    LS_RAW_DATA_STATUS ||--o{ LS_DATA_ISSUE : issues\n\n    LS_DATA_AUG ||--o| LS_DATA_AUG_RVW : verdict\n    LS_DATA_AUG ||--o{ LS_DATA_AUG_LBL_MAP : label_mapping\n\n    LS_DEIDENT_REPORT ||--o{ LS_BATCH_PROC_LOG : stage_logs\n\n    LS_TASK_ASSIGNMENT ||--o{ LS_TASK_ASSIGN_HISTORY : history\n    LS_TASK_ASSIGNMENT ||--o{ LS_TASK_EVENT_LOG : events\n\n    LS_DATA_RAW {\n        BIGINT RAW_SN PK\n        VARCHAR VMS_CLIP_ID UK\n        VARCHAR VMS_CCTV_ID\n        VARCHAR PRVC_TYPE_CD\n        VARCHAR DE_IDNTF_YN\n        VARCHAR DATA_STTS_CD\n        TIMESTAMP CAPTURED_AT\n    }\n    LS_DATA_SRC {\n        BIGINT SRC_SN PK\n        BIGINT RAW_SN FK\n        INT FRAME_NO\n        VARCHAR IMG_PATH\n        VARCHAR DEIDENT_IMG_PATH\n    }\n    LS_DATA_LBL {\n        BIGINT LBL_SN PK\n        BIGINT SRC_SN FK\n        BIGINT LABEL_ID FK\n        VARCHAR LBL_TYPE_CD\n        VARCHAR LBL_SRC_CD\n        LONGTEXT POINTS_JSON\n        DECIMAL CONFIDENCE\n        VARCHAR TRCK_ID\n    }\n    LS_LABEL {\n        BIGINT LABEL_ID PK\n        VARCHAR LABEL_NAME UK\n        VARCHAR COLOR\n        BIGINT PARENT_LABEL_ID FK\n        VARCHAR ACTIVE_YN\n    }\n    LS_RAW_DATA_STATUS {\n        BIGINT STATUS_SN PK\n        BIGINT RAW_SN FK\n        VARCHAR STATUS\n        BIGINT VERSION\n    }\n    LS_TASK_ASSIGNMENT {\n        BIGINT ASSIGNMENT_ID PK\n        BIGINT USER_NO\n        BIGINT RAW_DATA_ID FK\n        VARCHAR TASK_TYPE_CD\n    }\n    LS_DATA_META {\n        BIGINT META_SN PK\n        BIGINT RAW_SN FK\n        VARCHAR META_KEY\n        VARCHAR META_SRC_CD\n    }\n    LS_DATA_META_REVIEW {\n        BIGINT RVW_SN PK\n        BIGINT META_SN FK\n        VARCHAR META_TYPE_CD\n        VARCHAR RVW_STTS_CD\n    }\n    LS_DATA_AUG {\n        BIGINT AUG_SN PK\n        BIGINT RAW_SN FK\n        VARCHAR AUG_TYPE_CD\n        VARCHAR STATUS\n        VARCHAR REQUEST_ID UK\n    }\n    LS_LABEL_VERSION {\n        BIGINT VERSION_SN PK\n        BIGINT RAW_SN FK\n        VARCHAR COMMIT_HASH\n    }\n    LS_LABEL_PRESET {\n        BIGINT PRESET_ID PK\n        VARCHAR PRESET_NAME\n        VARCHAR EVNT_TYPE_CD UK\n    }\n    LS_PORTAL_USER_VIDEO {\n        BIGINT PORTAL_VIDEO_SN PK\n        BIGINT USER_NO\n        BIGINT RAW_SN FK\n    }\n    LS_DEIDENT_REPORT {\n        BIGINT DEIDENT_SN PK\n        BIGINT RAW_SN FK\n        VARCHAR RESULT_CD\n    }"
  },
  "_handle": "erd-authoring"
}
```

---

## 등록 순서

ENTITY 15건 + DATABASE 1건 등록 후, ARCHITECTURE 등록 전. ERD 는 ENTITY 와 직접 link 가 없으며(가이드 매트릭스 상 명시 link 부재) `REFERS_TO` 로 ARCHITECTURE → ERD 연결만 추가.

권장: ent-* 15건 → db-klid-system → **erd-authoring** → arch-authoring

## 다이어그램 렌더링 절차

```bash
# Mermaid CLI 설치
npm i -g @mermaid-js/mermaid-cli

# attrs.diagramSource 를 .mmd 파일로 저장한 뒤 SVG 추출
mmdc -i authoring-erd.mmd -o authoring-erd.svg -t neutral

# 발주처 제출용 PNG (고해상도)
mmdc -i authoring-erd.mmd -o authoring-erd.png -t neutral -w 2400 -H 1600
```

`mcp__ccarch__ccarch_get_node` 로 본 노드 조회 → `attrs.diagramSource` 추출 → 위 절차로 렌더링 → 산출물(엔티티 관계 모형 기술서) 부록으로 첨부.

## 관계

| 링크 타입 | source | target | 사유 |
|---|---|---|---|
| `REFERS_TO` | `arch-authoring` (ARCHITECTURE) | `erd-authoring` (ERD) | 아키텍처 설계서가 ERD 참조 |
| `REFERS_TO` | `erd-authoring` (ERD) | `db-klid-system` (DATABASE) | ERD 가 물리 DB 컨텍스트 참조 |

> ccarch v1 가이드 상 ENTITY ↔ ERD 명시 link 는 없다. ERD 의 attrs.scope 에 "ENT-01~15 (07-entities.md)" 라고 자연어로 명시하는 것으로 추적성 유지.
