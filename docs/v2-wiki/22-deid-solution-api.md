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
| 프로토콜 | **전송: 내부망 http(IP) 또는 https+사설CA — base-url 스키마로 자동 분기.** https 만 ca-cert 필수 |
| TLS 인증서 | https 일 때 **자체 CA 발급** — 클라이언트는 제공받은 `ca.crt`로 서버 검증. 내부망 평문 http(IP:port) 배포 시 ca-cert 불요 |
| 문자 인코딩 | UTF-8 |
| 컨텐츠 타입 | 기본 `application/json` / 파일 업로드 `multipart/form-data` / 파일 다운로드 파일 응답 |

### 실제 KPST API 특성 (Critical)

| 관점 | 저작도구 실제 연동 (공유 마운트 모델) |
|------|------------------------|
| 결과 수신 | **콜백 없음** — `GET /retrieve_progress` **폴링** 후 산출물 경로를 **응답 `dsStatus.fileName` 으로 회수**(다운로드 없음) |
| 작업 단위 | **프로젝트**(영상 1~50개 묶음) 생성 후 일괄 처리. 저작도구는 영상 1건=프로젝트 1개로 운영 |
| 파일 전달 | **업로드 없음** — `POST /project` 에 `input_path`=원본 디렉터리(공유 마운트), `files`=[원본 파일명] 을 직접 참조 |
| 결과 파일 | KPST 가 `export_path` 에 직접 산출. 저작도구는 `export_path`=`{STORAGE_DEIDENTIFIED_PATH}/videos/{rawSn}/` 로 지정해 **우리 저장소에 바로 쓰게** 하고, 완료 응답 `fileName` 으로 그 경로를 `DE_IDNTF_FILE_PATH_NM` 에 기록(복사 없음) |

→ ✅ **공유 마운트 단일 모델 확정(2026-06-30)**: 저작도구의 KPST 연동은 **공유 마운트 경로 참조**(『API 연동 테스트』 v1.0 2026.06.17, 테스트 서버 기준)로 단일화됐다. `KpstDeidentifyClient` 는 **핵심 4 엔드포인트(`GET /`·`POST /project`·`GET /retrieve_progress`·`POST /delete_project_id`)** 만 사용한다(⚠ 2026-09-17 정정 — `delete_project_id` 는 클라이언트에 메서드만 있고 **호출처가 0건**이다. 재위탁 전 이전 프로젝트 삭제는 두지 않는다 → 22.3.3 「저작도구 프로젝트 이름 규칙」) — `POST /upload`·`GET /download` 는 KPST 서버에 존재하나 **저작도구는 사용하지 않는다**(공유 마운트로 입력 참조·결과 직접 산출). `KpstDeidentService`/`KpstDeidentTxService` + `KpstDeidentPollJob`(Quartz)가 비식별 확정 경로다.
- **입력**: `input_path` = 원본(`rawFilePathNm`, 관제 NAS 절대경로)의 부모 디렉터리(끝 `/`) — KPST 가 공유 마운트에서 READ.
- **출력(no-copy)**: `export_path` = `{STORAGE_DEIDENTIFIED_PATH}/videos/{rawSn}/`(submit 전 기존 산출물 정리 `cleanExportDir` + `createDirectories`) → KPST 가 결과를 우리 base 에 직접 WRITE. **결과 파일명 계약(실서버 실측 2026-07-21)**: 완료(`procState=2`) 응답 `dsStatus.fileName` 은 **원본 입력파일의 절대경로**(예 `/nas-storage-prod2/klid_at_test/raw/001.mp4`, = `input_path`+원본명)이며 **비식별 결과가 아니다**. 실제 산출물은 `export_path` 에 **`{원본stem}-mask{ext}`**(예 `001-mask.mp4`) 로 생성된다. 따라서 회수 경로 = `{base}/videos/{rawSn}/` + `{stem(basename(fileName))}-mask{ext}` 를 1차로 시도하고(외부값 → basename 추출로 경로 정화 CWE-22 + base 하위 단언), 미사용 시 export 디렉터리 스캔(단일 산출물)으로 폴백한 뒤 `DE_IDNTF_FILE_PATH_NM` 에 기록한다. 산출 경로가 base 하위라 스트리밍(`VideoStreamService.resolveSafe`)·프레임추출(`FfmpegFrameExtractor`, 컬럼 READ) 정합. **`/download` 는 미사용**(구 서술 "`-mask` 구성 없음"은 오기 — KPST 는 `-mask` 접미사로 산출한다).
- `kpst.deid.enabled` 토글은 킬스위치로 유지(기본 **true**). 레거시 동기 SPI(`DeidentifyClient`)·결과 콜백 수신 경로는 제거됐다(폴백 없음). stg/prd 는 공유 마운트 실연동이며, **local/dev 도 자체 채움이 아니라 목 서버(:9400)로 실제 HTTP 위탁**한다. ⚠ **구 서술 폐기(2026-09-03)** — *"local/dev 는 `authoring.integration.deidentify.mock-mode=true`(원본 복사 mock, KPST 미호출) 기본"*. **두 가지가 동시에 거짓이다** — ①그 복사 경로는 폐지됐다(그 모드에는 **자체 산출 경로가 없다** — 남은 자리는 **판정뿐**이다) ②그 설정 키는 **어느 프로파일에도 설정돼 있지 않다**(꺼짐). 막을 산출 지점이 실재하지 않으므로 남은 토글은 **위탁 요청층에서 거부**한다 — 그 형상에서 비식별은 **전건 실패**하며 기동 기록과 상태로 드러난다. ⚠ **되살아나면 이 배선으로는 막히지 않는다** — 다시 만든다면 **산출 지점에 별도 차단**이 필요하다. 매핑은 `LS_DEIDENT_PROC_LOG`(V64 도입, V83 rename: DE_IDNTF_PJT_ID/DE_IDNTF_DATST_ID/POLL_STTS_CD). 본 페이지는 명세 정본 역할.

> ※ §22.3.2(`/upload`)·§22.3.12(`/download`)·§22.5(업로드 표준 흐름)는 **KPST 서버 API 레퍼런스로 보존**하되, 저작도구는 이를 호출하지 않는다(공유 마운트 모델). 구 업로드 모델(`/upload`→`/project`→`/download`)은 폐기됐다.

> 참고 — HMAC 웹훅 인프라(`HmacWebhookFilter`/`HmacSigner`)와 VLM(`/v1/vlm/callback`) 콜백은 **그대로 유지**된다. 증강 콜백은 2026-07-27 Phase 7-A2 에서 무서명 `/v1/genai/callback` 으로 교체됐다. 비식별 전용 콜백 경로(`/v1/deidentify/result`)와 `webhook.hmac.secret.deidentify` 설정 키만 제거되었다.

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

> **★저작도구 프로젝트 이름 규칙 (2026-09-17 사용자 확정 · `INT-004` v18 정본)** — 그 영상의 **첫 위탁은 `raw{영상번호}`**,
> **다시 위탁할 때(선두 비식별 재시작 · 검수완료 재비식별)는 `raw{영상번호}r{이번 회차 비식별 이력 번호}`**(예 `raw44r57`, 영문·숫자만).
> 불변식은 **KPST 에 같은 이름을 두 번 보내지 않는다**이다. 이름이 `raw{영상번호}` 고정이던 때는 위탁이 한 번 나간 영상의
> 재위탁이 **영원히 409** 였다(246 실측 — 재시작 2회차가 `Project 'raw44' already exists` 로 거부).
> ⚠ 기각안: 재위탁 전 이전 프로젝트 삭제 — 확인 응답 누락처럼 이전 번호를 모르는 경우 불가하고 외부 산출물·이력이 함께 지워진다.

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

조건에 맞는 프로젝트의 전체/파일별 진행률과 상태 조회. **GET 요청이라도 JSON 바디로 필터를 강제하며(필터 최소 1개 필요), 쿼리 파라미터 전용 호출은 `400 (Invalid JSON body)`로 거부됨이 실서버에서 확인됨.** 클라이언트(`KpstDeidentifyClient.retrieveProgress`)는 `reqUserId`/`prjId`를 JSON 바디로 전송한다.

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
| `dsStatus[].procState` | int | 데이터셋 처리 상태 코드(프로젝트 `prjState` 0~6 과 별개 도메인). **실서버 빌드 기준 완료=`2`(아래 §22.4 문서 표와 상이). 미시작 시 `null` 반환.** 저작도구 판정(`KpstDeidentService`): 완료=`2` / 진행중=`null`·`0`·`1` / **터미널 실패=`3`(중지)·`4`(삭제중)·`99`(오류)** → 즉시 `DE_IDENT_YN='F'` fast-fail. 구 `{4,5,6,99}`(prjState 값 오혼용) 폐기 |
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

- ✅ **공유 마운트 입출력(구현, no-copy)**: `submit` 은 `input_path`=원본 부모 디렉터리·`export_path`=`{STORAGE_DEIDENTIFIED_PATH}/videos/{rawSn}/` 로 `POST /project` 직접 참조(업로드 없음). 완료 시 응답 `dsStatus.fileName`(경로 정화 CWE-22)으로 산출 경로를 `DE_IDNTF_FILE_PATH_NM` 에 기록(다운로드·복사 없음). `/upload`·`/download` 미사용. 완료 분기의 회수/정화 실패와 타임아웃은 비-REDEIDENT `failPolling`/REDEIDENT `failRedeidentCompletion`(작업락 해제 포함)으로 **terminal 종결**해 무한 재폴링·락 영구잠금을 차단
- ✅ **`POST /project` 제출은 논블로킹(구현, 2026-07-30)**: `KpstDeidentifyClient.createProject` 가 `Mono<KpstProjectResponse>` 를 반환하고 `KpstDeidentService` 는 **구독만 하고 즉시 반환**한다 — 수락 응답(`prj_id`) 왕복을 어느 스레드도 기다리지 않는다(구 `blockOptional(45s)` 폐기). 위탁 원장은 제출 **앞**에서 `POLL_STTS=WAITING` + `DE_IDNTF_PJT_ID=null` 로 선커밋되고, ACK 수신 시 완료 핸들러(`KpstSubmitOutcomeRecorder`, 전용 풀)가 `prj_id` 를 확정한다. 활성 트랜잭션이 있으면 **커밋 후** 구독하며, 롤백이면 제출을 개시하지 않고 원장만 `KPST_SUBMIT_CANCELED` 로 취소 종결한다(영상 상태 불변) → [08 §8.2-1](08-deidentification.md)
- ✅ **폴링 주기(구현)**: 배치 파이프라인([07](07-batch-pipeline.md))의 비식별 단계가 `retrieve_progress` 폴링 + `procState`(state=2) 판정으로 완료를 감지 — `KpstDeidentPollJob`(Quartz, `@DisallowConcurrentExecution`, 최대 시도 240회/180분 타임아웃 → 타임아웃 시 `DE_IDENT_YN='F'`)로 구현됨. `prj_id` 가 아직 없는 건은 **ACK 대기 유예**(`kpst.deid.submit-ack-grace-sec`, 기본 180초) 안에서는 진행조회를 부르지 않고 시도 카운터도 소모하지 않으며, 유예 초과 시 폴러가 `KPST_ACK_MISSING` 으로 회수한다(별도 스위퍼 없음)
- ⏳ **수동 비식별 연계(후속, 미구현)**: `manual_deid_info`(state=3) + `dataset_frames`(base64+bbox)는 [08 비식별 누락 신고](08-deidentification.md)의 수동 비식별 워크플로(외부 솔루션 수동 처리)와 연결 가능 — 추후 협의
- ✅ **검출 집계 활용(구현, R14, 2026-08-11)**: 폴링 완료(state=2) 직후 `GET /retrieve_report`를 1회 조회해 `dsStatus[].faceCount`/`lpCount`/`totalFrame`/`startTime`/`endTime`/`fileName`을 `LS_DEIDENT_PROC_LOG` 신규 컬럼 6종(V184)에 기록한다. 리포트 조회 실패·미매칭은 완료 흐름을 막지 않고 값을 `null`로 남긴다(WARN) → [08 §8.3.1](08-deidentification.md)
- ✅ **전송 스키마 분기(구현)**: `KpstWebClientConfig`가 base-url 스키마로 자동 분기 — **내부망 `http://IP:port`(평문, 격리 전제, 기동 시 1회 WARN) 또는 `https`+사설CA 둘 다 지원**. https 만 `ca.crt` 필수(없으면 fail-closed, CWE-295), http 는 ca-cert 불요. `ftp`/스키마 없음은 거부
- ✅ **TLS 자체 CA(구현, https 경로)**: https 일 때 `KpstWebClientConfig`가 `ca.crt` 신뢰 저장소를 구성한 전용 WebClient 제공 (시스템 기본 신뢰 체인 아님, hostname 검증 유지). 이 경로의 TLS 신뢰·hostname 검증·fail-closed 는 약화 없음
- ✅ **RAW_SN ↔ 솔루션 prj_id/dataset_id 매핑(구현)**: `LS_DEIDENT_PROC_LOG`에 V64 마이그레이션으로 `DE_IDNTF_PJT_ID`/`DE_IDNTF_DATST_ID`/`POLL_STTS_CD` 컬럼 추가(V83 rename: KPST_PRJ_ID→DE_IDNTF_PJT_ID, KPST_DATASET_ID→DE_IDNTF_DATST_ID). 영상 1건=KPST 프로젝트 1개로 운영(1:1)
