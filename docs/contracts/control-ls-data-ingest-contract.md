# 관제서버 → 저작도구 영상 인입 계약 (`LS_DATA_INGEST`)

> 대상: 관제서버 개발팀 / 저작도구 개발팀
> 스키마 정본: `backend/src/main/resources/db/migration/V147__create_ls_data_ingest.sql`
> 상태: **운영 중** — 적재 배치가 이 테이블(`PROC_STTS_CD='PENDING'`)을 읽어 `LS_DATA_RAW` 로 이관한다.
>
> ## ⚠ 2026-08-03 전면 정정 — 이전 판(2026-07-31)을 보고 구현 중이면 반드시 §10 을 먼저 읽을 것
>
> 이전 판은 V147 확정 **전**의 초안이었고 **컬럼명·필수 여부·상태값이 실제 테이블과 달랐다.**
> 그대로 INSERT 하면 `column ... does not exist` 또는 NOT NULL 위반으로 **전량 실패**한다.
> 이 판은 V147 DDL 을 직접 대조해 작성했다. 변경 목록은 **§10 이전 판 대비 변경**.

---

## 1. 개요

관제서버는 학습용 영상을 저작도구 DB(`klid_at`)의 **`LS_DATA_INGEST` 테이블에 직접 INSERT** 한다.
저작도구는 주기 배치로 이 테이블의 미처리 행을 픽업해 작업 테이블 `LS_DATA_RAW` 로 적재하고,
이후 파이프라인(비식별 → 마킹 → VLM → 프레임추출 → 오토라벨링 → 검수)을 진행한다.

```
관제서버 ──INSERT──▶ LS_DATA_INGEST ──(저작도구 주기 배치)──▶ LS_DATA_RAW ──▶ 파이프라인
             (인입 테이블)                                       (작업 테이블)
```

**인입 테이블과 작업 테이블을 나눈 이유**

| | `LS_DATA_INGEST` (인입) | `LS_DATA_RAW` (작업) |
|---|---|---|
| 쓰기 주체 | **관제서버** (+ 저작도구가 처리 컬럼만) | 저작도구 전용 |
| 성격 | 인입 큐 / audit | 워크플로 상태 기계 |
| 관제 접근 | INSERT (아래 §5 권한 범위) | **접근하지 않음** |

관제서버가 `LS_DATA_RAW` 를 직접 쓰면 저작도구 워크플로 컬럼(`DATA_STTS_CD`, `DE_IDNTF_YN`, 배정·검수 상태)이
오염되어 파이프라인이 잘못된 단계로 점프한다. 그래서 관제의 쓰기 대상은 **`LS_DATA_INGEST` 뿐**이다.

**총 37컬럼 = 관제 수신 29 + 저작도구 운영 8.**

**조인은 관제가 끝내서 보낸다.** 저작도구는 관제 테이블을 조인하지 않는다 — 관제가 영상·메타·CCTV 제원을
조인한 결과를 **평면 1행**으로 넣어준다. CCTV 제원(`OG_CD`/`CCTV_NM`/`CCTV_HGT`/`MAIN_SURV_PAN_ANG`)이
영상마다 중복 저장되는 것은 **의도된 설계**다. 조인 참조로 바꾸면 카메라 교체·방위각 재설정 시
과거 영상의 어노테이션이 현재 제원으로 오염된다.

---

## 2. 컬럼별 기입 주체 계약

### 2.1 관제서버가 기입하는 컬럼 (29)

#### 필수 5 — 하나라도 빠지면 INSERT 가 실패한다 (NOT NULL, DEFAULT 없음)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `VMS_CLIP_ID` | VARCHAR(128) | 관제 클립 식별자. **UNIQUE** — 통지·조회 매핑 자연키 |
| `VMS_CCTV_ID` | VARCHAR(64) | 촬영 CCTV 식별자 |
| `VDO_FILE_NM` | VARCHAR(300) | 동영상 파일명 |
| `RAW_FILE_PATH_NM` | VARCHAR(500) | 영상 NAS **절대경로** |
| `SRC_TYPE` | VARCHAR(20) | **영상 출처** — §3. **NULL 불가**(DEFAULT 없음, fail-closed) |

#### 선택 24 — 모르면 NULL 로 두면 된다

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `SHT_DT` | TIMESTAMP | 촬영 일시. **대용값(현재시각 등)을 넣지 말 것** — 촬영환경 파생이 오염된다. 모르면 NULL |
| `LCLGV_CD` | VARCHAR(20) | 지자체 코드 |
| `RGN_NM` | VARCHAR(200) | 지역명 |
| `OG_CD` | VARCHAR(20) | 기관 코드 |
| `EVNT_ID` | VARCHAR(50) | 이벤트 아이디 (관제 `video.event_id`, 예 `ABA_0001`). **이벤트 유형코드가 아니다** — §9 미해결 |
| `EVNT_NM` | VARCHAR(200) | 이벤트명 |
| `MNTR_CN` | VARCHAR(4000) | 관제 일지 |
| `CCTV_NM` | VARCHAR(300) | CCTV 명 |
| `CCTV_HGT` | DECIMAL(4,1) | CCTV 설치 높이 |
| `MAIN_SURV_PAN_ANG` | INTEGER | 주감시 방향값(도) |
| `WGS84_LAT` | DECIMAL(10,7) | WGS84 위도 |
| `WGS84_LOT` | DECIMAL(10,7) | WGS84 경도 |
| `VDO_LEN_SEC` | NUMERIC(10) | 영상 길이(**초**) |
| `FRM_CNT` | NUMERIC(10) | 프레임 수 |
| `FPS` | VARCHAR(10) | 프레임 재생속도 |
| `WDTH` | NUMERIC(10) | 가로 |
| `VRTC` | NUMERIC(10) | 세로 |
| `RESL` | VARCHAR(20) | 해상도 |
| `ASPRT_RT` | VARCHAR(20) | 종횡비 |
| `VDO_CDC` | VARCHAR(20) | 영상 코덱 |
| `FILE_FMT` | VARCHAR(20) | 파일 형식 |
| `FILE_SZ` | BIGINT | 파일 크기(**바이트**) |
| `BIT` | VARCHAR(20) | 비트값(색심도) |
| `PXL` | VARCHAR(20) | 화소 |

> **개인정보 유형·촬영환경 컬럼은 인입에 없다** (설계 R6). 수동입력 메타(촬영환경 3필드·개인정보 3필드)와
> VLM 산출값은 저작도구가 자기 테이블에서 관리한다. 인입에 두면 같은 의미가 두 군데 생기고,
> 워크플로 컬럼은 관제의 잘못된 값이 저작도구 상태머신을 직접 오염시킨다.
> 적재 시 개인정보 유형은 저작도구가 **`PRVC`(fail-closed)** 로 채운다.

### 2.2 저작도구가 기입하는 컬럼 (8) — **관제는 기입/수정하지 않는다**

| 컬럼 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `RCPTN_SN` | BIGINT IDENTITY | 자동 | 수신 일련번호(PK). 관제가 값을 지정하지 않는다 |
| `RCPTN_DT` | TIMESTAMP | `CURRENT_TIMESTAMP` | 수신 일시 (관제 INSERT 시각) |
| `PROC_STTS_CD` | VARCHAR(20) | `'PENDING'` | 인입 처리 상태 (§4) |
| `RAW_SN` | BIGINT | NULL | 적재로 생성된 `LS_DATA_RAW.RAW_SN` |
| `RTY_CNT` | INTEGER | `0` | 적재 실패 누적 횟수 |
| `PRCS_DT` | TIMESTAMP | NULL | 처리 일시 (미도착 대기 기산점 겸용) |
| `NEXT_RTRY_DT` | TIMESTAMP | NULL | 다음 재시도 일시(backoff) |
| `ERR_MSG` | VARCHAR(4000) | NULL | 적재 실패 사유 요약 |

---

## 3. 영상 출처 `SRC_TYPE` (5종)

| 코드 | 의미 | INSERT 주체 |
|---|---|---|
| `ORIGINAL` | 원본 영상 | 관제 |
| `RELAY` | 중계서버가 수집한 CCTV 클립 | 관제 |
| `GENERATED` | **관제서버가** 생성형으로 만든 영상 | 관제 |
| `USER_ULD` | 사용자 업로드 (관제 인계분 + 저작도구 관리화면 업로드) | 관제 / 저작도구 |
| `AUGMENTED` | **저작도구 내부**가 만든 파생 — 해상도 파생(`RESL_*`) + 증강 AI(WINTER/NIGHT/RAIN) | 저작도구 |

**경계축은 "외부 위탁이냐"가 아니라 "관제가 만들었냐 / 저작도구가 만들었냐"다.**
증강 3종은 외부 생성형 AI 에 위탁하지만 요청·수신·파생 생성 주체가 저작도구이므로 `AUGMENTED` 다.

- **NULL 불가.** DB DEFAULT 가 없으므로 값을 빼면 NOT NULL 위반으로 INSERT 가 실패한다(의도된 fail-closed).
- **DB CHECK 제약은 없다.** 값 검증은 저작도구 적재 배치가 allowlist 로 수행한다 —
  목록 밖 값은 INSERT 시점엔 통과하지만 적재 시 `LS_DATA_RAW.SRC_TYPE` 이 **NULL 로 떨어지고 WARN** 이 남는다.
  즉 오타는 즉시 실패하지 않고 **조용히 출처 미상**이 되므로, 관제 쪽에서 값 검증을 해야 한다.
- **새 출처 코드가 필요하면 저작도구에 먼저 알린다** (§7 배포 순서).

---

## 4. 처리 상태 `PROC_STTS_CD` (저작도구 소유)

```
PENDING ──(원자 클레임)──▶ PROCESSING ──┬──▶ DONE     (LS_DATA_RAW 적재 완료)
 관제 INSERT 직후 기본값                 ├──▶ FAILED   (적재 실패, 사유는 ERR_MSG)
                                        └──▶ PENDING  (원본 파일 미도착 — backoff 후 재시도)
```

- `FAILED` 에서 빠져나오는 유일한 경로는 저작도구 검수자의 **재큐 API** 다(`POST /v1/control-ingests/{rcptnSn}/requeue`).
- **DB CHECK 제약은 없다.** 상태 오기입을 DB 가 막아주지 않는다.
- **관제는 이 컬럼을 절대 쓰지 않는다.** 관제가 `'DONE'` 같은 *유효한* 값을 잘못 넣으면
  저작도구 배치가 그 행을 **영구히 건너뛴다**(영상 유실). 이를 막는 수단은 §5 의 컬럼단위 권한 분리뿐이다.

---

## 5. DB 권한 분리 요청 (필수)

관제 계정과 저작도구 계정이 같은 DB 를 쓰므로, **관제 계정에는 관제 수신 컬럼에만 INSERT 권한을 부여**한다.
PostgreSQL 은 컬럼 단위 권한을 지원한다.

```sql
-- 관제 계정(예: control_app)에 인입 테이블의 관제 수신 컬럼만 INSERT 허용
GRANT INSERT (
    VMS_CLIP_ID, VMS_CCTV_ID, VDO_FILE_NM, RAW_FILE_PATH_NM, SRC_TYPE,
    SHT_DT, FILE_FMT, VDO_CDC, FILE_SZ, RGN_NM, VDO_LEN_SEC, FPS, FRM_CNT,
    ASPRT_RT, WDTH, VRTC, RESL, BIT, PXL, WGS84_LAT, WGS84_LOT,
    OG_CD, CCTV_NM, CCTV_HGT, MAIN_SURV_PAN_ANG, EVNT_ID, EVNT_NM, MNTR_CN, LCLGV_CD
) ON klid_at.LS_DATA_INGEST TO control_app;

-- ※ 아래 컬럼에는 INSERT/UPDATE 권한을 부여하지 않는다 (저작도구 소유)
--    RCPTN_SN, RCPTN_DT, PROC_STTS_CD, RAW_SN, RTY_CNT, PRCS_DT, NEXT_RTRY_DT, ERR_MSG
-- ※ UPDATE / DELETE 는 관제에 부여하지 않는다 (인입은 append-only)
-- ※ LS_DATA_RAW 등 다른 LS_* 테이블에는 어떤 권한도 부여하지 않는다
```

> 권한 분리가 적용되지 않은 환경에서는 이 계약이 **신사협정**에 머무른다.
> 상태 오염 탐지는 사후 조치이므로 **권한 분리를 우선한다.**

---

## 6. 예시 INSERT

### 6.1 최소 (필수 5컬럼)

```sql
INSERT INTO klid_at.LS_DATA_INGEST
    (VMS_CLIP_ID, VMS_CCTV_ID, VDO_FILE_NM, RAW_FILE_PATH_NM, SRC_TYPE)
VALUES
    ('CLIP-20260731-000123', 'CCTV-11110-004', 'clip_000123.mp4',
     '/nas-storage/vms/2026/07/31/clip_000123.mp4', 'RELAY');
```

`RCPTN_SN`, `RCPTN_DT=now()`, `PROC_STTS_CD='PENDING'`, `RTY_CNT=0` 이 DB 기본값으로 자동 채워진다.

### 6.2 권장 (1차 메타 + 기술메타)

```sql
INSERT INTO klid_at.LS_DATA_INGEST
    (VMS_CLIP_ID, VMS_CCTV_ID, VDO_FILE_NM, RAW_FILE_PATH_NM, SRC_TYPE,
     SHT_DT, LCLGV_CD, RGN_NM, OG_CD, EVNT_ID, EVNT_NM,
     CCTV_NM, CCTV_HGT, MAIN_SURV_PAN_ANG, WGS84_LAT, WGS84_LOT,
     VDO_LEN_SEC, FRM_CNT, FPS, WDTH, VRTC, RESL, ASPRT_RT,
     VDO_CDC, FILE_FMT, FILE_SZ)
VALUES
    ('CLIP-20260731-000123', 'CCTV-11110-004', 'clip_000123.mp4',
     '/nas-storage/vms/2026/07/31/clip_000123.mp4', 'RELAY',
     '2026-07-31 14:23:05', '11110', '서울특별시 중구', 'ORG-001', 'ABA_0001', '교통사고',
     '을지로입구 사거리', 4.5, 135, 37.5665000, 126.9780000,
     42, 1260, '30', 1920, 1080, '1920x1080', '16:9',
     'h264', 'mp4', 15728640);
```

### 6.3 관제서버가 생성한 영상

```sql
INSERT INTO klid_at.LS_DATA_INGEST
    (VMS_CLIP_ID, VMS_CCTV_ID, VDO_FILE_NM, RAW_FILE_PATH_NM, SRC_TYPE,
     SHT_DT, VDO_LEN_SEC)
VALUES
    ('GEN-20260731-000007', 'CCTV-11110-004', 'gen_000007.mp4',
     '/nas-storage/genai/2026/07/31/gen_000007.mp4', 'GENERATED',
     '2026-07-31 09:00:00', 30);
```

---

## 7. 금지 사항 · 주의

| # | 항목 | 내용 |
|---|---|---|
| 1 | **저작도구 소유 컬럼 기입 금지** | `RCPTN_SN` · `RCPTN_DT` · `PROC_STTS_CD` · `RAW_SN` · `RTY_CNT` · `PRCS_DT` · `NEXT_RTRY_DT` · `ERR_MSG` 를 INSERT 문에 포함하지 않는다. 특히 `PROC_STTS_CD` 오기입은 해당 영상이 **영구 미적재**로 사라지는 결과를 낳는다 |
| 2 | **`LS_DATA_RAW` 직접 INSERT/UPDATE 금지** | 작업 테이블은 저작도구 전용이다 |
| 3 | **`VMS_CLIP_ID` 중복 INSERT 금지** | UNIQUE 제약 위반으로 거부된다. 같은 클립을 다시 보내야 하면 저작도구에 문의 |
| 4 | **`SRC_TYPE` 누락 금지** | NOT NULL + DEFAULT 없음 → INSERT 실패. 값 오타는 실패하지 않고 **조용히 출처 미상**이 되니 관제 쪽 검증 필요 |
| 5 | **`RAW_FILE_PATH_NM` 은 NAS 절대경로** | 상대경로·`..` 포함 경로를 넣지 않는다. 심볼릭 링크로 허용 루트를 벗어나면 적재가 `FAILED` 로 종결된다 |
| 6 | **INSERT 후 행 수정/삭제 금지** | 인입은 append-only 다. 잘못 보낸 건은 저작도구에 문의 |
| 7 | **`SHT_DT` 대용값 금지** | 모르면 NULL. 현재시각 등을 채우면 촬영환경(시간대·계절) 파생이 오염된다 |

### 7.1 배포 순서

| 변경 | 순서 |
|---|---|
| 신규 인입 시작 | ① 저작도구 V147 마이그레이션 적용 → ② 권한 GRANT(§5) → ③ 관제 INSERT 개시 |
| 새 `SRC_TYPE` 코드 추가 | ① 저작도구가 allowlist 에 코드값 추가 → ② 관제 배포 |
| 새 수신 컬럼 추가 | ① 저작도구 마이그레이션 + 권한 GRANT → ② 관제 배포 |

> **관제 선배포 금지** — 관제가 먼저 새 컬럼을 쓰면 INSERT 가 전량 실패한다.

---

## 8. 저작도구 측 처리 보증

| 항목 | 내용 |
|---|---|
| **경로 미신뢰** | 저작도구는 `RAW_FILE_PATH_NM` 을 **신뢰하지 않는다**. 적재 시 정규화 + 허용 루트 하위 검증 + 실경로(심링크 해석) 재검증을 수행하며(CWE-22/59), 검증 실패 시 해당 인입 행을 `FAILED` 로 종결한다 |
| **파일 미도착 대기** | 행은 들어왔는데 파일이 아직 없으면 실패로 종결하지 않고 `PENDING` 으로 되돌려 backoff(1분~1시간) 재시도한다. 대기 상한(기본 24시간) 초과 시에만 `FAILED` |
| **중복 적재 방지** | 2노드 Active-Active 배포이므로, 배치는 조건부 UPDATE(`PENDING → PROCESSING`) 원자 클레임에 성공한 행만 적재한다. 같은 인입 행이 두 노드에서 두 번 적재되지 않는다 |
| **멱등** | 같은 `VMS_CLIP_ID` 로 이미 적재된 영상이 있으면 새로 만들지 않고 기존 `RAW_SN` 을 연결한 뒤 `DONE` 처리한다 |
| **참조 무결성** | `RAW_SN` 에는 FK 를 걸지 않는다(인입 행은 audit 성격이라 영상 행 수명과 독립) |
| **PII 미기록** | `ERR_MSG` 에는 실패 사유 요약만 남기며 절대경로·PII·스택트레이스를 넣지 않는다. 저작도구 로그도 `VMS_CLIP_ID` / `RCPTN_SN` 식별자만 출력한다 |
| **인입 행 보존** | 적재 성공·실패와 무관하게 인입 행은 삭제하지 않는다(추적 가능성) |

---

## 9. 미해결 — 관제팀 확인 요청

**`EVNT_ID` 로는 이벤트 유형을 알 수 없다.**
인입의 `EVNT_ID` 는 `ABA_0001` 형식인데, 저작도구가 통계·완료통지·데이터마트에서 쓰는
`EVNT_TYPE_CD` 는 `EV01000101` 형식(관제 `MNG_EX_EVNT_TYPE`)이고 **둘 사이 매핑이 없다.**
현재 적재분은 `LS_DATA_RAW.EVNT_TYPE_CD` 가 **NULL** 이며, 그 결과:

- 통계 이벤트 분포에서 해당 영상이 **빠진다**(`WHERE evnt_type_cd IS NOT NULL`)
- 관제 완료통지의 `event_type_cd`(required) 가 NULL 로 나간다
- 데이터마트 뷰 `V_COMPLETED_VIDEO` · export 스냅샷도 NULL

확인 요청 3건:

1. `video.event_id`(`ABA_0001`) ↔ `EVNT_TYPE_CD` 매핑표가 관제에 있는가?
2. 없다면 `EVNT_NM` 값이 `MNG_EX_EVNT_TYPE_MAP.EVNT_NM`(`침수(범람)`·`화재`·`교통사고` …) 과 같은 어휘인가?
   같다면 이름 매칭으로 **카테고리 수준**(`evnt_cls_cd`/`evnt_ctgry_cd`)은 채울 수 있다.
3. **인입에 `EVNT_TYPE_CD` 컬럼을 추가해 관제가 직접 채워줄 수 있는가?** — 가장 깨끗한 해법.

---

## 10. 이전 판(2026-07-31) 대비 변경 — 구현 중이면 반드시 확인

이전 판은 V147 확정 전 초안이라 **실제 테이블과 이름·제약이 달랐다.** 아래는 전부 이번 판이 정본이다.

| 이전 판 표기 | 실제 (V147) | 영향 |
|---|---|---|
| `INGEST_SN` | **`RCPTN_SN`** | 컬럼 부재 오류 |
| `LOAD_RAW_SN` | **`RAW_SN`** | 컬럼 부재 오류 |
| `ERR_MSG_CN` VARCHAR(1000) | **`ERR_MSG` VARCHAR(4000)** | 컬럼 부재 오류 |
| `REG_DT` / `MDFCN_DT` | **`RCPTN_DT`** (`MDFCN_DT` 없음) | 컬럼 부재 오류 |
| `VDO_SRC_CD` | **`SRC_TYPE`** | 컬럼 부재 오류 |
| `EVNT_TYPE_CD` | **없음** — `EVNT_ID` / `EVNT_NM` | 컬럼 부재 오류 (§9) |
| `PRVC_TYPE_CD` | **없음** (설계 R6 제외) | 컬럼 부재 오류. 개인정보 유형은 저작도구가 `PRVC` 로 채운다 |
| 필수 3개 | **필수 5개** (+`VDO_FILE_NM`, `SRC_TYPE`) | NOT NULL 위반 |
| 출처 NULL 허용 → `RELAY` 해석 | **NOT NULL, DEFAULT 없음** | NOT NULL 위반 |
| 출처 4종 `RELAY`/`GEN_AI`/`USER_ULD`/`AUG` | **5종** `ORIGINAL`/`RELAY`/`GENERATED`/`USER_ULD`/`AUGMENTED` | 값 불일치 → 출처 미상 |
| 상태 `CLAIMED` / `LOADED` | **`PROCESSING` / `DONE`** | 상태 해석 불일치 |
| CHECK 제약 `CK_LDI_VDO_SRC` · `CK_LDI_PROC_STTS` | **없음** | 잘못된 값이 DB 에서 막히지 않는다 — 관제 쪽 검증 필요 |
| 기술·CCTV 메타 미기재 | **24컬럼 추가 기재** (§2.1) | 관제가 보낼 수 있는 값이 문서에 없었다 |

---

## 11. 변경 이력

| 일자 | 내용 |
|---|---|
| 2026-07-31 | 최초 작성 — V147 확정 전 초안 (컬럼명·제약이 실제와 상이) |
| 2026-08-03 | **V147 DDL 대조 전면 정정** — 컬럼명 6건·필수 5건·출처 5종·상태 4종 정정, 기술/CCTV 메타 24컬럼 기재, CHECK 제약 부재 명시, `EVNT_TYPE_CD` 미해결 항목 신설(§9) |
