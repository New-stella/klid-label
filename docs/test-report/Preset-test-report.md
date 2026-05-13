# Preset 모듈 테스트 리포트

> 라벨링 프리셋 (SCR-LBL-PRESET-001) 의 누적 검증 기록.
> 새 Phase 결과는 본 문서에 누적 기재한다.

## Phase 1 — BBOX/POLYGON 어노테이션 토글 (V16)

- 일시: 2026-05-13
- 변경: 라벨 코드(`LS_LABEL_PRESET_CODE`) 별로 BBOX/POLYGON 활성 여부를 개별 토글
- 대상 패키지: `kr.co.cudo.authoring.preset.*`, `kr.co.cudo.authoring.batch.policy.PresetLabelLookupService`

### 변경/생성 파일

| 분류 | 파일 | 동작 |
|------|------|------|
| Migration | `backend/src/main/resources/db/migration/V16__alter_ls_label_preset_code_annotation_toggles.sql` | 생성 |
| Entity | `backend/.../preset/entity/LsLabelPresetCode.java` | 수정 |
| Entity | `backend/.../preset/entity/LsLabelPreset.java` | 수정 (`LabelCodeSpec` 추가, `replaceCodes(List<LabelCodeSpec>)`) |
| DTO | `backend/.../preset/dto/LabelCodeOptionDto.java` | 생성 (record) |
| Controller | `backend/.../preset/controller/PresetController.java` | 수정 (`labelCodeOptions` 신규 + 레거시 호환) |
| Service | `backend/.../preset/service/PresetService.java` | 수정 (시그니처 변경: options 받음) |
| Lookup | `backend/.../batch/policy/PresetLabelLookupService.java` | 수정 (`togglesFor()` 추가, `labelsFor()` deprecated) |
| Test | `backend/.../preset/entity/LsLabelPresetCodeTest.java` | 생성 |
| Test | `backend/.../preset/controller/PresetControllerTest.java` | 생성 |
| Test | `backend/.../preset/service/PresetServiceTest.java` | 수정 (옵션 케이스 추가) |
| Test | `backend/.../batch/policy/PresetLabelLookupServiceTest.java` | 수정 (`togglesFor` 케이스 추가) |

### 테스트 결과

| 테스트 클래스 | 케이스 | 결과 |
|--------------|:------:|:----:|
| `LsLabelPresetCodeTest` | 5 | PASS |
| `PresetServiceTest` | 11 | PASS |
| `PresetControllerTest` | 6 | PASS |
| `PresetLabelLookupServiceTest` | 11 | PASS |
| `LsLabelPresetRepositoryTest` (기존) | 4 | PASS |
| **합계** | **37** | **PASS** |

회귀 검증:
- `YoloAutolabelStepTest` 9 PASS — Phase 2 영향 없음 (`labelsFor` 레거시 동작 유지)
- `BatchOrchestratorTest` 10 PASS — 통합 회귀 OK

### 주요 케이스

| 케이스 | 확인 사항 |
|--------|----------|
| 기본 팩토리 BBOX/POLYGON 모두 true | `LsLabelPresetCode.of(p,c,o)` legacy delegate |
| `@AssertTrue` 둘 다 false 거부 | `isAtLeastOneEnabled()` |
| 새 팩토리 명시 토글 보존 | `of(p,c,o,bbox,polygon)` |
| Service create/update 옵션 그대로 전파 | `LabelCodeOptionDto → LabelCodeSpec` |
| Controller POST `labelCodeOptions` 정상 | 201 + 응답에 두 형식 모두 포함 |
| Controller POST 둘 다 false → 400 | `@AssertTrue + INVALID_INPUT` |
| Controller POST 레거시 `labelCodes` 호환 | 모두 true 정규화 |
| Controller POST 둘 다 빈 목록 → 400 | `PresetRequest.@AssertTrue` |
| Controller PUT 토글 변경 반영 | `replaceCodes(List<LabelCodeSpec>)` |
| `togglesFor()` 이벤트별 맵 반환 | 소문자 정규화 |
| `labelsFor() == togglesFor().keySet()` | 호환 보장 |
| `AnnotationToggle.BOTH` 상수 | bbox=true, polygon=true |

### 검증 명령

```
cd backend && ./gradlew test --tests '*Preset*' --tests '*LsLabelPresetCode*'
```

### 결정 사항

- `LS_LABEL_PRESET_CODE` 는 V13 마이그레이션에서 생성된 저작도구 전용 테이블 (`LS_*` 접두사) — 관제서버팀 협의 면제.
- `replaceCodes` 메서드는 type erasure 충돌 회피를 위해 String 입력 전용은 `replaceCodeStrings(List<String>)` 로 별도 메서드명 사용 (`@Deprecated`).
- `LabelCodeSpec` 은 `LsLabelPreset` 내부 record 로 정의 (도메인 패키지 일관성).
- `PresetRequest` 는 두 형식(`labelCodeOptions`, `labelCodes`) 동시 수용 — 신규 우선, 미지정 시 레거시 normalize.
- DB CHECK + Entity AssertTrue + DTO AssertTrue + 도메인 가드(`IllegalArgumentException`) 4중 보호.

### 다음 Phase

Phase 2 (YOLO/SAM2 배치 분기 + BbHint 인메모리 전달) 진입 가능.
