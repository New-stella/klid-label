# DATABASE 노드 정의 — `klid_system` 공유 DB (본 도구 사용 범위)

> ccarch v1 가이드의 `DATABASE` 노드 1건으로 본 도구가 **직접 사용하는 테이블/인덱스/테이블스페이스만** 등록한다. 자동 ID 코드 **DB**. 자식 배열 `tablespaces` / `tables` / `indexes` 는 각각 **TS-001 / TB-001 / ID-001** 형식으로 자동 부여된다 (id 미지정 시).
>
> **스코프 정책** (워크스페이스 답변 기준)
> - 본 도구가 INSERT/UPDATE 하는 모든 `LS_*` 테이블 — 포함
> - 본 도구가 SELECT/조회만 하는 관제 마스터(`MNG_*`) — 포함하되 `comment` 에 "읽기 전용 의존" 명시
> - Spring Boot Quartz 가 사용하는 `QRTZ_*` 11종 — 본 도구가 1건/분 처리에 사용하므로 포함
> - 공통 코드 `CM_CODE` — 포함
> - 관제서버 전용 테이블(본 도구가 호출/조회하지 않는 것) — **제외**
>
> **자동 추출 매핑** (가이드 `usedInArtifacts` 에 `DATABASE` 자체의 산출물은 없음). 본 노드는 `ENTITY` → `DATABASE_DESIGN`/`DATABASE_TABLE` 산출물 추출 시 **물리 DB 컨텍스트 (databaseId)** 로 참조된다 — 07-entities.md 의 각 ENTITY `attrs.databaseId` 보강 시 본 노드의 `_handle` 또는 등록 후 `node_id` 를 사용.

## DB-01. klid_system (MariaDB 10.11.13 LTS)

```json
{
  "type": "DATABASE",
  "title": "klid_system",
  "content": "**저작도구가 사용하는 공유 DB 인스턴스** — MariaDB 10.11.13 LTS / utf8mb4·utf8mb4_unicode_ci / InnoDB / MaxScale 24.02.5 (Master-Slave R/W Splitting).\n\n**공유 정책**\n- 관제서버팀과 동일 인스턴스 공유 (`192.168.102.101:13307`)\n- 저작도구 전용 독립 DB 없음\n- 신규 테이블 0건 원칙 — 기존 테이블 재사용. 예외: `LS_DATA_LBL` 에 3컬럼 추가(LBL_SRC_CD/CONFIDENCE/LABEL_ID — V20/V32)\n- Flyway 마이그레이션은 `klid_system` 공유 테이블 변경 시 관제서버팀 선승인 필수. JPA `ddl-auto=validate` 고정\n\n**본 노드 범위**\n- 저작도구가 직접 사용하는 테이블만 자식 배열에 명시 (LS_* + 읽기 전용 의존 MNG_* + Quartz QRTZ_* + 코드 CM_CODE). 관제서버 전용 영역은 등록하지 않음.\n\n**Aggregate Root 와의 매핑**\n- ENT-01~15 (07-entities.md) 의 각 ENTITY 는 본 DATABASE 의 한 `tables[].name` 과 1:1 대응됨 (ENT-12/15 처럼 다중 테이블 묶음 ENTITY 의 경우 Aggregate Root 테이블이 대표).\n\n**환경변수**: `CONTROL_DB_HOST=192.168.102.101`, `CONTROL_DB_PORT=13307`, `CONTROL_DB_NAME=klid_system`, `CONTROL_DB_USERNAME`, `CONTROL_DB_PASSWORD`",
  "attrs": {
    "vendor": "MariaDB",
    "version": "10.11.13 LTS",
    "charset": "utf8mb4",
    "collation": "utf8mb4_unicode_ci",
    "engine": "InnoDB",
    "tablespaces": [
      {"name": "innodb_default", "comment": "단일 InnoDB 기본 테이블스페이스 (별도 분리 운영 없음)"}
    ],
    "tables": [
      {"name": "LS_DATA_RAW",            "comment": "[저작도구] 영상 원본 — ENT-01 Aggregate Root"},
      {"name": "LS_DATA_RAW_HSTRY",      "comment": "[저작도구] 영상 상태 전이 이력"},
      {"name": "LS_DATA_SRC",            "comment": "[저작도구] 프레임 — ENT-02"},
      {"name": "LS_DATA_SRC_HSTRY",      "comment": "[저작도구] 프레임 변경 이력"},
      {"name": "LS_DATA_LBL",            "comment": "[저작도구] 라벨 — ENT-03 (본 사업 V20/V32 에서 3컬럼 추가)"},
      {"name": "LS_DATA_LBL_AI_INFO",    "comment": "[저작도구] 라벨 AI 메타 (YOLO/SAM2 결과)"},
      {"name": "LS_DATA_LBL_ATTR_VAL",   "comment": "[저작도구] 라벨 속성 값"},
      {"name": "LS_LABEL",               "comment": "[저작도구] 라벨 마스터 — ENT-04"},
      {"name": "LS_LABEL_ATTR",          "comment": "[저작도구] 라벨 속성 정의 — ENT-05"},
      {"name": "LS_DATA_META",           "comment": "[저작도구] 시계열/외부 메타 — ENT-06"},
      {"name": "LS_DATA_META_HSTRY",     "comment": "[저작도구] 메타 변경 이력"},
      {"name": "LS_DATA_META_REVIEW",    "comment": "[저작도구] 메타 검토 상태 — ENT-07"},
      {"name": "LS_RAW_DATA_STATUS",     "comment": "[저작도구] 검수 상태 + @Version — ENT-08"},
      {"name": "LS_TASK_ASSIGNMENT",     "comment": "[저작도구] 작업 배정 — ENT-09"},
      {"name": "LS_TASK_ASSIGN_HISTORY", "comment": "[저작도구] 배정 이력"},
      {"name": "LS_TASK_EVENT_LOG",      "comment": "[저작도구] 작업 이벤트 로그"},
      {"name": "LS_AUTH_WORK_LOCK",      "comment": "[저작도구] 동시 작업 잠금 (배정/검수 충돌 방지)"},
      {"name": "LS_DATA_ISSUE",          "comment": "[저작도구] 검수 이슈 — ENT-10"},
      {"name": "LS_LABEL_VERSION",       "comment": "[저작도구] Gitea 커밋 메타 — ENT-11"},
      {"name": "LS_DATA_AUG",            "comment": "[저작도구] 증강 묶음 — ENT-12 Aggregate Root"},
      {"name": "LS_DATA_AUG_RVW",        "comment": "[저작도구] 증강 결과 검수"},
      {"name": "LS_DATA_AUG_LBL_MAP",    "comment": "[저작도구] 원본↔증강 라벨 매핑"},
      {"name": "LS_LABEL_PRESET",        "comment": "[저작도구] 라벨 프리셋 — ENT-13 Aggregate Root"},
      {"name": "LS_LABEL_PRESET_CODE",   "comment": "[저작도구] 프리셋 라벨 매핑"},
      {"name": "LS_PORTAL_USER_VIDEO",   "comment": "[저작도구] 포털 사용자 영상 — ENT-14"},
      {"name": "LS_DEIDENT_REPORT",      "comment": "[저작도구] 비식별 처리 이력 — ENT-15 Aggregate Root"},
      {"name": "LS_DEIDENT_PROC_LOG",    "comment": "[저작도구] 비식별 진행 로그"},
      {"name": "LS_BATCH_PROC_LOG",      "comment": "[저작도구] 배치 단계별 로그"},
      {"name": "LS_DATA_SET",            "comment": "[저작도구] 학습데이터셋 (Export 결과)"},
      {"name": "LS_GITEA_FALLBACK_QUEUE", "comment": "[저작도구] Gitea 동기 호출 실패 시 fallback 큐"},
      {"name": "LS_WEBHOOK_IDEMPOTENCY", "comment": "[저작도구] 외부 webhook idempotency 보장"},
      {"name": "LS_SYSTEM_CONFIG",       "comment": "[저작도구] 시스템 설정 (YOLO threshold 등)"},
      {"name": "LS_DEADLINE",            "comment": "[저작도구] 마감일 관리"},
      {"name": "LS_RAW_DATA_ENROLLMENT", "comment": "[저작도구] 영상 등록 큐"},
      {"name": "LS_META",                "comment": "[저작도구] 일반 메타 (영상/프레임 외)"},
      {"name": "MNG_ACCT_USER",          "comment": "[읽기 전용 의존] 사용자 (관제서버 마스터)"},
      {"name": "MNG_ACCT_AUTHRT",        "comment": "[읽기 전용 의존] 권한 코드"},
      {"name": "MNG_ACCT_USER_AUTHRT",   "comment": "[읽기 전용 의존] 사용자-권한 매핑"},
      {"name": "MNG_CLIP_MASTER",        "comment": "[읽기 전용 의존] 관제 클립 마스터"},
      {"name": "MNG_RESOURCE_CCTV",      "comment": "[읽기 전용 의존] CCTV 자원"},
      {"name": "MNG_EX_EVNT_TYPE",       "comment": "[읽기 전용 의존] 외부 이벤트 타입"},
      {"name": "MNG_EX_EVNT_TYPE_MAP",   "comment": "[읽기 전용 의존] 외부 이벤트 타입 매핑"},
      {"name": "MNG_EX_LOCAL_GOV",       "comment": "[읽기 전용 의존] 지자체"},
      {"name": "MNG_CLIP_SCHEDULE_QUE",  "comment": "[읽기 전용 의존] 클립 처리 스케줄 큐"},
      {"name": "CM_CODE",                "comment": "[읽기 전용 의존] 공통 코드"},
      {"name": "QRTZ_JOB_DETAILS",       "comment": "[Quartz] 배치 잡 정의"},
      {"name": "QRTZ_TRIGGERS",          "comment": "[Quartz] 트리거"},
      {"name": "QRTZ_CRON_TRIGGERS",     "comment": "[Quartz] 크론 트리거"},
      {"name": "QRTZ_SIMPLE_TRIGGERS",   "comment": "[Quartz] 단순 트리거"},
      {"name": "QRTZ_BLOB_TRIGGERS",     "comment": "[Quartz] BLOB 트리거"},
      {"name": "QRTZ_SIMPROP_TRIGGERS",  "comment": "[Quartz] Property 트리거"},
      {"name": "QRTZ_FIRED_TRIGGERS",    "comment": "[Quartz] 실행 중 트리거"},
      {"name": "QRTZ_CALENDARS",         "comment": "[Quartz] 캘린더"},
      {"name": "QRTZ_PAUSED_TRIGGER_GRPS", "comment": "[Quartz] 일시중지된 트리거 그룹"},
      {"name": "QRTZ_LOCKS",             "comment": "[Quartz] 분산 잠금"},
      {"name": "QRTZ_SCHEDULER_STATE",   "comment": "[Quartz] 스케줄러 상태"}
    ],
    "indexes": [
      {"name": "UK_LS_DATA_RAW_VMS_CLIP",        "tableName": "LS_DATA_RAW",         "columns": ["VMS_CLIP_ID"], "comment": "관제 클립 중복 적재 방지 UK"},
      {"name": "IX_LS_DATA_RAW_CCTV",            "tableName": "LS_DATA_RAW",         "columns": ["VMS_CCTV_ID"], "comment": "CCTV 별 조회"},
      {"name": "IX_LS_DATA_RAW_STTS",            "tableName": "LS_DATA_RAW",         "columns": ["DATA_STTS_CD"], "comment": "상태 필터 (V38 추가)"},
      {"name": "IX_LS_DATA_LBL_SRC",             "tableName": "LS_DATA_LBL",         "columns": ["SRC_SN"],      "comment": "프레임별 라벨 조회"},
      {"name": "IX_LS_DATA_LBL_TRCK_ID",         "tableName": "LS_DATA_LBL",         "columns": ["TRCK_ID"],     "comment": "SAM2 트랙 검색"},
      {"name": "IX_LS_DATA_LBL_LABEL_ID",        "tableName": "LS_DATA_LBL",         "columns": ["LABEL_ID"],    "comment": "라벨 마스터별 조회 (V32 추가)"},
      {"name": "UK_LS_TASK_ASSIGNMENT",          "tableName": "LS_TASK_ASSIGNMENT",  "columns": ["RAW_DATA_ID","USER_NO","TASK_TYPE_CD"], "comment": "중복 배정 방지 UK"},
      {"name": "IX_LS_TASK_ASSIGNMENT_USER",     "tableName": "LS_TASK_ASSIGNMENT",  "columns": ["USER_NO","TASK_TYPE_CD"], "comment": "내 작업 목록"},
      {"name": "IX_LS_TASK_ASSIGNMENT_RAW",      "tableName": "LS_TASK_ASSIGNMENT",  "columns": ["RAW_DATA_ID"], "comment": "영상별 배정"},
      {"name": "UK_LS_DATA_META",                "tableName": "LS_DATA_META",        "columns": ["RAW_SN","META_KEY"], "comment": "영상-키 UK"},
      {"name": "IX_LS_LABEL_VERSION_RAW",        "tableName": "LS_LABEL_VERSION",    "columns": ["RAW_SN"],      "comment": "영상별 커밋 이력"},
      {"name": "IX_LS_DATA_AUG_RAW",             "tableName": "LS_DATA_AUG",         "columns": ["RAW_SN"],      "comment": "영상별 증강"},
      {"name": "UK_LS_DATA_AUG_REQUEST",         "tableName": "LS_DATA_AUG",         "columns": ["REQUEST_ID"],  "comment": "외부 idempotency UK"},
      {"name": "IX_LS_PORTAL_USER_VIDEO_USER",   "tableName": "LS_PORTAL_USER_VIDEO","columns": ["USER_NO"],     "comment": "본인 영상만 조회 (수평 권한)"},
      {"name": "IX_LS_GITEA_FALLBACK_STTS",      "tableName": "LS_GITEA_FALLBACK_QUEUE", "columns": ["STATUS","REG_DT"], "comment": "fallback 재시도 큐 스캔"}
    ]
  },
  "_handle": "db-klid-system"
}
```

---

## 등록 순서

ENTITY 15건 등록 후, ARCHITECTURE 등록 전. DATABASE 노드는 ENTITY 산출물 추출의 물리 DB 컨텍스트로 사용된다.

권장: ent-* 15건 → **db-klid-system** → erd-authoring (14-erd.md) → arch-authoring (10-architecture.md)

## 산출물 자동 추출 관계

- `DATABASE_DESIGN` 은 sourceNodeType=`ENTITY` 라 본 DATABASE 노드가 직접 추출 대상은 아니다. 다만 각 ENTITY 가 어느 물리 DB 에 속하는지 명확히 하기 위해 attrs.databaseId 를 본 노드의 `_handle` 또는 등록 후 `node_id` 로 연결 가능 — **현재는 단일 DB 인스턴스라 ENTITY 별 databaseId 보강은 옵션** (12-upload-plan.md 의 Phase B 보강 단계에서 결정).
- `DATABASE_TABLE` (sourceNodeType=`ENTITY`) — Flyway 스크립트 파일별 매핑이 필요한 산출물. **현재 미보강**. 본 도구 정책상 신규 스크립트가 거의 없으므로 본 노드 등록 후 운영 환경에서 별도 보강.

## 관계

| 링크 타입 | source | target | 사유 |
|---|---|---|---|
| `REFERS_TO` | `arch-authoring` (ARCHITECTURE) | `db-klid-system` (DATABASE) | 아키텍처 설계서에서 DB 의존성 명시 |
| `REFERS_TO` | `erd-authoring` (ERD) | `db-klid-system` (DATABASE) | ERD 가 물리 DB 컨텍스트 참조 |

> ccarch 가이드의 관계 타입 매트릭스 상 `ENTITY` → `DATABASE` 의 명시 link 는 없다. 따라서 ENTITY-DATABASE 매핑은 attrs.tableId(ENTITY 측) + 본 DATABASE 노드의 tables[].name 동일값으로 자연 매칭한다 — 명시 link 가 필요하면 `REFERS_TO` 사용 가능하지만 노드 수가 늘면 잡음이 커지므로 권장하지 않음.
