---
logicraft_item: CDIAG-036
type: class_diagram
version: 1
domain: DOMAIN-003
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T00:45:12.141Z
status: NEW
prev_version: null
content_hash: a31074be72c94f3ee5625396592c4573bb399f1f82b0dc7855cd0de02234e9d9
stale: false
raw: ./_raw/CDIAG-036.json
links:
  belongs_to_domain: ["[[DOMAIN-003]]"]
---

# 영상·프레임 수집 구현 계층 — 서비스·정책·연동 오퍼레이션

## theme

neutral

## title

영상·프레임 수집 구현 계층 — 서비스·정책·연동 오퍼레이션

## classes

### DevAutolabelTestService

- **kind**: service

**methods**:

#### upload

**params**:

- 영상 파일
- 인입 메타데이터

- **is_static**: false
- **visibility**: public
- **description**: 올린 영상을 적재하고 비식별을 선두로 파이프라인을 시작한다
- **is_abstract**: false
- **return_type**: 업로드 결과

**attributes**:

_(empty)_

- **description**: 영상 한 건을 직접 올려 파이프라인에 태우는 서비스

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### TrainingVideoIngestService

- **kind**: service

**methods**:

#### scanAndIngest

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 미처리 인입 행을 집어 영상으로 적재하고 적재 사건을 알린다
- **is_abstract**: false
- **return_type**: 적재 건수

#### reclaimStaleProcessing

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 처리 중으로 고착된 인입 행을 되돌려 다음 주기에 다시 집히게 한다
- **is_abstract**: false
- **return_type**: 회수 건수

**attributes**:

_(empty)_

- **description**: 관제가 넣은 인입 대기 행을 영상으로 적재하는 서비스

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### VideoQueryService

- **kind**: service

**methods**:

#### listForActor

**params**:

- 페이지 정보
- 걸러내기 조건
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 조건에 맞는 영상을 그 사용자가 볼 수 있는 범위로 좁혀 쪽 단위로 돌려준다
- **is_abstract**: false
- **return_type**: 영상 목록

#### getOne

**params**:

- 영상 식별번호

- **is_static**: false
- **visibility**: public
- **description**: 영상 한 건의 상세와 그 유형에 등록된 검증 질문 목록을 함께 돌려준다
- **is_abstract**: false
- **return_type**: 영상 상세

#### getAutoLabels

**params**:

- 영상 식별번호

- **is_static**: false
- **visibility**: public
- **description**: 그 영상의 오토라벨링 결과 요약을 돌려준다
- **is_abstract**: false
- **return_type**: 오토라벨 요약

#### usesReviewStatusJoin

**params**:

- 검수 상태

- **is_static**: false
- **visibility**: public
- **description**: 그 검수 상태로 거를 때 검수 상태 조인이 필요한지 판정한다
- **is_abstract**: false
- **return_type**: 참·거짓

**attributes**:

_(empty)_

- **description**: 영상 목록과 상세를 인가 범위 안에서 조회하는 서비스

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### VideoStreamService

- **kind**: service

**methods**:

#### stream

**params**:

- 영상 식별번호
- 요청 헤더

- **is_static**: false
- **visibility**: public
- **description**: 요청한 구간만큼 비식별 영상을 내보낸다 — 비식별본이 없으면 원본으로 대신하지 않는다
- **is_abstract**: false
- **return_type**: 스트리밍 응답

#### issueSignedUrl

**params**:

- 영상 식별번호
- 사용자 번호
- 일회용 값

- **is_static**: false
- **visibility**: public
- **description**: 정해진 시간만 유효한 서명 주소를 발급한다
- **is_abstract**: false
- **return_type**: 스트리밍 주소

#### resolveDeidPath

**params**:

- 영상 식별번호

- **is_static**: false
- **visibility**: public
- **description**: 그 영상의 비식별 산출물 경로를 찾는다
- **is_abstract**: false
- **return_type**: 경로 문자열

#### resolveSafe

**params**:

- 허용 기준 경로 목록
- 대상 경로

- **is_static**: false
- **visibility**: public
- **description**: 대상이 허용 범위 안에 있는지 실제 경로로 확인한 뒤에만 연다 — 심링크로 범위를 벗어나는 것을 막는다
- **is_abstract**: false
- **return_type**: 검증된 파일

#### exists

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 내보낼 비식별 영상이 실제로 있는지 확인한다
- **is_abstract**: false
- **return_type**: 참·거짓

**attributes**:

_(empty)_

- **description**: 비식별 영상만 구간 단위로 내보내는 서비스

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### VideoProbe

- **kind**: interface

**methods**:

#### probe

**params**:

- 영상 파일 경로

- **is_static**: false
- **visibility**: public
- **description**: 해상도·가로·세로·초당 프레임 수·비트율·코덱을 파일에서 직접 읽는다
- **is_abstract**: false
- **return_type**: 영상 기술메타

**attributes**:

_(empty)_

- **description**: 영상 파일에서 기술 메타를 읽어 오는 창구

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### TusUploadService

- **kind**: service

**methods**:

#### createSession

**params**:

- 사용자 번호
- 전체 길이
- 인입 메타데이터

- **is_static**: false
- **visibility**: public
- **description**: 전송 세션을 만들고 인입 대기 행을 함께 남긴다
- **is_abstract**: false
- **return_type**: 전송 식별자

#### appendChunk

**params**:

- 전송 식별자
- 사용자 번호
- 이어 붙일 위치
- 조각
- 조각 길이

- **is_static**: false
- **visibility**: public
- **description**: 보내온 조각을 이어 붙인다 — 위치가 어긋나면 받지 않는다
- **is_abstract**: false
- **return_type**: 이어 붙인 결과

#### getForOwner

**params**:

- 전송 식별자
- 사용자 번호

- **is_static**: false
- **visibility**: public
- **description**: 전송을 그 소유자에게만 돌려준다 — 남의 전송에 손대지 못하게 한다
- **is_abstract**: false
- **return_type**: 전송 원장

#### cancel

**params**:

- 전송 식별자
- 사용자 번호

- **is_static**: false
- **visibility**: public
- **description**: 진행 중인 전송을 취소하고 조각을 정리한다
- **is_abstract**: false
- **return_type**: 없음

#### probe

**params**:

- 영상 파일 경로

- **is_static**: false
- **visibility**: public
- **description**: 다 받은 영상의 초당 프레임 수를 읽어 둔다
- **is_abstract**: false
- **return_type**: 초당 프레임 수

**attributes**:

_(empty)_

- **description**: 끊겨도 이어 보낼 수 있는 업로드 전송을 관리하는 서비스

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### BatchPipeline

- **kind**: value_object

**methods**:

#### steps

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 담고 있는 단계 목록을 순서 그대로 돌려준다
- **is_abstract**: false
- **return_type**: 단계 목록

**attributes**:

_(empty)_

- **description**: 배치 단계를 순서대로 담는 값 — 순서를 바꾸는 유일한 자리

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

## brownfield

### status

new

### change_kind

- component-add

### diff_summary

2차 신규 — 구현 계층 서비스의 오퍼레이션을 설계 산출물에 적는다.

## description

컨트롤러가 아닌 구현 계층 클래스의 오퍼레이션을 적는다. 이 클래스들은 시퀀스가 받는 메시지에 시그니처가 드러나지 않아 설계 클래스 정의의 칸이 비어 있던 것들이다.

코드에 있는 공개 메서드를 다 싣지 않았다 — 다른 클래스가 실제로 부르는 창구와 프레임워크가 부르는 진입점(사건 수신·주기 실행·구성 생성)만 남기고 내부 보조와 저장소 조회는 뺐다. 그 기준으로 145개에서 63개로 좁혔다.

이름과 파라미터와 반환은 구현에서 실측했고 설명은 그 오퍼레이션이 무엇을 하는지 한 문장으로 적었다. 반환은 표를 읽기 쉽도록 추상 이름으로 적고 돌려줄 것이 없으면 없음이다. 주입 의존은 여기 적지 않는다 — 시퀀스에서 이미 드러난다.

## module_name

영상·프레임 수집 Service

## relationships

_(empty)_

## attached_files

_(empty)_

## depicts_dfeats

_(empty)_

## implementation

### status

planned

### modules

_(empty)_

### records

_(empty)_

### progress

0

### subtasks

_(empty)_

### module_paths

_(empty)_

## referenced_items

_(empty)_

## realizes_features

_(empty)_
