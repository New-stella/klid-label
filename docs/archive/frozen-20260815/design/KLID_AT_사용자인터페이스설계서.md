# D2 사용자 인터페이스 설계서

> **ID 표기 규칙(공통)**: 본 산출물의 모든 ID(KLID-AT-UC/CO/DC/DCD/SD/SC/AC/EN/ERD/TB/II/IC/IF/UCD/ACT 등)는 **전체 형식 `KLID-AT-XX-NNN`** 으로 표기한다. 끝부분만(예: `SC-001`) 약식 표기 금지. 범위는 시작 ID만 전체형으로(예: `KLID-AT-SC-005~036`). ※ 요구사항(RQ-SFR-NN-NN)·시스템시험(KLID-ST-NNN) 등 타 체계 ID는 각 체계 원형 유지.

## 작성 목적
> 시스템이 제공하는 사용자 인터페이스의 전체 구조와 메뉴 형식, 화면 목록과 화면의 상세 설계 내역을 기술한다.

## 작성 방법
> 전체 시스템에 대한 사용자 인터페이스의 구조를 사용자에게 제공하는 메뉴 형식으로 기술하고, 화면 및 출력으로 구분하여 목록을 작성하며, 화면의 상세 설계의 내용을 화면별로 기술한다.

## 산출물 양식

### 제·개정 이력

| 날짜 | 버전 | 작성자 | 승인자 | 내용 |
|------|------|--------|--------|------|
| 2026-08-06 | 1.0 | - | - | LogiCraft 그래프 기반 생성 (/cc-doc-gen) |

### 헤더

| D2 | 사용자 인터페이스 설계서 |
|-------|--------------|
| 시스템명 | AI 기반 지방정부 CCTV 관제지원시스템(2차) | 서브시스템명 | 학습데이터 저작도구 |
| 단계명  | 설계           | 작성일자   | 2026-08-06 | 버전 | 1.0 |

### 1. 사용자 인터페이스 구조도

> **▣ 전체 시스템에 대하여 1개 작성한다(반복 아님).** 저작도구 내부 채널(상위 시스템과 동일 도메인으로 세션을 인계받아 진입)의 좌측 메뉴(LNB) 트리를 최상위레벨에서 Top-down으로 기술한다. 본 산출물은 기능 요구사항을 실현하는 업무 화면만 다루므로, 구조도에도 해당 화면이 속한 메뉴 경로만 기재한다.

| 업무 영역 (Level 1) | Level 2 | Level 3 | Level 4 |
|-------------------|---------|---------|---------|
| 영상 | 영상 처리 현황 (KLID-AT-SC-007) | 영상 상세 (KLID-AT-SC-009) | 프레임 확대 보기 |
| 영상 | 영상 처리 현황 (KLID-AT-SC-007) | 마킹 (KLID-AT-SC-006) | - |
| 작업 | 검수 목록 (KLID-AT-SC-018) | 검수 상세 (KLID-AT-SC-019) | - |
| 작업 | 라벨링 캔버스 (KLID-AT-SC-005) | 버전 이력 패널 | 버전 비교·복구 |
| 데이터 | 증강 요청 (KLID-AT-SC-022) | 증강 결과 (KLID-AT-SC-023) | - |
| 관리 | 시스템 설정 (KLID-AT-SC-025) | - | - |
| 관리 | 라벨 관리 (KLID-AT-SC-036) | 속성 정의 패널 | - |
| 관리 | 비식별 신고 (KLID-AT-SC-033) | - | - |

> **메뉴 노출 정책**: 영상 상세·마킹·라벨링 캔버스·검수 상세·증강 결과는 좌측 메뉴에 직접 노출하지 않고 상위 목록·맥락 화면에서 진입한다. 데이터(증강) 영역과 관리 영역 전체는 검수자 전용으로 노출하며, 영상·작업 영역은 검수자·라벨링 작업자 공통으로 노출하되 배정·검수 동선은 검수자에게만 열린다.
> 외부 포털 채널은 별도 레이아웃(좌측 메뉴 없음)으로 운영되며 본 산출물의 범위에 포함하지 않는다(사유는 「부록」 참조).

### 2. 사용자 인터페이스 목록

#### 2.1 화면

| 화면 ID | 화면명 | 관련 유스케이스 ID | 관련 시퀀스도 ID |
|---------|-------|-----------------|-----------------|
| KLID-AT-SC-005 | 라벨링 캔버스 화면 | KLID-AT-UC-021, KLID-AT-UC-004, KLID-AT-UC-005, KLID-AT-UC-006, KLID-AT-UC-008, KLID-AT-UC-016, KLID-AT-UC-022 | KLID-AT-SD-021, KLID-AT-SD-004, KLID-AT-SD-005, KLID-AT-SD-006, KLID-AT-SD-008, KLID-AT-SD-016, KLID-AT-SD-022 |
| KLID-AT-SC-006 | 마킹 화면 | KLID-AT-UC-019, KLID-AT-UC-016 | KLID-AT-SD-019, KLID-AT-SD-016 |
| KLID-AT-SC-007 | 영상 목록 화면 | KLID-AT-UC-018, KLID-AT-UC-019 | KLID-AT-SD-018, KLID-AT-SD-019 |
| KLID-AT-SC-009 | 영상 상세 화면 | KLID-AT-UC-011, KLID-AT-UC-016 | KLID-AT-SD-011, KLID-AT-SD-016 |
| KLID-AT-SC-018 | 검수 목록 화면 | KLID-AT-UC-023 | KLID-AT-SD-023 |
| KLID-AT-SC-019 | 검수 상세 화면 | KLID-AT-UC-023, KLID-AT-UC-007, KLID-AT-UC-009 | KLID-AT-SD-023, KLID-AT-SD-007, KLID-AT-SD-009 |
| KLID-AT-SC-022 | 증강 요청 화면 | KLID-AT-UC-001, KLID-AT-UC-003 | KLID-AT-SD-001, KLID-AT-SD-003 |
| KLID-AT-SC-023 | 증강 결과 화면 | KLID-AT-UC-002, KLID-AT-UC-010 | KLID-AT-SD-002, KLID-AT-SD-010 |
| KLID-AT-SC-025 | 시스템 설정 화면 | KLID-AT-UC-006 | KLID-AT-SD-006 |
| KLID-AT-SC-033 | 비식별 신고 관리 화면 | KLID-AT-UC-016 | KLID-AT-SD-016 |
| KLID-AT-SC-036 | 라벨 관리 화면 | KLID-AT-UC-028 | KLID-AT-SD-028 |

> 유스케이스 ID와 시퀀스도 ID는 **동일 번호로 1:1 대응**한다(`KLID-AT-UC-NNN` ↔ `KLID-AT-SD-NNN`). 요구사항–유스케이스–화면 간 추적은 R3 요구사항 추적표에서 일괄 관리한다.

#### 2.2 출력물

| 출력물 ID | 출력물명 | 관련 유스케이스 ID | 관련 시퀀스도 ID |
|----------|--------|-----------------|-----------------|
| KLID-AT-OUT-001 | 라벨 버전 변경 내역(추가·수정·삭제 대비표) | KLID-AT-UC-008 | KLID-AT-SD-008 |
| KLID-AT-OUT-002 | 해상도 변경 파생 이미지셋 | KLID-AT-UC-003 | KLID-AT-SD-003 |

### 3. 화면 상세 설계

> **⟳ 화면별로 1개씩 반복 작성한다.** 화면 개요·와이어프레임·입출력 항목·처리 내용·기술적 고려사항을 같은 표 안에 구분해 포함한다.
> 입출력 항목 표에는 **데이터 입출력 필드만** 기재하며, 버튼·도구·모달 등 조작 요소의 동작은 처리 내용에 기술한다.
> 속성 표기: 입출력 구분 `I`(Input)·`O`(Output)·`IO`(Input+Output) / 편집 속성 `R`(ReadOnly)·`E`(Editable)·`H`(Hidden).

#### KLID-AT-SC-005 라벨링 캔버스 화면

<table>
<tr><td width="15%">화면 ID</td><td width="35%">KLID-AT-SC-005</td><td width="15%">화면명</td><td width="35%">라벨링 캔버스 화면</td></tr>
<tr><td>관련 유스케이스 ID</td><td colspan="3">KLID-AT-UC-021, KLID-AT-UC-004, KLID-AT-UC-005, KLID-AT-UC-006, KLID-AT-UC-008, KLID-AT-UC-016, KLID-AT-UC-022</td></tr>
<tr><td>관련 시퀀스도 ID</td><td colspan="3">KLID-AT-SD-021, KLID-AT-SD-004, KLID-AT-SD-005, KLID-AT-SD-006, KLID-AT-SD-008, KLID-AT-SD-016, KLID-AT-SD-022</td></tr>
<tr><td>화면유형</td><td>입력·조회·갱신·삭제</td><td>메뉴경로</td><td>작업 &gt; 라벨링 캔버스</td></tr>
<tr><td>화면개요</td><td colspan="3">배정된 영상의 프레임에 바운딩박스·폴리곤·영역분할·관절 포즈 라벨과 객체 속성값을 편집하고 작업본을 임시저장하는 전체 화면 편집 도구다. AI 보조 라벨링(객체 자동 추적·외곽 경계 자동 밀착·자동 탐지)과 정밀도 조절, 프레임 설명·시계열 메타·이벤트 어노테이션 검토, 개인정보·촬영환경 메타 입력, 버전 이력 비교·복구, 이슈 소통, 비식별 누락 신고를 제공한다. 라벨링 작업자와 검수자가 사용한다.</td></tr>
<tr><td colspan="4">

```html
<div style="background:#1f2937;color:#d1d5db;border:1px solid #374151;font-family:sans-serif;font-size:11px;">
  <!-- 라벨링 헤더 바 -->
  <div style="height:40px;background:#1f2937;border-bottom:1px solid #374151;display:flex;align-items:center;padding:0 10px;gap:8px;">
    <span>✕</span>
    <span style="color:#9ca3af;">CCTV-01 / Frame 12</span>
    <span style="background:#3730a3;color:#c7d2fe;padding:1px 6px;border-radius:3px;">쓰러짐</span>
    <span style="flex:1;"></span>
    <span>Frame 12 / 60</span>
    <span style="color:#60a5fa;">● 편집 중</span>
    <span style="background:#374151;padding:1px 6px;border-radius:3px;">비식별본</span>
    <!-- [작업자·검수자 한정] -->
    <span style="background:#374151;padding:2px 8px;border-radius:3px;">비식별 누락 신고</span>
    <span style="background:#374151;padding:2px 8px;border-radius:3px;">히스토리</span>
    <span style="background:#2563eb;color:white;padding:2px 8px;border-radius:3px;">저장</span>
    <!-- [작업자 한정] -->
    <span style="background:#2563eb;color:white;padding:2px 8px;border-radius:3px;">검수제출</span>
  </div>
  <!-- 본문 -->
  <div style="display:flex;height:300px;">
    <!-- 좌측 도구바 -->
    <div style="width:52px;background:#111827;border-right:1px solid #374151;padding-top:8px;text-align:center;line-height:2.0;color:#9ca3af;">
      선택<br>박스<br>폴리곤<br>분할<br>추적<br>키포인트<br>자동탐지<br>삭제<br>취소
    </div>
    <!-- 라벨 마스터 사이드바 -->
    <div style="width:118px;background:#1f2937;border-right:1px solid #374151;padding:8px;">
      <div style="color:#9ca3af;margin-bottom:6px;">라벨 마스터</div>
      <div>■ 사람 (1)</div>
      <div>■ 차량 (2)</div>
      <div>■ 자전거 (3)</div>
    </div>
    <!-- 캔버스 -->
    <div style="flex:1;background:#0f172a;display:flex;align-items:center;justify-content:center;color:#475569;">라벨링 캔버스 (이미지 · 라벨 · 오버레이)</div>
    <!-- 우측 패널 -->
    <div style="width:206px;background:#1f2937;border-left:1px solid #374151;padding:8px;">
      <div style="display:flex;gap:8px;border-bottom:1px solid #374151;padding-bottom:4px;">
        <span style="border-bottom:2px solid #2563eb;">객체</span><span style="color:#9ca3af;">이슈 <span style="background:#b91c1c;color:#fff;padding:0 4px;border-radius:8px;">2</span></span>
      </div>
      <div style="margin-top:6px;color:#9ca3af;">객체 목록</div>
      <div>✏️ 사람 #1 (BBOX)</div>
      <div style="margin-top:6px;color:#9ca3af;">객체 속성 (X/Y/W/H · 라벨 · 신뢰도)</div>
      <div style="margin-top:6px;color:#9ca3af;">프레임 설명</div>
      <div style="margin-top:6px;color:#9ca3af;">시계열 메타</div>
      <div style="margin-top:6px;color:#9ca3af;">이벤트 어노테이션</div>
      <div style="margin-top:6px;color:#9ca3af;">개인정보 · 촬영환경 메타</div>
    </div>
    <!-- [선택: 히스토리 토글 시] 버전 이력 패널 -->
    <div style="width:150px;background:#111827;border-left:1px solid #374151;padding:8px;">
      <div style="color:#9ca3af;">버전 이력</div>
      <div>☐ a1b2c3 <span style="background:#065f46;color:#d1fae5;padding:0 4px;">최신</span></div>
      <div>☐ d4e5f6</div>
      <div style="margin-top:6px;color:#9ca3af;">변경 내용</div>
      <div style="color:#34d399;">＋ 추가 1건</div>
      <div style="color:#fbbf24;">~ 수정 2건</div>
      <div style="margin-top:6px;text-align:right;"><span style="background:#374151;padding:2px 6px;">복구</span></div>
    </div>
  </div>
  <!-- 하단 프레임 타임라인 -->
  <div style="height:44px;background:#111827;border-top:1px solid #374151;display:flex;align-items:center;padding:0 10px;color:#475569;">프레임 썸네일 스트립 · 프레임 슬라이더</div>
</div>
```

</td></tr>
<tr><td colspan="4" align="center"><b>입출력 항목</b></td></tr>
<tr><td colspan="4">

| 항목명 | 컨트롤명 | 타입 및 길이 | 속성 | Validation Check |
|---|---|---|---|---|
| 활성 라벨 | activeLabelId | BIGINT | I / E | 필수, 사용 중인 라벨 마스터 1건 |
| 객체 라벨 | classId | BIGINT | IO / E | 필수, 라벨 마스터 목록에 존재하는 값 |
| 객체 좌측 좌표 | left | NUMERIC(10,2) | IO / E | 0 이상, 이미지 너비 이하 |
| 객체 상단 좌표 | top | NUMERIC(10,2) | IO / E | 0 이상, 이미지 높이 이하 |
| 객체 우측 좌표 | right | NUMERIC(10,2) | IO / E | 좌측 좌표보다 큰 값, 이미지 너비 이하 |
| 객체 하단 좌표 | bottom | NUMERIC(10,2) | IO / E | 상단 좌표보다 큰 값, 이미지 높이 이하 |
| 폴리곤 정점 좌표 | points | JSONB | IO / E | 정점 3개 이상 |
| 관절 포즈 좌표 | keypoints | JSONB | IO / E | 관절 17개, 각 항목 [X, Y, 가시성(0·1·2)] |
| 객체 속성값 | attrValue | VARCHAR(255) | IO / E | 속성 정의의 입력 형식·선택지 준수, 255자 이하 |
| 트랙 식별자 | trackId | VARCHAR(50) | O / R | - |
| 신뢰도 | confScore | NUMERIC(5,2) | O / R | - |
| 생성 출처 | lblSrcCd | VARCHAR(20) | O / R | - |
| 자동 탐지 도형 | shape | VARCHAR(10) | I / E | 필수, 박스·폴리곤 중 택1(기본 박스) |
| 자동 탐지 대상 라벨 | classIds | JSONB | I / E | 선택, 미지정 시 전체 탐지 클래스 |
| 인식 민감도 | confThreshold | NUMERIC(3,2) | I / E | 0.25 ~ 0.80 |
| 경계 세밀함 | simplifyTolerance | NUMERIC(4,1) | I / E | 0.0 ~ 50.0, 도형이 폴리곤일 때만 |
| 프레임 설명 | text | VARCHAR(1000) | IO / E | 1000자 이하, 빈 값 저장 시 삭제 |
| 시계열 메타 텍스트 | drafts | VARCHAR(2000) | IO / E | 항목당 2000자 이하 |
| 이벤트 어노테이션 분류 | eventClass | VARCHAR(100) | IO / E | 필수, 100자 이하 |
| 이벤트 어노테이션 설명 | caption | VARCHAR(4000) | IO / E | 4000자 이하 |
| 이벤트 어노테이션 반려 사유 | rejectReason | VARCHAR(1000) | I / E | 반려 시 필수, 1 ~ 1000자 |
| 익명처리 여부 | anonymity | BOOLEAN | IO / E | 사용자가 선택하지 않은 값은 전송하지 않음 |
| 가명처리 여부 | pseudonymity | BOOLEAN | IO / E | 사용자가 선택하지 않은 값은 전송하지 않음 |
| 개인정보 포함 여부 | privacyIncluded | BOOLEAN | IO / E | 사용자가 선택하지 않은 값은 전송하지 않음 |
| 촬영 날씨 | weather | VARCHAR(20) | IO / E | 제공 선택지 중 택1 |
| 촬영 시간대 | timeOfDay | VARCHAR(20) | IO / E | 제공 선택지 중 택1 |
| 촬영 계절 | season | VARCHAR(20) | IO / E | 제공 선택지 중 택1 |
| 비교 대상 버전 | selectedVersions | JSONB | I / E | 최대 2건, 현재 목록에 존재하는 버전만 |
| 버전 변경 내역 | diffItems | JSONB | O / R | - |
| 이슈 내용 | content | VARCHAR(1000) | I / E | 필수, 1 ~ 1000자 |
| 비식별 누락 신고 사유 | reason | VARCHAR(1000) | I / E | 필수, 1 ~ 1000자 |
| 현재 프레임 번호 | currentFrameNo | INT | O / R | - |
| 프레임 이미지 종류 | frameImageType | VARCHAR(10) | O / R | - |

*속성: 입출력 구분 I(Input)·O(Output)·IO(Input+Output) / 편집 속성 R(ReadOnly)·E(Editable)·H(Hidden)*

</td></tr>
<tr><td colspan="4" align="center"><b>처리 내용</b></td></tr>
<tr><td colspan="4">

- 화면 진입 (KLID-AT-SD-021)
. 본인에게 배정된 프레임인지 확인한 뒤 프레임 이미지와 라벨 목록, 라벨 마스터, 동일 영상의 프레임 목록을 조회해 캔버스와 우측 패널에 표시한다. 배정되지 않은 프레임 접근은 거부하고, 비식별 재처리 대기 중인 영상은 라벨 조회를 차단하며 안내 문구를 표시한다.
- 라벨 편집 (KLID-AT-SD-021)
. 좌측 도구(선택·박스·폴리곤·영역분할·추적·관절 포즈·삭제)로 객체를 생성·이동·수정·삭제하고, 우측 패널에서 라벨 종류·좌표·속성값을 편집한다. 좌표는 항상 이미지 실측 해상도 범위로 보정하며, 되돌리기·다시실행은 화면 세션 안에서 처리한다.
- 저장 (KLID-AT-SD-021)
. 현재 편집 결과 전체를 작업본으로 임시저장한다. 저장 시 변경 유형(추가·수정·삭제)이 라벨 변경 이력에 기록되며, 학습데이터 버전 스냅샷은 생성하지 않는다. 비식별 재처리 대기 구간에서는 저장이 차단된다. 미저장 상태에서 화면을 닫으려 하면 저장 여부를 확인한다.
- 자동 탐지 실행 (KLID-AT-SD-004, KLID-AT-SD-006)
. 도형(박스·폴리곤)과 대상 라벨, 인식 민감도·경계 세밀함을 지정해 현재 프레임의 객체를 자동 탐지한다. 결과는 저장하지 않고 좌표만 회신되어 캔버스 작업본에 병합되며, 기존 라벨은 보존하고 중복 검출은 제외한다. 저장을 눌러야 확정된다.
- 객체 자동 추적 (KLID-AT-SD-004)
. 시작 프레임에서 지정한 객체를 후속 프레임으로 자동 추적·전파해 위치와 경계를 갱신한다. 출력 도형은 선택한 객체의 도형을 따르며, 결과가 없는 중간 프레임은 추적 보간으로 채운다.
- 객체 외곽 경계 자동 밀착 (KLID-AT-SD-005, KLID-AT-SD-006)
. 캔버스에서 클릭 또는 박스로 객체를 한 번 지정하면 외곽 경계 폴리곤과 신뢰도를 산출해 경계에 밀착된 라벨로 적용한다. 산출된 폴리곤은 정밀도 설정값에 따라 단순화되며, 인식에 실패하면 수동 폴리곤 작성으로 전환한다.
- 트랙 편집(삭제·분할·병합) (KLID-AT-SD-021)
. 객체 목록에서 트랙 단위로 지정 프레임 이후 삭제, 특정 프레임 기준 분할, 두 트랙 병합을 수행하고 자동 생성 구간을 재보간한다. 동시 편집 충돌을 막기 위해 처리 중에는 해당 영상을 잠근다.
- 프레임 설명 저장 (KLID-AT-SD-021)
. 프레임 단위 설명을 입력·수정해 저장한다. 빈 값으로 저장하면 기존 설명을 삭제한다.
- 시계열 메타 검토·저장 (KLID-AT-SD-022)
. 외부 분석 서비스가 회신한 시계열 메타를 항목별로 조회해 검토·수정한 뒤 변경된 항목만 저장한다. 메타가 없는 영상은 신규 입력 슬롯 1개를 제공한다.
- 이벤트 어노테이션 검토 (KLID-AT-SD-022)
. 이벤트 분류·설명·근거 객체를 확인하고 설명을 직접 입력·수정한다. 검수자는 승인 또는 사유를 적어 반려하며, 승인된 어노테이션만 학습데이터 산출물에 반영된다.
- 개인정보·촬영환경 메타 입력 (KLID-AT-SD-021)
. 프레임 단위 익명처리·가명처리·개인정보 포함 여부와 촬영 날씨·시간대·계절을 선택해 저장한다. 사용자가 직접 고르지 않은 항목은 전송하지 않아 기본 판정값이 사람의 판정으로 승격되지 않게 한다. 비식별 재처리 대기 구간에서는 개인정보 항목 저장이 차단된다.
- 버전 이력 조회·비교 (KLID-AT-SD-008)
. 검수 승인 시점에 확정된 라벨 버전 목록을 최신순으로 조회한다. 버전 1건을 선택하면 그 버전과 현재 작업본을, 2건을 선택하면 두 버전 사이를 비교해 추가·수정·삭제 항목을 색상으로 구분해 표시한다(출력물 KLID-AT-OUT-001). 변경이 없으면 "변경 없음"으로 안내하고, 조회 실패는 변경 없음과 구분해 오류로 표시한다.
- 버전 복구 (KLID-AT-SD-008)
. 최신이 아닌 버전을 선택하면 복구를 제공하며, 확인 시 해당 스냅샷을 다시 활성 버전으로 되돌리고 라벨 본문을 작업본으로 복원한다. 새 버전을 쌓지 않으며, 이미 그 스냅샷이 활성이면 아무 것도 바뀌지 않는다. 작업자는 본인 배정 영상만 복구할 수 있다.
- 이슈 소통 (KLID-AT-SD-023)
. 우측 이슈 탭에서 검수자와 작업자가 프레임 단위 이슈를 주고받고, 미해소 문의 건수를 배지로 표시한다. 영상 맥락이 없는 진입에서는 탭을 노출하지 않는다.
- 비식별 누락 신고 (KLID-AT-SD-016)
. 개인정보 노출을 발견하면 사유를 입력해 신고한다. 신고 즉시 영상이 잠기고 라벨 조회·저장이 차단되나 라벨 작업 결과는 보존되며, 재비식별이 해소되면 보존된 라벨을 그대로 이어 작업한다. 중복 신고와 타인 배정 영상 신고는 거부한다. 파생영상은 신고 대상이 아니므로 버튼을 비활성화하고 사유를 안내한다.
- 검수 제출 (KLID-AT-SD-023)
. 임시저장된 라벨을 영상 단위 검수 대기 목록으로 제출한다. 라벨링 작업자에게만 제공한다.
- 프레임 이동 (KLID-AT-SD-021)
. 하단 썸네일 스트립·슬라이더 또는 좌우 방향키로 같은 영상의 다른 프레임으로 이동하고, 이동한 프레임의 라벨을 다시 조회한다.

</td></tr>
<tr><td colspan="4" align="center"><b>기술적 고려사항</b></td></tr>
<tr><td colspan="4">

- 캔버스는 컨테이너 크기 변화를 자동 측정해 반응형으로 렌더링하고, 이미지·라벨·오버레이를 계층으로 분리해 편집 성능을 확보한다.
- 프레임 이미지는 인증 토큰을 포함해 내려받아 표시하며, 기본적으로 비식별 프레임만 제공한다.
- AI 보조 라벨링은 외부 추론 호출이므로 타임아웃·재시도·장애 차단 정책을 적용하고, 실패 시 기존 라벨을 보존한다.
- AI 보조 결과는 즉시 저장하지 않고 작업본에 병합만 하며, 사용자가 저장해야 확정된다(배치 자동 라벨링은 별도로 저장을 유지한다).
- 비식별 누락 신고 접수처럼 서버가 이후 요청을 거부하게 되는 변화가 발생하면, 화면이 보관 중인 조회 결과를 갱신 표시가 아니라 **제거**해 캐시된 라벨 좌표가 다시 그려지지 않게 한다.
- 사용자 입력 문자열은 모두 텍스트로만 렌더링해 스크립트 삽입을 방어하고, 입력 길이는 화면과 서버에서 이중 검증한다.

</td></tr>
</table>

#### KLID-AT-SC-006 마킹 화면

<table>
<tr><td width="15%">화면 ID</td><td width="35%">KLID-AT-SC-006</td><td width="15%">화면명</td><td width="35%">마킹 화면</td></tr>
<tr><td>관련 유스케이스 ID</td><td colspan="3">KLID-AT-UC-019, KLID-AT-UC-016</td></tr>
<tr><td>관련 시퀀스도 ID</td><td colspan="3">KLID-AT-SD-019, KLID-AT-SD-016</td></tr>
<tr><td>화면유형</td><td>입력·조회</td><td>메뉴경로</td><td>영상 &gt; 영상 처리 현황 &gt; 마킹</td></tr>
<tr><td>화면개요</td><td colspan="3">비식별 영상을 구간 스트리밍으로 재생·배속 조절하며 이벤트 시점을 자동(프레임 간격) 또는 수동(단축키)으로 마킹하는 화면. 이벤트명은 화면에서 입력받지 않고 상위 시스템이 전달한 클립 메타에서 자동으로 가져온다. 마킹 완료를 신호로 잔여 배치 처리가 시작되며, 마킹 중 발견한 비식별 누락을 영상 단위로 신고할 수 있다.</td></tr>
<tr><td colspan="4">

```html
<div style="background:#f9fafb;color:#1f2937;border:1px solid #e5e7eb;font-family:sans-serif;font-size:11px;">
  <div style="height:48px;background:white;border-bottom:1px solid #e5e7eb;display:flex;align-items:center;padding:0 16px;">
    <span style="font-size:14px;font-weight:600;">마킹 — CCTV-01</span>
    <span style="flex:1;"></span>
    <span style="background:#e5e7eb;padding:3px 10px;border-radius:4px;">비식별 누락 신고</span>
  </div>
  <div style="padding:16px;display:flex;gap:12px;">
    <!-- 좌: 플레이어 + 타임라인 + 툴바 -->
    <div style="flex:1;">
      <div style="height:190px;background:#0f172a;border-radius:6px;display:flex;align-items:center;justify-content:center;color:#64748b;">영상 플레이어 (비식별 영상 · 구간 스트리밍)</div>
      <div style="margin-top:8px;display:flex;gap:6px;align-items:center;">
        <span style="background:#e5e7eb;padding:2px 8px;border-radius:3px;">▶ 재생</span>
        <span>배속</span><span style="background:#e5e7eb;padding:1px 6px;">0.25x</span><span style="background:#2563eb;color:white;padding:1px 6px;">1x</span><span style="background:#e5e7eb;padding:1px 6px;">4x</span>
        <span style="flex:1;"></span><span>00:12 / 01:00</span>
      </div>
      <!-- 마킹 타임라인 -->
      <div style="margin-top:8px;height:18px;background:#e5e7eb;border-radius:3px;position:relative;">
        <span style="position:absolute;left:12%;top:0;width:2px;height:18px;background:#2563eb;"></span>
        <span style="position:absolute;left:38%;top:0;width:2px;height:18px;background:#2563eb;"></span>
      </div>
      <!-- 마킹 툴바 -->
      <div style="margin-top:10px;background:white;border:1px solid #e5e7eb;border-radius:6px;padding:8px;">
        <span style="background:#2563eb;color:white;padding:1px 6px;">자동</span> <span style="background:#e5e7eb;padding:1px 6px;">수동</span>
        &nbsp; 간격(프레임): <span style="border:1px solid #d1d5db;padding:1px 8px;">300</span>
        &nbsp; <span style="color:#6b7280;">단축키: Space 마킹 / Del 삭제 / Enter 완료</span>
        &nbsp; <span style="background:#e5e7eb;padding:2px 8px;">초기화</span> <span style="background:#2563eb;color:white;padding:2px 8px;">마킹 완료</span>
      </div>
      <!-- 배치 단계 인디케이터 -->
      <div style="margin-top:10px;background:white;border:1px solid #e5e7eb;border-radius:6px;padding:8px;color:#6b7280;">
        처리 단계: 비식별 ✓ · 마킹 ● · 시계열 메타 ○ · 프레임추출 ○ · 자동탐지 ○ · 영역분할 ○ · 보간 ○
      </div>
    </div>
    <!-- 우: 현재 마킹 칩 -->
    <div style="width:170px;background:white;border:1px solid #e5e7eb;border-radius:6px;padding:8px;">
      <div style="color:#6b7280;margin-bottom:6px;">현재 마킹 (2건)</div>
      <div style="background:#eff6ff;border:1px solid #bfdbfe;border-radius:4px;padding:2px 6px;margin-bottom:4px;">F90 (00:03)</div>
      <div style="background:#eff6ff;border:1px solid #bfdbfe;border-radius:4px;padding:2px 6px;">F360 (00:12)</div>
    </div>
  </div>
</div>
```

</td></tr>
<tr><td colspan="4" align="center"><b>입출력 항목</b></td></tr>
<tr><td colspan="4">

| 항목명 | 컨트롤명 | 타입 및 길이 | 속성 | Validation Check |
|---|---|---|---|---|
| 마킹 방식 | mode | VARCHAR(10) | I / E | 필수, 자동·수동 중 택1 |
| 자동 마킹 간격(프레임) | intervalFrames | INT | I / E | 자동 방식 시 필수, 1 ~ 3600 정수 |
| 재생 배속 | playbackRate | NUMERIC(3,2) | I / E | 0.25 · 0.5 · 1 · 1.5 · 2 · 4 중 택1 |
| 재생 위치(초) | currentTime | NUMERIC(10,3) | IO / E | 0 이상, 영상 길이 이하 |
| 현재 마킹 목록 | localMarks | JSONB | O / R | - |
| 선택 마킹 순번 | selectedMarkIndex | INT | I / E | 현재 마킹 목록 범위 내 |
| 영상 길이(초) | durationSec | INT | O / R | - |
| 배치 처리 단계 | stages | JSONB | O / R | - |
| 비식별 누락 신고 사유 | reason | VARCHAR(1000) | I / E | 필수, 1 ~ 1000자 |

*속성: 입출력 구분 I(Input)·O(Output)·IO(Input+Output) / 편집 속성 R(ReadOnly)·E(Editable)·H(Hidden)*

</td></tr>
<tr><td colspan="4" align="center"><b>처리 내용</b></td></tr>
<tr><td colspan="4">

- 영상 로드·재생 (KLID-AT-SD-019)
. 대상 영상의 비식별본을 구간 스트리밍으로 재생한다. 재생·일시정지, 배속 조절, 탐색 슬라이더 이동을 제공하고 현재 시각과 총 길이를 표시한다. 비식별이 완료되지 않은 영상과 비식별 재처리 대기 중인 영상은 재생 대상이 되지 않는다.
- 이벤트 시점 마킹 (KLID-AT-SD-019)
. 자동 방식은 지정한 프레임 간격으로 마킹을 일괄 생성하고, 수동 방식은 단축키로 재생 중인 시점을 마킹한다. 마킹은 타임라인 막대와 칩으로 표시되며 선택·삭제할 수 있다. 이벤트명은 화면에서 입력받지 않고 상위 시스템이 전달한 클립 메타에서 가져온다.
- 마킹 초기화 (KLID-AT-SD-019)
. 아직 제출하지 않은 마킹을 전부 비운다.
- 마킹 완료 (KLID-AT-SD-019)
. 수동 방식은 마킹이 1건 이상일 때, 자동 방식은 간격이 유효할 때 마킹을 저장한다. 저장 완료를 기점으로 잔여 배치(시계열 메타 → 프레임 추출 → 자동 라벨링 → 추적 보간)가 비동기로 시작되며, 저장된 마킹 위치가 이후 프레임 추출의 기준이 된다. 영상 길이를 확정하지 못하면 자동 마킹은 거부한다.
- 배치 처리 단계 확인 (KLID-AT-SD-019)
. 대상 영상의 처리 단계와 진행률을 조회해 비식별부터 추적 보간까지의 진행 상태를 표시한다.
- 비식별 누락 신고 (KLID-AT-SD-016)
. 재생 중 개인정보 노출을 발견하면 사유를 입력해 영상 단위로 신고한다. 신고 즉시 영상이 잠기고 재생·라벨 조회가 차단되어 마킹을 이어갈 수 없으며, 검수자가 외부 비식별 처리 완료 후 해소하면 비식별 결과 위에서 마킹부터 다시 수행한다. 마킹 진입이 가능한 상태가 아니면 신고 버튼을 비활성화한다.

</td></tr>
<tr><td colspan="4" align="center"><b>기술적 고려사항</b></td></tr>
<tr><td colspan="4">

- 영상은 항상 비식별본만 구간 스트리밍으로 서빙하고, 비식별 미완료 영상은 존재 여부조차 노출하지 않는다.
- 스트리밍 응답은 저장 금지 정책을 적용해, 신고 접수 직후 클라이언트에 남은 응답이 재사용되지 않게 한다.
- 마킹 위치는 이후 프레임 추출의 기준이므로 재생 시각이 아닌 정확한 프레임 번호로 기록한다.
- 마킹 완료 이후 배치는 비동기로 진행되어 화면 응답을 붙잡지 않는다.
- 영상 길이를 응답에서 얻지 못하면 별도 산출 경로로 보완하되, 그 산출은 저장 트랜잭션 밖에서 수행한다.

</td></tr>
</table>

#### KLID-AT-SC-007 영상 목록 화면

<table>
<tr><td width="15%">화면 ID</td><td width="35%">KLID-AT-SC-007</td><td width="15%">화면명</td><td width="35%">영상 목록 화면</td></tr>
<tr><td>관련 유스케이스 ID</td><td colspan="3">KLID-AT-UC-018, KLID-AT-UC-019</td></tr>
<tr><td>관련 시퀀스도 ID</td><td colspan="3">KLID-AT-SD-018, KLID-AT-SD-019</td></tr>
<tr><td>화면유형</td><td>조회·갱신</td><td>메뉴경로</td><td>영상 &gt; 영상 처리 현황</td></tr>
<tr><td>화면개요</td><td colspan="3">상위 시스템에서 인계받아 적재된 영상의 처리 상태와 단계를 조회하는 목록 화면. 검수자는 목록에서 마킹 진입(자동·수동), 작업자 재배정, 선택 영상 일괄 배정을 수행한다. 조회는 검수자·라벨링 작업자 공통이며, 마킹·배정 동선은 검수자 전용이다.</td></tr>
<tr><td colspan="4">

```html
<div style="background:#f9fafb;color:#1f2937;border:1px solid #e5e7eb;font-family:sans-serif;font-size:11px;">
  <div style="display:flex;height:420px;">
    <div style="width:190px;background:#1f2937;color:#d1d5db;padding:12px;">
      <div style="color:#9ca3af;margin-bottom:8px;">메뉴</div>
      <div style="color:#9ca3af;font-size:10px;margin-top:6px;">영상</div>
      <div style="padding:6px 8px;background:#374151;border-radius:4px;">영상 처리 현황</div>
    </div>
    <div style="flex:1;display:flex;flex-direction:column;">
      <div style="height:52px;background:white;border-bottom:1px solid #e5e7eb;padding:0 16px;display:flex;align-items:center;">
        <span style="font-size:14px;font-weight:600;">영상 처리 현황</span>
        <span style="flex:1;"></span><span style="background:#e5e7eb;padding:4px 10px;border-radius:4px;">새로고침</span>
      </div>
      <div style="flex:1;padding:16px;background:#f9fafb;overflow:auto;">
        <!-- 검색·필터 -->
        <div style="background:white;border:1px solid #e5e7eb;border-radius:6px;padding:10px;display:flex;gap:6px;align-items:center;">
          <span style="border:1px solid #d1d5db;padding:3px 8px;">CCTV명 / 영상ID</span>
          <span style="border:1px solid #d1d5db;padding:3px 8px;">상태 ▾</span>
          <span style="border:1px solid #d1d5db;padding:3px 8px;">이벤트 유형 ▾</span>
          <span style="border:1px solid #d1d5db;padding:3px 8px;">시작일</span>
          <span style="border:1px solid #d1d5db;padding:3px 8px;">종료일</span>
          <span style="background:#2563eb;color:white;padding:3px 10px;">조회</span>
          <span style="background:#e5e7eb;padding:3px 10px;">초기화</span>
        </div>
        <!-- [검수자 한정] 일괄 배정 바 -->
        <div style="margin-top:8px;background:#eff6ff;border:1px solid #bfdbfe;border-radius:6px;padding:6px 10px;">선택 2건 → <span style="background:#2563eb;color:white;padding:2px 8px;">2개 일괄 배정</span></div>
        <!-- 데이터 테이블 -->
        <div style="margin-top:8px;background:white;border:1px solid #e5e7eb;border-radius:6px;">
          <div style="display:flex;background:#f3f4f6;padding:6px 10px;font-weight:600;">
            <span style="width:24px;">☑</span><span style="flex:1;">CCTV명</span><span style="width:70px;">이벤트</span><span style="width:70px;">녹화일</span><span style="width:50px;">길이</span><span style="width:80px;">처리 단계</span><span style="width:80px;">배정자</span><span style="width:90px;">액션</span>
          </div>
          <div style="display:flex;padding:6px 10px;border-top:1px solid #f3f4f6;">
            <span style="width:24px;">☐</span><span style="flex:1;">CCTV-01</span><span style="width:70px;">쓰러짐</span><span style="width:70px;">06-20</span><span style="width:50px;">60s</span><span style="width:80px;">마킹 대기</span><span style="width:80px;font-style:italic;color:#9ca3af;">미배정</span><span style="width:90px;"><span style="background:#2563eb;color:white;padding:1px 6px;">마킹</span></span>
          </div>
          <div style="display:flex;padding:6px 10px;border-top:1px solid #f3f4f6;">
            <span style="width:24px;">☐</span><span style="flex:1;">CCTV-02</span><span style="width:70px;">교통사고</span><span style="width:70px;">06-19</span><span style="width:50px;">45s</span><span style="width:80px;">완료</span><span style="width:80px;">김작업</span><span style="width:90px;"><span style="background:#e5e7eb;padding:1px 6px;">재배정</span></span>
          </div>
        </div>
        <div style="margin-top:8px;text-align:center;color:#6b7280;">‹ 1 2 3 ›</div>
      </div>
    </div>
  </div>
</div>
```

</td></tr>
<tr><td colspan="4" align="center"><b>입출력 항목</b></td></tr>
<tr><td colspan="4">

| 항목명 | 컨트롤명 | 타입 및 길이 | 속성 | Validation Check |
|---|---|---|---|---|
| CCTV명·영상ID 검색어 | keyword | VARCHAR(100) | I / E | 100자 이하 |
| 처리 상태 | status | VARCHAR(20) | I / E | 전체·완료·처리중·대기·실패 중 택1 |
| 이벤트 유형 | eventTypeCd | VARCHAR(20) | I / E | 제공 이벤트 유형 목록 중 택1 |
| 조회 시작일 | from | DATE | I / E | 날짜 형식, 종료일 이전 |
| 조회 종료일 | to | DATE | I / E | 날짜 형식, 시작일 이후 |
| 페이지 번호 | page | INT | IO / E | 0 이상 정수 |
| 선택 영상 | selected | JSONB | I / E | 일괄 배정 시 1건 이상 |
| 영상 목록 | content | JSONB | O / R | - |
| 배정자명 | workerName | VARCHAR(50) | O / R | - |
| 전체 건수 | totalElements | INT | O / R | - |
| 배정 대상 작업자 | workerId | BIGINT | I / E | 배정 시 필수, 작업자 목록 중 1건 |
| 배정 검수자 | reviewerId | BIGINT | I / E | 선택, 미지정 시 로그인 사용자 |
| 자동 마킹 간격(프레임) | intervalFrames | INT | I / E | 자동 마킹 선택 시 필수, 1 이상 정수 |

*속성: 입출력 구분 I(Input)·O(Output)·IO(Input+Output) / 편집 속성 R(ReadOnly)·E(Editable)·H(Hidden)*

</td></tr>
<tr><td colspan="4" align="center"><b>처리 내용</b></td></tr>
<tr><td colspan="4">

- 영상 목록 조회(조회·새로고침) (KLID-AT-SD-018)
. 검색어·처리 상태·이벤트 유형·기간 조건으로 영상 목록을 페이지 단위로 조회해 CCTV명·이벤트·녹화일·길이·처리 단계·배정자를 표시한다. 필터와 페이지 조건은 화면 주소에 반영해 새로고침·뒤로가기에서도 유지하며, 조회 조건 변경 시 첫 페이지로 되돌린다. 이벤트 유형 필터는 표시명이 같은 유형들을 한 항목으로 묶어 제공하고, 선택 시 묶인 유형 전체를 대상으로 조회한다.
- 조건 초기화 (KLID-AT-SD-018)
. 검색어와 모든 필터를 기본값으로 되돌리고 목록을 다시 조회한다.
- 마킹 진입(검수자 전용) (KLID-AT-SD-019)
. 미배정 상태이면서 마킹이 가능한 영상에 마킹 방식 선택 창을 연다. 자동을 선택하면 프레임 간격을 입력받아 마킹을 즉시 생성하고 목록을 갱신한다. 수동을 선택하면 작업자 배정 창으로 이어져 배정된 작업자가 마킹 화면에서 직접 마킹한다.
- 작업자 재배정(검수자 전용) (KLID-AT-SD-018)
. 이미 배정된 영상의 작업자를 변경한다. 검수 승인으로 완료된 작업은 재배정 대상에서 제외한다.
- 일괄 배정(검수자 전용) (KLID-AT-SD-018)
. 행 선택으로 1건 이상을 고르면 일괄 배정 바가 나타나고, 선택한 영상 전체에 작업자를 한 번에 배정한다. 성공 시 선택을 해제하고 배정자명을 즉시 갱신한다.
- 영상 상세 이동(행 클릭) (KLID-AT-SD-018)
. 선택한 영상의 상세 화면(KLID-AT-SC-009)으로 이동한다.

</td></tr>
<tr><td colspan="4" align="center"><b>기술적 고려사항</b></td></tr>
<tr><td colspan="4">

- 영상 적재는 상위 시스템의 학습용 설정을 주기 배치가 픽업하는 방식이므로, 목록은 적재·처리 진행 상태를 그대로 반영해 표시한다.
- 목록·집계·필터는 서버가 전체 데이터를 기준으로 계산하며, 현재 페이지 안에서 다시 거르지 않는다.
- 정렬 키는 허용 목록으로만 해석하고 등록되지 않은 키는 거부해, 조회 조건을 통한 임의 질의 조작을 차단한다.
- 개인정보 유무는 상위 시스템이 실제로 전달하지 않는 값이므로 목록에 표시하지 않는다.
- 배정 권한은 검수자로 제한하고 서버에서도 동일 권한을 재검증한다.

</td></tr>
</table>

#### KLID-AT-SC-009 영상 상세 화면

<table>
<tr><td width="15%">화면 ID</td><td width="35%">KLID-AT-SC-009</td><td width="15%">화면명</td><td width="35%">영상 상세 화면</td></tr>
<tr><td>관련 유스케이스 ID</td><td colspan="3">KLID-AT-UC-011, KLID-AT-UC-016</td></tr>
<tr><td>관련 시퀀스도 ID</td><td colspan="3">KLID-AT-SD-011, KLID-AT-SD-016</td></tr>
<tr><td>화면유형</td><td>조회·갱신</td><td>메뉴경로</td><td>영상 &gt; 영상 처리 현황 &gt; 영상 상세</td></tr>
<tr><td>화면개요</td><td colspan="3">영상 메타 정보, 배치 처리 단계(비식별 포함), 추출 프레임 미리보기, 자동 라벨링 결과를 탭으로 조회하는 상세 화면. 프레임 확대 보기에서 라벨링 편집으로 이동하며, 검수 완료됐으나 비식별이 완료되지 않은 영상은 검수자가 재비식별을 요청할 수 있다.</td></tr>
<tr><td>탭 구성</td><td colspan="3">

| 탭 | 레이아웃/구성 |
|---|---|
| 기본 정보 | 썸네일 + 메타 2열 그리드(CCTV 식별자·해상도·길이·녹화 시각·생성일·수정일)와 비식별을 포함한 배치 처리 단계 표시 |
| 프레임 미리보기 | 추출 프레임 썸네일 격자(이슈 있는 프레임은 표식). 썸네일 클릭 시 확대 보기(프레임 번호·타임스탬프·이슈)로 전환하고 라벨링 편집 진입 |
| 자동 라벨링 결과 | 총 라벨 수·자동 라벨 수·자동 라벨 비율·처리 상태, 신뢰도 구간 분포, 라벨별 분포(상위 10종) |

</td></tr>
<tr><td colspan="4">

```html
<div style="background:#f9fafb;color:#1f2937;border:1px solid #e5e7eb;font-family:sans-serif;font-size:11px;">
  <div style="height:48px;background:white;border-bottom:1px solid #e5e7eb;display:flex;align-items:center;padding:0 16px;gap:8px;">
    <span>‹ 뒤로</span><span style="font-size:14px;font-weight:600;">CCTV-01</span>
    <span style="background:#3730a3;color:#c7d2fe;padding:1px 6px;border-radius:3px;">쓰러짐</span>
    <span style="background:#dcfce7;color:#166534;padding:1px 6px;border-radius:3px;">완료</span>
    <span style="flex:1;"></span>
    <!-- [검수자 한정: 검수완료 + 비식별 미완] -->
    <span style="background:#b91c1c;color:white;padding:4px 10px;border-radius:4px;">재비식별</span>
  </div>
  <div style="padding:16px;">
    <div style="display:flex;gap:14px;">
      <div style="width:140px;height:90px;background:#0f172a;border-radius:6px;display:flex;align-items:center;justify-content:center;color:#64748b;">썸네일</div>
      <div style="flex:1;">
        <div style="display:flex;gap:16px;border-bottom:1px solid #e5e7eb;padding-bottom:6px;">
          <span style="border-bottom:2px solid #2563eb;padding-bottom:4px;font-weight:600;">기본 정보</span><span style="color:#6b7280;">프레임 미리보기</span><span style="color:#6b7280;">자동 라벨링 결과</span>
        </div>
        <div style="margin-top:10px;display:grid;grid-template-columns:1fr 1fr;gap:6px;color:#374151;">
          <span>CCTV 식별자: 1</span><span>해상도: 1920×1080</span><span>길이: 00:60</span><span>녹화: 2026-06-20</span><span>생성일: 2026-06-20</span><span>수정일: 2026-06-21</span>
        </div>
        <div style="margin-top:10px;background:white;border:1px solid #e5e7eb;border-radius:6px;padding:8px;color:#6b7280;">
          처리 단계: 비식별 ✓ · 마킹 ✓ · 시계열 메타 ✓ · 프레임추출 ✓ · 자동탐지 ✓ · 영역분할 ● · 보간 ○
        </div>
      </div>
    </div>
  </div>
</div>
```

</td></tr>
<tr><td colspan="4" align="center"><b>입출력 항목</b></td></tr>
<tr><td colspan="4">

| 항목명 | 컨트롤명 | 타입 및 길이 | 속성 | Validation Check |
|---|---|---|---|---|
| 상세 탭 | activeTab | VARCHAR(20) | I / E | 기본 정보·프레임 미리보기·자동 라벨링 결과 중 택1 |
| 영상 메타 정보 | video | JSONB | O / R | - |
| 배치 처리 단계 | stages | JSONB | O / R | - |
| 프레임 미리보기 목록 | framePreviews | JSONB | O / R | - |
| 확대 대상 프레임 | lightboxFrame | JSONB | I / E | 프레임 미리보기 목록에 존재하는 1건 |
| 자동 라벨링 집계 | labels | JSONB | O / R | - |
| 비식별 처리 상태 | deidentStatus | VARCHAR(20) | O / R | - |

*속성: 입출력 구분 I(Input)·O(Output)·IO(Input+Output) / 편집 속성 R(ReadOnly)·E(Editable)·H(Hidden)*

</td></tr>
<tr><td colspan="4" align="center"><b>처리 내용</b></td></tr>
<tr><td colspan="4">

- 영상 상세 조회(진입) (KLID-AT-SD-016)
. 영상 상세를 조회해 CCTV 식별자·해상도·길이·녹화 시각·생성·수정 일시를 표시하고, 비식별을 선두로 하는 배치 처리 단계와 각 단계의 상태·진행률을 확인한다.
- 프레임 미리보기 조회(탭 전환) (KLID-AT-SD-016)
. 추출된 프레임 썸네일을 격자로 표시하고 이슈가 있는 프레임을 표식으로 구분한다. 프레임이 없으면 빈 상태를 안내한다.
- 프레임 확대·라벨링 이동(썸네일 클릭) (KLID-AT-SD-016)
. 확대 보기에서 프레임 번호·타임스탬프·이슈 여부를 확인하고, 라벨링 캔버스 화면(KLID-AT-SC-005)으로 이동한다.
- 자동 라벨링 결과 조회(탭 전환) (KLID-AT-SD-016)
. 총 라벨 수·자동 라벨 수·자동 라벨 비율·처리 상태와 신뢰도 구간 분포, 라벨별 분포를 표시한다. 결과가 없는 경우와 조회에 실패한 경우를 구분해 안내한다.
- 재비식별 요청(검수자 전용) (KLID-AT-SD-011)
. 검수는 완료됐으나 비식별이 완료되지 않은 영상에 대해 확인 후 재비식별을 요청한다. 요청은 즉시 접수 응답으로 반환되고 실제 처리는 외부 비식별 솔루션 위탁으로 비동기 진행되며, 라벨과 검수 상태는 그대로 보존된다. 이미 처리 중이거나 비식별이 끝난 영상, 권한이 없는 요청은 거부한다. 처리 중에는 중복 요청을 차단한다.

</td></tr>
<tr><td colspan="4" align="center"><b>기술적 고려사항</b></td></tr>
<tr><td colspan="4">

- 프레임·썸네일 이미지는 인증 토큰을 포함해 내려받아 표시하고, 응답은 저장 금지 정책을 적용한다.
- 탭 전환은 화면 내부 상태로 처리해 불필요한 재조회를 줄인다.
- 자동 라벨링 결과와 프레임이 없는 경우 빈 상태와 오류 상태를 구분해 안내한다.
- 재비식별 요청 버튼의 노출 조건(권한·검수 상태·비식별 여부)은 화면에서 1차로 가리고, 실제 허용 여부는 서버가 최종 판정한다.

</td></tr>
</table>

#### KLID-AT-SC-018 검수 목록 화면

<table>
<tr><td width="15%">화면 ID</td><td width="35%">KLID-AT-SC-018</td><td width="15%">화면명</td><td width="35%">검수 목록 화면</td></tr>
<tr><td>관련 유스케이스 ID</td><td colspan="3">KLID-AT-UC-023</td></tr>
<tr><td>관련 시퀀스도 ID</td><td colspan="3">KLID-AT-SD-023</td></tr>
<tr><td>화면유형</td><td>조회</td><td>메뉴경로</td><td>작업 &gt; 검수 목록</td></tr>
<tr><td>화면개요</td><td colspan="3">검수자가 검수 대상 영상 목록과 현황(검수요청·검수중·승인·반려)을 조회하고, 상태별 동선으로 검수 상세에 진입하는 화면. 진입 기본값은 검수요청 + 제출일 오래된순이며 조회 조건은 화면 주소에 유지된다. 검수자 전용이다.</td></tr>
<tr><td colspan="4">

```html
<div style="background:#f9fafb;color:#1f2937;border:1px solid #e5e7eb;font-family:sans-serif;font-size:11px;">
  <div style="padding:16px;">
    <div style="font-size:14px;font-weight:600;margin-bottom:10px;">검수 목록</div>
    <!-- 현황 카드 (클릭 시 상태 필터 토글) -->
    <div style="display:flex;gap:8px;margin-bottom:10px;">
      <div style="flex:1;background:white;border:2px solid #2563eb;border-radius:6px;padding:8px;">검수요청<br><b style="font-size:16px;">8</b></div>
      <div style="flex:1;background:white;border:1px solid #e5e7eb;border-radius:6px;padding:8px;">검수중<br><b style="font-size:16px;">2</b></div>
      <div style="flex:1;background:white;border:1px solid #e5e7eb;border-radius:6px;padding:8px;">승인<br><b style="font-size:16px;">25</b></div>
      <div style="flex:1;background:white;border:1px solid #e5e7eb;border-radius:6px;padding:8px;">반려<br><b style="font-size:16px;">3</b></div>
    </div>
    <div style="background:white;border:1px solid #e5e7eb;border-radius:6px;padding:8px;display:flex;gap:6px;">
      <span style="border:1px solid #d1d5db;padding:3px 8px;flex:1;">영상명 / 작업자명 검색</span>
      <span style="border:1px solid #d1d5db;padding:3px 8px;">상태 ▾</span>
      <span style="background:#2563eb;color:white;padding:3px 10px;">조회</span>
      <span style="background:#e5e7eb;padding:3px 10px;">초기화</span>
    </div>
    <div style="margin-top:8px;background:white;border:1px solid #e5e7eb;border-radius:6px;">
      <div style="display:flex;background:#f3f4f6;padding:6px 10px;font-weight:600;"><span style="flex:1;">영상명</span><span style="width:70px;">이벤트</span><span style="width:70px;">작업자</span><span style="width:80px;">제출일 ▲</span><span style="width:60px;">라벨 수</span><span style="width:70px;">상태</span><span style="width:95px;">액션</span></div>
      <div style="display:flex;padding:6px 10px;border-top:1px solid #f3f4f6;"><span style="flex:1;">CCTV-01</span><span style="width:70px;">쓰러짐</span><span style="width:70px;">김작업</span><span style="width:80px;">06-21</span><span style="width:60px;text-align:right;">42</span><span style="width:70px;">검수요청</span><span style="width:95px;"><span style="background:#2563eb;color:white;padding:1px 6px;">검수시작 ▶</span></span></div>
      <div style="display:flex;padding:6px 10px;border-top:1px solid #f3f4f6;"><span style="flex:1;">CCTV-02</span><span style="width:70px;">교통사고</span><span style="width:70px;">이작업</span><span style="width:80px;">06-20</span><span style="width:60px;text-align:right;">31</span><span style="width:70px;">검수중</span><span style="width:95px;"><span style="background:#e5e7eb;padding:1px 6px;">이어서 검수</span></span></div>
    </div>
    <div style="margin-top:8px;text-align:center;color:#6b7280;">‹ 1 2 ›</div>
  </div>
</div>
```

</td></tr>
<tr><td colspan="4" align="center"><b>입출력 항목</b></td></tr>
<tr><td colspan="4">

| 항목명 | 컨트롤명 | 타입 및 길이 | 속성 | Validation Check |
|---|---|---|---|---|
| 영상명·작업자명 검색어 | q | VARCHAR(100) | I / E | 100자 이하 |
| 검수 상태 필터 | status | VARCHAR(20) | I / E | 전체·검수요청·검수중·승인·반려 중 택1 |
| 정렬 조건 | sort | VARCHAR(50) | I / E | 허용 정렬 키(제출일)와 방향 조합만 |
| 페이지 번호 | page | INT | IO / E | 0 이상 정수 |
| 페이지 크기 | size | INT | I / E | 1 ~ 100, 기본 20 |
| 검수 현황 집계 | summary | JSONB | O / R | - |
| 검수 목록 | content | JSONB | O / R | - |
| 전체 건수 | totalElements | INT | O / R | - |

*속성: 입출력 구분 I(Input)·O(Output)·IO(Input+Output) / 편집 속성 R(ReadOnly)·E(Editable)·H(Hidden)*

</td></tr>
<tr><td colspan="4" align="center"><b>처리 내용</b></td></tr>
<tr><td colspan="4">

- 검수 현황 집계 조회(진입) (KLID-AT-SD-023)
. 검수요청·검수중·승인·반려 건수를 서버가 필터 결과 전체 기준으로 집계해 4개 카드로 표시한다. 집계에 실패해도 목록 표시는 막지 않는다.
- 검수 목록 조회(조회) (KLID-AT-SD-023)
. 검색어와 상태 필터로 검수 대상 영상을 페이지 단위로 조회해 영상명·이벤트·작업자·제출일·라벨 수·상태를 표시한다. 필터·정렬·페이지 조건은 화면 주소에 유지하며, 화면에서 결과 행을 다시 거르지 않는다. 정렬을 바꾸면 첫 페이지로 되돌린다.
- 현황 카드 클릭 (KLID-AT-SD-023)
. 선택한 카드에 해당하는 상태로 목록 필터를 적용하고, 같은 카드를 다시 누르면 필터를 해제해 전체로 되돌린다.
- 조건 초기화 (KLID-AT-SD-023)
. 검색어·상태·정렬을 진입 기본값(검수요청·제출일 오래된순)으로 되돌린다. 이미 기본값이면 비활성 상태로 표시한다.
- 검수 상세 진입(행 액션) (KLID-AT-SD-023)
. 행 액션을 상태에 따라 검수 시작·이어서 검수·결과 보기로 구분해 표시하고, 선택 시 검수 상세 화면(KLID-AT-SC-019)으로 이동한다.

</td></tr>
<tr><td colspan="4" align="center"><b>기술적 고려사항</b></td></tr>
<tr><td colspan="4">

- 조회 조건의 단일 기준은 화면 주소이며, 새로고침·북마크·뒤로가기에서 동일한 결과가 재현되어야 한다.
- 등록되지 않은 정렬 키가 들어오면 오류로 끊지 않고 기본 정렬로 되돌린 뒤 경고만 남긴다. 보관된 주소가 깨지지 않게 하기 위한 정책이며, 작업 목록 계열의 엄격 거부 정책과 의도적으로 다르게 운영한다.
- 정렬은 시간축 단일 기준으로만 제공하고, 우선순위 정렬은 사용하지 않는다. "지금 처리할 것"은 현황 카드와 필터로 표현한다.
- 검수 목록은 검수자 전용으로 접근을 제한하고 서버에서도 동일 권한을 재검증한다.

</td></tr>
</table>

#### KLID-AT-SC-019 검수 상세 화면

<table>
<tr><td width="15%">화면 ID</td><td width="35%">KLID-AT-SC-019</td><td width="15%">화면명</td><td width="35%">검수 상세 화면</td></tr>
<tr><td>관련 유스케이스 ID</td><td colspan="3">KLID-AT-UC-023, KLID-AT-UC-007, KLID-AT-UC-009</td></tr>
<tr><td>관련 시퀀스도 ID</td><td colspan="3">KLID-AT-SD-023, KLID-AT-SD-007, KLID-AT-SD-009</td></tr>
<tr><td>화면유형</td><td>조회·갱신</td><td>메뉴경로</td><td>작업 &gt; 검수 목록 &gt; 검수 상세</td></tr>
<tr><td>화면개요</td><td colspan="3">검수자가 제출된 영상의 프레임 라벨을 읽기 전용 캔버스로 확인하고, 프레임 단위 이슈와 검수 의견을 기록한 뒤 승인 또는 반려하는 화면. 승인은 작업 완료로 처리되며 라벨 버전 스냅샷 저장과 상위 시스템 완료 통지가 이어진다.</td></tr>
<tr><td colspan="4">

```html
<div style="background:#1f2937;color:#d1d5db;border:1px solid #374151;font-family:sans-serif;font-size:11px;">
  <div style="height:40px;background:#1f2937;border-bottom:1px solid #374151;display:flex;align-items:center;padding:0 10px;gap:8px;">
    <span>✕</span><span>CCTV-01 #1024</span><span style="color:#9ca3af;">김작업 · 06-21 14:20</span>
    <span style="flex:1;"></span><span>Frame 5 / 42</span><span style="background:#1e3a8a;color:#bfdbfe;padding:1px 6px;border-radius:3px;">검수중</span>
  </div>
  <div style="display:flex;height:280px;">
    <div style="flex:1;background:#0f172a;display:flex;align-items:center;justify-content:center;color:#475569;">읽기 전용 라벨 캔버스 <span style="margin-left:8px;background:#374151;padding:1px 6px;border-radius:3px;">읽기 전용</span></div>
    <div style="width:215px;background:#1f2937;border-left:1px solid #374151;padding:8px;">
      <div style="color:#9ca3af;">객체 목록</div><div>● 사람 #1 (bbox)</div><div>● 차량 #2 (polygon)</div>
      <div style="margin-top:8px;color:#9ca3af;">객체 속성 (좌표 · 신뢰도 · 출처)</div>
      <div style="margin-top:8px;color:#9ca3af;">검수 메모</div>
      <div style="border:1px solid #374151;border-radius:4px;padding:4px;color:#64748b;">이슈 추가 모드 [ ON | OFF ]</div>
      <div style="border:1px solid #374151;border-radius:4px;padding:4px;margin-top:4px;color:#64748b;">검수 의견 (0/200)</div>
    </div>
  </div>
  <div style="height:44px;background:#111827;border-top:1px solid #374151;display:flex;align-items:center;padding:0 10px;">
    <span style="color:#64748b;">프레임 썸네일 스트립 · 진행률 5/42</span><span style="flex:1;"></span>
    <span style="background:#b91c1c;color:white;padding:3px 10px;border-radius:3px;">반려</span>&nbsp;<span style="background:#2563eb;color:white;padding:3px 10px;border-radius:3px;">승인</span>
  </div>
</div>
```

</td></tr>
<tr><td colspan="4" align="center"><b>입출력 항목</b></td></tr>
<tr><td colspan="4">

| 항목명 | 컨트롤명 | 타입 및 길이 | 속성 | Validation Check |
|---|---|---|---|---|
| 검수 의견 | reviewComment | VARCHAR(200) | I / E | 200자 이하 |
| 이슈 설명 | description | VARCHAR(1000) | I / E | 필수, 1 ~ 1000자 |
| 반려 사유 | reason | VARCHAR(1000) | I / E | 반려 시 필수, 1 ~ 1000자 |
| 이슈 추가 모드 | issueMode | BOOLEAN | I / E | - |
| 현재 프레임 순번 | currentFrameIdx | INT | IO / E | 0 이상, 전체 프레임 수 미만 |
| 선택 객체 | selectedLabelId | BIGINT | I / E | 현재 프레임 라벨 목록 내 1건 |
| 검수 상태 | reviewStatus | VARCHAR(20) | O / R | - |
| 프레임·라벨 목록 | frames | JSONB | O / R | - |
| 등록 이슈 목록 | issues | JSONB | O / R | - |
| 제출 정보(작업자·제출일시) | submitInfo | JSONB | O / R | - |

*속성: 입출력 구분 I(Input)·O(Output)·IO(Input+Output) / 편집 속성 R(ReadOnly)·E(Editable)·H(Hidden)*

</td></tr>
<tr><td colspan="4" align="center"><b>처리 내용</b></td></tr>
<tr><td colspan="4">

- 검수 진입 (KLID-AT-SD-023)
. 제출된 영상의 검수 정보와 프레임 목록을 조회한다. 검수요청 상태이면 검수중으로 자동 전이하고, 프레임 이미지 위에 라벨을 라벨 종류별 색상으로 읽기 전용 표시한다.
- 프레임 탐색 (KLID-AT-SD-023)
. 하단 썸네일 스트립 클릭이나 좌우 방향키로 프레임을 이동하고 진행률을 표시한다. 현재 프레임 썸네일은 자동으로 보이는 위치로 스크롤된다.
- 객체 확인 (KLID-AT-SD-023)
. 객체 목록을 라벨 종류별로 묶어 표시하고, 선택한 객체의 좌표·정점 수·신뢰도·생성 출처·라벨 종류를 속성 패널에 표시한다. 캔버스 선택과 목록 선택은 양방향으로 연동된다.
- 이슈 기록 (KLID-AT-SD-023)
. 이슈 추가 모드를 켜고 프레임 단위 이슈를 기록하며, 전체 검수 의견을 함께 입력한다. 저장 전 이슈는 화면에서 수정·삭제할 수 있고, 기록한 이슈와 의견은 반려 시 반려 사유에 합성된다.
- 검수 승인 (KLID-AT-SD-007, KLID-AT-SD-009)
. 승인 확인 후 작업을 완료 상태로 전이하고, 그 시점의 영상 단위 라벨 전체 스냅샷을 버전으로 저장한다. 학습데이터 산출물이 정상 생성되면 상위 시스템으로 완료 통지를 단방향 발행한다(본문·개인식별정보 미포함). 이미 처리된 검수는 승인·반려를 비활성화한다.
- 검수 반려 (KLID-AT-SD-023)
. 반려 사유를 입력해 반려하면 작업이 재작업 상태로 되돌아가고 작업자가 수정 후 재제출한다. 검수 의견과 기록한 이슈가 사유에 함께 담긴다.
- 검수 완료 후 수정 통지 (KLID-AT-SD-009)
. 검수 완료 이후 라벨·메타가 수정되면 동일 작업 식별자를 유지한 채 수정 요약(변경 프레임 목록·변경 종류·요약 건수)을 상위 시스템으로 단방향 발행한다. 짧은 시간 안에 여러 변경이 발생하면 묶어 1회만 발행한다.

</td></tr>
<tr><td colspan="4" align="center"><b>기술적 고려사항</b></td></tr>
<tr><td colspan="4">

- 검수 캔버스는 읽기 전용으로 라벨 편집을 차단하고, 프레임 이미지는 인증 토큰을 포함해 내려받는다.
- 검수자 1인 승인 체계로 2차 검수가 없으며, 승인·반려·제출 이벤트를 작업 이력으로 기록한다.
- 상위 시스템 통지는 비동기 단방향 발행이며 요청 식별자 기반 중복 방지와 재발행 큐, 장애 차단 정책을 적용한다.
- 통지는 학습데이터 산출물 생성이 성공한 뒤에 보내며, 산출이 실패하면 통지를 보류했다가 재산출 성공 시점에 재개한다.
- 반려 사유·검수 의견 등 사용자 입력은 텍스트로만 렌더링하고 길이를 화면·서버에서 이중 검증한다.

</td></tr>
</table>

#### KLID-AT-SC-022 증강 요청 화면

<table>
<tr><td width="15%">화면 ID</td><td width="35%">KLID-AT-SC-022</td><td width="15%">화면명</td><td width="35%">증강 요청 화면</td></tr>
<tr><td>관련 유스케이스 ID</td><td colspan="3">KLID-AT-UC-001, KLID-AT-UC-003</td></tr>
<tr><td>관련 시퀀스도 ID</td><td colspan="3">KLID-AT-SD-001, KLID-AT-SD-003</td></tr>
<tr><td>화면유형</td><td>입력·조회</td><td>메뉴경로</td><td>데이터 &gt; 증강 요청</td></tr>
<tr><td>화면개요</td><td colspan="3">검수자가 검수 완료(승인) 영상 1건을 골라 처리 종류 하나(겨울·야간·우천 증강 또는 해상도 변경)를 요청하는 화면. 증강 3종은 생성 조건 5필드와 함께 외부 생성형 AI 서비스에 위탁하고, 해상도 변경은 저작도구가 직접 수행한다. 최근 요청 이력을 함께 제공한다. 검수자 전용이다.</td></tr>
<tr><td colspan="4">

```html
<div style="background:#f9fafb;color:#1f2937;border:1px solid #e5e7eb;font-family:sans-serif;font-size:11px;">
  <div style="padding:16px;">
    <div style="background:#eff6ff;border:1px solid #bfdbfe;border-radius:6px;padding:8px;">ⓘ 처리 요청은 검수 완료(승인)된 영상만 가능합니다.</div>
    <!-- Step 1 -->
    <div style="margin-top:10px;font-weight:600;">Step 1. 처리 종류 선택 (단일 선택)</div>
    <div style="display:flex;gap:8px;margin-top:6px;">
      <div style="flex:1;border:2px solid #2563eb;background:#eff6ff;border-radius:6px;padding:8px;text-align:center;">◉ 겨울</div>
      <div style="flex:1;border:1px solid #e5e7eb;background:white;border-radius:6px;padding:8px;text-align:center;">○ 야간</div>
      <div style="flex:1;border:1px solid #e5e7eb;background:white;border-radius:6px;padding:8px;text-align:center;">○ 우천</div>
      <div style="flex:1;border:1px solid #e5e7eb;background:white;border-radius:6px;padding:8px;text-align:center;">○ 해상도 변경</div>
    </div>
    <!-- [증강 3종 선택 시] 생성 조건 5필드 -->
    <div style="margin-top:8px;background:white;border:1px solid #e5e7eb;border-radius:6px;padding:8px;">
      <div style="font-weight:600;margin-bottom:6px;">생성 조건 (모두 필수)</div>
      <div style="display:grid;grid-template-columns:repeat(5,1fr);gap:6px;">
        <span style="border:1px solid #d1d5db;padding:3px 6px;">시간대</span>
        <span style="border:1px solid #d1d5db;padding:3px 6px;">계절</span>
        <span style="border:1px solid #d1d5db;padding:3px 6px;">날씨</span>
        <span style="border:1px solid #d1d5db;padding:3px 6px;">지형</span>
        <span style="border:1px solid #d1d5db;padding:3px 6px;">심각도</span>
      </div>
    </div>
    <!-- [해상도 변경 선택 시] 타겟 해상도 -->
    <!-- <div style="margin-top:6px;">타겟 해상도(다중): ☑1080P ☑720P ☑480P</div> -->
    <!-- Step 2 -->
    <div style="margin-top:10px;font-weight:600;">Step 2. 대상 영상 선택 (단일 선택)</div>
    <div style="margin-top:6px;background:white;border:1px solid #e5e7eb;border-radius:6px;">
      <div style="display:flex;background:#f3f4f6;padding:6px 10px;font-weight:600;"><span style="width:34px;">선택</span><span style="flex:1;">영상명 / CCTV</span><span style="width:70px;">이벤트</span><span style="width:70px;">녹화일</span><span style="width:105px;">검수 완료 일시</span></div>
      <div style="display:flex;padding:6px 10px;border-top:1px solid #f3f4f6;"><span style="width:34px;">◉</span><span style="flex:1;">CCTV-01 #12</span><span style="width:70px;">쓰러짐</span><span style="width:70px;">06-20</span><span style="width:105px;">06-21 14:00</span></div>
    </div>
    <!-- 최근 요청 이력 -->
    <div style="margin-top:10px;font-weight:600;">최근 요청 이력</div>
    <div style="display:flex;gap:6px;margin-top:6px;">
      <div style="width:130px;background:white;border:1px solid #e5e7eb;border-radius:6px;padding:6px;">겨울 · 완료<br><span style="color:#9ca3af;">06-21 15:02</span></div>
      <div style="width:130px;background:white;border:1px solid #e5e7eb;border-radius:6px;padding:6px;">야간 · 처리중<br><span style="color:#9ca3af;">06-21 16:10</span></div>
    </div>
  </div>
  <!-- 고정 하단 액션 바 -->
  <div style="height:44px;background:white;border-top:1px solid #e5e7eb;display:flex;align-items:center;padding:0 16px;">
    <span>선택: 겨울 × 영상 #12</span><span style="flex:1;"></span>
    <span style="background:#e5e7eb;padding:4px 10px;border-radius:4px;">취소</span>&nbsp;<span style="background:#2563eb;color:white;padding:4px 10px;border-radius:4px;">처리 요청</span>
  </div>
</div>
```

</td></tr>
<tr><td colspan="4" align="center"><b>입출력 항목</b></td></tr>
<tr><td colspan="4">

| 항목명 | 컨트롤명 | 타입 및 길이 | 속성 | Validation Check |
|---|---|---|---|---|
| 처리 종류 | selectedKind | VARCHAR(20) | I / E | 필수, 겨울·야간·우천·해상도 변경 중 택1 |
| 타겟 해상도 | selectedPresets | JSONB | I / E | 해상도 변경 선택 시 1건 이상, 1080P·720P·480P 조합 |
| 생성 조건 - 시간대 | time | VARCHAR(50) | I / E | 필수, 공백·비표시 문자만 불가, 50자 이하 |
| 생성 조건 - 계절 | season | VARCHAR(50) | I / E | 필수, 공백·비표시 문자만 불가, 50자 이하 |
| 생성 조건 - 날씨 | weather | VARCHAR(50) | I / E | 필수, 공백·비표시 문자만 불가, 50자 이하 |
| 생성 조건 - 지형 | terrain | VARCHAR(50) | I / E | 필수, 공백·비표시 문자만 불가, 50자 이하 |
| 생성 조건 - 심각도 | severity | VARCHAR(50) | I / E | 필수, 공백·비표시 문자만 불가, 50자 이하 |
| 영상 검색어 | q | VARCHAR(100) | I / E | 100자 이하 |
| 이벤트 필터 | eventType | VARCHAR(20) | I / E | 제공 이벤트 유형 목록 중 택1 |
| 대상 영상 | selectedVideoId | BIGINT | I / E | 필수, 검수 완료 영상 1건(정수) |
| 검수 완료 영상 목록 | content | JSONB | O / R | - |
| 최근 요청 이력 | augments | JSONB | O / R | - |
| 해상도 변경 결과 | derivatives | JSONB | O / R | - |

*속성: 입출력 구분 I(Input)·O(Output)·IO(Input+Output) / 편집 속성 R(ReadOnly)·E(Editable)·H(Hidden)*

</td></tr>
<tr><td colspan="4" align="center"><b>처리 내용</b></td></tr>
<tr><td colspan="4">

- 처리 종류 선택(Step 1) (KLID-AT-SD-001)
. 겨울·야간·우천·해상도 변경 중 하나를 단일 선택한다. 증강 3종을 고르면 생성 조건 5필드 입력이, 해상도 변경을 고르면 타겟 해상도 선택이 나타나며, 종류를 바꾸면 하위 선택과 직전 결과를 초기화한다. 카드 사이는 방향키로도 이동할 수 있다.
- 생성 조건 입력 (KLID-AT-SD-001)
. 시간대·계절·날씨·지형·심각도 5필드를 모두 입력한다. 값은 자유 문자열이며 가공 없이 외부 서비스에 전달되고 요청 원문으로 보관된다. 사용자가 입력한 필드에만 오류를 표시하고, 5필드가 모두 유효해야 요청을 보낼 수 있다.
- 대상 영상 조회·선택(Step 2) (KLID-AT-SD-001)
. 검수 완료(승인) 영상만 페이지 단위로 조회하고 검색어·이벤트 필터로 좁힌 뒤 1건을 단일 선택한다. 같은 행을 다시 누르면 선택이 해제되며, 승인되지 않은 영상은 목록에 나타나지 않는다.
- 증강 요청(처리 요청) (KLID-AT-SD-001)
. 선택한 증강 종류와 생성 조건을 외부 생성형 AI 서비스에 위탁 요청하고, 접수되면 증강 결과 화면(KLID-AT-SC-023)으로 이동한다. 같은 영상·같은 종류를 다시 요청하는 것도 허용되며 결과는 요청별로 구분해 보관한다. 파생영상은 증강 요청 대상이 아니므로 요청을 거부하고 사유를 안내한다. 비식별 재처리 대기 중인 영상도 위탁을 거부한다.
- 해상도 변경 수행(처리 요청) (KLID-AT-SD-003)
. 선택한 표준 해상도 프리셋마다 새 파생영상을 생성한다. 영상 파일은 비식별본을 그대로 복사하고 프레임 이미지만 목표 해상도로 리스케일하며, 라벨 좌표는 해상도 배율로 재계산해 함께 적재한다(출력물 KLID-AT-OUT-002). 확대 변환도 허용하고 원본과 동일한 해상도인 프리셋만 건너뛴다. 생성된 파생영상 목록과 상태를 화면에 표시하며, 전부 건너뛰거나 전부 실패한 경우는 사유를 안내한다.
- 요청 이력 조회 (KLID-AT-SD-002)
. 최근 증강 요청을 카드로 표시하고, 카드를 선택하면 해당 요청의 증강 결과 화면으로 이동한다.
- 취소 (KLID-AT-SD-001)
. 입력을 버리고 직전 화면으로 돌아간다.

</td></tr>
<tr><td colspan="4" align="center"><b>기술적 고려사항</b></td></tr>
<tr><td colspan="4">

- 증강 생성 본체는 외부 책임이며 저작도구는 요청·수신·등록·검수만 담당한다. 외부 위탁 연동에는 타임아웃·재시도·장애 차단 정책을 적용하고 위탁은 비동기로 제출한다.
- 중복 요청은 서버가 차단하지 않는다. 같은 조건 재요청이 정당한 운영 동선이므로, 오조작 방지는 화면에서 요청 버튼 비활성화·중복 제출 차단으로 처리한다.
- 처리 종류는 허용 목록으로만 좁혀 전달하고, 자유 문자열인 생성 조건이 처리 종류 판정에 관여하지 않게 분리한다.
- 파생영상에서 다시 파생을 만들지 않도록 화면이 파생 여부를 미리 확인해 요청 버튼을 비활성화하고 사유를 안내하며, 서버도 동일 조건을 거부한다.
- 해상도 변경은 외부 위탁 없이 내부에서 수행하는 결정적 변환이며, 같은 프리셋을 중복 생성하지 않는다.

</td></tr>
</table>

#### KLID-AT-SC-023 증강 결과 화면

<table>
<tr><td width="15%">화면 ID</td><td width="35%">KLID-AT-SC-023</td><td width="15%">화면명</td><td width="35%">증강 결과 화면</td></tr>
<tr><td>관련 유스케이스 ID</td><td colspan="3">KLID-AT-UC-002, KLID-AT-UC-010</td></tr>
<tr><td>관련 시퀀스도 ID</td><td colspan="3">KLID-AT-SD-002, KLID-AT-SD-010</td></tr>
<tr><td>화면유형</td><td>조회·갱신</td><td>메뉴경로</td><td>데이터 &gt; 증강 요청 &gt; 증강 결과</td></tr>
<tr><td>화면개요</td><td colspan="3">외부에서 생성된 증강 결과를 원본·증강 프레임 페어로 비교하고, 라벨 무결성 비율을 확인한 뒤 학습데이터 활용 여부(채택·거부)를 결정하는 화면. 채택하면 원본을 부모로 하는 새 영상이 미검수 상태로 등록된다. 검수자 전용이다.</td></tr>
<tr><td colspan="4">

```html
<div style="background:#f9fafb;color:#1f2937;border:1px solid #e5e7eb;font-family:sans-serif;font-size:11px;">
  <div style="padding:16px;">
    <div style="display:flex;align-items:center;gap:8px;">
      <span style="font-size:14px;font-weight:600;">증강 결과 확인</span>
      <span style="color:#6b7280;">데이터 증강 / 요청 #7</span>
      <span style="background:#dcfce7;color:#166534;padding:1px 6px;border-radius:3px;">완료</span>
    </div>
    <!-- 작업 요약 -->
    <div style="margin-top:8px;background:white;border:1px solid #e5e7eb;border-radius:6px;padding:8px;display:grid;grid-template-columns:repeat(5,1fr);gap:6px;">
      <span>요청 ID: #7</span><span>증강 유형: 겨울</span><span>대상 영상: 1건</span><span>처리 이미지: 12장</span><span>라벨 무결성: 98%</span>
    </div>
    <!-- 영상별 결과 -->
    <div style="margin-top:8px;background:white;border:1px solid #e5e7eb;border-radius:6px;padding:8px;">
      <div style="font-weight:600;">CCTV-01</div>
      <div style="display:flex;gap:10px;border-bottom:1px solid #e5e7eb;padding-bottom:4px;margin-top:4px;"><span style="font-weight:600;border-bottom:2px solid #2563eb;">겨울</span><span style="color:#6b7280;">야간</span></div>
      <div style="margin-top:8px;display:flex;gap:6px;">
        <div style="width:110px;height:66px;background:#e5e7eb;border-radius:4px;text-align:center;line-height:66px;color:#6b7280;">원본</div>
        <div style="width:110px;height:66px;background:#dbeafe;border-radius:4px;text-align:center;line-height:66px;color:#1e40af;">증강</div>
        <div style="width:110px;height:66px;background:#e5e7eb;border-radius:4px;text-align:center;line-height:66px;color:#6b7280;">원본</div>
        <div style="width:110px;height:66px;background:#dbeafe;border-radius:4px;text-align:center;line-height:66px;color:#1e40af;">증강</div>
      </div>
      <!-- 활용 결정 카드 -->
      <div style="margin-top:8px;text-align:right;"><span style="background:#2563eb;color:white;padding:3px 10px;border-radius:4px;">채택</span>&nbsp;<span style="background:#b91c1c;color:white;padding:3px 10px;border-radius:4px;">거부</span></div>
    </div>
    <div style="margin-top:8px;text-align:center;color:#6b7280;">더 보기</div>
  </div>
</div>
```

</td></tr>
<tr><td colspan="4" align="center"><b>입출력 항목</b></td></tr>
<tr><td colspan="4">

| 항목명 | 컨트롤명 | 타입 및 길이 | 속성 | Validation Check |
|---|---|---|---|---|
| 증강 유형 탭 | activeType | VARCHAR(20) | I / E | 결과가 존재하는 증강 유형 중 택1 |
| 비교 대상 프레임 | selectedPair | JSONB | I / E | 표시된 프레임 페어 중 1건 |
| 거부 사유 | reason | VARCHAR(500) | I / E | 거부 시 필수, 1 ~ 500자 |
| 요청 상태 | status | VARCHAR(20) | O / R | - |
| 작업 요약 | summary | JSONB | O / R | - |
| 라벨 무결성 비율 | labelIntegrity | NUMERIC(5,2) | O / R | - |
| 프레임 페어 목록 | framePairs | JSONB | O / R | - |
| 생성 조건 원문 | prompt | JSONB | O / R | - |
| 활용 결정 상태 | decisionStatus | VARCHAR(20) | O / R | - |

*속성: 입출력 구분 I(Input)·O(Output)·IO(Input+Output) / 편집 속성 R(ReadOnly)·E(Editable)·H(Hidden)*

</td></tr>
<tr><td colspan="4" align="center"><b>처리 내용</b></td></tr>
<tr><td colspan="4">

- 증강 결과 조회(진입) (KLID-AT-SD-002)
. 요청 상태(처리 중·완료·실패)와 작업 요약(요청 식별자·증강 유형·대상 영상 건수·총 처리 이미지 수)을 표시한다. 처리 중과 실패는 상태 배너로, 완료는 라벨 무결성 비율로 표시한다. 요청 식별자가 올바르지 않거나 조회에 실패하면 오류 상태로 안내한다.
- 영상별 결과 비교(탭·프레임 선택) (KLID-AT-SD-002)
. 영상별로 묶어 증강 유형 탭을 제공하고, 원본·증강 프레임 페어를 격자로 보여준다. 선택한 프레임은 좌우 비교로 확인하며, 영상이 많으면 일부만 먼저 보여주고 더 보기로 확장한다. 같은 영상·같은 종류의 결과가 여러 건이면 요청 시 입력한 생성 조건 원문으로 구분한다.
- 활용 채택 (KLID-AT-SD-002)
. 채택하면 원본을 부모로 참조하는 새 영상을 생성하고, 원본 라벨·메타를 좌표와 속성 그대로 복사해 미검수 상태로 등록한다. 등록된 파생영상은 이후 작업 배정·라벨링·검수 흐름을 그대로 따르며, 검수 승인 시 새 작업 식별자로 상위 시스템에 완료 통지가 발행된다.
- 활용 거부 (KLID-AT-SD-010)
. 사유를 입력해 거부하면 해당 파생영상이 작업 대상에서 제외되고, 유예 기간이 지나면 데이터와 생성 파일이 정리된다. 이미 결정된 결과의 중복 처리와 사유 누락은 거부한다.
- 재시도 (KLID-AT-SD-002)
. 실패한 요청은 상태 배너에서 결과를 다시 조회한다.

</td></tr>
<tr><td colspan="4" align="center"><b>기술적 고려사항</b></td></tr>
<tr><td colspan="4">

- 증강 생성 본체는 외부 책임이며 저작도구는 결과 수신·등록·검수만 담당한다.
- 채택 시 라벨·메타를 좌표와 속성 그대로 복사해 무결성을 유지하고, 부모의 개인정보 판정은 생성 시점에 1회 계승한다.
- 생성 결과 상태(성공·실패·취소)와 사람이 내리는 활용 결정(채택·거부)은 서로 다른 축으로 분리해 관리한다.
- 파생영상에는 원본 영상이 없고 비식별본만 존재하므로, 결과 비교와 이후 서빙은 모두 비식별 사본을 대상으로 한다.
- 거부에 따른 실제 삭제는 파생영상·거부·유예 경과 세 조건을 동시에 만족할 때만 수행하고, 데이터 정리 후 파일을 정리한다.

</td></tr>
</table>

#### KLID-AT-SC-025 시스템 설정 화면

<table>
<tr><td width="15%">화면 ID</td><td width="35%">KLID-AT-SC-025</td><td width="15%">화면명</td><td width="35%">시스템 설정 화면</td></tr>
<tr><td>관련 유스케이스 ID</td><td colspan="3">KLID-AT-UC-006</td></tr>
<tr><td>관련 시퀀스도 ID</td><td colspan="3">KLID-AT-SD-006</td></tr>
<tr><td>화면유형</td><td>조회·갱신</td><td>메뉴경로</td><td>관리 &gt; 시스템 설정</td></tr>
<tr><td>화면개요</td><td colspan="3">검수자가 운영 파라미터(배치 처리·AI 추론·라벨링 정밀도)를 조회·수정하고 외부 연동 상태를 모니터링하는 화면. 라벨링 정밀도(인식 민감도·경계 세밀함)를 저장하면 이후 객체 자동 추적·외곽 경계 자동 밀착 결과의 경계 세밀함이 제어된다. 검수자 전용이다.</td></tr>
<tr><td colspan="4">

```html
<div style="background:#f9fafb;color:#1f2937;border:1px solid #e5e7eb;font-family:sans-serif;font-size:11px;">
  <div style="height:48px;background:white;border-bottom:1px solid #e5e7eb;display:flex;align-items:center;padding:0 16px;">
    <span style="font-size:14px;font-weight:600;">시스템 설정</span>
    <span style="margin-left:8px;color:#6b7280;">배치 파라미터 · 외부 연동 상태 · 위험 구역</span>
  </div>
  <div style="padding:16px;display:flex;gap:12px;">
    <div style="flex:1;display:grid;grid-template-columns:1fr 1fr;gap:8px;">
      <div style="background:white;border:1px solid #e5e7eb;border-radius:6px;padding:8px;">배치 처리<br><span style="color:#6b7280;">처리 주기(초) 10~3600 · 동시 처리 수 1~10</span><br><span style="background:#2563eb;color:white;padding:1px 6px;">저장</span></div>
      <div style="background:white;border:1px solid #e5e7eb;border-radius:6px;padding:8px;">AI 추론 파라미터<br><span style="color:#6b7280;">인식 민감도 0.25~0.80 · 이미지 크기 320~1920 · 중첩 임계값 0.25~0.80</span><br><span style="background:#2563eb;color:white;padding:1px 6px;">저장</span></div>
      <div style="background:white;border:1px solid #e5e7eb;border-radius:6px;padding:8px;">라벨링 정밀도<br><span style="color:#6b7280;">인식 민감도 0.25~0.80 · 경계 세밀함 0.0~50.0</span><br><span style="background:#2563eb;color:white;padding:1px 6px;">저장</span></div>
      <div style="background:#fef2f2;border:1px solid #fecaca;border-radius:6px;padding:8px;">위험 구역<br><span style="color:#b91c1c;">배치 큐 초기화 · 캐시 삭제 (확인 후 실행)</span></div>
    </div>
    <div style="width:200px;background:white;border:1px solid #e5e7eb;border-radius:6px;padding:8px;">
      <div style="color:#6b7280;margin-bottom:6px;">외부 연동 상태</div>
      <div>데이터베이스 <span style="color:#16a34a;">● 정상</span></div>
      <div>디스크 <span style="color:#16a34a;">● 정상</span></div>
      <div>상위 관제 시스템 <span style="color:#16a34a;">● 정상</span></div>
      <div>포털 <span style="color:#16a34a;">● 정상</span></div>
      <div>AI 추론 서버 <span style="color:#16a34a;">● 정상</span></div>
      <div>비식별 솔루션 <span style="color:#dc2626;">● 연결 끊김</span></div>
    </div>
  </div>
</div>
```

</td></tr>
<tr><td colspan="4" align="center"><b>입출력 항목</b></td></tr>
<tr><td colspan="4">

| 항목명 | 컨트롤명 | 타입 및 길이 | 속성 | Validation Check |
|---|---|---|---|---|
| 배치 처리 주기(초) | batchIntervalSec | INT | IO / E | 필수, 10 ~ 3600 정수 |
| 배치 동시 처리 수 | batchConcurrency | INT | IO / E | 필수, 1 ~ 10 정수 |
| 추론 인식 민감도 | detectConfThreshold | NUMERIC(3,2) | IO / E | 필수, 0.25 ~ 0.80 |
| 추론 이미지 크기 | detectImageSize | INT | IO / E | 필수, 320 ~ 1920, 32의 배수 |
| 추론 중첩 임계값 | detectIouThreshold | NUMERIC(3,2) | IO / E | 필수, 0.25 ~ 0.80 |
| 정밀도 인식 민감도 | precisionConfThreshold | NUMERIC(3,2) | IO / E | 필수, 0.25 ~ 0.80 |
| 정밀도 경계 세밀함 | polygonSimplifyTolerance | NUMERIC(4,1) | IO / E | 필수, 0.0 ~ 50.0 |
| 외부 연동 상태 | healthComponents | JSONB | O / R | - |
| 응답 지연(ms) | latencyMs | INT | O / R | - |

*속성: 입출력 구분 I(Input)·O(Output)·IO(Input+Output) / 편집 속성 R(ReadOnly)·E(Editable)·H(Hidden)*

</td></tr>
<tr><td colspan="4" align="center"><b>처리 내용</b></td></tr>
<tr><td colspan="4">

- 설정 조회(진입) (KLID-AT-SD-006)
. 운영 파라미터를 조회해 배치 처리·AI 추론 파라미터·라벨링 정밀도 3개 카드로 표시한다. 조회에 실패하면 오류 상태로 안내한다.
- 설정 저장(카드별 저장) (KLID-AT-SD-006)
. 카드별 독립 폼으로 값을 편집하고 변경이 있을 때만 저장을 활성화한다. 허용 범위·형식을 벗어난 값은 저장하지 않고 항목별 오류를 표시하며, 저장 결과는 알림으로 알린다.
- 라벨링 정밀도 반영 (KLID-AT-SD-006)
. 정밀도(인식 민감도·경계 세밀함)를 저장하면 이후 객체 자동 추적·외곽 경계 자동 밀착·자동 탐지 결과의 경계 점 수가 그 값으로 제어된다. 설정값은 짧은 유효시간의 캐시로 반영하며, 조회에 실패하면 기본값으로 동작한다.
- 외부 연동 모니터링 (KLID-AT-SD-006)
. 데이터베이스·디스크·상위 관제 시스템·포털·AI 추론 서버·비식별 솔루션의 연결 상태와 응답 지연을 주기적으로 조회해 정상·연결 끊김·서비스 중단으로 표시한다. 읽기 전용이며 편집할 수 없다.
- 위험 구역 실행 (KLID-AT-SD-006)
. 배치 큐 초기화·캐시 삭제 등 파괴적 조치는 확인 절차를 거쳐야만 실행되며, 운영 도구로 이관 예정임을 안내한다.

</td></tr>
<tr><td colspan="4" align="center"><b>기술적 고려사항</b></td></tr>
<tr><td colspan="4">

- 설정 변경은 검수자 전용으로 제한하고, 입력 범위·형식을 화면과 서버에서 이중 검증한다.
- 정밀도 설정은 짧은 유효시간 캐시로 반영해 추론 결과에 일관되게 적용하고, 조회 실패 시 기본값으로 되돌아간다.
- 외부 연동 상태는 주기 조회로 갱신하며 실시간 상태만 표시하고 이력은 남기지 않는다.
- 외부 비식별 솔루션이 제공하는 처리 옵션은 본 화면의 운영 파라미터와 별도로 해당 솔루션 관리 화면에서 설정한다.

</td></tr>
</table>

#### KLID-AT-SC-033 비식별 신고 관리 화면

<table>
<tr><td width="15%">화면 ID</td><td width="35%">KLID-AT-SC-033</td><td width="15%">화면명</td><td width="35%">비식별 신고 관리 화면</td></tr>
<tr><td>관련 유스케이스 ID</td><td colspan="3">KLID-AT-UC-016</td></tr>
<tr><td>관련 시퀀스도 ID</td><td colspan="3">KLID-AT-SD-016</td></tr>
<tr><td>화면유형</td><td>조회·갱신</td><td>메뉴경로</td><td>관리 &gt; 비식별 신고</td></tr>
<tr><td>화면개요</td><td colspan="3">마킹·라벨링 중 작업자가 제출한 비식별 누락 신고를 검수자가 상태별(미해소·해소됨)로 조회하고, 외부 비식별 솔루션으로 수동 처리를 완료한 뒤 해소 처리해 작업 잠금을 해제하는 관리 화면. 검수자 전용이다.</td></tr>
<tr><td colspan="4">

```html
<div style="background:#f9fafb;color:#1f2937;border:1px solid #e5e7eb;font-family:sans-serif;font-size:11px;">
  <div style="padding:16px;">
    <div style="font-size:14px;font-weight:600;">비식별 신고 관리</div>
    <div style="color:#6b7280;margin-bottom:10px;">라벨링·마킹 중 신고된 비식별 누락 건을 확인하고 외부 수동 비식별화 완료 후 해소 처리합니다.</div>
    <div style="display:flex;gap:6px;margin-bottom:8px;align-items:center;">
      <span style="background:#2563eb;color:white;padding:4px 10px;border-radius:4px;">미해소</span>
      <span style="background:#e5e7eb;padding:4px 10px;border-radius:4px;">해소됨</span>
      <span style="flex:1;"></span><span style="color:#6b7280;">전체 3건</span>
    </div>
    <div style="background:white;border:1px solid #e5e7eb;border-radius:6px;">
      <div style="display:flex;background:#f3f4f6;padding:6px 10px;font-weight:600;"><span style="width:80px;">신고 번호</span><span style="width:80px;">영상</span><span style="width:80px;">신고자</span><span style="flex:1;">사유</span><span style="width:100px;">신고일시</span><span style="width:90px;">처리</span></div>
      <div style="display:flex;padding:6px 10px;border-top:1px solid #f3f4f6;"><span style="width:80px;">#31</span><span style="width:80px;">#1042</span><span style="width:80px;">김작업</span><span style="flex:1;">얼굴 노출 구간 발견</span><span style="width:100px;">06-23 09:12</span><span style="width:90px;"><span style="background:#2563eb;color:white;padding:1px 6px;">해소 처리</span></span></div>
    </div>
    <div style="margin-top:8px;text-align:center;color:#6b7280;">‹ 1 ›</div>
  </div>
</div>
```

</td></tr>
<tr><td colspan="4" align="center"><b>입출력 항목</b></td></tr>
<tr><td colspan="4">

| 항목명 | 컨트롤명 | 타입 및 길이 | 속성 | Validation Check |
|---|---|---|---|---|
| 신고 상태 탭 | status | VARCHAR(20) | I / E | 미해소·해소됨 중 택1 |
| 페이지 번호 | page | INT | IO / E | 0 이상 정수 |
| 신고 목록 | content | JSONB | O / R | - |
| 신고 사유 | reason | VARCHAR(1000) | O / R | - |
| 신고자 | reporterNo | VARCHAR(50) | O / R | - |
| 신고 일시 | reportDt | TIMESTAMP | O / R | - |
| 해소 대상 신고 | rprtSn | BIGINT | I / E | 해소 시 필수, 미해소 신고 1건 |
| 전체 건수 | totalElements | INT | O / R | - |

*속성: 입출력 구분 I(Input)·O(Output)·IO(Input+Output) / 편집 속성 R(ReadOnly)·E(Editable)·H(Hidden)*

</td></tr>
<tr><td colspan="4" align="center"><b>처리 내용</b></td></tr>
<tr><td colspan="4">

- 신고 목록 조회(탭 전환) (KLID-AT-SD-016)
. 상태 탭(미해소·해소됨)으로 비식별 누락 신고를 페이지 단위로 조회하고, 각 행에 신고 번호·영상 식별자·신고자·사유·신고 일시를 표시한다. 탭을 전환하면 첫 페이지로 되돌리며 전체 건수를 함께 표시한다. 조회 실패와 빈 결과는 구분해 안내한다.
- 해소 처리 (KLID-AT-SD-016)
. 외부 비식별 솔루션으로 수동 비식별 처리를 완료한 뒤 해소를 실행하면 신고가 해소 상태로 전이되고 작업 잠금이 해제된다. 이미 해소된 신고를 다시 해소하는 요청은 충돌로 거부한다. 마킹 단계에서 접수된 신고는 배치 단계를 마킹 대기로 되감아 마킹부터 다시 수행하게 하고, 라벨링 단계에서 접수된 신고는 프레임 이미지만 다시 추출해 기존 라벨을 보존한 채 작업을 이어가게 한다. 검수 완료 영상이면 학습데이터 산출물을 재생성한 뒤 상위 시스템으로 수정 통지를 발행한다.

</td></tr>
<tr><td colspan="4" align="center"><b>기술적 고려사항</b></td></tr>
<tr><td colspan="4">

- 검수자 전용 화면으로 접근을 제한하고 서버에서도 동일 권한을 재검증한다.
- 신고 사유는 사용자 입력이므로 텍스트로만 렌더링해 스크립트 삽입을 방어한다.
- 자동 재비식별 큐는 운영하지 않으며, 외부 솔루션에서 수동 처리한 뒤 본 화면에서 잠금을 해제한다.
- 해소 처리는 상태 전이를 조건부 갱신으로 원자적으로 선점해, 다중 노드 환경에서 같은 신고가 두 번 처리되지 않게 한다.
- 신고 구간에도 이미 검수 완료되어 상위 시스템에 통지된 영상의 조회 경로는 차단하지 않으며, 해소 후 재산출·재통지로 정합을 맞춘다.

</td></tr>
</table>

#### KLID-AT-SC-036 라벨 관리 화면

<table>
<tr><td width="15%">화면 ID</td><td width="35%">KLID-AT-SC-036</td><td width="15%">화면명</td><td width="35%">라벨 관리 화면</td></tr>
<tr><td>관련 유스케이스 ID</td><td colspan="3">KLID-AT-UC-028</td></tr>
<tr><td>관련 시퀀스도 ID</td><td colspan="3">KLID-AT-SD-028</td></tr>
<tr><td>화면유형</td><td>입력·조회·갱신·삭제</td><td>메뉴경로</td><td>관리 &gt; 라벨 관리</td></tr>
<tr><td>화면개요</td><td colspan="3">검수자가 라벨 클래스(라벨 마스터)와 클래스별 속성 정의를 등록·수정·삭제하는 관리 화면. 라벨 마스터는 라벨링 화면·자동 라벨링 후보·오토라벨 프리셋이 함께 참조하는 단일 기준이므로, 여기서 바꾼 라벨명·형태·색상·정렬순·AI 탐지 클래스 매핑이 즉시 반영된다. 검수자 전용이다.</td></tr>
<tr><td colspan="4">

```html
<div style="background:#f9fafb;color:#1f2937;border:1px solid #e5e7eb;font-family:sans-serif;font-size:11px;">
  <div style="padding:16px;">
    <div style="display:flex;align-items:center;">
      <div>
        <div style="font-size:14px;font-weight:600;">라벨 관리</div>
        <div style="color:#6b7280;">라벨 클래스(마스터) 관리 — 전체 8개</div>
      </div>
      <span style="flex:1;"></span>
      <span style="background:#2563eb;color:white;padding:4px 10px;border-radius:4px;">라벨 추가</span>
    </div>
    <!-- 라벨 마스터 테이블 -->
    <div style="margin-top:10px;background:white;border:1px solid #e5e7eb;border-radius:6px;">
      <div style="display:flex;background:#f3f4f6;padding:6px 10px;font-weight:600;"><span style="flex:1;">라벨명</span><span style="width:90px;">형태</span><span style="width:110px;">색상</span><span style="width:70px;">정렬순</span><span style="width:130px;">AI 탐지 클래스</span><span style="width:120px;">관리</span></div>
      <div style="display:flex;padding:6px 10px;border-top:1px solid #f3f4f6;background:#eff6ff;"><span style="flex:1;">사람</span><span style="width:90px;">바운딩박스</span><span style="width:110px;"><span style="display:inline-block;width:10px;height:10px;background:#3B82F6;"></span> #3B82F6</span><span style="width:70px;">1</span><span style="width:130px;">person</span><span style="width:120px;">속성 · 수정 · 삭제</span></div>
      <div style="display:flex;padding:6px 10px;border-top:1px solid #f3f4f6;"><span style="flex:1;">차량</span><span style="width:90px;">폴리곤</span><span style="width:110px;"><span style="display:inline-block;width:10px;height:10px;background:#EF4444;"></span> #EF4444</span><span style="width:70px;">2</span><span style="width:130px;">car</span><span style="width:120px;">속성 · 수정 · 삭제</span></div>
    </div>
    <!-- [선택: 속성 버튼 클릭 시] 속성 정의 패널 -->
    <div style="margin-top:10px;background:white;border:1px solid #e5e7eb;border-radius:6px;padding:8px;">
      <div style="display:flex;align-items:center;"><span style="font-weight:600;">‘사람’ 속성 정의</span><span style="flex:1;"></span><span style="background:#2563eb;color:white;padding:2px 8px;border-radius:4px;">속성 추가</span></div>
      <div style="display:flex;background:#f3f4f6;padding:6px 10px;font-weight:600;margin-top:6px;"><span style="flex:1;">속성명</span><span style="width:90px;">입력 형식</span><span style="flex:1;">선택 항목</span><span style="width:90px;">기본값</span><span style="width:90px;">작업중 수정</span><span style="width:70px;">정렬순</span><span style="width:90px;">관리</span></div>
      <div style="display:flex;padding:6px 10px;border-top:1px solid #f3f4f6;"><span style="flex:1;">자세</span><span style="width:90px;">단일선택</span><span style="flex:1;">서있음 / 앉음 / 누움</span><span style="width:90px;">서있음</span><span style="width:90px;">허용</span><span style="width:70px;">1</span><span style="width:90px;">수정 · 삭제</span></div>
    </div>
  </div>
</div>
```

</td></tr>
<tr><td colspan="4" align="center"><b>입출력 항목</b></td></tr>
<tr><td colspan="4">

| 항목명 | 컨트롤명 | 타입 및 길이 | 속성 | Validation Check |
|---|---|---|---|---|
| 라벨명 | name | VARCHAR(50) | IO / E | 필수, 50자 이하, 중복 불가 |
| 라벨 형태 | type | VARCHAR(20) | IO / E | 필수, 바운딩박스·폴리곤·포인트·관절 포즈 중 택1 |
| 라벨 색상 | color | VARCHAR(7) | IO / E | 필수, `#RRGGBB` 형식 |
| 정렬 순서 | sortNo | INT | IO / E | 필수, 0 이상 정수 |
| AI 탐지 클래스 매핑 | dtctTypeCd | VARCHAR(20) | IO / E | 선택, 제공 탐지 클래스 목록 중 1건, 활성 라벨 간 중복 불가 |
| 라벨 마스터 목록 | labels | JSONB | O / R | - |
| 선택 라벨 | selectedLabelId | BIGINT | I / E | 라벨 마스터 목록 내 1건 |
| 속성명 | attrName | VARCHAR(64) | IO / E | 필수, 64자 이하, 동일 라벨 내 중복 불가 |
| 속성 입력 형식 | inputType | VARCHAR(20) | IO / E | 필수, 단일선택·다중선택·라디오·숫자·텍스트 중 택1 |
| 속성 선택 항목 | values | JSONB | IO / E | 선택형 입력 형식일 때 1개 이상, 전체 1000자 이하 |
| 속성 기본값 | defaultVal | VARCHAR(255) | IO / E | 255자 이하 |
| 작업 중 값 수정 허용 | mutable | VARCHAR(1) | IO / E | 필수, 허용·불가 중 택1 |
| 속성 정렬 순서 | attrSortNo | INT | IO / E | 필수, 0 이상 정수 |
| 속성 정의 목록 | attrs | JSONB | O / R | - |

*속성: 입출력 구분 I(Input)·O(Output)·IO(Input+Output) / 편집 속성 R(ReadOnly)·E(Editable)·H(Hidden)*

</td></tr>
<tr><td colspan="4" align="center"><b>처리 내용</b></td></tr>
<tr><td colspan="4">

- 라벨 마스터 목록 조회(진입) (KLID-AT-SD-028)
. 라벨 클래스를 정렬 순서 오름차순(동률은 등록 순)으로 조회해 라벨명·형태·색상·정렬순·AI 탐지 클래스 매핑을 표시하고 전체 개수를 함께 안내한다. 목록이 비면 빈 상태와 신규 등록 동선을 제공하고, 조회 실패는 오류 상태로 구분한다.
- 라벨 추가 (KLID-AT-SD-028)
. 라벨명·형태·색상·정렬순·AI 탐지 클래스 매핑을 입력해 새 라벨 클래스를 등록한다. 정렬순은 다음 순번을 자동으로 채우고, 형식 검증은 화면과 서버에서 이중으로 수행하며 실패 사유는 폼 상단에 표시한다.
- 라벨 수정 (KLID-AT-SD-028)
. 기존 라벨 클래스의 라벨명·형태·색상·정렬순·AI 탐지 클래스 매핑을 변경한다. 변경 내용은 라벨링 화면의 라벨 선택 목록과 오토라벨 프리셋에 즉시 반영된다.
- 라벨 삭제 (KLID-AT-SD-028)
. 확인 절차를 거쳐 라벨 클래스를 삭제한다. 삭제한 라벨을 쓰던 기존 프리셋은 오류 없이 미연결로 표시되고 색상이 기본값으로 표시될 수 있음을 함께 안내한다.
- 속성 정의 패널 열기 (KLID-AT-SD-028)
. 라벨 행에서 속성을 선택하면 하단에 해당 라벨의 속성 정의 패널이 열리고, 같은 버튼을 다시 누르면 닫힌다. 선택한 라벨이 삭제되면 패널을 자동으로 닫는다.
- 속성 정의 추가·수정·삭제 (KLID-AT-SD-028)
. 속성명·입력 형식·선택 항목·기본값·작업 중 수정 허용 여부·정렬순을 입력해 속성 정의를 등록·수정하고, 확인 후 삭제한다. 선택형 입력 형식은 선택 항목을 1개 이상 요구하고, 비선택형은 선택 항목을 저장하지 않는다. 여기서 정의한 속성은 라벨링 화면의 객체 속성 입력 항목으로 노출된다.

</td></tr>
<tr><td colspan="4" align="center"><b>기술적 고려사항</b></td></tr>
<tr><td colspan="4">

- 검수자 전용 화면으로 접근을 제한하고 서버에서도 동일 권한을 재검증한다.
- 요청 본문은 허용된 입력 필드만 담아 전송해, 서버가 관리하는 식별자·사용 여부 등이 외부 입력으로 덮이지 않게 한다.
- AI 탐지 클래스 매핑은 표준 탐지 클래스 목록으로 제한하고, 활성 라벨 1건당 1개 클래스만 매핑되도록 강제한다. 매핑되지 않은 라벨은 표시하되 자동 탐지 대상에서 제외한다.
- 라벨 마스터는 프리셋이 실시간으로 참조하는 단일 기준이므로, 변경 후 관련 조회 캐시를 무효화해 화면 간 표시가 어긋나지 않게 한다.
- 삭제는 확인 모달 승인 후에만 실행하고, 비가역 영향(프리셋 표시 변화)을 사전에 안내한다.

</td></tr>
</table>

### 4. 공통 UI 컴포넌트 명세

> 화면 간 재사용되는 UI 컴포넌트 카탈로그다. 화면 상세의 와이어프레임·처리 내용은 아래 컴포넌트를 조합해 구현한다.

#### 4.1 디자인 표준

| 구분 | 표준 |
|---|---|
| 디자인 시스템 | 정부 디자인시스템(KRDS) 기반 공공서비스 프리셋 — 접근성 우선, 큰 조작 영역, 명확한 포커스 표시 |
| 기본 색상 | 주색 `#0F4C97` / 보조색 `#1850D7` / 중립 `#3F4956` |
| 의미 색상 | 정보 `#0F4C97` · 성공 `#117C44` · 경고 `#C25700` · 오류 `#D1322C` |
| 타이포그래피 | 본문 17px(가변 400·500) / 제목 18px(600~800) / 라벨 14px(600) / 고정폭 14px |
| 간격 체계 | 기준 4px, 단계 4 · 8 · 16 · 24 · 40 · 64px |
| 모서리·그림자 | 반경 4 · 6 · 8px, 원형 처리 / 그림자 3단계(약·중·강) |
| 모션 | 기본 200ms(빠름 120ms, 느림 320ms), 표준 가감속 곡선 |
| 접근성 | 명암 대비 AA 이상, 키보드 조작·화면낭독기 대응, 색상 단독 정보 전달 금지 |

#### 4.2 공통 컴포넌트 목록

| ID | 컴포넌트명 | 분류 | 기능 요약 |
|---|---|---|---|
| UI-001 | Button | 액션 | 공통 버튼. 5종 표현(주요·보조·외곽선·위험·투명) × 3종 크기, 로딩·비활성·아이콘 슬롯 |
| UI-002 | Input | 입력 | 공통 텍스트 입력. 라벨·도움말·오류 슬롯과 화면낭독기 연결 |
| UI-003 | Select | 입력 | 공통 드롭다운. 선택지 배열 렌더, 안내 문구 항목, 라벨·오류 연결 |
| UI-004 | Modal | 오버레이 | 공통 모달. 화면 최상위 렌더, ESC 닫기·포커스 가둠·포커스 복귀, 4종 크기 |
| UI-005 | ConfirmDialog | 오버레이 | 확인·취소 다이얼로그. 일반·위험 표현, 로딩 상태, 배경 클릭 차단 옵션 |
| UI-006 | Drawer | 오버레이 | 측면 드로어. 좌·우 방향, 너비 지정, ESC·배경 닫기, 포커스 가둠·복귀 |
| UI-007 | DataTable | 데이터 | 제네릭 데이터 테이블. 컬럼 정의·정렬 토글·행 선택·빈 상태·로딩 표시 |
| UI-008 | Pagination | 내비게이션 | 페이지네이션. 페이지 번호 창, 처음·이전·다음·마지막, 표시 범위 안내 |
| UI-009 | Tabs | 내비게이션 | 탭 전환. 선택지 배열, 제어형 값, 좌우 방향키 이동 |
| UI-010 | KpiCard | 표시 | 지표 카드. 라벨·값(천단위 구분)·단위, 증감 표시, 아이콘 |
| UI-011 | Card | 레이아웃 | 섹션 카드 컨테이너. 제목·설명·액션 헤더와 푸터, 여백 단계 |
| UI-012 | PageHeader | 레이아웃 | 페이지 상단 헤더. 제목·설명·경로·우측 액션 영역 |
| UI-013 | Breadcrumb | 내비게이션 | 경로 표시. 마지막 항목 현재 위치 표기, 중간 항목 링크 |
| UI-014 | StatusBadge | 표시 | 작업·배치 상태 배지. 9종 이상 상태를 색·문구로 구분 |
| UI-015 | PrivacyBadge | 표시 | 개인정보 처리 등급 배지(개인정보·가명처리·비식별) |
| UI-016 | EventTypeBadge | 표시 | 이벤트 유형 배지(쓰러짐·폭력·교통사고·이상행동·침수·산불). 코드·표시명 모두 입력 가능 |
| UI-017 | StageBadge | 표시 | 배치 단계 배지. 단계 + 상태 조합으로 색·문구 결정 |
| UI-018 | BatchStageIndicator | 표시 | 배치 파이프라인 진행 스테퍼. 단계별 완료·진행·실패·대기 표시와 진행률 |
| UI-019 | ProgressBar | 피드백 | 진행률 바. 값 자동 보정, 4종 색조, 백분율 표기 옵션 |
| UI-020 | EmptyState | 피드백 | 빈 상태 안내. 제목·메시지·아이콘·후속 동작 버튼 |
| UI-021 | ErrorState | 피드백 | 오류 상태 안내. 제목·메시지·재시도 버튼 |
| UI-022 | LoadingOverlay | 피드백 | 로딩 덮개. 전체 화면·컨테이너 모드, 안내 문구 표시 |
| UI-023 | Toast | 피드백 | 알림 토스트. 성공·오류·경고·정보 표현, 자동 사라짐 |
| UI-024 | Checkbox | 입력 | 체크박스. 라벨·오류·부분 선택 상태 지원 |
| UI-025 | Radio | 입력 | 라디오 입력 기본 요소 |
| UI-026 | RadioGroup | 입력 | 라디오 그룹. 선택지 배열, 가로·세로 배치, 오류 표시 |
| UI-027 | Textarea | 입력 | 멀티라인 텍스트 입력. 라벨·도움말·오류, 행 수 지정 |
| UI-028 | DatePicker | 입력 | 날짜 선택. 값은 연-월-일 문자열, 한국어 표기 |
| UI-029 | DateRangePicker | 입력 | 기간 선택. 시작·종료 상호 제약 |
| UI-030 | FormField | 입력 | 폼 필드 바인딩 래퍼. 값·변경·오류를 하위 입력에 전달 |
| UI-031 | Popover | 오버레이 | 팝오버. 트리거 버튼과 위치 지정, 외부 클릭·ESC 닫기 |
| UI-032 | Spinner | 피드백 | 로딩 스피너. 3종 크기, 화면낭독기 안내 |
| UI-033 | Skeleton | 피드백 | 로딩 자리표시자. 너비·높이·모서리 지정 |
| UI-034 | AppLayout | 레이아웃 | 내부 채널 공통 레이아웃. 상단 내비게이션 + 좌측 메뉴 + 본문 |
| UI-035 | Gnb | 내비게이션 | 전역 상단 내비게이션. 로고·제목·역할 표시 |
| UI-036 | Lnb | 내비게이션 | 좌측 메뉴. 그룹별 항목과 역할 기반 노출 제어, 현재 위치 강조 |
| UI-037 | PortalLayout | 레이아웃 | 외부 포털 전용 레이아웃(좌측 메뉴 없음, 모바일 대응) |
| UI-038 | Footer | 레이아웃 | 하단 푸터. 버전·발주처 표기 |
| UI-039 | SimplePieChart | 데이터 | 파이 차트. 항목·값·색상, 범례 표시 |
| UI-040 | SimpleBarChart | 데이터 | 막대 차트. 항목·값, 반응형 크기, 축 간격 지정 |
| UI-041 | AuthImage | 표시 | 인증 토큰을 포함해 이미지를 내려받아 표시하고 사용 후 자원을 해제 |
| UI-042 | VideoPlayer | 표시 | 영상 플레이어. 재생·일시정지, 배속 0.25~4배, 현재 시각·프레임 조회, 위치 이동 |
| UI-043 | MarkingToolbar | 액션 | 마킹 도구 모음. 방식 토글, 자동 간격 입력, 제출·초기화, 마킹 건수 표시 |
| UI-044 | MarkingTimeline | 표시 | 마킹 타임라인. 영상 길이 대비 위치 배치, 마킹 선택 |
| UI-045 | MarkingList | 데이터 | 저장된 마킹 목록. 프레임 정보와 삭제, 빈 상태 안내 |
| UI-046 | CanvasShell | 표시 | 라벨링 캔버스 컨테이너. 이미지·라벨·오버레이 계층 분리로 재렌더 최소화 |
| UI-047 | ToolBar | 액션 | 라벨링 도구 선택 바. 도구 버튼과 단축키 안내 |
| UI-048 | LabelSidebar | 내비게이션 | 라벨 마스터 사이드바. 사용 중 라벨을 정렬순으로 표시하고 활성 라벨 선택 |
| UI-049 | ObjectClassTree | 데이터 | 객체 트리. 라벨별 그룹화·펼치기, 출처·형태 표시, 선택·삭제 |
| UI-050 | ObjectAttributePanel | 입력 | 객체 속성 편집 패널. 라벨 변경, 좌표 편집, 신뢰도·출처 표시 |
| UI-051 | FrameFilmstrip | 내비게이션 | 프레임 썸네일 가로 스크롤. 클릭 시 해당 프레임 이동 |
| UI-052 | FrameNavigator | 내비게이션 | 현재/전체 프레임 표시와 이전·다음 이동 |
| UI-053 | SaveCommitButton | 액션 | 라벨 저장 버튼. 작업본 임시저장, 저장 중 중복 클릭 차단 |
| UI-054 | UndoRedoToolbar | 액션 | 되돌리기·다시실행 버튼. 이력 길이에 따른 비활성 제어 |
| UI-055 | LabelHeader | 레이아웃 | 라벨링 화면 상단 헤더. 영상·프레임 정보, 저장 상태, 주요 액션 |
| UI-056 | TimeseriesSidePanel | 표시 | 시계열 메타 접이식 편집 패널. 항목별 조회·수정·저장 |
| UI-057 | DeidentReportButton | 액션 | 비식별 누락 신고 버튼. 사유 입력 모달과 신고 접수, 잠금 안내 |
| UI-058 | ReviewLabelCanvas | 표시 | 검수용 읽기 전용 라벨 캔버스. 라벨 오버레이와 선택 연동 |
| UI-059 | ReviewActionBar | 액션 | 검수 하단 액션 바. 승인·반려 버튼과 처리 완료 시 비활성 안내 |
| UI-060 | ReviewHeader | 레이아웃 | 검수 화면 상단 헤더. 영상·작업자·제출일·프레임 카운터·상태 |
| UI-061 | IssueSidebar | 데이터 | 검수 이슈 사이드바. 프레임별 이슈 카드 누적과 추가 |
| UI-062 | RejectModal | 오버레이 | 검수 반려 모달. 반려 사유 입력·검증 |
| UI-063 | ReviewFrameTimeline | 내비게이션 | 검수 프레임 타임라인. 썸네일 스크롤과 진행률 표시 |
| UI-064 | ObjectListPanel | 데이터 | 검수 객체 목록 패널. 라벨별 그룹 트리와 캔버스 선택 동기화 |
| UI-065 | ReviewMemoPanel | 입력 | 검수 메모 패널. 이슈 추가 모드, 이슈 목록, 검수 의견 입력 |
| UI-066 | VersionList | 데이터 | 버전 스냅샷 목록. 최신순 표시, 현재 버전 표기, 행별 복구 동선 |
| UI-067 | DiffViewer | 표시 | 버전 간 변경 표시. 추가·수정·삭제를 색으로 구분, 빈 상태 안내 |
| UI-068 | VersionPicker | 입력 | 비교 대상 버전 선택 드롭다운 |
| UI-069 | RollbackConfirmModal | 오버레이 | 복구 확인 모달. 위험 표현 확인 후 복구 실행 |
| UI-070 | HistoryPanel | 레이아웃 | 버전 이력 인라인 패널. 버전 목록·비교·복구를 한 패널로 제공 |
| UI-071 | AugmentTypeCard | 입력 | 증강 유형 선택 카드. 아이콘·제목·설명과 선택 표시 |
| UI-072 | JobCard | 표시 | 증강 요청 카드. 유형·상태·요청 일시 표시 |
| UI-073 | DecisionCard | 액션 | 증강 결과 활용 결정 카드. 채택·거부와 결정 일시·사유 표시 |
| UI-074 | TimeseriesTextPanel | 입력 | 시계열 메타 텍스트 검토·수정 패널 |
| UI-075 | StateChangeTimeline | 표시 | 외부 자동 감지 상태 변화 타임라인. 구간별 전이 순차 표시 |
| UI-076 | ConfidenceDistribution | 표시 | 자동 라벨 신뢰도 분포. 3구간 히스토그램 카드 |
| UI-077 | MyTaskCard | 표시 | 작업자 내 작업 현황 카드(대기·진행중·검수대기·반려) |
| UI-078 | EventDistributionGrid | 표시 | 이벤트 분포 그리드. 6종 이벤트 건수·비율 |
| UI-079 | NoticeCard | 표시 | 공지 카드. 고정 공지 우선·최신순, 빈 상태 안내 |
| UI-080 | WorkerStatsTable | 데이터 | 작업자 통계 표. 헤더 클릭 정렬, 로딩 상태 |
| UI-081 | DailyCompletionChart | 데이터 | 일별 완료량 막대 차트 |
| UI-082 | EventTypePieChart | 데이터 | 이벤트 유형 비율 파이 차트 |
| UI-083 | AssignModal | 오버레이 | 작업 배정 모달. 단건·재배정·일괄 3모드, 작업자·검수자 선택 |
| UI-084 | HistoryDrawer | 오버레이 | 배정 이력 드로어. 배정·재배정·제출·승인·반려 이력 타임라인 |
| UI-085 | TaskFilters | 입력 | 작업 목록 필터 바. 검색어·상태·담당자·이벤트 유형과 초기화 |
| UI-086 | InferenceConfigCard | 입력 | AI 추론 파라미터 설정 카드. 인식 민감도·이미지 크기·중첩 임계값 |
| UI-087 | BatchConfigCard | 입력 | 배치 파이프라인 설정 카드. 처리 주기·동시 처리 수 |
| UI-088 | PrecisionConfigCard | 입력 | 라벨링 정밀도 설정 카드. 인식 민감도·경계 세밀함 |
| UI-089 | HealthStatusList | 표시 | 외부 연동 상태 목록. 구성요소별 연결 상태와 응답 지연 |
| UI-090 | DangerActions | 액션 | 위험 작업 영역. 파괴적 조치를 확인 절차 후 실행 |
| UI-091 | PresetEditModal | 오버레이 | 오토라벨 프리셋 생성·편집 모달. 이벤트 유형과 라벨 구성 |
| UI-092 | PresetCodeChip | 표시 | 프리셋 라벨 칩. 라벨명 표시와 유효·무효 구분, 삭제 |
| UI-093 | VideoStatusStepper | 표시 | 영상 처리 단계 스테퍼. 단계별 상태 아이콘과 진행률 |
| UI-094 | VideoActions | 액션 | 영상 행 액션 버튼. 권한별 배정·상세 진입 |
| UI-095 | VideoFilters | 입력 | 영상 목록 필터. 검색어·상태·이벤트 유형·기간과 초기화 |
| UI-096 | AugmentTypeCheckbox | 입력 | 증강 유형 다중 선택 체크박스 |
| UI-097 | IssueThreadPanel | 데이터 | 검수자·작업자 이슈 소통 스레드 패널. 미해소 문의 건수 배지 |

## 항목 설명

### ■ 사용자 인터페이스 구조도
> 전체 시스템에 대하여 한 개를 작성한다. 최상위레벨에서 Top-down으로 작성되며, 시스템의 규모가 큰 경우 서브시스템으로 나누어 작성할 수 있다.

### ■ 사용자 인터페이스 목록
- **화면 ID**: 화면별로 유일한 ID를 부여하여 기입한다.
- **화면명**: 화면을 식별할 수 있는 명칭을 기입한다.
- **관련 유스케이스 ID**: 본 산출물이 관련되는 "유스케이스 명세서"의 유스케이스 ID를 기입한다.
- **관련 시퀀스도 ID**: 본 산출물이 관련되는 "클래스 설계서"의 시퀀스도 ID를 기입한다.
- **출력물 ID**: 출력물이 있을 경우 출력물별로 유일한 ID를 부여하여 기입한다.
- **출력물명**: 출력물을 식별할 수 있는 명칭을 기입한다.

### ■ 화면상세설계
- **화면 ID / 화면명**: 관련된 화면 ID·명칭을 기입한다.
- **관련 유스케이스 ID**: 관련되는 "유스케이스 명세서"의 유스케이스 ID를 기입한다.
- **관련 시퀀스도 ID**: 관련되는 "클래스 설계서"의 시퀀스도 ID를 기입한다.
- **화면유형**: 화면 데이터 처리 방식에 따라 입력·조회·갱신·삭제 등으로 표시한다.
- **메뉴 경로**: 사용자가 화면에 도달하기까지의 메뉴 트리 깊이를 `>` 로 구분해 표시하며, 1.사용자 인터페이스 구조도(Level 1~4)와 일관되게 작성한다.
- **화면 개요**: 화면의 목적 및 기능에 대하여 간략하게 기술한다.
- **탭 구성**: 탭이 있는 화면은 각 탭의 레이아웃·구성을 탭별로 구분해 기술한다(탭이 없는 화면은 생략).
- **항목명**: 화면의 입출력 데이터 항목을 식별할 수 있는 명칭을 기입한다(버튼·도구 등 조작 요소는 제외한다).
- **컨트롤명**: 화면을 구현하는 컨트롤의 명칭을 기술한다.
- **타입 및 길이**: 항목의 데이터 타입과 최대 길이를 기술한다.
- **속성**: 입출력 구분 Input(I)·Output(O)·Input+Output(IO)와 편집 속성 ReadOnly(R)·Editable(E)·Hidden(H)을 `구분 / 속성` 형태로 기술한다.
- **Validation Check**: 항목의 입력 오류를 체크할 수 있는 입력 범위(필수 여부·길이·범위·허용값)를 기술한다.
- **처리 내용**: 화면이 처리하는 업무 내용을 이벤트·버튼 단위로 구분해 기술하며, 각 이벤트에 관련 시퀀스도 ID를 함께 기재한다.
- **기술적 고려사항**: 화면을 구현하기 위하여 필요한 기술적 요소에 대한 고려사항을 기술한다.

## 작성 시 참고사항

> **ID 체계 (프로젝트 공식)**
> - 프로젝트 ID: `KLID` / 서브시스템 ID(저작도구): `AT`
> - 산출물 파일명: `KLID_AT_사용자인터페이스설계서_Rev {버전}`
> - 화면 ID: `KLID-AT-SC-NNN` / 관련 유스케이스 ID: `KLID-AT-UC-NNN`(R2 참조) / 관련 시퀀스도 ID: `KLID-AT-SD-NNN`(D1 참조) / 출력물 ID: `KLID-AT-OUT-NNN` / 공통 컴포넌트 ID: `UI-NNN`
> - 유스케이스 ID와 시퀀스도 ID는 동일 번호로 1:1 대응하며, 요구사항–유스케이스–화면 추적은 R3 요구사항 추적표에서 관리한다.
> - 속성 표기: 입출력 구분 `I`·`O`·`IO` / 편집 속성 `R`·`E`·`H`, `구분 / 속성` 형태로 기재한다.
> - 화면 와이어프레임은 인라인 스타일만 사용해 외부 자원 없이 자기완결적으로 작성한다.

## 부록 — 범위 제외 화면 및 빈 셀 사유

### 1. 범위 제외 화면

공통 범위 규칙에 따라 기능 요구사항(SFR)을 실현하는 유스케이스에 매핑된 화면만 본문에 포함하였다. 아래는 실재하지만 본문에서 제외한 화면과 사유다.

| 화면 | 구분 | 제외 사유 |
|------|------|----------|
| 세션 인계 진입 / 역할 클레임 / 접근 거부 | 인증·진입 보조 | 상위 시스템이 발급한 토큰을 인계받아 채널별로 분기하는 진입 처리 화면으로, 기능 요구사항을 실현하는 유스케이스 매핑이 없다. |
| 개발용 로그인 / 오토라벨 테스트 | 개발 지원 | 운영 배포 대상이 아닌 개발·검증 전용 화면이다. |
| 대시보드 / 작업자 통계 / 전체 구축 현황 | 운영 조회 보조 | 현황 요약·통계 조회 화면으로 전용 기능 요구사항과 대응 유스케이스가 없다(유스케이스 명세서 부록의 제외 항목과 정합). |
| 작업 목록 | 중복 | 작업자의 배정 작업 진입 목록으로, 영상 적재·마킹 진입 동선의 대표 화면이 이미 영상 목록 화면(KLID-AT-SC-007)으로 본문에 반영되어 중복이다. 작업 배정 자체는 별도 기능 요구사항이 아니다. |
| 사용자 관리 / 프리셋 관리 / 이벤트 유형 관리 | 운영 관리 | 전용 기능 요구사항이 없는 운영 관리 화면이다. 라벨 정의 관리만 유스케이스가 정의되어 본문(KLID-AT-SC-036)에 포함하였다. |
| 게시판(공지 목록·공지 상세) | 보조 기능 | 기능 요구사항 매핑이 없는 게시판 보조 기능이다. |
| 포털 홈 / 포털 라벨링 / 포털 업로드 / 포털 업로드 라벨링 | 채널 범위 밖 | 외부 포털 채널 화면으로 본 산출물의 기능 요구사항 기준선 범위 밖이다(유스케이스 명세서 부록과 정합). |
| 라벨 이력(전용 페이지) | 폐지 | 전용 페이지를 폐지하고 라벨링 캔버스 화면(KLID-AT-SC-005)의 버전 이력 패널로 기능을 통합하였다. 버전 비교·복구 동선은 본문 KLID-AT-SC-005에 기술한다. |
| 비식별 목록 / 비식별 상세 | 폐지 | 1차 방식(화면 내 직접 마스킹)을 폐지하고 2차에서는 외부 비식별 솔루션 위탁으로 대체하였다. |
| 영상 처리 현황(구 전용 화면) / 작업 배정(구 전용 화면) / 오토라벨 요약 / 시계열 메타 검토(구 전용 화면) | 폐지 | 진입점이 없어 각각 영상 목록·영상 상세·라벨링 캔버스(시계열 메타 패널)로 기능이 흡수되었다. |

> 시계열 메타 검토는 전용 화면이 폐지되어 라벨링 캔버스 화면(KLID-AT-SC-005)의 시계열 메타 패널이 담당한다.
> 비식별 옵션 설정은 저작도구의 전용 화면 없이, 외부 비식별 솔루션이 제공하는 옵션 관리 화면에서 설정하고 그 결과가 위탁 요청에 반영되는 운영 동선으로 처리한다(대응 화면 부재 — 성격상 부재).
> 비식별 처리 요청은 적재 완료를 신호로 자동 수행되는 것이 기본 동선이며, 사람이 개입하는 재요청 동선만 영상 상세 화면(KLID-AT-SC-009)에 존재한다.

### 2. 빈 셀(`-`) 사유

- 입출력 항목 표의 `Validation Check` 열 `-` 는 **성격상 부재**다. 출력 전용(`O / R`) 항목은 사용자가 입력하지 않으므로 입력 검증 규칙이 존재하지 않는다.
- 사용자 인터페이스 구조도의 Level 3·4 열 `-` 는 해당 메뉴가 그 깊이의 하위 화면을 갖지 않음을 뜻하며 **성격상 부재**다.
- 본문 화면 목록·화면 상세에는 데이터 미상으로 인한 빈 셀이 없다. 모든 수록 화면에 관련 유스케이스·시퀀스도가 매핑되었다.
- 메뉴 구조 자료(내부 채널·포털 채널 2건)는 §1 사용자 인터페이스 구조도에, 공통 컴포넌트 자료(97건)와 디자인 표준(1건)은 §4 공통 UI 컴포넌트 명세에 각각 반영하였다.
