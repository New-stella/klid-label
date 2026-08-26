---
logicraft_item: DFEAT-029
type: domain_feature
version: 14
domain: DOMAIN-007
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-26T05:49:05.245Z
status: CHANGED
prev_version: 12
content_hash: fd92f5ce9a78b84315709afbf242c6012ac0a5c16fbe77886fa5751027be7d52
stale: true
raw: ./_raw/DFEAT-029.json
links:
  based_on: ["[[ADR-018]]"]
  belongs_to_domain: ["[[DOMAIN-007]]"]
  implements: ["[[API-059]]", "[[API-060]]", "[[API-061]]", "[[API-062]]", "[[API-063]]", "[[API-092]]", "[[API-179]]", "[[API-188]]", "[[API-189]]", "[[API-190]]"]
  migrated_from: ["[[LEGACY-015]]"]
  specializes: ["[[FEAT-004]]"]
  triggers: ["[[EVT-011]]"]
  verifies: ["[[AC-001]]", "[[AC-002]]", "[[AC-003]]", "[[AC-018]]"]
  depicts_backward: ["[[CDIAG-010]]", "[[CMP-007]]"]
  realizes_backward: ["[[UC-001]]", "[[UC-002]]", "[[UC-003]]"]
  references_backward: ["[[CDIAG-010]]"]
---

# 생성형 AI 외부 증강(WINTER/NIGHT/RAIN) + 해상도 변경 내부 파생(증강 저장모델 통합)

## title

생성형 AI 외부 증강(WINTER/NIGHT/RAIN) + 해상도 변경 내부 파생(증강 저장모델 통합)

## status

designed

## consumes

_(empty)_

## priority

should

## triggers

- EVT-011

## brownfield

### notes

2026-07-30: 제목·본문이 1차 baseline(5종 CVAT류 증강)에 고착돼 있어 실제 정책(외부 3종+해상도 3종)으로 전면 재작성

### status

modified

### decided_by

ADR-018

### legacy_source

#### type

table

#### identifier

LS_DATA_AUG

#### legacy_artifact_id

LEGACY-015

## user_story

### as

검수자(REVIEWER)

### i_want

원본(비식별) 영상에 외부 생성형 AI 증강(WINTER/NIGHT/RAIN)과 내부 해상도 변경(1080P/720P/480P) 파생영상을 생성하기를

### so_that

학습 데이터 다양성을 확보한다

## description

원본(비식별) 영상에 외부 생성형 AI 3종 증강(WINTER/NIGHT/RAIN — 이미지-to-이미지, 영상 재생성 없이 원본 비디오 파일 복사 + 프레임 이미지만 변환)을 위탁하고, 내부 해상도 변경 3종(1080P/720P/480P — ffmpeg 리스케일, 업스케일 포함)을 증강 저장모델(LS_DATA_AUG/LS_DATA_AUG_LBL_MAP)에 통합 적재한다. 표준 해상도 프리셋의 두 수치는 산출 프레임의 고정 크기가 아니라 크기 상한이며, 산출 프레임 크기는 원본 종횡비에 따라 달라진다 — 원본의 짧은 변을 프리셋의 짧은 값에 맞추는 배율을 구하고, 그 배율로 계산한 긴 변이 프리셋의 긴 값을 넘을 때만 긴 변이 상한에 맞도록 배율을 낮춘다. 남는 영역을 채우는 패딩은 없다. 둘 다 새 파생영상(RAW_SN, ORGNL_RAW_SN으로 원본 참조)을 생성하며 라벨 좌표는 원본 복사(해상도 변경은 배율 재계산 COORD_RECALC_YN/SCALE_X/SCALE_Y — 가로·세로에 같은 배율을 적용하는 균일 배율이라 두 값은 항상 같고 종횡비가 보존된다. 좌표 변환은 그 균일 배율의 곱셈만이며 오프셋 가산이 없고, BBOX·POLYGON·세그멘테이션·키포인트 전 종류에 동일 적용된다). 구 '5종(밝게/어둡게/좌우반전/반전+밝게/반전+어둡게) CVAT류 기본 증강' 정책(1차 baseline)은 폐기됐다. 파생 깊이는 1 로 고정한다 — 이미 파생인 영상은 증강 요청 대상이 될 수 없고 400 으로 거부되며, 재시도 여지가 없는 영구 조건이다. 같은 (영상 × 종류) 재요청은 몇 번이든 허용하고 서버가 차단하지 않는다 — 생성 결과가 매회 달라 동일 조건 재요청이 정당한 운영 동선이기 때문이며, 그래서 공존하는 파생을 구분하는 축은 요청 시 보관한 생성 조건 원문이다. 외부 위탁 요청에는 생성 조건 5필드(시간·계절·날씨·지형·심각도)가 전부 필수이고 자유 문자열로 가공 없이 중계·보관된다(해상도 변경은 외부 위탁이 아니라 생성 조건을 받지 않는다).

## invokes_apis

_(empty)_

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

### last_updated

2026-08-26T02:28:59.890Z

### module_paths

_(empty)_

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_DATA_AUG
- LS_DATA_AUG_RVW
- LS_DATA_AUG_LBL_MAP
- LS_DATA_AUG_DSCD
- LS_DATA_AUG_JOB
- LS_DATA_AUG_JOB_FILE

## related_acceptances

- AC-001
- AC-002
- AC-003
- AC-018

## specializes_feature

FEAT-004

## implemented_by_endpoints

- API-059
- API-060
- API-061
- API-062
- API-063
- API-179
- API-188
- API-189
- API-190
- API-092

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
