# 18. 데이터베이스

> 출처: D8 엔티티관계모형설계서, D9 데이터베이스설계서, CLAUDE.md(DB 정책·View), 코드(`backend/src/main/resources/db/migration/` V1~V13, 2026-08-19 실측)
> 관련: 각 기능 페이지 · [19 외부 시스템](19-external-security-cvat.md)
>
> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — 헤더의 *"코드(`db/migration/` V0~V56)"* 는 사실과 다르다. 2026-08-13 스쿼시로 그 V0~V185 범위는 전부 아카이브로 옮겨졌고 현재 실행 대상은 `V1__baseline.sql` + `V2`~`V13`(13개 파일)뿐이다. 근거: `V1__baseline.sql`(`backend/src/main/resources/db/migration/`).

## 18.1 기본 정보

| 항목 | 내용 |
|------|------|
| DBMS | **PostgreSQL** |
| 스키마 | `klid_at` |
| 마이그레이션 | **Flyway** — 2026-08-13 스쿼시로 누적 180개(V0~V185, 결번 존재)를 `V1__baseline.sql` 로 접었다. **현재 `V1`~`V13`이며 신규는 `V14`부터**(2026-08-19 실측 — 구 "V1~V9·신규는 V10부터" 서술은 V10~V13 추가로 낡았다). 형상: 저작도구 소유 `LS_*` **57개** + 데이터마트 뷰 4 + Quartz `QRTZ_*` 11 |
| 소유 정책 | 저작도구 **LS_*** 자체 소유(자체 Flyway), 관제 **MNG_*** **0개**(2026-08-04 전량 제거 — 18.4), Quartz `QRTZ_*` |
| DDL | PostgreSQL 표준 문법 (MariaDB 문법 금지), `ddl-auto=validate` 고정 |

> **MNG_* 공유 테이블 변경 시 관제서버팀 선승인 필수.** 엔티티 수정 시 Flyway 마이그레이션 동반.

> ⚠ **한글 서술 안의 버전번호 두 갈래 — 혼동 주의.** 이 문서 곳곳의 테이블·컬럼 설명에 붙은 `(V2)`·`(V13)`·`(V56)`·`(V90·V91)` 같은 표기는 **2026-08-13 스쿼시 이전(구 V0~V185 체계)의 이력 표기**이며, 그 파일들은 지금 `V1__baseline.sql` 안에 전부 접혀 들어가 있다(원문은 `backend/src/test/resources/db-archive/migration/` 에 180개 보존, Flyway 가 읽지 않는 경로라 미실행). 반면 §18.3.3~18.3.6 및 아래 「신규 마이그레이션(V10~V13)」 절처럼 **날짜(2026-08-1x)를 동반한 `V5`~`V13` 서술은 스쿼시 이후 실제로 실행되는 새 파일**을 가리킨다. 같은 숫자(예 `V11`·`V12`·`V13`)가 옛 체계와 새 체계에 각각 다른 의미로 등장하므로, **날짜가 붙어 있으면 신규, 없으면 스쿼시 이전 이력**으로 읽는다.

### 신규 마이그레이션 (V10~V13, 스쿼시 이후 · 2026-08-19 실측)

§18.3.3~18.3.6 이 이미 다룬 V5·V7·V8·V9 에 이어, 다음 4개가 뒤따라 추가됐다(각 대상 테이블 행에도 교차 표기):

| 버전 | 파일명 | 요지 |
|---|---|---|
| V10 | `V10__add_ls_issue_comment_issue_fk.sql` | `LS_ISSUE_COMMENT.DATA_ISSUE_SN` → `LS_DATA_ISSUE.DATA_ISSUE_SN` FK 신설(`ON DELETE RESTRICT`). 설계(ERD-023)엔 있었으나 구현에 빠져 있던 참조 무결성 — 부모 이슈가 CASCADE 로 사라져도 댓글이 고아로 남던 것을 막는다. 적용 전 기존 고아 댓글을 먼저 삭제(NOTICE 로 건수만 기록, 본문은 미기록) |
| V11 | `V11__seed_portal_retention_config.sql` | 포털 보존기간 설정 3키 시드(`LS_SYSTEM_CONFIG`, `ON CONFLICT DO NOTHING`) — `portal.datamart.retention-days`(7일)·`portal.upload.retention-days`(7일)·`portal.upload.failed-retention-days`(1일). 이 값을 읽는 삭제 배치는 폴백하지 않으므로(파괴적 기능 fail-open 금지) 시드가 없으면 배치가 죽은 채 배포된다 → [16 포털](16-portal.md) |
| V12 | `V12__add_ls_acnt_user_last_lgn_dt.sql` | `LS_ACNT_USER.LAST_LGN_DT` 신설 — §18.4 하단 「최종로그인일시 신설」에 상세 |
| V13 | `V13__drop_yolo_imgsz_config.sql` | `LS_SYSTEM_CONFIG` 의 `YOLO_IMGSZ`(추론 입력 해상도) 설정 키 폐기 — 조정해도 ai-server 추론에 반영되지 않던 죽은 설정면. 화이트리스트에서 먼저 뺐고 이 마이그레이션은 남은 시드 행을 지운다 → [10 라벨링](10-labeling.md) §관제 이벤트 타입 인접 「AI 탐지 추론 파라미터」 |

> `LS_ISSUE_COMMENT` 는 §18.2 표에 별도 행이 없던 기존 갭(V10 이전부터, 이 정합 라운드에서 §18.2 「작업·상태·운영」에 행을 신설해 메웠다)이었다. 존재는 §18.3.1 FK 표(line "일반 자식")와 gov-first rename 문단(`ISSUE_COMMENT_SN→CMNT_SN`)에서도 확인된다.

## 18.2 LS_* 핵심 테이블 (저작도구 소유)

### 영상 · 프레임 · 라벨
| 테이블 | 용도 | 위키 |
|--------|------|------|
| `LS_DATA_RAW` (V2) | 원본 영상 메타 (VMS_CLIP_ID, EVNT_TYPE_CD, DE_IDENT_YN, ORGNL_RAW_SN — V82 물리 rename, 구 PARENT_RAW_SN 폐지. V95에서 데이터마트 뷰 출력 컬럼도 ORGNL_RAW_SN 으로 통일돼 내부·외부 모두 ORGNL_RAW_SN). `DATA_STTS_CD`(배치 단계): `PENDING`→`MARKING_READY`(선두 비식별 성공)→`COMPLETED`(배치 완료). **촬영환경 수동 메타(V130, 요구 외 추가 2026-07-24)**: `WTHR_NM VARCHAR(20)`(날씨·명V20)·`DAY_NGT_CD VARCHAR(20)`(시간대·코드V20)·`SESN_CD VARCHAR(20)`(계절·코드V20) — 전부 NULL 허용(**NULL=미입력→동결·export 모두 null(미상). 촬영일시 추정 안 함**, E-ISSUE-42 2026-07-29. 구 "export가 파생 폴백" 폐기 — 파생 폴백은 화면 프리필 조회에만 남음). 라벨링 메타탭 촬영환경 패널이 수동 편집, 검수 승인 export(NiaVideo weather/time_of_day/season)에 우선 반영. **개인정보 수동 메타(V163, 2026-08-03)**: `ANONY_INCL_YN CHAR(1)`·`PSDO_INCL_YN CHAR(1)`·`PRVC_INCL_YN CHAR(1)` — 여부C1 표준, **★2026-08-04 부터 INSERT 시점에 실제 값(`Y`/`N`/`N`)을 적재**한다(`@Builder` 생성자 — 두 엔티티에 `@DynamicInsert` 가 없어 Hibernate 가 모든 컬럼을 명시 INSERT 하므로 **DB DEFAULT 는 주 적재 경로에 적용될 수 없다**). NULL 은 이 변경 **이전에 적재된 레거시 행**에만 남으며 조회 시 비식별 기본상수로 프리필된다. 라벨링 메타탭 **개인정보(영상)** 패널이 영상 단위 수동 편집(`GET/PUT /v1/videos/{rawSn}/privacy-meta`), export **video 블록의 `deid` 축** 원천이다. ⚠ **`orgnl` 축은 이 컬럼이 아니라 관제 인입값 `LS_DATA_INGEST.*_INCL_YN`(V166 신설·V170 fail-closed DEFAULT `N`/`N`/`Y`)** 이다 — 관제 미송신 필드는 `null` 유지(지어내지 않음). **구 서술 "`orgnl` 은 3필드 모두 null"(2026-08-03)은 폐기**됐다([24 §24.3.3](24-dataset-export.md) · [CLAUDE.md](../../CLAUDE.md) 「export 개인정보 3필드 정책」). 동결 스냅샷 컬럼 없음(소비자가 export 하나뿐) — 승인 후 수정은 `TaskModifiedEvent(exportRegenerated=true)` + 콘텐츠 해시 편입으로 동기화 | [05](05-video-management.md)·[24](24-dataset-export.md) |
| `LS_DATA_INGEST` (V147, **관제 계약 정합 V185**) | **관제 수신 원장** — 관제서버가 우리 코드를 거치지 않고 **직접 INSERT** 한다(저작도구 쓰기 통로는 내부 업로드 1곳뿐이며 `LsDataIngestWriteGuardTest` 가 기계적으로 고정). 값은 불변이라 동결 스냅샷에 복사하지 않고 **조회 시점 LATERAL 조인**으로 조달한다(설계 D1, 단일 진실원 `IngestSourceLink`). **개인정보 원천 축** `ANONY/PSDO/PRVC_INCL_YN`(V166 신설 · **V170 fail-closed DB DEFAULT `N`/`N`/`Y`** — 관제가 직접 INSERT 하므로 애플리케이션 팩토리가 아니라 DB DEFAULT 가 유일한 수단). **검증이벤트유형 `VRFC_EVNT_TYPE_CD VARCHAR(20)`(V176 신설, NULL 허용)** — 외부 시계열 위탁 **묘사 축(`POST /v1/videovlm-klid/describe`)의 `event_type`** 조달원(추가 질문 축 `custom` 은 이 값을 싣지 않는다). 저작도구는 **자체 매핑표를 두지 않고** 이 값을 그대로 쓴다(값을 지어내지 않음). 미수신이면 **작업자가 마킹 화면에서 고른 값**(`LS_MARKING.VRFC_EVNT_TYPE_CD`, V35)이 2순위 조달값이고 그것도 없으면 `null` 을 그대로 실어 보낸다. ⚠ **구 서술 폐기** — *"외부 VLM **`verify`** 의 `event_type`(**6종 enum**) 조달원 · **미수신·미허용이면 위탁을 건너뛰고** `LS_BATCH_PROC_LOG` 에 SKIPPED 를 남긴다"* 는 **세 가지가 모두 사실과 다르다**: ①판정 창구 `verify` 는 연동하지 않는다(2026-08-24) ②6종은 허용목록이 아니라 **프리셋**이고 사업자 지원값은 **7종**이다(`smoke` 포함 — 2026-09-07 실측) ③**사전 차단은 2026-08-06 에 폐지**돼 그대로 위탁하며 판정의 단일 진실원은 사업자 응답이다. ⚠ **CHECK 제약을 걸지 않는다** — 걸면 우리가 모르는 값 하나가 관제 인입 INSERT 전체를 실패시킨다. 검증은 ①우리 쓰기 통로 400(**형식만** — 소문자·숫자·밑줄 + 20자) ②소비 시점 정규화 2단. **V16(2026-08-24 관제 확정)** — ①**`BIT` 의미 재정의**: 색심도(`24bit`)가 아니라 **비트레이트(bps 정수, 예 `2050627`)**. 관제 실측값이 bps 정수임을 관제가 재확인했다(물리명·타입 `varchar(20)` 유지, 의미만 정정). 저작도구는 이 값을 `video.bit_rate` 의 **1차 소스**로 쓰고 없거나 파싱 불가일 때만 ffprobe 로 폴백한다 ②**`THMB_FILE_PATH_NM VARCHAR(500)`(썸네일파일경로명) 신설** — 관제 pass-through. `V_COMPLETED_VIDEO` 로 노출해 관제가 **완료 조회 뷰 하나로** 패키징 조달한다(통지 페이로드에는 싣지 않는다 — 통지는 메타만). ⚠ **구 서술 폐기(2026-08-25 · V17)** — 이 컬럼은 **제거됐다.** 뷰의 `THMB_FILE_PATH_NM` 은 이제 저작도구가 보유한 **비식별 첫 프레임의 절대경로**(`LS_DATA_SRC.DE_IDNTF_SRC_FILE_PATH_NM` · 그 영상의 `FRM_NO` 최소 · 폐기 아님 · 경로 비어있지 않음 · **비식별 경로가 원본 경로와 같지 않음**(`V_COMPLETED_FRAME` 의 비식별 경로 불변식 게이트와 동치 — 그 형태는 비식별이 실제로 이뤄지지 않은 것이라 두 뷰가 함께 걸러낸다) 중 1건, `varchar(1000)`)를 내보내며 관제 인입값을 통과시키지 않는다. 근거는 구 방식이 저작도구가 통제할 수 없는 전제 셋에 기댔다는 것이다 — ①관제가 보내야만 값이 존재하고 ②그 파일을 저작도구가 소유하지 않으며 ③원천 영상에서 뽑은 것이라 **마스킹 전 화면일 수 있다**(산출물은 전부 비식별 기준인데 대표 이미지만 원천 기준이면 기준이 갈린다). 프레임이 없으면 NULL 이고 **원본 프레임 경로로 폴백하지 않는다**. 파생영상은 부모 상속이 아니라 **자기 프레임**에서 조달한다. 뷰 출력 타입이 바뀌므로 재정의는 `CREATE OR REPLACE` 가 아니라 **`DROP VIEW` + `CREATE VIEW`** 다. ★**관제 협의 대상** — 관제가 인입에 값을 넣고 있었다면 컬럼 제거 순간 **영상 인입 INSERT 전체가 실패**한다 ③**`OG_CD` 재추가** — 아래 V185 가 제거했으나 관제가 "실보유"로 재확인. ⚠ DROP 시 값이 소실돼 신규 인입부터 채워진다. export JSON `og_cd` 는 종전대로 null 고정(배선 없음). **V185(2026-08-12 관제 확정)** — `OG_CD`(기관코드) **컬럼 제거**(관제 회신 "현행 미사용 값, 공급 불가" — export JSON 의 `og_cd` 필드는 원래부터 null 고정이라 산출물 영향 없음. ⚠ **위 V16 이 되살렸다**) · `VMS_CCTV_ID` **NOT NULL 해제**(관제 회신 "CCTV 식별자가 없는 영상(수동 업로드 등)이 존재" — `LS_DATA_RAW.VMS_CCTV_ID` 도 함께 해제해야 인입→적재 NOT NULL 위반을 막는다. 화면 표시는 CCTV명→`VMS_CCTV_ID`→`영상 #{rawSn}` 3단 폴백으로 보강, [05](05-video-management.md) 참조) | [05](05-video-management.md)·[09](09-vlm-timeseries.md)·[24](24-dataset-export.md) |
| `LS_DATA_SRC` (V4) | 추출 프레임 (FRM_NO, 원본/비식별 경로, `FRM_EXPLN` 프레임설명 V103 — NIA image.description 작업자 수기). **개인정보 수동 메타(V130, 요구 외 추가 2026-07-24)**: `ANONY_INCL_YN CHAR(1)`(익명여부)·`PSDO_INCL_YN CHAR(1)`(가명여부)·`PRVC_INCL_YN CHAR(1)`(개인정보 포함여부) — 여부C1 표준. **★2026-08-04 부터 INSERT 시점에 실제 값(`Y`/`N`/`N`)을 팩토리에서 적재**하며, NULL 은 그 이전 레거시 행에만 남아 조회 시 기본상수로 프리필된다. ⚠ **이 3컬럼에 DB DEFAULT 를 걸지 않는다** — 한 벌인데 원천·비식별 두 축이 공유하므로 원천 기본값(`N`/`N`/`Y`)을 걸면 비식별의 "미입력"이 사라져 `deid.anonymity` 가 `Y`→`N` 으로 뒤집힌다. 라벨링 메타탭 개인정보 패널이 프레임 단위 수동 편집. **3필드 모두** export(NiaImage anonymity/pseudonymity/privacy_included)의 **`deid` 축**에 우선 반영된다. ⚠ **`orgnl` 축은 정책 상수 `N`/`N`/`Y`**(`ExportPrivacyPolicy.ORGNL_DEFAULT_*`) — 프레임 단위 원천 판정 **데이터가 존재하지 않기** 때문이며, 영상 단위(`video` 블록)가 관제 인입값을 쓰는 것과 **출처가 다르다**(값이 갈려도 모순이 아니라 입도가 다른 사실). **구 서술 "`orgnl` 산출물은 3필드 모두 null"(2026-08-03)은 폐기**. 영상 단위 대응 저장소는 `LS_DATA_RAW.*_INCL_YN`(V163)이며 **입도가 다른 별개 축**이다. ★**비식별 누락 신고는 이 3필드를 리셋하지 않고 보존**한다(2026-08-04 확정 — 사람이 입력한 판정도 라벨과 같은 작업 결과이므로 폐기하지 않고 resolve 후 이어서 진행. **구 "신고 시 두 축 NULL 리셋" 정책은 폐기**, `PRIVACY_META_RESET` 감사 타입은 과거 행 판독용으로만 존치). **`DSCD_YN CHAR(1)` NOT NULL DEFAULT 'N'(V179, 폐기여부·행안부 공통표준용어 그대로)** — 프레임 **논리 폐기**(R4·R5). 쓰기는 `FrameDiscardApplier.apply` 단일 지점, 감사는 `LS_TASK_EVNT_LOG`(`FRAME_DISCARD`/`FRAME_RESTORE`), 데이터마트 뷰(`V_COMPLETED_VIDEO`·`V_COMPLETED_FRAME`, V182)·산출물·학습데이터 JSON·**통계 집계**(검수완료 기준 라벨 수·이미지 수·이벤트 분포, 2026-08-17 반영 → [17 §17.0](17-statistics.md))에서 제외된다(`COALESCE(DSCD_YN,'N') <> 'Y'`, 애플리케이션·뷰 공통 기준). **한번이라도 검수 완료된 영상은 새로 폐기·복원할 수 없다**(400, 회차 적용은 예외) → [10 §10.7](10-labeling.md) | [07](07-batch-pipeline.md)·[10](10-labeling.md)·[24](24-dataset-export.md) |
| `LS_DATA_SRC_HSTRY` (V4) | 프레임 변경 이력 | |
| `LS_DATA_LBL` (V4) | 라벨 (좌표·트랙ID·LABEL_NM, 작업 중 임시저장). `LBL_TYPE_CD`: BBOX/POLYGON/SEGMENT/TRACK/**SKELETON**. `POINT_CN`(JSON): BBOX=`[[l,t],[r,b]]`·POLYGON=`[[x,y],…]` 2튜플. **SKELETON=COCO-17 휴먼 포즈 17×`[x,y,v]` 삼중값**(정확히 17개, v∈{0=미표기,1=비가시,2=가시}) — type-routed 직렬화로 2튜플 경로와 격리 | [10](10-labeling.md) |
| `LS_DATA_LBL_ATTR_VAL` (V33) | 라벨 속성값 | [10](10-labeling.md) |

### 라벨 마스터 · 프리셋 · 버전
| 테이블 | 용도 | 위키 |
|--------|------|------|
| `LS_LABEL` (V31, **CI 유일 V120·exact 제약 제거 V121**) | 라벨 마스터 (LBL_NM, COLR_VL, LBL_TYPE_CD: BBOX/POLYGON/POINT/SKELETON). `LBL_NM` 유일성은 **활성(`USE_YN='Y'`) 한정 `LOWER(TRIM(LBL_NM))` 부분 유니크(`UK_LS_LABEL_NM_CI`, V120)가 단독 강제** — 대소문자 근사중복(오토라벨 조회 크래시)·앞뒤 공백(labelId null 유실) 차단. **V121: all-rows exact UNIQUE(`UK_LS_LABEL_NAME`)는 제거**(soft-delete 된 이름 재사용을 막던 배치 해소 — Q1 정합). 저장 시 서비스가 trim + 활성 CI 중복검사(409), soft-delete 이름은 재사용 허용, 동일 exact 활성 중복·동시 생성 경합은 CI 인덱스가 원자 차단→409. V120 마이그레이션은 기존 행 미변경, 활성 근사중복 존재 시 안전중단(RAISE). | [10](10-labeling.md) |
| `LS_LABEL_ATTR` (V33) | 라벨 속성 정의 (INPUT_TYPE_CD, MUTABLE_YN) | [10](10-labeling.md) |
| `LS_LABEL_PRESET` / `LS_LABEL_PRESET_CODE` (V13, **마스터 연동 V117~V119**) | 프리셋 마스터 / 라벨 코드. **`LS_LABEL_PRESET_CODE` 재설계(2026-07-21, V117~V119)**: 프리셋 코드가 라벨 마스터(`LS_LABEL`)를 **단일 진실원으로 실시간 참조**하도록 전환 — ①`LBL_ID`(BIGINT, FK→`LS_LABEL.LBL_ID`, **nullable=미연결 허용**) + FK 컬럼 인덱스 추가·이름 매칭 backfill(V117), ②형태 스냅샷 컬럼 `BBOX_ENABLED`/`POLYGON_ENABLED` **제거**(V117 — 형태는 마스터 `LBL_TYPE_CD` 소유: BBOX→bbox·POLYGON→polygon·POINT/SKELETON→도형 오토라벨 미적용, 프리셋 개별 토글 불가), ③`LBL_CD` **NOT NULL 제거**(V118 — labelId 기반 신규 행은 코드 미저장, 미연결 레거시 행만 표시용 코드 보유), ④부분 유니크 인덱스 `UK_LS_LABEL_PRESET_CODE_LBLID (PRESET_ID, LBL_ID) WHERE LBL_ID IS NOT NULL`(V119 — 동시 갱신 시 프리셋당 같은 labelId 중복 저장 방지, 위반은 409 CONFLICT). 라벨명·형태를 스냅샷하지 않고 조회·표시·오토라벨 사용 시점에 마스터에서 join하므로 마스터 변경이 신규·기존 프리셋에 즉시 반영. 마스터에 매칭 안 되는 코드(labelId null/비활성)는 오류 없이 **'미연결'** 표시(자동 생성/삭제 없음) | [10](10-labeling.md) |
| `LS_LABEL_VERSION` (V24) | 라벨 버전 스냅샷 (VERSION_HASH, SAVE_REASON_CD, ACTVTN_YN). **`VER_NO`(V90 rename) 는 V180 에서 의미 재정의**됐다 — 구 "프레임별 순번"에서 **"영상 단위 산출 회차 번호"**(`LS_DATASET_EXPORT.OUTPUT_VER_NO` 와 동일 축)로 바뀌고 NULL 허용으로 전환됐다(V181 이 옛 의미의 값을 전량 무효화 — 역산 불가능이라 값을 지어내지 않는다). **`ACTVTN_YN` 은 "버전의 정본 표식"이 아니라 "현재 작업본과 일치하는 스냅샷을 가리키는 포인터"** — 어느 산출 회차가 어느 스냅샷이었는지의 단일 원천은 `LS_OUTPUT_VER_SNPSH`(아래)다 → [13 §13.8.3](13-version-control.md) | [13](13-version-control.md) |
| `LS_OUTPUT_VER_SNPSH` (V183, 신설 2026-08-11) | **산출 회차 ↔ 라벨 버전 스냅샷 매핑** — "산출 회차 N 의 프레임 F 내용은 스냅샷 S 였다"를 산출 마감 트랜잭션(`OutputVersionStamper.stamp`)이 그 시점 ACTIVE 스냅샷 전량에 대해 기록한다. 컬럼: `OUTPUT_VER_SNPSH_SN`(PK)·`DATA_RAW_SN`·`DATA_SRC_SN`·`OUTPUT_VER_NO`(=`LS_DATASET_EXPORT.OUTPUT_VER_NO`)·`LBL_VER_SN`(FK→`LS_LABEL_VERSION.LBL_VERSION_SN`)·`REG_DT`. UNIQUE `(DATA_RAW_SN, DATA_SRC_SN, OUTPUT_VER_NO)` — **한 번 기록된 매핑은 불변**(`ON CONFLICT DO NOTHING`, 같은 회차가 다시 마감돼도 덮어쓰지 않는다). FK 2종 `ON DELETE CASCADE`(V146 자식 정책 승계 — 안 걸면 영상 삭제 시 조용한 고아가 남는다). 「시작 버전 선택」(R6)이 되돌릴 스냅샷을 고르는 판정의 **단일 원천**(`VER_NO` 는 조회·표시용으로만 남는다) → [13 §13.8.3](13-version-control.md) | [13](13-version-control.md) |
| `LS_DATA_LBL_HSTRY` (V58, 확장 V112, **저장이벤트 재구조화 V114**) | 라벨 **저장 이벤트** 이력. **재구조화(2026-07-21, V114)**: 기존 '라벨 1건=1행'(구 `LBL_SN`·`CHG_KIND_CD` 라벨단위)에서 **'저장 이벤트=1행 + diff 페이로드'**(프레임 단위)로 전환. 컬럼: `LBL_HSTRY_SN`(PK, 저장이벤트 ID)·`SRC_SN`(프레임)·`REG_DT`(저장시각)·`REG_ID`(작업자, NULLABLE)·`ADD_CNT`/`MDFCN_CNT`/`DEL_CNT`(INTEGER NOT NULL DEFAULT 0, 추가/수정/삭제 건수)·`CHG_DTL_CN`(TEXT, 항목별 diff JSON — `{lblSn, changeKind, labelName, before, after}` 목록). **제거된 컬럼: `LBL_SN`·`CHG_KIND_CD`**(라벨단위→이벤트단위). 저장 클릭 1회=이력 1건(직전 저장 대비 이전값→새값 diff, 첫 저장은 전부 ADDED, 저장은 프레임 전체 교체라 요청에서 빠진 라벨은 실제 삭제+DELETED, 무변경 저장은 이력 미생성). 트랙 삭제·비식별 신고 삭제도 저장이벤트 모델로 프레임당 기록. `GET /v1/frames/{srcSn}/label-history` 조회, 라벨링 화면(SC-005) 히스토리 패널 '변경 이력' 탭에서 표시 | [10](10-labeling.md)·[13](13-version-control.md) |
| `LS_DATASET_EXPORT` (V105, CONTENT_HASH V106, **표준용어 정합+용량 V173**, **FRME_CNT 산정 정정 V185**) | 검수 승인(APPROVED) 시 학습데이터 **파일 산출 추적·버전 원장**. 영상(DATA_RAW_SN) 단위 export 누적(OUTPUT_VER_NO=count+1, UK(DATA_RAW_SN,OUTPUT_VER_NO)), OUTPUT_STTS_CD(PENDING/SUCCEEDED/PARTIAL/FAILED), CONTENT_HASH(라벨+프레임설명+영상메타 SHA-256 멱등키), **DATA_ETBL_CPCT**(데이터구축용량 — 버전 폴더 총 바이트, 관제 `dataset_versions.data_etbl_cpct` 공급, NULL=미산출). **V173 개명**: `EXPORT_*` 는 표준 미등록이라 산출물=`OUTPUT`·프레임=`FRME` 로 정정(`EXPORT_SN`→`OUTPUT_SN`·`EXPORT_PATH_NM`→`OUTPUT_PATH_NM`·`EXPORT_STTS_CD`→`OUTPUT_STTS_CD`·`EXPORT_VER_NO`→`OUTPUT_VER_NO`·`FRAME_CNT`→`FRME_CNT`) — **테이블명·자바 필드명은 불변**. **`FRME_CNT` 산정 방식 — 벌 합계가 아니라 실제 프레임 수(N)**: 관제가 이 값을 `datasets.img_nocs`·`dataset_versions.data_etbl_nocs` 에 "추출·라벨링 프레임 수"로 그대로 적재하므로, 원본(orgnl)+비식별(deid) 2벌 쓰기 건수를 합산하면 2배로 부풀고 1벌만 산출하는 파생영상과 값의 축이 갈린다. **벌별 쓰기 건수의 최댓값**을 적재해 영상 유형(일반 2벌/파생 1벌)과 무관하게 일관된 N 을 낸다(`DatasetExportService`). **V185**는 V173 이 남긴 "원본벌+비식별벌 합계" COMMENT 를 이 정정된 서술로 재지정한 것뿐이며 DDL 변경은 없다(`DATA_ETBL_CPCT` 는 폴더 총 바이트라 2벌 포함이 정상이고 이 축과 무관 — 두 컬럼을 같은 산정 축으로 묶지 말 것). 실제 산출: `{labeling_root}/{RAW_SN}/v{n}/orgnl\|deid/` (승인 AFTER_COMMIT @Async, API 없음) | [24](24-dataset-export.md) |

### 메타 · 마킹
| 테이블 | 용도 | 위키 |
|--------|------|------|
| `LS_MARKING` (V45, **V35 컬럼 추가**) | 마킹 (MARK_MODE_CD, FRME_INTV_NOCS, MARK_CN JSON, `VRFC_EVNT_QSTN_SN` 작업자가 고른 검증 이벤트 질문, **`VRFC_EVNT_TYPE_CD varchar(20) NULL`** — 관제가 유형을 보내지 않은 영상에서 **작업자가 마킹 화면에서 고른 검증 이벤트 유형**. 묘사 축 위탁 `event_type` 의 2순위 조달값이며, **관제 값이 있는 영상에서는 화면이 선택을 아예 노출하지 않아 이 칸이 빈다** → [06 §6.3-2](06-marking.md). ⚠ 화면에서 「필수」인 것과 이 컬럼의 NULL 허용은 **다른 축**이다 — NOT NULL 로 올리면 관제 값이 있는 영상의 마킹이 전량 거부된다) + 부분 유니크 `UK_LS_MARKING_RAW_ACTVTN`(V142 — `RAW_SN` where `STTS_CD IN ('PENDING','VLM_REQUESTED')`). `STTS_CD` 종결값에 `SKIPPED`(V159 — 배치 트리거 skip 시 마킹 종결, B-ISSUE-41) 추가 — 신규 컬럼 없음, 값만 확장이라 유니크 술어는 불변 | [06](06-marking.md) |
| `LS_DATA_META` (V4) | 시계열 메타 (META_KEY/VL, EXTERNAL_JOB_ID) | [09](09-vlm-timeseries.md) |
| `LS_DATA_META_REVIEW` (V5) | 메타 검수 | [09](09-vlm-timeseries.md) |
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
| `LS_META_REPL_OUTBOX` (V98) | ⚠ **[폐기·읽는 코드 없음]** — 2026-08-31 「저작도구와 포털은 서로의 DB 에 접근하지 않는다」 확정(구속)으로 이 표가 떠받치던 복제가 폐기됐고 **같은 날 워커·발신함 코드가 철거**됐다. **표 정의는 남아 있으나 읽고 쓰는 코드가 0건이다** — 드롭 마이그레이션은 되돌릴 수 없는 작업이라 **분리**했고 시점은 별도 판단이다. 아래는 철거 이전의 동작 기록이다(판정 정본 `INT-009`). 포털(별도 물리 DB) 단방향 복제 outbox(PENDING/DONE/DEAD/SUPERSEDED, at-least-once, 멱등키 RAW_SN+SNPSHT_HASH). XA 부재 대응 — 승인과 분리된 워커가 복제 | — |

> 포털 복제본 테이블은 `db/portal/V1~V4`(Flyway `db/migration` 스캔 밖 — 저작도구 Flyway는 control(@Primary) 데이터소스에만 붙는다). **포털 스키마 프로비저닝은 설치 단계 책임**이다: 온프렘은 `gen-schema-sql.sh` 가 만든 `deploy/onprem/db/portal-schema.sql` 을 `install/17-load-portal-schema.sh` 가 로드하고, 그 밖의 환경은 `db/portal/V*.sql` 을 포털 DB에 1회 수동 적용한다. **로드하지 않으면 복제가 조용히 0건으로 유지된다**(워커는 graceful skip만 하고 승인·export·관제 통지는 정상 동작하므로 헬스·기능으로 드러나지 않는다 — dev cudo_246 실측). 복제를 쓰지 않는 환경은 `META_REPLICATION_ENABLED=false` 를 **명시**할 것(기본값 true). `db/portal/V4` 는 control `V128`(`EVNT_ANNO_CN`) 드리프트 정정분이다. ★ **다만 그 정정은 DDL 뿐이었고 복제 로직은 따라오지 않았다**(2026-08-26 실측) — 복제 경로 네 단(payload 직렬화·전송 계약 record·복원 빌더·포털 INSERT)에서 그 컬럼이 전부 빠져 있어 **컬럼은 있는데 값이 항상 NULL** 이었다. `V4` 주석 자체가 추가 사유를 *"복제하려고"* 가 아니라 *"같은 엔티티를 쓰는 JPA 파생 조회가 42703 으로 깨지지 않게"* 라고 밝히고 있다 — **DDL 을 맞췄다는 것이 복제가 된다는 뜻은 아니다.** 위 「로드하지 않으면 복제가 조용히 0건」 과는 다른 형태의 조용한 결손이며(로드해도 그 컬럼만 빈다), 회귀 가드 3종으로 닫았다 ([16-portal.md §16.1b](16-portal.md)). 가용성 probe는 포털 DataSource 직결(JPA/트랜잭션 미경유) — 트랜잭션 안에서 42P01을 삼키면 rollback-only 마킹 때문에 커밋에서 `UnexpectedRollbackException` 이 나 graceful skip이 깨진다(회귀 가드 `MetaReplicationUnprovisionedPortalIT`). 순서 보증은 **Quartz 클러스터 락**(`QRTZ_LOCKS`, `isClustered=true`)으로 2노드 Active-Active 에서도 동일 트리거를 1노드만 발화 + `@DisallowConcurrentExecution`(클러스터 전역 동시 실행 금지) 전제 — 워커는 `PENDING` 을 `REG_DT` ASC 로 순차 폴링한다. 클러스터링을 끄고(`QUARTZ_CLUSTERED=false`) 다중 노드를 띄우면 순서 보증이 깨진다.

### 증강 · 비식별 · 해상도
| 테이블 | 용도 | 위키 |
|--------|------|------|
| `LS_DATA_AUG` (V8) | 증강 데이터 (`AUG_PROC_STTS_CD` = PENDING / ACCEPTED / REJECTED / **CANCELED**) — **CANCELED 는 V154 신설**(사용자 취소 종결). 계약상 취소는 웹훅을 발사하지 않아 동기 응답 시점에 확정하지 않으면 영구 PENDING 이 되고 고아 회수기(job 0건 기준)도 건지지 못한다. REJECTED 재사용 불가 — 그 값엔 이미 REVIEWER 정상 반려와 처리 실패 롤업 두 의미가 겹쳐 있다(E-06 계열). 취소는 실패가 아니므로 `DEAD_LETTER_AT` 은 찍지 않는다. **V154 은 COMMENT 만 갱신한다** — 이 컬럼엔 CHECK 제약이 없고 VARCHAR(20) 이라 DDL 변경이 불필요하며, 유일하게 이 컬럼을 술어로 쓰던 `UK_LS_DATA_AUG_ACTVTN`(V143)은 V153 에서 DROP 됐다. — **해상도 변경 파생도 흡수**: `AUG_TYPE_CD='RESL_1080P/720P/480P'` 판별자 + 부분 유니크 인덱스 `UK_LS_DATA_AUG_RESL (SRC_SN, AUG_TYPE_CD) WHERE AUG_TYPE_CD LIKE 'RESL_%'` (V124). **`PROMPT_CN`**(VARCHAR(4000), V153) = 외부로 전송한 생성 조건 prompt JSON 원문 — 표준용어 등록 복합용어 `프롬프트내용/PROMPT_CN` + 표준도메인 `내용V4000`. ⚠ 같은 CSV 676행에 `프롬프트/PROMPT`(정의 "생성형AI 입력 프롬프트 텍스트", V4000)도 있으나 **PROMPT_CN 유지 확정(2026-07-31)** — ①PROMPT_CN 만 표준도메인(`내용V4000`)에 연결돼 물리명·타입·크기 3축을 모두 충족하고(676행은 사업도메인 칸이 빈 채 V/4000 직접 선언) ②저작도구 v1 DB 설계서가 이미 'AI 생성 프롬프트'를 `LS_DATA_SRC.PROMPT_CN` 으로 적재해 같은 의미에 물리명이 두 벌이 되는 것을 막으며 ③LS_* 관행(형식단어로 끝남)에 맞다. 재검토 완료 — 다시 열지 말 것(근거 원문은 V153 주석). V153 이전 요청·해상도 파생(RESL_*)은 NULL. ⚠ 활성 중복 방지 인덱스 `UK_LS_DATA_AUG_ACTVTN`(V143)은 **V153 에서 DROP** — 같은 (영상 × 종류) 재요청이 정상 동선으로 확정(2026-07-31)됐기 때문이며, `UK_LS_DATA_AUG_RESL` 은 별개 계약이라 유지된다. **하위 테이블**: 외부 위탁 1건이 100장 상한으로 분할되므로 `LS_DATA_AUG` 1행 : `LS_DATA_AUG_JOB` N행 : `LS_DATA_AUG_JOB_FILE` M행 (모두 `ON DELETE CASCADE`). **`NEW_RAW_SN`**(BIGINT NULL, V155, 표준용어 복합용어 `신규원시일련번호`) = 이 증강 요청 행이 만든 **파생 영상**(`LS_DATA_RAW.RAW_SN`) 매핑 — 구 `VMS_CLIP_ID` 시각 접미 역파싱을 대체(동시 콜백 파싱 오귀속 방지), 부분 인덱스 `IX_LS_DATA_AUG_NEW_RAW_SN`(WHERE NOT NULL). 백필 없음(V155 이전 요청은 NULL="그랜드퍼더링" — 등재 게이트·§14.3 프레임 비교쌍 매핑이 이 컬럼을 판정축으로 쓴다) | [14](14-augmentation.md) |
| `LS_DATA_AUG_JOB` (V140) | 증강 외부 위탁 작업 — 생성형 AI 명세서 **v1.3** 정합(작업 상태 5종은 v1.3 §3.2, `OTSD_JOB_ID` 의 202 발급은 §4.1 과 대조 확인 — 판본을 올려도 이 컬럼 구성은 그대로다). PK `AUG_JOB_SN`(BIGINT), `DATA_AUG_SN`(BIGINT FK→`LS_DATA_AUG`), `JOB_SEQ`(INT, 분할 순서 1부터), `IDMP_KEY`(VARCHAR(128) **UNIQUE** `UK_LDAJ_IDMP_KEY` — 우리가 발급한 request_id. **웹훅 발급 게이트의 단일 진실원**으로 `LS_WEBHOOK_IDEMPOTENCY` 를 대체), `OTSD_JOB_ID`(VARCHAR(200) — 외부가 202 로 발급, 접수 전 NULL), `JOB_STTS_CD`(VARCHAR(20) RECEIVED/RUNNING/SUCCEEDED/FAILED/CANCELED — `AUG_PROC_STTS_CD` 검수축과 별개), `TOT_NOCS`(INT), `ERR_CD`(VARCHAR(50))/`ERR_MSG_CN`(VARCHAR(1000) — 위탁 실패를 조용히 삼키지 않기 위한 사유 기록). 인덱스 `IDX_LDAJ_AUG_SEQ (DATA_AUG_SN, JOB_SEQ)`·`IDX_LDAJ_OTSD_JOB_ID` | [14](14-augmentation.md) |
| `LS_DATA_AUG_JOB_FILE` (V141) | 증강 위탁 파일 매핑 — 위탁 순서↔프레임 대응 + 외부 산출 경로. PK `AUG_JOB_FILE_SN`(BIGINT), `AUG_JOB_SN`(BIGINT FK→`LS_DATA_AUG_JOB`), `FILE_SEQ`(INT = `input_files[].sequence`), `SRC_SN`(BIGINT — 위탁한 비식별 프레임), `RSLT_FILE_PATH_NM`(VARCHAR(500) = `results[].output_file_path`, 수신 전 NULL). UNIQUE `UK_LDAJF_JOB_FILE_SEQ (AUG_JOB_SN, FILE_SEQ)`, 인덱스 `IDX_LDAJF_SRC_SN`. **왜 필요한가**: 계약상 `results[]` 에 입력 식별자가 없어 순서로만 대응하므로, 위탁 시점 대응을 못박지 않으면 위탁~콜백 사이 프레임 증감이 조용히 어긋나 다른 프레임에 남의 증강본이 붙는다(무증상 오염). 산출 경로는 SUCCEEDED 콜백에 되붙이고, 건수 불일치 시 미적재 + job FAILED(fail-closed) | [14](14-augmentation.md) |
| `LS_DATA_AUG_RVW` (V25) / `LS_DATA_AUG_LBL_MAP` (V26) | 증강 검수 / 라벨 매핑 (`COORD_RECALC_YN`/`SCALE_X`/`SCALE_Y` — 증강·해상도 파생 공통 재사용) | [14](14-augmentation.md) |
| `LS_DATA_AUG_DSCD` (V156, 감사·재시도 컬럼 V157) | 증강 파생영상 **폐기 원장** — 반려 시 표식(`DSCD_DT`=유예 기산점), 복구(`RSTR_DT`/`RSTR_RSN`), 실삭제 클레임·완료(`DEL_PRCS_DT`/`DEL_DT`/`FILE_DEL_DT`), 파생 비디오 경로 비석(`VDO_FILE_PATH`). **FK 없음**(실삭제가 비석 자신을 지우면 감사·재시도 단서가 사라진다). **V157 보강 2종**: ①**폐기 감사 스냅샷** `AUG_TYPE_CD`(VARCHAR(20))·`PROMPT_CN`(VARCHAR(4000)) — `LS_DATA_AUG` 행이 실삭제로 사라지므로 "무엇을 왜 버렸는가"의 앞 절반(증강 종류·생성 조건)을 비석에 남긴다(백필 없음, 신규 표식부터). ②**파일 정리 수렴** `FILE_DEL_RTRY_NMTM`(INT NOT NULL DEFAULT 0, 표준용어 `RTRY_NMTM`)·`FILE_DEL_FAIL_DT`(TIMESTAMP)·`FILE_DEL_FAIL_RSN`(VARCHAR(4000)) — 파일 정리 시도 누적, 상한(`file-cleanup-max-attempts`) 초과 시 `FILE_DEL_FAIL_DT` 로 데드레터 종결해 재시도 큐(`IX_LS_DATA_AUG_DSCD_FILE_RTY`, `WHERE FILE_DEL_FAIL_DT IS NULL`)에서 뺀다. **재개는 전용 API 없이 운영자 수동 UPDATE** — `FILE_DEL_FAIL_DT`·`FILE_DEL_FAIL_RSN`·`FILE_DEL_RTRY_NMTM`(=0으로) **셋을 함께** 되돌려야 하며, `FILE_DEL_RTRY_NMTM` 을 빠뜨리면 이미 상한에 도달해 있어 다음 tick 첫 실패로 즉시 재종결된다(SQL 예시는 V157 마이그레이션 주석) | [14](14-augmentation.md) |
| ~~`LS_RESOLUTION_EXPORT`~~ · ~~`LS_RESOLUTION_LBL_MAP`~~ | **폐기(V125 백필 후 fail-closed DROP)** — 해상도 변경 저장모델을 `LS_DATA_AUG`+`LS_DATA_AUG_LBL_MAP`으로 통합(2026-07-22) | [14](14-augmentation.md) |
| `LS_DEIDENT_REPORT` (V21) / `LS_DEIDENT_PROC_LOG` (V29, `REQ_KND_CD` BATCH/REDEIDENT V68 도입·V83 rename REQ_KIND_CD→REQ_KND_CD) | 비식별 누락 신고 / 처리 이력(배치·검수완료재비식별 분기). **`LS_DEIDENT_PROC_LOG` 검출 리포트 컬럼 6종(V184, 신설 2026-08-11, R14, 전부 nullable·DEFAULT 없음)**: `FACE_DTCT_CNT`/`NOPLT_DTCT_CNT`/`FRME_CNT`(수N10, 얼굴·번호판 검출수·총 프레임수)·`PRCS_BGNG_DT`/`PRCS_END_DT`(연월일시분초D, 외부 솔루션 처리 시작·종료 일시)·`RPT_FILE_PATH_NM`(VARCHAR(1000), 벤더 응답 파일 경로 — 표준도메인 명V300 이탈은 의도적, 형제 경로 컬럼과 동일 폭). `NULL`=리포트 미조회/조회 실패(0건 검출과 구분, DEFAULT 미사용 이유). 위탁 회차마다 새 행이 쌓이므로 이 테이블 자체가 영상 상세 「비식별 이력」의 원천이다 → [08 §8.3.1](08-deidentification.md) | [08](08-deidentification.md) |

### 작업 · 상태 · 운영
| 테이블 | 용도 | 위키 |
|--------|------|------|
| `LS_RAW_DATA_STATUS` (V36) | 영상 진행 상태. **`REVLT_YN CHAR(1)` NOT NULL DEFAULT 'N'(V177, 재검토여부·행안부 공통표준용어 + 도메인 여부C1)** — 승인 후 사람이 콘텐츠를 고치는 경로(라벨 저장·트랙 편집/병합·버전 롤백·촬영환경/개인정보 메타 수정 등)가 발생하면 세워지는 표시. 승인 상태(`APPROVED`)를 되돌리지 않는다 — 데이터마트 뷰가 라이브 `APPROVED` 로 게이트하므로 상태를 내리면 이미 통지된 영상이 관제에서 사라진다. **판정 단일 지점 `ReviewApprovalGate`**: 지금 상태(`isApproved`)와 이력(`hasEverApproved`, P2b·2026-08-11)은 **다른 축**이다 → [12 §12.2.2](12-review-assignment.md). **`DE_IDNTF_CMPTN_YN CHAR(1)` NOT NULL DEFAULT 'Y'**(비식별화완료여부 — 설계 확정, 코드 미착수): 외부 산출물을 **원본으로 지정해 이관**한 영상만 `N` 으로 시작하며 그동안 **검수 승인만** 거부된다(라벨 조회·프레임 이미지·스트리밍·산출물 생성은 닫지 않는다 — 그것들을 함께 닫는 것은 비식별 누락 신고라는 별개 축) → [25 §25.5](25-external-import.md) | [05](05-video-management.md)·[12](12-review-assignment.md) |
| `LS_OTSD_CTGRY_MPNG` (설계 확정, 코드 미착수) | 외부 산출물의 분류 ↔ 저작도구 라벨·이벤트 유형 **대응**. 대응은 종류 단위이며 개별 항목마다 정하지 않는다. 미확정 분류가 남으면 적재하지 않는다 | [25](25-external-import.md) |
| `LS_OTSD_DATST_TRNSF_HSTRY` (설계 확정, 코드 미착수) | **이관 이력** — 언제 누가 어떤 폴더를 가져와 몇 건이 들어왔는지. 같은 산출물 중복 반입 거부의 근거 | [25](25-external-import.md) |
| `LS_TASK_ALTMNT` (V36, **개명 V9**) | 작업 배정. **물리명은 V9(2026-08-15)에서 `LS_TASK_ASSIGNMENT` → `LS_TASK_ALTMNT`** 로 표준용어 정합됐다(배정=행안부 공통표준단어 `ALTMNT`, → [18.3.6](#1836-작업-배정이벤트-로그-테이블-표준용어-정합-v9-2026-08-15)). **재배정 이력의 적재처는 `LS_TASK_EVNT_LOG` 하나다**(V4 이전에는 `LS_TASK_ASSIGN_HISTORY` 와 이중 기록했으나 조회 API 가 이벤트 로그만 읽어 앞 테이블은 프로덕션 read 0 이었다) | [12](12-review-assignment.md) |
| `LS_TASK_EVNT_LOG` (V36, **개명 V9**) | 작업 이벤트 로그 — 배정·재배정·검수 제출/승인/반려·개인정보 선언 변경·프레임 폐기/복원의 **행 단위 감사 원장**. **물리명은 V9(2026-08-15)에서 `LS_TASK_EVENT_LOG` → `LS_TASK_EVNT_LOG`** 로 표준용어 정합됐다(이벤트=사업표준단어 `EVNT`, → [18.3.6](#1836-작업-배정이벤트-로그-테이블-표준용어-정합-v9-2026-08-15)) | [12](12-review-assignment.md) |
| `LS_ACNT_USER` (V169) | 사용자 마스터 — **저작도구 소유**. 역할 클레임 시점에 관제가 브라우저 `localStorage` 로 인계한 표시 정보(`userId`·`userNm`)로 **자동등록·갱신**(원자 upsert). 구 관제 `MNG_ACCT_USER` 이관처. 인가 역할은 여기가 아니라 `LS_USER_ROLE` 이 단일 진실원. **`LAST_LGN_DT`(최종로그인일시, V12)** 는 JWT 검증을 통과한 INTERNAL 요청이 조건부 UPDATE 로 갱신한다(아래 절) | [03](03-auth-roles.md) |
| `LS_USER_ROLE` (V75, **`MDFR_ID` 신설 V22**) | 저작도구 라벨링 역할 매핑 (USER_NO→ROLE_CD: ADMIN/REVIEWER/WORKER/PORTAL_USER) — 인가 역할 단일 진실원. 구 관제 권한 매핑 대체(역할 분리 2026-06, 구 테이블은 V165 로 삭제). **`MDFR_ID`(수정자아이디, `VARCHAR(30)` nullable, V22)** 는 이 사용자의 역할을 마지막으로 바꾼 사람이다 — 그전까지 영속 기록에는 대상만 있고 주체가 없어 누가 누구를 관리자로 올렸는지 알려면 유효창 발급 로그와 **시각으로 이어 붙여야** 했다. 값은 **인증 주체(인계 토큰 subject)에서만** 취하며 요청 바디에서 받지 않는다(바디 값은 위조 가능). **백필하지 않는다** — null 은 「이 컬럼이 생기기 전에 바뀐 행」이라는 사실 자체이며 지어낸 값을 채우지 않는다. ⚠ 남기는 것은 **마지막 변경자 하나**이고 변경 이력 전체는 대상이 아니다(보존주기·조회창구·정리배치를 함께 정해야 하는 별개 결정) | [03](03-auth-roles.md) |
| `LS_BATCH_PROC_LOG` (V12) | 배치 단계 로그 (STAGE_CD, RESP_PAYLOAD_CN). **REVIEWER 수동 단계 스킵/해제 표식도 이 테이블을 재사용**한다(2026-08-12 — 새 테이블·새 컬럼 없음): 스킵·해제 **둘 다 `PROC_STTS_CD='SKIPPED'`** 로 append-only 적재하고 `ERR_CD`(`MANUAL_SKIP`/`MANUAL_SKIP_CLEARED`)로 구분하며, 현재 상태 = (영상 × 단계) **마지막 표식 행**의 `ERR_CD`(정렬 키는 시각이 아니라 **PK** — 같은 밀리초 토글에서 판정이 뒤집히지 않게). ⚠ 해제에 다른 상태값을 쓰면 그 행이 최신 **진행** 행이 되어 화면 단계 표시가 엉뚱하게 바뀐다(진행 조회가 `SKIPPED` 를 제외하는 성질에 의존) | [07 §7.5-1](07-batch-pipeline.md) |
| `LS_BAT_RTY_WTNG` (V116) | 배치 실패 영상 재시도 대기 — **DB 영속화**(구 in-memory 큐 대체, 2노드 Active-Active 정합). PK `BAT_RTY_SN`, `RAW_SN` UNIQUE(영상 1건=1행), 컬럼(`RTY_NMTM`/`MAX_RTY_NMTM`/`STTS_CD`=PENDING/RETRYING/EXHAUSTED/`RTY_PRNMNT_DT`=재시도 예정 일시/`LAST_ERR_MSG_CN`)은 사업(program) 표준용어(배치=BAT·재시도=RTY·횟수=NMTM·예정=PRNMNT·대기=WTNG) 준거. 폴링은 조건부 원자 UPDATE(PENDING→RETRYING)로 동시 폴링 직렬화, 최초 등록은 `INSERT ... ON CONFLICT DO NOTHING`+FOR UPDATE 로 UK 경쟁 흡수, 최대 초과 시 EXHAUSTED 소진(삭제 아님, 이력 보존). **stale RETRYING 회수(Phase 9-C, B-ISSUE-83)** — 클레임 노드가 처리 중 죽으면 영구 RETRYING 으로 굳어 재시도가 무음 중단되므로, `MDFCN_DT`(=클레임 시각) 기준 임계(기본 180분·하한 30분 clamp)를 넘긴 행만 조건부 UPDATE 로 PENDING 복귀시킨다. 죽은 시도는 `RTY_NMTM+1` 로 계상하고 상한 도달분은 복귀 대신 EXHAUSTED 종결(무한 부활 금지) | [07](07-batch-pipeline.md) |
| `LS_SYSTEM_CONFIG` (V11) | 시스템 설정 (화이트리스트 key/value). 이벤트 필터 제외 대분류 `eventtype.excluded-class-codes`(JSON, 기본 `["08"]`, V161) 포함 — 아래 §관제 이벤트 타입 참조 | [10](10-labeling.md) |
| `LS_AI_SRVR` (**V23**, 2026-09-01) | **AI 서버 원장** — 추론 요청을 나눠 보낼 서버(노드) 목록의 단일 진실원. 추론 서버가 장비 두 대에 이중화로 올라가는데 저작도구가 부르는 주소는 **설정값 하나뿐**이라 두 대를 나눠 쓸 배선이 없었고, 이 표가 그 목록을 데이터로 옮긴다. 컬럼: `SRVR_ID`(PK, 명V20) · `SRVR_NM`(명V100) · `SRVR_ADDR`(주소V200) · `SRVR_TYPE_CD`(코드V20) · `SRVR_STTS_CD`(코드V20) · `CHCK_DT`(점검일시) · `CHCK_FAIL_NOCS`(점검실패건수, 수N10) · **`CHCK_SCS_NOCS`**(점검성공건수, 수N10 — **V24 추가**) · `REG_DT` · `MDFR_ID` · `MDFCN_DT`. ⚠ **`WTNG_NOCS` 는 V24 에서 제거**됐다 — 부하가 노드 단위가 아니라 **노드 x 용도** 단위로 갈렸다(아래 `LS_AI_SRVR_USG`). 남겨 두면 다음 사람이 그 값을 「노드 전체 부하」로 읽어 두 용도를 다시 합치게 되므로, 읽는 코드가 없는 지금 지웠다. `CHCK_SCS_NOCS` 는 이용불가 노드의 **복귀 판정(연속 N회 성공)** 축이며 실패가 한 번이라도 끼면 0으로 되돌아간다 — 폴링 노드의 메모리에 두지 않는 이유는 2노드가 틱을 나눠 갖고(클러스터링이 틱마다 한 노드에서만 발화) 재기동으로도 사라지기 때문이고, 기존 실패 카운터를 부호 있는 값으로 겸용하지 않는 이유는 한 컬럼에 두 축을 담으면 읽는 쪽마다 해석이 갈리기 때문이다. `SRVR_TYPE_CD` 는 `INFERENCE`(추론) \| `TIMESERIES`(외부 시계열) — 전자는 동기 호출, 후자는 논블로킹 제출+콜백이라 **부하의 성질이 달라 같은 목록에서 섞어 고르지 않는다**. `SRVR_STTS_CD` 는 `AVAILABLE` \| `UNAVAILABLE` \| `DRAINING` \| `DISABLED`. ★**`SRVR_ID` 는 소문자·숫자·하이픈·밑줄 20자 이내**(체크 제약 `CK_LS_AI_SRVR_SRVR_ID_FORMAT`) — 맨 앞·맨 뒤에 하이픈·밑줄이 와도 된다. **형식을 아예 없애지 않는 이유**는 이 값이 외부 벤더 요청과 기록에 그대로 실려, 공백·개행·제어문자가 섞이면 기록이 오염되고 상대측 해석이 깨지기 때문이다. 길이 20 은 컬럼 폭이라 **늘리지 않는다**(넘기면 입구를 통과한 값이 저장 시점에 오류로 샌다). 실제 장비 호스트명은 `SRVR_NM` 에 따로 둔다. ⚠ **구 서술 폐기(2026-09-08 사용자 요청으로 확대)** — *"소문자·숫자만"* 이었고 그 근거가 *"이 값이 **서킷브레이커 이름과 메트릭 라벨로 조립**되는데 하이픈이 섞이면 파싱으로 복원되지 않는다"* 였다. **그 전제는 실측상 성립하지 않는다** — `srvrId` 를 서킷브레이커 이름·메트릭 태그로 쓰는 자리가 **0건**이고, 실제 조립은 한 곳뿐인데 **구분자가 `/`** 라 하이픈·밑줄과 겹치지 않는다. 되살리려면 그 조립이 실제로 생겼는지부터 확인할 것. 제약은 **3겹**이다 — DB 체크 제약 · 기동 가드 · 관리 화면 입력 검증(제약을 우회해 들어온 행을 읽는 쪽이 막아야 하므로 어느 겹도 다른 겹을 대체하지 않는다). ★**시드하지 않는다** — 환경마다 주소가 다른데 마이그레이션은 애플리케이션 설정을 읽지 못한다. 대신 기동 시 원장이 비어 있으면 애플리케이션이 기존 설정값(`authoring.integration.ai-server.base-url`)으로 노드 하나를 세우므로 **이 변경 이후에도 기존 배포는 설정값 그대로 동작**한다. 설정값은 최초 1회 씨앗일 뿐 그 뒤로는 원장이 이긴다. ★**마지막 가용 노드 보호는 「사람이 내리는 축」에만 걸린다** — 관리 화면에서 삭제하거나 가용에서 내리는 것은 거부하고(그 판정은 조회 후 UPDATE 가 아니라 **유형별 가용 행을 잠그는 조건부 UPDATE** 여야 한다 — 서로 다른 행을 동시에 내리면 행 잠금이 부딪히지 않아 둘 다 통과한다), **상태점검이 자동으로 내리는 이용불가 전이는 그 보호의 대상이 아니다.** 그건 사람의 결정이 아니라 **관측**이며, 막으면 **죽은 장비로 계속 보내면서 화면에는 「가용」으로 보인다**. ⚠ **구 서술 폐기(2026-09-07 실사고 · 2026-09-08 정정)** — *"가용 행이 0이 되면 AI 기능 전체가 멈추므로 **마지막 가용 노드의 강등은 애플리케이션이 거부**한다"* 는 **축을 가르지 않아** 상태점검 강등까지 막았다. 현장에서 장비 두 대가 다 죽었는데 **한 대가 「가용」으로 표시**되는 사고가 났다. 가용이 0이 되는 것을 두려워하지 않는다 — **쓸 수 있는 후보가 0이면 위탁은 사유 불문 거부**되고 **폴백은 어떤 경우에도 두지 않는다**. ⚠ **분산이 계통마다 다르다 (2026-09-08 실측)** — **외부 시계열**은 원장에서 장비를 골라 나가지만, **추론은 아직 원장을 보지 않고 단일 설정값 주소로 고정 발신**한다. 즉 추론 축은 원장·상태점검·배정 표를 갖춰 두고 **고르는 배선만 없다**. 관리 화면의 추론 장비 목록은 그때까지 **관측 전용**이다 | [11](11-ai-assisted.md)·[07](07-batch-pipeline.md) |
| `LS_AI_SRVR_ALTMNT` (**V23**, 2026-09-01) | **영상별 노드 배정** — 어느 영상을 어느 노드로 보내기로 했는지의 기록. 컬럼: `ALTMNT_SN`(PK, 시퀀스 `LS_AI_SRVR_ALTMNT_SEQ`) · `RAW_SN`(**UNIQUE**) · `SRVR_ID`(**FK 없음** — V26 에서 제거) · `ALTMNT_DT` · `ALTMNT_RSN`(내용V4000). 인덱스 `IX_LS_AI_SRVR_ALTMNT_SRVR_ID`. ★**`RAW_SN` 유니크가 「같은 영상의 프레임은 같은 노드로 간다」는 보장의 전부다** — 추적기가 노드 프로세스의 로컬 메모리에 있어 한 영상의 프레임이 두 노드로 흩어지면 추적이 끊긴다. 2노드 Active-Active 라 같은 영상의 배정이 동시에 일어날 수 있고, 진 쪽은 예외를 받는 대신 **이긴 쪽의 배정을 그대로 읽는다**(하려던 일이 이미 끝나 있을 뿐이라 실패가 아니다). ★**`RAW_SN` 에는 물리 FK 를 걸지 않는다** — 이 행은 추적 불연속의 원인을 나중에 가릴 유일한 근거라 영상보다 오래 살아남을 수 있다. 반대로 배정→원장 FK 는 `RESTRICT` 다(배정이 딸린 노드를 지우면 그 영상들이 갈 곳을 잃는다 — 노드를 내리는 정상 동선은 삭제가 아니라 **정비 지정 후 잔여 배정 0 확인**). `ALTMNT_RSN` 은 **노드 이탈로 다시 배정했을 때만** 채운다 — 값이 있으면 추적 불연속의 원인이 재배정이고, 없는데 추적이 끊겼으면 추적기 만료·노드 재기동·캐시 축출 셋 중 하나다(그 대조가 오진을 막는 유일한 수단이라 기록은 재배정과 **같은 트랜잭션**에서 남긴다). ⚠ 배정 대상은 **배치 축(영상 단위)뿐**이다 — 상호작용 요청은 식별자가 요청 1건짜리라 기록해도 재사용되지 않고 쓰레기 행만 쌓인다 | [11](11-ai-assisted.md)·[07](07-batch-pipeline.md) |

> ★ **`LS_AI_SRVR_ALTMNT.SRVR_ID` 의 외래키는 V26 에서 제거됐다(2026-09-01).** 그 배정은 **처리가 도는 동안** 프레임을 한 노드에 묶어 두는 자리이고, 처리가 끝나면 그 묶음은 의미가 없다 — 추적기 상태가 그 프로세스 메모리에 있었고 그때 이미 사라졌다. 끝난 이력까지 삭제를 막으면 **한 번이라도 영상을 처리한 장비는 영구히 교체 불가**가 된다. 같은 이유로 `LS_WEBHOOK_IDEMPOTENCY.SRVR_ID` 는 처음부터 외래키를 걸지 않았고, 그 축에 맞춘 것이다. 장비를 지우면 그 값은 **이제 없는 장비를 가리키는 기록**으로 남는데 그것이 의도다.
> ⚠ **유령 장비로의 신규 배정은 여전히 막힌다** — 외래키가 묶고 있던 두 축을 갈라, 그 보호는 INSERT 조건(`WHERE EXISTS`)으로 옮겼다. 막아야 할 것은 신규 배정이지 이미 끝난 이력이 아니다.
> ⚠ **처리 중인 영상의 보호도 사라지지 않았다** — 그것은 외래키가 아니라 **정비중(DRAINING)** 이 담당하고("신규 배정만 막고 진행 중인 배정은 끝까지 간다"), 그 위에 **유형별 마지막 가용 장비 보호**가 삭제·활성 이탈 양쪽을 따로 막는다.

| `LS_AI_SRVR_USG` (**V24**, 2026-09-01) | **AI 서버 용도별 부하** — 노드 x 용도 조합의 관측값. 컬럼: `SRVR_ID`+`USG_TYPE_CD`(복합 PK) · `WTNG_NOCS`(대기건수, 수N10) · `PRCS_NOCS`(처리건수, 수N10) · `CHCK_DT`(점검일시) · `REG_DT` · `MDFR_ID` · `MDFCN_DT`. `USG_TYPE_CD` 는 `BATCH`(일괄 처리) \| `INTERACTIVE`(화면에서 쓰는 요청) — 체크 제약. FK `SRVR_ID`→`LS_AI_SRVR` **`ON DELETE CASCADE`**(배정 표는 **V26 에서 외래키를 제거**했으므로 이제 대비 대상이 아니다 — 구 서술 *"배정 표의 `RESTRICT` 와 반대"* 는 폐기. 이쪽이 `CASCADE` 인 것은 **여전히 의도다** — 이쪽은 관측값이라 노드보다 오래 살 이유가 없고, 남으면 같은 식별자로 세운 새 장비에 죽은 장비의 부하가 되살아난다). ★**장비마다 값 하나를 두지 않는 것이 이 표의 존재 이유다** — 추론 서버는 장비 안에서 실행을 용도별로 갈라 두었고, 노드를 고를 때 보는 값은 **그 요청 자신의 용도에 해당하는 부하뿐**이다. 두 값을 합쳐 보면 장비 안에서 갈라 놓은 것을 부르는 쪽에서 다시 붙이는 셈이라, 일괄 처리가 밀린 장비를 화면 요청이 **피할 이유가 없는데도 피하게** 된다. ★**실효 부하 = `PRCS_NOCS` + `WTNG_NOCS`** — 용도마다 동시 처리가 하나여서 대기가 없어도 이미 하나를 잡고 있으면 바쁘다(대기만 보면 한가한 장비와 바쁜 장비가 똑같이 「없음」으로 보여 요청의 절반을 바쁜 쪽으로 보낸다). ⚠ 대기 건수는 상대가 **잠그지 않고 세는 근사값**이라 정확한 수로 등식을 세우지 말 것. ⚠ `CHCK_DT` 는 **우리가 관측한 시각**이다 — 상대 응답의 관측 시각은 상대 장비의 시계라 우리 시계에서 빼면 시계 오차가 그대로 지연으로 잡힌다. ★이 표를 채우는 관측 배치는 **기본으로 켜져 있다** — 꺼 두면 장비를 여러 대 등록해도 관측값이 전부 0으로 같아 동률이 되고, 동률의 결정 규칙이 식별자 순서라 **요청이 항상 한 대로만 간다**(분산이 도입됐는데 동작하지 않는 상태가 조용히 유지된다). ⚠ **구 서술 폐기(2026-09-08)** — *"설정 기본값이 **꺼짐**이다 … 추론 서버가 실행을 용도별로 나누기 전에는 상태 점검조차 늦어져 바쁜 장비를 죽은 장비로 오판한다"*. **그 선행조건은 충족됐다** — 실행이 용도별 슬롯으로 갈렸고 배치 포화 중에도 화면 응답이 유지되는 것이 실측으로 확인됐다. ⚠ 같은 문단이 근거로 든 *"장비가 둘 이상이면 「마지막 가용 노드 보호」에 걸리지 않아 멀쩡한 장비를 하나 잃는다"* 도 **전제가 틀렸다** — 그 보호는 **사람의 조작 축만** 소유하므로 장비가 하나뿐이어도 막아 주지 않는다. 오판 위험은 **대수와 무관**했다 | [11](11-ai-assisted.md)·[07](07-batch-pipeline.md) |
| `LS_AUTH_WORK_LOCK` (V22, 동일영상 활성락 1건 partial unique index V69) | 비식별 재진행 중 잠금(동시 이중 위탁 차단) | [08](08-deidentification.md) |
| `LS_WEBHOOK_IDEMPOTENCY` (V39, **V34 컬럼 추가**) | 웹훅 멱등성 + **시계열 위탁 상관키 원장**(request_id → 채널·RAW_SN) + **`QSTN_CN varchar(4000) NULL`(V34 — 위탁 시점에 실제로 보낸 질문 문구 전문)**. 추가 질문 축이 질문 문구를 요청 본문에 직접 싣게 되면서(2026-09-07) **보낸 값과 기록되는 값을 일치**시키기 위한 칸이다 — 콜백이 이미 요청 식별자로 이 행을 역조회하므로 재위탁·도착순서가 구조적으로 해결된다(위탁 1건 = 원장 1행). ⚠ 질문 **식별자가 아니라 문구 전문**을 보관한다(질문 목록은 전체 교체가 정상 동선이라 식별자만 두면 다시 읽을 수 없다). ⚠ 보관값이 없는 **과거 행은 소급해 채우지 않는다**. ⚠ 인지·수용한 대가 — 본래 중복 판정용 원장에 **업무 값이 한 종류 섞인다** → [09 §9.4-2](09-vlm-timeseries.md). `STTS_CD` 값 3종 — `ISSUED`(발급, 제출 전 선커밋) / **`ACCEPTED`**(수락 응답 수신, 2026-07-30 추가 · **스키마 변경 없음**) / `PROCESSED`(콜백 처리 완료). ISSUED↔ACCEPTED 구분이 미결 회수 스위퍼의 **ACK 창 / 콜백 창** 분리 근거다 — 발급 게이트는 두 값을 동일 취급하므로 콜백 인증 동작은 불변 → [09 §9.2-3](09-vlm-timeseries.md). **`SRVR_ID`**(VARCHAR(20) nullable, V25 2026-09-01) — 이 위탁을 **어느 AI 서버(장비)로 보냈는가**. 외부 시계열 분석 서버 이중화로 장비별 부하를 세고 결과 출처를 되짚는 축이다. ★**부하로 세는 것은 `ACCEPTED` 행뿐**(`ISSUED` 는 벤더가 아직 받지 않아 부하 0 — 세면 제출이 몰린 장비를 과대평가해 반대편으로 쏠린다). ★**nullable = 장비 미상**(컬럼 도입 전 행 + 장비를 고르지 못한 위탁)이라 **미결 회수 스윕의 조회·클레임 조건에 장비 축을 걸지 않는다** — 걸면 미상 행과 죽은 장비의 몫이 영영 회수되지 않는다. **FK 없음**(감사 기록이라 장비 원장보다 오래 살아야 한다). 인덱스 `IDX_LS_WEBHOOK_IDEMPOTENCY_SRVR_STATE (SRVR_ID, STTS_CD)`. **`APLY_DT`(신청일시) 는 V8(2026-08-15)에서 `APLCN_DT`(적용일시) 로 개명** — 담는 값이 콜백 처리 완료 반영 시각이라 뜻이 「적용」인데 「신청」의 표준 약어를 쓰고 있었다(→ [18.3.5](#1835-웹훅-멱등-원장-적용일시-표준용어-정합-v8-2026-08-15)) | [19](19-external-security-cvat.md) |
| `LS_WHK_SIGN_USE` (V131) | 웹훅 서명 사용 원장 — replay 방지용 **1회성 소비** 기록. PK `SIGN_HASH`(경로+X-Timestamp+X-Signature 의 SHA-256 hex, VARCHAR(64)), `WHK_PATH_NM`·`EXPD_DT`. 필터가 트랜잭션 밖에서 `INSERT ... ON CONFLICT DO NOTHING` + updateCount 로 판정(PG UNIQUE 위반이 tx 전체를 abort 시키는 25P02 회피). 표준용어 웹훅=WHK·서명=SIGN·해시=HASH·사용=USE·만료=EXPD | [19](19-external-security-cvat.md) |
| `LS_WHK_FAIL_NMTM` (V131) | 웹훅 인증 실패 횟수 — 2노드 공유 rate limit 집계. PK (`CALL_IP_ADDR` IP주소V45, `BGNG_DT` 분 단위 윈도우), `FAIL_NMTM`(수I11)·`EXPD_DT`. 앱은 1차 JVM-local 카운터로 즉시 차단하고 본 테이블은 2차 집계(공유 저장소 장애 시 fail-open). 표준용어 호출=CALL·주소=ADDR·시작=BGNG·실패=FAIL·횟수=NMTM | [19](19-external-security-cvat.md) |
| `LS_AUTHRT_GRANT_ATMPT` (V132) | 권한 자가부여(role-claim) 시도 횟수 — 2노드 공유 rate limit 집계. PK (`ATMPT_SE_CD` 코드V20 = ACCOUNT/GLOBAL, `ATMPT_IDNTFR` 식별자V36 = 요청자 sub 또는 'GLOBAL', `BGNG_DT` 분 단위 윈도우), `ATMPT_NMTM`(수I11)·`EXPD_DT`. 서비스 트랜잭션이 실패로 롤백돼도 카운터가 남도록 **REQUIRES_NEW** 로 기록. 공유 저장소 장애 시에도 JVM-local Caffeine 카운터가 최종 방어선(완전 fail-open 금지). 표준용어 권한=AUTHRT·부여=GRANT·시도=ATMPT·구분=SE·식별자=IDNTFR·횟수=NMTM | [19](19-external-security-cvat.md) |
| `LS_CONTROL_NOTIFY_FALLBACK` (V44, `SEND_RSLT_CD` 발송결과 컬럼 V77) / `LS_GITEA_FALLBACK_QUEUE` (V41) | 통지 재시도 큐 + 발송 결과 상태 관찰(`STTS_CD`=큐 처리 PENDING/RETRYING/SUCCEEDED/DEAD_LETTER, `SEND_RSLT_CD`=SUCCESS/FAILED — 즉시 성공도 SUCCEEDED+SUCCESS 터미널 행으로 적재) / Gitea 실패 재시도 | [15](15-control-notify.md)·[13](13-version-control.md) |
| `LS_MON_NOTI_ACML` (V144) | 관제 수정 통지 **디바운스 누적**(Phase 9-C) — 영상 1건의 수정을 윈도우로 모아 1회만 flush. 구 인메모리 윈도우는 2노드 Active-Active 에서 ①양 노드에 나뉜 축적이 각자 flush 돼 export 재생성·통지가 2회 나가고 ②노드가 flush 전에 죽으면 축적분이 유실됐다. PK `NOTI_ACML_SN`, `RAW_SN`+부분 유니크(`STTS_CD='PENDING'`)로 영상당 열린 윈도우 1개, `CHG_DTL_CN`(누적 변경 JSON — 라벨/메타 본문·PII 미포함)·`EXPORT_RPRCS_YN`(재생성 동반 OR 누적)·`REG_DT`(윈도우 개시=만료 기준)·`MDFCN_DT`(FLUSHING 행에서는 클레임 임차 시작). flush 는 PENDING→FLUSHING 조건부 UPDATE 로 한 노드만 클레임하고 발송 후 행 삭제, 클레임 노드가 죽으면 임차(기본 300초) 만료 후 재클레임. 물리명은 표준용어(관제=MON·알림=NOTI·누적=ACML·변경상세내용=CHG_DTL_CN·재처리=RPRCS) 준거. **`STTS_CD` 는 V7(2026-08-15)에서 `VARCHAR(20)`→`VARCHAR(16)`** 으로 표준도메인 폭에 맞췄다(→ [18.3.4](#1834-통지-디바운스-누적-상태코드-폭-정합-v7-2026-08-15)) | [15](15-control-notify.md) |
| `LS_PORTAL_USER_LABEL` (V47) | 포털 사용자 라벨 (데이터마트 영상 대상) | [16](16-portal.md) |
| `LS_NOTICE` / `LS_NOTICE_ATTACH` (V56) | 게시판 공지(DRAFT/PUBLISHED, UPEND_FIX_YN) / 첨부(UUID 저장명, FK cascade) — R1 외 추가 | [20](20-notice-board.md) |
| `LS_TUS_UPLOAD` (V59, 표준용어 rename V88·V90) | TUS 1.0 재개 가능 업로드 세션 — `ULD_ID`(UUID PK)/`USER_NO`(소유자)/`ULD_LEN`/`ULD_OFFSET`(예약어 OFFSET 회피)/`STTS_CD`(IN_PROGRESS·COMPLETED·EXPIRED)/`FILE_PATH`(UUID 저장명 강제)/메타(`VMS_CLIP_ID`·`CCTV_ID`·…)/`EXPRY_DT`(+24h TTL, 공공 만료일시)/`VER`(낙관적 잠금). 완료 시 `LS_DATA_RAW` 합류. 인덱스 `IDX_LTU_USER_STATUS`(동시 세션 상한)·`IDX_LTU_EXPIRES`(만료 정리 잡) | [05](05-video-management.md) |
| `LS_DATA_ISSUE` (V5) | 품질 이슈 | — |
| `LS_ISSUE_COMMENT`(엔티티 `LsIssueComment`, 이 문서 §18.2 목록에 신규 반영·2026-08-19) | 품질 이슈 댓글. `DATA_ISSUE_SN`→`LS_DATA_ISSUE.DATA_ISSUE_SN` FK(`ON DELETE RESTRICT`, **V10 신설** — 위 「신규 마이그레이션(V10~V13)」 참조). 컬럼은 gov-first rename(`ISSUE_COMMENT_SN→CMNT_SN`)에서 이미 다뤄졌으나 테이블 자체가 §18.2 목록에서 빠져 있었다 | — |
| ~~`LS_DEADLINE`~~ · ~~`LS_META`~~ · ~~`LS_RAW_DATA_ENROLLMENT`~~ (V36) | **폐기(V3 DROP, 2026-08-13)** — 아래 참조 | — |
| ~~`LS_COM_CD`~~ · ~~`LS_DATA_META_HSTRY`~~ · ~~`LS_DATA_RAW_HSTRY`~~ · ~~`LS_TASK_ASSIGN_HISTORY`~~ | **폐기(V4 DROP, 2026-08-13)** — 아래 참조 | — |
| ~~`LS_DATA_LBL_AI_INFO`~~ | **폐기(V6, 2026-08-13)** — 삭제가 아니라 `LS_DATA_LBL` 로 **흡수**됐다(`LBL_SRC_CD`·`MDL_NM`·`MDL_VER`·`CONF_SCORE`·`AUTO_LBL_YN` 5컬럼 이관). 아래 참조 | [10](10-labeling.md)·[11](11-ai-assisted.md) |

> 구 `LS_DATA_SET` (V8, 학습데이터셋 Export용)은 **범위 외 orphan 테이블로 판정되어 삭제**됨(V86) — 엔티티·활성쿼리·View·FK 참조 0건 검증. ⚠ **삭제 결론은 유효하나 근거 서술은 폐기(2026-08-15)** — *"학습데이터셋 Export는 범위 외(관제/데이터마트 책임)"* 는 **뒤집힌 전제**다. **export(NIA JSON) 산출은 저작도구 범위 안**이며(`ADR-005` → **`ADR-020`** 이 대체), 범위 밖인 것은 **데이터마트 구축·검색·다운로드**다. 이 테이블이 삭제된 실제 사유는 **1차 데이터셋 테이블 기반 내보내기가 폐기**되고 산출이 **검수 승인 경로의 폴더 export** 로 바뀌었기 때문이다.

> 구 `LS_DEADLINE`·`LS_META`·`LS_RAW_DATA_ENROLLMENT` (V36, 1차 스키마 `LS_PJT_DDLN`·`LS_PJT_META`·`LS_PJT_DATA_MPNG` 의 개명 복제본)는 **사용처 0 으로 판정되어 삭제**됨(2026-08-13, `V1__baseline.sql` 정의 제거 + `V3__drop_unused_tables.sql` DROP). 검증: 리포지토리 0건 · `backend/src`(main+test) 전체에서 엔티티명이 **자기 클래스 선언 1줄뿐** · 네이티브/JPQL·화면·배포 스크립트 0건 · **이들을 참조하는 FK 0건**(`LS_RAW_DATA_ENROLLMENT`→`LS_DATA_RAW` 자식 방향 FK 하나뿐이었다) · dev 실측 행수 0/0/0. 엔티티 클래스 3종과 `kr.co.cudo.authoring.project` 패키지도 함께 제거. **V3 는 행이 1건이라도 있으면 DROP 하지 않고 기동을 멈춘다**(fail-closed — 전제가 깨졌다는 신호). 원문은 `backend/src/test/resources/db-archive/migration/` 의 V36(생성)·V37(이관)·V60·V85 에 보존. 감리 산출물 정합은 2026-08-13 시점 **당시 정본이던 `docs/design/`(D8·D9·R3)에 반영 완료**됐다. ⚠ `docs/archive/design-full/` 은 2026-08-06 전량 재생성으로 대체된 **구판**이라 대상이 아니다 — 이미 `LS_RESOLUTION_EXPORT`·`LS_RESOLUTION_LBL_MAP`(7월 삭제분)을 들고 있고 `LS_DATA_INGEST`·`LS_EVNT_ANNO`·`LS_PORTAL_*` 를 모르며 hwpx 변환 대상도 아니다. ⚠ `docs/design/` 은 LogiCraft 그래프에서 생성되는 산출물이므로 대응 ITEM 을 갱신하지 않으면 다음 재생성 때 되살아난다(당시 기준 서술).
> ⚠ **구 서술 폐기(2026-08-19 재확인)** — *"현 정본인 `docs/design/`"* 이라는 **현재형** 표현은 더 이상 사실이 아니다. 이틀 뒤인 **2026-08-15에 `docs/design/`이 동결**되어 그 감리 산출물(D8·D9·R3 포함)은 `docs/archive/frozen-20260815/design/`로 이관됐고, 판정 근거로도 쓰지 않는다. 지금 `docs/design/`에는 LogiCraft 구현 키트 디렉터리 14개만 있다. 위 문장은 "2026-08-13 시점에는 참이었다"는 이력 서술로 남기고, 동결 사실은 이 각주로 보충한다. 근거: `docs/archive/frozen-20260815/README.md`.

> 구 `LS_COM_CD`(공통코드 마스터, V2 에서 `CM_CODE` 를 개명)·`LS_DATA_META_HSTRY`·`LS_DATA_RAW_HSTRY`·`LS_TASK_ASSIGN_HISTORY` 는 **사용처 0 으로 판정되어 삭제**됨(2026-08-13, `V1__baseline.sql` 정의 제거 + `V4__drop_unused_tables_round2.sql` DROP). `LS_COM_CD` 는 런타임 조회 없이 시드 5행만 있던 코드 마스터이고, 이력 3종은 **쓰기만 있고 읽는 경로가 없었다** — 특히 재배정은 `LS_TASK_ASSIGN_HISTORY` 와 `LS_TASK_EVNT_LOG` 에 같은 사실을 이중 기록했는데 조회 API(`GET /v1/assignments/{id}/history`)는 **이벤트 로그만** 읽었다. 따라서 적재처가 한 곳으로 좁혀졌을 뿐 **불변식·조회 경로·응답 스키마는 그대로**다. V4 도 V3 과 같이 행이 1건이라도 있으면 DROP 하지 않고 기동을 멈춘다(fail-closed).

> 구 `LS_DATA_LBL_AI_INFO` 는 **삭제가 아니라 `LS_DATA_LBL` 로 흡수**됐다(2026-08-13, `V6__absorb_lbl_ai_info_into_ls_data_lbl.sql`). 두 테이블은 완전한 1:1 이었고 라벨 응답이 출처·신뢰도·자동여부를 항상 함께 내려주어 조회가 늘 둘을 같이 읽었다 — 라벨을 읽을 때마다 붙던 조인을 없애는 것이 목적이다. 이관 컬럼은 `LBL_SRC_CD`·`MDL_NM`·`MDL_VER`·`CONF_SCORE`·`AUTO_LBL_YN` 5종이며, 영상·프레임 식별자는 라벨 행에 이미 있는 값의 사본이라 함께 버렸다. **기본값을 두지 않는 것이 의도다** — 값이 비어 있음은 "AI 가 만들지 않았다", `'N'` 은 "AI 가 만들었지만 자동이 아니다"를 뜻하므로 기본값을 걸면 두 사실이 같은 값이 되어 구분이 사라진다 → [11 §11](11-ai-assisted.md)

> **공공 우선(gov-first) 표준용어 rename (V90·V91, 2026-07-10)**: 공공 표준용어에 동일 한글용어가 존재하는 컬럼 15건을 공공약어로 정합 — `EXPD_DT→EXPRY_DT`(LS_AUTH_WORK_LOCK·LS_TUS_UPLOAD), `RESP_DT→RSPNS_DT`, `REJECT_RSN→RJCT_RSN`(×2), `MODEL_NM→MDL_NM`, `VERSION_NO→VER_NO`, `REPORT_DT→DCLR_DT`, `ISSUE_COMMENT_SN→CMNT_SN`, `ATTACH_SN→ATCH_FILE_SN`, `STORE_FILE_NM→STRG_FILE_NM`, `LOCK_DT→LCK_DT`, `RELEASE_DT→RMV_DT`, `RELEASE_RSN→RMV_RSN`, `ATTR_NM→ATRB_NM`(V91, `V_COMPLETED_LABEL_ATTR` 뷰 재생성(V114에서 뷰 제거) — 출력 별칭 `ATTR_NAME` 불변). Java 필드명·JSON 계약은 불변(물리 컬럼만 rename).

### 포털 자산 업로드 (ADR-013 예외, V107~)

> 포털 사용자(PORTAL_USER)가 **본인 이미지·영상을 직접 업로드**해 수동 라벨링(BBOX/POLYGON)하는 별도 경로. 내부 파이프라인(비식별→마킹→배치→검수)·데이터마트 View와 **완전 분리**되며 오토라벨링·SAM2·VLM·검수·버전관리 미적용 → [16](16-portal.md)·[04](04-screens-ia.md).
>
> ⚠ **위 「내부 파이프라인(비식별→**마킹**→배치→검수)과 분리」를 「포털에 마킹이 없다」로 읽지 말 것** — 2026-09-02 로 **포털 업로드 영상에는 자체 마킹이 생겼다**(`SC-045`). 분리돼 있는 것은 **내부 파이프라인의 마킹 단계**(위탁·배치 기동·검수 연계를 낀 것)이고, 포털 마킹은 **프레임 추출 위치 지정 하나**이며 **저장만 같은 `LS_MARKING` 원장을 쓴다** → [16 §16.5a](16-portal.md).

| 테이블 | 용도 | 위키 |
|--------|------|------|
| `LS_PORTAL_ULD` (V107) | 포털 업로드 자산 (소유자 USER_NO, 자산유형 IMAGE/VIDEO, 원본 파일명·경로, `STTS_CD`: `UPLOADED`→`PROCESSING`→`READY`\|`FAILED` — **이 전이는 영상 전용이고 이미지는 적재 시점에 곧바로 `READY`** 다). 이미지 20MB/장·50장/요청(⚠ **신규 접수는 영상만** — 기존 이미지 자산의 조회·라벨링·삭제는 유지되고 값역에 이미지 값이 남는다), 영상 5GB(mp4/mov/avi) | [16](16-portal.md) |
| `LS_PORTAL_ULD_FRME` (V107) | 업로드 자산 프레임 (SEQUENCE PK, 프레임 경로). 영상은 **마킹 위치 기반 추출**(2026-09-02 확정 — `SC-045` 마킹 화면이 정한 지점, 장수 상한 초과 시 **절단**), 이미지는 1프레임. ⚠ **구 서술 폐기** — *"영상은 고정 간격 추출(`portal.upload.frame-interval-sec` 기본 5초)"* 은 더 이상 사실이 아니다. 그 동작은 **자동 마킹의 기본 동작으로 흡수**됐고 단위가 초에서 **프레임 수(기본 300)** 로 바뀌었다. **장수 상한(maxFrames)은 그대로 유효**하다 | [16 §16.5a](16-portal.md) |
| `LS_PORTAL_ULD_LBL` (V107) | 업로드 자산 수동 라벨 (BBOX/POLYGON만, 좌표 JSON). 오토라벨 미적용 | [16](16-portal.md) |
| `LS_PORTAL_TUS_ULD` (V108) | 포털 영상 TUS 1.0 재개 가능 업로드 세션 (소유자·오프셋·만료 등, 내부 `LS_TUS_UPLOAD`와 분리) | [16](16-portal.md) |

> ### ★위 4벌은 공용 원장으로 흡수하기로 확정됐다 — 다만 **아직 스키마에 그대로 있다** (2026-09-02 · `ADR-058`)
>
> **설계는 확정, DDL 이관은 미착수**다. 위 4행은 **현재 배포 스키마의 사실**이므로 지우지 않는다.
> 이관이 끝나면 그때 이 표에서 뺀다.
>
> | 흡수 전 | 흡수처 | 신설 |
> |---|---|---|
> | `LS_PORTAL_ULD` | `LS_DATA_RAW` | `PORTAL_USER_NO varchar(100) nullable` |
> | `LS_PORTAL_ULD_FRME` | `LS_DATA_SRC` | 신설 컬럼 0 (원장이 상위집합) |
> | `LS_PORTAL_ULD_LBL` | `LS_DATA_LBL` | 기존 `REG_USER_NO` 를 `bigint`→`varchar(100)` |
> | `LS_PORTAL_TUS_ULD` | `LS_TUS_UPLOAD` | 사용자 식별자 폭 64→100 |
> | **`LS_PORTAL_USER_LABEL`** | **흡수하지 않는다 — 존치** | — |
>
> - 채널 구분은 **`SRC_TYPE='PORTAL_ULD'`**(축 신설이 아니라 값 1개 추가) + **`PORTAL_USER_NO`** 다.
> - **`LS_PORTAL_USER_LABEL` 만 남기는 이유** — 존재 이유가 「저장해도 원본을 수정하지 않는다」인
>   단방향 오버레이라 라벨 원장에 합치면 **원본을 덮어쓴다.** 소유자 구분으로 섞으면 구분을 한 번만
>   잊는 순간 **남의 오버레이가 정본 라벨로 읽히는 fail-open** 이 된다. 「일관성」을 이유로 함께
>   흡수하지 말 것.
> - **★착지처 확정 (2026-09-02) — 영상 원장에 컬럼을 더 달지 않는다.** 나머지 다섯도 `LS_DATA_META` 로
>   간다: `ORGNL_FILE_NM`→`video.original_filename` · `MIME_TYPE_NM`→`video.mime` ·
>   `ULD_STTS_CD`→`portal.upload_status` · `FAIL_RSN_CN`→`portal.fail_reason`.
>   `ULD_TYPE_CD`(자산 종류)는 **보관하지 않고 MIME 유형에서 판정**한다 — 두 값을 따로 두면 어긋났을 때
>   어느 쪽이 정본인지 알 수 없는 두 번째 진실원이 된다. 프레임률·파일 크기는 `video.fps`·`video.filesize`
>   로 이미 있고 프레임 수는 프레임 원장 행을 센다.
>   ⇒ **신설 컬럼은 `PORTAL_USER_NO` 하나뿐이다.**
> - ★**마킹은 흡수 대상 4벌 밖의 별개 축이다 (2026-09-02 확정)** — 포털 업로드 영상의 마킹은 **기존
>   `LS_MARKING` 원장을 그대로 쓴다**(포털 전용 마킹 표를 만들지 않는다). 자동 간격은 그 표의 **프레임
>   간격 컬럼**이며 **단위가 프레임 수라 시간 단위 설정에서 환산할 필요가 없다.** 여기에도 **사용자 식별자
>   폭 확대**가 따라붙는다(라벨 원장 `REG_USER_NO` 와 같은 조치). → [16 §16.5a](16-portal.md)
> - ⚠ `META_VL` 이 `varchar(2000)` 이라 실패 사유가 **좁아진다** — 사유 문장만 담고 넘치면 잘라 저장하되
>   잘렸다는 사실이 드러나야 한다(조용한 절단 금지).
> - ⚠ **연쇄 삭제 표면이 넓어진다** — 지금은 `LS_PORTAL_ULD` 삭제가 2개 표만 딸고 가지만, 이관 후
>   `LS_DATA_RAW` 를 참조하는 표는 **약 24개**다. 그중 `LS_DATA_LBL_HSTRY`·`LS_DATA_AUG`·
>   `LS_DATA_AUG_LBL_MAP` 은 **부모 외래키가 없어 연쇄로 정리되지 않아** 조용히 고아가 남는다.

> 신규 API `/v1/portal/uploads/**` (images·목록·상세·frames·image·삭제·tus·labels·export·file). 영상은 비식별 미적용(본인 데이터), 다운로드는 본인 데이터(JSON export/원본) 기준.

## 18.3 데이터마트 적재용 View (V52)

`klid_at` 스키마에 4종 View (V114 재편) — **검수 완료(APPROVED) 영상만 노출**. 나머지 3종은 `CREATE OR REPLACE VIEW`라 멱등이고, `V_COMPLETED_VIDEO` 는 V174 에서 컬럼 제거·개명이 있어 `DROP VIEW IF EXISTS` + `CREATE VIEW`(재실행 안전, `CASCADE` 미사용)다.

| View | 내용 |
|------|------|
| `V_COMPLETED_VIDEO` | **V174 재작성 — 38 → 30컬럼**(정본 `docs/관제-저작도구-데이터연동-규격서-20260805.md` §5-1). 관제가 `datasets`·`dataset_versions` 를 SELECT 1회로 채우고 산출물을 픽업하는 계약면이다. 기반은 종전대로 `LS_DATASET_VIDEO_META`(ACTIVE_YN='Y') 동결 스냅샷 + 라이브 APPROVED 게이트. **제거 26** — 관제 적재 대상이 없는 값(`VMS_CLIP_ID`·`CCTV_NM`·`WGS84_*`·`SIDO_NM`·`SGG_NM`·`FILE_FMT`·`VDO_CDC`·`FPS`·`BIT_RT`·`ASPRT_RT`·`RESL`·`VDO_WDTH`·`VDO_HGT`·`FILE_SZ`·`DAY_NGT_CD`·`SESN_CD`·`WTHR_NM`·`EVNT_NM`·`CAPTURED_AT`·`PRVC_TYPE_CD`·`PRVC_YN`·상태 3종 등). **별칭 정정 6**(표준 물리명) — `DURATION_SEC`→`VDO_LEN_SEC` · `FRAME_CNT`→`FRME_CNT` · `REVIEW_COMPLETED_AT`→`RVW_CMPTN_DT` · `ORIGINAL_VIDEO_PATH`→`ORGNL_VDO_PATH_NM` · `EXPORT_PATH_NM`→`OUTPUT_PATH_NM` · `EXPORT_STTS_CD`→`OUTPUT_STTS_CD`. **신규 18** — `EVNT_CLSF_CD`·`EVNT_CTGRY_CD`·`LCLGV_NM`(인입 `LS_DATA_INGEST` LATERAL 조인) · `GEN_AI_YN`(`LS_DATA_RAW.SRC_TYPE IN ('GENERATED','AUGMENTED')`) · `DATST_NM`/`DATST_EXPLN`(`{이벤트명} 데이터셋 구축`, 둘 다 같은 값, **명시 캐스팅 `DATST_NM`=`varchar(200)` · `DATST_EXPLN`=`varchar(4000)`** — 표준 우선순위(①행안부→②사업)로 각각 판정: `데이터셋명`은 공통표준용어 `DATST_NM`(도메인 `명V200`), `데이터셋설명`은 공통 **미등록**이라 사업표준 `DATA_SET_EXPLN`(도메인 `내용V4000`). ⚠ **길이가 다른 것이 정상**이며(명칭 vs 내용은 도메인 그룹이 다르고 관제도 `name`=varchar(500)/`description`=text 로 갈라 놨다) 통일하지 말 것. ⚠ 명시 캐스팅은 에러 없이 자르므로 `DATST_NM` 은 **이벤트명 192자 초과 시 절단**된다(`DATST_EXPLN` 은 상한이 커 절단 없음)) · `IMG_YN`(`FRME_CNT>0`)/`VDO_YN`(비식별 영상 경로 존재) · 개인정보 **비식별 축** `ANONY/PSDO/PRVC_INCL_YN`(`LS_DATA_RAW` V163 수동값, 미입력 시 `Y`/`N`/`N`) · **원천 축** `SRC_*_INCL_YN`(인입 V166 값, **파생영상은 NULL**) · `DATA_ETBL_YR`(검수완료 연도) · `DATA_ETBL_CPCT`(V173 산출 폴더 총 바이트) · `LBL_TYPE`(`LS_DATA_LBL.LBL_TYPE_CD` 영상 단위 집합, `varchar(256)` 명시 캐스팅 — 사업표준 `라벨유형` 도메인 `명V256`) · `LBL_FMT`(상수). ⚠ **`DE_IDNTF_YN` 은 제거 금지**(신고 구간 관제 자체판단 노출 정책). ⚠ **`FRME_CNT` 는 미산출 시 0**(규격서가 NOT NULL 로 공표)이고, `DATA_ETBL_CPCT`·`OUTPUT_PATH_NM` 은 미산출 시 NULL 이다. ⚠ 인입 신규 노출값은 **동결 스냅샷에 넣지 않는다**(설계 D1) — 인입은 관제 수신 원장이라 불변이므로 동결과 라이브 조인의 결과가 같다. 조인 규칙은 앱의 단일 진실원 `IngestSourceLink` 와 동일(`COALESCE(ORGNL_RAW_SN, RAW_SN)` + 최신 `RCPTN_SN` 1행). ⚠ `GEN_AI_YN` 은 동결 `AI_CRT_YN` 을 읽지 **않는다**(설계 D2) — 그 컬럼은 도출식 결함으로 `GENERATED` 원본이 `N` 으로 오동결돼 있다. LATERAL 4개(산출 원장 · 비식별 이력 · 인입 · 라벨 집계)가 모두 최대 1행이라 **영상 1건 = 1 row** 불변 |
| `V_COMPLETED_FRAME` | 프레임 페어 (`ORIGINAL_PATH`=원본, `DEIDENTIFIED_PATH`=비식별; 원천 `LS_DATA_SRC.DE_IDNTF_SRC_FILE_PATH_NM`) + **`DESCRIPTION`(V104, `FRM_EXPLN` 프레임설명, 하위호환 끝 추가)** |
| `V_COMPLETED_LABEL_CHANGE` (V114 신설 · V115 재정의) | 라벨 변경점 (`LS_DATA_LBL_HSTRY` 기반, **저장이벤트 단위** — `ADD_CNT`/`MDFCN_CNT`/`DEL_CNT` 종류별 건수 + `CHG_DTL_CN` diff JSON, APPROVED 게이트). V115의 `LS_DATA_LBL_HSTRY` 저장이벤트 재구조화(구 `LBL_SN`/`CHG_KIND_CD` 제거)에 맞춰 뷰를 재정의(관제 연동 계약 변경 — 협의 대상). 구 라벨 좌표·속성 본문 뷰(`V_COMPLETED_LABEL`·`V_COMPLETED_LABEL_ATTR`)는 V114에서 제거 — 라벨 내용은 검수 승인 export 폴더 JSON이 진실원(뷰 중복 노출 제거) |
| `V_COMPLETED_META` | 시계열 메타 (RVW_STTS_CD='APPROVED'만). V101에서 `video.*` 기술메타 6키 제외(통합 스냅샷 `V_COMPLETED_VIDEO`로 이관) — VLM/외부 시계열만 노출 |

> 관제서버는 `TASK_COMPLETED`/`TASK_MODIFIED` 수신 후 RAW_SN으로 4 View SELECT → 영상 1건=1 row UPSERT. 비식별 **영상** 경로는 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 적재값 사용(문자열 치환 도출 아님, View 미포함). 비식별 **프레임** 경로는 `V_COMPLETED_FRAME.DEIDENTIFIED_PATH`(=`LS_DATA_SRC.DE_IDNTF_SRC_FILE_PATH_NM`)에 직접 노출되며, 신규 추출은 원본 `{base}/frames/raw/{rawSn}`·비식별 `{base}/frames/deid/{rawSn}` 로 분기 저장돼 `STORAGE_RAW_PATH==STORAGE_DEIDENTIFIED_PATH`(=`/nas-storage`)여도 충돌하지 않는다. → [15](15-control-notify.md)

### 18.3.1 `LS_DATA_RAW` 참조 무결성 — 자식 FK + `ON DELETE CASCADE` (V146)

구 스키마는 `LS_DATA_RAW` 를 참조하는 FK 가 `LS_EVNT_ANNO` 단 1건뿐이라 영상 행이 사라져도 자식이 고아로 잔존했다(실측: `LS_MARKING` 고아 2행). V146 이 **자식 27개 테이블에 FK 를 신설**한다.

| 구분 | 대상 | 삭제 규칙 | 근거 |
|------|------|:---------:|------|
| 일반 자식 | `LS_DATA_SRC`·`LS_MARKING`·`LS_DATA_META`·`LS_DATA_META_REVIEW`·`LS_DEIDENT_PROC_LOG`·`LS_DEIDENT_REPORT`·`LS_BATCH_PROC_LOG`·`LS_BAT_RTY_WTNG`·`LS_AUTH_WORK_LOCK`·`LS_DATA_ISSUE`·`LS_DATA_AUG_RVW`·~~`LS_DATA_RAW_HSTRY`~~·`LS_LABEL_VERSION`·`LS_DATASET_EXPORT`·`LS_DATASET_VIDEO_META`·`LS_RAW_DATA_STATUS`·~~`LS_RAW_DATA_ENROLLMENT`~~·`LS_TASK_ALTMNT`·~~`LS_TASK_ASSIGN_HISTORY`~~·`LS_TASK_EVNT_LOG`·`LS_CONTROL_NOTIFY_FALLBACK`·`LS_META_REPL_OUTBOX`·`LS_MON_NOTI_ACML`·`LS_PORTAL_USER_LABEL`·`LS_EVNT_ANNO`(기존 FK 를 NO ACTION→CASCADE 로 통일) | `CASCADE` | 영상 행이 사라지면 그 자식 데이터는 의미가 없다. RESTRICT 로 하면 실재 삭제 경로(`ResolutionPersistService.deleteFailedDerivativeRaw`·`TusUploadService` 완료 경합 롤백)가 깨진다 |
| 원장·세션 | `LS_WEBHOOK_IDEMPOTENCY.RAW_SN`·`LS_TUS_UPLOAD.RAW_SN` | `SET NULL` | 행이 사라지면 웹훅 재전송 방지/업로드 멱등 응답이 무너진다. 두 컬럼 모두 nullable 이라 참조만 끊는다 |
| ~~**제외**~~ → **편입(V162)** | `LS_CLIP_SCHEDULE_QUE.RAW_SN`(구 `MNG_CLIP_SCHEDULE_QUE`) | `CASCADE` | V146 은 이름만 보고 "관제서버 소유(MNG_*)" 로 오판해 제외했으나, 실제로는 저작도구가 V2 에서 직접 CREATE 한 **자체 소유 배치 큐**였다(관제 미참조). V162 가 `LS_` 로 개명하고 누락된 FK 를 보강 → 18.3.2 |
| **제외** | `LS_DATA_RAW.ORGNL_RAW_SN`·`LS_DATASET_VIDEO_META.ORGNL_RAW_SN` | — | 자식이 아니라 파생 계보(self-reference)/승인 시점 **동결** 값. 고아 자동 복구가 둘 다 위험(NULL 화 시 파생본이 "원본" 으로 승격돼 비식별 신고 거부·PII 정책이 역전, 삭제 시 검수 완료 파생 학습데이터 소실) — 별건 |

> ~~취소선~~ 3종(`LS_DATA_RAW_HSTRY`·`LS_RAW_DATA_ENROLLMENT`·`LS_TASK_ASSIGN_HISTORY`)은 **V3·V4 에서 테이블째 삭제**돼 현재 스키마에 없다. 위 목록은 V146 이 FK 를 신설한 시점의 대상이라 원문대로 두고 표기로만 구분한다 — 현재 스키마의 `LS_DATA_RAW` CASCADE 자식은 **24개**로, 위 22개(25 − 삭제 3)에 **`LS_CLIP_SCHEDULE_QUE`**(V162 편입, 아래 행 참조)와 **`LS_OUTPUT_VER_SNPSH`**(이후 신설)가 더해진 값이다. 구 `LS_DATA_LBL_AI_INFO` 도 `LS_DATA_RAW` 자식이었으나 V6 흡수로 사라졌다(라벨 경유 자식이라 이 목록에는 원래 없었다).

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

### 18.3.3 배치 큐 · 메타복제 발신함 컬럼 표준용어 개명 (V5, 2026-08-13)

`LS_CLIP_SCHEDULE_QUE`(7) · `LS_META_REPL_OUTBOX`(4) 의 **컬럼 11종**만 영문 서술형 물리명으로 남아 있었다. 같은 개념을 이미 표준 조합으로 쓰는 형제 테이블(`LS_BAT_RTY_WTNG` · `LS_CONTROL_NOTIFY_FALLBACK` — `STTS_CD VARCHAR(16)` · `RTRY_NMTM` · `LAST_ERR_MSG_CN VARCHAR(2000)`)과 형태를 맞췄다. 판정은 **①행안부 공통표준 → ②사업표준** 순으로 CSV 정본 전수 대조.

| 테이블 | 현재 → 개명 |
|---|---|
| `LS_CLIP_SCHEDULE_QUE` | `JOB_TYPE(32)`→**`JOB_TYPE_CD(20)`** · `STATUS`→**`STTS_CD`** · `RETRY_COUNT`→**`RTRY_NMTM`** · `REGISTERED_AT`→**`REG_DT`** · `STARTED_AT`→**`BGNG_DT`** · `COMPLETED_AT`→**`CMPTN_DT`** · `LAST_ERROR`→**`LAST_ERR_MSG_CN`** |
| `LS_META_REPL_OUTBOX` | `PAYLOAD`→**`PAYLOAD_CN`**(타입 `text` 유지) · `STATUS(20)`→**`STTS_CD(16)`** · `RETRY_CNT`→**`RTRY_NMTM`** · `PROC_DT`→**`PRCS_DT`** |

- **재시도는 `RTRY`(행안부)이지 `RTY`(사업)가 아니다** — 우선순위를 거꾸로 적용해 `LS_DATA_INGEST` 에서 이미 맞던 이름을 바꿨다가 되돌린 이력(구 V172→V175)이 있다.
- **처리는 `PRCS`다** — `PROC` 는 *프로세스*라 뜻이 달라진다(`LS_DATA_INGEST.PROC_STTS_CD`→`PRCS_STTS_CD` 와 같은 교정).
- **폭 축소 2건은 fail-closed** — `ALTER TYPE` 직전에 실제 최장값을 세어 목표 폭을 넘으면 `RAISE EXCEPTION` 으로 중단한다(조용한 절단 금지). `DO` 블록이 원자적이라 중단 시 개명도 남지 않는다.
- **RENAME 이지 재생성이 아니다** — `LS_META_REPL_OUTBOX` 의 미완 복제 이벤트가 사라지면 포털 메타가 영구 stale 이 된다(승인 트랜잭션이 이미 커밋돼 재발행 트리거가 없다).
- **자바 필드명도 함께 개명**(물리명 camelCase 미러 관례). 값 상수(`JOB_LABELING_BATCH`·`STATUS_*`)의 **이름과 값은 불변** — 컬럼이 아니라 값의 의미를 가리킨다.
- **이번에 손대지 않은 것**: `OUTBOX_SN`(테이블명 축과 함께 갈 항목) · `SNPSHT_HASH`(**포털 DB 복제본**이 같은 이름을 써 한쪽만 바꾸면 복제가 깨진다) · 테이블명 · 인덱스/제약/시퀀스 이름(인덱스 *정의*는 RENAME 을 자동 추종하나 이름의 `status` 토큰은 남는다).
- **★`PAYLOAD_CN` 은 확정이다 — 낱말을 재조합한 `PYLD_CN` 으로 바꾸지 말 것.** 근거 넷: ①행안부 공통표준에는 페이로드/`PAYLOAD` 가 **단어·용어 양쪽 다 0건**이라 「행안부 1순위」 조항이 개입하지 않는다 ②사업표준*용어* 「페이로드내용 = `PAYLOAD_CN`」의 출처가 **`KLID-저작도구 ERD-021` — 우리가 등록한 값**이다 ③이 스키마의 payload 계열 4개(`REQ_PAYLOAD_CN`·`RESP_PAYLOAD_CN`·`PAYLOAD_CN`·`LBL_PAYLOAD`)가 전부 `PAYLOAD` 형태이고 `PYLD` 컬럼은 **0개**라 `PYLD_CN` 은 유일한 예외가 된다 ④**직계 형제가 `LS_CONTROL_NOTIFY_FALLBACK.PAYLOAD_CN`** 인데, 나머지 3종의 형태 근거로 바로 그 테이블을 인용해 놓고 4번째만 벗어나면 같은 마이그레이션 안에서 자기모순이다. 우선순위 규칙의 해석 단위는 **등록된 용어**이지 낱말 재조합이 아니며(규칙이 든 예 `DATST_NM` 도 용어 단위 채택), 「단어 조합」은 등록된 용어가 없을 때 쓰는 규칙이다.
- ⚠ **다만 사전 자체의 갈림(`PYLD` vs `PAYLOAD`)은 여전히 미결** — 사업표준*단어*에는 페이로드가 `PYLD` 로만 등록돼 있어 `PAYLOAD` 는 단어 사전에 없다. **우리 컬럼명은 확정, 사전 정합은 별건**이며 두 축을 섞지 말 것. 사전이 `PYLD` 쪽으로 확정되면 이 컬럼 하나가 아니라 **`PAYLOAD` 계열 5개 컬럼을 한 라운드로 묶어** 바꿔야 한다.
- **`PAYLOAD_CN` 타입은 `text` 유지** — 등록 용어의 도메인은 V/4000 이지만 형제 3개가 전부 `text` 이고 이 값은 영상 메타 스냅샷 전문이라 4000 을 넘을 수 있다. **폭 축소 대상이 아니다**(폭 축소는 `JOB_TYPE_CD`·아웃박스 `STTS_CD` 2건뿐).
- 회귀 가드: `V5StandardColumnRenameIT`(적용 결과 형상·읽기 경로) · `V5StandardColumnRenameGuardIT`(멱등·데이터 보존·폭 fail-closed).

### 18.3.4 통지 디바운스 누적 상태코드 폭 정합 (V7, 2026-08-15)

`LS_MON_NOTI_ACML.STTS_CD` 만 `VARCHAR(20)` 으로 남아 있었다. 같은 개념을 형제 두 원장이 이미 `VARCHAR(16)` 으로 쓰므로(`LS_CLIP_SCHEDULE_QUE`·`LS_META_REPL_OUTBOX` — 18.3.3, 그리고 그보다 앞선 `LS_BAT_RTY_WTNG`·`LS_CONTROL_NOTIFY_FALLBACK`) 한 스키마 안에서 **같은 개념이 두 폭**을 갖고 있었다 → **20 → 16**.

| 테이블 | 변경 |
|---|---|
| `LS_MON_NOTI_ACML` | `STTS_CD VARCHAR(20)` → **`STTS_CD VARCHAR(16)`** (물리명 불변) |

- **이름은 바꾸지 않는다** — `STTS_CD` 는 이미 표준 조합(상태 `STTS` + 코드 `CD`, 둘 다 행안부 공통표준*단어*)이고 어긋난 것은 폭뿐이었다.
- **★폭 16 의 출처는 사업표준*용어*다** — 「상태코드 = `STTS_CD`, 데이터타입 `V`, 길이 `16`」(출처 `KLID-저작도구 ERD-013`). 행안부 공통표준*용어*에는 **「상태코드」 단독 용어가 없어**(`STTS` 를 쓰는 용어 31건은 전부 `…상태명 = …STTS_NM / 명V300` 계열) 우선순위 규칙의 ①행안부 조항이 개입하지 않고 ②사업표준이 적용된다. 즉 남의 사전을 우회하는 것이 아니라 **우리가 등록한 값을 우리가 어기고 있던** 상황이다. ⚠ 18.3.3 의 V5 헤더가 이 16 을 "공통표준용어"라 적은 것은 **부정확**하나 값은 동일하며, 이미 적용된 마이그레이션이라 체크섬 때문에 고칠 수 없어 V7 헤더에 사실을 남겼다.
- **저장되는 값은 `PENDING`(7자)·`FLUSHING`(8자) 둘뿐**이라 잘리는 값이 없다. 그럼에도 **폭 축소는 fail-closed** — `ALTER TYPE` 직전에 실제 최장값을 세어 초과 행이 1건이라도 있으면 `RAISE EXCEPTION` 으로 중단한다(작성 시점에 일부 환경의 실데이터를 확인할 수 없었고, 확인하지 못한 것을 "없을 것"으로 가정해 자르면 그 윈도우는 어느 상태로도 해석되지 않아 **영영 flush 되지 않는다**). 사유에는 대상·목표 폭·**초과 건수·최장 길이**만 싣고 값 자체는 싣지 않는다(CWE-209). `DO` 블록이 원자적이라 중단 시 폭도 그대로다.
- **★부속 객체 보존이 이 변경의 실질 위험이다** — 이 컬럼은 인덱스 3종에 걸려 있다(`IDX_LMNA_STTS_REG`·`IDX_LMNA_STTS_MDFCN`·부분 유니크 `UK_LMNA_RAW_PENDING`). 특히 부분 유니크는 **술어**에 이 컬럼이 있어(`WHERE STTS_CD='PENDING'`) 무너지면 「영상당 열린 윈도우 1개」 불변식이 조용히 깨진다. 그래서 정의 문자열이 아니라 **실제 강제 여부**까지 테스트로 고정했다. `DEFAULT 'PENDING'`·`NOT NULL` 도 함께 확인한다(기본값이 떨어지면 새 윈도우가 상태 없이 INSERT 되어 부분 유니크가 걸리지 않는다).
- **관제 계약면 무영향** — 이 테이블은 데이터마트 뷰 4종(`V_COMPLETED_*`)에 공급하지 않는다(네 뷰 정의 본문에 참조 0건).
- ⚠ **이번에 손대지 않은 것 — 접두형 `*_STTS_CD`(`VARCHAR(20)`)**: 같은 형태가 스키마 전반에 남아 있으나, 그중 `V_COMPLETED_VIDEO.OUTPUT_STTS_CD`·`V_COMPLETED_META.RVW_STTS_CD` 는 **관제서버가 직접 SELECT 하는 뷰 출력 컬럼**이라 폭을 건드리는 순간 외부 계약면 협의 대상이 된다. 「빠뜨린 것」으로 오인해 함께 바꾸지 말 것 — 뷰 계약과 함께 별도 라운드에서 다룬다.
- 회귀 가드: `V7MonNotiAcmlSttsWidthIT`(적용 결과 형상·윈도우 라이프사이클) · `V7MonNotiAcmlSttsWidthGuardIT`(멱등·데이터 보존·폭 fail-closed·부속 객체 보존).

### 18.3.5 웹훅 멱등 원장 적용일시 표준용어 정합 (V8, 2026-08-15)

`LS_WEBHOOK_IDEMPOTENCY.APLY_DT` 는 **뜻이 다른 표준용어**를 쓰고 있었다. 이 컬럼이 담는 값은 `markProcessed` 시점, 즉 **콜백 처리 완료(`PROCESSED`)를 원장에 반영한 시각**이라 「적용」인데, `APLY` 는 「신청」의 표준 약어다 → **`APLCN_DT`**.

| 테이블 | 변경 |
|---|---|
| `LS_WEBHOOK_IDEMPOTENCY` | `APLY_DT`(신청일시) → **`APLCN_DT`**(적용일시). 타입 `timestamp`·nullable·컬럼 순서 불변 |

- **★근거는 「표준에 없어서 빌려 썼다」가 아니다** — 행안부 공통표준*용어*에 「신청일시 = `APLY_DT`」와 「적용일시 = `APLCN_DT`」가 **둘 다** 등록돼 있고(공통표준*단어*도 신청 `APLY` / 적용 `APLCN` 별개), 그중 **뜻이 다른 쪽을 골라 쓰고 있던** 상황이다. 물리명만 읽는 사람에게 이 컬럼은 「무언가를 신청한 시각」으로 읽힌다. 우선순위 규칙 ①행안부에서 판정이 끝나므로 ②사업표준은 개입하지 않는다.
- **논리명은 처음부터 '적용일시'였다** — 바뀐 것은 물리명뿐이며 의미·용도는 그대로다. 자바 필드도 물리명 camelCase 미러 관례에 따라 `aplyDt` → **`aplcnDt`**.
- ⚠ **물리 축에는 선례가 없고 용어 축에는 있다 — 두 축을 섞어 말하지 말 것.**
  - **물리 컬럼**: 이 스키마에서 `APLCN` 을 쓰는 컬럼은 **0개**, `APLY` 를 쓰는 컬럼은 **이것 하나뿐**이었다. 즉 물리명으로는 **`APLCN` 을 처음 쓰는 것**이다. (`LS_DEIDENT_PROC_LOG` 가 `DE_IDNTF_APLCN_DT` **컬럼**을 갖는다는 서술은 **사실이 아니다** — 그 테이블의 일시 컬럼은 `REQ_DT`·`RSPNS_DT`·`PRCS_BGNG_DT` 등이다.)
  - **표준용어**: 그러나 사업표준용어 사전에 **「비식별화적용일시 = `DE_IDNTF_APLCN_DT`」가 등록돼 있다**(출처 **KLID-BM 표준용어정의서 2026-05-28 배포분** — 우리 등록분이 아니라 관제·포털과 공유하는 상위 배포분, 도메인 `연월일시분초D`). 즉 **용어 축에는 `APLCN` 선례가 이미 있으며** 이번 개명은 그것과 정합하는 방향이다.
- ✅ **사업표준*용어* 사전의 override 행은 삭제했다** — 사전에 「적용일시 = `APLY_DT`」가 **우리 프로젝트 출처(`KLID-저작도구 ERD-021`)** 로 등록돼 있었고 설명에 *"gov 표준 `APLY_DT`=신청일시와 약어 충돌하나 우리 의미는 적용일시라 override 등록"* 이라고 **충돌을 인지한 채** 적혀 있었다. 그러나 행안부에 「적용일시 = `APLCN_DT`」가 **이미 있으므로** 그 override 는 애초에 성립하지 않는다 — 없는 개념을 채운 것이 아니라 **있는 개념을 다른 약어로 덮어쓴 것**이고, 「사업표준은 행안부에 없는 개념을 채우는 보충」이라는 우선순위 규칙에 어긋난다. ⇒ **개명(`APLCN_DT`)으로 정정하지 않고 행을 지웠다**(같은 용어를 두 사전에 중복 등록하지 않기 위해). 사전은 관제서버·포털과 공유하는 자산이라 **사용자 확정 후** 삭제했으며, `createdBy` 가 우리 소유임을 먼저 확인했다.
- **RENAME 이지 재생성이 아니다** — 이 원장은 **미결 위탁의 상관키 저장소**다. 행이 사라지면 ①지각 콜백이 발급 게이트를 통과하지 못하고 ②미결 스위퍼(`VlmSubmitPendingSweeper`)가 회수 대상을 잃어 그 영상의 시계열 메타가 영구 결손된다.
- **fail-closed 의 축이 V5·V7 과 다르다** — 개명에는 값이 잘릴 위험이 없다. 대신 **두 이름이 동시에 존재하는 형상**(수기 조작·부분 적용 흔적)에서 중단한다. 그대로 진행하면 애플리케이션은 새 컬럼만 보고 옛 컬럼에 남은 값은 **아무도 읽지 않는 채로 사라진다**. 사유에는 컬럼 이름과 상태만 싣고 `IDMP_KEY`(외부 상관키)는 싣지 않는다(CWE-209).
- **관제 계약면 무영향** — 이 테이블은 데이터마트 뷰 4종(`V_COMPLETED_*`)에 공급하지 않는다(네 뷰 정의 본문에 참조 0건, 실측).
- ⚠ **하위호환이 아니다 — 전진 창이 있고 영향 경로가 둘이다.** 스키마에 V8 이 적용된 뒤 구 jar 노드는 `APLY_DT` 로 SQL 을 만들어 ①**웹훅 콜백 처리**(`VlmResultService` → `markProcessed`)와 ②**위탁 제출**(`VlmTimeseriesStep` → `recordIssued`)이 모두 깨진다. ②는 엔티티에 `@DynamicInsert` 가 없어 INSERT 가 전 컬럼을 명시하기 때문이며, 그 실패가 `CustomException` 으로 승격돼 **파이프라인이 `FAILED` 로 전이**한다 — 가용성만 잃는 ①과 달리 **상태 전이와 재시도 예산까지 소모**한다. 진입점은 구 jar 노드에서 도는 잔여 배치다.
- **데이터는 잃지 않고 중복 위탁도 열리지 않는다** — 실패는 트랜잭션 롤백이고 원장 행은 미결로 남아 미결 스위퍼가 회수·재위탁한다. `recordIssued` 가 외부 호출보다 **앞**이고 실패 시 abort 하므로 원장 없는 제출이 나갈 수 없다(fail-closed).
- ⚠ **창을 여는 것은 Flyway 가 아니다** — 온프렘 2노드 이중화는 `SPRING_FLYWAY_ENABLED=false` 라 **어느 노드도 V8 을 적용하지 않고**(스키마는 `schema.sql` 로드), Flyway 가 켜진 구성은 **단일 노드**다. 즉 2노드와 Flyway 는 이 배포 형상에서 상호배타이며, 2노드에서 창을 여는 것은 **DBA 의 수동 DDL** 이다. 수동 적용 시점에 따라 **두 노드가 동시에 구 jar 인 구간**이 생길 수 있어 "한 노드만 구 jar" 가정보다 불리하다. ⇒ **V5 와 마찬가지로 「정지 후 배포」를 권장**한다. 절차는 `deploy/onprem/docs/09-operations-runbook.md` 「V8 배포 시 주의」 절.
- 회귀 가드: `V8WebhookIdempotencyAplcnDtRenameIT`(적용 결과 형상·**엔티티 매핑**·원장 라이프사이클) · `V8WebhookIdempotencyAplcnDtRenameGuardIT`(멱등·데이터 보존·fail-closed·컬럼 순서·부속 객체 보존).
- ★**엔티티 매핑 가드가 이 라운드의 핵심이다** — 필드명만 바꾸고 `@Column` 을 빠뜨리거나 그 반대여도 **컴파일은 통과**하고, `ddl-auto=validate` 도 이를 **잡지 못한다**(mutation 으로 실증: 매핑을 옛 이름으로 되돌려도 컨텍스트는 정상 기동했고 두 매핑 테스트만 실패했다).

### 18.3.6 작업 배정·이벤트 로그 테이블 표준용어 정합 (V9, 2026-08-15)

두 테이블의 물리명에 **어느 사전에도 등록되지 않은 영문 서술형**이 남아 있었다. V5 가 정리한 `STATUS`·`PAYLOAD`·`RETRY_COUNT` 계열과 같은 부류이며, 이번에는 **컬럼이 아니라 테이블명** 축이다.

| 옛 물리명 | 새 물리명 | 근거 |
|---|---|---|
| `LS_TASK_ASSIGNMENT` | **`LS_TASK_ALTMNT`** | 배정 = `ALTMNT` (**행안부 공통표준단어**, 1순위) |
| `LS_TASK_EVENT_LOG` | **`LS_TASK_EVNT_LOG`** | 이벤트 = `EVNT` (**사업표준단어** — 행안부에 이 개념이 없어 2순위 적용) |

- **비표준 토큰은 `ASSIGNMENT`·`EVENT` 둘뿐이다** — `TASK`(업무)·`LOG`(로그)는 **양쪽 사전에 이미 등록**된 약어라 손대지 않는다. CSV 정본 전수 대조 결과이며, 검색 API 는 상한 때문에 「미등록」 오판을 내므로 판정 근거로 쓰지 않았다.
- ⚠ **배정을 `ALOT`(할당)로 쓰지 않는다** — 행안부에 「할당 = `ALOT`」가 **따로** 등록돼 있어 뜻이 갈린다. 이 테이블이 담는 것은 REVIEWER 가 WORKER 에게 **몫을 나누어 정한** 배정이므로 `ALTMNT` 다.
- ⚠ **`EVNT` 는 행안부에도 있으나 그쪽 뜻은 「행사」다.** 우리 뜻(이벤트)의 등록은 사업표준이며, 이는 **행안부에 우리 개념이 없어** 2순위가 적용된 것이지 있는 개념을 덮어쓴 것이 아니다.

**★컬럼은 하나도 바뀌지 않는다.** `ASSIGNMENT_ID`·`ACTOR_USER_NO`·`SUBJECT_USER_NO`·`PREV_USER_NO` 는 전부 **사업표준용어 등록분**이라 이미 정합이다. 우선순위 규칙의 해석 단위는 **등록된 용어**이지 낱말 재조합이 아니며(V5 의 `PAYLOAD_CN` 판정과 같은 논리), 「단어 조합」은 **등록된 용어가 없을 때** 쓰는 규칙이다. 테이블명만 용어 등록으로 보호되지 않아 단어 축 판정을 받았다.

**★자바 식별자도 바꾸지 않는다(인지·수용한 표기 드리프트).** 엔티티 클래스는 `LsTaskAssignment`·`LsTaskEventLog` 그대로이고 필드·Q클래스·API 경로·응답 필드·FE 타입도 불변이다. 결과적으로 클래스명과 테이블명의 표기가 갈리는데, **클래스 다이어그램은 자바 축이라 별개**이고 클래스 개명은 Q클래스·임포트까지 번져 **범위가 다르다**. 정적 가드 `TaskTableLegacyNameRemovalTest`(`자바_식별자는_개명하지_않는다`)가 이 경계가 조용히 확대되는 것을 막는다.

**★이 라운드의 핵심 경계는 부속 객체다.** `ALTER TABLE ... RENAME TO` 는 **시퀀스·제약·인덱스 이름을 따라오게 하지 않는다.** 정의는 자동 추종하므로 하나를 빠뜨려도 **애플리케이션은 멀쩡히 동작**하고 어긋남은 카탈로그를 직접 볼 때에야 드러난다 — 기능 테스트로는 절대 잡히지 않는 유형이다.

| 종류 | 개수 | 예 |
|---|:--:|---|
| 테이블 | 2 | `ls_task_altmnt` · `ls_task_evnt_log` |
| IDENTITY 시퀀스 | 2 | `ls_task_altmnt_assignment_id_seq` · `ls_task_evnt_log_evnt_id_seq` |
| 인덱스(PK 2 · UNIQUE 1 · 일반 4) | 7 | `uk_ls_task_altmnt` · `ix_ls_task_evnt_log_raw` … |
| FK 제약 | 2 | `fk_ls_task_altmnt_raw` · `fk_ls_task_evnt_log_raw` |

> **★시퀀스 하나는 단순 접두 치환이 아니다.** 옛 이름 `ls_task_event_log_event_seq_seq` 는 **존재하지 않는 `event_seq` 컬럼**을 이름에 달고 있었다(실제 컬럼은 `evnt_id`) — 과거 컬럼 개명이 시퀀스를 빠뜨린 **잔재**이며, 그 잔재의 존재 자체가 위 경계의 증거다. V9 에서 `ls_task_evnt_log_evnt_id_seq` 로 **바로잡았다**. 기계적으로 치환한 이름을 기대하면 어긋난다.

- **RENAME 이지 재생성이 아니다** — `LS_TASK_EVNT_LOG` 는 감사 원장이다. 행이 사라지면 ①V4 가 `LS_TASK_ASSIGN_HISTORY` 를 지울 때 근거로 삼은 **재배정 증적**이 사라지고 ②비식별 신고·프레임 폐기 차단 판정(`ReviewApprovalGate.hasEverApproved`)의 **fail-closed 2순위 축**인 `APPROVE` 이력이 사라져 **승인 이력이 있는 영상에 신고가 통과**한다. `LS_TASK_ALTMNT` 가 사라지면 작업 배정이 통째로 풀린다.
- **fail-closed 의 축은 V8 과 같다** — 개명에는 값이 잘릴 위험이 없다. 대신 **옛/새 이름이 동시에 존재하는 형상**에서 중단하며, 두 짝을 **개명 전에 모두** 검사해 사유가 어느 짝의 것인지 헷갈리지 않게 한다. 사유에는 테이블 이름만 싣고 원장 값(사용자 번호·사유 문구는 PII 인접)은 싣지 않는다(CWE-209/359).
- ⚠ **관제 계약면 무영향** — 두 테이블은 데이터마트 뷰 4종(`V_COMPLETED_*`)에 공급하지 않는다.
- ⚠ **하위호환이 아니며 이 계열에서 영향이 가장 넓다.** 구 jar 노드는 옛 테이블명으로 SQL 을 만들어 **배정·재배정 / 검수 제출·승인·반려 / 작업·검수·영상 목록 조회 / 통계 / 개인정보 선언 감사**가 전부 깨진다. **V5·V8 과 달리 자기치유되지 않는다** — 두 앞선 개명은 실패분이 미결 큐·발신함에 남아 신 jar 노드가 이어 처리했지만, 여기서 실패하는 것은 **사람이 방금 누른 조작**이라 다시 시도해야 한다(데이터는 트랜잭션 롤백이라 잃지 않는다). ⇒ **「정지 후 배포」를 강력 권장**한다. 절차·13종 전수 확인 쿼리는 `deploy/onprem/docs/09-operations-runbook.md` §4-0-2.
- ⚠ **동결 대상은 옛 이름을 그대로 갖는다** — `V1`~`V8` 원문과 `db-archive/` 는 체크섬·원문 보존 때문에 고칠 수 없고, **V4 가 V9 보다 먼저 돌므로** V4 안의 `ls_task_event_log` 참조는 순서상 정상이다. 다만 그 동결 SQL 을 **라이브 스키마에서 재실행**하는 IT 2건은 그 이름이 없어 깨지므로 각각 스크래치 픽스처(`V4DropUnusedTablesIT`)·재생 사본 치환(`LsDataRawOrphanCleanupIT`)으로 보정했다.
- 회귀 가드: `V9TaskTableStdTermRenameGuardIT`(10건 — **13종 전수**·멱등·부분 개명 완주·데이터 보존·컬럼 무변경·UNIQUE/FK **실강제**·CASCADE **실동작**·IDENTITY **채번 연속**·fail-closed·CWE-209) · `V9TaskTableStdTermRenameIT`(5건 — 라이브 스키마 옛 이름 잔존 0 / 새 이름 13종 / 컬럼 불변 / 시퀀스 이름 정정 / **엔티티 매핑 실동작**) · `TaskTableLegacyNameRemovalTest`(3건 — 옛 이름 부활 **정적 차단**).
- ★**두 가드 모두 mutation 으로 실증했다** — 엔티티 `@Table` 을 옛 이름으로 되돌리면 정적 가드 2건이, V9 에서 시퀀스 개명 1건을 빼면 IT 2건이 각각 실패한다. `ddl-auto=validate` 가 실동작하지 않아 **기동 성공은 근거가 되지 못하므로** 이 실증이 가드의 유효성 근거다.

## 18.4 ~~관제서버 소유 MNG_*~~ → **전량 제거 완료 (2026-08-04)**

> ★ **현재 관제 소유 공유 테이블은 0개다.** 아래 목록·서술은 **제거 이전 상태의 이력**이며, 각 항목이 어떤 마이그레이션으로 사라졌는지는 이 절의 정정 문구를 따른다. (구 목록 7개 = `MNG_ACCT_USER`(V169) · `MNG_CLIP_MASTER`·`MNG_CLIP_EVNT_LST`·`MNG_RESOURCE_CCTV`·`MNG_EX_LOCAL_GOV`(V167) · `MNG_EX_EVNT_TYPE`·`MNG_EX_EVNT_TYPE_MAP`(V168). 앞서 `MNG_CLIP_SCHEDULE_QUE`(V162 개명)·`MNG_ACCT_AUTHRT`·`MNG_ACCT_USER_AUTHRT`(V165 DROP) 포함 **총 9종**.) 회귀 가드: `MngAcctUserTableRemovalTest`(`MNG_` 접두 실행 참조 0). ⚠ **18.4.1 은 2026-08-05 에 SoT·`ddl-auto=validate` 서술을 정정 완료**(V168 반영)했고, 같은 날 `LS_EVNT_TYPE`/`LS_EVNT_CTGRY` 를 **§18.2 인벤토리(「이벤트유형 마스터」)에 등재 완료**했다.

`MNG_ACCT_USER`, `MNG_CLIP_MASTER`, `MNG_CLIP_EVNT_LST`, `MNG_RESOURCE_CCTV`, `MNG_EX_EVNT_TYPE`, `MNG_EX_EVNT_TYPE_MAP`, `MNG_EX_LOCAL_GOV`.

> **⚠ 목록 누락 정정 (2026-08-04)**: 이 목록은 그동안 `MNG_CLIP_EVNT_LST`(V63 생성, `TrainingVideoIngestTx`·`MngClipEvntLstRepository` 등에서 실사용 중)를 빠뜨린 채 개수를 세고 있었다. 그 결과 아래 V162·V165 정정 문구의 산식이 실제보다 1 적었고, 같은 절 아래 "ERD-024 정본 매핑" 문단이 이 테이블을 언급하는 것과 **자기모순**이었다. 개수를 실측 기준으로 재정정한다(`docs/관제팀-공유테이블-변경금지-가이드.md` 의 산식과 이제 일치).

> **2026-08 정정**: 과거 이 목록에 있던 `MNG_CLIP_SCHEDULE_QUE`(배치 큐)는 관제 소유가 아니라 **저작도구 자체 소유**임이 확인되어 `LS_CLIP_SCHEDULE_QUE` 로 개명됐다(V162, 18.3.2). 관제 공유 테이블은 10개가 아니라 **9개**다.

> **⚠ 배포 전 확인 필요(후속, 2026-07-31 — V147)**: `MNG_CLIP_MASTER`(16컬럼)·`MNG_CLIP_EVNT_LST`(12컬럼)를 **ERD-024 정본 기준으로 전량 매핑**했다. 매핑이 늘어난 만큼 `ddl-auto=validate` 보호 대상도 늘어, **실 관제 스키마와 컬럼명·타입이 하나라도 다르면 stg/prd 기동이 실패**한다. 현재 이 매핑은 **ERD-024(2026-06-10 조회) 신뢰에 전적으로 의존**하므로, **배포 전 dev 환경에서 실 관제 DB 를 대상으로 1회 검증**이 필요하다(로컬/테스트는 자체 stub 이라 드리프트를 잡지 못한다). 관제팀 공유 문서: `docs/관제팀-공유테이블-변경금지-가이드.md`.

> **~~역할 분리 (2026-06)~~ → 폐기(V169)**: 구 서술은 "`MNG_ACCT_USER` 는 관제가 채우고 저작도구는 READ 전용(`@Immutable`)" 이었고 `MngAcctWriteGuardTest` 가 쓰기 0건을 지켰다. **실측 결과 아무도 채우지 않았다** — 관제 2차 실DB 에 `MNG_` 접두 테이블이 0개이고 저작도구 쓰기 경로도 0이라, 신규 사용자는 DBA 가 손으로 넣기 전까지 역할 클레임이 404 로 막혔다.

> **사용자 마스터 이관 + 자동등록 (2026-08-04, V169)**: `MNG_ACCT_USER` 를 **저작도구 소유 `LS_ACNT_USER`** 로 이관·DROP 하고, 채우는 주체를 **역할 클레임 시점의 자동등록**(관제 `localStorage` 인계값 `userId`·`userNm`, 원자 upsert)으로 바꿨다. ★이것으로 **관제 `MNG_*` 9종 제거가 완료**됐다(V162·V165·V167·V168·V169 — 남은 관제 소유 공유 테이블 **0개**). 물리명은 표준용어로 정정했다(`ACCT`→`ACNT` 계정, `USER_EMAIL`→`USER_EML_ADDR` — `EMAIL` 은 표준단어 `EML` 의 금칙어, `UPD_DT`→`MDFCN_DT`). 회귀 가드: `MngAcctUserTableRemovalTest`(구 쓰기 금지 가드를 **`MNG_` 접두 참조 0** 으로 강화 승계). ⚠ 구버전 jar 롤백용 재생성 DDL·데이터 복원·Flyway 이력 정리 절차는 **V169 파일 주석**에 보존돼 있다.

> **최종로그인일시 신설 (2026-08-17, V12)**: `LS_ACNT_USER.LAST_LGN_DT`(행안부 공통표준용어 `최종로그인일시`, 표준도메인 `연월일시분초D`, `timestamp without time zone`, **nullable**). 사용자 관리 화면(`/manage/users`)이 「최신 로그인」 컬럼을 그리는데 **그 데이터가 존재하지 않아** 화면이 등록일(`REG_DT`)을 폴백으로 표시하고 있었다 — 가입 시각이 "최근 로그인" 헤더 아래 나오는 **거짓 표기**였다. 두 값은 용도가 다른 별개 축이라(등록일=가입 이력 / 최종로그인일시=휴면 계정 판단) 화면도 **두 컬럼을 함께** 보인다.
> · **기록 지점** — 저작도구엔 독립 로그인 UI 가 없어 "로그인" 이벤트가 없다. 관측 가능한 가장 가까운 사실인 **JWT 검증을 통과한 INTERNAL 요청**이 기록 지점이며 `JwtAuthenticationFilter` → `LastLoginRecorder` → `LastLoginTouchTxService` 가 담당한다.
> · **매 요청 UPDATE 가 아니다** — 그 경로는 모든 요청에서 돌기 때문에 `UserRepository.touchLastLogin` 의 **조건부 UPDATE**(`WHERE LAST_LGN_DT IS NULL OR LAST_LGN_DT < now - throttle`)로 최소 간격을 강제한다(설정 `authoring.user.last-login.throttle-minutes`, 기본 5분). 2노드 Active-Active 라 애플리케이션 로컬 캐시가 아니라 **이 조건절**이 정확성의 근거다(CWE-362).
> · **기록 실패는 요청을 죽이지 않는다**(fail-open — 보안 게이트가 아니라 부가 기록). try/catch 는 트랜잭션 경계 **바깥**에 둔다(안쪽이면 rollback-only 로 커밋 시점에 다시 터진다). 그 배치는 `LastLoginRecorderTest(예외를_삼키는_쪽은_트랜잭션_경계가_아니다)` 가 구조로 고정한다.
> · **백필하지 않는다** — 이 컬럼 이전의 접속 기록은 어디에도 없어 무엇을 넣어도 지어낸 값이다. 기존 전 행은 NULL 로 남고 각자 다음 접속에서 채워진다. 같은 이유로 DB DEFAULT 도 두지 않는다.
> · **PORTAL 채널은 기록하지 않는다** — 포털 토큰의 `sub` 는 문자열 식별자로 쓰이고 이 마스터의 `USER_NO` 와의 매핑이 **확인되지 않았다**. 추측 조인은 남의 행에 접속 기록을 쓰는 것이다(CWE-639). `MDFCN_DT` 도 건드리지 않는다(관제 인계 표시정보의 변경 시각이라 뜻이 오염된다).
> · NULL 허용 + DEFAULT 없는 `ADD COLUMN` 이라 **하위호환**이며 롤링 재기동으로 배포할 수 있다. 회귀 가드: `LastLoginRecordIT` · `LastLoginRecordFailOpenIT` · `FlywaySquashBaselineIT`.

> **죽은 권한 테이블 2종 제거 (2026-08, V165)**: 위 목록에 있던 `MNG_ACCT_AUTHRT`·`MNG_ACCT_USER_AUTHRT`는 역할 축이 `LS_USER_ROLE`(V75)로 이관된 뒤 **런타임 참조가 0**이 됐는데도 엔티티·시드만 잔존했다(dev 실DB 3행/5행이 전부 dev-seed 시드값 — 외부 유입 0). V165 가 두 테이블을 DROP 하고 엔티티(`MngAcctAuthrt`)·dev 시드·테스트 픽스처를 함께 제거했다. 관제 공유 테이블은 9개가 아니라 **7개**다. 회귀 가드: `DeadAcctAuthrtTableRemovalTest`. ⚠ 구버전 jar 롤백 시 `ddl-auto=validate` 기동 실패를 막는 **재생성 DDL·Flyway 이력 정리 절차는 V165 파일 주석**에 보존돼 있다.

### 18.4.1 이벤트 타입 진실원 — `LS_EVNT_TYPE` / `LS_EVNT_CTGRY` (저작도구 소유, V168)

> ⚠ **정정(2026-08-05)**: 이 소절 제목·첫 문단은 위 18.4 가 이미 폐기라고 명시한 "관제 소유 `MNG_EX_EVNT_TYPE`(+`MNG_EX_EVNT_TYPE_MAP`) 가 SoT" 서술을 그대로 들고 있던 **드리프트**였다(같은 절 아래 "표시명 그룹" 문단은 이미 V168 이후 사실을 서술하고 있어 한 절 안에서 자기모순이었다). V168(2026-08-04)이 이 2종을 DROP 하고 **저작도구 소유** `LS_EVNT_TYPE`(유형)·`LS_EVNT_CTGRY`(카테고리, 표시명 폴백 원천)를 신설했다 — 아래 본문으로 정정한다. **두 테이블은 §18.2(LS_* 핵심 테이블)의 「이벤트유형 마스터」 소절에 등재 완료**(2026-08-05)했다.

영상 이벤트 타입의 **단일 진실원(SoT)** 은 저작도구 소유 `LS_EVNT_TYPE`(+카테고리명 원천 `LS_EVNT_CTGRY`)다. 관제 인입 소비 시점(`TrainingVideoIngestTx`)에 미등록 유형코드가 **자동 등록**되고(원자 upsert), 이미 등록된 유형은 인입값으로 덮어쓰지 않는다(운영자 정정 보호). 관제 마스터(`MNG_EX_EVNT_TYPE`/`_MAP`)는 더 이상 조회하지 않는다 — READ 연동·`@Immutable`·`ddl-auto=validate` 구조는 **폐기**됐다. (구 `EvntType` enum 6종/`EVT_*` 하드코딩은 이전에 **폐기·삭제**됨 — 관제 실코드와 어긋났던 잔재.)

- **코드 체계**: `EVNT_TYPE_CD` = `EV` + 대분류(2) + 카테고리(2) + 상세(2), 예 `EV02000201`. 3단계 계층(`EVNT_CLS_CD` 대분류 / `EVNT_CTGRY_CD` 카테고리 / 상세). `CLCT_YN`=수집여부.
- **라벨**: 유형별 이름(`LS_EVNT_TYPE.EVNT_NM`)이 비어 있으면 **카테고리명**(`LS_EVNT_CTGRY.EVNT_CTGRY_NM`, `EVNT_CLSF_CD+EVNT_CTGRY_CD` 조인)으로 폴백한다 — 관제 마스터에는 애초에 유형별 이름이 없었고 사람이 읽는 이름은 카테고리 레벨에만 있었다(구 이관 전 `MNG_EX_EVNT_TYPE_MAP` 의 `CD_TYPE='02'` 10건이 그 원천이었다).
- **표시명 출처**: 이벤트유형 관리 화면(`/manage/event-types`, REVIEWER 전용)은 판정 결과 문자열에 더해 그 값이 4단 중 어느 단계에서 온 것인지도 함께 받는다 — 운영자 지정명이면 `operator`, 관제 수신 유형명이면 `control`, 카테고리명이면 `category`, 유형코드면 `code`. 판정은 여전히 서버 하나(`EventTypeDisplayNamePolicy`)가 하며 화면은 원본 필드들로 폴백을 다시 계산하지 않는다 — 계산이 두 곳으로 갈리면 어느 쪽이 맞는지 알 수 없어진다. 화면은 이 값을 표시명 옆에 칩으로 병기해, 같은 이름이 여러 줄에 보이는 까닭(유형별 이름이 아직 없어 카테고리명으로 접힌 상태 등)을 표시명 문자열만으로는 알 수 없던 것을 드러낸다. 기존 응답 필드는 이름·타입·의미가 그대로이고 이 출처 값(`dsplNmSource`)은 추가된 필드다(하위호환). 우리가 모르는 출처 값을 받아도 화면은 표시명을 그대로 보이고 칩만 생략한다(알려진 네 값 밖으로는 추측하지 않는다 — 대소문자를 맞추거나 빈 문자열을 임의 단계로 승격시키지 않는다).
- **저작도구 노출 정책**: 라벨 해석(매핑)은 **전체 코드** 대상(들어오는 어떤 EV-코드든 한글명), 필터/선택 드롭다운은 **`CLCT_YN='Y'` 중 제외 대분류가 아닌 유형**을 **표시명으로 묶어** 노출한다. 구현: `eventtype/service/EventTypeService`(`groupIndex`/`filterOptions`/`codeLabelMap`/`filterKeyOf`, Caffeine 캐시) + `GET /api/v1/event-types`·`/labels`.
  - **표시명 그룹 (2026-08-05, R3~R5)**: 축은 유형(V168)이지만 관제가 유형별 이름(`EVNT_NM`)을 아직 보내지 않아 표시명이 **카테고리명으로 폴백**되면서 드롭다운에 같은 이름이 여러 번 떴다(침수 3·교통사고 3·화재 2). 그래서 **표시명이 같은 유형들을 옵션 1건으로 접는다** — `categoryKey`=그룹 **대표코드**(그룹 내 최소 유형코드), `memberCodes`=그룹 전체 코드. **파라미터 값은 여전히 코드**이며(표시명 문자열을 필터 파라미터로 올리지 않는다), **비대표 코드로 들어온 기존 북마크**(`?eventTypeCd=EV01000103`)도 `codesForFilterKey` 가 그룹 전체로 해석한다. 프리셋도 `filterKeyOf` 가 대표코드로 접어 **그룹 전체에 적용**된다(V168 이 "의도된 동작 축소"로 남겨둔 부분의 복구). **영구 병합이 아니다** — 관제가 이름을 보내거나 운영자가 표시명을 지정하면 그 유형만 자기 이름을 얻어 그룹이 **자동으로 쪼개진다**. 응답 스키마(`EventTypeResponse` 필드명·타입)는 불변.
  - **제외 대분류는 설정값**(구 소스 상수 `IGNORE_CLASS_CD="08"` 폐기): `LS_SYSTEM_CONFIG` `eventtype.excluded-class-codes`(JSON 문자열 배열, 기본 `["08"]`=배회, V161 시드)를 REVIEWER 가 시스템 설정 화면(`PUT /v1/manage/configs/{key}`)에서 배포 없이 편집한다. 원소는 2자리 숫자·최대 20개로 검증하며, 조회 실패 시 기본값 `08` 로 폴백(fail-safe). 값 변경 시 `eventType` 캐시가 함께 무효화되어 즉시 반영된다.
  - **기존 프리셋 호환**: 어떤 카테고리가 나중에 제외되어도 그 카테고리에 매핑된 **기존 프리셋의 이름·설명·라벨 편집은 계속 가능**하다(`PresetService.update` 는 `EVNT_TYPE_CD` 가 실제로 바뀔 때만 유효성 검증). 신규 생성·다른 제외 카테고리로의 재매핑은 기존대로 400 차단.
- **저장 단위**: 영상 `LS_DATA_RAW.EVNT_TYPE_CD`=상세 EV-코드(관제 적재값), 프리셋 `LS_LABEL_PRESET.EVNT_TYPE_CD`=**표시명 그룹 대표코드**(필드명은 하위호환으로 `categoryKey` 그대로 — 값의 입도만 카테고리→유형→그룹 대표코드로 바뀌었다). 프리셋 매칭은 영상 EV-코드를 `EventTypeService.filterKeyOf`로 대표코드로 접어 조회한다(`PresetLabelLookupService.togglesFor`, 회귀 가드 `PresetLabelLookupGroupMatchingIT`).
- **마이그레이션**: 구 `EVT_*` 잔존 데이터는 V72(`V72__migrate_preset_evnt_type_to_category.sql`)가 프리셋→categoryKey, 영상→대표 EV-코드로 정정(멱등·운영 no-op). V168(`V168__create_ls_evnt_type_and_drop_mng_masters.sql`)이 프리셋 축을 카테고리키(6자리)→**그 카테고리의 최소 EV-코드**로 재정정했다(의도된 동작 축소 — 구 카테고리 프리셋은 소속 상세코드 전부에 적용됐으나 전환 후엔 대표 코드 1개에만 적용. 축소된 유형은 필터 드롭다운에 각자 옵션으로 노출돼 REVIEWER 가 화면에서 프리셋을 추가해 복구할 수 있다). 관제 신규 코드 추가는 관제가 관리(저작도구 마이그레이션 없음, 인입 소비 시점 자동등록).
- `LS_EVNT_TYPE`/`LS_EVNT_CTGRY` 는 이제 **저작도구 소유** 객체라 ERD-024(관제 공유 클립 ERD, MNG_* 전용)의 관할이 아니다. §18.2 인벤토리 등재와 **LogiCraft 등재 모두 완료**(2026-08-05) — 신규 **`ERD-025` 「이벤트유형 마스터 ERD (고도화, PostgreSQL)」**(DOMAIN-003 소속, 근거 `ADR-042`)에 V168 DDL 과 1:1 등재했다. 구 조달처가 관제 공유 마스터라 **D8/D9 산출물에서 통째로 빠져 있었는데**(MNG_* 는 산출물 비대상 + 대체 테이블은 미등재), 이제 다음 `/cc-doc-gen` 재생성 시 D8/D9 에 포함된다. ⚠ **현재 저장소의 D8/D9 파일에는 아직 반영돼 있지 않다** — 재생성 전까지는 위키(§18.2)가 이 2종의 유일한 문서 원천이다.

## 18.5 Quartz

`QRTZ_*` (JobStore, PostgreSQLDelegate, BYTEA) — 배치 스케줄 상태. → [07](07-batch-pipeline.md).

> 상세 컬럼·ERD는 D8/D9 참고 → [19 설계 문서 카탈로그](19-external-security-cvat.md#194-설계-문서-카탈로그).
