---
logicraft_item: CDIAG-014
type: class_diagram
version: 17
domain: DOMAIN-005
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-08T00:22:49.392Z
status: CHANGED
prev_version: 16
content_hash: 4e77621249ad8311fe9b8ddc8896f25fe862ca240cc51b6d46a54e6637064ea1
stale: false
raw: ./_raw/CDIAG-014.json
links:
  belongs_to_domain: ["[[DOMAIN-005]]"]
  implements: ["[[IMPREC-114]]"]
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

- **description**: VLM 결과 인계 처리. 멱등 allowlist/replay 검증 → SSRF resultFilePath 검증 → LS_DATA_META upsert(metaKey) + LS_DATA_META_REVIEW(PENDING) 적재 → 마킹 VLM_COMPLETED 전이. 콜백 바디에 창구 구분자가 없어 위탁 시 등록한 채널로 축을 되짚은 뒤, 어노테이션 초안 반영은 TimeseriesResultApplier 로 넘기고 채우는 규칙 자체는 갖지 않는다. 초안 반영이 실패해도 시계열 서술 전문의 적재를 롤백시키지 않는다.

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

- **description**: 외부 VLM 시계열 분석 위탁 클라이언트(Outbound). 논블로킹 제출(제출만 개시하고 수락 여부는 완료 핸들러가 비동기로 기록), idempotencyKey 단일 발급, 위탁 목적지는 장비 원장에서 고른 시계열 유형 장비의 주소이고 그 유형에 쓸 수 있는 후보가 하나도 없으면 사유를 가리지 않고 거부(설정값은 그 유형이 비어 있을 때 최초 1회 씨앗이며 대신 호출하는 폴백은 두지 않는다. 활성화 토글 폐지), Resilience4j Retry+CircuitBreaker, 응답 무결성 검증.

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

##### module_paths

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

##### module_paths

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

##### module_paths

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

##### module_paths

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

##### module_paths

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

##### module_paths

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

##### module_paths

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

##### module_paths

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

##### module_paths

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

##### module_paths

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

##### module_paths

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

##### module_paths

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

##### module_paths

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

##### module_paths

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

##### module_paths

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

##### module_paths

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

##### module_paths

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

##### module_paths

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

##### module_paths

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

##### module_paths

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

##### module_paths

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

##### module_paths

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

##### module_paths

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

##### module_paths

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

- **description**: VLM 결과 인계 페이로드(POST /v1/vlm/callback). idempotencyKey/externalJobId/status(SUCCESS|FAILED|PARTIAL)/rawSn/results/resultFilePath. results 는 배열이 아니라 단일 객체 {description} 다 — 서술 전문만 받는다(일치도·판정은 검증 전용이라 이 연동으로 오지 않는다). 구 배열 규격(마킹별 서술 항목 배열)은 폐기됐고, 벤더가 배열을 보내면 관대 파싱 없이 400 으로 거부한다. 콜백은 묘사(describe)·추가 질문(custom) 두 축을 공용 수신하며, 콜백 바디에 API 종류 식별자가 없어 요청 식별자(request_id)로 축을 역조회한다.

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

### TimeseriesResultApplier

- **kind**: interface

**methods**:

#### applyDescription

**params**:

- rawSn: Long
- description: String

- **is_static**: false
- **visibility**: public
- **description**: 묘사(describe) 전문을 초안으로 반영한다. 전문 가운데 「상황」 항목만 뽑아 캡션 후보의 사고 단계 1단계 칸에 넣는다. 그 파싱은 이 포트를 구현하는 어노테이션 도메인이 수행하며 콜백 수신부에 두지 않는다. 항목을 찾지 못하면 지어내지 않고 건너뛴다. 반환값은 실제로 초안이 채워졌는지 여부이며, 채우지 못해도 시계열 서술 전문의 적재는 롤백되지 않는다.
- **is_abstract**: true
- **return_type**: boolean

#### applySubDescription

**params**:

- rawSn: Long
- description: String

- **is_static**: false
- **visibility**: public
- **description**: 추가 질문(custom) 결과를 초안으로 반영한다. 서술은 같은 캡션 후보의 캡션 본문 칸에 넣는다. 답변 칸은 자동으로 채우지 않는다. 질문 문장 칸은 이 계약이 채우지 않는다 — 그 값의 조달원은 저작도구가 보관하는 검증 이벤트 유형별 질문 문구 목록이며, 어노테이션 도메인이 마킹에서 고른 질문(없으면 그 유형의 첫 번째)을 읽어 채운다. 메서드 이름의 추가 질문 표기는 어느 창구의 결과인지를 가리키는 것이라 그대로 둔다.
- **is_abstract**: true
- **return_type**: boolean

**attributes**:

_(empty)_

- **description**: 콜백 수신부가 외부 시계열 분석 결과를 이벤트 어노테이션 도메인으로 넘기는 협력자 포트. 창구가 둘이라 축 중립 이름을 쓴다 — 추가 질문 전용을 함의하는 이름은 묘사 축까지 담게 되는 순간 이름과 책임이 어긋난다. 구현이 지켜야 하는 계약: 사람이 이미 고친 값과 승인으로 동결된 값은 덮지 않는다 · 자동 채움 자체는 재검수를 발화시키지 않는다 · 같은 결과를 여러 번 받아도 결과가 같다(콜백은 중복 수신될 수 있다) · 두 창구가 공유하는 캡션 후보는 통째로 교체하지 않고 자기 칸만 채운다.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### EvntAnnoResultApplier

- **kind**: service

**methods**:

#### applyDescription

**params**:

- rawSn: Long
- description: String

- **is_static**: false
- **visibility**: public
- **description**: 묘사 전문에서 「상황」 항목을 뽑아 캡션 후보의 사고 단계 1단계 칸을 채운다. 1단계 키만 만들고 그 뒤는 사람이 채울 공란으로 둔다.
- **is_abstract**: false
- **return_type**: boolean

#### applySubDescription

**params**:

- rawSn: Long
- description: String

- **is_static**: false
- **visibility**: public
- **description**: 추가 질문 서술로 같은 캡션 후보의 캡션 본문 칸을 채운다. 답변 칸은 건드리지 않는다.
- **is_abstract**: false
- **return_type**: boolean

**attributes**:

_(empty)_

- **description**: 이벤트 어노테이션 도메인이 소유하는 협력자 구현. 어노테이션의 구조·동결·재검수 시맨틱이 이 도메인에 있으므로 채우는 규칙도 여기 둔다. 승인 이력이 있는 영상은 손대지 않고(이미 산출물로 나간 회차와 어긋난다), 이미 값이 있는 칸은 사람이 쓴 것으로 보아 덮지 않으며, 어노테이션 검토가 승인·반려로 종결된 뒤에는 채우지 않는다. 본문을 읽지 못하면 예외를 던지지 않고 건너뛴다 — 여기서 예외가 나면 콜백 처리 전체가 롤백돼 시계열 서술까지 잃는다.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

## description

UC-022(VLM 시계열 메타 검토·수정) 전용 클래스도. 외부 VLM 서비스에 시계열 메타 분석을 위탁(VlmTimeseriesStep→VlmClient)하고, 결과를 콜백(VlmResultController→VlmResultService)으로 수신해 LS_DATA_META(K/V) 적재 + LS_DATA_META_REVIEW 검수큐(PENDING) 진입시킨 뒤, REVIEWER 가 MetaController→MetaService 로 조회·수정·승인/반려한다. 메타 본체 생성은 외부 책임이고 저작도구는 시계열 정보 획득 연동 + 검수만 보유한다(FEAT-009).

[★위탁은 논블로킹 제출] VlmTimeseriesStep 은 .block(45s) 로 파이프라인 스레드를 붙잡지 않고 subscribe() 로 제출만 개시한다. 스텝이 확정적으로 말하는 사실은 '제출을 개시했다' 뿐이고, 수락(ACK)은 완료 핸들러가 원장·LS_BATCH_PROC_LOG 에 비동기 기록한다(명시적 디스패치 — publishOn 은 풀 포화 시 거부가 이벤트 루프로 흘러 JPA 쓰기를 거기서 실행시킨다).

[★미결 회수 — 임계가 두 개] 아무 신호도 없으면 in-memory subscription 은 노드가 죽을 때 통째로 사라져 실패 행조차 없다 — 미결 스위퍼가 유일한 회수 경로다. 임계는 ACK 창(stale-timeout, 기본 30분 — 원장 ISSUED)과 콜백 창(callback-timeout, 기본 360분 — 원장 ACCEPTED)으로 나뉘며 하나로 덮으면 정상 위탁을 빼앗는다. 다중 노드 중복 재위탁은 조건부 UPDATE 원자 클레임으로 막는다.

[신고 구간 보류] media.path 가 비식별본인데 신고는 바로 그 비식별본의 마스킹 실패를 뜻하므로, 신고 구간에는 위탁을 실패가 아니라 보류(SKIPPED + 사유 적재)한다. 보류는 스스로 재개되지 않으므로 해소 시 DeidentGateReopenedEvent(EVT-007) → VlmResumeBridge → VlmWithheldResumeRunner 가 그 SKIPPED 기록을 근거로 재위탁한다(멱등 조건: 시계열 메타 0건).

entity LS_DATA_META 는 batch 패키지, 검수 상태 LS_DATA_META_REVIEW 는 meta 패키지에 위치한다.

[★어노테이션 초안 조달 — 창구별로 들어가는 자리가 다르다] 외부 시계열 분석 두 창구의 결과는 이벤트 어노테이션의 서로 다른 칸으로 간다. 묘사(describe) 전문은 영상 서술 칸(video.vd_description)으로 그대로 가고 이는 기존과 같다. 그 전문 가운데 「상황」 항목만 뽑은 값이 캡션 후보 c1 의 사고 단계 첫 칸(event.caption.c1.cot 의 1단계 키)에 초안으로 들어간다. 추가 질문(custom) 결과는 같은 후보의 캡션 본문 칸(event.caption.c1.caption_text)으로 간다. 질문 문장 칸(event.question)은 이 결과가 아니라 위탁 시점에 조달해 요청 본문에 실어 보낸 그 문구를 원장 보관값에서 읽어 채운다 — 콜백 시점에 재조달하지 않는다. 답변 칸은 자동으로 채우지 않고, 사고 단계는 1단계 키만 만들며 그 뒤는 사람이 채울 공란으로 둔다. 두 창구는 같은 캡션 후보를 공유하는데 도착 순서가 보장되지 않으므로, 후보를 통째로 교체하지 않고 자기 칸만 채워 먼저 도착한 축의 값을 지우지 않는다.

[책임 경계] 콜백 수신부는 어느 창구의 결과인지 되짚어 협력자에게 넘기는 일까지만 한다. 어노테이션을 어떻게 채우는지 — 「상황」 항목을 뽑는 파싱, 기존 값과의 병합, 사람이 고친 값과 동결된 값을 덮지 않는 보호 경계 — 는 어노테이션 도메인이 소유한다. 그 규칙을 수신부에 복제하면 한쪽만 갱신돼 조용히 어긋난다. 초안 채움이 실패해도 시계열 서술 전문의 적재를 롤백시키지 않는다 — 초안 하나 때문에 주 축을 잃는 것이 더 나쁘다.

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

### [21]

- **to**: TimeseriesResultApplier
- **from**: VlmResultService
- **kind**: dependency
- **label**: 초안 반영 위임

### [22]

- **to**: TimeseriesResultApplier
- **from**: EvntAnnoResultApplier
- **kind**: realization

## attached_files

_(empty)_

## depicts_dfeats

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-114

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-25T09:58:41.903Z

### module_paths

_(empty)_

## referenced_items

_(empty)_

## realizes_features

- FEAT-009
