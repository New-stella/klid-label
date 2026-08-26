---
logicraft_item: FEAT-004
type: feature
version: 10
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-26T05:49:05.313Z
status: CHANGED
prev_version: 9
content_hash: 1abbab3cbf31711e01e3d9cf2ff1e97a3c632bde568cbb1c644361216b5a46af
stale: true
raw: ./_raw/FEAT-004.json
links:
  based_on: ["[[ADR-004]]", "[[ADR-018]]"]
  implements: ["[[REQ-001]]", "[[REQ-003]]", "[[REQ-004]]", "[[REQ-005]]", "[[REQ-020]]", "[[REQ-023]]"]
  migrated_from: ["[[LEGACY-015]]"]
  covers_backward: ["[[AC-018]]", "[[AC-021]]"]
  granted_on_backward: ["[[ROLE-001]]"]
  implements_backward: ["[[API-059]]", "[[API-060]]", "[[API-061]]", "[[API-062]]", "[[API-063]]", "[[API-092]]", "[[API-165]]", "[[API-179]]", "[[API-188]]", "[[API-189]]", "[[API-190]]"]
  realizes_backward: ["[[MOD-013]]", "[[MOD-018]]", "[[UC-001]]", "[[UC-002]]", "[[UC-003]]", "[[UC-010]]"]
  references_backward: ["[[SCREEN-022]]", "[[SCREEN-023]]"]
  specializes_backward: ["[[DFEAT-029]]"]
---

# 영상 증강 연동·검수 + 해상도 변경

## priority

should

## main_flow

### [1]

- **step**: 1
- **action**: 외부 증강 영상(WINTER/NIGHT/RAIN)을 원본과 연결된 새 영상으로 수신·등록한다

### [2]

- **step**: 2
- **action**: 원본의 라벨 정보를 새 영상에 복사·매핑하고 좌표를 보존한다(해상도 동일)

### [3]

- **step**: 3
- **action**: 해상도 변경은 표준 해상도 프리셋마다 원본을 참조하는 새 파생영상을 만들고, 비디오는 원본(비식별)을 복사한 채 프레임 이미지만 종횡비를 보존한 균일 배율로 리스케일하며 라벨 좌표를 같은 배율로 재계산해 적재한다(업스케일 허용·패딩 없음)

### [4]

- **step**: 4
- **action**: 증강 새 영상은 미검수 상태로 들어온다

### [5]

- **step**: 5
- **actor**: 검수자
- **action**: 검수자가 내용을 확인해 학습데이터 활용을 승인/반려한다

## brownfield

### status

modified

### decided_by

ADR-004

### change_kind

- capability-add
- component-replace

### diff_summary

1차 증강 5종(밝기/어둡기/반전) → 2차 외부 증강 연동·결과 검수 + 해상도 변경 (V1.5/V2.0, SFR-07)

### legacy_source

#### type

table

#### identifier

LS_DATA_AUG

#### legacy_artifact_id

LEGACY-015

## complexity

complex

## user_story

### goal

외부에서 증강된 영상을 받아 라벨이 잘 보존됐는지 확인하고 학습데이터로 쓸지 결정하기를 원한다

### actor

검수자

### benefit

증강 데이터를 안전하게 학습데이터로 편입할 수 있다

## description

외부 증강 시스템이 만든 새 영상을 원본과 연결해 수신하고, 원본 라벨을 복사·매핑하여 무결성을 보존하며, 미검수 상태로 시작해 검수 승인/반려를 거친다. 해상도 변경은 저작도구가 직접 수행하며, 표준 해상도 프리셋마다 원본을 참조하는 새 파생영상을 만든다. 비디오 파일은 원본(비식별)을 복사하고 재인코딩하지 않으며 프레임 이미지만 리스케일한다. 프리셋의 두 수치는 산출 프레임의 고정 크기가 아니라 크기 상한이며, 원본의 짧은 변을 프리셋의 짧은 값에 맞추는 배율을 구하되 그 배율로 계산한 긴 변이 프리셋의 긴 값을 넘으면 그때만 긴 변이 상한에 맞도록 배율을 낮춘다. 가로·세로에 같은 배율을 적용해 종횡비를 보존하고 남는 영역을 채우는 패딩을 두지 않는다. 업스케일(확대)도 허용하며, 라벨 좌표는 그 균일 배율의 곱셈으로 재계산해 적재한다.

## based_on_adrs

- ADR-018

## business_rules

- 증강 결과는 원본과 다른 새 영상으로 등록하며 원본을 참조한다
- 프롬프트·생성 본체는 외부 시스템 책임으로 범위 외
- 해상도 변경도 표준 해상도 프리셋마다 원본을 참조하는 새 파생영상을 만든다. 비디오 파일은 원본(비식별)을 복사하며 재인코딩하지 않고 프레임 이미지만 리스케일한다
- 표준 해상도 프리셋의 두 수치는 산출 프레임의 고정 크기가 아니라 상한이며, 산출 프레임 크기는 원본 종횡비에 따라 달라진다
- 리스케일 배율은 원본의 짧은 변을 프리셋의 짧은 값에 맞추어 정하고, 그 배율로 계산한 긴 변이 프리셋의 긴 값을 넘을 때만 긴 변이 상한에 맞도록 배율을 낮춘다
- 가로·세로에 같은 배율을 적용해 종횡비를 보존하며, 남는 영역을 채우는 패딩을 두지 않는다. 축을 따로 늘려 왜곡시키지 않는다
- 라벨 좌표는 그 균일 배율의 곱셈으로 재계산해 적재하며 오프셋 가산이 없다. BBOX·POLYGON·세그멘테이션·키포인트에 동일하게 적용한다
- 업스케일(확대)을 허용한다. 산출 크기가 원본과 같아지는 프리셋만 건너뛰며, 대상 프리셋이 전부 건너뛰어지면 요청을 거부한다
- 해상도 파생의 저장모델은 증강과 통합돼 있다. 판별자는 증강 종류 코드값 RESL_1080P·RESL_720P·RESL_480P 이고, 라벨 배율은 기존 좌표 재계산 여부·배율 컬럼을 재사용하며 신규 컬럼을 두지 않는다. 배율 컬럼은 균일 배율이라 가로·세로가 항상 같은 값이다
- 해상도 파생은 증강 이력에 노출되고 집계에 포함되나, 내부 생성물이라 검수 승인·반려 대상이 아니다
- 이 리스케일 규칙은 신규 생성부터 적용하며, 기존에 생성된 파생본은 재생성하지 않는다

## implementation

### status

implemented

### modules

_(empty)_

### records

_(empty)_

### progress

100

### subtasks

_(empty)_

### module_paths

_(empty)_

## implements_requirements

- REQ-001
- REQ-003
- REQ-004
- REQ-005
- REQ-020
- REQ-023
