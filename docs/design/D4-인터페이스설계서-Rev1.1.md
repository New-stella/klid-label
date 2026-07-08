# D4 인터페이스 설계서 (Rev 1.1)

> **ID 표기 규칙(공통)**: 본 산출물의 모든 ID(KLID-AT-UC/CO/DC/DCD/SD/SC/AC/EN/ERD/TB/II/IC/IF/UCD/ACT 등)는 **전체 형식 `KLID-AT-XX-NNN`** 으로 표기한다. 끝부분만(예: `II-001`) 약식 표기 금지. 범위는 시작 ID만 전체형으로(예: `KLID-AT-II-001~011`). ※ 요구사항(RQ-SFR-NN-NN)·시스템시험(KLID-ST-NNN) 등 타 체계 ID는 각 체계 원형 유지.
>
> **본 파일 관계**: HWP 정본 `KLID_AT_인터페이스 설계서 Rev 1.1` 구조에 정합한 **신규 파일**이다. 기존 `D4-인터페이스설계서.md`(v1.6, 12종)는 이력 보존을 위해 병존한다. 본 Rev 1.1은 인터페이스 **11종**(KLID-AT-II-001~011)으로 재구성했다.

## 작성 목적
> 시스템의 내·외부 인터페이스를 식별하고 인터페이스의 명세를 기술한다.

## 작성 방법
> 식별된 인터페이스를 송신측과 수신측으로 구분하여 기술하고 송·수신간의 인터페이스 방식을 기술하며, 데이터 송신 시스템과 수신 시스템간의 데이터 저장소와 속성 등의 상세 내역을 기술한다.

## 산출물 양식

### 제·개정 이력

| 날짜 | 버전 | 작성자 | 승인자 | 내용 |
|------|------|--------|--------|------|
| 2026-07-07 | 1.1 | - | - | HWP 정본(KLID_AT_인터페이스 설계서 Rev 1.1) 구조에 정합해 **신규 작성** — 인터페이스 11종(KLID-AT-II-001~011). ① 관제 통지를 **완료통지(II-007)·업데이트통지(II-008)** 2종으로 분리, ② **포털 DB Load·SAM2 추적 제외**, ③ AI 추론(II-009~011) 수신시스템명을 **`학습데이터 저작도구(AI 추론 서버)`** 로 표기, ④ 수신 프로그램 ID를 HWP 표기(객체탐지·객체추적·분할)로 정합. §2 명세는 기존 D4(v1.6) 코드 정합 속성표를 11종 구조로 이관. |

> **주의 — 정본(HWP) 대비 정정 2건**(코드 실측 우선, 문서 내부 일관성 유지):
> 1. **II-002 발생빈도**: HWP `1회/1분` → 본 문서 `1회/30초`. 실구현 폴링 주기 `kpst.deid.poll-interval-sec` 기본값이 **30초**(`KpstDeidentPollTriggerConfig`)이고 §2 명세도 "30초 주기 폴링"으로 기술되어, §1/§2 일관성을 위해 30초로 표기. HWP를 1분으로 유지하려면 설정값을 60으로 변경해야 함(운영 결정 필요).
> 2. **II-001 비고**: HWP `업로드로 입력 경로 확보` → 본 문서 `공유 마운트(no-copy) 직접 참조(업로드 없음)`. KPST 실연동은 공유 마운트 no-copy 모델(원본 업로드·결과 다운로드 단계 없음)로, §2 명세와 일관되도록 정정.

### 헤더

| D4 | 인터페이스 설계서 |
|-------|-----------|
| 시스템명 | AI 기반 지방정부 CCTV 관제지원시스템(2차) | 서브시스템명 | 학습데이터 저작도구 |
| 단계명 | 설계 | 작성일자 | 2026-07-07 | 버전 | 1.1 |

### 1. 인터페이스 목록

| 인터페이스번호 | 송신 일련번호 | 송신 시스템명 | 송신 프로그램 ID | 전달 처리형태 | 전달 인터페이스방식 | 전달 발생빈도 | 수신 상대 담당자 | 수신 프로그램 ID | 수신시스템명 | 수신 일련번호 | 수신번호 | 관련 요구사항 ID | 비고 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| KLID-AT-II-001 | 1 | 학습데이터 저작도구 | 비식별위탁 | Online | HTTP/REST(JSON) | 1회/영상 | 연동 완료(KPST) — 담당자 성명 확인 필요 | 비식별프로젝트생성 | 외부 비식별 솔루션 | 1 | - | RQ-SFR-09-01 | 전체 영상 비식별 위탁(파이프라인 선두). **공유 마운트(no-copy)** 로 원본 경로를 직접 참조(업로드 없음)하고 결과는 결과 저장 경로에 KPST가 직접 산출. 원본은 별도 경로 보존. 재시도(최대 3회·점증 대기). 내부망 평문 또는 자체 인증서 기반 보안 전송으로 분기. **KPST 실연동 구현·운영** |
| KLID-AT-II-002 | 1 | 학습데이터 저작도구 | 비식별진행폴링 | Batch | HTTP/REST(JSON) | 1회/30초 | 연동 완료(KPST) — 담당자 성명 확인 필요 | 비식별진행조회 | 외부 비식별 솔루션 | 1 | - | RQ-SFR-09-01 | 비식별 진행 상태 주기 폴링(콜백 미사용). 전체 완료 확인 시 결과 회수·마킹 진입. 최대 회수·시간 초과 시 비식별 실패 처리(거짓 완료 방지). **KPST 실연동 구현·운영** |
| KLID-AT-II-003 | 1 | 학습데이터 저작도구 | VLM위탁 | Online | HTTP/REST(JSON) | 1회/영상 | 미정 — 연동 시 확정 | VLM시계열생성 | 외부 VLM 시계열 메타 서비스 | 1 | - | RQ-SFR-08 | 시계열 메타 생성 위탁(수락만 동기 응답). 결과는 콜백(KLID-AT-II-004)으로 수신. 멱등 키 헤더로 중복 차단. 응답시간 45초 초과 시 차단·재시도 |
| KLID-AT-II-004 | 1 | 외부 VLM 시계열 메타 서비스 | VLM시계열생성 | Online | HTTP/REST(JSON) | 1회/영상 | (저작도구 검수 담당) | VLM결과수신 | 학습데이터 저작도구 | 1 | - | RQ-SFR-08 | VLM 시계열 결과 콜백 수신 → 시계열 메타 적재 + 검수 대기열 진입. 콜백 진위 검증(메시지 인증) + 멱등 처리 |
| KLID-AT-II-005 | 1 | 학습데이터 저작도구 | 증강위탁 | Online | HTTP/REST(JSON) | 1회/증강요청 | 미정 — 연동 시 확정 | 증강생성 | 외부 생성형 AI(증강) 시스템 | 1 | - | RQ-SFR-07 | 날씨·계절·시간 증강(WINTER/NIGHT/RAIN) 생성 위탁. **비식별 영상 전달(원본 미전송 — 개인정보 보호)**. 결과는 콜백(KLID-AT-II-006)으로 수신. 인증 키 기반. 해상도 변경은 저작도구 내부 기능으로 외부 위탁 대상 아님 |
| KLID-AT-II-006 | 1 | 외부 생성형 AI(증강) 시스템 | 증강생성 | Online | HTTP/REST(JSON) | 1회/증강결과 | - | 증강결과수신 | 학습데이터 저작도구 | 1 | - | RQ-SFR-07 | 증강 결과 콜백 수신 → 새 영상 등록(원본 참조)·원본 라벨 복사·검수 대기 시작. 콜백 진위 검증(메시지 인증) + 멱등 처리 |
| KLID-AT-II-007 | 1 | 학습데이터 저작도구 | 완료통지발신 | Online | HTTP/REST(JSON) | 이벤트(영상 단위 라벨링 최초 완료 시) | 미정 — 연동 시 확정 | 완료통지수신 | 관제지원시스템 | 1 | - | RQ-SFR-08-01 | 완료 통지 → 데이터셋 생성. 영상 단위 검수 완료(TASK_COMPLETED)를 관제지원시스템에 단방향 통지(메타 요약만, 라벨·메타 본문·개인정보 미포함). 멱등 처리·실패 재등록 큐·인계 토큰/IP 화이트리스트 보호. **ControlNotifyClient 실구현 페이로드에 정합** |
| KLID-AT-II-008 | 1 | 학습데이터 저작도구 | 업데이트통지발신 | Online | HTTP/REST(JSON) | 이벤트(JSON 업데이트 시) | 미정 — 연동 시 확정 | 업데이트통지수신 | 관제지원시스템 | 1 | - | RQ-SFR-08-01 | 업데이트 통지 → 버전 증가. 검수 완료 후 라벨/메타 수정(TASK_MODIFIED)을 영상 단위로 단방향 통지(변경 요약만). 동일 작업 ID 유지(버전 업 아님). 멱등 처리·실패 재등록 큐. **ControlNotifyClient 실구현 페이로드에 정합** |
| KLID-AT-II-009 | 1 | 학습데이터 저작도구 | YOLO탐지 | Online | HTTP/REST(JSON) | 1회/프레임 | 내부 — ai-server(자체 운영) | 객체탐지 | 학습데이터 저작도구(AI 추론 서버) | 1 | - | RQ-SFR-08 | 오토라벨링 객체 탐지. `POST /infer/yolo/predict`. 원본 프레임(base64) 입력 → 탐지 박스 동기 응답. ai-server 무상태 추론(인증·DB 없음), AiServerClient(Resilience4j) 호출 |
| KLID-AT-II-010 | 1 | 학습데이터 저작도구 | YOLO추적 | Online | HTTP/REST(JSON) | 1회/프레임 | 내부 — ai-server(자체 운영) | 객체추적 | 학습데이터 저작도구(AI 추론 서버) | 1 | - | RQ-SFR-08 | 오토라벨링 프레임 간 객체 추적. `POST /infer/yolo/track`. 프레임+클립ID+프레임인덱스 입력 |
| KLID-AT-II-011 | 1 | 학습데이터 저작도구 | SAM2분할 | Online | HTTP/REST(JSON) | 1회/분할요청 | 내부 — ai-server(자체 운영) | 분할 | 학습데이터 저작도구(AI 추론 서버) | 1 | - | RQ-SFR-08 | 객체 분할(외각선). 클릭/박스 프롬프트 분할(폴리곤). `POST /infer/sam2/segment`. mock 응답은 FE 자동적용 차단 |

> ※ '수신번호' 컬럼은 상대 시스템 채번 미보유로 전 인터페이스 `-`. 외부 미연동 인터페이스의 '수신 상대 담당자'는 "미정 — 연동 시 확정"으로 표기한다. **단, 비식별(II-001·II-002)은 KPST 실연동이 구현·운영되어 "연동 완료(KPST) — 담당자 성명 확인 필요"로 표기**하며, 나머지 외부 미연동(VLM·증강·관제 통지)만 "미정 — 연동 시 확정"을 유지한다. 관련 요구사항 ID 매핑 근거는 부록 참조.
>
> ※ AI 추론(YOLO 객체탐지·추적, SAM2 분할) 호출은 저작도구가 자체 운영하는 **내부 추론 인프라(ai-server)** 로, 저작도구 ↔ ai-server 가 별도 프로세스로 HTTP/REST 호출되는 시스템 간 연동이므로 **KLID-AT-II-009~011 내부 인터페이스로 본 목록에 포함**한다. 수신시스템명은 저작도구 서브시스템임을 나타내 `학습데이터 저작도구(AI 추론 서버)`로 표기한다. 컴포넌트 설계서(D3)는 동일 연동의 내부 구조를 상세화한다. ai-server 의 `POST /infer/vlm/verify-objects`(VLM 객체 검증)·`POST /infer/sam2/track`(SAM2 추적)은 본 Rev 1.1 목록에서 제외한다(HWP 정본 정합 — 추후 필요 시 보완).

### 2. 인터페이스 명세

> 인터페이스별로 1개씩 반복 작성한다. 각 명세는 **인터페이스 설명**(연동 목적·처리 흐름), **속성 표**(송신·수신 데이터 저장소·속성 좌우 대조 + 속성 설명 — 필수여부는 데이터타입 셀에 병기), **동기 응답**(수락 확인·결과 등 응답 규격) 순으로 기술한다.
>
> **좌우 대조 규칙(Critical)**: 속성 표는 **송신측 실제 속성(데이터 저장소·속성명·데이터타입·길이)** 과 **수신측 실제 속성** 을 좌우로 대조해 **변환(매핑)** 을 표현한다. 양측은 이름·타입·저장소가 **서로 다를 수 있다** — 저작도구측은 실제 `LS_*` 테이블 컬럼명 + DB 타입(VARCHAR/BIGINT/INT/TIMESTAMP + 길이), 외부/전송측은 실제 JSON 필드명 + 전송 타입(string/int/number/long/array)으로 기술한다. 예: `ORGNL_FILE_PATH_NM`(VARCHAR,1000) → `input_path`(string), `META_KEY`(VARCHAR,64) ← `metaKey`(string). 코드 실측(클라이언트 DTO·엔티티 `@Column`)에 정합한다. 모든 속성명은 `기술명 (한글 뜻)` 으로 병기한다. 대응 DB 컬럼이 없는 순수 요청 파라미터·생성값·집계값은 송·수신에 동일 전송 필드명을 표기한다.
>
> **필수여부 표기**: `필수`(항상 전달) / `선택`(미지정 시 규격 기본값 적용) / `조건부`(특정 조건에서만 전달). 필수여부는 별도 컬럼 없이 **데이터타입 셀에 괄호로 병기**한다(예: `string (필수)`, `int (선택)`). 조합·파생 속성은 **속성명 셀에 조합 규칙을 병기**한다(예: `project_name (프로젝트명 = "raw"+RAW_SN 조합)`).

#### KLID-AT-II-001 — 비식별 처리 위탁

> **인터페이스 설명**: 전체 영상을 외부 비식별 솔루션(KPST)에 위탁해 비식별 프로젝트를 생성하고 마스킹을 개시한다(파이프라인 선두). **공유 마운트(no-copy) 모델**로 원본은 입력 경로로 직접 참조하고(업로드 단계 없음) 결과는 결과 저장 경로에 KPST가 직접 산출한다(다운로드 단계 없음). 원본은 별도 경로에 보존한다. **API: `POST /project`** (삭제 `POST /delete_project_id`).

<table>
<tr><td rowspan="2">인터페이스 번호</td><td colspan="5" align="center"><b>데이터송신시스템</b></td><td rowspan="2">송신 프로그램 ID</td><td colspan="5" align="center"><b>데이터수신시스템</b></td><td rowspan="2">수신 프로그램 ID</td><td rowspan="2">속성 설명</td></tr>
<tr><td>시스템명</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입(필수여부)</td><td>길이</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입(필수여부)</td><td>길이</td><td>시스템명</td></tr>
<tr><td rowspan="10">KLID-AT-II-001</td><td rowspan="10">학습데이터 저작도구</td><td rowspan="10">LS_DEIDENT_PROC_LOG (비식별 처리 로그)</td><td>ORGNL_FILE_PATH_NM (원본 영상 경로)</td><td>VARCHAR (필수)</td><td>1000</td><td rowspan="10">비식별위탁</td><td rowspan="10">비식별 요청 (KPST POST /project)</td><td>input_path (입력 경로)</td><td>string (필수)</td><td>-</td><td rowspan="10">외부 비식별 솔루션</td><td rowspan="10">비식별프로젝트생성</td><td>원본 영상 입력 디렉터리 경로. 공유 마운트로 직접 참조(업로드 없음).</td></tr>
<tr><td>DE_IDNTF_FILE_PATH_NM (비식별 결과 경로)</td><td>VARCHAR (필수)</td><td>1000</td><td>export_path (결과 저장 경로)</td><td>string (필수)</td><td>-</td><td>비식별 결과 저장 경로. KPST가 no-copy 로 직접 산출.</td></tr>
<tr><td>project_name (프로젝트명 = "raw"+RAW_SN 조합)</td><td>string (필수)</td><td>-</td><td>project_name (프로젝트명 = "raw"+RAW_SN 조합)</td><td>string (필수)</td><td>-</td><td>비식별 프로젝트명. RAW_SN 으로 조합 생성(숫자 유니크, 예: raw279). 동일명 존재 시 서버 409.</td></tr>
<tr><td>creator (요청자)</td><td>string (필수)</td><td>-</td><td>creator (요청자)</td><td>string (필수)</td><td>-</td><td>위탁 요청자 식별자. 설정값(kpst.deid.creator-id, 기본 authoring) — 대응 DB 컬럼 없음.</td></tr>
<tr><td>files (대상 파일 목록)</td><td>string array (필수)</td><td>-</td><td>files (대상 파일 목록)</td><td>string array (필수)</td><td>-</td><td>비식별 처리 대상 파일명 목록(문자열 배열).</td></tr>
<tr><td>masking_type (마스킹 종류)</td><td>int (선택)</td><td>-</td><td>masking_type (마스킹 종류)</td><td>int (선택)</td><td>-</td><td>마스킹 방식 코드. 미지정 시 기본값 0.</td></tr>
<tr><td>db_save (DB 저장 여부)</td><td>int (선택)</td><td>-</td><td>db_save (DB 저장 여부)</td><td>int (선택)</td><td>-</td><td>결과 DB 저장 여부 코드. 미지정 시 기본값 0.</td></tr>
<tr><td>masking_range (마스킹 범위)</td><td>int (선택)</td><td>-</td><td>masking_range (마스킹 범위)</td><td>int (선택)</td><td>-</td><td>마스킹 적용 범위 코드. 미지정 시 기본값 1.</td></tr>
<tr><td>exp_quality (결과 품질)</td><td>int (선택)</td><td>-</td><td>exp_quality (결과 품질)</td><td>int (선택)</td><td>-</td><td>결과 영상 품질 코드. 미지정 시 기본값 0.</td></tr>
<tr><td>exp_format (결과 포맷)</td><td>int (선택)</td><td>-</td><td>exp_format (결과 포맷)</td><td>int (선택)</td><td>-</td><td>결과 영상 포맷 코드. 미지정 시 기본값 1.</td></tr>
</table>

> **동기 응답**: 외부 비식별 솔루션(KPST) → 저작도구. 응답 데이터 — 처리 결과 코드(result, VARCHAR), 비식별 프로젝트 식별자(prj_id, BIGINT, 성공 시에만). 공유 마운트(no-copy)로 영상 업로드·결과 다운로드 단계가 없으며 입력/결과 경로로 참조·산출한다. 원본은 별도 경로에 보존한다. 재시도(최대 3회·점증 대기). 진행 추적은 KLID-AT-II-002. 운영 전송은 내부망 평문 또는 자체 인증서(TLS 자체 CA) 기반 보안 전송으로 분기.

#### KLID-AT-II-002 — 비식별 진행 상태 폴링

> **인터페이스 설명**: 생성한 비식별 프로젝트의 처리 진행 상태를 30초 주기로 폴링해 전체 완료를 확인하고 결과를 회수(마킹 진입 허용)한다. 콜백을 사용하지 않으며, 최대 회수·시간 초과 시 거짓 완료를 방지하기 위해 비식별 실패로 처리한다. **API: `GET /retrieve_progress`** (JSON 바디 필수).

<table>
<tr><td rowspan="2">인터페이스 번호</td><td colspan="5" align="center"><b>데이터송신시스템</b></td><td rowspan="2">송신 프로그램 ID</td><td colspan="5" align="center"><b>데이터수신시스템</b></td><td rowspan="2">수신 프로그램 ID</td><td rowspan="2">속성 설명</td></tr>
<tr><td>시스템명</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입(필수여부)</td><td>길이</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입(필수여부)</td><td>길이</td><td>시스템명</td></tr>
<tr><td rowspan="2">KLID-AT-II-002</td><td rowspan="2">학습데이터 저작도구</td><td rowspan="2">LS_DEIDENT_PROC_LOG (비식별 처리 로그)</td><td>reqUserId (요청자 ID)</td><td>string (필수)</td><td>-</td><td rowspan="2">비식별진행폴링</td><td rowspan="2">진행 조회 요청 (KPST GET /retrieve_progress)</td><td>reqUserId (요청자 ID)</td><td>string (필수)</td><td>-</td><td rowspan="2">외부 비식별 솔루션</td><td rowspan="2">비식별진행조회</td><td>진행 조회 요청자 ID. 설정값(kpst.deid.req-user-id, 기본 authoring) — 대응 DB 컬럼 없음. 실서버가 JSON 바디 필터로 강제.</td></tr>
<tr><td>KPST_PRJ_ID (KPST 프로젝트 ID)</td><td>BIGINT (필수)</td><td>-</td><td>prjId (프로젝트 ID)</td><td>long (필수)</td><td>-</td><td>조회 대상 비식별 프로젝트 ID. 폴링 시 단일 프로젝트 대상.</td></tr>
</table>

> **동기 응답**: 외부 비식별 솔루션(KPST) → 저작도구. 응답 데이터 — 프로젝트별 처리 상태 목록(JSON 배열: 데이터셋 처리 상태 procState 정수, 진행률 progressRate 실수, 종료 시각 endTime 문자열). 처리 완료(다운로드 가능) 상태 확인 시 결과를 회수하고 비식별 완료로 처리(마킹 진입 허용)한다. 미시작(procState null) 상태는 미완료로 처리한다. 최대 회수·시간 초과 시 비식별 실패로 마킹(거짓 완료 방지)한다. 30초 주기 폴링. 진행 조회 요청은 GET + JSON 바디로 식별자를 전달한다(실서버 규격).

#### KLID-AT-II-003 — VLM 시계열 위탁

> **인터페이스 설명**: 마킹 완료된 영상의 시계열 메타 생성을 외부 VLM 서비스에 위탁한다. 요청은 수락 여부만 동기 응답하고 결과 상세는 콜백(KLID-AT-II-004)으로 수신하며, 멱등 키 헤더로 중복을 차단한다.

<table>
<tr><td rowspan="2">인터페이스 번호</td><td colspan="5" align="center"><b>데이터송신시스템</b></td><td rowspan="2">송신 프로그램 ID</td><td colspan="5" align="center"><b>데이터수신시스템</b></td><td rowspan="2">수신 프로그램 ID</td><td rowspan="2">속성 설명</td></tr>
<tr><td>시스템명</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입(필수여부)</td><td>길이</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입(필수여부)</td><td>길이</td><td>시스템명</td></tr>
<tr><td rowspan="6">KLID-AT-II-003</td><td rowspan="6">학습데이터 저작도구</td><td rowspan="6">LS_MARKING (마킹) · LS_DATA_RAW (영상)</td><td>RAW_SN (영상 식별자)</td><td>BIGINT (필수)</td><td>-</td><td rowspan="6">VLM위탁</td><td rowspan="6">VLM 시계열 위탁 요청 (외부 VLM POST /v1/timeseries/submit)</td><td>rawSn (영상 식별자)</td><td>long (필수)</td><td>-</td><td rowspan="6">외부 VLM 시계열 메타 서비스</td><td rowspan="6">VLM시계열생성</td><td>시계열 메타 생성 대상 영상 식별자(LS_DATA_RAW.RAW_SN).</td></tr>
<tr><td>VIDEO_FILE_PATH_NM (영상 경로)</td><td>VARCHAR (필수)</td><td>500</td><td>videoUri (영상 URI)</td><td>string (필수)</td><td>-</td><td>대상 비식별 영상 경로/URI(LS_MARKING).</td></tr>
<tr><td>EVNT_NM (이벤트명)</td><td>VARCHAR (필수)</td><td>100</td><td>eventName (이벤트명)</td><td>string (필수)</td><td>-</td><td>마킹된 이벤트명(시계열 생성 컨텍스트).</td></tr>
<tr><td>MARK_CN (마킹 내용)</td><td>TEXT (필수)</td><td>-</td><td>marks (마킹 시점 목록)</td><td>string (필수)</td><td>-</td><td>마킹 시점(프레임) 목록(직렬화 문자열).</td></tr>
<tr><td>idempotencyKey (멱등 키)</td><td>string (필수)</td><td>64</td><td>idempotencyKey (멱등 키)</td><td>string (필수)</td><td>64</td><td>멱등 키(HTTP 헤더 X-Idempotency-Key). 재시도 시 동일 키 유지.</td></tr>
<tr><td>callbackUrl (콜백 URL)</td><td>string (필수)</td><td>-</td><td>callbackUrl (콜백 URL)</td><td>string (필수)</td><td>-</td><td>결과(KLID-AT-II-004)를 회신받을 콜백 URL.</td></tr>
</table>

> **동기 응답**: 외부 VLM 서비스 → 저작도구. 응답 데이터 — 수락 결과 코드(VARCHAR), 위탁 식별자(VARCHAR). 결과 상세는 콜백(KLID-AT-II-004)으로 별도 수신한다. 멱등 키 헤더로 중복을 차단한다. 응답시간 45초 초과 시 차단·재시도.

#### KLID-AT-II-004 — VLM 시계열 결과 콜백 수신

> **인터페이스 설명**: 외부 VLM 서비스가 생성한 시계열 메타 결과를 콜백으로 수신해 적재하고 검수 대기열에 진입시킨다. 콜백 진위 검증(메시지 인증)과 멱등 처리를 적용한다. **API(수신): `POST /v1/vlm/result`** (HMAC 인증).

<table>
<tr><td rowspan="2">인터페이스 번호</td><td colspan="5" align="center"><b>데이터송신시스템</b></td><td rowspan="2">송신 프로그램 ID</td><td colspan="5" align="center"><b>데이터수신시스템</b></td><td rowspan="2">수신 프로그램 ID</td><td rowspan="2">속성 설명</td></tr>
<tr><td>시스템명</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입(필수여부)</td><td>길이</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입(필수여부)</td><td>길이</td><td>시스템명</td></tr>
<tr><td rowspan="7">KLID-AT-II-004</td><td rowspan="7">외부 VLM 시계열 메타 서비스</td><td rowspan="7">VLM 시계열 결과 (외부 VLM POST /v1/vlm/result)</td><td>rawSn (영상 식별자)</td><td>long (필수)</td><td>-</td><td rowspan="7">VLM시계열생성</td><td rowspan="7">LS_DATA_META (시계열 메타) · LS_DATA_META_REVIEW (메타 검수큐)</td><td>RAW_SN (영상 식별자)</td><td>BIGINT (필수)</td><td>-</td><td rowspan="7">학습데이터 저작도구</td><td rowspan="7">VLM결과수신</td><td>분석 대상 영상 식별자.</td></tr>
<tr><td>vlmMetaItems[].metaKey (시계열 정렬 키)</td><td>string (필수)</td><td>64</td><td>META_KEY (메타 키)</td><td>VARCHAR (필수)</td><td>64</td><td>시계열 정렬 키(마킹 frameIndex). (RAW_SN, META_KEY) UNIQUE.</td></tr>
<tr><td>vlmMetaItems[].metaVal (자연어 서술)</td><td>string (필수)</td><td>2000</td><td>META_VL (메타 값)</td><td>VARCHAR (필수)</td><td>2000</td><td>해당 시점의 자연어 서술(항목별 1행 적재).</td></tr>
<tr><td>idempotencyKey (멱등 키)</td><td>string (필수)</td><td>64</td><td>IDMP_KEY (멱등 키)</td><td>VARCHAR (필수)</td><td>64</td><td>원래 위탁 요청 멱등 키(중복 차단).</td></tr>
<tr><td>externalJobId (외부 작업 ID)</td><td>string (필수)</td><td>128</td><td>OTSD_JOB_ID (외부 작업 ID)</td><td>VARCHAR (필수)</td><td>128</td><td>외부 VLM 서비스 작업 ID.</td></tr>
<tr><td>status (처리 상태)</td><td>string (필수)</td><td>-</td><td>RVW_STTS_CD (검수 상태)</td><td>VARCHAR (필수)</td><td>20</td><td>처리 상태(SUCCESS/FAILED/PARTIAL) → LS_DATA_META_REVIEW 검수 상태로 반영.</td></tr>
<tr><td>resultFilePath (결과 경로)</td><td>string (선택)</td><td>1000</td><td>-</td><td>- (선택)</td><td>-</td><td>결과 위치 URL/경로(SSRF 검증 대상).</td></tr>
</table>

> **동기 응답**: 저작도구 → 외부 VLM 서비스. 수신 확인 결과 코드(VARCHAR). 수신 후 시계열 메타를 적재하고 검수 대기열에 진입시킨다. 콜백 진위 검증(메시지 인증·시각·멱등 키 허용목록) + 멱등 처리.

#### KLID-AT-II-005 — 외부 증강 생성 위탁

> **인터페이스 설명**: 비식별 영상의 날씨·계절·시간 증강(WINTER/NIGHT/RAIN) 생성을 외부 생성형 AI 시스템에 위탁한다. 위탁 요청은 `originAugSn`·`augType`·멱등/작업 식별자·콜백 URL 만 전달하는 계약(`ExternalAugmentClient#requestAugment`)이며, **대상 비식별 영상은 KPST 와 동일하게 공유 컨텍스트(경로)로 접근하고 전송 필드로 싣지 않는다(원본 미전송 — 개인정보 보호).** 결과는 콜백(KLID-AT-II-006)으로 수신하며 인증 키 기반으로 보호한다. 해상도 변경은 저작도구 내부 기능으로 위탁 대상이 아니다. ※ 현재 실구현 클라이언트는 Noop/DevSim 으로, 실제 외부 증강 시스템 연동 시 계약 확정 필요.

<table>
<tr><td rowspan="2">인터페이스 번호</td><td colspan="5" align="center"><b>데이터송신시스템</b></td><td rowspan="2">송신 프로그램 ID</td><td colspan="5" align="center"><b>데이터수신시스템</b></td><td rowspan="2">수신 프로그램 ID</td><td rowspan="2">속성 설명</td></tr>
<tr><td>시스템명</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입(필수여부)</td><td>길이</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입(필수여부)</td><td>길이</td><td>시스템명</td></tr>
<tr><td rowspan="5">KLID-AT-II-005</td><td rowspan="5">학습데이터 저작도구</td><td rowspan="5">LS_DATA_AUG (증강 데이터)</td><td>DATA_AUG_SN (증강 행 PK)</td><td>BIGINT (필수)</td><td>-</td><td rowspan="5">증강위탁</td><td rowspan="5">증강 위탁 요청 (외부 증강)</td><td>originAugSn (원본 증강 SN)</td><td>long (필수)</td><td>-</td><td rowspan="5">외부 생성형 AI(증강) 시스템</td><td rowspan="5">증강생성</td><td>PENDING 상태로 사전 등록된 증강 행 PK(콜백 매칭 키).</td></tr>
<tr><td>AUG_TYPE_CD (증강 유형)</td><td>VARCHAR (필수)</td><td>20</td><td>augType (증강 유형)</td><td>string (필수)</td><td>-</td><td>증강 유형. WINTER/NIGHT/RAIN 중 하나만 허용.</td></tr>
<tr><td>IDMP_KEY (멱등 키)</td><td>VARCHAR (필수)</td><td>64</td><td>idempotencyKey (멱등 키)</td><td>string (필수)</td><td>-</td><td>원래 위탁 요청 멱등 키.</td></tr>
<tr><td>OTSD_JOB_ID (외부 작업 ID)</td><td>VARCHAR (필수)</td><td>128</td><td>externalJobId (외부 작업 ID)</td><td>string (필수)</td><td>-</td><td>외부 증강 시스템 작업 ID(콜백 컨텍스트).</td></tr>
<tr><td>callbackUrl (콜백 URL)</td><td>string (필수)</td><td>-</td><td>callbackUrl (콜백 URL)</td><td>string (필수)</td><td>-</td><td>결과(KLID-AT-II-006)를 회신받을 콜백 URL.</td></tr>
</table>

> **동기 응답**: 외부 증강 시스템 → 저작도구. 수락 결과 코드(VARCHAR), 위탁 식별자(VARCHAR). 증강 종류는 날씨·계절·시간(WINTER/NIGHT/RAIN)만 허용한다. 결과는 콜백(KLID-AT-II-006)으로 수신한다. 인증 키 기반. 해상도 변경은 외부 위탁 대상이 아닌 저작도구 내부 기능.

#### KLID-AT-II-006 — 증강 결과 콜백 수신

> **인터페이스 설명**: 외부 증강 시스템의 증강 결과를 콜백으로 수신해 새 영상으로 등록(원본 참조)하고 원본 라벨을 복사한 뒤 검수 대기를 시작한다. 콜백 진위 검증(메시지 인증)과 멱등 처리를 적용한다. **API(수신): `POST /v1/augments/result`** (HMAC 인증).

<table>
<tr><td rowspan="2">인터페이스 번호</td><td colspan="5" align="center"><b>데이터송신시스템</b></td><td rowspan="2">송신 프로그램 ID</td><td colspan="5" align="center"><b>데이터수신시스템</b></td><td rowspan="2">수신 프로그램 ID</td><td rowspan="2">속성 설명</td></tr>
<tr><td>시스템명</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입(필수여부)</td><td>길이</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입(필수여부)</td><td>길이</td><td>시스템명</td></tr>
<tr><td rowspan="6">KLID-AT-II-006</td><td rowspan="6">외부 생성형 AI(증강) 시스템</td><td rowspan="6">증강 생성 결과 (외부 증강 POST /v1/augments/result)</td><td>originAugSn (원본 증강 SN)</td><td>long (필수)</td><td>-</td><td rowspan="6">증강생성</td><td rowspan="6">LS_DATA_AUG (증강 데이터) · LS_DATA_RAW (신규 영상)</td><td>DATA_AUG_SN (증강 행 PK)</td><td>BIGINT (필수)</td><td>-</td><td rowspan="6">학습데이터 저작도구</td><td rowspan="6">증강결과수신</td><td>사전 등록 증강 행 PK로 매칭(원본 참조 = PARENT_RAW_SN 연계).</td></tr>
<tr><td>augType (증강 유형)</td><td>string (필수)</td><td>-</td><td>AUG_TYPE_CD (증강 유형)</td><td>VARCHAR (필수)</td><td>20</td><td>적용된 증강 유형(WINTER/NIGHT/RAIN).</td></tr>
<tr><td>resultFilePath (결과 영상 경로)</td><td>string (필수)</td><td>1000</td><td>RAW_FILE_PATH_NM (영상 경로)</td><td>VARCHAR (필수)</td><td>500</td><td>생성된 증강 영상 경로 → 신규 RAW_SN 영상으로 등록(SSRF 검증).</td></tr>
<tr><td>status (처리 상태)</td><td>string (필수)</td><td>-</td><td>AUG_PROC_STTS_CD (증강 처리 상태)</td><td>VARCHAR (필수)</td><td>20</td><td>처리 상태(SUCCESS/FAILED/PARTIAL).</td></tr>
<tr><td>idempotencyKey (멱등 키)</td><td>string (필수)</td><td>64</td><td>IDMP_KEY (멱등 키)</td><td>VARCHAR (필수)</td><td>64</td><td>원래 위탁 요청 멱등 키(중복 차단).</td></tr>
<tr><td>externalJobId (외부 작업 ID)</td><td>string (필수)</td><td>128</td><td>OTSD_JOB_ID (외부 작업 ID)</td><td>VARCHAR (필수)</td><td>128</td><td>외부 증강 시스템 작업 ID.</td></tr>
</table>

> **동기 응답**: 저작도구 → 외부 증강 시스템. 수신 확인 결과 코드(VARCHAR). 수신 후 새 영상을 등록(원본 참조)하고 원본 라벨을 복사한 뒤 검수 대기로 시작한다. 증강 종류는 WINTER/NIGHT/RAIN만 허용. 콜백 진위 검증(메시지 인증) + 멱등 처리.

#### KLID-AT-II-007 — 관제서버 완료 통지 (TASK_COMPLETED)

> **인터페이스 설명**: 영상 단위 작업의 **검수 완료(TASK_COMPLETED)** 를 관제지원시스템에 단방향으로 통지한다(완료 통지 → 데이터셋 생성). 공통 필드(이벤트 종류·작업 식별자·요청 ID) 위에 완료 통지 필드(검수자·완료 일시·프레임 카운트)를 전달한다. 라벨/메타 본문·개인정보는 포함하지 않으며(CWE-359), 관제서버는 수신 후 조회 인터페이스로 상세를 가져간다. **ControlNotifyClient(단방향 outbound) `TaskCompletedPayload` 에 정합.**

<table>
<tr><td rowspan="2">인터페이스 번호</td><td colspan="5" align="center"><b>데이터송신시스템</b></td><td rowspan="2">송신 프로그램 ID</td><td colspan="5" align="center"><b>데이터수신시스템</b></td><td rowspan="2">수신 프로그램 ID</td><td rowspan="2">속성 설명</td></tr>
<tr><td>시스템명</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입(필수여부)</td><td>길이</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입(필수여부)</td><td>길이</td><td>시스템명</td></tr>
<tr><td rowspan="7">KLID-AT-II-007</td><td rowspan="7">학습데이터 저작도구</td><td rowspan="7">LS_RAW_DATA_STATUS (작업/검수 상태) · LS_DATA_RAW (영상)</td><td>eventType (이벤트 종류)</td><td>string (필수)</td><td>-</td><td rowspan="7">완료통지발신</td><td rowspan="7">완료 통지 페이로드 (관제지원시스템 POST /api/v1/notify)</td><td>eventType (이벤트 종류)</td><td>string (필수)</td><td>-</td><td rowspan="7">관제지원시스템</td><td rowspan="7">완료통지수신</td><td>통지 유형 — TASK_COMPLETED 고정. 공통 필드.</td></tr>
<tr><td>RAW_DATA_ID (작업 식별자)</td><td>BIGINT (필수)</td><td>-</td><td>rawSn (작업 식별자)</td><td>long (필수)</td><td>-</td><td>영상 단위 작업 식별자(=LS_DATA_RAW.RAW_SN). 공통 필드.</td></tr>
<tr><td>requestId (요청 ID)</td><td>string (필수)</td><td>36</td><td>requestId (요청 ID)</td><td>string (필수)</td><td>36</td><td>멱등 처리용 요청 식별자(UUID). 공통 필드.</td></tr>
<tr><td>reviewerName (검수자 이름)</td><td>string (필수)</td><td>-</td><td>reviewerName (검수자 이름)</td><td>string (필수)</td><td>-</td><td>검수를 승인한 검수자 이름.</td></tr>
<tr><td>UPD_DT (수정 일시)</td><td>TIMESTAMP (필수)</td><td>-</td><td>approvedAt (검수 완료 일시)</td><td>string (필수)</td><td>-</td><td>검수 완료(승인) 일시(ISO-8601 Instant).</td></tr>
<tr><td>totalFrames (총 프레임 수)</td><td>int (필수)</td><td>-</td><td>totalFrames (총 프레임 수)</td><td>int (필수)</td><td>-</td><td>영상의 총 프레임 수.</td></tr>
<tr><td>labeledFrames (라벨링 완료 프레임 수)</td><td>int (필수)</td><td>-</td><td>labeledFrames (라벨링 완료 프레임 수)</td><td>int (필수)</td><td>-</td><td>라벨링 완료 프레임 수.</td></tr>
</table>

> **동기 응답**: 관제지원시스템 → 저작도구. 수신 확인 결과 코드(VARCHAR). 라벨/메타 본문·개인정보는 미포함하며(관제서버는 수신 후 조회 인터페이스로 상세를 보강), 영상 내 다수 완료가 짧은 시간 내 발생하면 디바운스 후 1회 통지한다. 멱등 처리(requestId)·실패 재등록 큐·인계 토큰/IP 화이트리스트 보호.

#### KLID-AT-II-008 — 관제서버 수정 통지 (TASK_MODIFIED)

> **인터페이스 설명**: 검수 완료 후 라벨/메타가 수정될 때 **작업 수정(TASK_MODIFIED)** 을 관제지원시스템에 단방향으로 통지한다(업데이트 통지 → 버전 증가). 동일 작업 ID를 유지하며(새 작업 ID 발급/버전 업 아님), 공통 필드 위에 수정 통지 필드(마지막 수정 일시·변경 프레임 ID·변경 종류)를 전달한다. 라벨/메타 본문은 미포함한다. **ControlNotifyClient `TaskModifiedPayload` 에 정합.**

<table>
<tr><td rowspan="2">인터페이스 번호</td><td colspan="5" align="center"><b>데이터송신시스템</b></td><td rowspan="2">송신 프로그램 ID</td><td colspan="5" align="center"><b>데이터수신시스템</b></td><td rowspan="2">수신 프로그램 ID</td><td rowspan="2">속성 설명</td></tr>
<tr><td>시스템명</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입(필수여부)</td><td>길이</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입(필수여부)</td><td>길이</td><td>시스템명</td></tr>
<tr><td rowspan="6">KLID-AT-II-008</td><td rowspan="6">학습데이터 저작도구</td><td rowspan="6">LS_RAW_DATA_STATUS (작업/검수 상태) · LS_DATA_LBL_HSTRY (라벨 변경 이력)</td><td>eventType (이벤트 종류)</td><td>string (필수)</td><td>-</td><td rowspan="6">업데이트통지발신</td><td rowspan="6">수정 통지 페이로드 (관제지원시스템 POST /api/v1/notify)</td><td>eventType (이벤트 종류)</td><td>string (필수)</td><td>-</td><td rowspan="6">관제지원시스템</td><td rowspan="6">업데이트통지수신</td><td>통지 유형 — TASK_MODIFIED 고정. 공통 필드.</td></tr>
<tr><td>RAW_DATA_ID (작업 식별자)</td><td>BIGINT (필수)</td><td>-</td><td>rawSn (작업 식별자)</td><td>long (필수)</td><td>-</td><td>영상 단위 작업 식별자(=LS_DATA_RAW.RAW_SN). 공통 필드.</td></tr>
<tr><td>UPD_DT (수정 일시)</td><td>TIMESTAMP (필수)</td><td>-</td><td>lastModifiedAt (마지막 수정 일시)</td><td>string (필수)</td><td>-</td><td>마지막 수정 일시(ISO-8601 Instant).</td></tr>
<tr><td>SRC_SN (프레임 식별자)</td><td>BIGINT (필수)</td><td>-</td><td>frameIds (변경 프레임 목록)</td><td>long array (필수)</td><td>-</td><td>변경된 프레임 ID 배열(LS_DATA_LBL_HSTRY.SRC_SN).</td></tr>
<tr><td>changeTypes (변경 종류 목록)</td><td>string array (필수)</td><td>-</td><td>changeTypes (변경 종류 목록)</td><td>string array (필수)</td><td>-</td><td>변경 종류 배열 — LABEL_ADDED/LABEL_UPDATED/LABEL_DELETED/META_UPDATED.</td></tr>
<tr><td>requestId (요청 ID)</td><td>string (필수)</td><td>36</td><td>requestId (요청 ID)</td><td>string (필수)</td><td>36</td><td>멱등 처리용 요청 식별자(UUID). 공통 필드.</td></tr>
</table>

> **동기 응답**: 관제지원시스템 → 저작도구. 수신 확인 결과 코드(VARCHAR). 동일 작업 ID를 유지(버전 업 아님)하며 수신측은 마지막 상태로 갱신한다. 라벨/메타 본문·개인정보는 미포함하고, 같은 트랜잭션·짧은 시간 내 다수 변경은 디바운스 후 1회 통지한다. 멱등 처리(requestId)·실패 재등록 큐·인계 토큰/IP 화이트리스트 보호.

#### KLID-AT-II-009 — YOLO 객체 탐지 (오토라벨링)

> **인터페이스 설명**: 작업자가 일일이 박스를 그리지 않아도 되도록, 영상에서 뽑아낸 한 장의 화면(프레임)을 AI 추론 서버에 보내면 **AI가 사람·차량 같은 물체를 찾아 네모 박스로 자동 표시**해 돌려준다(오토라벨링). 저작도구가 요청을 보내면 그 자리에서 결과를 바로 받는다(동기). **API: `POST /infer/yolo/predict`**. AI 추론 서버는 로그인·데이터베이스 없이 "그림 넣으면 결과만 내주는" 계산 전용 서버이며, 외부 호출이 느리거나 실패할 때를 대비해 저작도구가 재시도·타임아웃·차단(서킷브레이커)을 걸어 안전하게 호출한다.

<table>
<tr><td rowspan="2">인터페이스 번호</td><td colspan="5" align="center"><b>데이터송신시스템</b></td><td rowspan="2">송신 프로그램 ID</td><td colspan="5" align="center"><b>데이터수신시스템</b></td><td rowspan="2">수신 프로그램 ID</td><td rowspan="2">속성 설명</td></tr>
<tr><td>시스템명</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입(필수여부)</td><td>길이</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입(필수여부)</td><td>길이</td><td>시스템명</td></tr>
<tr><td rowspan="4">KLID-AT-II-009</td><td rowspan="4">학습데이터 저작도구</td><td rowspan="4">LS_DATA_SRC (프레임 이미지)</td><td>SRC_FILE_PATH_NM (프레임 경로)</td><td>VARCHAR (필수)</td><td>500</td><td rowspan="4">YOLO탐지</td><td rowspan="4">YOLO 추론 요청 (POST /infer/yolo/predict, 무상태)</td><td>image_b64 (프레임 이미지)</td><td>string (필수)</td><td>-</td><td rowspan="4">학습데이터 저작도구(AI 추론 서버)</td><td rowspan="4">객체탐지</td><td>원본 프레임 이미지 — 파일을 base64 인코딩해 전송.</td></tr>
<tr><td>conf_threshold (신뢰도 임계값)</td><td>number (선택)</td><td>-</td><td>conf_threshold (신뢰도 임계값)</td><td>number (선택)</td><td>-</td><td>탐지 신뢰도 임계값(0~1, 미지정 시 서버 기본값).</td></tr>
<tr><td>imgsz (입력 해상도)</td><td>int (선택)</td><td>-</td><td>imgsz (입력 해상도)</td><td>int (선택)</td><td>-</td><td>추론 입력 해상도(px, 기본 1280).</td></tr>
<tr><td>iou (IoU 임계값)</td><td>number (선택)</td><td>-</td><td>iou (IoU 임계값)</td><td>number (선택)</td><td>-</td><td>NMS IoU 임계값(기본 0.5).</td></tr>
</table>

> **동기 응답**: ai-server → 저작도구. 탐지 결과 목록 detections[label(라벨) · points(좌표 배열) · score(신뢰도)] + mock(모의응답 여부) + source(추론 소스). 결과는 LS_DATA_LBL 라벨로 반영한다. 무상태 추론(저장 없음).

#### KLID-AT-II-010 — YOLO 추적 (오토라벨링)

> **인터페이스 설명**: 앞 화면에서 찾은 물체가 **다음 화면에서 어디로 움직였는지 AI가 이어서 따라가며(추적)** 박스를 자동으로 붙여준다. 화면마다 새로 물체를 찾지 않고 같은 물체에 같은 번호를 유지해, 영상 전체에 라벨을 자동으로 퍼뜨린다(오토라벨링). **API: `POST /infer/yolo/track`**. 화면 그림, 어느 영상인지(클립 식별자), 몇 번째 화면인지(프레임 인덱스)를 함께 보낸다.

<table>
<tr><td rowspan="2">인터페이스 번호</td><td colspan="5" align="center"><b>데이터송신시스템</b></td><td rowspan="2">송신 프로그램 ID</td><td colspan="5" align="center"><b>데이터수신시스템</b></td><td rowspan="2">수신 프로그램 ID</td><td rowspan="2">속성 설명</td></tr>
<tr><td>시스템명</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입(필수여부)</td><td>길이</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입(필수여부)</td><td>길이</td><td>시스템명</td></tr>
<tr><td rowspan="6">KLID-AT-II-010</td><td rowspan="6">학습데이터 저작도구</td><td rowspan="6">LS_DATA_SRC (프레임) · LS_DATA_RAW (영상)</td><td>SRC_FILE_PATH_NM (프레임 경로)</td><td>VARCHAR (필수)</td><td>500</td><td rowspan="6">YOLO추적</td><td rowspan="6">YOLO 추적 요청 (POST /infer/yolo/track, 무상태)</td><td>image_b64 (프레임 이미지)</td><td>string (필수)</td><td>-</td><td rowspan="6">학습데이터 저작도구(AI 추론 서버)</td><td rowspan="6">객체추적</td><td>원본 프레임 이미지 — base64 인코딩 전송.</td></tr>
<tr><td>RAW_SN (영상 식별자)</td><td>BIGINT (필수)</td><td>-</td><td>clip_id (클립 식별자)</td><td>string (필수)</td><td>-</td><td>영상 고유값(clip_id = String.valueOf(rawSn)). 트래커 상태 격리 단위.</td></tr>
<tr><td>FRM_NO (프레임 번호)</td><td>INT (필수)</td><td>-</td><td>frame_index (프레임 인덱스)</td><td>int (필수)</td><td>-</td><td>프레임 인덱스.</td></tr>
<tr><td>conf_threshold (신뢰도 임계값)</td><td>number (선택)</td><td>-</td><td>conf_threshold (신뢰도 임계값)</td><td>number (선택)</td><td>-</td><td>탐지 신뢰도 임계값.</td></tr>
<tr><td>imgsz (입력 해상도)</td><td>int (선택)</td><td>-</td><td>imgsz (입력 해상도)</td><td>int (선택)</td><td>-</td><td>추론 입력 해상도.</td></tr>
<tr><td>iou (IoU 임계값)</td><td>number (선택)</td><td>-</td><td>iou (IoU 임계값)</td><td>number (선택)</td><td>-</td><td>NMS IoU 임계값.</td></tr>
</table>

> **동기 응답**: ai-server → 저작도구. 추적 반영된 탐지 결과 목록 detections[label · points · score] + mock + source. 무상태 추론(저장 없음).

#### KLID-AT-II-011 — SAM2 분할

> **인터페이스 설명**: 작업자가 물체를 **한 번 클릭하거나 네모로 감싸주면**, AI가 그 물체의 **정확한 외곽선(테두리)을 따라 오려내듯 영역을 잡아준다**(분할). 네모 박스보다 훨씬 정밀하게 물체 모양대로 라벨을 만들 수 있다. **API: `POST /infer/sam2/segment`**. 클릭 좌표(points) 또는 네모 범위(box) 중 하나는 반드시 보내야 한다. 단, AI 서버가 실제 계산 없이 흉내만 낸 임시 응답(mock)일 때는 화면에 자동으로 적용되지 않는다(잘못된 결과 방지).

<table>
<tr><td rowspan="2">인터페이스 번호</td><td colspan="5" align="center"><b>데이터송신시스템</b></td><td rowspan="2">송신 프로그램 ID</td><td colspan="5" align="center"><b>데이터수신시스템</b></td><td rowspan="2">수신 프로그램 ID</td><td rowspan="2">속성 설명</td></tr>
<tr><td>시스템명</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입(필수여부)</td><td>길이</td><td>데이터 저장소명</td><td>속성명</td><td>데이터타입(필수여부)</td><td>길이</td><td>시스템명</td></tr>
<tr><td rowspan="3">KLID-AT-II-011</td><td rowspan="3">학습데이터 저작도구</td><td rowspan="3">LS_DATA_SRC (프레임 이미지)</td><td>SRC_FILE_PATH_NM (프레임 경로)</td><td>VARCHAR (필수)</td><td>500</td><td rowspan="3">SAM2분할</td><td rowspan="3">SAM2 분할 요청 (POST /infer/sam2/segment, 무상태)</td><td>image_b64 (프레임 이미지)</td><td>string (필수)</td><td>-</td><td rowspan="3">학습데이터 저작도구(AI 추론 서버)</td><td rowspan="3">분할</td><td>원본 프레임 이미지 — base64 인코딩 전송.</td></tr>
<tr><td>points (클릭 좌표)</td><td>number array (조건부)</td><td>-</td><td>points (클릭 좌표)</td><td>number array (조건부)</td><td>-</td><td>클릭 좌표 배열 [[x,y],...] (box 와 택1).</td></tr>
<tr><td>box (박스 프롬프트)</td><td>number array (조건부)</td><td>-</td><td>box (박스 프롬프트)</td><td>number array (조건부)</td><td>-</td><td>박스 프롬프트 [x1,y1,x2,y2] (points 와 택1).</td></tr>
</table>

> **동기 응답**: ai-server → 저작도구(`Sam2SegmentResponse` 5필드). polygon(분할 폴리곤 좌표 배열 [[x,y],…] — 외각선) + score(신뢰도 0~1) + mock(모의응답 여부) + source("mock"|"model") + mock_reason(mock 사유 "env_mock"|"weights_missing"|"load_failed"). mock=true 응답은 FE 가 자동 적용하지 않는다(모델 미로드 경고). 무상태 추론(저장 없음).

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

- **인터페이스 설명**: 해당 인터페이스의 연동 목적과 처리 흐름(위탁·콜백·통지·조회 등)을 서술한다.
- **인터페이스 번호**: 송신 시스템의 인터페이스 일련번호를 기입한다.
- **데이터송신시스템 시스템명**: 송신 시스템명을 기술한다.
- **데이터송신시스템 데이터저장소명**: 인터페이스 송신과 관련된 엔티티 또는 파일명을 기술한다. 저작도구 내부는 실제 `LS_*` 테이블명(한글 설명 병기), 외부 시스템은 스키마 미소유로 논리 저장소명(시스템명 병기)으로 기술한다.
- **데이터송신시스템 속성명**: 관련 엔티티의 속성명 또는 파일의 항목명을 기술한다.
- **데이터송신시스템 데이터타입**: 엔티티 속성 또는 항목 타입을 기술한다.
- **데이터송신시스템 길이**: 데이터의 길이를 기술한다.
- **송신 프로그램 ID**: 송신에 해당하는 프로그램 ID를 기입한다.
- **데이터수신시스템 데이터저장소명**: 인터페이스 송수신과 관련된 엔티티 또는 파일명을 기술한다.
- **데이터수신시스템 속성명**: 관련 엔티티의 속성명 또는 파일의 항목명을 기술한다.
- **데이터수신시스템 데이터타입**: 엔티티 속성 또는 항목 타입을 기술한다.
- **데이터수신시스템 길이**: 데이터의 길이를 기술한다.
- **데이터수신시스템 시스템명**: 수신 시스템명을 기술한다.
- **수신 프로그램 ID**: 수신에 해당하는 프로그램 ID를 기술한다.
- **필수여부**: 해당 속성의 전달 필수 여부. `필수`(항상 전달) / `선택`(미지정 시 규격 기본값 적용) / `조건부`(특정 조건에서만 전달). 별도 컬럼 없이 **데이터타입 셀에 괄호로 병기**한다.
- **조합·파생 속성 표기**: 단일 컬럼이 아니라 조합/생성되는 속성(예: `project_name = "raw"+RAW_SN`)은 **속성명 셀에 조합 규칙을 병기**한다.
- **속성 설명**: 해당 속성이 담는 데이터의 의미와 처리상 용도를 기술한다. 구현된 인터페이스는 실제 API 필드명을 괄호로 병기한다.

## 부록 — 데이터 부재/매핑 사유

### 빈 셀(`-`) 사유

- **수신번호 (전 인터페이스)**: 상대 시스템의 인터페이스 채번 체계를 본 산출물 작성 시점에 보유하지 않음 — 외부 연동 확정 시 보완.
- **수신 상대 담당자 (외부 연동)**: 시스템 간 양방향 인증 연동이 미운영 상태로 외부 시스템 담당자가 미지정 — "미정 — 연동 시 확정" 표기. 콜백 수신(KLID-AT-II-004)의 수신측은 저작도구 검수 담당으로 처리. **비식별(II-001·II-002)은 예외로 KPST 실연동이 구현·운영되어 "연동 완료(KPST) — 담당자 성명 확인 필요"로 표기**한다(상대측 실무담당자 성명만 미확보).
- **데이터타입 길이 (대부분)**: 송수신이 데이터베이스 고정 길이가 아닌 구조화 메시지(JSON) 또는 파일 경로/이진 첨부 기반이라 단일 고정 길이가 성립하지 않음 — 길이 미상으로 `-` 표기(고정 길이 항목만 수치 기입).

### §2 속성 좌우 매핑 출처 (코드 실측)

§2 속성 표의 송·수신 속성명·데이터타입·길이는 아래 코드에 정합한다(임의 추정 없음).

- **외부/전송 필드(JSON)**: `KpstProjectRequest`·`KpstProgressRequest`(비식별), `VlmTimeseriesRequest`·`VlmResultRequest`(VLM), `ExternalAugmentClient#requestAugment`·`AugmentResultRequest`(증강), `TaskCompletedPayload`·`TaskModifiedPayload`(관제 통지), `YoloRequest`·`YoloTrackRequest`·`Sam2Request`(ai-server).
- **저작도구 DB 컬럼(LS_*)**: 엔티티 `@Column` 매핑 — `LsDeidentProcLog`(ORGNL_FILE_PATH_NM·DE_IDNTF_FILE_PATH_NM·REQ_ID·REG_ID·KPST_PRJ_ID), `LsMarking`(VIDEO_FILE_PATH_NM·EVNT_NM·MARK_CN), `LsDataMeta`(RAW_SN·META_KEY·META_VL·IDMP_KEY·OTSD_JOB_ID), `LsDataAug`(DATA_AUG_SN·AUG_TYPE_CD·AUG_PROC_STTS_CD·IDMP_KEY·OTSD_JOB_ID), `LsRawDataStatus`(RAW_DATA_ID·UPD_DT), `LsDataRaw`(RAW_SN·RAW_FILE_PATH_NM), `LsDataSrc`(SRC_FILE_PATH_NM·FRM_NO).
- **매핑 방향**: 아웃바운드(II-001·002·003·005·007·008·009·010·011)는 저작도구 LS 컬럼(VARCHAR/BIGINT/INT/TIMESTAMP) → 외부/전송 JSON 필드(string/int/number/long/array). 인바운드 콜백(II-004·006)은 외부 JSON 필드 → 저작도구 LS 컬럼. 대응 DB 컬럼이 없는 순수 요청 파라미터(files·masking_*·callbackUrl·conf_threshold·points·box 등)와 요청 시 생성값(requestId·eventType 등)·집계값(totalFrames·labeledFrames 등)은 대응 컬럼이 없으므로 송·수신에 **동일 전송 필드명**을 표기하고 저장소는 송신측 대표 저장소로 묶는다(전송 필드이지 특정 컬럼 매핑이 아님).
- **속성명 병기**: 모든 속성명은 `기술명 (한글 뜻)` 형식으로 병기한다 — DB 컬럼은 `ORGNL_FILE_PATH_NM (원본 영상 경로)`, JSON 필드는 `input_path (입력 경로)`.

### 관련 요구사항 ID 매핑 근거

- KLID-AT-II-001·002 ↔ RQ-SFR-09-01: 영상 비식별 처리(개인정보 보호).
- KLID-AT-II-003·004 ↔ RQ-SFR-08: 저작도구 핵심 기능(외부 VLM 시계열 연동).
- KLID-AT-II-005·006 ↔ RQ-SFR-07: 생성형 AI 외부 증강(WINTER/NIGHT/RAIN).
- KLID-AT-II-007·008 ↔ RQ-SFR-08-01: 관제서버 완료/수정 통지(작업 단위 완료·수정 통지 → 데이터셋 생성·버전 증가).
- KLID-AT-II-009·010·011 ↔ RQ-SFR-08: 저작도구 핵심 기능(오토라벨링 YOLO 탐지·추적, SAM2 분할).

> 위 매핑은 R1 사용자요구사항정의서의 실존 요구사항 ID를 근거로 한다. R1에 직접 대응 항목이 없으면 `-`로 두며 의사값을 채우지 않는다.

### 비식별(II-001·II-002) 코드 정합 사유

- 비식별 위탁·폴링은 KPST 비식별 솔루션과 실연동(구현·운영)되므로 명세의 속성·필수여부·속성 설명을 **실제 구현 코드**(`KpstDeidentifyClient`, `KpstProjectRequest`, `KpstProgressRequest`, 규격 정본 `docs/v2-wiki/22-deid-solution-api.md`)에 정합한다.
- **공유 마운트(no-copy) 모델**: 위탁은 원본 경로(`input_path`)를 `POST /project` 에 직접 참조시켜 업로드 단계가 없고, 결과는 KPST 가 결과 경로(`export_path`)에 직접 산출해 다운로드 단계도 없다. 따라서 II-001 명세에서 영상 파일 업로드(BYTEA)·`multipart` 전달을 두지 않고 전달방식을 `HTTP/REST(JSON)` 으로 표기한다. (정본 HWP의 II-001 비고 "업로드로 입력 경로 확보" 표현은 본 no-copy 모델로 정정)
- **선택 코드 필드**: `masking_type`·`db_save`·`masking_range`·`exp_quality`·`exp_format` 은 규격 기본값이 존재하는 선택 필드로 필수여부 `선택` 으로 표기한다(§22.4 부록 A 기본값).
- **폴링 주기**: 실구현 `kpst.deid.poll-interval-sec` 기본값 30초(`KpstDeidentPollTriggerConfig`)에 정합해 §1 발생빈도를 `1회/30초`로 표기한다. (정본 HWP의 II-002 `1회/1분` 은 코드 실측과 상이 — 운영 결정 시 설정값 조정 필요)

### 관제 통지(II-007·II-008) 코드 정합 사유

- 관제서버 완료/수정 통지는 `ControlNotifyClient`(단방향 outbound)로 실구현되어, 명세를 실제 페이로드 record 두 종(`TaskCompletedPayload`·`TaskModifiedPayload`)에 정합한다. **HWP 정본에 맞춰 완료 통지(II-007)·수정 통지(II-008)를 별개 인터페이스로 분리**한다.
- **완료 통지(II-007, TASK_COMPLETED)**: 공통 필드(`eventType`·`rawSn`·`requestId`) + `reviewerName`·`approvedAt`·`totalFrames`·`labeledFrames`. 전용 통지이므로 이벤트별 필드도 `필수`.
- **수정 통지(II-008, TASK_MODIFIED)**: 공통 필드 + `lastModifiedAt`·`frameIds`(Long Array)·`changeTypes`(String Array — LABEL_ADDED/UPDATED/DELETED·META_UPDATED). 동일 작업 ID 유지(버전 업 아님), 수신측은 마지막 상태로 갱신.
- 라벨/메타 본문·PII·토큰·원본(비-비식별) 이미지 경로는 페이로드에 포함하지 않는다(CWE-359). 관제서버는 통지 수신 후 저작도구 조회 API로 상세를 보강한다.

### AI 추론(ai-server) 내부 인터페이스 포함 사유

- 객체탐지·추적·분할 추론(YOLO/SAM2)은 저작도구가 자체 운영·배포하는 **내부 추론 인프라(ai-server)** 로, 인증·DB 없는 **무상태(stateless) 추론**만 수행한다. 저작도구(Spring Boot) ↔ ai-server 는 별도 프로세스로 HTTP/REST 호출되는 시스템 간 연동이라 인터페이스 설계서에 명시한다. 저작도구가 `AiServerClient`(Resilience4j 타임아웃·재시도·서킷브레이커)로 호출한다.
- **수신시스템명 표기**: ai-server 는 저작도구 서브시스템이므로 HWP 정본에 맞춰 `학습데이터 저작도구(AI 추론 서버)`로 표기한다(외부 시스템 아님).
- **엔드포인트**: II-009 `POST /infer/yolo/predict` · II-010 `POST /infer/yolo/track` · II-011 `POST /infer/sam2/segment`.
- ai-server 무상태 특성상 수신측 '데이터 저장소명'은 영속 저장소가 없어 "추론 입력 (무상태)"로 표기한다. 이미지 입력은 base64 문자열(길이 미상 `-`), 좌표(points/box/polygon)는 JSON 수치 배열이다.
- ai-server 는 `POST /infer/vlm/verify-objects`(VLM 객체 검증)·`POST /infer/sam2/track`(SAM2 추적)도 노출하나 본 Rev 1.1 목록에서는 **제외**한다(HWP 정본 정합). 시계열 VLM(II-003·004)과 SAM2 분할(II-011)로 핵심 흐름은 커버되며, SAM2 추적(VOS)은 추후 필요 시 별도 인터페이스로 보완한다.

### 외부 양방향 연동 정책 메모

- 관제서버/외부 학습데이터 시스템과의 양방향 통합(시스템 간 자동 연동)은 현재 미운영한다. 본 산출물의 관제 연동(KLID-AT-II-007·008)은 저작도구가 관제지원시스템으로 통지를 보내는 단방향 송신과, 관제서버가 상세를 가져가는 조회 인터페이스 제공으로 구성되며, 재구축 시 별도 설계가 필요하다.

### Rev 1.1 변경 요약 (기존 D4 v1.6 대비)

| 구분 | 기존 D4 v1.6 (12종) | Rev 1.1 (11종, HWP 정합) |
|---|---|---|
| 관제 통지 | II-007 관제통지 1종(완료+수정 통합, 조건부 필드) | **II-007 완료통지 + II-008 수정통지 2종 분리**(각 필드 필수) |
| 포털 DB Load | II-008 존재(RQ-SFR-19) | **제외** |
| AI 추론 | II-009~012 4종(YOLO 탐지·추적 + SAM2 분할·추적) | **II-009~011 3종**(SAM2 추적 제외) |
| AI 수신시스템명 | AI 추론 서버(ai-server) | **학습데이터 저작도구(AI 추론 서버)** |
| AI 수신 프로그램 ID | 객체탐지추론·객체추적추론·분할추론 | **객체탐지·객체추적·분할** |
| 관제 수신시스템명 | 관제서버 | **관제지원시스템** |
