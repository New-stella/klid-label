# 전 클러스터 이슈 대장 (2차)

> 회차: 2026-08-02 2차 (버그수정 후 재검증). 타겟재검증(A/B/C/D/E 수정영역, 17건 전부 PASS)은 이슈 없음.

## 클러스터 F — 포털

### [F-ISSUE-27] TC-PORTALUP-023 인접 — **배포된 backend jar 가 워킹트리 소스보다 오래되어** 포털 정렬 allowlist 가 라이브에 없다 (`sort=status` → 500)
- **심각도**: HIGH (검증 환경 신뢰성 · 라이브 노출 기준으로는 CWE-209/770)
- **기대 동작(기대효과)**: 검증 대상 스택은 검증 기준 소스(qa-0801)와 동일 산출물이어야 한다. 소스는 `PortalUploadController.safeSort/safeFrameSort` → `SortAllowlist.resolve`(strict)로 미등록 정렬 키를 **400** 으로 거부한다(A-ISSUE-61 수정분, `PortalUploadController.java:164-174`).
- **현재 동작(이슈 내용)**: 컨테이너 jar 에 그 배선이 없다.
  ```
  docker inspect klid-backend → created 2026-08-01T14:10:06Z, /app/app.jar mtime Aug 1 14:05
  jar!BOOT-INF/classes/.../PortalUploadController.class : safeSort=False, SortAllowlist=False, capped=True
  jar!BOOT-INF/classes/.../SortAllowlist.class          : PORTAL_UPLOAD=False, PORTAL_UPLOAD_FRAME=False, DEIDENT_REPORT=False
  git status --short | wc -l → 80 (미커밋 수정), 그중 SortAllowlist.java·PortalUploadController.java·PortalLabelController.java 포함
  ```
  라이브 실측:
  ```
  GET /v1/portal/uploads?sort=status,asc      → 500 INTERNAL_ERROR
     backend log: ERROR GlobalExceptionHandler - unhandled exception
       org.springframework.data.mapping.PropertyReferenceException: No property 'status' found for type 'LsPortalUld'
  GET /v1/portal/uploads?sort=filePathNm,desc → 200, 응답 sort.sorted=true  (응답에 노출되지 않는 내부 저장경로 컬럼으로 실제 정렬)
  GET /v1/portal/uploads?sort=orgnlFileNm,asc → 200 (소스에서는 PII 사유로 의도적 제외 키)
  ```
- **재현/확인 경로**: 위 3개 curl + `docker cp klid-backend:/app/app.jar` 후 클래스 문자열 검사.
- **영향**: ①**검증 무효화 위험** — 이 스택 위의 "실동작 PASS" 가 소스 기준 동작을 보증하지 못한다(이번 F-3/F-6 24건은 무관함을 개별 확인했으나, 정렬·SAM2·`PortalLabelController` 를 다루는 다른 파트는 영향권). ②배포본 자체 기준으로는 내부 엔티티명이 ERROR 로그에 적재되는 CWE-209 + 정렬 항목 상한 부재(CWE-770)가 그대로 살아 있다.
- **수정 방향(제안)**: 검증 착수 전 **소스 재빌드 → 이미지 재배포**를 환경 게이트(§3-1)에 추가하고, `_raw/stack-bringup.md` 에 "배포 산출물 커밋 해시 = 검증 기준 해시" 대조 항목을 명시한다. 아울러 워킹트리의 미커밋 80파일을 커밋/정리해 기준을 확정한다. ⚠ 코드는 건드리지 않음.


### [F-ISSUE-41] TC-PORTAL-060, TC-PORTAL-061 — 포털 SAM2 분할/추적 엔드포인트가 여전히 살아 있음(정책상 미제공 기능)
- **심각도**: HIGH *(1차 대비 완화 — 전송 픽셀이 비식별본으로 교체되고 신고 게이트가 걸려 CRITICAL 아님)*
- **기대 동작(기대효과)**: 루트 `CLAUDE.md` 포털 섹션 + ADR-013 은 포털 채널에 **"오토라벨링·SAM2·VLM·버전관리·검수 미제공"** 을 명시한다. 포털은 데이터마트 영상 열람 + 수동 라벨링(BBOX/POLYGON) + 본인 자산 업로드만 담당해야 하며, GPU 추론 자원을 외부 채널에 개방하면 ①내부 파이프라인 자원 경합 ②승인 완료(데이터마트 노출) 프레임 픽셀의 외부 프로세스 반복 전송 ③요구사항 범위 확대(감리 지적)로 이어진다.
- **현재 동작(이슈 내용)**: 두 엔드포인트가 무조건 노출된다. 기능 토글·프로파일 가드가 없다.
  - `backend/.../portal/controller/PortalSam2Controller.java:44,54-55,71-72`
    ```java
    @RequestMapping("/v1/portal/frames")
    @PostMapping("/{srcSn}/sam2-track")   @PreAuthorize("hasRole('PORTAL_USER')")
    @PostMapping("/{srcSn}/sam2-segment") @PreAuthorize("hasRole('PORTAL_USER')")
    ```
  - 실동작(2026-08-02, 포털 토큰 `channel=PORTAL, role=PORTAL_USER, sub=3001`):
    ```
    POST /api/v1/portal/frames/1/sam2-segment  {"srcSn":1,"box":[10,10,100,100]}
      → 200 {"success":true,"data":{"polygon":[[16.0,10.0], … 176점 …],"score":…}}
    POST /api/v1/portal/frames/1/sam2-track    {"srcSn":1,"trackId":"qa-track-1",…,"nextSrcSns":[2,3]}
      → 200 {"data":{"tracked":[{"srcSn":2,…},{"srcSn":3,…}]}}
    ```
    ai-server 직접 조회로 실추론 확인: `{"mock":false,"source":"model"}`.
  - `application.yml` 에는 `resilience4j.bulkhead.configs.portalSam2`(:675) · `ratelimiter.configs.portalSam2`(:684) 만 있고 **기능 on/off 키가 없다** → 운영에서 끌 수단이 없음.
- **재현/확인 경로**:
  ```bash
  TOK=$(curl -s -X POST http://localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
        -d '{"role":"PORTAL_USER","channel":"PORTAL","sub":"3001"}' | jq -r .data.token)
  curl -s -w '\n%{http_code}\n' -X POST http://localhost:18081/api/v1/portal/frames/1/sam2-segment \
    -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d '{"srcSn":1,"box":[10,10,100,100]}'
  # → 200 + 실좌표
  ```
  (srcSn=1 은 rawSn=4, `LS_RAW_DATA_STATUS.DATA_STTS_CD='APPROVED'`)
- **영향**: 요구사항·ADR 위반(범위 이탈). 보안 측면은 **완화됨** — 전송 픽셀이 비식별본이고(TC-075) 신고 구간은 412 로 차단(TC-076/077)되므로 CWE-359 잔여 위험은 "승인된 비식별 프레임이 외부 채널 트리거로 ai-server 로 반복 전송됨" 수준. 자원 측면은 포털 전용 bulkhead(4)·per-user rate limit(30/min)로 내부 `aiOnline` 경로와 격리돼 있어 잠식 위험도 제한적. 남는 실질 리스크는 **정책·감리 정합성**과 **끌 수 없는 노출 표면**.
- **수정 방향(제안)**: ⚠ 구현하지 않음.
  1. (정석) `PortalSam2Controller` + `PortalSam2Service` + FE 분기(`api.ts:604-610,812-823`, `useSam2Segment`/`useSam2Track`/`CanvasShell`/`Sam2TrackTool`/`ObjectAttributePanel`) 제거, 포털 도구바에서 `AI 분할`·`AI 추적`·`스켈레톤` 비노출(F-ISSUE-42 와 동시 처리), `LabelingPagePortalRestrictions.test.tsx:108-124` 를 **부재 단언**으로 반전.
  2. (대안) 정책을 "포털 SAM2 제공"으로 **상향 확정**한다면 `CLAUDE.md` 포털 섹션·ADR-013 을 개정하고 UNCERTAINTIES #1 을 종결 — 단 그 경우에도 운영 kill-switch(`authoring.portal.sam2.enabled` 등 `@ConditionalOnProperty`)를 함께 두어야 한다.
  - 어느 쪽이든 **정책 확정이 선행**돼야 하며, 현재 상태(문서=미제공 / 코드=제공)의 방치가 가장 나쁘다.


### [F-ISSUE-64] TC-PORTALUP-051 / TC-PORTALUP-052 — 라벨 PUT 본문 상한 필터가 **URL 퍼센트 인코딩 경로로 우회**됨 (pre-parse DoS 재개통)
- **심각도**: HIGH
- **기대 동작(기대효과)**: `PortalLabelBodySizeFilter` 는 이름 그대로 **Jackson 역직렬화 이전**에 본문 크기를 차단하기 위해 존재한다(클래스 Javadoc: *"대용량 페이로드로 파서 메모리를 소진시키는 pre-parse DoS(CWE-770 / OWASP API4)를 막지 못한다. 본 필터가 파싱 전에 상한을 적용한다"*). 라벨 PUT 핸들러에 **도달하는 모든 요청**이 이 상한을 통과해야 의미가 있다.
- **현재 동작(이슈 내용)**: 필터는 **디코딩되지 않은 원시 URI**(`getRequestURI()`)에 정규식을 적용하는데, Spring MVC 는 **디코딩된 경로**로 핸들러를 매핑한다. 두 해석이 어긋나 `labels` 의 한 글자만 퍼센트 인코딩해도 필터는 스킵되고 핸들러는 정상 실행된다(CWE-436 Interpretation Conflict).
  - `backend/src/main/java/kr/co/cudo/authoring/portal/config/PortalLabelBodySizeFilter.java:40-41, 56, 91-101`
    ```java
    private static final Pattern LABEL_PUT_PATH =
            Pattern.compile("^/v1/portal/uploads/frames/[^/]+/labels$");
    ...
    return !LABEL_PUT_PATH.matcher(pathWithinApp(request)).matches();
    ...
    private static String pathWithinApp(HttpServletRequest request) {
        String uri = request.getRequestURI();      // ← 퍼센트 인코딩이 살아있는 원시 URI
    ```
- **재현/확인 경로** (실측):
  ```bash
  # 12,960,000 바이트 JSON 준비
  # (1) 정규 경로 — 파싱 전 차단
  curl -s -o /dev/null -w "code=%{http_code} time=%{time_total}\n" -H "Authorization: Bearer $PORTAL_JWT" \
    -H "Content-Type: application/json" -X PUT --data-binary @huge.json \
    http://localhost:18081/api/v1/portal/uploads/frames/1/labels
  # → code=413 time=0.0017     (본문을 읽지도 않음)

  # (2) 'l' 만 %6C 로 인코딩 — 필터 스킵, 12MB 전량 파싱 후 거부
  curl -s -o /dev/null -w "code=%{http_code} time=%{time_total}\n" -H "Authorization: Bearer $PORTAL_JWT" \
    -H "Content-Type: application/json" -X PUT --data-binary @huge.json \
    http://localhost:18081/api/v1/portal/uploads/frames/1/%6Cabels
  # → code=400 time=0.21       (413 이 아님 = 상한 미적용)

  # (3) chunked 가드(411)도 동일하게 우회 — 인코딩 경로 + Transfer-Encoding: chunked → 200 (라벨이 실제로 교체됨)
  curl -s -w " HTTP:%{http_code}\n" -H "Authorization: Bearer $PORTAL_JWT" -H "Content-Type: application/json" \
    -H "Transfer-Encoding: chunked" -X PUT --data '[]' \
    http://localhost:18081/api/v1/portal/uploads/frames/1/%6Cabels
  # → {"success":true,"data":[]} HTTP:200
  ```
  인증/인가는 우회되지 않는다(무토큰 401 · 타 사용자 403 확인). 우회되는 것은 **본문 크기·chunked 가드뿐**이다.
- **영향**: CWE-436 / CWE-770 (OWASP API4:2023 Unrestricted Resource Consumption). 인증된 PORTAL_USER 가 임의 크기 JSON 을 Jackson 에 밀어 넣어 힙·CPU 를 소모시킬 수 있다(멀티 커넥션 병행 시 증폭). chunked 차단 우회는 Content-Length 조차 선언하지 않은 무제한 스트림을 허용한다. A 클러스터에서 확인된 "HMAC 필터 URL 인코딩 우회(CWE-436)" 와 **동일 계열의 반복 결함**이다.
- **수정 방향(제안)**: 필터의 경로 판정을 원시 URI 정규식에서 **정규화·디코딩된 경로 매칭**으로 교체한다 — Spring 이 이미 사용하는 `ServletRequestPathUtils.parseAndCache(request)` + `PathPattern`(또는 최소한 `UriUtils.decode(uri, UTF_8)` 후 매칭 + 중복 슬래시 정규화). 더 견고한 대안은 경로 판정을 없애고 **모든 요청에 대해** `HandlerInterceptor`(핸들러 확정 후) 또는 컨트롤러 진입 시점의 `ContentCachingRequestWrapper` 상한을 쓰는 것이다. 회귀 가드로 `PortalLabelBodySizeFilterTest` 에 퍼센트 인코딩 경로(`%6Cabels`, `%2Fabels` 등) 케이스를 추가할 것.

---

## 6. 검증 중 생성/변경한 테스트 데이터 (참고)

- `ls_portal_uld` 신규 행: `portal-qa-1`(uld_sn 1~4·5~54·76), `portal-qa-2`(74·75·78 등) — 이미지 자산
- 데이터 조작(원복하지 않은 항목): `uld_sn=1` 의 `orgnl_file_nm`(CRLF 주입 문자열, TC-056 근거) · `uld_sn=3` 의 `file_path_nm='/etc/passwd'`(TC-057 근거)
- 임시 DB 제약 `qa_tmp_chk` 는 **추가 직후 DROP 완료**(현재 미존재)
- 고아 파일 1건 존치(F-ISSUE-62 물증): `/app/storage/raw/portal/images/87b5ba03-6f4e-4640-af90-afa73e8ab352.jpeg`


### [F-ISSUE-21] TC-PORTAL-045 인접 — 포털 사용자 라벨 저장이 **비SKELETON 타입에는 좌표 유한성·경계 검증을 전혀 하지 않는다**
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: SKELETON 에 적용된 fail-closed 검증(유한성·음수 거부)은 좌표를 저장하는 모든 타입에 적용돼야 한다. 저장된 좌표는 FE 캔버스가 `number` 로 소비하므로 비유한값이 들어가면 렌더가 깨지고, 경계를 벗어난 좌표는 학습데이터로서 의미가 없다. 내부 경로는 `LabelService.validateWithinBounds` 로 **400 거부**(CLAUDE.md ★3 "사용자 저장 = 400 거부")를 이미 강제한다.
- **현재 동작(이슈 내용)**: `PortalLabelService.saveUserLabel` 은 타입 분기에서 SKELETON 만 `validateSkeletonPoints` 로 보내고, 그 외는 "빈 좌표인지"만 본다.
  ```java
  // PortalLabelService.java:209-217
  if (LsDataLbl.TYPE_SKELETON.equals(req.lblTypeCd())) {
      validateSkeletonPoints(req.points());          // 유한성·범위·개수 검증
  } else {
      if (parsePoints(req.points()).isEmpty()) {     // ← 비어있는지만 본다
          throw new CustomException(ErrorCode.INVALID_INPUT, "points 좌표가 비어있습니다.");
      }
  }
  ```
  실측: `{"lblTypeCd":"BBOX","points":"[[1e400,1e400],[2,2]]"}` → **201**(userLblSn=5). 로드 시
  `GET /v1/portal/frames/1/labels` 응답이 `"points":[["Infinity","Infinity"],[2.0,2.0]]` — Jackson 이 비유한 double 을 **문자열**로 직렬화해 `number[][]` 계약이 깨진다. 또한 `[[-99999,-99999],[999999999,999999999]]` 도 **201**(경계 검증 부재).
- **재현/확인 경로**:
  ```bash
  TOK=$(curl -s -XPOST localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
     -d '{"role":"PORTAL_USER","channel":"PORTAL","userNo":"3001"}' | jq -r .data.token)
  curl -s -XPOST localhost:18081/api/v1/portal/user-labels -H "Authorization: Bearer $TOK" \
     -H 'Content-Type: application/json' \
     -d '{"sourceRawSn":4,"sourceSrcSn":1,"lblTypeCd":"BBOX","label":"inf","points":"[[1e400,1e400],[2,2]]"}'   # 201
  curl -s localhost:18081/api/v1/portal/frames/1/labels -H "Authorization: Bearer $TOK" | grep Infinity
  ```
- **영향**: 데이터 정합(학습데이터로 쓸 수 없는 좌표 적재) + FE 계약 파손(CWE-20 입력 검증 미흡). PII 노출은 아님.
- **수정 방향(제안)**: `PortalLabelService.saveUserLabel` 의 else 분기에 ①`Double.isFinite` 검사 ②(가능하면) 프레임 해상도 기반 경계 검사를 추가한다. 내부 `LabelService` 의 검증 헬퍼를 공용 유틸로 추출해 두 경로가 같은 판정기를 쓰게 하는 편이 드리프트를 막는다. ⚠ 구현하지 않음.


### [F-ISSUE-22] TC-PORTAL-040/046 인접 — 포털 사용자 라벨 **좌표 개수 상한 부재** (내부 경로는 1,000점 상한)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 라벨 1건의 좌표 수는 상한을 가져야 한다. 내부 라벨 경로는 `LabelService.MAX_POINTS_PER_LABEL = 1000` 을 두고 초과 시 400 또는 단순화(`PolygonSimplifier`)한다. 외부 채널(포털)은 인증만 있으면 누구나 호출하므로 상한이 더 필요하다.
- **현재 동작(이슈 내용)**: 포털 저장 경로에는 개수 검사가 없다(`PortalLabelService.java:209-220` 전체에 `size()` 비교 없음). 실측: 30,000점 POLYGON(요청 본문 458KB) → **201**(userLblSn=8). 이후 `GET /v1/portal/frames/1/labels` 응답이 **519,395 bytes** 로 부풀고, 이 엔드포인트는 **페이징이 없어** 같은 프레임에 이런 라벨을 여러 건 쌓으면 응답이 선형 증가한다.
  ```
  $ curl ... /v1/portal/frames/1/labels -o f.json -w '%{size_download}'
  519395
  ```
- **재현/확인 경로**: `points` 에 `[[i,i] for i in range(30000)]` JSON 을 넣어 `POST /v1/portal/user-labels` → 201. `GET /v1/portal/frames/{srcSn}/labels` 응답 크기 확인.
- **영향**: 자원 소진(CWE-770 / OWASP API4:2023) — 저장·조회 양쪽. `portal.upload.max-label-body-bytes`(2MB)는 **포털 업로드 라벨 PUT 전용 필터**라 이 엔드포인트를 막지 않는다.
- **수정 방향(제안)**: `PortalLabelService.saveUserLabel` 에 `MAX_POINTS_PER_LABEL` 동일 상한(공용 상수 참조)을 적용해 초과 시 400. 아울러 `loadFrameLabels`/`listMyLabels` 응답에 항목 상한 또는 페이징 도입. ⚠ 구현하지 않음.


### [F-ISSUE-24] TC-PORTAL-041 인접 — `sourceSrcSn` 의 **존재 여부·부모 영상 소속을 검증하지 않아** APPROVED 게이트가 프레임 축에서 무력화된다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 저장 요청의 `sourceSrcSn` 은 ①실재하는 프레임이어야 하고 ②`sourceRawSn` 에 속해야 한다. 그래야 "APPROVED 영상만 작업 가능"이라는 게이트가 프레임 단위로도 성립하고, 저장된 행이 로드 경로(프레임 기준 조회)와 일관된다.
- **현재 동작(이슈 내용)**: 게이트는 `sourceRawSn` 하나만 본다.
  ```java
  // PortalLabelService.java:203-206
  if (!isExposedToDatamart(req.sourceRawSn())) { ... throw FORBIDDEN; }
  // sourceSrcSn 은 이후 어디에서도 조회·대조되지 않고 그대로 적재된다 (:218-220)
  ```
  실측 ①존재하지 않는 프레임: `{"sourceRawSn":900,"sourceSrcSn":9001}` → **201**(rawSn 900 의 실제 프레임은 429,430). ②타 영상 프레임: `{"sourceRawSn":4,"sourceSrcSn":35}` (srcSn 35 는 **미승인** `rawSn=7` 소속) → **201**(userLblSn=19).
  DB 스키마상 FK 는 `src_raw_sn → ls_data_raw` 하나뿐이고 `src_data_src_sn` 에는 FK 가 없다(`\d ls_portal_user_label`).
- **재현/확인 경로**:
  ```bash
  curl -XPOST .../v1/portal/user-labels -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
    -d '{"sourceRawSn":4,"sourceSrcSn":35,"lblTypeCd":"BBOX","label":"cross","points":"[[1,1],[2,2]]"}'   # 201
  # 대조: 그 프레임을 읽으려 하면 정상적으로 막힌다
  curl .../v1/portal/frames/35/labels -H "Authorization: Bearer $TOK"   # 403 (미승인 영상)
  ```
- **영향**: 데이터 정합 위주. **정보 유출은 없다**(로드 경로 `loadFrameLabels` 가 프레임의 실제 `rawSn` 으로 다시 게이팅해 403/412 를 낸다 — 실측 확인). 다만 미승인·신고 영상 프레임 번호에 매달린 고아 라벨이 무제한 쌓이고, 향후 다운로드/집계가 프레임 기준으로 조인하면 잘못된 영상에 라벨이 붙는다(CWE-20 / 약한 형태의 CWE-639).
- **수정 방향(제안)**: `saveUserLabel` 에서 `srcRepository.findById(sourceSrcSn)` 로 프레임을 조회해 부재 시 404, `frame.getRawSn() != sourceRawSn` 이면 400/403. 겸사 `src_data_src_sn` 에 FK 추가 검토. ⚠ 구현하지 않음.


### [F-ISSUE-42] TC-PORTAL-072 — FE 포털 라벨링 도구바가 SAM2(AI 분할/추적)·스켈레톤을 노출하고, 테스트가 그 노출을 기대값으로 고정
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 포털 모드 라벨링 화면은 수동 도형(BBOX/POLYGON)만 제공해야 한다(ADR-013). BE 를 닫아도 FE 가 버튼을 노출하면 사용자에게 미제공 기능을 광고하게 되고, 반대로 FE 만 닫으면 API 는 열린 채로 남는다.
- **현재 동작(이슈 내용)**: 포털 모드에서 SAM2 버튼이 노출되며, **회귀 테스트가 이 노출을 단언**한다.
  - `frontend/src/features/portal/__tests__/LabelingPagePortalRestrictions.test.tsx:108-124`
    ```tsx
    // Phase 9 (ADR-013 override) — 포털 캔버스 좌측 도구바에 SAM2 분할/추적·키포인트 노출.
    it('포털_라벨링_도구바_SAM2_분할_추적_키포인트_노출_Phase9', async () => {
      expect(screen.getByRole('button', { name: 'AI 분할' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'AI 추적' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: '스켈레톤' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'AI 탐지' })).toBeNull();  // YOLO만 숨김
    });
    ```
  - API 배선: `frontend/src/features/label/api.ts:604-610`(track), `:812-823`(segment) 가 `portalMode` 시 `/portal/frames/{id}/sam2-*` 로 분기. 소비처 `canvas/CanvasShell.tsx:40-41`, `canvas/tools/Sam2TrackTool.tsx:43-44`, `hooks/useSam2Segment.ts:40`, `hooks/useSam2Track.ts:34`, `components/ObjectAttributePanel.tsx:89`, `pages/label/LabelingPage.tsx:1511-1516`.
  - 대조군(정상): 같은 파일 `:86-105` 는 검수제출 버튼·VLM/시계열 메타 **부재**를 단언한다(TC-073/074 PASS) — 즉 다른 미제공 기능은 제대로 닫혀 있고 SAM2 만 예외.
- **재현/확인 경로**: 포털 토큰(`channel=PORTAL`)으로 `/label/{srcSn}` 진입 → 좌측 도구바에 `AI 분할`·`AI 추적`·`스켈레톤` 버튼 표시. 정적으로는 위 테스트 파일·`api.ts` 분기 확인.
- **영향**: 기능 범위(ADR-013) 위반의 사용자 접점. 테스트가 위반 상태를 고정하고 있어, F-ISSUE-41 을 BE 에서 닫으면 **이 FE 테스트가 RED 로 뒤집히는** 구조적 결합이 있다(수정 시 함께 반전 필요).
- **수정 방향(제안)**: ⚠ 구현하지 않음. F-ISSUE-41 의 정책 확정 결과에 종속. "미제공"으로 확정되면 도구바 렌더 조건에 `!portalMode` 를 추가(현재 `showMeta = !portalMode` 와 동일 패턴, `LabelingPage.tsx:474-475`)하고 위 테스트를 `queryByRole(...)` **null 단언**으로 반전한다. `api.ts` 의 포털 분기와 `PortalSam2*` 도 함께 제거.


### [F-ISSUE-61] TC-PORTALUP-004 — `files` 파트 부재 업로드가 400 이 아니라 500(스택트레이스 로깅)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 업로드할 이미지가 없는 요청은 **클라이언트 입력 오류(400 INVALID_INPUT)** 로 거부돼야 한다. 5xx 로 나가면 ①클라이언트가 재시도 가능한 서버 장애로 오인하고 ②`GlobalExceptionHandler` 최종 `Exception` 핸들러가 **ERROR + 스택트레이스**를 남겨 내부 프레임워크/필터 체인이 로그에 노출된다(CWE-209). 모니터링상 5xx 알람도 오염된다.
- **현재 동작(이슈 내용)**: `MissingServletRequestPartException` 이 `GlobalExceptionHandler` 에 매핑돼 있지 않다. 형제 클래스인 `MissingServletRequestParameterException` 만 400 으로 매핑돼 있어 누락이 눈에 띄지 않는다.
  - `backend/src/main/java/kr/co/cudo/authoring/common/exception/GlobalExceptionHandler.java:76-77`
    ```java
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
    ```
  - 실측 서버 로그: `ERROR ... GlobalExceptionHandler - [Exception] unhandled exception` + `org.springframework.web.multipart.support.MissingServletRequestPartException: Required part 'files' is not present.`
  - 서비스의 빈 목록 가드(`PortalUploadService.java:87-89`)는 **도달조차 하지 않는다**(컨트롤러 바인딩 단계에서 예외).
- **재현/확인 경로**:
  ```bash
  curl -s -w "\n%{http_code}\n" -H "Authorization: Bearer $PORTAL_JWT" \
       -X POST http://localhost:18081/api/v1/portal/uploads/images
  # → {"success":false,...,"errorCode":"INTERNAL_ERROR"} / 500
  # 참고: -F "files=" (빈 값) 도 동일하게 500
  ```
- **영향**: CWE-209(내부 정보 노출 — 스택트레이스 ERROR 로깅) + 오류 분류 오염(4xx→5xx). 인증 필요 경로라 외부 무인증 공격면은 아니나, PORTAL_USER 누구나 5xx 알람을 유발할 수 있다.
- **수정 방향(제안)**: `GlobalExceptionHandler` 에 `@ExceptionHandler(MissingServletRequestPartException.class)`(또는 상위 `ServletRequestBindingException`) 를 추가해 `ErrorCode.INVALID_INPUT`(400) 으로 매핑. 컨트롤러 테스트에 "files 파트 없는 업로드 → 400" 케이스 추가.


### [F-ISSUE-62] TC-PORTALUP-014 — 프레임 INSERT 가 커밋 시점에 실패하면 업로드 파일이 고아로 영구 잔존
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `uploadImages` 는 all-or-nothing 이다(클래스 Javadoc `#4 고아 파일 방지: write 후 DB INSERT 실패 시 이번 요청에 기록한 파일을 즉시 보상 삭제`). DB 가 롤백되면 디스크에도 아무것도 남지 않아야 한다. 남으면 ①소유 레코드 없는 **사용자 업로드 원본**이 스토리지에 무기한 잔존해 파기 요구(개인정보 보존기간)를 만족할 수 없고 ②어떤 스윕도 회수하지 않아 누적된다.
- **현재 동작(이슈 내용)**: `LsPortalUldFrme` 의 PK 전략이 `SEQUENCE` 라 `frmeRepository.save()` 는 INSERT 를 **큐에만 넣고** 실제 실행은 트랜잭션 커밋 시 flush 에서 일어난다. 그 실패는 `uploadImages` 의 try/catch **밖**이라 `rollbackFiles(writtenThisRequest)` 가 실행되지 않는다.
  - `PortalUploadService.java:133-147`
    ```java
    LsPortalUldFrme frme = frmeRepository.save(              // ← SEQUENCE: 실제 INSERT 는 커밋 시 flush
            LsPortalUldFrme.create(savedUld.getUldSn(), IMAGE_FRAME_NO, dst.toString()));
    ...
    } catch (Exception e) {
        rollbackFiles(writtenThisRequest);                    // ← 커밋 단계 실패에는 도달하지 않음
    ```
  - `LsPortalUldFrme.java:36-37` → `@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "lsPortalUldFrmeSeq")` (반면 `LsPortalUld.java:44-45` 는 IDENTITY 라 즉시 INSERT)
  - 단위테스트 `PortalUploadServiceTest#orphanFileRemovedOnDbFailure` 는 `frmeRepository.save()` 를 **모킹해 즉시 throw** 시키므로 이 지연 특성을 재현하지 못한다.
  - 회수 수단 부재: `PortalUploadSweepJob` 은 만료 TUS 임시파일과 `frames/{uldSn}/` 디렉터리만 정리하며 `portal/images/` 고아는 대상이 아니다(`PortalUploadSweepJob.java:67-113`).
- **재현/확인 경로** (실측 완료 — 임시 제약은 즉시 DROP 했음):
  ```sql
  ALTER TABLE ls_portal_uld_frme ADD CONSTRAINT qa_tmp_chk CHECK (frme_no <> 0) NOT VALID;
  ```
  ```bash
  docker exec klid-backend sh -c "ls /app/storage/raw/portal/images | wc -l"   # 68
  curl -s -w "\n%{http_code}\n" -H "Authorization: Bearer $PORTAL_JWT" \
       -F "files=@ok.jpg;filename=orphanprobe2.jpg" \
       http://localhost:18081/api/v1/portal/uploads/images                      # 500 INTERNAL_ERROR
  docker exec klid-backend sh -c "ls /app/storage/raw/portal/images | wc -l"   # 69  ← 고아 1건
  ```
  ```sql
  ALTER TABLE ls_portal_uld_frme DROP CONSTRAINT qa_tmp_chk;
  SELECT count(*) FROM ls_portal_uld WHERE orgnl_file_nm='orphanprobe2.jpg';   -- 0 (DB 는 정상 롤백)
  ```
  잔존 파일: `/app/storage/raw/portal/images/87b5ba03-6f4e-4640-af90-afa73e8ab352.jpeg`
- **영향**: CWE-459(Incomplete Cleanup) + 저장소 고갈(OWASP API4). 고아 파일은 **사용자 업로드 원본 이미지**이므로 소유·삭제 경로가 사라진 개인 자산이 남는다(CWE-359 인접).
- **수정 방향(제안)**: ①업로드 트랜잭션에 `TransactionSynchronization`(`afterCompletion(STATUS_ROLLED_BACK)`) 을 등록해 커밋 실패까지 포함한 보상 삭제를 걸거나 ②프레임 저장 직후 `saveAndFlush`(또는 `entityManager.flush()`)로 INSERT 실패를 메서드 내부로 끌어오거나 ③주기 스윕에 `portal/images/` ↔ `LS_PORTAL_ULD.FILE_PATH_NM` 대조 고아 회수를 추가. 아울러 단위테스트를 모킹 대신 실 DB(Testcontainers) 제약 위반으로 바꿔 지연 flush 를 재현할 것.


### [F-ISSUE-81] TC-TUS-014 — PATCH 의 Content-Type 불일치/부재가 415 가 아니라 500 + 스택트레이스 ERROR 로 처리됨
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `@PatchMapping(consumes = "application/offset+octet-stream")` 과 다른 Content-Type 으로 온 PATCH 는 **415 Unsupported Media Type**(TUS 1.0 도 415 를 규정)으로 거부돼야 한다. 클라이언트가 "내 요청이 잘못됐다"를 알 수 있어야 하고, 정상적인 클라이언트 오류가 서버 장애로 집계·알림되면 안 된다.
- **현재 동작(이슈 내용)**: `HttpMediaTypeNotSupportedException` 을 처리하는 핸들러가 `GlobalExceptionHandler` 에 없어 **generic 핸들러로 낙하 → 500 + `INTERNAL_ERROR`**, 동시에 `[Exception] unhandled exception` 으로 **ERROR 레벨 전체 스택트레이스**가 로그에 남는다.
  ```
  2026-08-02 18:02:37.110 ERROR k.c.c.a.c.e.GlobalExceptionHandler - [Exception] unhandled exception
  org.springframework.web.HttpMediaTypeNotSupportedException: Content-Type 'application/octet-stream' is not supported
      at org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping.handleNoMatch(...)
  ```
  응답: `{"success":false,"data":null,"message":"서버 내부 오류가 발생했습니다.","errorCode":"INTERNAL_ERROR"}` (500)
  **포털 TUS 전용이 아니라 관제 내부 TUS(`PATCH /v1/uploads/{id}`)도 동일하게 500** 이다(양쪽 실측).
- **재현/확인 경로**:
  ```bash
  curl -s -w " <%{http_code}>" -X PATCH -H "Authorization: Bearer $PORTAL_TOKEN" \
    -H "Tus-Resumable: 1.0.0" -H "Upload-Offset: 0" \
    -H "Content-Type: application/octet-stream" --data-binary @chunk.bin \
    http://localhost:18081/api/v1/portal/uploads/tus/00000000-0000-0000-0000-000000000000
  # → 500 INTERNAL_ERROR (기대: 415)
  # Content-Type 자체를 생략해도 동일하게 500
  ```
- **영향**: ①프로토콜 오분류 — 표준 TUS 클라이언트가 415(요청 수정)와 500(서버 장애, 재시도 대상)을 구분 못 해 무의미한 재시도를 반복한다. ②운영 노이즈/오탐 — 클라이언트 실수가 ERROR 스택트레이스로 남아 장애 알림·SLO 를 오염시킨다. ③CWE-209 인접 — 내부 프레임워크 예외 타입·스택이 로그에 그대로 적재된다(응답 본문 유출은 없음).
- **수정 방향(제안)**: `common/exception/GlobalExceptionHandler` 에 `@ExceptionHandler(HttpMediaTypeNotSupportedException.class)` 를 추가해 **415 + 도메인 errorCode**(예: `UNSUPPORTED_MEDIA_TYPE`)로 매핑하고 WARN 레벨로 낮춘다. 함께 `HttpRequestMethodNotSupportedException`(405)·`HttpMediaTypeNotAcceptableException`(406)도 같은 계층에서 처리 여부를 점검할 것. ⚠ 구현은 하지 않음.


### [F-ISSUE-82] TC-TUS-002 — 세션 생성 `Location` 헤더가 context-path `/api` 를 누락해 표준 TUS 클라이언트가 따라갈 수 없음
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: TUS 1.0 은 POST 응답의 `Location` 을 **이후 HEAD/PATCH/DELETE 에 그대로 사용하는 URL** 로 규정한다. 컨트롤러 javadoc 이 명시적으로 *"응답: TUS 1.0 헤더 기반(ApiResponse 미사용 — **표준 tus-js-client 호환**)"*(`PortalTusUploadController.java:38-40`)을 표방하므로, Location 은 실제로 도달 가능한 경로여야 한다.
- **현재 동작(이슈 내용)**: 애플리케이션 context-path 가 `/api`(`application.yml:126-127`)인데 Location 은 `/api` 없이 조립된다.
  ```java
  // PortalTusUploadController.java:94-97
  return ResponseEntity.status(HttpStatus.CREATED)
          .header(H_RESUMABLE, TUS_VERSION)
          .header(HttpHeaders.LOCATION, "/v1/portal/uploads/tus/" + uldId)   // ← "/api" 누락
          .build();
  ```
  실측: `Location: /v1/portal/uploads/tus/7cc90fe6-67e7-40bb-ae57-930fb3096a07`. 이 절대경로를 origin 기준으로 해석하면 `http://host/v1/portal/uploads/tus/{id}` 로 **404**. 자체 FE(`frontend/src/features/upload/api/tusClient.ts:99` `location.split('/').pop()`)는 **마지막 세그먼트만 잘라 endpointBase 로 재조립**하기 때문에 우연히 동작한다. 관제 내부 `TusUploadController.java:96` 도 동일 패턴.
- **재현/확인 경로**:
  ```bash
  L=$(curl -s -D - -o /dev/null -X POST -H "Authorization: Bearer $PORTAL_TOKEN" \
      -H "Tus-Resumable: 1.0.0" -H "Upload-Length: 1024" \
      -H "Upload-Metadata: filename $(printf a.mp4|base64)" \
      http://localhost:18081/api/v1/portal/uploads/tus | grep -i '^location')
  echo "$L"                       # Location: /v1/portal/uploads/tus/{uuid}
  curl -s -o /dev/null -w "%{http_code}\n" -I "http://localhost:18081/v1/portal/uploads/tus/{uuid}"  # 404
  ```
- **영향**: 기능(외부 연동) — 문서가 표방한 tus-js-client 등 표준 클라이언트 연동이 즉시 깨진다. 현재는 자체 FE 의 "마지막 세그먼트만 사용" 우회에 의존하고 있어, FE 가 표준 라이브러리로 교체되거나 외부 파트너가 붙는 순간 드러난다.
- **수정 방향(제안)**: `ServletUriComponentsBuilder.fromCurrentRequest()` 또는 `request.getContextPath()` 를 이용해 context-path 포함 URL 을 조립(포털·관제 두 컨트롤러 동시). 리버스 프록시 경로 재작성 가능성을 고려해 `server.forward-headers-strategy` 와 함께 검토. ⚠ 구현은 하지 않음.


### [F-ISSUE-85] TC-TUS-011/029 — CANCELLED 세션에 HEAD 하면 204 + 실제로는 존재하지 않는 offset 을 돌려준다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 재개(resume)의 진입점은 HEAD 다. 종료된(취소된) 업로드에 대해 서버는 **더 이상 재개 대상이 아님**을 HEAD 단계에서 알려야(TUS 1.0: 404) 클라이언트가 새 세션을 만드는 정상 동선으로 갈 수 있다.
- **현재 동작(이슈 내용)**: `getForOwner` 는 소유자·만료만 검사하고 **취소 여부를 보지 않는다**.
  ```java
  // PortalVideoUploadService.java:126-137
  LsPortalTusUpload session = tusRepository.findById(uldId).orElseThrow(... NOT_FOUND ...);
  if (!session.isOwnedBy(portalUserNo)) { throw ... FORBIDDEN ...; }
  if (session.isExpired(java.time.LocalDateTime.now())) { throw ... GONE ...; }   // isExpired 는 IN_PROGRESS 만 판정
  return session;                                                                  // CANCELLED 여도 그대로 반환
  ```
  실측 시나리오(TC-031 레이스 산출물): 느린 PATCH 로 614,400B 를 커밋한 직후 DELETE 가 착지 → DB `stts_cd=CANCELLED, uld_offset=614400`, **임시파일은 삭제됨**. 이 세션에 HEAD → **204 `Upload-Offset: 614400`**. 클라이언트는 "614,400B 부터 이어서 보내면 된다"고 판단하지만 이어지는 PATCH 는 409 `취소된 업로드 세션입니다` 로 거부된다(파일도 이미 없다).
- **재현/확인 경로**:
  ```bash
  # 세션 생성 → 청크 일부 PATCH → DELETE → HEAD
  curl -s -o /dev/null -w "%{http_code}\n" -X DELETE -H "Authorization: Bearer $T" -H "Tus-Resumable: 1.0.0" $B/v1/portal/uploads/tus/$S
  curl -s -I -H "Authorization: Bearer $T" -H "Tus-Resumable: 1.0.0" $B/v1/portal/uploads/tus/$S | grep -iE "HTTP|Upload-Offset"
  # → HTTP/1.1 204 / Upload-Offset: 614400   (기대: 404 또는 410)
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select stts_cd,uld_offset from ls_portal_tus_uld where uld_id='$S';"
  # → CANCELLED | 614400  (파일은 삭제 상태)
  ```
- **영향**: 기능/데이터정합 — 재개 클라이언트가 무효 세션을 유효한 것으로 오인해 불필요한 재시도 루프에 빠지고, 사용자에게는 "이어받기 가능"으로 표시된 뒤 실패한다. DB offset 과 실제 파일(부재)의 불일치가 그대로 노출된다. 보안 영향은 없다(소유자 검증은 선행되고 파일도 이미 삭제).
- **수정 방향(제안)**: `getForOwner` 에 `if (session.isCancelled()) throw new CustomException(ErrorCode.NOT_FOUND, ...)` 를 추가(TUS 1.0 의 terminated upload → 404 규약). 취소 후 오래 지난 세션의 존재 여부 오라클을 만들지 않도록 **404 로 통일**하는 편이 410 보다 안전하다. 관제 내부 TUS 도 동일 패턴인지 확인. ⚠ 구현은 하지 않음.


### [F-ISSUE-86] TC-TUS-006 — TUS 세션 생성에 rate limit 이 없고 종결 세션 행이 영구 잔존해 create/cancel 루프로 무제한 증식 가능
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 포털은 외부 채널이므로 인증된 사용자라도 자원 소비를 제한해야 한다(OWASP API4:2023 Unrestricted Resource Consumption). 같은 포털 도메인의 **이미지 업로드(`PortalUploadController:189-192`, config `portalUpload`)와 SAM2(`PortalSam2Service:286-289`, config `portalSam2`)에는 per-user RateLimiter 가 이미 적용**돼 있어, TUS 만 빠진 것은 방어 일관성의 구멍이다.
- **현재 동작(이슈 내용)**: `PortalTusUploadController`·`PortalVideoUploadService` 어디에도 RateLimiter 배선이 없다(`grep -rn "RateLimit" portal/` → `PortalUploadController`·`PortalSam2Service` 2곳만 매치). 유일한 억제 수단은 `MAX_CONCURRENT_IN_PROGRESS = 3`(`Service:52,96-102`)인데, 이는 **IN_PROGRESS 만 세므로** create→cancel 을 반복하면 무한히 우회된다. 게다가 정리 스윕은 **IN_PROGRESS 만 삭제**한다:
  ```java
  // LsPortalTusUploadRepository
  @Query("SELECT u FROM LsPortalTusUpload u WHERE u.sttsCd = 'IN_PROGRESS' AND u.expiresAt < :now")
  List<LsPortalTusUpload> findExpired(...);
  @Query("DELETE FROM LsPortalTusUpload u WHERE u.uldId = :uldId AND u.sttsCd = 'IN_PROGRESS'")
  int deleteExpiredInProgress(...);
  ```
  → **CANCELLED/COMPLETED 행은 어떤 경로로도 삭제되지 않는다**(`PortalUploadSweepTxService.claimExpiredSessions` 실측). 세션 생성 시마다 `TusChunkStore.createEmptyFile` 로 0바이트 파일도 만들어진다(cancel 시 삭제되긴 함).
- **재현/확인 경로**:
  ```bash
  # POST → DELETE 를 N회 반복해도 429 없이 계속 성공하고, 행은 계속 쌓인다
  for i in $(seq 1 100); do
    L=$(curl -s -D - -o /dev/null -X POST -H "Authorization: Bearer $T" -H "Tus-Resumable: 1.0.0" \
        -H "Upload-Length: 1024" -H "Upload-Metadata: filename $(printf a.mp4|base64)" \
        $B/v1/portal/uploads/tus | grep -i '^location' | tr -d '\r' | awk '{print $2}')
    curl -s -o /dev/null -X DELETE -H "Authorization: Bearer $T" -H "Tus-Resumable: 1.0.0" $B$L
  done
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select stts_cd,count(*) from ls_portal_tus_uld group by stts_cd;"
  # 본 검증 세션만으로도 CANCELLED 20건이 영구 잔존 상태로 남았다
  ```
- **영향**: 가용성/저장소(CWE-770). 인증된 포털 사용자 1인이 `LS_PORTAL_TUS_ULD` 를 무제한 증식시킬 수 있고, 종결 행이 정리되지 않아 시간이 지날수록 테이블이 단조 증가한다. 부분 인덱스(`idx_lptu_user_stts`, `idx_lptu_expry`)가 IN_PROGRESS 조건부라 조회 성능 영향은 제한적이지만 스토리지·백업 비용은 누적된다.
- **수정 방향(제안)**: ①`PortalTusUploadController.create` 에 `PortalUploadController` 와 동일한 per-user RateLimiter(`portalTusCreate` config 신설 또는 `portalUpload` 재사용) 적용. ②스윕(`PortalUploadSweepTxService`)에 **종결 세션 보존기간(retention) 정리**를 추가 — `CANCELLED` 이고 `mdfcn_dt < now - N일` 인 행 조건부 벌크 삭제. `COMPLETED` 는 `uld_sn` 추적 가치가 있으니 보존기간을 따로 잡을 것. ⚠ 구현은 하지 않음.


### [F-ISSUE-88] TC-TUS-022/027 — 완료 결과(uldSn·completed)가 HTTP 응답에 노출되지 않고, FE 도 완료 시 목록을 무효화하지 않아 업로드한 영상이 화면에 안 보인다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 서비스가 완료 시 `uldSn` 을 산출하므로(`PortalTusPatchResult(newOffset, completed, uldSn)`) 클라이언트는 어떤 자산이 만들어졌는지 알 수 있어야 하고, 최소한 사용자가 방금 올린 영상이 "내 업로드" 목록에 즉시 나타나야 한다.
- **현재 동작(이슈 내용)**: 컨트롤러가 `result.completed()`·`result.uldSn()` 을 **버리고** offset 만 반환한다.
  ```java
  // PortalTusUploadController.java:136-142
  PortalVideoUploadService.PortalTusPatchResult result =
          uploadService.appendChunk(uldId, requireUser(actor), uploadOffset, in, contentLength);
  return ResponseEntity.noContent()
          .header(H_RESUMABLE, TUS_VERSION)
          .header(H_UPLOAD_OFFSET, String.valueOf(result.newOffset()))   // completed/uldSn 미노출
          .build();
  ```
  실측: 완료 청크 PATCH → `HTTP/1.1 204` + `Upload-Offset: 3390` 뿐(본문 없음, 커스텀 헤더 없음). DB 에는 `uld_sn=57` 이 정상 기록됨.
  FE 쪽도 보완이 없다 — `PortalUploadPage.tsx:95-99` 의 `onVideoStart` 는 `tus.start(...).catch(() => undefined)` 만 하고 **`queryClient.invalidateQueries` 를 호출하지 않으며**, 목록 훅 `usePortalUploads`(`hooks/usePortalUploads.ts:17-29`)의 폴링은 **이미 캐시에 있는 목록에 진행중 자산이 있을 때만** 동작한다(`pollIntervalFor`: 전부 종결이면 `false`). 즉 목록이 전부 READY/FAILED 인 상태에서 영상을 새로 올리면 **폴링도 무효화도 없어 새 자산이 화면에 나타나지 않는다**(이미지 업로드는 `useUploadImages.ts:31` 에서 `invalidateQueries` 를 한다 — 경로별 비대칭).
- **재현/확인 경로**:
  ```bash
  # BE: 완료 응답에 uldSn 이 없다
  curl -s -D - -o /dev/null -X PATCH -H "Authorization: Bearer $T" -H "Tus-Resumable: 1.0.0" \
    -H "Upload-Offset: 2000" -H "Content-Type: application/offset+octet-stream" \
    --data-binary @chunk2.bin $B/v1/portal/uploads/tus/$S
  # → HTTP/1.1 204 / Upload-Offset: 3390  (uldSn 없음)
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select uld_id,stts_cd,uld_sn from ls_portal_tus_uld where uld_id='$S';"
  # → COMPLETED | 57
  ```
  FE: 목록의 모든 자산이 READY 인 상태(`/portal/uploads`)에서 영상을 업로드 → 진행률 100% 도달 후에도 목록에 새 행이 추가되지 않음(수동 새로고침 필요).
- **영향**: 기능/UX. 업로드가 성공했는데 화면상 실패처럼 보인다. TUS 204 규약을 지키려는 의도는 타당하나, 그 경우 **클라이언트 측 후속 동선(목록 무효화)** 이 반드시 있어야 하는데 양쪽 다 빠져 있다.
- **수정 방향(제안)**: ⓐ최소안 — FE `PortalUploadPage.onVideoStart` 의 성공 콜백에서 `qc.invalidateQueries({ queryKey: PORTAL_KEYS.all })`(이미지 경로와 동일 패턴) 호출. ⓑ보강안 — BE 가 완료 시 커스텀 헤더(예: `X-Portal-Uld-Sn`)를 함께 내려 클라이언트가 생성된 자산을 직접 지목할 수 있게 한다(204 본문 없는 TUS 규약과 양립). **ⓐ 는 필수, ⓑ 는 선택**. ⚠ 구현은 하지 않음.


### [F-ISSUE-89] F-8 전반 — TUS 컨트롤러 계층 자동 테스트가 0건이라 헤더 프로토콜·동시성·만료가 회귀 무방비
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: TUS 는 **전적으로 HTTP 헤더로 동작하는 프로토콜**이라 서비스 단위 테스트만으로는 계약을 보호할 수 없다. 상태코드·헤더 조합(201+Location, 204+Upload-Offset, 412, 413, 410, 409, no-store)이 회귀 테스트로 고정돼야 한다.
- **현재 동작(이슈 내용)**: F-8 관련 자동 테스트는 `PortalVideoUploadServiceTest.java`(11 테스트, 서비스 계층 + fake repository) **단 1 파일**이다. `grep -rln "PortalTus|PortalVideoUpload|portal/uploads/tus" backend/src/test` 결과에 **MockMvc/`@WebMvcTest`/`@SpringBootTest` 기반 컨트롤러·IT 테스트가 없다**. 결과적으로 F-8 32건 중 **21건(TC-001·002·003·006·008·009·010·011·013·015·016·017·018·019·020·024·026·028·029·030·031)이 미커버**다. 미커버 항목에는 이번 검증에서 실제로 결함이 나온 지점(Tus-Resumable 부재 통과=F-ISSUE-83, Location 조립=F-ISSUE-82, CANCELLED HEAD=F-ISSUE-85)이 전부 포함된다.
- **재현/확인 경로**:
  ```bash
  grep -rln "PortalTus\|PortalVideoUpload\|portal/uploads/tus" backend/src/test
  # → PortalUploadPropertiesBindingTest / PortalUploadSweepIT / PortalUploadSweepTxServiceTest
  #    PortalVideoUploadServiceTest / V111PortalTusUploadMigrationTest   (컨트롤러 테스트 없음)
  grep -c "@DisplayName" backend/src/test/java/kr/co/cudo/authoring/portal/PortalVideoUploadServiceTest.java   # → 11
  ```
- **영향**: 품질/회귀. 헤더 계약이 코드 리팩터로 조용히 깨져도 baseline(4,755 tests) 이 전부 통과한다. 동시성(TC-020/027/028/031)·만료(TC-013)는 Testcontainers 없이는 검증 자체가 불가능한데 IT 가 없다.
- **수정 방향(제안)**: ①`PortalTusUploadControllerTest`(`@WebMvcTest` + `@WithMockUser` 또는 TokenClaims 주입)로 헤더 프로토콜 12건(TC-001·002·003·008·009·010·011·015) 고정. ②`PortalTusUploadIT`(Testcontainers PostgreSQL)로 재개 시퀀스·offset 불일치 409·만료 410·동시 PATCH 직렬화·완료 멱등(TC-013·016·018·019·020·022·027·029·030·031) 커버. ③F-ISSUE-83/85 수정 시 회귀 가드를 함께 추가. ⚠ 구현은 하지 않음.

---

## 부기 — 확증편향 반증에서 "문제 없음"으로 확인된 항목

| 반증 시도 | 결과 |
|-----------|------|
| 타 사용자 세션 하이재킹(HEAD/PATCH/DELETE) | 3경로 전부 403. `isOwnedBy` 가 HEAD 는 `getForOwner`, PATCH 는 `appendChunkTx`, DELETE 는 `cancel` 에서 **각각 독립 검사**(한 곳에 몰지 않음) |
| 세션 ID 추측 | `UUID.randomUUID()`(v4, SecureRandom). 미존재 uldId → 404, 잘못된 UUID 문자열 → 400 (존재 여부 오라클 없음) |
| 역할/채널 우회 | WORKER(2001)·REVIEWER(1001) INTERNAL 토큰 → POST/OPTIONS 모두 **403**, 무토큰 → **401**. `@PreAuthorize("hasRole('PORTAL_USER')")` + SecurityConfig 채널 격리 이중 방어 실동작 확인 |
| 확장자 우회 | `A.MP4`(대문자) 201 정상 / `a.webm`·`a.mkv`(`EXT_TO_MIME` 상수엔 존재) → **400**. allowlist(`properties.allowedExtensions()`)가 최종 게이트라 상수 잔재로는 우회 불가 |
| 매직바이트 polyglot | `ftyp` 만 위장한 파일(`corrupt.mp4`)은 매직바이트를 통과하지만 **ffprobe 2차 게이트에서 400**. 브랜드 allowlist(`VideoMagicByteValidator.ALLOWED_BRANDS`) + box size 상한(1MB)도 정적 확인 |
| 부분 쓰기 오염 | 413(청크 상한)·400(잔여 초과) 거부 후 파일 크기·DB offset 모두 불변. `TusChunkStore.writeChunkAtomically` 의 `truncateTo` 롤백 + `advanceOffset` 미호출 경로 확인 |
| 검증 실패 시 세션 고착 | `finalizeRejected` 가 `REQUIRES_NEW` 라 400 예외로 롤백되지 않음 — 실측 3케이스 모두 DB 가 CANCELLED 로 **커밋 확정**됨(IN_PROGRESS 고착 0) |
| 임의 파일 삭제(CWE-22) | `TusChunkStore.deleteQuietly(path, storageRoot)` 가 `startsWith(storageRoot)` 재검증. 저장 경로 자체가 사용자 입력 미도달이라 이중 방어 |
| 완료 이벤트 중복 | 완료 7세션 ↔ ULD 7행 ↔ 프레임 7행 (1:1:1). 조건부 UPDATE 게이트 유효 |


### [F-ISSUE-01] TC-PORTAL-020 / TC-PORTAL-027 / TC-PORTAL-035 (인접 갭: TC-PORTAL-023·024 엔드포인트) — `GET /v1/portal/datamart/labels` 에 APPROVED 게이트·비식별 신고 게이트가 **모두 부재**해 외부 채널이 내부 전 영상의 라벨 좌표를 열람

- **심각도**: **HIGH**
- **기대 동작(기대효과)**:
  포털은 **데이터마트 노출(검수 완료=APPROVED) 영상만** 접근할 수 있어야 하고(CLAUDE.md 포털 절 · `PortalLabelService.isExposedToDatamart`),
  비식별 누락 신고 구간(`DE_IDNTF_YN='F'`)에는 **라벨 좌표 조회를 차단**해야 한다(CLAUDE.md: *"라벨 좌표가 PII 위치 특정 정보이므로 스트리밍만 막는 것으로는 부족"*, 차단 범위 목록 ①).
  실제로 형제 엔드포인트 `GET /v1/portal/frames/{srcSn}/labels` 는 두 게이트를 모두 갖는다(403 / 412).
- **현재 동작(이슈 내용)**:
  `PortalLabelService.loadDatamartLabels` (`backend/src/main/java/kr/co/cudo/authoring/portal/service/PortalLabelService.java:100-116`) 에는 `isExposedToDatamart` 호출도, `accessGuard.requireNotUnderDeidentReport` 호출도 **없다**. rawSn 만 받아 `lblRepository.findAllByRawSn(rawSn)` 를 그대로 반환한다.

  ```java
  public List<DatamartLabelResponse> loadDatamartLabels(Long rawSn, int page, int size) {
      if (rawSn == null) { throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 은 필수입니다."); }
      int clampedSize = Math.min(Math.max(size, 1), 100);
      int clampedPage = Math.max(page, 0);
      List<LsDataLbl> all = lblRepository.findAllByRawSn(rawSn);   // ← 게이트 0건
      ...
  }
  ```
  컨트롤러(`PortalLabelController.java:83-91`)도 `@PreAuthorize("hasRole('PORTAL_USER')")` + `requireActor` 만 있고 영상 단위 인가가 없다.

  **실측(투입 데이터 기준)**
  | 요청 | 대상 영상 상태 | 결과 |
  |---|---|---|
  | `GET /v1/portal/datamart/labels?rawSn=9502` | `PENDING`(미승인) | **200**, `LEAK-PENDING-LABEL` 포함 100건 + `pointsJson` 좌표 원문 |
  | `GET /v1/portal/datamart/labels?rawSn=9500` | `APPROVED` + `DE_IDNTF_YN='F'`(신고 구간) | **200**, `LEAK-REPORTED-LABEL` 좌표 반환 |
  | (대조) `GET /v1/portal/frames/990020/labels` | 동일 미승인 영상 | **403** |
  | (대조) `GET /v1/portal/frames/990010/labels` | 동일 신고 구간 영상 | **412** |

  즉 **같은 데이터에 대해 게이트가 걸린 경로와 안 걸린 경로가 공존**하며, 후자가 전자를 무력화한다. rawSn 은 1..N 연속 정수라 열거가 자명하다.
- **재현/확인 경로**:
  ```sql
  -- 미승인 영상 + 라벨 1건 만들기
  INSERT INTO ls_data_raw (raw_sn,vms_clip_id,vms_cctv_id,prvc_type_cd,prvc_yn,de_ident_yn,raw_file_path_nm,data_stts_cd,reg_dt)
    VALUES (9502,'QA-F1-PENDING','CCTV-QA','PRVC','Y','Y','/app/storage/raw/qa/p.mp4','PENDING',now());
  INSERT INTO ls_raw_data_status (raw_data_id,data_stts_cd,upd_dt) VALUES (9502,'PENDING',now());
  INSERT INTO ls_data_src (src_sn,raw_sn,frm_no,de_idntf_src_file_path_nm,reg_dt)
    VALUES (990020,9502,0,'/app/storage/deidentified/frames/deid/4/frame-0.jpg',now());
  INSERT INTO ls_data_lbl (src_sn,lbl_type_cd,lbl_nm,point_cn,reg_dt)
    VALUES (990020,'BBOX','LEAK','[[3.0,3.0],[7.0,7.0]]',now());
  ```
  ```bash
  TOK=$(curl -s -XPOST localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
        -d '{"role":"PORTAL_USER","channel":"PORTAL"}' | jq -r .data.token)
  curl -s -H "Authorization: Bearer $TOK" \
       "localhost:18081/api/v1/portal/datamart/labels?rawSn=9502"   # → 200 + 좌표
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOK" \
       "localhost:18081/api/v1/portal/frames/990020/labels"          # → 403 (대조)
  ```
- **영향**:
  - **CWE-862(Missing Authorization) / CWE-639(IDOR — rawSn 열거)**: 외부 채널(PORTAL_USER)이 검수 전·반려·실패 상태 영상의 라벨 본문(라벨명·BBOX/POLYGON 좌표·`trackId`)을 전건 덤프할 수 있다. 데이터마트에 미노출인 내부 작업 중 산출물이 외부로 샌다.
  - **CWE-359(Private Information Exposure)**: 비식별 누락 신고 구간의 라벨 좌표는 **마스킹 실패 PII 의 위치 좌표**다. CLAUDE.md 가 명시적으로 차단 대상으로 규정한 자산이 그대로 나간다. 게이트 해제(`resolve`) 대기 중에도 계속 열람 가능.
  - ⚠ 이는 ★1(파생 경유 열람) 확정 정책과 **무관한 별개 경로**다 — 파생 여부와 상관없이 **원본 rawSn 자체**가 뚫린다.
- **수정 방향(제안)**:
  `PortalLabelService.loadDatamartLabels` 진입부에 형제 메서드(`loadFrameLabels`:257-267)와 **동일 순서·동일 컴포넌트**로 두 게이트를 배선한다 — ①`requireActor(actor)`(시그니처에 `TokenClaims actor` 추가) ②`if (!isExposedToDatamart(rawSn)) throw FORBIDDEN` ③`accessGuard.requireNotUnderDeidentReport(rawSn)`. 판정을 국소 재구현하지 말 것(본 결함의 원인이 정확히 "게이트 복제 누락"이다).
  아울러 **회귀 가드**로 `PortalUserLabelServiceTest` 에 미승인/신고 구간 케이스를 추가하고, 카탈로그 F-2 에 이 엔드포인트의 게이트 케이스(TC-PORTAL-039/040 신규)를 등재할 것 — 현재 카탈로그가 `/datamart/labels` 의 게이트를 **한 건도 커버하지 않아** 1차·2차 모두 케이스 통과로는 잡히지 않았다.

---


### [F-ISSUE-02] TC-PORTAL-021 — 데이터마트 영상 목록의 `totalElements`/`totalPages` 오보 + 중간 빈 페이지로 뒷 페이지 영상 유실

- **심각도**: **MEDIUM**
- **기대 동작(기대효과)**: 프레임 0건 영상을 목록에서 제외하되(진입 불가하므로), 페이징 메타(`totalElements`/`totalPages`)와 실제 순회 결과가 일치해 포털 사용자가 **노출 대상 영상 전체를 빠짐없이** 볼 수 있어야 한다.
- **현재 동작(이슈 내용)**:
  `PortalLabelService.java:148-161` 이 **DB 페이지를 가져온 뒤 애플리케이션에서 필터**하고, 총건수는 필터 전 값을 그대로 넘긴다.
  ```java
  List<DatamartVideoResponse> content = rows.stream()
          .filter(r -> firstSrcSnByVideo.get(r.getRawSn()) != null)   // 페이지 내부에서만 제외
          .map(...).toList();
  // 제외로 인해 페이지 size 보다 적어질 수 있으나 totalElements 는 원본(게이트 후) 기준 유지.
  return new PageImpl<>(content, pageable, page.getTotalElements());
  ```
  실측(APPROVED 20건 중 프레임 보유 12건):
  | 요청 | totalElements | totalPages | 실제 content |
  |---|--:|--:|--:|
  | `?page=0&size=5` | 20 | 4 | 5 (94,81,80,906,905) |
  | `?page=1&size=5` | 20 | 4 | 5 (902,903,901,900,26) |
  | `?page=2&size=5` | 20 | 4 | **0** |
  | `?page=3&size=5` | 20 | 4 | 2 (18, 4) |
  | `?page=0&size=100` | **12** | 1 | 12 |

  `size=100` 에서 total 이 12 로 바뀌는 것은 `PageImpl` 이 `offset + pageSize > total` 일 때 총계를 `offset + content.size()` 로 재계산하기 때문이며, 그 결과 **같은 데이터셋의 총건수가 페이지 크기에 따라 20↔12 로 달라진다**.
- **재현/확인 경로**:
  ```bash
  for p in 0 1 2 3; do
    curl -s -H "Authorization: Bearer $TOK" \
      "localhost:18081/api/v1/portal/datamart/videos?page=$p&size=5" \
      | jq -c '{page:'"$p"',total:.data.totalElements,pages:.data.totalPages,n:(.data.content|length)}'
  done
  ```
- **영향**: 기능/데이터정합.
  ① 무한스크롤·"더 보기" 처럼 **빈 응답을 종료 신호로 쓰는 FE 구현은 page2 에서 멈춰 rawSn 18·4 를 영원히 못 본다**(영상 유실).
  ② 페이지네이터가 "총 20건"을 표시하는데 실제 순회 가능 건수는 12건이라 사용자 혼란.
  ③ 프레임 0건 영상이 늘어날수록 빈 페이지 구간이 커진다(현재도 APPROVED 20건 중 8건이 프레임 0건).
  보안 영향은 없다.
- **수정 방향(제안)**: 제외 조건을 **DB 쿼리로 내린다** — `findAllWithReviewStatus` 계열에 `EXISTS (select 1 from LsDataSrc s where s.rawSn = v.rawSn)` 조건을 추가해 페이징·카운트가 같은 술어를 쓰게 한다. 그러면 `totalElements` 가 12 로 일관되고 빈 페이지도 사라진다. 애플리케이션 필터를 유지해야 한다면 최소한 `totalElements` 를 필터 후 기준으로 별도 count 쿼리로 산출하고, 현재 주석("totalElements 는 원본 기준 유지")을 정책으로 확정할지 사용자 판단을 받을 것.

---

## 4. 근거(file:line) 드리프트 — 카탈로그 정합성 결함

> `SecurityConfig.java`(F-1 전건: 126·132·134-135·147-153)와 `AiInferenceDeidentReportGateTest.java:339-351` 은 **정확 일치**. 아래는 어긋난 항목.

| TC | 카탈로그 근거 | 실제 위치 |
|----|---|---|
| TC-PORTAL-009 | `PortalUploadController.java:138-143` | `PortalUploadController.java:146-151` |
| TC-PORTAL-020 | `PortalLabelService.java:130-161` | `:131-162` |
| TC-PORTAL-021 | `:147-157` | `:148-158` |
| TC-PORTAL-022 | `:143-145` | `:144-146` |
| TC-PORTAL-023 | `:104-108` | `:105-108` |
| TC-PORTAL-024 | `:101-103` | `:102-104` |
| TC-PORTAL-025 | `:284-292` | `:285-293` |
| TC-PORTAL-026 | `:293-300` | `:294-301` |
| TC-PORTAL-027 | `:256-259` | `:257-260` |
| TC-PORTAL-028 | `:250-251` | `:251-252` |
| TC-PORTAL-029 | `:414-460` | `:430-501` |
| TC-PORTAL-030 | `:420-423` | `:436-439` |
| TC-PORTAL-031 | `:430-434` | `:446-450` |
| TC-PORTAL-032/033 | `:438-439` | `:457-459` (+ base 세팅 `:85-97`) |
| TC-PORTAL-034 | `:440-443` | `:460-471` |
| TC-PORTAL-035 | `:261-266` | `:262-267` |
| TC-PORTAL-036 | `:425-427` | `:441-443` |
| TC-PORTAL-037 | `:452-456` | `:493-497` |
| TC-PORTAL-038 | `DeidentReportGate.java:23-37` | `:24-38` |

`PortalLabelService.java` 는 앞부분(~L108)이 **+1행**, `serveFrameImage` 구간이 **+16~+41행** 밀렸다(2026-07-30 이후 javadoc·게이트 주석 추가분).

---

## 5. 테스트 커버 대조 (`_raw/test-baseline.md` — 전건 통과 상태)

| TC | 커버 테스트 (파일:메서드) |
|----|---|
| 001~004 | `auth/SecurityConfigChannelTest.java:portalChannelAllowedOnPortal` / `internalChannelForbiddenOnPortal` / `portalChannelForbiddenOnInternalVideos` / `portalChannelForbiddenOnManage` |
| 005~007 | `portal/PortalLabelControllerTest.java:portalTokenBlockedFromInternalSam2AndAutolabel` / `portalUserBlockedFromInternalApi` / `versionApiHiddenFromPortalUser` |
| 009 | `portal/PortalUserLabelServiceTest.java:saveUserLabel_noToken_rejected` |
| 010·011 | `frontend/src/router/__tests__/portalGuard.test.tsx` (4 케이스) |
| 020·021 | `portal/PortalDatamartVideosServiceTest.java:listDatamartVideos_approvedOnly_enriched` / `_pendingExcludedByGate` / `_zeroFrameExcluded` ⚠ **페이징 메타 단언 없음** → F-ISSUE-02 미커버 |
| 023·024 | `portal/PortalUserLabelServiceTest.java:loadDatamartLabels_returnsDtoList` / `_nullRawSn_rejected` ⚠ **게이트 케이스 없음** → F-ISSUE-01 미커버 |
| 025~028 | `portal/PortalFrameLabelsServiceTest.java:loadFrameLabels_userLabel_takesPrecedence` / `_noUserLabel_returnsDatamart` / `_notApproved_forbidden` / `_frameMissing_notFound` |
| 029~034 | `portal/PortalFrameImageServiceTest.java:serveFrameImage_approvedVideo_returnsDeidImage` / `_absoluteDeidPath_underDeidentifiedDir_returns200` / `_rawFrameSubtree_forbidden` / `_symlinkToRawFrame_forbidden` / `_traversalOutsideBase_forbidden` / `_fileMissing_notFound` / `_notApproved_forbidden` / `_noDeidPath_notFound` |
| 036·037 | `portal/PortalFrameImageCacheControlTest.java:portalFrameImageIsNotCached` / `portalFrameImageBlockedUnderDeidentReport` |
| 038 | `label/service/AiInferenceDeidentReportGateTest.java:segmentNotBlockedByOriginReport` (동일 게이트 컴포넌트 공유) |
| 035 | 전용 단위 테스트 미발견(`DeidentReportGateCoverageIT` 가 게이트 배선 자체는 커버) — 실동작으로 412 확인 |

---

## 6. 참고 관찰 (이슈 미승격)

- **UNCERTAINTIES #12 (rate limit 부재) 미해소 유지 확인**: `PortalLabelController` 의 `/datamart/videos`·`/datamart/labels`·`/user-labels`·`/frames/{srcSn}/labels`·`/frames/{srcSn}/image` 5개 핸들러 어디에도 rate limit 어노테이션·필터가 없다(업로드·SAM2·TUS 에만 존재). 프레임 이미지 서빙은 `no-store` 라 매 요청 서버 I/O 가 발생하므로 열거·대량 다운로드 억제 수단이 없다. 07-30 판정 그대로 **확인필요**로 이월.
- **신고 구간 영상이 데이터마트 목록에는 노출된다**: rawSn=900(`'F'` + `APPROVED`)이 `/datamart/videos` 결과에 그대로 포함되고, 진입하면 프레임 라벨/이미지에서 412 를 만난다. 관제 뷰 쪽 확정 정책(신고 필터 미적용)과 방향은 같으므로 **결함으로 보고하지 않는다**. 다만 포털 FE 가 412 를 graceful 처리하는지는 H 클러스터에서 확인 대상.
- **`/v1/portal/auth/**` 는 `SecurityConfig.java:90` 에서 permitAll** — 본 파트 케이스 대상은 아니나 채널 게이트 밖에 있는 유일한 포털 경로이므로 A 클러스터와 교차 확인 권장.

---

## 7. 집계

| 판정 | F-1 | F-2 | 합계 |
|---|--:|--:|--:|
| PASS | 11 | 18 | **29** |
| PARTIAL | 0 | 1 | **1** |
| FAIL / BLOCKED / N/A / 확인필요 | 0 | 0 | **0** |
| **계** | **11** | **19** | **30** |

- 이슈 2건: **F-ISSUE-01(HIGH)** · **F-ISSUE-02(MEDIUM)**
- ⚠ F-ISSUE-01 은 **30개 케이스 중 어느 것으로도 잡히지 않는 커버리지 갭**에서 발견됐다(반증 과정에서 형제 엔드포인트 간 게이트 비대칭을 대조해 노출). 카탈로그 F-2 에 `/v1/portal/datamart/labels` 게이트 케이스 신규 등재가 필요하다.
- 근거 드리프트 **19건**(대부분 +1행, `serveFrameImage` 구간 +16~+41행).


### [F-ISSUE-23] TC-PORTAL-040 인접 — `lblTypeCd` 가 **열거로 검증되지 않아 임의 문자열이 저장**된다
- **심각도**: LOW
- **기대 동작(기대효과)**: 라벨 타입은 `BBOX/POLYGON/SEGMENT/SKELETON/TRACK` 등 정해진 코드값만 허용돼야 한다. 타입은 로드 시 **파싱 라우팅 키**(`parsePointsRouted`)로 쓰이므로 미지의 값은 조용히 2-튜플 경로로 흘러간다.
- **현재 동작(이슈 내용)**: DTO 제약이 `@NotBlank @Size(max = 16)` 뿐이다(`PortalUserLabelRequest.java:13`). 서비스도 `TYPE_SKELETON` 동등 비교만 한다. 실측: `{"lblTypeCd":"HACK",...}` → **201**(userLblSn=7), 로드 응답에 `{"lblTypeCd":"HACK","points":[[1.0,1.0],[2.0,2.0]]}` 그대로 반환.
- **재현/확인 경로**: `POST /v1/portal/user-labels` 에 `"lblTypeCd":"HACK"` → 201, `GET /v1/portal/frames/1/labels` 응답에 그대로 노출.
- **영향**: 데이터 정합(다운로드/Export 소비자가 알 수 없는 타입을 만남). 값이 응답 JSON 문자열로만 나가므로 XSS 는 아니나 FE 분기 로직이 깨질 수 있다(CWE-20).
- **수정 방향(제안)**: DTO 에 `@Pattern` 또는 서비스에서 허용 코드 집합(allowlist) 검사 후 400. ⚠ 구현하지 않음.


### [F-ISSUE-25] TC-PORTAL-048 인접 — `GET /v1/portal/user-labels` 가 **페이징 없이 전건 반환**한다
- **심각도**: LOW
- **기대 동작(기대효과)**: `.claude/rules/api-design.md` — "목록 조회는 반드시 페이징 적용. 전체 조회(페이징 없는 findAll) 금지."
- **현재 동작(이슈 내용)**: 컨트롤러·서비스 모두 `List<>` 를 그대로 반환한다.
  ```java
  // PortalLabelController.java:106-113  → @GetMapping("/user-labels") … ApiResponse<List<PortalUserLabelResponse>>
  // PortalLabelService.java:228-235
  return userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(actor.sub(), rawSn)
          .stream().map(PortalUserLabelResponse::from).toList();
  ```
  같은 영상에 사용자가 저장한 라벨이 누적되면(현 설계상 매 저장이 **INSERT**, upsert 아님) 응답이 무한 증가한다. `loadFrameLabels`(`:249-304`)도 동일하게 무페이징이며 F-ISSUE-22 와 곱해진다.
- **재현/확인 경로**: 같은 `(rawSn, srcSn)` 으로 `POST /v1/portal/user-labels` 를 N 회 반복 → `GET /v1/portal/user-labels?rawSn=4` 응답 항목이 N 개로 선형 증가(실측: 8건까지 확인).
- **영향**: 자원 소진(CWE-770), 규칙 위반.
- **수정 방향(제안)**: `Pageable` 도입(기본 20 / 상한 100, `PortalUploadController.capped` 패턴 재사용). 하위호환이 걸리면 응답 래퍼 유지 + 상한만 우선 적용. ⚠ 구현하지 않음.


### [F-ISSUE-26] TC-PORTAL-040 인접 — 비식별 누락 신고 구간(`DE_IDENT_YN='F'`) 영상에도 **포털 사용자 라벨 저장이 허용**된다 (조회는 412)
- **심각도**: LOW
- **기대 동작(기대효과)**: 신고 구간에는 그 영상에 대한 작업 자체를 잠그는 것이 일관적이다. 내부 경로는 작업락으로 저장·수정을 409 차단하고, 포털 조회 경로는 412 로 막는다(CLAUDE.md 차단 범위 ④).
- **현재 동작(이슈 내용)**: `saveUserLabel` 에는 `accessGuard.requireNotUnderDeidentReport` 호출이 없다(`PortalLabelService.java:196-224`). 같은 서비스의 `loadFrameLabels`(`:267`)·`serveFrameImage`(`:443`) 에는 있다.
  실측: `rawSn=900`(APPROVED, `DE_IDENT_YN='F'`) 에 저장 → **201**(userLblSn=18). 대조로 같은 영상 프레임 조회 → **412** `"비식별 재처리 대기 중인 영상은 라벨을 조회할 수 없습니다."`
- **재현/확인 경로**:
  ```bash
  curl -XPOST .../v1/portal/user-labels -d '{"sourceRawSn":900,"sourceSrcSn":429,...}'   # 201
  curl .../v1/portal/frames/429/labels                                                    # 412
  ```
- **영향**: PII 노출 없음(이미지·라벨 조회가 모두 막혀 있어 유의미한 라벨을 만들 수 없다). **일관성/데이터 정합** 축의 갭이며, 신고 해제 후 남는 무의미한 라벨 행이 문제.
- **수정 방향(제안)**: 정책 판단 필요 — ①`saveUserLabel` 앞에 동일 게이트를 추가해 412 로 통일하거나 ②"저장은 본인 작업본이므로 허용"을 의도로 못 박고 주석·문서에 남긴다. ⚠ 구현하지 않음. (CLAUDE.md 차단 범위 목록에 write 경로가 없어 **확정 정책 위반으로 단정하지 않는다**)


### [F-ISSUE-28] TC-PORTALUP-030 인접 — 삭제 상태 검사에 **락이 없고 `UPLOADED` 도 삭제 허용**되어 비동기 추출 러너와 경합 가능
- **심각도**: LOW
- **기대 동작(기대효과)**: 주석이 선언한 대로 "처리 완료(READY/FAILED) 후 허용"이어야 하고, 상태 판정 후 파일 삭제까지 그 상태가 유지돼야 한다(프레임 추출 러너가 쓰는 파일을 삭제 중 제거하면 파일-DB 불일치).
- **현재 동작(이슈 내용)**:
  ```java
  // PortalUploadService.java:268-276
  LsPortalUld uld = uldRepository.findByUldSnAndPortalUserNo(uldSn, portalUserNo)   // 락 없음
          .orElseThrow(this::forbidden);
  if (LsPortalUld.STTS_PROCESSING.equals(uld.getUldSttsCd())) { throw CONFLICT; }   // PROCESSING 만 차단
  ```
  실측: `uld_stts_cd='UPLOADED'` 로 두고 `DELETE` → **204**(삭제됨). `UPLOADED` 는 TUS 업로드 완료 후 프레임 추출 시작 **직전** 상태라, 검사 통과 직후 러너가 `UPLOADED→PROCESSING` 으로 전이하면 삭제와 추출이 겹친다(TOCTOU). `findByUldSnAndPortalUserNo` 에 `@Lock` 없음(레포지토리 확인).
- **재현/확인 경로**: `UPDATE ls_portal_uld SET uld_stts_cd='UPLOADED' WHERE uld_sn=N;` → `DELETE /v1/portal/uploads/N` → 204. 경합 자체는 추출 러너 기동과 동시 실행이 필요해 결정적 재현은 미수행(관찰은 상태 허용 사실까지).
- **영향**: 데이터 정합(고아 프레임 행/파일), 확률 낮음(CWE-362).
- **수정 방향(제안)**: ①차단 상태를 `PROCESSING` + `UPLOADED` 로 넓히거나 ②조건부 UPDATE(`... SET uld_stts_cd='DELETING' WHERE uld_sn=? AND uld_stts_cd IN ('READY','FAILED')`)로 원자 클레임 후 삭제. 최소한 주석과 코드의 상태 집합을 일치시킨다. ⚠ 구현하지 않음.


### [F-ISSUE-29] TC-PORTALUP-023 인접 — 자산 상세(`GET /v1/portal/uploads/{uldSn}`)의 **프레임 목록이 무페이징**
- **심각도**: LOW
- **기대 동작(기대효과)**: 목록성 응답은 페이징하거나 상한을 둔다(api-design.md). 별도 엔드포인트 `/{uldSn}/frames` 는 이미 100 하드캡이 있는데 상세는 없다.
- **현재 동작(이슈 내용)**:
  ```java
  // PortalUploadService.java:176-182
  List<LsPortalUldFrme> frames = frmeRepository.findAllByUldSnOrderByFrmeNo(uld.getUldSn());
  return PortalUploadDetailResponse.of(uld, frames);   // frames 전건을 DTO 로 직렬화
  ```
  영상 자산의 프레임 수는 `portal.upload.max-frames`(기본 2,000)까지 가능하므로 상세 1회 호출이 최대 2,000 항목을 반환한다. 실측(이미지 자산 1프레임)에서는 노출되지 않는 경로라 코드/설정 기준.
- **재현/확인 경로**: 프레임 다수(수백~2,000)인 VIDEO 자산에 `GET /v1/portal/uploads/{uldSn}` → 응답 `frames` 배열 길이 = 전체 프레임 수.
- **영향**: 자원 소진(CWE-770). 상한이 2,000 으로 유한해 심각도 LOW.
- **수정 방향(제안)**: 상세 응답에서 `frames` 를 제거(별도 `/frames` 페이징 엔드포인트로 유도)하거나 상위 N 건 + `frmeCnt` 만 반환. ⚠ 구현하지 않음.

---

## 6. 남긴 테스트 데이터 (다음 회차 참고)

| 테이블 | 내용 |
|---|---|
| `ls_portal_user_label` | `portal_user_no` 3001(userLblSn 1~8,10~16,18,19) · 3002(9) — F-3 검증용. 10~16 은 stale/손상 좌표 주입분 |
| `ls_portal_uld` / `_frme` | 3011(`uldSn=63`), 3012(`uldSn=66`) 잔존. `64/65/77/79` 는 검증 중 삭제 완료 |
| 파일시스템 | `images/qa-symlink-test.png`(심링크)·`images/qa-dir-test/` 는 **검증 후 제거 완료** |


### [F-ISSUE-43] TC-PORTAL-070 — ai-server 가 `polygon: []`(빈 배열)을 주면 502 가 아니라 400 이 나가고, 이 축의 테스트가 0건
- **심각도**: LOW
- **기대 동작(기대효과)**: 외부 추론 응답이 **비었거나 실패**하면 "외부 API 오류"(`EXTERNAL_API_ERROR` = 502)로 종결해야 한다. 502 는 "외부 의존 실패, 재시도 가능"을 뜻하고 400 은 "클라이언트 입력이 잘못됨"을 뜻하므로, 외부 원인을 400 으로 내보내면 FE·운영이 원인을 사용자 입력으로 오귀속하고 모니터링(외부 API 실패율)에도 잡히지 않는다.
- **현재 동작(이슈 내용)**: null 만 502 로 분기하고 **빈 배열은 좌표 검증으로 흘러 400** 이 된다.
  - `backend/.../portal/service/PortalSam2Service.java:121-123`
    ```java
    if (aiRes == null || aiRes.polygon() == null) {
        throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "SAM2 segment 응답이 비어있습니다.");
    }
    ```
  - 이어서 `:132` `validatePolygon(aiRes.polygon())` → `:255-257`
    ```java
    if (polygon == null || polygon.isEmpty()) {
        throw new CustomException(ErrorCode.INVALID_INPUT, "폴리곤 좌표가 비어있습니다.");  // 400
    }
    ```
  - track 도 동일 구조(`:179-181` null 체크 → `:182` 검증).
  - 테스트: `PortalSam2ServiceTest` 에 ai 실패/빈응답·좌표 이상치 케이스 **0건**(`grep "EXTERNAL_API_ERROR\|isFinite\|음수" backend/src/test/.../portal/` 무결과).
- **재현/확인 경로**: 현 환경에서는 **실동작 재현 불가** — ai-server 는 contour < 3점이거나 변환 실패 시 빈 배열이 아니라 **mock 폴백**(`mock=true, source="mock"`)을 반환하므로(`ai-server/app/routers/sam2.py:157-162, 184-201, 305-313`) `polygon: []` + `source: "model"` 조합이 실제로 나오지 않는다. 재현하려면 스텁으로 `new Sam2Response(List.of(), 0.9)` 를 주입해야 한다(단위테스트 영역).
- **영향**: 기능·보안 영향 없음(양쪽 다 fail-closed 거부이고, 좌표가 사용자에게 적용되지 않는다). **오분류·관측성** 문제이며 계약 방어의 완결성 결함. 향후 ai-server 가 빈 배열을 정상 응답으로 내보내도록 바뀌면(계약 드리프트) 그 시점에 드러난다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. `:121-123`·`:179-181` 의 조건을 `polygon() == null || polygon().isEmpty()` 로 확장해 빈 응답을 502 로 일원화하고(내부 경로 `Sam2SegmentService`·`Sam2TrackService` 와 조건을 맞출 것), `PortalSam2ServiceTest` 에 ①ai null/빈 배열 → 502 ②ai 음수·NaN 좌표 → 400 두 케이스를 추가한다.


### [F-ISSUE-63] TC-PORTALUP-015 — write 도중 IOException 시 **실패한 그 파일의 부분 기록분**이 보상되지 않음
- **심각도**: LOW
- **기대 동작(기대효과)**: 디스크 고갈 등으로 저장이 실패하면 이번 요청이 만든 파일은 **하나도 남지 않아야** 한다(클래스 Javadoc `#5 디스크 고갈: 저장 도중 IOException 시 이번 요청 파일 전부 롤백 삭제`).
- **현재 동작(이슈 내용)**: 롤백 목록 등록이 write **성공 이후**라, copy 중간에 끊긴 파일은 목록에 없어 삭제되지 않는다.
  - `PortalUploadService.java:120-123`
    ```java
    Path dst = resolveSafe(baseDir, imagesDir.resolve(storedName));
    writeToDisk(v.file(), dst);          // ← 여기서 IOException 이면 dst 는 부분 기록된 채
    writtenThisRequest.add(dst);         // ← 이 줄에 도달하지 못해 롤백 목록에서 누락
    ```
  - JDK 동작 실측(`Files.copy(InputStream, Path)` 는 중간 실패 시 target 을 지우지 않음):
    ```
    copy failed: disk full
    target exists=true size=100
    ```
  - 기존 단위테스트 `laterWriteFailureRollsBackEarlierFiles` 의 테스트 더블은 `getInputStream()` 에서 던지므로 **target 파일이 아예 생성되지 않아** 이 경로를 검증하지 못한다.
  - F-ISSUE-62 와 마찬가지로 `portal/images/` 고아를 회수하는 스윕이 없다.
- **재현/확인 경로**: 저장 볼륨을 소진시키거나(예: 작은 tmpfs 를 `/app/storage/raw/portal/images` 에 마운트) `Files.copy` 중간 IOException 을 유발한 뒤 `ls /app/storage/raw/portal/images` 에 부분 파일 잔존을 확인. 코드 순서만으로도 결정적으로 성립한다.
- **영향**: CWE-459. 디스크가 이미 고갈된 상황에서 잔여물이 남아 회복을 더 어렵게 만든다(보안 영향은 낮음).
- **수정 방향(제안)**: `writtenThisRequest.add(dst)` 를 `writeToDisk` **호출 전**으로 옮기거나(rollback 이 `deleteIfExists` 라 미생성 파일에도 안전), `writeToDisk` 내부 catch 에서 `Files.deleteIfExists(dst)` 를 수행한 뒤 예외를 던진다.


### [F-ISSUE-83] TC-TUS-010 — `Tus-Resumable` 헤더가 **부재**하면 버전 검사를 통째로 건너뛴다(412 미발생)
- **심각도**: LOW
- **기대 동작(기대효과)**: TUS 1.0 core 규약상 OPTIONS 를 제외한 모든 요청은 `Tus-Resumable` 을 포함해야 하며, 부재 또는 미지원 버전이면 서버는 **412 Precondition Failed** 로 거부해야 한다. 버전 협상을 강제해야 클라이언트/서버 프로토콜 드리프트가 조용히 통과하지 않는다.
- **현재 동작(이슈 내용)**: null 을 예외 처리해 검사를 우회한다.
  ```java
  // PortalTusUploadController.java:163-168
  private void requireTusVersion(String tusResumable) {
      if (tusResumable != null && !TUS_VERSION.equals(tusResumable)) {   // ← null 이면 통과
          throw new CustomException(ErrorCode.PRECONDITION_FAILED, ...);
      }
  }
  ```
  실측: 헤더 생략 POST → **201**(세션 생성됨), 헤더 생략 HEAD/PATCH/DELETE 도 정상 처리. `0.2.2` 같은 잘못된 값만 412.
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w "%{http_code}\n" -X POST -H "Authorization: Bearer $PORTAL_TOKEN" \
    -H "Upload-Length: 1024" -H "Upload-Metadata: filename $(printf a.mp4|base64)" \
    http://localhost:18081/api/v1/portal/uploads/tus     # → 201 (기대: 412)
  ```
- **영향**: 프로토콜 conformance. 버전 미표기 클라이언트가 조용히 성공하므로, 향후 TUS 2.x 도입이나 헤더 규약 변경 시 호환성 문제를 사전에 못 잡는다. 보안 영향은 없다(인증·소유자 검증은 별도로 동작).
- **수정 방향(제안)**: `requireTusVersion` 을 `if (!TUS_VERSION.equals(tusResumable))` 로 바꿔 null 도 412 로 거부. 단 **기존 자체 FE 클라이언트가 항상 헤더를 보내는지 먼저 확인**(`tusClient.ts` 는 POST/HEAD/PATCH 모두 전송 확인, DELETE 경로만 재확인 필요)한 뒤 적용. 관제 내부 컨트롤러도 동일 검토. ⚠ 구현은 하지 않음.


### [F-ISSUE-84] TC-TUS-004 — 오류 응답에 `Tus-Resumable` 헤더가 포함되지 않음
- **심각도**: LOW
- **기대 동작(기대효과)**: TUS 1.0 은 서버가 **모든 응답**(오류 포함)에 `Tus-Resumable` 을 실어 보내도록 규정한다. 클라이언트가 오류 응답만 보고도 상대가 TUS 서버인지·어떤 버전인지 판별할 수 있어야 한다.
- **현재 동작(이슈 내용)**: 정상 응답(201/204)에는 컨트롤러가 직접 `H_RESUMABLE` 을 붙이지만, 예외 경로는 `GlobalExceptionHandler` 가 `ApiResponse` JSON 을 만들어 반환하므로 TUS 헤더가 전부 사라진다. 실측:
  ```
  $ curl -D - -X POST ... -H "Upload-Length: 99999999999" ...
  HTTP/1.1 413
  (Tus-Resumable 헤더 없음)
  ```
- **재현/확인 경로**: 위 curl 의 `-D -` 출력에서 `Tus-Resumable` grep → 0건. 413/412/409/400 전 오류 코드 동일.
- **영향**: 프로토콜 conformance만. 기능·보안 영향 없음.
- **수정 방향(제안)**: TUS 경로 전용 `HandlerInterceptor` 또는 `ResponseBodyAdvice` 로 `/v1/portal/uploads/tus/**`·`/v1/uploads/**` 응답에 `Tus-Resumable` 을 일괄 부착. F-ISSUE-81 의 예외 매핑 작업과 함께 처리하면 경제적. ⚠ 구현은 하지 않음.


### [F-ISSUE-87] TC-TUS-020 — 낙관적 락 충돌 복원 분기(`truncateTo`)가 비관적 락에 가려 실행되지 않는 사실상 dead branch
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스는 동시 PATCH 시 `OptimisticLockingFailureException` → 409 + **기록 시작 오프셋으로 truncate 복원**을 기대한다. 방어 코드가 실제로 도는지, 아니면 다른 계층이 먼저 막는지가 명확해야 유지보수 시 잘못된 안전감을 갖지 않는다.
- **현재 동작(이슈 내용)**: `appendChunkTx` 진입 시 이미 `findByUldIdForUpdate`(PESSIMISTIC_WRITE)로 행을 잠그므로(`TxService:71-73`) 동일 세션의 PATCH 는 **완전 직렬화**된다. 후행 요청은 락 획득 후 `expectedOffset != session.getOffsetBytes()` 에 걸려 `TxService:98-100` 에서 409 로 끝나고, `@Version` 충돌은 발생할 수 없다.
  ```java
  // TxService:109-115 — 실동작에서 미발화
  } catch (OptimisticLockingFailureException e) {
      TusChunkStore.truncateTo(session.getFilePathNm(), expectedOffset);
      log.warn("[PortalTus] concurrent PATCH conflict uldId={}", uldId);
      throw new CustomException(ErrorCode.CONFLICT, "동시 업로드 요청이 충돌했습니다. 재시도하세요.");
  }
  ```
  실측: offset=0 동시 PATCH 3건 → 204×1 + 409×2, 두 409 의 메시지는 모두 `Upload-Offset 이 서버 상태와 일치하지 않습니다`(=offset 불일치 경로). backend 로그에 `concurrent PATCH conflict` **0건**.
- **재현/확인 경로**:
  ```bash
  for i in 1 2 3; do ( curl -s -w "req$i:<%{http_code}> " -X PATCH -H "Authorization: Bearer $T" \
      -H "Tus-Resumable: 1.0.0" -H "Upload-Offset: 0" -H "Content-Type: application/offset+octet-stream" \
      --data-binary @1mb.bin $B/v1/portal/uploads/tus/$S ) & done; wait
  docker logs klid-backend 2>&1 | grep -c "concurrent PATCH conflict"   # → 0
  ```
- **영향**: 기능·보안 영향 없음(결과는 정확히 1건만 성공, 파일 무결). 다만 ①테스트 카탈로그의 전제(TC-TUS-020 이 인용한 `TxService:109-115`)와 실제 방어 계층이 어긋나고 ②커버리지가 영원히 도달하지 못하는 분기가 남아, 향후 누군가 비관적 락을 제거하면 "낙관적 락이 받쳐준다"는 잘못된 전제로 회귀를 만들 수 있다.
- **수정 방향(제안)**: 둘 중 택일 — ⓐ현 구조 유지 + 해당 catch 블록에 "PESSIMISTIC_WRITE 로 도달 불가한 최후 방어" 주석 명시 + 카탈로그 TC-TUS-020 의 근거를 `TxService:98-100`(offset 불일치 409)로 정정, ⓑ 비관적 락을 유지하는 한 낙관적 락 catch 를 제거. **ⓐ 권장**(다중 노드/락 타임아웃 상황의 심층방어 가치는 남는다). ⚠ 구현은 하지 않음.



## 클러스터 G — ai-server + 외부 벤더 목업 계약

### [G-ISSUE-01] TC-AIYOLO-49 — `/infer/yolo/track` 이 weights_missing·load_failed 경로에서 이미지 입력 검증을 전면 스킵한다
- **심각도**: HIGH
- **기대 동작(기대효과)**: `/track` 은 `/predict` 와 동일하게 `image_b64` 를 검증해야 한다 — 잘못된 base64·미지원 형식·크기/픽셀 초과는 `400 INVALID_IMAGE` / `413 IMAGE_TOO_LARGE`. BE 는 이 4xx 로 "프레임 이미지가 깨졌다"를 인지해 그 프레임을 드롭하거나 재추출해야 한다. mock 사유는 **모델 가용성** 축이고 입력 검증은 **요청 유효성** 축이라 서로 독립이어야 한다.
- **현재 동작(이슈 내용)**: `_mock_track` 이 `reason == "env_mock"` 일 때만 디코드한다.
  ```python
  # ai-server/app/routers/yolo.py:227-232
  detections: list[Detection] = []
  if reason == "env_mock":
      width, height = decode_image_b64(req.image_b64)   # ← 검증이 이 블록 안에만 있다
      cx, cy = width / 2.0, height / 2.0
  ```
  반면 `/predict` 는 mock 사유와 무관하게 검증한다 — `routers/yolo.py:137` `width, height = decode_image_b64(req.image_b64)` 가 `if backend is None:` 블록 **선두**에 있다.
  실측(운영 컨테이너, `mock_reason=weights_missing`):
  ```
  $ curl -X POST http://localhost:19300/infer/yolo/track -d '{"image_b64":"!!!notb64","clip_id":"c1","frame_index":0}'
  {"detections":[],"mock":true,"source":"mock","mock_reason":"weights_missing","success":true,...}  HTTP:200
  $ curl -X POST http://localhost:19300/infer/yolo/predict -d '{"image_b64":"!!!notb64"}'
  {"error_code":"INVALID_IMAGE","message":"base64 디코드 실패"}  HTTP:400
  ```
  자동테스트 `tests/test_yolo_track.py:126 test_track_invalid_base64_입력시_400` 은 `tests/conftest.py:17` 이 `AI_MOCK_MODE=true` 를 강제해 **env_mock 분기만** 타므로 통과한다 — **운영 형상(weights_missing)에서는 검증되지 않는 거짓 통과**다.
- **재현/확인 경로**:
  ```bash
  # 운영 컨테이너 = AI_MOCK_MODE=false + /app/weights 비어 있음 → weights_missing
  curl -s -X POST http://localhost:19300/infer/yolo/track -H 'Content-Type: application/json' \
    -d '{"image_b64":"!!!notb64","clip_id":"c1","frame_index":0}' -w "\nHTTP:%{http_code}\n"
  # 기대 400 / 실제 200
  # 크기·형식 게이트도 동일하게 우회된다(GIF·10MB 초과·64Mpx 전부 200)
  ```
- **영향**: CWE-20(Improper Input Validation). ①`/predict` 와 `/track` 의 계약이 배포 형상에 따라 갈려 BE 가 프레임 손상을 탐지하지 못한다(현재 전 환경이 weights_missing 이므로 **실제로 track 입력 검증이 0**). ②가중치를 배포해 실백엔드가 켜지는 순간 같은 입력이 갑자기 400 을 내기 시작한다(형상 의존 동작 변경). ③크기·픽셀 게이트도 함께 우회되므로 가중치 로드 실패(`load_failed`)로 폴백된 순간에는 DoS 방어(CWE-770)가 사라진다.
- **수정 방향(제안)**: `ai-server/app/routers/yolo.py` `_track_yolox`(161-172)에서 `_mock_track` 호출 **전에** `decode_image_b64(req.image_b64)` 를 1회 호출해 검증하고(폭·높이를 `_mock_track` 에 인자로 전달), `_mock_track` 내부의 조건부 디코드를 제거한다 — `/predict` 의 137행 구조와 동일하게 맞춘다. 회귀 가드로 `tests/test_yolo_track.py` 에 `monkeypatch.setattr(yolox_loader, "get_yolox_mock_reason", lambda: "weights_missing")` 를 건 invalid-base64 400 테스트를 추가한다(현 테스트는 env_mock 만 검증). ⚠ 구현은 하지 않는다.


### [G-ISSUE-02] TC-AIYOLO-25/24/48 — YOLOX ONNX 가중치가 어느 환경에도 배포되지 않아 실추론 경로가 전면 미가동·미검증
- **심각도**: HIGH
- **기대 동작(기대효과)**: 검증 스택은 "정상 시나리오 위에서 실동작 판정"이 대전제다(VERIFY-PROMPT §1). 오토라벨(YOLO)이 실제 검출을 내야 `YoloAutolabelStep` → `LS_DATA_LBL` 적재, 트랙 ID 연속성, 좌표 clamp, NMS 등 G-1/G-2/G-3 의 실추론 계약이 검증된다.
- **현재 동작(이슈 내용)**: 가중치 파일이 존재하지 않아 `get_yolox_model()` 이 항상 None 을 반환하고 전 요청이 mock 이다.
  ```
  $ docker exec klid-ai-server ls -la /app/weights
  total 8
  drwxr-xr-x 2 app app   64 Jul 30 17:56 .     ← 파일 0개
  $ docker logs klid-ai-server | head -3
  [YOLOX] weights not found — fallback to mock
  ```
  ```python
  # ai-server/app/models/yolox_loader.py:399-404
  weights_path, reason = _resolve_yolox_weights()
  if reason == "weights_missing":
      logger.warning("[YOLOX] weights not found — fallback to mock")
      _mock_reason = "weights_missing"
      _loaded = True
      return None
  ```
  compose 는 `./weights:/app/weights:ro`(`docker-compose.yml:191`) / `./ai-server/weights:/app/weights:ro`(`docker-compose.local.yml:107`) 로 **경로가 서로 다르고 둘 다 비어 있다.** BE 는 이 상태를 차단하지 않는다 — `YoloAutolabelStep.java:222-232` 는 `resp.untrusted()` 를 WARN 로그로만 남기고 계속 진행하므로 **배치는 "성공"으로 끝나되 라벨 0건**이다.
- **재현/확인 경로**:
  ```bash
  docker exec klid-ai-server ls -la /app/weights          # 빈 디렉터리
  curl -s -X POST http://localhost:19300/infer/yolo/predict -H 'Content-Type: application/json' \
    -d "{\"image_b64\":\"<valid png b64>\"}"              # mock_reason=weights_missing, detections=[]
  docker logs klid-backend 2>&1 | grep "mock response detected"   # 배치가 WARN 만 남기고 통과
  ```
- **영향**: 기능. 오토라벨링(SFR-08 핵심)이 전 환경에서 무동작이며, 그 사실이 배치 실패가 아니라 "라벨 0건 성공"으로 나타나 조용히 지나간다. 부수적으로 TC-AIYOLO-25(실백엔드 mock=false)·24(load_failed)·48(실백엔드 트래커 리셋)·G-2 전 항목·G-3 실트래킹이 **검증 불가(BLOCKED)** 상태로 남는다.
- **수정 방향(제안)**: ①`weights/yolox_s.onnx` 를 배포 산출물에 포함하거나 기동 시 fetch 하는 절차를 `deploy/onprem/docs/04-configuration.md` 에 명문화 + compose 두 파일의 마운트 소스 경로 통일. ②stg/prd 프로파일에서는 `weights_missing`/`load_failed` 를 **기동 차단 또는 배치 FAIL** 로 승격(현 `VlmUrlPolicy`·`QuartzClusteringGuard` 와 동일한 fail-closed 골격) — 운영에서 mock 라벨/0건 라벨이 학습데이터로 흘러가는 것을 막는다. ③검증 환경에는 가중치를 두어 G-2/G-3 실추론 케이스를 다음 회차에 해소. ⚠ 구현은 하지 않는다.


### [G-ISSUE-41] TC-AISAM2-13 — `points` 원소 길이 미검증: 실추론 예외의 mock fallback 자체가 다시 터져 500
- **심각도**: HIGH
- **기대 동작(기대효과)**: `_real_segment` 의 예외 catch(`sam2.py:217-220`)는 "추론 실패해도 크래시 금지(graceful) → mock 응답"이 계약이다(주석 `graceful: 추론 실패는 mock fallback (크래시 금지)`). 어떤 입력이든 200 mock 또는 400 검증오류여야 하며, 500 이 나오면 BE(`Sam2SegmentService`)는 `EXTERNAL_API_ERROR` 로 승격시켜 라벨링 화면의 SAM2 분할이 통째로 실패한다.
- **현재 동작(이슈 내용)**: `Sam2SegmentRequest.points` 가 `list[list[float]]` 로만 선언돼(`ai-server/app/schemas.py:152`) **내부 리스트의 원소 개수를 검증하지 않는다.** 원소가 1개인 포인트를 보내면 ①실추론이 `IndexError` → ②`sam2.py:217-220` 이 잡아 `_mock_segment` 호출 → ③`_mock_segment` 가 같은 이유로 다시 `IndexError` → 미처리 → 500.
  ```python
  # ai-server/app/schemas.py:152
  points: list[list[float]] | None = Field(default=None, description="[[x, y], ...] 클릭 좌표")
  # ai-server/app/routers/sam2.py:305-308  (_mock_segment)
  elif req.points:
      cx, cy = req.points[0][0], req.points[0][1]   # ← IndexError (fallback 안에서 재발)
  ```
  실측 로그(`docker logs klid-ai-server`):
  ```
  ERROR:app.routers.sam2:[SAM2] segment real predict 실패 — mock fallback
  ...  File "/app/app/routers/sam2.py", line 220, in _real_segment
         return _mock_segment(width, height, req, "empty_mask")
       File "/app/app/routers/sam2.py", line 306, in _mock_segment
         cx, cy = req.points[0][0], req.points[0][1]
  IndexError: list index out of range
  ERROR:app.exceptions:[AI] unhandled exception type=IndexError
  ```
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:19300/infer/sam2/segment \
    -H 'Content-Type: application/json' \
    -d '{"image_b64":"<유효 PNG base64>","points":[[5]]}'
  # 실측: 500 {"error_code":"INTERNAL_ERROR","message":"서버 내부 오류"}
  # 대조: points=[[1,2,3]] 은 200 mock (fallback 성공) — 1원소일 때만 fallback 이 재폭발
  ```
- **영향**: CWE-20(Improper Input Validation) + CWE-755(Improper Handling of Exceptional Conditions). 보안 노출은 없으나(스택트레이스 비노출, `exceptions.py:65-69` 확인) **fail-graceful 설계가 특정 입력에서 무효화**된다. `rules/security.md` "Mishandling of Exceptional Conditions(A10:2025) — 예외 발생 시 안전한 기본값" 위반. BE 가 SAM2 분할 요청 좌표를 그대로 릴레이하므로 FE 버그·악의적 요청 어느 쪽으로도 도달 가능.
- **수정 방향(제안)**:
  1. `schemas.py:152` — `points` 원소에 길이 제약 부여(`list[Annotated[list[float], Field(min_length=2, max_length=2)]]`) → 400 VALIDATION_ERROR 로 앞단 차단.
  2. `sam2.py:_mock_segment` — `req.points` 접근을 방어적으로(`len(req.points[0]) >= 2` 확인 후, 아니면 중앙 폴백 분기 사용)해 **fallback 은 어떤 입력에도 절대 예외를 던지지 않도록** 한다.
  3. 회귀 테스트: `tests/test_sam2_meta.py` 에 "원소 1개 point → 400 또는 200 mock, 500 금지" 케이스 추가.


### [G-ISSUE-42] TC-AISAM2-17 — track bbox 계산이 try 블록 밖이라 `prev_polygon` 이상 원소에서 fallback 없이 500
- **심각도**: HIGH
- **기대 동작(기대효과)**: `_real_track` 은 추론 실패 시 **이전 폴리곤을 그대로 반환**해 트랙이 끊기지 않게 하는 것이 계약이다(`sam2.py:248-260`, 주석 `graceful: 추론 실패는 이전 폴리곤 mock fallback (크래시 금지)`). SAM2 Track 은 SFR-08-01(VOS) 의 핵심 경로라 500 이 나면 N프레임 전파가 중단된다.
- **현재 동작(이슈 내용)**: bbox 유도 코드가 **try 진입 전(`sam2.py:240-242`)** 에 있어 여기서 난 예외는 어떤 fallback 도 타지 못한다. 게다가 `prev_polygon` 역시 원소 길이 미검증(`schemas.py:177` 은 바깥 리스트 `min_length=3` 만 검사).
  ```python
  # ai-server/app/routers/sam2.py:240-244  (_real_track)
  xs = [p[0] for p in req.prev_polygon]
  ys = [p[1] for p in req.prev_polygon]      # ← 원소가 1개면 IndexError, try 밖이라 미포착
  bbox = [min(xs), min(ys), max(xs), max(ys)]
  pil_image = decode_image_b64_pil(req.next_image_b64)
  try:                                        # ← 보호 구간은 여기서야 시작
  ```
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:19300/infer/sam2/track \
    -H 'Content-Type: application/json' \
    -d '{"track_id":"t3","prev_polygon":[[1],[2],[3]],"prev_image_b64":"<PNG b64>","next_image_b64":"<PNG b64>"}'
  # 실측: 500 {"error_code":"INTERNAL_ERROR","message":"서버 내부 오류"}
  ```
  (참고: 정상 3원소 퇴화 폴리곤 `[[5,5],[5,5],[5,5]]` 는 정상적으로 200 + prev 폴리곤 fallback → 보호 구간 자체는 동작)
- **영향**: CWE-20 + CWE-755. `_real_segment` 와 달리 **예외 처리 경계가 잘못 그어진 구조적 결함**이라 향후 bbox 유도 로직을 확장하면 같은 함정이 재발한다. G-ISSUE-41 과 동일 근인(원소 길이 미검증)이나 **fallback 이 아예 존재하지 않는 구간**이라는 점에서 별건.
- **수정 방향(제안)**:
  1. `schemas.py:177` — `prev_polygon` 원소에 `min_length=2, max_length=2` 제약 부여.
  2. `sam2.py` — bbox 유도(240-242)를 `try` 블록 **안**으로 이동하거나, 전용 `_polygon_bbox()` 헬퍼로 뽑고 실패 시 prev-polygon fallback 을 반환하도록 감싼다.
  3. 회귀 테스트: `_real_track` 에 이상 폴리곤 입력 시 500 이 아님을 단언하는 케이스 추가.


### [G-ISSUE-03] TC-AIYOLO-09 — `imgsz` 파라미터가 API 계약상 유효한 것처럼 검증되지만 추론에는 전혀 반영되지 않는다(640 고정)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: API 가 `imgsz: 320~1920`(기본 1280)을 받아 검증까지 하면 그 값이 추론 입력 해상도에 반영되어야 한다. 반영하지 않을 것이면 파라미터를 받지 않거나 계약에 "무시됨"을 명시해야 한다.
- **현재 동작(이슈 내용)**: 요청 → `InferenceParams` 까지는 전달되지만 전처리는 상수만 쓴다.
  ```python
  # ai-server/app/models/yolox_loader.py:53
  _DEFAULT_INPUT_SIZE: tuple[int, int] = (640, 640)
  # :376  세션 생성 시 고정
  return _YoloxBackend(session, input_size=_DEFAULT_INPUT_SIZE, device=device)
  # :284  전처리는 self._input_size 만 사용 — params.imgsz 미참조
  ih, iw = self._input_size
  ```
  `predict()`(301-314)·`track()`(316-326) 어디에서도 `params.imgsz` 를 읽지 않는다(grep 확인). 그런데 스키마는 `imgsz: int = Field(default=1280, ge=320, le=1920)`(`schemas.py:41,111`)로 범위를 강제하고, BE 는 시스템설정 `YOLO_IMGSZ` 를 읽어 전송한다(`AutolabelOnlineService.java:491`). 즉 **운영자가 화면에서 imgsz 를 바꿔도 추론은 언제나 640** 이고, 기본값 1280 자체가 실제와 2배 어긋난다. `logger.info` 는 `imgsz=%d` 를 찍지만 그 로그도 출력되지 않는다(G-ISSUE-04).
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:19300/infer/yolo/predict -H 'Content-Type: application/json' \
    -d "{\"image_b64\":\"<png>\",\"imgsz\":1920}"   # 200 수용
  grep -n "params.imgsz\|\.imgsz" ai-server/app/models/yolox_loader.py   # 참조 0건
  ```
- **영향**: 기능/운영. 해상도 튜닝 수단이 없는데 있는 것처럼 노출되어 소형 객체 검출률 저하를 운영자가 진단할 수 없다. `LS_SYSTEM_CONFIG` 의 `YOLO_IMGSZ` 는 죽은 설정이다. UNCERTAINTIES #14(imgsz 무효 검증 방법)를 **"코드상 확정 — 미반영"** 으로 갱신 가능.
- **수정 방향(제안)**: 둘 중 택1 — ⓐ`_YoloxBackend.predict/track` 이 `params.imgsz` 로 `input_size` 를 동적 구성(ONNX 입력이 동적 shape 인 경우에만 유효하므로 세션 입력 shape 확인 후 지원 여부 판단). ⓑ지원 불가면 `schemas.py` 의 `imgsz` description 에 "현재 로더는 640 고정 — 값은 무시됨"을 명시하고 BE `ConfigKeys.YOLO_IMGSZ` 를 설정 화면에서 숨긴다. 어느 쪽이든 `_DEFAULT_INPUT_SIZE` 와 스키마 `default=1280` 의 불일치는 해소한다. ⚠ 구현은 하지 않는다.


### [G-ISSUE-04] TC-AIYOLO-05 — ai-server 의 `logger.info` 가 운영에서 전량 유실되어 mock 사유·추론 파라미터·트래커 생명주기가 관측 불가
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: CLAUDE.md "배치 성능 — ai-server 추론 자원 모니터링 포인트 확보" 및 observability 규칙상, 요청별 mock 사유/추론 파라미터/트래커 생성·축출이 로그로 남아야 한다. 특히 mock 응답은 WARN-once 라 **2번째 이후 요청의 mock 사유를 알 수 있는 유일한 수단이 INFO 로그**다.
- **현재 동작(이슈 내용)**: `ai-server` 어디에도 `logging.basicConfig`/`dictConfig` 가 없고(`grep -rn "basicConfig\|dictConfig\|logging.config" ai-server/` → 0건), Dockerfile 은 `CMD ["uvicorn","app.main:app",...]`(`ai-server/Dockerfile:44`)로 로그 설정 없이 기동한다. 결과적으로 root 로거 기본 레벨 WARNING 이 적용되어 앱 INFO 가 전부 드롭된다.
  ```
  $ docker logs klid-ai-server 2>&1 | grep -c "INFO:app"
  0
  $ docker logs klid-ai-server 2>&1 | grep -E "\[AI\] startup|\[YOLOX\]" | head
  [YOLOX] weights not found — fallback to mock          ← WARNING 만 나옴
  ```
  `main.py:33-38` 의 기동 요약(`[AI] startup mock_mode=%s device=%s max_image_mb=%d`)조차 출력되지 않는다. 드롭되는 것들: `routers/yolo.py:140-143`(mock 사유+파라미터), `:151-154`(실추론 파라미터), `:167-171`(track mock), `yolox_loader.py:451,458,484-489`(트래커 TTL/LRU 축출·생성).
- **재현/확인 경로**: `docker logs klid-ai-server 2>&1 | grep -c "INFO:app"` → 0. 대조: 동일 코드를 `logging.basicConfig(level=INFO)` 로 기동하면 같은 메시지가 출력된다(컨테이너 내 `python -c` 프로브로 확인).
- **영향**: 운영/관찰가능성. ①mock 폴백이 언제·왜 발생했는지 첫 1회 WARN 이후 추적 불가 → 학습데이터 오염 원인 규명 불능. ②트래커 축출(G-ISSUE-05)이 완전히 무음이 된다. ③기동 시 `mock_mode`/`max_image_mb` 실효값을 로그로 확인할 수 없어 배포 검증 수단이 없다.
- **수정 방향(제안)**: `app/main.py` 에 `logging.basicConfig(level=os.getenv("LOG_LEVEL","INFO"))` 또는 uvicorn `--log-config` 로 앱 로거 레벨을 지정하고, Dockerfile CMD 에 `--log-level info` 를 추가한다. 레벨은 환경변수(`LOG_LEVEL`)로 조절 가능하게 두어 prd 에서 낮출 수 있게 한다. ⚠ 구현은 하지 않는다.


### [G-ISSUE-05] TC-AIYOLO-48/55 — 트래커 캐시(LRU max=10)가 온라인 오토라벨의 UUID clipId 채번과 충돌해 배치 영상의 track_id 연속성이 무신호로 파손될 수 있다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `clip_id` 별 트래커는 한 영상의 전 프레임 처리 동안 유지되어야 한다. 같은 객체가 프레임을 넘어 같은 `track_id` 를 받아야 `LS_DATA_LBL.TRCK_ID` 로 트랙 보간·트랙 편집이 성립한다. 트래커가 중간에 리셋되면 **동일 객체가 새 track_id 를 받거나(트랙 단절), 다른 객체가 이전 ID 를 재사용(오귀속)** 한다.
- **현재 동작(이슈 내용)**: ai-server 트래커 캐시는 **전역 10개** 상한이다.
  ```python
  # ai-server/app/models/yolox_loader.py:61-62
  _MAX_TRACKERS: int = 10
  _TRACKER_TTL_SEC: float = 300.0
  # :454-458  초과분 무조건 축출 (clip 종류 구분 없음)
  while len(_TRACKERS) > _MAX_TRACKERS:
      oldest_key, _ = _TRACKERS.popitem(last=False)
  ```
  그런데 BE 온라인 경로는 **요청마다 새 clipId** 를 만든다:
  ```java
  // backend/.../label/service/AutolabelOnlineService.java:495-499
  String clipId = rawSn + ":" + UUID.randomUUID();   // 요청마다 유일 — 재사용 없음
  return aiServerClient.predictYoloTrack(
          new YoloTrackRequest(imageB64, clipId, 0, conf, imgsz, iou, classes))
  ```
  → 온라인 요청 10건이면 배치 영상(`clipId = String.valueOf(rawSn)`, `YoloAutolabelStep.java:208-210`)의 트래커가 LRU 로 밀려난다. 다음 프레임 호출은 `clip_id not in _TRACKERS` 라 **새 핸들**을 만들고(`yolox_loader.py:479-490`) track_id 시퀀스가 처음부터 다시 시작된다. 응답에는 이를 알리는 필드가 없고 축출 로그는 `logger.info` 라 출력조차 되지 않는다(G-ISSUE-04). TTL 300초도 같은 축으로, 프레임 간 간격이 5분을 넘으면 조용히 리셋된다.
  더불어 실 ByteTrack 은 **트래커 생성 직후 첫 프레임에 항상 `track_id=None`** 을 준다(컨테이너 실측: f0 `[None,None]` → f1 `[0,1]` → f2 `[0,1]`). 따라서 온라인 경로는 항상 `frameIndex=0` + 매번 새 clipId 라 **실백엔드에서도 track_id 를 영구히 받지 못한다**(`AutolabelOnlineService.java:338,430` 이 `d.trackId()` 를 그대로 저장 → 항상 null).
- **재현/확인 경로**(가중치 배포 후):
  ```bash
  # 1) 배치 영상 clipId="123" 로 frame 0..N 처리 중
  # 2) 그 사이 온라인 오토라벨 11회 호출(각기 다른 UUID clipId)
  # 3) 배치의 다음 프레임 응답에서 track_id 가 1부터 다시 시작되는지 확인
  #    (동일 객체인데 TRCK_ID 가 바뀜)
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT src_sn, trck_id, count(*) FROM ls_data_lbl WHERE auto_lbl_yn='Y' GROUP BY 1,2 ORDER BY 1"
  ```
  현 환경은 가중치 부재로 트래커 캐시 자체가 미가동(`get_yolox_tracker` 가 mock 사유로 즉시 None) → 실증 BLOCKED, 코드·단위테스트(`test_yolox_loader.py:300 LRU`, `:316 TTL`)로 확인.
- **영향**: 데이터 정합. 오토라벨 트랙이 한 영상 안에서 조각나 트랙 보간(`INTERPOLATE` 단계)·트랙 병합/편집이 잘못된 단위로 동작한다. 무신호라 검수자가 원인을 알 수 없다. 온라인 경로는 track_id 가 상시 null 이라 트랙 기능 자체가 성립하지 않는다.
- **수정 방향(제안)**: ①온라인 단발 추론은 트래커가 필요 없으므로 `/infer/yolo/predict` 를 쓰거나(현재 BE `predictYolo` 는 프로덕션 호출자 0건 — 사장된 API), ai-server 에 "트래커 미사용" 플래그를 두어 캐시 엔트리를 만들지 않게 한다. ②`_MAX_TRACKERS` 를 동시 처리 영상 수 기준으로 상향하고 축출을 WARN 으로 승격해 BE 가 관측 가능하게 한다. ③또는 응답에 `tracker_reset: true` 를 실어 BE 가 트랙 경계를 인지하게 한다. ④온라인 경로가 track_id 를 필요로 한다면 첫 프레임 None 을 전제로 설계를 바꾼다. ⚠ 구현은 하지 않는다.


### [G-ISSUE-06] TC-AIYOLO-14/49 — `ai` Resilience4j 인스턴스가 결정적 4xx 를 재시도·서킷 실패로 집계한다(`vlmClient` 와 비대칭)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `400 INVALID_IMAGE`·`413 IMAGE_TOO_LARGE`·`400 VALIDATION_ERROR` 는 **결정적 실패**다. 재시도해도 같은 결과이므로 즉시 실패해야 하고, 서킷 브레이커의 실패율에도 집계되면 안 된다(정상 4xx 가 서킷을 여는 것 방지).
- **현재 동작(이슈 내용)**: 같은 파일 안에서 `vlmClient` 는 이 처리를 명시적으로 했는데 `ai` 는 하지 않았다.
  ```yaml
  # backend/src/main/resources/application.yml:602-612
      ai:
        max-attempts: 3
        wait-duration: 1s
        exponential-backoff-multiplier: 2      # ← ignore-exceptions 없음
      vlmClient:
        max-attempts: 3
        wait-duration: 1s
        exponential-backoff-multiplier: 2
        # V1: 4xx(400/422 등) 비-일시적 오류는 재시도하지 않는다 …
        ignore-exceptions:
          - kr.co.cudo.authoring.common.client.NonRetryableExternalException
  ```
  서킷 쪽도 동일(`:526-530` `ai` 에는 `ignore-exceptions` 없음 / `:532-538` `vlmClient` 에는 있음). `AiServerClient.predictYolo/predictYoloTrack`(`:39-67`)은 `.retrieve()` 라 4xx 에 `WebClientResponseException` 을 던지고, 그것이 `RetryOperator`·`CircuitBreakerOperator` 를 그대로 통과한다.
- **재현/확인 경로**:
  ```bash
  # 깨진 프레임 이미지(0바이트/GIF 등)가 있는 영상으로 YoloAutolabelStep 을 태우면
  # 프레임마다 400 → 1s·2s 백오프 3회 → 프레임당 ~3초 지연
  docker logs klid-backend 2>&1 | grep -E "CircuitBreaker 'ai'|Retry 'ai'"
  ```
- **영향**: 가용성/성능. 손상 프레임이 다수인 영상 1건이 `ai` 서킷(실패율 50%, 슬라이딩 10, 최소 5콜)을 열어 **30초 동안 YOLO·SAM2·VLM 온라인 추론 전부가 차단**된다(같은 `aiCircuitBreaker` 를 4개 메서드가 공유). 배치 지연도 프레임당 3초씩 누적. ⚠ 현재는 G-ISSUE-01 때문에 `/track` 이 4xx 를 아예 내지 않아 증상이 가려져 있으며, 01 을 고치는 순간 이 문제가 드러난다(두 이슈는 같이 봐야 한다).
- **수정 방향(제안)**: `KpstDeidentifyClient`/`VlmClient` 가 쓰는 `NonRetryableExternalException` 매핑을 `AiServerClient` 에도 적용(`onStatus(HttpStatusCode::is4xxClientError, …)`)하고, `application.yml` 의 `resilience4j.retry.instances.ai` 와 `circuitbreaker.instances.ai` 양쪽에 `ignore-exceptions` 를 추가한다. ⚠ 구현은 하지 않는다.


### [G-ISSUE-64] ai-server 애플리케이션 INFO 로그가 전량 유실됨 (logging 미구성)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: ai-server 는 각 추론 라우터에서 진단 로그를 남기도록 작성돼 있다(예: 요청별 이미지 크기·객체 수). 장애 분석·계약 검증 시 "무엇이 어떤 입력으로 들어왔는가"를 사후 추적할 수 있어야 한다.
- **현재 동작(이슈 내용)**: **컨테이너 로그에 앱 INFO 가 단 1건도 없다.** ai-server 는 `logging.basicConfig`/`dictConfig`/uvicorn `--log-config` 를 어디에서도 설정하지 않아 앱 로거가 Python 기본 root level(WARNING)로 동작한다. 결과적으로 `logger.info(...)` 는 모두 버려지고 `logger.warning` 이상만 출력된다.
  - `ai-server/app/routers/vlm.py:46-51` — `logger.info("[VLM] verify-objects received image_size=%dx%d objects=%d", width, height, len(req.objects))`
  - `docker logs klid-ai-server | grep -c "verify-objects received"` → **0** (실제로는 12회 호출됨 — uvicorn access 로그로 확인)
  - 같은 로그에서 `WARNING:app.routers.vlm:[VLM][MOCK] …` 은 정상 출력 → 레벨 컷임이 확정
  - `grep -rn "basicConfig\|dictConfig\|log_level\|LOG_LEVEL" ai-server/app ai-server/Dockerfile*` → 매치 0건, 기동 커맨드 `["uvicorn","app.main:app","--host","0.0.0.0","--port","9300"]` 에도 로그 옵션 없음
- **재현/확인 경로**:
  ```
  curl -s -X POST http://localhost:19300/infer/vlm/verify-objects -H 'Content-Type: application/json' \
    -d '{"image_b64":"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==","objects":[{"obj_id":"o1","expected_label":"person","bbox":[0,0,1,1]}]}'
  docker logs --since 1m klid-ai-server | grep "verify-objects received"   # 0건
  ```
- **영향**: 운영 관찰가능성 결손 — 추론 서버에서 장애·계약 불일치가 발생해도 요청 단위 컨텍스트(이미지 크기·객체 수·모델 로드 상태 등)를 사후 확인할 수 없다. `main.py:31-37` 의 startup 진단(`[AI] startup mock_mode=… device=… max_image_mb=…`)도 동일하게 유실돼 **실행 중 인스턴스의 mock 모드 여부를 로그로 확인할 수 없다**(본 검증에서도 `docker exec env` 로 우회해야 했다). rules/observability.md 의 traceId·구조화 로깅 원칙과도 어긋난다.
- **수정 방향(제안)**: `ai-server/app/main.py` 에 `logging.config.dictConfig`(또는 최소 `logging.basicConfig(level=os.getenv("LOG_LEVEL","INFO"))`)를 앱 생성 전에 배선하고, 레벨을 `LOG_LEVEL` 환경변수로 노출(기본 INFO, prd 는 조정 가능). `RequestIdMiddleware` 가 이미 request-id 를 들고 있으므로 포매터에 함께 실어 BE 의 `X-Trace-Id` 와 상관관계를 맞춘다. 대안으로 uvicorn `--log-config` 파일 배선.


### [G-ISSUE-81] TC-AIMOCK-07 — 기대결과가 폐기된 구 정책(18바이트 placeholder)을 그대로 정본화
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 카탈로그 기대결과는 현재 코드의 확정 정책과 일치해야 한다. 불일치하면 다음 회차 검증자가 "placeholder 가 없다"를 **결함(FAIL)** 으로 오판하거나, 반대로 정책을 되돌리는 수정을 유발한다(이 저장소는 실제로 placeholder 를 만들었다가 되돌린 이력이 있다).
- **현재 동작(이슈 내용)**: 카탈로그는 `기대결과 = target 파일이 원본 복사가 아닌 18바이트 placeholder` 라고 적었으나, 코드는 **placeholder 를 명시적으로 폐기**했다.
  ```
  # mock-server/app/services/deid_sim.py:210-217
  # ★ #3 — <b>placeholder 산출물은 폐기됐다</b>. 원본을 읽지 못하는 경우(...) 구 구현은 18바이트
  #   스텁을 <b>최종 경로</b>에 쓰고 완료(procState=2)로 보고했다. ... 지금은 <b>산출 실패
  #   (procState=99)</b> 로 종결한다 ... 위장 산출물이 되기 때문이다(CWE-345).
  ```
  실동작(2026-08-02 09:15): `input_path=/etc/` 요청 → export 디렉터리 **완전히 빈 상태**, `procState=99`, 로그 `placeholder 로 최종 이름을 선점하지 않는다`.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:9400/project -H 'Content-Type: application/json' \
    -d '{"project_name":"chk","creator":"qa","export_path":"/app/storage/deidentified/videos/chk/","input_path":"/etc/","files":["hosts.mp4"]}'
  sleep 12
  curl -s -X GET http://localhost:9400/retrieve_progress -H 'Content-Type: application/json' \
    -d '{"reqUserId":"qa","prjName":"chk"}'      # procState=99
  docker exec klid-mock-server ls -la /app/storage/deidentified/videos/chk   # 빈 디렉터리
  ```
- **영향**: 기능/보안 영향 없음(현 동작이 더 안전). **카탈로그 정합성 결함** — 회차 간 판정 재현성을 깨뜨린다.
- **수정 방향(제안)**: `docs/test-cases/G-ai-server.md` TC-AIMOCK-07 의 케이스명·기대결과를 "허용 루트 밖 input_path 는 원본을 읽지 않고 **산출물을 만들지 않은 채 procState=99 로 실패 종결**(placeholder 로 최종 이름 선점 금지 — CWE-345)" 로 정정. 근거 `file:line` 도 `deid_sim.py:210-217, 334-353, 1704~` 로 갱신. **코드는 그대로 둔다.**


### [G-ISSUE-82] TC-AIMOCK-10 — VLM 콜백 SSRF 가드가 genai 가드보다 약해 목 서버 자기참조·임의 포트 POST 가 성립(실증)
- **심각도**: MEDIUM (목 서버가 루프백 전용 발행이라 원격 노출은 없음. 노출 시 HIGH)
- **기대 동작(기대효과)**: 목 서버는 무인증이므로 요청자 지정 `callback_url` 로의 서버측 outbound 는 **호스트뿐 아니라 포트·경로·자기참조**까지 좁혀야 한다. 같은 서버의 genai 가드(`genai_sim.is_allowed_url`)는 이미 `host:port` allowlist + 경로 접두사 + **자기참조 차단**을 구현하고 있으므로, VLM 만 약한 것은 방어 비대칭이다.
- **현재 동작(이슈 내용)**: VLM 은 `url_guard.is_allowed_callback` 만 사용하고 **호스트 완전일치만** 검사한다.
  ```python
  # mock-server/app/services/url_guard.py:7-9 (docstring)
  # 정책: 허용 호스트 목록(...)에 정확히 일치하는 호스트만 수락하고 ...
  # 포트/경로는 제한하지 않는다(콜백 수신 포트가 환경마다 다름).
  # :28-33
  def is_allowed_callback(url, allowed_hosts) -> bool:
      host = callback_host(url)
      if host is None: return False
      return host in {h.lower() for h in allowed_hosts}
  ```
  기본 allowlist 에 `localhost,127.0.0.1` 이 들어 있어 **목 서버 자신**이 항상 허용된다. 실증(2026-08-02 09:19:30~32, 컨테이너 로그):
  ```
  [MOCK][VLM] verify accepted request_id=qa-g5-ssrf1 callback_url=http://127.0.0.1:9400/api/genai/_mock/reset
  [MOCK][GENAI] store reset
  INFO: 127.0.0.1:52944 - "POST /api/genai/_mock/reset HTTP/1.1" 200 OK
  [MOCK][VLM] callback sent url=http://127.0.0.1:9400/api/genai/_mock/reset status=200
  ```
  → VLM 엔드포인트 1회 호출만으로 **다른 벤더(genai)의 작업 저장소가 전량 삭제**됐다. 또한 `http://klid-backend:5005/actuator` 처럼 **backend 컨테이너의 임의 포트**로도 POST 가 발사됐다(ConnectError 로그 = 실제 커넥션 시도).
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:9400/v1/videovlm/verify -H 'Content-Type: application/json' \
    -d '{"request_id":"x","event_type":"fall","media":{"type":"video","source_type":"path","path":"/x.mp4"},
         "callback_url":"http://127.0.0.1:9400/api/genai/_mock/reset"}'
  # 200 accepted → 2초 후 docker logs 에 "[MOCK][GENAI] store reset"
  ```
- **영향**: 보안 **CWE-918(SSRF)** + **CWE-352 유사(무인증 상태변경 트리거)**. 컨테이너 네트워크 안에서 임의 호스트:포트 POST 발판 + 목 서버 자체 상태 파괴. 검증 관점에서는 다른 회차의 genai 관측 이력이 조용히 사라져 **검증 결과 신뢰성**을 훼손한다.
- **수정 방향(제안)**: `app/routers/vlm.py:_assert_allowed_callback` 이 `url_guard` 대신 **genai 와 같은 판정기**(`genai_sim.is_allowed_url` 을 벤더 중립 모듈로 승격하거나 `url_guard` 에 `host:port` + 경로 접두사 + `_is_self_target` 을 이식)를 쓰도록 통합. 최소한 `_mock/*` 보조 EP 는 콜백 대상에서 무조건 배제. 아울러 `MOCK_CALLBACK_ALLOWED_HOSTS` 기본값에서 `localhost,127.0.0.1` 의 필요성을 재검토.


### [G-ISSUE-84] TC-AIMOCK-12 — `/v1/videovlm/status` 응답이 IntelliVIX v2.0.1 규격(`ready`/`busy`)과 불일치하고 카탈로그가 그 드리프트를 정본화
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 목업의 존재 이유는 **벤더 계약을 대신 재현**하는 것이다. 벤더 원문(`docs/video_vlm_api_ v2.0.1.docx` §2.7 "서버 상태 체크")은 다음을 규정한다.
  | 상황 | 응답 |
  |---|---|
  | 요청 처리 가능 | `200 {"status":"ready"}` |
  | 작업 진행 중 | `200 {"status":"busy"}` |
  `service` 필드는 규격에 없다. 즉 목업은 `ready`/`busy` 두 값을 재현해야 소비 측(향후 헬스 인디케이터·서킷 판정)이 로컬에서 실제로 검증된다.
- **현재 동작(이슈 내용)**:
  ```python
  # mock-server/app/routers/vlm.py:241-244
  @router.get("/v1/videovlm/status")
  async def status_check() -> dict[str, str]:
      return {"status": "ok", "service": "videovlm"}
  ```
  실동작: `GET /v1/videovlm/status` → `200 {"status":"ok","service":"videovlm"}`. `ok` 는 규격 어휘가 아니며 `busy` 상태는 아예 재현 불가.
  카탈로그 TC-AIMOCK-12 기대결과가 `200 {status:ok, service:videovlm}` 으로 **목업 구현을 그대로 베껴** 계약 위반을 통과시키고 있다(확증편향 사례).
- **재현/확인 경로**: `curl -s http://localhost:9400/v1/videovlm/status` → `{"status":"ok","service":"videovlm"}` vs 벤더 문서 §2.7.
- **영향**: 기능 영향 **현재 없음**(BE 전수 grep 결과 `/v1/videovlm/status` 호출부 0건 — `VlmClient` 는 `DESCRIBE_PATH="/v1/videovlm/describe"` 만 사용). 그러나 ①"목업 = 계약 정본" 이라는 전제가 이 EP 에서 깨져 있고 ②향후 VLM 헬스 인디케이터를 `"ready"` 기준으로 붙이면 **로컬에선 전부 DOWN, 실벤더에선 UP** 이 되어 로컬 검증이 무의미해진다. mock-server `/health`(`{"status":"ok"}`)와 값이 같아 두 개념이 혼동되기도 쉽다.
- **수정 방향(제안)**: `vlm.py:status_check` 를 규격대로 `{"status": "ready"}` 로 바꾸고, 진행 중인 콜백/probe 태스크가 있을 때 `{"status":"busy"}` 를 반환하도록 선택적으로 확장(`vlm_sim.describe_probe_inflight()` 로 판정 가능). `service` 필드는 제거하거나 명시적으로 "목 전용 확장"임을 주석화. 카탈로그 TC-AIMOCK-12 기대결과도 `ready`/`busy` 로 정정. **본 회차에서는 수정하지 않음.**


### [G-ISSUE-07] TC-AIYOLO-55 — `coco_id_from_label` 의 미지 라벨 id 가 프로세스마다 달라져 문서화된 계약("항상 동일 정수")을 위반한다
- **심각도**: LOW
- **기대 동작(기대효과)**: docstring 이 "같은 라벨은 항상 같은 정수가 되며 COCO id 범위(0~79)와 충돌하지 않는다"고 명시한다. 트래커 클래스 분리 키가 재시작·노드 간에 안정해야 track 결과가 재현 가능하다.
- **현재 동작(이슈 내용)**:
  ```python
  # ai-server/app/models/detector_backend.py:123-124
  # 미지의 라벨 — 결정적 fallback (해시 mod). 동일 라벨은 항상 동일 정수.
  return _UNKNOWN_LABEL_ID_BASE + (hash(label) & 0xFFFF)
  ```
  CPython 의 `str.__hash__` 는 `PYTHONHASHSEED` 로 프로세스마다 랜덤화된다. 실측(같은 컨테이너, 3개 프로세스):
  ```
  coco_id_from_label('zzz'), coco_id_from_label('unknown_label')
  32934 52844
  51100 66851
  58582 62124
  ```
  "결정적"은 **단일 프로세스 수명 내**에서만 참이다.
- **재현/확인 경로**: `for i in 1 2 3; do docker exec klid-ai-server python -c "from app.models.detector_backend import coco_id_from_label; print(coco_id_from_label('zzz'))"; done`
- **영향**: 기능(잠재). 현재 YOLOX 출력 라벨은 COCO 80종뿐이라 `_LABEL2ID` 히트로 이 분기를 타지 않아 **실피해 없음**. 다만 ai-server 다중 프로세스/재시작 시 비-COCO 라벨이 유입되면 클래스 분리 키가 흔들려 track_id 오귀속이 가능하고, 무엇보다 **주석이 사실과 다르다**(오독 유발).
- **수정 방향(제안)**: `hash()` 대신 `int.from_bytes(hashlib.sha256(label.encode()).digest()[:2],'big')` 같은 안정 해시로 교체하거나, 주석을 "프로세스 수명 내 결정적"으로 정정한다. ⚠ 구현은 하지 않는다.


### [G-ISSUE-08] TC-AIYOLO-52~55 — `bytetrack_util` 전용 테스트 파일이 없어 track_id 정규화 로직의 자동 회귀 커버가 0이다
- **심각도**: LOW
- **기대 동작(기대효과)**: `-1 → None` 정규화, 길이 불일치 WARN+보존, trackers 미설치 graceful, 클래스 분리는 track_id 정합의 핵심이라 자동 테스트로 고정되어야 한다.
- **현재 동작(이슈 내용)**: `ai-server/tests/` 13개 파일 중 `bytetrack_util` 을 직접 검증하는 파일이 없다. 유일한 관련 테스트 `tests/test_yolox_loader.py:389 test_backend_track가_predict후_bytetrack으로_track_id_부여` 는 `monkeypatch.setattr(yolox_loader, "_apply_bytetrack", _fake_apply)`(`:407`)로 **검증 대상 함수를 통째로 대체**하고 "호출됐는지"만 본다. `grep -rn "tracker_id\|_apply_bytetrack" tests/` 결과 실제 로직 단언 0건.
- **재현/확인 경로**: `ls ai-server/tests/ | grep -i bytetrack` → 없음. `grep -rn "_apply_bytetrack" ai-server/tests/` → `test_yolox_loader.py:407`(monkeypatch)뿐.
- **영향**: 회귀 위험. 본 검증에서 컨테이너 인라인 프로브로 4건 모두 정상 확인했으나(TC-52~55 PASS), 코드 변경 시 이를 잡아줄 자동 가드가 없다.
- **수정 방향(제안)**: `ai-server/tests/test_bytetrack_util.py` 신설 — FakeTracker 로 `tracker_id=[-1]`→None, 길이 불일치 WARN(caplog), `builtins.__import__` 패치로 미설치 WARN-once, `coco_id_from_label('person')!=coco_id_from_label('car')` 를 각각 단언. ⚠ 구현은 하지 않는다.


### [G-ISSUE-09] 근거 드리프트 — 카탈로그 `G-ai-server.md` 의 전제·file:line 3건 불일치
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그의 전제·근거는 실제 코드/운영 형상과 일치해야 다음 회차가 같은 기준으로 재검증할 수 있다.
- **현재 동작(이슈 내용)**:
  | 케이스 | 카탈로그 표기 | 실제 |
  |---|---|---|
  | TC-AIYOLO-15 | 전제 `MAX=1MB`, 입력 `1500x1500(~6MB)` | 1MB 는 **테스트 전용**(`tests/conftest.py:18`). 운영 기본 10MB(`config.py:40`) → 1500x1500(6.7MB)은 운영에서 **통과**한다. 실제 413 재현에는 2200x2200(14.5MB) 필요 |
  | TC-AIYOLO-27 | 근거 `routers/yolo.py:107` | 107행은 `return YoloResponse(` — 실제 근거는 `Detection(...)` 생성부 `:100-106` + `schemas.py:59`(default None) |
  | TC-AIYOLO-45 | 근거 `routers/yolo.py:164-172` | 실제 mock 분기는 `:164-172` 중 `:165-172` (164 는 `if backend is None:`). 경미 |
  그 외 G-1/G-3 의 근거 라인 37건은 실측 일치(드리프트율 3/40 = 7.5%).
- **재현/확인 경로**: 위 표의 file:line Read 대조.
- **영향**: 검증 정합성. TC-15 는 전제를 그대로 따르면 **413 이 안 나와 오FAIL** 판정될 수 있다.
- **수정 방향(제안)**: `docs/test-cases/G-ai-server.md` 의 해당 3행 전제·근거를 위 실측으로 갱신. ⚠ 구현은 하지 않는다.

---

## 5. UNCERTAINTIES 갱신 제안

| 항목 | 현 상태 | 본 검증 결과 |
|---|---|---|
| **#14 imgsz 무효(640 고정) 실효 검증 방법** | 미해소 유지 | **부분 확정** — 코드상 `params.imgsz` 참조 0건(`yolox_loader.py:53,301-326`)으로 **무시 확정**. 실추론 대조는 가중치 미배포(G-ISSUE-02)로 여전히 불가. "코드 확정 / 실추론 미대조"로 상태 갱신 권장 |

## 6. self-fill 관점 점검 (본 프로젝트 핵심 관심사)

- ai-server 는 실추론이 불가할 때 **값을 지어내지 않는다** — `weights_missing`/`load_failed` 는 `detections=[]` 를 반환하고(`routers/yolo.py:94-106` — `env_mock` 사유일 때만 결정적 박스 생성), `mock=true`/`source="mock"`/`mock_reason` 3필드로 자기 상태를 명시한다. **self-fill 결함 아님**(실동작 확인).
- BE 도 긍정 증명 기반으로 불신 판정한다 — `AiMockMeta.untrusted(mock, source)` 가 `source="model"` 명시가 없으면 불신(fail-closed, `AiMockMeta.java:37-39`), `YoloResponse.untrusted()` 가 이를 위임한다.
- 다만 **불신 판정 이후의 처리가 로그뿐**이다(`YoloAutolabelStep.java:222-232` WARN 후 계속 진행) → 운영에서 mock/빈 결과가 "성공한 배치"로 종결된다(G-ISSUE-02 의 영향 항목 참조).


### [G-ISSUE-43] TC-AISAM2-02/03 — mock 폴리곤이 입력 좌표를 무검증 반사해 이미지 밖·영면적·비현실 좌표를 생성
- **심각도**: LOW
- **기대 동작(기대효과)**: mock/fallback 응답도 **그 이미지 안의 유효한 폴리곤**이어야 한다. ai-server 는 이미지 해상도(`width,height`)를 이미 알고 있으므로(`sam2.py:35`, `:207`) 경계 clamp 와 최소 면적 보장이 가능하다.
- **현재 동작(이슈 내용)**: `_mock_segment` 가 요청 box/point 를 그대로 폴리곤으로 되돌린다(clamp·유효성 검사 없음).
  ```python
  # ai-server/app/routers/sam2.py:303-311
  if req.box and len(req.box) == 4:
      x1, y1, x2, y2 = req.box              # ← 경계·면적 검증 없이 그대로
  elif req.points:
      cx, cy = req.points[0][0], req.points[0][1]
      half = min(width, height) * 0.1
      x1, y1, x2, y2 = cx - half, cy - half, cx + half, cy + half   # ← 음수 가능
  polygon = [[x1, y1], [x2, y1], [x2, y2], [x1, y2]]
  ```
  실측(100×80 이미지):
  | 입력 | mock 폴리곤 |
  |---|---|
  | `points=[[1,2,3]]` | `[[-7,-6],[9,-6],[9,10],[-7,10]]` — **음수 좌표** |
  | `box=[0,0,0,0]` | `[[0,0],[0,0],[0,0],[0,0]]` — **영면적(퇴화)** |
  | `box=[1e308]*4` | `[[1e308,…]]` — 비현실 좌표 |
- **재현/확인 경로**: 위 표의 3개 요청을 `POST /infer/sam2/segment` 로 전송(전부 200).
- **영향**: 기능 영향 제한적 — BE `Sam2SegmentService`(`backend/.../label/service/Sam2SegmentService.java:126-131`)가 `aiRes.untrusted()` 로 mock 응답을 **빈 폴리곤으로 치환**하고, 통과하더라도 `validatePolygon(polygon, imgWidth, imgHeight)` 이 좌표 상한을 검증한다. 즉 **현재는 BE 방어에 의해 흡수**된다. 다만 ai-server 를 다른 소비자가 직접 호출하면 방어가 없고, `CLAUDE.md` ★3(AI 검출 응답 = clamp) 의 정신과도 어긋난다.
- **수정 방향(제안)**: `_mock_segment` 에서 `x1,y1,x2,y2` 를 `[0,width]`/`[0,height]` 로 clamp하고, clamp 후 면적이 0 이면 중앙 사각(`sam2.py:310`) 분기로 폴백. (⚠ 구현하지 않음)


### [G-ISSUE-44] TC-AISAM2-19 — `track_id` 를 정제 없이 로그 포맷에 투입(Log Injection 잠재)
- **심각도**: LOW
- **기대 동작(기대효과)**: 사용자/상위 시스템이 제어하는 문자열은 로그 출력 전 개행(`\n`,`\r`) 제거가 필요하다(`rules/security.md` — CWE-117 Log Injection, BE 는 `LogSanitizer` 로 이미 이 규약을 지킨다).
- **현재 동작(이슈 내용)**: `track_id` 가 원문 그대로 로그 포맷 인자로 들어간다.
  ```python
  # ai-server/app/routers/sam2.py:57-62, 67-75
  logger.info("[SAM2][MOCK] track reason=%s track_id=%s points=%d", reason, req.track_id, ...)
  logger.info("[SAM2] track received track_id=%s prev=%dx%d next=%dx%d points=%d", req.track_id, ...)
  ```
  `track_id="a\nINJECT b"` 요청은 **200 으로 처리되고 응답에도 그대로 반사**된다(실측). 다만 현재 배포 컨테이너는 **앱 로거의 INFO 가 출력되지 않아**(`docker logs` 에 `[SAM2] track received` 0건, WARNING/ERROR 만 출력) **미발현 상태**다.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:19300/infer/sam2/track -H 'Content-Type: application/json' \
    -d '{"track_id":"a\nINJECT b","prev_polygon":[[1,1],[9,1],[9,9]],"prev_image_b64":"<b64>","next_image_b64":"<b64>"}'
  # 실측 200, 응답 track_id 에 개행 그대로. 로그 레벨을 INFO 로 올리면 로그 라인 위조 가능
  ```
- **영향**: CWE-117. 로그 레벨을 INFO 로 올리거나 로그 수집기를 붙이는 순간 위조 라인이 삽입된다(감사 추적 오염). 현재 심각도는 낮으나 **로그 설정 변경 한 번으로 활성화되는 잠재 결함**이다.
- **수정 방향(제안)**: `track_id` 에 스키마 제약(`pattern=r"^[A-Za-z0-9_\-:.]{1,64}$"` 등)을 걸어 앞단에서 차단하거나, ai-server 공통 `sanitize()` 헬퍼로 개행 제거 후 로깅. (⚠ 구현하지 않음)


### [G-ISSUE-45] TC-AISAM2-05/16 인접 — `points`/`prev_polygon` 배열 길이 상한 부재(CWE-770)
- **심각도**: LOW
- **기대 동작(기대효과)**: `image_b64` 는 크기 상한(`max_image_size_mb`)과 픽셀 상한(`MAX_IMAGE_PIXELS=50M`)이 이중으로 걸려 있다(`image_utils.py:34,79`). 동일한 리소스 소비 축인 **프롬프트 배열도 상한**이 있어야 일관된다(`rules/security.md` — Unrestricted Resource Consumption / OWASP API4:2023).
- **현재 동작(이슈 내용)**: 배열 길이 상한이 없다.
  ```python
  # ai-server/app/schemas.py:152
  points: list[list[float]] | None = Field(default=None, ...)          # max_length 없음
  # ai-server/app/schemas.py:177
  prev_polygon: list[list[float]] = Field(..., min_length=3, ...)      # 하한만 있고 상한 없음
  ```
  실측(부하 억제를 위해 1,000 원소까지만 시도): `points=1`/`100`/`1000` 각각 1.43s/1.49s/1.57s, `prev_polygon=1000` 1.44s — **1,000 규모에서는 영향 미미**(SAM2 가 프롬프트를 배치 처리). 상한이 없다는 사실만 확인.
- **재현/확인 경로**: `POST /infer/sam2/segment` 에 `points` 를 대량(수십만 개) 실어 전송. JSON 파싱·numpy 변환 메모리가 요청 크기에 선형 비례한다. (본 검증에서는 공유 환경 보호를 위해 1,000 까지만 실행)
- **영향**: CWE-770. ai-server 는 무상태·무인증이라 내부망에서 직접 도달 가능하고, GPU/CPU 자원을 BE 배치와 공유하므로 대량 프롬프트 요청이 배치 추론 지연으로 번질 수 있다. 실측상 즉각적 DoS 는 아니어서 LOW.
- **수정 방향(제안)**: `points` 에 `max_length`(예: 64), `prev_polygon` 에 `max_length`(예: 4096) 부여. 아울러 uvicorn/게이트웨이 레벨 요청 바디 상한 설정 검토. (⚠ 구현하지 않음)

---

## 5. 근거 드리프트

**0건.** 카탈로그 28행의 `file:line` 을 전수 대조한 결과 전부 현행과 일치(대표: `routers/sam2.py:299-314/303-304/309-310/134-163/150-151/155-158/199-201/217-220/232-235/238-247/248-260/282-292/317-326/84-101`, `schemas.py:148-155/151/153-155/177`, `image_utils.py:37-44/42-43`, `sam2_loader.py:32-37/44-47/51-58/27-59`, `test_mock_indicator.py:33,49`, `test_sam2_meta.py:290`, `test_sam2_real.py:56,77,94`).
`ai-server/` 는 2026-07-25 이후 코드 변경이 없다는 카탈로그 기술과 일치(워킹트리 미커밋 변경 0건).

## 6. UNCERTAINTIES 갱신 제안 (사실 확정분)

| # | 항목 | 기존 | **본 회차 실측** |
|---|------|------|------|
| 15 | 실모델 테스트 게이팅 | 미해소 유지 | **✅ 부분 확정** — 배포 컨테이너 `klid-ai-server` 에 `sam2`(Meta, `requirements.txt:177`) 가 **실제 설치·로드**돼 있고 실추론이 동작한다(`mock=false, source="model"`). 따라서 `test_sam2_real.py` 의 `skipif(find_spec("sam2") is None)` 게이트는 **이 이미지 안에서는 skip 되지 않는다**. 로컬 호스트 `.venv` 기준으로만 "미설치 skip" 이었던 것이며, 컨테이너 기준으로 실모델 테스트를 상시 실행하도록 baseline 방침 갱신 가능 |
| — | (신규) SAM2 `weights_missing` 사유 | — | SAM2 는 HF 자동 다운로드 방식이라 `weights_missing` 이 **실경로에서 도달 불가**한 사문 분기(`routers/sam2.py:101`). YOLOX(로컬 ONNX 파일)와 사유 어휘를 공유하면서 생긴 잔재 — 정리 여부 판단 필요 |


### [G-ISSUE-61] TC-AIMOCK-44 — VLM 타임아웃 "2계층(45s 블록 + 10s WebClient)" 전제가 코드에 없음 (논블로킹 전환으로 폐지)
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스는 "WebClient 자체 10s(`vlm.client.timeout-seconds`) 안쪽에 배치 오케스트레이션 블록 45s(`BLOCK_TIMEOUT`)" 라는 **2계층 타임아웃**이 존재하고, describe 무응답 시 10s 가 먼저 발화하며 스텝 상한이 45s 임을 보장해야 한다고 기술한다. 외부 지연이 파이프라인 스레드를 무한 점유하지 않도록 하는 안전장치의 존재 확인이 목적이다.
- **현재 동작(이슈 내용)**: `VlmTimeseriesStep` 은 Phase C-1 에서 **논블로킹 제출**로 전환되어 `.block(45s)` 와 `BLOCK_TIMEOUT` 상수가 **삭제**됐다. 45s 는 폐지된 구현을 설명하는 주석에만 남는다.
  - `backend/.../batch/step/VlmTimeseriesStep.java:76` (javadoc) — `<li><b>제출</b> — {@code subscribe} 만 하고 즉시 반환({@code status="submitted"}).</li>`
  - `VlmTimeseriesStep.java:348-349` (주석) — `구 코드는 .block(45s) 로 파이프라인 스레드(batch-async- / Quartz 워커 …)를 최대 45초 붙잡았다.`
  - `VlmTimeseriesStep.java:366` — `.subscribe(`
  - 현존 타임아웃 1계층: `VlmClient.java:76` `this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));` ← `application.yml:709 timeout-seconds: 10`, 적용 지점 `VlmClient.java:108 .timeout(timeout)`
  - 케이스가 지정한 근거 `VlmTimeseriesStep.java:78` 은 현재 javadoc 라인(코드 아님)
- **재현/확인 경로**: `grep -n "BLOCK_TIMEOUT\|block(" backend/src/main/java/kr/co/cudo/authoring/batch/step/VlmTimeseriesStep.java` → 매치 0건(주석의 `.block(45s)` 문자열만). 반대 방향을 강제하는 회귀 가드: `backend/src/test/java/.../batch/step/VlmTimeseriesStepNonBlockingTest.java:106 ACK가_끝내_오지_않아도_제출호출이_파이프라인_스레드를_붙잡지_않는다`
- **영향**: 코드 결함 아님(논블로킹 전환은 배치 풀 고갈을 막는 의도된 개선). 다만 **카탈로그가 사문화된 기대값을 들고 있어** 다음 회차에서 "45s 계층이 없다 → FAIL" 로 오판정하거나, 반대로 45s 를 되살리는 회귀 수정을 유발할 수 있다. UNCERTAINTIES #13 의 "타임아웃은 이중 구조 — 블록 상한 45s ↔ 실효 10s" 서술도 같은 이유로 낡았다.
- **수정 방향(제안)**: 코드 수정 없음. `docs/test-cases/G-ai-server.md` TC-AIMOCK-44 의 기대결과를 "타임아웃은 `vlm.client.timeout-seconds`(기본 10s) **단일 출처**이며, 스텝은 ACK 를 기다리지 않고 즉시 반환한다(미회신은 `VlmSubmitPendingSweeper` `staleTimeoutMinutes=30` 이 회수)" 로 교체하고 근거를 `VlmClient.java:76,108` + `VlmTimeseriesStep.java:366` 으로 갱신. `UNCERTAINTIES.md` #13 의 이중구조 문구도 동일 정정.


### [G-ISSUE-62] TC-AIVLM-14 파생 — `AiServerClient.verifyObjects` 프로덕션 호출부 0건 (G-5 엔드포인트 전체가 미사용 표면)
- **심각도**: LOW
- **기대 동작(기대효과)**: ai-server `/infer/vlm/verify-objects` 는 "YOLO/SAM2 검출 결과의 라벨 정합성 검증" 목적으로 노출된 엔드포인트이고, BE 는 `AiServerClient.verifyObjects` 로 이를 호출하는 것이 카탈로그 §G-5 전제다. 노출된 추론 표면은 실제로 사용되거나, 사용되지 않으면 제거되어야 한다(미사용 API 표면 = OWASP API9:2023 Improper Inventory Management).
- **현재 동작(이슈 내용)**: 클라이언트 메서드는 존재하나 **`src/main` 어디에서도 호출되지 않는다.**
  - `backend/.../common/client/AiServerClient.java:89-98` — `public Mono<VlmVerifyResponse> verifyObjects(VlmVerifyRequest request) { return webClient.post().uri("/infer/vlm/verify-objects") … }`
  - `grep -rn "verifyObjects" backend/src/main backend/src/test` → `src/main` 은 정의부 1건뿐, 호출부 0건. `src/test` 도 실호출 없이 `AiInferenceDeidentReportGateTest.java:71` 주석 언급 1건
  - `grep -rn "infer/vlm" backend/src frontend/src` → BE 3건(정의/주석/DTO 주석), FE 0건
  - [실동작] ai-server 접근 로그 집계: `POST /infer/vlm/verify-objects` 12건이 **전부 172.20.0.1(호스트 = 본 검증자 curl)** 이고 backend(172.20.0.5) 발신 0건. 같은 로그에서 `POST /infer/sam2/track` 138건 · `/infer/yolo/predict` 23건 등 실제 사용 엔드포인트는 backend 발신이 관측됨
- **재현/확인 경로**:
  - `grep -rn "verifyObjects" backend/src/main` (호출부 0건 확인)
  - `docker logs klid-ai-server 2>&1 | grep "verify-objects" | awk '{print $2}' | sort -u` (발신 IP 확인)
- **영향**: 기능 결함 아님. 다만 ①인증 없는 추론 표면이 사용처 없이 열려 있고(ai-server 는 무인증) ②G-5 14개 케이스가 **제품 동선에서 도달 불가능한 경로**를 검증 중이라 검증 리소스 배분이 왜곡된다. `AiInferenceDeidentReportGateTest.java:71` 주석은 "`verifyObjects` 처럼 같은 이미지를 운반하는 다른 메서드"가 비식별 신고 게이트를 우회할 수 있음을 지적하는데, 현재는 호출부가 없어 잠재 위험으로만 남아 있다 — 향후 배선 시 게이트 누락 위험(CWE-359).
- **수정 방향(제안)**: 정책 결정 필요 — ①실사용 계획이 없으면 `AiServerClient.verifyObjects` + `ai-server/app/routers/vlm.py` 라우트 + 관련 DTO/스키마를 제거(표면 축소), 또는 ②사용 계획이 있으면 배선 시 `encodeDeidentifiedFrameForInference` 경로 + 비식별 신고 게이트를 반드시 함께 적용. 어느 쪽이든 카탈로그 §G-5 에 "현재 프로덕션 호출부 0건" 을 명기해 검증자가 도달 불가 경로임을 알게 한다.


### [G-ISSUE-63] 근거 file:line 드리프트 4건 (카탈로그 정합성)
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스의 `근거(file:line)` 는 판정자가 즉시 대조할 수 있는 실제 위치여야 한다. 어긋나면 검증자가 엉뚱한 라인을 읽고 거짓 PASS/FAIL 을 낸다.
- **현재 동작(이슈 내용)**: 아래 4건이 실제 위치와 불일치(각각 실측 확인).
  | ID | 카탈로그 표기 | 실제 위치 |
  |---|---|---|
  | TC-AIVLM-10 | `schemas.py:204,211` | 요청 모델 `extra="forbid"` 는 `schemas.py:196`(ObjectToVerify) · `:204`(VlmVerifyRequest). **`:211` 은 응답 모델 `ObjectVerification`** 이라 요청 거부 근거가 아님 |
  | TC-AIMOCK-43 | `application.yml:512-520` | 실제는 circuitbreaker `application.yml:532-539` + retry `:606-612`. 512-520 은 `kpst.deid.*` 폴링 설정 |
  | TC-AIMOCK-45 | `application-local.yml:150` | 실제는 `application-local.yml:147 enabled: ${VLM_CLIENT_ENABLED:true}`. 150 은 빈 줄 |
  | TC-AIMOCK-46 | `VlmTimeseriesStep.java:60-65,95,216-220` | 게이트 본체는 `:303-306`, 사유 상수는 `:113`. `:95` 는 `DEFAULT_FRAMERATE` 상수, `:216-220` 은 `execute(BatchContext)` javadoc |
  (별도로 TC-AIMOCK-44 의 `VlmTimeseriesStep.java:78` 은 G-ISSUE-61 에서 다룸)
- **재현/확인 경로**: `sed -n '196p;204p;211p' ai-server/app/schemas.py` · `sed -n '532,539p;606,612p' backend/src/main/resources/application.yml` · `sed -n '147p;150p' backend/src/main/resources/application-local.yml` · `sed -n '95p;303,306p' backend/src/main/java/kr/co/cudo/authoring/batch/step/VlmTimeseriesStep.java`
- **영향**: 검증 효율·신뢰도 저하(카탈로그 자체의 정합성 결함). 제품 동작 영향 없음.
- **수정 방향(제안)**: `docs/test-cases/G-ai-server.md` 의 해당 4행 근거 컬럼을 위 표의 "실제 위치" 로 교체.


### [G-ISSUE-65] TC-AIMOCK-40 — 외부 URL 대역 검사가 호스트의 **첫 번째 해석 주소만** 검사 (다중 A 레코드 우회 여지)
- **심각도**: LOW
- **기대 동작(기대효과)**: relaxed 정책은 링크로컬/클라우드 메타데이터 대역(169.254.0.0/16 · fe80::/10)을, strict 정책은 loopback/사설/링크로컬 전부를 거부해야 한다(CWE-918 SSRF). 호스트명이 **여러 주소로 해석되는 경우에도** 위험 대역이 섞여 있으면 거부되는 것이 안전하다.
- **현재 동작(이슈 내용)**: 두 검사 모두 `InetAddress.getByName(host)` 를 써 **첫 번째 주소 1개만** 판정한다. 호스트명이 `[공인IP, 169.254.169.254]` 처럼 복수 레코드로 해석되고 첫 주소가 안전하면 검사를 통과하며, 실제 커넥션은 JDK/OS 의 주소 선택에 따라 위험 주소로 갈 수 있다.
  - `backend/.../common/config/ExternalUrlPolicy.java:161-165` — `return InetAddress.getByName(normalized);` (relaxed 경로)
  - `ExternalUrlPolicy.java:170-175` — `addr = InetAddress.getByName(host);` (strict 경로)
  - `:148` / `:176-177` 이 그 **단일** `addr` 만 검사
- **재현/확인 경로**: `vlm.client.url` 을 A 레코드가 2개(첫 번째 공인, 두 번째 169.254.x)인 호스트명으로 설정 → 기동이 통과. (환경 구성이 필요해 본 회차에서 실동작 재현은 하지 않음 — 정적 확인)
- **영향**: CWE-918(SSRF) 잔여 표면. 다만 **트리거 조건이 "운영자가 base-url 설정값을 그런 호스트명으로 지정" 이라 신뢰 경계 안쪽**이고, 값은 사용자 입력이 아니라 환경변수라 실착취 가능성은 낮다. 부수적으로 기동 시점 검사와 실제 커넥션 시점 해석이 분리돼 있어 DNS rebinding 에 대한 TOCTOU 여지도 동일하게 남는다(현 설계가 이를 방어 대상으로 선언하지 않음).
- **수정 방향(제안)**: `InetAddress.getAllByName(host)` 로 바꿔 **해석된 전 주소**에 대해 대역 검사를 수행(하나라도 위험 대역이면 거부). relaxed 경로의 "해석 실패는 통과" 규약은 그대로 유지(`getAllByName` 도 `UnknownHostException` 을 던지므로 동일 catch 로 처리 가능). 근본적 rebinding 방어가 필요해지면 커넥션 시점 IP 검증(커스텀 `AddressResolver`)을 별도 설계.

---

## 근거 드리프트 요약 (카탈로그 정정 입력)

| ID | 카탈로그 근거 | 실제 |
|---|---|---|
| TC-AIVLM-10 | `schemas.py:204,211` | `schemas.py:196,204` (211=응답모델) |
| TC-AIMOCK-43 | `application.yml:512-520` | `application.yml:532-539`(CB) + `:606-612`(retry) |
| TC-AIMOCK-44 | `VlmTimeseriesStep.java:78` | 45s 계층 폐지 — `VlmClient.java:76,108` 단일 계층 |
| TC-AIMOCK-45 | `application-local.yml:150` | `application-local.yml:147` |
| TC-AIMOCK-46 | `VlmTimeseriesStep.java:95,216-220` | `VlmTimeseriesStep.java:113,303-306` |

정확했던 근거(대조 완료): `routers/vlm.py:38-69/72-80/89-104/93/45`, `schemas.py:195-200/200/207`, `image_utils.py:42-43`, `AiServerClient.java:91`, `VlmClient.java:53,67,88-112,94-97,106,121-127,154-169`, `DeidentifyHealthIndicator.java:77-83/84-91/92-104/105-112`, `ProfileGatedUrlPolicy.java:78-91/121-133/145-151`, `ExternalUrlPolicy.java:80-82/143-153/169-181`, `test_vlm.py:52`.

## UNCERTAINTIES 갱신 제안

- **#13 (VLM 45s 타임아웃·콜백·IntelliVIX v2.0.1)**: "타임아웃은 이중 구조 — 블록 상한 45s ↔ 실효 10s" → **"단일 구조 — `vlm.client.timeout-seconds`(기본 10s) 단일 출처. 45s 블록 계층은 Phase C-1 논블로킹 전환으로 폐지"** 로 정정(G-ISSUE-61). describe→callback 실왕복은 본 회차에서 **재확인**(rawSn 80·81·94). 남은 미확정은 여전히 IntelliVIX 실서버 대조 1건.
- **#15 (실모델 테스트 게이팅)**: 런타임 실측으로 상태 확정 — 컨테이너는 `AI_MOCK_MODE=false` 이지만 **가중치 파일 부재**로 `mock_reason=weights_missing` 이 반환된다(`[YOLOX] weights not found — fallback to mock` 기동 로그). 즉 "환경변수는 실모델이나 실제로는 mock" 상태이며, 이를 구분할 수 있는 신호는 응답의 `mock_reason` 뿐이고 **INFO 로그로는 확인 불가**(G-ISSUE-64). 게이팅 정책 자체는 여전히 미확정.


### [G-ISSUE-83] TC-AIMOCK-11 — 기대결과의 "동기 202" 는 오기(벤더 규격·실동작 모두 200)
- **심각도**: LOW
- **기대 동작(기대효과)**: VLM verify/describe 의 동기 응답 코드는 벤더 v2.0.1 §2.1/§2.5 가 **200 OK** 로 못박고 있고 BE `VlmClient` 도 그 전제로 동작한다. 카탈로그가 202 라고 적으면 다음 회차가 "202 가 아니니 FAIL" 로 오판할 수 있다.
- **현재 동작(이슈 내용)**: 카탈로그 TC-AIMOCK-11 기대결과 = `동기 202 accepted, 콜백은 status=failed+error_code/message`. 실동작은 **200**:
  ```
  $ curl -w "HTTP=%{http_code}" -X POST .../v1/videovlm/verify -d '{"request_id":"fail-x",...}'
  {"request_id":"fail-x","status":"accepted"}  HTTP=200
  ```
  같은 절의 TC-AIMOCK-09 는 "동기 200" 으로 적혀 있어 **절 내부에서도 자기모순**이다. 또 실패 콜백은 평면 `error_code`/`error_message` 가 아니라 벤더 규격대로 **중첩 `error{code,message}`** 다(`vlm_sim.py:329-335`).
- **재현/확인 경로**: 위 curl 1줄.
- **영향**: 카탈로그 정합성. 202 를 정본으로 착각해 목업을 202 로 "고치면" **벤더 계약을 깨는 회귀**가 된다.
- **수정 방향(제안)**: TC-AIMOCK-11 기대결과를 `동기 200 accepted, 콜백은 {"status":"failed","error":{"code":"INFERENCE_ERROR","message":...}}` 로 정정.


### [G-ISSUE-85] G-8 — VLM 요청 스키마가 벤더 규격의 조건부 필수 필드를 검증하지 않아 계약 회귀를 못 잡는다
- **심각도**: LOW
- **기대 동작(기대효과)**: 목업이 벤더보다 관대하면 BE 가 규격 위반 요청을 보내도 로컬에서 200 이 나고, 실벤더 전환 시점에야 400/422 로 드러난다. 벤더 §3.1/§3.2 의 조건부 필수는 목업도 강제해야 한다.
  | 필드 | 벤더 규격 |
  |---|---|
  | `media.path` | `source_type=path` 인 경우 **필수** |
  | `frame_policy.framerate` | **Required = Y** |
  | `frame_policy.selected_frames` | `mode=frame_selected` 인 경우 **필수**(최대 8) |
- **현재 동작(이슈 내용)**: 셋 다 무조건 Optional 이라 누락돼도 접수된다.
  ```python
  # mock-server/app/schemas/vlm.py:42-46
  framerate: Optional[int] = Field(default=None, ge=1, le=240, ...)
  selected_frames: Optional[list[int]] = Field(default=None, max_length=8, ...)
  # :56
  path: Optional[str] = Field(default=None, ...)
  ```
  실동작(3건 모두 `200 accepted`): ①`frame_policy={"mode":"frame_interval"}`(framerate 없음) ②`media={"type":"video","source_type":"path"}`(path 없음) ③`frame_policy={"mode":"frame_selected","framerate":25}`(selected_frames 없음).
- **재현/확인 경로**:
  ```bash
  curl -s -w " %{http_code}\n" -X POST http://localhost:9400/v1/videovlm/describe -H 'Content-Type: application/json' \
    -d '{"request_id":"lax","media":{"type":"video","source_type":"path"},"callback_url":"http://localhost:1/cb"}'
  # {"request_id":"lax","status":"accepted"} 200   ← 벤더 규격상 path 필수
  ```
- **영향**: 계약 검증 공백. 현재 BE(`VlmTimeseriesRequest.ofFrameInterval`)는 항상 `framerate`·`path` 를 채우므로 즉시 장애는 없으나, BE 리팩터가 이를 빠뜨려도 **로컬·CI 어디서도 잡히지 않는다**.
- **수정 방향(제안)**: `schemas/vlm.py` 의 `Media`/`FramePolicy` 에 pydantic `model_validator(mode="after")` 를 추가해 위 3개 조건부 필수를 400(구조 오류) 으로 거부. G-8 에 회귀 케이스 3건 신설.


### [G-ISSUE-86] G-7 — `GET /manual_deid_info` 가 KPST 규격의 "프로젝트 상태=3(수동 대상)" 조건을 반영하지 않는다
- **심각도**: LOW
- **기대 동작(기대효과)**: KPST 명세(`docs/v2-wiki/22-deid-solution-api.md` §22.3.8 / §22.4)는 이 EP 를 "`db_save=1` **이며 프로젝트 상태가 수동 대상(state=3)** 인 데이터셋"으로 정의한다.
- **현재 동작(이슈 내용)**:
  ```python
  # mock-server/app/routers/deid.py:543-545
  def _manual_targets() -> list[Project]:
      """수동 비식별화 대상(db_save=1) 프로젝트 목록."""
      return [p for p in get_store().list_projects() if p.db_save == 1]
  ```
  실동작: `db_save=1` 로 만든 프로젝트가 **진행 중이든 완료든** 전부 반환된다(09:14 실측, 완료 직후 조회 시 1건 반환).
  더 나아가 목의 상태코드 정의(`deid_sim.py:56-60`)는 `prjState 3 = 완료` 인데 KPST 명세 §22.4 는 `프로젝트 상태 3 = 수동 비식별화 대상` 이라 **같은 값에 두 의미**가 붙어 있다.
- **재현/확인 경로**: `curl -s http://localhost:9400/manual_deid_info` — 완료(`prjState=3`)·진행중(`prjState=2`) 프로젝트가 구분 없이 나온다.
- **영향**: 기능 영향 없음(`22-deid-solution-api.md` §22.6 이 수동 비식별 연계를 "⏳ 후속·미구현"으로 명시, BE 호출부 0건). 다만 향후 수동 비식별 워크플로를 붙일 때 목업이 필터를 재현하지 않아 로컬 검증이 헛돌 수 있고, `prjState=3` 의미 충돌은 오독을 부른다.
- **수정 방향(제안)**: `_manual_targets` 에 `prj_state_for(...) == 수동대상` 조건 추가 또는 목이 수동 대상 상태를 별도 축으로 모델링. 최소한 `deid_sim.py:56` 의 상태코드 주석에 "KPST 명세 §22.4 의 '수동 대상 state=3' 과 값이 겹친다"는 경고를 남긴다.


### [G-ISSUE-87] G-7/G-8/G-9 — 근거 `file:line` 대량 드리프트(33건 중 15건)
- **심각도**: LOW
- **기대 동작(기대효과)**: 근거 `file:line` 은 검증자가 곧바로 해당 코드로 점프하는 진입점이다. 어긋나면 매 회차마다 Grep 재탐색 비용이 들고, 최악에는 **엉뚱한 코드를 근거로 PASS** 를 찍는다(실제로 TC-AIMOCK-03 이 가리키는 `deid_sim.py:210-217` 에는 지금 placeholder **폐기** 주석이 있어, 그대로 읽으면 정반대 결론에 도달한다).
- **현재 동작(이슈 내용)**: 확인된 드리프트 15건.
  | TC | 카탈로그 | 실제 |
  |---|---|---|
  | 01 | `deid.py:101-105` | `deid.py:116-119` |
  | 02 | `main.py:94-96` | `main.py:103-105` |
  | 03 | `deid.py:76-98` / `deid_sim.py:210-217` | `deid.py:86-112` / `deid_sim.py:272-279` |
  | 04 | `deid_sim.py:162-165,193-207` | `deid_sim.py:225,255-269` |
  | 05 | `deid_sim.py:409-444` | `deid_sim.py:1704-1714` |
  | 06 | `deid_sim.py:248-276,446-453` | `deid_sim.py:1716-1725` / `path_policy.resolve_output_dir` |
  | 07 | `deid_sim.py:279-326,359-406` | `deid_sim.py:334-353, 210-217` |
  | 08 | `deid_sim.py:328-346,368-373` | `deid_sim.py:439~, 1523~` |
  | 12 | `vlm.py:228-231` | `vlm.py:241-244` |
  | 17 | `genai_sim.py:81-86,480-495,583-648` | `genai_sim.py:93-97,761-826,639-654` |
  | 19 | `genai_sim.py:380-458` | `genai_sim.py:539-617` |
  | 22 | `genai_sim.py:396-400` | `genai_sim.py:555-559` |
  | 23 | `genai_sim.py:223-250` | `genai_sim.py:382-409` |
  | 24 | `genai_sim.py:161-220` | `genai_sim.py:328-376` |
  | 26 | `genai_sim.py:288-361,461-476` | `genai_sim.py:447-467,620-635` |
  | 27 | `genai_sim.py:364-378` | `genai_sim.py:523-536` |
  | 29 | `config.py:179-186` | `config.py:203-210` |
  | 31 | `genai_sim.py:563-579` | `genai_sim.py:722-740` |
  라우터(`augment.py`)·`main.py:32-48`·`augment.py:178-184` 등은 정확하다. 드리프트는 **크게 성장한 서비스 모듈**(`deid_sim.py` 1,988줄 / `genai_sim.py` 860줄)에 집중.
- **재현/확인 경로**: `sed -n '210,217p' mock-server/app/services/deid_sim.py` — TC-AIMOCK-03 이 "fileName=원본 경로"의 근거로 가리키는 자리에 placeholder 폐기 주석이 있다.
- **영향**: 카탈로그 정합성 + 검증 효율. 회차마다 반복 비용.
- **수정 방향(제안)**: `G-ai-server.md` §G-7~G-9 의 근거 컬럼을 위 표대로 일괄 갱신. 장기적으로는 라인 번호 대신 **심볼명**(`deid_sim.produce_deid_outputs`, `genai_sim.build_results` 등)으로 표기해 드리프트를 원천 차단.


### [G-ISSUE-88] G-7 — 산출 성공 후 `.mock-tmp/` 빈 디렉터리가 export_path 에 잔존
- **심각도**: LOW
- **기대 동작(기대효과)**: BE 는 완료 후 export 디렉터리를 **폴백 스캔**해 단일 산출물을 회수한다(`22-deid-solution-api.md`). 목이 만드는 임시 작업 디렉터리는 산출 완료 시 정리돼 스캔 대상이 깨끗해야 한다.
- **현재 동작(이슈 내용)**: 정상 산출 후에도 디렉터리가 남는다.
  ```
  $ docker exec klid-mock-server ls -la /app/storage/deidentified/videos/qa-g5-p1/
  drwx------ 2 app app  4096 Aug  2 09:14 .mock-tmp     ← 빈 디렉터리, 잔존
  -rw-r--r-- 1 app app 50854 Aug  2 09:14 clip-9101-mask.mp4
  ```
  `sweep_temp_dir`/`sweep_orphan_temp_files`(`deid_sim.py:1174,1227`)는 **임시 파일**을 지우지만 디렉터리 자체는 제거하지 않고, 산출 진입 시점(`produce_deid_outputs`)에만 sweep 한다.
- **재현/확인 경로**: 위 `ls -la` (정상 완료 프로젝트의 export_path).
- **영향**: 현재 무해(BE 회수는 1차 `{stem}-mask{ext}` 경로가 맞아 폴백 스캔에 도달하지 않으며, 폴백 스캔도 파일만 대상). 다만 export 폴더에 계약 밖 엔트리가 남아 관제/데이터마트 관점의 산출 폴더 청결성을 해친다.
- **수정 방향(제안)**: `produce_deid_outputs` 종료부에서 `TEMP_DIR_NAME` 디렉터리가 비어 있으면 `rmdir`(genai `discard_results:534-536` 와 동일 패턴). 실패는 무시(격리).

---

## 부록 — 확증편향 반증 시도 기록

이 클러스터는 "목업 자체의 품질"을 감사하므로, **목업 구현을 기준으로 삼지 않고 벤더 계약 원문을 기준**으로 대조했다.

| 반증 시도 | 결과 |
|---|---|
| 벤더 원문(`video_vlm_api_ v2.0.1.docx`) 본문을 추출해 목업과 필드 단위 대조 | `/status` 응답 어휘 불일치 발견(**G-ISSUE-84**), 조건부 필수 3건 미검증 발견(**G-ISSUE-85**) |
| 카탈로그 기대결과가 코드를 그대로 베낀 것인지 확인 | TC-AIMOCK-12 가 정확히 그 사례 — 계약 위반을 통과시키고 있었음 |
| 목업이 "보안 가드가 있다"고 주장하는 지점을 실제로 뚫어봄 | VLM 콜백 가드를 **자기참조로 우회해 genai 저장소 파괴 성공**(**G-ISSUE-82**) |
| 카탈로그 기대결과가 현 코드 정책과 반대인 곳 탐색 | TC-AIMOCK-07 placeholder(**G-ISSUE-81**), TC-AIMOCK-11 202(**G-ISSUE-83**) |
| PASS 근거로 인용한 라인이 실제로 그 코드인지 확인 | 15건 드리프트(**G-ISSUE-87**) — 그중 TC-AIMOCK-03 은 정반대 결론을 유도할 위치 |
| self-fill 여부(목 미경유 자체 채움) | genai `_mock/jobs` 에 BE 발급 `AUG-*` job 실존, KPST 산출물이 실제 파일로 존재, VLM 콜백 outbound 로그 존재 → **self-fill 징후 없음** |
| 경계 위반이 조용히 성공으로 보고되는지 | export 밖 → `procState=99`, input 밖 → `procState=99`, genai 출력 base 미설정 → FAILED. **거짓 완료 없음** |

### 실동작으로 확인된 응답 코드 매트릭스 (재현용)

| 요청 | 실측 |
|---|---|
| `GET /` | 200 `Connect` (text/plain) |
| `GET /health` | 200 `{"status":"ok"}` |
| `POST /project` 정상 | 200 `{"result":"success","prj_id":N}` |
| `POST /project` 중복 이름 | 409 `CONFLICT` |
| `POST /project` files 없음(is_img=0) | 400 `VALIDATION_ERROR` |
| `GET /retrieve_progress` reqUserId 누락 | 400 · 필터 0개 400 · 미존재 404 |
| `POST /v1/videovlm/verify` 정상 | **200** `{request_id,status:"accepted"}` |
| `…/verify` event_type 오류 | 422 · media 누락 400 · callback 호스트 위반 400 |
| `GET /v1/videovlm/status` | 200 `{"status":"ok","service":"videovlm"}` (규격은 `ready`/`busy`) |
| `POST /api/genai/jobs` 정상 | **202** `{request_id,job_id,status:"RECEIVED",received_at}` |
| genai 필수누락/중복seq/경로위반/콜백위반/64자초과 | 400 (각각 `REQUIRED_FIELD_MISSING`/`INVALID_PARAMETER`) |
| genai 본문>1MiB | 413 `GA-MEDIA-001` · prompt>64KiB 400 `INVALID_METADATA` |
| genai results(비SUCCEEDED) / cancel(종결) | 409 `STATE_CONFLICT` |
| genai 미존재 job | 404 `JOB_NOT_FOUND` |



## 클러스터 H — FE/화면/E2E

### [H-ISSUE-02] TC-E2E-001 / TC-FE-003 / TC-FE-021 — 상위 시스템 로그인 URL 이 **운영 배포 어디에서도 주입되지 않아** 세션 만료·401 시 상위 로그인 이동이 성립하지 않는다
- **심각도**: HIGH
- **기대 동작(기대효과)**: `CLAUDE.md` "세션 만료 시 각 상위 시스템 로그인 페이지로 리다이렉트". TC-E2E-001(토큰 없이 보호 경로 → 상위 로그인), TC-FE-003(exp 만료 → `redirectToUpstream`), TC-FE-021(401 → `clear()`+상위 로그인)이 모두 이 배선에 의존한다. 저작도구는 **자체 로그인 UI 가 없으므로** 이 경로가 끊기면 사용자가 재로그인할 수단 자체가 없다.
- **현재 동작(이슈 내용)**: `redirectToUpstream` 은 env 미설정 시 아무 것도 하지 않고 `false` 를 반환하는데, 그 env 를 채우는 배포 배선이 없다.
  ```ts
  // frontend/src/features/auth/redirectToUpstream.ts:13-18
  const portalUrl  = import.meta.env.VITE_PORTAL_LOGIN_URL as string | undefined;
  const controlUrl = import.meta.env.VITE_CONTROL_LOGIN_URL as string | undefined;
  const target = channel === 'PORTAL' ? portalUrl : controlUrl;
  if (!target) return false;          // ← 운영 빌드에서 항상 여기
  ```
  전수 grep 결과 — 두 변수는 **`frontend/.env.example`(빈 값 아님, 예시 도메인)과 `frontend/.env.development`(빈 값)에만** 존재하고
  `docker-compose.yml` · `frontend/Dockerfile` · `deploy/onprem/**`(패키징·설치 스크립트·Caddyfile/nginx 템플릿·env.template) 어디에도 없다.
  ```
  grep -rn "VITE_CONTROL_LOGIN_URL\|VITE_PORTAL_LOGIN_URL" docker-compose.yml frontend/Dockerfile deploy → 0건
  grep -rn "VITE_" frontend/Dockerfile → VITE_API_BASE_URL, VITE_TOKEN_INGRESS 두 개뿐
  ```
  Vite 는 `VITE_*` 를 **빌드 시점에 정적 치환**하므로(`deploy/onprem/docs/02-build-package.md:177` 명시) 런타임 주입으로 보완되지도 않는다.
- **재현/확인 경로**:
  ```
  # 현재 스택(dev 빌드) — 빈 값이라 redirect 미발생. dev 플래그 덕에 /dev/login 으로 빠진다
  신규 탭 → http://localhost:13000/dashboard   → 최종 URL /dev/login
  # 운영 빌드 재현: VITE_DEV_LOGIN_ENABLED=false 로 빌드 후 동일 진입
  #   → redirectToUpstream=false → SessionIngressPage.tsx:47 setErrorMessage(...)
  #   → "로그인 서버에 연결할 수 없습니다." 문구만 뜨는 막다른 화면(재진입 수단 없음)
  ```
- **영향**: 기능 — 운영에서 세션 만료·401 발생 시 **사용자가 스스로 복귀할 수 없다**(브라우저 주소창에 관제 URL 을 직접 입력해야 함). 특히 `client.ts:73-75` 는 `clear()` 를 먼저 수행하므로 토큰만 사라지고 이동은 안 되는 상태가 된다. TC-E2E-001 의 기대결과가 구조적으로 미충족.
- **수정 방향(제안)**: ①`frontend/Dockerfile` 에 `ARG/ENV VITE_CONTROL_LOGIN_URL`·`VITE_PORTAL_LOGIN_URL` 추가, `docker-compose.yml` build args 및 `deploy/onprem/scripts/package/20-build-frontend.sh`·`install/build-from-source.sh` 에 export 추가(값 미지정 시 **빌드를 실패시키는 fail-closed** 검사 권장 — 조용히 비면 위 막다른 화면이 된다). ②`deploy/onprem/config/frontend/*.template` 또는 설치 문서(`04-configuration.md`)에 필수 항목으로 등재. ③보완책으로 `SessionIngressPage` 의 막다른 에러 화면에 "관제서버로 돌아가기" 링크를 두되, 링크 대상도 같은 env 라 ①이 선행돼야 한다.


### [H-ISSUE-41] TC-FE-198 — 라벨 저장 PUT 이 낙관적 동시성 토큰(`labelVersion`)을 전혀 싣지 않아 lost update 가 그대로 성립
- **심각도**: HIGH
- **기대 동작(기대효과)**: 조회 응답의 `labelVersion` 을 저장 요청 body 에 되돌려 보내야, 그사이 다른 사용자가 같은 프레임을 저장했을 때 BE 가 409 로 거부한다. 라벨 저장이 **full-replace 계약**이므로 이 토큰이 없으면 "내 화면에 없던 남의 라벨"이 조용히 전량 삭제된다(C-ISSUE-21 이 막으려던 실측 결함).
- **현재 동작(이슈 내용)**: BE 는 `GET /v1/frames/{srcSn}/labels` 응답에 `labelVersion` 을 내려주지만(실측 `{"srcSn":4,…,"labelVersion":2,…}`), FE 변환 함수가 이 필드를 **반환 객체에 매핑하지 않는다**.
  ```ts
  // frontend/src/features/label/api.ts:216-228  getLabels()
  return {
    frameNo: d.frameNo ?? 0,
    srcSn,
    videoId: …,
    frameImageType,
    lockSttsCd,
    siblings,
    labels: rawList.map(normalizeLabel),
  };          // ← labelVersion 없음 (types.ts:263-269 는 필드를 선언하고 있다)
  ```
  결과적으로 `LabelingPage.tsx:545 { labelVersion: data?.labelVersion }` 는 항상 `undefined` 이고, `useUpdateLabels.ts:63-65` 의 캐시 폴백(`qc.getQueryData(internalKey)?.labelVersion`)도 같은 변환 결과를 읽으므로 `undefined` 다. `putLabels(…, undefined)` 는 `api.ts:304 ...(labelVersion != null ? { labelVersion } : {})` 로 필드를 생략 → **BE 하위호환 경로(검사 skip)** 로 떨어진다.
  저장 직후 `setQueryData` 가 PUT 응답(원본 JSON, `labelVersion` 포함)을 병합해 잠깐 캐시에 값이 생기지만, 같은 블록의 `invalidateQueries` 재조회가 다시 `getLabels()` 를 태워 값을 **덮어 지운다**.
- **재현/확인 경로**:
  1. 브라우저: `/label/4` 진입 → BBOX 1건 그리기 → Ctrl+S. DevTools Network 의 `PUT /api/v1/frames/4/labels` request payload 확인 → `{"items":[…]}` (labelVersion 없음). 2.5초 후 재저장해도 동일.
  2. 서버 측 확인:
     ```bash
     curl -s -H "Authorization: Bearer $T" http://localhost:18081/api/v1/frames/4/labels | jq '.data.labelVersion'   # → 2
     curl -s -X PUT -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
          -d '{"items":[]}' http://localhost:18081/api/v1/frames/4/labels                                            # → 200 (stale 여부와 무관하게 통과)
     ```
- **영향**: 데이터 정합 — 같은 프레임을 두 작업자(또는 두 탭)가 편집하면 **나중 저장이 앞 저장 결과를 경고 없이 전량 삭제**한다(CWE-362 계열, 라벨 유실). 부수적으로 TC-FE-197 의 409 충돌 다이얼로그와 TC-FE-199 의 캐시 우선 로직이 **실사용에서 도달 불가능한 죽은 경로**가 된다. 단위테스트(`api.test.ts:401-433`, `useUpdateLabels.test.tsx:98-`)는 `putLabels`/`useUpdateLabels` 에 버전을 **직접 주입**해 검증하므로 이 배선 단절을 잡지 못한다.
- **수정 방향(제안)**: `api.ts` `getLabels()` 반환 객체에 `labelVersion: typeof d.labelVersion === 'number' ? d.labelVersion : null` 을 추가한다(응답에 없으면 null → 기존 하위호환 유지). 회귀 가드로 `getLabels` 가 응답의 `labelVersion` 을 그대로 노출하는지 검증하는 테스트를 추가하고, 가능하면 "저장 PUT 이 조회에서 받은 버전을 실제로 싣는지"를 화면 레벨(`LabelingPage`)에서 확인하는 통합 테스트를 둔다. ⚠ 구현은 하지 않았다.


### [H-ISSUE-43] (H-3 케이스 미할당 — 캔버스 도구 실동작) 폴리곤 도구에서 마우스 클릭으로 점이 추가되지 않는다
- **심각도**: HIGH (확정 시) / 현재 판정 **확인필요**
- **기대 동작(기대효과)**: 폴리곤 도구 선택 후 캔버스를 클릭하면 점이 추가되고, dblclick 또는 시작점 근접으로 닫혀 POLYGON 라벨이 커밋되어야 한다(`OverlayLayer.tsx:107` 주석 "클릭으로 점 추가, dblclick 또는 시작점 근접 시 닫기"). 라벨링 도구의 핵심 입력 경로다.
- **현재 동작(이슈 내용)**: Chromium(Playwright) 실브라우저에서 폴리곤 도구 활성 후 캔버스 3~4회 클릭 → **드래프트 정점이 렌더되지 않고**(스크린샷 `.playwright-mcp/h3-poly-draft.png`), 이어진 `dblclick` 및 `Q` 모두 라벨 0건. 활성 라벨(`1` 키)을 먼저 선택한 경우와 아닌 경우 **모두 동일**했고, 오류 토스트도 없다(무음 실패).
  같은 페이지·같은 좌표에서:
  - `F`(점 추가) 3회 + `Q`(완성) → **POLYGON 라벨 정상 커밋**(`사람 #2 … POLYGON`)
  - BBOX 드래그(mousedown→mousemove→mouseup) → 정상 커밋
  - 선택 도구로 라벨 클릭 → 정상 선택(속성 패널에 `#tmp-…`, `형태 BBOX`) → **Konva click 자체는 발화한다**
  즉 `captureRect` 의 `onMouseDown/Move/Up`·`onDblClick` 은 발화하는데 **`onClick`(`OverlayLayer.tsx:869-905` 의 폴리곤 분기)만 도달하지 않는 것으로 보인다.
- **재현/확인 경로**: `/label/1` 진입 → 툴바 `폴리곤`(또는 `P`) → 캔버스 3회 클릭 → 정점 표시 여부 확인 → `Q` 또는 dblclick → 객체 수 변화 확인. 대조군으로 마우스 이동 후 `F` 3회 + `Q` 를 수행하면 정상 생성된다.
- **영향**: 사실이면 **마우스만 쓰는 작업자가 폴리곤/세그멘테이션을 그릴 수 없다**(SFR-08 라벨링 핵심 기능). 같은 `onClick` 경로를 쓰는 **KEYPOINT 17점 순차 배치**와 **AI 분할 클릭 프롬프트 누적**도 동일하게 영향받을 수 있다. 다만 CDP 합성 클릭과 Konva 의 click 합성(`pointerdown/pointerup` → `click`) 간 상호작용 아티팩트일 가능성이 남아 있어 **결함 확정 전 실사용자 마우스 재현이 필요**하다.
- **수정 방향(제안)**: ① 먼저 실제 마우스로 재현 여부 확정(재현 안 되면 본 이슈는 하네스 아티팩트로 종결). ② 재현되면 `OverlayLayer.tsx:869-905` 의 `onClick` 분기에 도달하는지(특히 `pointerCanvas()` 가 mouseup 시점에 null 을 반환하는지) 진단하고, 필요 시 폴리곤 점 추가를 `onMouseUp`(이동량 임계값 기반) 경로로 옮겨 BBOX/SAM2 와 이벤트 축을 통일한다. ③ 어느 쪽이든 **커밋 실패 시 무음이 되지 않도록** `commitPolygon` 실패·점 부족 상황의 사용자 안내를 보강한다. ⚠ 구현은 하지 않았다.


### [H-ISSUE-103] TC-FE-275 — 포털 데이터마트 라벨링에 AI 분할·추적·스켈레톤 도구가 노출된다 (ADR-013 위반, 이월)
- **심각도**: HIGH
- **기대 동작(기대효과)**: `CLAUDE.md` "포털 — 오토라벨링(YOLO/SAM2)·VLM·버전관리·검수 미제공" + ADR-013 에 따라 포털 채널에서는 AI 보조 도구가 제공되지 않아야 한다. `UNCERTAINTIES.md` #1 이 "문서가 정본, 노출은 정책 위반 → 결함 플래그 유지"로 확정.
- **현재 동작(이슈 내용)**: `frontend/src/features/label/components/DarkToolbar.tsx:103-105,141-147`
  ```ts
  // Phase 9 — 포털에 SAM 분할/추적·키포인트 도구 제공(PORTAL_HIDDEN_TOOLS 현재 비어있음).
  ...
  if (item.kind === 'tool') return !PORTAL_HIDDEN_TOOLS.includes(item.tool);
  ```
  실측(PORTAL_USER, `/portal/label/448`): `role=toolbar` 버튼 = `선택, 바운딩 박스, 폴리곤, AI 분할, AI 추적, 스켈레톤, 삭제, 실행 취소, 화면 맞춤, 저장`. 단축키 안내도 노출(`AI 분할 G`, `AI 추적 Shift+T`, `스켈레톤 K`). AI 탐지(YOLO)만 `portalHidden:true` 로 숨겨짐.
- **재현/확인 경로**: PORTAL_USER JWT 로 `/portal/label/{srcSn}` 진입 → 좌측 도구바에 "AI 분할"·"AI 추적"·"스켈레톤" 버튼 확인.
- **영향**: 범위 정책 위반(요구사항 불일치). 포털 사용자가 ai-server 추론 자원을 소비할 수 있다(외부 채널 자원 소모 + `UNCERTAINTIES` #12 에 따라 해당 경로에 rate limit 부재). 전송 픽셀은 비식별본(`encodeDeidentifiedFrameForInference`)이고 신고 게이트도 적용되므로 PII 노출 위험은 완화된 상태.
- **수정 방향(제안)**: `PORTAL_HIDDEN_TOOLS` 에 `SAM_SEGMENT`·`TRACK`·`KEYPOINT` 를 추가하고 `useLabelingShortcuts` 의 포털 게이팅 정책 소스와 동기화. **또는** 정책을 바꿀 거라면 `CLAUDE.md`·ADR-013·`UNCERTAINTIES.md` #1 을 먼저 갱신(문서가 정본이므로 코드 단독 선행 금지).


### [H-ISSUE-143] TC-E2E-016/017/018/019 — 전체 워크플로 E2E 픽스처(`WORKFLOW_VIDEO_ID=9035`)가 현재 DB에 존재하지 않아 완주 불가
- **심각도**: HIGH
- **기대 동작(기대효과)**: 라벨링→저장→검수제출→반려→롤백→재제출→승인 전 구간이 실제 스택에서 완주되어야 한다. 이 4건은 H 클러스터에서 유일하게 **작업 종결 워크플로 전체를 검증**하는 자산이다.
- **현재 동작(이슈 내용)**: `frontend/e2e/fixtures/test-data.ts:53-54`
  ```ts
  export const WORKFLOW_VIDEO_ID = 9035;
  export const WORKFLOW_SRC_SN = 241;
  ```
  현재 스택 실측:
  ```
  GET /api/v1/videos/9035  → 404 {"errorCode":"NOT_FOUND","message":"영상을 찾을 수 없습니다."}
  GET /api/v1/reviews/9035 → 404 {"errorCode":"NOT_FOUND","message":"검수 대상 영상을 찾을 수 없습니다."}
  GET /api/v1/videos?page=0&size=3&sort=rawSn,desc → 최대 id 906 (9035 는 범위 밖)
  GET /api/v1/frames/241/labels → {"srcSn":241,"frameNo":5,"videoId":24, ...}   ← 241 은 9035 가 아니라 24 소속
  ```
  스펙은 `/reviews/9035/submit`·`/start`·`/reject`·`/approve` 를 `waitForResponse` 로 기다리므로 제출 단계에서 타임아웃/404 로 실패하고, `describe.serial` 이라 이후 롤백·재제출·승인 3건이 연쇄 스킵된다. 스펙 상단 주석의 DB 전제(`RAW_DATA_ID=9035`, `LS_TASK_ASSIGNMENT` LABELER(2001)/REVIEWER(1001))도 현 시드와 어긋난다.
- **재현/확인 경로**: 위 curl 4줄. (E2E 실행은 본 검증 범위상 금지라 미실행 — 데이터 부재만으로 실패가 확정된다.)
- **영향**: 기능. 검수 워크플로 종결 경로의 E2E 커버리지가 **사실상 0** 이며, "E2E 스펙 11개 보유" 라는 자산 통계가 실제 보장과 어긋난다.
- **수정 방향(제안)**: ①E2E 전용 시드(Flyway `test` 프로파일 또는 `e2e/fixtures` 의 setup 스크립트)로 `rawSn=9035` + 배정 + 라벨 버전 2건을 **테스트가 스스로 만들도록** 바꾸거나, ②픽스처를 현 시드의 실재 값(예: 배정·제출 가능한 rawSn 과 그 첫 프레임 srcSn 을 API 로 조회해 주입)으로 동적 해석한다. 하드코딩 상수는 시드가 바뀔 때마다 같은 방식으로 다시 깨진다. **본 검증에서는 수정하지 않음.**


### [H-ISSUE-01] TC-FE-016 인접 — 운영 빌드 기본값이 `VITE_TOKEN_INGRESS=all` 이라 **URL `?token=` 인계 채널이 활성**이다 (CLAUDE.md 정책 위반)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 루트 `CLAUDE.md` "인증·진입" 절이 **"브라우저 스토리지(localStorage/sessionStorage) 공유로 JWT 전달. URL 쿼리 파라미터(`?token=`) 방식 미사용"** 을 명시한다. 운영 산출물에서는 URL 채널이 꺼져 있어야 JWT 가 웹서버 access log·프록시 로그·브라우저 주소창/기록에 남지 않는다(CWE-598 Sensitive Information in Query String).
- **현재 동작(이슈 내용)**: 모든 운영 배포 경로의 기본값이 `all`(= url → localStorage → cookie 순 시도)이다.
  ```
  frontend/Dockerfile:33        ARG VITE_TOKEN_INGRESS=all
  docker-compose.yml:314        VITE_TOKEN_INGRESS: ${VITE_TOKEN_INGRESS:-all}
  .env.example:135              VITE_TOKEN_INGRESS=all
  deploy/onprem/scripts/package/20-build-frontend.sh:29
                                export VITE_TOKEN_INGRESS="${VITE_TOKEN_INGRESS:-all}"
  deploy/onprem/scripts/install/build-from-source.sh:182  (동일)
  deploy/onprem/docs/02-build-package.md:30  VITE_API_BASE_URL=/api/v1 VITE_TOKEN_INGRESS=all ./scripts/package.sh
  ```
  `frontend/.env.example:5` 만 `localStorage` 이고 **`.env.production` 파일 자체가 없다**. 실행중 컨테이너 실효값도 `all`:
  ```
  docker inspect klid-frontend → VITE_TOKEN_INGRESS=all
  ```
  코드:
  ```ts
  // frontend/src/features/auth/tokenIngress.ts:130-132
  if (strategy === 'url' || strategy === 'both' || strategy === 'all') {
    candidates.push(params.urlToken);
  }
  ```
- **재현/확인 경로**: 라이브 실측으로 URL 토큰 인계가 그대로 동작한다.
  ```
  브라우저 신규 탭 → http://localhost:13000/ingress?token=<JWT>
    → 최종 URL /dashboard (INTERNAL) 또는 /portal (PORTAL) 로 진입, sessionStorage['klid_jwt'] 적재됨
  ```
  (본 검증의 TC-FE-004/010/011/012 실측이 전부 이 채널로 수행됐다 = 채널이 살아 있다는 증거)
- **영향**: 보안 — JWT 가 GET 요청라인에 실려 nginx/Caddy access log, 리버스 프록시 로그, WAF 로그에 평문 기록된다(CWE-598/CWE-532). 관제·저작도구가 **같은 도메인**이라 브라우저 기록에도 남는다. `SessionIngressPage.tsx:97` 이 `navigate(target,{replace:true})` 로 history 항목은 치환하지만 **최초 document 요청은 이미 서버 로그에 남은 뒤**다.
- **수정 방향(제안)**: ①`frontend/Dockerfile:33`·`docker-compose.yml:314`·`deploy/onprem/scripts/package/20-build-frontend.sh:29`·`build-from-source.sh:182`·`.env.example:135` 의 기본값을 **`localStorage`** 로 뒤집는다(운영 표준 채널). ②`url` 채널이 필요한 곳은 로컬 개발뿐이므로 `frontend/.env.development` 에서만 `all` 유지. ③`deploy/onprem/docs/02-build-package.md:30,177` 예시 명령도 동시 갱신(문서가 `all` 을 권장 형태로 보여주고 있다).


### [H-ISSUE-03] TC-FE-016 / TC-E2E-001 인접 — 온프렘 **운영 패키징 기본값이 `VITE_DEV_LOGIN_ENABLED=true` · `VITE_DEV_UPLOAD_ENABLED=true`** 라 prd 산출물에 dev 라우트가 포함된다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `frontend/src/router/index.tsx:167-181` 주석이 명시하듯 "플래그가 false/미설정인 prod 빌드에서는 `if` 블록 전체가 dead-code 로 제거되어 DevLoginPage 청크 자체가 산출물에 포함되지 않는다"(TC-FE-028 기대결과와 동일). 운영 산출물에는 개발용 토큰 발급 UI 가 존재해선 안 된다.
- **현재 동작(이슈 내용)**: 온프렘 패키징·소스빌드 스크립트가 기본을 **`true`** 로 켠다.
  ```
  deploy/onprem/scripts/package/20-build-frontend.sh:33
      export VITE_DEV_LOGIN_ENABLED="${VITE_DEV_LOGIN_ENABLED:-true}"
  deploy/onprem/scripts/install/build-from-source.sh:183-184
      export VITE_DEV_LOGIN_ENABLED="${VITE_DEV_LOGIN_ENABLED:-true}"
      export VITE_DEV_UPLOAD_ENABLED="${VITE_DEV_UPLOAD_ENABLED:-true}"
  deploy/onprem/docs/08-build-from-source.md:64-65  (동일 기본값을 문서에 명시)
  ```
  결과적으로 prd 번들에 `/dev/login` 라우트 + `DevLoginPage` 청크가 실린다. 실동작으로도 확인됨 — 현재 스택의 미인증 진입이 상위 로그인이 아니라 **`/dev/login`** 으로 빠진다(`SessionIngressPage.tsx:41-44`).
- **재현/확인 경로**:
  ```
  신규 탭 → http://localhost:13000/dashboard → /dev/login (역할 3종 라디오 + "토큰 발급 + 진입" 버튼 노출)
  # BE 측 최종 방어는 살아 있다(fail-closed 확인):
  #   backend/src/main/java/.../dev/controller/DevTokenController.java:34
  #     @ConditionalOnProperty(prefix="authoring.dev.login", name="enabled", havingValue="true")
  #   application.yml:456-458  authoring.dev.login.enabled = ${DEV_LOGIN_ENABLED:false}
  #   application-prd.yml      dev 블록 없음 → false, deploy/onprem/config/backend/env.template:155 도 주석 처리
  ```
- **영향**: 보안(공격 표면) — BE 가 fail-closed 라 **실제 토큰 자가 발급은 차단**되므로 권한 상승은 성립하지 않는다. 다만 ①운영 화면에 동작하지 않는 개발용 로그인 UI 가 노출돼(H-ISSUE-02 와 겹쳐 **만료 사용자가 이 막다른 페이지로 유도된다**) ②`DEV_LOGIN_ENABLED=true` 가 한 번이라도 BE 에 켜지면 즉시 임의 역할 토큰 발급으로 이어지는 **단일 설정 실수 = 전면 권한 우회** 구조가 된다 ③LNB "개발 도구 / 영상 업로드" 메뉴도 REVIEWER 에게 노출된다.
- **수정 방향(제안)**: 패키징/설치 스크립트 기본값을 **`false`** 로 뒤집고(폐쇄망 bring-up 시에만 명시 opt-in), `deploy/onprem/docs/08-build-from-source.md:64-65`·`02-build-package.md` 예시를 함께 정정. 추가로 빌드 산출물 검사(`grep -r "dev/login" dist/`)를 패키징 스크립트의 사후 게이트로 넣는 것을 권장.


### [H-ISSUE-42] TC-FE-199 — 연속 저장 409 미발생이 "최신 버전 전송"이 아니라 "버전 미전송"으로 성립
- **심각도**: MEDIUM (H-ISSUE-41 의 파생)
- **기대 동작(기대효과)**: 1회차 저장 성공 직후 2회차 저장이 **응답으로 갱신된 최신 `labelVersion`** 을 보내 자기 자신과 409 가 나지 않아야 한다(DEV_FIX H12).
- **현재 동작(이슈 내용)**: 실측상 연속 저장은 409 없이 둘 다 성공하지만, 두 PUT 모두 `labelVersion` 자체가 없다.
  ```
  PUT /api/v1/frames/4/labels  {"items":[{"id":null,…}]}     ← 1회차
  PUT /api/v1/frames/4/labels  {"items":[{"id":726,…}]}      ← 2회차 (labelVersion 여전히 없음)
  ```
  `useUpdateLabels.ts:63-65` 의 `cached?.labelVersion ?? options.labelVersion` 은 두 소스가 모두 `undefined` 라 동작할 여지가 없다.
- **재현/확인 경로**: H-ISSUE-41 재현 1과 동일(연속 2회 Ctrl+S 후 두 payload 비교).
- **영향**: 케이스가 "통과처럼 보이지만 보호 효과는 0"인 상태다. H-ISSUE-41 을 고치면 이 로직이 비로소 실효를 갖는데, 그때 **캐시 우선 순서가 실제로 맞는지**는 아직 실환경 미검증이므로 수정 후 재검증 대상이다.
- **수정 방향(제안)**: H-ISSUE-41 수정 후, 연속 저장 2회의 payload `labelVersion` 이 `v` → `v+1` 로 증가하는지 실브라우저로 재검증한다(수정 없이는 판정 불가).


### [H-ISSUE-70] TC-FE-130 — BatchStageIndicator 단계 상태가 스크린리더에 노출되지 않음
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: WCAG 2.1 AA 준수 요구(루트 CLAUDE.md 포털 섹션) 및 `component.md`의 "색상만으로 정보 전달 금지(아이콘/텍스트 병행)" 원칙에 따라, 배치 단계별 상태(완료/진행중/실패/대기)는 스크린리더 사용자도 알 수 있어야 한다.
- **현재 동작(이슈 내용)**: `frontend/src/components/common/BatchStageIndicator.tsx` 의 `StageIcon`(:30-57)이 렌더하는 `Check`/`Loader2`/`X` 아이콘은 모두 `aria-hidden`(각 `:34,41,48`)이고 PENDING 상태는 장식용 `<div>`(`:53-55`)뿐이다. 스테이지마다 노출되는 텍스트(`:77-82`)는 **단계 이름**(예: "비식별", "마킹")만이며 상태를 나타내는 `aria-label`/`sr-only`/`role` 속성이 컴포넌트 전체에 하나도 없다(`grep -n "aria-label|sr-only|role="` 결과 0건). 즉 스크린리더 사용자는 "비식별 단계가 존재한다"는 것만 알 수 있고 그 단계가 끝났는지/실패했는지/대기 중인지 알 방법이 없다.
- **재현/확인 경로**: 스크린리더(VoiceOver 등)로 마킹/영상상세 화면 진입 → `data-testid="batch-stage-indicator"` 영역 탐색 → 각 단계 이름만 낭독되고 상태 정보 낭독 없음. 코드 확인: `grep -n "aria-hidden\|aria-label" frontend/src/components/common/BatchStageIndicator.tsx`.
- **영향**: 접근성(WCAG 2.1 AA 1.1.1/4.1.2 상당) — 시각장애 사용자가 배치 처리 실패(FAIL) 여부를 화면에서 인지할 수 없어, 실패 시 대응이 늦어질 수 있음. 보안 영향 없음.
- **수정 방향(제안)**: 각 단계 아이콘 wrapper 또는 단계 컨테이너에 상태를 서술하는 `aria-label`(예: `"${label} — ${status==='DONE'?'완료':status==='PROGRESS'?'진행중':status==='FAIL'?'실패':'대기'}"`) 또는 시각적으로 숨긴 `sr-only` 텍스트를 추가. 아이콘 자체의 `aria-hidden` 은 유지하되 상위 요소에 접근성 트리 정보를 부여.

---

## 4. 집계 요약

| 클러스터 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| H-6 | 20 | 20 | 0 | 0 | 0 | 0 | 0 |
| H-7 | 5 | 4 | 0 | 1 | 0 | 0 | 0 |
| **합계** | **25** | **24** | **0** | **1** | **0** | **0** | **0** |

신규 이슈: **H-ISSUE-70** (MEDIUM, a11y) 1건. 이전 회차 이월 대상 없음(H-6/H-7 최초 세부 검증).


### [H-ISSUE-81] TC-FE-146 — 검수 화면에 증강/해상도 파생 여부·종류 표시가 전혀 없다(BE 계약 자체 미보유)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 검수자가 검수 화면(`/review/:id`)에서 이 영상이 원본인지, 증강(WINTER/NIGHT/RAIN)이나 해상도 변경(RESL_1080P/720P/480P) 파생본인지, 파생이라면 어떤 종류인지 확인할 수 있어야 한다. 파생영상은 라벨/메타가 원본에서 복사된 값이고 좌표가 재계산(해상도) 또는 그대로(증강) 적용된 결과라, 검수자가 이 맥락을 모르면 원본 대비 이상 여부(예: 리스케일 아티팩트, 계절/야간 변환에 따른 색상 변화)를 오판할 위험이 있다.
- **현재 동작(이슈 내용)**: `frontend/src/features/review/components/ReviewHeader.tsx`·`ReviewMetaPanel.tsx` 전체에 `ORGNL_RAW_SN`/`orgnlRawSn`/`VMS_CLIP_ID`/`vmsClipId`/증강 관련 키워드 매치가 0건이다. 상위 타입 `frontend/src/features/review/types.ts`의 `Review` 인터페이스도 다음 필드만 갖는다:
  ```ts
  export interface Review {
    id: number; videoId: number; cctvName: string; workerId: number;
    workerName: string; submittedAt: string; labelCount: number;
    status: ReviewStatus; reviewerId?: number;
    eventName?: string | null; eventTypeCd?: string | null;
  }
  ```
  BE 응답 계약도 동일하게 비어있다. `backend/src/main/java/kr/co/cudo/authoring/review/dto/ReviewResponse.java`의 필드 전체가 `id/cctvName/workerId/workerName/submittedAt/labelCount/status/videoId/dataSttsCd/version/updDt/eventName/eventTypeCd`뿐이며 파생 식별자가 없다. 즉 FE 구현 누락이 아니라 **BE 응답 DTO 자체에 파생 정보가 실려있지 않다.**
- **재현/확인 경로**: 증강 파생본(`LS_DATA_RAW.ORGNL_RAW_SN IS NOT NULL`)을 검수 목록에서 진입해 `GET /api/v1/reviews/{id}` 응답을 확인하면 `orgnlRawSn` 등 파생 관련 키가 존재하지 않는다. (참고: `GET /api/v1/tasks/board` 응답에는 `증강 데이터: 해상도 480p` 같은 배지가 실제로 존재함 — 작업목록 화면은 파생 여부를 이미 표시하고 있어 검수 화면만 빠진 것으로 보인다. 본 검증 세션에서 `/task` 화면 스냅샷에 `쓰러짐 증강 데이터: 해상도 480p` 셀이 다수 관측됨.)
- **영향**: 기능 정합성(검수 정확도) 저하. 보안 영향은 없음(정보 은닉이 아니라 단순 미표시).
- **수정 방향(제안)**: `ReviewResponse`에 `orgnlRawSn`(nullable)·(선택) 증강/해상도 종류 코드를 추가하고, `ReviewMetaPanel` 또는 `ReviewHeader`에 파생 배지를 렌더한다. 작업목록(`TaskBoardTable`)이 이미 "증강 데이터: {종류}" 배지를 갖고 있으므로 동일 소스/포맷을 재사용할 수 있는지 확인.


### [H-ISSUE-101] TC-E2E-011 — 포털 업로드 E2E 스펙이 업로드 화면이 아닌 포털 홈을 겨냥한다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `/portal/uploads` 의 파일 선택 영역 노출과 실제 업로드 흐름이 E2E 로 회귀 보호돼야 한다. ADR-013 예외로 신설된 포털 자산 업로드는 내부 파이프라인과 분리된 별도 경로라 단위테스트만으로는 라우팅·가드·화면 조립이 보장되지 않는다.
- **현재 동작(이슈 내용)**:
  - `frontend/e2e/pages/PortalHomePage.ts:19-25`
    ```ts
    async goto() {
      await this.page.evaluate(() => {
        if (window.location.pathname !== '/portal') {
          window.history.pushState({}, '', '/portal');   // ← 업로드 화면이 아니라 포털 홈
    ```
  - `frontend/e2e/specs/portal-upload.spec.ts:10-18` 이 그 POM 으로 이동한 뒤 `expect((await home.dropzone.count()) > 0 || (await home.fileInput.count()) > 0).toBe(true)` 를 단언한다.
  - 실측(Playwright, PORTAL_USER): `/portal` 본문에 `input[type=file]` 0개(버튼만: "시작하기 ▶", 영상 선택 카드 12개). `grep -rn "upload-dropzone" frontend/src` 결과는 `pages/portal/__tests__/PortalHomePage.test.tsx:51`(= `toBeNull()` 단언) 1건뿐.
  - `frontend/src/pages/portal/PortalHomePage.tsx`(134줄)에 `input` 문자열 0건.
  - 두 번째 테스트(`:20-38`)는 `if ((await home.fileInput.count()) > 0)` 안에서만 단언하므로 파일 입력이 없으면 **아무 것도 검증하지 않고 통과**한다. fixture `e2e/fixtures/sample.jpg.txt` 도 첫 줄이 `e2e mock placeholder file (not a real jpg)` 라 실제 이미지가 아니다(BE 매직바이트 검증 통과 불가).
- **재현/확인 경로**: `cd frontend && npx playwright test e2e/specs/portal-upload.spec.ts` → 첫 테스트 실패 예상. 또는 브라우저로 `/portal` 진입 후 `document.querySelectorAll('input[type=file]').length` → `0`.
- **영향**: 포털 업로드 화면의 E2E 회귀 보호가 **0**이다. 라우팅·`ChannelGuard`·업로드 UI 조립이 깨져도 CI 가 잡지 못한다(기능 결함은 아니며 테스트 자산 결함).
- **수정 방향(제안)**: ① `PortalHomePage` POM 을 그대로 두고 `PortalUploadPage` POM 을 신설해 `/portal/uploads` 로 이동시키거나, `portal-upload.spec.ts` 가 직접 `/portal/uploads` 로 이동하도록 변경 ② `input#portal-image-input` / `input#portal-video-input` 을 명시 로케이터로 사용 ③ `if (count>0)` 가드를 제거하고 무조건 단언 ④ fixture 를 실제 최소 JPEG(매직바이트 `FFD8FF`) 로 교체.


### [H-ISSUE-102] TC-E2E-007 — 증강 채택 E2E 가 존재하지 않는 jobId 를 대상으로 해 무단언 통과한다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: PENDING 증강 결과를 채택하면 ACCEPTED 로 전이되는 흐름이 E2E 로 보호돼야 한다(REVIEWER 검수 결정 경로).
- **현재 동작(이슈 내용)**:
  - `frontend/e2e/specs/augment-decision.spec.ts:6-18`
    ```ts
    await reviewerPage.goto('/augment/result/3001');
    const adoptBtn = reviewerPage.getByRole('button', { name: /채택/ });
    if ((await adoptBtn.count()) > 0) {   // ← 0 이면 통째로 스킵
      await adoptBtn.first().click();
    ```
  - 실측: `GET /api/v1/augments/3001/result` → `{"jobId":3001,"status":"PROCESSING","results":[],...}`. 결과 항목이 0건이라 DecisionCard 자체가 렌더되지 않고 `adoptBtn.count()===0` → 단언 미실행.
  - 실 DB 의 잡 ID 는 `4·18·26·50·80·81·900·906·77777777` 이며 3001 은 없다(`GET /v1/augments` 실측 totalElements=9).
- **재현/확인 경로**: `curl -s localhost:18081/api/v1/augments/3001/result -H "Authorization: Bearer <REVIEWER JWT>"`
- **영향**: 채택 상태전이 회귀가 CI 에서 잡히지 않는다(수동 실동작 검증에서는 정상 동작 확인됨 — TC-FE-162).
- **수정 방향(제안)**: 스펙 안에서 `POST /v1/augments/request` 로 PENDING 항목을 만들거나 시드 고정 잡을 쓰고, `if (count>0)` 가드를 제거해 `expect(adoptBtn).toBeVisible()` → 클릭 → `expect(getByText('채택됨')).toBeVisible()` 로 무조건 단언.


### [H-ISSUE-141] TC-E2E-014 — 라벨링 플로우 E2E 의 핵심 단언이 전부 조건부라 미렌더 시 조용히 통과
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: "목록→캔버스→BBox 작성→저장→저장 토스트" 가 실제로 동작함을 E2E 가 보장해야 한다. 라벨링은 이 제품의 핵심 작업이라, 도구바·캔버스·저장 버튼 중 하나라도 사라지면 E2E 가 **반드시 빨간불**이어야 한다.
- **현재 동작(이슈 내용)**: `frontend/e2e/specs/labeling-flow.spec.ts:26-42`
  ```ts
  if ((await labeling.bboxToolBtn.count()) > 0) { await labeling.bboxToolBtn.first().click(); }
  if ((await labeling.canvas.count()) > 0) { await labeling.drawBoundingBox(...); }
  if ((await labeling.saveBtn.count()) > 0) {
    await labeling.save();
    await expect(workerPage.getByText(/저장|완료/).first()).toBeVisible({ timeout: 5000 });
  }
  ```
  세 요소가 모두 0개면 테스트는 **아무 단언 없이 PASS** 한다. 토스트 매처도 `/저장|완료/` 라 "저장 실패"·"저장하시겠습니까" 같은 무관한 텍스트에도 매칭될 수 있다.
- **재현/확인 경로**: 라벨링 화면에서 BBox 도구 버튼의 selector 가 바뀌면(리팩터링) 이 스펙은 계속 초록불을 유지한다. 대조군: 같은 저장소의 `labeling-review-full-flow.spec.ts:40-55` 는 `await expect(labeling.bboxToolBtn).toBeVisible()` + `waitForResponse(PUT /frames/{srcSn}/labels)` + `getByText('저장됨 · 버전 기록됨')` 으로 무조건 단언한다.
- **영향**: 기능 회귀 감지 실패(거짓 PASS). 보안 영향 없음.
- **수정 방향(제안)**: `labeling-flow.spec.ts` 의 `if (count() > 0)` 가드를 제거하고 `full-flow` 스펙과 동일하게 `toBeVisible()` + API 응답 + 정확한 토스트 문구로 단언한다. 요소가 조건부로만 존재한다면 그 조건을 전제(fixture)로 명시한다. **본 검증에서는 수정하지 않음.**


### [H-ISSUE-142] TC-E2E-015 — 바운딩박스 버튼 렌더 단언이 항상 참(`>= 0`)인 공허한 단언
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: WORKER 라벨링 진입 시 **바운딩박스 도구 버튼이 렌더**됨을 보장해야 한다(케이스 기대결과 원문).
- **현재 동작(이슈 내용)**: `frontend/e2e/specs/worker-labeling.spec.ts:29-36`
  ```ts
  test('라벨링_도구_바운딩박스_버튼_렌더', async ({ workerPage }) => {
    const labeling = new LabelingPage(workerPage);
    await labeling.goto(TEST_VIDEO_WITH_LABEL);
    const cnt = await labeling.bboxToolBtn.count();
    expect(cnt).toBeGreaterThanOrEqual(0);   // ← 개수가 0이어도 통과. 항상 참.
  });
  ```
  `count()` 는 음수가 될 수 없으므로 이 단언은 **어떤 상황에서도 실패하지 않는다**. 테스트 이름만 "버튼 렌더" 이고 실제로는 아무것도 검증하지 않는다.
- **재현/확인 경로**: 도구 패널을 통째로 제거해도 이 테스트는 통과한다.
- **영향**: 기능 회귀 감지 실패(거짓 PASS). 통과율 통계를 왜곡한다.
- **수정 방향(제안)**: `await expect(labeling.bboxToolBtn.first()).toBeVisible({ timeout: 5000 })` 로 교체하거나, 풀스크린 UI 문구 변동이 우려되면 `data-testid` 를 부여해 안정 selector 로 단언한다. **본 검증에서는 수정하지 않음.**


### [H-ISSUE-146] TC-FE-242 — WORKER KPI 4카드가 "전체 기준" 이 아니라 **현재 페이지 20행**만 집계한다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 루트 `CLAUDE.md` "목록 화면 정렬·필터 정책" 구속 규칙 — **"필터·집계는 BE 에서 전체 기준으로 처리하며 FE 클라이언트 필터로 대체하지 않는다 — 현재 페이지 단위 집계는 오답이다."** REVIEWER 5카드는 이 규칙대로 서버 집계(`/v1/tasks/board/summary`)를 쓴다. WORKER 4카드도 같은 기준이어야 헤더 "전체 N건" 과 카드가 같은 집합을 말한다.
- **현재 동작(이슈 내용)**: `frontend/src/features/task/components/TaskWorkerKpiCards.tsx:20-30`
  ```tsx
  export function TaskWorkerKpiCards({ rowStatuses }: TaskWorkerKpiCardsProps) {
    const count = (status: RowStatus) => rowStatuses.filter((s) => s === status).length;
    …
    <KpiCard data-testid="kpi-total" label="전체 작업" value={rowStatuses.length} … />
  ```
  `rowStatuses` 는 `TaskListPage.tsx:484-487` 의 `pagedRows.map(r => r.rowStatus)` = **현재 페이지 행(최대 20)**. 즉 "전체 작업" 카드는 21건 이상 배정된 WORKER 에게 항상 `20` 으로 고정되고, 헤더의 "전체 N건"(서버 `totalElements`)과 어긋난다. 소스 주석은 "REVIEWER 전용 집계 API(403)라 구 동작 그대로" 라고 사유를 밝히고 있으나, 정책이 금지한 페이지 단위 집계다.
- **재현/확인 경로**: 배정 21건 이상인 WORKER 로 `/task` 진입 → 헤더 "전체 21건" vs KPI "전체 작업 20". 현재 시드에서는 worker 2001 의 배정이 16건(`/assignments?workerId=2001` → `totalElements:16`)이라 **아직 드러나지 않는 잠재 결함**이다.
- **영향**: 데이터 정합/사용자 오판. 작업자가 자신의 잔여 작업량을 실제보다 적게 인식한다. 보안 영향 없음.
- **수정 방향(제안)**: ①BE 에 WORKER 도 호출 가능한 배정 집계 엔드포인트(예: `GET /v1/assignments/summary`, 서버가 인가로 본인 범위 고정)를 추가해 REVIEWER 5카드와 같은 "전체 기준" 축으로 통일하거나, ②당장 어렵다면 카드 라벨을 "이 페이지 기준" 으로 명시하고 "전체 작업" 값만 서버 `totalElements` 로 대체한다. **본 검증에서는 수정하지 않음.**


### [H-ISSUE-04] TC-FE-016 인접 — `getStrategy()` 가 **잘못된 설정값을 가장 넓은 `all` 로 fail-open** 한다
- **심각도**: LOW
- **기대 동작(기대효과)**: 보안 관련 설정의 파싱 실패는 **가장 좁은 값(fail-closed)** 으로 떨어져야 한다(`rules/security.md` "Mishandling of Exceptional Conditions — fail-secure/fail-closed"). 오타 하나로 인계 채널이 조용히 넓어져선 안 된다.
- **현재 동작(이슈 내용)**:
  ```ts
  // frontend/src/features/auth/tokenIngress.ts:22-34
  function getStrategy(): IngressStrategy {
    const v = (import.meta.env.VITE_TOKEN_INGRESS as string | undefined) ?? 'all';
    if (v === 'url' || v === 'cookie' || v === 'localStorage' || v === 'both' || v === 'all') return v;
    return 'all';          // ← 오타·미설정 모두 최광범위로 폴백
  }
  ```
  기존 테스트도 이 동작을 고정하고 있다(`tokenIngress.test.ts:156` `VITE_TOKEN_INGRESS_잘못된_값이면_all로_폴백`).
- **재현/확인 경로**: `VITE_TOKEN_INGRESS=localstorage`(소문자 오타)로 빌드 → `all` 로 동작해 URL·cookie 채널이 함께 열린다.
- **영향**: 보안 — H-ISSUE-01 의 재발 경로. 설정을 `localStorage` 로 고쳐도 오타 시 원상 복귀한다.
- **수정 방향(제안)**: 폴백을 `'localStorage'`(운영 표준 채널)로 바꾸고, 미인식 값은 `console.warn` + 폴백을 남긴다. 함께 `frontend/src/vite-env.d.ts:5` 의 `readonly VITE_TOKEN_INGRESS: 'url' | 'cookie'` 타입을 실제 5종(`'url'|'cookie'|'localStorage'|'both'|'all'`)으로 정정한다(현재 타입 선언이 실제 허용값과 어긋나 IDE 가 오타를 못 잡는다).


### [H-ISSUE-05] TC-FE-020 — 401 토큰 레이스 **1회 재시도 로직에 자동 테스트가 0건**이다
- **심각도**: LOW
- **기대 동작(기대효과)**: "토큰 적재 직전에 발사돼 Authorization 없이 401 을 받은 요청은 토큰을 붙여 **정확히 1회만** 재시도" 는 무한 재시도(요청 폭주)와 재시도 누락(첫 화면 데이터 유실) 사이의 좁은 계약이라 회귀 가드가 필요하다.
- **현재 동작(이슈 내용)**: 구현은 존재하고 정확하다.
  ```ts
  // frontend/src/lib/api/client.ts:62-71
  if (config && currentToken && !sentAuth && !config._retriedWithToken) {
    config._retriedWithToken = true;
    config.headers?.set?.('Authorization', `Bearer ${currentToken}`);
    return apiClient.request(config);
  }
  ```
  그러나 `frontend/src/lib/api/__tests__/client.test.ts` 의 7개 케이스(`:24,38,50,68,87,117`) 중 재시도를 다루는 것이 없고, 저장소 전체에 `_retriedWithToken` 을 참조하는 테스트가 0건이다.
- **재현/확인 경로**: `grep -rn "_retriedWithToken" frontend/src --include="*.test.ts*"` → 0건. 실동작 재현은 "토큰 적재 직전 발사" 레이스라 결정론적으로 만들 수 없다.
- **영향**: 기능/회귀 — 인터셉터 리팩터링 시 `!sentAuth` 나 `_retriedWithToken` 가드가 조용히 사라지면 401 무한 루프(자기 DoS) 또는 초기 화면 빈 데이터가 된다. 두 실패 모두 조용하다.
- **수정 방향(제안)**: `client.test.ts` 에 ①헤더 없이 보낸 401 + store 토큰 존재 → 재요청 1회 + `Authorization` 부착 ②재시도분도 401 → **추가 재시도 없이** `clear()`+redirect ③애초에 헤더를 달고 보낸 401 → 재시도 0회, 3케이스를 추가한다(`vi.spyOn(apiClient,'request')` 호출 횟수로 단언).


### [H-ISSUE-06] TC-FE-192 / TC-FE-195 — `resolveApiMessage` 정책을 **우회해 `error.message` 를 그대로 렌더**하는 화면이 있다
- **심각도**: LOW
- **기대 동작(기대효과)**: `resolveApiMessage.ts:4,16-19` 는 "400/409/412 만 서버 문구 노출, 그 외(401/403/5xx·비-ApiError)는 fallback" 를 **단일 지점**에서 강제하려고 만든 유틸이다(CWE-209). 에러 표시 경로가 이 유틸을 통과해야 정책이 실효를 갖는다.
- **현재 동작(이슈 내용)**: 아래 4곳이 상태코드 구분 없이 원문을 렌더한다.
  ```tsx
  frontend/src/features/version/components/HistoryPanel.tsx:194
      <ErrorState title="버전 이력 조회 실패" message={error.message} />
  frontend/src/features/version/components/HistoryPanel.tsx:254
      <ErrorState title="diff 조회 실패" message={diffQuery.error.message} />
  frontend/src/pages/HistoryPage.tsx:99
      <ErrorState title="프레임 조회 실패" message={error.message} />
  frontend/src/pages/label/LabelingPage.tsx:1144
      <p className="text-sm text-gray-400 mb-4">{error.message}</p>
  ```
- **재현/확인 경로**: 네트워크 차단 상태에서 `/history/{videoId}` 진입 → 화면에 axios 원문(`Network Error` / `timeout of 30000ms exceeded`)이 그대로 노출. 5xx 시에는 BE 가 준 문구가 상태코드 필터 없이 노출.
- **영향**: 보안(정보 노출, 현재는 **잠재**) — 실측상 BE 는 500 에 `ErrorCode.INTERNAL_ERROR.defaultMessage()` 고정값만 내려주므로(`GlobalExceptionHandler.java:273-278`) **지금 당장 내부 정보가 새지는 않는다**. 다만 BE 가 어떤 예외 핸들러에서 상세 메시지를 담기 시작하면 이 4곳만 조용히 새는 구조이고, 정책이 한 곳에 모여 있지 않아 감사도 어렵다.
- **수정 방향(제안)**: 위 4곳을 `resolveApiMessage(error, '조회에 실패했습니다. 잠시 후 다시 시도해 주세요.')` 로 교체한다. 재발 방지로 ESLint `no-restricted-syntax` 규칙(예: JSX 속성/자식으로 `*.message` 직접 사용 금지, 폼 검증 `errors.*.message` 는 예외) 추가를 검토.


### [H-ISSUE-07] TC-FE-191 — `StageBadge` 의 미지 단계 폴백이 **원시 기술 코드 그대로**여서 `BatchStageIndicator` 의 `'처리중'` 정책과 어긋난다
- **심각도**: LOW
- **기대 동작(기대효과)**: TC-FE-191 기대결과 "미지 단계는 '처리중'". 화면에 배치 단계 **기술 코드명이 새지 않아야** 한다(`BatchStageIndicator.tsx:26-28` 주석이 같은 취지를 명시).
- **현재 동작(이슈 내용)**: 같은 목적의 두 컴포넌트가 폴백 정책이 다르다.
  ```tsx
  // frontend/src/components/common/BatchStageIndicator.tsx:28,81
  const STAGE_LABEL_FALLBACK = '처리중';
  {STAGE_LABEL[stage.name] ?? STAGE_LABEL_FALLBACK}

  // frontend/src/components/common/StageBadge.tsx:60
  const label = STAGE_LABEL[stage] ?? stage;      // ← 원시 코드 노출
  ```
  게다가 `StageBadge` 의 맵(`:15-24`)에는 BE canonical 단계 중 **`MARKING`·`INTERPOLATE` 가 없다**(`BatchStageIndicator` 에는 있다).
- **재현/확인 경로**: `<StageBadge stage="MARKING" />` → 화면에 `MARKING` 그대로 출력. **현재 프로덕션 호출부는 `pages/VideoListPage.tsx:318,320` 두 곳뿐이고 둘 다 리터럴 `"COMPLETED"`/`"FAILED"` 를 넘기므로 실도달하지 않는다**(그래서 TC-FE-191 을 FAIL 이 아닌 PARTIAL 로 판정).
- **영향**: 기능/용어정책(잠재) — BE 가 단계를 추가하거나 `StageBadge` 를 동적 stage 로 재사용하는 순간 기술 코드가 화면에 노출된다. 두 컴포넌트의 정책 분기 자체가 드리프트 소스다.
- **수정 방향(제안)**: `StageBadge.tsx:60` 폴백을 `'처리중'` 으로 바꾸고 `MARKING`/`INTERPOLATE` 매핑을 추가한다. 근본적으로는 `STAGE_LABEL` 맵과 폴백 상수를 한 모듈(예: `components/common/stageLabel.ts`)로 추출해 두 컴포넌트가 공유하게 한다.


### [H-ISSUE-08] TC-FE-191 — 사용자 문구에 **"관리자"** 를 쓴다 (ADMIN 역할 부재 · UI 호칭 '검수자' 통일 정책 위반)
- **심각도**: LOW
- **기대 동작(기대효과)**: `CLAUDE.md` "역할 정의" — *"시스템 관리자(ADMIN) 역할은 없으며 모든 관리 권한은 REVIEWER에 통합되어 있다. **UI 호칭은 '검수자'로 통일**"*. 존재하지 않는 역할명을 화면에 쓰면 사용자가 누구에게 요청해야 하는지 알 수 없고, 감리에서 역할 모델 불일치로 지적된다.
- **현재 동작(이슈 내용)**:
  ```tsx
  frontend/src/pages/RoleClaimPage.tsx:60   관리자에게 받은 패스워드로 역할을 부여받으세요.
  frontend/src/pages/RoleClaimPage.tsx:87   label="관리자 패스워드"
  frontend/src/pages/RoleClaimPage.tsx:90   placeholder="관리자에게 받은 패스워드를 입력하세요"
  frontend/src/pages/RoleClaimPage.tsx:94   aria-label="관리자 패스워드"
  frontend/src/pages/RoleClaimPage.tsx:129  '관리자 패스워드가 일치하지 않습니다.'
  frontend/src/pages/MarkingPage.tsx:70     data.batchSkipReason ?? '관리자에게 문의하세요.'
  ```
  같은 파일 `:12` 주석은 오히려 *"REVIEWER(사실상 관리자)"* 라고 적어 코드 자체가 호칭 혼용을 인지하고 있다.
- **재현/확인 경로**: WORKER/REVIEWER 세션 무관 — role 미부여 토큰으로 진입하면 `/role-claim` 이 렌더되고 위 문구가 그대로 표시된다(본 검증에서 `/role-claim` 실렌더 확인). 실동작 대조: LNB/GNB 역할 표기는 정책대로 **"검수자"** 로 나온다(`document.body.innerText` 실측) → 화면 간 호칭이 갈린다.
- **영향**: 요구사항 정합/UX — 역할 모델(REVIEWER 단일화)과 화면 문구 불일치. 사용자가 존재하지 않는 "관리자"를 찾게 된다.
- **수정 방향(제안)**: 위 6개 문구를 **"검수자"** 기준으로 정정(예: `검수자 패스워드`, `검수자에게 받은 패스워드를 입력하세요`, `검수자에게 문의하세요`). `RoleClaimPage.tsx:12` 주석의 "사실상 관리자" 표현도 정리. 재발 방지로 FE 문구 lint(금칙어 `관리자`/`ADMIN`, 예외: 코드 식별자·주석)를 검토.


### [H-ISSUE-09] TC-FE-002 인접 — `/` 하위 **404 catch-all 라우트가 무가드**라 미인증 사용자에게 AppLayout(GNB/LNB 메뉴 전체)이 렌더된다
- **심각도**: LOW
- **기대 동작(기대효과)**: 인증 전에는 애플리케이션 셸(메뉴 구조)이 노출되지 않아야 한다. `/` 이하 모든 경로는 `InternalRoute`(ChannelGuard+RoleGuard)를 통과한 뒤 렌더되는 것이 라우터 설계 의도다.
- **현재 동작(이슈 내용)**: `path:'/'` 의 `element: <AppLayout />` 자체에는 가드가 없고, 자식 중 `{ path:'*' }` 와 `{ index:true }` 만 가드가 빠져 있다.
  ```tsx
  // frontend/src/router/index.tsx:228-233, 459
  { path: '/', element: <AppLayout />, errorElement: <AppErrorPage status={500} />,
    children: [
      { index: true, element: <Navigate to="/dashboard" replace /> },   // 가드 없음(→ 즉시 guarded 경로로 이동하므로 무해)
      ...
      { path: '*', element: <AppErrorPage status={404} /> },            // 가드 없음 → 셸 렌더
  ```
  `AppLayout.tsx` → `Gnb`/`Lnb` 는 API 호출은 없으나 claims 없을 때 기본값으로 렌더된다(`Gnb.tsx:30-31` `role = claims?.role ?? Role.WORKER`, `name = claims?.name ?? '사용자'`).
- **재현/확인 경로**:
  ```
  신규 탭(빈 sessionStorage) → http://localhost:13000/xyz-nonexistent-h1
    → /ingress 로 튕기지 않고 그대로 유지되며 AppLayout 404 화면이 렌더된다
    (비교: /dashboard·/review 등 가드 경로는 즉시 /ingress→/dev/login 으로 이동)
  ```
- **영향**: 보안(정보 노출, 경미) — 사용자 데이터는 없고 **메뉴 구조·기능 목록만** 노출된다(CWE-200 경미). 개인정보·업무 데이터 유출은 없다.
- **수정 방향(제안)**: `router/index.tsx:459` 의 404 element 를 `<InternalRoute allow={internalAllRoles}><AppErrorPage status={404} /></InternalRoute>` 로 감싸거나, `AppLayout` 자체를 `ChannelGuard` 안으로 옮긴다(후자가 `index` 라우트까지 한 번에 정리된다).


### [H-ISSUE-10] TC-FE-015 / TC-FE-016 — `hydrate()`·세션 스토리지 정책에 **자동 테스트가 0건**이다
- **심각도**: LOW
- **기대 동작(기대효과)**: "만료 토큰은 hydrate 시점에 sessionStorage 에서 제거"(TC-FE-015)와 "토큰은 sessionStorage 에만 저장"(TC-FE-016)은 XSS 노출면과 직결된 보안 계약이라 회귀 가드가 필요하다.
- **현재 동작(이슈 내용)**: 두 동작을 검증하는 테스트가 없다.
  ```
  grep -rn "hydrate(" frontend/src --include="*.test.ts*"        → 0건
  grep -rln "sessionStorage" frontend/src --include="*.test.ts*" → 0건
  ```
  `stores/__tests__/useAuthStore.test.ts` 의 3케이스(`:18,27,47`)는 `setToken`/`clear`/무효 토큰만 다루고 `hydrate` 와 스토리지 종류는 다루지 않는다.
- **재현/확인 경로**: 본 검증은 실브라우저로 두 동작을 확인했다(TC-FE-015: 만료 토큰 주입 후 재적재 → `sessionStorage.klid_jwt === null` / TC-FE-016: `Object.keys(sessionStorage) === ['klid_jwt']`). 자동화가 없어 회귀는 잡히지 않는다.
- **영향**: 회귀 — `hydrate` 의 `claims.exp > now` 비교가 사라지면 만료 토큰이 잔류하고, 저장소를 localStorage 로 바꾸는 변경이 테스트에 걸리지 않는다.
- **수정 방향(제안)**: `useAuthStore.test.ts` 에 ①만료 토큰이 sessionStorage 에 있을 때 `hydrate()` → `token=null` + `sessionStorage.getItem('klid_jwt')===null` + `isHydrated=true` ②유효 토큰 → 복원 성공 ③`setToken` 후 `localStorage` 에 아무 키도 생기지 않음, 3케이스를 추가한다(`src/test/setup.ts:40-58` 이 이미 jsdom 스토리지를 폴리필하므로 추가 배선 불필요).

---

## 5. 판정 시 적용한 확정 정책(★) 확인
- **★1(신고 게이트 자기 rawSn 한정)·★2(정렬 키 응답 비대칭)·★3(좌표 검증 2축)** — 본 범위(H-1·H-14)에는 해당 케이스가 없어 적용 대상 없음. 위 이슈 중 어느 것도 ★ 정책을 되돌리자는 제안이 아니다.
- UNCERTAINTIES **#23**(VideoPlayer 배속 이산 6버튼)·**#25**(DevAutolabelTestPage/내부 TUS dead-code) 관련 — #25 의 "prod 빌드 기준 dead-code 맞음" 전제는 **온프렘 패키징 기본값이 `VITE_DEV_UPLOAD_ENABLED=true` 라 성립하지 않는다**(H-ISSUE-03). UNCERTAINTIES #25 원문 갱신 제안 대상.


### [H-ISSUE-44] (카탈로그 정합성) H-3 절 근거 `file:line` 전건 드리프트 + 기대 문구 1건 불일치
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그의 근거 `file:line` 이 실제 코드 위치를 가리켜야 다음 회차 검증·수정 작업이 곧바로 착지한다.
- **현재 동작(이슈 내용)**: `LabelingPage.tsx` 가 **1,609줄**로 늘면서 H-3 의 `pages/label/LabelingPage.tsx:*` 근거가 사실상 전건 어긋났다. 대표 예:
  | TC | 카탈로그 | 실제 |
  |---|---|---|
  | TC-FE-033 | 911-930 | **1067-1086** |
  | TC-FE-038 | 492-510 | **556-578** |
  | TC-FE-043 | 292-302 | **337-349** |
  | TC-FE-052 | 698-701 | **692-695** |
  | TC-FE-055 | 673-690 | **815-833** |
  | TC-FE-066 | 465-473 | **527-535** |
  | TC-FE-067 | 1079-1090 | **1238-1247** |
  | TC-FE-079 | 1290-1310 | **1467-1475** |
  | TC-FE-197 | 515-519,533-538,1385-1394 | **583-588, 601-640, 1562-1571** |
  | TC-FE-060 | useSam2Track.ts:57-59 | **useSam2Track.ts:96-110** |
  또 TC-FE-085 의 기대 문구가 `되돌릴 항목이 없습니다` 인데 실제 구현은 `되돌릴 항목이 현재 작업본에 없습니다.`(`LabelingPage.tsx:668`)다.
- **재현/확인 경로**: `grep -n` 으로 위 표의 심볼(`잘못된 프레임 ID`, `handleSave`, `requestJumpTo`, `handleDeidentReportSuccess` 등) 위치 확인.
- **영향**: 판정 자체는 바뀌지 않으나, 근거를 따라가면 무관한 코드가 나와 재검증·수정 착수 비용이 늘고 오판 위험이 생긴다.
- **수정 방향(제안)**: H-3 절 근거를 위 실측 위치로 일괄 갱신하고 TC-FE-085 기대 문구를 실제 구현 문구로 정정한다. 라인 대신 **심볼/함수명 기준 근거 표기**로 바꾸면 드리프트가 재발하지 않는다. ⚠ 카탈로그는 수정하지 않았다.

---

## 3. 검증 방법 메모 (재현용)

- 토큰: `POST /api/v1/dev/tokens {"role":"WORKER","channel":"INTERNAL"}` → `data.token` 을 `sessionStorage.setItem('klid_jwt', …)`.
- 브라우저 프로파일을 다른 검증 에이전트와 공유해 탭이 탈취되므로, **모든 조작을 단일 `browser_run_code_unsafe` 호출 안에서 `context.newPage()` 로 격리**해 수행했다.
- 전제 조작(잠금/RAW/파생/409/500/지연)은 `page.route` 로 **응답만 패치**했고 프로덕션·테스트·설정 파일은 일절 수정하지 않았다.
- DB 부작용: 검증 중 `src_sn=4` 에 BBOX 1건을 저장했다가 **삭제 후 재저장해 라벨 0건으로 원복**했다(`LS_DATA_LBL` 잔여 없음). `labelVersion` 만 증가했다(무해).
- 스크린샷: `.playwright-mcp/h3-canvas.png`(비식별 프레임 렌더), `h3-poly-draft.png`(폴리곤 클릭 무반응), `h3-bbox-drawn.png`, `h3-select-click.png`.


### [H-ISSUE-82] TC-FE-149 — 카탈로그 근거 file:line 드리프트: `/manage/*` REVIEWER 전용 접근제어의 실제 검증 파일이 다르다
- **심각도**: LOW
- **기대 동작(기대효과)**: 테스트 케이스 카탈로그의 "근거" 컬럼은 실제로 그 단언을 검증하는 코드/테스트 위치를 정확히 가리켜야 한다(회귀 시 추적성).
- **현재 동작(이슈 내용)**: `docs/test-cases/H-frontend-e2e.md` TC-FE-149 행의 근거가 `pages/manage/__tests__/UserManagePage.test.tsx`로 기재돼 있으나, 이 파일(`frontend/src/pages/manage/__tests__/UserManagePage.test.tsx:31`)은 REVIEWER 세션을 고정한 채 검색 필터 동작만 테스트하고 **역할 접근제어를 전혀 검증하지 않는다**. 실제로 WORKER/REVIEWER 접근 분기를 검증하는 파일은 `frontend/src/router/__tests__/manageGuard.test.tsx:36-75`이다(3케이스: WORKER→`/manage/users` FORBIDDEN, WORKER→`/manage/settings` FORBIDDEN, REVIEWER→둘 다 정상 렌더).
- **재현/확인 경로**: `UserManagePage.test.tsx` 파일 내용을 `grep -n "WORKER\|forbidden\|Role\."`으로 확인하면 역할 분기 코드가 없음을 바로 확인 가능.
- **영향**: 기능 결함은 아님(실제 접근제어는 라우터 가드 + BE 403으로 정상 동작, 본 검증에서 실HTTP로 재확인됨). 카탈로그 정합성 결함으로, 다음 회차 검증자가 잘못된 파일을 열어보고 "테스트 없음"으로 오판할 위험이 있다.
- **수정 방향(제안)**: 카탈로그 근거를 `router/__tests__/manageGuard.test.tsx:45-74`로 정정.

---

## 5. 판정 근거 요약

- **PASS 31건**: 코드 정적 대조(라인 정확 일치 다수) + 기존 vitest 컴포넌트 테스트(axios-mock-adapter 기반 실행 검증, 2026-08-01 baseline 1,951건 전량 통과, 이후 frontend 워킹트리 변경 0건이라 현재도 유효) + 일부 BE 실HTTP 왕복(RBAC 403, REVIEW_NO_LABEL 409 계약)으로 뒷받침.
- **FAIL 1건**(TC-FE-146): BE 응답 DTO에 파생 식별자 필드 자체가 없어 FE 가 표시할 수 없는 상태 — 미구현 갭으로 판단해 FAIL 승격.
- 브라우저 실동작(mcp playwright) 은 세션 공유로 인한 탭 콘텐츠 불안정 때문에 상호작용 흐름의 신뢰 가능한 근거로 사용하지 않았고, 대신 이미 실행된 컴포넌트 테스트(동일 렌더 엔진·동일 이벤트 시스템 — React Testing Library + userEvent)로 대체했다. 이는 README §5 방법 3("테스트 커버 확인")에 해당하며, 방법 1(실동작)이 아니라는 점을 근거확인 컬럼에 정직하게 `[정적]`으로 표기했다.


### [H-ISSUE-104] TC-FE-213 — 카탈로그 기대값 "라벨 무결성 카드"가 구현에 존재하지 않는다 (카탈로그 정합성)
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스의 기대결과가 현재 구현을 정확히 서술해야 다음 회차 재검증이 성립한다.
- **현재 동작(이슈 내용)**: `frontend/src/pages/AugmentResultPage.tsx:276-296`
  ```tsx
  {summary.status === 'COMPLETED' && summary.hasFramePairs && (
    <div ... data-testid="augment-result-integrity">
      <p ...>증강 이미지 생성률</p>
      ...현재 화면에 표시된 프레임 {summary.loadedPairs}쌍 기준입니다.
  ```
  "라벨 무결성"이라는 이름·개념은 코드에서 제거됐고(주석에 "라벨을 검사한 값이 아니어서 구 이름은 사실과 달랐다"고 명시), 카드는 **프레임 쌍이 있는 COMPLETED 잡에서만** 렌더된다. 실측: jobId 81 → "증강 이미지 생성률 100% / 현재 화면에 표시된 프레임 15쌍 기준입니다.", jobId 18(쌍 0건) → 카드 없음. 또 "총 처리 이미지"는 "비교 프레임 쌍"으로 개명됐다(값 자체는 기대대로 페이징 전 전체 쌍 수).
- **재현/확인 경로**: `/augment/result/18` 진입 → `document.querySelector('[data-testid=augment-result-integrity]')` → `null`.
- **영향**: 케이스를 문자 그대로 판정하면 오검(FAIL)이 난다. 기능 결함 아님.
- **수정 방향(제안)**: `H-frontend-e2e.md` TC-FE-213 기대결과를 "`augment-result-completed-empty` 미노출 + (프레임 쌍이 있을 때만) `augment-result-integrity`= '증강 이미지 생성률' 카드 표시 + '비교 프레임 쌍' = 페이징 전 전체 쌍 수"로 갱신.


### [H-ISSUE-105] H-10/H-11 근거 `file:line` 드리프트 8건 (카탈로그 정합성)
- **심각도**: LOW
- **기대 동작(기대효과)**: 근거 링크가 실제 구현 위치를 가리켜야 재검증 비용이 들지 않는다.
- **현재 동작(이슈 내용)**: 아래 8건이 어긋난다. 특히 TC-FE-160/209/210/211 은 **파일 자체가 바뀌었다**(증강 결과 화면이 `AugmentResultPage` → `AugmentVideoSection` → `AugmentResultPanel` 로 분해되면서 프레임 페이징·DecisionCard·authImages 배선이 전부 패널로 이동. `AugmentResultPage.tsx` 는 422줄이라 `452-461` 은 존재하지 않는 라인이다).

  | TC | 카탈로그 근거 | 실제 위치 |
  |----|------|------|
  | TC-FE-159 | `JobCard.tsx:64-72` | `JobCard.tsx:81-89` |
  | TC-FE-160 | `AugmentResultPage.tsx:419-421,452-461` | `features/augment/components/AugmentResultPanel.tsx:123-128,237-252` |
  | TC-FE-161 | `JobCard.tsx:75-83` | `JobCard.tsx:91-98` |
  | TC-FE-209 | `AugmentResultPage.tsx:425-450` · `FrameGrid12.tsx:156-167` | `AugmentResultPanel.tsx:170-196` · `FrameGrid12.tsx:158`(`SideBySideCompare.tsx:90` 은 정확) |
  | TC-FE-210 | `AugmentResultPage.tsx:33,414-441` | `AugmentResultPanel.tsx:30,113,177-186` |
  | TC-FE-211 | `AugmentResultPage.tsx:344-349,418` | `AugmentVideoSection.tsx:59-64` · `AugmentResultPage.tsx:120-124` |
  | TC-FE-213 | `AugmentResultPage.tsx:36-38,89-99,242-254` | `AugmentResultPage.tsx:186-196,263-274,282-296` |
  | TC-FE-274 | `PortalUploadLabelingPage.tsx:42-48` | `PortalUploadLabelingPage.tsx:43-49` |

  정확했던 근거(참고): `augTypeLabel.ts:30-36` · `useResolutionDerivative.ts:17-27` · `validation.ts:33-49/9,44/12,50-56` · `PortalUploadPage.tsx:48-53,77-87,270-271,288-292,296-306,280-281` · `useSaveUploadLabels.ts:85-98` · `busyPolicy.ts:69` · `SideBySideCompare.tsx:88-98`.
- **재현/확인 경로**: 각 파일을 Read 해 라인 대조.
- **영향**: 검증자가 매번 Grep 으로 재탐색해야 한다. 기능 결함 아님.
- **수정 방향(제안)**: `H-frontend-e2e.md` H-10/H-11 근거 컬럼을 위 표대로 갱신.

---

## 4. 확증편향 반증 시도 기록 (요구 지시 대응)

| 반증 대상 | 시도 | 결과 |
|------|------|------|
| **증강 폐기/복구가 실제로 도는가** | jobId 4 `비 #2` 를 UI 로 거부(REJECTED) → `[data-testid=decision-restore]` 클릭 → 복구 사유 모달("결과물을 다시 활용 결정 대기로 되돌립니다…") 입력 → [복구 확정] | **정상 동작** — `data-decision` REJECTED→**PENDING**, 탭 라벨도 "비 #2 · 거부됨"→"비 #2 · 활용 결정 대기", 토스트 "복구 처리됨". `restoreEligible=false` 인 항목(id 3 = GENERATION_FAILED, id 14 = ACCEPTED)에는 복구 버튼이 아예 안 뜬다(fail-closed 확인) |
| **진행률이 자체 생성(self-fill)인가** | jobId 4 항목 14 의 `augment-progress-*` 실측 | **BE 실값** — "진행 상태 완료 / 진행률 100% / 처리 완료 1 / 1건". `AugmentResultPage.tsx:200-206` 주석대로 잡 단위 가짜 진행률(0/50/100)을 그리지 않고 항목별 `GET /v1/augments/{id}/progress` 를 쓴다. 해상도 파생 항목은 `enabled={!isResolution}` 으로 폴링 자체를 안 함 |
| **생성 조건(prompt)도 self-fill 인가** | 같은 항목 `augment-prompt-14` | **BE 값 그대로** — `{"time":"NIGHT","season":"WINTER","weather":"RAIN","terrain":"ROAD","severity":"HIGH"}` 를 "시간대 NIGHT / 계절 WINTER / …" 로 표시. 값 없는 항목(`prompt:null`)은 카드를 그리지 않음 |
| **포털에 오토라벨/검수/버전관리 버튼이 정말 없는가** | `/portal`·`/portal/uploads`·`/portal/uploads/80/label` 3화면 본문 정규식 검사 | 업로드 라벨링: `AI 탐지\|AI 분할\|AI 추적\|스켈레톤\|오토라벨` **0건**, `검수\|버전\|승인\|반려` **0건**. 포털 홈 내비 링크 = `/portal`·`/portal/uploads` 2개뿐. ⚠ **단 `/portal/label/:srcSn`(데이터마트 영상)에는 AI 분할·추적·스켈레톤이 노출** → H-ISSUE-103 |
| **해상도 파생에 채택/거부가 새는 경로가 있는가** | jobId 81 3탭 + jobId 4 의 RESL 3탭 전수 + JobCard 전체 코드 | `decision-card` 0건. BE 도 `reviewable=false` 로 내려줌. 다만 `GET /v1/augments/4/result` 실측상 RESL 항목의 `decision` 은 `ACCEPTED` 로 채워져 내려오는데(내부 생성물 라이프사이클), 화면은 `isResolution` 우선 판정이라 표시되지 않는다 — 의도된 동작 |
| **프레임 페이저로 도달 불가한 프레임이 있는가** | 30쌍 항목에서 페이지 1→2 이동, 12쌍 슬라이스 경계 확인 | 1페이지 `frame-pair-339~350`, 2페이지 `frame-pair-351~362`, 페이저 버튼 `1/2/3` → 30쌍 전부 도달 가능 |
| **탭 전환 시 빈 그리드에 갇히는가** | 30쌍 항목 2페이지 → 5쌍짜리 480P 탭 전환 | framePage 리셋되어 정상 렌더(갇힘 없음). 안전망 `emptyFramePage` + "첫 페이지로" 버튼도 코드상 존재(`AugmentResultPanel.tsx:198-228`) |
| **XSS 가 실제로 실행되는가** | ①증강 거부 사유 `<img src=x onerror="window.__xss=1">` ②포털 업로드 파일명 `qa <img src=x onerror=alert(1)>.jpg` | 둘 다 **escape 되어 텍스트로 렌더**, DOM 에 주입 `img` 0개, 스크립트 미실행 |
| **포털 저장이 원본을 건드리는가** | 포털 user-label 저장 전/후 내부 `GET /v1/frames/448/labels` 대조 | items 0 → 0, labelVersion 0 → 0 (**불변**). 포털 데이터는 `LS_PORTAL_USER_LABEL` 에만 적재 |
| **포털 채널 가드가 경로별로 다 도는가** | `/dashboard`·`/manage/users`·`/review/pending`·`/augment` 4경로 시도 | 전부 `/forbidden`. 포털 전용 `/portal`·`/portal/uploads` 만 통과 |


### [H-ISSUE-144] TC-FE-224 — "IN_PROGRESS 는 UI 값이되 서버 미전송" 기대결과가 현행 구현과 불일치(카탈로그 stale)
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스 원문은 "WORKER 시각 URL `?status=IN_PROGRESS` → select 값은 유지(클라이언트 필터), 서버 파라미터에서는 제외(`asWorkStatusParam`→undefined)" 를 기대한다.
- **현재 동작(이슈 내용)**: 축이 둘로 분리되면서 이 조합이 사라졌다.
  - REVIEWER 축(`boardParams.ts:100-106 asWorkStatusParam`): `IN_PROGRESS` → `undefined`(서버 미전송)인 것은 맞으나, `TaskListPage.tsx:93-102` 가 초기 state 에서 `workStatus=''` 로 정규화하므로 **select 값도 유지되지 않는다**(= TC-FE-225 가 기술하는 동작). 실측: select `task-filter-status` value `""`.
  - WORKER 축(`boardParams.ts:115-121 asAssignmentWorkStatusParam`): `IN_PROGRESS` 는 **허용값**이라 `/v1/assignments?workStatus=IN_PROGRESS` 로 **서버 전송**된다. BE 도 지원 — 실측 `workerId=2001` 전체 16건 → `workStatus=IN_PROGRESS` 필터 시 2건.
- **재현/확인 경로**:
  ```
  화면: /task?status=IN_PROGRESS (REVIEWER) → 요청 status=COMPLETED&page=0&size=20&sort=regDt,desc (workStatus 없음), select value=""
  BE  : curl -H "$AUTH" "/api/v1/assignments?workerId=2001&workStatus=IN_PROGRESS&page=0&size=2" → totalElements 2
  ```
- **영향**: 기능 영향 없음(현행 동작이 정책상 더 옳다 — 클라이언트 필터 금지). **카탈로그 기대결과의 정합성 결함**이며, 그대로 두면 다음 회차에서 "구현이 틀렸다" 는 오판을 부른다.
- **수정 방향(제안)**: TC-FE-224 를 폐기(`~~취소선~~` + `[폐기 2026-08-02]`)하고, 두 축 각각의 실제 계약으로 케이스를 재작성한다 — ①REVIEWER: URL `IN_PROGRESS` 는 select·요청 양쪽에서 제거(TC-FE-225 와 통합) ②WORKER: `IN_PROGRESS` 는 select 유지 + `/v1/assignments` 로 **서버 전송**. **본 검증에서는 카탈로그를 수정하지 않음.**


### [H-ISSUE-145] TC-FE-242 — "WORKER 시각은 클라이언트 필터" 기대결과가 현행 구현과 불일치(카탈로그 stale)
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스 원문은 "`/v1/assignments` 가 필터·정렬을 지원하지 않아 화면에서 거른다. 헤더 '전체 N건'=거른 행 수" 를 기대한다.
- **현재 동작(이슈 내용)**: `/v1/assignments` 는 `q`/`workStatus`/`eventTypeCd` 를 **서버에서 지원**하고 화면도 서버에 위임한다.
  ```
  BE 실측: /assignments?workerId=2001            → totalElements 16
           /assignments?workerId=2001&q=zzzz     → totalElements 0
           /assignments?workerId=2001&workStatus=IN_PROGRESS → totalElements 2
           /assignments/event-types              → {"items":[...],"truncated":false}
  ```
  `TaskListPage.tsx:322-327` — "화면에 그릴 행 = 서버가 이미 거른 결과 그대로다 … 클라이언트 재필터 금지 — **역할 무관**", `pagedRows = allRows`(:327), 총건수 `listPage?.totalElements`(:334). `features/task/types.ts:79-88` 도 서버사이드 필터임을 명시.
- **재현/확인 경로**: 위 curl 4줄 + `TaskListPage.worker.filters.test.tsx` 의 '작업목록_클라이언트_재필터가_적용되지_않는다' / '전체건수는_서버_totalElements_를_표시한다'.
- **영향**: 기능 영향 없음(현행이 루트 `CLAUDE.md` "필터·집계는 BE 에서 전체 기준으로 처리하며 FE 클라이언트 필터로 대체하지 않는다" 구속 정책에 부합). 카탈로그 정합성 결함.
- **수정 방향(제안)**: TC-FE-242 기대결과를 "WORKER 도 `/v1/assignments` 서버 필터를 사용하고 '전체 N건'=서버 `totalElements`. KPI 는 클릭 필터 없는 표시 전용 4카드" 로 정정한다. **본 검증에서는 카탈로그를 수정하지 않음.**


### [H-ISSUE-147] H-16 근거 `file:line` 광범위 드리프트 (카탈로그 정합성)
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그의 근거 `file:line` 은 클릭 시 해당 로직에 닿아야 한다(추적성).
- **현재 동작(이슈 내용)**: H-16 의 `boardParams.ts` / `TaskListPage.tsx` 근거가 일괄 어긋나 있다(H-15 의 `labeling-review-full-flow.spec.ts` 와 H-17 전체는 **정확**).
  | TC | 카탈로그 근거 | 실제 위치 |
  |---|---|---|
  | TC-FE-219 | `boardParams.ts:126-140` | `boardParams.ts:151-165`(`buildBoardParams`) |
  | TC-FE-221 | `boardParams.ts:14-17,59,131` | `:18-21`(주석), `:67`(`BOARD_BATCH_STATUS`), `:156` |
  | TC-FE-222 | `boardParams.ts:168-176` | `:231-239`(`searchParamsToFilters`) |
  | TC-FE-223 | `boardParams.ts:80-96` | `:90-93`(`asUiWorkStatus`) |
  | TC-FE-224 | `boardParams.ts:74-96` | `:100-106`(`asWorkStatusParam`) |
  | TC-FE-229 | `boardParams.ts:188-202` | `:251-265`(`filtersToSearchParams`) |
  | TC-FE-233 | `boardParams.ts:142-157` | `:173-182`(`buildBoardSummaryParams`) |
  | TC-FE-241 | `boardParams.ts:53,106-109` | `:61`, `:131-134` |
  | TC-FE-225 | `TaskListPage.tsx:85-92` | `:93-102` |
  | TC-FE-230 | `TaskListPage.tsx:395-402` | `:377-384`(`handleFiltersReset`) |
  | TC-FE-231 | `TaskListPage.tsx:404-412` | `:387-394`(`handleKpiSelect`) |
  | TC-FE-234 | `TaskListPage.tsx:174-176` | `:188-191` |
  | TC-FE-235 | `TaskListPage.tsx:316-317,340-352` | `:327`, `:331-334` |
  | TC-FE-236 | `TaskListPage.tsx:368-381,423-429` | `:356-365`, `:405-411` |
  | TC-FE-237 | `TaskListPage.tsx:385-421` | `:367-403` |
  | TC-FE-238 | `TaskListPage.tsx:362-366` | `:344-348` |
  | TC-FE-240 | `TaskListPage.tsx:542-554,460-465` / `TaskBoardTable.tsx:149-163,320-340` | `:526-538`, `:601` / `TaskBoardTable.tsx:155,246,325-330` |
  | TC-FE-242 | `TaskListPage.tsx:319-334,350-352` | `:322-334`, `:482-487` |
  | TC-E2E-014 | `labeling-flow.spec.ts:10-25` | `:26-42`(BBox 저장 테스트) |
  | TC-E2E-015 | `worker-labeling.spec.ts:12-28` | `:29-36`(도구 버튼 테스트) |
  (`boardSort.ts` 근거 6건 — TC-FE-220/226/227/228 — 은 **전부 정확**)
- **재현/확인 경로**: 위 표의 좌우 대조.
- **영향**: 추적성. 다음 회차 검증자가 잘못된 라인을 읽고 "구현 없음" 으로 오판할 위험.
- **수정 방향(제안)**: H-16 표의 근거 컬럼을 위 실제 위치로 일괄 갱신. **본 검증에서는 카탈로그를 수정하지 않음.**

---

## 4. 판정 근거 원문 (실동작 로그 발췌)

### 작업목록 — 진입/URL 정규화/정렬
```
/task                                   → GET /v1/tasks/board?status=COMPLETED&page=0&size=20&sort=regDt,desc
                                          GET /v1/tasks/board/summary?status=COMPLETED
                                          GET /v1/tasks/board/event-types?status=COMPLETED
/task?status=UNASSIGNED                 → status=COMPLETED&workStatus=UNASSIGNED&page=0&size=20&sort=regDt,desc   (URL 유지)
/task?status=BOGUS&sort=priority,desc   → status=COMPLETED&page=0&size=20&sort=regDt,desc                          (URL → /task)
/task?status=IN_PROGRESS (REVIEWER)     → status=COMPLETED&page=0&size=20&sort=regDt,desc, select value=""         (URL → /task)
/task?sort=videoId,desc&sort=rawSn,asc&sort=shtDt,asc&sort=regDt,asc
                                        → sort=rawSn,desc&sort=shtDt,asc&sort=regDt,asc   (dedup + cap3)
                                          영상 ID aria-sort=descending / 촬영일시 aria-sort=ascending
촬영일시 헤더 1클릭(기준 sort=regDt,asc) → sort=shtDt,desc&sort=regDt,asc   (URL ?sort=shtDt,desc&sort=regDt,asc)
촬영일시 헤더 2클릭                       → sort=shtDt,asc&sort=regDt,asc
"초기화" 클릭                            → URL /task , sort=regDt,desc , 촬영일시 aria-sort 제거
검색 input                               → name="task-filter-q" maxLength=100
KPI 카드                                 → 전체 작업42 / 미배정30 / 작업중3 / 검수요청2 / 반려1 (aria-pressed 토글)
'작업중' 클릭                            → URL ?status=PENDING , 요청 workStatus=PENDING
'미배정' 클릭                            → URL ?status=UNASSIGNED , 요청 workStatus=UNASSIGNED , 헤더 "전체 30건" , 행 20
```

### 검수목록 — 진입/정규화/정렬/액션
```
/review                                        → GET /v1/reviews?page=0&size=20&sort=submittedAt,asc&status=PENDING
                                                 GET /v1/reviews/summary        (파라미터 없음)
                                                 URL = /review?status=REVIEW_PENDING&sort=submittedAt,asc&page=0&size=20
?status=BOGUS&sort=labelPayload,asc            → URL 교정 ?status=REVIEW_PENDING&sort=submittedAt,asc&page=0&size=20
                                                 요청 sort=submittedAt,asc&status=PENDING
?status=ALL&sort=submittedAt,asc&page=0&size=20 → 요청 page=0&size=20&sort=submittedAt,asc     (status 키 생략, URL 은 ALL 유지)
?status=REJECTED&sort=submittedAt,desc         → 요청 sort=submittedAt,desc&status=REJECTED
제출일 헤더 클릭 (page=1 상태)                  → URL ?status=ALL&sort=submittedAt,desc&page=0&size=20 (page 0 복귀)
제출일 헤더 재클릭                              → sort=submittedAt,asc
th 정렬 버튼 유무                               → 영상명 X / 이벤트 X / 작업자 X / 제출일 O(ascending) / 라벨수 X / 상태 X / 액션 X
행 액션 접근성 이름                             → "결과보기 CCTV-강남구-001" , "이어서 검수 CCTV-QA1"
KPI 4카드                                      → 검수요청1 / 검수중1 / 승인20 / 반려1
"초기화" 버튼(status=ALL 상태)                  → 활성(disabled=false)
```

### BE 계약 실측
```
GET /v1/tasks/board?sort=priority,desc  → 400 INVALID_INPUT "지원하지 않는 정렬 기준입니다."   (strict)
GET /v1/reviews?sort=labelPayload,asc   → 200 totalElements 23                                (lenient 폴백)
GET /v1/reviews?status=REVIEW_PENDING   → 200 totalElements 0   ← FE 코드 그대로 보내면 "빈 결과 위장"
GET /v1/reviews?status=BOGUS            → 200 totalElements 0   ← 동일
GET /v1/tasks/board/event-types?status=COMPLETED → {"items":["EV02000201","INTRUSION","LOITERING"],"truncated":false}
GET /v1/assignments/event-types                  → 동형 객체
GET /v1/assignments?workerId=2001                → 16 / &workStatus=IN_PROGRESS → 2 / &q=zzzz → 0
SortAllowlist.TASK_BOARD = {regDt, capturedAt→shtDt, shtDt, rawSn, videoId→rawSn}   (FE 매핑과 1:1 동일)
SortAllowlist.REVIEW     = {submittedAt→updDt, updDt, videoId→rawDataId, status→dataSttsCd}
```


