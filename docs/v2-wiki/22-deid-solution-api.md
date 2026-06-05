# 22. 비식별화 솔루션 API 연동 명세 (KPST)

> 출처: ㈜한국플랫폼서비스기술(KPST) 『비식별화 솔루션 API 연동 방안』 v1.0 (2026.06.04) — 내부 개발 참조용 전사본.
> **원본 PDF는 git에 커밋하지 않음** (`.gitignore` 처리, 저작권 고지에 따라 외부 전재 금지).
> 관련: [08 비식별화](08-deidentification.md) · [07 배치 파이프라인](07-batch-pipeline.md) · [19 외부 시스템](19-external-security-cvat.md)

## 22.1 연동 개요

### 일정·지원
- **API 연동 테스트**: 협의 일로부터 **2주간 (2026년 6월 이내)**
- KPST에서 **테스트 서버 제공** + 테스트 기간 기술 지원

### 기능 협의 (추후)
- API 연동 테스트 이후 추가 인터페이스 기능 추후 협의
- **수동 비식별 프레임 선별 자동화** 기능 추후 협의

### 프로토콜·공통 규격
| 항목 | 내용 |
|------|------|
| 프로토콜 | HTTPS (TLS 1.2/1.3) |
| TLS 인증서 | **자체 CA 발급** — 클라이언트는 제공받은 `ca.crt`로 서버 검증 |
| 문자 인코딩 | UTF-8 |
| 컨텐츠 타입 | 기본 `application/json` / 파일 업로드 `multipart/form-data` / 파일 다운로드 파일 응답 |

### ⚠ 현행 저작도구 설계와의 갭 (Critical)

| 관점 | 현행 코드·위키 가정 ([08](08-deidentification.md)) | 실제 KPST API (본 명세) |
|------|--------------------------------------------|------------------------|
| 결과 수신 | 외부가 **콜백**(`POST /v1/deidentify/result`, HMAC + 멱등키) 푸시 | **콜백 없음** — `GET /retrieve_progress` **폴링** 후 `GET /download` |
| 작업 단위 | 영상 1건 위탁 요청 | **프로젝트**(영상 1~50개 묶음) 생성 후 일괄 처리 |
| 파일 전달 | 공유 경로 기반 위탁 | `POST /upload`(multipart)로 솔루션 서버에 직접 업로드 |
| 결과 파일 | 콜백 페이로드의 경로 | export_path 아래 `'원본명-mask.mp4'` 또는 `GET /download` 첨부 다운로드 |

→ `DeidentifyClient`·`DeidentifyStep`·`webhook/DeidentifyResultController`는 **폴링 어댑터로 재설계 필요** (별도 DEV 과제). 본 페이지는 명세 정본 역할.

## 22.2 API 엔드포인트 요약 (13종)

| Method | URL | 설명 |
|:------:|-----|------|
| GET | `/` | 서버 연결 확인 |
| POST | `/upload` | 영상 파일 업로드 |
| POST | `/project` | 프로젝트 생성 및 작업 등록 |
| POST | `/delete_project_name` | 프로젝트 삭제 (이름 기준) |
| POST | `/delete_project_id` | 프로젝트 삭제 (ID 기준) |
| GET | `/retrieve_progress` | 진행 상황 조회 |
| GET | `/retrieve_report` | 처리 결과 리포트 조회 |
| GET | `/manual_deid_info` | 수동 비식별화 대상 정보 |
| GET | `/manual_deid_info/project_id` | 수동 비식별화 프로젝트 ID 목록 |
| GET | `/manual_deid_info/project_name` | 수동 비식별화 프로젝트 이름 목록 |
| GET | `/dataset_frames` | 마스킹 프레임(이미지) 조회 |
| GET | `/download` | 마스킹 결과 파일 다운로드 |
| GET | `/retrieve_job_logs` | 작업 로그 조회 |

## 22.3 엔드포인트 상세

### 22.3.1 서버 연결 확인 — `GET /`

- 서버 동작 여부 확인용, 본문 없이 호출
- 응답: `"Connect"` (text/plain, HTTP 200)

### 22.3.2 파일 업로드 — `POST /upload`

비식별화 대상 영상 파일을 서버 업로드 디렉터리에 저장. 응답의 `inputPath`/`files`를 그대로 `/project` 요청에 사용. 요청 형식 `multipart/form-data`.

**요청 파라미터**
| 필드 | 타입 | 필수 | 설명 |
|------|------|:---:|------|
| `files` | file[] | △ | 업로드 파일(복수). `file` 또는 `files` 중 하나 사용 |
| `file` | file | △ | 업로드 파일(단일) |
| `subdir` | string | N | 저장 하위 폴더명(영문/숫자/한글). 미지정 시 기본 디렉터리 |

**요청 예시**
```bash
curl --cacert ca.crt -X POST https://<IP>:<Port>/upload \
  -F "files=@sample1.mp4" -F "files=@sample2.mp4" -F "subdir=projectA"
```

**응답 필드**
| 필드 | 타입 | 설명 |
|------|------|------|
| `data.inputPath` | string | 저장 경로(끝에 `/` 포함). `/project`의 `input_path`로 사용 |
| `data.files` | string[] | 저장된 파일명 목록 |
| `data.count` | int | 저장된 파일 수 |

**응답 예시**
```json
{
  "result":"success",
  "data":{ "inputPath":"/share/Deid-data/upload/projectA/",
           "files":["sample1.mp4","sample2.mp4"], "count":2 }
}
```

**오류 응답**
| HTTP | Message | 상황 |
|:----:|---------|------|
| 400 | No file part | 파일 필드 누락 |
| 400 | Invalid file type / Invalid characters | 확장자 또는 파일명 규칙 위반 |
| 400 | Too many files (max 50) | 파일 51개 이상 |
| 403 | Forbidden path | 허용 디렉터리 밖 경로 |
| 500 | Cannot create / Failed to save | 디스크 저장 실패 |

### 22.3.3 프로젝트 생성 — `POST /project`

프로젝트를 생성하고 파일별 데이터셋과 작업을 등록. **동일 이름 프로젝트가 있으면 409**. 요청 형식 `application/json`.

**요청 파라미터**
| 필드 | 타입 | 필수 | 설명 |
|------|------|:---:|------|
| `project_name` | string | Y | 프로젝트명(허용 문자 규칙 준수) |
| `creator` | string | Y | 생성자 ID |
| `export_path` | string | Y | 결과 내보내기 경로 |
| `input_path` | string | Y | 원본 파일 경로(끝에 `/` 포함) |
| `files` | string[] | Y | 파일명 목록(1~50, 영상 확장자) |
| `masking_type` | int | N | 마스킹/박스 타입 코드(기본 0) |
| `db_save` | int | N | DB 저장 여부 0/1(기본 0) |
| `masking_range` | int | N | 마스킹 범위 코드(기본 1) |
| `exp_quality` | int | N | 출력 화질 코드(기본 0) |
| `exp_format` | int | N | 출력 포맷 코드(기본 1) |

**요청 예시**
```json
{
  "project_name":"projectA", "creator":"user01",
  "export_path":"/share/Deid-data/export/projectA/",
  "input_path":"/share/Deid-data/upload/projectA/",
  "files":["sample1.mp4","sample2.mp4"],
  "masking_type":1, "db_save":1, "masking_range":1,
  "exp_quality":0, "exp_format":1
}
```

**응답 필드**: `result` (success/fail), `prj_id` (생성된 프로젝트 ID, 성공 시)
**응답 예시**: `{ "result":"success", "prj_id":279 }`

**오류 응답**
| HTTP | message | 상황 |
|:----:|---------|------|
| 400 | Missing field / invalid | 필수 필드 누락 또는 형식 오류 |
| 409 | Project already exists | 동일 이름 존재 |
| 500 | (내부 오류 메시지) | 서버/DB 오류 |

> ※ 내부적으로 저장 프로시저 `proc_di_make_project_and_join`(IN 11 + OUT 3)을 호출.

### 22.3.4 프로젝트 삭제 (이름) — `POST /delete_project_name`

프로젝트명으로 프로젝트와 하위 데이터셋/작업 삭제. `application/json`.

**요청**: `project_name`(string, Y), `user_id`(string, Y — 로그 기록용)
**예시**: `{ "project_name":"projectA", "user_id":"user01" }`
**응답 예시**: `{ "result":"success", "message":"Project 'projectA' deleted successfully" }`

**오류 응답**
| HTTP | message | 상황 |
|:----:|---------|------|
| 400 | Missing required fields | project_name/user_id 누락 |
| 500 | MySQL Error: ... | 프로시저/DB 오류 |

> ※ 이름→ID 변환 및 삭제는 DB 프로시저에서 수행. by-name 삭제 프로시저는 user_id 인자를 받도록 구성되어 있어야 함.

### 22.3.5 프로젝트 삭제 (ID) — `POST /delete_project_id`

프로젝트 ID로 프로젝트와 하위 데이터셋/작업 삭제. `application/json`.

**요청**: `project_id`(int, Y), `user_id`(string, Y)
**예시**: `{ "project_id":279, "user_id":"user01" }`
**응답 예시**: `{ "result":"success", "message":"Project '279' deleted successfully" }`
**오류**: 400 Missing required fields / 500 MySQL Error (위와 동일 패턴)

> ※ 내부적으로 `proc_di_drop_project(project_id, user_id)` 호출.

### 22.3.6 진행 상황 조회 — `GET /retrieve_progress`

조건에 맞는 프로젝트의 전체/파일별 진행률과 상태 조회. **요청 본문(JSON)으로 필터 전달, 필터 최소 1개 필요.**

**요청 파라미터**
| 필드 | 타입 | 필수 | 설명 |
|------|------|:---:|------|
| `reqUserId` | string | Y | 요청자 ID |
| `userId` | string | N | 생성자 ID 필터 |
| `prjName` | string | N | 프로젝트명 필터 |
| `prjId` | int | N | 프로젝트 ID 필터 |
| `startDate` | string | N | 시작일(YYYY-MM-DD) |
| `endDate` | string | N | 종료일(YYYY-MM-DD) |

**요청 예시**: `{ "reqUserId":"user01", "prjName":"projectA" }`

**응답 필드**
| 필드 | 타입 | 설명 |
|------|------|------|
| `data.prjCount` | int | 조회된 프로젝트 수 |
| `prjStatus[].prjId / prjName` | int/string | 프로젝트 ID / 이름 |
| `prjStatus[].progressRate` | float | 프로젝트 전체 진행률 |
| `prjStatus[].createTime/startTime/endTime` | datetime | 생성/시작/종료 시각 |
| `prjStatus[].createId / exportPath` | string | 생성자 / 내보내기 경로 |
| `prjStatus[].dsCount` | int | 데이터셋(파일) 수 |
| `dsStatus[].dsId / fileName` | int/string | 데이터셋 ID / 파일명 |
| `dsStatus[].procState` | int | 처리 상태 코드 |
| `dsStatus[].progressRate` | float | 파일별 진행률 |
| `dsStatus[].totalFrame` | int | 총 프레임 수 |
| `dsStatus[].startTime/endTime` | datetime | 처리 시작/종료 시각 |

**응답 예시**
```json
{ "result":"success", "data":{ "prjCount":1, "prjStatus":[
  { "prjId":279, "prjName":"projectA", "progressRate":42.5,
    "dsCount":2, "dsStatus":[
      { "dsId":1270, "fileName":"sample1.mp4", "procState":1,
        "progressRate":85.0, "totalFrame":5400 } ] } ] } }
```

**오류 응답**
| HTTP | message | 상황 |
|:----:|---------|------|
| 400 | reqUserId is required / invalid | 필수 누락 또는 형식 오류 |
| 400 | At least one filter parameter is required | 필터 조건 없음 |
| 404 | No projects found | 조회 결과 없음 |
| 500 | Internal server error | 서버/DB 오류 |

### 22.3.7 처리 결과 리포트 조회 — `GET /retrieve_report`

처리 완료(**state=2**)된 데이터셋에 대해 **얼굴/번호판 검출 집계** 포함 리포트 조회. 요청 본문(JSON) 필터.

**요청 파라미터**: `reqUserId`(Y), `userId`(N), `prjId`(N), `startDate`(N), `endDate`(N) — 진행 조회와 동일 패턴
**요청 예시**: `{ "reqUserId":"user01", "userId":"user01" }`

**응답 필드**
| 필드 | 타입 | 설명 |
|------|------|------|
| `data.prjCount` | int | 조회된 프로젝트 수 |
| `prjStatus[].progressRate` | float | 프로젝트 진행률 |
| `dsStatus[].dsId / fileName` | int/string | 데이터셋 ID / 파일명 |
| `dsStatus[].faceCount` | int | **얼굴 검출 수(전체-번호판)** |
| `dsStatus[].lpCount` | int | **번호판(license plate) 검출 수** |
| `dsStatus[].totalFrame` | int | 총 프레임 수 |
| `dsStatus[].startTime/endTime` | datetime | 처리 시작/종료 시각 |

**오류**: 400 reqUserId is required/invalid · 500 Internal server error
> ※ 완료된 데이터셋이 없는 프로젝트는 결과에서 제외된다.

### 22.3.8 수동 비식별화 대상 정보 — `GET /manual_deid_info`

DB 저장(`db_save=1`)이며 프로젝트 상태가 **수동 대상(state=3)**인 데이터셋 정보를 프로젝트 단위로 반환. 파라미터 없음.

**응답 필드**
| 필드 | 타입 | 설명 |
|------|------|------|
| `data[].project_id / project_name` | int/string | 프로젝트 ID / 이름 |
| `data[].creator_id` | string | 생성자 ID |
| `datasets[].dataset_id / file_name` | int/string | 데이터셋 ID / 파일명 |
| `datasets[].masking_table_name` | string | 마스킹 데이터 테이블명 |
| `datasets[].masked_frame_table_name` | string | 마스킹 프레임 테이블명 |

**오류**: 404 No matching records found · 500 (내부 오류 메시지)

### 22.3.9 수동 비식별화 프로젝트 ID 목록 — `GET /manual_deid_info/project_id`

수동 비식별화 대상 프로젝트의 ID 목록 반환. 파라미터 없음.
**응답 예시**: `{ "result":"success", "ids":[101, 205, 279] }`

### 22.3.10 수동 비식별화 프로젝트 이름 목록 — `GET /manual_deid_info/project_name`

수동 비식별화 대상 프로젝트의 이름 목록 반환. 파라미터 없음.
**응답 예시**: `{ "result":"success", "names":["projectA","projectB"] }`

### 22.3.11 마스킹 프레임 조회 — `GET /dataset_frames`

데이터셋의 마스킹된 프레임 이미지(**복호화 후 base64**)와 bbox 정보 조회. 쿼리스트링 사용.

**요청 파라미터**
| 필드 | 타입 | 필수 | 설명 |
|------|------|:---:|------|
| `dataset_id` | int | Y | 데이터셋 ID(숫자) |
| `frame_no` | string | N | 단일(`'120'`) 또는 범위(`'100-200'`) |

**요청 예시**: `GET /dataset_frames?dataset_id=1270&frame_no=100-200`

**응답 필드**
| 필드 | 타입 | 설명 |
|------|------|------|
| `data[].frame_no` | int | 프레임 번호 |
| `data[].image` | string\|null | 마스킹 이미지(base64). 없으면 null |
| `data[].bbox` | string | 바운딩 박스 정보 |

**오류 응답**
| HTTP | message | 상황 |
|:----:|---------|------|
| 400 | dataset_id is required / frame_no invalid | 파라미터 오류 |
| 404 | No matching frames found | 해당 프레임 없음 |
| 500 | (내부 오류 메시지) | 서버/DB 오류 |

### 22.3.12 마스킹 결과 다운로드 — `GET /download`

처리 완료(**state=2**)된 데이터셋의 마스킹 결과 파일을 첨부 파일로 다운로드. 결과 파일은 해당 프로젝트 `export_path` 아래 **`'원본명-mask.mp4'`** 형식으로 저장된 파일. 쿼리스트링.

**요청**: `dataset_id`(int, Y)
**요청 예시**: `GET /download?dataset_id=1270`
**응답**: (성공) HTTP 200, `application/octet-stream` — 파일 본문(attachment)

**오류 응답**
| HTTP | message | 상황 |
|:----:|---------|------|
| 400 | dataset_id is required | 파라미터 오류 |
| 403 | Processing not completed | 처리 미완료(state≠2) |
| 404 | Dataset not found / File not found on disk | 대상/파일 없음 |
| 500 | (내부 오류 메시지) | 서버/DB 오류 |

### 22.3.13 작업 로그 조회 — `GET /retrieve_job_logs`

작업 로그를 사용자/프로젝트/기간 조건으로 조회. 쿼리스트링. **시간 조건은 `start_time`, `end_time`을 함께 지정해야 적용**.

**요청 파라미터**
| 필드 | 타입 | 필수 | 설명 |
|------|------|:---:|------|
| `user_id` | string | N | 사용자 ID 필터 |
| `prj_name` | string | N | 프로젝트명 필터 |
| `start_time` | string | N | 시작 시각(YYYY-MM-DD HH:MM:SS) |
| `end_time` | string | N | 종료 시각(YYYY-MM-DD HH:MM:SS) |

**요청 예시**: `GET /retrieve_job_logs?user_id=user01&prj_name=projectA`

**응답 필드**
| 필드 | 타입 | 설명 |
|------|------|------|
| `data[].user_id / prj_name` | string | 사용자 / 프로젝트명 |
| `data[].dataset_message` | string | 데이터셋명 + 메시지 |
| `data[].state` | int | 상태 코드 |
| `data[].export_path` | string | 내보내기 경로 |
| `data[].time` | datetime | 로그 시각 |

**오류**: 400 Invalid time format · 404 No matching records found · 500 (내부 오류 메시지)

## 22.4 부록 A — 필드/코드 정의

요청에 사용되는 코드성 필드. **코드 값의 상세 매핑은 처리 파이프라인 설정을 따르며**, 상세 값 정의는 시스템 공통코드를 따름 — 연동에 필요한 코드표는 요청 시 별도 제공.

| 필드 | 기본값 | 설명 |
|------|:---:|------|
| `masking_type` | 0 | 마스킹/박스 타입 코드 |
| `masking_range` | 1 | 마스킹 적용 범위 코드 |
| `exp_quality` | 0 | 출력 화질 코드 |
| `exp_format` | 1 | 출력 포맷 코드 |
| `db_save` | 0 | 처리 결과 DB 저장 여부(0/1) |

### 주요 상태 코드 (코드 동작 기준)

| 대상 | 값 | 의미 |
|------|:---:|------|
| 데이터셋 처리 상태(state) | **2** | 처리 완료 — 다운로드/리포트 집계 대상 |
| 프로젝트 상태(state) | **3** | 수동 비식별화 대상(manual_deid_info) |

## 22.5 부록 B — 표준 연동 흐름

업로드부터 결과 다운로드까지 권장 호출 순서:

1. `POST /upload` — 영상 파일 업로드, 응답에서 `inputPath`/`files` 획득
2. `POST /project` — 위 `inputPath`/`files`로 프로젝트 생성, 응답에서 `prj_id` 획득
3. `GET /retrieve_progress` — `prjId` 또는 `prjName`으로 진행률 **폴링**
4. `GET /retrieve_report` — 완료 후 검출 집계 리포트 확인
5. `GET /download?dataset_id=…` — 완료(state=2)된 결과 파일 다운로드

## 22.6 저작도구 연동 시사점 (내부 메모 — 명세 외)

- **폴링 주기 설계 필요**: 배치 파이프라인([07](07-batch-pipeline.md))의 비식별 단계가 `retrieve_progress` 폴링 + `procState` 판정으로 완료를 감지해야 함 (Quartz 잡 또는 Step 내 폴링 루프 + 타임아웃)
- **수동 비식별 연계**: `manual_deid_info`(state=3) + `dataset_frames`(base64+bbox)는 [08 비식별 누락 신고](08-deidentification.md)의 수동 비식별 워크플로(외부 솔루션 수동 처리)와 연결 가능
- **검출 집계 활용**: `retrieve_report`의 faceCount/lpCount는 비식별 처리 이력(`LS_DEIDENT_PROC_LOG`) 기록에 활용 가능
- **TLS 자체 CA**: `DeidentifyClient` WebClient에 `ca.crt` 신뢰 저장소 구성 필요 (시스템 기본 신뢰 체인 아님)
- **저작도구 RAW_SN ↔ 솔루션 prj_id/dataset_id 매핑 테이블** 필요 (프로젝트=영상 묶음 단위이므로 1:N 주의)
