# 05. 영상 관리 · 업로드

> 출처: CLAUDE.md(배치 파이프라인·파일 업로드), 코드(`video/`, db migration), D9
> 관련: [06 마킹](06-marking.md) · [07 배치 파이프라인](07-batch-pipeline.md) · [18 DB](18-database.md)

화면: `KLID-AT-SC-007`(영상 목록 `/video/completed`), `SC-008`(처리 현황 `/video/status`), `SC-009`(영상 상세 `/video/:id`). 코드: `video/`(29 파일).

## 5.1 작업 단위 = 영상 1건

- 작업 식별자: **`LS_DATA_RAW.RAW_SN`** (프로젝트 개념 없음)
- 영상 1건이 마킹→배치→라벨링→검수→통지의 단위
- 증강 결과는 **새 영상**(`RAW_SN`, `PARENT_RAW_SN`=원본 참조) → [14](14-augmentation.md)

## 5.2 영상 적재

- 영상 적재는 **자체 업로드(관리 화면) 기반** — 관제서버 자동 송신 미연동
- **포털 사용자 업로드는 미제공** (ADR-013)
- **TUS 재개 가능 업로드**(CVAT portable-modules/03 포팅) — 내부 관리 화면 대용량 영상 적재용

### 업로드 검증 (보안)
- 확장자 allowlist + 파일 크기 제한 + MIME 검증 필수
- 경로 순회(`..`) 차단(`Path.normalize` + 기준 경로 검증, CWE-22)

## 5.3 영상 스트리밍

- `GET /v1/videos/{rawSn}/stream` — **HTTP Range 지원** (마킹 화면 재생용)
- 마킹 화면에서 배속(0.25x~4x) 재생 → [06](06-marking.md)

## 5.4 개인정보 분류 (PRVC_TYPE_CD)

| 값 | 의미 | 비식별 처리 |
|----|------|------------|
| `PRVC` | 개인정보 포함 | 비식별 호출 |
| `PSDO` | 가명처리 대상 | 비식별 호출 |
| `ANONY` | 비식별 불요 | 원본만 저장 |

- 원본 영상과 비식별 영상은 **별도 경로 동시 저장** (`STORAGE_RAW_PATH` / `STORAGE_DEIDENTIFIED_PATH`)
- 비식별 상태: `LS_DATA_RAW.DE_IDNTF_YN` (Y/N/F) → [08](08-deidentification.md)

## 5.5 영상 상태 (LS_RAW_DATA_STATUS)

- `LS_RAW_DATA_STATUS.DATA_STTS_CD` — 작업(검수 워크플로우) 진행 상태
- 배치 진행 시 `PROCESSING`, 배치 완료 시 `ASSIGNED` 복귀(라벨링/검수 진행 가능), 검수 제출 `PENDING`, 검수 시작 `IN_REVIEW`, 검수 승인 `APPROVED`, 실패 `FAILED`
- `COMPLETED` 는 작업 종결 상태로 검수 승인 흐름에서만 도달(배치 완료가 점프시키지 않음). 배치 단계 종료는 `LS_DATA_RAW.DATA_STTS_CD=COMPLETED` 로 별도 표기
- `APPROVED` 전이 시 버전 스냅샷 + 관제 `TASK_COMPLETED` 통지
- 영상 등록/상태 분리: `LS_RAW_DATA_ENROLLMENT`(등록) + `LS_RAW_DATA_STATUS`(상태)

## 5.6 관련 데이터 (DB)

`LS_DATA_RAW`(영상 메타·VMS_CLIP_ID·EVNT_TYPE_CD·DE_IDNTF_YN·PARENT_RAW_SN), `LS_DATA_RAW_HSTRY`(상태 이력), `LS_DATA_SRC`(추출 프레임·원본/비식별 경로), `LS_RAW_DATA_STATUS`/`LS_RAW_DATA_ENROLLMENT`. 관제 소유 `MNG_CLIP_MASTER`/`MNG_RESOURCE_CCTV` 참조. → [18](18-database.md).
