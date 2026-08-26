---
logicraft_item: CDIAG-010
type: class_diagram
version: 11
domain: DOMAIN-007
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-26T05:49:10.140Z
status: CHANGED
prev_version: 9
content_hash: b3cab474a46957487b0bc24ed7f6ad4a5bb29b2dbff2e49d0351e66acb043bcf
stale: true
raw: ./_raw/CDIAG-010.json
links:
  belongs_to_domain: ["[[DOMAIN-007]]"]
  depicts: ["[[DFEAT-029]]", "[[DFEAT-030]]"]
  references: ["[[ADR-018]]", "[[DFEAT-029]]", "[[DFEAT-030]]"]
  references_backward: ["[[ADR-018]]"]
---

# 데이터 증강 도메인 모델

## theme

neutral

## title

데이터 증강 도메인 모델

## classes

### DataAugmentation

- **kind**: aggregate_root

**methods**:

#### applyGenerationResult

**params**:

- newStatus

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### markCanceled

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### markResolutionGenerated

**params**:

_(empty)_

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

#### dataAugSn

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

##### module_paths

_(empty)_

#### augTypeCd

- **type**: AugType
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

#### augProcSttsCd

- **type**: AugProcStatus
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

#### lblIntgrtPct

- **type**: BigDecimal
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

#### dcsnUserNo

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

#### dcsnDt

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

#### regUserNo

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

#### idmpKey

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

#### otsdJobId

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

#### promptCn

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

#### newRawSn

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

- **description**: 증강 데이터 루트. 외부 생성 3종(WINTER/NIGHT/RAIN) 증강 결과를 영상 단위(SRC_SN)로 관리하고 처리 상태·비동기 멱등/재시도 메타를 보유. (LS_DATA_AUG)

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### DataAugmentationReview

- **kind**: entity

**methods**:

#### approve

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### reject

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

**attributes**:

#### dataAugRvwSn

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

#### dataAugSn

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

#### rvwSttsCd

- **type**: ReviewStatus
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

#### lblIntgrtPct

- **type**: BigDecimal
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

- **description**: 증강 결과 검수. 상태·라벨무결성비율·반려사유·검수자를 LS_DATA_AUG에서 분리 저장. (LS_DATA_AUG_RVW)

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### DataAugmentationLabelMap

- **kind**: entity

**methods**:

#### create

**params**:

- dataAugSn
- orgnlDataLblSn
- dataLblSn
- coordRecalculated
- scaleX
- scaleY
- regId

- **is_static**: true
- **visibility**: public
- **description**: 라벨 매핑 행 생성. scaleX·scaleY 는 가로·세로에 동일하게 적용되는 균일 배율이라 축별로 다른 값을 받지 않으며 항상 같은 값이 들어온다. coordRecalculated 가 거짓이면 두 배율은 비운다.
- **is_abstract**: false
- **return_type**: LsDataAugLblMap

**attributes**:

#### dataAugLblMapSn

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

#### dataAugSn

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

#### orgnlDataLblSn

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

#### dataLblSn

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

#### coordRecalcYn

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

#### scaleX

- **type**: BigDecimal
- **is_static**: false
- **visibility**: private
- **description**: 좌표 배율. 가로·세로에 동일하게 적용되는 균일 배율(종횡비 보존)이라 scaleY 와 항상 같은 값이며, 오프셋 가산이 없어(항상 0) 이 값만으로 좌표 변환이 완전히 역산된다. 업스케일 시 1 초과를 허용한다. 외부 증강 3종은 해상도가 같아 비운다. (SCALE_X)
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

#### scaleY

- **type**: BigDecimal
- **is_static**: false
- **visibility**: private
- **description**: 좌표 배율. 가로·세로에 동일하게 적용되는 균일 배율(종횡비 보존)이라 scaleX 와 항상 같은 값이며, 오프셋 가산이 없어(항상 0) 이 값만으로 좌표 변환이 완전히 역산된다. 업스케일 시 1 초과를 허용한다. 외부 증강 3종은 해상도가 같아 비운다. (SCALE_Y)
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

- **description**: 증강 결과 라벨과 원본 라벨 간 매핑. UC-002 증강 결과 수신 시 라벨 복사와 함께 기록(AugmentResultService, 동일 트랜잭션 saveAll) — 외부 증강 3종은 해상도 동일로 COORD_RECALC_YN='N'·SCALE null. 해상도 파생(RESL_*) 시 SCALE_X/Y 에 좌표 배율을 보관한다 — 가로·세로에 동일하게 적용되는 균일 배율(종횡비 보존)이라 두 값은 항상 같으며, 오프셋 가산이 없어(항상 0) 두 값만으로 좌표 변환이 완전히 역산된다. 축소·확대(업스케일 포함, 1 초과 허용) 모두 대상이다. (LS_DATA_AUG_LBL_MAP)

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### AugType

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 증강유형코드 — 외부 증강 3종(WINTER/NIGHT/RAIN, SFR-07) + 해상도 파생 3종(RESL_1080P/RESL_720P/RESL_480P). 해상도 변경(SFR-06-03)을 증강 파생영상 모델로 통합(ADR-018) — RESL_1080P/720P/480P 3종이 AugType 정식 판별자이며 해상도 파생=증강 파생영상(RAW_SN)이다. 구 RESOLUTION 상수는 레거시 잔존.

**enum_values**:

- WINTER
- NIGHT
- RAIN
- RESL_1080P
- RESL_720P
- RESL_480P

**stereotypes**:

_(empty)_

### AugProcStatus

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 증강처리상태코드 — 외부 처리(웹훅) 결과 전용 축. 사람의 사용·폐기 결정(검수)은 ReviewStatus(LS_DATA_AUG_RVW.RVW_STTS_CD)가 별도로 소유한다(ADR-045, 두 축을 합치지 않는다).

**enum_values**:

- PENDING
- ACCEPTED
- REJECTED
- CANCELED

**stereotypes**:

_(empty)_

### ReviewStatus

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 증강 검수상태코드.

**enum_values**:

- PENDING
- ACCEPTED
- REJECTED

**stereotypes**:

_(empty)_

### DataAugmentationDiscard

- **kind**: entity

**methods**:

#### mark

**params**:

- dataAugSn
- newRawSn
- orgnlRawSn

- **is_static**: true
- **visibility**: public
- **is_abstract**: false
- **return_type**: DataAugmentationDiscard

#### restore

**params**:

- actorId
- reason
- at

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### recordVideoPath

**params**:

- path

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### markDbDeleted

**params**:

- actorId
- at

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### markFilesDeleted

**params**:

- at

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### markFileCleanupAbandoned

**params**:

- reason
- at

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

**attributes**:

#### dataAugDscdSn

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

#### dataAugSn

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

#### newRawSn

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

##### module_paths

_(empty)_

#### augTypeCd

- **type**: AugType
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

#### promptCn

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

#### dscdDt

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

#### dscdRsn

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

#### rstrDt

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

#### rstrRsn

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

#### delPrcsDt

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

#### delDt

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

#### fileDelDt

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

#### fileDelRtryNmtm

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

##### module_paths

_(empty)_

#### fileDelFailDt

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

#### fileDelFailRsn

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

#### vdoFilePath

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

- **description**: 폐기(반려) 표식 원장. 반려 시 표식하고 유예 경과 후 배치가 DB 행과 생성 파일까지 실삭제한다(7일 기본, ADR-045) — 삭제 대상은 파생·반려·유예경과 3조건 동시 충족만. 실삭제 후에도 비석(자기 행)으로 존속해 감사 추적 + 파일 삭제 재시도 단서를 남긴다. 복구(restore)는 반려 판단 자체를 되돌리며 이력(actor·사유·시각)을 남긴다. (LS_DATA_AUG_DSCD)

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

## description

외부 SFR-07 시스템이 생성한 3종(WINTER/NIGHT/RAIN) 증강 결과와 저작도구 내부 해상도 파생 3종(RESL_1080P/720P/480P, SFR-06-03)을 관리하고, 검수와 라벨 매핑을 분리해 다루는 도메인 모델. 해상도 변경을 증강 파생영상으로 통합(ADR-018) — AugType 판별자 RESL_* · LS_DATA_AUG_LBL_MAP 좌표 배율 재사용. ERD-011 기반(1차 데이터셋 테이블 LS_DATA_SET 기반 내보내기 기능이 폐기되어 제외 — 검수 승인 학습데이터 export 산출은 ADR-020 으로 저작도구 범위 안이나 검수 승인 경로가 담당해 이 도메인 모델에 속하지 않는다).

[★두 개의 상태축을 합치지 말 것 (ADR-045)] AUG_PROC_STTS_CD 는 생성 결과 전용이고 웹훅이 소유한다(ACCEPTED/REJECTED/CANCELED). REVIEWER 의 사용·폐기 결정은 LS_DATA_AUG_RVW.RVW_STTS_CD 가 단독으로 소유한다. 경위: 외부 증강은 웹훅이 항상 먼저 도착해 aug 행을 ACCEPTED 로 옮기는데, 검수 전이는 PENDING 에서만 허용돼 REVIEWER 의 승인·반려가 영구히 409 로 막혀 있었다(워크플로 자체가 도달 불가). 작업목록 등재 게이트도 리뷰 축으로 판정하며, 해상도 파생은 검수 대상이 아니라 명시적 통과 예외다.

[★중복 요청 허용 (ADR-044)] 같은 (영상 × 종류)를 몇 번이든 다시 요청할 수 있다 — 생성 결과가 매번 달라 동일 조건 재요청이 정당한 운영 동선이기 때문이다. 사전 조회 가드·409 응답·부분 유니크 인덱스(UK_LS_DATA_AUG_ACTVTN)는 모두 제거됐고 연타 방어는 FE 책임이다. 귀결로 같은 (영상 × 종류) 파생이 여러 건 공존하므로 결과물 구분의 유일한 축은 PROMPT_CN(요청 시 생성조건 5필드 원문)이고 파생 식별자는 증강 행 PK(DATA_AUG_SN)로 유일화한다. ⚠ UK_LS_DATA_AUG_RESL(해상도)은 별개 계약이라 유지된다.

[★미사용 파생 폐기 (ADR-045)] 반려된 파생은 등재 게이트로 즉시 작업 대상에서 빠지고, 유예 7일 경과 후 배치가 DB 행과 파일까지 삭제한다. 삭제 대상은 파생·반려·유예경과 3조건 동시 충족만이며 조건을 최종 DELETE SQL 에 리터럴로 박는다. NEW_RAW_SN 이 NULL 인 그랜드퍼더링 증강은 실삭제 대상에서 완전 제외한다.

[파생 깊이] 파생본은 증강 요청 대상이 될 수 없다(깊이 1 고정, ADR-023).

## module_name

DataAugmentation

## relationships

### [1]

- **to**: AugType
- **from**: DataAugmentation
- **kind**: dependency
- **label**: 증강유형
- **to_multiplicity**: 1
- **from_multiplicity**: 1

### [2]

- **to**: AugProcStatus
- **from**: DataAugmentation
- **kind**: dependency
- **label**: 처리상태
- **to_multiplicity**: 1
- **from_multiplicity**: 1

### [3]

- **to**: DataAugmentation
- **from**: DataAugmentationReview
- **kind**: association
- **label**: 증강 결과의 검수(DATA_AUG_SN)
- **to_multiplicity**: 1
- **from_multiplicity**: 0..*

### [4]

- **to**: DataAugmentation
- **from**: DataAugmentationLabelMap
- **kind**: association
- **label**: 증강 결과의 라벨 매핑(DATA_AUG_SN)
- **to_multiplicity**: 1
- **from_multiplicity**: 0..*

### [5]

- **to**: ReviewStatus
- **from**: DataAugmentationReview
- **kind**: dependency
- **label**: 검수상태
- **to_multiplicity**: 1
- **from_multiplicity**: 1

## depicts_dfeats

- DFEAT-029
- DFEAT-030

## implementation

### status

implemented

### modules

_(empty)_

### records

_(empty)_

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-26T02:28:56.480Z

### module_paths

_(empty)_

## referenced_items

- ADR-018

## realizes_features

- DFEAT-029
- DFEAT-030
