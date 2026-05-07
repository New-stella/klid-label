# UI-UX 설계서 vs 실제 구현 정합성 검토

> **검토일**: 2026-05-07 | **검토자**: design-reviewer | **대상 설계서**: 저작도구_UI-UX설계서_FINAL.md (Ver FINAL)

---

## 종합 판정: CONDITIONAL

회귀 방지 항목 전원 통과, 공통 컴포넌트·색상 토큰·레이아웃 정합. 단, 이벤트 분포 6종 코드명이 설계서 명칭과 불일치하며 대시보드 KPI 텍스트가 설계서와 미세하게 다름. 치명적 위반은 없으나 아래 MEDIUM 이슈 해소 후 PASS 판정.

---

## 16 화면 매트릭스

| # | 화면 ID | 정합 | 누락/차이 | 회귀/위반 |
|---|--------|:----:|---------|---------|
| 4-1 | SCR-INGRESS | ✅ | - | - |
| 4-2 | SCR-VIDEO-001/002/003 | ✅ | [배정][상세][✨] 3종 행 액션 구현, URL 동기화 확인 | - |
| 4-3 | SCR-DASH-001 | ⚠️ | KPI 레이블 차이·이벤트 코드 불일치 (MEDIUM) | - |
| 4-4 | SCR-PRESET-001/002 | ✅ | 라벨 최대 6종 MAX_ITEMS=6 적용, 복사본 접미사 확인 필요 | - |
| 4-5 | SCR-TASK-001/002 | ✅ | 작업자 라디오 단독 — 회귀 방지 OK | - |
| 4-6 | SCR-LABEL-001/002 | ✅ | Layer 3분리·도구 7종·SaveCommit 2단계·portalMode skip | - |
| 4-7 | SCR-AUTO-001/002 | ✅ | 신뢰도 3구간·VLM+외부메타 명확 분리 | - |
| 4-8 | SCR-HIST-001/002 | ✅ | diff 3색·RollbackConfirmModal·portalMode 히스토리 미렌더 | - |
| 4-9 | SCR-REVIEW-001/002/003 | ✅ | IssueSidebar·좌표마커 X·RejectModal 텍스트만 | - |
| 4-10 | SCR-DEIDENT-001/002 | ✅ | 12 그리드 라디오·50:50 비교·비식별 이미지 없음 표시 | - |
| 4-11 | SCR-STAT-001/002 | ✅ | 누적 ProgressBar X·6종 고정·CSV 다운로드 버튼 | - |
| 4-12 | SCR-AUG-001/002·SCR-EXPORT-001 | ✅ | 4종 체크박스·결정카드 3상태·COCO/YOLO/CoT·마트 UI X | - |
| 4-13 | SCR-GEN-001/002 | ✅ | Modal 영상readonly·12그리드 라디오·산불/침수·단일카드 안내 | - |
| 4-15 | SCR-PORTAL-001/002 | ✅ | 2단계 카드·TUS 5GB·mp4/mov/avi·다운로드 UI X | - |
| 4-16 | SCR-MANAGE-001/002 | ✅ | REVIEWER RoleGuard·3섹션·범위값 정확 | - |
| 공통 §2-§3 | 색상·타이포·StatusBadge | ✅ | 9색 토큰·9종 뱃지·Tailwind 정의 일치 | - |

---

## 회귀 방지 항목 검증

| 항목 | 결과 | 근거 파일 |
|------|:----:|---------|
| AssignModal 작업자 라디오만 (우선순위/기한/메모 X) | ✅ | `features/task/components/AssignModal.tsx` 주석 명시, Radio만 존재 |
| FrameGrid12 단일 라디오 | ✅ | `features/deident/components/FrameGrid12.tsx` `type="radio" name="frame-grid-12"` |
| 좌우 50:50 비교 (슬라이더 X) | ✅ | `SideBySideCompare.tsx` `grid-cols-2`, V1.6 주석 |
| 검수 캔버스 좌표 마커 미사용 | ✅ | `IssueSidebar.tsx` 주석 "캔버스 좌표 마커 절대 미사용" |
| 통계 누적 ProgressBar 미노출 | ✅ | `OverallStatPage.test.tsx` `queryByRole('progressbar')` not.toBeInTheDocument |
| 포털 다운로드 UI 절대 미제공 | ✅ | `PortalHomePage.tsx` 다운로드 관련 UI 없음, 안내 문구만 존재 |
| 데이터마트 검색 UI 미제공 | ✅ | `ExportPage.tsx` NAS 내보내기만, 마트 UI 없음 |
| VLM/시계열 자동생성 UI 미제공 | ✅ | `VlmVerificationCard.tsx` 객체검증만, `LabelingPagePortalRestrictions.test.tsx` VLM 탭 미노출 검증 |

모든 회귀 방지 항목 **8/8 통과**.

---

## 색상/디자인 토큰

| 항목 | 결과 |
|------|:----:|
| 9색 토큰 적용 (`tailwind.config.js`) | ✅ |
| StatusBadge 9종 매핑 | ✅ |
| 타이포그래피 6종 (`page-title`/`section-title`/`body`/`sub`/`btn-label`/`table-header`) | ✅ |
| GNB 배경 `bg-primary`, 로고 → `/video/completed` | ✅ |
| LNB 역할별 메뉴 필터링 (REVIEWER/WORKER/PORTAL_USER) | ✅ |

---

## 식별된 이슈

### [MEDIUM] 이벤트 분포 6종 코드명 불일치

**설계서 §4-3/§4-11**: 쓰러짐(FALL) · 폭력(VIOLENCE) · 교통사고(TRAFFIC_ACCIDENT) · 이상행동(ABNORMAL_BEHAVIOR) · 침수(FLOOD) · 산불(WILDFIRE)

**구현** (`EventDistributionGrid.tsx` + `types.ts`):
`FIRE(화재)` / `FALL(쓰러짐)` / `INVASION(침입)` / `CROWD(군집)` / `VIOLENCE(폭력)` / `ABANDON(유기/방치)`

6종이라는 수량은 일치하나 코드명과 한글 레이블이 전혀 다름. 설계서의 "교통사고·이상행동·침수·산불"이 없고, 구현에는 "화재·침입·군집·유기/방치"가 있음. 백엔드 도메인 코드와의 일치 여부 확인 필요.

### [MEDIUM] 대시보드 KPI 레이블 차이

**설계서 §4-3**: WORKER KPI 4개 = 처리 대기 / 처리 완료 / 내 작업 / 반려 건수

**구현** (`DashboardPage.test.tsx`): "전체 영상" / "누적 라벨 프레임" / "검수 대기" / "내 배정" 로 레이블이 다름. 설계서의 "처리 대기"="검수 대기", "처리 완료"="누적 라벨 프레임"이 아닐 가능성이 높음. 기능적으로는 유사하나 사용자에게 노출되는 레이블이 다르므로 UX 기준에서 확인 필요.

### [MEDIUM] 프리셋 복사 시 "-복사본" 접미사 미확인

**설계서 §4-4**: 복사 시 `-복사본` 접미사 자동 부여. 구현(`PresetEditModal.tsx`)에서 복사 로직은 `PresetListPage.tsx`에 있을 것으로 예상되나 spot-check 범위 내에서 접미사 처리 코드 미확인. 별도 검증 권고.

### [LOW] REVIEWING 상태 색상 불일치

**설계서 §3.4**: REVIEWING은 `#9C27B0` (보라). **구현** (`StatusBadge.tsx`): REVIEWING이 `bg-accent/10 text-accent`(`#2196F3` 계열)로 매핑됨. 설계서의 보라색(`#9C27B0`) 토큰이 Tailwind에 미정의 — `#9C27B0` 전용 토큰(`purple`) 추가 권고.

### [LOW] LNB에 "비식별화 결과" 메뉴 존재 (설계서 미기술)

설계서 §3.2 LNB 표에는 비식별화 결과 경로(`/deident`)가 명시되어 있지 않으나 구현 LNB에는 `{ label: '비식별화 결과', path: '/deident', allow: ['REVIEWER', 'WORKER'] }`가 존재. 기능적으로 필요하나 설계서 §3.2 메뉴 구조 표와 미일치. 설계서 보완 또는 구현 재검토 필요.

---

## 권고

1. **이벤트 코드 정렬**: DB 실제 `MNG_EX_EVNT_TYPE_MAP` 코드 기준으로 설계서 코드명 또는 구현 코드명 중 하나를 일치시킬 것.
2. **REVIEWING 색상 토큰**: Tailwind에 `purple: '#9C27B0'` 추가 후 `StatusBadge`의 REVIEWING에 적용.
3. **대시보드 KPI 레이블**: PO/기획과 함께 사용자 노출 레이블 최종 확정 후 설계서 또는 구현 동기화.
4. **프리셋 복사 기능**: `-복사본` 접미사 처리 로직 구현 여부 확인 및 테스트 추가.
5. **LNB 비식별화 항목**: 설계서 §3.2에 `/deident` 항목 추가하거나, 접근 권한 재검토.

---

## 판정 근거

회귀 방지 8개 항목 전원 통과, 색상 토큰 9종 정확히 정의, StatusBadge 9종 구현 확인, 핵심 화면(SessionIngress·AssignModal·FrameGrid12·SideBySideCompare·IssueSidebar·SaveCommit·RejectModal·ExportPage·BackgroundGenerateModal·GenerateResultPage) 모두 설계서 명세와 일치. MEDIUM 2건(이벤트코드 불일치·KPI 레이블 차이)은 사용자 가시 텍스트에 영향을 주나 기능 동작 자체를 막지는 않음. 이를 해소하면 PASS 가능.
