---
logicraft_item: SCREEN-042
type: screen_spec
version: 15
domain: DOMAIN-014
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-08T00:22:48.396Z
status: CHANGED
prev_version: 11
content_hash: 0e8e9d49556ab4dcea79f1f7b581597e0ad55e9d5c8c99dc70d0839fcfd2abcd
stale: false
raw: ./_raw/SCREEN-042.json
links:
  based_on: ["[[ADR-046]]"]
  belongs_to_domain: ["[[DOMAIN-014]]"]
  consumes: ["[[API-068]]", "[[API-069]]", "[[API-194]]", "[[API-226]]", "[[API-227]]", "[[API-228]]", "[[API-229]]", "[[API-230]]"]
  covered_by: ["[[AC-1088]]", "[[AC-1089]]", "[[AC-1090]]", "[[AC-1091]]", "[[AC-1092]]"]
  implements: ["[[IMPREC-156]]"]
  references: ["[[API-068]]", "[[API-069]]", "[[API-194]]", "[[API-226]]", "[[API-227]]", "[[API-228]]", "[[API-229]]", "[[API-230]]"]
  requires: ["[[ROLE-004]]"]
  designs_backward: ["[[SD-036]]"]
  granted_on_backward: ["[[ROLE-004]]"]
  navigates_to_backward: ["[[NAV-001]]"]
  references_backward: ["[[ADR-046]]", "[[SEQ-025]]"]
---

# 연동 서버 주소 관리 화면

## route

/admin/endpoints

## title

연동 서버 주소 관리 화면

## device

desktop

## status

draft

## purpose

관리자가 외부 연동 대상의 주소를 재기동 없이 교체하는 관리자 페이지 화면. 다루는 주소는 두 축으로 갈린다. 설정 칸 축은 비식별 서버와 관제 통지 수신처이며, 저장한 값이 그 축의 진실원이고 다음 호출부터 쓰인다. 장비 목록 축은 AI 추론과 외부 시계열 분석 벤더이며, 그 두 축의 주소 진실원은 장비 원장이고 배포 설정값은 그 유형의 장비가 하나도 없을 때 최초 1회 씨앗으로만 쓰인다 — 원장에 행이 없을 때 설정값으로 대신 호출하는 폴백은 두지 않는다. 데이터베이스 접속정보는 이 화면의 대상이 아니다. 이 화면은 관리자 역할을 요구하며, 저장에는 관리자 패스워드로 연 단기 유효창이 더 가산된다 — 유효창은 역할을 올리지 않는다. 값은 스킴과 형식만 보고 주소 대역으로는 막지 않는다. 잔여 둘 — AI 추론과 외부 시계열 분석 벤더의 설정 칸은 아직 화면에 남아 있으나 추론 축이 원장에서 장비를 고르는 경로에 이어지면 두 칸을 함께 걷어내 장비 목록으로 일원화하며, 외부 증강 벤더 주소는 이 창구가 다루기로 정해진 대상이지만 아직 등록되지 않았다.

## sections

### 페이지 헤더

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **type**: Heading
- **label**: 연동 서버 주소

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 부제. 이 화면이 다루는 대상을 그대로 나열한다.
- **type**: Text
- **label**: 비식별 서버 · AI 추론 서버 · 외부 시계열 분석 벤더 · 관제 통지 수신처

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: 보조 문구. 대상 밖이라는 사실을 화면에서 밝혀 오인을 막는다.
- **type**: Text
- **label**: 데이터베이스 접속정보는 이 화면에서 다루지 않습니다.

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 제목 '연동 서버 주소'와 부제('비식별 서버 · AI 추론 서버 · 외부 시계열 분석 벤더 · 관제 통지 수신처'). 관리자 페이지에 속하는 화면이라 진입할 때 관리자 패스워드 확인을 거친다. 조회 자체는 관리자 역할만으로 되므로, 패스워드 확인을 마치지 않은 상태에서도 현재 값은 보이고 저장만 막힌다. 데이터베이스 접속정보는 이 화면에서 다루지 않는다는 사실을 부제 아래 보조 문구로 밝혀, 여기서 찾다가 없다고 판단하는 일이 없게 한다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 연동 서버 주소

- **role**: main
- **layout**: form

**components**:

#### [1]

- **note**: 위탁 요청과 진행 상태 조회가 함께 이 주소를 따라간다.
- **type**: Input
- **label**: 비식별 서버 주소

**columns**:

_(empty)_

- **io_attr**: E

**options**:

_(empty)_

- **binds_to**: configs.kpst.deid.base-url
- **validation**: 필수 · 스킴은 http 또는 https · 주소 형식과 호스트가 있어야 한다 · 길이 상한 있음. 주소 대역으로는 막지 않으므로 사설망·루프백 주소도 저장된다.
- **placeholder**: http:// 또는 https:// 로 시작하는 주소

#### [2]

- **type**: Input
- **label**: AI 추론 서버 주소

**columns**:

_(empty)_

- **io_attr**: E

**options**:

_(empty)_

- **binds_to**: configs.authoring.integration.ai-server.base-url
- **validation**: 필수 · 스킴은 http 또는 https · 주소 형식과 호스트가 있어야 한다 · 길이 상한 있음. 주소 대역으로는 막지 않으므로 사설망·루프백 주소도 저장된다.
- **placeholder**: http:// 또는 https:// 로 시작하는 주소

#### [3]

- **type**: Input
- **label**: 외부 시계열 분석 벤더 주소

**columns**:

_(empty)_

- **io_attr**: E

**options**:

_(empty)_

- **binds_to**: configs.vlm.client.url
- **validation**: 필수 · 스킴은 http 또는 https · 주소 형식과 호스트가 있어야 한다 · 길이 상한 있음. 주소 대역으로는 막지 않으므로 사설망·루프백 주소도 저장된다.
- **placeholder**: http:// 또는 https:// 로 시작하는 주소

#### [4]

- **type**: Input
- **label**: 관제 통지 수신처 주소

**columns**:

_(empty)_

- **io_attr**: E

**options**:

_(empty)_

- **binds_to**: configs.authoring.control-notify.url
- **validation**: 필수 · 스킴은 http 또는 https · 주소 형식과 호스트가 있어야 한다 · 길이 상한 있음. 주소 대역으로는 막지 않으므로 사설망·루프백 주소도 저장된다.
- **placeholder**: http:// 또는 https:// 로 시작하는 주소

#### [5]

- **note**: 그 주소의 저장 행이 아직 없을 때만 입력란 아래에 붙는다. 오류 표기로 그리지 않는다.
- **type**: Text
- **label**: 저장된 값 없음 — 배포 기본값으로 동작 중

**columns**:

_(empty)_

**options**:

_(empty)_

#### [6]

- **note**: 실제로 바뀐 주소만 요청에 담는다. 유효창이 남아 있지 않으면 요청을 보내기 전에 관리자 확인 창을 먼저 연다.
- **type**: Button
- **label**: 저장

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-069

#### [7]

- **note**: 거부 안내는 어느 입력란이 문제인지까지만 알리고, 입력 원문·호스트·해석 결과를 문구에 담지 않는다.
- **type**: Alert
- **label**: 주소 형식이 올바르지 않습니다

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

#### [8]

- **note**: 값을 바꾼 노드에서는 즉시, 다른 노드에서는 설정 캐시 수명만큼 늦게 반영된다는 사실을 함께 적어, 바로 반영되지 않는 것을 오류로 읽지 않게 한다.
- **type**: Text
- **label**: 저장한 주소는 재기동 없이 다음 호출부터 쓰입니다

**columns**:

_(empty)_

**options**:

_(empty)_

**description**:

현재 저장된 주소를 불러와 입력란에 채운다. 저장된 값이 없으면 배포 기본값이 쓰이는 상태이며, 그 경우 입력란은 비어 있고 '저장된 값 없음'을 보조 문구로 알린다. 값이 없는 것은 오류가 아니라 정상 상태다. 조회는 관리자 역할만으로 되고 유효창을 요구하지 않는다. 저장은 실제로 바뀐 주소만 요청에 담는다. 값 검증은 스킴이 http 또는 https 인지와 주소 형식·호스트 존재까지이며, 주소 대역으로는 막지 않는다 — 사설망·루프백 주소도 그대로 저장된다. 연동 대상이 내부망의 별도 서버에 있을 수 있어 대역으로 막으면 정당한 연동이 막히기 때문이고, 망 통제는 인프라 계층이 담당한다. 형식이 올바르지 않으면 거부하되 거부 사유에 입력 원문·호스트·해석 결과를 싣지 않는다. 저장한 값은 재기동 없이 다음 호출부터 반영된다 — 값을 바꾼 노드에서는 즉시, 다른 노드에서는 설정 캐시 수명만큼 늦다.

★**네 칸 중 둘은 한 칸이 아니라 목록이다.** AI 추론 서버와 외부 시계열 분석 벤더는 장비를 여러 대 두고 부하에 따라 나눠 보내므로, 그 두 축의 진실원은 설정값 한 칸이 아니라 **장비 원장**이다. 배포 설정값은 그 유형의 장비가 하나도 없을 때 최초 1회 씨앗으로만 쓰이고 그 뒤로는 원장이 이긴다 — 여기서 바꾼 주소는 재기동으로 되돌아가지 않는다. 비식별 서버와 관제 통지 수신처는 종전대로 한 칸이다. ⚠ 넷을 한꺼번에 원장으로 옮긴 것으로 읽지 말 것.

잔여 — 이 절의 네 칸 가운데 AI 추론과 외부 시계열 분석 벤더 칸은 추론 축이 원장에서 장비를 고르는 경로에 이어지는 시점에 함께 걷어내고 장비 목록 한 곳으로 모은다. 추론 칸을 먼저 걷으면 그 이음이 설 때까지 추론 주소를 바꿀 길이 없어지므로 둘을 함께 걷는다. 외부 시계열 분석 벤더 칸은 이미 실효가 없다 — 그 유형의 장비가 등록돼 있으면 위탁은 원장 주소로 나가므로 이 칸을 바꿔도 나가는 주소는 달라지지 않는다. 외부 증강 벤더 주소도 이 창구의 대상이지만 아직 칸이 없다.

**references_apis**:

- API-068
- API-069

**references_features**:

_(empty)_

### AI 장비 목록 — 추론·시계열 노드

- **role**: main
- **layout**: list

**components**:

#### [1]

- **note**: 유형별로 묶어 본다. 두 유형은 부하를 세는 축이 달라 한 표에 섞으면 같은 수치로 오해된다 — 추론은 그 장비가 처리를 기다리는 건수, 시계열은 결과를 기다리는 위탁 건수다.
- **type**: Tabs
- **label**: 장비 유형 전환

**columns**:

_(empty)_

**options**:

- 추론
- 외부 시계열 분석

#### [2]

- **note**: 상태는 가용·이용불가·정비중·비활성 넷이며 배지로 구분한다. 이용불가는 상태점검 연속 실패로 자동 배제된 것이라 사람이 내린 비활성과 구분해 보여야 한다.
- **type**: Table
- **label**: 장비 목록

**columns**:

- 식별자
- 이름
- 주소
- 상태
- 최근 점검
- 연속 실패
- 용도별 대기

**options**:

_(empty)_

- **binds_to**: aiServers.items

#### [3]

- **note**: 유효창이 없으면 눌렀을 때 이 화면의 관리자 확인 창이 열린다 — 새 동선을 만들지 않는다.
- **type**: Button
- **label**: 장비 등록

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **note**: 식별자(소문자·숫자 20자 이하)·이름·주소·유형을 받는다. 수정에서는 이름과 주소만 열린다 — 유형이 바뀌면 그 장비를 고르던 축이 달라져 새로 등록하는 것이 맞고, 상태는 전이 규칙이 있는 별도 창구가 소유한다.
- **type**: Dialog
- **label**: 장비 등록·수정

**columns**:

_(empty)_

**options**:

_(empty)_

#### [5]

- **note**: 현재 상태에서 갈 수 있는 상태만 고를 수 있게 한다. 허용되지 않는 전이와 현재와 같은 상태는 서버가 충돌로 거부하므로 화면은 그 문구를 그대로 보여준다.
- **type**: Select
- **label**: 상태 전이

**columns**:

_(empty)_

**options**:

- 가용
- 이용불가
- 정비중
- 비활성

#### [6]

- **note**: 그 유형의 마지막 가용 장비를 지우거나 내리려 하면 서버가 거부한다. 어느 유형이 비게 되는지와 함께, 교체하려면 새 장비를 먼저 넣으라는 것을 알린다.
- **type**: Alert
- **label**: 마지막 가용 장비 보호 안내

**columns**:

_(empty)_

**options**:

_(empty)_

**description**:

AI 추론 장비와 외부 시계열 분석 장비를 유형별로 여러 대 등록·관리한다. 위 주소 칸의 그 두 축이 여기서 목록으로 펼쳐진 것이며, 이 목록이 주소의 진실원이다.

이 화면은 관리자 페이지에 속해 조회부터 관리자 역할을 요구한다. 조회는 유효창 없이 되고, 등록·수정·상태 전이·삭제에는 유효창이 가산된다 — 위 주소 칸의 저장과 같은 규칙이다.

주소를 저장할 때 스킴·형식·예약 대역 검증이 걸린다. 평문 http 와 사설 대역은 통과가 정상이다 — 연동 대상이 내부망의 별도 장비에 있는 것이 통상이기 때문이다. 클라우드 메타데이터·링크로컬 같은 예약 대역과 비허용 스킴만 거부되며, 거부 문구에 입력 주소나 해석 결과가 드러나지 않으므로 화면도 그것을 되비추지 않는다.

**references_apis**:

- API-226
- API-227
- API-228
- API-229
- API-230

**references_features**:

_(empty)_

### 관리자 유효창 상태

- **role**: side
- **layout**: stack

**components**:

#### [1]

- **note**: 유효창이 열려 있는지를 두 상태로만 보여준다.
- **type**: Badge
- **label**: 관리자 확인됨 / 확인 필요

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 남은 시간을 분·초로 보여주고 얼마 남지 않으면 눈에 띄게 바꾼다. 판정은 서버가 가지므로 이 값은 안내다.
- **type**: Text
- **label**: 남은 유효 시간

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: 유효창을 새로 열거나 남은 시간을 다시 채울 때 누른다. 관리자 확인 창을 연다.
- **type**: Button
- **label**: 관리자 확인

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary
- **triggers_api**: API-194

#### [4]

- **note**: 확인이 저장에만 가산되고 역할을 올리지 않는다는 사실을 화면에서 밝힌다.
- **type**: Text
- **label**: 조회는 이 확인 없이도 됩니다

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 관리자 패스워드로 연 유효창이 지금 열려 있는지와 남은 시간을 사람이 볼 수 있게 표시한다. 남은 시간을 보여주는 이유는, 긴 입력을 마치고 저장을 눌렀을 때 비로소 만료를 알게 되는 일을 없애기 위해서다. 유효 여부의 판정은 서버가 소유하므로 이 표시는 안내일 뿐이며 화면이 스스로 아직 유효하다고 정하지 않는다. 유효창은 역할을 올리지 않는다 — 관리자 역할은 그대로 필요하고, 유효창은 저장에만 가산된다. 조회는 유효창 없이도 된다. 남은 시간이 끝난 뒤 저장을 누르면 거부 코드로만 알리고 끝내지 않고 관리자 확인 창을 다시 열어 준다. 확인을 마치면 누르던 저장을 이어서 수행한다.

**references_apis**:

- API-194

**references_features**:

_(empty)_

### 관리자 확인 창

- **role**: modal
- **layout**: form

**components**:

#### [1]

- **type**: Heading
- **label**: 관리자 확인

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Input
- **label**: 관리자 패스워드

**columns**:

_(empty)_

- **io_attr**: I

**options**:

_(empty)_

- **validation**: 필수 · 입력값은 가려 보이며 화면 상태와 기록 어디에도 남기지 않는다.
- **placeholder**: 관리자 패스워드

#### [3]

- **note**: 확인이 끝나면 유효창이 열리고, 저장 때문에 열린 창이면 그 저장을 이어서 수행한다.
- **type**: Button
- **label**: 확인

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-194

#### [4]

- **note**: 입력하던 주소를 그대로 둔 채 창만 닫는다.
- **type**: Button
- **label**: 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

#### [5]

- **note**: 실패 사실만 알리고 무엇이 틀렸는지 나누어 알리지 않는다. 시도가 몰린 경우에는 잠시 뒤 다시 시도하도록 안내한다.
- **type**: Alert
- **label**: 확인하지 못했습니다

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

- **description**: 관리자 패스워드를 받아 유효창을 연다. 이 화면에 진입할 때, 그리고 유효창이 끝난 뒤 저장을 눌렀을 때 열린다. 입력값은 가려 보이며 화면 상태·기록 어디에도 남기지 않는다. 자격증명을 주소에 담은 표기는 주소 입력에서도 받지 않으며, 그런 값이 들어오면 기록에 남기지 않는다. 확인에 실패하면 실패 사실만 알리고 무엇이 틀렸는지는 나누어 알리지 않는다. 짧은 시간에 시도가 몰리면 잠시 뒤 다시 시도하도록 안내한다. 저장을 이어서 수행하려고 열린 경우에는 확인을 마친 뒤 그 저장을 이어서 진행하고, 취소하면 입력하던 값을 그대로 둔 채 창만 닫는다 — 확인에 실패했다고 해서 입력한 주소를 버리지 않는다.

**references_apis**:

- API-194

**references_features**:

_(empty)_

## brownfield

### status

new

### decided_by

ADR-046

### change_kind

- capability-add

### diff_summary

연동 서버 주소를 운영 화면에서 교체하는 관리자 페이지 화면 신설

## surface_kind

web

## consumes_apis

- API-068
- API-069
- API-194
- API-226
- API-227
- API-228
- API-229
- API-230

## attached_files

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-156

### progress

100

### subtasks

_(empty)_

### last_updated

2026-09-01T14:54:51.760Z

### module_paths

_(empty)_

## required_roles

- ROLE-004

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-042/main.html
- **label**: 연동 서버 주소 관리 화면 — 와이어프레임
- **width**: 1440
- **surface**: page
- **platform**: web

**sections**:

_(empty)_

- **source_hash**: 6a10818b74d83f5999bd374a8776d2c080b62bcce01e391d1847391e37b2d030
- **generated_at**: 2026-09-08T00:15:22.310Z
- **generated_by**: generate-wireframes.py

**triggered_by**:

_(empty)_

## uses_constants

_(empty)_

## uses_components

_(empty)_

## external_designs

_(empty)_

## realizes_use_cases

_(empty)_

## covered_by_acceptances

- AC-1088
- AC-1089
- AC-1090
- AC-1091
- AC-1092
