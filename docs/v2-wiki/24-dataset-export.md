# 24. 검수 승인 시 학습데이터 파일 산출 (Dataset Export)

> 출처: CLAUDE.md(라벨링·버전관리·데이터마트 View), 코드(`dataset/export/`), NIA COCO 확장 포맷(xlsx v1.3).
> 관련: [12 검수](12-review-assignment.md) · [13 버전관리](13-version-control.md) · [15 관제서버 통지](15-control-notify.md) · [18 데이터베이스](18-database.md)

검수 승인(`APPROVED`) 시, 영상 1건의 확정 라벨을 **디스크에 물리 파일(프레임 이미지 + NIA COCO 확장 JSON)** 로 산출하는 기능이다. [13 버전관리](13-version-control.md)의 `LS_LABEL_VERSION`(DB 스냅샷)과는 **별개 레이어** — 버전관리는 DB 안의 라벨 페이로드 스냅샷이고, 본 기능은 데이터마트/외부 소비를 위한 **파일 산출물**이다.

## 24.1 트리거 (승인 AFTER_COMMIT, API 없음)

- 사용자가 직접 호출하는 REST API가 **없다**. 검수 승인 트랜잭션 커밋 이후 자동 실행된다.
- 배선: `ReviewApprovedEvent`(`@TransactionalEventListener(AFTER_COMMIT)`) → `DatasetExportBridge` → `@Async("batchAsyncExecutor")` `AsyncDatasetExportRunner.runAsync(rawSn)` → `DatasetExportService.export(rawSn)`.
- **승인 불변 정합(HIGH)**: 산출은 승인 커밋 이후 별도 스레드에서 실행되며, 파일 쓰기 실패는 예외를 삼키고 export 레코드를 `FAILED` 로만 기록한다 — **검수 승인은 절대 롤백되지 않는다**.
- 토글: `authoring.dataset-export.enabled` (기본 활성 — `matchIfMissing=true`). `false` 면 브릿지 빈이 등록되지 않아 산출이 비활성화된다.

## 24.2 폴더 구조

```
{authoring.storage.labeling-path}/{RAW_SN}/v{n}/orgnl/frame-{frameNo}.jpg
{authoring.storage.labeling-path}/{RAW_SN}/v{n}/orgnl/frame-{frameNo}.json
{authoring.storage.labeling-path}/{RAW_SN}/v{n}/deid/frame-{frameNo}.jpg
{authoring.storage.labeling-path}/{RAW_SN}/v{n}/deid/frame-{frameNo}.json
```

- **버전이 종류(orgnl/deid)의 상위** — `v{n}/{orgnl|deid}/`.
- `orgnl` = 원본 프레임 산출, `deid` = 비식별 프레임 산출. **원본·비식별 2벌**을 함께 산출한다.
- 프레임 이미지는 `FrameSource` 가 해석하는 원천 경로에서 복사한다 — 원본은 `authoring.storage.raw-path` 하위(`LS_DATA_SRC.SRC_FILE_PATH_NM`), 비식별은 `authoring.storage.deidentified-path` 하위(`LS_DATA_SRC.DE_IDNTF_SRC_FILE_PATH_NM`).
- **부분 성공**: 원천 이미지가 없는 프레임은 건너뛰고(skip) 나머지만 산출한다(경로 원문/PII 미로깅).
- **경로 보안(CWE-22/59)**: 산출 경로는 `DatasetExportPathResolver` 가 `labeling-path` 하위 포함을 `normalize()`+`startsWith` 로 검증, 원천 이미지는 `FrameSource` 가 base 하위 포함 + `toRealPath()` 심링크 이탈까지 재검증한다. JSON 은 `.tmp` 기록 후 `Files.move`(ATOMIC_MOVE 시도→REPLACE 폴백)로 원자적 교체.

## 24.3 JSON 포맷 (NIA COCO 확장 xlsx v1.3)

프레임마다 자기완결 문서 1건(`NiaAnnotationDoc`). **최상위 8키(고정 순서)**:

| 키 | 내용 |
|----|------|
| `info` | year·version(`1.3`)·description·date_created |
| `dataset` | id(=RAW_SN)·name·path·(url=null) |
| `licences` | 사용 라이선스(현재 private-use 1건) |
| `video` | 영상 메타 (§24.4 매핑표) |
| `image` | 프레임 메타 (아래) |
| `annotations` | 라벨 목록 — 타입별 bbox/polygon/keypoints 중 하나 |
| `categories` | 사용된 라벨 마스터(`LS_LABEL`) → 카테고리 |
| `type` | `instances` (고정) |

`@JsonInclude(ALWAYS)` 로 값이 null 인 **필수 키도 항상 직렬화**되어 관제 데이터마트 스키마와 정합한다.

### image 블록 주요 필드
- `file_name` = `frame-{frameNo}.jpg`, `frame_num` = frameNo, `width`/`height` = 영상 메타 해상도.
- `description` = `LS_DATA_SRC.FRM_EXPLN`(작업자 수기 프레임 설명). 미입력 시 null.
- `anonymity` = **orgnl → `N`, deid → `Y`** (산출 종류로 결정).
- `pseudonymity` = 영상 개인정보 유형이 `PSDO` 이면 `Y`, 아니면 `N`.

### annotations 블록 (타입별)
- `BBOX`/`TRACK` → `bbox=[x, y, w, h]` (POINT_CN min/max 바운딩, 픽셀 그대로).
- `POLYGON`/`SEGMENT` → `polygon=[[x, y, x, y, …]]` (flat).
- `SKELETON` → `keypoints=[[x, y, v]×17]` (v: 0 미표기 / 1 비가시 / 2 가시).
- malformed 라벨 1건은 문서 전체를 깨지 않고 skip(fail-secure).

## 24.4 video 필드 매핑표

`LsDatasetVideoMeta`(1차 소스) + `LsDataRaw`(폴백) → `NiaVideo`. 원천이 없는 **미보유 필수 필드는 null** 로 두되 키는 유지한다.

| video 키 | 원천 | 비고 |
|----------|------|------|
| `id` | RAW_SN | |
| `filename` / `orign_filename` | RAW_FILE_PATH_NM basename | |
| `date_created` | SHT_DT (YYYY-MM-DD) | |
| `format` / `filesize` | FILE_FMT / FILE_SZ | |
| `location` | SIDO_NM + SGG_NM | |
| `length` | VDO_LEN_SEC (폴백 raw.durationSec) | 초 |
| `fps` / `aspect_ratio` | FPS / ASPRT_RT | |
| `width` / `height` / `resolution` | VDO_WDTH / VDO_HGT / RESL | |
| `bit` | BIT_RT | |
| `weather` | WTHR_NM | 현재 null |
| `coordinates` | WGS84_LAT,WGS84_LOT | |
| `cctv_name` | CCTV_NM | |
| `anonymity` | **산출 종류 오버라이드** (orgnl=N/deid=Y) | |
| `pseudonymity` / `privacy_included` | PRVC_TYPE_CD(=PSDO?) / PRVC_YN | |
| `event_id` / `event_name` | EVNT_TYPE_CD / EVNT_NM | |
| `time_of_day` / `season` | DAY_NGT_CD / SESN_CD | |
| `type`, `pixel`, `frames`, `license_id`, `og_cd`, `cctv_height`, `cctv_azimuth`, `cctv_mng_no`, `cto`, `vqa`, `event_log` | — | **미보유 → null** (키 유지) |

> **미보유 필수 필드 null 정책 (소비측 주의)**: 위 "미보유" 필드는 저작도구가 원천 데이터를 보유하지 않아 **의도적으로 null** 이다. `@JsonInclude(ALWAYS)` 로 키 자체는 항상 존재하므로, 소비측은 "키 부재"가 아니라 "**값 null**"로 미보유를 판정해야 한다.

## 24.5 categories 와 SKELETON 인덱싱

- 사용된 `LS_LABEL` → `NiaCategory`(id·name·type). type: BBOX/TRACK→`bbox`, POLYGON/SEGMENT→`polygon`, POINT/SKELETON→`keypoints`.
- keypoints 타입 카테고리에는 COCO-17 관절명(`keypoints`)과 스켈레톤 엣지(`skeleton`)를 주입한다(`KeypointSkeleton`).
- **⚠ SKELETON_EDGES 는 1-indexed(COCO 표준 관절 번호쌍)** 이다. 관절명 리스트(`KEYPOINT_NAMES`)는 0-based 이지만, 스켈레톤 엣지 표기는 **1-based** 다(xlsx 예시가 0-indexed 로 표기한 것과 다름 — 소비측 오독 방지). 두 표기가 섞이지 않도록 주의.

## 24.6 멱등 · 버전 누적

- **버전 채번**: 기존 export 건수 + 1 = 다음 `EXPORT_VER_NO`. UK(DATA_RAW_SN, EXPORT_VER_NO) 위반 시 재채번 재시도(동시 승인 TOCTOU/CWE-362 백스톱).
- **무수정 재승인 멱등(콘텐츠 해시)**: 산출 시점 상태를 SHA-256 콘텐츠 해시로 계산한다. 해시 원천은 **라벨 + 프레임(FRM_EXPLN 등) + 영상 메타(개인정보 유형·해상도 등)** — 라벨뿐 아니라 프레임 설명/개인정보 정정도 반영한다. 직전 **SUCCEEDED** export 의 해시와 같으면 재산출을 **skip**(중복 버전 생성 방지). 직전이 FAILED/PENDING 이어도 그 이전 SUCCEEDED 해시를 상태 필터로 정확히 찾는다.
- **수정 후 재승인**: 라벨/설명/개인정보 변경 → 해시 변경 → **v{n+1} 폴더 신규 생성, 이전 버전 폴더는 보존**. 재검수→재승인마다 버전이 누적된다.

## 24.7 추적 원장 (LS_DATASET_EXPORT)

| 컬럼 | 내용 |
|------|------|
| `EXPORT_SN` | PK |
| `DATA_RAW_SN` | 대상 영상(RAW_SN) |
| `EXPORT_VER_NO` | 산출 버전(≥1). UK(DATA_RAW_SN, EXPORT_VER_NO) |
| `EXPORT_PATH_NM` | 산출 루트 경로(`{labeling_root}/{RAW_SN}/v{n}`) |
| `EXPORT_STTS_CD` | `PENDING → SUCCEEDED\|FAILED\|PARTIAL` |
| `FRAME_CNT` | 산출 프레임 파일 수(orgnl + deid 합산) |
| `CONTENT_HASH` | 산출 시점 콘텐츠 해시(SHA-256, 멱등 판정 키) |
| `REG_DT` | 생성 일시 |

## 24.8 설정

| 설정 키 | 기본값 | 의미 |
|---------|--------|------|
| `authoring.storage.labeling-path` | `./storage/labeling` | 학습데이터 파일 산출 루트(`{RAW_SN}/v{n}/orgnl\|deid/`) |
| `authoring.storage.raw-path` | `./storage/raw` | 원본 프레임 이미지 base(orgnl 복사 원천) |
| `authoring.storage.deidentified-path` | `./storage/deidentified` | 비식별 프레임 이미지 base(deid 복사 원천) |
| `authoring.dataset-export.enabled` | `true`(matchIfMissing) | 승인 시 파일 산출 트리거 토글 |

## 24.9 코드 · 테스트

- 코드: `dataset/export/` — `DatasetExportService`(오케스트레이터) · `DatasetExportTxService`(REQUIRES_NEW DB 게이트웨이) · `DatasetExportWriter`(파일 쓰기) · `DatasetExportPathResolver`(CWE-22) · `FrameSource`(원천 이미지·CWE-59) · `json/`(NIA 문서 빌더·매퍼) · `listener/DatasetExportBridge` · `AsyncDatasetExportRunner`.
- 테스트: `DatasetExportServiceIT`(채번·상태·멱등, 파일 부재 환경) · `DatasetExportE2EIT`(실 이미지 fixture 기반 디스크 파일 산출 end-to-end — orgnl/deid 이미지+JSON 페어, 8키·description·좌표·anonymity, v1/v2 누적).
