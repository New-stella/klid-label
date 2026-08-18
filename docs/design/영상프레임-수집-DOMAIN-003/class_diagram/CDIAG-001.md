---
logicraft_item: CDIAG-001
type: class_diagram
version: 6
domain: DOMAIN-003
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:51.133Z
status: NEW
prev_version: null
content_hash: fd5bd97929f0ed706ea27460c0e3bd513fb6a8fb34d1f68cd4aa65a1fb4bda95
stale: false
raw: ./_raw/CDIAG-001.json
links:
  belongs_to_domain: ["[[DOMAIN-003]]"]
  depicts: ["[[DFEAT-007]]", "[[DFEAT-008]]", "[[DFEAT-009]]", "[[DFEAT-010]]", "[[DFEAT-011]]"]
  references: ["[[DFEAT-008]]", "[[DFEAT-009]]"]
---

# 영상·프레임 수집 도메인 모델

## theme

neutral

## title

영상·프레임 수집 도메인 모델

## classes

### DataRaw

- **kind**: aggregate_root

**methods**:

#### deriverPrvcYn

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### changeStatus

**params**:

- newStatus: DataStatus

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### markDeidentifyFailed

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### isDeidentifyTarget

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: boolean

**attributes**:

#### rawSn

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

#### vmsClipId

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

#### vmsCctvId

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

#### evntTypeCd

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

#### lclgvCd

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

#### prvcTypeCd

- **type**: PrivacyType
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

#### prvcYn

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

#### deIdntfYn

- **type**: DeidentifyResult
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

#### rawFilePathNm

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

#### shtDt

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

#### durationSec

- **type**: Integer
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

#### orgnlRawSn

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

#### dataSttsCd

- **type**: DataStatus
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

#### anonyInclYn

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

implemented

##### modules

_(empty)_

##### records

_(empty)_

##### progress

100

##### subtasks

_(empty)_

#### psdoInclYn

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

implemented

##### modules

_(empty)_

##### records

_(empty)_

##### progress

100

##### subtasks

_(empty)_

#### prvcInclYn

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

implemented

##### modules

_(empty)_

##### records

_(empty)_

##### progress

100

##### subtasks

_(empty)_

- **description**: 라벨링 대상 원시 영상(영상 1건=작업 단위 RAW_SN). VMS_CLIP_ID 유니크로 동일 클립 재수신 시 멱등 처리. ★ORGNL_RAW_SN(V82 개명, 구 PARENT_RAW_SN)으로 파생영상이 원본을 자기참조하며 파생 깊이는 1 로 고정된다. 개인정보 3필드(ANONY/PSDO/PRVC_INCL_YN, V163)는 영상 축 수동 판정이며 프레임 축(LS_DATA_SRC)과 입도가 다른 별개 사실이다. 영상 관련 수신값(CCTV명·지자체명·이벤트명·파일포맷)은 이 테이블에 복사하지 않고 LS_DATA_INGEST 조인으로 얻는다. (LS_DATA_RAW)

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### DataSrc

- **kind**: entity

**methods**:

#### attachDeidentifiedPath

**params**:

- deIdntfSrcFilePathNm: String

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

**attributes**:

#### srcSn

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

#### frameNo

- **type**: Integer
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

#### srcFilePathNm

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

#### deIdntfSrcFilePathNm

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

#### shtDt

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

#### updDt

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

- **description**: 영상에서 추출한 키프레임(FFmpeg 추출 산출). 원본 경로와 비식별 프레임 경로를 같은 row에 보유. (RAW_SN,FRAME_NO) 유니크. (LS_DATA_SRC)

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### DataSrcHstry

- **kind**: entity

**methods**:

_(empty)_

**attributes**:

#### hstrySeq

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

#### srcSn

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

#### chgTypeCd

- **type**: SrcChangeType
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

#### chgUserNo

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

#### chgDt

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

- **description**: 프레임 변경 이력. 생성(CREATED)·비식별경로 연결(DEID_ATTACHED) 사유 기록. (LS_DATA_SRC_HSTRY)

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### BatchProcLog

- **kind**: entity

**methods**:

#### complete

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### fail

**params**:

- errorCd: String
- errorMsg: String

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### incrementRetry

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

**attributes**:

#### batchProcLogSn

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

#### jobId

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

#### procStepCd

- **type**: BatchStage
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

#### procSttsCd

- **type**: ProcStatus
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

#### startDt

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

#### endDt

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

#### rtryCnt

- **type**: Integer
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

#### errorCd

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

#### errorMsg

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

#### reqPayloadCn

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

#### resPayloadCn

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

- **description**: 배치 파이프라인 단계별 처리 이력. 영상/프레임 단위로 단계·상태·재시도·오류·요청/응답 페이로드 적재. (LS_BATCH_PROC_LOG)

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### AuthWorkLock

- **kind**: entity

**methods**:

#### release

**params**:

- releaseRsn: String

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### isExpired

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: boolean

**attributes**:

#### workLockSn

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

#### lockTargetCd

- **type**: LockTarget
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

#### lockSttsCd

- **type**: LockStatus
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

#### lockId

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

#### lockOwnerId

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

#### lockDt

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

#### expireDt

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

#### releaseDt

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

#### releaseRsn

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

- **description**: 저작도구 작업 잠금(재비식별 등). LOCK_ID 유니크, 기본 6시간 만료. (LS_AUTH_WORK_LOCK)

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### PrivacyType

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 개인정보 유형. ★게이팅이 폐지돼 이 값과 무관하게 전체 영상이 비식별 대상이다(구 'PRVC/PSDO만 비식별 대상' 서술 폐기). export 개인정보 3필드 파생에서도 소멸해 현재는 비식별 대상 판정(needsDeidentify)에만 남아 있다. (PRVC_TYPE_CD)

**enum_values**:

- PRVC
- PSDO
- ANONY

**stereotypes**:

_(empty)_

### DeidentifyResult

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 비식별 처리 결과. (DE_IDNTF_YN)

**enum_values**:

- Y
- N
- F

**stereotypes**:

_(empty)_

### DataStatus

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 배치 단계 상태(LS_DATA_RAW.DATA_STTS_CD, DEFAULT PENDING). 적재 PENDING → 선두 비식별 성공 MARKING_READY → 배치 완료 COMPLETED. ⚠ 작업·검수 워크플로우 상태는 LS_RAW_DATA_STATUS 가 별도로 소유하며 두 축을 혼동하면 안 된다.

**enum_values**:

- PENDING
- MARKING_READY
- BATCH_QUEUED
- PROCESSING
- COMPLETED
- FAILED

**stereotypes**:

_(empty)_

### RawChangeType

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 영상 변경 사유. (CHG_TYPE_CD — 확정 code_values는 INGEST_NEW/INGEST_UPD, STATUS_CHANGE는 설명문 기반)

**enum_values**:

- INGEST_NEW
- INGEST_UPD
- STATUS_CHANGE

**stereotypes**:

_(empty)_

### SrcChangeType

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 프레임 변경 사유. (CHG_TYPE_CD)

**enum_values**:

- CREATED
- DEID_ATTACHED

**stereotypes**:

_(empty)_

### BatchStage

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 파이프라인 처리 단계 코드(PROC_STEP_CD). ★순서 정정: DEIDENTIFY 가 pre-marking 선두 단계이고 나머지가 post-marking 이다(구 순서 '마킹→VLM→비식별' 폐기). 단계 구성은 BatchPipelineConfig 의 List.of(...) 한 곳에서 재배치한다.

**enum_values**:

- DEIDENTIFY
- MARKING
- VLM
- FRAME
- YOLO
- SAM2
- INTERPOLATE
- COMPLETED
- FAILED

**stereotypes**:

_(empty)_

### ProcStatus

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 단계 처리 상태. (PROC_STTS_CD)

**enum_values**:

- STARTED
- COMPLETED
- FAILED

**stereotypes**:

_(empty)_

### LockTarget

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 잠금 대상 유형. (LOCK_TARGET_CD)

**enum_values**:

- RAW

**stereotypes**:

_(empty)_

### LockStatus

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 잠금 상태. (LOCK_STTS_CD)

**enum_values**:

- LOCKED
- RELEASED

**stereotypes**:

_(empty)_

### DataIngest

- **kind**: entity

**methods**:

_(empty)_

**attributes**:

#### rawSn

- **type**: Long
- **is_static**: false
- **visibility**: private
- **is_readonly**: true

**implementation**:

##### status

implemented

##### modules

_(empty)_

##### records

_(empty)_

##### progress

100

##### subtasks

_(empty)_

#### cctvNm

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

implemented

##### modules

_(empty)_

##### records

_(empty)_

##### progress

100

##### subtasks

_(empty)_

#### lclgvNm

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

implemented

##### modules

_(empty)_

##### records

_(empty)_

##### progress

100

##### subtasks

_(empty)_

#### evntNm

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

implemented

##### modules

_(empty)_

##### records

_(empty)_

##### progress

100

##### subtasks

_(empty)_

#### evntTypeCd

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

implemented

##### modules

_(empty)_

##### records

_(empty)_

##### progress

100

##### subtasks

_(empty)_

#### fileFmt

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

implemented

##### modules

_(empty)_

##### records

_(empty)_

##### progress

100

##### subtasks

_(empty)_

#### anonyInclYn

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

implemented

##### modules

_(empty)_

##### records

_(empty)_

##### progress

100

##### subtasks

_(empty)_

#### psdoInclYn

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

implemented

##### modules

_(empty)_

##### records

_(empty)_

##### progress

100

##### subtasks

_(empty)_

#### prvcInclYn

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

implemented

##### modules

_(empty)_

##### records

_(empty)_

##### progress

100

##### subtasks

_(empty)_

- **description**: ★관제가 직접 INSERT 하는 평면 수신 테이블(ADR-042). 저작도구 폴링 배치가 미처리 행을 원자 클레임해 LS_DATA_RAW 로 적재하며, 이름값(CCTV명·지자체명·이벤트명·파일포맷·좌표)은 조회 시점에 이 테이블을 LEFT JOIN 해 얻는다(RAW 로 복사하지 않는다). 행을 삭제하는 코드가 없어 영구 보존된다. 파생영상은 자기 인입 행이 없어 COALESCE(ORGNL_RAW_SN, RAW_SN) 1단계 폴백으로 부모 행을 참조한다. 전체 컬럼은 ERD-012 참조(여기엔 대표 컬럼만 등재). (LS_DATA_INGEST)

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

## description

관제 인입(LS_DATA_INGEST 평면 수신)부터 영상 적재(RAW_SN), FFmpeg 프레임 추출(SRC_SN), 영상/프레임 변경이력, 파생영상 자기참조(ORGNL_RAW_SN), Quartz 배치 단계별 처리이력·작업잠금까지를 한 다이어그램에 표현한 도메인 클래스 모델이다. ★ADR-042 이후 관제 공유 마스터(MNG_*) 참조는 전면 제거되고 영상 관련 정보는 전부 인입 테이블에서 평면으로 받는다.

## module_name

VideoFrameCollection

## relationships

### [1]

- **to**: DataSrc
- **from**: DataRaw
- **kind**: composition
- **label**: extracts frames
- **to_multiplicity**: 0..*
- **from_multiplicity**: 1

### [2]

- **to**: DataRaw
- **from**: DataRaw
- **kind**: association
- **label**: derived from (orgnlRawSn) — 깊이 1 고정
- **to_multiplicity**: 0..1
- **from_multiplicity**: 0..*

### [3]

- **to**: DataSrc
- **from**: DataSrcHstry
- **kind**: association
- **label**: records history of
- **to_multiplicity**: 1
- **from_multiplicity**: 0..*

### [4]

- **to**: DataRaw
- **from**: BatchProcLog
- **kind**: association
- **label**: processes (dataRawSn)
- **to_multiplicity**: 0..1
- **from_multiplicity**: 0..*

### [5]

- **to**: DataRaw
- **from**: AuthWorkLock
- **kind**: association
- **label**: locks (dataRawSn)
- **to_multiplicity**: 0..1
- **from_multiplicity**: 0..*

### [6]

- **to**: PrivacyType
- **from**: DataRaw
- **kind**: association
- **label**: typed by
- **to_multiplicity**: 1
- **from_multiplicity**: 0..*

### [7]

- **to**: DataStatus
- **from**: DataRaw
- **kind**: association
- **label**: status
- **to_multiplicity**: 1
- **from_multiplicity**: 0..*

### [8]

- **to**: DataRaw
- **from**: DataIngest
- **kind**: association
- **label**: ingested into (rawSn)
- **to_multiplicity**: 1
- **from_multiplicity**: 0..1

## depicts_dfeats

- DFEAT-008
- DFEAT-009
- DFEAT-010
- DFEAT-011
- DFEAT-007

## referenced_items

_(empty)_

## realizes_features

- DFEAT-008
- DFEAT-009
