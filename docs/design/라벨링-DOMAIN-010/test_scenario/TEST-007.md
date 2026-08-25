---
logicraft_item: TEST-007
type: test_scenario
version: 1
domain: DOMAIN-017
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-25T10:53:45.614Z
status: NEW
prev_version: null
content_hash: e3651142c847a7958c24e25ff1fd3fefe713ddcb746ee2b2189b698134c80d65
stale: false
raw: ./_raw/TEST-007.json
links:
  belongs_to_domain: ["[[DOMAIN-017]]"]
  references: ["[[API-205]]", "[[API-206]]", "[[API-207]]", "[[API-208]]", "[[API-209]]", "[[API-210]]", "[[DOMAIN-005]]", "[[DOMAIN-010]]", "[[DOMAIN-017]]", "[[SCREEN-039]]", "[[UC-035]]", "[[UC-036]]"]
---

# 외부 산출물을 검사해 적재하고 검수 승인까지 잇는다

## kind

integration

## notes

이 시나리오는 정상 흐름 하나를 끝까지 잇는 것이 목적이라, 거부되는 경로는 다루지 않는다. 경로 거부·중복 반입·미대응 잔존 같은 축은 단위 시험이 각각 맡는다. 비식별이 갈리는 축은 별도 시나리오가 맡는다.

## steps

### [1]

- **seq**: 1
- **note**: 이 단계는 DB 와 폴더에 아무 변화도 남기지 않는다
- **action**: 외부 산출물 폴더를 검사한다
- **expected**: 프레임 수와 라벨 수가 문서 선언값이 아니라 실제 파일 기준으로 돌아온다. 짝이 없거나 선언 수가 다른 것은 경고로만 담기고 적재 가능 여부는 따로 돌아온다
- **test_item**: 검사가 실제 파일 기준 건수와 경고, 미확정 분류를 함께 돌려주는지
- **input_data**: 산출물 폴더 경로
- **screen_ref**: SCREEN-039
- **preconditions**: 산출물 폴더가 허용 범위 안에 있다

### [2]

- **seq**: 2
- **note**: 추측해서 채우면 다른 분류로 적재된다
- **action**: 검사 결과에서 대응이 정해지지 않은 분류를 확인한다
- **expected**: 미확정 분류 목록이 나오고, 이름이 정확히 같은 라벨이 하나뿐인 분류에는 그 라벨이 추천으로 붙는다. 같은 이름 후보가 둘 이상이면 추천이 비어 있다
- **test_item**: 미확정 분류가 추천 후보와 함께 나오는지
- **input_data**: 없음
- **screen_ref**: SCREEN-039
- **preconditions**: 1단계 완료

### [3]

- **seq**: 3
- **action**: 분류 대응을 확정한다
- **expected**: 두 종류가 함께 저장된다. 한 건이라도 거부되면 같은 요청의 다른 건도 저장되지 않는다
- **test_item**: 라벨 대응과 이벤트 유형 대응이 한 요청으로 저장되는지
- **input_data**: 분류별 연결 대상
- **screen_ref**: SCREEN-039
- **preconditions**: 2단계에서 확인한 미확정 분류

### [4]

- **seq**: 4
- **note**: 매번 다시 확정해야 한다면 대응을 저장하는 의미가 없다
- **action**: 같은 폴더를 다시 검사한다
- **expected**: 미확정 분류가 남아 있지 않고 적재 가능으로 돌아온다
- **test_item**: 확정한 대응이 다음 검사에 반영되는지
- **input_data**: 같은 폴더 경로
- **screen_ref**: SCREEN-039
- **preconditions**: 3단계 완료

### [5]

- **seq**: 5
- **note**: 두 수가 다르면 사용자는 유실로 읽는다
- **action**: 산출물을 적재한다
- **expected**: 영상 한 건과 실제 파일 기준 프레임이 만들어지고 검수 대기 상태가 된다. 적재된 프레임 수와 라벨 수가 1단계 검사가 돌려준 수와 같다
- **test_item**: 검수 대기 영상과 프레임이 만들어지는지, 검사가 약속한 수와 같은지
- **input_data**: 산출물 폴더 경로, 비식별이 끝난 것으로 지정
- **screen_ref**: SCREEN-039
- **preconditions**: 4단계에서 적재 가능

### [6]

- **seq**: 6
- **note**: 이 경로에는 라벨링 작업 단계 자체가 없다
- **action**: 검수 목록에서 적재된 영상을 연다
- **expected**: 작업자 배정 없이 검수 목록에 나타나고 검수를 시작할 수 있다. 프레임 이미지와 라벨이 열린다
- **test_item**: 배정 절차 없이 검수를 시작할 수 있는지
- **input_data**: 없음
- **preconditions**: 5단계 완료

### [7]

- **seq**: 7
- **action**: 검수를 승인한다
- **expected**: 승인이 통과하고 학습데이터 산출이 성공으로 마감된다
- **test_item**: 학습데이터 산출이 성공으로 마감되는지
- **input_data**: 없음
- **preconditions**: 6단계 완료

### [8]

- **seq**: 8
- **note**: 두 값은 다른 축이라 한 값으로 합칠 수 없다
- **action**: 이관 이력 목록을 조회한다
- **expected**: 최근순 목록의 첫 쪽에 이번 이관이 성공으로 담긴다. 이관 상태와 승인 보류 여부가 각각 돌아온다
- **test_item**: 이번 이관이 성공 이력으로 남는지
- **input_data**: 없음
- **screen_ref**: SCREEN-039
- **preconditions**: 5단계 완료

### [9]

- **seq**: 9
- **action**: 이관 이력 상세를 연다
- **expected**: 산출물 경로와 결과가 함께 돌아온다
- **test_item**: 경로와 결과가 함께 나오는지
- **input_data**: 이력 식별자
- **screen_ref**: SCREEN-039
- **preconditions**: 8단계에서 고른 이력

## status

draft

## objective

외부에서 받은 1차 어노테이션 산출물을 폴더 검사로 미리 보고, 미확정 분류를 대응시킨 뒤 적재해, 배정 없이 검수를 시작하고 승인까지 마치는 흐름 전체가 이어지는지 확인한다. 검사가 약속한 수와 실제로 적재된 수가 같은지도 이 흐름에서 함께 본다.

## related_apis

- API-205
- API-209
- API-210
- API-206
- API-207
- API-208

## preconditions

- 검수자로 인증되어 있다
- 허용된 저장소 범위 안에 외부 산출물 폴더가 놓여 있다
- 산출물이 쓰는 분류 이름에 대응할 라벨 마스터와 이벤트 유형이 등록돼 있다

## verifies_nfrs

_(empty)_

## related_domains

- DOMAIN-017
- DOMAIN-005
- DOMAIN-010

## covers_use_cases

- UC-035
- UC-036

## exercises_screens

- SCREEN-039

## verifies_requirements

_(empty)_
