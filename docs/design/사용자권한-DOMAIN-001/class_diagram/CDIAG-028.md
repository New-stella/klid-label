---
logicraft_item: CDIAG-028
type: class_diagram
version: 1
domain: DOMAIN-001
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T00:45:16.934Z
status: NEW
prev_version: null
content_hash: b92c8812a5d7326940873ee47cedcd27487e8d9a48a9d9d2f8fddea88fe202b0
stale: false
raw: ./_raw/CDIAG-028.json
links:
  belongs_to_domain: ["[[DOMAIN-001]]"]
---

# 사용자·권한 구현 계층 — 배치·실행기·보안 오퍼레이션

## theme

neutral

## title

사용자·권한 구현 계층 — 배치·실행기·보안 오퍼레이션

## classes

### AdminPasswordVerifier

- **kind**: service

**methods**:

#### currentHash

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 현재 보관 중인 관리자 패스워드 해시를 돌려준다
- **is_abstract**: false
- **return_type**: 해시 문자열

#### isConfigured

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **description**: 관리자 패스워드가 설정돼 있는지 알려준다
- **is_abstract**: false
- **return_type**: 참·거짓

#### matches

**params**:

- 입력 패스워드

- **is_static**: false
- **visibility**: public
- **description**: 입력한 패스워드가 보관 중인 것과 맞는지 대조한다
- **is_abstract**: false
- **return_type**: 참·거짓

#### replace

**params**:

- 새 패스워드
- 수정자 식별자
- 변경 일시

- **is_static**: false
- **visibility**: public
- **description**: 관리자 패스워드를 새 값으로 교체하고 누가 언제 바꿨는지 남긴다
- **is_abstract**: false
- **return_type**: 없음

**attributes**:

_(empty)_

- **description**: 관리자 패스워드를 보관·대조하고 교체하는 판정기

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### JwtAuthenticationFilter

- **kind**: class

**methods**:

#### doFilterInternal

**params**:

- 요청
- 응답
- 필터 연결

- **is_static**: false
- **visibility**: protected
- **description**: 요청에 실린 토큰의 발급처와 채널을 검증하고 저작도구가 보관한 역할로 인가 문맥을 세운다
- **is_abstract**: false
- **return_type**: 없음

**attributes**:

_(empty)_

- **description**: 상위 시스템이 발급한 토큰을 해석해 인가 문맥을 세우는 진입 필터

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### SecurityConfig

- **kind**: class

**methods**:

#### securityFilterChain

**params**:

- 보안 설정
- 교차 출처 설정
- 역할 계층

- **is_static**: false
- **visibility**: public
- **description**: 순서 있는 매처로 각 창구의 접근 규칙을 세운다
- **is_abstract**: false
- **return_type**: 보안 필터 사슬

#### corsConfigurationSource

**params**:

- 허용 출처 목록

- **is_static**: false
- **visibility**: public
- **description**: 허용할 교차 출처를 정한다 — 아무 곳이나 열지 않는다
- **is_abstract**: false
- **return_type**: 교차 출처 설정

**attributes**:

_(empty)_

- **description**: 창구별 접근 규칙과 교차 출처 허용 범위를 정하는 보안 구성

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

2차 신규 — 구현 계층 오퍼레이션을 설계 산출물에 처음으로 적는다.

## description

인증 진입과 인가 판정, 관리자 패스워드를 다루는 구현 계층이다.

컨트롤러가 아닌 구현 계층 클래스의 오퍼레이션을 적는다. 이름과 파라미터와 반환은 구현에서 실측했고 설명은 그 오퍼레이션이 무엇을 하는지 한 문장으로 적었다. 반환은 표를 읽기 쉽도록 추상 이름으로 적고 돌려줄 것이 없으면 없음이다.

주입 의존은 여기 적지 않는다 — 시퀀스에서 이미 드러난다. 이름이 같고 파라미터가 다른 오퍼레이션은 둘 다 적었다 — 받는 값이 다르면 하는 일도 다르기 때문이다. 이 다이어그램은 도메인 객체 모델이 아니라 구현 계층 목록이므로 같은 도메인의 도메인 모델 다이어그램과 축이 다르다.

## module_name

사용자·권한 Implementation

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
