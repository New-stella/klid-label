# D4 인터페이스 설계서

> **ID 표기 규칙(공통)**: 본 산출물의 모든 ID(KLID-AT-UC/CO/DC/DCD/SD/SC/AC/EN/ERD/TB/II/IC/IF/UCD/ACT 등)는 **전체 형식 `KLID-AT-XX-NNN`** 으로 표기한다. 끝부분만(예: `II-001`) 약식 표기 금지. 범위는 시작 ID만 전체형으로(예: `KLID-AT-II-001~014`). ※ 요구사항(RQ-SFR-NN-NN)·시스템시험(KLID-ST-NNN) 등 타 체계 ID는 각 체계 원형 유지.

## 작성 목적
> 시스템의 내.외부 인터페이스를 식별하고 인터페이스의 명세를 기술한다.

## 작성 방법
> 식별된 인터페이스를 송신측과 수신측으로 구분하여 기술하고 송.수신간의 인터페이스 방식을 기술하며, 데이터 송신 시스템과 수신 시스템간의 데이터 저장소와 속성 등의 상세 내역을 기술한다.

## 산출물 양식

### 제.개정 이력

| 날짜 | 버전 | 작성자 | 승인자 | 내용 |
|------|------|--------|--------|------|
| 2026-08-06 | 1.0 | - | - | LogiCraft 그래프 기반 생성 (/cc-doc-gen) |

### 헤더

| D4 | 인터페이스 설계서 |
|-------|-----------|
| 시스템명 | AI 기반 지방정부 CCTV 관제지원시스템(2차) | 서브시스템명 | 학습데이터 저작도구 |
| 단계명  | 설계        | 작성일자   | 2026-08-06 | 버전 | 1.0 |

### 1. 인터페이스 목록

| 인터페이스번호 | 송신 일련번호 | 송신 시스템명 | 송신 프로그램 ID | 전달 처리형태 | 전달 인터페이스방식 | 전달 발생빈도 | 수신 상대 담당자 | 수신 프로그램 ID | 수신시스템명 | 수신 일련번호 | 수신번호 | 관련 요구사항 ID | 비고 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| KLID-AT-II-001 | 1 | 학습데이터 저작도구 | 비식별위탁 | Online | HTTP/REST(JSON) | 1회/영상 | 연동 완료(외부 비식별 솔루션) — 담당자 성명 확인 필요 | 비식별프로젝트생성 | 외부 비식별 솔루션 | 1 | - | RQ-SFR-09-01 · RQ-SFR-09-02 | 적재된 전체 영상을 대상으로 하는 비식별 위탁(처리 흐름의 선두 단계). **공유 저장소 직접 참조 방식**으로 원본을 입력 경로로 지정하고(영상 본문 전송 없음) 결과는 솔루션이 결과 경로에 직접 산출한다. 원본은 별도 경로에 보존한다. 인증 헤더 없음 — 내부망 평문 또는 자체 인증서 기반 보안 전송으로 분기. 재시도 최대 3회(점증 대기), 요청 오류(4xx)는 재시도 제외 |
| KLID-AT-II-002 | 1 | 학습데이터 저작도구 | 비식별진행폴링 | Batch | HTTP/REST(JSON) | 1회/30초 | 연동 완료(외부 비식별 솔루션) — 담당자 성명 확인 필요 | 비식별진행조회 | 외부 비식별 솔루션 | 1 | - | RQ-SFR-09-02 · RQ-SFR-09-05 | 위탁한 비식별 작업의 진행·완료를 주기 조회한다(솔루션이 결과 통지를 제공하지 않아 주기 조회가 유일한 완료 감지 수단). 시도 상한 240회·경과 상한 180분 초과 시 비식별 실패로 종결(거짓 완료 방지). 완료 판정은 결과 파일이 실재하고 크기가 0보다 클 때만 성립한다 |
| KLID-AT-II-003 | 1 | 학습데이터 저작도구 | 시계열메타위탁 | Online | HTTP/REST(JSON) | 1회/영상 | 미정 — 연동 시 확정 | 시계열메타생성 | 외부 시계열 메타 분석 서비스 | 1 | - | RQ-SFR-17 | 마킹 완료된 **비식별 영상**의 구간별 상황 서술 생성을 외부에 위탁한다. 영상 본문은 전송하지 않고 경로만 전달한다. 위탁은 처리 흐름을 붙잡지 않는 비동기 제출이며 수락 여부만 동기 응답으로 확인하고 결과 상세는 KLID-AT-II-004로 수신한다. 미회수 건은 주기 점검으로 회수한다. 인증 토큰이 설정된 경우에만 인증 헤더를 부착한다 |
| KLID-AT-II-004 | 1 | 외부 시계열 메타 분석 서비스 | 시계열메타생성 | Online | HTTP/REST(JSON) | 1회/영상 | 내부 — 저작도구 운영 담당 | 시계열메타결과수신(API-065) | 학습데이터 저작도구 | 1 | - | RQ-SFR-17 · RQ-SFR-11-10 | 생성된 구간별 서술을 수신해 시계열 메타로 적재하고 검수 대기열에 진입시킨다. 외부 규격이 무서명 방식이라 ①허용 호출 출처 목록 ②요청량·본문 크기 상한 ③저작도구가 발급한 요청 식별자 대조 3계층으로 진위를 검증한다. 요청 식별자 기준 멱등 처리(중복 수신 무시), 동시 수신은 직렬화 |
| KLID-AT-II-005 | 1 | 학습데이터 저작도구 | 증강위탁 | Online | HTTP/REST(JSON) | 1회/증강요청(입력 100장 초과 시 분할) | 미정 — 연동 시 확정 | 증강생성 | 외부 생성형 AI(증강) 시스템 | 1 | - | RQ-SFR-07-01 · RQ-SFR-11-05 | 검수 완료 영상의 **비식별 프레임**과 생성 조건(시간·계절·날씨·지형·심각도)을 외부 생성형 AI에 위탁한다(동절기·야간·강우 3종). 결과는 KLID-AT-II-006으로 수신한다. 멱등키 헤더(`Idempotency-Key`)를 항상 부착해 재전송 안전성을 확보한다. 인증 없음(규격 확정 사항). 동일 조건 재요청을 허용한다(중복 차단 없음) |
| KLID-AT-II-006 | 1 | 외부 생성형 AI(증강) 시스템 | 증강생성 | Online | HTTP/REST(JSON) | 4회/증강요청(진행 3회 + 종결 1회) | 내부 — 저작도구 운영 담당 | 증강결과수신(API-165) | 학습데이터 저작도구 | 1 | - | RQ-SFR-07-01 · RQ-SFR-07-02 | 증강 진행·종결 상태를 수신한다. 성공 종결 시 원본을 참조하는 **새 영상**으로 등록하고 원본 라벨·메타를 좌표·속성 그대로 복사한 뒤 미검수 상태로 시작한다. 무서명 규격이라 허용 호출 출처 목록(미설정 시 전면 차단)·요청량/본문 크기 상한·요청 식별자 대조로 방어한다. 반영 여부를 응답 본문으로 회신하며 중복 수신은 멱등 흡수한다 |
| KLID-AT-II-007 | 1 | 학습데이터 저작도구 | 완료통지발신 | Online | HTTP/REST(JSON) | 1회/검수승인 | 미정 — 연동 시 확정 | 완료통지수신 | 관제지원시스템 | 1 | API-251 | RQ-SFR-11-10 · RQ-SFR-08-04 | 영상 단위 검수 승인 시 완료를 단방향 통지한다(메타 요약만 — 라벨·메타 본문, 개인정보, 인증정보 미포함). 산출물 생성이 성공한 뒤 발송하며 실패 시 성공 시점까지 보류한다. 실패분은 영속 재전송 대기열에 적재해 5분 간격·지수 백오프로 재전송하고 상한 초과 시 사후처리 대기열로 이관한다. 인증 헤더(`x-access-token`) 발급 주체는 상대 시스템 회신 대기 중 |
| KLID-AT-II-008 | 1 | 학습데이터 저작도구 | 수정통지발신 | Online | HTTP/REST(JSON) | 1회/수정확정(단시간 다중 변경은 1회로 병합) | 미정 — 연동 시 확정 | 수정통지수신 | 관제지원시스템 | 1 | API-285 | RQ-SFR-08-04 · RQ-SFR-08-05 | 검수 완료 후 라벨·메타가 수정되어 산출물을 새 버전으로 재생성했을 때 변경 요약을 통지한다(본문 미포함). 동일 작업 식별자를 유지하며 수신측은 마지막 상태로 갱신한다. 재생성이 없는 수정은 변경 목록을 빈 값으로 보낸다 |
| KLID-AT-II-009 | 1 | 학습데이터 저작도구 | 학습데이터조회뷰 | Online | 데이터베이스 연결(SQL 조회) | 1회/통지수신 | 미정 — 연동 시 확정 | 데이터마트적재 | 관제지원시스템 | 1 | - | - | 검수 승인 영상만 노출하는 조회 전용 뷰 4종(영상·프레임·라벨 변경점·시계열 메타)을 데이터베이스 계정으로 제공하고 상대 시스템이 직접 조회한다. 영상 1건 = 1행이 보장된다. 라벨 본문은 뷰에 두지 않고 산출 폴더 파일이 유일한 출처다. 검수 완료·통지된 영상은 어떤 사유로도 뷰에서 감추거나 경로를 비우지 않는다 |
| KLID-AT-II-010 | 1 | 학습데이터 저작도구 | 메타복제송신 | Batch | 데이터베이스 연결(SQL 반영) | 1회/60초 | 포털 운영 담당 — 담당자 성명 확인 필요 | 메타복제적재 | 포털 | 1 | - | - | 영상 메타 스냅샷을 포털 채널 데이터베이스로 **단방향 복제**한다. 전송 대기 원장에 적재한 뒤 주기 점검으로 반영하며, (영상 식별자, 스냅샷 해시) 기준으로 중복을 무시해 멱등을 보장한다. 실패 누적 5회 초과 시 사후처리 상태로 이관하고, 복제본이 아직 구성되지 않은 환경은 안전하게 건너뛴다 |
| KLID-AT-II-011 | 1 | 학습데이터 저작도구 | 객체탐지요청 | Online | HTTP/REST(JSON) | 1회/프레임 | 내부 — 저작도구 운영 담당 | 객체탐지(API-113) | 학습데이터 저작도구(AI 추론 서버) | 1 | - | RQ-SFR-11-04 · RQ-SFR-11-08 | 자동 라벨링용 객체 탐지. 프레임 이미지를 부호화 문자열로 전달하고 탐지 결과(라벨·경계 좌표·신뢰도)를 동기 수신한다. AI 추론 서버는 인증·데이터베이스가 없는 무상태 추론 전용이며, 저작도구가 타임아웃·재시도·장애 차단 정책을 적용해 호출한다 |
| KLID-AT-II-012 | 1 | 학습데이터 저작도구 | 객체추적요청 | Online | HTTP/REST(JSON) | 1회/프레임 | 내부 — 저작도구 운영 담당 | 객체추적(API-119) | 학습데이터 저작도구(AI 추론 서버) | 1 | - | RQ-SFR-08-01 | 프레임 간 객체 추적. 영상 식별자별로 추적기를 격리하며 프레임 순번이 0이면 추적기를 초기화한다. 응답의 추적 식별자로 프레임 간 동일 객체를 연결한다 |
| KLID-AT-II-013 | 1 | 학습데이터 저작도구 | 영역분할요청 | Online | HTTP/REST(JSON) | 1회/분할요청 | 내부 — 저작도구 운영 담당 | 영역분할(API-120) | 학습데이터 저작도구(AI 추론 서버) | 1 | - | RQ-SFR-08-02 | 사용자의 클릭 또는 박스 지정을 받아 객체 외곽 경계(폴리곤)와 신뢰도를 산출한다. 산출 좌표는 이미지 실측 크기 범위 내로 검증한 뒤 적용한다 |
| KLID-AT-II-014 | 1 | 학습데이터 저작도구 | 분할전파요청 | Online | HTTP/REST(JSON) | 1회/전파프레임 | 내부 — 저작도구 운영 담당 | 분할전파(API-121) | 학습데이터 저작도구(AI 추론 서버) | 1 | - | RQ-SFR-08-01 | 이전 프레임의 경계를 다음 프레임으로 전파하고 동일 추적 식별자를 유지한다. 추론 실패 시 기존 경계를 그대로 반환해 라벨을 보존한다 |

> ※ '수신번호'는 상대 시스템의 인터페이스 채번을 보유한 관제 통지(KLID-AT-II-007·008)만 기입하고 나머지는 `-` 로 둔다. '수신 상대 담당자'가 `미정 — 연동 시 확정` 인 항목은 시스템 간 양방향 자동 연동이 미운영 상태로 상대측 실무담당자가 지정되지 않은 경우다. 관련 요구사항 ID가 `-` 인 항목(KLID-AT-II-009·010)의 사유는 부록에 기술한다.
>
> ※ 객체 탐지·추적·분할 추론(KLID-AT-II-011~014)은 저작도구가 자체 운영하는 **내부 추론 인프라**로, 저작도구 본체와 추론 서버가 별도 프로세스로 통신하는 시스템 간 연동이므로 본 목록에 포함한다. 수신시스템명은 저작도구의 하위 구성임을 나타내 `학습데이터 저작도구(AI 추론 서버)` 로 표기한다.
>
> ※ 외부 시스템과의 양방향 자동 연동(상호 인증 기반 통합)은 미운영한다. 관제지원시스템 연동은 저작도구가 보내는 **단방향 통지**(KLID-AT-II-007·008)와 상대 시스템이 상세를 가져가는 **조회 제공**(KLID-AT-II-009 및 저작도구 조회 인터페이스)으로 구성한다.

### 2. 인터페이스 명세

각 인터페이스 명세는 **인터페이스 설명**(연동 목적·처리 흐름), **속성 표**(송신·수신 데이터 저장소·속성 좌우 대조), **동기 응답**(수락 확인·결과 등 응답 규격) 순으로 기술한다. 표의 각 행은 전달(송신) 데이터의 속성 1건이며, 송신측 데이터 저장소·속성과 그 데이터가 적재·처리되는 수신측 데이터 저장소·속성을 좌우로 대조한다.

<!-- hwpx:ignore-start -->
### 비식별 처리 위탁
<!-- hwpx:ignore-end -->

#### KLID-AT-II-001 — 비식별 처리 위탁

> **인터페이스 설명**: 적재된 영상 전체를 외부 비식별 솔루션에 위탁해 비식별 프로젝트를 생성하고 마스킹을 개시한다(처리 흐름의 선두 단계). **공유 저장소 직접 참조 모델**로 원본을 입력 경로로 지정하므로 영상 본문 업로드 단계가 없고, 결과도 솔루션이 결과 경로에 직접 산출하므로 내려받기 단계가 없다. 원본은 별도 경로에 보존하며 실패 시에도 삭제하지 않는다.

<table>
<tr><td rowspan="2">인터페이스 번호</td><td colspan="5" align="center"><b>데이터송신시스템</b></td><td rowspan="2">송신 프로그램 ID</td><td colspan="5" align="center"><b>데이터수신시스템</b></td><td rowspan="2">수신 프로그램 ID</td><td rowspan="2">속성 설명</td></tr>
<tr><td>시스템명</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>시스템명</td></tr>
<tr><td rowspan="10">KLID-AT-II-001</td><td rowspan="10">학습데이터 저작도구</td><td rowspan="10">LS_DEIDENT_PROC_LOG (비식별 처리 로그)</td><td>ORGNL_FILE_PATH_NM (원본 파일 경로명)</td><td>VARCHAR</td><td>1000</td><td rowspan="10">비식별위탁</td><td rowspan="10">비식별 프로젝트 생성 요청</td><td>input_path (입력 경로)</td><td>VARCHAR</td><td>1000</td><td rowspan="10">외부 비식별 솔루션</td><td rowspan="10">비식별프로젝트생성</td><td>필수. 원본 영상이 놓인 입력 디렉터리 경로. 공유 저장소로 직접 참조하므로 영상 본문을 전송하지 않는다</td></tr>
<tr><td>DE_IDNTF_FILE_PATH_NM (비식별 파일 경로명)</td><td>VARCHAR</td><td>1000</td><td>export_path (결과 저장 경로)</td><td>VARCHAR</td><td>1000</td><td>필수. 비식별 결과가 산출될 디렉터리 경로. 솔루션이 이 경로에 직접 산출하며 실제 산출 파일명은 솔루션이 정한다</td></tr>
<tr><td>project_name (프로젝트명 = 접두어 + 영상 식별자 조합)</td><td>VARCHAR</td><td>-</td><td>project_name (프로젝트명)</td><td>VARCHAR</td><td>-</td><td>필수. 비식별 프로젝트명. 영상 식별자로 조합해 유일성을 보장하며 동일명이 존재하면 상대 시스템이 충돌로 거부한다</td></tr>
<tr><td>creator (요청자)</td><td>VARCHAR</td><td>-</td><td>creator (요청자)</td><td>VARCHAR</td><td>-</td><td>필수. 위탁 요청자 식별자. 시스템 설정값이며 대응 데이터베이스 컬럼이 없다</td></tr>
<tr><td>files (대상 파일 목록)</td><td>JSONB</td><td>-</td><td>files (대상 파일 목록)</td><td>JSONB</td><td>-</td><td>필수. 비식별 처리 대상 파일명 배열</td></tr>
<tr><td>masking_type (마스킹 종류)</td><td>INT</td><td>-</td><td>masking_type (마스킹 종류)</td><td>INT</td><td>-</td><td>선택. 마스킹 방식 코드. 미지정 시 규격 기본값 적용. 관리 화면에서 설정한 옵션을 반영한다</td></tr>
<tr><td>masking_range (마스킹 범위)</td><td>INT</td><td>-</td><td>masking_range (마스킹 범위)</td><td>INT</td><td>-</td><td>선택. 마스킹 적용 범위 코드. 미지정 시 규격 기본값 적용</td></tr>
<tr><td>db_save (결과 저장 여부)</td><td>INT</td><td>-</td><td>db_save (결과 저장 여부)</td><td>INT</td><td>-</td><td>선택. 상대 시스템의 결과 보관 여부 코드. 미지정 시 규격 기본값 적용</td></tr>
<tr><td>exp_quality (결과 품질)</td><td>INT</td><td>-</td><td>exp_quality (결과 품질)</td><td>INT</td><td>-</td><td>선택. 결과 영상 품질 코드. 미지정 시 규격 기본값 적용</td></tr>
<tr><td>exp_format (결과 포맷)</td><td>INT</td><td>-</td><td>exp_format (결과 포맷)</td><td>INT</td><td>-</td><td>선택. 결과 영상 포맷 코드. 미지정 시 규격 기본값 적용</td></tr>
</table>

> **동기 응답**: 외부 비식별 솔루션 → 저작도구. 처리 결과 코드(`result`, VARCHAR)와 비식별 프로젝트 식별자(`prj_id`, BIGINT — 성공 시에만)를 회신한다. 프로젝트 식별자는 `LS_DEIDENT_PROC_LOG.DE_IDNTF_PJT_ID`(BIGINT)에 보관하며 이후 진행 조회(KLID-AT-II-002)의 조회 키가 된다. 재위탁 시에는 이전 산출물을 먼저 정리해 구 결과를 잘못 회수하는 일을 막는다. 요청 오류(4xx)는 재시도 대상에서 제외한다.

<!-- hwpx:ignore-start -->
### 비식별 진행 상태 조회
<!-- hwpx:ignore-end -->

#### KLID-AT-II-002 — 비식별 진행 상태 조회

> **인터페이스 설명**: 생성한 비식별 프로젝트의 처리 진행 상태를 30초 주기로 조회해 완료를 감지하고 결과를 회수한다(마킹 단계 진입 허용). 상대 시스템이 결과 통지를 제공하지 않으므로 주기 조회가 유일한 완료 감지 수단이다. 결과 파일 경로는 상대 시스템이 통보한 값을 그대로 기록해 사용하며 조합·추측하지 않는다.

<table>
<tr><td rowspan="2">인터페이스 번호</td><td colspan="5" align="center"><b>데이터송신시스템</b></td><td rowspan="2">송신 프로그램 ID</td><td colspan="5" align="center"><b>데이터수신시스템</b></td><td rowspan="2">수신 프로그램 ID</td><td rowspan="2">속성 설명</td></tr>
<tr><td>시스템명</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>시스템명</td></tr>
<tr><td rowspan="2">KLID-AT-II-002</td><td rowspan="2">학습데이터 저작도구</td><td rowspan="2">LS_DEIDENT_PROC_LOG (비식별 처리 로그)</td><td>reqUserId (요청자 식별자)</td><td>VARCHAR</td><td>-</td><td rowspan="2">비식별진행폴링</td><td rowspan="2">비식별 진행 조회 요청</td><td>reqUserId (요청자 식별자)</td><td>VARCHAR</td><td>-</td><td rowspan="2">외부 비식별 솔루션</td><td rowspan="2">비식별진행조회</td><td>필수. 진행 조회 요청자 식별자. 시스템 설정값이며 대응 데이터베이스 컬럼이 없다</td></tr>
<tr><td>DE_IDNTF_PJT_ID (비식별 프로젝트 식별자)</td><td>BIGINT</td><td>-</td><td>prjId (프로젝트 식별자)</td><td>BIGINT</td><td>-</td><td>필수. 조회 대상 비식별 프로젝트 식별자. 위탁(KLID-AT-II-001) 응답으로 받은 값이며 조회는 단일 프로젝트를 대상으로 한다</td></tr>
</table>

> **동기 응답**: 외부 비식별 솔루션 → 저작도구. 프로젝트별 처리 상태 목록을 회신한다 — 데이터셋 처리 상태(`procState`, INT), 진행률(`progressRate`, NUMERIC), 결과 파일 경로(`fileName`, VARCHAR), 총 프레임 수(`totalFrame`, INT), 시작·종료 일시(`startTime`·`endTime`, TIMESTAMP). 처리 상태가 완료를 뜻하면 결과 파일의 **실재 여부와 크기(0 초과)** 를 검증한 뒤에만 비식별 완료로 전이하고 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM`·`POLL_STTS_CD`·`RSPNS_DT` 를 갱신한다. 중지·삭제중·오류는 즉시 실패로 종결하고, 미시작·진행중은 다음 조회까지 대기한다. 시도 상한 240회 또는 경과 180분을 넘기면 실패로 마킹하며, 자동 재위탁 없이 외부 솔루션에서 수동 비식별화한 뒤 해소 처리하는 것이 확정 운영 정책이다.

<!-- hwpx:ignore-start -->
### 시계열 메타 생성 위탁
<!-- hwpx:ignore-end -->

#### KLID-AT-II-003 — 시계열 메타 생성 위탁

> **인터페이스 설명**: 마킹이 완료된 **비식별 영상**의 구간별 상황 서술(시계열 메타) 생성을 외부 분석 서비스에 위탁한다. 영상 본문은 전송하지 않고 공유 저장소 경로만 전달한다. 위탁은 처리 흐름을 붙잡지 않는 비동기 제출이며 수락 여부만 동기 확인하고 결과 상세는 KLID-AT-II-004로 수신한다. 비식별 누락 신고가 열린 구간에서는 위탁 전송 자체를 보류하고, 신고가 해소되면 보류 기록을 근거로 재위탁한다.

<table>
<tr><td rowspan="2">인터페이스 번호</td><td colspan="5" align="center"><b>데이터송신시스템</b></td><td rowspan="2">송신 프로그램 ID</td><td colspan="5" align="center"><b>데이터수신시스템</b></td><td rowspan="2">수신 프로그램 ID</td><td rowspan="2">속성 설명</td></tr>
<tr><td>시스템명</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>시스템명</td></tr>
<tr><td rowspan="7">KLID-AT-II-003</td><td rowspan="7">학습데이터 저작도구</td><td rowspan="7">LS_DATA_META (시계열 메타) · LS_DEIDENT_PROC_LOG (비식별 처리 로그)</td><td>IDMP_KEY (멱등 키)</td><td>VARCHAR</td><td>128</td><td rowspan="7">시계열메타위탁</td><td rowspan="7">시계열 메타 생성 요청</td><td>request_id (요청 식별자)</td><td>VARCHAR</td><td>128</td><td rowspan="7">외부 시계열 메타 분석 서비스</td><td rowspan="7">시계열메타생성</td><td>필수. 위탁 상관관계 키. 결과 수신 시 이 값으로 대상 영상을 역매핑하며 발급 이력이 없는 값은 거부한다</td></tr>
<tr><td>media.type (매체 종류)</td><td>VARCHAR</td><td>-</td><td>media.type (매체 종류)</td><td>VARCHAR</td><td>-</td><td>필수. 시계열 생성은 영상 매체로 고정한다</td></tr>
<tr><td>media.source_type (매체 지정 방식)</td><td>VARCHAR</td><td>-</td><td>media.source_type (매체 지정 방식)</td><td>VARCHAR</td><td>-</td><td>필수. 공유 저장소 경로 참조 방식으로 고정한다(본문 전송 없음)</td></tr>
<tr><td>DE_IDNTF_FILE_PATH_NM (비식별 파일 경로명)</td><td>VARCHAR</td><td>1000</td><td>media.path (매체 경로)</td><td>VARCHAR</td><td>1000</td><td>필수. **비식별 영상** 경로. 원본 경로는 전송하지 않는다</td></tr>
<tr><td>media.frame_policy.mode (프레임 선택 방식)</td><td>VARCHAR</td><td>-</td><td>media.frame_policy.mode (프레임 선택 방식)</td><td>VARCHAR</td><td>-</td><td>조건부(영상 매체 시 필수). 일정 간격 선택 또는 지정 프레임 선택</td></tr>
<tr><td>media.frame_policy.framerate (기준 초당 프레임)</td><td>INT</td><td>-</td><td>media.frame_policy.framerate (기준 초당 프레임)</td><td>INT</td><td>-</td><td>조건부. 1초 간격 프레임 계산 기준값</td></tr>
<tr><td>callback_url (결과 회신 주소)</td><td>VARCHAR</td><td>-</td><td>callback_url (결과 회신 주소)</td><td>VARCHAR</td><td>-</td><td>필수. 결과(KLID-AT-II-004)를 회신받을 주소</td></tr>
</table>

> **동기 응답**: 외부 시계열 메타 분석 서비스 → 저작도구. 요청 식별자(`request_id`, VARCHAR)와 수락 상태(`status`, VARCHAR)를 회신하며 **수락 값만 정상으로 인정**한다. 결과 상세는 회신 주소로 별도 전달된다. 응답 대기 상한은 10초이고 재시도는 최대 3회(1초 대기, 지수 증가)이며 요청 오류(4xx)는 재시도·장애 집계에서 제외한다. 수락 응답조차 받지 못한 건과 결과 회신이 오지 않은 건은 서로 다른 경과 기준(각각 30분·360분)으로 주기 점검이 회수한다. 지정 프레임 선택 방식일 때는 선택 프레임 목록(`selected_frames`, JSONB, 최대 8건)을 함께 전달한다.

<!-- hwpx:ignore-start -->
### 시계열 메타 결과 수신
<!-- hwpx:ignore-end -->

#### KLID-AT-II-004 — 시계열 메타 결과 수신

> **인터페이스 설명**: 외부 분석 서비스가 생성한 구간별 서술을 수신해 시계열 메타로 적재하고 검수 대기열에 진입시킨다. 검수자가 화면에서 검토·수정·승인하며 승인된 메타만 학습데이터에 포함된다. 외부 규격이 무서명이라 허용 호출 출처 목록·요청량 및 본문 크기 상한·요청 식별자 발급 대조 3계층으로 진위를 검증한다.

<table>
<tr><td rowspan="2">인터페이스 번호</td><td colspan="5" align="center"><b>데이터송신시스템</b></td><td rowspan="2">송신 프로그램 ID</td><td colspan="5" align="center"><b>데이터수신시스템</b></td><td rowspan="2">수신 프로그램 ID</td><td rowspan="2">속성 설명</td></tr>
<tr><td>시스템명</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>시스템명</td></tr>
<tr><td rowspan="7">KLID-AT-II-004</td><td rowspan="7">외부 시계열 메타 분석 서비스</td><td rowspan="7">시계열 메타 생성 결과</td><td>request_id (요청 식별자)</td><td>VARCHAR</td><td>128</td><td rowspan="7">시계열메타생성</td><td rowspan="7">LS_DATA_META (시계열 메타) · LS_DATA_META_REVIEW (메타 검수 대기열)</td><td>IDMP_KEY (멱등 키)</td><td>VARCHAR</td><td>128</td><td rowspan="7">학습데이터 저작도구</td><td rowspan="7">시계열메타결과수신(API-065)</td><td>필수. 위탁 시 발급한 상관관계 키. 이 값으로 대상 영상(`RAW_SN`)을 역매핑하며 발급 이력이 없으면 거부한다. 동일 값 재수신은 멱등 흡수한다</td></tr>
<tr><td>status (처리 상태)</td><td>VARCHAR</td><td>20</td><td>status (처리 상태)</td><td>VARCHAR</td><td>20</td><td>필수. 완료 또는 실패. 완료면 구간 서술을 적재하고 실패면 적재 없이 실패 상태로 종결한다</td></tr>
<tr><td>results[].start_sec (구간 시작 초)</td><td>INT</td><td>-</td><td>META_KEY (메타 키)</td><td>VARCHAR</td><td>64</td><td>조건부(완료 시 필수). 구간 시작 초. 종료 초와 조합해 시계열 정렬 키를 구성하며 (영상 식별자, 메타 키)가 유일하다</td></tr>
<tr><td>results[].end_sec (구간 종료 초)</td><td>INT</td><td>-</td><td>META_KEY (메타 키)</td><td>VARCHAR</td><td>64</td><td>조건부(완료 시 필수). 구간 종료 초. 시작 초와 조합해 정렬 키를 구성한다</td></tr>
<tr><td>results[].description (구간 서술)</td><td>VARCHAR</td><td>2000</td><td>META_VL (메타 값)</td><td>VARCHAR</td><td>2000</td><td>조건부(완료 시 필수). 해당 구간의 자연어 상황 서술. 구간 1건당 1행으로 적재한다</td></tr>
<tr><td>error.code (오류 코드)</td><td>VARCHAR</td><td>50</td><td>error.code (오류 코드)</td><td>VARCHAR</td><td>50</td><td>조건부(실패 시 필수). 실패 사유 코드</td></tr>
<tr><td>error.message (오류 메시지)</td><td>VARCHAR</td><td>1000</td><td>error.message (오류 메시지)</td><td>VARCHAR</td><td>1000</td><td>조건부(실패 시 필수). 실패 사유 메시지</td></tr>
</table>

> **동기 응답**: 저작도구 → 외부 시계열 메타 분석 서비스. 표준 응답 규약으로 반영 여부(`applied`, BOOLEAN)와 요청 식별자를 회신한다. 적재된 메타는 검수 대기열(`LS_DATA_META_REVIEW`)에 검토 대기 상태(`RVW_STTS_CD`, VARCHAR 20)·메타 유형(`META_TYPE_CD`, VARCHAR 20)·출처 구분(`SRC_SYS_CD`, VARCHAR 20)과 함께 등록된다. 동시 수신은 직렬화하며 처리 중 오류로 되돌려지면 처리 완료 표시도 함께 되돌려 상대 시스템의 재전송을 유도한다.

<!-- hwpx:ignore-start -->
### 증강 생성 위탁
<!-- hwpx:ignore-end -->

#### KLID-AT-II-005 — 증강 생성 위탁

> **인터페이스 설명**: 검수 완료 영상의 **비식별 프레임**과 생성 조건을 외부 생성형 AI에 위탁해 날씨·계절·시간 증강(동절기·야간·강우 3종)을 요청한다. 입력 프레임이 100장을 넘으면 여러 요청으로 분할해 위탁한다. 결과는 KLID-AT-II-006으로 수신한다. 파생영상은 다시 증강 요청 대상이 될 수 없다(파생 깊이 1 고정). 해상도 변경은 저작도구 내부에서 수행하므로 본 위탁 대상이 아니다.

<table>
<tr><td rowspan="2">인터페이스 번호</td><td colspan="5" align="center"><b>데이터송신시스템</b></td><td rowspan="2">송신 프로그램 ID</td><td colspan="5" align="center"><b>데이터수신시스템</b></td><td rowspan="2">수신 프로그램 ID</td><td rowspan="2">속성 설명</td></tr>
<tr><td>시스템명</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>시스템명</td></tr>
<tr><td rowspan="9">KLID-AT-II-005</td><td rowspan="9">학습데이터 저작도구</td><td rowspan="9">LS_DATA_AUG (데이터 증강) · LS_DATA_SRC (프레임) · LS_DATA_RAW (원시 영상)</td><td>IDMP_KEY (멱등 키)</td><td>VARCHAR</td><td>128</td><td rowspan="9">증강위탁</td><td rowspan="9">증강 생성 요청</td><td>request_id (요청 식별자)</td><td>VARCHAR</td><td>128</td><td rowspan="9">외부 생성형 AI(증강) 시스템</td><td rowspan="9">증강생성</td><td>필수. 요청 단위 멱등키. 멱등키 헤더(`Idempotency-Key`)로도 동일 값을 부착하며 결과 수신 시 대조 키가 된다</td></tr>
<tr><td>request_channel (요청 채널)</td><td>VARCHAR</td><td>20</td><td>request_channel (요청 채널)</td><td>VARCHAR</td><td>20</td><td>필수. 저작도구 채널 고정값</td></tr>
<tr><td>REG_USER_NO (등록 사용자 번호)</td><td>VARCHAR</td><td>50</td><td>request_user_id (요청 사용자 식별자)</td><td>VARCHAR</td><td>50</td><td>필수. 증강을 요청한 검수자 식별자</td></tr>
<tr><td>EVNT_TYPE_CD (이벤트 유형 코드)</td><td>VARCHAR</td><td>20</td><td>evnt_type (이벤트 유형)</td><td>VARCHAR</td><td>20</td><td>필수. 대상 영상의 이벤트 유형 코드</td></tr>
<tr><td>operation_type (작업 종류)</td><td>VARCHAR</td><td>20</td><td>operation_type (작업 종류)</td><td>VARCHAR</td><td>20</td><td>필수. 증강 작업 고정값</td></tr>
<tr><td>generation_mode (생성 방식)</td><td>VARCHAR</td><td>20</td><td>generation_mode (생성 방식)</td><td>VARCHAR</td><td>20</td><td>필수. 이미지에서 이미지를 생성하는 방식 고정값(영상 자체를 재생성하지 않는다)</td></tr>
<tr><td>DE_IDNTF_SRC_FILE_PATH_NM (비식별 프레임 파일 경로명)</td><td>VARCHAR</td><td>1000</td><td>input_files[].file_path (입력 파일 경로)</td><td>VARCHAR</td><td>1000</td><td>필수. 증강 대상 **비식별 프레임** 경로. 요청 1건당 최대 100장이며 초과분은 요청을 분할한다</td></tr>
<tr><td>FRM_NO (프레임 번호)</td><td>BIGINT</td><td>-</td><td>input_files[].sequence (입력 순번)</td><td>BIGINT</td><td>-</td><td>필수. 입력 프레임의 순서. 결과 프레임과 원본 프레임의 대응 근거가 된다</td></tr>
<tr><td>PROMPT_CN (생성 조건 내용)</td><td>VARCHAR</td><td>4000</td><td>prompt (생성 조건)</td><td>VARCHAR</td><td>4000</td><td>필수. 시간·계절·날씨·지형·심각도 5개 항목의 생성 조건. 구조화된 형태로 전송하며 가공 없이 원문을 보관해 결과물 간 구분 근거로 제공한다</td></tr>
</table>

> **동기 응답**: 외부 생성형 AI(증강) 시스템 → 저작도구. 접수 응답으로 요청 식별자(`request_id`, VARCHAR 128), **상대 시스템이 발급한 작업 식별자**(`job_id`, VARCHAR 200), 접수 상태(`status`, VARCHAR 20)를 회신한다. 요청 식별자 일치·접수 상태·작업 식별자 존재를 모두 검증하며 하나라도 어긋나면 외부 연동 오류로 처리한다. 작업 식별자는 `LS_DATA_AUG.OTSD_JOB_ID`(VARCHAR 200)에 보관해 결과 수신(KLID-AT-II-006)의 상관관계 키로 사용한다. 결과 회신 주소(`callback_url`, VARCHAR)를 함께 전달한다. 재시도는 최대 3회(1초 대기, 지수 증가)이며 요청 오류(4xx)는 이미 상대에 도달했을 수 있어 재시도 대상에서 제외한다. 비식별 누락 신고가 열린 구간에서는 위탁을 거부하며 보류하지 않는다(신고 해소 후 재요청이 정상 동선이다).

<!-- hwpx:ignore-start -->
### 증강 결과 수신
<!-- hwpx:ignore-end -->

#### KLID-AT-II-006 — 증강 결과 수신

> **인터페이스 설명**: 외부 생성형 AI가 증강 진행·종결 상태를 전달한다. 성공 종결 시 원본을 참조하는 **새 영상**으로 등록하고 원본 라벨·메타를 좌표·속성 그대로 복사한 뒤 미검수 상태로 검수 흐름에 진입시킨다. 실패 종결은 반려 상태로 확정한다. 상태 전이마다 전달되며 전송 실패 시 상대가 재전송하므로 동일 내용의 중복 도착이 정상이고 수신부는 멱등해야 한다.

<table>
<tr><td rowspan="2">인터페이스 번호</td><td colspan="5" align="center"><b>데이터송신시스템</b></td><td rowspan="2">송신 프로그램 ID</td><td colspan="5" align="center"><b>데이터수신시스템</b></td><td rowspan="2">수신 프로그램 ID</td><td rowspan="2">속성 설명</td></tr>
<tr><td>시스템명</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>시스템명</td></tr>
<tr><td rowspan="10">KLID-AT-II-006</td><td rowspan="10">외부 생성형 AI(증강) 시스템</td><td rowspan="10">증강 생성 결과</td><td>request_id (요청 식별자)</td><td>VARCHAR</td><td>128</td><td rowspan="10">증강생성</td><td rowspan="10">LS_DATA_AUG (데이터 증강) · LS_DATA_RAW (원시 영상) · LS_DATA_SRC (프레임)</td><td>IDMP_KEY (멱등 키)</td><td>VARCHAR</td><td>128</td><td rowspan="10">학습데이터 저작도구</td><td rowspan="10">증강결과수신(API-165)</td><td>필수. 위탁 시 발급한 멱등키의 되돌림 값. 저작도구가 발급한 이력이 있는 값만 처리한다</td></tr>
<tr><td>job_id (외부 작업 식별자)</td><td>VARCHAR</td><td>200</td><td>OTSD_JOB_ID (외부 작업 아이디)</td><td>VARCHAR</td><td>200</td><td>필수. 접수 응답 시 상대 시스템이 발급한 작업 식별자</td></tr>
<tr><td>status (처리 상태)</td><td>VARCHAR</td><td>20</td><td>AUG_PROC_STTS_CD (증강 처리 상태 코드)</td><td>VARCHAR</td><td>20</td><td>필수. 진행·성공·실패. 성공은 생성 완료, 실패는 생성 실패를 뜻하며 **사람의 활용 여부 판단과는 별개 항목**이다</td></tr>
<tr><td>progress (진행률)</td><td>INT</td><td>-</td><td>progress (진행률)</td><td>INT</td><td>-</td><td>필수. 0~100 진행률. 진행 표시용이며 별도 컬럼에 보관하지 않는다</td></tr>
<tr><td>current_step (현재 단계)</td><td>VARCHAR</td><td>20</td><td>current_step (현재 단계)</td><td>VARCHAR</td><td>20</td><td>선택. 현재 처리 단계 표시값</td></tr>
<tr><td>updated_at (갱신 일시)</td><td>TIMESTAMP</td><td>-</td><td>updated_at (갱신 일시)</td><td>TIMESTAMP</td><td>-</td><td>선택. 상태 전이 시각. 처리 이력으로만 사용한다</td></tr>
<tr><td>results[].output_file_path (산출 파일 경로)</td><td>VARCHAR</td><td>500</td><td>SRC_FILE_PATH_NM (원천 파일 경로명)</td><td>VARCHAR</td><td>500</td><td>조건부(성공 시 필수). 생성된 프레임 이미지 경로. 결과 1건당 1프레임이며 최대 100건</td></tr>
<tr><td>results[].generated_data_id (생성 데이터 식별자)</td><td>VARCHAR</td><td>200</td><td>results[].generated_data_id (생성 데이터 식별자)</td><td>VARCHAR</td><td>200</td><td>조건부(성공 시 필수). 상대 시스템의 생성 결과 식별자</td></tr>
<tr><td>results[].media_type (매체 종류)</td><td>VARCHAR</td><td>20</td><td>results[].media_type (매체 종류)</td><td>VARCHAR</td><td>20</td><td>조건부(성공 시 필수). 이미지 또는 영상 구분</td></tr>
<tr><td>error_code (오류 코드)</td><td>VARCHAR</td><td>50</td><td>REJECT_RSN (반려 사유)</td><td>VARCHAR</td><td>500</td><td>조건부(실패 시 필수). 실패 사유 코드. 오류 메시지(`error_message`, VARCHAR 1000)와 함께 반려 사유로 보관한다</td></tr>
</table>

> **동기 응답**: 저작도구 → 외부 생성형 AI(증강) 시스템. 실제 반영 여부(`applied`, BOOLEAN)를 회신한다 — 멱등 흡수(중복)·집계 보류는 미반영으로 회신해 상대가 정상 인계로 오해하지 않게 한다. 성공 종결 시 새 영상(`LS_DATA_RAW.RAW_SN`, BIGINT)을 등록하고 원본 참조(`ORGNL_RAW_SN`, BIGINT)를 설정하며, 영상 파일은 부모의 **비식별 영상을 복사**하고 프레임 이미지만 증강 결과로 대체한다(파생영상에는 원본 영상이 없고 비식별본만 존재한다). 부모 영상의 촬영환경·개인정보 판정 메타는 생성 시점에 1회 계승한다. 부모를 물리적으로 사용할 수 없는 경우(비식별 미완료·부모 또는 프레임 부재)는 보류하지 않고 즉시 실패로 확정한다.

<!-- hwpx:ignore-start -->
### 검수 완료 통지
<!-- hwpx:ignore-end -->

#### KLID-AT-II-007 — 검수 완료 통지

> **인터페이스 설명**: 영상 단위 검수 승인 시 작업 완료를 관제지원시스템으로 단방향 통지한다. **메타 요약만 전달**하며 라벨·메타 본문, 개인정보, 인증정보는 싣지 않는다. 상대 시스템은 통지 수신 후 조회 뷰(KLID-AT-II-009)와 산출 폴더에서 상세를 가져간다. 학습데이터 산출이 성공한 뒤에 발송하며, 산출이 실패하면 통지를 보류했다가 재산출 성공 시점에 재개한다.

<table>
<tr><td rowspan="2">인터페이스 번호</td><td colspan="5" align="center"><b>데이터송신시스템</b></td><td rowspan="2">송신 프로그램 ID</td><td colspan="5" align="center"><b>데이터수신시스템</b></td><td rowspan="2">수신 프로그램 ID</td><td rowspan="2">속성 설명</td></tr>
<tr><td>시스템명</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>시스템명</td></tr>
<tr><td rowspan="9">KLID-AT-II-007</td><td rowspan="9">학습데이터 저작도구</td><td rowspan="9">LS_DATA_RAW (원시 영상) · LS_DATA_INGEST (인입 원장) · LS_RAW_DATA_STATUS (영상 작업 상태)</td><td>RAW_SN (원시 일련번호)</td><td>BIGINT</td><td>-</td><td rowspan="9">완료통지발신</td><td rowspan="9">데이터셋 생성 요청</td><td>job_id (작업 식별자)</td><td>BIGINT</td><td>-</td><td rowspan="9">관제지원시스템</td><td rowspan="9">완료통지수신</td><td>필수. 작업 식별자 = 영상 식별자. 문자열로 직렬화해 전송한다(최대 100자)</td></tr>
<tr><td>EVNT_TYPE_CD (이벤트 유형 코드)</td><td>VARCHAR</td><td>20</td><td>event_type_cd (이벤트 유형 코드)</td><td>VARCHAR</td><td>20</td><td>필수. 인입 시 수신한 이벤트 유형 코드</td></tr>
<tr><td>EVNT_CLSF_CD (이벤트 분류 코드)</td><td>CHAR</td><td>2</td><td>evnt_cls_cd (이벤트 분류 코드)</td><td>CHAR</td><td>2</td><td>필수. 인입 원장에서 조달한다(원시 영상 테이블에는 없는 값). 값이 없어도 키는 남긴다</td></tr>
<tr><td>EVNT_CTGRY_CD (이벤트 카테고리 코드)</td><td>CHAR</td><td>4</td><td>evnt_ctgry_cd (이벤트 카테고리 코드)</td><td>CHAR</td><td>4</td><td>필수. 인입 원장에서 조달한다. 값이 없어도 키는 남긴다</td></tr>
<tr><td>LCLGV_CD (지자체 코드)</td><td>VARCHAR</td><td>20</td><td>lclgv_cd (지자체 코드)</td><td>VARCHAR</td><td>20</td><td>필수. 인입 시 수신한 지자체 코드</td></tr>
<tr><td>LCLGV_NM (지자체명)</td><td>VARCHAR</td><td>100</td><td>lclgv_nm (지자체명)</td><td>VARCHAR</td><td>100</td><td>선택. 값이 없으면 키를 생략한다</td></tr>
<tr><td>VDO_LEN_SEC (영상 길이 초)</td><td>INT</td><td>-</td><td>duration_sec (영상 길이 초)</td><td>INT</td><td>-</td><td>필수. 영상 재생 길이(초)</td></tr>
<tr><td>image_count (프레임 수 = 추출·라벨링 프레임 집계)</td><td>INT</td><td>-</td><td>image_count (프레임 수)</td><td>INT</td><td>-</td><td>필수. **추출·라벨링된 프레임 수**로 영상 전체 프레임 수와 다르다. 집계값이며 대응 컬럼이 없다</td></tr>
<tr><td>gen_ai_yn (생성형 AI 여부 = SRC_TYPE 도출)</td><td>CHAR</td><td>1</td><td>gen_ai_yn (생성형 AI 여부)</td><td>CHAR</td><td>1</td><td>필수. 자기 영상의 출처 구분에서 도출한다(외부 생성 인입 또는 저작도구 파생이면 해당). 인입 원장에서 가져오지 않는다</td></tr>
</table>

> **동기 응답**: 관제지원시스템 → 저작도구. 접수 응답으로 데이터셋 버전 식별자와 처리 상태를 회신한다. 요구 필드(선택 1건 제외 8건)는 값이 없더라도 **키를 남겨** 전송한다 — 키를 누락하면 상대가 검증 실패로 거부한다. 전송 항목명은 상대 시스템 규격명이며 저작도구 컬럼명과 일치하지 않는다. 응답 대기 상한은 10초이고 실패분은 영속 재전송 대기열(멱등키 유일, 대기열 깊이 상한 10,000건)에 적재해 5분 간격·배치 20건으로 재전송하며, 지수 백오프(상한 60분)로 5회를 초과하면 사후처리 대기열로 이관한다. 성공 건도 감사 목적으로 기록한다. 인증 헤더(`x-access-token`)의 발급 주체는 상대 시스템 회신 대기 중이며, 인증 토큰은 상위 시스템이 발급하고 저작도구는 발급하지 않는다.

<!-- hwpx:ignore-start -->
### 검수 후 수정 통지
<!-- hwpx:ignore-end -->

#### KLID-AT-II-008 — 검수 후 수정 통지

> **인터페이스 설명**: 검수 완료 후 라벨·메타가 수정되어 학습데이터 산출물을 새 버전으로 재생성했을 때 변경 요약을 통지한다. 라벨·메타 본문은 포함하지 않으며, 동일 작업 식별자를 유지하고 새 작업 식별자를 발급하지 않는다(수신측은 마지막 상태로 갱신한다). 같은 영상의 다중 변경이 짧은 시간에 발생하면 1회로 병합해 통지한다.

<table>
<tr><td rowspan="2">인터페이스 번호</td><td colspan="5" align="center"><b>데이터송신시스템</b></td><td rowspan="2">송신 프로그램 ID</td><td colspan="5" align="center"><b>데이터수신시스템</b></td><td rowspan="2">수신 프로그램 ID</td><td rowspan="2">속성 설명</td></tr>
<tr><td>시스템명</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>시스템명</td></tr>
<tr><td rowspan="4">KLID-AT-II-008</td><td rowspan="4">학습데이터 저작도구</td><td rowspan="4">LS_DATA_RAW (원시 영상) · LS_DATA_LBL_HSTRY (라벨 변경 이력) · LS_DATA_SRC (프레임)</td><td>RAW_SN (원시 일련번호)</td><td>BIGINT</td><td>-</td><td rowspan="4">수정통지발신</td><td rowspan="4">데이터셋 버전 갱신 요청</td><td>job_id (작업 식별자)</td><td>BIGINT</td><td>-</td><td rowspan="4">관제지원시스템</td><td rowspan="4">수정통지수신</td><td>필수. 작업 식별자 = 영상 식별자. 완료 통지와 동일한 값을 유지한다</td></tr>
<tr><td>changed_items.images (변경 이미지 파일명 목록)</td><td>JSONB</td><td>-</td><td>changed_items.images (변경 이미지 파일명 목록)</td><td>JSONB</td><td>-</td><td>필수. 변경된 프레임 이미지 파일명 배열(프레임 번호 4자리 + 이미지 확장자). 산출물 파일명 규칙과 동일 규칙을 공유한다</td></tr>
<tr><td>changed_items.jsons (변경 라벨 파일명 목록)</td><td>JSONB</td><td>-</td><td>changed_items.jsons (변경 라벨 파일명 목록)</td><td>JSONB</td><td>-</td><td>필수. 변경된 라벨 파일명 배열(프레임 번호 4자리 + 라벨 확장자). 재생성이 없는 수정은 빈 배열로 보낸다</td></tr>
<tr><td>ver_expln (버전 설명)</td><td>VARCHAR</td><td>-</td><td>ver_expln (버전 설명)</td><td>VARCHAR</td><td>-</td><td>선택. 이번 버전의 변경 요약 문구. 값이 없으면 키를 생략한다(상대 컬럼이 필수라 명시적 빈 값보다 미전송이 안전하다)</td></tr>
</table>

> **동기 응답**: 관제지원시스템 → 저작도구. 접수 응답으로 데이터셋 버전 식별자·버전·처리 상태를 회신한다. 재전송·사후처리 정책은 KLID-AT-II-007과 동일하다. 구축 메타 갱신 항목(`data_info`)은 키 구성이 미확정이라 본 명세에서 제외하며 확정 시 보완한다. 통지는 산출물 재생성이 성공한 뒤 발송하고 재생성 트리거는 통지 발송 설정과 무관하게 항상 동작한다.

<!-- hwpx:ignore-start -->
### 데이터마트 적재용 조회 뷰 제공
<!-- hwpx:ignore-end -->

#### KLID-AT-II-009 — 데이터마트 적재용 조회 뷰 제공

> **인터페이스 설명**: 검수 승인 영상만 노출하는 조회 전용 뷰를 데이터베이스 계정으로 제공하고, 관제지원시스템이 통지 수신 후 직접 조회해 데이터마트로 가져간다. 영상 1건 = 1행이 보장되며, 라벨 본문은 뷰에 두지 않고 학습데이터 산출 폴더의 파일이 유일한 출처다. 아래 표는 영상 단위 뷰(`V_COMPLETED_VIDEO`, 30개 항목)의 명세이며, 프레임 페어·라벨 변경점·시계열 메타 뷰는 표 하단에 기술한다.

<table>
<tr><td rowspan="2">인터페이스 번호</td><td colspan="5" align="center"><b>데이터송신시스템</b></td><td rowspan="2">송신 프로그램 ID</td><td colspan="5" align="center"><b>데이터수신시스템</b></td><td rowspan="2">수신 프로그램 ID</td><td rowspan="2">속성 설명</td></tr>
<tr><td>시스템명</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>시스템명</td></tr>
<tr><td rowspan="30">KLID-AT-II-009</td><td rowspan="30">학습데이터 저작도구</td><td rowspan="30">V_COMPLETED_VIDEO (검수 완료 영상 조회 뷰)</td><td>RAW_SN (원시 일련번호)</td><td>BIGINT</td><td>-</td><td rowspan="30">학습데이터조회뷰</td><td rowspan="30">데이터셋 · 데이터셋 버전</td><td>job_id (작업 식별자)</td><td>BIGINT</td><td>-</td><td rowspan="30">관제지원시스템</td><td rowspan="30">데이터마트적재</td><td>값 있음 보장. 데이터셋 적재 키</td></tr>
<tr><td>ORGNL_RAW_SN (원본 원시 일련번호)</td><td>BIGINT</td><td>-</td><td>ORGNL_RAW_SN (원본 원시 일련번호)</td><td>BIGINT</td><td>-</td><td>적재 대상 아님(대조용). 파생영상의 부모 영상 식별자이며 원본이면 값이 없다</td></tr>
<tr><td>EVNT_TYPE_CD (이벤트 유형 코드)</td><td>VARCHAR</td><td>20</td><td>evnt_type_cd (이벤트 유형 코드)</td><td>VARCHAR</td><td>20</td><td>인입 시 값을 받지 못했으면 비어 있다(저작도구가 임의로 채우지 않는다)</td></tr>
<tr><td>EVNT_CLSF_CD (이벤트 분류 코드)</td><td>CHAR</td><td>2</td><td>evnt_clsf_cd (이벤트 분류 코드)</td><td>CHAR</td><td>2</td><td>인입 원장에서 조인해 제공한다. 파생영상은 대응 인입 행이 없어 비어 있다</td></tr>
<tr><td>EVNT_CTGRY_CD (이벤트 카테고리 코드)</td><td>CHAR</td><td>4</td><td>evnt_ctgry_cd (이벤트 카테고리 코드)</td><td>CHAR</td><td>4</td><td>인입 원장에서 조인해 제공한다</td></tr>
<tr><td>LCLGV_CD (지자체 코드)</td><td>VARCHAR</td><td>20</td><td>lclgv_cd (지자체 코드)</td><td>VARCHAR</td><td>20</td><td>인입 시 수신한 지자체 코드</td></tr>
<tr><td>LCLGV_NM (지자체명)</td><td>VARCHAR</td><td>100</td><td>lclgv_nm (지자체명)</td><td>VARCHAR</td><td>100</td><td>인입 원장에서 조인해 제공한다</td></tr>
<tr><td>GEN_AI_YN (생성형 AI 여부)</td><td>CHAR</td><td>1</td><td>gen_ai_yn (생성형 AI 여부)</td><td>CHAR</td><td>1</td><td>값 있음 보장. 자기 영상의 출처 구분에서 도출하며 동결 스냅샷을 읽지 않는다</td></tr>
<tr><td>DATST_NM (데이터셋명)</td><td>VARCHAR</td><td>200</td><td>name (데이터셋명)</td><td>VARCHAR</td><td>200</td><td>이벤트명 기반 데이터셋 명칭</td></tr>
<tr><td>DATST_EXPLN (데이터셋 설명)</td><td>VARCHAR</td><td>4000</td><td>description (데이터셋 설명)</td><td>VARCHAR</td><td>4000</td><td>이벤트명 기반 데이터셋 설명</td></tr>
<tr><td>VDO_LEN_SEC (영상 길이 초)</td><td>INT</td><td>-</td><td>vdo_len (영상 길이)</td><td>INT</td><td>-</td><td>영상 재생 길이(초)</td></tr>
<tr><td>FRME_CNT (프레임 수)</td><td>INT</td><td>-</td><td>img_nocs (이미지 수)</td><td>INT</td><td>-</td><td>값 있음 보장. **추출·라벨링된 프레임 수**이며 미산출 영상은 0이다(경로·용량이 비는 것과 대칭이 아니다)</td></tr>
<tr><td>RVW_CMPTN_DT (검토 완료 일시)</td><td>TIMESTAMP</td><td>-</td><td>RVW_CMPTN_DT (검토 완료 일시)</td><td>TIMESTAMP</td><td>-</td><td>값 있음 보장. 검수 완료 일시이며 동기화 기준 시각으로 사용한다</td></tr>
<tr><td>IMG_YN (이미지 여부)</td><td>CHAR</td><td>1</td><td>img_yn (이미지 여부)</td><td>CHAR</td><td>1</td><td>값 있음 보장. 프레임 이미지 산출 여부</td></tr>
<tr><td>VDO_YN (영상 여부)</td><td>CHAR</td><td>1</td><td>vdo_yn (영상 여부)</td><td>CHAR</td><td>1</td><td>값 있음 보장. 영상 파일 존재 여부</td></tr>
<tr><td>ANONY_INCL_YN (익명정보 포함 여부)</td><td>CHAR</td><td>1</td><td>anony_incl_yn (익명정보 포함 여부)</td><td>CHAR</td><td>1</td><td>값 있음 보장. **비식별 영상 기준** 판정. 사람이 입력하지 않았으면 기본값이 채워져 나간다</td></tr>
<tr><td>PSDO_INCL_YN (가명정보 포함 여부)</td><td>CHAR</td><td>1</td><td>psdo_incl_yn (가명정보 포함 여부)</td><td>CHAR</td><td>1</td><td>값 있음 보장. 비식별 영상 기준 판정</td></tr>
<tr><td>PRVC_INCL_YN (개인정보 포함 여부)</td><td>CHAR</td><td>1</td><td>prvc_incl_yn (개인정보 포함 여부)</td><td>CHAR</td><td>1</td><td>값 있음 보장. 비식별 영상 기준 판정</td></tr>
<tr><td>SRC_ANONY_INCL_YN (출처 익명정보 포함 여부)</td><td>CHAR</td><td>1</td><td>SRC_ANONY_INCL_YN (출처 익명정보 포함 여부)</td><td>CHAR</td><td>1</td><td>참고·대조용. **원천 영상 기준** 판정이며 상대 시스템이 인입 시 보낸 값이다. 파생영상은 비어 있다</td></tr>
<tr><td>SRC_PSDO_INCL_YN (출처 가명정보 포함 여부)</td><td>CHAR</td><td>1</td><td>SRC_PSDO_INCL_YN (출처 가명정보 포함 여부)</td><td>CHAR</td><td>1</td><td>참고·대조용. 원천 영상 기준 판정</td></tr>
<tr><td>SRC_PRVC_INCL_YN (출처 개인정보 포함 여부)</td><td>CHAR</td><td>1</td><td>SRC_PRVC_INCL_YN (출처 개인정보 포함 여부)</td><td>CHAR</td><td>1</td><td>참고·대조용. 원천 영상 기준 판정</td></tr>
<tr><td>DATA_ETBL_YR (데이터 구축 연도)</td><td>VARCHAR</td><td>100</td><td>data_etbl_yr (데이터 구축 연도)</td><td>VARCHAR</td><td>100</td><td>값 있음 보장. 검수 완료 연도</td></tr>
<tr><td>DATA_ETBL_CPCT (데이터 구축 용량)</td><td>BIGINT</td><td>-</td><td>data_etbl_cpct (데이터 구축 용량)</td><td>BIGINT</td><td>-</td><td>산출 폴더 총 바이트. 미산출 시 비어 있다</td></tr>
<tr><td>LBL_TYPE (라벨 유형)</td><td>VARCHAR</td><td>256</td><td>lbl_type (라벨 유형)</td><td>VARCHAR</td><td>256</td><td>사용된 라벨 형태를 구분자로 이어 붙인 값</td></tr>
<tr><td>LBL_FMT (라벨 형식)</td><td>VARCHAR</td><td>256</td><td>lbl_fmt (라벨 형식)</td><td>VARCHAR</td><td>256</td><td>값 있음 보장. 라벨 파일 포맷</td></tr>
<tr><td>OUTPUT_PATH_NM (산출물 경로명)</td><td>VARCHAR</td><td>500</td><td>OUTPUT_PATH_NM (산출물 경로명)</td><td>VARCHAR</td><td>500</td><td>학습데이터 산출 폴더 경로(영상 루트). 하위에 버전 폴더가 누적되며 전 버전을 보존한다. 미산출 시 비어 있다</td></tr>
<tr><td>OUTPUT_STTS_CD (산출물 상태 코드)</td><td>VARCHAR</td><td>20</td><td>OUTPUT_STTS_CD (산출물 상태 코드)</td><td>VARCHAR</td><td>20</td><td>산출 종결 상태(전체 성공 또는 일부 프레임만 산출). 미산출 시 비어 있다</td></tr>
<tr><td>DE_IDNTF_FILE_PATH_NM (비식별 파일 경로명)</td><td>VARCHAR</td><td>1000</td><td>DE_IDNTF_FILE_PATH_NM (비식별 파일 경로명)</td><td>VARCHAR</td><td>1000</td><td>비식별 영상 파일 경로. 파일명이 고정이 아니므로 **이 값을 그대로 사용**해야 하며 조합·추측하면 안 된다</td></tr>
<tr><td>ORGNL_VDO_PATH_NM (원본 영상 경로명)</td><td>VARCHAR</td><td>500</td><td>ORGNL_VDO_PATH_NM (원본 영상 경로명)</td><td>VARCHAR</td><td>500</td><td>원본 영상 경로. **파생영상은 원본 영상이 없어 비어 있으며** 비식별 파일 경로를 대신 사용한다</td></tr>
<tr><td>DE_IDNTF_YN (비식별 여부)</td><td>CHAR</td><td>1</td><td>DE_IDNTF_YN (비식별 여부)</td><td>CHAR</td><td>1</td><td>값 있음 보장. 비식별 완료 또는 누락 신고 접수 상태</td></tr>
</table>

> **부가 조회 뷰**: 영상 단위 뷰 외에 ①프레임 페어 뷰(`V_COMPLETED_FRAME` — 원본 프레임 경로 `ORIGINAL_PATH` VARCHAR 500, 비식별 프레임 경로 `DEIDENTIFIED_PATH` VARCHAR 1000) ②라벨 변경점 뷰(`V_COMPLETED_LABEL_CHANGE` — 변경 종류 `CHG_KIND_CD` VARCHAR 20으로 추가·수정·삭제 구분) ③시계열 메타 뷰(`V_COMPLETED_META` — 승인된 메타만 노출)를 함께 제공한다. 세 뷰 모두 검수 승인 영상만 노출한다. 라벨 좌표·속성 본문은 뷰로 중복 노출하지 않으며 산출 폴더 파일이 유일한 출처다.
>
> **접근 통제·운영 규약**: 별도 응용 수준 인증 없이 데이터베이스 계정 접근 통제에 위임한다. 뷰는 재실행해도 안전한 형태로 정의해 멱등하다. 경로 항목은 문자열이며 조회 시점마다 파일 실재를 검증하지 않으므로 픽업 실패 시 재시도하거나 다음 통지를 기다린다. 검수 완료·통지된 영상은 비식별 누락 신고 등 어떤 사유로도 뷰에서 감추거나 경로를 비우지 않는다(상대 시스템이 보던 행이 예고 없이 사라지지 않게 하기 위한 확정 정책이다).

<!-- hwpx:ignore-start -->
### 포털 영상 메타 복제
<!-- hwpx:ignore-end -->

#### KLID-AT-II-010 — 포털 영상 메타 복제

> **인터페이스 설명**: 포털(외부 채널)에서 사용할 영상 메타 스냅샷을 포털 채널 데이터베이스로 **단방향 복제**한다. 저작도구는 내부 채널과 포털 채널의 데이터 원본을 분리 운영하며, 복제는 전송 대기 원장에 적재한 뒤 주기 점검(60초)으로 반영하는 방식이라 직접 쓰기를 하지 않는다. 포털 사용자가 저장한 라벨·작업 데이터는 원본·데이터마트에 반영되지 않는 단방향 정책이다.

<table>
<tr><td rowspan="2">인터페이스 번호</td><td colspan="5" align="center"><b>데이터송신시스템</b></td><td rowspan="2">송신 프로그램 ID</td><td colspan="5" align="center"><b>데이터수신시스템</b></td><td rowspan="2">수신 프로그램 ID</td><td rowspan="2">속성 설명</td></tr>
<tr><td>시스템명</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>시스템명</td></tr>
<tr><td rowspan="17">KLID-AT-II-010</td><td rowspan="17">학습데이터 저작도구</td><td rowspan="17">LS_DATASET_VIDEO_META (영상 메타 스냅샷)</td><td>RAW_SN (원시 일련번호)</td><td>BIGINT</td><td>-</td><td rowspan="17">메타복제송신</td><td rowspan="17">LS_DATASET_VIDEO_META (영상 메타 복제본)</td><td>RAW_SN (원시 일련번호)</td><td>BIGINT</td><td>-</td><td rowspan="17">포털</td><td rowspan="17">메타복제적재</td><td>필수. 대상 영상 식별자. 스냅샷 해시와 함께 유일 키를 구성한다</td></tr>
<tr><td>SNPSHT_HASH (스냅샷 해시)</td><td>VARCHAR</td><td>64</td><td>SNPSHT_HASH (스냅샷 해시)</td><td>VARCHAR</td><td>64</td><td>필수. 메타 내용 해시. (영상 식별자, 해시) 중복 시 반영을 건너뛰어 멱등을 보장한다</td></tr>
<tr><td>ACTIVE_YN (활성 여부)</td><td>CHAR</td><td>1</td><td>ACTIVE_YN (활성 여부)</td><td>CHAR</td><td>1</td><td>필수. 현재 유효한 스냅샷 여부</td></tr>
<tr><td>ORGNL_RAW_SN (원본 원시 일련번호)</td><td>BIGINT</td><td>-</td><td>ORGNL_RAW_SN (원본 원시 일련번호)</td><td>BIGINT</td><td>-</td><td>선택. 파생영상의 부모 영상 식별자</td></tr>
<tr><td>VMS_CLIP_ID (영상 클립 아이디)</td><td>VARCHAR</td><td>128</td><td>VMS_CLIP_ID (영상 클립 아이디)</td><td>VARCHAR</td><td>128</td><td>선택. 원천 영상 클립 식별자</td></tr>
<tr><td>RAW_FILE_PATH_NM (원시 파일 경로명)</td><td>VARCHAR</td><td>500</td><td>RAW_FILE_PATH_NM (원시 파일 경로명)</td><td>VARCHAR</td><td>500</td><td>선택. 영상 파일 경로. 파생영상은 값을 두지 않는다</td></tr>
<tr><td>SHT_DT (촬영 일시)</td><td>TIMESTAMP</td><td>-</td><td>SHT_DT (촬영 일시)</td><td>TIMESTAMP</td><td>-</td><td>선택. 영상 촬영 일시</td></tr>
<tr><td>VDO_LEN_SEC (영상 길이 초)</td><td>INT</td><td>-</td><td>VDO_LEN_SEC (영상 길이 초)</td><td>INT</td><td>-</td><td>선택. 영상 재생 길이(초)</td></tr>
<tr><td>LCLGV_CD (지자체 코드)</td><td>VARCHAR</td><td>32</td><td>LCLGV_CD (지자체 코드)</td><td>VARCHAR</td><td>32</td><td>선택. 지자체 코드</td></tr>
<tr><td>DE_IDENT_YN (비식별 여부)</td><td>CHAR</td><td>1</td><td>DE_IDENT_YN (비식별 여부)</td><td>CHAR</td><td>1</td><td>선택. 비식별 처리 상태</td></tr>
<tr><td>EVNT_TYPE_CD (이벤트 유형 코드)</td><td>VARCHAR</td><td>32</td><td>EVNT_TYPE_CD (이벤트 유형 코드)</td><td>VARCHAR</td><td>32</td><td>선택. 이벤트 유형 코드</td></tr>
<tr><td>EVNT_NM (이벤트명)</td><td>VARCHAR</td><td>255</td><td>EVNT_NM (이벤트명)</td><td>VARCHAR</td><td>255</td><td>선택. 이벤트 명칭</td></tr>
<tr><td>RESL (해상도)</td><td>VARCHAR</td><td>32</td><td>RESL (해상도)</td><td>VARCHAR</td><td>32</td><td>선택. 영상 파일 기준 해상도. 영상 폭·높이(`VDO_WDTH`·`VDO_HGT`, INT), 초당 프레임(`FPS`, NUMERIC), 파일 크기(`FILE_SZ`, BIGINT) 등 기술 메타가 함께 복제된다</td></tr>
<tr><td>DAY_NGT_CD (주야 코드)</td><td>VARCHAR</td><td>8</td><td>DAY_NGT_CD (주야 코드)</td><td>VARCHAR</td><td>8</td><td>선택. 촬영 시간대 구분</td></tr>
<tr><td>SESN_CD (계절 코드)</td><td>VARCHAR</td><td>8</td><td>SESN_CD (계절 코드)</td><td>VARCHAR</td><td>8</td><td>선택. 촬영 계절 구분</td></tr>
<tr><td>WTHR_NM (날씨명)</td><td>VARCHAR</td><td>32</td><td>WTHR_NM (날씨명)</td><td>VARCHAR</td><td>32</td><td>선택. 촬영 시 날씨</td></tr>
<tr><td>RVW_CMPL_DT (검수 완료 일시)</td><td>TIMESTAMP</td><td>-</td><td>RVW_CMPL_DT (검수 완료 일시)</td><td>TIMESTAMP</td><td>-</td><td>선택. 검수 완료 일시</td></tr>
</table>

> **처리 규약**: 반영은 (영상 식별자, 스냅샷 해시) 충돌 시 아무것도 하지 않는 방식이라 재실행해도 안전하다. 실패 시 재시도 횟수를 누적해 5회를 초과하면 사후처리 상태로 이관한다. 복제본 테이블이 아직 구성되지 않은 환경에서는 사전 점검으로 안전하게 건너뛰어 주기 작업 실패로 번지지 않게 한다. 접속 정보는 환경변수로 주입하며 소스에 평문으로 두지 않는다. 위 표에 없는 촬영 위치·장비 정보 등 잔여 메타 항목도 동일한 항목명·타입으로 함께 복제된다.

<!-- hwpx:ignore-start -->
### 객체 탐지 추론
<!-- hwpx:ignore-end -->

#### KLID-AT-II-011 — 객체 탐지 추론

> **인터페이스 설명**: 자동 라벨링 단계에서 프레임 이미지의 객체를 탐지한다. 저작도구 본체가 프레임 이미지를 부호화 문자열로 전달하고 탐지 결과를 동기 수신해 자동 라벨로 적재한다. AI 추론 서버는 인증·데이터베이스가 없는 무상태 추론 전용이므로 수신측 데이터 저장소가 없다. 탐지 대상 클래스는 라벨 마스터에 등록된 검출 유형 매핑과의 교집합만 사용하며 요청자가 보낸 값을 그대로 신뢰하지 않는다.

<table>
<tr><td rowspan="2">인터페이스 번호</td><td colspan="5" align="center"><b>데이터송신시스템</b></td><td rowspan="2">송신 프로그램 ID</td><td colspan="5" align="center"><b>데이터수신시스템</b></td><td rowspan="2">수신 프로그램 ID</td><td rowspan="2">속성 설명</td></tr>
<tr><td>시스템명</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>시스템명</td></tr>
<tr><td rowspan="4">KLID-AT-II-011</td><td rowspan="4">학습데이터 저작도구</td><td rowspan="4">LS_DATA_SRC (프레임)</td><td>image_b64 (프레임 이미지 부호화 문자열)</td><td>VARCHAR</td><td>-</td><td rowspan="4">객체탐지요청</td><td rowspan="4">추론 입력(무상태)</td><td>image_b64 (프레임 이미지 부호화 문자열)</td><td>VARCHAR</td><td>-</td><td rowspan="4">학습데이터 저작도구(AI 추론 서버)</td><td rowspan="4">객체탐지(API-113)</td><td>필수. 프레임 파일을 읽어 부호화한 이미지 문자열. 자동 라벨링은 **원본 프레임**을 대상으로 실행하고 결과 좌표를 비식별본과 공유한다</td></tr>
<tr><td>conf_threshold (신뢰도 임계값)</td><td>NUMERIC</td><td>-</td><td>conf_threshold (신뢰도 임계값)</td><td>NUMERIC</td><td>-</td><td>선택. 0~1 범위. 미지정 시 기본값 적용</td></tr>
<tr><td>iou (중첩 제거 임계값)</td><td>NUMERIC</td><td>-</td><td>iou (중첩 제거 임계값)</td><td>NUMERIC</td><td>-</td><td>선택. 0~1 범위. 중복 검출 제거 기준. 미지정 시 기본값 적용</td></tr>
<tr><td>imgsz (추론 입력 해상도)</td><td>INT</td><td>-</td><td>imgsz (추론 입력 해상도)</td><td>INT</td><td>-</td><td>선택. 320~1920 범위(픽셀). 미지정 시 기본값 적용</td></tr>
</table>

> **동기 응답**: AI 추론 서버 → 저작도구. 탐지 객체 목록(`detections`, JSONB)을 회신하며 각 항목은 클래스 라벨(`label`, VARCHAR), 경계 좌표(`points`, JSONB — 좌상단·우하단 4개 수치), 신뢰도(`score`, NUMERIC 0~1)로 구성된다. 저작도구는 결과를 `LS_DATA_LBL`(라벨)에 자동 라벨로 적재하고 라벨 유형(`LBL_TYPE_CD`, VARCHAR 16)·라벨 식별자(`LBL_ID`, BIGINT)·좌표(`POINT_CN`, 텍스트)를 채운다. 요청 검증에 실패하면 처리 불가 응답을 받는다. 호출에는 응답 대기 상한 60초와 재시도·장애 차단 정책을 적용한다.

<!-- hwpx:ignore-start -->
### 객체 추적 추론
<!-- hwpx:ignore-end -->

#### KLID-AT-II-012 — 객체 추적 추론

> **인터페이스 설명**: 프레임 간 동일 객체를 연결하는 추적 추론이다. 영상 식별자별로 추적기를 격리·보관하며 프레임 순번이 0이면 추적기를 초기화한다. 응답의 추적 식별자로 프레임 간 객체를 이어 붙여 트랙을 구성한다.

<table>
<tr><td rowspan="2">인터페이스 번호</td><td colspan="5" align="center"><b>데이터송신시스템</b></td><td rowspan="2">송신 프로그램 ID</td><td colspan="5" align="center"><b>데이터수신시스템</b></td><td rowspan="2">수신 프로그램 ID</td><td rowspan="2">속성 설명</td></tr>
<tr><td>시스템명</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>시스템명</td></tr>
<tr><td rowspan="6">KLID-AT-II-012</td><td rowspan="6">학습데이터 저작도구</td><td rowspan="6">LS_DATA_SRC (프레임) · LS_DATA_RAW (원시 영상)</td><td>image_b64 (프레임 이미지 부호화 문자열)</td><td>VARCHAR</td><td>-</td><td rowspan="6">객체추적요청</td><td rowspan="6">추론 입력(무상태)</td><td>image_b64 (프레임 이미지 부호화 문자열)</td><td>VARCHAR</td><td>-</td><td rowspan="6">학습데이터 저작도구(AI 추론 서버)</td><td rowspan="6">객체추적(API-119)</td><td>필수. 추적 대상 프레임 이미지 문자열</td></tr>
<tr><td>RAW_SN (원시 일련번호)</td><td>BIGINT</td><td>-</td><td>clip_id (영상 식별자)</td><td>BIGINT</td><td>-</td><td>필수. 추적기 인스턴스를 격리하는 키. 문자열로 직렬화해 전송한다</td></tr>
<tr><td>VDO_FRM_NO (영상 프레임 번호)</td><td>BIGINT</td><td>-</td><td>frame_index (프레임 순번)</td><td>BIGINT</td><td>-</td><td>필수. 영상 내 프레임 순서. 0이면 추적기를 초기화한다</td></tr>
<tr><td>conf_threshold (신뢰도 임계값)</td><td>NUMERIC</td><td>-</td><td>conf_threshold (신뢰도 임계값)</td><td>NUMERIC</td><td>-</td><td>선택. 0~1 범위. 미지정 시 기본값 적용</td></tr>
<tr><td>iou (중첩 제거 임계값)</td><td>NUMERIC</td><td>-</td><td>iou (중첩 제거 임계값)</td><td>NUMERIC</td><td>-</td><td>선택. 0~1 범위. 미지정 시 기본값 적용</td></tr>
<tr><td>imgsz (추론 입력 해상도)</td><td>INT</td><td>-</td><td>imgsz (추론 입력 해상도)</td><td>INT</td><td>-</td><td>선택. 320~1920 범위(픽셀). 미지정 시 기본값 적용</td></tr>
</table>

> **동기 응답**: AI 추론 서버 → 저작도구. 탐지 객체 목록(`detections`, JSONB)을 회신하며 각 항목에 추적 식별자(`track_id`, BIGINT — 저신뢰 검출은 값이 없을 수 있음)가 포함된다. 저작도구는 이 값을 `LS_DATA_LBL.TRCK_ID`(VARCHAR 30)에 반영해 프레임 간 트랙을 구성하고, 결과가 없는 중간 프레임은 추적 보간으로 채운다. 추론 호출이 실패하면 기존 라벨을 그대로 유지한다.

<!-- hwpx:ignore-start -->
### 영역 분할 추론
<!-- hwpx:ignore-end -->

#### KLID-AT-II-013 — 영역 분할 추론

> **인터페이스 설명**: 사용자가 프레임에서 클릭 또는 박스로 객체를 한 번 지정하면 외곽 경계(폴리곤)와 신뢰도를 산출해 경계에 밀착된 라벨로 적용한다. 프롬프트는 클릭·박스 중 하나만 지정하며, 산출 좌표는 이미지 실측 크기 범위 내로 검증한 뒤 적용한다. 본인에게 배정되지 않은 프레임의 요청은 거부한다.

<table>
<tr><td rowspan="2">인터페이스 번호</td><td colspan="5" align="center"><b>데이터송신시스템</b></td><td rowspan="2">송신 프로그램 ID</td><td colspan="5" align="center"><b>데이터수신시스템</b></td><td rowspan="2">수신 프로그램 ID</td><td rowspan="2">속성 설명</td></tr>
<tr><td>시스템명</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>시스템명</td></tr>
<tr><td rowspan="3">KLID-AT-II-013</td><td rowspan="3">학습데이터 저작도구</td><td rowspan="3">LS_DATA_SRC (프레임)</td><td>image_b64 (프레임 이미지 부호화 문자열)</td><td>VARCHAR</td><td>-</td><td rowspan="3">영역분할요청</td><td rowspan="3">추론 입력(무상태)</td><td>image_b64 (프레임 이미지 부호화 문자열)</td><td>VARCHAR</td><td>-</td><td rowspan="3">학습데이터 저작도구(AI 추론 서버)</td><td rowspan="3">영역분할(API-120)</td><td>필수. 분할 대상 프레임 이미지 문자열</td></tr>
<tr><td>points (클릭 좌표 목록)</td><td>JSONB</td><td>-</td><td>points (클릭 좌표 목록)</td><td>JSONB</td><td>-</td><td>조건부. 클릭 프롬프트 좌표 배열. 박스 프롬프트와 함께 지정하거나 단독 지정한다</td></tr>
<tr><td>box (박스 프롬프트 좌표)</td><td>JSONB</td><td>-</td><td>box (박스 프롬프트 좌표)</td><td>JSONB</td><td>-</td><td>조건부. 좌상단·우하단 4개 수치. 클릭·박스 모두 없으면 이미지 중앙점을 기본 프롬프트로 사용한다</td></tr>
</table>

> **동기 응답**: AI 추론 서버 → 저작도구. 외곽 경계 좌표(`polygon`, JSONB — 폐곡선을 이루는 좌표 배열)와 신뢰도(`score`, NUMERIC 0~1)를 회신한다. 저작도구는 좌표 범위를 검증한 뒤 `LS_DATA_LBL.POINT_CN`(텍스트)에 폴리곤 라벨로 적용하며, 경계 점 수는 시스템 설정의 정밀도 값으로 단순화한다(정밀도 설정 변경 권한은 검수자 전용이고 설정 조회 실패 시 기본값으로 안전 폴백한다).

<!-- hwpx:ignore-start -->
### 분할 전파 추론
<!-- hwpx:ignore-end -->

#### KLID-AT-II-014 — 분할 전파 추론

> **인터페이스 설명**: 이전 프레임에서 확정한 경계를 다음 프레임으로 전파하고 동일 추적 식별자를 유지한다. 시작 프레임의 객체 지정이 선행되어야 하며, 가림·장면 전환 구간에서는 사용자 보정을 전제로 한다. 추론이 실패하면 이전 경계를 그대로 반환해 기존 라벨을 보존한다.

<table>
<tr><td rowspan="2">인터페이스 번호</td><td colspan="5" align="center"><b>데이터송신시스템</b></td><td rowspan="2">송신 프로그램 ID</td><td colspan="5" align="center"><b>데이터수신시스템</b></td><td rowspan="2">수신 프로그램 ID</td><td rowspan="2">속성 설명</td></tr>
<tr><td>시스템명</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입</td><td>길이</td><td>시스템명</td></tr>
<tr><td rowspan="4">KLID-AT-II-014</td><td rowspan="4">학습데이터 저작도구</td><td rowspan="4">LS_DATA_LBL (라벨) · LS_DATA_SRC (프레임)</td><td>TRCK_ID (트랙 아이디)</td><td>VARCHAR</td><td>30</td><td rowspan="4">분할전파요청</td><td rowspan="4">추론 입력(무상태)</td><td>track_id (추적 식별자)</td><td>VARCHAR</td><td>30</td><td rowspan="4">학습데이터 저작도구(AI 추론 서버)</td><td rowspan="4">분할전파(API-121)</td><td>필수. 이전 프레임에서 부여된 추적 식별자. 전파 후에도 동일 값을 유지한다</td></tr>
<tr><td>POINT_CN (좌표 내용)</td><td>JSONB</td><td>-</td><td>prev_polygon (이전 프레임 경계)</td><td>JSONB</td><td>-</td><td>필수. 이전 프레임의 폴리곤 좌표 배열(최소 3점). 이 경계의 외접 사각형을 다음 프레임의 프롬프트로 사용한다</td></tr>
<tr><td>prev_image_b64 (이전 프레임 이미지 부호화 문자열)</td><td>VARCHAR</td><td>-</td><td>prev_image_b64 (이전 프레임 이미지 부호화 문자열)</td><td>VARCHAR</td><td>-</td><td>선택. 이전 프레임 이미지 문자열</td></tr>
<tr><td>next_image_b64 (다음 프레임 이미지 부호화 문자열)</td><td>VARCHAR</td><td>-</td><td>next_image_b64 (다음 프레임 이미지 부호화 문자열)</td><td>VARCHAR</td><td>-</td><td>선택. 전파 대상(다음) 프레임 이미지 문자열</td></tr>
</table>

> **동기 응답**: AI 추론 서버 → 저작도구. 유지된 추적 식별자(`track_id`, VARCHAR 30), 전파된 경계 좌표(`polygon`, JSONB), 신뢰도(`score`, NUMERIC 0~1)를 회신한다. 저작도구는 다음 프레임의 라벨로 적용하며 추적 결과의 출력 형태는 사용자가 선택한 객체의 라벨 형태를 따른다. 한 번의 요청 범위를 넘는 연속 전파는 프레임 단위로 반복 호출한다.

## 항목 설명

### 인터페이스 목록

- **송신 인터페이스번호**: 송신 시스템의 인터페이스 일련번호를 기입한다.
- **송신 일련번호**: 한 개 송신단위에서 여러 개의 서브시스템으로 동시에 전송되는 경우에는 순차적으로 기술한다.
- **송신 시스템명**: 송신 시스템이름을 기술한다.
- **송신 프로그램 ID**: 송신에 해당하는 프로그램 ID를 기입한다.
- **전달 처리형태**: 인터페이스를 처리하는 형태를 기술한다. Batch / Online 등
- **전달 인터페이스방식**: 통신 프로토콜 및 통신 기술 방식을 기술한다.
- **전달 발생빈도**: 인터페이스 발생빈도를 기술한다. "회수/주기"의 형식으로 기술한다.
- **수신 상대 담당자**: 수신 시스템의 업무담당자명을 기술한다.
- **수신 프로그램 ID**: 수신과 관련된 프로그램 ID를 기입한다.
- **수신시스템명**: 인터페이스 수신 시스템명을 기술한다.
- **수신 일련번호**: 수신 시스템의 동일 인터페이스가 여러 시스템에서 동시에 수신을 받는 경우에 순차적으로 번호를 부여한다.
- **수신번호**: 수신 시스템 측의 인터페이스 일련번호(상대 시스템 채번)를 기입한다. 미보유 시 `-`.
- **관련 요구사항 ID**: 해당 인터페이스와 관련된 분석단계의 "사용자 요구사항 정의서"의 요구사항 ID를 기입한다. R1에 실존하는 ID만 기재하며 없으면 `-` + 부록 사유.
- **비고**: 특이사항 등을 기입한다.

### 인터페이스 명세

- **인터페이스 설명**: 해당 인터페이스의 연동 목적과 처리 흐름(위탁·결과 수신·통지·조회 등)을 서술한다.
- **인터페이스 번호**: 송신 시스템의 인터페이스 일련번호를 기입한다.
- **데이터송신시스템 시스템명**: 송신 시스템명을 기술한다.
- **데이터송신시스템 데이터저장소명**: 인터페이스 송신과 관련된 엔티티 또는 파일명을 기술한다. 저작도구 내부는 실제 테이블·뷰 명칭(한글 설명 병기)으로, 외부 시스템은 스키마를 소유하지 않으므로 논리 저장소명으로 기술한다.
- **데이터송신시스템 속성명**: 관련 엔티티의 속성명 또는 파일의 항목명을 기술한다. 모든 속성명은 `기술명 (한글 뜻)` 형식으로 병기하며, 조합·파생 속성은 속성명 셀에 조합 규칙을 병기한다.
- **데이터송신시스템 데이터타입**: 엔티티 속성 또는 항목 타입을 기술한다. 데이터베이스 표준 타입 1개만 기입하며 길이·필수여부·부가 설명을 함께 적지 않는다.
- **데이터송신시스템 길이**: 데이터의 길이를 기술한다. 고정 길이가 성립하지 않으면 `-`.
- **송신 프로그램 ID**: 송신에 해당하는 프로그램 ID를 기입한다.
- **데이터수신시스템 데이터저장소명**: 인터페이스 송수신과 관련된 엔티티 또는 파일명을 기술한다. 영속 저장소가 없는 무상태 수신측은 논리 입력명으로 기술한다.
- **데이터수신시스템 속성명**: 관련 엔티티의 속성명 또는 파일의 항목명을 기술한다.
- **데이터수신시스템 데이터타입**: 엔티티 속성 또는 항목 타입을 기술한다. 송신측 타입과 대칭으로 기술하며 전송 과정의 형 변환은 속성 설명에 적는다.
- **데이터수신시스템 길이**: 데이터의 길이를 기술한다.
- **데이터수신시스템 시스템명**: 수신 시스템명을 기술한다.
- **수신 프로그램 ID**: 수신에 해당하는 프로그램 ID를 기술한다.
- **속성 설명**: 해당 속성의 필수여부(`필수` / `선택` / `조건부`)와 담는 데이터의 의미·처리상 용도를 기술한다.

## 작성 시 참고사항

> **ID 체계 (프로젝트 공식)**
> - 프로젝트 ID: `KLID`
> - 서브시스템 ID (저작도구): `AT`
> - 산출물 파일명: `KLID_AT_인터페이스설계서_Rev {버전}`
> - 본 산출물에서 사용하는 ID:
>   - 인터페이스 번호: `KLID-AT-II-NNN` (예: `KLID-AT-II-001`)
>   - 송신/수신 프로그램 ID: 프로그램별 부여
>   - 관련 요구사항 ID: **R1 실존 ID만** — `RQ-SFR-NN-NN`(기능) · `NFR-NNN`(비기능)
>
> - 본 프로젝트의 인터페이스는 연동 상대 시스템을 기준으로 식별한다: 외부 비식별 솔루션, 외부 시계열 메타 분석 서비스, 외부 생성형 AI(증강) 시스템, 관제지원시스템, 포털, 내부 AI 추론 서버.
> - 처리형태: 추론 호출과 위탁·통지는 Online, 주기 조회·복제는 Batch로 구분한다.
> - 인터페이스 방식: 시스템 간 호출은 HTTP/REST(JSON), 데이터마트 제공과 포털 복제는 데이터베이스 연결 기반이다.
> - 결과를 되돌려 받는 연동(시계열 메타·증강)은 위탁과 결과 수신을 **별도 인터페이스로 분리**해 식별한다.

## 부록 — 데이터 부재/매핑 사유

### 빈 셀(`-`) 사유

- **수신번호**: 관제 통지(KLID-AT-II-007·008)만 상대 시스템의 인터페이스 채번을 보유해 기입했고, 나머지는 상대 시스템 채번 체계를 본 산출물 작성 시점에 보유하지 않아 `-`. 외부 연동 확정 시 보완한다. — *성격상 부재 아님(후속 확보 대상)*
- **수신 상대 담당자 (외부 연동)**: 시스템 간 양방향 자동 연동이 미운영 상태라 외부 시스템 실무담당자가 지정되지 않았다. 비식별 연동(KLID-AT-II-001·002)은 연동이 구현·운영되어 담당 조직은 확정됐고 실무담당자 성명만 미확보다. — *후속 확보 대상*
- **데이터타입 길이 (다수)**: 송수신이 데이터베이스 고정 길이가 아닌 구조화 메시지(JSON) 또는 이미지 부호화 문자열·좌표 배열 기반이라 단일 고정 길이가 성립하지 않는다. 고정 길이가 성립하는 항목만 수치를 기입했다. — *성격상 부재*
- **관련 요구사항 ID — KLID-AT-II-009(데이터마트 적재용 조회 뷰 제공)**: 데이터마트 구축·검색·다운로드는 외부 제공 시스템 책임으로 R1에서 확정 결번(`RQ-SFR-08-06`) 처리되어 직접 대응하는 실존 요구사항 ID가 없다. 본 인터페이스는 저작도구가 검수 완료 데이터를 제공하는 경계면에 해당하므로 의사값을 채우지 않고 `-` 로 둔다. — *성격상 부재*
- **관련 요구사항 ID — KLID-AT-II-010(포털 영상 메타 복제)**: 포털 채널 데이터 소스 연동에 직접 대응하는 기능 요구사항이 R1에 없다(포털 관련 요구는 접근성 `NFR-006`·웹표준 `NFR-015` 등 비기능 축에만 존재하며 본 연동의 직접 근거가 아니다). 의사값을 채우지 않고 `-` 로 둔다. — *성격상 부재*
- **KLID-AT-II-008 구축 메타 갱신 항목(`data_info`)**: 상대 시스템과 키 구성이 미확정이라 속성 표에서 제외했다. 확정 시 보완한다. — *후속 확정 대상*

### 관련 요구사항 ID 매핑 근거

| 인터페이스 | 요구사항 ID | 근거 |
|---|---|---|
| KLID-AT-II-001 | RQ-SFR-09-01 · RQ-SFR-09-02 | 수집 영상 전체 비식별화 처리 / 비식별 솔루션 제공 규격에 따른 연동 |
| KLID-AT-II-002 | RQ-SFR-09-02 · RQ-SFR-09-05 | 위탁·진행 조회·결과 회수 연동 / 처리 결과 연동 확인 |
| KLID-AT-II-003 | RQ-SFR-17 | 영상 학습데이터 구축 — 외부 시계열 메타 위탁 |
| KLID-AT-II-004 | RQ-SFR-17 · RQ-SFR-11-10 | 외부 시계열 메타 검수 / 자동 결과의 검수 대기열 적재 |
| KLID-AT-II-005 | RQ-SFR-07-01 · RQ-SFR-11-05 | 생성형 AI 학습데이터 자동 생성 위탁 / 다양한 환경 영상 확보 |
| KLID-AT-II-006 | RQ-SFR-07-01 · RQ-SFR-07-02 | 생성 결과 수신·새 영상 등록 / 생성 라벨링 데이터 무결성 유지 |
| KLID-AT-II-007 | RQ-SFR-11-10 · RQ-SFR-08-04 | 검수 승인 시 상위 시스템 완료 통지 / 학습데이터 버전관리 운영 |
| KLID-AT-II-008 | RQ-SFR-08-04 · RQ-SFR-08-05 | 변경이력 추적 / 버전별 변경 내용 비교·복구 운영(검수 완료 후 수정 통지) |
| KLID-AT-II-009 | - | 부록 '빈 셀 사유' 참조 |
| KLID-AT-II-010 | - | 부록 '빈 셀 사유' 참조 |
| KLID-AT-II-011 | RQ-SFR-11-04 · RQ-SFR-11-08 | 적재 영상의 자동 라벨링 처리 / 자동 라벨링 보조 |
| KLID-AT-II-012 | RQ-SFR-08-01 | 라벨링 정확도(객체 위치·경계) 향상 — 후속 프레임 추적 |
| KLID-AT-II-013 | RQ-SFR-08-02 | 객체 외곽 경계 자동 밀착 |
| KLID-AT-II-014 | RQ-SFR-08-01 | 라벨링 정확도 향상 — 경계 전파 |

> 위 매핑은 R1 사용자요구사항정의서에 실존하는 요구사항 ID만 인용한다. 직접 대응 항목이 없으면 `-` 로 두며 의사값을 채우지 않는다.

### 속성 좌우 매핑 규칙

- **매핑 방향**: 위탁·통지·조회 제공(KLID-AT-II-001·002·003·005·007·008·009·010·011·012·013·014)은 저작도구 저장소 속성 → 상대 시스템 전송·적재 항목이고, 결과 수신(KLID-AT-II-004·006)은 상대 시스템 전송 항목 → 저작도구 저장소 속성이다.
- **대응 컬럼이 없는 항목**: 순수 요청 파라미터(요청자 식별자·마스킹 옵션·임계값 등), 요청 시점 생성값(요청 채널·작업 종류 등), 집계값(프레임 수 등)은 저작도구에 대응 컬럼이 없으므로 송·수신에 **동일 항목명**을 표기하고 저장소는 송신측 대표 저장소로 묶는다.
- **타입 대칭**: 송신·수신 데이터타입은 대칭으로 기술한다. 전송 과정에서 문자열로 직렬화되는 항목(작업 식별자 등)은 타입을 대칭으로 두고 직렬화 사실을 속성 설명에 적는다.
- **항목명 출처**: 저작도구 측 속성명은 데이터베이스 물리 컬럼명(표준용어 조합)이고, 상대 시스템 측 항목명은 각 연동 규격이 정한 이름이다. **양측 이름이 다른 것이 정상**이며 임의로 맞추지 않는다(예: 이벤트 분류 코드의 저작도구 컬럼명과 관제 규격명은 서로 다르다).

### 연동 정책 메모

- **외부 양방향 자동 연동 미운영**: 관제지원시스템·외부 학습데이터 시스템과의 양방향 통합(상호 인증 기반 자동 연동)은 미운영한다. 관제 연동은 저작도구가 보내는 **단방향 통지**(KLID-AT-II-007·008)와 상대가 상세를 가져가는 **조회 제공**(KLID-AT-II-009 및 저작도구 조회 인터페이스)으로만 구성하며, 재구축 시 별도 설계가 필요하다.
- **인증 토큰 발급 주체**: 저작도구는 인증 토큰을 발급하지 않고 상위 시스템(관제·포털)이 발급한 토큰을 인계받아 검증한다. 관제 통지의 인증 헤더 발급 주체는 상대 시스템 회신 대기 중이다.
- **개인정보 보호**: 외부로 전송하는 영상·프레임은 모두 **비식별본**이며 원본은 전송하지 않는다. 통지 페이로드에는 라벨·메타 본문, 개인정보, 인증정보를 포함하지 않는다. 비식별 누락 신고가 열린 구간에서는 외부 위탁(KLID-AT-II-003·005)을 보류하거나 거부한다.
- **파생영상 취급**: 증강·해상도 변경으로 만들어진 파생영상에는 원본 영상이 존재하지 않고 비식별본만 존재하므로, 상대 시스템은 원본 경로 대신 비식별 파일 경로로 픽업해야 한다. 파생 깊이는 1로 고정한다.
- **상대 시스템 협의 대상**: ①라벨 본문 조회 뷰 제거(라벨 본문은 산출 폴더 파일이 유일한 출처) ②파생영상의 원본 영상 경로 부재 ③통지 규격 전면 교체(2경로 분리·평면 구조)는 상대 시스템의 소비 로직에 영향을 주므로 협의가 필요하다.
