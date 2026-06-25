# v1(MariaDB) → v2(PostgreSQL) 영상·라벨 이관 — 폐쇄망 Runbook

> **목적**: 1차(MariaDB `klid_system`)의 **영상 목록 + 라벨링 결과**를 2차(PostgreSQL `klid_at`/`public`)로 이관한다.
> **분석 정본**: [`../../v2-wiki/23-v1-v2-db-migration.md`](../../v2-wiki/23-v1-v2-db-migration.md) (GAP 정책·매핑 근거).
> **전제**: 폐쇄망. 인터넷·외부 라이브러리 불가. **표준 클라이언트 2개(`mysql`, `psql`)만 사용**. 데이터는 환경마다 다르므로 스크립트는 **스키마 기반·데이터 비종속**으로 작성됨.

---

## 0. 왜 이 방식인가 (폐쇄망 제약)

| 제약 | 대응 |
|------|------|
| 인터넷 없음 / pip·apt 불가 | `pgloader`·Python 드라이버 등 미사용. **`mysql`+`psql`만** |
| 에어갭(망분리) | 중간 산출물을 **TSV 파일**로 떨궈 승인 반입(USB 등)으로 이동 |
| 크로스 엔진(MySQL→PG) | 단일 SQL 직접 조인 불가 → **PG 스테이징 적재 후 PG SQL로 변환** |
| 실데이터 상이 | 라벨 클래스·건수가 환경마다 다름 → **변환 SQL은 값 하드코딩 없이 스테이징에서 유도**, 의미 매핑만 운영자 검토 |

흐름: **① v1 export(TSV) → ② 파일 반입 → ③ PG 스테이징 적재 → ④ 변환·적재 → ⑤ 검증**

---

## 1. 사전 결정 사항 (분석 문서 §3·§6에서 확정)

### 핵심 데이터 (영상·프레임·라벨)
- **GAP① vms_clip_id**: `LEGACY-{DATA_RAW_SN}` 합성키(주 경로). `vms_cctv_id` 미채움은 `UNKNOWN`. (관제 클립 조인은 0.3%뿐이라 미적용)
- **GAP② 라벨 클래스**: 의미 매핑 + v2에 없는 클래스 자동 신규 추가, **타입(BBOX/POLYGON) v1 유지**. 라벨 손실 0.
- **GAP③ 영상 메타**: 코덱·해상도·FPS·GPS 등 **이관 안 함**(공용 MNG가 진실원).
- **좌표 변환**: v1 BBOX `{x,y,width,height}` → v2 `[[x,y],[x+w,y+h]]`, v1 POLYGON `[{x,y},…]` → v2 `[[x,y],…]`.
- **ID 매핑**: 영상·라벨은 오프셋(`+offset`), 프레임·라벨클래스는 **명시 대응표**(프레임은 (영상,프레임) 중복 dedup, 클래스는 이름+타입 dedup).

### 확장 스코프 — 프로젝트 단위 → 영상 단위 (§6에서 확정)
> v1 은 **프로젝트(`LS_PJT`) 단위로 파일·작업을 관리**(영상↔프로젝트 = `LS_PJT_DATA_MPNG`, 상태·배정도 프로젝트 종속)했다. v2 는 **영상(`RAW_SN`) 단위**(프로젝트 개념 폐지 — V34/V35 에서 `LS_PJT` 제거)다. 아래는 그 **차원 축소(collapse)** 결정이다.

- **A. 검수/완료 상태**: `LS_PJT_DATA_STTS`(pjt,raw) → `ls_raw_data_status`(raw). 동일 영상이 여러 PJT면 **가장 진행된 상태**를 채택. `DONE→APPROVED`(데이터마트 노출), `ASSIGN→ASSIGNED`, `REJECT→REJECTED`, `FAIL→FAILED`.
- **B. 작업자 배정**: V1 은 영상별 배정이 없고 프로젝트 멤버십(`LS_PJT_USER_AUTHRT`)만 있다(naive 확장 시 ~67K 오배정). → **실제 라벨 작성자(`LS_DATA_LBL.REG_ID`)를 영상별 `LABELER` 로 배정**. `user_id`→`mng_acct_user.user_no` 매핑, 미매칭은 soft skip(보고).
- **C. 증강 (레거시·GAP④)**: `LS_DATA_AUG`(v1 내부 BRIGHT/DARK/LR) → `ls_data_aug`. v2 모델(외부 생성형 WINTER/NIGHT/RAIN)과 **의미 불일치**라 타입코드 보존 적재. ⚠ v2 에 파일경로 컬럼 부재 → `AUG_FILE_PATH` 손실(증강 발생 사실만 보존).
- **D. 이슈**: `LS_DATA_ISSUE`(pjt,raw,src, 스레드) → 루트는 `ls_data_issue`, 답글은 `ls_issue_comment`. PJT 제거, raw/src 대응표 재연결.
- **ID 매핑(확장)**: 상태는 `raw_data_id = raw_sn`(공유), 배정/이슈/증강은 별도 오프셋(`asgn_off`/`issue_off`/`aug_off`).

---

## 2. 실행 절차

### STEP 0 — 사전 점검 (실데이터 상이 대비, 필수)
```sql
-- (v1, MySQL) export 대상 컬럼이 실제로 존재하는지 확인 — 없으면 export 실패
DESCRIBE LS_DATA_RAW;  DESCRIBE LS_DATA_SRC;  DESCRIBE LS_DATA_LBL;  DESCRIBE LS_PJT_LBL;
-- (v1) 라벨 클래스 전체 목록 — 02 의 mig_label_name_map 의미 매핑 검토 근거
SELECT DISTINCT LBL_ID, PRC_TYPE_CD FROM LS_PJT_LBL ORDER BY LBL_ID;
```
> v1 스키마가 본 분석(개발 v1)과 컬럼이 다르면 `01_export_mysql.sh` 의 SELECT 컬럼을 환경에 맞게 조정한다. 라벨 클래스가 다르면 `02_load_transform.sql` 의 `mig_label_name_map` 을 수정한다(미매핑·미존재 클래스는 자동 신규 추가되며, 매핑 누락 시 02 의 **5-b 가드가 트랜잭션을 ABORT**하므로 조용한 손실은 없다).

### STEP 1 — v1에서 export (MySQL 측, 망 안)
```bash
# 산출물: ./out/*.tsv  (JSON POINT 보존 위해 CSV 아닌 TSV)
bash 01_export_mysql.sh <v1_host> <v1_port> <v1_user> klid_system ./out
```
> `01_export_mysql.sh` 는 **7개 TSV**를 떨군다 — 핵심 4개(`raw`/`src`/`lbl`/`pjt_lbl`) + 확장 3개(`stts`=`LS_PJT_DATA_STTS`, `issue`=`LS_DATA_ISSUE`, `aug`=`LS_DATA_AUG`). 배정(B)은 별도 export 없이 `lbl.tsv` 의 `REG_ID`(작성자)에서 도출. NULL은 `\N`(psql `\copy` 기본 NULL 표기).

### STEP 2 — 파일 반입
`./out/*.tsv` 를 승인 절차로 PG 호스트로 이동(USB 등). 7개 파일.

### STEP 3·4 — PG 스테이징 적재 + 변환 (PostgreSQL 측)
```bash
# psql 변수: tsv 경로(:dir)와 ID 오프셋(:raw_off/:src_off/:lbl_off)
psql -h <pg_host> -p 15432 -U klid_user -d klid_system \
  -v dir="$(pwd)/out" \
  -v raw_off=1000000 -v src_off=10000000 -v lbl_off=100000000 \
  -v asgn_off=200000000 -v issue_off=300000000 -v aug_off=400000000 \
  -f 02_load_transform.sql
```
> 오프셋은 **현재 v2 max PK보다 크게**(충돌 회피). 기본값은 여유 있게 잡음. 실행 전 `02_load_transform.sql` 상단의 **`mig_label_name_map` 의미 매핑**을 환경 데이터에 맞게 검토·수정한다(예: `fallen_person→fallen-person`, `two_wheeler→motorbike`).
> **확장 스코프 전제**: 배정(B)·이슈 작성자는 `mng_acct_user.user_id`(=v1 `REG_ID`/`USER_ID`)가 v2 에 적재돼 있어야 매칭된다. 비어 있으면 배정은 누락(NOTICE 보고)되며 상태·라벨 이관은 정상 진행된다.

### STEP 5 — 검증
```bash
psql -h <pg_host> -p 15432 -U klid_user -d klid_system \
  -v raw_off=1000000 -v src_off=10000000 -v lbl_off=100000000 \
  -v asgn_off=200000000 -v issue_off=300000000 -v aug_off=400000000 \
  -f 03_verify.sql
```
> 03_verify.sql 도 6개 오프셋 변수를 **02 와 동일하게** 넘겨야 한다(미전달 시 WHERE 절 오작동으로 검증 무효).

모든 검증 항목이 `OK` 여야 한다. `FAIL` 이면 롤백(스테이징·이관분 삭제) 후 원인 교정.

---

## 3. 멱등성 / 롤백

- `02_load_transform.sql` 은 **트랜잭션 1개**로 감싸 실패 시 전체 롤백된다.
- 재실행 시: 이관분은 오프셋 PK라 `WHERE raw_sn >= :raw_off` 등으로 식별·삭제 가능. 스크립트 상단에 정리(cleanup) 블록 포함(주석 처리 — 의도적 실행).
- 운영자가 의미 매핑을 바꿔 재실행하려면 cleanup 블록을 활성화한 뒤 다시 실행.

## 4. 범위 / 주의

- **대상**: 라벨 보유 영상만 적재(무라벨 영상은 스크립트 `:only_labeled` 토글). 프레임·라벨·상태·배정·이슈·증강은 그 영상에 종속분만.
- **LS_RAW_DATA_STATUS / 검수완료 플래그 (확장 스코프 A로 해결됨)**: 이제 02 §7 이 `LS_PJT_DATA_STTS` 의 실제 상태를 `ls_raw_data_status` 로 이관한다. v1 `DONE`(최종완료) → `APPROVED` 로 매핑되어 **데이터마트 View(`DATA_STTS_CD='APPROVED'` 필터)에 자동 노출**된다. 별도 일괄 APPROVED 처리 불필요(실제 상태 보존).
- **파일 실체(영상/프레임 이미지)**: DB 경로만 이관한다. NAS 실파일 이전은 별도 절차.
- 실데이터는 본 분석(개발 v1)과 라벨 클래스·건수가 다를 수 있으므로 **STEP 5 검증 + 의미 매핑 검토는 필수**.

## 5. NAS 경로 정합 (배포 노트 — Critical)

이관되는 영상/프레임 경로는 **v1 절대경로 그대로**(`/nas-storage/...`)다. v2 코드는 이미지 서빙 시 `baseRawPath.resolve(경로)` 후 **`startsWith(STORAGE_RAW_PATH)` 가드**(CWE-22 경로순회 방어)를 적용하므로, **저장된 절대경로가 `STORAGE_RAW_PATH` 로 시작해야** 서빙된다.

- **v1 실제 경로 트리**:
  - 관제 클립(공용): `/nas-storage/data/clip/gov/{raw,preview}/...`
  - 저작도구 영상: `/nas-storage/label-studio/{raw,upload}/...`
  - 프레임 원천: `/nas-storage/label-studio/src/{프로젝트번호}/...`
  - 프레임 비식별: `/nas-storage/label-studio/bkup/{...}/...`
- **현재 on-prem 기본값(불일치)**: `STORAGE_RAW_PATH=/var/lib/klid/storage/raw`, `STORAGE_DEIDENTIFIED_PATH=/var/lib/klid/storage/deidentified`
  (`deploy/onprem/config/backend/env.template`). → `/nas-storage/...` 절대경로가 가드를 통과 못 해 **이미지/영상 서빙이 NOT_FOUND/FORBIDDEN** 된다.

### 배포 시 조치 (둘 중 하나)
1. **`STORAGE_RAW_PATH` 를 NAS 루트로 정합** (권장): 실제 NAS 마운트 루트(예: `/nas-storage`)로 설정 → v1 두 트리(`data/clip/gov`·`label-studio`)를 모두 덮어 절대경로가 가드를 통과. `STORAGE_DEIDENTIFIED_PATH` 도 비식별 프레임 실제 위치에 맞게 설정.
2. **이관 시 경로 재작성**: NAS 마운트 구조를 바꿀 수 없으면, 적재 단계에서 `raw_file_path`/`src_file_path` 의 prefix 를 v2 베이스로 치환(스크립트 보강 필요).

> ⚠ v2 자체 정합도 확인 필요: `TrainingVideoIngestTx` 는 관제 `MNG_CLIP_MASTER.FILE_PATH`(=`/nas-storage/...`)를 그대로 `rawFilePathNm` 에 저장하는 반면, `FfmpegFrameExtractor` 는 추출 프레임을 `STORAGE_RAW_PATH` 베이스에 쓴다. 적재 영상과 추출 프레임이 같은 베이스를 공유하도록 **운영 경로 규칙을 단일화**해야 가드가 일관되게 통과한다.
