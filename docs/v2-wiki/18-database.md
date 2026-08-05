# 18. 데이터베이스

> 출처: D8 엔티티관계모형설계서, D9 데이터베이스설계서, CLAUDE.md(DB 정책·View), 코드(`db/migration/` V0~V56)
> 관련: 각 기능 페이지 · [19 외부 시스템](19-external-security-cvat.md)

## 18.1 기본 정보

| 항목 | 내용 |
|------|------|
| DBMS | **PostgreSQL** |
| 스키마 | `klid_at` |
| 마이그레이션 | **Flyway** (V0~V128, 70+ 테이블/뷰) |
| 소유 정책 | 저작도구 **LS_*** 자체 소유(자체 Flyway), 관제 **MNG_*** **0개**(2026-08-04 전량 제거 — 18.4), Quartz `QRTZ_*` |
| DDL | PostgreSQL 표준 문법 (MariaDB 문법 금지), `ddl-auto=validate` 고정 |

> **MNG_* 공유 테이블 변경 시 관제서버팀 선승인 필수.** 엔티티 수정 시 Flyway 마이그레이션 동반.

## 18.2 LS_* 핵심 테이블 (저작도구 소유)

### 영상 · 프레임 · 라벨
| 테이블 | 용도 | 위키 |
|--------|------|------|
| `LS_DATA_RAW` (V2) | 원본 영상 메타 (VMS_CLIP_ID, EVNT_TYPE_CD, DE_IDENT_YN, ORGNL_RAW_SN — V82 물리 rename, 구 PARENT_RAW_SN 폐지. V95에서 데이터마트 뷰 출력 컬럼도 ORGNL_RAW_SN 으로 통일돼 내부·외부 모두 ORGNL_RAW_SN). `DATA_STTS_CD`(배치 단계): `PENDING`→`MARKING_READY`(선두 비식별 성공)→`COMPLETED`(배치 완료). **촬영환경 수동 메타(V130, 요구 외 추가 2026-07-24)**: `WTHR_NM VARCHAR(20)`(날씨·명V20)·`DAY_NGT_CD VARCHAR(20)`(시간대·코드V20)·`SESN_CD VARCHAR(20)`(계절·코드V20) — 전부 NULL 허용(**NULL=미입력→동결·export 모두 null(미상). 촬영일시 추정 안 함**, E-ISSUE-42 2026-07-29. 구 "export가 파생 폴백" 폐기 — 파생 폴백은 화면 프리필 조회에만 남음). 라벨링 메타탭 촬영환경 패널이 수동 편집, 검수 승인 export(NiaVideo weather/time_of_day/season)에 우선 반영. **개인정보 수동 메타(V163, 2026-08-03)**: `ANONY_INCL_YN CHAR(1)`·`PSDO_INCL_YN CHAR(1)`·`PRVC_INCL_YN CHAR(1)` — 여부C1 표준, **★2026-08-04 부터 INSERT 시점에 실제 값(`Y`/`N`/`N`)을 적재**한다(`@Builder` 생성자 — 두 엔티티에 `@DynamicInsert` 가 없어 Hibernate 가 모든 컬럼을 명시 INSERT 하므로 **DB DEFAULT 는 주 적재 경로에 적용될 수 없다**). NULL 은 이 변경 **이전에 적재된 레거시 행**에만 남으며 조회 시 비식별 기본상수로 프리필된다. 라벨링 메타탭 **개인정보(영상)** 패널이 영상 단위 수동 편집(`GET/PUT /v1/videos/{rawSn}/privacy-meta`), export **video 블록의 `deid` 축** 원천이다. ⚠ **`orgnl` 축은 이 컬럼이 아니라 관제 인입값 `LS_DATA_INGEST.*_INCL_YN`(V166 신설·V170 fail-closed DEFAULT `N`/`N`/`Y`)** 이다 — 관제 미송신 필드는 `null` 유지(지어내지 않음). **구 서술 "`orgnl` 은 3필드 모두 null"(2026-08-03)은 폐기**됐다([24 §24.3.3](24-dataset-export.md) · [CLAUDE.md](../../CLAUDE.md) 「export 개인정보 3필드 정책」). 동결 스냅샷 컬럼 없음(소비자가 export 하나뿐) — 승인 후 수정은 `TaskModifiedEvent(exportRegenerated=true)` + 콘텐츠 해시 편입으로 동기화 | [05](05-video-management.md)·[24](24-dataset-export.md) |
| `LS_DATA_RAW_HSTRY` (V2) | 영상 상태 변경 이력 | [05](05-video-management.md) |
| `LS_DATA_SRC` (V4) | 추출 프레임 (FRM_NO, 원본/비식별 경로, `FRM_EXPLN` 프레임설명 V103 — NIA image.description 작업자 수기). **개인정보 수동 메타(V130, 요구 외 추가 2026-07-24)**: `ANONY_INCL_YN CHAR(1)`(익명여부)·`PSDO_INCL_YN CHAR(1)`(가명여부)·`PRVC_INCL_YN CHAR(1)`(개인정보 포함여부) — 여부C1 표준. **★2026-08-04 부터 INSERT 시점에 실제 값(`Y`/`N`/`N`)을 팩토리에서 적재**하며, NULL 은 그 이전 레거시 행에만 남아 조회 시 기본상수로 프리필된다. ⚠ **이 3컬럼에 DB DEFAULT 를 걸지 않는다** — 한 벌인데 원천·비식별 두 축이 공유하므로 원천 기본값(`N`/`N`/`Y`)을 걸면 비식별의 "미입력"이 사라져 `deid.anonymity` 가 `Y`→`N` 으로 뒤집힌다. 라벨링 메타탭 개인정보 패널이 프레임 단위 수동 편집. **3필드 모두** export(NiaImage anonymity/pseudonymity/privacy_included)의 **`deid` 축**에 우선 반영된다. ⚠ **`orgnl` 축은 정책 상수 `N`/`N`/`Y`**(`ExportPrivacyPolicy.ORGNL_DEFAULT_*`) — 프레임 단위 원천 판정 **데이터가 존재하지 않기** 때문이며, 영상 단위(`video` 블록)가 관제 인입값을 쓰는 것과 **출처가 다르다**(값이 갈려도 모순이 아니라 입도가 다른 사실). **구 서술 "`orgnl` 산출물은 3필드 모두 null"(2026-08-03)은 폐기**. 영상 단위 대응 저장소는 `LS_DATA_RAW.*_INCL_YN`(V163)이며 **입도가 다른 별개 축**이다. ★**비식별 누락 신고는 이 3필드를 리셋하지 않고 보존**한다(2026-08-04 확정 — 사람이 입력한 판정도 라벨과 같은 작업 결과이므로 폐기하지 않고 resolve 후 이어서 진행. **구 "신고 시 두 축 NULL 리셋" 정책은 폐기**, `PRIVACY_META_RESET` 감사 타입은 과거 행 판독용으로만 존치) | [07](07-batch-pipeline.md)·[10](10-labeling.md)·[24](24-dataset-export.md) |
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
| `LS_MARKING` (V45) | 마킹 (MARK_MODE_CD, FRME_INTV_NOCS, MARK_CN JSON) + 부분 유니크 `UK_LS_MARKING_RAW_ACTVTN`(V142 — `RAW_SN` where `STTS_CD IN ('PENDING','VLM_REQUESTED')`). `STTS_CD` 종결값에 `SKIPPED`(V159 — 배치 트리거 skip 시 마킹 종결, B-ISSUE-41) 추가 — 신규 컬럼 없음, 값만 확장이라 유니크 술어는 불변 | [06](06-marking.md) |
| `LS_DATA_META` (V4) | 시계열 메타 (META_KEY/VL, EXTERNAL_JOB_ID) | [09](09-vlm-timeseries.md) |
| `LS_DATA_META_HSTRY` (V4) / `LS_DATA_META_REVIEW` (V5) | 메타 이력 / 검수 | [09](09-vlm-timeseries.md) |
| `LS_EVNT_ANNO` (V127) | 이벤트 어노테이션(event_annotation, VQA/CoT) 영상 단위 저장. `ANNO_CN` jsonb(payload 원문, caption/evidence 후보 c1..cn), UK(RAW_SN) 영상당 1건 | [09](09-vlm-timeseries.md) · [24](24-dataset-export.md) |
| `LS_EVNT_ANNO_REVIEW` (V127) | event_annotation 검토 상태 (`RVW_STTS_CD` VARCHAR(20): AUTO_GENERATED/PENDING/APPROVED/REJECTED, `META_TYPE_CD`, `RJCT_RSN`, `VER` 낙관적 잠금 CWE-362) | [09](09-vlm-timeseries.md) |

### 이벤트유형 마스터 (V168, 저작도구 소유)
| 테이블 | 용도 | 위키 |
|--------|------|------|
| `LS_EVNT_TYPE` (V168) | 이벤트유형 마스터 — **단일 진실원(SoT)**. PK `EVNT_TYPE_CD VARCHAR(20)`(코드V20), `EVNT_NM VARCHAR(200)`(**관제 수신** 유형명 — 인입이 값 변경 시에만 갱신), `OPTR_INDCT_NM VARCHAR(200)`(**운영자 표시명** — 관리 API 전용 칸, 관제가 절대 쓰지 않아 비우면 관제값으로 자연 복귀), `EVNT_CLSF_CD`/`EVNT_CTGRY_CD VARCHAR(20)`(관제 인입값, **코드에서 유도하지 않음**), `CLCT_YN CHAR(1)`(수집여부 — 필터 노출 조건), `REG_DT`. **신규 수동 생성 경로 없음** — 등록의 유일한 출처는 관제 인입 소비 시점 자동 upsert. `LS_EVNT_CTGRY` 에 **물리 FK 를 걸지 않는다**(미등록 카테고리코드가 오면 INSERT 가 FK 위반으로 실패해 그 유형이 영구 미등록으로 남고, 결과적으로 필터에서 사라지기 때문 — 참조 무결성보다 데이터 인입을 우선한 의도된 설계). ⚠ **관제는 현재 코드만 송신**하며 `EVNT_NM`·대분류·카테고리를 인입에 싣지 않는다(dev 실측 전 행 NULL, 반영 요청 전) | [05](05-video-management.md)·[06](06-marking.md) |
| `LS_EVNT_CTGRY` (V168) | 이벤트 카테고리 마스터 — 복합 PK `(EVNT_CLSF_CD, EVNT_CTGRY_CD)`, `EVNT_CTGRY_NM`. 원래 3계층(대분류→카테고리→유형)의 중간 레벨이며 **표시명 폴백 3순위의 원천**이다(구 `MNG_EX_EVNT_TYPE_MAP` `CD_TYPE='02'` 10건 이관) | [05](05-video-management.md) |

> **표시명 = `COALESCE(운영자 표시명, 관제 수신 유형명, 카테고리명, 유형코드)`** 4단 폴백. 판정 단일 원천은 `EventTypeDisplayNamePolicy`(Java `resolve` / native SQL `SQL_COALESCE`)이며 **복제 금지** — 필터 옵션·라벨맵·관리 화면·승인 시점 동결(export `event_name`)이 전부 같은 결과를 내야 한다. 유형별 이름이 비어 있는 동안 같은 카테고리의 유형들이 같은 이름으로 보이는 것은 **결함이 아니라 정상 상태**이고, 그 상태에서 필터 드롭다운이 중복돼 보이는 문제는 **표시명 그룹 축**(2026-08-05, [CLAUDE.md](../../CLAUDE.md) 「이벤트유형 필터는 "표시명 그룹" 축이다」)으로 해소한다 — 아래 §18.4.1 참조.

### 데이터마트 통합 메타 스냅샷 (포털향, V97~)
| 테이블 | 용도 | 위키 |
|--------|------|------|
| `LS_DATASET_VIDEO_META` (V97, 부분 유니크 인덱스 V99, `EVNT_ANNO_CN` V128) | 검수완료(APPROVED) 시점 영상 메타 **동결 스냅샷**(1영상=1행 컬럼형, `SNPSHT_HASH` 멱등·`ACTIVE_YN` append-only). 표준용어 컬럼(VDO_CDC·FPS·BIT_RT·ASPRT_RT·RESL·WGS84_LAT/LOT·SESN_CD·DAY_NGT_CD 등). **`EVNT_ANNO_CN`(V128, jsonb)**: 승인 시점 APPROVED event_annotation payload 동결본(미승인/부재 시 NULL) — 재export 멱등. control DB 단일 진실원(SoT). `ReviewService.approve()` 트랜잭션 편승 materialize + 기존 APPROVED 백필(ApplicationRunner). MNG_* live JOIN 제거로 동결 무결성 | [24](24-dataset-export.md) |
| `LS_META_REPL_OUTBOX` (V98) | 포털(별도 물리 DB) 단방향 복제 outbox(PENDING/DONE/DEAD/SUPERSEDED, at-least-once, 멱등키 RAW_SN+SNPSHT_HASH). XA 부재 대응 — 승인과 분리된 워커가 복제 | — |

> 포털 복제본 테이블은 `db/portal/V1~V4`(Flyway `db/migration` 스캔 밖 — 저작도구 Flyway는 control(@Primary) 데이터소스에만 붙는다). **포털 스키마 프로비저닝은 설치 단계 책임**이다: 온프렘은 `gen-schema-sql.sh` 가 만든 `deploy/onprem/db/portal-schema.sql` 을 `install/17-load-portal-schema.sh` 가 로드하고, 그 밖의 환경은 `db/portal/V*.sql` 을 포털 DB에 1회 수동 적용한다. **로드하지 않으면 복제가 조용히 0건으로 유지된다**(워커는 graceful skip만 하고 승인·export·관제 통지는 정상 동작하므로 헬스·기능으로 드러나지 않는다 — dev cudo_246 실측). 복제를 쓰지 않는 환경은 `META_REPLICATION_ENABLED=false` 를 **명시**할 것(기본값 true). `db/portal/V4` 는 control `V128`(`EVNT_ANNO_CN`) 드리프트 정정분이다. 가용성 probe는 포털 DataSource 직결(JPA/트랜잭션 미경유) — 트랜잭션 안에서 42P01을 삼키면 rollback-only 마킹 때문에 커밋에서 `UnexpectedRollbackException` 이 나 graceful skip이 깨진다(회귀 가드 `MetaReplicationUnprovisionedPortalIT`). 순서 보증은 **Quartz 클러스터 락**(`QRTZ_LOCKS`, `isClustered=true`)으로 2노드 Active-Active 에서도 동일 트리거를 1노드만 발화 + `@DisallowConcurrentExecution`(클러스터 전역 동시 실행 금지) 전제 — 워커는 `PENDING` 을 `REG_DT` ASC 로 순차 폴링한다. 클러스터링을 끄고(`QUARTZ_CLUSTERED=false`) 다중 노드를 띄우면 순서 보증이 깨진다.

### 증강 · 비식별 · 해상도
| 테이블 | 용도 | 위키 |
|--------|------|------|
| `LS_DATA_AUG` (V8) | 증강 데이터 (`AUG_PROC_STTS_CD` = PENDING / ACCEPTED / REJECTED / **CANCELED**) — **CANCELED 는 V154 신설**(사용자 취소 종결). 계약상 취소는 웹훅을 발사하지 않아 동기 응답 시점에 확정하지 않으면 영구 PENDING 이 되고 고아 회수기(job 0건 기준)도 건지지 못한다. REJECTED 재사용 불가 — 그 값엔 이미 REVIEWER 정상 반려와 처리 실패 롤업 두 의미가 겹쳐 있다(E-06 계열). 취소는 실패가 아니므로 `DEAD_LETTER_AT` 은 찍지 않는다. **V154 은 COMMENT 만 갱신한다** — 이 컬럼엔 CHECK 제약이 없고 VARCHAR(20) 이라 DDL 변경이 불필요하며, 유일하게 이 컬럼을 술어로 쓰던 `UK_LS_DATA_AUG_ACTVTN`(V143)은 V153 에서 DROP 됐다. — **해상도 변경 파생도 흡수**: `AUG_TYPE_CD='RESL_1080P/720P/480P'` 판별자 + 부분 유니크 인덱스 `UK_LS_DATA_AUG_RESL (SRC_SN, AUG_TYPE_CD) WHERE AUG_TYPE_CD LIKE 'RESL_%'` (V124). **`PROMPT_CN`**(VARCHAR(4000), V153) = 외부로 전송한 생성 조건 prompt JSON 원문 — 표준용어 등록 복합용어 `프롬프트내용/PROMPT_CN` + 표준도메인 `내용V4000`. ⚠ 같은 CSV 676행에 `프롬프트/PROMPT`(정의 "생성형AI 입력 프롬프트 텍스트", V4000)도 있으나 **PROMPT_CN 유지 확정(2026-07-31)** — ①PROMPT_CN 만 표준도메인(`내용V4000`)에 연결돼 물리명·타입·크기 3축을 모두 충족하고(676행은 사업도메인 칸이 빈 채 V/4000 직접 선언) ②저작도구 v1 DB 설계서가 이미 'AI 생성 프롬프트'를 `LS_DATA_SRC.PROMPT_CN` 으로 적재해 같은 의미에 물리명이 두 벌이 되는 것을 막으며 ③LS_* 관행(형식단어로 끝남)에 맞다. 재검토 완료 — 다시 열지 말 것(근거 원문은 V153 주석). V153 이전 요청·해상도 파생(RESL_*)은 NULL. ⚠ 활성 중복 방지 인덱스 `UK_LS_DATA_AUG_ACTVTN`(V143)은 **V153 에서 DROP** — 같은 (영상 × 종류) 재요청이 정상 동선으로 확정(2026-07-31)됐기 때문이며, `UK_LS_DATA_AUG_RESL` 은 별개 계약이라 유지된다. **하위 테이블**: 외부 위탁 1건이 100장 상한으로 분할되므로 `LS_DATA_AUG` 1행 : `LS_DATA_AUG_JOB` N행 : `LS_DATA_AUG_JOB_FILE` M행 (모두 `ON DELETE CASCADE`). **`NEW_RAW_SN`**(BIGINT NULL, V155, 표준용어 복합용어 `신규원시일련번호`) = 이 증강 요청 행이 만든 **파생 영상**(`LS_DATA_RAW.RAW_SN`) 매핑 — 구 `VMS_CLIP_ID` 시각 접미 역파싱을 대체(동시 콜백 파싱 오귀속 방지), 부분 인덱스 `IX_LS_DATA_AUG_NEW_RAW_SN`(WHERE NOT NULL). 백필 없음(V155 이전 요청은 NULL="그랜드퍼더링" — 등재 게이트·§14.3 프레임 비교쌍 매핑이 이 컬럼을 판정축으로 쓴다) | [14](14-augmentation.md) |
| `LS_DATA_AUG_JOB` (V140) | 증강 외부 위탁 작업 — 생성형 AI 명세서 v1.1 정합. PK `AUG_JOB_SN`(BIGINT), `DATA_AUG_SN`(BIGINT FK→`LS_DATA_AUG`), `JOB_SEQ`(INT, 분할 순서 1부터), `IDMP_KEY`(VARCHAR(128) **UNIQUE** `UK_LDAJ_IDMP_KEY` — 우리가 발급한 request_id. **웹훅 발급 게이트의 단일 진실원**으로 `LS_WEBHOOK_IDEMPOTENCY` 를 대체), `OTSD_JOB_ID`(VARCHAR(200) — 외부가 202 로 발급, 접수 전 NULL), `JOB_STTS_CD`(VARCHAR(20) RECEIVED/RUNNING/SUCCEEDED/FAILED/CANCELED — `AUG_PROC_STTS_CD` 검수축과 별개), `TOT_NOCS`(INT), `ERR_CD`(VARCHAR(50))/`ERR_MSG_CN`(VARCHAR(1000) — 위탁 실패를 조용히 삼키지 않기 위한 사유 기록). 인덱스 `IDX_LDAJ_AUG_SEQ (DATA_AUG_SN, JOB_SEQ)`·`IDX_LDAJ_OTSD_JOB_ID` | [14](14-augmentation.md) |
| `LS_DATA_AUG_JOB_FILE` (V141) | 증강 위탁 파일 매핑 — 위탁 순서↔프레임 대응 + 외부 산출 경로. PK `AUG_JOB_FILE_SN`(BIGINT), `AUG_JOB_SN`(BIGINT FK→`LS_DATA_AUG_JOB`), `FILE_SEQ`(INT = `input_files[].sequence`), `SRC_SN`(BIGINT — 위탁한 비식별 프레임), `RSLT_FILE_PATH_NM`(VARCHAR(500) = `results[].output_file_path`, 수신 전 NULL). UNIQUE `UK_LDAJF_JOB_FILE_SEQ (AUG_JOB_SN, FILE_SEQ)`, 인덱스 `IDX_LDAJF_SRC_SN`. **왜 필요한가**: 계약상 `results[]` 에 입력 식별자가 없어 순서로만 대응하므로, 위탁 시점 대응을 못박지 않으면 위탁~콜백 사이 프레임 증감이 조용히 어긋나 다른 프레임에 남의 증강본이 붙는다(무증상 오염). 산출 경로는 SUCCEEDED 콜백에 되붙이고, 건수 불일치 시 미적재 + job FAILED(fail-closed) | [14](14-augmentation.md) |
| `LS_DATA_AUG_RVW` (V25) / `LS_DATA_AUG_LBL_MAP` (V26) | 증강 검수 / 라벨 매핑 (`COORD_RECALC_YN`/`SCALE_X`/`SCALE_Y` — 증강·해상도 파생 공통 재사용) | [14](14-augmentation.md) |
| `LS_DATA_AUG_DSCD` (V156, 감사·재시도 컬럼 V157) | 증강 파생영상 **폐기 원장** — 반려 시 표식(`DSCD_DT`=유예 기산점), 복구(`RSTR_DT`/`RSTR_RSN`), 실삭제 클레임·완료(`DEL_PRCS_DT`/`DEL_DT`/`FILE_DEL_DT`), 파생 비디오 경로 비석(`VDO_FILE_PATH`). **FK 없음**(실삭제가 비석 자신을 지우면 감사·재시도 단서가 사라진다). **V157 보강 2종**: ①**폐기 감사 스냅샷** `AUG_TYPE_CD`(VARCHAR(20))·`PROMPT_CN`(VARCHAR(4000)) — `LS_DATA_AUG` 행이 실삭제로 사라지므로 "무엇을 왜 버렸는가"의 앞 절반(증강 종류·생성 조건)을 비석에 남긴다(백필 없음, 신규 표식부터). ②**파일 정리 수렴** `FILE_DEL_RTRY_NMTM`(INT NOT NULL DEFAULT 0, 표준용어 `RTRY_NMTM`)·`FILE_DEL_FAIL_DT`(TIMESTAMP)·`FILE_DEL_FAIL_RSN`(VARCHAR(4000)) — 파일 정리 시도 누적, 상한(`file-cleanup-max-attempts`) 초과 시 `FILE_DEL_FAIL_DT` 로 데드레터 종결해 재시도 큐(`IX_LS_DATA_AUG_DSCD_FILE_RTY`, `WHERE FILE_DEL_FAIL_DT IS NULL`)에서 뺀다. **재개는 전용 API 없이 운영자 수동 UPDATE** — `FILE_DEL_FAIL_DT`·`FILE_DEL_FAIL_RSN`·`FILE_DEL_RTRY_NMTM`(=0으로) **셋을 함께** 되돌려야 하며, `FILE_DEL_RTRY_NMTM` 을 빠뜨리면 이미 상한에 도달해 있어 다음 tick 첫 실패로 즉시 재종결된다(SQL 예시는 V157 마이그레이션 주석) | [14](14-augmentation.md) |
| ~~`LS_RESOLUTION_EXPORT`~~ · ~~`LS_RESOLUTION_LBL_MAP`~~ | **폐기(V125 백필 후 fail-closed DROP)** — 해상도 변경 저장모델을 `LS_DATA_AUG`+`LS_DATA_AUG_LBL_MAP`으로 통합(2026-07-22) | [14](14-augmentation.md) |
| `LS_DEIDENT_REPORT` (V21) / `LS_DEIDENT_PROC_LOG` (V29, `REQ_KND_CD` BATCH/REDEIDENT V68 도입·V83 rename REQ_KIND_CD→REQ_KND_CD) | 비식별 누락 신고 / 처리 이력(배치·검수완료재비식별 분기) | [08](08-deidentification.md) |

### 작업 · 상태 · 운영
| 테이블 | 용도 | 위키 |
|--------|------|------|
| `LS_RAW_DATA_ENROLLMENT` / `LS_RAW_DATA_STATUS` (V36) | 영상 등록 / 진행 상태 | [05](05-video-management.md) |
| `LS_TASK_ASSIGNMENT` / `LS_TASK_ASSIGN_HISTORY` (V36) | 작업 배정 / 재배정 이력 | [12](12-review-assignment.md) |
| `LS_TASK_EVENT_LOG` (V36) | 작업 이벤트 로그 | [12](12-review-assignment.md) |
| `LS_ACNT_USER` (V169) | 사용자 마스터 — **저작도구 소유**. 역할 클레임 시점에 관제가 브라우저 `localStorage` 로 인계한 표시 정보(`userId`·`userNm`)로 **자동등록·갱신**(원자 upsert). 구 관제 `MNG_ACCT_USER` 이관처. 인가 역할은 여기가 아니라 `LS_USER_ROLE` 이 단일 진실원 | [03](03-auth-roles.md) |
| `LS_USER_ROLE` (V75) | 저작도구 라벨링 역할 매핑 (USER_NO→ROLE_CD: REVIEWER/WORKER/PORTAL_USER) — 인가 역할 단일 진실원. 구 관제 권한 매핑 대체(역할 분리 2026-06, 구 테이블은 V165 로 삭제) | [03](03-auth-roles.md) |
| `LS_BATCH_PROC_LOG` (V12) | 배치 단계 로그 (STAGE_CD, RESP_PAYLOAD_CN) | [07](07-batch-pipeline.md) |
| `LS_BAT_RTY_WTNG` (V116) | 배치 실패 영상 재시도 대기 — **DB 영속화**(구 in-memory 큐 대체, 2노드 Active-Active 정합). PK `BAT_RTY_SN`, `RAW_SN` UNIQUE(영상 1건=1행), 컬럼(`RTY_NMTM`/`MAX_RTY_NMTM`/`STTS_CD`=PENDING/RETRYING/EXHAUSTED/`RTY_PRNMNT_DT`=재시도 예정 일시/`LAST_ERR_MSG_CN`)은 사업(program) 표준용어(배치=BAT·재시도=RTY·횟수=NMTM·예정=PRNMNT·대기=WTNG) 준거. 폴링은 조건부 원자 UPDATE(PENDING→RETRYING)로 동시 폴링 직렬화, 최초 등록은 `INSERT ... ON CONFLICT DO NOTHING`+FOR UPDATE 로 UK 경쟁 흡수, 최대 초과 시 EXHAUSTED 소진(삭제 아님, 이력 보존). **stale RETRYING 회수(Phase 9-C, B-ISSUE-83)** — 클레임 노드가 처리 중 죽으면 영구 RETRYING 으로 굳어 재시도가 무음 중단되므로, `MDFCN_DT`(=클레임 시각) 기준 임계(기본 180분·하한 30분 clamp)를 넘긴 행만 조건부 UPDATE 로 PENDING 복귀시킨다. 죽은 시도는 `RTY_NMTM+1` 로 계상하고 상한 도달분은 복귀 대신 EXHAUSTED 종결(무한 부활 금지) | [07](07-batch-pipeline.md) |
| `LS_SYSTEM_CONFIG` (V11) | 시스템 설정 (화이트리스트 key/value). 이벤트 필터 제외 대분류 `eventtype.excluded-class-codes`(JSON, 기본 `["08"]`, V161) 포함 — 아래 §관제 이벤트 타입 참조 | [10](10-labeling.md) |
| `LS_AUTH_WORK_LOCK` (V22, 동일영상 활성락 1건 partial unique index V69) | 비식별 재진행 중 잠금(동시 이중 위탁 차단) | [08](08-deidentification.md) |
| `LS_WEBHOOK_IDEMPOTENCY` (V39) | 웹훅 멱등성 + **VLM 위탁 상관키 원장**(request_id → 채널·RAW_SN). `STTS_CD` 값 3종 — `ISSUED`(발급, 제출 전 선커밋) / **`ACCEPTED`**(수락 응답 수신, 2026-07-30 추가 · **스키마 변경 없음**) / `PROCESSED`(콜백 처리 완료). ISSUED↔ACCEPTED 구분이 미결 회수 스위퍼의 **ACK 창 / 콜백 창** 분리 근거다 — 발급 게이트는 두 값을 동일 취급하므로 콜백 인증 동작은 불변 → [09 §9.2-3](09-vlm-timeseries.md) | [19](19-external-security-cvat.md) |
| `LS_WHK_SIGN_USE` (V131) | 웹훅 서명 사용 원장 — replay 방지용 **1회성 소비** 기록. PK `SIGN_HASH`(경로+X-Timestamp+X-Signature 의 SHA-256 hex, VARCHAR(64)), `WHK_PATH_NM`·`EXPD_DT`. 필터가 트랜잭션 밖에서 `INSERT ... ON CONFLICT DO NOTHING` + updateCount 로 판정(PG UNIQUE 위반이 tx 전체를 abort 시키는 25P02 회피). 표준용어 웹훅=WHK·서명=SIGN·해시=HASH·사용=USE·만료=EXPD | [19](19-external-security-cvat.md) |
| `LS_WHK_FAIL_NMTM` (V131) | 웹훅 인증 실패 횟수 — 2노드 공유 rate limit 집계. PK (`CALL_IP_ADDR` IP주소V45, `BGNG_DT` 분 단위 윈도우), `FAIL_NMTM`(수I11)·`EXPD_DT`. 앱은 1차 JVM-local 카운터로 즉시 차단하고 본 테이블은 2차 집계(공유 저장소 장애 시 fail-open). 표준용어 호출=CALL·주소=ADDR·시작=BGNG·실패=FAIL·횟수=NMTM | [19](19-external-security-cvat.md) |
| `LS_AUTHRT_GRANT_ATMPT` (V132) | 권한 자가부여(role-claim) 시도 횟수 — 2노드 공유 rate limit 집계. PK (`ATMPT_SE_CD` 코드V20 = ACCOUNT/GLOBAL, `ATMPT_IDNTFR` 식별자V36 = 요청자 sub 또는 'GLOBAL', `BGNG_DT` 분 단위 윈도우), `ATMPT_NMTM`(수I11)·`EXPD_DT`. 서비스 트랜잭션이 실패로 롤백돼도 카운터가 남도록 **REQUIRES_NEW** 로 기록. 공유 저장소 장애 시에도 JVM-local Caffeine 카운터가 최종 방어선(완전 fail-open 금지). 표준용어 권한=AUTHRT·부여=GRANT·시도=ATMPT·구분=SE·식별자=IDNTFR·횟수=NMTM | [19](19-external-security-cvat.md) |
| `LS_CONTROL_NOTIFY_FALLBACK` (V44, `SEND_RSLT_CD` 발송결과 컬럼 V77) / `LS_GITEA_FALLBACK_QUEUE` (V41) | 통지 재시도 큐 + 발송 결과 상태 관찰(`STTS_CD`=큐 처리 PENDING/RETRYING/SUCCEEDED/DEAD_LETTER, `SEND_RSLT_CD`=SUCCESS/FAILED — 즉시 성공도 SUCCEEDED+SUCCESS 터미널 행으로 적재) / Gitea 실패 재시도 | [15](15-control-notify.md)·[13](13-version-control.md) |
| `LS_MON_NOTI_ACML` (V144) | 관제 수정 통지 **디바운스 누적**(Phase 9-C) — 영상 1건의 수정을 윈도우로 모아 1회만 flush. 구 인메모리 윈도우는 2노드 Active-Active 에서 ①양 노드에 나뉜 축적이 각자 flush 돼 export 재생성·통지가 2회 나가고 ②노드가 flush 전에 죽으면 축적분이 유실됐다. PK `NOTI_ACML_SN`, `RAW_SN`+부분 유니크(`STTS_CD='PENDING'`)로 영상당 열린 윈도우 1개, `CHG_DTL_CN`(누적 변경 JSON — 라벨/메타 본문·PII 미포함)·`EXPORT_RPRCS_YN`(재생성 동반 OR 누적)·`REG_DT`(윈도우 개시=만료 기준)·`MDFCN_DT`(FLUSHING 행에서는 클레임 임차 시작). flush 는 PENDING→FLUSHING 조건부 UPDATE 로 한 노드만 클레임하고 발송 후 행 삭제, 클레임 노드가 죽으면 임차(기본 300초) 만료 후 재클레임. 물리명은 표준용어(관제=MON·알림=NOTI·누적=ACML·변경상세내용=CHG_DTL_CN·재처리=RPRCS) 준거 | [15](15-control-notify.md) |
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
| `V_COMPLETED_VIDEO` | 영상 메타 + 원본 경로 + 검수 완료 일시. **재구성(V101·V102)**: `LS_DATASET_VIDEO_META`(ACTIVE_YN='Y') 동결 스냅샷 기반 + 라이브 APPROVED 게이트. 기존 출력 컬럼 alias 보존(관제 무영향) + 신규 메타 18컬럼(cctv명·좌표·코덱·fps·해상도 등) 추가. **`EXPORT_PATH_NM`·`FRAME_CNT`(V114)**: 최신 export(`LS_DATASET_EXPORT`)를 `LEFT JOIN LATERAL`(LIMIT 1)로 끝에 append — 행 증식 0, 미export 영상은 두 값 null. **`EXPORT_STTS_CD`(V160)**: 조인 대상 상태를 `IN ('SUCCEEDED','PARTIAL')` 로 확장하고 부분 산출 식별용으로 상태 컬럼을 끝에 추가 — 통지가 나간 산출(PARTIAL 포함)은 반드시 뷰에서 보인다(E-ISSUE-81). `FAILED`/`PENDING` 은 계속 배제 |
| `V_COMPLETED_FRAME` | 프레임 페어 (`ORIGINAL_PATH`=원본, `DEIDENTIFIED_PATH`=비식별; 원천 `LS_DATA_SRC.DE_IDNTF_SRC_FILE_PATH_NM`) + **`DESCRIPTION`(V104, `FRM_EXPLN` 프레임설명, 하위호환 끝 추가)** |
| `V_COMPLETED_LABEL_CHANGE` (V114 신설 · V115 재정의) | 라벨 변경점 (`LS_DATA_LBL_HSTRY` 기반, **저장이벤트 단위** — `ADD_CNT`/`MDFCN_CNT`/`DEL_CNT` 종류별 건수 + `CHG_DTL_CN` diff JSON, APPROVED 게이트). V115의 `LS_DATA_LBL_HSTRY` 저장이벤트 재구조화(구 `LBL_SN`/`CHG_KIND_CD` 제거)에 맞춰 뷰를 재정의(관제 연동 계약 변경 — 협의 대상). 구 라벨 좌표·속성 본문 뷰(`V_COMPLETED_LABEL`·`V_COMPLETED_LABEL_ATTR`)는 V114에서 제거 — 라벨 내용은 검수 승인 export 폴더 JSON이 진실원(뷰 중복 노출 제거) |
| `V_COMPLETED_META` | 시계열 메타 (RVW_STTS_CD='APPROVED'만). V101에서 `video.*` 기술메타 6키 제외(통합 스냅샷 `V_COMPLETED_VIDEO`로 이관) — VLM/외부 시계열만 노출 |

> 관제서버는 `TASK_COMPLETED`/`TASK_MODIFIED` 수신 후 RAW_SN으로 4 View SELECT → 영상 1건=1 row UPSERT. 비식별 **영상** 경로는 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 적재값 사용(문자열 치환 도출 아님, View 미포함). 비식별 **프레임** 경로는 `V_COMPLETED_FRAME.DEIDENTIFIED_PATH`(=`LS_DATA_SRC.DE_IDNTF_SRC_FILE_PATH_NM`)에 직접 노출되며, 신규 추출은 원본 `{base}/frames/raw/{rawSn}`·비식별 `{base}/frames/deid/{rawSn}` 로 분기 저장돼 `STORAGE_RAW_PATH==STORAGE_DEIDENTIFIED_PATH`(=`/nas-storage`)여도 충돌하지 않는다. → [15](15-control-notify.md)

### 18.3.1 `LS_DATA_RAW` 참조 무결성 — 자식 FK + `ON DELETE CASCADE` (V146)

구 스키마는 `LS_DATA_RAW` 를 참조하는 FK 가 `LS_EVNT_ANNO` 단 1건뿐이라 영상 행이 사라져도 자식이 고아로 잔존했다(실측: `LS_MARKING` 고아 2행). V146 이 **자식 27개 테이블에 FK 를 신설**한다.

| 구분 | 대상 | 삭제 규칙 | 근거 |
|------|------|:---------:|------|
| 일반 자식 | `LS_DATA_SRC`·`LS_MARKING`·`LS_DATA_META`·`LS_DATA_META_REVIEW`·`LS_DATA_LBL_AI_INFO`·`LS_DEIDENT_PROC_LOG`·`LS_DEIDENT_REPORT`·`LS_BATCH_PROC_LOG`·`LS_BAT_RTY_WTNG`·`LS_AUTH_WORK_LOCK`·`LS_DATA_ISSUE`·`LS_DATA_AUG_RVW`·`LS_DATA_RAW_HSTRY`·`LS_LABEL_VERSION`·`LS_DATASET_EXPORT`·`LS_DATASET_VIDEO_META`·`LS_RAW_DATA_STATUS`·`LS_RAW_DATA_ENROLLMENT`·`LS_TASK_ASSIGNMENT`·`LS_TASK_ASSIGN_HISTORY`·`LS_TASK_EVENT_LOG`·`LS_CONTROL_NOTIFY_FALLBACK`·`LS_META_REPL_OUTBOX`·`LS_MON_NOTI_ACML`·`LS_PORTAL_USER_LABEL`·`LS_EVNT_ANNO`(기존 FK 를 NO ACTION→CASCADE 로 통일) | `CASCADE` | 영상 행이 사라지면 그 자식 데이터는 의미가 없다. RESTRICT 로 하면 실재 삭제 경로(`ResolutionPersistService.deleteFailedDerivativeRaw`·`TusUploadService` 완료 경합 롤백)가 깨진다 |
| 원장·세션 | `LS_WEBHOOK_IDEMPOTENCY.RAW_SN`·`LS_TUS_UPLOAD.RAW_SN` | `SET NULL` | 행이 사라지면 웹훅 재전송 방지/업로드 멱등 응답이 무너진다. 두 컬럼 모두 nullable 이라 참조만 끊는다 |
| ~~**제외**~~ → **편입(V162)** | `LS_CLIP_SCHEDULE_QUE.RAW_SN`(구 `MNG_CLIP_SCHEDULE_QUE`) | `CASCADE` | V146 은 이름만 보고 "관제서버 소유(MNG_*)" 로 오판해 제외했으나, 실제로는 저작도구가 V2 에서 직접 CREATE 한 **자체 소유 배치 큐**였다(관제 미참조). V162 가 `LS_` 로 개명하고 누락된 FK 를 보강 → 18.3.2 |
| **제외** | `LS_DATA_RAW.ORGNL_RAW_SN`·`LS_DATASET_VIDEO_META.ORGNL_RAW_SN` | — | 자식이 아니라 파생 계보(self-reference)/승인 시점 **동결** 값. 고아 자동 복구가 둘 다 위험(NULL 화 시 파생본이 "원본" 으로 승격돼 비식별 신고 거부·PII 정책이 역전, 삭제 시 검수 완료 파생 학습데이터 소실) — 별건 |

**고아 선행 정리 정책** (FK 는 고아가 있으면 생성 자체가 실패):
- 일반 자식의 고아는 삭제하되 테이블별 건수를 `RAISE NOTICE` 로 남긴다.
- **데이터마트 뷰 공급 테이블**(`LS_DATASET_VIDEO_META`·`LS_RAW_DATA_STATUS`·`LS_DATASET_EXPORT`·`LS_DEIDENT_PROC_LOG`·`LS_DATA_SRC`·`LS_DATA_META`·`LS_DATA_META_REVIEW`)에 고아가 있으면 **삭제하지 않고 마이그레이션을 중단**한다 — 관제가 보던 행이 예고 없이 사라지는 것을 막는다("검수 완료·통지 건 관제 접근 보장" 구속 제약, V143 선례).
- 한 테이블 고아가 **1,000건 초과**면 정상 운영의 잔여물이 아니라고 보고 중단한다.

> ⚠ 배포 시 `ADD CONSTRAINT` 가 자식 테이블에 SHARE ROW EXCLUSIVE 를 잡고 전량 검증 스캔을 한다 — 2노드 Active-Active 롤링 배포 중 짧은 쓰기 차단이 발생할 수 있다(읽기 무영향).
>
> **남은 갭(후속)**: 2단계 이하(`LS_DATA_SRC`→`LS_DATA_LBL`, `LS_DATA_LBL`→`LS_DATA_AUG_LBL_MAP` 등)에는 여전히 FK 가 없다. 영상 삭제가 프레임을 CASCADE 로 지우면 그 프레임의 라벨은 고아로 남는다. 실 삭제 경로 두 곳은 모두 "프레임 0건" 가드가 있어 현재는 도달하지 않지만, 별도 이슈로 다뤄야 한다.
>
> **Phase 7 갱신**: 폐기 유예 만료 실삭제는 프레임이 있는 파생영상을 지우므로 이 갭에 <b>실제로 도달한다</b>. 그래서 `AugmentDiscardPurgeTxService.DELETE_ORDER` 가 FK 없는 자식(`LS_DATA_LBL_ATTR_VAL`·`LS_DATA_AUG_LBL_MAP`·`LS_DATA_LBL_HSTRY`·`LS_DATA_LBL`)을 RAW 삭제 <b>전에</b> 명시적으로 지운다. FK 신설이 아니라 삭제 경로에서의 보완이므로 위 갭 자체는 그대로 남아 있다.

### 18.3.2 배치 큐 소유 접두 정정 — `MNG_CLIP_SCHEDULE_QUE` → `LS_CLIP_SCHEDULE_QUE` (V162)

라벨링 배치 파이프라인 입구인 이 큐는 이름만 `MNG_` 접두라 **관제서버 소유(공유·읽기전용)로 오인**됐으나, 실제로는 저작도구가 `V2__phase3_video_queue_quartz.sql` 에서 직접 `CREATE TABLE` 한 자체 소유 테이블이다(Flyway `@Primary` 단일 데이터소스가 `klid_at` 스키마 전체를 관리한다 — 외부 미러링이 아니다).

그 오인이 실제 결함을 냈다 — V146(18.3.1)이 "MNG_* 는 관제팀 선승인 필요" 로 판단해 이 테이블만 FK 대상에서 제외했고, 그 결과 `RAW_SN` 에 참조무결성 보호가 없어 영상 원본 삭제 시 큐 행이 고아로 잔존했다.

| 항목 | 변경 |
|------|------|
| 테이블 | `MNG_CLIP_SCHEDULE_QUE` → `LS_CLIP_SCHEDULE_QUE` (RENAME — 데이터 유실 없음) |
| 인덱스 | `IX_MNG_CLIP_SCHEDULE_QUE_STATUS`·`_RAW` → `IX_LS_CLIP_SCHEDULE_QUE_STATUS`·`_RAW`. PK 제약·IDENTITY 시퀀스도 함께 개명(PostgreSQL 은 테이블 RENAME 시 부속 객체명을 따라 바꾸지 않아 남겨두면 오인이 재발) |
| FK | `FK_LS_CLIP_SCHEDULE_QUE_RAW (RAW_SN) → LS_DATA_RAW (RAW_SN) ON DELETE CASCADE` — V146 의 배치/파이프라인 자식과 동일 정책 |
| 엔티티 | `MngClipScheduleQue`/`MngClipScheduleQueRepository` → `LsClipScheduleQue`/`LsClipScheduleQueRepository` (동작·시그니처 무변경) |

> **고아 정책은 V146 과 다르다** — 이 테이블은 처리 대기/진행 중 작업이 담긴 **작업 큐**라 조용한 삭제가 배치 유실로 이어진다. 따라서 고아 발견 시 자동 삭제하지 않고 `RAISE EXCEPTION` 으로 마이그레이션을 **중단**한다(운영자 확인 후 정리·재기동).
>
> 관제 영향 없음 — 관제서버는 이 테이블을 읽지 않으며 데이터마트 뷰(`V_COMPLETED_*`)에도 공급하지 않는다. 회귀 가드: `LsClipScheduleQueFkIT`.

## 18.4 ~~관제서버 소유 MNG_*~~ → **전량 제거 완료 (2026-08-04)**

> ★ **현재 관제 소유 공유 테이블은 0개다.** 아래 목록·서술은 **제거 이전 상태의 이력**이며, 각 항목이 어떤 마이그레이션으로 사라졌는지는 이 절의 정정 문구를 따른다. (구 목록 7개 = `MNG_ACCT_USER`(V169) · `MNG_CLIP_MASTER`·`MNG_CLIP_EVNT_LST`·`MNG_RESOURCE_CCTV`·`MNG_EX_LOCAL_GOV`(V167) · `MNG_EX_EVNT_TYPE`·`MNG_EX_EVNT_TYPE_MAP`(V168). 앞서 `MNG_CLIP_SCHEDULE_QUE`(V162 개명)·`MNG_ACCT_AUTHRT`·`MNG_ACCT_USER_AUTHRT`(V165 DROP) 포함 **총 9종**.) 회귀 가드: `MngAcctUserTableRemovalTest`(`MNG_` 접두 실행 참조 0). ⚠ **18.4.1 은 2026-08-05 에 SoT·`ddl-auto=validate` 서술을 정정 완료**(V168 반영)했고, 같은 날 `LS_EVNT_TYPE`/`LS_EVNT_CTGRY` 를 **§18.2 인벤토리(「이벤트유형 마스터」)에 등재 완료**했다.

`MNG_ACCT_USER`, `MNG_CLIP_MASTER`, `MNG_CLIP_EVNT_LST`, `MNG_RESOURCE_CCTV`, `MNG_EX_EVNT_TYPE`, `MNG_EX_EVNT_TYPE_MAP`, `MNG_EX_LOCAL_GOV`.

> **⚠ 목록 누락 정정 (2026-08-04)**: 이 목록은 그동안 `MNG_CLIP_EVNT_LST`(V63 생성, `TrainingVideoIngestTx`·`MngClipEvntLstRepository` 등에서 실사용 중)를 빠뜨린 채 개수를 세고 있었다. 그 결과 아래 V162·V165 정정 문구의 산식이 실제보다 1 적었고, 같은 절 아래 "ERD-024 정본 매핑" 문단이 이 테이블을 언급하는 것과 **자기모순**이었다. 개수를 실측 기준으로 재정정한다(`docs/관제팀-공유테이블-변경금지-가이드.md` 의 산식과 이제 일치).

> **2026-08 정정**: 과거 이 목록에 있던 `MNG_CLIP_SCHEDULE_QUE`(배치 큐)는 관제 소유가 아니라 **저작도구 자체 소유**임이 확인되어 `LS_CLIP_SCHEDULE_QUE` 로 개명됐다(V162, 18.3.2). 관제 공유 테이블은 10개가 아니라 **9개**다.

> **⚠ 배포 전 확인 필요(후속, 2026-07-31 — V147)**: `MNG_CLIP_MASTER`(16컬럼)·`MNG_CLIP_EVNT_LST`(12컬럼)를 **ERD-024 정본 기준으로 전량 매핑**했다. 매핑이 늘어난 만큼 `ddl-auto=validate` 보호 대상도 늘어, **실 관제 스키마와 컬럼명·타입이 하나라도 다르면 stg/prd 기동이 실패**한다. 현재 이 매핑은 **ERD-024(2026-06-10 조회) 신뢰에 전적으로 의존**하므로, **배포 전 dev 환경에서 실 관제 DB 를 대상으로 1회 검증**이 필요하다(로컬/테스트는 자체 stub 이라 드리프트를 잡지 못한다). 관제팀 공유 문서: `docs/관제팀-공유테이블-변경금지-가이드.md`.

> **~~역할 분리 (2026-06)~~ → 폐기(V169)**: 구 서술은 "`MNG_ACCT_USER` 는 관제가 채우고 저작도구는 READ 전용(`@Immutable`)" 이었고 `MngAcctWriteGuardTest` 가 쓰기 0건을 지켰다. **실측 결과 아무도 채우지 않았다** — 관제 2차 실DB 에 `MNG_` 접두 테이블이 0개이고 저작도구 쓰기 경로도 0이라, 신규 사용자는 DBA 가 손으로 넣기 전까지 역할 클레임이 404 로 막혔다.

> **사용자 마스터 이관 + 자동등록 (2026-08-04, V169)**: `MNG_ACCT_USER` 를 **저작도구 소유 `LS_ACNT_USER`** 로 이관·DROP 하고, 채우는 주체를 **역할 클레임 시점의 자동등록**(관제 `localStorage` 인계값 `userId`·`userNm`, 원자 upsert)으로 바꿨다. ★이것으로 **관제 `MNG_*` 9종 제거가 완료**됐다(V162·V165·V167·V168·V169 — 남은 관제 소유 공유 테이블 **0개**). 물리명은 표준용어로 정정했다(`ACCT`→`ACNT` 계정, `USER_EMAIL`→`USER_EML_ADDR` — `EMAIL` 은 표준단어 `EML` 의 금칙어, `UPD_DT`→`MDFCN_DT`). 회귀 가드: `MngAcctUserTableRemovalTest`(구 쓰기 금지 가드를 **`MNG_` 접두 참조 0** 으로 강화 승계). ⚠ 구버전 jar 롤백용 재생성 DDL·데이터 복원·Flyway 이력 정리 절차는 **V169 파일 주석**에 보존돼 있다.

> **죽은 권한 테이블 2종 제거 (2026-08, V165)**: 위 목록에 있던 `MNG_ACCT_AUTHRT`·`MNG_ACCT_USER_AUTHRT`는 역할 축이 `LS_USER_ROLE`(V75)로 이관된 뒤 **런타임 참조가 0**이 됐는데도 엔티티·시드만 잔존했다(dev 실DB 3행/5행이 전부 dev-seed 시드값 — 외부 유입 0). V165 가 두 테이블을 DROP 하고 엔티티(`MngAcctAuthrt`)·dev 시드·테스트 픽스처를 함께 제거했다. 관제 공유 테이블은 9개가 아니라 **7개**다. 회귀 가드: `DeadAcctAuthrtTableRemovalTest`. ⚠ 구버전 jar 롤백 시 `ddl-auto=validate` 기동 실패를 막는 **재생성 DDL·Flyway 이력 정리 절차는 V165 파일 주석**에 보존돼 있다.

### 18.4.1 이벤트 타입 진실원 — `LS_EVNT_TYPE` / `LS_EVNT_CTGRY` (저작도구 소유, V168)

> ⚠ **정정(2026-08-05)**: 이 소절 제목·첫 문단은 위 18.4 가 이미 폐기라고 명시한 "관제 소유 `MNG_EX_EVNT_TYPE`(+`MNG_EX_EVNT_TYPE_MAP`) 가 SoT" 서술을 그대로 들고 있던 **드리프트**였다(같은 절 아래 "표시명 그룹" 문단은 이미 V168 이후 사실을 서술하고 있어 한 절 안에서 자기모순이었다). V168(2026-08-04)이 이 2종을 DROP 하고 **저작도구 소유** `LS_EVNT_TYPE`(유형)·`LS_EVNT_CTGRY`(카테고리, 표시명 폴백 원천)를 신설했다 — 아래 본문으로 정정한다. **두 테이블은 §18.2(LS_* 핵심 테이블)의 「이벤트유형 마스터」 소절에 등재 완료**(2026-08-05)했다.

영상 이벤트 타입의 **단일 진실원(SoT)** 은 저작도구 소유 `LS_EVNT_TYPE`(+카테고리명 원천 `LS_EVNT_CTGRY`)다. 관제 인입 소비 시점(`TrainingVideoIngestTx`)에 미등록 유형코드가 **자동 등록**되고(원자 upsert), 이미 등록된 유형은 인입값으로 덮어쓰지 않는다(운영자 정정 보호). 관제 마스터(`MNG_EX_EVNT_TYPE`/`_MAP`)는 더 이상 조회하지 않는다 — READ 연동·`@Immutable`·`ddl-auto=validate` 구조는 **폐기**됐다. (구 `EvntType` enum 6종/`EVT_*` 하드코딩은 이전에 **폐기·삭제**됨 — 관제 실코드와 어긋났던 잔재.)

- **코드 체계**: `EVNT_TYPE_CD` = `EV` + 대분류(2) + 카테고리(2) + 상세(2), 예 `EV02000201`. 3단계 계층(`EVNT_CLS_CD` 대분류 / `EVNT_CTGRY_CD` 카테고리 / 상세). `CLCT_YN`=수집여부.
- **라벨**: 유형별 이름(`LS_EVNT_TYPE.EVNT_NM`)이 비어 있으면 **카테고리명**(`LS_EVNT_CTGRY.EVNT_CTGRY_NM`, `EVNT_CLSF_CD+EVNT_CTGRY_CD` 조인)으로 폴백한다 — 관제 마스터에는 애초에 유형별 이름이 없었고 사람이 읽는 이름은 카테고리 레벨에만 있었다(구 이관 전 `MNG_EX_EVNT_TYPE_MAP` 의 `CD_TYPE='02'` 10건이 그 원천이었다).
- **저작도구 노출 정책**: 라벨 해석(매핑)은 **전체 코드** 대상(들어오는 어떤 EV-코드든 한글명), 필터/선택 드롭다운은 **`CLCT_YN='Y'` 중 제외 대분류가 아닌 유형**을 **표시명으로 묶어** 노출한다. 구현: `eventtype/service/EventTypeService`(`groupIndex`/`filterOptions`/`codeLabelMap`/`filterKeyOf`, Caffeine 캐시) + `GET /api/v1/event-types`·`/labels`.
  - **표시명 그룹 (2026-08-05, R3~R5)**: 축은 유형(V168)이지만 관제가 유형별 이름(`EVNT_NM`)을 아직 보내지 않아 표시명이 **카테고리명으로 폴백**되면서 드롭다운에 같은 이름이 여러 번 떴다(침수 3·교통사고 3·화재 2). 그래서 **표시명이 같은 유형들을 옵션 1건으로 접는다** — `categoryKey`=그룹 **대표코드**(그룹 내 최소 유형코드), `memberCodes`=그룹 전체 코드. **파라미터 값은 여전히 코드**이며(표시명 문자열을 필터 파라미터로 올리지 않는다), **비대표 코드로 들어온 기존 북마크**(`?eventTypeCd=EV01000103`)도 `codesForFilterKey` 가 그룹 전체로 해석한다. 프리셋도 `filterKeyOf` 가 대표코드로 접어 **그룹 전체에 적용**된다(V168 이 "의도된 동작 축소"로 남겨둔 부분의 복구). **영구 병합이 아니다** — 관제가 이름을 보내거나 운영자가 표시명을 지정하면 그 유형만 자기 이름을 얻어 그룹이 **자동으로 쪼개진다**. 응답 스키마(`EventTypeResponse` 필드명·타입)는 불변.
  - **제외 대분류는 설정값**(구 소스 상수 `IGNORE_CLASS_CD="08"` 폐기): `LS_SYSTEM_CONFIG` `eventtype.excluded-class-codes`(JSON 문자열 배열, 기본 `["08"]`=배회, V161 시드)를 REVIEWER 가 시스템 설정 화면(`PUT /v1/manage/configs/{key}`)에서 배포 없이 편집한다. 원소는 2자리 숫자·최대 20개로 검증하며, 조회 실패 시 기본값 `08` 로 폴백(fail-safe). 값 변경 시 `eventType` 캐시가 함께 무효화되어 즉시 반영된다.
  - **기존 프리셋 호환**: 어떤 카테고리가 나중에 제외되어도 그 카테고리에 매핑된 **기존 프리셋의 이름·설명·라벨 편집은 계속 가능**하다(`PresetService.update` 는 `EVNT_TYPE_CD` 가 실제로 바뀔 때만 유효성 검증). 신규 생성·다른 제외 카테고리로의 재매핑은 기존대로 400 차단.
- **저장 단위**: 영상 `LS_DATA_RAW.EVNT_TYPE_CD`=상세 EV-코드(관제 적재값), 프리셋 `LS_LABEL_PRESET.EVNT_TYPE_CD`=**표시명 그룹 대표코드**(필드명은 하위호환으로 `categoryKey` 그대로 — 값의 입도만 카테고리→유형→그룹 대표코드로 바뀌었다). 프리셋 매칭은 영상 EV-코드를 `EventTypeService.filterKeyOf`로 대표코드로 접어 조회한다(`PresetLabelLookupService.togglesFor`, 회귀 가드 `PresetLabelLookupGroupMatchingIT`).
- **마이그레이션**: 구 `EVT_*` 잔존 데이터는 V72(`V72__migrate_preset_evnt_type_to_category.sql`)가 프리셋→categoryKey, 영상→대표 EV-코드로 정정(멱등·운영 no-op). V168(`V168__create_ls_evnt_type_and_drop_mng_masters.sql`)이 프리셋 축을 카테고리키(6자리)→**그 카테고리의 최소 EV-코드**로 재정정했다(의도된 동작 축소 — 구 카테고리 프리셋은 소속 상세코드 전부에 적용됐으나 전환 후엔 대표 코드 1개에만 적용. 축소된 유형은 필터 드롭다운에 각자 옵션으로 노출돼 REVIEWER 가 화면에서 프리셋을 추가해 복구할 수 있다). 관제 신규 코드 추가는 관제가 관리(저작도구 마이그레이션 없음, 인입 소비 시점 자동등록).
- `LS_EVNT_TYPE`/`LS_EVNT_CTGRY` 는 이제 **저작도구 소유** 객체라 ERD-024(관제 공유 클립 ERD, MNG_* 전용)의 관할이 아니다. §18.2 인벤토리 등재와 **LogiCraft 등재 모두 완료**(2026-08-05) — 신규 **`ERD-025` 「이벤트유형 마스터 ERD (고도화, PostgreSQL)」**(DOMAIN-003 소속, 근거 `ADR-042`)에 V168 DDL 과 1:1 등재했다. 구 조달처가 관제 공유 마스터라 **D8/D9 산출물에서 통째로 빠져 있었는데**(MNG_* 는 산출물 비대상 + 대체 테이블은 미등재), 이제 다음 `/cc-doc-gen` 재생성 시 D8/D9 에 포함된다. ⚠ **현재 저장소의 D8/D9 파일에는 아직 반영돼 있지 않다** — 재생성 전까지는 위키(§18.2)가 이 2종의 유일한 문서 원천이다.

## 18.5 Quartz

`QRTZ_*` (JobStore, PostgreSQLDelegate, BYTEA) — 배치 스케줄 상태. → [07](07-batch-pipeline.md).

> 상세 컬럼·ERD는 D8/D9 참고 → [19 설계 문서 카탈로그](19-external-security-cvat.md#설계-문서-카탈로그).
