---
logicraft_item: INTSPEC-001
type: integration_spec
version: 10
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-03T07:39:21.621Z
status: CHANGED
prev_version: 9
content_hash: 2aead0203e85a0c5b5b8c43ce035427e85b78bb652cccc529808028fb9b621e0
stale: false
raw: ./_raw/INTSPEC-001.json
links:
  references: ["[[INT-004]]"]
  references_backward: ["[[INT-004]]", "[[INT-005]]"]
---

# KPST 비식별 솔루션 API 규격 (실측 보정본)

## status

draft

## version

v1.2

## spec_kind

markdown

## attached_files

_(empty)_

## change_summary

벤더 규격서 v1.0(2026-06-17, docs/비식별화 솔루션 API 연동 테스트.pdf) 요약 + 2026-06-18 실서버 직접 호출 검증 보정. procState·GET 바디 등 문서↔실서버 불일치 명시.

## content_inline

# KPST 비식별 솔루션 API 규격 (요약 + 실측 보정)

**본 ITEM 은 벤더 규격의 요약 + 실서버 실측 보정 델타다** — 13종 엔드포인트 전체·필드/코드 정의·표준 흐름은 아래 출처의 벤더 규격서 원문을 따른다.
출처: 벤더 『비식별화 솔루션 API 연동 테스트』 v1.0(2026-06-17) + 실서버 직접 호출 검증(2026-06-18) + 공유 마운트 단일화 확정(2026-06-30).

## 통합 모델 — 공유 마운트(shared-mount, no-copy) 단일화 (2026-06-30 확정)
저작도구는 **핵심 4종만** 사용:
- GET `/` — 연결확인(응답 `Connect`)
- POST `/project` — 프로젝트 생성(body: project_name·creator·export_path·input_path·files[]·masking_type·db_save 등. 응답 `{result, prj_id}`. 내부 proc_di_make_project_and_join)
- GET `/retrieve_progress` — 진행조회(⚠ GET이나 JSON 바디 필수: reqUserId+prjId)
- POST `/delete_project_id` — 삭제(body: project_id·user_id. 내부 proc_di_drop_project)

입력 = `input_path`(원본 부모 디렉터리, 공유마운트 READ) + `files[]` 직접 참조 → **업로드 없음**. 출력 = KPST가 `export_path`(`{STORAGE_DEIDENTIFIED_PATH}/videos/{rawSn}/`)에 직접 산출 → **다운로드 없음(no-copy)**. 완료 응답 `dsStatus.fileName`을 경로정화(CWE-22) 후 `DE_IDNTF_FILE_PATH_NM` 기록.

## 벤더 API 전체 = 13종 (정본 §22.2)
`/` · `/upload` · `/project` · `/delete_project_name` · `/delete_project_id` · `/retrieve_progress` · `/retrieve_report` · `/manual_deid_info`(+`/project_id`·`/project_name`) · `/dataset_frames` · `/download` · `/retrieve_job_logs`.
→ **`/upload`·`/download`는 KPST 서버에 존재하나 저작도구 미사용**(공유 마운트 모델). **`/retrieve_report`는 실제로 연동돼 있다** — 위 핵심 4종에 더해 사용 중이며, 이 응답이 `LS_DEIDENT_PROC_LOG`의 리포트 회수 축(얼굴·번호판 검출 수·프레임 수·처리 시작/종료 일시·결과 파일 경로) 6컬럼의 원천이고 `VideoDetailResponse` API 응답으로도 나간다. 수동비식별·프레임조회·로그는 현재 미연동(정본에 레퍼런스 보존).

## 실측 보정 (2026-06-18 실서버)
- 완료 판정: **procState=`2`**(progressRate 100·endTime 세팅). 미시작=null. ⚠ 벤더 문서 표(3=완료)와 불일치 — **벤더 확인·정정 필요**. 코드 PROC_STATE_COMPLETED=2.
- `/retrieve_progress`: 쿼리 전용 3종 모두 400, **JSON 바디만 200**. 일반적인 HTTP 클라이언트는 GET 요청에 바디를 싣지 않으므로, 바디를 실을 수 있는 저수준 HTTP 클라이언트로 Content-Length 를 명시해 전송한다.
- 처리 예: 1.mp4(totalFrame 1543) 약 13초 완료.

## 전송·인증
base-url 스키마로 **http(내부망 IP 평문)/https(자체 CA TLS)** 자동 분기. https만 ca-cert 필수(fail-closed, CWE-295 hostname 검증). 클라이언트 토큰/API키 없음. 운영은 내부망 http://IP:port 유력.

## 매핑·구성
`LS_DEIDENT_PROC_LOG`(V64: DE_IDNTF_PJT_ID/DE_IDNTF_DATST_ID/POLL_STTS_CD). 비식별 위탁 연동 + 진행상태 폴링 배치(Quartz 30초 주기). `kpst.deid.enabled` 킬스위치(기본 true). ⚠ **[폐기 · 아래 정정]** local/dev `mock-mode=true`(원본 복사 mock, KPST 미호출), stg/prd 공유 마운트 실연동. 레거시 동기 SPI 콜백 경로 제거(폴백 없음).

⚠ **[폐기] 위 「local/dev `mock-mode=true`(원본 복사 mock, KPST 미호출)」** — 한 문장에 두 가지가 함께 사실과 다르다. ① **「원본 복사」 경로가 존재하지 않는다** — 이 모드에는 **자체 산출 경로가 없다**. 그 경로는 폐지됐고, 지금 이 모드에 남은 것은 **판정뿐**이다. ② **활성화 실태도 다르다** — 이 모드는 **어느 프로파일에서도 켜져 있지 않다**(전 프로파일 미설정 = 꺼짐). 따라서 이 모드로 갈리는 환경은 실제로 없으며 **전 환경이 실제 위탁 경로를 탄다**. 구 서술을 지우지 않고 무엇이 사실인지를 함께 남긴다.

⚠ 같은 문장의 **「stg/prd 공유 마운트 실연동」은 그대로 유효**하다 — 폐기 범위는 앞의 local/dev 절뿐이니 함께 지우지 말 것.

**대체 규칙 — 위탁 요청층 거부.** 막을 산출 지점이 실재하지 않으므로, 남은 토글은 **위탁 요청층에서 거부**한다. 그 형상에서 비식별은 **전건 실패**하며 그 사실이 기동 기록과 상태로 드러난다.

★ **되살아날 때를 위한 경고.** 자체 복사 산출 경로가 다시 생기면 **이 배선으로는 막히지 않는다**(그 경로는 외부 요청을 타지 않는다). 그런 경로를 다시 만든다면 **산출 지점에 별도 차단을 함께** 넣어야 한다.

## effective_date

2026-06-30

## integration_point

INT-004
