---
logicraft_item: DFEAT-024
type: domain_feature
version: 17
domain: DOMAIN-005
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-15T13:21:55.598Z
status: CHANGED
prev_version: 14
content_hash: 1f3238ec48b2d4c07e4513f1bed75663b0f855e11fb0195e5e385f3ccec5a218
stale: true
raw: ./_raw/DFEAT-024.json
links:
  based_on: ["[[ADR-060]]"]
  belongs_to_domain: ["[[DOMAIN-005]]"]
  implements: ["[[API-014]]", "[[API-015]]", "[[API-250]]", "[[IMPREC-358]]"]
  migrated_from: ["[[LEGACY-078]]"]
  specializes: ["[[FEAT-008]]"]
  triggers: ["[[EVT-006]]"]
  verifies: ["[[AC-1040]]", "[[AC-1041]]", "[[AC-1042]]", "[[AC-1113]]", "[[AC-1114]]", "[[AC-1115]]"]
  depicts_backward: ["[[CDIAG-006]]", "[[CMP-005]]"]
  realizes_backward: ["[[UC-023]]"]
  references_backward: ["[[ADR-060]]"]
---

# 승인·반려

## title

승인·반려

## status

designed

## consumes

_(empty)_

## priority

must

## triggers

- EVT-006

## brownfield

### status

modified

### decided_by

ADR-060

### change_kind

- actor-change

### diff_summary

1차 승인/반려/관리자 확인 요청 → 2차 관리자 역할 폐기(REVIEWER 흡수)로 검수자 확인으로 변경

### legacy_source

#### repo

KLID-AI-PF-001

#### type

screen

#### identifier

SKKLID-UI-02-02-17

#### legacy_artifact_id

LEGACY-078

## user_story

### as

검수자

### i_want

제출 데이터를 승인 또는 반려하기를

### so_that

검수 결과를 확정하고 재작업을 지시한다

## description

검수자가 제출된 데이터를 승인 또는 반려한다. 관리자 확인 요청은 이 기능에 두지 않는다 — 검수는 검수자 1인의 판단으로 종결한다. ⚠ 당초 근거였던 '별도 ADMIN 역할이 없어 확인을 요청할 상대가 없다'(ADR-003)는 전제는 무효다 — ADR-055 가 관리자 역할을 신설해 그 결정을 뒤집었고, 관리자는 검수자 권한을 계층으로 물려받는다. 전제가 무효가 된 것과 기능을 되살리는 것은 다른 축이라 '관리자 확인 요청'을 두지 않는다는 결론은 그대로 유지한다. 재도입 여부는 ADR-060 이 결정했다 — 관리자 역할이 신설된 뒤에도 「관리자 확인 요청」 창구를 두지 않는다. 가장 큰 근거는 겸직이다 — 관리자는 검수자 권한을 계층으로 물려받아 검수·배정을 그대로 수행하므로 검수 판단에 관한 한 올려보낼 위가 없다. 즉 이 창구는 대체된 것이 아니라 성립하지 않는다. 검수자와 작업자 사이의 확인·문의 소통 축은 이슈 스레드(DFEAT-049)가 담당한다. (1차 baseline, 화면 SKKLID-UI-02-02-17)

승인에는 전제조건이 있다 — 검수 워크플로 상태의 비식별화완료여부가 완료여야 한다. 미완료이면 승인 요청은 거부된다. 이 판정은 상태 전이보다 먼저 이뤄지므로, 거부된 요청에서는 상태 전이도 라벨 버전 스냅샷도 학습데이터 산출물 생성도 관제 통지도 일어나지 않는다. 비식별화완료여부는 기본값이 완료다 — 외부 산출물 이관 경로로 원본이라고 지정해 들어온 영상만 미완료로 시작하며, 그 경로와 무관한 기존 영상이 이 전제조건 때문에 막히지 않게 하기 위함이다. 이 전제조건이 막는 것은 검수 승인뿐이다 — 라벨 조회·프레임 이미지·영상 스트리밍은 이 값으로 닫지 않고, 학습데이터 산출물 생성 경로 자체도 이 값으로 닫지 않는다(승인이 거부되면 산출물이 생기지 않는 것은 승인이 그 방아쇠이기 때문이지 산출 경로가 닫혀서가 아니다). 그 통로들을 함께 닫는 것은 비식별 누락 신고 구간의 차단이며, 이 전제조건과는 별개 축이다.

검수 시작·승인·반려 이력에는 행위자와 함께 그 행위를 한 시점의 역할이 남는다. 조회 시점에 현재 역할을 다시 읽는 방식이 아니라서 그 사람의 역할이 나중에 바뀌어도 과거 행위의 역할은 그대로다 — 관리자가 승인한 건은 관리자로 남는다. 역할 칸이 생기기 전에 쌓인 이력은 역할을 복원할 수 없어 비어 있을 수 있으며 지어내 채우지 않는다.

승인·반려는 검수 점유를 푸는 자리이기도 하다 — 다만 해제를 따로 수행하지 않는다. 점유 판정이 「최신 검수 시작 기록보다 뒤에 종결 기록이 없을 것」을 조건으로 삼으므로, 승인·반려가 자기 기록을 남기는 것만으로 점유가 저절로 풀린다.

여러 건을 한 번에 처리하는 창구는 승인에만 둔다(API-250). 반려는 건마다 사유가 달라 한 번에 묶을 수 없으므로 일괄 창구를 두지 않는다. 일괄 승인의 자격은 유효 점유의 주인이 요청자 본인이고 단건 승인이 허용하는 상태인 건으로 한정하며, 건수 상한을 두고 부분 실패를 허용한다. 성공한 건의 결과는 단건 승인과 완전히 같다 — 라벨 스냅샷 동결·메타 동결·학습데이터 산출 폴더 재생성·관제 통지·재검토 필요 표시 해제 가운데 하나도 빠지지 않는다. 승인 한 건이 산출 전량 재생성과 외부 통지를 연쇄로 일으키므로 여러 건의 연쇄가 한꺼번에 몰리지 않도록 뒤에서 줄 세운다.

근거는 ADR-067.

## invokes_apis

_(empty)_

## attached_files

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-358

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-29T01:26:20.690Z

### module_paths

_(empty)_

## uses_constants

_(empty)_

## acceptance_rules

### [1]

- **then**: 승인이 거부된다. 판정이 상태 전이보다 먼저 이뤄지므로 상태 전이·라벨 버전 스냅샷·학습데이터 산출물·관제 통지가 하나도 만들어지지 않는다
- **when**: 검수자가 그 영상의 검수 승인을 요청한다
- **given**: 외부 산출물 이관 경로로 원본이라고 지정해 들어온 영상이라, 검수 워크플로 상태의 비식별화완료여부가 미완료다

### [2]

- **then**: 이 전제조건은 승인을 막지 않는다
- **when**: 검수자가 그 영상의 검수 승인을 요청한다
- **given**: 이 이관 경로와 무관하게 적재된 영상이라, 비식별화완료여부가 기본값인 완료다

### [3]

- **then**: 이 전제조건은 그 통로들을 닫지 않는다. 그 통로들을 함께 닫는 것은 비식별 누락 신고 구간의 차단이며 별개 축이다
- **when**: 라벨 조회·프레임 이미지·영상 스트리밍·학습데이터 산출물 생성을 시도한다
- **given**: 비식별화완료여부가 미완료인 영상이다

## persists_in_tables

- LS_RAW_DATA_STATUS
- LS_DATA_ISSUE

## related_acceptances

- AC-1040
- AC-1041
- AC-1042
- AC-1113
- AC-1114
- AC-1115

## specializes_feature

FEAT-008

## implemented_by_endpoints

- API-014
- API-015
- API-250

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
