---
logicraft_item: CDIAG-008
type: class_diagram
version: 4
domain: DOMAIN-001
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:50.326Z
status: NEW
prev_version: null
content_hash: f0b74e69a8ed6c5b5b74880b9befc363ebd8328063ed8a88e5ca336860ca7bed
stale: true
raw: ./_raw/CDIAG-008.json
links:
  belongs_to_domain: ["[[DOMAIN-001]]"]
  depicts: ["[[DFEAT-001]]", "[[DFEAT-002]]", "[[DFEAT-003]]"]
  references: ["[[DFEAT-001]]", "[[DFEAT-002]]", "[[DFEAT-003]]"]
---

# 사용자·권한 도메인 모델 (개념 모델 — ERD 없음)

## theme

neutral

## title

사용자·권한 도메인 모델 (개념 모델 — ERD 없음)

## classes

### User

- **kind**: aggregate_root

**methods**:

#### hasRole

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: boolean

#### isReviewer

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: boolean

**attributes**:

#### userNo

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

#### role

- **type**: Role
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

#### channel

- **type**: Channel
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

- **description**: 저작도구 사용자(개념). 마스터 데이터는 저작도구 소유 테이블에 있으며, 관제가 브라우저 localStorage 에 넣어주는 userId·userNm 을 받아 역할 클레임 시점에 우리 사용자 테이블로 upsert 한다. userNo 는 JWT sub 에서 오므로 위조 불가하고, userId/userNm 은 FE 가 보내는 표시용 값이라 위조해도 자기 행 이름만 바뀐다.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### TokenClaims

- **kind**: entity

**methods**:

#### isExpired

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: boolean

**attributes**:

#### userNo

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

#### role

- **type**: Role
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

#### channel

- **type**: Channel
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

#### issuer

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

#### expiresAt

- **type**: Instant
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

- **description**: 관제/포털이 발급한 JWT에서 디코드한 클레임(개념 VO). role·channel·사용자 식별자·만료를 보유하며 SecurityContext에 적재된다. DFEAT-001.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### MenuAccessRule

- **kind**: entity

**methods**:

#### isAccessibleBy

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: boolean

**attributes**:

#### role

- **type**: Role
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

#### menuCode

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

#### accessible

- **type**: boolean
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

- **description**: 역할↔메뉴/기능 접근 매핑 규칙(개념). 역할별 노출 메뉴·기능 접근 권한을 분기한다. DFEAT-002 (1차 LS_USER_MENU 계보, 6역할→3역할 통합).

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### Role

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 사용자 역할(2차 3역할 통합). REVIEWER가 ADMIN 권한 흡수(사용자 관리·시스템 설정·배정·검수 승인/반려), WORKER는 라벨 수정·검수 제출, PORTAL_USER는 데이터마트 영상 선택·라벨 확인.

**enum_values**:

- REVIEWER
- WORKER
- PORTAL_USER

**stereotypes**:

_(empty)_

### Channel

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: JWT 발급 채널(개념). 토큰 channel 클레임으로 내부(관제)/외부(포털) 진입을 구분해 권한 분기.

**enum_values**:

- INTERNAL
- PORTAL

**stereotypes**:

_(empty)_

### AccessControlService

- **kind**: service

**methods**:

#### authenticate

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: TokenClaims

#### authorize

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: boolean

#### resolveAccessibleMenus

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: List<MenuAccessRule>

**attributes**:

_(empty)_

- **description**: JWT 검증 → TokenClaims 추출 → 역할 기반 접근 제어를 수행하는 도메인 서비스. 검증 실패 시 상위 시스템 로그인으로 리다이렉트, 역할/메뉴 매핑 판정. DFEAT-001/DFEAT-002 구현.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### UserManagementService

- **kind**: service

**methods**:

#### listUsers

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: Page<User>

#### changeRole

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

**attributes**:

_(empty)_

- **description**: REVIEWER가 사용자 목록 조회·역할/권한 부여·변경을 수행하는 도메인 서비스(개념). 역할축은 저작도구 소유 테이블 LS_USER_ROLE이 단독 진실원이며, 사용자 마스터 자체도 관제 공유 테이블을 떠나 저작도구 소유 테이블로 upsert 된다. DFEAT-003 구현.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

## description

외부 JWT 인계 인증과 역할 기반 접근 제어(RBAC)를 표현하는 개념 모델. 저작도구는 독립 로그인 UI 가 없고 관제서버/포털이 발급한 JWT 의 role·channel 클레임으로 사용자·권한을 식별한다.

[★소유 구조 정정 (구 서술 폐기)] 이전 본문은 '사용자 마스터는 관제 공유 MNG_ACCT_*/MNG_AUTHRT_* + LS_AUTHRT_MPNG 매핑에 위임된다'고 적었으나 두 단계로 무효화됐다.
· 역할축은 이미 저작도구 소유 테이블 LS_USER_ROLE(V75)이 단독으로 갖고 있었고, 관제 공유 계정권한 테이블 2종은 런타임 참조 0 인 죽은 테이블이어서 V165 로 삭제됐다.
· 사용자 마스터 자체도 관제 공유 테이블을 떠난다(ADR-042/043). 관제가 브라우저 localStorage 에 넣어주는 userId·userNm 을 받아 역할 클레임 시점에 우리 사용자 테이블로 upsert 한다. 근거: 그 공유 테이블을 채우는 코드가 양쪽 어느 곳에도 없어 사실상 비어 있었다(실측).

[식별자 신뢰 경계] userNo 는 JWT sub 에서 오므로 위조 불가하고, userId/userNm 은 FE 가 보내는 표시용 값이라 위조해도 자기 행 이름만 바뀐다.

[역할 3종] REVIEWER · WORKER · PORTAL_USER. 별도 ADMIN 은 없고 관리 권한은 REVIEWER 에 통합된다(ADR-003). 자가부여 화이트리스트에는 WORKER 와 REVIEWER 가 들어가며, 관리자 비밀번호의 관리 수준이 시스템 전체 권한 경계다(인지·수용된 잔여 위험).

전용 활성 ERD 가 없어(1차 ERD-001 폐기) 물리 컬럼이 아닌 도메인 개념 속성으로 구성한다.

## module_name

UserAccessControl

## relationships

### [1]

- **to**: Role
- **from**: User
- **kind**: composition
- **label**: 역할 보유
- **to_multiplicity**: 1
- **from_multiplicity**: 1

### [2]

- **to**: Channel
- **from**: User
- **kind**: composition
- **label**: 진입 채널
- **to_multiplicity**: 1
- **from_multiplicity**: 1

### [3]

- **to**: Role
- **from**: TokenClaims
- **kind**: association
- **label**: role 클레임
- **to_multiplicity**: 1
- **from_multiplicity**: 1

### [4]

- **to**: Channel
- **from**: TokenClaims
- **kind**: association
- **label**: channel 클레임
- **to_multiplicity**: 1
- **from_multiplicity**: 1

### [5]

- **to**: Role
- **from**: MenuAccessRule
- **kind**: association
- **label**: 역할별 접근 규칙
- **to_multiplicity**: 1
- **from_multiplicity**: 0..*

### [6]

- **to**: TokenClaims
- **from**: AccessControlService
- **kind**: dependency
- **label**: JWT 디코드 결과
- **to_multiplicity**: 1
- **from_multiplicity**: 1

### [7]

- **to**: MenuAccessRule
- **from**: AccessControlService
- **kind**: dependency
- **label**: 메뉴 접근 판정
- **to_multiplicity**: 0..*
- **from_multiplicity**: 1

### [8]

- **to**: User
- **from**: UserManagementService
- **kind**: dependency
- **label**: 사용자/역할 관리
- **to_multiplicity**: 0..*
- **from_multiplicity**: 1

## depicts_dfeats

- DFEAT-001
- DFEAT-002
- DFEAT-003

## referenced_items

_(empty)_

## realizes_features

- DFEAT-001
- DFEAT-002
- DFEAT-003
