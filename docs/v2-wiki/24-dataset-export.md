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
- **부분 성공**: 원천 이미지가 없는 프레임은 건너뛰고(skip) 나머지만 산출한다(경로 원문/PII 미로깅). 산출량에 따라 상태가 갈린다 — 전부 성공=`SUCCEEDED`, 일부만 산출(skip>0 & written>0)=`PARTIAL`, 한 장도 못 씀(written=0)=`FAILED`(§24.7).
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
| `annotations` | 라벨 목록 — 타입별 bbox/polygon/keypoints 중 하나 (객체 인스턴스 배열) |
| `event_annotation` | **[설계 제안·미구현]** 이벤트 단위 VLM VQA/CoT (§24.3.1). 키·형태 변경 가능 |
| `categories` | 사용된 라벨 마스터(`LS_LABEL`) → 카테고리 |
| `type` | `instances` (고정) |

`@JsonInclude(ALWAYS)` 로 값이 null 인 **필수 키도 항상 직렬화**되어 관제 데이터마트 스키마와 정합한다.

### image 블록 주요 필드
- `file_name` = `frame-{frameNo}.jpg`, `frame_num` = frameNo, `width`/`height` = 영상 메타 해상도.
- `orign_file_name` = **산출 종류별 원천 소스 파일 basename** — orgnl 은 `LS_DATA_SRC.SRC_FILE_PATH_NM`, deid 는 `DE_IDNTF_SRC_FILE_PATH_NM` 의 basename.
- `description` = `LS_DATA_SRC.FRM_EXPLN`(작업자 수기 프레임 설명). 미입력 시 null.
- `anonymity` = **orgnl → `N`, deid → `Y`** (산출 종류로 결정).
- `pseudonymity` = 영상 개인정보 유형이 `PSDO` 이면 `Y`, 아니면 `N`.

> **orgnl ↔ deid JSON 델타(소비측 주의)**: 같은 프레임의 원본/비식별 JSON 은 **`anonymity`(N/Y) 와 `orign_file_name`(원천 소스 파일명)** 만 다르다. **`file_name`(`frame-{n}.jpg`)·`frame_num`·좌표·`annotations`·해상도·`description` 은 동일**하다(동일 basename 은 원본↔비식별 페어링을 위한 의도된 설계이며, 두 벌은 폴더 경로 `orgnl/`·`deid/` 로 구분된다). 즉 "이미지 파일명이 다르다"가 아니라 "**폴더와 anonymity·원천파일명이 다르다**"가 정확한 서술이다.

### annotations 블록 (타입별)
- `BBOX`/`TRACK` → `bbox=[x, y, w, h]` (POINT_CN min/max 바운딩, 픽셀 그대로).
- `POLYGON`/`SEGMENT` → `polygon=[[x, y, x, y, …]]` (flat).
- `SKELETON` → `keypoints=[[x, y, v]×17]` (v: 0 미표기 / 1 비가시 / 2 가시).
- malformed 라벨 1건은 문서 전체를 깨지 않고 skip(fail-secure).

### 24.3.1 event_annotation 블록 (VLM VQA/CoT) — 설계 제안(미구현, 키 변경 가능)

> ⚠️ **본 절은 확정 설계가 아닌 제안이다. 필드명(키)·중첩 형태는 벤더 VLM 계약 확정 시 변경될 수 있다.** 현재 코드(`dataset/export/json/`)엔 미구현이며, `annotations`(COCO 객체 배열)과 **분리된 최상위 키**로 둔다.
> 근거: `docs/망AI기반CCTV_어노테이션 포맷 및 데이터 구조_저작도구팀 확인요_VLM어노테이션추가.xlsx`(속성 정의) + `docs/AI기반CCTV_어노테이션_예제_sample.json`(예제).

**분리 이유**: VQA/CoT는 프레임·객체 단위가 아니라 **이벤트/영상 단위** 추론·서술 메타다. COCO 규격의 `annotations`(per-object 배열, → `LS_DATA_LBL`)와 층위가 달라, 우리 DB에서도 이미 **`LS_DATA_META`(영상 키, RAW_SN)** 로 분리 적재되는 값이다. 따라서 JSON도 `annotations` 안에 넣지 않고 **별도 최상위 `event_annotation`(object)** 으로 둔다. ([09 VLM 시계열](09-vlm-timeseries.md) 참조)

| 필드 (경로) | 타입 | 값 조달 | 비고 |
|-------------|------|:------:|------|
| `event_class` | string | VLM | 이벤트 클래스(분류명) |
| `question` | string | VLM | 이벤트 발생 여부·근거를 묻는 질문 |
| `caption` | object | **VLM + 수동입력** | 후보 캡션 집합 `{c1, c2, … cn}` |
| `caption.{cN}.caption_text` | string | VLM + 수동입력 | 후보별 캡션 텍스트 |
| `caption.{cN}.cot.{1·2·3단계}` | string | VLM + 수동입력 | 사고 과정(CoT) **1·2·3단계 전부 필수** |
| `answer` | string | VLM | 이벤트 확인 결과 |
| `evidence` | object | VLM | 후보 근거 집합 `{c1, c2, … cn}` |
| `evidence.{cN}.evidence_text` | string | VLM | 후보별 근거 서술 |
| `evidence.{cN}.frame_id` | number[] | VLM | 근거 프레임 ID 목록 |
| `evidence.{cN}.obj_id` | string[] | VLM | 객체 ID 목록 |
| `evidence.{cN}.obj_bbox` | number[][] | VLM | 객체 바운딩박스 `[x1,y1,x2,y2]` 목록 |
| `evidence.{cN}.obj_label` | string[] | VLM | 객체 라벨 목록 |

- **조달 구분**: 전 필드가 **VLM 전달 수신**이 원칙이나, **`caption`(후보·CoT)만 VLM+수동입력** — 일부만 VLM이 전달하고 미전달분은 UI로 검수자가 수동 입력한다.
- **후보 키(`c1`~`cn`)는 가변**이며, `cot`는 후보마다 1·2·3단계를 모두 채운다.

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
| `type`, `pixel`, `frames`, `license_id`, `og_cd`, `cctv_height`, `cctv_azimuth`, `cctv_mng_no`, `event_log` | — | **미보유 → null** (키 유지) |

> **VQA/CoT 위치 정정**: 구 video 블록의 `cto`/`vqa` 플레이스홀더는 폐기한다 — VLM VQA/CoT는 영상 기술메타(video)가 아니라 **최상위 `event_annotation`**(§24.3.1)으로 분리한다. (설계 제안·키 변경 가능)

> **미보유 필수 필드 null 정책 (소비측 주의)**: 위 "미보유" 필드는 저작도구가 원천 데이터를 보유하지 않아 **의도적으로 null** 이다. `@JsonInclude(ALWAYS)` 로 키 자체는 항상 존재하므로, 소비측은 "키 부재"가 아니라 "**값 null**"로 미보유를 판정해야 한다.

## 24.5 categories 와 SKELETON 인덱싱

- 사용된 `LS_LABEL` → `NiaCategory`(id·name·type). type: BBOX/TRACK→`bbox`, POLYGON/SEGMENT→`polygon`, POINT/SKELETON→`keypoints`.
- keypoints 타입 카테고리에는 COCO-17 관절명(`keypoints`)과 스켈레톤 엣지(`skeleton`)를 주입한다(`KeypointSkeleton`).
- **⚠ SKELETON_EDGES 는 1-indexed(COCO 표준 관절 번호쌍)** 이다. 관절명 리스트(`KEYPOINT_NAMES`)는 0-based 인덱스로 접근하지만, 스켈레톤 엣지의 관절 번호는 **1-based**(예: `[16,14]` = 16번 left_ankle ↔ 14번 left_knee, 배열 접근 시 −1)다. 이는 COCO person-keypoints 표준 토폴로지와 정확히 일치하며 BE `KeypointSkeleton.SKELETON_EDGES` ↔ FE `COCO_SKELETON` 이 동일 값으로 잠겨 있다(FE 테스트 `keypointConstants.test.ts` 로 parity 검증, 렌더러는 `keypoints[a−1]` 로 소비).
  - **정본 대조**: xlsx v1.3 은 `skeleton` 필드를 "키포인트 연결 정보(`number[]`)"로만 선언하고 **index base(0/1)를 규정하지 않는다**(구체 예시 없음). 따라서 저작도구는 사실상 표준인 **COCO 1-indexed** 를 채택한다 — xlsx 와 모순되지 않는다. 외부 소비측(관제 데이터마트)은 skeleton 관절 번호를 **1-based** 로 해석해야 한다(off-by-one 방지).

## 24.6 멱등 · 버전 누적

- **버전 채번**: 기존 export 건수 + 1 = 다음 `EXPORT_VER_NO`. UK(DATA_RAW_SN, EXPORT_VER_NO) 위반 시 재채번 재시도(동시 승인 TOCTOU/CWE-362 백스톱).
- **무수정 재승인 멱등(콘텐츠 해시)**: 산출 시점 상태를 SHA-256 콘텐츠 해시로 계산한다. 해시 원천은 **라벨 + 프레임(FRM_EXPLN 등) + 영상 메타(개인정보 유형·해상도 등)** — 라벨뿐 아니라 프레임 설명/개인정보 정정도 반영한다. 직전 **SUCCEEDED 또는 PARTIAL**(멱등 baseline) export 의 해시와 같으면 재산출을 **skip**(중복 버전 생성 방지). 직전이 FAILED/PENDING 이어도 그 이전의 SUCCEEDED/PARTIAL 해시를 상태 IN 필터로 정확히 찾는다.
  - **PARTIAL 을 baseline 에 포함하는 이유(무한 누적 방지)**: 원천 이미지가 지속 부재해 매번 `PARTIAL` 로 끝나는 영상을 무수정 재승인할 때, PARTIAL 을 baseline 에서 제외하면 `v2·v3·v4…` 가 무한 채번되며 매 버전 이미지 파일이 재복사되어 디스크가 무한 증가한다(OWASP API4). PARTIAL 도 멱등 baseline 으로 삼아 이를 차단한다. `FAILED`(written=0)는 디스크 누적이 없고 재시도를 유도해야 하므로 baseline 에서 계속 제외한다.
  - **한계(설계상 수용)**: PARTIAL 후 라벨이 불변인 채 부재 이미지가 나중에 실제로 복구돼도, 해시가 같아 자동 재완성되지는 않는다(라벨/설명을 조금이라도 수정하면 해시 변경으로 재산출됨). 이는 무한 누적 방지를 위한 의도된 트레이드오프이며 기존 산출물은 보존된다(데이터 손실 아님).
- **수정 후 재승인**: 라벨/설명/개인정보 변경 → 해시 변경 → **v{n+1} 폴더 신규 생성, 이전 버전 폴더는 보존**. 재검수→재승인마다 버전이 누적된다.

## 24.7 추적 원장 (LS_DATASET_EXPORT)

| 컬럼 | 내용 |
|------|------|
| `EXPORT_SN` | PK |
| `DATA_RAW_SN` | 대상 영상(RAW_SN) |
| `EXPORT_VER_NO` | 산출 버전(≥1). UK(DATA_RAW_SN, EXPORT_VER_NO) |
| `EXPORT_PATH_NM` | 산출 루트 경로(`{labeling_root}/{RAW_SN}/v{n}`) |
| `EXPORT_STTS_CD` | `PENDING → SUCCEEDED\|PARTIAL\|FAILED` (아래 상태 의미) |
| `FRAME_CNT` | 산출 프레임 파일 수(orgnl + deid 합산) |
| `CONTENT_HASH` | 산출 시점 콘텐츠 해시(SHA-256, 멱등 판정 키) |
| `REG_DT` | 생성 일시 |

**상태 의미**: `PENDING`=채번 후 파일 쓰기 전(예약), `SUCCEEDED`=전 프레임 산출, `PARTIAL`=일부 프레임 skip(원천 이미지 부재 등), `FAILED`=한 장도 못 씀 또는 파일 쓰기 예외.

**stale PENDING 정리 (크래시 복구)**: `insertNextVersion` 이 PENDING 레코드를 커밋한 뒤 파일 쓰기/상태 마감 전에 프로세스가 크래시하면 그 레코드가 `PENDING` 으로 영구 고착된다. 주기 Quartz 잡 `DatasetExportPendingSweepJob`(기본 10분 간격, `@DisallowConcurrentExecution` + PostgreSQL JobStore 클러스터 락으로 2노드 중 1노드만 tick)이 `REG_DT` 가 `stale-minutes`(기본 30분) 이전인 PENDING 을 `FAILED` 로 마감한다. **파일은 삭제하지 않고 상태만 회수**한다(라벨링 전용 루트라 잔재 파일은 정합에 무해하며, 다음 산출은 `v{n+1}` 로 진행). 정상 산출은 수 초 내 완료되므로 30분 임계를 넘는 PENDING 은 크래시 잔재뿐이다. 초장기 산출이 sweep 으로 FAILED 마킹돼도 이후 완료가 `SUCCEEDED` 로 최종 수렴한다(last-writer-wins, 무해). `stale-minutes` 오설정(0/음수)은 안전 기본값 30 으로 폴백한다.

## 24.8 설정

| 설정 키 | 기본값 | 의미 |
|---------|--------|------|
| `authoring.storage.labeling-path` | `./storage/labeling` | 학습데이터 파일 산출 루트(`{RAW_SN}/v{n}/orgnl\|deid/`) |
| `authoring.storage.raw-path` | `./storage/raw` | 원본 프레임 이미지 base(orgnl 복사 원천) |
| `authoring.storage.deidentified-path` | `./storage/deidentified` | 비식별 프레임 이미지 base(deid 복사 원천) |
| `authoring.dataset-export.enabled` | `true`(matchIfMissing) | 승인 시 파일 산출 트리거 토글 |
| `authoring.dataset-export.pending-sweep.enabled` | `true`(matchIfMissing) | stale PENDING 정리 Quartz 잡 등록 토글 |
| `authoring.dataset-export.pending-sweep.interval-sec` | `600` | sweep 실행 간격(초) |
| `authoring.dataset-export.pending-sweep.stale-minutes` | `30` | 이 분(minute)보다 오래된 PENDING 을 FAILED 로 회수(0/음수 오설정 시 30 폴백) |

## 24.9 관찰성 메트릭 (Micrometer)

산출 오케스트레이션은 종결 시점마다 메트릭을 발행한다. 메트릭은 전용 `DatasetExportMetrics`(`observability.metrics` 패키지, 기존 `*Metrics` 컴포넌트 관례) 로 캡슐화한다. 태그는 6개 고정 문자열만 사용(저카디널리티 — rawSn·경로 등 미포함).

| 메트릭 | 타입 | 태그 | 의미 |
|--------|------|------|------|
| `dataset.export.result` | Counter | `outcome` | 산출 종결 건수. outcome ∈ `completed`/`partial`/`failed`/`version_exhausted`/`idempotent_skip`/`no_input` |
| `dataset.export.duration` | Timer | `outcome` | 산출 1건 소요 시간(outcome 별) |
| `dataset.export.skipped_frames` | Counter | — | 원천 이미지 부재 등으로 건너뛴 프레임 총량 |

- **정확히 1회 기록**: `export()` 진입 시 `Timer.Sample` 시작 + outcome 지역변수(기본값 `failed`), 모든 종결 분기(조기 return 3종 + 파일결과 3종 + 예외)에서 `finally` 단일 지점이 timer stop + result counter 를 배타적으로 1회 기록한다. 계측 코드는 예외를 던지지 않아 "파일 실패가 승인 롤백 안 함" 계약이 불변이다.
- **outcome ↔ 레코드 정합**: 파일을 다 썼더라도 상태전이(`markSucceeded`)가 예외로 실패하면 레코드는 `FAILED` 로 마감되고 metric outcome 도 `failed` 다(다운스트림 View 는 SUCCEEDED 만 소비하므로 실효 결과와 일치).

## 24.10 코드 · 테스트

- 코드: `dataset/export/` — `DatasetExportService`(오케스트레이터) · `DatasetExportTxService`(REQUIRES_NEW DB 게이트웨이 + `sweepStalePending`) · `DatasetExportWriter`(파일 쓰기) · `DatasetExportPathResolver`(CWE-22) · `FrameSource`(원천 이미지·CWE-59) · `json/`(NIA 문서 빌더·매퍼) · `listener/DatasetExportBridge` · `AsyncDatasetExportRunner` · `DatasetExportPendingSweeper`. 스케줄러: `batch/scheduler/DatasetExportPendingSweepJob` · `DatasetExportPendingSweepTriggerConfig`. 메트릭: `observability/metrics/DatasetExportMetrics`.
- 테스트: `DatasetExportServiceTest`(판정 3분기·멱등·재채번·메트릭 outcome) · `DatasetExportPendingSweeperTest`/`DatasetExportPendingSweepJobTest`(sweep cutoff·하한폴백·예외삼킴) · `DatasetExportTxServiceTest`(멱등 baseline IN·sweep) · `DatasetExportServiceIT`(채번·상태·멱등, 파일 부재 환경) · `DatasetExportE2EIT`(실 이미지 fixture 기반 디스크 파일 산출 end-to-end — orgnl/deid 이미지+JSON 페어, 8키·description·좌표·anonymity, v1/v2 누적).
