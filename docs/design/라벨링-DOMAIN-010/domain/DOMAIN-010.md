---
logicraft_item: DOMAIN-010
type: domain
version: 10
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:54.248Z
status: NEW
prev_version: null
content_hash: 4e8733b5a5bd47df320fd9761e39b05b947973b637b589fcf765a6801bc01880
stale: true
raw: ./_raw/DOMAIN-010.json
links:
  collaborates_with: ["[[DOMAIN-003]]", "[[DOMAIN-004]]", "[[DOMAIN-005]]"]
  migrated_from: ["[[LEGACY-004]]"]
  applies_to_backward: ["[[NFR-008]]", "[[NFR-009]]", "[[NFR-010]]", "[[NFR-011]]", "[[NFR-015]]", "[[NFR-016]]", "[[NFR-018]]", "[[NFR-019]]"]
  belongs_to_domain_backward: ["[[ADR-009]]", "[[ADR-032]]", "[[ADR-033]]", "[[ADR-034]]", "[[ADR-036]]", "[[API-018]]", "[[API-019]]", "[[API-021]]", "[[API-022]]", "[[API-023]]", "[[API-024]]", "[[API-025]]", "[[API-026]]", "[[API-027]]", "[[API-028]]", "[[API-029]]", "[[API-030]]", "[[API-031]]", "[[API-032]]", "[[API-034]]", "[[API-035]]", "[[API-036]]", "[[API-037]]", "[[API-038]]", "[[API-039]]", "[[API-040]]", "[[API-041]]", "[[API-066]]", "[[API-067]]", "[[API-117]]", "[[API-125]]", "[[API-126]]", "[[API-127]]", "[[API-128]]", "[[API-129]]", "[[API-132]]", "[[API-133]]", "[[API-134]]", "[[API-135]]", "[[API-175]]", "[[API-176]]", "[[API-182]]", "[[API-195]]", "[[API-196]]", "[[API-197]]", "[[CDIAG-004]]", "[[CDIAG-015]]", "[[CMP-004]]", "[[CMP-006]]", "[[DFEAT-012]]", "[[DFEAT-014]]", "[[DFEAT-015]]", "[[DFEAT-016]]", "[[DFEAT-017]]", "[[DFEAT-050]]", "[[DFEAT-051]]", "[[DFEAT-052]]", "[[ERD-010]]", "[[ERD-019]]", "[[SCREEN-005]]", "[[SCREEN-010]]", "[[SCREEN-026]]", "[[SCREEN-035]]", "[[SD-002]]", "[[SD-006]]", "[[SEQ-020]]", "[[SEQ-021]]", "[[SEQ-022]]", "[[UC-007]]", "[[UC-008]]", "[[UC-021]]", "[[UC-028]]", "[[UC-032]]"]
  collaborates_with_backward: ["[[DOMAIN-003]]", "[[DOMAIN-004]]", "[[DOMAIN-005]]", "[[DOMAIN-007]]"]
  derived_domain_backward: ["[[AC-017]]", "[[AC-020]]", "[[AC-021]]", "[[AC-023]]", "[[AC-024]]"]
  implements_in_backward: ["[[MOD-006]]", "[[MOD-007]]", "[[MOD-008]]", "[[MOD-021]]", "[[MOD-028]]", "[[MOD-031]]", "[[MOD-032]]", "[[MOD-033]]", "[[MOD-043]]", "[[MOD-047]]"]
  references_backward: ["[[TEST-002]]", "[[TEST-004]]"]
---

# 라벨링

## name

라벨링

## brownfield

### notes

1차 핵심 도메인(라벨링 도구). Pass 2에서 konva 캔버스 재구현 modified

### status

preserved

### legacy_source

#### type

module

#### legacy_artifact_id

LEGACY-004

## description

비식별 프레임 위에 객체를 표시해 학습데이터를 만드는 핵심 도메인. 캔버스는 konva.js 기반이다.

[라벨 형태] 바운딩박스·폴리곤·세그멘테이션·스켈레톤(COCO-17 키포인트)·SAM2 분할(클릭·박스 프롬프트)·SAM2 Track(박스→N프레임 추적 전파).

[도구] 캔버스 도구(이동·회전·확대·그리드·밝기·투명도), 객체·메타·이슈 탭, 단축키, 전체 복사/붙여넣기, 프레임 설명, 트랙 편집(병합·분할·삭제)과 선형보간.

[★비식별은 이 도메인 밖이다] 구 본문의 '블러(비식별) 처리 — 다른 라벨링 전 반드시 먼저 수행'은 폐기됐다(ADR-006). 비식별은 외부 솔루션이 파이프라인 선두에서 자동 수행하며, 라벨러는 이미 비식별된 프레임을 본다. 라벨러의 잔존 책임은 누락 발견 시 신고뿐이다.

[★2계층 저장 — 혼동 금지] ①작업 임시저장: 라벨러 저장 시 현재 작업본을 LS_DATA_LBL 에 full-replace 로 영속하고 되돌리기는 FE undo/redo(세션)로 처리한다. 학습데이터 버전이 아니다. ②학습데이터 버전: 검수 승인 시점에만 스냅샷을 LS_LABEL_VERSION 에 쌓는다 — 승인은 영상 1건을 한 트랜잭션으로 확정하지만 행은 라벨을 가진 프레임마다 1건씩이며, 같은 스냅샷인지는 프레임과 페이로드 해시의 짝으로 식별한다. 롤백은 새 버전을 적층하지 않고 대상 스냅샷 행을 재활성화하며, 라벨 본문을 작업본으로 복원하되 LBL_SN·AI 메타·TRCK_ID 까지 보존한다(PK 재발급하면 diff 가 '전량 교체'로 오분류된다).

[라벨 마스터 단일 진실원] 수동 라벨 선택은 라벨 마스터(LS_LABEL) 전체를 쓰고, 프리셋은 오토라벨 전용이다. 프리셋은 라벨명·형태를 스냅샷하지 않고 LBL_ID 로 마스터를 실시간 join 한다.

[★신고 구간 차단] 비식별 누락 신고(DE_IDNTF_YN='F') 중인 영상은 라벨 조회·이력·버전 diff/롤백이 412 로 차단된다(역할 무관). 라벨 좌표 자체가 PII 위치 특정 정보라 스트리밍만 막는 것으로는 부족하기 때문이다. 라벨은 삭제하지 않고 보존하며 해소 시 그대로 재사용한다.

## upstream_of

_(empty)_

## context_kind

core

## collaborators

- DOMAIN-004
- DOMAIN-005
- DOMAIN-003

## integrates_with

_(empty)_

## uses_integrations

_(empty)_

## ubiquitous_language

### [1]

- **term**: 바운딩박스
- **meaning**: 2회 클릭으로 완성하는 객체 탐지용 사각 영역

### [2]

- **term**: 폴리곤
- **meaning**: 다점 클릭으로 그리는 정밀 객체 영역 분할

### [3]

- **term**: 스켈레톤
- **meaning**: 사람 관절 포즈 탐지용 키포인트 집합(COCO-17)

### [4]

- **term**: 트랙(TRCK_ID)
- **meaning**: 연속 프레임을 가로지르는 동일 객체의 식별자. 병합·분할·삭제 편집이 가능하다

### [5]

- **term**: 작업 임시저장
- **meaning**: LS_DATA_LBL 에 full-replace 로 영속되는 작업본. 학습데이터 버전이 아니다

### [6]

- **term**: 학습데이터 버전
- **meaning**: 검수 승인 시점에 만들어지는 영상 단위 전체 스냅샷(LS_LABEL_VERSION)

### [7]

- **term**: 객체 탭
- **meaning**: 우측 패널의 라벨 변경·속성·잠금·뷰어·삭제·복사 관리
