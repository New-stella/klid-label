---
logicraft_item: CDIAG-045
type: class_diagram
version: 1
domain: DOMAIN-017
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T00:45:11.228Z
status: NEW
prev_version: null
content_hash: c63f5868db40075a77c855aa1e46edb956f37c3219ac348d346ec806508e37f6
stale: false
raw: ./_raw/CDIAG-045.json
links:
  belongs_to_domain: ["[[DOMAIN-017]]"]
---

# 외부 산출물 이관 구현 계층 — 서비스·정책·연동 오퍼레이션

## theme

neutral

## title

외부 산출물 이관 구현 계층 — 서비스·정책·연동 오퍼레이션

## classes

### ImportBrowseService

- **kind**: service

**methods**:

#### listFolders

**params**:

- 현재 자리
- 이어받을 자리

- **is_static**: false
- **visibility**: public
- **description**: 허용 범위 안에서 그 자리의 한 단계 아래 폴더를 돌려준다
- **is_abstract**: false
- **return_type**: 탐색 결과

#### listVideoFiles

**params**:

- 현재 자리
- 이어받을 자리

- **is_static**: false
- **visibility**: public
- **description**: 그 자리의 영상 파일을 돌려준다
- **is_abstract**: false
- **return_type**: 탐색 결과

**attributes**:

_(empty)_

- **description**: 가져올 폴더를 한 단계씩 훑어 보여주는 서비스

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### ImportScanService

- **kind**: service

**methods**:

#### scan

**params**:

- 검사 요청 정보

- **is_static**: false
- **visibility**: public
- **description**: 폴더를 훑어 알림과 적재 가능 여부, 미등록 분류와 추천 후보를 돌려준다 — 아무것도 저장하지 않는다
- **is_abstract**: false
- **return_type**: 검사 결과

**attributes**:

_(empty)_

- **description**: 가져올 산출물을 미리 훑어 판정하는 서비스

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### ImportService

- **kind**: service

**methods**:

#### importFolder

**params**:

- 적재 요청 정보
- 실행자 식별자

- **is_static**: false
- **visibility**: public
- **description**: 영상과 프레임과 라벨을 적재하고 이관 이력을 남긴다 — 이미 들어온 산출물이면 덮지 않고 거부한다
- **is_abstract**: false
- **return_type**: 적재 결과

**attributes**:

_(empty)_

- **description**: 검사를 통과한 산출물을 실제로 적재하는 서비스

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### ImportMappingService

- **kind**: service

**methods**:

#### save

**params**:

- 대응 등록 정보
- 실행자 식별자

- **is_static**: false
- **visibility**: public
- **description**: 외부 분류와 우리 분류의 대응을 기록한다 — 이후 같은 분류는 자동으로 적용된다
- **is_abstract**: false
- **return_type**: 저장 결과

#### list

**params**:

- 분류 축
- 쓰이지 않는 것 포함 여부
- 페이지 정보

- **is_static**: false
- **visibility**: public
- **description**: 등록된 분류 대응을 쪽 단위로 돌려준다
- **is_abstract**: false
- **return_type**: 대응 목록

#### disable

**params**:

- 대응 식별번호
- 실행자 식별자

- **is_static**: false
- **visibility**: public
- **description**: 분류 대응을 더 쓰지 않도록 내린다
- **is_abstract**: false
- **return_type**: 없음

**attributes**:

_(empty)_

- **description**: 외부 분류와 우리 분류의 대응을 관리하는 서비스

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### ImportDeidentCompleteService

- **kind**: service

**methods**:

#### record

**params**:

- 영상 식별번호
- 비식별 완료 정보
- 실행자 식별자

- **is_static**: false
- **visibility**: public
- **description**: 비식별 산출물이 실제로 있는지 확인한 뒤에만 완료로 기록해 검수 승인 보류를 푼다
- **is_abstract**: false
- **return_type**: 기록 결과

**attributes**:

_(empty)_

- **description**: 외부에서 비식별한 산출물을 받아 기록하는 서비스

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

외부 산출물 이관 Service

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
