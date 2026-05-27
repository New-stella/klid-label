# CBD 산출물 가이드

## 개요

CBD(Component Based Development) SW개발 표준 산출물 25종의 마크다운 원본 + hwpx 양식 + 스타일 정보를 관리한다.

**파이프라인**: 마크다운(작성) → md_uml(미리보기/PDF) → hwpx(최종 납품)

## 디렉토리 구조

```
docs/design/
├── README.md              ← 이 파일
├── CBD.pdf                ← NIA CBD 표준 산출물 가이드 원본 (2011.12)
│
├── R1-사용자요구사항정의서.md    ← 실제 산출물 (마크다운)
├── R2-유스케이스명세서.md
├── R3-요구사항추적표.md
├── D1-클래스설계서.md ~ D12-데이터전환및초기데이터설계서.md
│
├── templates/             ← 빈 양식 템플릿
│   ├── R1-사용자요구사항정의서.md ~ T7-인수시험결과서.md (25종)
│   ├── _hwpx-styles.json           ← R2 기준 상세 스타일 파싱
│   ├── _hwpx-styles-summary.json   ← 12종 문서별 폰트/속성 요약
│   ├── _hwpx-styles-readable.json  ← 명명 스타일 → 폰트/크기/정렬 매핑
│   └── styles/                     ← hwpx 원본 header.xml (문서별)
│       ├── _index.json             ← 문서명 ↔ hwpx 파일 매핑
│       └── {문서명}-header.xml     ← 폰트/문자속성/문단속성/테두리/셀 정의
│
└── hwpx/                  ← hwpx 원본 양식 12종
    └── KLID_{OO|DE}_*.hwpx
```

## 산출물 목록

| 단계 | 코드 | 산출물명 | 템플릿 | hwpx | 산출물 |
|:---:|:---:|---|:---:|:---:|:---:|
| 분석 | R1 | 사용자 요구사항 정의서 | ✅ | - | ✅ |
| 분석 | R2 | 유스케이스 명세서 | ✅ | ✅ | ✅ |
| 분석 | R3 | 요구사항 추적표 | ✅ | - | ✅ |
| 설계 | D1 | 클래스 설계서 | ✅ | ✅ | ✅ |
| 설계 | D2 | 사용자 인터페이스 설계서 | ✅ | ✅ | ✅ |
| 설계 | D3 | 컴포넌트 설계서 | ✅ | ✅ | ✅ |
| 설계 | D4 | 인터페이스 설계서 | ✅ | ✅ | ✅ |
| 설계 | D5 | 아키텍처 설계서 | ✅ | ✅ | ✅ |
| 설계 | D6 | 총괄시험 계획서 | ✅ | - | ✅ |
| 설계 | D7 | 시스템시험 시나리오 | ✅ | ✅ | ✅ |
| 설계 | D8 | 엔티티 관계 모형 설계서 | ✅ | ✅ | ✅ |
| 설계 | D9 | 데이터베이스 설계서 | ✅ | ✅ | ✅ |
| 설계 | D10 | 통합시험 시나리오 | ✅ | ✅ | ✅ |
| 설계 | D11 | 단위시험 케이스 | ✅ | ✅ | ✅ |
| 설계 | D12 | 데이터 전환 및 초기데이터 설계서 | ✅ | ✅ | ✅ |
| 구현 | I1 | 프로그램 코드 | ✅ | - | - |
| 구현 | I2 | 단위시험 결과서 | ✅ | - | - |
| 구현 | I3 | DB 생성 스크립트 | ✅ | - | - |
| 시험 | T1 | 통합시험 결과서 | ✅ | - | - |
| 시험 | T2 | 시스템시험 결과서 | ✅ | - | - |
| 시험 | T3 | 사용자 지침서 | ✅ | - | - |
| 시험 | T4 | 운영자 지침서 | ✅ | - | - |
| 시험 | T5 | 시스템 설치 결과서 | ✅ | - | - |
| 시험 | T6 | 인수시험 시나리오 | ✅ | - | - |
| 시험 | T7 | 인수시험 결과서 | ✅ | - | - |

## 마크다운 작성 규칙

### 테이블 형식

CBD 양식의 테이블은 두 가지 방식으로 작성한다.

**1) 마크다운 테이블** — 단순 데이터 테이블 (셀 병합 불필요)

```markdown
| 유스케이스 ID | 유스케이스명 | 유스케이스 설명 | 관련액터 ID |
|---|---|---|---|
| KLID-AT-UC-001 | 자동 파이프라인 실행 | ... | KLID-AT-AC-004 |
```

**2) HTML 테이블** — 셀 병합이 필요한 양식 테이블 (`colspan` 사용)

```html
<table>
<tr><td>화면 ID</td><td>KLID-AT-SC-003</td><td>화면명</td><td>대시보드</td></tr>
<tr><td>관련 유스케이스 ID</td><td colspan="3">KLID-AT-UC-001</td></tr>
<tr><td>화면유형</td><td>조회</td><td>메뉴경로</td><td>홈 > 대시보드</td></tr>
<tr><td>화면개요</td><td colspan="3">처리 대기/완료/반려 영상 건수 KPI...</td></tr>
</table>
```

### HTML 테이블 적용 대상

| 문서 | 섹션 | colspan 패턴 |
|---|---|---|
| R2 | 유스케이스 기술서 | 항목 1~8 → `colspan="4"` 단일 셀 |
| D2 | 화면 상세 설계 | 관련UC ID, 시퀀스도 ID, 화면개요 → `colspan="3"` |
| D4 | 인터페이스 명세 | 데이터송신/수신시스템 헤더 → `colspan="5"` |
| D5 | 아키텍처 요구사항 | 요구사항 ID/내용/구현방안 (multi-line `<br>`) |
| D7 | 시스템시험 시나리오 | 시험유형, 관련요구사항 ID → `colspan="5"` |

### PlantUML 다이어그램

PlantUML 코드 블록은 md_uml에서 자동 렌더링된다.

```markdown
​```plantuml
@startuml
Alice -> Bob: Hello
@enduml
​```
```

- UCD(유스케이스 다이어그램): `@startuml` + `left to right direction`
- UI 와이어프레임: `@startsalt`
- 시퀀스도: `@startuml` + 시퀀스 구문

### 줄바꿈 규칙

- 볼드 레이블과 내용 사이: **빈 줄** 필수 (같은 줄에 붙으면 동일 단락으로 렌더링)
- HTML 테이블 내: `<br>` 사용
- 대안 시나리오 가/나/다 앞: **빈 줄** 필수

## hwpx 변환 가이드 (md_uml → hwpx)

### 스타일 적용

hwpx 변환 시 `templates/styles/` 디렉토리의 `header.xml`을 활용한다.

```
1. 대상 문서의 header.xml 로드 (예: KLID_OO_유스케이스 명세서-header.xml)
2. header.xml의 fontfaces, charPrList, paraPrList, borderFillList 그대로 주입
3. 마크다운 요소 → hwpx 스타일 매핑:
   - # 제목     → style "heading 1" (charPrIDRef, paraPrIDRef 참조)
   - ## 제목    → style "heading 2"
   - 본문 텍스트 → style "바탕글" (Normal)
   - **굵은 글씨** → charPr bold 속성 적용
   - 표 셀     → borderFill 참조 (테두리, 배경색)
```

### colspan 매핑

HTML `<td colspan="N">`은 hwpx `<hp:tc>` 요소의 병합 속성으로 직접 변환한다.

```xml
<!-- hwpx 셀 병합 예시 -->
<hp:tc ... colSpan="3">
  <hp:cellAddr colAddr="1" rowAddr="0"/>
  ...
</hp:tc>
```

### 공통 폰트

모든 문서에서 사용되는 공통 폰트 8종:

| 폰트 | 용도 |
|---|---|
| 맑은 고딕 (Malgun Gothic) | 본문, 표 셀 |
| 함초롬바탕 | 본문 기본 (바탕글 스타일) |
| 함초롬돋움 | 제목, 머리말, 차례 |
| 바탕 | 머리글 |
| 한컴바탕 | 보조 본문 |
| Times New Roman | 영문, 숫자 |
| Wingdings | 특수 기호 (■ 등) |

## ID 체계

| 항목 | 패턴 | 예시 |
|---|---|---|
| 프로젝트 | `KLID` | |
| 서브시스템 | `KLID-AT-SS-NNN` | KLID-AT-SS-001 |
| 유스케이스 | `KLID-AT-UC-NNN` | KLID-AT-UC-001 |
| 액터 | `KLID-AT-AC-NNN` | KLID-AT-AC-001 |
| UCD | `KLID-AT-UCD-NNN` | KLID-AT-UCD-001 |
| 화면 | `KLID-AT-SC-NNN` | KLID-AT-SC-001 |
| 시퀀스도 | `KLID-AT-SD-NNN` | KLID-AT-SD-001 |
| 인터페이스 | `KLID-AT-II-NNN` | KLID-AT-II-001 |
| 요구사항 | `RQ-{SFR\|NFR}-NN-NN` | RQ-SFR-08-01 |
| 시스템시험 | `KLID-ST-NNN` | KLID-ST-001 |
| 통합시험 시나리오 | `KLID-IT-TS-NNN` | KLID-IT-TS-001 |
| 통합시험 케이스 | `KLID-IT-TC-NNN` | KLID-IT-TC-001 |
