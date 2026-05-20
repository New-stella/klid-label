# DATABASE 노드 정의

> ccarch type: `DATABASE` / 필수: `title`, `content` / 옵션: `tablespaces`, `tables`, `indexes`, `engine`, `vendor`, `charset`, `collation`, `version`
> 본 사업 저작도구의 klid_system 공유 DB. 관제서버팀이 운영하며 저작도구는 사전 협의된 DDL만 적용. **저작도구 도메인 LS_* 26개 테이블 전수**.

## 추출 산출물 (3종)

본 노드는 ccarch 다음 산출물의 source로 사용된다 (Phase 28 RFC):
1. `DATABASE_DESIGN` (데이터베이스 설계서)
2. `DATABASE_TABLE` (DB 생성 스크립트)
3. `DATA_MIGRATION_DESIGN` (데이터 전환 및 초기데이터 설계서)

## DB-01. klid_system (MariaDB 10.11.13 LTS)

```json
{
  "type": "DATABASE",
  "title": "klid_system (저작도구 공유 DB)",
  "content": "## 1. 개요\n\n- **호스트**: 192.168.102.101:13307 (MaxScale 24.02.5 Master-Slave R/W splitting)\n- **DBMS**: MariaDB 10.11.13 LTS / InnoDB\n- **문자셋**: utf8mb4 / utf8mb4_unicode_ci\n- **운영 주체**: 관제서버팀 (저작도구는 제한된 DDL만 사전 협의)\n- **스케줄러**: Quartz `QRTZ_*` 11개 테이블 공유 (기존 운영 호환)\n\n## 2. 도메인별 테이블 분류 (LS_* 26개)\n\n### 2-1. 영상/프레임 (4종)\n- `LS_DATA_RAW` — 영상 본체 (Aggregate Root)\n- `LS_DATA_RAW_HSTRY` — 영상 변경 이력\n- `LS_DATA_SRC` — 프레임\n- `LS_DATA_SRC_HSTRY` — 프레임 변경 이력\n\n### 2-2. 라벨 (7종)\n- `LS_DATA_LBL` — 라벨 (BBox/Polygon/Mask/Track)\n- `LS_DATA_LBL_AI_INFO` — AI 라벨 출처/신뢰도 (Phase 23)\n- `LS_DATA_LBL_ATTR_VAL` — 라벨 객체별 속성값\n- `LS_LABEL` — 라벨 마스터 (CVAT-Like 라벨 풀)\n- `LS_LABEL_ATTR` — 라벨 속성 정의 (SELECT/CHECKBOX/RADIO/NUMBER/TEXT)\n- `LS_LABEL_PRESET` — 라벨 프리셋 (이벤트별)\n- `LS_LABEL_PRESET_CODE` — 프리셋 라벨 코드 매핑\n\n### 2-3. 메타 (3종)\n- `LS_DATA_META` — 영상/프레임 시계열 메타 (외부 VLM 시계열 수신)\n- `LS_DATA_META_HSTRY` — 메타 변경 이력\n- `LS_DATA_META_REVIEW` — 외부 메타 검토 상태\n\n### 2-4. 검수 (3종)\n- `LS_RAW_DATA_STATUS` — 영상 검수 상태 (낙관적 잠금, V36 신규명)\n- `LS_PJT_DATA_STTS` — 기존 프로젝트 데이터 상태 (V1, 호환 잔존)\n- `LS_DATA_ISSUE` — 검수 반려 이슈 (계층형)\n\n### 2-5. 배정/워크플로우 (7종)\n- `LS_TASK_ASSIGNMENT` — 작업 배정 (V36 신규명)\n- `LS_TASK_ASSIGN_HISTORY` — 재배정 이력 (V36 신규명)\n- `LS_PJT_USER_AUTHRT` — 기존 배정 (V1, 호환 잔존)\n- `LS_PJT_USER_AUTHRT_HSTRY` — 기존 배정 이력 (V1, 호환 잔존)\n- `LS_TASK_EVENT_LOG` — 작업 이벤트 누적 로그 (V36 신규명)\n- `LS_PJT_TASK_EVENT_LOG` — 기존 이벤트 로그 (V14, 호환 잔존)\n- `LS_AUTH_WORK_LOCK` — 작업 잠금 (동시성 보호)\n\n### 2-6. 증강 (3종)\n- `LS_DATA_AUG` — 증강 작업 (WINTER/NIGHT/RAIN/RESOLUTION)\n- `LS_DATA_AUG_RVW` — 증강 검수 결과\n- `LS_DATA_AUG_LBL_MAP` — 증강-라벨 좌표 매핑\n\n### 2-7. 버전 관리 (Gitea, 2종)\n- `LS_LABEL_VERSION` — Gitea 커밋 추적\n- `LS_GITEA_FALLBACK_QUEUE` — Gitea fallback 큐 (CircuitBreaker open 시)\n\n### 2-8. 비식별 (2종)\n- `LS_DEIDENT_REPORT` — 비식별 처리 리포트\n- `LS_DEIDENT_PROC_LOG` — 비식별 API 호출 이력\n\n### 2-9. 외부 채널 (2종)\n- `LS_PORTAL_USER_VIDEO` — 포털 사용자 업로드 영상\n- `LS_WEBHOOK_IDEMPOTENCY` — 외부 시스템 위탁 멱등성 원장\n\n### 2-10. 데이터셋 (1종)\n- `LS_DATA_SET` — 학습데이터셋 내보내기 (YOLO/COCO)\n\n### 2-11. 시스템 (2종)\n- `LS_SYSTEM_CONFIG` — 시스템 설정 (FFMPEG_THREADS 등)\n- `LS_BATCH_PROC_LOG` — 배치 단계별 처리 로그\n\n## 3. 외부 마스터 테이블 (읽기 전용, MNG_*)\n\n관제서버팀이 운영. 저작도구는 SELECT만:\n- `MNG_ACCT_USER` — 계정\n- `MNG_ACCT_AUTHRT` — 권한 코드\n- `MNG_ACCT_USER_AUTHRT` — 사용자-권한 매핑\n- `MNG_CLIP_MASTER` — 클립 마스터\n- `MNG_RESOURCE_CCTV` — CCTV 자원 (WGS84 위경도)\n- `MNG_EX_EVNT_TYPE` — 이벤트 타입\n- `MNG_EX_EVNT_TYPE_MAP` — 이벤트 매핑 (계층)\n- `MNG_EX_LOCAL_GOV` — 지자체 코드\n- `MNG_CLIP_SCHEDULE_QUE` — 배치 큐 (JOB_TYPE=LABELING_BATCH)\n\n## 4. Quartz 스케줄러 (11종, 공유)\n\nQRTZ_JOB_DETAILS, QRTZ_TRIGGERS, QRTZ_SIMPLE_TRIGGERS, QRTZ_CRON_TRIGGERS, QRTZ_SIMPROP_TRIGGERS, QRTZ_BLOB_TRIGGERS, QRTZ_CALENDARS, QRTZ_PAUSED_TRIGGER_GRPS, QRTZ_FIRED_TRIGGERS, QRTZ_SCHEDULER_STATE, QRTZ_LOCKS.\n\n## 5. 데이터 전환 정책 (DATA_MIGRATION_DESIGN)\n\n### 5-1. 신규 테이블 0 원칙\n관제서버팀과 협의 — 신규 테이블 13종(LS_LABEL/LS_LABEL_ATTR/LS_LABEL_PRESET/LS_DATA_LBL_AI_INFO/LS_LABEL_VERSION/LS_DATA_AUG_*/LS_DATA_META_REVIEW/LS_DEIDENT_*/LS_BATCH_PROC_LOG/LS_PJT_TASK_EVENT_LOG/LS_AUTH_WORK_LOCK/LS_WEBHOOK_IDEMPOTENCY/LS_GITEA_FALLBACK_QUEUE)는 사전 협의 후 Flyway V11~V41 단계적 적용.\n\n### 5-2. 기존 테이블 ALTER\n- `LS_DATA_LBL`: TRCK_ID 컬럼 추가 (V18) — 추적 ID 보존\n- `LS_DATA_LBL`: LBL_SRC_CD 컬럼 추가 (V20) — MANUAL/AUTO_YOLO/AUTO_SAM2/INTERPOLATED\n- `LS_PJT_DATA_STTS`: VERSION 컬럼 추가 (V5) — @Version 낙관적 잠금\n- `LS_DATA_AUG`: AUG_PROC_STTS_CD, LBL_INTGRT_PCT, REJECT_REASON, DECISION_* (V7)\n- `LS_DATA_SET`: EXPORT_STTS_CD, NAS_PATH, ERROR_MESSAGE, EXPORTED_AT (V8)\n- `LS_DEIDENT_REPORT`: PROC_STTS_CD nullable (V28), 슬림화 (V30)\n\n### 5-3. 데이터 이관 (Phase 11)\nV36에서 신규 LS_RAW_DATA_*/LS_TASK_*/LS_META/LS_DEADLINE 생성 → V37에서 기존 LS_PJT_* 데이터 복사. V34/V35에서 LS_PJT 폐기. BE Entity 매핑 전환은 Phase 11에서.\n\n### 5-4. 초기 데이터 시드\n- V3: cm_code seed data_stts (DATA_STTS_CD 코드)\n- V17: YOLO threshold config (LS_SYSTEM_CONFIG)\n- V11: LS_SYSTEM_CONFIG 4종 (FFMPEG_THREADS, FFMPEG_OUTPUT_FPS, BATCH_INTERVAL_SEC, BATCH_CONCURRENCY)\n- V19: YOLO conf_threshold default 갱신\n\n### 5-5. 비상 계획\n- 마이그레이션 실패: Flyway baseline-on-migrate=false (운영) + 사전 pg_dump\n- 운영 데이터 손실: 매 마이그레이션 직전 백업 (klid-label backups/)\n- ALTER 실패: IF NOT EXISTS 모든 컬럼 (V7/V8/V9 패턴) — 부분 적용 안전\n\n## 6. 핵심 인덱스 정책\n\n- 배치 큐: `IX_MNG_CLIP_SCHEDULE_QUE_STATUS (STATUS, JOB_TYPE)` — PENDING 잡 빠른 조회\n- 영상 상태: `IX_LS_DATA_RAW_STTS (DATA_STTS_CD)` — 파이프라인 단계별 필터\n- 프레임-영상: `UK_LS_DATA_SRC_RAW_FRAME (RAW_SN, FRAME_NO)` — 중복 방지\n- 라벨-프레임: `IX_LS_DATA_LBL_SRC (SRC_SN)` — 프레임별 라벨 조회\n- 배정 유일성: `UK_LS_TASK_ASSIGNMENT (RAW_DATA_ID, USER_NO, TASK_TYPE_CD)`\n- AI 라벨: `IDX_LS_DATA_LBL_AI_INFO_SRC_CD (LBL_SRC_CD)` — MANUAL/AUTO_* 분류\n- Gitea 커밋: `UK_LS_LABEL_VERSION_HASH (GITEA_CMT_HASH)` — 중복 커밋 방지\n- 웹훅 멱등: `LS_WEBHOOK_IDEMPOTENCY.PK (IDEMPOTENCY_KEY)` — 외부 위탁 멱등성\n- Gitea fallback: `idx_status_next_retry (STATUS, NEXT_RETRY_AT)` — Quartz Retry Job 조회\n\n## 7. 외래키 정책\n\n외래키는 운영 정책상 FK CONSTRAINT 보다는 **애플리케이션 레벨 무결성 검증** 우선 (운영 데이터 일관성 + DBA 정책). 단 다음은 명시적 FK:\n- `LS_LABEL_PRESET_CODE.PRESET_ID → LS_LABEL_PRESET(PRESET_ID) ON DELETE CASCADE`\n- `LS_LABEL_ATTR.LABEL_ID → LS_LABEL(LABEL_ID)`\n- `LS_DATA_LBL_ATTR_VAL.LBL_SN → LS_DATA_LBL(LBL_SN)`\n- `LS_DATA_LBL_ATTR_VAL.ATTR_ID → LS_LABEL_ATTR(ATTR_ID)`\n\n## 8. 상세 컬럼 정의\n\n각 테이블의 모든 컬럼은 본 노드의 `attrs.tables[].columns` 자식 배열에 명시. attrs는 JSON 직렬화 64KB 한도 (Phase 32 후속 B-2 보강으로 8KB → 64KB 증가).\n\n상세 DDL은 `backend/src/main/resources/db/migration/V1~V49.sql` 원본 참조.",
  "attrs": {
    "engine": "MariaDB 10.11.13 (InnoDB)",
    "vendor": "MariaDB",
    "version": "10.11.13 LTS",
    "charset": "utf8mb4",
    "collation": "utf8mb4_unicode_ci",
    "tablespaces": [
      {"name": "klid_main", "purpose": "기본 LS_*/MNG_*/QRTZ_* 데이터 테이블 (InnoDB 기본 테이블스페이스)"}
    ],
    "tables": [
      {"name": "LS_DATA_RAW", "purpose": "영상 본체 (Aggregate Root)", "columns": ["RAW_SN BIGINT PK AUTO_INCREMENT", "VMS_CLIP_ID VARCHAR(128) NOT NULL UK", "VMS_CCTV_ID VARCHAR(64) NOT NULL", "EVNT_TYPE_CD VARCHAR(32)", "LCLGV_CD VARCHAR(32)", "PRVC_TYPE_CD VARCHAR(16) NOT NULL", "PRVC_YN VARCHAR(1) NOT NULL DEFAULT 'N'", "DE_IDNTF_YN VARCHAR(1) NOT NULL DEFAULT 'N'", "FILE_PATH VARCHAR(500) NOT NULL", "CAPTURED_AT TIMESTAMP", "DURATION_SEC INT", "DATA_STTS_CD VARCHAR(32) NOT NULL DEFAULT 'PENDING'", "REG_DT TIMESTAMP NOT NULL", "UPD_DT TIMESTAMP"]},
      {"name": "LS_DATA_RAW_HSTRY", "purpose": "영상 변경 이력", "columns": ["HSTRY_SEQ BIGINT PK AUTO_INCREMENT", "RAW_SN BIGINT NOT NULL", "CHG_TYPE VARCHAR(16) NOT NULL", "PREV_STTS_CD VARCHAR(32)", "NEW_STTS_CD VARCHAR(32)", "CHG_USER_NO BIGINT", "CHG_DT TIMESTAMP NOT NULL"]},
      {"name": "LS_DATA_SRC", "purpose": "프레임 (FFmpeg 추출 산출)", "columns": ["SRC_SN BIGINT PK AUTO_INCREMENT", "RAW_SN BIGINT NOT NULL", "FRAME_NO INT NOT NULL", "FILE_PATH VARCHAR(500) NOT NULL", "SRC_BKUP_FILE_PATH VARCHAR(1000)", "CAPTURED_AT TIMESTAMP", "REG_DT TIMESTAMP NOT NULL", "UPD_DT TIMESTAMP", "UK (RAW_SN, FRAME_NO)"]},
      {"name": "LS_DATA_SRC_HSTRY", "purpose": "프레임 변경 이력", "columns": ["HSTRY_SEQ BIGINT PK AUTO_INCREMENT", "SRC_SN BIGINT NOT NULL", "CHG_TYPE VARCHAR(16) NOT NULL", "CHG_USER_NO BIGINT", "CHG_DT TIMESTAMP NOT NULL"]},
      {"name": "LS_DATA_LBL", "purpose": "라벨 (BBox/Polygon/Segment/Track)", "columns": ["LBL_SN BIGINT PK AUTO_INCREMENT", "SRC_SN BIGINT NOT NULL FK", "LBL_TYPE_CD VARCHAR(16) NOT NULL", "LABEL VARCHAR(255) NOT NULL", "POINTS_JSON LONGTEXT", "TRCK_ID VARCHAR(64)", "LBL_SRC_CD VARCHAR(20) (V20 추가)", "LABEL_ID BIGINT FK (V32 추가)", "REG_USER_NO BIGINT", "REG_DT TIMESTAMP NOT NULL", "UPD_DT TIMESTAMP"]},
      {"name": "LS_DATA_LBL_AI_INFO", "purpose": "AI 라벨 출처/신뢰도", "columns": ["DATA_LBL_AI_INFO_SN BIGINT PK", "DATA_LBL_SN BIGINT NOT NULL", "PJT_SN BIGINT NOT NULL", "DATA_RAW_SN BIGINT NOT NULL", "DATA_SRC_SN BIGINT NOT NULL", "LBL_SRC_CD VARCHAR(20) NOT NULL", "MODEL_NM VARCHAR(100)", "MODEL_VER VARCHAR(50)", "CONF_SCORE DECIMAL(6,5)", "AUTO_LBL_YN VARCHAR(1) NOT NULL DEFAULT 'Y'", "REG_ID VARCHAR(30)", "REG_DT TIMESTAMP NOT NULL", "MDFCN_ID VARCHAR(30)", "MDFCN_DT TIMESTAMP"]},
      {"name": "LS_DATA_LBL_ATTR_VAL", "purpose": "라벨 객체별 속성값 (CVAT-Like)", "columns": ["ATTR_VAL_ID BIGINT PK", "LBL_SN BIGINT NOT NULL FK", "ATTR_ID BIGINT NOT NULL FK", "ATTR_VAL VARCHAR(1000)", "REG_DT TIMESTAMP NOT NULL", "MDFCN_DT TIMESTAMP", "UK (LBL_SN, ATTR_ID)"]},
      {"name": "LS_LABEL", "purpose": "라벨 마스터 (Aggregate Root, CVAT-Like 라벨 풀)", "columns": ["LABEL_ID BIGINT PK AUTO_INCREMENT", "PJT_ID BIGINT NOT NULL", "NAME VARCHAR(64) NOT NULL", "COLOR VARCHAR(7) NOT NULL (#RRGGBB)", "TYPE VARCHAR(16) NOT NULL", "SORT_NO INT NOT NULL DEFAULT 0", "USE_YN VARCHAR(1) NOT NULL DEFAULT 'Y'", "REG_ID VARCHAR(30)", "REG_DT TIMESTAMP NOT NULL", "MDFCN_ID VARCHAR(30)", "MDFCN_DT TIMESTAMP", "UK (PJT_ID, NAME)"]},
      {"name": "LS_LABEL_ATTR", "purpose": "라벨 속성 정의 (SELECT/CHECKBOX/RADIO/NUMBER/TEXT)", "columns": ["ATTR_ID BIGINT PK", "LABEL_ID BIGINT NOT NULL FK", "NAME VARCHAR(64) NOT NULL", "INPUT_TYPE VARCHAR(16) NOT NULL", "VALUES_JSON VARCHAR(1000)", "DEFAULT_VAL VARCHAR(255)", "MUTABLE VARCHAR(1) NOT NULL DEFAULT 'Y'", "SORT_NO INT NOT NULL DEFAULT 0", "USE_YN VARCHAR(1) NOT NULL DEFAULT 'Y'", "REG_ID VARCHAR(30)", "REG_DT TIMESTAMP NOT NULL", "MDFCN_ID VARCHAR(30)", "MDFCN_DT TIMESTAMP", "UK (LABEL_ID, NAME)"]},
      {"name": "LS_LABEL_PRESET", "purpose": "라벨 프리셋 (이벤트별)", "columns": ["PRESET_ID BIGINT PK AUTO_INCREMENT", "NAME VARCHAR(64) NOT NULL", "DESCRIPTION VARCHAR(500)", "CREATED_AT DATETIME NOT NULL", "UPDATED_AT DATETIME NOT NULL", "UK NAME"]},
      {"name": "LS_LABEL_PRESET_CODE", "purpose": "프리셋 라벨 코드 매핑 (1:N)", "columns": ["CODE_SN BIGINT PK AUTO_INCREMENT", "PRESET_ID BIGINT NOT NULL FK CASCADE", "CODE VARCHAR(32) NOT NULL", "SORT_ORDER INT NOT NULL DEFAULT 0", "UK (PRESET_ID, CODE)"]},
      {"name": "LS_DATA_META", "purpose": "영상/프레임 시계열 메타 (외부 VLM 수신)", "columns": ["META_SN BIGINT PK AUTO_INCREMENT", "RAW_SN BIGINT NOT NULL", "META_KEY VARCHAR(64) NOT NULL", "META_VAL VARCHAR(2000)", "REG_DT TIMESTAMP NOT NULL", "UPD_DT TIMESTAMP", "UK (RAW_SN, META_KEY)"]},
      {"name": "LS_DATA_META_HSTRY", "purpose": "메타 변경 이력", "columns": ["HSTRY_SEQ BIGINT PK", "META_SN BIGINT NOT NULL", "PREV_VAL VARCHAR(2000)", "NEW_VAL VARCHAR(2000)", "CHG_USER_NO BIGINT", "CHG_DT TIMESTAMP NOT NULL"]},
      {"name": "LS_DATA_META_REVIEW", "purpose": "외부 메타 검토 상태 (저작도구 검수자 워크플로우)", "columns": ["DATA_META_REVIEW_SN BIGINT PK", "DATA_META_SN BIGINT NOT NULL", "PJT_SN BIGINT NOT NULL", "DATA_RAW_SN BIGINT NOT NULL", "DATA_SRC_SN BIGINT", "META_TYPE_CD VARCHAR(20) NOT NULL", "SRC_SYS_CD VARCHAR(20)", "RVW_STTS_CD VARCHAR(20) NOT NULL", "RVW_ID VARCHAR(30)", "RVW_DT TIMESTAMP", "REJECT_REASON VARCHAR(1000)", "REG_ID VARCHAR(30)", "REG_DT TIMESTAMP NOT NULL", "MDFCN_ID VARCHAR(30)", "MDFCN_DT TIMESTAMP"]},
      {"name": "LS_RAW_DATA_STATUS", "purpose": "영상 검수 상태 (낙관적 잠금 @Version)", "columns": ["RAW_DATA_ID BIGINT PK", "DATA_STTS_CD VARCHAR(32) NOT NULL DEFAULT 'PENDING'", "STP_CYCL INT NOT NULL DEFAULT 0", "IGI_CYCL INT NOT NULL DEFAULT 0", "UPD_DT TIMESTAMP NOT NULL", "VERSION BIGINT NOT NULL DEFAULT 0"]},
      {"name": "LS_DATA_ISSUE", "purpose": "검수 반려 이슈 (계층형, 자기참조)", "columns": ["DATA_ISSUE_SN BIGINT PK AUTO_INCREMENT", "UP_DATA_ISSUE_SN BIGINT", "VIDEO_ID BIGINT NOT NULL", "ISSUE_REASON VARCHAR(1000)", "REPORTED_USER_NO VARCHAR(50)", "REGISTERED_AT TIMESTAMP NOT NULL"]},
      {"name": "LS_TASK_ASSIGNMENT", "purpose": "작업 배정 (V36 신규명, LABELER/REVIEWER)", "columns": ["ASSIGNMENT_ID BIGINT PK AUTO_INCREMENT", "USER_NO BIGINT NOT NULL", "RAW_DATA_ID BIGINT NOT NULL", "TASK_TYPE_CD VARCHAR(32) NOT NULL", "REG_USER_NO BIGINT NOT NULL", "REG_DT TIMESTAMP NOT NULL", "UK (RAW_DATA_ID, USER_NO, TASK_TYPE_CD)"]},
      {"name": "LS_TASK_ASSIGN_HISTORY", "purpose": "재배정 이력 (V36 신규명)", "columns": ["HSTRY_SEQ BIGINT PK", "AUTHRT_SEQ BIGINT NOT NULL", "RAW_DATA_ID BIGINT NOT NULL", "PREV_USER_NO BIGINT NOT NULL", "NEW_USER_NO BIGINT NOT NULL", "TASK_TYPE_CD VARCHAR(32) NOT NULL", "CHG_USER_NO BIGINT NOT NULL", "CHG_DT TIMESTAMP NOT NULL"]},
      {"name": "LS_TASK_EVENT_LOG", "purpose": "작업 이벤트 누적 로그 (배정/재배정/검수/승인/반려)", "columns": ["EVENT_SEQ BIGINT PK AUTO_INCREMENT", "RAW_DATA_ID BIGINT NOT NULL", "EVENT_TYPE_CD VARCHAR(32) NOT NULL", "ACTOR_USER_NO BIGINT NOT NULL", "SUBJECT_USER_NO BIGINT", "PREV_USER_NO BIGINT", "REASON VARCHAR(500)", "OCCURRED_AT TIMESTAMP NOT NULL"]},
      {"name": "LS_AUTH_WORK_LOCK", "purpose": "작업 잠금 (동시성 보호)", "columns": ["WORK_LOCK_SN BIGINT PK", "LOCK_TARGET_CD VARCHAR(20) NOT NULL", "PJT_SN BIGINT", "DATA_RAW_SN BIGINT", "DATA_SRC_SN BIGINT", "LOCK_STTS_CD VARCHAR(20) NOT NULL", "LOCK_ID VARCHAR(64) NOT NULL UK", "LOCK_OWNER_ID VARCHAR(30)", "LOCK_DT TIMESTAMP NOT NULL", "EXPIRE_DT TIMESTAMP", "RELEASE_DT TIMESTAMP", "RELEASE_REASON VARCHAR(500)", "REG_ID VARCHAR(30)", "REG_DT TIMESTAMP NOT NULL", "MDFCN_ID VARCHAR(30)", "MDFCN_DT TIMESTAMP"]},
      {"name": "LS_DATA_AUG", "purpose": "증강 작업 (WINTER/NIGHT/RAIN/RESOLUTION)", "columns": ["DATA_AUG_SN BIGINT PK AUTO_INCREMENT", "SRC_SN BIGINT NOT NULL", "AUG_TYPE_CD VARCHAR(20) NOT NULL", "AUG_PROC_STTS_CD VARCHAR(20) NOT NULL DEFAULT 'PENDING'", "LBL_INTGRT_PCT DECIMAL(5,2)", "REJECT_REASON VARCHAR(500)", "DECISION_USER_NO VARCHAR(50)", "DECISION_AT TIMESTAMP", "REGISTERED_AT TIMESTAMP NOT NULL", "REGISTERED_USER_NO VARCHAR(50)"]},
      {"name": "LS_DATA_AUG_RVW", "purpose": "증강 검수 결과 (ACCEPTED/REJECTED)", "columns": ["DATA_AUG_RVW_SN BIGINT PK", "DATA_AUG_SN BIGINT NOT NULL", "PJT_SN BIGINT NOT NULL", "DATA_RAW_SN BIGINT NOT NULL", "DATA_SRC_SN BIGINT NOT NULL", "RVW_STTS_CD VARCHAR(20) NOT NULL", "LBL_INTGRT_PCT DECIMAL(5,2)", "REJECT_REASON VARCHAR(1000)", "RVW_ID VARCHAR(30)", "RVW_DT TIMESTAMP", "REG_ID VARCHAR(30)", "REG_DT TIMESTAMP NOT NULL", "MDFCN_ID VARCHAR(30)", "MDFCN_DT TIMESTAMP"]},
      {"name": "LS_DATA_AUG_LBL_MAP", "purpose": "증강-라벨 좌표 매핑 (해상도 유지/저하)", "columns": ["DATA_AUG_LBL_MAP_SN BIGINT PK", "DATA_AUG_SN BIGINT NOT NULL", "ORGN_DATA_LBL_SN BIGINT", "DATA_LBL_SN BIGINT NOT NULL", "COORD_RECALC_YN VARCHAR(1) NOT NULL DEFAULT 'N'", "SCALE_X DECIMAL(10,6)", "SCALE_Y DECIMAL(10,6)", "REG_ID VARCHAR(30)", "REG_DT TIMESTAMP NOT NULL"]},
      {"name": "LS_LABEL_VERSION", "purpose": "Gitea 커밋 추적 (현재 라벨의 커밋 기준)", "columns": ["LABEL_VERSION_SN BIGINT PK", "PJT_SN BIGINT NOT NULL", "DATA_RAW_SN BIGINT NOT NULL", "DATA_SRC_SN BIGINT", "GITEA_CMT_HASH VARCHAR(64) NOT NULL UK", "VERSION_NO INT NOT NULL", "SAVE_REASON_CD VARCHAR(20)", "ACTIVE_YN VARCHAR(1) NOT NULL DEFAULT 'Y'", "REG_ID VARCHAR(30)", "REG_DT TIMESTAMP NOT NULL"]},
      {"name": "LS_GITEA_FALLBACK_QUEUE", "purpose": "Gitea 호출 실패 시 fallback 큐 (Quartz Retry Job)", "columns": ["QUEUE_SN BIGINT PK AUTO_INCREMENT", "IDEMPOTENCY_KEY VARCHAR(64) NOT NULL UK", "OPERATION VARCHAR(16) NOT NULL", "PATH VARCHAR(512) NOT NULL", "BRANCH VARCHAR(128)", "COMMIT_MESSAGE VARCHAR(1024)", "AUTHOR VARCHAR(64)", "CONTENT_BASE64 LONGTEXT", "RETRY_COUNT INT NOT NULL DEFAULT 0", "MAX_RETRY INT NOT NULL DEFAULT 5", "STATUS VARCHAR(16) NOT NULL", "LAST_ERROR VARCHAR(2000)", "NEXT_RETRY_AT DATETIME", "DEAD_LETTER_AT DATETIME", "CREATED_AT DATETIME NOT NULL", "UPDATED_AT DATETIME NOT NULL"]},
      {"name": "LS_DEIDENT_REPORT", "purpose": "비식별 처리 리포트 (시스템 + 사용자 신고)", "columns": ["DEIDENT_REPORT_SN BIGINT PK AUTO_INCREMENT", "DATA_RAW_SN BIGINT NOT NULL", "REQ_ID VARCHAR(64)", "ORGN_FILE_PATH VARCHAR(1000)", "DE_IDNTF_FILE_PATH VARCHAR(1000)", "PROC_STTS_CD VARCHAR(20) (V28 nullable)", "REQ_DT TIMESTAMP", "RES_DT TIMESTAMP", "ERROR_CD VARCHAR(50)", "ERROR_MSG VARCHAR(1000)", "REPORTER_NO BIGINT", "REASON VARCHAR(1000)", "REPORT_STTS_CD VARCHAR(16)", "REPORT_DT TIMESTAMP", "RESOLVED_DT TIMESTAMP", "REG_ID VARCHAR(30)", "REG_DT TIMESTAMP NOT NULL", "MDFCN_ID VARCHAR(30)", "MDFCN_DT TIMESTAMP"]},
      {"name": "LS_DEIDENT_PROC_LOG", "purpose": "비식별 API 호출 이력 (DeidentifyStep)", "columns": ["PROC_LOG_SN BIGINT PK AUTO_INCREMENT", "DATA_RAW_SN BIGINT NOT NULL", "REQ_ID VARCHAR(64)", "ORGN_FILE_PATH VARCHAR(1000) NOT NULL", "DE_IDNTF_FILE_PATH VARCHAR(1000)", "PROC_STTS_CD VARCHAR(20) NOT NULL", "REQ_DT TIMESTAMP NOT NULL", "RES_DT TIMESTAMP", "ERROR_CD VARCHAR(50)", "ERROR_MSG VARCHAR(1000)", "REG_ID VARCHAR(30)", "REG_DT TIMESTAMP NOT NULL", "MDFCN_ID VARCHAR(30)", "MDFCN_DT TIMESTAMP"]},
      {"name": "LS_PORTAL_USER_VIDEO", "purpose": "포털 사용자 업로드 영상", "columns": ["PORTAL_VIDEO_SN BIGINT PK AUTO_INCREMENT", "PORTAL_USER_NO VARCHAR(50) NOT NULL (IDOR key)", "FILE_NAME VARCHAR(255)", "FILE_PATH VARCHAR(500)", "FILE_SIZE BIGINT", "MIME_TYPE VARCHAR(50)", "THUMBNAIL_PATH VARCHAR(500)", "DOWNLOAD_DDLN_DT TIMESTAMP", "REGISTERED_AT TIMESTAMP NOT NULL"]},
      {"name": "LS_WEBHOOK_IDEMPOTENCY", "purpose": "외부 시스템 위탁 멱등성 원장", "columns": ["IDEMPOTENCY_KEY VARCHAR(64) PK", "CHANNEL VARCHAR(32) NOT NULL (DEIDENTIFY|VLM|AUGMENT)", "STATE VARCHAR(16) NOT NULL (ISSUED|PROCESSED|FAILED)", "EXTERNAL_JOB_ID VARCHAR(128)", "APPLIED_AT DATETIME", "CREATED_AT DATETIME NOT NULL", "UPDATED_AT DATETIME NOT NULL"]},
      {"name": "LS_DATA_SET", "purpose": "학습데이터셋 내보내기 (YOLO/COCO)", "columns": ["EXPORT_SN BIGINT PK AUTO_INCREMENT", "PJT_ID BIGINT NOT NULL", "EXPORT_FORMAT VARCHAR(20) NOT NULL", "EXPORT_STTS_CD VARCHAR(20) NOT NULL DEFAULT 'PENDING'", "NAS_PATH VARCHAR(500)", "ERROR_MESSAGE VARCHAR(1000)", "EXPORTED_AT TIMESTAMP", "REGISTERED_USER_NO VARCHAR(50)", "REGISTERED_AT TIMESTAMP NOT NULL"]},
      {"name": "LS_SYSTEM_CONFIG", "purpose": "시스템 설정 (FFMPEG_THREADS 등 편집 가능 키)", "columns": ["CONFIG_KEY VARCHAR(100) PK", "CONFIG_VALUE VARCHAR(500)", "CONFIG_TYPE VARCHAR(20) NOT NULL", "DESCRIPTION VARCHAR(500)", "UPDATED_BY VARCHAR(50)", "UPDATED_AT TIMESTAMP NOT NULL"]},
      {"name": "LS_BATCH_PROC_LOG", "purpose": "배치 단계별 처리 로그", "columns": ["BATCH_PROC_LOG_SN BIGINT PK AUTO_INCREMENT", "JOB_ID VARCHAR(64) NOT NULL", "DATA_RAW_SN BIGINT", "DATA_SRC_SN BIGINT", "PROC_STEP_CD VARCHAR(30) NOT NULL", "PROC_STTS_CD VARCHAR(20) NOT NULL", "START_DT TIMESTAMP", "END_DT TIMESTAMP", "RTRY_CNT INT NOT NULL DEFAULT 0", "ERROR_CD VARCHAR(50)", "ERROR_MSG VARCHAR(1000)", "REQ_PAYLOAD LONGTEXT", "RES_PAYLOAD LONGTEXT", "REG_ID VARCHAR(30)", "REG_DT TIMESTAMP NOT NULL", "MDFCN_ID VARCHAR(30)", "MDFCN_DT TIMESTAMP"]}
    ],
    "indexes": [
      {"name": "IX_LS_DATA_RAW_CCTV", "tableId": "LS_DATA_RAW", "columns": ["VMS_CCTV_ID"]},
      {"name": "IX_LS_DATA_RAW_STTS", "tableId": "LS_DATA_RAW", "columns": ["DATA_STTS_CD"]},
      {"name": "UK_LS_DATA_RAW_VMS_CLIP", "tableId": "LS_DATA_RAW", "columns": ["VMS_CLIP_ID"], "unique": true},
      {"name": "IX_LS_DATA_SRC_RAW", "tableId": "LS_DATA_SRC", "columns": ["RAW_SN"]},
      {"name": "UK_LS_DATA_SRC_RAW_FRAME", "tableId": "LS_DATA_SRC", "columns": ["RAW_SN", "FRAME_NO"], "unique": true},
      {"name": "IX_LS_DATA_LBL_SRC", "tableId": "LS_DATA_LBL", "columns": ["SRC_SN"]},
      {"name": "IX_LS_DATA_LBL_TRCK_ID", "tableId": "LS_DATA_LBL", "columns": ["TRCK_ID"]},
      {"name": "IDX_LS_DATA_LBL_AI_INFO_LBL", "tableId": "LS_DATA_LBL_AI_INFO", "columns": ["DATA_LBL_SN"]},
      {"name": "IDX_LS_DATA_LBL_AI_INFO_SRC", "tableId": "LS_DATA_LBL_AI_INFO", "columns": ["PJT_SN", "DATA_RAW_SN", "DATA_SRC_SN"]},
      {"name": "IDX_LS_DATA_LBL_AI_INFO_SRC_CD", "tableId": "LS_DATA_LBL_AI_INFO", "columns": ["LBL_SRC_CD"]},
      {"name": "UK_LS_LABEL_NAME", "tableId": "LS_LABEL", "columns": ["PJT_ID", "NAME"], "unique": true},
      {"name": "IDX_LS_LABEL_PJT_USE", "tableId": "LS_LABEL", "columns": ["PJT_ID", "USE_YN", "SORT_NO"]},
      {"name": "UK_LS_LABEL_ATTR_NAME", "tableId": "LS_LABEL_ATTR", "columns": ["LABEL_ID", "NAME"], "unique": true},
      {"name": "UK_LS_DATA_LBL_ATTR_VAL", "tableId": "LS_DATA_LBL_ATTR_VAL", "columns": ["LBL_SN", "ATTR_ID"], "unique": true},
      {"name": "UK_LS_LABEL_PRESET_NAME", "tableId": "LS_LABEL_PRESET", "columns": ["NAME"], "unique": true},
      {"name": "UK_LS_LABEL_PRESET_CODE", "tableId": "LS_LABEL_PRESET_CODE", "columns": ["PRESET_ID", "CODE"], "unique": true},
      {"name": "IX_LS_DATA_META_RAW", "tableId": "LS_DATA_META", "columns": ["RAW_SN"]},
      {"name": "UK_LS_DATA_META_RAW_KEY", "tableId": "LS_DATA_META", "columns": ["RAW_SN", "META_KEY"], "unique": true},
      {"name": "IDX_LS_DATA_META_REVIEW_META", "tableId": "LS_DATA_META_REVIEW", "columns": ["DATA_META_SN"]},
      {"name": "IDX_LS_DATA_META_REVIEW_STTS", "tableId": "LS_DATA_META_REVIEW", "columns": ["META_TYPE_CD", "RVW_STTS_CD"]},
      {"name": "IX_LS_DATA_ISSUE_VIDEO", "tableId": "LS_DATA_ISSUE", "columns": ["VIDEO_ID"]},
      {"name": "IX_LS_DATA_ISSUE_UP", "tableId": "LS_DATA_ISSUE", "columns": ["UP_DATA_ISSUE_SN"]},
      {"name": "IX_LS_DATA_ISSUE_REPORTER", "tableId": "LS_DATA_ISSUE", "columns": ["REPORTED_USER_NO"]},
      {"name": "UK_LS_TASK_ASSIGNMENT", "tableId": "LS_TASK_ASSIGNMENT", "columns": ["RAW_DATA_ID", "USER_NO", "TASK_TYPE_CD"], "unique": true},
      {"name": "IX_LS_TASK_ASSIGNMENT_USER", "tableId": "LS_TASK_ASSIGNMENT", "columns": ["USER_NO", "TASK_TYPE_CD"]},
      {"name": "IX_LS_TASK_ASSIGN_HISTORY_AUTHRT", "tableId": "LS_TASK_ASSIGN_HISTORY", "columns": ["AUTHRT_SEQ"]},
      {"name": "IX_LS_TASK_EVENT_LOG_RAW", "tableId": "LS_TASK_EVENT_LOG", "columns": ["RAW_DATA_ID", "OCCURRED_AT"]},
      {"name": "IX_LS_TASK_EVENT_LOG_ACTOR", "tableId": "LS_TASK_EVENT_LOG", "columns": ["ACTOR_USER_NO"]},
      {"name": "UK_LS_AUTH_WORK_LOCK_ID", "tableId": "LS_AUTH_WORK_LOCK", "columns": ["LOCK_ID"], "unique": true},
      {"name": "IDX_LS_AUTH_WORK_LOCK_TARGET", "tableId": "LS_AUTH_WORK_LOCK", "columns": ["LOCK_TARGET_CD", "PJT_SN", "DATA_RAW_SN", "DATA_SRC_SN"]},
      {"name": "IDX_LS_AUTH_WORK_LOCK_STTS", "tableId": "LS_AUTH_WORK_LOCK", "columns": ["LOCK_STTS_CD", "EXPIRE_DT"]},
      {"name": "IDX_LS_DATA_AUG_SRC", "tableId": "LS_DATA_AUG", "columns": ["SRC_SN", "AUG_TYPE_CD"]},
      {"name": "IDX_LS_DATA_AUG_STTS", "tableId": "LS_DATA_AUG", "columns": ["AUG_PROC_STTS_CD", "REGISTERED_AT"]},
      {"name": "IDX_LS_DATA_AUG_RVW_AUG", "tableId": "LS_DATA_AUG_RVW", "columns": ["DATA_AUG_SN"]},
      {"name": "IDX_LS_DATA_AUG_LBL_MAP_AUG", "tableId": "LS_DATA_AUG_LBL_MAP", "columns": ["DATA_AUG_SN"]},
      {"name": "UK_LS_LABEL_VERSION_HASH", "tableId": "LS_LABEL_VERSION", "columns": ["GITEA_CMT_HASH"], "unique": true},
      {"name": "IDX_LS_LABEL_VERSION_TARGET", "tableId": "LS_LABEL_VERSION", "columns": ["PJT_SN", "DATA_RAW_SN", "DATA_SRC_SN", "ACTIVE_YN"]},
      {"name": "uk_idempotency_key", "tableId": "LS_GITEA_FALLBACK_QUEUE", "columns": ["IDEMPOTENCY_KEY"], "unique": true},
      {"name": "idx_status_next_retry", "tableId": "LS_GITEA_FALLBACK_QUEUE", "columns": ["STATUS", "NEXT_RETRY_AT"]},
      {"name": "IDX_LS_DEIDENT_PROC_LOG_RAW", "tableId": "LS_DEIDENT_PROC_LOG", "columns": ["DATA_RAW_SN"]},
      {"name": "IDX_PORTAL_USER_NO", "tableId": "LS_PORTAL_USER_VIDEO", "columns": ["PORTAL_USER_NO", "REGISTERED_AT"]},
      {"name": "IDX_LS_WEBHOOK_IDEMPOTENCY_CH_STATE", "tableId": "LS_WEBHOOK_IDEMPOTENCY", "columns": ["CHANNEL", "STATE"]},
      {"name": "IDX_LS_DATA_SET_PJT", "tableId": "LS_DATA_SET", "columns": ["PJT_ID", "REGISTERED_AT"]},
      {"name": "IDX_LS_DATA_SET_STTS", "tableId": "LS_DATA_SET", "columns": ["EXPORT_STTS_CD", "REGISTERED_AT"]},
      {"name": "IDX_LS_BATCH_PROC_LOG_JOB", "tableId": "LS_BATCH_PROC_LOG", "columns": ["JOB_ID"]},
      {"name": "IDX_LS_BATCH_PROC_LOG_STEP_STTS", "tableId": "LS_BATCH_PROC_LOG", "columns": ["PROC_STEP_CD", "PROC_STTS_CD"]}
    ]
  },
  "_handle": "db-klid-system"
}
```

## 활용

- `DATABASE_DESIGN` 산출물: tables + indexes + tablespaces 자식 배열 본문 출력 (`DatabaseDesignRenderer`)
- `DATABASE_TABLE` 산출물: tables.columns 기반 CREATE TABLE DDL 추출 (`DatabaseTableRenderer`)
- `DATA_MIGRATION_DESIGN` 산출물: content의 § 5 전환 정책 + tables 기반 마이그레이션 매핑 (`DataMigrationRenderer`)

## 후속

- 26 LS_* 모든 테이블 + 핵심 컬럼 + 46 인덱스 attrs 직렬화 — 약 23KB (Phase 32 후속 B-2 보강의 64KB 한도 내)
- MNG_*/QRTZ_* 외부/Quartz 테이블은 content § 3, § 4에 별도 기록 (저작도구 도메인 외이므로 attrs 미포함)
- 향후 Flyway V42+ 신규 컬럼 추가 시 본 노드의 attrs.tables.columns 동기 갱신 필요 (영향도 추적 ccarch revision)
