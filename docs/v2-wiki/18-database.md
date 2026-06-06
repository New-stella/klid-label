# 18. 데이터베이스

> 출처: D8 엔티티관계모형설계서, D9 데이터베이스설계서, CLAUDE.md(DB 정책·View), 코드(`db/migration/` V0~V56)
> 관련: 각 기능 페이지 · [19 외부 시스템](19-external-security-cvat.md)

## 18.1 기본 정보

| 항목 | 내용 |
|------|------|
| DBMS | **PostgreSQL** |
| 스키마 | `klid_at` |
| 마이그레이션 | **Flyway** (V0~V56, 70+ 테이블/뷰) |
| 소유 정책 | 저작도구 **LS_*** 자체 소유(자체 Flyway), 관제 **MNG_*** 9개 `ddl-auto=validate` 참조, Quartz `QRTZ_*` |
| DDL | PostgreSQL 표준 문법 (MariaDB 문법 금지), `ddl-auto=validate` 고정 |

> **MNG_* 공유 테이블 변경 시 관제서버팀 선승인 필수.** 엔티티 수정 시 Flyway 마이그레이션 동반.

## 18.2 LS_* 핵심 테이블 (저작도구 소유)

### 영상 · 프레임 · 라벨
| 테이블 | 용도 | 위키 |
|--------|------|------|
| `LS_DATA_RAW` (V2) | 원본 영상 메타 (VMS_CLIP_ID, EVNT_TYPE_CD, DE_IDNTF_YN, PARENT_RAW_SN) | [05](05-video-management.md) |
| `LS_DATA_RAW_HSTRY` (V2) | 영상 상태 변경 이력 | [05](05-video-management.md) |
| `LS_DATA_SRC` (V4) | 추출 프레임 (FRAME_NO, 원본/비식별 경로) | [07](07-batch-pipeline.md) |
| `LS_DATA_SRC_HSTRY` (V4) | 프레임 변경 이력 | |
| `LS_DATA_LBL` (V4) | 라벨 (좌표·트랙ID·LABEL_NM, 작업 중 임시저장) | [10](10-labeling.md) |
| `LS_DATA_LBL_AI_INFO` (V23) | AI 라벨 출처(YOLO/SAM2/VLM)·신뢰도 CONF_SCORE | [11](11-ai-assisted.md) |
| `LS_DATA_LBL_ATTR_VAL` (V33) | 라벨 속성값 | [10](10-labeling.md) |

### 라벨 마스터 · 프리셋 · 버전
| 테이블 | 용도 | 위키 |
|--------|------|------|
| `LS_LABEL` (V31) | 라벨 마스터 (LABEL_NM, COLR_VL, LABEL_TYPE_CD) | [10](10-labeling.md) |
| `LS_LABEL_ATTR` (V33) | 라벨 속성 정의 (INPUT_TYPE_CD, MUTABLE_YN) | [10](10-labeling.md) |
| `LS_LABEL_PRESET` / `LS_LABEL_PRESET_CODE` (V13) | 프리셋 마스터 / 라벨 코드 | [10](10-labeling.md) |
| `LS_LABEL_VERSION` (V24) | 라벨 버전 스냅샷 (VERSION_HASH, SAVE_REASON_CD, ACTIVE_YN) | [13](13-version-control.md) |
| `LS_DATA_LBL_HSTRY` | 라벨 변경 이력 | [13](13-version-control.md) |

### 메타 · 마킹
| 테이블 | 용도 | 위키 |
|--------|------|------|
| `LS_MARKING` (V45) | 마킹 (MARK_MODE_CD, FRME_INTV_NOCS, MARK_CN JSON) | [06](06-marking.md) |
| `LS_DATA_META` (V4) | 시계열 메타 (META_KEY/VL, EXTERNAL_JOB_ID) | [09](09-vlm-timeseries.md) |
| `LS_DATA_META_HSTRY` (V4) / `LS_DATA_META_REVIEW` (V5) | 메타 이력 / 검수 | [09](09-vlm-timeseries.md) |

### 증강 · 비식별 · 해상도
| 테이블 | 용도 | 위키 |
|--------|------|------|
| `LS_DATA_AUG` (V8) | 증강 데이터 (AUG_PROC_STTS_CD) | [14](14-augmentation.md) |
| `LS_DATA_AUG_RVW` (V25) / `LS_DATA_AUG_LBL_MAP` (V26) | 증강 검수 / 라벨 매핑 | [14](14-augmentation.md) |
| `LS_RESOLUTION_EXPORT` (V55) | 해상도 변경 기록 | [14](14-augmentation.md) |
| `LS_DEIDENT_REPORT` (V21) / `LS_DEIDENT_PROC_LOG` (V29) | 비식별 누락 신고 / 처리 이력 | [08](08-deidentification.md) |

### 작업 · 상태 · 운영
| 테이블 | 용도 | 위키 |
|--------|------|------|
| `LS_RAW_DATA_ENROLLMENT` / `LS_RAW_DATA_STATUS` (V36) | 영상 등록 / 진행 상태 | [05](05-video-management.md) |
| `LS_TASK_ASSIGNMENT` / `LS_TASK_ASSIGN_HISTORY` (V36) | 작업 배정 / 재배정 이력 | [12](12-review-assignment.md) |
| `LS_TASK_EVENT_LOG` (V36) | 작업 이벤트 로그 | [12](12-review-assignment.md) |
| `LS_BATCH_PROC_LOG` (V12) | 배치 단계 로그 (STAGE_CD, RES_PAYLOAD_CN) | [07](07-batch-pipeline.md) |
| `LS_SYSTEM_CONFIG` (V11) | 시스템 설정 (화이트리스트 key/value) | [10](10-labeling.md) |
| `LS_AUTH_WORK_LOCK` (V22) | 비식별 재진행 중 잠금 | [08](08-deidentification.md) |
| `LS_WEBHOOK_IDEMPOTENCY` (V39) | 웹훅 멱등성 | [19](19-external-security-cvat.md) |
| `LS_CONTROL_NOTIFY_FALLBACK` (V44) / `LS_GITEA_FALLBACK_QUEUE` (V41) | 통지 / Gitea 실패 재시도 | [15](15-control-notify.md)·[13](13-version-control.md) |
| `LS_PORTAL_USER_LABEL` (V47) | 포털 사용자 라벨 | [16](16-portal.md) |
| `LS_NOTICE` / `LS_NOTICE_ATTACH` (V56) | 게시판 공지(DRAFT/PUBLISHED, PIN_YN) / 첨부(UUID 저장명, FK cascade) — R1 외 추가 | [20](20-notice-board.md) |
| `LS_TUS_UPLOAD` (V59) | TUS 1.0 재개 가능 업로드 세션 — `UPLOAD_ID`(UUID PK)/`USER_NO`(소유자)/`UPLOAD_LENGTH`/`UPLOAD_OFFSET`(예약어 OFFSET 회피)/`STATUS`(IN_PROGRESS·COMPLETED·EXPIRED)/`FILE_PATH`(UUID 저장명 강제)/메타(`VMS_CLIP_ID`·`CCTV_ID`·…)/`EXPIRES_AT`(+24h TTL)/`VERSION`(낙관적 잠금). 완료 시 `LS_DATA_RAW` 합류. 인덱스 `IDX_LTU_USER_STATUS`(동시 세션 상한)·`IDX_LTU_EXPIRES`(만료 정리 잡) | [05](05-video-management.md) |
| `LS_DATA_ISSUE` (V5) / `LS_DATA_SET` (V8) / `LS_DEADLINE`·`LS_META` (V36) | 품질 이슈 / 데이터셋 / 데드라인·전역 메타 | — |

## 18.3 데이터마트 적재용 View (V52)

`klid_at` 스키마에 4종(+1) View — **검수 완료(APPROVED) 영상만 노출**. 모두 `CREATE OR REPLACE VIEW`라 멱등.

| View | 내용 |
|------|------|
| `V_COMPLETED_VIDEO` | 영상 메타 + 원본 경로 + 검수 완료 일시 (PARENT_RAW_SN 증강 추적) |
| `V_COMPLETED_FRAME` | 프레임 페어 (FILE_PATH=원본, SRC_BKUP_FILE_PATH=비식별) |
| `V_COMPLETED_LABEL` + `V_COMPLETED_LABEL_ATTR` | 라벨 좌표·마스터 코드 + 속성값 |
| `V_COMPLETED_META` | 시계열 메타 (RVW_STTS_CD='APPROVED'만) |

> 관제서버는 `TASK_COMPLETED`/`TASK_MODIFIED` 수신 후 RAW_SN으로 4 View SELECT → 영상 1건=1 row UPSERT. 비식별 경로는 `STORAGE_RAW_PATH`→`STORAGE_DEIDENTIFIED_PATH` 컨벤션 치환으로 도출(View 미포함). → [15](15-control-notify.md)

## 18.4 관제서버 소유 MNG_* (읽기 전용 9개)

`MNG_ACCT_USER`, `MNG_ACCT_AUTHRT`, `MNG_ACCT_USER_AUTHRT`, `MNG_CLIP_MASTER`, `MNG_RESOURCE_CCTV`, `MNG_EX_EVNT_TYPE`, `MNG_EX_EVNT_TYPE_MAP`, `MNG_EX_LOCAL_GOV`, `MNG_CLIP_SCHEDULE_QUE`(배치 큐).

## 18.5 Quartz

`QRTZ_*` (JobStore, PostgreSQLDelegate, BYTEA) — 배치 스케줄 상태. → [07](07-batch-pipeline.md).

> 상세 컬럼·ERD는 D8/D9 참고 → [19 설계 문서 카탈로그](19-external-security-cvat.md#설계-문서-카탈로그).
