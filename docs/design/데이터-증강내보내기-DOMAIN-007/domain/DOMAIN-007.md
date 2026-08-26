---
logicraft_item: DOMAIN-007
type: domain
version: 16
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-26T05:49:10.294Z
status: CHANGED
prev_version: 15
content_hash: 7bab304a73ae55904168b998eb3015f5e45f45c6a408867fedf025a4dfaa6391
stale: false
raw: ./_raw/DOMAIN-007.json
links:
  based_on: ["[[ADR-020]]"]
  collaborates_with: ["[[DOMAIN-005]]", "[[DOMAIN-010]]"]
  applies_to_backward: ["[[NFR-009]]"]
  belongs_to_domain_backward: ["[[ADR-004]]", "[[ADR-018]]", "[[ADR-023]]", "[[ADR-044]]", "[[ADR-045]]", "[[API-059]]", "[[API-060]]", "[[API-061]]", "[[API-062]]", "[[API-063]]", "[[API-165]]", "[[API-179]]", "[[API-188]]", "[[API-189]]", "[[API-190]]", "[[CDIAG-010]]", "[[CMP-007]]", "[[DFEAT-029]]", "[[DFEAT-030]]", "[[ERD-011]]", "[[EVT-011]]", "[[INT-006]]", "[[INT-008]]", "[[REQ-023]]", "[[SCREEN-022]]", "[[SCREEN-023]]", "[[UC-001]]", "[[UC-002]]", "[[UC-003]]"]
  derived_domain_backward: ["[[AC-001]]", "[[AC-002]]", "[[AC-003]]", "[[AC-018]]"]
  implements_in_backward: ["[[MOD-013]]", "[[MOD-018]]"]
  references_backward: ["[[TEST-003]]"]
---

# 데이터 증강

## name

데이터 증강

## brownfield

### notes

1차 도메인. Pass 2에서 데이터마트/Export는 범위 외(deprecated), 증강은 생성AI 연동으로 modified. 이후 ADR-020 이 검수 승인 학습데이터 export 산출을 저작도구 범위로 들였으나 그 산출은 검수 승인 경로가 담당해 이 도메인으로 돌아오지 않으며, 데이터마트 구축·검색·다운로드는 여전히 외부 제공 시스템 책임이다

### status

modified

### decided_by

ADR-020

### change_kind

- scope-shrink

### diff_summary

1차 '데이터 증강 + 내보내기' → 2차 내보내기(Export)·데이터마트 폐기(외부 제공 시스템 책임, V1.4/V1.9), 데이터 증강만 잔존. 검수 승인 export 산출은 ADR-020 으로 저작도구 범위 안이나 검수 승인 경로 소관이고, 데이터마트 구축·검색·다운로드는 외부 제공 시스템 책임으로 유지된다

## description

원본(비식별) 영상에서 파생영상을 만들어 학습데이터를 늘리는 도메인.

[증강 2계열]
· 외부 생성형 AI 위탁 3종 — WINTER / NIGHT / RAIN. 이미지-to-이미지라 영상(비디오)을 재생성하지 않고 부모의 비식별 영상 파일을 복사한 뒤 프레임 이미지만 변환한다. 해상도가 같으므로 라벨 좌표는 그대로 복사한다.
· 해상도 변경 3종 내부 파생 — RESL_1080P / RESL_720P / RESL_480P. 외부 위탁이 전혀 없는 내부 ffmpeg 리스케일이다. 프리셋의 두 수치는 산출 프레임의 고정 크기가 아니라 상한이며, 산출 프레임 크기는 원본 종횡비에 따라 달라지고 남는 영역을 채우는 패딩이 없다. 배율은 원본의 짧은 변을 프리셋의 짧은 값에 맞추되, 그 배율로 계산한 긴 변이 프리셋의 긴 값을 넘으면 긴 변이 상한에 맞도록 낮춘다. 가로·세로에 같은 배율을 적용해 종횡비를 보존하며, 라벨 좌표 변환은 그 균일 배율의 곱셈만이고 오프셋 가산이 없다. 업스케일도 허용한다.

[저장모델 통합] 해상도 파생은 전용 테이블을 두지 않고 증강과 같은 LS_DATA_AUG + LS_DATA_AUG_LBL_MAP 에 적재한다. 판별자는 AUG_TYPE_CD 값 RESL_* 이고, 중복 방지는 부분 유니크 UK_LS_DATA_AUG_RESL 이다. 구 전용 테이블(LS_RESOLUTION_EXPORT·LS_RESOLUTION_LBL_MAP)은 V126 에서 백필 후 폐기됐다.

[★파생은 새 영상이다] 증강·해상도 모두 새 RAW_SN 을 만들고 ORGNL_RAW_SN 으로 부모를 참조한다. 파생 깊이는 1 로 고정되어 파생본에서는 어떤 파생도 만들 수 없다(ADR-023). 파생에는 '원본영상'이 없고 비식별본만 있으며, 유일한 소비자는 관제서버다.

[★두 개의 상태축을 합치지 말 것] LS_DATA_AUG.AUG_PROC_STTS_CD 는 생성 결과 전용(웹훅 소유: ACCEPTED/REJECTED/CANCELED)이고, REVIEWER 의 사용·폐기 결정은 LS_DATA_AUG_RVW.RVW_STTS_CD 가 단독으로 소유한다. 작업목록 등재 게이트의 판정축도 리뷰 행이다(생성 성공만으로 통과시키면 기능이 무의미해진다). 해상도 파생은 검수 대상이 아니라 게이트 통과 예외를 명시적으로 둔다.

[★중복 요청 허용] 같은 (영상 × 종류)를 몇 번이든 다시 요청할 수 있다(ADR-044) — 생성 결과가 매번 달라 동일 조건 재요청이 정당한 운영 동선이기 때문이다. BE 차단·속도제한을 두지 않으며 연타 방어는 FE 책임이다. 결과물 구분축은 PROMPT_CN(요청 시 생성조건 5필드 원문)이다.

[미사용 파생 폐기] 반려된 파생은 작업 대상에서 빠지고 유예 7일 후 배치가 DB 행과 파일까지 실삭제한다(ADR-045). 삭제 대상은 파생·반려·유예경과 3조건 동시 충족만이며 조건을 최종 DELETE SQL 에 리터럴로 박는다.

[범위 외] 데이터마트 구축·검색·다운로드는 저작도구 책임이 아니다(ADR-020). 학습데이터셋 export 산출은 저작도구 범위 안이지만 이 도메인이 아니라 검수 승인 경로가 담당한다(ADR-020 이 내보내기를 범위 외로 두던 ADR-005 를 대체한다). 생성형 AI 모델 본체도 외부 시스템 책임이다(ADR-004).

## upstream_of

_(empty)_

## context_kind

core

## collaborators

- DOMAIN-010
- DOMAIN-005

## integrates_with

_(empty)_

## uses_integrations

_(empty)_

## ubiquitous_language

### [1]

- **term**: 증강(파생영상)
- **meaning**: 원본의 비식별 영상을 복사하고 프레임 이미지만 변환해 만든 새 영상. 새 RAW_SN 을 가지며 ORGNL_RAW_SN 으로 부모를 참조한다

### [2]

- **term**: 외부 증강 3종
- **meaning**: WINTER/NIGHT/RAIN — 생성형 AI 에 위탁하는 이미지-to-이미지 변환

### [3]

- **term**: 해상도 파생 3종
- **meaning**: RESL_1080P/720P/480P — 외부 위탁 없는 내부 ffmpeg 리스케일. 프리셋의 두 수치는 고정 크기가 아니라 상한이며 산출 크기는 원본 종횡비에 따라 달라진다. 가로·세로에 같은 배율을 적용해 종횡비를 보존하고, 라벨 좌표는 그 균일 배율의 곱셈으로만 재계산한다(오프셋 가산 없음)

### [4]

- **term**: 생성 조건(prompt)
- **meaning**: REVIEWER 가 입력하는 time/season/weather/terrain/severity 5필드. 가공 없이 외부로 전송되고 PROMPT_CN 에 원문 보관된다

### [5]

- **term**: 생성 결과축
- **meaning**: AUG_PROC_STTS_CD — 웹훅이 소유. 생성 성공/실패/취소만 나타낸다

### [6]

- **term**: 검수 결정축
- **meaning**: LS_DATA_AUG_RVW.RVW_STTS_CD — REVIEWER 의 사용·폐기 결정. 등재 게이트의 판정축

### [7]

- **term**: 파생 깊이 1 고정
- **meaning**: 파생본은 증강 요청 대상이 될 수 없다. 모든 파생의 부모는 항상 원본이다

### [8]

- **term**: 균일 배율(종횡비 보존)
- **meaning**: 가로·세로에 동일하게 적용되는 하나의 배율. SCALE_X 와 SCALE_Y 는 유지되지만 항상 같은 값이며, 오프셋이 없으므로 이 두 값만으로 라벨 좌표 변환이 완전히 역산된다
