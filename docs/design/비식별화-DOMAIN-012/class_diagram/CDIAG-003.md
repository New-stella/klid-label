---
logicraft_item: CDIAG-003
type: class_diagram
version: 11
domain: DOMAIN-012
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:55.719Z
status: NEW
prev_version: null
content_hash: f7aa6ae9317ac24a7073c81f4e27deb7409e3d70c721233c7d1fe35b18890236
stale: true
raw: ./_raw/CDIAG-003.json
links:
  belongs_to_domain: ["[[DOMAIN-012]]"]
  depicts: ["[[DFEAT-041]]", "[[DFEAT-042]]", "[[DFEAT-048]]"]
  references: ["[[DFEAT-041]]", "[[DFEAT-042]]"]
---

# 비식별화 도메인 모델

## theme

neutral

## title

비식별화 도메인 모델

## classes

### DeidentProcLog

- **kind**: aggregate_root

**methods**:

#### succeed

**params**:

- deIdntfFilePathNm: String

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

#### attachExternalJobId

**params**:

- externalJobId: String

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### markKpstSubmitPending

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### markKpstSubmitted

**params**:

- kpstPrjId: Long
- kpstDatasetId: Long

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### markPolling

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### markDownloaded

**params**:

- deIdntfFilePathNm: String

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### markRedeident

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

**attributes**:

#### procLogSn

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

#### reqId

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

#### orgnlFilePathNm

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

#### deIdntfFilePathNm

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

#### procSttsCd

- **type**: DeidentProcStatus
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

#### reqDt

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

#### rspnsDt

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

#### errCd

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

#### errMsgCn

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

#### deIdntfPjtId

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

#### deIdntfDatstId

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

#### pollSttsCd

- **type**: DeidentPollStatus
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

#### pollLastDt

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

#### pollAtmptCnt

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

#### reqKndCd

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

#### faceDtctCnt

- **type**: Integer
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

#### noPltDtctCnt

- **type**: Integer
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

#### frmeCnt

- **type**: Integer
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

#### prcsBgngDt

- **type**: LocalDateTime
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

#### prcsEndDt

- **type**: LocalDateTime
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

#### rptFilePathNm

- **type**: String
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

- **description**: DeidentifyStep 이 영상 단위로 기록하는 외부 KPST 비식별 솔루션 위탁·폴링 이력. OTSD_JOB_ID 유니크로 동일 작업 재인계 시 단일 row upsert(race condition 차단). 제출은 논블로킹이라 POLL_STTS_CD='WAITING' 행을 먼저 선커밋한 뒤 프로젝트 생성을 비동기 디스패치하고, 결과는 별도 콜백 없이 주기 폴링이 회수한다. 결과 파일명은 솔루션이 정하므로 DE_IDNTF_FILE_PATH_NM 에 통보받은 값을 그대로 기록하고 조합·추측하지 않는다. (LS_DEIDENT_PROC_LOG)

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### DeidentReport

- **kind**: aggregate_root

**methods**:

#### resolve

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### dismiss

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

**attributes**:

#### deidentReportSn

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

#### reporterNo

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

#### rsn

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

#### reportSttsCd

- **type**: ReportStatus
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

#### dclrDt

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

#### resolvedDt

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

#### dclrStpCd

- **type**: DeidentReportStage
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

- **description**: 라벨러(작업자)가 비식별 누락을 신고하는 전용 엔티티. 신고자·사유·신고 상태·신고/해소 일시에 더해 신고 단계(DCLR_STP_CD, V171)를 보유한다 — 해소 후 재개 지점이 그 단계로 갈린다. 신고는 라벨도 개인정보 판정도 지우지 않고 보존하며, 신고 구간 동안 조회·저장을 412 로 차단했다가 resolve 로 게이트가 자동 해제되면 기존 라벨을 그대로 재사용한다. (LS_DEIDENT_REPORT)

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### DeidentProcStatus

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 비식별 처리 상태. (PROC_STTS_CD)

**enum_values**:

- REQUESTED
- SUCCEEDED
- FAILED

**stereotypes**:

_(empty)_

### ReportStatus

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 비식별 누락 신고 처리 상태. (REPORT_STTS_CD)

**enum_values**:

- OPEN
- RESOLVED
- DISMISSED

**stereotypes**:

_(empty)_

### AuthWorkLock

- **kind**: entity

**methods**:

#### lockRawForRedeident

**params**:

- rawSn: Long
- ownerId: String

- **is_static**: true
- **visibility**: public
- **is_abstract**: false
- **return_type**: AuthWorkLock

#### release

**params**:

- actorId: String
- reason: String

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

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

#### lockSttsCd

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

- **description**: 비식별 누락 신고 시 WorkLockService가 영상 단위(DATA_RAW_SN)로 선점하는 작업락. UUID lockId·6시간 만료, 수동 비식별 후 resolve 시 해제(LOCKED→RELEASED). (LS_AUTH_WORK_LOCK)

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### DeidentPollStatus

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: KPST 위탁 후 다운로드까지의 폴링 진행 단계. WAITING 은 논블로킹 제출이 먼저 선커밋하는 값이고, FAILED 는 타임아웃·불완전 산출물로 종료 처리된 건의 종료값이라 재폴링·중복 다운로드를 차단한다. null 은 폴링 미사용 경로다. (POLL_STTS_CD)

**enum_values**:

- WAITING
- POLLING
- DOWNLOADED
- FAILED

**stereotypes**:

_(empty)_

### DeidentReportStage

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 비식별 누락 신고가 접수된 단계(V171). MARKING 은 마킹 화면 접수로 DATA_STTS_CD='MARKING_READY' 일 때만 받으며 해소 후 마킹부터 다시 진행한다. LABELING 은 라벨링 화면 접수로 배치 단계와 무관하며 해소 후 프레임 이미지만 재추출하고 라벨 좌표를 보존한다. NULL(컬럼 신설 이전 신고)은 백필하지 않고 단계별 재개 이벤트도 발행하지 않는다. (DCLR_STP_CD)

**enum_values**:

- MARKING
- LABELING

**stereotypes**:

_(empty)_

## description

영상 단위(DATA_RAW_SN)로 외부 KPST 비식별 솔루션 위탁·폴링·회수 이력을 기록하는 LS_DEIDENT_PROC_LOG 와 라벨러의 비식별 누락 신고를 처리하는 LS_DEIDENT_REPORT 를 표현한 비식별 처리 도메인 클래스 모델이다. 비식별은 게이팅 없이 전체 영상을 대상으로 하며 파이프라인 선두 단계로 적재 직후 자동 시작한다. ★ 시나리오 변경: 비식별 검증·결과 검토는 외부 비식별 솔루션 제공 프로그램으로 이관됨(FEAT-006 폐기). 저작도구는 위탁·결과 저장(DeidentProcLog, DFEAT-041)·라벨링 중 비식별 누락 신고(DeidentReport, DFEAT-048)만 보유한다. ⚠ 구 서술 '외부가 콜백으로 결과를 회신한다' 는 폐기됐다 — 제출은 논블로킹(WAITING 원장 행 선커밋 후 비동기 디스패치)이고 결과는 주기 폴링(KpstDeidentPollJob)이 회수한다.

## module_name

Deidentification

## relationships

### [1]

- **to**: DeidentProcStatus
- **from**: DeidentProcLog
- **kind**: association
- **label**: status
- **to_multiplicity**: 1
- **from_multiplicity**: 0..*

### [2]

- **to**: ReportStatus
- **from**: DeidentReport
- **kind**: association
- **label**: status
- **to_multiplicity**: 1
- **from_multiplicity**: 0..*

### [3]

- **to**: AuthWorkLock
- **from**: DeidentReport
- **kind**: dependency
- **label**: 신고 시 작업락 선점·해소 시 해제(동일 DATA_RAW_SN)
- **to_multiplicity**: 0..*
- **from_multiplicity**: 1

### [4]

- **to**: DeidentPollStatus
- **from**: DeidentProcLog
- **kind**: association
- **label**: poll status
- **to_multiplicity**: 0..1
- **from_multiplicity**: 0..*

### [5]

- **to**: DeidentReportStage
- **from**: DeidentReport
- **kind**: association
- **label**: stage
- **to_multiplicity**: 0..1
- **from_multiplicity**: 0..*

## depicts_dfeats

- DFEAT-041
- DFEAT-048
- DFEAT-042

## referenced_items

_(empty)_

## realizes_features

- DFEAT-041
- DFEAT-042
