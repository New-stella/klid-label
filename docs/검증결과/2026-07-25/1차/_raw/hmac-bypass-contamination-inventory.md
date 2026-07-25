# HMAC 우회 침투 오염 데이터 인벤토리

> 작성: 2026-07-25 | 대상: `A-ISSUE-13` / `E-ISSUE-01`(CRITICAL) 침투로 생성·전이된 데이터
> 환경: 로컬 docker 스택 (`klid-postgres`, DB `klid_system`, 스키마 `public`)
> 계획서 근거: `.claude-plan.md` Phase 1 "오염 데이터 처리 (사용자 확정 — 인벤토리 후 격리)"

## 1. 침투 요약

`POST /api/v1/%61ug/callback` 이 HMAC 필터를 우회해 무인증으로 컨트롤러에 도달했고, 그 결과:
- 증강행 **4건** 상태 전이
- 신규 증강영상 **2건**(`raw_sn=29`, `raw_sn=31`) 생성

## 2. 영향 범위 실측 (조회 기준 2026-07-25)

### 2-1. 무인증 생성 영상 2건

| raw_sn | ORGNL_RAW_SN | VMS_CLIP_ID | DATA_STTS_CD | RAW_FILE_PATH_NM |
|---:|---:|---|---|---|
| 29 | 13 | `test-1784092965352_AUG_WINTER_1784947127240` | **FAILED** | `/nas-storage/aug/winter8.mp4` |
| 31 | 17 | `test-1784791814270_AUG_RAIN_1784947281795` | **FAILED** | `/nas-storage/aug/conc.mp4` |

**자식 행 전수 조회 결과 — 전부 0건:**

| 테이블/뷰 | 29·31 관련 행 수 |
|---|---:|
| `ls_data_src` (프레임) | **0** |
| `ls_dataset_export` | **0** |
| `ls_raw_data_status` | **0** |
| `v_completed_video` (데이터마트 뷰) | **0** |
| `ls_data_aug` (29·31을 src 로 갖는 고아) | **0** |

→ **두 영상은 FAILED 로 종결돼 프레임·export·데이터마트 어디에도 노출되지 않았다.**

### 2-2. 상태 전이된 증강행 4건

| data_aug_sn | src_sn | raw_sn | AUG_TYPE_CD | AUG_PROC_STTS_CD | OTSD_JOB_ID | REG_DT | 라벨맵 |
|---:|---:|---:|---|---|---|---|---:|
| 8 | 358 | 13 | WINTER | ACCEPTED | `EXT-OK-1` | 2026-07-25 11:37:11 | 0 |
| 9 | 2 | 5 | NIGHT | ACCEPTED | `EXT-PII-1` | 2026-07-25 11:38:30 | 0 |
| 10 | 372 | 14 | RAIN | REJECTED | `EXT-FAIL-1` | 2026-07-25 11:38:30 | 0 |
| 16 | 408 | 17 | RAIN | ACCEPTED | `CONC-1` | 2026-07-25 11:40:19 | 0 |

### 2-3. 관제 통지 유출 여부

| 항목 | 값 | 판정 |
|---|---|---|
| `CONTROL_NOTIFY_ENABLED` (backend 컨테이너 실측) | `false` | 통지 빈 미등록 |
| `ls_control_notify_fallback` 행 수 | **0** | 발송 이력 없음 |

→ **관제서버로의 유출은 발생하지 않았다.**

## 3. ★ 성격 재판정 — "공격자 데이터"가 아니라 "검증 잔재"

`OTSD_JOB_ID` 를 보면 오염 4건은 전부 **1차 검증이 `TC-AUG-050~063` 실행을 위해 심어둔 합성 픽스처**다:

| 구분 | OTSD_JOB_ID 패턴 | 예 |
|---|---|---|
| **검증 합성 픽스처** | 센티넬 문자열 | `EXT-OK-1` · `EXT-PII-1` · `EXT-FAIL-1` · `CONC-1` |
| 정규 경로 생성 | `JOB-<uuid>` | `JOB-031e3502-…` (aug_sn 14·15·21) |
| 해상도 파생 | (없음) | aug_sn 1~4 |

`raw_sn=29·31` 의 `VMS_CLIP_ID` 도 `test-…` 접두를 가진 검증용 값이다.

**따라서 이 데이터는 실사용자 데이터가 손상된 것이 아니라, 우회 취약점을 실증하는 과정에서 검증자가 생성한 로컬 환경 잔재다.** 정리의 목적은 실데이터 보호가 아니라 **2차 재검증을 깨끗한 상태에서 시작하는 것**이다(계획서: "재검증은 정리 후 신규 rawSn 으로 수행").

## 4. 권고 조치

| 대상 | 조치 | 근거 |
|---|---|---|
| `raw_sn=29·31` | **손대지 않음** | 이미 `FAILED` 이고 프레임·export·데이터마트 노출 0건. 삭제하면 침투 발생 사실의 감사 흔적만 사라진다 |
| `data_aug_sn` 8·9·10·16 | **`PENDING` 복원하지 않음**. 검증 잔재로 식별 가능하도록 본 문서로 기록 유지 | `PENDING` 으로 되돌리면 배치가 살아있는 요청으로 오인해 재처리할 수 있어 오히려 위험 |
| 2차 재검증 | **신규 `rawSn` 으로 수행** | 기존 오염 행 위에서 판정하지 않기 위함 |
| 회귀 방어 | `WebhookPathBypassSecurityIT` 의 "우회 시도 → 401 + DB 무변경(before/after count 동일)" 단정 | 동일 침투가 재현되면 테스트가 실패 |

## 5. 재조회 SQL (재실행 안전 — 읽기 전용)

```sql
-- 무인증 생성 영상 + 자식 행
SELECT 'RAW' src, raw_sn::text, orgnl_raw_sn::text, vms_clip_id, data_stts_cd
  FROM ls_data_raw WHERE raw_sn IN (29,31)
UNION ALL SELECT 'FRAMES', count(*)::text, '-', '-', '-' FROM ls_data_src WHERE raw_sn IN (29,31)
UNION ALL SELECT 'EXPORT', count(*)::text, '-', '-', '-' FROM ls_dataset_export WHERE data_raw_sn IN (29,31)
UNION ALL SELECT 'MARTVIEW', count(*)::text, '-', '-', '-' FROM v_completed_video WHERE raw_sn IN (29,31);

-- 검증 합성 픽스처 식별 (센티넬 job id)
SELECT data_aug_sn, src_sn, aug_type_cd, aug_proc_stts_cd, otsd_job_id, reg_dt
  FROM ls_data_aug
 WHERE otsd_job_id IS NOT NULL AND otsd_job_id NOT LIKE 'JOB-%'
 ORDER BY data_aug_sn;

-- 관제 통지 발송 이력
SELECT count(*) FROM ls_control_notify_fallback;
```
