# ERD 노드 정의

> ccarch type: `ERD` / 필수: `title`, `content` / 옵션: `diagramSource`, `scope`, `diagramFormat`
> 본 사업 저작도구의 15 ENTITY 관계를 종합한 ER 다이어그램. 06-entities.md의 각 ENTITY "관계" 섹션을 단일 ER 모델로 통합.

## 추출 산출물

본 노드는 ccarch `ENTITY_RELATIONSHIP_MODEL` 산출물(`엔티티 관계 모형 기술서`)의 source로 사용된다. Phase 28 RFC 결정: sourceNodeType = `ERD` (ENTITY 아님).

## ERD-01. 저작도구 도메인 ERD

```json
{
  "type": "ERD",
  "title": "저작도구 도메인 ERD (15 ENTITY)",
  "content": "**범위**: 저작도구(AT) 서브시스템의 15 ENTITY 전체 관계 모델.\n\n**핵심 흐름**\n1. **영상 처리**: `LS_DATA_RAW`(영상) → `LS_DATA_SRC`(프레임) → `LS_DATA_LBL`(라벨)\n2. **라벨 마스터**: `LS_DATA_LBL` → `LS_LABEL`(마스터) → `LS_LABEL_ATTR`(속성)\n3. **검수 워크플로우**: `LS_DATA_RAW` → `LS_RAW_DATA_STATUS`(검수 상태) → `LS_TASK_ASSIGNMENT`(배정) → `LS_DATA_ISSUE`(이슈)\n4. **버전 관리**: `LS_DATA_RAW` → `LS_LABEL_VERSION`(Gitea 커밋)\n5. **증강**: `LS_DATA_RAW` → `LS_DATA_AUG`(증강 작업)\n6. **부가**: `LS_DATA_META`(시계열 메타), `LS_LABEL_PRESET`(프리셋), `LS_PORTAL_USER_VIDEO`(외부 영상), `LS_DEIDENT_REPORT`(비식별), `LS_BATCH_PROC_LOG`(배치 로그)\n\n**카디널리티 요약**\n- `LS_DATA_RAW` 1:N `LS_DATA_SRC` (영상별 프레임 다수)\n- `LS_DATA_SRC` 1:N `LS_DATA_LBL` (프레임별 라벨 다수)\n- `LS_DATA_LBL` N:1 `LS_LABEL` (라벨은 마스터 1건 참조)\n- `LS_LABEL` 1:N `LS_LABEL_ATTR` (마스터별 속성 다수)\n- `LS_DATA_RAW` 1:1 `LS_RAW_DATA_STATUS` (영상별 검수 상태 1건, 낙관적 잠금)\n- `LS_DATA_RAW` 1:N `LS_TASK_ASSIGNMENT` (영상별 배정 다수 — UK: raw+user+type)\n- `LS_DATA_RAW` 1:N `LS_DATA_AUG` (영상별 증강 작업 다수)\n- `LS_DATA_RAW` 1:N `LS_DATA_ISSUE` (영상별 검수 이슈 다수)\n- `LS_DATA_RAW` 1:N `LS_LABEL_VERSION` (영상별 Gitea 커밋 다수)\n- `LS_DATA_RAW` 1:N `LS_DATA_META` (영상별 시계열 메타)\n\n**Aggregate 경계**\n- `Video` aggregate: LS_DATA_RAW + LS_DATA_SRC + LS_DATA_LBL\n- `Assignment` aggregate: LS_TASK_ASSIGNMENT + LS_TASK_ASSIGN_HISTORY\n- `LabelMaster` aggregate: LS_LABEL + LS_LABEL_ATTR\n- `LabelPreset` aggregate: LS_LABEL_PRESET (독립)\n- `DeidentReport` aggregate: LS_DEIDENT_REPORT (독립)\n\n**DB**: klid_system 공유 DB (관제서버 팀과 협의). 신규 테이블 0, `LS_DATA_LBL` 3컬럼 추가만.\n\n**상세**: 06-entities.md 각 ENTITY 노드 + 10-traceability.md 참조.",
  "attrs": {
    "scope": "AT (저작도구 서브시스템)",
    "diagramFormat": "mermaid",
    "diagramSource": "erDiagram\n  LS_DATA_RAW ||--o{ LS_DATA_SRC : \"1:N 프레임\"\n  LS_DATA_RAW ||--|| LS_RAW_DATA_STATUS : \"1:1 검수상태\"\n  LS_DATA_RAW ||--o{ LS_TASK_ASSIGNMENT : \"1:N 배정\"\n  LS_DATA_RAW ||--o{ LS_DATA_AUG : \"1:N 증강\"\n  LS_DATA_RAW ||--o{ LS_DATA_ISSUE : \"1:N 검수이슈\"\n  LS_DATA_RAW ||--o{ LS_LABEL_VERSION : \"1:N Gitea커밋\"\n  LS_DATA_RAW ||--o{ LS_DATA_META : \"1:N 시계열메타\"\n  LS_DATA_SRC ||--o{ LS_DATA_LBL : \"1:N 라벨\"\n  LS_DATA_LBL }o--|| LS_LABEL : \"N:1 마스터참조\"\n  LS_LABEL ||--o{ LS_LABEL_ATTR : \"1:N 속성정의\"\n  LS_PORTAL_USER_VIDEO ||--o{ LS_DATA_RAW : \"1:N 포털영상\"\n  LS_DATA_RAW ||--o| LS_DEIDENT_REPORT : \"1:0..1 비식별이력\"\n  LS_BATCH_PROC_LOG }o--o{ LS_DATA_RAW : \"N:N 배치추적\"\n  LS_LABEL_PRESET }o--|| LS_LABEL : \"N:1 이벤트별프리셋\"\n  LS_DATA_RAW {\n    bigint RAW_SN PK\n    varchar VMS_CCTV_ID\n    varchar EVENT_TYPE_CD\n    varchar PRVC_TYPE_CD\n    varchar STATUS\n    timestamp CAPTURED_AT\n    char DE_IDNTF_YN\n  }\n  LS_DATA_SRC {\n    bigint SRC_SN PK\n    bigint RAW_SN FK\n    int FRAME_NO\n    varchar IMG_PATH\n    varchar DEIDENT_IMG_PATH\n  }\n  LS_DATA_LBL {\n    bigint LBL_SN PK\n    bigint SRC_SN FK\n    bigint LABEL_ID FK\n    varchar LBL_TYPE_CD\n    varchar LBL_SRC_CD\n    json COORDINATES\n    float CONFIDENCE\n    varchar TRACK_ID\n  }\n  LS_LABEL {\n    bigint LABEL_ID PK\n    varchar LABEL_NAME\n    varchar COLOR\n    varchar CATEGORY\n    bigint PARENT_LABEL_ID\n    char ACTIVE_YN\n  }\n  LS_LABEL_ATTR {\n    bigint ATTR_ID PK\n    bigint LABEL_ID FK\n    varchar ATTR_NAME\n    varchar ATTR_TYPE\n  }\n  LS_RAW_DATA_STATUS {\n    bigint RAW_SN PK_FK\n    varchar REVIEW_STATUS_CD\n    int VERSION\n  }\n  LS_TASK_ASSIGNMENT {\n    bigint ASGN_SN PK\n    bigint RAW_DATA_ID FK\n    bigint USER_NO\n    varchar TASK_TYPE_CD\n    varchar STATUS\n  }"
  },
  "_handle": "erd-authoring-domain"
}
```

## 활용

- ccarch FE: `/api/wiki/diagrams` 또는 노드 상세에서 PlantUML/Mermaid 렌더링
- ccarch BE: `ENTITY_RELATIONSHIP_MODEL` 산출물 추출 시 `ErmRenderer`가 attrs.diagramSource 본문 포함

## 후속

- 11 종 추가 ENTITY는 diagramSource에 상세 컬럼 미반영(공간 한도). 06-entities.md 노드 본문에서 확인
- 다이어그램 동기화: ENTITY 노드 변경 시 본 ERD 노드의 diagramSource도 갱신 필요 (영향도 추적은 ccarch revision으로 모니터링)
