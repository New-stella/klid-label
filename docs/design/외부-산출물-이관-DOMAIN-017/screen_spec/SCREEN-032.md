---
logicraft_item: SCREEN-032
type: screen_spec
version: 28
domain: DOMAIN-012
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-07T13:23:46.523Z
status: CHANGED
prev_version: 26
content_hash: fadecb31b26ccba3d3bdc8435ded1156353ab8d916c2190ec122e1ba5e4cd1ac
stale: false
raw: ./_raw/SCREEN-032.json
links:
  based_on: ["[[ADR-065]]"]
  belongs_to_domain: ["[[DOMAIN-012]]"]
  consumes: ["[[API-094]]", "[[API-109]]", "[[API-202]]", "[[API-207]]", "[[API-215]]"]
  implements: ["[[IMPREC-336]]"]
  realizes: ["[[UC-016]]", "[[UC-036]]"]
  references: ["[[API-094]]", "[[API-109]]", "[[API-207]]", "[[API-215]]"]
  requires: ["[[ROLE-001]]"]
  applies_to_backward: ["[[SHELL-001]]"]
  designs_backward: ["[[SD-021]]"]
  granted_on_backward: ["[[ROLE-001]]"]
  navigates_to_backward: ["[[NAV-001]]"]
  realizes_backward: ["[[MOD-006]]"]
  references_backward: ["[[UC-016]]", "[[UC-036]]"]
---

# 비식별 신고 관리 화면

## route

/manage/deident-reports

## title

비식별 신고 관리 화면

## device

desktop

## status

draft

## purpose

REVIEWER 전용(/manage/deident-reports). 라벨링·마킹 중 작업자가 개인정보 노출(비식별 누락)을 신고하면 영상이 잠기고(작업락 + DE_IDNTF_YN='F') 그 구간 동안 라벨 조회·저장·프레임 이미지·버전 diff·롤백·개인정보 메타 PUT 이 412 로 차단되고 스트리밍은 404 가 된다. ★라벨은 삭제되지 않으며(스냅샷도 남기지 않는다) 개인정보 3필드 판정도 보존된다. REVIEWER 는 본 화면에서 미처리 신고를 확인하고, 외부 솔루션으로 수동 비식별화를 완료한 뒤 '해소 처리'로 작업락을 해제한다(POST /v1/deident-reports/{rprtSn}/resolve). 해소로 'F'→'Y' 가 복원되면 게이트가 자동 해제되어 보존된 기존 라벨을 그대로 재사용한다(별도 복원 API 없음). 자동 재비식별 큐는 두지 않는다. ★목록은 '영상 #{rawSn}' 단위로 표시하며, 각 행에 신고 단계(마킹/라벨링/미상) 배지를 함께 표시한다 — 마킹 단계에서 접수된 신고인지 라벨링 단계에서 접수된 신고인지, 그 신고를 해소하면 무엇이 재개되는지를 REVIEWER 가 목록에서 바로 알 수 있게 한다. 서버는 신고 단계를 보유하며 해소 후 재개 지점이 그 단계로 갈린다. 접근: REVIEWER — 화면 진입 시점과 서버 요청 시점 양쪽에서 역할을 이중으로 검사한다.

★이 화면은 신고 해소와 함께, 산출물 가져오기로 들어온 영상의 검수 승인 보류를 푸는 자리도 겸한다. 원본이라고 지정해 들여온 영상에는 승인 보류가 서고 그 보류는 외부에서 비식별한 산출물의 위치를 사람이 알려 주어야 풀린다(POST /v1/videos/{rawSn}/deident-complete). 그 창구는 검수자만 호출할 수 있는데 그것을 부르는 자리가 관리자 전용 화면에만 있으면, 검수자는 자기가 들어갈 수 없는 화면에 자기 승인을 막는 열쇠를 두고 있는 셈이 된다. 비식별이 되지 않은 상태를 사람이 확인해 해소한다는 성질이 신고 해소와 같고 이 화면이 검수자 권한으로 열리므로, 검수자가 보류를 만나는 자리에서 바로 풀 수 있도록 그 진입점을 여기에 함께 둔다. 다만 두 축은 대상이 다르다 — 신고는 기존 파이프라인 영상의 마스킹 누락이고, 보류는 외부에서 원본으로 들여온 산출물이다. 그래서 한 목록에 섞지 않고 별도 목록으로 가른다.

## sections

### 페이지 헤더

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **type**: Heading
- **label**: 비식별 신고 관리

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: PageHeader. '비식별 신고 관리' 제목 + description('라벨링·마킹 중 신고된 비식별 누락 건을 확인하고 외부 수동 비식별화 완료 후 해소 처리합니다.').

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 신고 상태 필터 탭

- **role**: filter
- **layout**: stack

**components**:

#### [1]

- **type**: Custom
- **label**: 상태 탭 (미처리 / 처리완료)

**columns**:

_(empty)_

**options**:

- 미처리
- 처리완료

- **custom_name**: StatusTabs

#### [2]

- **type**: Custom
- **label**: 탭별 건수 배지

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: reports.totalElements
- **custom_name**: CountBadge

- **description**: 상태 탭 2종(미처리 기본 / 처리완료). 탭 전환 시 페이지가 처음으로 리셋되고 상태별 건수가 다시 조회된다. 각 탭 오른쪽에 해당 상태의 건수 배지.

**references_apis**:

- API-109

**references_features**:

_(empty)_

### 신고 목록 테이블

- **role**: main
- **layout**: list

**components**:

#### [1]

- **type**: Table
- **label**: 신고 목록

**columns**:

- 신고 번호
- 영상
- 신고자
- 사유
- 신고일시
- 신고 단계
- 상태
- 처리

**options**:

_(empty)_

#### [2]

- **note**: 단계별 툴팁으로 해소 시 무엇이 재개되는지 안내한다. 단계 기록이 없는 레거시 신고는 '미상'으로 표시되며 해소해도 자동 재개가 없다는 안내를 별도 툴팁으로 준다.
- **type**: Custom
- **label**: 신고 단계 배지 (마킹/라벨링/미상)

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: DeidentStageBadge

#### [3]

- **type**: Custom
- **label**: 신고 상태 배지 (미처리/처리완료)

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: DeidentStatusBadge

#### [4]

- **note**: 미처리 상태 행에만 노출. 클릭 시 비식별 산출물 선택 다이얼로그가 열리며, 거기서 고른 파일명을 실어 해소 처리를 요청한다. 서버 안전장치 — ①선택한 파일이 요청 시점에 다시 열거한 후보 목록에 있어야 하고 저장 서브트리 실경로·산출물 무결성·신고 시각 이후 수정 검증을 모두 통과해야 해소가 성립하며 실패 시 미처리 상태가 그대로 유지된다 ②동일 신고 2인 동시 해소 요청 시 하나만 성공하고 나머지는 실패로 처리된다(중복 실행 차단) ③성공한 요청만 작업락을 해제하고 선택한 산출물 경로를 비식별 처리 이력에 새 성공 행으로 적재한 뒤 신고 단계에 따라 재개 동작을 수행한다.
- **type**: Button
- **label**: 해소 처리

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-094

#### [5]

- **note**: totalPages>1 일 때 노출. 이전 · 페이지 번호 · 다음 순서로 배치하고, 페이지 번호는 양끝(첫·마지막)과 현재 앞뒤 1칸만 노출하며 그 사이는 말줄임으로 접는다. 페이지당 20건.
- **type**: Custom
- **label**: 페이지네이션

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: Pagination

#### [6]

- **type**: Alert
- **label**: 신고 목록을 불러올 수 없습니다
- **state**: error

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 신고 목록 테이블(신고 번호 · 영상 · 신고자 · 사유 · 신고일시 · 신고 단계 · 상태 · 처리). 영상은 '영상 #{rawSn}' 단위로 표시한다. 신고 단계 배지는 마킹(마킹 화면에서 접수)/라벨링(라벨링 화면에서 접수)/미상(이 기능 이전 접수, 기록 없음) 중 하나이며, 호버 시 해소하면 무엇이 재개되는지 안내 문구가 뜬다. 신고 상태 배지는 미처리/처리완료를 표시한다. 신고자 표시명은 페이지의 USER_NO 를 단일 IN 쿼리로 한 번에 해석한다(N+1 회피, 마스터에 없는 번호는 null). 미처리 행에만 '해소 처리' 버튼이 노출되며 해소 성공 시 그 영상의 스트림 메타 캐시가 커밋 후 무효화되고(외부 수동 재비식별로 비식별본이 교체됐을 수 있음) 신고 단계에 따라 재개 이벤트가 발행된다 — 마킹 단계 신고는 배치 단계를 되감고 활성 마킹을 종결해 재마킹을 열며, 라벨링 단계 신고는 프레임 이미지만 재추출해 라벨 좌표를 보존한 채 이어간다(단계 미상은 재개 이벤트 미발행). 목록 로딩 중에는 스켈레톤을, 탭별로 '미처리 신고가 없습니다'/'처리완료된 신고가 없습니다' 빈 상태 안내를 표시한다. 에러 시 ErrorState.

**references_apis**:

- API-094
- API-109

**references_features**:

_(empty)_

### 비식별 산출물 선택 다이얼로그

- **role**: modal
- **layout**: form

**components**:

#### [1]

- **type**: Heading
- **label**: 비식별 산출물 선택

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 각 후보는 파일명·크기·수정시각을 함께 보여준다. 내부 저장 경로는 표시하지 않는다. 현재 이력에 기록된 산출물에는 그 사실을 알리는 표시를 단다. 초기에는 아무것도 선택돼 있지 않다.
- **type**: Custom
- **label**: 산출물 후보 목록 (단일 선택)

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: DeidentArtifactCandidateList

#### [3]

- **note**: 후보 0건일 때만 노출. 외부 솔루션으로 비식별을 완료한 뒤 다시 시도하라고 안내하고 확인 조작은 비활성 상태로 둔다.
- **type**: Alert
- **label**: 선택할 수 있는 비식별 산출물이 없습니다

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **note**: 후보를 고르기 전에는 비활성. 선택 후 클릭하면 고른 파일명을 실어 해소를 요청하고 성공 시 토스트 + 목록 최신화 후 닫힌다. 서버 검증에 실패하면 다이얼로그를 연 채 실패를 알리고 신고는 미처리로 남는다.
- **type**: Button
- **label**: 해소 처리
- **state**: disabled

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-094

#### [5]

- **type**: Button
- **label**: 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

- **description**: '해소 처리'를 누르면 열리는 다이얼로그. 서버가 그 영상의 비식별 산출 디렉터리를 다시 열거해 만든 후보 목록을 보여주고 REVIEWER 가 하나를 고른다. 초기 상태는 아무것도 선택되지 않은 상태이며, 고르기 전에는 확인 조작을 할 수 없다 — 서버가 기본값을 고르지 않으므로 사람이 명시적으로 선택해야 한다. 각 후보는 파일명과 함께 크기·수정시각을 보여준다. 외부 비식별 솔루션이 같은 이름으로 덮어쓰지 않고 다른 이름으로 산출할 수 있어, 어느 것이 이번에 새로 만들어진 산출물인지 사람이 판단할 근거가 필요하기 때문이다. 현재 비식별 처리 이력에 기록된 산출물에는 그 사실을 알리는 표시를 달아 구분한다. 화면에는 내부 저장 경로를 표시하지 않고 파일명만 보여준다. 후보가 0건이면 확인 조작을 비활성화하고 외부 솔루션으로 비식별을 완료한 뒤 다시 시도하라는 안내를 표시한다. 선택한 파일이 서버 검증을 통과하지 못하면 해소가 성립하지 않고 신고는 미처리 상태로 남는다.

**references_apis**:

- API-094

**references_features**:

_(empty)_

### 이관 보류 영상 목록

- **role**: main
- **layout**: list

**components**:

#### [1]

- **type**: Heading
- **label**: 이관으로 들어온 승인 보류 영상

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Table
- **label**: 보류 영상 목록

**columns**:

- 가져온 시각
- 폴더명
- 영상 번호
- 승인 보류
- 프레임 수
- 실행자

- **io_attr**: O

**options**:

_(empty)_

#### [3]

- **note**: totalPages>1 일 때 노출. 신고 목록과 같은 규칙으로 페이지당 20건.
- **type**: Custom
- **label**: 페이지네이션

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: Pagination

#### [4]

- **note**: 승인 보류가 선 행에만 노출된다. 클릭하면 비식별 완료 기록 다이얼로그가 열린다.
- **type**: Button
- **label**: 비식별 완료 기록

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

#### [5]

- **type**: Alert
- **label**: 보류 영상 목록을 불러올 수 없습니다
- **state**: error

**columns**:

_(empty)_

**options**:

_(empty)_

**description**:

산출물 가져오기로 들어와 검수 승인 보류가 선 영상을 최근순으로 보여준다. 목록은 이관 이력에서 조달하며, 각 항목은 그 이관으로 만들어진 영상에 승인 보류가 서 있는지를 함께 싣는다. 이 값은 이관 상태와 다른 축이라 이관이 성공했는지로 대신 판단하지 않는다. 값은 보류·없음·미상 세 갈래이며, 비어 있는 것을 보류 아님으로 단정하지 않고 기록하는 자리를 연다 — 단정하면 그 자리가 감춰져 그 영상은 승인될 길을 잃는다.

이 목록은 위의 신고 목록과 대상이 다르다. 신고 목록의 한 행은 작업자가 접수한 신고 건이고, 여기의 한 행은 외부에서 원본이라고 지정해 들여온 영상이다. 신고 해소는 잠긴 영상의 작업락을 풀어 마킹·라벨링을 재개시키고, 여기의 기록은 검수 승인 보류만 푼다. 그래서 위의 상태 탭은 이 목록에 걸리지 않으며 두 목록을 한 표로 합치지 않는다.

보류가 선 행에만 '비식별 완료 기록' 자리가 열린다. 조회가 실패했을 때와 조회는 됐으나 보류가 선 영상이 한 건도 없을 때는 다른 안내로 가른다 — 한 문구로 묶으면 그런 영상이 없다는 뜻과 불러오지 못했다는 뜻이 구분되지 않기 때문이다.

**references_apis**:

- API-207

**references_features**:

_(empty)_

### 비식별 완료 기록 다이얼로그

- **role**: modal
- **layout**: form

**components**:

#### [1]

- **type**: Heading
- **label**: 비식별 완료 기록

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 허용된 저장소 범위 밖이거나 그 자리에 산출물이 실재하지 않으면 거부된다. 길이는 폴더경로명 표준 도메인 폭을 넘을 수 없다. 허용 저장소가 바로가기로 구성돼 있어도 폴더 탐색이 돌려준 위치를 그대로 붙여 넣을 수 있다.
- **type**: Input
- **label**: 비식별 산출물 폴더 경로

**columns**:

_(empty)_

- **io_attr**: I

**options**:

_(empty)_

- **placeholder**: 예: /nas-storage/handover/00000073-deid

#### [3]

- **note**: 경로를 적기 전에는 비활성. 이름이 맞는 파일이 한 건도 없으면 아무것도 기록하지 않고 거부하며 승인 보류는 그대로 남는다.
- **type**: Button
- **label**: 기록
- **state**: disabled

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-215

#### [4]

- **type**: Alert
- **label**: 기록 결과 — 승인 보류 해제 여부, 비식별 이미지를 채운 프레임 수, 이름이 맞는 파일이 없어 비워 둔 프레임 수

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

#### [5]

- **note**: 비워 둔 프레임이 하나라도 있을 때만 노출
- **type**: Alert
- **label**: 비워 둔 프레임이 남았다 — 그만큼의 프레임이 비식별 이미지 없이 남아 이 영상의 학습데이터 산출물에 빠진 채로 나간다.

**columns**:

_(empty)_

- **io_attr**: O

**options**:

_(empty)_

#### [6]

- **type**: Button
- **label**: 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

**description**:

'비식별 완료 기록'을 누르면 열리는 다이얼로그. 외부에서 이미 비식별한 산출물이 놓인 폴더의 위치를 사람이 적어 그 사실을 기록한다. 이 자리가 비식별을 수행하지는 않는다.

기록은 비식별 산출물이 실제로 존재하는지 확인한 뒤에만 성립한다. 적어 넣은 위치가 허용된 저장소 범위 밖이거나 그 자리에 산출물이 실재하지 않으면 아무것도 기록하지 않고 거부한다. 확인 없이 기록만 바꿀 수 있으면 처리되지 않은 산출물이 검수 승인을 통과하기 때문이다. 허용 저장소가 바로가기로 구성돼 있어도 폴더 탐색이 돌려준 위치를 그대로 붙여 넣어 기록을 요청할 수 있다.

확인을 통과하면 그 영상의 비식별 처리 이력에 행을 남기고 거기에 산출물의 위치를 적재한 뒤 검수 승인 보류를 푼다. 그리고 폴더의 파일을 프레임에 대응시켜 각 프레임의 비식별 이미지 위치까지 채운다. 대응은 파일 이름으로 하며 순서나 개수로 짐작해 잇지 않는다 — 짐작으로 이으면 다른 프레임의 비식별 이미지가 붙고 그것은 되돌릴 수 없다. 이름이 맞는 파일이 없는 프레임은 비워 둔 채로 두고 그 수를 결과로 알린다. 비워 둔 프레임이 하나라도 있으면 그만큼의 프레임이 비식별 이미지 없이 남아 그 영상의 학습데이터 산출물에 빠진 채로 나가므로 사람이 그 사실을 알아야 한다. 한 건도 잇지 못하면 그 폴더는 이 영상의 산출물이 아니므로 아무것도 기록하지 않고 거부하며 승인 보류는 그대로 남는다.

거부는 사유마다 다른 안내로 가른다. 허용 범위 밖이거나 산출물이 실재하지 않거나 이름이 맞는 파일이 한 건도 없거나 대상이 이관 경로로 들어온 영상이 아닌 경우는 뒤에 달라지지 않는 조건이라 다시 시도해도 결과가 같다. 이미 비식별 완료로 기록되어 풀 보류가 없는 경우는 다시 기록하지 않고 그 사실을 알린다. 기록 도중 실패한 경우에는 승인 보류가 그대로 남는다.

**references_apis**:

- API-215

**references_features**:

_(empty)_

## brownfield

### notes

### 2026-09-07 — 위치 입력 안내에 조건 명시
- 「비식별 완료 기록 다이얼로그」의 위치 입력 안내에 조건을 덧붙였다. 허용 저장소가 바로가기로 구성돼 있어도 폴더 탐색이 돌려준 위치를 그대로 붙여 넣어 기록을 요청할 수 있다. 본문 서술과 입력 부품 안내 두 자리에 같은 조건을 넣었다.
- 화면 서술 자리가 1000자 상한이라 본문에는 압축한 한 문장만 넣었다. 풀어 쓰면 이렇다 — 위치를 읽는 시작점이 허용 저장소의 표기와 실제 자리 둘 다로 인정되므로, 허용 저장소가 바로가기로 놓여 표기와 실제 자리가 서로 다른 형상에서도 폴더 탐색이 돌려준 위치가 그대로 통과한다. 그전에는 표기 그대로만 시작점으로 인정되어 허용 저장소 루트 바로 아래에서 한 걸음도 내려가지 못했다.
- 기존에 허용 범위 축을 서술하던 세 자리(입력 부품 안내 · 본문의 기록 성립 조건 · 거부 사유 안내)는 하나도 지우지 않고 그대로 두었다. 조건만 더했다.
- brownfield.decided_by 에 ADR-065 를 넣었다. 그 자리는 그전까지 비어 있었으므로 교체되거나 폐기된 기존 결정은 없다.
- 검수 승인 보류 해제·비식별 완료 판정·폴더 파일과 프레임의 대응 규칙은 다른 축이라 건드리지 않았다. 신고 목록과 비식별 산출물 선택 다이얼로그도 대상이 아니다.
- 구현 상태는 올리지 않았다. 코드는 아직 이 조건을 따르지 않는다.


### status

new

### decided_by

ADR-065

### diff_summary

비식별 누락 신고 관리(수동 흐름) — REVIEWER 가 OPEN 신고를 외부 수동 비식별화 후 해소 처리하는 화면. v2 신규.

## surface_kind

web

## consumes_apis

- API-094
- API-109
- API-202
- API-207
- API-215

## attached_files

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-336

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-29T01:25:14.226Z

### module_paths

_(empty)_

## required_roles

- ROLE-001

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-032/main.html
- **label**: 비식별 신고 관리 화면 — 와이어프레임
- **width**: 1440
- **surface**: page

**sections**:

_(empty)_

- **description**: 
- **source_hash**: 17955cff4555e4230d1a7d0f8b814f247654af1442043a23736dd95a7695ec0f
- **generated_at**: 2026-09-07T11:48:15.195Z
- **generated_by**: sections-deterministic-generator

**triggered_by**:

_(empty)_

## uses_constants

_(empty)_

## uses_components

_(empty)_

## external_designs

_(empty)_

## realizes_use_cases

- UC-016
- UC-036

## covered_by_acceptances

_(empty)_
