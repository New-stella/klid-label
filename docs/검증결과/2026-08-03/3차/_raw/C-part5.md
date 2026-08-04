# C 클러스터 part5 — C-3. TC-SAM2 (SAM2 분할/트랙 프록시) 전수 검증

- **회차**: 2026-08-03 3차
- **담당 범위**: `docs/test-cases/C-marking-labeling.md` C-3 절 (검증 착수 시점 223~268행, TC-SAM2-01~35 **35건**)
- **검증 방식**: 풀스택 실동작(backend `localhost:18081` + ai-server `:9300` + mock-server `:9400` + PostgreSQL) 우선, 실동작 재현 불가분만 자동테스트/정적 대조
- **사용 데이터**: `_raw/pipeline-drive.md` 공용 정상 데이터 **rawSn=101 / srcSn 468~477**(WORKER 2001 배정), 파생영상 **rawSn=18 / srcSn 45~74**(`SRC_FILE_PATH_NM` NULL), 신고구간 **rawSn=900 / srcSn 429~433**(`DE_IDENT_YN='F'`)
- **토큰**: `POST /v1/dev/tokens` — WORKER(userNo 2001) / REVIEWER(userNo 1001)
- **금지사항 준수**: 프로덕션·테스트·설정 코드 **무수정**. 빌드/테스트 **미실행**. 카탈로그는 담당 라인범위 내에서만 Edit. DB 는 TC-SAM2-06 경로순회 재현을 위해 `ls_data_src.src_sn=46` 1행을 일시 변경 후 **즉시 원복**(원복 확인 완료).

---

## 0. 환경 전제 (판정에 직접 영향)

| 항목 | 실측 | 판정 영향 |
|---|---|---|
| ai-server `/app/weights` | **빈 디렉터리**(0 files) | **YOLO 는 `weights_missing` mock**. 단 **SAM2 는 mock 이 아니다** — `sam2_loader.py:44-48` 이 HuggingFace `SAM2ImagePredictor.from_pretrained(model_id)` 로 **HF 캐시에서 로드**하며 `/app/weights` 를 쓰지 않는다. 실측 응답 `source="model"`, `score=0.9652`(mock 고정값 0.95/0.9 아님) → **실모델 동작 확인** |
| SAM2 mock 유도 방법 | 마스크가 안 잡히는 프롬프트(`box:[10000,10000,10001,10001]` / 역박스 / 화면 밖 `prevPolygon`) → ai-server 가 `mock_reason=empty_mask` 로 폴백 | **mock 게이트를 실동작으로 검증할 수 있었다**(정적 추정 아님) |
| 자동테스트 baseline | `_raw/test-baseline.md` — backend 4,152 / 실패 0 / 에러 0 | 테스트 근거로 인용한 케이스는 baseline 통과분 |

---

## 1. 판정 결과표

> 판정 토큰: PASS / FAIL / PARTIAL / BLOCKED / N/A / 확인필요 (§7 표기 규칙 준수)

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-SAM2-01 | PASS | [실동작] `POST /v1/frames/468/sam2-segment {"points":[[50,50]]}` → **200** `{"polygon":[[76,9],…9점],"score":0.9652,"empty":false}`, `message:null`. **클릭 1점이 400 으로 죽지 않음**(회귀 가드 성립). box 프롬프트도 200. DB 미저장(`ls_data_lbl` 신규행 0 — 서비스가 write 없음). [정적] `Sam2SegmentService.java:91-175`, 요청검증 `:97-99` |
| TC-SAM2-02 | PASS | [실동작] path 469 / body 468 → **400** `"path 의 srcSn 과 body 의 srcSn 이 다릅니다."` [정적] `LabelController.java:171-176` |
| TC-SAM2-03 | PASS | [실동작] points+box 동시 → **400** `"exactlyOnePrompt: points 또는 box 중 정확히 하나만 제공해야 합니다."` [정적] `Sam2SegmentRequest.java:46-51` |
| TC-SAM2-04 | PASS | [실동작] 둘 다 미전송 → **400** 동일 메시지(`hasPoints ^ hasBox`) |
| TC-SAM2-05 | PASS | [실동작] WORKER 2001 → 미배정 srcSn 429 → **403** `"본인에게 배정되지 않은 영상입니다."` [정적] `Sam2SegmentService.java:93` |
| TC-SAM2-06 | PASS | [실동작] `ls_data_src.src_sn=46` 을 `src_file_path_nm='../../../etc/passwd'`, `de_idntf_src_file_path_nm=NULL` 로 일시 변경 후 segment 호출 → **400** `"허용되지 않은 경로입니다."` (원복 완료). 서비스가 경로를 조립하지 않고 `FrameImageEncoder.resolveSafe`(`:220-229` `startsWith(baseDir)`)가 차단함을 확인. 회귀 가드 `FrameImageEncoderTest#경로_순회_시도는_INVALID_INPUT_차단` |
| TC-SAM2-07 | PASS | [실동작] srcSn 999999 → **404** `"프레임을 찾을 수 없습니다."` |
| TC-SAM2-08 | PASS | [정적+테스트] `Sam2SegmentService.java:114-118` → `ErrorCode.PAYLOAD_TOO_LARGE` = `HttpStatus.PAYLOAD_TOO_LARGE`(`ErrorCode.java:24`) = **413**. `Sam2SegmentServiceTest#이미지_크기_상한_초과시_400`(제목과 달리 실제 단언은 `PAYLOAD_TOO_LARGE`)이 `verify(aiServerClient, never()).segment(any())` 까지 고정. ※실동작은 `authoring.sam2.max-image-bytes`(기본 20MB)를 낮추려면 재기동이 필요해 미수행 — 프레임 이미지가 KB 급이라 자연 초과 불가 |
| TC-SAM2-09 | PASS | [실동작] mock 유도 `{"box":[10000,10000,10001,10001]}` → **200** `{"polygon":[],"score":0.0,"empty":true}` + `message:"AI 모델 미로드 — 결과 신뢰 불가"`. 역박스 `[100,100,10,10]` 도 동일. FE `OverlayLayer.tsx:611-616` 이 `polygon.length===0` 에서 **저신뢰 분기보다 먼저** 자동적용 차단 + `onMockWarning` 호출 → **FE 자동적용 차단 종단 확인**. ⚠ 안내 문구 정확도는 별건(C-ISSUE-83 아래) |
| TC-SAM2-10 | PASS | [테스트+정적] `Sam2SegmentService.java:182-185` → `EXTERNAL_API_ERROR`(502). `Sam2SegmentServiceTest#3점_미만_폴리곤_응답시_오류` 가 2점 응답 → `EXTERNAL_API_ERROR` 단언 |
| TC-SAM2-11 | PASS | [테스트+정적] `:186-198` `x>imgWidth‖y>imgHeight` → 502. `Sam2SegmentServiceTest#응답_폴리곤_좌표_이미지경계_초과시_오류`(100×100 이미지에 x=150). ※경계 상한이 segment 전용인 것은 확정 정책 방향(★3 AI 응답 축)과 별개의 **미적용 잔여**이며 카탈로그 오참조를 정정함(아래 §3) |
| TC-SAM2-12 | PASS | [실동작] `simplifyTolerance:60` → **400** `"simplifyTolerance: 경계 세밀함은 50.0 이하여야 합니다."` [정적] `Sam2SegmentRequest.java:35-36` |
| TC-SAM2-13 | PASS | [테스트+정적] `:166-169` 단순화 결과 <3점이면 `outPolygon = aiRes.polygon()`. `Sam2SegmentServiceTest#AI분할_simplify_결과가_3점미만이면_원본폴리곤유지`(tolerance 100 → 원본 5점 유지) |
| TC-SAM2-14 | PASS | [실동작] `POST /v1/frames/468/sam2-track` nextSrcSns=[469,470] → **200**, 프레임별 폴리곤 7점씩 2건, `score` 0.9834/0.9818(**프레임마다 다름 = 시드 복제 아님**). DB 미저장 |
| TC-SAM2-15 | PASS | [실동작] path 469 / body 468 → **400** [정적] `LabelController.java:137-142` |
| TC-SAM2-16 | PASS | [실동작] nextSrcSns 51개 → **400** `"nextSrcSns: size must be between 0 and 50"`. 경계 50개는 검증 통과 후 인가 단계 도달(403) → **상한이 정확히 50** [정적] `label/Sam2TrackRequest.java:33`. FE 도 `SAM2_TRACK_CHUNK_SIZE=50` 청크 분할(`features/label/api.ts:632,717`) |
| TC-SAM2-17 | PASS | [실동작] `nextSrcSns:[]` → **400** `"must not be empty"` |
| TC-SAM2-18 | PASS | [실동작] prevPolygon 2점 → **400** `"prevPolygon: size must be between 3 and 1000"` |
| TC-SAM2-19 | PASS | [실동작] prevPolygon 1001점 → **400** 동일 |
| TC-SAM2-20 | PASS | [실동작] trackId 65자 → **400** `"trackId: size must be between 0 and 64"` |
| TC-SAM2-21 | PASS | [실동작] 시작 468(배정) + 후속 429(미배정) → **403**. 루프 선두 `accessGuard.verifyAccess(nextSrcSn)`(`:99`)가 **그 프레임의 ai 호출 이전**에 판정 |
| TC-SAM2-22 | PASS | [실동작] 후속 srcSn 999999 → **404**. ⚠ 근거 드리프트 — 실측 메시지가 `"프레임을 찾을 수 없습니다."`(가드 문구)로, 카탈로그 근거 `:102` 의 `"후속 프레임을 찾을 수 없습니다: 999999"` 가 **아니다**. 카탈로그 정정함(아래 §3) |
| TC-SAM2-23 | PASS | [테스트+정적] `Sam2TrackService.java:233-236` = `validateResponseMinPoints`(502) **먼저** → `validatePolygon`(400). 좌표 형식 위반은 400 유지: `Sam2TrackServiceTest#sam2_track_ai응답폴리곤_검증실패시_400`(음수 좌표 3점 → `INVALID_INPUT`). 카탈로그의 "정점 수를 먼저 판정한다" 서술이 코드 순서와 일치 |
| TC-SAM2-24 | PASS | [실동작] `shape:"BBOX"` → **200** `points:[[40,8],[79,178]]` = 폴리곤 외접박스 [[minX,minY],[maxX,maxY]] |
| TC-SAM2-25 | PASS | [테스트+정적] `:153-159`(BBOX 만 `continue` 스킵) + `:206-208`(`MIN_BBOX_EXTENT=1.0`). `Sam2TrackServiceTest#추적_박스형태_퇴화폴리곤은_해당프레임만_스킵한다`. POLYGON 퇴화는 TC-SAM2-34(502)가 담당 |
| TC-SAM2-26 | PASS | [실동작] `shape:null` → **200** `shapeType:"POLYGON"` [정적] `:80` `shapeOrDefault()` |
| TC-SAM2-27 | PASS | [테스트+정적] `:114-123` catch → `EXTERNAL_API_ERROR`(502) + 예외 원문 미노출(LogSanitizer 서버로그 전용). `Sam2TrackServiceTest#sam2_track_ai호출실패시_502`·`#sam2_track_ai응답_polygon_null이면_502`. segment 대응: `Sam2SegmentInputValidationTest#segment_외부호출_실패시_내부URL이_노출되지_않는다` |
| TC-SAM2-28 | PASS | [실동작] trackId `"t\r\nINJECTED"` → **400** `"트랙 ID 는 영숫자와 . _ : - 만 사용할 수 있습니다."` — CRLF 는 `@Pattern`(`label/Sam2TrackRequest.java:29`)이 **선차단**하며 `LogSanitizer`(`:139-140`,`:171`)는 심층방어. 기대결과 보강(아래 §3) |
| TC-SAM2-29 | PASS | [실동작] REVIEWER 토큰 + srcSn 429(rawSn 900 `DE_IDENT_YN='F'`) → **412** `"비식별 재처리 대기 중인 영상은 AI 추론을 실행할 수 없습니다."`. `FrameImageEncoder.requireNotUnderDeidentReport`(`:206-217`)가 `resolveFrameImageWithoutGate` **앞**에서 판정 → 파일 read·base64 인코딩 0회. **역할 무관 차단 확인(REVIEWER 도 412)** |
| TC-SAM2-30 | PASS | [실동작] 동일 조건 track → **412** 동일 메시지. 시작·후속 프레임 인코딩이 모두 `encodeFrame`(`:178-180`) 경유 → 프레임마다 재판정. 회귀 가드 `AiInferenceDeidentReportGateTest`(SAM2 분할/추적/파생/YOLO 15건) |
| TC-SAM2-31 | PASS | [정적] `grep -rn "encodeToBase64" backend/src/main` → **0건**. `FrameImageEncoder` 의 public 진입점은 `resolveFrameImageForInference`·`encodeFrame`·`encodeDeidentifiedFrameForInference` 뿐이고 전부 `LsDataSrc` 를 받아 게이트를 통과한 뒤 private `encode(Path)`(`:190-197`)로 수렴. 경로 문자열 오버로드 없음. ⚠ 부수 발견 — `encodeDeidentifiedFrameForInference` 는 포털 SAM2 제거 이후 **프로덕션 호출부 0건**(아래 C-ISSUE-85) |
| TC-SAM2-32 | PASS | [정적] `resolveFrameImageWithoutGate`(`:94`)에 접근제어자 없음 = package-private. 유일 소비자 `FrameBoundsResolver.java:101` 은 `ImageIO.read` 로 **치수만** 읽고 픽셀 미유출. ⚠ 카탈로그·프로덕션 javadoc 의 "**패키지 외** 소비자" 서술은 사실 오류 — `FrameBoundsResolver` 는 `kr.co.cudo.authoring.label.service` 로 **동일 패키지**다. 카탈로그 정정함(§3), 코드 javadoc 은 C-ISSUE-86 |
| TC-SAM2-33 | PASS | [실동작] srcSn 45(rawSn 18 파생, `src_file_path_nm` **NULL**) → segment **200** 9점 폴리곤 / track **200** 7점. "이미지 경로가 비어있습니다"(400) 미발생 → `FrameImageEncoder:98-105` 비식별 우선 해석 성립 |
| TC-SAM2-34 | PASS | [테스트+정적] `Sam2CoordinateValidator.validateResponseMinPoints`(`:49-54`, `MIN_POLYGON_POINTS=3` `:31`) → `EXTERNAL_API_ERROR`. `Sam2TrackServiceTest#SAM2_track_응답_폴리곤_2점이면_502` + 과차단 회귀 가드 `#SAM2_track_응답_폴리곤_3점이면_정상` |
| TC-SAM2-35 | PASS | [실동작] ① segment `points:[[50,50]]` **200**(1점 클릭 정상) ② track `prevPolygon` 2점 **400** `@Size(min=3)`. 요청 축 400 ↔ 응답 축 502 분리 유지. [정적] 공용 `validatePolygon`(`Sam2CoordinateValidator.java:65-77`)에 최소 정점 수 **없음** 확인 |
| **TC-SAM2-36**(신설) | PASS | [실동작] 화면 밖 `prevPolygon:[[9000,9000],…]` 로 mock 유도 → **200** `{"tracked":[]}` + `message:"AI 모델 미로드 — 결과 신뢰 불가"`. **1차 C-ISSUE-81(HIGH) 실동작 해소 확인** — 구 동작이면 시드 폴리곤 2건이 `score 0.9` 로 반환됐어야 한다. 판정이 `AiMockMeta.untrusted`(`:50-52` 긍정 증명)라 mock 메타 생략 응답도 fail-closed. 가드: `AiMockMetaTest`(5) · `Sam2MockMetaDriftTest` · `Sam2TrackServiceTest` mock 4건 |
| **TC-SAM2-37**(신설) | PASS | [테스트+정적] `Sam2TrackService.java:136-144` 가 mock 프레임만 `continue` 하고 실결과는 유지, `:172` `Sam2TrackOutcome.of(...,anyMock)` → `PARTIAL_MOCK_MESSAGE`. `Sam2TrackServiceTest#추적_일부프레임만_mock이면_실결과는_유지하고_부분안내를_준다`·`#추적_실모델(mock아님)_응답은_그대로_자동적용되고_안내가_없다`. FE 청크 누적 `features/label/api.ts:745-746,:753-757` + `hooks/useSam2Track.ts:75`. ※특정 프레임만 mock 으로 유도할 결정적 수단이 없어 실동작 재현은 미수행 |

### 집계 (37건 = 원 35 + 신설 2)

| PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|:--:|:--:|:--:|:--:|:--:|:--:|
| **37** | 0 | 0 | 0 | 0 | 0 |

> 기대결과 단언 기준으로는 전건 통과다. 다만 **케이스 기대결과 바깥**에서 발견한 부수 결함·이월 미해소가 9건 있어 아래에 전건 기록한다(§2). "케이스가 PASS 라 문제 없음"으로 읽지 말 것.

---

## 2. 이슈 기록

> ⚠ **번호 충돌 주의**: 본 파트 이슈 번호는 **2026-08-03 3차 회차의 part5 배정 번호(C-ISSUE-81~)** 다.
> 2026-08-01 1차 ISSUES.md 의 동명 번호(C-ISSUE-81~86)와 **무관**하다. 1차 이슈와의 대응은 각 블록에 명시했다.

---

### [C-ISSUE-81] TC-SAM2-01 (부수) — `Sam2SegmentService` 의 `@Transactional` 이 여전히 남아 AI 블로킹 호출 내내 DB 커넥션을 점유한다 (1차 C-ISSUE-83 **미해소 이월**)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: SAM2 분할은 **DB write 가 없는 stateless 프록시**다(클래스 javadoc `Sam2SegmentService.java:41` — "BE 는 DB 저장하지 않는 stateless 프록시"). 파일 I/O + `ImageIO.read` + base64 + **최대 60s 블로킹 AI 호출** 구간에서 control HikariCP 커넥션을 쥐면 안 된다. 형제 서비스 `Sam2TrackService` 는 이미 이 이유로 트랜잭션을 제거했고 근거를 코드에 남겼다(`Sam2TrackService.java:38-39`: *"비트랜잭셔널(F-1 커넥션풀 고갈 방지) … AI 블로킹 호출 구간이 control HikariCP 커넥션을 점유하지 않는다"*). 프로젝트 규약도 동일하다(`CLAUDE.md` — *"프레임 이미지 서빙은 트랜잭션 밖에서 파일 I/O 를 한다 … 커넥션을 쥔 채 NAS I/O 를 하면 커넥션 기아로 간다 — 전례 있음"*).
- **현재 동작(이슈 내용)**: segment 만 트랜잭션이 남아 있고, 1차 지적 이후 코드가 바뀌지 않았다.
  ```java
  // backend/.../label/service/Sam2SegmentService.java:62-66
  @Slf4j
  @Service
  @RequiredArgsConstructor
  @Transactional(value = "controlTransactionManager", readOnly = true)   // ← 여전히 존재
  public class Sam2SegmentService {
      public Sam2SegmentResponse segment(...) {          // :91  프록시 경유 public
          accessGuard.verifyAccess(req.srcSn(), actor);  // :93  DB 접근 → 커넥션 획득
          …
          aiRes = aiServerClient.segment(aiReq).block(); // :130 최대 60s 블로킹
  ```
  **3차 실측(반증 재현)** — segment 6건 동시 호출 중 `pg_stat_activity` 샘플링:
  ```
   state               | count
   active              |     1
   idle                |     4
   idle in transaction |     6     ← 6건 전부 AI 호출 동안 커넥션 점유 (1차와 동일)
  ```
  대조군인 track 은 `idle in transaction` 0건이다(1차 실측, 코드 무변경).
- **재현/확인 경로**:
  ```bash
  W=$(<worker.token)
  for i in 468 469 470 471 472 473; do curl -s -o /dev/null -X POST \
    -H "Authorization: Bearer $W" -H 'Content-Type: application/json' \
    -d "{\"srcSn\":$i,\"points\":[[50,50]]}" \
    localhost:18081/api/v1/frames/$i/sam2-segment & done
  sleep 1.2
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select state, count(*) from pg_stat_activity where datname='klid_system' group by 1;"
  ```
- **영향**: 가용성/성능 — CWE-400 Uncontrolled Resource Consumption · OWASP API4:2023. 운영 풀은 `application-prd.yml` `maximum-pool-size: 20`(control/portal 각각). 라벨링 작업자 20명이 동시에 클릭 분할하면 control 풀이 소진되고, ai-server 가 느려질수록(60s 타임아웃) 점유 시간이 그대로 늘어나 **SAM2 와 무관한 API(라벨 저장·검수·배치)까지 동반 지연/실패**한다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. 클래스 `@Transactional` 제거 → DB 조회(`accessGuard.verifyAccess` + `srcRepository.findById` + 신고게이트 조회)만 별도 `@Transactional(readOnly)` 조회 빈으로 분리하고 **파일 I/O·`ImageIO.read`·base64·AI 호출은 트랜잭션 밖**에서 수행한다. 같은 목적의 선례 `FrameImageLookupService` 패턴을 그대로 따르면 된다. ⚠ 자기호출(self-invocation)로 프록시를 우회하면 효과가 없다. 회귀 가드는 `FrameImageServingHardeningTest` 와 동형으로 "SAM2 분할 빈에 `@Transactional` 이 없다"를 구조 단언으로 고정.

---

### [C-ISSUE-82] TC-SAM2-06 (부수) — `Sam2SegmentService` 클래스 javadoc 과 **공개 Swagger 설명**이 "SAM2 는 원본 이미지에만 실행"이라 실제 동작(비식별 우선)과 정반대다 (1차 C-ISSUE-84 ② **미해소 이월** + 신규 표면 1건)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 2026-07-30 확정 정책 — **라벨링 캔버스는 비식별 프레임을 서빙**하며 온라인(사용자 트리거) SAM2 도 같은 픽셀을 대상으로 해야 좌표가 맞는다. 문서·주석은 이 동작을 그대로 서술해야 한다. 특히 Swagger 설명은 **OpenAPI 로 외부에 나가는 계약 문서**라, 여기에 "원본 이미지에만 실행"이 적혀 있으면 관제/연동 상대가 저작도구가 비식별 전 픽셀을 AI 로 보낸다고 오해한다(개인정보 처리 방침 오기).
- **현재 동작(이슈 내용)**: 1차에서 dead code `resolveSafe` 삭제와 경로가드 javadoc(`:53-56`) 정정은 반영됐으나, **이미지 소스 정책 서술 2곳은 그대로 남았다.**
  ```java
  // Sam2SegmentService.java:43-45  (클래스 javadoc)
  * <p>이미지 소스 정책: CLAUDE.md "오토라벨링" 규칙에 따라 SAM2 는 <b>원본 이미지에만 실행</b>한다.
  * 원본과 비식별본은 동일 해상도이므로 좌표를 공유하며, 별도 비식별 추론은 수행하지 않는다.
  * (기존 {@link Sam2TrackService} 와 동일하게 {@code srcFilePathNm} = 원본 프레임 경로 사용.)

  // LabelController.java:155  (Swagger @Operation description — 신규 발견 표면)
  + "path srcSn 과 body srcSn 불일치 시 400 (CWE-345). 정책상 원본 이미지에만 실행."
  ```
  실제 코드는 정반대다 — `Sam2SegmentService.java:112` → `FrameImageEncoder.resolveFrameImageForInference` → `resolveFrameImageWithoutGate`(`:98-105`)가 **`DE_IDNTF_SRC_FILE_PATH_NM` 을 먼저** 검증·반환하고, 없을 때만 원본으로 폴백한다.
  **실동작 반증(결정적)**: srcSn 45(rawSn 18 파생 프레임)는 `SRC_FILE_PATH_NM` 이 **NULL** 인데 segment 가 **200 + 9점 폴리곤**을 반환했다 — 원본 경로만 쓴다면 물리적으로 불가능하다.
  ```
  psql> SELECT src_sn, src_file_path_nm IS NULL FROM ls_data_src WHERE src_sn=45;  →  45 | t
  curl POST /v1/frames/45/sam2-segment {"srcSn":45,"points":[[50,50]]}
    → 200 {"polygon":[[76,9],…],"score":0.9652,"empty":false}
  ```
- **재현/확인 경로**: `grep -n "원본 이미지에만" backend/src/main/java/kr/co/cudo/authoring/label/service/Sam2SegmentService.java backend/src/main/java/kr/co/cudo/authoring/label/controller/LabelController.java` → 2건. 위 srcSn 45 curl. Swagger 는 `GET /api/v3/api-docs` 의 `/v1/frames/{srcSn}/sam2-segment` description 에서 확인.
- **영향**: 유지보수·감사(주석이 개인정보 처리 동작을 잘못 서술 — 감리 지적 가능) + **외부 계약 문서 오기**(OpenAPI). 런타임 보안 영향은 없다(실동작은 정책 준수 방향). `CLAUDE.md` 가 경고한 *"컨트롤러가 정책 판정을 복제 보유하면 정책 갱신 때 조용히 뒤처진다"* 의 문서판이며, 다음 검증자가 "원본을 쓴다"를 믿고 비식별 서빙 회귀를 놓칠 위험이 있다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. ① `Sam2SegmentService.java:43-45` 를 "**비식별 우선 해석**(`FrameImageEncoder.resolveFrameImageForInference`) — 캔버스가 보여주는 픽셀과 분할 대상이 같아야 좌표가 맞는다. 원본 폴백은 비식별 경로 부재 시에만"으로 정정. ② `LabelController.java:155` 의 "정책상 원본 이미지에만 실행" 문구를 삭제하고 "비식별 프레임 기준 실행"으로 교체. ③ `CLAUDE.md` "오토라벨링" 절에 **온라인(사용자 트리거) SAM2 = 비식별본 / 배치 YOLO·SAM2 = 원본** 구분을 명시(현재 절은 배치 기준만 서술).

---

### [C-ISSUE-83] TC-SAM2-09 (부수) — mock 안내 메시지가 사유와 무관하게 "AI 모델 미로드"로 고정돼 오진단을 유도한다 (1차 C-ISSUE-86 **미해소 이월**)

- **심각도**: LOW
- **기대 동작(기대효과)**: 사용자·운영자에게 나가는 안내는 실제 사유와 일치해야 한다. ai-server 는 사유를 4종으로 구분해 내려준다(`mock_reason` ∈ `env_mock` | `weights_missing` | `load_failed` | `empty_mask`)이며 BE DTO 도 이미 파싱한다(`Sam2Response.java:23`, `Sam2TrackResponse.java:31`).
- **현재 동작(이슈 내용)**: BE 는 사유를 무시하고 단일 문자열을 세팅한다.
  ```java
  // label/dto/Sam2SegmentResponse.java:25
  public static final String MOCK_UNAVAILABLE_MESSAGE = "AI 모델 미로드 — 결과 신뢰 불가";
  // LabelController.java:179-181 — res.isEmpty() 이면 무조건 위 문자열
  // label/dto/Sam2TrackOutcome.java:20 — track 도 같은 상수를 재사용
  ```
  **3차 실측(1차보다 강한 증거)**: 이번 스택은 SAM2 가 **HF 캐시에서 정상 로드**돼 있다(같은 프레임에 정상 프롬프트를 주면 `score 0.9652` 실결과 반환). 그런데 마스크가 안 잡히는 프롬프트를 주면:
  ```
  POST /v1/frames/468/sam2-segment {"srcSn":468,"box":[100,100,10,10]}
  → 200 {"polygon":[],"score":0.0,"empty":true}  message:"AI 모델 미로드 — 결과 신뢰 불가"
  ```
  ai-server 실제 사유는 `empty_mask`(`ai-server/app/routers/sam2.py:328-331`)이고 **모델은 로드돼 있다.** 실제 의미는 "프롬프트 위치에 객체가 없음"이다. track 도 동일(`_prev_polygon_fallback` reason=`empty_mask` → 같은 문구).
- **재현/확인 경로**: 위 curl 2줄. 대조로 `{"points":[[50,50]]}` 을 주면 같은 프레임에서 실결과 200 이 나온다(= 모델은 살아 있음).
- **영향**: 운영/UX. 작업자가 "서버 장애"로 오인해 불필요한 에스컬레이션을 하거나, 반대로 진짜 `weights_missing`(현재 YOLO 가 그 상태다) 상황이 "늘 뜨는 메시지"로 묻힌다. 자동적용 차단 동작 자체는 정상이므로 데이터 오염 위험은 없다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. `mockReason` 을 사유별 메시지로 매핑 — `empty_mask` → "선택 지점에서 객체를 찾지 못했습니다 — 다른 위치를 시도해 주세요", `weights_missing`/`load_failed`/`env_mock` → 현행 문구. **빈 폴리곤(자동적용 차단) 동작은 두 경우 모두 유지**한다. 매핑은 segment·track 이 공유하도록 `Sam2SegmentResponse`/`Sam2TrackOutcome` 상수 대신 공용 헬퍼로 뺀다. FE(`OverlayLayer.onMockWarning`·`useSam2Track`)는 `message` 를 그대로 표시하므로 BE 만 고치면 된다.

---

### [C-ISSUE-84] TC-SAM2-11 / TC-SAM2-14 — SAM2 **track** 응답의 `score` 가 클램프·반올림 없이 원본 그대로 나간다 (1차 C-ISSUE-82 ③ **잔여**)

- **심각도**: LOW
- **기대 동작(기대효과)**: "외부 응답 불신" 원칙상 신뢰도도 계약 범위 [0,1] 로 강제돼야 한다. segment 는 이미 그렇게 한다 — `Sam2SegmentService.clampScore`(`:242-246`)가 `NaN→0.0`, `[0,1]` 클램프, 4자리 반올림을 적용한다. FE 는 이 값으로 저신뢰 분기(`SAM_LOW_CONFIDENCE_THRESHOLD`)를 태운다.
- **현재 동작(이슈 내용)**: track 은 ai 원본값을 그대로 싣는다.
  ```java
  // Sam2TrackService.java:160-166 — clampScore 호출 없음
  tracked.add(new Sam2TrackResponseDto.TrackedItem(
          nextSrcSn, aiRes.trackId(), req.label(), bbox, aiRes.score(), …));
  ```
  **실측**:
  ```
  POST /v1/frames/468/sam2-track … → 200
  {"tracked":[{"srcSn":469,…,"score":0.9834924936294556,"shapeType":"POLYGON"}, …]}
  ```
  segment 는 같은 스택에서 `"score":0.9652`(4자리)로 나간다. ai-server 가 계약 밖 값(음수·>1·NaN)을 주면 track 만 그대로 통과한다.
- **재현/확인 경로**: 위 두 엔드포인트 응답의 `score` 자릿수 비교. 계약 위반값 재현은 ai-server 스텁 필요(단위 레벨).
- **영향**: 기능/UX(FE 저신뢰 임계 판정의 입력이 계약 밖 값일 수 있음) + 표현 비일관. 보안 영향 없음. ⚠ **이미지 경계 상한 미적용**은 별개 축으로, 카탈로그 TC-SAM2-11 이 "track·오토라벨은 **의도적** 미적용"으로 고정하고 있으므로 본 이슈에 포함하지 않는다(다만 그 비고의 교차참조 번호가 틀려 정정함 — §3).
- **수정 방향(제안)**: ⚠ 구현하지 않음. `clampScore` 를 `Sam2SegmentService` 의 private 에서 공용 유틸(`Sam2CoordinateValidator` 또는 신설 `Sam2ScorePolicy`)로 올려 track 의 두 `tracked.add` 지점에 적용. 단위테스트로 `NaN`·`-0.1`·`1.5` 3케이스 고정.

---

### [C-ISSUE-85] TC-SAM2-31 (부수) — 포털 SAM2 제거 후 `encodeDeidentifiedFrameForInference` 가 **프로덕션 호출부 0건 dead code** 이고, 클래스 javadoc 이 폐지된 포털 SAM2 를 여전히 소비자로 서술한다

- **심각도**: LOW
- **기대 동작(기대효과)**: "단일 진입점" 클래스의 javadoc 은 **현재 실제 소비자**를 정확히 나열해야 한다. 이 클래스는 그 정확성 자체가 보안 통제(다음 사람이 "여기만 보면 된다"고 믿는 근거)이며, 클래스 스스로도 그렇게 경고한다(`:47-48`: *"**\"모든 전송이 이 클래스를 통과한다\"고 쓰지 말 것** — 사실이 아니고, 다음 사람이 그 문장을 믿고 검사를 생략한다"*).
- **현재 동작(이슈 내용)**: 포털 SAM2 컨트롤러·서비스가 제거됐는데(회귀 가드 `portal/PortalSam2RemovedTest.java` — "포털(외부 채널) SAM2 도구 **완전 제거**") javadoc 과 메서드는 남았다.
  ```java
  // FrameImageEncoder.java:34-36
  * <b>사용자 요청으로 실행되는</b> 프레임 전송 경로는 예외 없이 {@link #resolveFrameImageForInference}
  * / {@link #encodeFrame} / {@link #encodeDeidentifiedFrameForInference} 를 거친다 — 내부 채널의
  * SAM2 분할·SAM2 추적·YOLO 추적·온라인 오토라벨, 외부 채널의 포털 SAM2 분할/추적이 전부 해당한다.
  //                                              ^^^^^^^^^^^^^^^^^^^^^^ 이미 제거된 경로
  // :138-148
  * <b>비식별본 전용</b> 외부 추론 전송 진입점 … <p>포털(외부 채널)용이다.
  public String encodeDeidentifiedFrameForInference(LsDataSrc frame) { … }
  ```
  호출부 실측:
  ```
  $ grep -rn "encodeDeidentifiedFrameForInference" backend/src/main backend/src/test
  main/.../FrameImageEncoder.java:35   (javadoc)
  main/.../FrameImageEncoder.java:148  (정의)
  test/.../FrameImageEncoderTest.java:103  (테스트만)
  ```
  → **프로덕션 호출부 0건.**
- **재현/확인 경로**: 위 grep. 포털 SAM2 부재는 `PortalSam2RemovedTest` + `grep -rn "sam2" frontend/src/features/portal` 로 교차 확인.
- **영향**: 유지보수/검증 신뢰도. TC-SAM2-31 의 구조 단언("게이트 없는 base64 오버로드 부재")은 여전히 성립하나, **public 이면서 호출부 없는 진입점**은 향후 누군가 "포털용이 있네" 하며 다시 외부 채널을 여는 진입 유혹이 된다(이 클래스가 이미 겪은 사고 유형이다). 런타임 영향 없음.
- **수정 방향(제안)**: ⚠ 구현하지 않음. ① 메서드를 제거하거나(테스트 동반 삭제) 최소한 package-private 로 좁히고 `@Deprecated` + "현재 소비자 없음" 명시 ② `:34-36` 의 "외부 채널의 포털 SAM2 분할/추적" 문구 삭제 ③ 판단 근거를 남길 것 — 포털이 다시 SAM2 를 갖게 되는 일은 ADR-013 상 없다.

---

### [C-ISSUE-86] TC-SAM2-32 — `FrameImageEncoder` javadoc 의 "유일한 **패키지 외부** 소비자" 서술이 사실과 다르다 (1차 C-ISSUE-84 ③ **미해소 이월**, 카탈로그 측은 이번에 정정)

- **심각도**: LOW
- **기대 동작(기대효과)**: `resolveFrameImageWithoutGate` 를 package-private 으로 좁힌 이유가 주석에 정확히 남아야 한다. 이 케이스의 단언은 "**패키지 밖 소비자가 0건**"이며, 그래야 게이트 없는 해석기가 외부로 새지 않는다는 보장이 성립한다.
- **현재 동작(이슈 내용)**:
  ```java
  // FrameImageEncoder.java:86-88
  * <p>현재 유일한 패키지 외부 소비자는 {@code FrameBoundsResolver} — 라벨 좌표 정규화를 위해
  * 이미지 <b>치수만</b> 읽고 픽셀을 밖으로 내보내지 않으므로 게이트 대상이 아니다.
  ```
  실제로는 두 클래스가 같은 패키지다:
  ```
  FrameImageEncoder.java:1    package kr.co.cudo.authoring.label.service;
  FrameBoundsResolver.java:1  package kr.co.cudo.authoring.label.service;
  ```
  package-private 메서드는 애초에 패키지 밖에서 호출될 수 없으므로 "패키지 외부 소비자"라는 표현 자체가 성립하지 않는다.
- **재현/확인 경로**: `head -1 backend/src/main/java/kr/co/cudo/authoring/label/service/{FrameImageEncoder,FrameBoundsResolver}.java`
- **영향**: 유지보수/검증 신뢰도(LOW). 실제 접근제어는 정상이며 런타임 영향 없음. 다만 이 문장을 근거로 "패키지 밖에도 소비자가 있다 → 그러면 public 으로 올려도 되겠다"는 오판이 나올 수 있다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. "현재 유일한 소비자는 **동일 패키지의** `FrameBoundsResolver` 이며 **패키지 밖 소비자는 0건**이다"로 정정. 구조 회귀 가드로 "이 메서드에 public 접근제어자가 없다"를 ArchUnit 류로 고정하는 것도 검토.

---

### [C-ISSUE-87] TC-SAM2-06 (부수) — `Sam2SegmentService` 에 미사용 `@Value` 필드 `storageRawPath` 잔존 (1차 C-ISSUE-84 ① **부분 미해소**)

- **심각도**: LOW
- **기대 동작(기대효과)**: 1차 지적의 취지는 "이 서비스는 경로를 스스로 조립하지 않는다"를 코드로도 참으로 만드는 것이었다. dead 메서드 `resolveSafe` 는 삭제됐으나 그 메서드가 쓰던 설정 주입 필드가 남아, 여전히 "이 클래스가 스토리지 base 를 안다"는 신호를 준다.
- **현재 동작(이슈 내용)**:
  ```java
  // Sam2SegmentService.java:84-85
  @Value("${authoring.storage.raw-path:./storage/raw}")
  private String storageRawPath;      // ← 클래스 내 참조 0건
  ```
  ```
  $ grep -n "storageRawPath" .../Sam2SegmentService.java
  85:    private String storageRawPath;      ← 선언 1건뿐
  ```
- **재현/확인 경로**: 위 grep.
- **영향**: 코드 위생/유지보수(LOW). 런타임 영향 없음(빈 필드 주입 1회). 다만 `resolveSafe` 를 다시 만들고 싶어지는 "재료"가 남아 있어 판정 지점 이중화(=정책 드리프트) 재발 유혹이 된다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. 필드와 `@Value` 삭제, `import org.springframework.beans.factory.annotation.Value` 정리.

---

### [C-ISSUE-88] TC-SAM2-22 — `Sam2TrackService:101-102` 의 후속 프레임 404 는 **도달 불가**하다 (근거 드리프트 — 카탈로그는 이번에 정정)

- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그 근거 `file:line` 은 **실제로 실행되는 방어 지점**을 가리켜야 한다. 그래야 다음 수정자가 그 줄을 지웠을 때 회귀를 인지한다.
- **현재 동작(이슈 내용)**: 루프 선두의 인가 검사가 먼저 404 를 낸다.
  ```java
  // Sam2TrackService.java:97-102
  for (Long nextSrcSn : req.nextSrcSns()) {
      accessGuard.verifyAccess(nextSrcSn, actor);            // :99  ← 여기서 404
      LsDataSrc nextSrc = srcRepository.findById(nextSrcSn)
              .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                      "후속 프레임을 찾을 수 없습니다: " + nextSrcSn));   // :102 ← 도달 불가
  ```
  `LabelAccessGuard.verifyAndGet`(`:55`, `:58`)이 존재하지 않는 `srcSn` 에 대해 `"프레임을 찾을 수 없습니다."` 로 먼저 던진다.
  **실측**:
  ```
  POST /v1/frames/468/sam2-track {"nextSrcSns":[999999], …}
  → 404 {"message":"프레임을 찾을 수 없습니다.","errorCode":"NOT_FOUND"}
      (":102" 의 "후속 프레임을 찾을 수 없습니다: 999999" 가 아님)
  ```
- **재현/확인 경로**: 위 curl 의 응답 message 문자열 비교.
- **영향**: 카탈로그 정합성(검증 자체의 신뢰도). 동작은 기대대로 404 이므로 기능·보안 영향 없음. `:101-102` 는 방어심층으로 유지할 가치가 있으나 "여기가 404 의 출처"라는 서술은 틀리다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. **카탈로그는 본 파트에서 이미 정정**(§3-2). 코드 측은 선택 — `:102` 메시지를 가드와 통일하거나, 주석으로 "가드가 먼저 판정하므로 방어심층"임을 명시.

---

### [C-ISSUE-89] TC-SAM2-23/34/36 (구조 관찰) — track 은 **응답 좌표 검증(:128) → mock 판정(:136)** 순서이고 segment 는 **mock 판정(:145) → 응답 검증(:151)** 로 반대다

- **심각도**: LOW (현재 도달 불가 — 예방적 기록)
- **기대 동작(기대효과)**: mock 응답은 "외부가 잘못 준 좌표"가 아니라 "신뢰할 수 없는 모드"이므로, 두 경로 모두 **mock 을 먼저 판정해 우아하게 제외/빈 결과**로 마감하는 편이 일관적이다. 검증을 먼저 두면 mock 응답이 502/400 오류로 나가 "부분 추적 + 안내"라는 설계된 UX 가 깨진다.
- **현재 동작(이슈 내용)**:
  ```java
  // Sam2TrackService.java:127-136
  validateResponsePolygon(aiRes.polygon(), "ai-server polygon");  // :128  502/400
  …
  if (aiRes.untrusted()) { anyMock = true; … continue; }          // :136  mock 제외
  ```
  ```java
  // Sam2SegmentService.java:145-151  — 반대 순서
  if (aiRes.untrusted()) { return Sam2SegmentResponse.empty(); }  // :145
  validatePolygon(aiRes.polygon(), imgWidth, imgHeight);          // :151
  ```
- **재현/확인 경로**: 현재는 **도달 불가**임을 확인했다 — ai-server 의 track mock 두 경로(`_mock_track` `sam2.py:443-455` / `_prev_polygon_fallback` `:351-366`)가 모두 **BE 가 보낸 `prev_polygon` 을 되돌려주고**, 그 값은 요청 축(`@Size(min=3)`) 또는 직전 응답 축(`validateResponseMinPoints`)으로 이미 3점 이상·유한·비음수임이 보장되기 때문이다. 단 `_echo_polygon`(`:99-117`)이 변환 불가 원소를 **버리므로**, ai-server 가 향후 좌표를 가공하면 mock 응답이 2점으로 줄어 **502 로 나갈 수 있다**(설계 의도는 "빈 결과 + 안내").
- **영향**: 현재 0. 계약 드리프트 시 UX 저하(안내 대신 502). 보안·데이터 정합 영향 없음.
- **수정 방향(제안)**: ⚠ 구현하지 않음. track 의 `untrusted()` 판정을 `validateResponsePolygon` **앞으로** 이동해 segment 와 순서를 맞춘다(둘 다 "신뢰 판정 → 내용 검증"). 회귀 가드로 "mock=true + 2점 폴리곤 응답 → 502 가 아니라 프레임 제외 + 안내"를 단위테스트로 고정.

---

## 3. 카탈로그 정정 (담당 라인범위 내 Edit 완료 — 4건 정정 + 2건 신설)

> 프로덕션·테스트 코드는 일절 수정하지 않았다. 아래는 `docs/test-cases/C-marking-labeling.md` C-3 절만 대상이다.

### 3-1. TC-SAM2-11 — 교차참조 번호 오류 + 누락 축 보강
- **정정 전**: "track·오토라벨은 의도적으로 미적용(별건 **C-ISSUE-61**)"
- **문제**: 1차 `C-ISSUE-61` 은 `GET /v1/deident-reports` 정렬 키 미검증 이슈로 **완전히 무관**하다. 실제 대응 이슈는 1차 `C-ISSUE-82 ③`(경계 상한·score 미검증).
- **정정 후**: 교차참조를 `C-ISSUE-82 ③` 으로 바꾸고 오참조 사실을 명시. 아울러 **score 클램프도 segment 전용**임을 실측값과 함께 비고에 추가(근거 `:242-246` 병기).

### 3-2. TC-SAM2-22 — 근거 `file:line` 드리프트 정정
- **정정 전**: 근거 `Sam2TrackService.java:101-102`
- **문제**: 실측 404 메시지가 가드 문구(`"프레임을 찾을 수 없습니다."`)로, `:102` 는 도달하지 않는다(C-ISSUE-88).
- **정정 후**: 근거를 `Sam2TrackService.java:99 · LabelAccessGuard.java:55, :58` 로 교체하고 `:101-102` 는 "도달 불가 방어심층"으로 괄호 표기.

### 3-3. TC-SAM2-28 — 기대결과 보강 (실동작이 카탈로그보다 강함)
- **정정 전**: "CRLF → LogSanitizer 정제"
- **문제**: 실제로는 `@Pattern("^[A-Za-z0-9._:-]+$")` 가 **400 으로 선차단**해 LogSanitizer 까지 도달하지 않는다. 카탈로그대로만 확인하면 "패턴이 완화돼도 통과"하는 무의미한 검증이 된다.
- **정정 후**: 1차 방어선 = `label/Sam2TrackRequest.java:29` `@Pattern`(400, 실측 응답 병기), LogSanitizer 는 심층방어임을 명시하고 근거에 두 지점 모두 기재.

### 3-4. TC-SAM2-32 — 사실 오류 정정
- **정정 전**: "유일한 **패키지 외** 소비자 `FrameBoundsResolver`"
- **문제**: 두 클래스 모두 `kr.co.cudo.authoring.label.service` 로 **동일 패키지**. 이 케이스의 단언은 "패키지 밖 소비자 0건"이어야 한다(C-ISSUE-86).
- **정정 후**: "동일 패키지 유일 소비자 / 패키지 밖 소비자 0건"으로 정정하고, 프로덕션 javadoc `FrameImageEncoder.java:86` 에 같은 오기가 남아 있음을 ⚠ 로 병기.

### 3-5. **TC-SAM2-36 신설** — track mock → 해당 프레임 제외 + 전량 안내
- **사유**: 1차 `C-ISSUE-81`(HIGH, track mock 게이트 부재)이 **수정 반영**됐는데 C-3 에 대응 케이스가 없었다(1차 보고서도 "카탈로그에 track mock 케이스가 **없다** → 신설 필요"로 지적). 2차 타겟 재검증도 "카탈로그에 없어 인접 신규 케이스로 판정"했다. CLAUDE.md 문서 동기화 규칙(**신설**)에 따라 정식 케이스로 승격.
- **내용**: 200 + `tracked:[]` + 전량 안내. 판정 규약이 **긍정 증명**(`AiMockMeta.untrusted`)이라는 점(필드 생략 fail-open 방지)과 mock 플래그를 FE-facing DTO 로 노출하지 않는 계약을 기대결과에 못박음.

### 3-6. **TC-SAM2-37 신설** — track 일부 프레임만 mock → 실결과 유지 + 부분 안내
- **사유**: 전량/부분 두 갈래가 별도 메시지 상수(`MOCK_UNAVAILABLE_MESSAGE` vs `PARTIAL_MOCK_MESSAGE`)로 갈리는데 커버 케이스가 없었다. **과차단 회귀**(실모델 결과까지 버리는 회귀)를 잡는 축이라 별도 케이스가 필요하다.
- **내용**: 실모델 프레임만 반환 + 부분 안내, 전 프레임 실모델이면 `message=null`. mock 프레임도 전파(`currentPolygon`)는 이어간다는 설계 의도와 FE 청크 누적까지 기대결과에 포함.

### 3-7. 미조치 (담당 라인범위 밖 — 병합 담당자 반영 필요)
- 파일 상단 **`## 변경 이력`** 표에 3차 회차 행을 추가해야 한다(정정 4 / 신설 2 / 폐기 0). 이 표는 담당 라인범위(223~268행) 밖이고 다른 part 에이전트와 동시 편집 충돌 위험이 있어 **의도적으로 건드리지 않았다.**
- C-3 절의 케이스 수가 **35 → 37** 로 늘었다. `README.md` 총계·`grep -cE '^\| *~*TC-'` 실측 대조 시 반영 필요.

---

## 4. self-fill 관점 정리 (§9 SUMMARY 입력)

| 항목 | 판정 |
|---|---|
| SAM2 분할/추적 좌표의 출처 | **전량 ai-server 응답**. BE 에 하드코딩 폴백 좌표 0건 — 응답이 null 이거나 polygon 이 null 이면 502 로 마감(`Sam2SegmentService.java:139-141` / `Sam2TrackService.java:124-126`). **self-fill 없음** |
| ai-server 내부 mock 의 self-fill 성격 | ai-server `_mock_segment`(합성 사각형) / `_mock_track`·`_prev_polygon_fallback`(시드 폴리곤 복사, `score` 0.9/0.5)은 **좌표를 자체 합성한다**. → **BE 가 6개 경로 전부에서 차단**: 온라인 segment(`Sam2SegmentService:145`) · 온라인 track(`Sam2TrackService:136`) · 온라인 오토라벨 YOLO(`AutolabelOnlineService:241`) · 온라인 오토라벨 SAM 폴리곤(`:325-330`) · 배치 YOLO(`YoloAutolabelStep:244`) · 배치 SAM2(`Sam2SegmentStep:215`). **1차 self-fill 항목(C-ISSUE-81, track 미차단)은 해소됨** |
| 판정 규약의 fail-open 여부 | `AiMockMeta.untrusted(mock, source) = mock \|\| !"model".equals(source)` — **긍정 증명 요구**. mock 메타를 통째로 생략한 JSON(primitive `boolean` 기본값 `false`)도 불신 처리 = fail-closed. 계약 고정 `Sam2MockMetaDriftTest`·`AiMockMetaTest` |
| 과차단(정상 결과 차단) 회귀 여부 | 없음 — 실모델 응답은 `source="model"` 로 정상 통과함을 실동작으로 확인(segment `score 0.9652` / track 2프레임 반환) |

---

## 5. 이전 회차 이슈 대조

| 이슈 | 1차 내용 | 3차 상태 |
|---|---|---|
| **C-ISSUE-81**(1차, HIGH) | SAM2 **track** 경로에 mock 게이트 없음 → mock 좌표 자동 적용(CWE-345) | **✅ 해소 — 실동작 확인.** mock 유도 시 `tracked:[]` + 안내. DTO 3필드 추가 + `AiMockMeta` 긍정 증명 + `Sam2TrackOutcome` + FE 배선까지 종단 완결. 2차 타겟 재검증(PASS)이 3차 실동작으로 재확인됨. **카탈로그 케이스 신설(TC-SAM2-36/37)로 커버 공백도 해소** |
| **C-ISSUE-82**(1차, MEDIUM) | track 응답 검증이 요청 검증과 동일 코드 → ①400 오귀속 ②정점 수 미검증 ③경계 상한·score 미검증 | **🔶 부분 해소.** ② 해소(`validateResponseMinPoints` → 502). ① 해소(정점 수 축은 502, 좌표 형식 축은 400 유지 — 카탈로그 TC-SAM2-23 이 2축 공존으로 재정의). ③ **미해소** — 경계 상한은 카탈로그가 "의도적 미적용"으로 고정, **score 클램프는 여전히 잔여** → 3차 **C-ISSUE-84** 로 이월 |
| **C-ISSUE-83**(1차, MEDIUM) | `Sam2SegmentService` `@Transactional` 잔존 → AI 호출 중 커넥션 점유 | **❌ 미해소 이월.** 3차 실측에서도 `idle in transaction 6` 재현 → 3차 **C-ISSUE-81** |
| **C-ISSUE-84**(1차, LOW) | ①dead `resolveSafe` ②javadoc 이 "원본 이미지" 로 반대 서술 ③TC-SAM2-32 "패키지 외" 오기 | **🔶 부분 해소.** ① `resolveSafe` 삭제됨(단 미사용 `storageRawPath` 필드 잔존 → 3차 **C-ISSUE-87**). ② **미해소** + **Swagger 표면 1건 추가 발견** → 3차 **C-ISSUE-82**. ③ **미해소**(코드), 카탈로그는 이번에 정정 → 3차 **C-ISSUE-86** |
| **C-ISSUE-86**(1차, LOW) | mock 안내 메시지가 사유 무관 고정 문구 | **❌ 미해소 이월.** 3차에서 `empty_mask` + **모델 정상 로드** 상태로 더 강한 반증 확보 → 3차 **C-ISSUE-83** |
| UNCERTAINTIES #1 (포털 SAM2 노출 = 정책 위반) | 문서 정본, 결함 유지 | **✅ 해소(F 클러스터 소관, 여기서는 교차 확인만).** `PortalSam2Controller`/`PortalSam2Service` **완전 제거**, 회귀 가드 `portal/PortalSam2RemovedTest`(핸들러 부재 404 판정). 부작용으로 `encodeDeidentifiedFrameForInference` 가 dead 가 됨 → 3차 **C-ISSUE-85** |

---

## 6. 실행 로그 요약 (재현용)

```bash
# 토큰
POST /api/v1/dev/tokens {"role":"WORKER","channel":"INTERNAL","userNo":"2001","expSeconds":86400}
POST /api/v1/dev/tokens {"role":"REVIEWER","channel":"INTERNAL","userNo":"1001","expSeconds":86400}

# 정상 (rawSn 101 / srcSn 468~477, WORKER 2001 배정)
POST /v1/frames/468/sam2-segment {"srcSn":468,"points":[[50,50]]}                       → 200 9점 score .9652
POST /v1/frames/468/sam2-track   {"srcSn":468,"trackId":"t1","prevPolygon":[[40,49],[78,9],[79,176],[41,178]],
                                  "label":"person","nextSrcSns":[469,470]}              → 200 2프레임
POST … same + {"shape":"BBOX"}                                                          → 200 [[40,8],[79,178]]

# mock 게이트 (SAM2 실모델 로드 상태에서 empty_mask 유도)
POST /v1/frames/468/sam2-segment {"box":[10000,10000,10001,10001]}                      → 200 polygon:[] + 안내
POST /v1/frames/468/sam2-segment {"box":[100,100,10,10]}                                → 200 polygon:[] + 안내
POST /v1/frames/468/sam2-track   prevPolygon:[[9000,9000],[9010,9000],[9010,9010],[9000,9010]]
                                                                                        → 200 tracked:[] + 안내

# 신고구간 (rawSn 900 DE_IDENT_YN='F', REVIEWER 토큰)
POST /v1/frames/429/sam2-segment                                                        → 412
POST /v1/frames/429/sam2-track                                                          → 412

# IDOR (WORKER 2001)
POST /v1/frames/429/sam2-segment                                                        → 403
POST /v1/frames/468/sam2-track  nextSrcSns:[429]                                        → 403

# 파생영상 프레임 (rawSn 18, SRC_FILE_PATH_NM NULL)
POST /v1/frames/45/sam2-segment                                                         → 200
POST /v1/frames/45/sam2-track   nextSrcSns:[46]                                         → 200

# 입력 검증 (전부 400)
srcSn 불일치 / points+box 동시 / 둘 다 없음 / simplifyTolerance 60 / points 101개 / box 3개
nextSrcSns 51개·[] / prevPolygon 2점·1001점·음수 / trackId 65자·CRLF

# 경로순회 (DB 일시 변경 후 원복)
UPDATE ls_data_src SET src_file_path_nm='../../../etc/passwd', de_idntf_src_file_path_nm=NULL WHERE src_sn=46;
POST /v1/frames/46/sam2-segment                                                         → 400 "허용되지 않은 경로입니다."
UPDATE ls_data_src SET src_file_path_nm=NULL,
       de_idntf_src_file_path_nm='/app/storage/deidentified/frames/deid/18/frame-1.jpg' WHERE src_sn=46;  -- 원복 확인
```
