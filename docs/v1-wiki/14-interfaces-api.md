# 14. 인터페이스 · REST API

> 출처: 인터페이스규격서 D4 V1.0(**PDF 원문 미포함** — 1차 PDF 3종에 이 문서가 없다. `sources/KLID-AI-인터페이스규격서_분석.md` 기준) · 통합설계서 §13(PDF 원문 미포함, `sources/` 분석본 기준) · UI설계서 §5(**존재하지 않는 절** — 아래 정정 참고)
> 관련: [06 프레임 추출](06-video-frame-pipeline.md) · [08 AI 보조 라벨링](08-ai-assisted-labeling.md)
>
> ⚠ **출처 확인 결과(2026-08-19 원문 실측)** — 이 문서(14장)가 다루는 인터페이스 규격서(D4)·REST API·데이터 모델·연동 시스템 서술은 **1차 PDF 3종(화면정의서·관리자매뉴얼 Rev.1.0·UI설계서 D2 V1.1) 어디에도 없다.** `KLID-AI-II`·`인터페이스` 키워드로 3개 파일 전문을 검색했으나 매칭은 "사용자 인터페이스 설계서"라는 문서 제목 표기뿐이었다(0건 실질 매칭). 아래 절별로 실제 출처를 표기한다. 근거: `01-UIUX설계서.txt`·`02-관리자매뉴얼.txt`·`03-UI설계서V1.1.txt` 전문 검색(`grep -a`)

## 14.1 인터페이스 목록 (KLID-AI-II-*)

| ID | 시스템 | 처리 형태 | 발생 빈도 | 프로그램 | 요구사항 | 설명 |
|----|--------|----------|----------|---------|---------|------|
| `KLID-AI-II-001` | 학습저작도구 | Batch | 1회/분 | VIDEO-PROC | SFR-06 | 비디오 프레임 추출 **요청** |
| `KLID-AI-II-002` | 학습저작도구 | Batch | 1회/분 | VIDEO-PROC | SFR-06 | 비디오 추출 **상태 응답** |
| `KLID-AI-II-003` | 학습저작도구 | Online | 수시 | AUTO-LABEL | SFR-07 | 오토라벨링 - 탐지 클래스+좌표(Array) |
| `KLID-AI-II-004` | 학습저작도구 | Online | 수시 | AUTO-LABEL | SFR-07 | 오토라벨링 - 탐지 객체들(Array) |
| `KLID-AI-II-005` | 학습저작도구 | Online | 수시 | AUTO-LABEL | SFR-07 | 오토라벨링 - Object 탐지 |
| `KLID-AI-II-006` | 학습저작도구 | Online | 수시 | AUTO-LABEL | SFR-07 | 오토라벨링 - 배열 좌표 정보 |
| `KLID-AI-II-007` | 학습저작도구 | Online | 수시 | AUTO-LABEL | SFR-07 | 트래킹 Object 정보 |

**시스템 구성**: 데이터송신시스템(원시 데이터 제공·결과 수신) ↔ 학습저작도구(처리·라벨링 중앙) ↔ 데이터수신시스템(결과 수신).
**기본 데이터 타입**: Boolean / String(가변) / Array / Object.

## 14.2 상세 정의

### II-001 비디오 추출 요청 (VIDEO-PROC)
송신: 데이터송신시스템 → 수신: 학습저작도구
속성: 원시데이터 고유번호 · 비디오 추출 여부(Boolean) · 영상 파일 경로 · 추출 시작/종료 시간 · 요청 처리 결과 메시지 · 프레임 추출 방식 · 조건 값

### II-002 비디오 추출 상태 응답 (VIDEO-PROC)
송신: 학습저작도구 → 수신: 데이터송신시스템
속성: 원시데이터 고유번호(String) · 상태/오류 메시지 · 전체 프레임 수 · 현재 추출 프레임 수

### II-003 오토라벨링 - 탐지 클래스 (AUTO-LABEL)
속성: 탐지클래스(Array) → 파일위치 + 좌표정보(Array: X·Y·가로·세로)

### II-004 오토라벨링 - 탐지 객체들
속성: 탐지클래스(Array) → 파일위치 + 좌표정보들(Array: X·Y)

### II-005 오토라벨링 - Object 탐지
속성: 파일위치 + 좌표정보들(Object: X·Y·가로·세로)

### II-006 오토라벨링 - 배열 좌표정보
속성: 파일위치 + 좌표정보들(Array: X·Y)

### II-007 트래킹 Object 정보
속성: 트래킹 목록(Array) → 트래킹 ID · 프레임번호 · 이미지경로 · 좌표(X·Y·가로·세로) · 트래킹 프레임 여부
JSON 구조는 [08 §8.4](08-ai-assisted-labeling.md#트랙-데이터-구조-ii-007-원문-미확인--위-안내-참고) 참고.

## 14.3 REST API 엔드포인트

> ⚠ **원문 미확인** — 아래 엔드포인트 목록은 1차 PDF 3종에 없다. `sources/KLID-AI-저작도구_통합설계서.md`(§13.2) 및 `sources/KLID-AI-사용자인터페이스설계서_V1_1_분석.md`에 동일 내용이 있으나, 두 분석본 모두 원출처가 무엇인지 명시하지 않는다(분석본은 진실원이 아니다). 원본 HWP(통합설계서 또는 인터페이스규격서 D4)에서 직접 확인되기 전까지 1차 사실로 인용하지 말 것.

```
GET    /api/v1/projects                              - 프로젝트 목록
GET    /api/v1/projects/{projectId}                  - 프로젝트 상세
GET    /api/v1/projects/{projectId}/videos           - 영상 목록
GET    /api/v1/projects/{projectId}/images           - 이미지 목록
GET    /api/v1/projects/{projectId}/labels           - 라벨 목록
POST   /api/v1/projects/{projectId}/labels           - 라벨 생성
GET    /api/v1/projects/{projectId}/annotations      - 어노테이션 조회
POST   /api/v1/projects/{projectId}/annotations      - 어노테이션 저장
PUT    /api/v1/projects/{projectId}/annotations/{id} - 어노테이션 수정
POST   /api/v1/projects/{projectId}/validate         - 데이터 검수
```

## 14.4 데이터 모델

> ⚠ **원문 미확인** — 아래 JSON 스키마도 1차 PDF 3종에 없다. UI설계서(D2 V1.1) §3의 화면별 입출력 항목표는 필드 단위(`paramXxx`/`camelCase` 응답 필드, string/number/resource 등)로 정의돼 있어 아래처럼 리소스 단위 JSON 스키마로 뭉쳐 제시하지 않는다. 출처 불명 — `sources/` 분석본 기준으로 추정.

**Project**
```json
{
  "projectId": "string",
  "projectName": "string",
  "status": "active|inactive|completed",
  "createdDate": "datetime",
  "assignedWorkers": ["workerId"]
}
```

**Annotation**
```json
{
  "annotationId": "string",
  "sourceDataId": "string",
  "labelId": "string",
  "coordinates": [[x1, y1], [x2, y2]],
  "objectType": "boundingbox|polygon|skeleton",
  "trackingFrames": boolean,
  "order": number,
  "status": "pending|completed|rejected"
}
```

## 14.5 데이터 흐름

```
1. 영상/이미지 수집(CCTV) → 스토리지 저장
2. 데이터 메타정보 관리(프로젝트, 속성)
3. 작업자에 프로젝트 할당
4. 라벨링 도구로 어노테이션 작업
5. 검수자 품질 검증
6. 학습 데이터 저장 및 내보내기
7. AI 모델 학습
```

### 연동 시스템

> ⚠ **출처 정정(2026-08-19 원문 실측)** — "UI설계서 §4"는 **존재하지 않는 절**이다. UI설계서(D2 V1.1)의 최상위 절은 **"1. 사용자 인터페이스 구조도" · "2. 사용자 인터페이스 목록" · "3. 화면 상세 설계" 3개뿐**이며 §4·§5는 없다(문서 끝까지 확인, 마지막 절도 §3 "화면 상세 설계"의 하위 화면들이다). 근거: `03-UI설계서V1.1.txt` §1·§2·§3 각 절 헤더(파일 끝까지 §4 미출현). 아래 내용은 `sources/KLID-AI-사용자인터페이스설계서_V1_1_분석.md` §4 "연동 시스템"에서 확인되나 그 분석본 자체가 원 PDF 어느 절에서 왔는지 밝히지 않는다.

CCTV 관제시스템 · 영상/이미지 스토리지 · AI 모델 서버 · DB(메타/라벨/속성).

> **v2 참고**: v2 API는 `ApiResponse<T>` 래퍼 + `/api/v{version}/` + 페이징 표준(rules/api-design.md). 외부 연동은 **비식별/ai-server/외부 VLM(Resilience4j)** 중심이며, 관제서버로 **단방향 outbound 통지(`TASK_COMPLETED`/`TASK_MODIFIED`)**를 보낸다. v1의 데이터송신/수신 시스템 양방향 II 인터페이스는 v2에서 deprecated(M2M 미운영).
