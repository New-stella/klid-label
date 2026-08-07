# 배치 B 수정 완료 — SCREEN-006/007/008/009

## 진행 중 정책 정정 (반드시 먼저 읽을 것)

작업 도중 코디네이터로부터 4차례 정정이 왔고, 전부 반영했다.

1. **P10 철회**: 화면 `purpose`에 "기준 구현: klid-label-frontend(납품 FE)" 같은 레포 출처 문구를 넣지 않았다(넣기 전에 철회돼 실제로 넣은 적 없음).
2. **P5 범위 정정**: ITEM 본문(`purpose`/`sections[].description`/`components[].note`)에는 코드 파일명·심볼명(`.tsx`, 훅 이름, prop 이름 등)을 쓰지 않는다. 이미 넣었던 `MarkingHeader.tsx`/`LabelHeader.tsx`/`unsupportedReason` 등을 SCREEN-006 재수정으로 제거했다.
3. **`change_summary`도 오염 금지**: 로컬 리포트 파일명(`B-video-marking.md`), finding 코드(`ERR-B01` 등), "감사에서 발견" 류 서술을 빼고 설계 근거 중심으로 다시 썼다. 단 **SCREEN-006의 처음 두 리비전(버전 10→11→12)의 `change_summary`에는 이 규칙 정정 이전에 쓴 텍스트가 남아있다** — LogiCraft는 과거 리비전의 `change_summary`를 소급 수정하는 기능이 없어 그대로 남는다(현재 리비전 13의 `change_summary`는 규칙을 준수함).
4. **구현상태 언급 전면 금지**: "미구현"/"아직 반영 안 됨"/"현행 구현에 없음" 류 서술을 본문 어디에도 쓰지 않는다. 이에 따라:
   - **SCREEN-007은 결국 아무것도 수정하지 않았다.** CONFLICT-B01(라우트 불일치)은 설계 문서에 반영할 내용이 아니라는 최종 판단이었다.
   - SCREEN-006의 GAP-B02(비식별 신고 사전 비활성화 미배선) 관련 note도 "현재 구현 상태" 언급을 전부 제거하고 원문 그대로 되돌렸다 — 설계 자체는 이미 정확했으므로 설계 변경이 필요 없었다.
   - `implementation` 필드는 어떤 ITEM에서도 건드리지 않았다.

이 정정들 때문에 최종 결과가 최초 작업 지시(D1의 SCREEN-007 `purpose` 예외 등)와 달라진 지점이 있다. 아래 표에 "최종 처리"로 반영했다.

## 처리 결과 표

| finding | 화면 | 최종 처리 | 비고 |
|---|---|---|---|
| ERR-B01 (VLM 이벤트유형 소싱처) | SCREEN-006 | 반영 | "관제 클립 메타" → "관제 인입값" 으로 정정 |
| ERR-B02 (스트리밍 직접 바인딩) | SCREEN-006 | 반영 | 서명 URL 선행 조회 후 바인딩으로 정정 |
| ERR-B03 (마킹완료 disabled) | SCREEN-006 | 반영 | `state:"disabled"` 제거, 토스트 경고 방식으로 정정 |
| GAP-B01 (모드 전환 단축키) | SCREEN-006 | 반영 | 1/2 단축키(항상 발화) + Backspace 보강 |
| GAP-B02 (신고 버튼 사전 비활성화 미배선) | SCREEN-006 | **설계 변경 없음** | 설계 자체가 이미 정확 — 실제 코드 갭이라 로컬 후속 과제로만 취급(아래 "남은 것") |
| CONFLICT-B01 (SCREEN-007 라우트 불일치) | SCREEN-007 | **설계 변경 없음(최종 철회)** | 구현상태 언급 금지 정정으로 인해 `purpose`에 아무것도 추가하지 않음. SCREEN-007은 이번 라운드에서 0건 수정 |
| CONFLICT-B02 (간격 상한 3600 vs 미검증) | SCREEN-006 | 반영 | "1 이상 정수, 상한 없음"으로 통일(SCREEN-007과 정합) |
| GAP-B04 (재비식별 API 조건부 등록) | SCREEN-009 | 반영 | 404 가 대상 부재 외에 기능 비활성 환경에서도 발생함을 설계 사실로 보강 |
| GAP-B05 (required_roles 스키마화) | SCREEN-006/009 | 보류 | 확신도 low, 스키마 표준화 별건 — 이번 라운드 범위 밖으로 유지 |
| D1 (SCREEN-008 재활성) | SCREEN-008 | 반영 | `status: deprecated → draft`. 옛 KPI+폴링 대시보드 4섹션은 `[폐기]` 접두 + 대체 섹션 안내로 보존, 실제 화면 구성(검색·필터/목록 테이블/일괄배정/배정모달/마킹진입팝업) 6섹션 신설 |

## 화면별 sections 개수 검증 (before → after)

| ITEM | before | after | 비고 |
|---|---:|---:|---|
| SCREEN-006 | 5 | 5 | 개수 불변, 내용만 정정 |
| SCREEN-007 | 8 | 8 | **무변경** (이번 라운드 0건 수정) |
| SCREEN-008 | 4 | 10 | 옛 4개 `[폐기]` 표시로 보존 + 신규 6개 추가 — 감소 없음 |
| SCREEN-009 | 6 | 6 | 개수 불변, component 1개 note만 보강 |

get_item 재조회로 4개 ITEM 모두 확인 완료 — 배열 원소 삭제 없음, 기존 컴포넌트 보존 확인.

## SCREEN-008 재활성 상세

- `status`: `deprecated` → `draft` (top-level + `data.status` 동일 적용)
- 옛 4섹션(`페이지 헤더+새로고침(구)`/`처리 현황 KPI`/`선택 일괄 액션 바`/`배치 처리 현황 테이블`)은 이름 앞에 `[폐기]` 접두를 붙이고, description에 "유효한 설계는 아래 OO 섹션이다" 식으로 대체 섹션을 안내(P8 준수 — 배열에서 제거하지 않음)
- 신규 6섹션(페이지 헤더+새로고침 / 검색·필터 / 일괄 배정 바 / 영상 목록 테이블+페이지네이션 / 작업자 배정 모달 / 마킹 진입 팝업)은 SCREEN-007과 기능적으로 대응되는 실제 화면 구성을 반영
- `consumes_apis`: 기존 `["API-042","API-077"]`에 `["API-047","API-070","API-071","API-181"]` 추가(제거 없음 — API-077은 이미 폐기된 API지만 옛 섹션이 참조하므로 존치)
- `brownfield.status`를 `deprecated`→`modified`로 갱신, `diff_summary`도 설계 수준 서술로 교체

## 남은 것 (이번 라운드 범위 밖 / 후속 필요)

1. **GAP-B02 실제 코드 갭**: 비식별 신고 버튼의 클라이언트 사전 비활성화(파생영상·배치단계별 비활성+툴팁)가 마킹 화면 실제 구현에는 배선되지 않았다(대상 화면의 신고 버튼 호출부가 `unsupportedReason`류 prop을 전달하지 않음, 라벨링 화면에는 이미 있음). **설계는 이미 정확하므로 LogiCraft ITEM은 건드리지 않았다** — 개발 조치가 필요하면 별도 이슈로 추적할 것.
2. **CONFLICT-B01 (SCREEN-007 vs SCREEN-008 라우트 관계)**: 두 화면이 기능적으로 거의 동일한 내용을 서로 다른 라우트(`/video/completed` vs `/video/status`)로 서술하고 있다. 이번 라운드는 "구현상태를 설계 문서에 적지 않는다"는 원칙에 따라 이 불일치 자체를 설계 문서에서 언급하지 않기로 했다 — 두 ITEM 중 어느 라우트가 최종 확정 사양인지는 **사용자 판단이 필요한 미결 사안**으로 남는다.
3. **SCREEN-006 과거 리비전(v10~v12) change_summary 잔존**: 규칙 정정 이전에 작성한 change_summary에 리포트 파일명·finding 코드·코드 심볼이 남아있다. 소급 수정 불가 — 현재(v13) change_summary부터 규칙 준수.
4. **SCREEN-008 brownfield 경고 2건**: `decided_by`(ADR)와 `legacy_source.identifier`가 비어있다는 경고가 떴다. 실제 근거가 되는 ADR이 없어 임의로 채우지 않았다(추정 금지 원칙) — 필요 시 사용자 확인 후 보완.
5. **SCREEN-009 update 응답에 `unresolved:1`** 링크 경고가 있었다(원인 미상, 이번 수정 범위와 직접 관련은 없어 보임) — 후속 세션에서 `get_item`으로 unresolved link 상세를 확인할 것.
6. **GAP-B05 (required_roles 스키마화)**: 확신도 low, 별도 스키마 표준화 논의 필요 — 미착수.

## 관련 파일

- 감사 원본: `/Users/ck/orca/workspaces/klid-label/upload-ui/reports/logicraft-audit-2/B-video-marking.md`
- 기준 구현 확인 경로(로컬 파일 조사용, 설계 문서에는 인용하지 않음): `/Users/ck/orca/klid-label-frontend/src/{routes,pages/VideoStatusPage.tsx,components/video-status/**,components/marking/**,hooks/marking/**,components/video-detail/RedeidentButton.tsx}`
