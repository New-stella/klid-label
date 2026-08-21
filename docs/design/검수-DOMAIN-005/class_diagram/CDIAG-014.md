---
logicraft_item: CDIAG-014
type: class_diagram
version: 8
domain: DOMAIN-005
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-21T00:02:42.635Z
status: CHANGED
prev_version: 7
content_hash: d8bf8ede21006fbdde313b32a410468a5a6c928139a69df59b8b22f7bc215bc5
stale: false
raw: ./_raw/CDIAG-014.json
links:
  belongs_to_domain: ["[[DOMAIN-005]]"]
  references: ["[[FEAT-009]]"]
---

# VLM 시계열 메타 도메인 모델

## theme

neutral

## title

VLM 시계열 메타 도메인 모델

## classes

### MetaController

- **kind**: class

**methods**:

#### getMeta

**params**:

- srcSn: Long
- actor: TokenClaims

- **is_static**: false
- **visibility**: public
- **description**: GET /v1/frames/{srcSn}/meta
- **is_abstract**: false
- **return_type**: ApiResponse<MetaResponse>

#### updateMeta

**params**:

- srcSn: Long
- req: MetaUpdateRequest
- actor: TokenClaims

- **is_static**: false
- **visibility**: public
- **description**: PUT /v1/frames/{srcSn}/meta
- **is_abstract**: false
- **return_type**: ApiResponse<MetaResponse>

#### approveReview

**params**:

- metaReviewSn: Long
- actor: TokenClaims

- **is_static**: false
- **visibility**: public
- **description**: POST /v1/meta/{metaReviewSn}/approve (REVIEWER)
- **is_abstract**: false
- **return_type**: ApiResponse<Void>

#### rejectReview

**params**:

- metaReviewSn: Long
- req: MetaReviewRejectRequest
- actor: TokenClaims

- **is_static**: false
- **visibility**: public
- **description**: POST /v1/meta/{metaReviewSn}/reject (REVIEWER)
- **is_abstract**: false
- **return_type**: ApiResponse<Void>

**attributes**:

_(empty)_

- **description**: 프레임 시계열 메타 검토 REST API. GET/PUT /v1/frames/{srcSn}/meta, POST /v1/meta/{metaReviewSn}/approve|reject. 본인 배정 검증.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### MetaService

- **kind**: service

**methods**:

#### getByFrame

**params**:

- srcSn: Long
- actor: TokenClaims

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: MetaResponse

#### update

**params**:

- srcSn: Long
- req: MetaUpdateRequest
- actor: TokenClaims

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: MetaResponse

#### approveReview

**params**:

- metaReviewSn: Long
- actor: TokenClaims

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### rejectReview

**params**:

- metaReviewSn: Long
- reason: String
- actor: TokenClaims

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### verifyAccess

**params**:

- srcSn: Long
- actor: TokenClaims

- **is_static**: false
- **visibility**: private
- **description**: REVIEWER 통과 / WORKER 본인 LABELER 배정 검증
- **is_abstract**: false
- **return_type**: LsDataSrc

#### isReviewApproved

**params**:

- rawSn: Long

- **is_static**: false
- **visibility**: private
- **description**: [폐기] 이 클래스에서 만들지 않는다 — 검수 승인 판정은 ReviewApprovalGate 가 단일 지점으로 수행하며 MetaService 는 이를 주입해 위임한다(과거 여러 클래스에 복제됐던 private 판정이 그 단일 지점으로 통합됨).
- **is_abstract**: false
- **return_type**: boolean

**attributes**:

_(empty)_

- **description**: 외부 생성 시계열 메타의 검토·수정만 제공(V1.7). 메타 자동 생성 엔드포인트 없음. APPROVED 영상 수정 시 TaskModifiedEvent(META_UPDATED) 발행.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### VlmResultController

- **kind**: class

**methods**:

#### receive

**params**:

- request: VlmResultRequest

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: ResponseEntity<ApiResponse<Map<String,Object>>>

**attributes**:

_(empty)_

- **description**: 외부 VLM 시계열 메타 결과 수신 webhook. POST /v1/vlm/callback (WebhookProtectedPaths.PATH_VLM). ★인증은 무서명 규격이다(ADR-031) — 벤더 확정 계약(IntelliVIX Video VLM API v2.0.1)이 무서명이라 HMAC 을 요구하지 않으며 SIGNATURE_REQUIRED 목록은 비어 있다. HmacWebhookFilter 는 서명 검증이 아니라 무서명 가드(IP allowlist·rate limit·본문 크기 상한)로만 개입하고, 컨트롤러 진입 직전 WebhookGateInterceptor 가 통과 증거를 재확인한다(경로 인코딩 변형 우회 이중 차단). 최종 인증은 request_id 발급 게이트가 담당한다.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### VlmResultService

- **kind**: service

**methods**:

#### handle

**params**:

- req: VlmResultRequest

- **is_static**: false
- **visibility**: public
- **description**: true=신규 적재 / false=멱등 스킵
- **is_abstract**: false
- **return_type**: boolean

#### validateFilePath

**params**:

- filePath: String

- **is_static**: false
- **visibility**: private
- **is_abstract**: false
- **return_type**: void

**attributes**:

_(empty)_

- **description**: VLM 결과 인계 처리(Phase 2). 멱등 allowlist/replay 검증 → SSRF resultFilePath 검증 → LS_DATA_META upsert(metaKey) + LS_DATA_META_REVIEW(PENDING) 적재 → 마킹 VLM_COMPLETED 전이.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### VlmTimeseriesStep

- **kind**: class

**methods**:

#### stage

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: BatchStage

#### execute

**params**:

- ctx: BatchContext

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### run

**params**:

- rawSn: Long

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: VlmTimeseriesResponse

#### runWithMarking

**params**:

- rawSn: Long
- marking: LsMarking

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: VlmTimeseriesResponse

#### doSubmit

**params**:

- rawSn: Long
- marking: LsMarking

- **is_static**: false
- **visibility**: private
- **is_abstract**: false
- **return_type**: VlmTimeseriesResponse

#### persistResult

**params**:

- rawSn: Long
- resp: VlmTimeseriesResponse

- **is_static**: false
- **visibility**: private
- **description**: externalJobId/status를 LS_BATCH_PROC_LOG.RES_PAYLOAD_CN에 JSON 적재
- **is_abstract**: false
- **return_type**: void

**attributes**:

_(empty)_

- **description**: VLM 시계열 메타 외부 위탁 배치 단계(BatchStep, stage=VLM). 검수자가 수동 스킵한 묶음이면 실행하지 않고 통과한다(연동 비활성 토글은 폐지). 마킹 유무로 runWithMarking/run 분기.

**enum_values**:

_(empty)_

**stereotypes**:

- <<BatchStep>>

### VlmClient

- **kind**: class

**methods**:

#### submitTimeseries

**params**:

- request: VlmTimeseriesRequest

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: Mono<VlmTimeseriesResponse>

#### isEnabled

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: boolean

#### resolveIdempotencyKey

**params**:

- provided: String

- **is_static**: false
- **visibility**: private
- **is_abstract**: false
- **return_type**: String

#### validateResponse

**params**:

- resp: VlmTimeseriesResponse

- **is_static**: false
- **visibility**: private
- **is_abstract**: false
- **return_type**: VlmTimeseriesResponse

#### safeForLog

**params**:

- s: String

- **is_static**: true
- **visibility**: public
- **is_abstract**: false
- **return_type**: String

**attributes**:

_(empty)_

- **description**: 외부 VLM 시계열 분석 위탁 클라이언트(Outbound). 논블로킹 제출(제출만 개시하고 수락 여부는 완료 핸들러가 비동기로 기록), idempotencyKey 단일 발급, 연동 주소 미주입 시 실패(활성화 토글 폐지), Resilience4j Retry+CircuitBreaker, 응답 무결성 검증.

**enum_values**:

_(empty)_

**stereotypes**:

- <<api_client>>

### LsDataMeta

- **kind**: entity

**methods**:

#### create

**params**:

- rawSn: Long
- metaKey: String
- metaVl: String

- **is_static**: true
- **visibility**: public
- **is_abstract**: false
- **return_type**: LsDataMeta

#### updateValue

**params**:

- newVal: String

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### assignIdempotencyKey

**params**:

- idempotencyKey: String

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### assignExternalJobId

**params**:

- externalJobId: String

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### incrementRetryCount

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### markDeadLetter

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

**attributes**:

#### metaSn

- **type**: Long
- **is_static**: false
- **visibility**: private
- **is_readonly**: true

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

#### rawSn

- **type**: Long
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

#### metaKey

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

#### metaVl

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

#### regDt

- **type**: LocalDateTime
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

#### mdfcnDt

- **type**: LocalDateTime
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

#### idempotencyKey

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

#### externalJobId

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

#### retryCount

- **type**: int
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

#### deadLetterAt

- **type**: LocalDateTime
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

- **description**: LS_DATA_META — 영상/프레임 메타 K/V. (RAW_SN, META_KEY) UNIQUE. Phase4 비동기 표준 컬럼(IDMP_KEY/OTSD_JOB_ID/RTRY_NMTM/DEAD_LETTER_AT) 보유. batch 패키지 위치.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### LsDataMetaReview

- **kind**: entity

**methods**:

#### createAuto

**params**:

- dataMetaSn: Long
- dataRawSn: Long
- dataSrcSn: Long
- metaTypeCd: String
- srcSysCd: String
- rvwSttsCd: String

- **is_static**: true
- **visibility**: public
- **is_abstract**: false
- **return_type**: LsDataMetaReview

#### approve

**params**:

- reviewerId: String
- at: LocalDateTime

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### reject

**params**:

- reason: String
- reviewerId: String
- at: LocalDateTime

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### ensureReviewable

**params**:

_(empty)_

- **is_static**: false
- **visibility**: private
- **is_abstract**: false
- **return_type**: void

**attributes**:

#### dataMetaReviewSn

- **type**: Long
- **is_static**: false
- **visibility**: private
- **is_readonly**: true

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

#### dataMetaSn

- **type**: Long
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

#### dataRawSn

- **type**: Long
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

#### dataSrcSn

- **type**: Long
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

#### metaTypeCd

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

#### srcSysCd

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

#### rvwSttsCd

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

#### rvwId

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

#### rvwDt

- **type**: LocalDateTime
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

#### rejectRsn

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

#### regId

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

#### regDt

- **type**: LocalDateTime
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

#### mdfcnId

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

#### mdfcnDt

- **type**: LocalDateTime
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

- **description**: LS_DATA_META_REVIEW — 외부/자동 메타 검토 상태. META_TYPE_CD(VLM/EXTERNAL), SRC_SYS_CD(AI_SERVER/CONTROL_SERVER/PORTAL), RVW_STTS_CD(AUTO_GENERATED/PENDING/APPROVED/REJECTED). meta 패키지 위치.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### LsDataMetaRepository

- **kind**: repository

**methods**:

#### findByRawSn

**params**:

- rawSn: Long

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: List<LsDataMeta>

#### findByRawSnAndMetaKey

**params**:

- rawSn: Long
- metaKey: String

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: Optional<LsDataMeta>

**attributes**:

_(empty)_

- **description**: LS_DATA_META JPA 리포지토리(@ControlRepo).

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### LsDataMetaReviewRepository

- **kind**: repository

**methods**:

#### existsByDataMetaSn

**params**:

- dataMetaSn: Long

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: boolean

#### findAllByDataRawSnAndRvwSttsCd

**params**:

- dataRawSn: Long
- rvwSttsCd: String

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: List<LsDataMetaReview>

#### findAllByDataRawSn

**params**:

- dataRawSn: Long

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: List<LsDataMetaReview>

**attributes**:

_(empty)_

- **description**: LS_DATA_META_REVIEW JPA 리포지토리(@ControlRepo).

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### VlmResultRequest

- **kind**: class

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: VLM 결과 인계 페이로드(POST /v1/vlm/callback). idempotencyKey/externalJobId/status(SUCCESS|FAILED|PARTIAL)/rawSn/results/resultFilePath. results 는 배열이 아니라 단일 객체 {accuracy, description} 다 — 일치도와 서술 전문을 한 벌로 받는다. 구 배열 규격(마킹별 서술 항목 배열)은 폐기됐고, 벤더가 배열을 보내면 관대 파싱 없이 400 으로 거부한다.

**enum_values**:

_(empty)_

**stereotypes**:

- <<DTO>>

### MetaResponse

- **kind**: class

**methods**:

#### of

**params**:

- entities: List<LsDataMeta>

- **is_static**: true
- **visibility**: public
- **is_abstract**: false
- **return_type**: MetaResponse

**attributes**:

_(empty)_

- **description**: 메타 조회 응답 — items[]{metaSn, metaKey, metaVal}.

**enum_values**:

_(empty)_

**stereotypes**:

- <<DTO>>

### MetaUpdateRequest

- **kind**: class

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 메타 수정 요청 — items[]{metaKey, metaVal}, 최대 100건(DoS 방어). 값만 수정(키 추가/삭제 없음).

**enum_values**:

_(empty)_

**stereotypes**:

- <<DTO>>

### VlmTimeseriesRequest

- **kind**: class

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: VLM 위탁 요청 — request_id/event_type/media/callback_url. eventName·marks 는 벤더 규격 밖이라 본 요청 바디에 포함하지 않는다 — 마킹 정보는 frame_policy 로만 반영된다.

**enum_values**:

_(empty)_

**stereotypes**:

- <<DTO>>

### VlmTimeseriesResponse

- **kind**: class

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: VLM 위탁 응답(수락 Ack) — externalJobId/idempotencyKey/status. skipped() sentinel 제공.

**enum_values**:

_(empty)_

**stereotypes**:

- <<DTO>>

## description

UC-022(VLM 시계열 메타 검토·수정) 전용 클래스도. 외부 VLM 서비스에 시계열 메타 분석을 위탁(VlmTimeseriesStep→VlmClient)하고, 결과를 콜백(VlmResultController→VlmResultService)으로 수신해 LS_DATA_META(K/V) 적재 + LS_DATA_META_REVIEW 검수큐(PENDING) 진입시킨 뒤, REVIEWER 가 MetaController→MetaService 로 조회·수정·승인/반려한다. 메타 본체 생성은 외부 책임이고 저작도구는 시계열 정보 획득 연동 + 검수만 보유한다(FEAT-009).

[★위탁은 논블로킹 제출] VlmTimeseriesStep 은 .block(45s) 로 파이프라인 스레드를 붙잡지 않고 subscribe() 로 제출만 개시한다. 스텝이 확정적으로 말하는 사실은 '제출을 개시했다' 뿐이고, 수락(ACK)은 완료 핸들러가 원장·LS_BATCH_PROC_LOG 에 비동기 기록한다(명시적 디스패치 — publishOn 은 풀 포화 시 거부가 이벤트 루프로 흘러 JPA 쓰기를 거기서 실행시킨다).

[★미결 회수 — 임계가 두 개] 아무 신호도 없으면 in-memory subscription 은 노드가 죽을 때 통째로 사라져 실패 행조차 없다 — 미결 스위퍼가 유일한 회수 경로다. 임계는 ACK 창(stale-timeout, 기본 30분 — 원장 ISSUED)과 콜백 창(callback-timeout, 기본 360분 — 원장 ACCEPTED)으로 나뉘며 하나로 덮으면 정상 위탁을 빼앗는다. 2노드 중복 재위탁은 조건부 UPDATE 원자 클레임으로 막는다.

[신고 구간 보류] media.path 가 비식별본인데 신고는 바로 그 비식별본의 마스킹 실패를 뜻하므로, 신고 구간에는 위탁을 실패가 아니라 보류(SKIPPED + 사유 적재)한다. 보류는 스스로 재개되지 않으므로 해소 시 DeidentGateReopenedEvent(EVT-007) → VlmResumeBridge → VlmWithheldResumeRunner 가 그 SKIPPED 기록을 근거로 재위탁한다(멱등 조건: 시계열 메타 0건).

entity LS_DATA_META 는 batch 패키지, 검수 상태 LS_DATA_META_REVIEW 는 meta 패키지에 위치한다.

## module_name

meta

## relationships

### [1]

- **to**: MetaService
- **from**: MetaController
- **kind**: dependency
- **label**: 위임

### [2]

- **to**: LsDataMetaRepository
- **from**: MetaService
- **kind**: dependency

### [3]

- **to**: LsDataMetaReviewRepository
- **from**: MetaService
- **kind**: dependency

### [4]

- **to**: LsDataMeta
- **from**: MetaService
- **kind**: dependency
- **label**: 조회·수정

### [5]

- **to**: LsDataMetaReview
- **from**: MetaService
- **kind**: dependency
- **label**: approve/reject

### [6]

- **to**: VlmResultService
- **from**: VlmResultController
- **kind**: dependency
- **label**: 위임

### [7]

- **to**: LsDataMetaRepository
- **from**: VlmResultService
- **kind**: dependency
- **label**: upsert

### [8]

- **to**: LsDataMetaReviewRepository
- **from**: VlmResultService
- **kind**: dependency
- **label**: 검수큐 적재

### [9]

- **to**: LsDataMeta
- **from**: VlmResultService
- **kind**: dependency

### [10]

- **to**: LsDataMetaReview
- **from**: VlmResultService
- **kind**: dependency

### [11]

- **to**: VlmResultRequest
- **from**: VlmResultController
- **kind**: dependency

### [12]

- **to**: VlmClient
- **from**: VlmTimeseriesStep
- **kind**: dependency
- **label**: submitTimeseries

### [13]

- **to**: VlmTimeseriesRequest
- **from**: VlmTimeseriesStep
- **kind**: dependency

### [14]

- **to**: VlmTimeseriesRequest
- **from**: VlmClient
- **kind**: dependency

### [15]

- **to**: VlmTimeseriesResponse
- **from**: VlmClient
- **kind**: dependency

### [16]

- **to**: LsDataMeta
- **from**: LsDataMetaRepository
- **kind**: dependency

### [17]

- **to**: LsDataMetaReview
- **from**: LsDataMetaReviewRepository
- **kind**: dependency

### [18]

- **to**: LsDataMeta
- **from**: LsDataMetaReview
- **kind**: association
- **label**: DATA_META_SN 참조
- **to_multiplicity**: 1
- **from_multiplicity**: 0..*

### [19]

- **to**: MetaResponse
- **from**: MetaService
- **kind**: dependency

### [20]

- **to**: MetaUpdateRequest
- **from**: MetaController
- **kind**: dependency

## depicts_dfeats

_(empty)_

## referenced_items

_(empty)_

## realizes_features

- FEAT-009
