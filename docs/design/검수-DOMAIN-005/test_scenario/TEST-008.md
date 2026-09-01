---
logicraft_item: TEST-008
type: test_scenario
version: 3
domain: DOMAIN-017
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-01T07:14:23.184Z
status: CHANGED
prev_version: 1
content_hash: 7d09690df02f3eb4808e9ab1b72748e493197eb093486a57420733b6563ee243
stale: true
raw: ./_raw/TEST-008.json
links:
  belongs_to_domain: ["[[DOMAIN-017]]"]
  references: ["[[API-206]]", "[[API-207]]", "[[API-215]]", "[[DOMAIN-005]]", "[[DOMAIN-012]]", "[[DOMAIN-017]]", "[[SCREEN-039]]", "[[UC-035]]"]
---

# 영상 없이 받은 원본 산출물을 외부 비식별 뒤 승인까지 잇는다

## kind

integration

## notes

영상 파일을 함께 받은 경우는 저작도구가 스스로 비식별을 수행하므로 이 시나리오의 대상이 아니다. 두 경우가 갈리는 지점은 단위 시험이 따로 고정한다. 이 흐름에 비식별 누락 신고는 쓰이지 않는다 — 신고는 비식별을 한 번이라도 수행한 영상만 접수하므로, 아직 한 적 없는 이 영상은 애초에 그 통로에 들어가지 못한다.

## steps

### [1]

- **seq**: 1
- **note**: 원본이라고 지정하는 자리는 이 갈래에만 있다
- **action**: 산출물 종류가 라벨링 완료인지 확인한다
- **expected**: 기본값이 라벨링 완료라 화면에 들어오면 이미 골라져 있고, 이 산출물이 원본인지 비식별이 끝난 것인지 고르는 자리가 나온다
- **test_item**: 라벨링 완료 갈래의 입력 항목이 나오는지
- **input_data**: 없음
- **screen_ref**: SCREEN-039
- **preconditions**: 가져오기 화면에 들어와 있다

### [2]

- **seq**: 2
- **note**: 대상이 없는데 실행시키면 고칠 것이 없는 실패만 쌓인다
- **action**: 원본이라고 지정해 적재한다
- **expected**: 영상과 프레임이 만들어지고 승인 보류가 선다. 비식별할 대상 영상이 없으므로 비식별 단계는 실행되지 않는다
- **test_item**: 승인 보류가 서는지, 비식별 단계가 실행되지 않는지
- **input_data**: 산출물 폴더 경로, 원본으로 지정
- **screen_ref**: SCREEN-039
- **preconditions**: 영상 파일이 없는 산출물

### [3]

- **seq**: 3
- **note**: 보류가 막는 것은 승인 하나다
- **action**: 적재된 영상의 검수를 승인해 본다
- **expected**: 승인이 거부된다. 라벨 조회와 프레임 이미지는 막히지 않는다 — 그것까지 막으면 검수 자체가 성립하지 않는다
- **test_item**: 보류가 승인을 실제로 막는지
- **input_data**: 없음
- **preconditions**: 2단계 완료

### [4]

- **seq**: 4
- **note**: 확인 없이 기록하면 처리되지 않은 산출물이 승인을 통과한다
- **action**: 실재하지 않는 위치를 비식별 산출물로 제출해 본다
- **expected**: 거부되고 아무것도 기록되지 않는다. 승인 보류가 그대로 남는다
- **test_item**: 확인 없이 기록만 바꾸는 경로가 없는지
- **input_data**: 산출물이 없는 위치
- **preconditions**: 2단계 완료

### [5]

- **seq**: 5
- **note**: 이름이 맞는 파일이 한 건도 없으면 기록하지 않고 보류를 유지한다
- **action**: 외부에서 비식별한 산출물의 위치를 제출한다
- **expected**: 산출물 실재를 확인한 뒤 비식별 처리 이력이 남고 프레임의 비식별 경로가 채워지며 승인 보류가 풀린다. 이름이 맞지 않아 채우지 못한 프레임이 있으면 그 수가 응답에 나온다
- **test_item**: 산출물 실재 확인 후 프레임까지 이어지고 보류가 풀리는지
- **input_data**: 비식별 산출물 위치
- **preconditions**: 외부 비식별을 마쳐 산출물이 허용 범위 안에 놓여 있다

### [6]

- **seq**: 6
- **action**: 다시 검수를 승인한다
- **expected**: 승인이 통과하고 학습데이터 산출이 성공으로 마감된다
- **test_item**: 보류가 풀린 뒤 승인이 통과하는지
- **input_data**: 없음
- **preconditions**: 5단계 완료

### [7]

- **seq**: 7
- **note**: 두 값이 한 축이었다면 이 변화를 표현할 수 없다
- **action**: 이관 이력에서 보류 여부를 확인한다
- **expected**: 이관 상태는 처음부터 성공이었고 보류 여부만 해제로 바뀌어 있다
- **test_item**: 승인 보류 여부가 이관 상태와 별개로 갱신되는지
- **input_data**: 없음
- **screen_ref**: SCREEN-039
- **preconditions**: 6단계 완료

## status

draft

## objective

원본이라고 지정해 가져왔는데 영상 파일이 없어 저작도구가 스스로 비식별할 수 없는 경우, 승인 보류가 서고 외부에서 비식별한 산출물을 기록해야만 보류가 풀려 승인까지 이어지는지 확인한다.

## related_apis

- API-206
- API-215
- API-207

## preconditions

- 관리자로 인증되어 있다
- 분류 대응이 이미 확정돼 적재를 막는 사유가 없다
- 산출물에 프레임 이미지는 있으나 영상 파일은 들어 있지 않다

## verifies_nfrs

_(empty)_

## attached_files

_(empty)_

## related_domains

- DOMAIN-017
- DOMAIN-012
- DOMAIN-005

## covers_use_cases

- UC-035

## exercises_screens

- SCREEN-039

## verifies_requirements

_(empty)_
