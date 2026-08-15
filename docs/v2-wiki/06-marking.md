# 06. 마킹

> 출처: CLAUDE.md(마킹 단계·마킹 화면), R2 KLID-AT-SS-002, 코드(`marking/`)
> 관련: [07 배치 파이프라인](07-batch-pipeline.md) · [09 VLM 시계열](09-vlm-timeseries.md)

화면: `KLID-AT-SC-006`(마킹 `/marking/:rawSn`). 코드: `marking/`(9 파일) — v2 신설 기능(v1 없음).

## 6.1 마킹이란

영상에서 **이벤트(관심 시점)를 자동/수동으로 표시**하는 단계. 마킹 완료가 잔여 배치를 기동하고, 마킹 결과는 외부 VLM 시계열 위탁의 **프레임 선택 근거**이자 FFmpeg 프레임 추출 위치의 기준이 된다.

- 마킹 결과 **저장** = 이벤트명(`EVNT_NM`) + 영상 경로(`VIDEO_FILE_PATH_NM`) + marks 배열(`MARK_CN` JSON)
  - ⚠ **이 셋을 VLM 에 전달하지 않는다** — 위탁 요청에는 `frame_policy` 만 싣는다(수동 → `frame_selected` + `selected_frames` / 자동·부재 → `frame_interval`). 구 서술 *"마킹 결과(이벤트명 + 영상 경로 + marks 배열)를 VLM 에 전달"* 은 **폐기** → [09](09-vlm-timeseries.md)
  - ⚠ **"콜백"은 방향이 반대다** — 마킹 → VLM 은 **위탁/제출**이고, VLM → 저작도구 방향만 콜백(`POST /v1/vlm/callback`)이다
- **이벤트명은 수동 입력하지 않는다** — 영상의 이벤트 유형(`LS_DATA_RAW.EVNT_TYPE_CD`)을 서버가 자동 소싱해 `LS_MARKING.EVNT_NM` 에 채운다(API-047 계약 변경). 이벤트 유형이 미지정(null/blank)인 영상은 마킹할 수 없다(`INVALID_INPUT` 400).
  - ⚠ **구 서술 폐기** — *"VLM 전달 페이로드 형식은 무변경(자동 소싱된 값을 `EVNT_NM` 으로 그대로 전달)"* 은 사실과 다르다. 자동 소싱된 값은 `LS_MARKING.EVNT_NM` 에 **저장될 뿐** verify 요청 바디에 실리지 않는다. VLM 위탁의 `event_type` 은 **다른 컬럼**(`LS_DATA_INGEST.VRFC_EVNT_TYPE_CD`)에서 조달하며 둘을 대체·유도하지 않는다
- **이벤트 유형은 적재 시점에 해석해 채운다** (2026-08-04 수정) — 관제 인입(`LS_DATA_INGEST`)은 이벤트 **식별자**(`EVNT_ID`, 예 `ABA_0001`)만 주므로, 적재 시 `EVNT_ID` 로 관제 공유 이벤트리스트(`MNG_CLIP_EVNT_LST`)를 조회해 `EVNT_TYPE_CD` 를 도출한다(`TrainingVideoIngestTx#resolveEvntTypeCd`). 기존 적재분은 `V164` 백필이 같은 규칙으로 채운다
  - **해석 실패는 적재 실패가 아니다** — 매칭이 없거나 **한 이벤트에 유형이 둘 이상**(`MNG_CLIP_EVNT_LST` 는 `(EVNT_ID, EVNT_TYPE_CD)` 복합 PK)이면 `null` 로 적재하고 **적재는 성공**시킨다. 임의 선택은 하지 않는다 — 관제 완료통지 계약 필드·목록 필터·통계 버킷·export 메타가 **틀린 값으로 확정**되어 null 보다 나쁘다. 결손은 프로세스 1회 WARN 으로 관측되고, 그 영상은 위의 마킹 가드가 400 으로 막는다
  - ⚠ **구 서술 폐기** — "적재 시 `EVNT_TYPE_CD` 는 **항상 null**(인입에 컬럼 없음)"(`TrainingVideoIngestTx` 구 javadoc·`docs/test-cases/B` TC-BATCH-020)은 폐기됐다. 그 구현은 이 절의 마킹 가드와 충돌해 **관제 인입 적재분의 자동마킹을 100% 400 으로 실패**시켰다. 인입에 유형코드 컬럼이 없는 것은 사실이지만, 금지 대상은 `EVNT_ID` 를 유형코드 자리에 **직접 대입**하는 것이지 조인 해석이 아니다
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
- **마크 칩 개별 삭제 버튼**(2026-08-08 신설) — 각 마크 칩이 `선택 버튼 + 삭제(×) 버튼` 두 개로 이뤄져, 특정 마크를 마우스만으로 지울 수 있다. 그 전에는 삭제 수단이 `Del` 단축키(선택된 마크 1건)뿐이라 마우스 사용자에게는 특정 마크를 지울 방법이 아예 없었다. **기존 `Del` 단축키는 그대로 유지**되며, 삭제 버튼의 접근성 이름에는 어느 마크인지 알 수 있도록 **타임라인과 같은 표기**를 붙인다
- **이벤트명 입력란 없음** — 영상의 이벤트 유형(`EVNT_TYPE_CD`)에서 자동 소싱(6.1)
- **'저장된 마킹 목록'(MarkingList) 없음** — 화면은 현재 작업 중 마크(타임라인/마크 칩)만 표시. 이를 백킹하던 마킹 관리 API(목록 조회 `GET /v1/videos/{rawSn}/markings`, 단건 조회 `GET .../{markingSn}`, 삭제 `DELETE .../{markingSn}`)는 FE 미사용으로 제거됨 — 마킹 API 는 생성(`POST /v1/videos/{rawSn}/markings`)만 보유
- **완료 버튼 활성 조건**: 수동=마크 1건 이상, 자동=intervalFrames 유효(1 이상)
- 비식별 누락 신고: 라벨링 단계(srcSn 기준)·**마킹 단계(rawSn 기준 `POST /v1/videos/{rawSn}/deident-report`) 모두 구현됨 — 2026-08-05 부터 마킹 화면에 신고 버튼이 있다**(그 전에는 API 만 있고 화면 진입점이 없어 마킹 중 발견해도 라벨링까지 진행해야 했다). 부수효과(작업락 + `DE_IDENT_YN='F'` + 검수완료 영상 통지 — 라벨·개인정보 3필드는 보존)는 두 경로가 동일하며 파생영상은 412 로 거부된다 → [08](08-deidentification.md#84-누락-신고-rq-sfr-09-03-uc-016)
  - **★마킹 단계 신고는 배치 단계가 `MARKING_READY` 일 때만 접수**된다(아니면 **412**, 문구 1종 — 배치 단계를 노출하지 않는다). 그 상태는 선두 비식별 성공 직후·마킹 이전이라 프레임 행도 라벨도 없어, 해소 후 **마킹부터 다시** 해도 파괴할 작업 결과가 없다.
  - **해소(resolve) 후 재개 지점이 신고 단계로 갈린다** — 마킹 단계 신고면 배치 단계를 `MARKING_READY` 로 되감고 활성 마킹(`PENDING`/`VLM_REQUESTED`)을 종결해 **재마킹 진입을 연다**(재마킹 409 해제). 라벨링 단계 신고면 마킹은 그대로 두고 **프레임 이미지만 재추출**한다.
  - FE 는 파생영상·비-`MARKING_READY` 영상에서 **버튼을 비활성화 + 사유 툴팁**을 띄운다 — 사유를 다 적고 제출한 뒤에야 412 를 보는 동선을 없앤다(라벨링 화면과 동일 관례).

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
- 종결 상태(`VLM_COMPLETED`/`VLM_FAILED`/`SKIPPED`)만 남은 영상의 **재마킹은 허용**된다(새 배치 사이클을 여는 정당한 동선).
- 동시 요청 방어는 서비스 사전 조회가 아니라 **DB 부분 유니크 인덱스** `UK_LS_MARKING_RAW_ACTVTN`(V142)가 최종 보증한다 — 위반은 409 로 변환된다.

### 6.4-1-a 배치 skip 시 마킹 종결 — `SKIPPED` (B-ISSUE-41, 2026-08-02)

- 마킹 저장(커밋)과 배치 트리거 판단(`MarkingBatchBridge`, AFTER_COMMIT)이 분리돼 있어, 브릿지가 **정당하게 skip**(검수 소유 작업 상태 / 이미 큐잉·처리중·완료 / 비식별 미완료 / 영상 미존재)해도 방금 커밋된 `PENDING` 마킹은 남았다.
- `PENDING → VLM_*` 전이는 **오직 VLM 단계**에서만 일어나고 그 단계는 배치가 돌아야 도달하므로, skip 된 마킹은 아무도 종결시키지 않는 **영구 고아**가 됐다. 활성 마킹 유일성 때문에 그 영상은 **다시는 마킹할 수 없었고**(재마킹 409), `POST /v1/videos/{rawSn}/batch/retry` 도 stage 가 `FAILED` 가 아니라 409 라 **복구 API 가 전무**했다.
- 이제 **모든 skip 분기가 그 마킹을 `SKIPPED`(종결)로 내린다**(`MarkingSkipTxService`, `REQUIRES_NEW`). 전이는 **`PENDING` 한정**이라 진행 중(`VLM_REQUESTED`)·종결 마킹을 덮지 않는다. 응답은 종전대로 201 + `batchTriggered=false` + `batchSkipReason` 이며, 영상은 재마킹으로 복구 가능하다.
- 기존 고착 행은 **V159** 가 1회 정리한다(작업 상태가 검수 소유 상태인 영상의 `PENDING` 마킹만 — 그 상태에서는 배치 진입 가드가 파이프라인을 확정 차단하므로 "곧 소비될 마킹"이 존재할 수 없다).

### 6.4-2 VLM 위탁 구간의 마킹 상태 전이 (2026-07-30)

VLM 제출이 논블로킹이 되면서 **콜백이 수락 응답(ACK)보다 먼저 도착**할 수 있게 됐다. 마킹 상태가 `VLM_REQUESTED` 에 영구 고착되는 것을 **양단에서** 막는다 → [09 §9.2-3](09-vlm-timeseries.md).

- **송신측(선커밋)** — `PENDING → VLM_REQUESTED` 전이를 **제출 전에** 독립 커밋한다(`VlmMarkingTxService`, `REQUIRES_NEW`). 전이는 `PENDING` 에서만 발생한다(종결 상태 역행 금지).
- **수신측(조회 범위 확대)** — 콜백 처리(`VlmResultService`)가 전이 대상을 `VLM_REQUESTED` 단독이 아니라 **`PENDING` + `VLM_REQUESTED`**(= 위 활성 정의와 동일)로 조회한다. 종결 상태는 포함하지 않으므로 이미 `VLM_COMPLETED` 면 0건 = 멱등 no-op.
- **단, 위탁 발급 시각 이후에 새로 생긴 `PENDING` 마킹은 제외** — 앞선 위탁이 `VLM_FAILED` 로 끝난 뒤 작업자가 **재마킹**했는데 옛 request 의 지각 콜백이 도착하면, 한 번도 위탁된 적 없는 새 마킹이 `VLM_COMPLETED`(또는 `VLM_FAILED`)로 잘못 전이된다. 발급 시각을 알 수 없는 구 원장 행은 종전대로 전부 대상(고착 방지 우선).

## 6.5 관련 데이터 (DB)

`LS_MARKING` (V45) — `EVNT_NM`(이벤트명), `MARK_MODE_CD`(AUTO/MANUAL), `FRME_INTV_NOCS`(프레임 간격), `MARK_CN`(marks JSON), `STTS_CD`(상태), 부분 유니크 인덱스 `UK_LS_MARKING_RAW_ACTVTN`(V142 — 활성 마킹 1건). → [18](18-database.md).
