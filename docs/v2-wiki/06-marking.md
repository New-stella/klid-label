# 06. 마킹

> 출처: CLAUDE.md(마킹 단계·마킹 화면), R2 KLID-AT-SS-002, 코드(`marking/`)
> 관련: [07 배치 파이프라인](07-batch-pipeline.md) · [09 VLM 시계열](09-vlm-timeseries.md)

화면: `KLID-AT-SC-006`(마킹 `/marking/:rawSn`). 코드: `marking/`(9 파일) — v2 신설 기능(v1 없음).

## 6.1 마킹이란

영상에서 **이벤트(관심 시점)를 자동/수동으로 표시**하는 단계. 마킹 결과는 외부 VLM 시계열 콜백 트리거가 되고, FFmpeg 프레임 추출 위치의 기준이 된다.

- 마킹 결과 = **이벤트명 + 영상 경로 + marks 배열** (`LS_MARKING.MARK_CN` JSON)
- **이벤트명은 수동 입력하지 않는다** — 영상의 이벤트 유형(`LS_DATA_RAW.EVNT_TYPE_CD`, 적재 시 MNG_CLIP_EVNT_LST 조인으로 보존)을 서버가 자동 소싱해 `LS_MARKING.EVNT_NM` 에 채운다(API-047 계약 변경). 이벤트 유형이 미지정(null/blank)인 영상은 마킹할 수 없다(`INVALID_INPUT` 400). VLM 전달 페이로드 형식은 무변경(자동 소싱된 값을 `EVNT_NM` 으로 그대로 전달)
- 마킹 대상: **비식별 영상** (구현됨, NFR-001 v1.5). 적재 직후 선두 비식별이 완료(`LsDataRaw.dataSttsCd=MARKING_READY`, `deIdntfYn='Y'`)된 영상만 마킹 진입 → [08](08-deidentification.md)

## 6.2 자동 / 수동 모드

영상별 모드 설정 (`LS_MARKING.MARK_MODE_CD`):

| 모드 | 동작 |
|------|------|
| **자동(AUTO)** | **프레임 간격**(`FRME_INTV_NOCS`, intervalFrames) 기반 자동 마킹 |
| **수동(MANUAL)** | 작업자가 키보드 단축키로 이벤트 시점 마킹 |

## 6.3 마킹 화면

- **비식별 영상** 스트리밍 재생 (`GET /v1/videos/{rawSn}/stream`, HTTP Range — 항상 비식별 영상 서빙, 비식별 미완료 시 NOT_FOUND 로 원본 노출 차단) → [05](05-video-management.md#53-영상-스트리밍)
- **배속 설정 0.25x ~ 4x**
- **키보드 단축키**: `Space`(마킹), `Del`(삭제), `Enter`(완료)
- **이벤트명 입력란 없음** — 영상의 이벤트 유형(`EVNT_TYPE_CD`)에서 자동 소싱(6.1)
- **'저장된 마킹 목록'(MarkingList) 없음** — 화면은 현재 작업 중 마크(타임라인/마크 칩)만 표시. 이를 백킹하던 마킹 관리 API(목록 조회 `GET /v1/videos/{rawSn}/markings`, 단건 조회 `GET .../{markingSn}`, 삭제 `DELETE .../{markingSn}`)는 FE 미사용으로 제거됨 — 마킹 API 는 생성(`POST /v1/videos/{rawSn}/markings`)만 보유
- **완료 버튼 활성 조건**: 수동=마크 1건 이상, 자동=intervalFrames 유효(1 이상)
- 비식별 누락 신고: 라벨링 단계(srcSn 기준)·**마킹 단계(rawSn 기준 `POST /v1/videos/{rawSn}/deident-report`) 모두 구현됨** — 부수효과(작업락 + `DE_IDENT_YN='F'` + 개인정보 3필드 리셋 + 검수완료 영상 통지)는 두 경로가 동일하며 파생영상은 412 로 거부된다 → [08](08-deidentification.md#84-누락-신고-rq-sfr-09-03-uc-016)

## 6.4 마킹 완료 → 배치 자동 시작

```
마킹 완료
  → MarkingCompletedEvent 발행
  → MarkingBatchBridge (AFTER_COMMIT)
  → deid 가드(deIdntfYn='Y' 검증) → @Async post-marking 배치 자동 시작
```

- **deid 가드 (2중)**: ①**마킹 생성 단계** — `MarkingService.create` 가 비식별 미완료(`deIdntfYn != 'Y'`) 영상의 마킹 생성을 거부(`PRECONDITION_FAILED` 412, "비식별이 완료된 영상에서만 마킹할 수 있습니다."). ②**배치 진입 단계** — `MarkingBatchBridge` 가 비식별 미완료 영상의 잔여 배치 트리거를 차단. "마킹은 비식별 완료 영상 대상" 규칙을 생성·배치 두 지점에서 일관 강제한다.
- 이후 배치는 비식별 단계를 제외한 post-marking 시퀀스(VLM→프레임추출→YOLO→SAM2→보간).

코드: `MarkingCompletedEvent`, `MarkingBatchBridge`. 이후 파이프라인 → [07](07-batch-pipeline.md).

## 6.4-1 영상당 활성 마킹 1건 (409)

- **활성 = 미종결** = `STTS_CD IN ('PENDING','VLM_REQUESTED')`. 같은 `rawSn` 에 활성 마킹이 이미 있으면 마킹 생성은 **409(CONFLICT)** 로 거부된다.
- 이유: 배치는 영상당 **최신 마킹 1건**만 VLM 에 위탁하므로(`MarkingLoadStep`/`VlmTimeseriesStep`), 활성 마킹이 2건 이상이면 나머지는 영원히 `PENDING` 인 고아 행으로 남는다.
- 종결 상태(`VLM_COMPLETED`/`VLM_FAILED`)만 남은 영상의 **재마킹은 허용**된다(새 배치 사이클을 여는 정당한 동선).
- 동시 요청 방어는 서비스 사전 조회가 아니라 **DB 부분 유니크 인덱스** `UK_LS_MARKING_RAW_ACTVTN`(V142)가 최종 보증한다 — 위반은 409 로 변환된다.

### 6.4-2 VLM 위탁 구간의 마킹 상태 전이 (2026-07-30)

VLM 제출이 논블로킹이 되면서 **콜백이 수락 응답(ACK)보다 먼저 도착**할 수 있게 됐다. 마킹 상태가 `VLM_REQUESTED` 에 영구 고착되는 것을 **양단에서** 막는다 → [09 §9.2-3](09-vlm-timeseries.md).

- **송신측(선커밋)** — `PENDING → VLM_REQUESTED` 전이를 **제출 전에** 독립 커밋한다(`VlmMarkingTxService`, `REQUIRES_NEW`). 전이는 `PENDING` 에서만 발생한다(종결 상태 역행 금지).
- **수신측(조회 범위 확대)** — 콜백 처리(`VlmResultService`)가 전이 대상을 `VLM_REQUESTED` 단독이 아니라 **`PENDING` + `VLM_REQUESTED`**(= 위 활성 정의와 동일)로 조회한다. 종결 상태는 포함하지 않으므로 이미 `VLM_COMPLETED` 면 0건 = 멱등 no-op.
- **단, 위탁 발급 시각 이후에 새로 생긴 `PENDING` 마킹은 제외** — 앞선 위탁이 `VLM_FAILED` 로 끝난 뒤 작업자가 **재마킹**했는데 옛 request 의 지각 콜백이 도착하면, 한 번도 위탁된 적 없는 새 마킹이 `VLM_COMPLETED`(또는 `VLM_FAILED`)로 잘못 전이된다. 발급 시각을 알 수 없는 구 원장 행은 종전대로 전부 대상(고착 방지 우선).

## 6.5 관련 데이터 (DB)

`LS_MARKING` (V45) — `EVNT_NM`(이벤트명), `MARK_MODE_CD`(AUTO/MANUAL), `FRME_INTV_NOCS`(프레임 간격), `MARK_CN`(marks JSON), `STTS_CD`(상태), 부분 유니크 인덱스 `UK_LS_MARKING_RAW_ACTVTN`(V142 — 활성 마킹 1건). → [18](18-database.md).
