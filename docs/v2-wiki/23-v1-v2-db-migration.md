# v1(MariaDB) → v2(PostgreSQL) 이관 분석 — 프로젝트 단위 → 영상 단위

> **범위(확정·2026-06-25 확장)**: **① 영상 목록 ② 라벨링 결과**(핵심, §1~5) + **③ 검수/완료 상태 ④ 작업자 배정 ⑤ 이슈 ⑥ 증강(레거시)**(확장 스코프, §6). 모두 v1 **프로젝트 단위 → v2 영상(RAW_SN) 단위로 collapse** 한다. 제외: 프로젝트 메타 자체·사용자 계정(공용 MNG)·통계·게시판·라벨 이력(168만)·시계열 메타(v1 0건).
> **데이터 출처(실DB 검증)**: v1 = `nt sql klid` → MySQL `192.168.102.102:13307/klid_system`, v2 = `nt sql klid_system_246` → PostgreSQL `192.168.102.246:15432/klid_system`(스키마 `public`). 본 문서의 컬럼·건수·JSON 포맷은 **2026-06-25 실DB 조회로 확정**.
> 구조 정본은 `backend/.../db/migration/V*.sql`(v2) — 충돌 시 코드 우선.
>
> ⚠ **정합 메모(2026-08-19)** — 본 문서는 **v1(외부 MariaDB) 실측치는 재검증 불가**(2026-06-25 조회 당시 상태를 그대로 신뢰)이므로 그 수치·건수·JSON 포맷은 손대지 않았다. 반면 **v2(우리 PostgreSQL) 쪽 테이블·컬럼명은 현재 코드로 재검증 가능**하여, 2026-06-25 이후 개명·제약 변경된 항목만 아래에서 정정했다(§1-A·§1-C·§1-D·§6-A). 또한 이 문서가 인용하는 `V1`·`V34`·`V35`·`V52`·`V67`·`V169` 같은 마이그레이션 번호는 **2026-08-13 스쿼시 이전(구 V0~V185 체계)의 이력 표기**다 — 그 파일들은 지금 `V1__baseline.sql` 에 전부 접혀 있고 원문은 `backend/src/test/resources/db-archive/migration/` 에 보존돼 있다(Flyway 미실행 경로). 상세는 [18 데이터베이스](18-database.md) 헤더 「한글 서술 안의 버전번호 두 갈래」 참조 — 이 문서를 스쿼시 이후 새 `V1`~`V13` 체계와 혼동하지 말 것.

---

## 0. 핵심 발견 — 실 구조는 설계서 분석보다 v2와 거의 동형

기존 설계서(v1-wiki V1.4) 기반 분석은 `LS_DATA_SRC`를 영상으로 보고 라벨이 이력에만 있다고 추정했으나, **실 v1 DB는 v2처럼 영상/프레임/라벨이 이미 분리**돼 있어 매핑이 훨씬 단순하다.

| 의미 | v1 테이블 (실측 건수) | v2 테이블 | 관계 |
|------|----------------------|-----------|------|
| **영상 목록** | `LS_DATA_RAW` (2,008) | `ls_data_raw` | 동형(영상 1건) |
| 프레임(원천) | `LS_DATA_SRC` (102,574) | `ls_data_src` | 동형(영상당 N프레임) — 라벨이 참조하므로 동반 이관 필요 |
| **라벨 결과(현재본)** | `LS_DATA_LBL` (≈295K) | `ls_data_lbl` | 동형. 좌표=`POINT`, 트랙=`TRCK_ID` |
| 라벨 클래스 정의 | `LS_PJT_LBL` (24, 8개 PJT) | `ls_label` (13) | 의미 매핑 필요(아래 GAP②) |
| 라벨 이력(누적) | `LS_DATA_LBL_HSTRY` (1,680,505) | — | **이관 안 함**(현재본만 필요) |

- v1 `LS_DATA_LBL`은 이미 `DATA_RAW_SN`(영상)+`DATA_SRC_SN`(프레임)을 직접 보유 → **PJT_SN만 버리면 영상 단위로 평탄화 완료**(별도 재구성 불필요).
- `ATRB`(라벨 속성) 컬럼은 샘플 전수 NULL — 속성 이관은 사실상 없음.
- `TRCK_ID`(트랙)는 PJT 2/4/7/9에서 대량 사용 → v2 `trck_id`로 직접 이관.

---

## 1. 테이블별 컬럼 매핑 (실 v1 → 실 v2)

### 1-A. 영상: `LS_DATA_RAW` → `ls_data_raw`

v2 `ls_data_raw`는 **15컬럼으로 매우 슬림**, v1은 48컬럼. 대부분 v1 메타가 v2에 자리가 없다(GAP③).

| v1 컬럼 | v2 컬럼 | 변환 |
|---------|---------|------|
| `DATA_RAW_SN` (PK) | `raw_sn` (PK, IDENTITY) | ID 재발번 권장 + 대응표 보관 |
| (없음) | `vms_clip_id` **NOT NULL UK(128)** | ⚠ **GAP①** — v1에 클립ID 없음 |
| `VMS_CCTV_ID`(30) | `vms_cctv_id`(64) | 1,215/2,008만 채워짐. ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — *"NOT NULL(64)"* 는 더 이상 사실이 아니다. 관제 회신("CCTV 식별자가 없는 영상이 존재")에 따라 **V185 에서 NOT NULL 제약이 해제**돼 현재는 nullable 이다(근거: `deploy/onprem/db/schema.sql` `ls_data_raw.vms_cctv_id`). 기본값 부여가 더 이상 필수는 아니며, 미채움은 NULL 로 그대로 이관 가능 |
| `EVNT_TYPE_CD` | `evnt_type_cd` | 직접 |
| `LCLGV_CD` | `lclgv_cd` | 직접 |
| `PRVC_YN` | `prvc_yn` **NOT NULL** | NULL→기본값('N' 등) |
| `DE_IDNTF_YN` | `de_ident_yn` **NOT NULL** | NULL→'N' |
| (없음) | `prvc_type_cd` **NOT NULL(16)** | 기본값 부여 필요 |
| `RAW_FILE_PATH`(1000) | `raw_file_path_nm`(500) | ⚠ 길이 축소 — 경로 500자 초과분 확인 |
| `SHT_DT` | `sht_dt` | datetime→timestamp |
| `VDO_LEN`(int, **ms**) | `vdo_len_sec`(초) **+** `vdo_len_ms`(신설) | ⚠ **단위변환 + 원본보존** — VDO_LEN 은 밀리초(실측: `VDO_LEN/(FRM_CNT/FPS)=1000`, 1993/2006건). `vdo_len_sec`=÷1000(초, V2 계약), 원본 ms 는 신설 `vdo_len_ms`(**Flyway V67**, nullable, 이관본만)에 보존. 직접 복사 시 1000배 오적재. 원본은 시간단위 CCTV 영상이라 초값 수천이 정상 |
| (상태 추정) | `data_stts_cd` **NOT NULL(20)** | 이관본은 `COMPLETED`/`APPROVED` 등 고정 부여. ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — 폭 *"(32)"* 는 사실과 다르다. 현재 `character varying(20)` 이다(근거: `deploy/onprem/db/schema.sql` `ls_data_raw.data_stts_cd` — CLAUDE.md 의 "코드값 도메인은 VARCHAR(20)이 표준" 과도 일치) |
| `REG_DT`/`MDFCN_DT` | `reg_dt`/`mdfcn_dt` | 직접 |
| `AI_CRT_YN`(증강여부) | (없음) | 증강추적은 `orgnl_raw_sn` 컬럼이 담당하나 v1 부모링크 없음 → 버림. ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — 컬럼명 `parent_raw_sn` 은 더 이상 존재하지 않는다. **`ORGNL_RAW_SN`(V82 물리 rename)** 이 정식 명칭이며 데이터마트 뷰 출력도 이 이름으로 통일돼 있다(근거: `deploy/onprem/db/schema.sql` `ls_data_raw.orgnl_raw_sn` · [18 데이터베이스](18-database.md) §18.2 `LS_DATA_RAW` 행) |
| **버려지는 v1 메타** | — | `FILE_FMT,FPS,FRM_CNT,WDTH,HGT,RSLTN,BIT_RATE,VDO_CDC,ASPRT_RT,FILE_SZ,SESN_CD,WTHR_CD,HR_TYPE_CD,STDG_CD,WGS84_LAT/LOT,CCTV_NM,MNTR_CN,CLCT_*,SHT_*,LCNS` → **GAP③** |

### 1-B. 프레임: `LS_DATA_SRC` → `ls_data_src`

| v1 컬럼 | v2 컬럼 | 변환 |
|---------|---------|------|
| `DATA_SRC_SN` (PK) | `src_sn` (PK, IDENTITY) | ID 재발번 + 대응표 |
| `DATA_RAW_SN` | `raw_sn` | 1-A 영상 대응표로 재연결 |
| `FRM_NO` | `frm_no` | 직접 (UK: raw_sn+frm_no) |
| `SRC_FILE_PATH`(1000) | `src_file_path_nm`(500) | ⚠ 길이 축소 확인 |
| `SRC_BKUP_FILE_PATH` | `de_idntf_src_file_path_nm` | 백업경로=비식별 프레임 경로로 매핑 |
| `REG_DT`/`MDFCN_DT` | `reg_dt`/`upd_dt` | 직접 |
| `PJT_SN`,`DATA_STTS_CD`,`STP_CYCL`,`IGI_CYCL` 등 | (없음) | 버림 |
| `IMG_EXPLN` | `frm_expln` | ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — *"IMG_EXPLN 은 v2 에 대응 없이 버림"* 은 더 이상 사실이 아니다. 이 문서 작성(2026-06-25) **이후**에 신설된 `LS_DATA_SRC.FRM_EXPLN`(프레임설명, NIA `image.description` 작업자 수기, V103)이 같은 의미의 자리를 갖게 됐다 — 이관 시 버리지 않고 이 컬럼으로 옮길 수 있다(근거: `deploy/onprem/db/schema.sql` `ls_data_src.frm_expln` · [18 데이터베이스](18-database.md) §18.2 `LS_DATA_SRC` 행) |

### 1-C. 라벨 클래스: `LS_PJT_LBL` → `ls_label`

| v1 컬럼 | v2 컬럼 | 변환 |
|---------|---------|------|
| `LBL_SN` (PK, PJT별) | `lbl_id` (PK) | **8개 PJT의 24행을 의미 중복 제거 후 재발번**(GAP②) |
| `LBL_NM`(물/사람…) | `lbl_nm`(80) | 의미 매핑. ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — 폭 *"(64)"* 는 현재 `character varying(80)` 이다(근거: `deploy/onprem/db/schema.sql` `ls_label.lbl_nm`). **"UK" 표기도 부정확** — all-rows exact UNIQUE 는 없고, 유니크성은 **활성(`USE_YN='Y'`) 한정 대소문자무시 부분 유니크**(`UK_LS_LABEL_NM_CI`)가 강제한다 — [18 데이터베이스](18-database.md) §18.2 `LS_LABEL` 행 참조 |
| `LBL_COLR`(#hex) | `colr_vl`(7) | 직접 |
| `PRC_TYPE_CD`(BBOX/POLYGON) | `lbl_type_cd`(16) | 코드 동일 — 직접 |
| `SORT_SEQ` | `sort_seq` | 직접 |
| `PJT_SN`,`LBL_ID`(varchar),`UP_LBL_SN`,`ATRB_INFO`,`BSC_RLT_CRD_X/Y`(스켈레톤) | (없음) | 버림 |

### 1-D. 라벨 결과: `LS_DATA_LBL` → `ls_data_lbl`

| v1 컬럼 | v2 컬럼 | 변환 |
|---------|---------|------|
| `DATA_LBL_SN` (PK) | `lbl_sn` (PK, IDENTITY) | ID 재발번 |
| `DATA_SRC_SN` | `src_sn` | 1-B 프레임 대응표로 재연결 |
| `LBL_SN` | `lbl_id` | 1-C 라벨클래스 대응표로 재연결 |
| (클래스 PRC_TYPE_CD) | `lbl_type_cd` **NOT NULL** | 클래스에서 **비정규화 복사** |
| (클래스 LBL_NM) | `lbl_nm` **NOT NULL(80)** | 클래스에서 **비정규화 복사**. ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — 폭 *"(255)"* 는 현재 `character varying(80)` 이다(근거: `deploy/onprem/db/schema.sql` `ls_data_lbl.lbl_nm`) |
| `POINT`(text) | `point_cn`(text) | ⚠ **포맷 변환 필수**(§2) |
| `TRCK_ID` | `trck_id`(30) | 직접. ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — 폭 *"(64)"* 는 현재 `character varying(30)` 이다(근거: `deploy/onprem/db/schema.sql` `ls_data_lbl.trck_id`) — v1 값이 30자를 넘으면 이관 전 확인 필요 |
| `REG_DT`/`MDFCN_DT` | `reg_dt`/`mdfcn_dt` | 직접 |
| `PJT_SN`,`DATA_RAW_SN`,`TRCK_USE_YN`,`TRCK_FRM_YN`,`ATRB`,`SORT_SEQ` | (없음) | 버림(영상 연결은 src_sn→frame→raw로 도출) |
| (없음) | `reg_user_no` | v1 `REG_ID`(varchar)→`user_no`(bigint) 매핑 또는 NULL. ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — *"관제 USER_NO 매핑"* 은 낡았다. 2026-08-04(V169)부터 사용자 마스터는 **관제 `MNG_ACCT_USER` 가 아니라 저작도구 소유 `LS_ACNT_USER`** 다(관제 `MNG_*` 9종 전량 제거 — 아래 §6-B 「★2026-08-04(V169) 조인 대상 변경」과 같은 축) |

---

## 2. 좌표(POINT) 포맷 변환 — Critical

v1과 v2의 좌표 JSON 표현이 **서로 다르다**(실측 확인). 단순 복사 시 캔버스에서 깨진다.

| 타입 | v1 `POINT` (실측) | v2 `point_cn` (실측) | 변환 |
|------|-------------------|----------------------|------|
| **BBOX** | `{"x":622.5,"width":32.4,"y":131.9,"height":103.7}` (객체, x/y/w/h) | `[[x1,y1],[x2,y2]]` (좌상단·우하단 좌표쌍) | `[[x, y],[x+width, y+height]]` |
| **POLYGON** | `[{"x":..,"y":..}, …]` (객체 배열) | `[[x,y], …]` (좌표쌍 배열) | 각 `{x,y}` → `[x,y]` |

> 변환 로직은 라벨당 1회 파싱→재직렬화. 검증: 변환 후 무작위 N건을 캔버스/스크립트로 역렌더해 v1 박스와 동일 위치인지 대조.

---

## 3. GAP 정책 — 확정 (2026-06-25 결정)

### GAP① — `vms_clip_id`: 합성키가 주 경로 (조인 커버리지 검증 결과)

**확정: 합성키(`LEGACY-{DATA_RAW_SN}`)가 주 경로. 관제 조인은 극소수(6~17건)만 보조 적용.**

**조인 커버리지 전수 검증(2026-06-25):**

| 항목 | 수치 |
|------|------|
| 전체 영상 | 2,008 |
| 라벨 달린 영상(이관 핵심 대상) | **1,814** |
| CLIP_토큰 보유 → `MNG_CLIP_MASTER` 실조인 | **17** (라벨 달린 것 중 **6**) |
| → 관제 미연결(별도 학습셋) | **1,808 / 1,814 ≈ 99.7%** |

- **사실**: 라벨 달린 v1 영상은 거의 전부 **관제 클립 스캔 경로를 거치지 않은 큐레이션 라벨링 데이터셋**이다. 파일명이 `대구동구1.mp4`·`대전2.mp4`·CCTV추출명(`(A-24-1-고정1)…^20240717-070000_….mp4`)이고 `VMS_CCTV_ID`도 NULL. `MNG_CLIP_MASTER.CLIP_ID(clip-raw 1,226건)` 와 매칭되는 건 6~17건뿐.
- **런타임 로직과의 관계**: 운영 적재(`ControlTrainingVideoScanJob`→`TrainingVideoIngestTx:78` `vmsClipId=clip.getClipId()`, UK 멱등키)에선 `vms_clip_id`가 항상 관제 CLIP_ID지만, **이 레거시 라벨셋은 그 흐름 밖**이라 적용되지 않는다. → 그래서 합성키가 불가피.
- **방식**: 전 이관 영상에 `vms_clip_id = 'LEGACY-' || DATA_RAW_SN` (UK 충돌 0, 출처 추적). 조인되는 17건은 선택적으로 실 CLIP_ID 사용 가능하나 영향 미미.
- `vms_cctv_id`(NOT NULL)는 대부분 NULL(라벨셋) → 기본값(예 `'UNKNOWN'`) 부여.
- `vms_clip_id` ≠ `data_raw_sn`: 후자는 저작도구 내부 PK, 전자는 관제 네임스페이스(합성키로 대체).

### GAP② — 라벨 클래스: 의미 매핑 + 신규 추가, 타입은 v1 유지

**확정: v2에 없는 클래스는 신규 추가, fire/smoke는 v1 BBOX 유지(v2에 BBOX형 추가).** 라벨 손실 0.

| v1 클래스(타입) | v2 처리 | 조치 |
|-----------------|---------|------|
| water(POLY) | `침수`(10) | 매핑 |
| person(BBOX) | `사람`(1) | 매핑 |
| car(BBOX) | `자동차`(2) | 매핑 |
| fallen_person(BBOX) | `fallen-person`(11) | 매핑(표기 `_`↔`-`) |
| fire(BBOX) | **신규 `화재`(BBOX)** | v2 기존 `화재`(8)는 POLYGON → **BBOX형 별도 추가** |
| smoke(BBOX) | **신규 `smoke`(BBOX)** | 동일 |
| two_wheeler(BBOX) | `오토바이`(4) | 매핑(이륜차→오토바이) |
| asphalt(도로, POLY) | **신규 추가** | ls_label INSERT |
| other(제외, POLY) | **신규 추가** | ls_label INSERT |
| fighting_person(BBOX) | **신규 추가** | ls_label INSERT |

> ★v2 라벨 **표시명은 한글**이다(2026-08-19). 대응은 표시명이 아니라 `lbl_id` 로 하며, AI 검출 매칭축은 `DTCT_TYPE_CD`(COCO 영문 클래스명)로 별개다.
> `fallen-person`(11) 은 신규 설치에 없다 — 구 시드가 만든 행을 비활성(`USE_YN='N'`)으로 정리한 것이라, 레거시 DB 에만 존재한다.
>
> v1 `LBL_SN` → v2 `lbl_id` 대응표를 만들고, 라벨 행에 `lbl_type_cd`·`lbl_nm` 을 클래스에서 비정규화 복사.

### GAP③ — 영상 메타: 저작도구 이관 안 함 (공용 MNG 위임)

**확정: 코덱·해상도·FPS·GPS·환경 등은 이관하지 않는다.** 손실이 아니라 **소유 주체가 관제 공용(MNG)** 이기 때문.

- GPS·해상도·CCTV 화소·주소 → `MNG_RESOURCE_CCTV`(공용, `WGS84_LAT/LOT`,`CAM_RSLTN`,`CCTV_PXL`,`SHT_ADDR`)
- 영상 길이·파일경로·코덱·이벤트 → `MNG_CLIP_MASTER`(공용)
- v1은 `LS_DATA_RAW`(저작도구)에 이를 **중복 저장**했을 뿐. v2는 이 메타를 저작도구가 이관하지 않고 관제 책임으로 남겨 `ls_data_raw` 가 슬림하다.

> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — *"v2는 '관제 소유, 저작도구 READ' 원칙이라 … 이 메타가 필요하면 관제 `MNG_*` 가 진실원 … (현재 v2 PG `mng_*` 는 stub — 실적재는 관제)"* 는 사실과 다르다. `mng_*` 는 **stub 이 아니라 테이블 자체가 없다** — `MNG_RESOURCE_CCTV`·`MNG_CLIP_MASTER`·`MNG_EX_LOCAL_GOV` 모두 **V167 에서 제거**됐고 JPA 매핑·SQL 참조 각 0건이다(회귀 가드 `MngControlMasterTableRemovalTest`). CCTV명·좌표·파일형식 등은 이제 관제가 인입 원장 `LS_DATA_INGEST`(저작도구 소유)에 **직접 INSERT** 하는 평면값에서 조달한다(ADR-042 적재 주체 반전) — 조인 대상이 아니라 저작도구 자체 테이블의 컬럼이다. 근거: `IngestSourceLink.java`, `VideoRepository.java`(`구 MNG_RESOURCE_CCTV 조인은 V167 로 제거`), `DatasetMetaSourceRepository.java`, `MngControlMasterTableRemovalTest`.

---

## 4. 이관 순서 + 검증

```
1) GAP①②③ 정책 확정 (완료 — §3)
2) 라벨 클래스 매핑표 적재 (LS_PJT_LBL 24 → ls_label: 의미 dedupe + 신규 6종 INSERT
   (asphalt/other/fighting_person + BBOX형 fire/smoke) + lbl_id 대응표)
3) 영상 이관 (LS_DATA_RAW → ls_data_raw, raw_sn 재발번 + 대응표):
   vms_clip_id = 'LEGACY-'||DATA_RAW_SN (주 경로, 1808/1814) / 조인되는 17건만 실 CLIP_ID 선택;
   vms_cctv_id 미채움은 기본값; 메타(코덱·GPS 등)는 이관 제외(GAP③).
   대상 영상은 라벨 보유 1,814건 중심(전체 2,008 중 무라벨 194건 이관 여부는 선택)
4) 프레임 이관 (LS_DATA_SRC → ls_data_src, raw 대응표로 재연결, src_sn 대응표)
5) 라벨 이관 (LS_DATA_LBL → ls_data_lbl: src/lbl 대응표 재연결
   + POINT 포맷 변환 + lbl_type_cd/lbl_nm 비정규화 복사)
6) IDENTITY 시퀀스 RESTART + FK/UK 정합성 검증 + 행 수 대사
```

**검증 게이트**: ① 행 수(영상 2,008 / 라벨 ≈295K 대비 적재량) ② 고아 라벨 0(모든 src_sn·lbl_id 연결됨) ③ 좌표 역렌더 샘플 대조 ④ UK 충돌 0(vms_clip_id).

> v1 라벨 ≈295K건(8개 PJT). 동일 영상이 여러 PJT에 속하면 라벨이 중복 적재될 수 있어, **영상 동일성 기준(파일경로/CCTV+촬영시각)으로 중복 영상 통합 여부**도 GAP①과 함께 결정.

---

## 5. 한눈 요약

| 대상 | v1 → v2 | 난이도 | 핵심 |
|------|---------|:---:|------|
| 영상 목록 | `LS_DATA_RAW`(2,008) → `ls_data_raw` | 중 | 컬럼 매핑 단순하나 `vms_clip_id` 필수값·메타 손실 결정(GAP①③) |
| 프레임 | `LS_DATA_SRC`(102K) → `ls_data_src` | 하 | 거의 동형, ID 재연결만 |
| 라벨 클래스 | `LS_PJT_LBL`(24) → `ls_label` | 중 | 의미 매핑표 필요(GAP②) |
| 라벨 결과 | `LS_DATA_LBL`(≈295K) → `ls_data_lbl` | 중 | **POINT 포맷 변환**(§2) + 클래스/프레임 ID 재연결 |

> **결론**: 실 구조가 v2와 동형이라 **엔진/구조 변환 난관은 작다**. 실제 작업은 ① **좌표 포맷 변환** ② **ID 3종 재연결**(영상·프레임·라벨클래스 대응표) ③ **GAP 3종 정책 결정**에 집중된다. 라벨 이력(168만 건)은 이관 대상 아님.

---

## 6. 확장 스코프 — 프로젝트 단위 → 영상 단위 collapse (2026-06-25 확정)

### 6-0. v1 프로젝트 구조와 collapse 원리

v1 은 **프로젝트(`LS_PJT`, 8개)** 를 작업 관리 단위로 썼다. 영상은 `LS_PJT_DATA_MPNG(AUTHRT_SN,PJT_SN,DATA_RAW_SN)` 로 프로젝트에 매핑되고, 상태·배정·이슈·증강이 모두 `PJT_SN` 을 키에 포함한다. v2 는 **프로젝트 개념을 폐지**(V34 `drop_pjt_id_columns`, V35 `drop_ls_pjt`)하고 **영상(`RAW_SN`) 단위**로 작업한다.

**collapse 가 거의 무손실인 이유(실측):**

| 사실 | 수치 | 함의 |
|------|------|------|
| 영상↔프로젝트 매핑(`LS_PJT_DATA_MPNG`) | 1,534 영상=1 PJT, **3 영상만 2 PJT** | 데이터 차원에서 프로젝트 중복은 극소 |
| 라벨/프레임은 이미 `DATA_RAW_SN` 직접 보유 | — | **`PJT_SN` 만 버리면 영상 단위 평탄화 완료** |
| 상태(`LS_PJT_DATA_STTS`)의 cross-PJT 충돌 | 5 영상(예: raw 1 = `DONE`∧`FAIL`) | **상태 우선순위 규칙**으로만 해소 |
| 프로젝트 미매핑 영상 | **471** (aigen 업로드/미배정) | 상태·배정 없는 영상 = 데이터마트 미노출(정상) |

> 핵심 위험은 차원 축소 자체가 아니라 **상태 충돌 해소**와 **배정 차원의 부재**(아래 6-B)다.

### 6-A. 검수/완료 상태 — `LS_PJT_DATA_STTS` → `ls_raw_data_status`

| v1 (pjt,raw) | v2 (raw) | 변환 |
|------|------|------|
| `DATA_RAW_SN` | `raw_data_id`(=raw_sn) | 영상 대응표 |
| `PJT_DATA_STTS_CD` | `data_stts_cd`(20) | **상태 매핑(아래)**. ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — 폭 *"(32)"* 는 현재 `character varying(20)` 이다(근거: `deploy/onprem/db/schema.sql` `ls_raw_data_status.data_stts_cd`) |
| `STP_CYCL`/`IGI_CYCL` | `stp_cycl`/`igi_cycl` | 직접(NULL→0) |
| `MDFCN_DT`/`REG_DT` | `upd_dt` | COALESCE |
| (없음) | `ver` | 1 |
| (없음) | `revlt_yn` | 이 문서 작성(2026-06-25) 이후 신설된 컬럼(재검토여부, V177) — `NOT NULL DEFAULT 'N'` 이라 이관본은 별도 값 없이 기본값으로 채워진다 |
| `PJT_SN` | (없음) | **버림(collapse)** |

**상태코드 매핑 + cross-PJT 우선순위**(가장 진행된 상태 채택):

| v1 `PJT_DATA_STTS_CD` | v2 `data_stts_cd` | rank | v1 실측 건수(영상) |
|------|------|:--:|--:|
| `DONE`(최종완료)·`REVIEW`(검수완료) | **`APPROVED`** | 6 | 1,811 |
| `PROCESS`(가공완료) | `IN_REVIEW` | 5 | 0 |
| `ASSIGN`(배정완료) | `ASSIGNED` | 4 | 6 |
| `REJECT`(검수반려) | `REJECTED` | 3 | 1 |
| `WAIT`/`PENDING`/`PROC` | `PENDING` | 2 | 0 |
| `FAIL`(변환오류) | `FAILED` | 1 | 1 |

> 예: raw 1 은 PJT별 `DONE`+`FAIL` → rank 6(APPROVED) 채택. **이 적재가 데이터마트 노출을 만든다** — `V_COMPLETED_*` View 는 `LS_RAW_DATA_STATUS.DATA_STTS_CD='APPROVED'` 만 노출(V52 확인). 별도 일괄 APPROVED 처리 불필요(실제 상태 보존, 사용자 결정).

### 6-B. 작업자 배정 — 라벨 작성자에서 도출 (⚠ 차원 부재)

**문제**: v1 에는 **영상별 배정 테이블이 없다.** `LS_PJT_USER_AUTHRT` 는 *프로젝트 멤버십*(누가 이 프로젝트에 참여 가능한가)이지 *영상별 담당*이 아니다. 이를 영상으로 naive 확장하면 `프로젝트 멤버 × 프로젝트 영상` = **WORKER 34,039 + REVIEWER 31,766 ≈ 67K행 오배정**(영상마다 멤버 전원이 배정됨).

**결정(사용자 확정)**: 실제 라벨을 만든 사람 = `LS_DATA_LBL.REG_ID` 를 그 영상의 `LABELER` 로 배정한다(영상별 ≈1인, 정확).

| v1 | v2 `ls_task_altmnt` | 변환 |
|------|------|------|
| `LS_DATA_LBL.REG_ID`(영상별 distinct) | `user_no` | `REG_ID`(varchar) → `ls_acnt_user.user_id` → `user_no`(bigint) (V169 이전: `mng_acct_user`) |
| `LS_DATA_LBL.DATA_RAW_SN` | `raw_data_id` | 영상 대응표 |
| — | `task_type_cd` | 고정 `'LABELER'` |
| min(`REG_DT`) | `reg_dt` | 영상별 최초 작성시각 |
| (자기) | `reg_user_no` | 이관 시 자기 배정으로 기록 |

- **user 식별 GAP**: v1 `MNG_ACCT_USER.USER_ID` 는 varchar(`label1`,`reviewer01`…)이고 **숫자 id 없음**. v2 사용자 마스터는 `user_no`(bigint) + `user_id`(varchar) 둘 다 보유 → `user_id` 조인으로 해소. 미등록 작성자는 **soft skip**(배정만 누락, 상태·라벨은 정상) + NOTICE 보고.
- **★2026-08-04(V169) 조인 대상 변경**: v2 사용자 마스터가 관제 소유 `mng_acct_user` → **저작도구 소유 `ls_acnt_user`** 로 이관됐다(관제 `MNG_*` 9종 전량 제거). 런북 `02_load_transform.sql` 의 조인 2곳도 함께 갱신됐다 — **옛 이름으로 실행하면 `relation "mng_acct_user" does not exist` 로 즉시 실패**한다.
  - ⚠ **`user_id` 길이 축소(64→20)**: 신규 `ls_acnt_user.USER_ID` 는 표준도메인 명V20 이다. 조인 자체는 문자열 비교라 무해하지만 v1 `reg_id` 가 20자를 넘으면 v2 에 그 값이 저장될 수 없어 **매칭이 0**이 된다(soft skip 집계로 드러남). 이관 전 `SELECT max(length(reg_id)) FROM stg_lbl;` 로 확인한다.
  - ⚠ **`ls_acnt_user` 는 역할 클레임 시점 자동등록으로 채워진다** — 이관 실행 시점에 해당 작성자들이 아직 한 번도 진입하지 않았다면 행이 없어 배정이 통째로 누락된다. 로그인 이후 이 섹션만 재실행하면 보강된다(멱등).
- 검수자(REVIEWER) 배정은 v1 에 영상별 근거가 없어 **이관하지 않음**(운영 재배정). 라벨 작성자 22명/1,814 영상 기준.

### 6-C. 증강 — `LS_DATA_AUG` → `ls_data_aug` (레거시·GAP④)

**GAP④ — 의미 불일치**: v1 증강은 **내부 영상처리**(`PJT_AUG_OPT_CD`: `BRIGHT`/`DARK`/`LR`/`LR_BRIGHT`/`LR_DARK` = 밝기·좌우반전, 프레임 이미지 변형). v2 증강은 **외부 생성형 위탁**(`aug_type_cd`: `AUGMENT` 단일값, 외부 job)이며 무엇으로 바꿀지는 생성 조건 5항목이 정한다. **대응 코드가 없다.**

> ⚠ **구 서술 폐기(2026-09-02 · `ADR-059`)** — *"v2 증강은 **외부 생성형 3종**(`aug_type_cd`: `WINTER`/`NIGHT`/`RAIN`, 외부 job)이다"* 는 사실과 다르다. 세 값은 단일값 `AUGMENT` 로 합쳐졌고 겨울·야간·우천은 **생성 조건 프리셋**이 됐다 → [14](14-augmentation.md). ⚠ **v2 에 이미 만들어진 파생본의 구 코드값은 백필 없이 보존**되므로, 이관 대비표를 읽을 때 옛 값과 새 값이 함께 존재할 수 있음을 전제한다. GAP④(대응 코드 없음) 판정 자체는 그대로다.


| v1 (pjt,raw,src) | v2 `ls_data_aug` | 변환 |
|------|------|------|
| `DATA_SRC_SN` | `src_sn` | 프레임 대응표(매칭분만) |
| `PJT_AUG_OPT_CD` | `aug_type_cd`(20) | **타입코드 보존**(BRIGHT/DARK/LR…) — v2 앱 미인식 가능 |
| `AUG_PROC_STTS_CD` | `aug_proc_stts_cd` | 값 매핑 필요(값 세트가 다르다 — 아래) |
| `AUG_FILE_PATH` | **(컬럼 부재)** | ⚠ **손실** — 증강 발생 사실만 보존 |
| `PJT_SN`,`DATA_RAW_SN` | (없음) | 버림(src→frame→raw 도출) |

> **결정(사용자 확정)**: 레거시로 적재(이력 보존). 215행. v2 외부 증강 모델과 의미가 다르고 파일경로가 손실되므로 **참고용 이력**으로 본다.
>
> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — *"`AUG_PROC_STTS_CD`→`aug_proc_stts_cd` 직접(SUCCESS/PENDING/FAIL)"* 은 사실과 다르다. `aug_proc_stts_cd` 에 DB CHECK 제약은 없지만, 저작도구 앱이 실제로 쓰는 값 세트는 **`PENDING`/`ACCEPTED`/`REJECTED`/`CANCELED`**(CLAUDE.md 「증강 파이프라인」)이며 v1 의 `SUCCESS`/`FAIL` 과 다르다 — "직접" 복사하면 앱이 인식하지 못하는 값이 저장된다. 레거시 이력이라는 이 절의 결론(참고용 보존)은 유지되므로, 이관 시 v1 값을 그대로 두어도 무방하나(v2 앱이 조회 안 함) **v2 화면에 노출할 계획이 있다면 SUCCESS→ACCEPTED/FAIL→REJECTED 매핑이 필요**하다.

### 6-D. 이슈 — `LS_DATA_ISSUE` → `ls_data_issue` + `ls_issue_comment`

v1 이슈는 (pjt,raw,src) + 스레드(`UP_DATA_ISSUE_SN`). v2 는 **루트 이슈(`ls_data_issue`)와 답글(`ls_issue_comment`)을 분리**.

| v1 | v2 | 변환 |
|------|------|------|
| `DATA_ISSUE_SN`(루트, UP IS NULL) | `ls_data_issue.data_issue_sn` | 오프셋 |
| `DATA_ISSUE_SN`(답글, UP NOT NULL) | `ls_issue_comment` | `data_issue_sn`=부모(`UP_*`)+offset |
| `DATA_RAW_SN`/`DATA_SRC_SN` | `data_raw_sn`/`src_sn`(nullable) | 대응표(프레임 미매칭 시 NULL) |
| `ISSUE_TYPE_CD`(REJECT/ISSUE) | `issue_type_cd` | ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — *"직접"*(값 그대로 복사)은 사실과 다르다. v2 `ls_data_issue` 는 `issue_type_cd` 에 CHECK 제약(`ck_ls_data_issue_type`)이 있어 **`REJECTION`/`INQUIRY` 두 값만 허용**한다(근거: `deploy/onprem/db/schema.sql`) — v1 값 `REJECT`→`REJECTION`, `ISSUE`→`INQUIRY` 매핑이 필요하며, 그대로 복사하면 제약 위반으로 적재가 실패한다 |
| `USE_YN` | `issue_stts_cd` | 유도: Y→`OPEN`, N→`RESOLVED` |
| `ISSUE_CN`(+`RJCT_DTL_CD`+`ISSUE_DTL_CD`) | `issue_rsn`(1000) | 상세코드는 본문에 접미(v2 상세컬럼 부재) |
| `REG_ID` | `reported_user_no`/`author_no`(varchar50) | 직접 |
| (없음) | `author_role_cd`(답글) | 기본 `'WORKER'`(v1 미보유) |
| `PJT_SN` | (없음) | 버림 |

### 6-E. 확장 스코프 한눈 + 잔여 GAP

| 도메인 | v1 → v2 | 행수 | collapse 방식 | 잔여 GAP |
|------|------|--:|------|------|
| A 상태 | `LS_PJT_DATA_STTS`→`ls_raw_data_status` | 1,823 | (pjt,raw)→raw, 상태 우선순위 | cross-PJT 충돌 5건 우선순위로 해소 |
| B 배정 | `LS_DATA_LBL.REG_ID`→`ls_task_altmnt` | ≈1,814 | 라벨 작성자=LABELER | user_id 미등록 soft skip / REVIEWER 미이관 |
| C 증강 | `LS_DATA_AUG`→`ls_data_aug` | 215 | src 대응표 | ④ 타입 의미 불일치 + 파일경로 손실 |
| D 이슈 | `LS_DATA_ISSUE`→`ls_data_issue`+`ls_issue_comment` | 135 | 루트/답글 분리 | 상세코드 본문 접미, 역할 기본값 |

> **구현**: `docs/migration/v1-to-v2/{01_export_mysql.sh,02_load_transform.sql,03_verify.sql}` §7~§10(02) + [8]~[13](03)에 반영. 단일 트랜잭션·멱등(오프셋 PK)·검증 게이트 동일 정책.

---

## 7. 이관 후속 — 비식별 미완 영상(`de_ident_yn='N'`) 처리

이관된 라벨 영상은 대부분 `de_ident_yn='N'`(비식별 미완, 비식별 프레임 경로 없음)이다. 이 상태는 PRVC/PSDO 서빙 정책상 프레임 서빙 NOT_FOUND·민감영상 노출 위험을 유발한다. **검수완료 영상 재비식별(Approved Re-deidentification)** 기능이 이 정공법 해법이다:

- REVIEWER가 `POST /v1/videos/{rawSn}/redeident` 호출 → KPST 영상단위 비식별 → 기존 프레임 `frm_no` 위치로 비식별 프레임 추출·attach(**라벨 무변경**) → `de_ident_yn='Y'` + `prvc_type_cd` 'UNKNOWN'→'PRVC' 정정 + **APPROVED 유지**.
- 전제 `de_ident_yn != 'Y'` 가드가 이관 영상(frm_no=실프레임번호)만 대상화하고 v2 네이티브(frm_no=추출순번)는 배제 → 좌표 정합 보장.
- 상세: [08 비식별화 §8.6](08-deidentification.md)
- ⚠ 전제: 이관 영상 `raw_file_path_nm`(NAS 절대경로) 파일 접근 + `STORAGE_*_PATH` 경로 정합(runbook §5) 선행 필요.

## 관련 문서
- [08 비식별화](08-deidentification.md) — 검수완료 재비식별(이관 비식별 미완 영상 해법)
- [18 데이터베이스](18-database.md) — v2 테이블·View 상세
- [v1-wiki 15 데이터베이스](../v1-wiki/15-database.md) — v1 설계서 기준(실DB와 일부 상이, 본 문서가 실측 정정)
- [migration runbook](../migration/v1-to-v2/README.md) — 폐쇄망 실행 절차(export→반입→적재→검증)
