# 관제서버 → 저작도구 영상 인입 계약 (`LS_DATA_INGEST`)

> 대상: 관제서버 개발팀 / 저작도구 개발팀
> 스키마 정본: `backend/src/main/resources/db/migration/V147__create_ls_data_ingest.sql`
> 상태: **Phase 1 (테이블·계약 확정)**. 저작도구 적재 배치의 소스 교체는 Phase 2.
>
> ※ 2026-08-01 갱신 — **Phase 2 는 완료됐다**(PR #69, `main`). 적재 배치가 `MNG_CLIP_MASTER` 스캔이
> 아니라 이 테이블(`PROC_STTS_CD='PENDING'`)을 읽는다. 본문의 "Phase 2 예정" 서술은 그 시점
> 기준이며, 계약(스키마·컬럼·상태 전이) 자체는 그대로 유효하다.

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

관제서버가 `LS_DATA_RAW` 를 직접 쓰면 저작도구 워크플로 컬럼(`DATA_STTS_CD`, `DE_IDENT_YN`, 배정·검수 상태)이
오염되어 파이프라인이 잘못된 단계로 점프한다. 그래서 관제의 쓰기 대상은 **`LS_DATA_INGEST` 뿐**이다.

---

## 2. 컬럼별 기입 주체 계약

### 2.1 관제서버가 기입하는 컬럼

| 컬럼 | 타입 | 필수 | 설명 |
|---|---|:--:|---|
| `VMS_CLIP_ID` | VARCHAR(128) | **필수** | 관제 클립 식별자. **UNIQUE** — 통지·조회 매핑 자연키 |
| `VMS_CCTV_ID` | VARCHAR(64) | **필수** | 촬영 CCTV 식별자 |
| `RAW_FILE_PATH_NM` | VARCHAR(500) | **필수** | 영상 NAS **절대경로** |
| `EVNT_TYPE_CD` | VARCHAR(20) | 선택 | 이벤트 유형 코드 |
| `LCLGV_CD` | VARCHAR(20) | 선택 | 지자체 코드 |
| `PRVC_TYPE_CD` | VARCHAR(16) | 선택 | 개인정보 처리 유형. `ANONY` / `PRVC` / `PSDO` |
| `SHT_DT` | TIMESTAMP | 선택 | 촬영 일시 |
| `VDO_LEN_SEC` | INT | 선택 | 영상 길이(초) |
| `VDO_SRC_CD` | VARCHAR(20) | 선택 | **영상 출처** — §3 참조. 미기입(NULL) 시 `RELAY` 로 해석 |

> `PRVC_TYPE_CD` 를 주면 적재 시 그대로 승계한다. 주지 않으면 저작도구 기본값 정책을 따른다.

### 2.2 저작도구가 기입하는 컬럼 — **관제는 기입/수정하지 않는다**

| 컬럼 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `INGEST_SN` | BIGINT IDENTITY | 자동 | 인입 PK. 관제가 값을 지정하지 않는다 |
| `PROC_STTS_CD` | VARCHAR(20) | `'PENDING'` | 인입 처리 상태 (§4) |
| `LOAD_RAW_SN` | BIGINT | NULL | 적재로 생성된 `LS_DATA_RAW.RAW_SN` |
| `RTY_CNT` | INT | `0` | 적재 재시도 횟수 |
| `PRCS_DT` | TIMESTAMP | NULL | 처리 종결(LOADED/FAILED) 일시 |
| `ERR_MSG_CN` | VARCHAR(1000) | NULL | 적재 실패 사유 요약 |
| `REG_DT` | TIMESTAMP | `CURRENT_TIMESTAMP` | 인입 시각 (관제 INSERT 시각) |
| `MDFCN_DT` | TIMESTAMP | `CURRENT_TIMESTAMP` | 마지막 상태 전이 시각 |

---

## 3. 영상 출처 코드 `VDO_SRC_CD` (4종 확정)

| 코드 | 의미 | INSERT 주체 |
|---|---|---|
| `RELAY` | 중계서버가 수집한 CCTV 클립 | 관제 |
| `GEN_AI` | **관제서버가** 생성형으로 만든 영상 | 관제 |
| `USER_ULD` | 관리화면 TUS 업로드 + 관제가 넘기는 외부 사용자 업로드 | 저작도구 / 관제 |
| `AUG` | **저작도구 내부**가 만든 파생 — 해상도 파생(`RESL_*`) + 증강 AI(WINTER/NIGHT/RAIN) | 저작도구 |

**경계축은 "외부 위탁이냐"가 아니라 "관제가 만들었냐 / 저작도구가 만들었냐"다.**
증강 3종(WINTER/NIGHT/RAIN)은 외부 생성형 AI 에 위탁하지만 요청·수신·파생 생성 주체가 저작도구이므로 `AUG` 다.

- **NULL 허용** — 출처 컬럼을 모르는 관제 버전이 먼저 배포된 구간을 위해 NULL 을 허용하며, 저작도구는 `RELAY` 로 해석한다.
- **위 4종 외의 값은 INSERT 가 즉시 실패한다** (`CK_LDI_VDO_SRC` CHECK 제약).
  조용히 저장돼 나중에 오분류되는 것보다, 관제가 배포 시점에 즉시 알아채는 편이 안전하다는 판단(fail-closed).
  **새 출처 코드가 필요하면 저작도구에 먼저 알려 마이그레이션으로 코드값을 추가한 뒤 관제를 배포한다** (§7 배포 순서).

---

## 4. 처리 상태 `PROC_STTS_CD` (저작도구 소유)

```
PENDING ──(원자 클레임)──▶ CLAIMED ──┬──▶ LOADED   (LS_DATA_RAW 적재 완료)
 관제 INSERT 직후 기본값              └──▶ FAILED   (적재 실패, 사유는 ERR_MSG_CN)
```

- 4종 외의 값은 INSERT/UPDATE 가 즉시 실패한다 (`CK_LDI_PROC_STTS` CHECK 제약).
- **관제는 이 컬럼을 절대 쓰지 않는다.** 관제가 `'LOADED'` 같은 *유효한* 값을 잘못 넣으면
  CHECK 제약으로는 막을 수 없고, 저작도구 배치가 그 행을 **영구히 건너뛴다**(영상 유실).
  이를 막는 수단은 §5 의 컬럼단위 권한 분리뿐이다.

---

## 5. DB 권한 분리 요청 (필수)

관제 계정과 저작도구 계정이 같은 DB 를 쓰므로, **관제 계정에는 1차 컬럼에만 INSERT 권한을 부여**한다.
PostgreSQL 은 컬럼 단위 권한을 지원한다.

```sql
-- 관제 계정(예: control_app)에 인입 테이블의 1차 컬럼만 INSERT 허용
GRANT INSERT (
    VMS_CLIP_ID, VMS_CCTV_ID, EVNT_TYPE_CD, LCLGV_CD, PRVC_TYPE_CD,
    RAW_FILE_PATH_NM, SHT_DT, VDO_LEN_SEC, VDO_SRC_CD
) ON klid_at.LS_DATA_INGEST TO control_app;

-- ※ 아래 컬럼에는 INSERT/UPDATE 권한을 부여하지 않는다 (저작도구 소유)
--    PROC_STTS_CD, LOAD_RAW_SN, RTY_CNT, PRCS_DT, ERR_MSG_CN
-- ※ UPDATE / DELETE 는 관제에 부여하지 않는다 (인입은 append-only)
-- ※ LS_DATA_RAW 등 다른 LS_* 테이블에는 어떤 권한도 부여하지 않는다
```

> 권한 분리가 적용되지 않은 환경에서는 이 계약이 **신사협정**에 머무른다.
> 저작도구는 상태 오염을 탐지·보고하는 로직을 Phase 2 에서 추가할 예정이나,
> **탐지는 사후 조치이므로 권한 분리를 우선한다.**

---

## 6. 예시 INSERT

### 6.1 최소 (필수 컬럼만)

```sql
INSERT INTO klid_at.LS_DATA_INGEST
    (VMS_CLIP_ID, VMS_CCTV_ID, RAW_FILE_PATH_NM)
VALUES
    ('CLIP-20260731-000123', 'CCTV-11110-004', '/nas-storage/vms/2026/07/31/clip_000123.mp4');
```

`PROC_STTS_CD='PENDING'`, `RTY_CNT=0`, `REG_DT=now()` 가 DB 기본값으로 자동 채워진다.

### 6.2 권장 (1차 메타 + 출처 전체)

```sql
INSERT INTO klid_at.LS_DATA_INGEST
    (VMS_CLIP_ID, VMS_CCTV_ID, EVNT_TYPE_CD, LCLGV_CD, PRVC_TYPE_CD,
     RAW_FILE_PATH_NM, SHT_DT, VDO_LEN_SEC, VDO_SRC_CD)
VALUES
    ('CLIP-20260731-000123', 'CCTV-11110-004', 'INTRUSION', '11110', 'PRVC',
     '/nas-storage/vms/2026/07/31/clip_000123.mp4', '2026-07-31 14:23:05', 42, 'RELAY');
```

### 6.3 관제서버가 생성한 영상

```sql
INSERT INTO klid_at.LS_DATA_INGEST
    (VMS_CLIP_ID, VMS_CCTV_ID, RAW_FILE_PATH_NM, SHT_DT, VDO_LEN_SEC, VDO_SRC_CD)
VALUES
    ('GEN-20260731-000007', 'CCTV-11110-004',
     '/nas-storage/genai/2026/07/31/gen_000007.mp4', '2026-07-31 09:00:00', 30, 'GEN_AI');
```

---

## 7. 금지 사항 · 주의

| # | 항목 | 내용 |
|---|---|---|
| 1 | **저작도구 소유 컬럼 기입 금지** | `PROC_STTS_CD` · `LOAD_RAW_SN` · `RTY_CNT` · `PRCS_DT` · `ERR_MSG_CN` 를 INSERT 문에 포함하지 않는다. 특히 `PROC_STTS_CD` 오기입은 해당 영상이 **영구 미적재**로 사라지는 결과를 낳는다 |
| 2 | **`LS_DATA_RAW` 직접 INSERT/UPDATE 금지** | 작업 테이블은 저작도구 전용이다 |
| 3 | **`VMS_CLIP_ID` 중복 INSERT 금지** | UNIQUE 제약 위반으로 거부된다(멱등 실패). 같은 클립을 다시 보내야 하면 저작도구에 문의 |
| 4 | **미지 `VDO_SRC_CD` 값 금지** | CHECK 제약 위반으로 INSERT 가 실패한다. 새 코드는 저작도구 마이그레이션 선행 후 사용 |
| 5 | **`RAW_FILE_PATH_NM` 은 NAS 절대경로** | 상대경로·심볼릭 링크·`..` 포함 경로를 넣지 않는다 |
| 6 | **INSERT 후 행 수정/삭제 금지** | 인입은 append-only 다. 잘못 보낸 건은 저작도구에 문의 |

### 7.1 배포 순서

| 변경 | 순서 |
|---|---|
| 신규 인입 시작 | ① 저작도구 V147 마이그레이션 적용 → ② 권한 GRANT(§5) → ③ 관제 INSERT 개시 |
| 새 `VDO_SRC_CD` 코드 추가 | ① 저작도구가 CHECK 제약에 코드값 추가(마이그레이션) → ② 관제 배포 |
| 새 1차 컬럼 추가 | ① 저작도구 마이그레이션 + 권한 GRANT → ② 관제 배포 |

> **관제 선배포 금지** — 관제가 먼저 새 코드/컬럼을 쓰면 INSERT 가 전량 실패한다(의도된 fail-closed).

---

## 8. 저작도구 측 처리 보증

| 항목 | 내용 |
|---|---|
| **경로 미신뢰** | 저작도구는 `RAW_FILE_PATH_NM` 을 **신뢰하지 않는다**. Phase 2 적재 시 `Path.normalize()` + 허용 루트 하위 검증을 수행하며(CWE-22 경로 순회 차단), 검증 실패 시 해당 인입 행을 `FAILED` 로 종결한다 |
| **중복 적재 방지** | 2노드 Active-Active 배포이므로, 배치는 조건부 UPDATE(`PENDING → CLAIMED`) 원자 클레임에 성공한 행만 적재한다. 같은 인입 행이 두 노드에서 두 번 적재되지 않는다 |
| **참조 무결성** | `LOAD_RAW_SN` 에는 FK 를 걸지 않는다(인입 행은 audit 성격이라 영상 행 수명과 독립). 정합은 저작도구 적재 서비스 책임이다 |
| **PII 미기록** | `ERR_MSG_CN` 에는 실패 사유 요약만 남기며 절대경로·PII·스택트레이스를 넣지 않는다. 저작도구 로그도 `VMS_CLIP_ID` / `INGEST_SN` 식별자만 출력하고 영상 경로 전문은 출력하지 않는다 |
| **인입 행 보존** | 적재 성공·실패와 무관하게 인입 행은 삭제하지 않는다(추적 가능성) |

---

## 9. 변경 이력

| 일자 | 내용 |
|---|---|
| 2026-07-31 | 최초 작성 — `LS_DATA_INGEST` 신설(V147), 출처 4종 확정, 권한 분리 요청 |
