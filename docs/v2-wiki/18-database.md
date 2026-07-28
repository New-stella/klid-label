# 18. 데이터베이스

> 출처: D8 엔티티관계모형설계서, D9 데이터베이스설계서, CLAUDE.md(DB 정책·View), 코드(`db/migration/` V0~V56)
> 관련: 각 기능 페이지 · [19 외부 시스템](19-external-security-cvat.md)

## 18.1 기본 정보

| 항목 | 내용 |
|------|------|
| DBMS | **PostgreSQL** |
| 스키마 | `klid_at` |
| 마이그레이션 | **Flyway** (V0~V128, 70+ 테이블/뷰) |
| 소유 정책 | 저작도구 **LS_*** 자체 소유(자체 Flyway), 관제 **MNG_*** 9개 `ddl-auto=validate` 참조, Quartz `QRTZ_*` |
| DDL | PostgreSQL 표준 문법 (MariaDB 문법 금지), `ddl-auto=validate` 고정 |

> **MNG_* 공유 테이블 변경 시 관제서버팀 선승인 필수.** 엔티티 수정 시 Flyway 마이그레이션 동반.

## 18.2 LS_* 핵심 테이블 (저작도구 소유)

### 영상 · 프레임 · 라벨
| 테이블 | 용도 | 위키 |
|--------|------|------|
| `LS_DATA_RAW` (V2) | 원본 영상 메타 (VMS_CLIP_ID, EVNT_TYPE_CD, DE_IDENT_YN, ORGNL_RAW_SN — V82 물리 rename, 구 PARENT_RAW_SN 폐지. V95에서 데이터마트 뷰 출력 컬럼도 ORGNL_RAW_SN 으로 통일돼 내부·외부 모두 ORGNL_RAW_SN). `DATA_STTS_CD`(배치 단계): `PENDING`→`MARKING_READY`(선두 비식별 성공)→`COMPLETED`(배치 완료). **촬영환경 수동 메타(V130, 요구 외 추가 2026-07-24)**: `WTHR_NM VARCHAR(20)`(날씨·명V20)·`DAY_NGT_CD VARCHAR(20)`(시간대·코드V20)·`SESN_CD VARCHAR(20)`(계절·코드V20) — 전부 NULL 허용(NULL=미입력→export가 파생 폴백). 라벨링 메타탭 촬영환경 패널이 수동 편집, 검수 승인 export(NiaVideo weather/time_of_day/season)에 우선 반영 | [05](05-video-management.md)·[24](24-dataset-export.md) |
| `LS_DATA_RAW_HSTRY` (V2) | 영상 상태 변경 이력 | [05](05-video-management.md) |
| `LS_DATA_SRC` (V4) | 추출 프레임 (FRM_NO, 원본/비식별 경로, `FRM_EXPLN` 프레임설명 V103 — NIA image.description 작업자 수기). **개인정보 수동 메타(V130, 요구 외 추가 2026-07-24)**: `ANONY_INCL_YN CHAR(1)`(익명여부)·`PSDO_INCL_YN CHAR(1)`(가명여부)·`PRVC_INCL_YN CHAR(1)`(개인정보 포함여부) — 여부C1 표준, 전부 NULL 허용(NULL=미입력→파생 폴백). 라벨링 메타탭 개인정보 패널이 프레임 단위 수동 편집. **가명여부·개인정보 포함여부만** export(NiaImage pseudonymity/privacy_included)에 우선 반영, **익명여부(anonymity)는 export를 덮지 않고 시스템 kind 자동값 유지**(원본 N/비식별 Y). 비식별 누락 신고 처리 시 3필드 NULL 리셋 | [07](07-batch-pipeline.md)·[10](10-labeling.md)·[24](24-dataset-export.md) |
| `LS_DATA_SRC_HSTRY` (V4) | 프레임 변경 이력 | |
| `LS_DATA_LBL` (V4) | 라벨 (좌표·트랙ID·LABEL_NM, 작업 중 임시저장). `LBL_TYPE_CD`: BBOX/POLYGON/SEGMENT/TRACK/**SKELETON**. `POINT_CN`(JSON): BBOX=`[[l,t],[r,b]]`·POLYGON=`[[x,y],…]` 2튜플. **SKELETON=COCO-17 휴먼 포즈 17×`[x,y,v]` 삼중값**(정확히 17개, v∈{0=미표기,1=비가시,2=가시}) — type-routed 직렬화로 2튜플 경로와 격리 | [10](10-labeling.md) |
| `LS_DATA_LBL_AI_INFO` (V23) | AI 라벨 출처(YOLO/SAM2/VLM)·신뢰도 CONF_SCORE | [11](11-ai-assisted.md) |
| `LS_DATA_LBL_ATTR_VAL` (V33) | 라벨 속성값 | [10](10-labeling.md) |

### 라벨 마스터 · 프리셋 · 버전
| 테이블 | 용도 | 위키 |
|--------|------|------|
| `LS_LABEL` (V31, **CI 유일 V120·exact 제약 제거 V121**) | 라벨 마스터 (LBL_NM, COLR_VL, LBL_TYPE_CD: BBOX/POLYGON/POINT/SKELETON). `LBL_NM` 유일성은 **활성(`USE_YN='Y'`) 한정 `LOWER(TRIM(LBL_NM))` 부분 유니크(`UK_LS_LABEL_NM_CI`, V120)가 단독 강제** — 대소문자 근사중복(오토라벨 조회 크래시)·앞뒤 공백(labelId null 유실) 차단. **V121: all-rows exact UNIQUE(`UK_LS_LABEL_NAME`)는 제거**(soft-delete 된 이름 재사용을 막던 배치 해소 — Q1 정합). 저장 시 서비스가 trim + 활성 CI 중복검사(409), soft-delete 이름은 재사용 허용, 동일 exact 활성 중복·동시 생성 경합은 CI 인덱스가 원자 차단→409. V120 마이그레이션은 기존 행 미변경, 활성 근사중복 존재 시 안전중단(RAISE). | [10](10-labeling.md) |
| `LS_LABEL_ATTR` (V33) | 라벨 속성 정의 (INPUT_TYPE_CD, MUTABLE_YN) | [10](10-labeling.md) |
| `LS_LABEL_PRESET` / `LS_LABEL_PRESET_CODE` (V13, **마스터 연동 V117~V119**) | 프리셋 마스터 / 라벨 코드. **`LS_LABEL_PRESET_CODE` 재설계(2026-07-21, V117~V119)**: 프리셋 코드가 라벨 마스터(`LS_LABEL`)를 **단일 진실원으로 실시간 참조**하도록 전환 — ①`LBL_ID`(BIGINT, FK→`LS_LABEL.LBL_ID`, **nullable=미연결 허용**) + FK 컬럼 인덱스 추가·이름 매칭 backfill(V117), ②형태 스냅샷 컬럼 `BBOX_ENABLED`/`POLYGON_ENABLED` **제거**(V117 — 형태는 마스터 `LBL_TYPE_CD` 소유: BBOX→bbox·POLYGON→polygon·POINT/SKELETON→도형 오토라벨 미적용, 프리셋 개별 토글 불가), ③`LBL_CD` **NOT NULL 제거**(V118 — labelId 기반 신규 행은 코드 미저장, 미연결 레거시 행만 표시용 코드 보유), ④부분 유니크 인덱스 `UK_LS_LABEL_PRESET_CODE_LBLID (PRESET_ID, LBL_ID) WHERE LBL_ID IS NOT NULL`(V119 — 동시 갱신 시 프리셋당 같은 labelId 중복 저장 방지, 위반은 409 CONFLICT). 라벨명·형태를 스냅샷하지 않고 조회·표시·오토라벨 사용 시점에 마스터에서 join하므로 마스터 변경이 신규·기존 프리셋에 즉시 반영. 마스터에 매칭 안 되는 코드(labelId null/비활성)는 오류 없이 **'미연결'** 표시(자동 생성/삭제 없음) | [10](10-labeling.md) |
| `LS_LABEL_VERSION` (V24) | 라벨 버전 스냅샷 (VERSION_HASH, SAVE_REASON_CD, ACTVTN_YN) | [13](13-version-control.md) |
| `LS_DATA_LBL_HSTRY` (V58, 확장 V112, **저장이벤트 재구조화 V114**) | 라벨 **저장 이벤트** 이력. **재구조화(2026-07-21, V114)**: 기존 '라벨 1건=1행'(구 `LBL_SN`·`CHG_KIND_CD` 라벨단위)에서 **'저장 이벤트=1행 + diff 페이로드'**(프레임 단위)로 전환. 컬럼: `LBL_HSTRY_SN`(PK, 저장이벤트 ID)·`SRC_SN`(프레임)·`REG_DT`(저장시각)·`REG_ID`(작업자, NULLABLE)·`ADD_CNT`/`MDFCN_CNT`/`DEL_CNT`(INTEGER NOT NULL DEFAULT 0, 추가/수정/삭제 건수)·`CHG_DTL_CN`(TEXT, 항목별 diff JSON — `{lblSn, changeKind, labelName, before, after}` 목록). **제거된 컬럼: `LBL_SN`·`CHG_KIND_CD`**(라벨단위→이벤트단위). 저장 클릭 1회=이력 1건(직전 저장 대비 이전값→새값 diff, 첫 저장은 전부 ADDED, 저장은 프레임 전체 교체라 요청에서 빠진 라벨은 실제 삭제+DELETED, 무변경 저장은 이력 미생성). 트랙 삭제·비식별 신고 삭제도 저장이벤트 모델로 프레임당 기록. `GET /v1/frames/{srcSn}/label-history` 조회, 라벨링 화면(SC-005) 히스토리 패널 '변경 이력' 탭에서 표시 | [10](10-labeling.md)·[13](13-version-control.md) |
| `LS_DATASET_EXPORT` (V105, CONTENT_HASH V106) | 검수 승인(APPROVED) 시 학습데이터 **파일 산출 추적·버전 원장**. 영상(DATA_RAW_SN) 단위 export 누적(EXPORT_VER_NO=count+1, UK(DATA_RAW_SN,EXPORT_VER_NO)), EXPORT_STTS_CD(SUCCEEDED/FAILED), CONTENT_HASH(라벨+프레임설명+영상메타 SHA-256 멱등키). 실제 산출: `{labeling_root}/{RAW_SN}/v{n}/orgnl\|deid/` (승인 AFTER_COMMIT @Async, API 없음) | [24](24-dataset-export.md) |

### 메타 · 마킹
| 테이블 | 용도 | 위키 |
|--------|------|------|
| `LS_MARKING` (V45) | 마킹 (MARK_MODE_CD, FRME_INTV_NOCS, MARK_CN JSON) + 부분 유니크 `UK_LS_MARKING_RAW_ACTVTN`(V142 — `RAW_SN` where `STTS_CD IN ('PENDING','VLM_REQUESTED')`) | [06](06-marking.md) |
| `LS_DATA_META` (V4) | 시계열 메타 (META_KEY/VL, EXTERNAL_JOB_ID) | [09](09-vlm-timeseries.md) |
| `LS_DATA_META_HSTRY` (V4) / `LS_DATA_META_REVIEW` (V5) | 메타 이력 / 검수 | [09](09-vlm-timeseries.md) |
| `LS_EVNT_ANNO` (V127) | 이벤트 어노테이션(event_annotation, VQA/CoT) 영상 단위 저장. `ANNO_CN` jsonb(payload 원문, caption/evidence 후보 c1..cn), UK(RAW_SN) 영상당 1건 | [09](09-vlm-timeseries.md) · [24](24-dataset-export.md) |
| `LS_EVNT_ANNO_REVIEW` (V127) | event_annotation 검토 상태 (`RVW_STTS_CD` VARCHAR(20): AUTO_GENERATED/PENDING/APPROVED/REJECTED, `META_TYPE_CD`, `RJCT_RSN`, `VER` 낙관적 잠금 CWE-362) | [09](09-vlm-timeseries.md) |

### 데이터마트 통합 메타 스냅샷 (포털향, V97~)
| 테이블 | 용도 | 위키 |
|--------|------|------|
| `LS_DATASET_VIDEO_META` (V97, 부분 유니크 인덱스 V99, `EVNT_ANNO_CN` V128) | 검수완료(APPROVED) 시점 영상 메타 **동결 스냅샷**(1영상=1행 컬럼형, `SNPSHT_HASH` 멱등·`ACTIVE_YN` append-only). 표준용어 컬럼(VDO_CDC·FPS·BIT_RT·ASPRT_RT·RESL·WGS84_LAT/LOT·SESN_CD·DAY_NGT_CD 등). **`EVNT_ANNO_CN`(V128, jsonb)**: 승인 시점 APPROVED event_annotation payload 동결본(미승인/부재 시 NULL) — 재export 멱등. control DB 단일 진실원(SoT). `ReviewService.approve()` 트랜잭션 편승 materialize + 기존 APPROVED 백필(ApplicationRunner). MNG_* live JOIN 제거로 동결 무결성 | [24](24-dataset-export.md) |
| `LS_META_REPL_OUTBOX` (V98) | 포털(별도 물리 DB) 단방향 복제 outbox(PENDING/DONE/DEAD/SUPERSEDED, at-least-once, 멱등키 RAW_SN+SNPSHT_HASH). XA 부재 대응 — 승인과 분리된 워커가 복제 | — |

> 포털 복제본 테이블은 `db/portal/V1`(Flyway `db/migration` 스캔 밖 — 포털 DB에 수동 프로비저닝, 워커 graceful probe). 순서 보증은 단일 인스턴스+`@DisallowConcurrentExecution` 전제(스케일아웃 시 재설계 필요).

### 증강 · 비식별 · 해상도
| 테이블 | 용도 | 위키 |
|--------|------|------|
| `LS_DATA_AUG` (V8) | 증강 데이터 (AUG_PROC_STTS_CD) — **해상도 변경 파생도 흡수**: `AUG_TYPE_CD='RESL_1080P/720P/480P'` 판별자 + 부분 유니크 인덱스 `UK_LS_DATA_AUG_RESL (SRC_SN, AUG_TYPE_CD) WHERE AUG_TYPE_CD LIKE 'RESL_%'` (V124). **하위 테이블**: 외부 위탁 1건이 100장 상한으로 분할되므로 `LS_DATA_AUG` 1행 : `LS_DATA_AUG_JOB` N행 : `LS_DATA_AUG_JOB_FILE` M행 (모두 `ON DELETE CASCADE`) | [14](14-augmentation.md) |
| `LS_DATA_AUG_JOB` (V140) | 증강 외부 위탁 작업 — 생성형 AI 명세서 v1.1 정합. PK `AUG_JOB_SN`(BIGINT), `DATA_AUG_SN`(BIGINT FK→`LS_DATA_AUG`), `JOB_SEQ`(INT, 분할 순서 1부터), `IDMP_KEY`(VARCHAR(128) **UNIQUE** `UK_LDAJ_IDMP_KEY` — 우리가 발급한 request_id. **웹훅 발급 게이트의 단일 진실원**으로 `LS_WEBHOOK_IDEMPOTENCY` 를 대체), `OTSD_JOB_ID`(VARCHAR(200) — 외부가 202 로 발급, 접수 전 NULL), `JOB_STTS_CD`(VARCHAR(20) RECEIVED/RUNNING/SUCCEEDED/FAILED/CANCELED — `AUG_PROC_STTS_CD` 검수축과 별개), `TOT_NOCS`(INT), `ERR_CD`(VARCHAR(50))/`ERR_MSG_CN`(VARCHAR(1000) — 위탁 실패를 조용히 삼키지 않기 위한 사유 기록). 인덱스 `IDX_LDAJ_AUG_SEQ (DATA_AUG_SN, JOB_SEQ)`·`IDX_LDAJ_OTSD_JOB_ID` | [14](14-augmentation.md) |
| `LS_DATA_AUG_JOB_FILE` (V141) | 증강 위탁 파일 매핑 — 위탁 순서↔프레임 대응 + 외부 산출 경로. PK `AUG_JOB_FILE_SN`(BIGINT), `AUG_JOB_SN`(BIGINT FK→`LS_DATA_AUG_JOB`), `FILE_SEQ`(INT = `input_files[].sequence`), `SRC_SN`(BIGINT — 위탁한 비식별 프레임), `RSLT_FILE_PATH_NM`(VARCHAR(500) = `results[].output_file_path`, 수신 전 NULL). UNIQUE `UK_LDAJF_JOB_FILE_SEQ (AUG_JOB_SN, FILE_SEQ)`, 인덱스 `IDX_LDAJF_SRC_SN`. **왜 필요한가**: 계약상 `results[]` 에 입력 식별자가 없어 순서로만 대응하므로, 위탁 시점 대응을 못박지 않으면 위탁~콜백 사이 프레임 증감이 조용히 어긋나 다른 프레임에 남의 증강본이 붙는다(무증상 오염). 산출 경로는 SUCCEEDED 콜백에 되붙이고, 건수 불일치 시 미적재 + job FAILED(fail-closed) | [14](14-augmentation.md) |
| `LS_DATA_AUG_RVW` (V25) / `LS_DATA_AUG_LBL_MAP` (V26) | 증강 검수 / 라벨 매핑 (`COORD_RECALC_YN`/`SCALE_X`/`SCALE_Y` — 증강·해상도 파생 공통 재사용) | [14](14-augmentation.md) |
| ~~`LS_RESOLUTION_EXPORT`~~ · ~~`LS_RESOLUTION_LBL_MAP`~~ | **폐기(V125 백필 후 fail-closed DROP)** — 해상도 변경 저장모델을 `LS_DATA_AUG`+`LS_DATA_AUG_LBL_MAP`으로 통합(2026-07-22) | [14](14-augmentation.md) |
| `LS_DEIDENT_REPORT` (V21) / `LS_DEIDENT_PROC_LOG` (V29, `REQ_KND_CD` BATCH/REDEIDENT V68 도입·V83 rename REQ_KIND_CD→REQ_KND_CD) | 비식별 누락 신고 / 처리 이력(배치·검수완료재비식별 분기) | [08](08-deidentification.md) |

### 작업 · 상태 · 운영
| 테이블 | 용도 | 위키 |
|--------|------|------|
| `LS_RAW_DATA_ENROLLMENT` / `LS_RAW_DATA_STATUS` (V36) | 영상 등록 / 진행 상태 | [05](05-video-management.md) |
| `LS_TASK_ASSIGNMENT` / `LS_TASK_ASSIGN_HISTORY` (V36) | 작업 배정 / 재배정 이력 | [12](12-review-assignment.md) |
| `LS_TASK_EVENT_LOG` (V36) | 작업 이벤트 로그 | [12](12-review-assignment.md) |
| `LS_USER_ROLE` (V75) | 저작도구 라벨링 역할 매핑 (USER_NO→ROLE_CD: REVIEWER/WORKER/PORTAL_USER) — 인가 역할 단일 진실원. 관제 `MNG_ACCT_USER_AUTHRT` 대체(역할 분리 2026-06) | [03](03-auth-roles.md) |
| `LS_BATCH_PROC_LOG` (V12) | 배치 단계 로그 (STAGE_CD, RESP_PAYLOAD_CN) | [07](07-batch-pipeline.md) |
| `LS_BAT_RTY_WTNG` (V116) | 배치 실패 영상 재시도 대기 — **DB 영속화**(구 in-memory 큐 대체, 2노드 Active-Active 정합). PK `BAT_RTY_SN`, `RAW_SN` UNIQUE(영상 1건=1행), 컬럼(`RTY_NMTM`/`MAX_RTY_NMTM`/`STTS_CD`=PENDING/RETRYING/EXHAUSTED/`RTY_PRNMNT_DT`=재시도 예정 일시/`LAST_ERR_MSG_CN`)은 사업(program) 표준용어(배치=BAT·재시도=RTY·횟수=NMTM·예정=PRNMNT·대기=WTNG) 준거. 폴링은 조건부 원자 UPDATE(PENDING→RETRYING)로 동시 폴링 직렬화, 최초 등록은 `INSERT ... ON CONFLICT DO NOTHING`+FOR UPDATE 로 UK 경쟁 흡수, 최대 초과 시 EXHAUSTED 소진(삭제 아님, 이력 보존) | [07](07-batch-pipeline.md) |
| `LS_SYSTEM_CONFIG` (V11) | 시스템 설정 (화이트리스트 key/value) | [10](10-labeling.md) |
| `LS_AUTH_WORK_LOCK` (V22, 동일영상 활성락 1건 partial unique index V69) | 비식별 재진행 중 잠금(동시 이중 위탁 차단) | [08](08-deidentification.md) |
| `LS_WEBHOOK_IDEMPOTENCY` (V39) | 웹훅 멱등성 | [19](19-external-security-cvat.md) |
| `LS_WHK_SIGN_USE` (V131) | 웹훅 서명 사용 원장 — replay 방지용 **1회성 소비** 기록. PK `SIGN_HASH`(경로+X-Timestamp+X-Signature 의 SHA-256 hex, VARCHAR(64)), `WHK_PATH_NM`·`EXPD_DT`. 필터가 트랜잭션 밖에서 `INSERT ... ON CONFLICT DO NOTHING` + updateCount 로 판정(PG UNIQUE 위반이 tx 전체를 abort 시키는 25P02 회피). 표준용어 웹훅=WHK·서명=SIGN·해시=HASH·사용=USE·만료=EXPD | [19](19-external-security-cvat.md) |
| `LS_WHK_FAIL_NMTM` (V131) | 웹훅 인증 실패 횟수 — 2노드 공유 rate limit 집계. PK (`CALL_IP_ADDR` IP주소V45, `BGNG_DT` 분 단위 윈도우), `FAIL_NMTM`(수I11)·`EXPD_DT`. 앱은 1차 JVM-local 카운터로 즉시 차단하고 본 테이블은 2차 집계(공유 저장소 장애 시 fail-open). 표준용어 호출=CALL·주소=ADDR·시작=BGNG·실패=FAIL·횟수=NMTM | [19](19-external-security-cvat.md) |
| `LS_AUTHRT_GRANT_ATMPT` (V132) | 권한 자가부여(role-claim) 시도 횟수 — 2노드 공유 rate limit 집계. PK (`ATMPT_SE_CD` 코드V20 = ACCOUNT/GLOBAL, `ATMPT_IDNTFR` 식별자V36 = 요청자 sub 또는 'GLOBAL', `BGNG_DT` 분 단위 윈도우), `ATMPT_NMTM`(수I11)·`EXPD_DT`. 서비스 트랜잭션이 실패로 롤백돼도 카운터가 남도록 **REQUIRES_NEW** 로 기록. 공유 저장소 장애 시에도 JVM-local Caffeine 카운터가 최종 방어선(완전 fail-open 금지). 표준용어 권한=AUTHRT·부여=GRANT·시도=ATMPT·구분=SE·식별자=IDNTFR·횟수=NMTM | [19](19-external-security-cvat.md) |
| `LS_CONTROL_NOTIFY_FALLBACK` (V44, `SEND_RSLT_CD` 발송결과 컬럼 V77) / `LS_GITEA_FALLBACK_QUEUE` (V41) | 통지 재시도 큐 + 발송 결과 상태 관찰(`STTS_CD`=큐 처리 PENDING/RETRYING/SUCCEEDED/DEAD_LETTER, `SEND_RSLT_CD`=SUCCESS/FAILED — 즉시 성공도 SUCCEEDED+SUCCESS 터미널 행으로 적재) / Gitea 실패 재시도 | [15](15-control-notify.md)·[13](13-version-control.md) |
| `LS_PORTAL_USER_LABEL` (V47) | 포털 사용자 라벨 (데이터마트 영상 대상) | [16](16-portal.md) |
| `LS_NOTICE` / `LS_NOTICE_ATTACH` (V56) | 게시판 공지(DRAFT/PUBLISHED, UPEND_FIX_YN) / 첨부(UUID 저장명, FK cascade) — R1 외 추가 | [20](20-notice-board.md) |
| `LS_TUS_UPLOAD` (V59, 표준용어 rename V88·V90) | TUS 1.0 재개 가능 업로드 세션 — `ULD_ID`(UUID PK)/`USER_NO`(소유자)/`ULD_LEN`/`ULD_OFFSET`(예약어 OFFSET 회피)/`STTS_CD`(IN_PROGRESS·COMPLETED·EXPIRED)/`FILE_PATH`(UUID 저장명 강제)/메타(`VMS_CLIP_ID`·`CCTV_ID`·…)/`EXPRY_DT`(+24h TTL, 공공 만료일시)/`VER`(낙관적 잠금). 완료 시 `LS_DATA_RAW` 합류. 인덱스 `IDX_LTU_USER_STATUS`(동시 세션 상한)·`IDX_LTU_EXPIRES`(만료 정리 잡) | [05](05-video-management.md) |
| `LS_DATA_ISSUE` (V5) / `LS_DEADLINE`·`LS_META` (V36) | 품질 이슈 / 데드라인·전역 메타 | — |

> 구 `LS_DATA_SET` (V8, 학습데이터셋 Export용)은 **범위 외 orphan 테이블로 판정되어 삭제**됨(V86) — 엔티티·활성쿼리·View·FK 참조 0건 검증. 학습데이터셋 Export는 CLAUDE.md 범위 외(관제/데이터마트 책임).

> **공공 우선(gov-first) 표준용어 rename (V90·V91, 2026-07-10)**: 공공 표준용어에 동일 한글용어가 존재하는 컬럼 15건을 공공약어로 정합 — `EXPD_DT→EXPRY_DT`(LS_AUTH_WORK_LOCK·LS_TUS_UPLOAD), `RESP_DT→RSPNS_DT`, `REJECT_RSN→RJCT_RSN`(×2), `MODEL_NM→MDL_NM`, `VERSION_NO→VER_NO`, `REPORT_DT→DCLR_DT`, `ISSUE_COMMENT_SN→CMNT_SN`, `ATTACH_SN→ATCH_FILE_SN`, `STORE_FILE_NM→STRG_FILE_NM`, `LOCK_DT→LCK_DT`, `RELEASE_DT→RMV_DT`, `RELEASE_RSN→RMV_RSN`, `ATTR_NM→ATRB_NM`(V91, `V_COMPLETED_LABEL_ATTR` 뷰 재생성(V114에서 뷰 제거) — 출력 별칭 `ATTR_NAME` 불변). Java 필드명·JSON 계약은 불변(물리 컬럼만 rename).

### 포털 자산 업로드 (ADR-013 예외, V107~)

> 포털 사용자(PORTAL_USER)가 **본인 이미지·영상을 직접 업로드**해 수동 라벨링(BBOX/POLYGON)하는 별도 경로. 내부 파이프라인(비식별→마킹→배치→검수)·데이터마트 View와 **완전 분리**되며 오토라벨링·SAM2·VLM·검수·버전관리 미적용 → [16](16-portal.md)·[04](04-screens-ia.md).

| 테이블 | 용도 | 위키 |
|--------|------|------|
| `LS_PORTAL_ULD` (V107) | 포털 업로드 자산 (소유자 USER_NO, 자산유형 IMAGE/VIDEO, 원본 파일명·경로, `STTS_CD`: `UPLOADED`→`PROCESSING`→`READY`\|`FAILED`). 이미지 20MB/장·50장/요청, 영상 5GB(mp4/mov/avi) | [16](16-portal.md) |
| `LS_PORTAL_ULD_FRME` (V107) | 업로드 자산 프레임 (SEQUENCE PK, 프레임 경로). 영상은 고정 간격 추출(`LS_SYSTEM_CONFIG` `portal.upload.frame-interval-sec` 기본 5초, 상한 maxFrames 2000), 이미지는 1프레임 | [16](16-portal.md) |
| `LS_PORTAL_ULD_LBL` (V107) | 업로드 자산 수동 라벨 (BBOX/POLYGON만, 좌표 JSON). 오토라벨 미적용 | [16](16-portal.md) |
| `LS_PORTAL_TUS_ULD` (V108) | 포털 영상 TUS 1.0 재개 가능 업로드 세션 (소유자·오프셋·만료 등, 내부 `LS_TUS_UPLOAD`와 분리) | [16](16-portal.md) |

> 신규 API `/v1/portal/uploads/**` (images·목록·상세·frames·image·삭제·tus·labels·export·file). 영상은 비식별 미적용(본인 데이터), 다운로드는 본인 데이터(JSON export/원본) 기준.

## 18.3 데이터마트 적재용 View (V52)

`klid_at` 스키마에 4종 View (V114 재편) — **검수 완료(APPROVED) 영상만 노출**. 모두 `CREATE OR REPLACE VIEW`라 멱등.

| View | 내용 |
|------|------|
| `V_COMPLETED_VIDEO` | 영상 메타 + 원본 경로 + 검수 완료 일시. **재구성(V101·V102)**: `LS_DATASET_VIDEO_META`(ACTIVE_YN='Y') 동결 스냅샷 기반 + 라이브 APPROVED 게이트. 기존 출력 컬럼 alias 보존(관제 무영향) + 신규 메타 18컬럼(cctv명·좌표·코덱·fps·해상도 등) 추가. **`EXPORT_PATH_NM`·`FRAME_CNT`(V114)**: 최신 SUCCEEDED export(`LS_DATASET_EXPORT`)를 `LEFT JOIN LATERAL`(LIMIT 1)로 끝에 append — 행 증식 0, 미export 영상은 두 값 null |
| `V_COMPLETED_FRAME` | 프레임 페어 (`ORIGINAL_PATH`=원본, `DEIDENTIFIED_PATH`=비식별; 원천 `LS_DATA_SRC.DE_IDNTF_SRC_FILE_PATH_NM`) + **`DESCRIPTION`(V104, `FRM_EXPLN` 프레임설명, 하위호환 끝 추가)** |
| `V_COMPLETED_LABEL_CHANGE` (V114 신설 · V115 재정의) | 라벨 변경점 (`LS_DATA_LBL_HSTRY` 기반, **저장이벤트 단위** — `ADD_CNT`/`MDFCN_CNT`/`DEL_CNT` 종류별 건수 + `CHG_DTL_CN` diff JSON, APPROVED 게이트). V115의 `LS_DATA_LBL_HSTRY` 저장이벤트 재구조화(구 `LBL_SN`/`CHG_KIND_CD` 제거)에 맞춰 뷰를 재정의(관제 연동 계약 변경 — 협의 대상). 구 라벨 좌표·속성 본문 뷰(`V_COMPLETED_LABEL`·`V_COMPLETED_LABEL_ATTR`)는 V114에서 제거 — 라벨 내용은 검수 승인 export 폴더 JSON이 진실원(뷰 중복 노출 제거) |
| `V_COMPLETED_META` | 시계열 메타 (RVW_STTS_CD='APPROVED'만). V101에서 `video.*` 기술메타 6키 제외(통합 스냅샷 `V_COMPLETED_VIDEO`로 이관) — VLM/외부 시계열만 노출 |

> 관제서버는 `TASK_COMPLETED`/`TASK_MODIFIED` 수신 후 RAW_SN으로 4 View SELECT → 영상 1건=1 row UPSERT. 비식별 **영상** 경로는 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 적재값 사용(문자열 치환 도출 아님, View 미포함). 비식별 **프레임** 경로는 `V_COMPLETED_FRAME.DEIDENTIFIED_PATH`(=`LS_DATA_SRC.DE_IDNTF_SRC_FILE_PATH_NM`)에 직접 노출되며, 신규 추출은 원본 `{base}/frames/raw/{rawSn}`·비식별 `{base}/frames/deid/{rawSn}` 로 분기 저장돼 `STORAGE_RAW_PATH==STORAGE_DEIDENTIFIED_PATH`(=`/nas-storage`)여도 충돌하지 않는다. → [15](15-control-notify.md)

## 18.4 관제서버 소유 MNG_* (읽기 전용 9개)

`MNG_ACCT_USER`, `MNG_ACCT_AUTHRT`, `MNG_ACCT_USER_AUTHRT`, `MNG_CLIP_MASTER`, `MNG_RESOURCE_CCTV`, `MNG_EX_EVNT_TYPE`, `MNG_EX_EVNT_TYPE_MAP`, `MNG_EX_LOCAL_GOV`, `MNG_CLIP_SCHEDULE_QUE`(배치 큐).

> **역할 분리 (2026-06)**: 과거 저작도구가 쓰던 `MNG_ACCT_USER`(useYn UPDATE)·`MNG_ACCT_USER_AUTHRT`(역할 delete/insert)는 이제 **저작도구 쓰기 0건**이다. `MNG_ACCT_USER`는 사용자 식별 READ 전용(`@Immutable`), `MNG_ACCT_USER_AUTHRT`는 저작도구 미사용(역할은 `LS_USER_ROLE`로 분리). 아키텍처 가드 테스트(`MngAcctWriteGuardTest`)로 회귀 차단.

### 18.4.1 이벤트 타입 진실원 — `MNG_EX_EVNT_TYPE` / `MNG_EX_EVNT_TYPE_MAP` (SoT)

영상 이벤트 타입의 **단일 진실원(SoT)** 은 관제 소유 `MNG_EX_EVNT_TYPE`(+`MNG_EX_EVNT_TYPE_MAP` 라벨)다. 저작도구는 READ 연동(`@Immutable`, `ddl-auto=validate`)하며 자체 마스터 테이블을 두지 않는다. (구 `EvntType` enum 6종/`EVT_*` 하드코딩은 **폐기·삭제**됨 — 관제 실코드와 어긋났던 잔재.)

- **코드 체계**: `EVNT_TYPE_CD` = `EV` + 대분류(2) + 카테고리(2) + 상세(2), 예 `EV02000201`. 3단계 계층(`EVNT_CLS_CD` 대분류 / `EVNT_CTGRY_CD` 카테고리 / 상세). `CLCT_YN`=수집여부.
- **라벨**: `MNG_EX_EVNT_TYPE_MAP` (`CD_TYPE`='01' 대분류명 / '02' 카테고리명, 5컬럼 복합 PK `CD_TYPE+EVNT_CLS_CD+EVNT_CTGRY_CD+DTL_EVNT+EVNT_TYPE_CD`).
- **저작도구 노출 정책**: 라벨 해석(매핑)은 **전체 코드** 대상(들어오는 어떤 EV-코드든 한글명), 필터/선택 드롭다운은 **`CLCT_YN='Y'` 중 대분류≠08(ignore) = 9 카테고리**(상세 14코드를 카테고리로 dedup). 구현: `eventtype/service/EventTypeService`(`filterOptions`/`codeLabelMap`/`categoryKeyOf`, Caffeine 캐시) + `GET /api/v1/event-types`·`/labels`.
- **저장 단위**: 영상 `LS_DATA_RAW.EVNT_TYPE_CD`=상세 EV-코드(관제 적재값), 프리셋 `LS_LABEL_PRESET.EVNT_TYPE_CD`=categoryKey. 프리셋 매칭은 영상 EV-코드를 categoryKey로 변환 후 조회(`PresetLabelLookupService`).
- **마이그레이션**: 구 `EVT_*` 잔존 데이터는 V72(`V72__migrate_preset_evnt_type_to_category.sql`)가 프리셋→categoryKey, 영상→대표 EV-코드로 정정(멱등·운영 no-op). 관제 신규 코드 추가는 관제가 관리(저작도구 마이그레이션 없음).
- 실제 스키마는 LogiCraft **ERD-024**(관제 공유 클립 ERD)에 진실원 기록(MNG_* prefix 규칙으로 D8/D9 산출물 비대상). ※ ERD-024에 두 테이블 모델 추가는 후속 정합 권장.

## 18.5 Quartz

`QRTZ_*` (JobStore, PostgreSQLDelegate, BYTEA) — 배치 스케줄 상태. → [07](07-batch-pipeline.md).

> 상세 컬럼·ERD는 D8/D9 참고 → [19 설계 문서 카탈로그](19-external-security-cvat.md#설계-문서-카탈로그).
