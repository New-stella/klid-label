package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import kr.co.cudo.authoring.batch.status.BatchBundleFailureGate;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.ManualStageSkip;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.AutoLabelInfoProjection;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.video.dto.AutoLabelResultResponse;
import kr.co.cudo.authoring.sysconfig.repository.LsVrfcEvntQstnRepository;
import kr.co.cudo.authoring.video.dto.VideoDetailResponse;
import kr.co.cudo.authoring.video.dto.VideoListFilter;
import kr.co.cudo.authoring.video.dto.VideoSummaryResponse;
import kr.co.cudo.authoring.user.service.UserNameResolver;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.IngestSourceRow;
import kr.co.cudo.authoring.video.repository.VideoExportProjection;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class VideoQueryService {

    private static final String DEFAULT_LABEL_COLOR = "#3B82F6";

    /**
     * 영상 상세에 내리는 비식별 이력 최대 건수. [req: R14]
     *
     * <p>정상 운영에서 한 영상의 위탁 회차는 손에 꼽지만, 위탁 실패가 반복되면 원장 행이 계속 쌓인다.
     * 무제한으로 내리면 상세 조회 하나가 응답 크기를 좌우한다(CWE-770). 상세 화면이 실제로 보여줄 수
     * 있는 범위를 넘는 이력은 화면의 관심사가 아니므로 여기서 자른다.
     */
    public static final int DEIDENT_HISTORY_MAX = 20;

    private final VideoRepository videoRepository;
    private final IngestSourceRepository ingestSourceRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository lblRepository;
    private final LsRawDataStatusRepository rawDataStatusRepository;
    private final LsTaskAssignmentRepository taskAssignmentRepository;
    private final UserNameResolver userNameResolver;
    private final LsDeidentProcLogRepository deidentProcLogRepository;
    private final BatchStatusService batchStatusService;
    /**
     * DEV_FIX(H10) — 영상 상세에 실 fps 를 실어 FE 마킹 화면이 서버와 동일한 fps 로 frameIndex 를
     * 산출하게 한다(FE 30fps 하드코딩 ↔ 서버 실 fps 상한의 불일치 제거). 마킹 상한 검증과 같은 진실원.
     */
    private final VideoFpsResolver fpsResolver;

    /**
     * 검수 상태 필터 입력 길이 상한 — 정상 enum 값(PENDING/ASSIGNED/IN_REVIEW/APPROVED/REJECTED)은
     * 모두 20자 이하. 상한 초과 입력은 즉시 차단해 의도 외 query 부하/탐색 방지.
     * 파라미터 바인딩으로 SQL Injection 자체는 차단되지만, 입력 검증 차원의 1차 가드.
     */
    private static final int REVIEW_STATUS_MAX_LEN = 20;

    /**
     * 검색어 길이 상한 — FE 입력({@code VideoFilters}, {@code maxLength=100})과 같은 값이다.
     * 초과 입력은 정상 동선에서 발생할 수 없으므로 400 으로 거부한다(CWE-20 / CWE-770).
     */
    private static final int KEYWORD_MAX_LEN = 100;

    /**
     * 이벤트 카테고리 키 길이 상한 — 실제 키는 6자({@code EVNT_CLS_CD}(2) + {@code EVNT_CTGRY_CD}(4))이며
     * 코드값 표준도메인 {@code VARCHAR(20)} 을 상한으로 둔다. 초과는 <b>정의상 미등록 코드</b>이므로
     * 400 이 아니라 "매칭 0건" 으로 처리한다(미등록 코드 처리와 동일 — 수용기준 2).
     */
    private static final int EVENT_TYPE_MAX_LEN = 20;

    /**
     * LIKE 이스케이프 문자. 백슬래시 대신 {@code !} 를 쓰는 이유는 JPQL 문자열 리터럴·JDBC·DB 설정
     * ({@code standard_conforming_strings})마다 백슬래시 해석이 갈리기 때문이다.
     */
    private static final char LIKE_ESCAPE = '!';

    /**
     * 이벤트 필터가 <b>매칭 0건</b>이어야 할 때 넘기는 sentinel. 빈 컬렉션을 {@code IN} 에 넘기면
     * 유효한 SQL 로 렌더되지 않으므로, 실제 코드와 절대 겹치지 않는 값 1건을 넘겨 0건을 만든다.
     */
    private static final List<String> NO_EVENT_MATCH = List.of("__NO_EVENT_CODE_MATCH__");

    /** 검색어가 영상 ID(rawSn)로도 해석되는 조건 — 숫자만으로 이뤄지고 long 범위를 넘지 않는 입력. */
    private static final java.util.regex.Pattern NUMERIC_KEYWORD =
            java.util.regex.Pattern.compile("\\d{1,18}");

    /**
     * 이벤트 카테고리 키 → EV-코드 변환기. 프리셋 경로({@code PresetLabelLookupService})와 <b>같은
     * 역인덱스</b>를 쓴다 — 축이 갈라지면 같은 영상이 화면마다 다른 카테고리로 잡힌다.
     */
    private final kr.co.cudo.authoring.eventtype.service.EventTypeService eventTypeService;
    /**
     * P2b — "한번이라도 검수 완료"의 단일 판정 원천(화면 버튼 비활성화 근거).
     *
     * <p>필드를 <b>맨 뒤</b>에 둔다: {@code @RequiredArgsConstructor} 가 선언 순서로 생성자를 만들므로
     * 중간에 넣으면 위치 인자를 쓰는 기존 테스트가 조용히 어긋난다(컴파일이 잡아주지 못하는 조합도 있다).
     */
    private final kr.co.cudo.authoring.assignment.service.ReviewApprovalGate approvalGate;
    /**
     * 「그 작업 묶음이 실패한 상태인가」의 단일 판정 지점 — 상세의 {@code failedStages} 와 목록의
     * {@code failedStage} 필터가 같은 축을 쓰게 한다. [@design ADR-050]
     *
     * <p>필드를 <b>맨 뒤</b>에 둔다({@code approvalGate} 와 같은 이유) — {@code @RequiredArgsConstructor}
     * 가 선언 순서로 생성자를 만들므로 중간에 넣으면 위치 인자를 쓰는 기존 테스트가 조용히 어긋난다.
     */
    private final BatchBundleFailureGate bundleFailureGate;

    /**
     * 검증 이벤트 유형별 질문 조회 — 마킹 화면이 고를 목록의 조달처. [@design API-043] [@design ERD-033]
     *
     * <p><b>읽기 전용 재사용</b>이다. 정렬 규칙(정렬순서 오름차순)은 저장소 메서드 이름이 갖고 있으므로
     * 여기서 다시 정렬하지 않는다 — 「첫 번째 질문」의 결정성이 그 정렬에 걸려 있어 사본을 만들면
     * 그것이 곧 두 번째 진실원이 된다.
     */
    private final LsVrfcEvntQstnRepository vrfcEvntQstnRepository;

    /**
     * 기존 호출(상태 필터 2종만) 호환 진입점 — 신규 필터는 전부 미적용.
     *
     * <p><b>사용자 축 스코핑이 없다</b>(검수자와 같은 전체 범위). 인증 주체를 받지 않으므로 HTTP 진입점이
     * 이 오버로드를 쓰면 안 된다 — 컨트롤러는 {@link #listForActor(Pageable, VideoListFilter, TokenClaims)} 로만
     * 들어온다. [@design API-042]
     */
    public Page<VideoSummaryResponse> list(Pageable pageable, String dataSttsCd, String reviewStatusCd) {
        return list(pageable, VideoListFilter.ofStatus(dataSttsCd, reviewStatusCd));
    }

    /**
     * 영상 처리 현황 목록 — 상태 2종 + 검색어 + 이벤트 카테고리 + 촬영기간 조합 조회.
     *
     * <p>필터는 <b>전부 DB 조건</b>으로 내려간다(페이징 후 Java 필터 금지) — 그래야 {@code totalElements}
     * 와 페이지 수가 필터 적용 후 전체 기준이 된다.
     */
    public Page<VideoSummaryResponse> list(Pageable pageable, VideoListFilter filter) {
        return listScoped(pageable, filter, null);
    }

    /**
     * 영상 처리 현황 목록 — <b>호출자 역할에 따라 조회 범위가 갈린다</b>. [@design API-042]
     *
     * <p>검수자는 전체 영상을, 라벨링 작업자는 <b>본인에게 배정된 영상만</b> 본다. 범위 제한은 거부가
     * 아니라 <b>결과 축소</b>이며 배정이 하나도 없으면 403 이 아니라 빈 목록이다 — 목록을 부르는 행위
     * 자체는 정상이기 때문이다.
     *
     * <p>이 창구에는 사용자 축 인가가 아예 없었는데 형제 단건 창구는 배정을 요구해서, 목록에는 남의
     * 영상이 나오는데 그 영상을 열면 403 인 비대칭이 있었다(CWE-639 IDOR — 촬영지·이벤트·비식별 상태가
     * 그대로 노출됐다). 단건의 403 은 그대로 둔다 — 직접 URL 입력·외부 클라이언트를 막는 별개 방어선이다.
     */
    public Page<VideoSummaryResponse> listForActor(Pageable pageable, VideoListFilter filter, TokenClaims actor) {
        return listScoped(pageable, filter, scopeUserNoFor(actor));
    }

    /**
     * 조회 범위 확정 — 스코핑 대상 사용자 번호를 인증 주체에서만 도출한다. [@design API-042] [@design ROLE-002]
     *
     * <p><b>판정 규칙은 {@code AssignmentService.scopeForActor} 와 같다</b>(원본) — 토큰 없음 401 ·
     * 라벨링 작업자는 토큰 subject 로 고정 · 검수자는 미적용 · 그 외 역할은 403. 배정 존재 판정 축도
     * 단건 가드({@code LabelAccessGuard.verifyRawAccess})와 같은 {@code TASK_LABELER} 다.
     *
     * <p><b>공용 헬퍼로 뽑지 못한 이유</b>: 원본은 {@code assignment} 패키지의 {@code private} 메서드이고
     * 그 판정 결과를 그 도메인 전용 조건 객체({@code AssignmentSearchCondition.scopedToSelf})에 실어
     * 돌려준다. 공용화하려면 {@code assignment} 또는 {@code common} 을 함께 고쳐야 해 이 변경의 경계를
     * 넘는다. 그래서 <b>규칙만 최소 복제</b>했다.
     *
     * <p>⚠ <b>두 창구의 동치를 검증하는 가드는 없다.</b> 회귀 가드({@code VideoListAssignmentScopeIT})가
     * 고정하는 것은 <b>이 창구의 동작</b>이지 원본 규칙과의 동치가 아니다 — 그 가드는 원본을 참조하지
     * 않으므로 원본이 바뀌어도 실패하지 않는다. 따라서 {@code AssignmentService} 의 역할 스코프 판정을
     * 고칠 때는 <b>이 메서드를 함께 확인해야 한다</b>(드리프트를 잡아 줄 장치가 없다).
     *
     * <p>역할 게이트({@code @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")})와 <b>이중 방어</b>다 —
     * 그 게이트가 느슨해져도 여기서 다시 막힌다.
     *
     * @return 라벨링 작업자면 본인 사용자 번호, 검수자면 {@code null}(스코핑 미적용)
     */
    private Long scopeUserNoFor(TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        if (actor.role() == Role.WORKER) {
            return parseUserNo(actor.sub());
        }
        if (actor.role() == Role.REVIEWER) {
            return null;
        }
        throw new CustomException(ErrorCode.FORBIDDEN, "조회 권한이 없습니다.");
    }

    /**
     * 토큰 subject → 사용자 번호. 비숫자 subject 는 {@code AssignmentService.parseUserNo} 와
     * <b>같게</b> 401 로 다룬다(값을 지어내거나 스코핑을 풀지 않는다 — fail-closed).
     */
    private Long parseUserNo(String sub) {
        try {
            return Long.parseLong(sub);
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "토큰 subject 형식이 올바르지 않습니다.");
        }
    }

    private Page<VideoSummaryResponse> listScoped(Pageable pageable, VideoListFilter filter, Long assignedToUserNo) {
        VideoListFilter cond = filter != null ? filter : VideoListFilter.ofStatus(null, null);
        String normalizedDataStts = trimToNull(cond.dataSttsCd());
        String normalizedReviewStts = normalizeReviewStatusCd(cond.reviewStatusCd());
        String keywordRaw = trimToNull(cond.cctvNameKeyword());
        if (keywordRaw != null && keywordRaw.length() > KEYWORD_MAX_LEN) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "검색어는 " + KEYWORD_MAX_LEN + "자 이하여야 합니다.");
        }
        String keywordPattern = keywordRaw == null ? null : likePattern(keywordRaw);
        Long keywordRawSn = parseRawSn(keywordRaw);
        Collection<String> eventCodes = resolveEventCodes(cond.eventTypeCd());
        int eventFilterOn = eventCodes == null ? 0 : 1;
        LocalDateTime from = rangeStart(cond.from(), cond.to());
        LocalDateTime to = rangeEnd(cond.to());
        String skippedBundle = normalizeSkippedStage(cond.skippedStage());
        // [@design API-042] [@design ADR-050] 실패 묶음 필터 — 미지정이면 두 값 모두 null(필터 미적용).
        //   실패의 두 축(진행 축 · 시계열 위탁 실패 감사 행)은 판정 소유자와 <b>같은 합성</b>이며,
        //   사유 문자열의 단일 원천도 그 소유자다(여기서 복제하지 않는다).
        BatchStageBundle failedBundle = normalizeFilterBundle(cond.failedStage());
        Collection<String> failedBundleStages = failedBundle == null ? null
                : failedBundle.stages().stream().map(BatchStage::name).toList();
        Collection<String> vlmFailureReasons = failedBundle == BatchStageBundle.VLM
                ? BatchBundleFailureGate.vlmFailureSkipReasons() : null;

        // R1 — 영상 처리 현황은 원본 RAW 만 노출한다(파생 RAW=ORGNL_RAW_SN NOT NULL 제외).
        // 파생물은 증강 이력 화면에서만 보이며, 작업 목록(TaskBoardService)에는 여전히 포함된다(R2, 분리 유지).
        // 검수 상태 필터 지정 시 LS_RAW_DATA_STATUS 조인이 상태행 없는 영상을 걸러낸다(구 INNER JOIN 과 동치).
        // 정렬은 컨트롤러가 allowlist 로 검증·매핑한 Pageable Sort 에 위임한다(기본 regDt DESC) —
        // 조인 alias 를 통한 검수 완료 시각(reviewCompletedAt → s.updDt) 정렬은 usesReviewStatusJoin 이
        // 참인 호출에서만 allowlist 를 통과한다.
        Page<LsDataRaw> page = videoRepository.searchOriginals(
                normalizedDataStts, normalizedReviewStts,
                keywordPattern, keywordRawSn,
                eventFilterOn, eventCodes != null ? eventCodes : NO_EVENT_MATCH,
                from, to, skippedBundle, failedBundleStages, vlmFailureReasons,
                assignedToUserNo, pageable);
        Map<Long, String> cctvNameMap = lookupCctvNames(page.getContent());
        Map<Long, Long> frameCountMap = lookupFrameCounts(page.getContent());
        Map<Long, VideoSummaryResponse.ExportInfo> exportInfoMap = lookupExportInfos(page.getContent());
        Map<Long, LocalDateTime> reviewCompletedAtMap = lookupReviewCompletedAt(page.getContent());
        Map<Long, VideoSummaryResponse.AssignmentInfo> assignmentMap = lookupCurrentAssignments(page.getContent());
        Map<Long, VideoSummaryResponse.DeidentInfo> deidentMap = lookupDeidentInfos(page.getContent());
        return page.map(e -> VideoSummaryResponse.from(
                e,
                cctvNameMap.get(e.getRawSn()),
                null,
                frameCountMap.getOrDefault(e.getRawSn(), 0L),
                exportInfoMap.get(e.getRawSn()),
                reviewCompletedAtMap.get(e.getRawSn()),
                assignmentMap.get(e.getRawSn()),
                deidentMap.get(e.getRawSn())
        ));
    }

    /**
     * 건너뜀 필터 값 해석 — 허용 묶음이 아니면 <b>400</b>. [@design API-042] [@design ADR-050]
     *
     * <p>미지정({@code null}·공백)은 <b>필터 미적용</b>이다 — 신규 파라미터를 보내지 않던 기존 호출의
     * 결과가 달라지면 안 된다(하위호환 계약).
     *
     * <p>해석은 {@link BatchStageBundle#parse} <b>단일 지점</b>을 재사용한다(허용 목록을 복제하지 않는다).
     * 미지 값은 0건이 아니라 <b>400</b> 이다 — 이벤트 유형 키(등록되지 않으면 0건)와 축이 다르다.
     * 그쪽은 운영자가 바꾸는 <b>데이터</b>라 없는 값이 정상 입력일 수 있지만, 묶음은 <b>코드로 고정된
     * 집합</b>이라 목록 밖의 값은 요청 오류이며 조용히 0건을 주면 화면이 "건너뛴 영상이 없다"로 오독한다.
     *
     * <p>거부 문구에 요청 값을 되비추지 않는다(CWE-79/117). 허용 값은 상수라 문구에 담아도 안전하다.
     */
    private static String normalizeSkippedStage(String raw) {
        BatchStageBundle bundle = normalizeFilterBundle(raw);
        return bundle == null ? null : bundle.name();
    }

    /**
     * 묶음 필터 값 해석 공용 파서 — {@code skippedStage}·{@code failedStage} 가 <b>같은 규칙</b>을 쓴다.
     * [@design API-042] [@design ADR-050]
     *
     * <p>두 파라미터는 축이 다르지만(「사람이 건너뛴 상태」 vs 「실패한 상태」) <b>값 집합과 거부 규칙은
     * 같다</b>. 규칙을 복제하면 한쪽만 갱신돼 같은 값이 파라미터마다 다르게 해석된다.
     *
     * @return 해석된 묶음, 미지정({@code null}·공백)이면 {@code null}(필터 미적용)
     */
    private static BatchStageBundle normalizeFilterBundle(String raw) {
        String trimmed = trimToNull(raw);
        if (trimmed == null) {
            return null;
        }
        BatchStageBundle bundle = BatchStageBundle.parse(trimmed);
        if (bundle == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "작업 묶음 필터 값이 올바르지 않습니다. 허용 값: " + ManualStageSkip.skippableBundleNames());
        }
        return bundle;
    }

    /**
     * 페이지 단위로 rawSn 들의 프레임 개수를 단일 GROUP BY 쿼리로 batch 조회 (N+1 회피).
     *
     * <p>{@link LsDataSrcRepository#countByRawSnsGrouped}({@code [rawSn, frameCount]} Object 배열) 결과를
     * Map 으로 변환한다. 프레임이 0건인 영상은 결과에 포함되지 않으므로 caller 가 0L 폴백 처리한다.
     */
    private Map<Long, Long> lookupFrameCounts(List<LsDataRaw> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> rawSns = rows.stream().map(LsDataRaw::getRawSn).toList();
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : srcRepository.countByRawSnsGrouped(rawSns)) {
            map.put((Long) row[0], (Long) row[1]);
        }
        return map;
    }

    /**
     * 페이지 단위로 rawSn 들의 비식별 상태를 한 번의 batch 조회로 파생한다 (N+1 회피).
     *
     * <p>각 rawSn 의 최신 LS_DEIDENT_PROC_LOG 1행을 IN 절 1회로 조회({@code findLatestByDataRawSnIn})하고,
     * LS_DATA_RAW.DE_IDENT_YN 과 결합해 deidentStatus 를 파생한다. 우선순위는 {@link #deriveDeidentStatus} 참조.
     */
    private Map<Long, VideoSummaryResponse.DeidentInfo> lookupDeidentInfos(List<LsDataRaw> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> rawSns = rows.stream().map(LsDataRaw::getRawSn).toList();
        Map<Long, LsDeidentProcLog> latestByRaw = deidentProcLogRepository.findLatestByDataRawSnIn(rawSns).stream()
                .collect(Collectors.toMap(LsDeidentProcLog::getDataRawSn, p -> p, (a, b) -> a));
        Map<Long, VideoSummaryResponse.DeidentInfo> map = new HashMap<>();
        for (LsDataRaw raw : rows) {
            String status = deriveDeidentStatus(raw, latestByRaw.get(raw.getRawSn()));
            map.put(raw.getRawSn(), new VideoSummaryResponse.DeidentInfo(raw.getDeIdntfYn(), status));
        }
        return map;
    }

    /**
     * 영상 1건의 비식별 상태 파생 (우선순위 — 'Y' 최우선):
     * <ol>
     *   <li>deIdntfYn=='Y' → DONE (완료, 마킹 진입 가능)</li>
     *   <li>deIdntfYn=='F' 또는 최신 procLog FAILED → FAILED</li>
     *   <li>최신 procLog 진행중(REQUESTED / POLL WAITING|POLLING) → IN_PROGRESS</li>
     *   <li>그 외(procLog 없음 & deIdntfYn=='N') → NONE</li>
     * </ol>
     *
     * <p>SUCCEEDED/DOWNLOADED procLog + deIdntfYn='N' 비정상 상태도 NONE 에 해당하나, 정상
     * 트랜잭션(markDeidentified('Y')+procLog.succeed() 동일 커밋)에서는 발생하지 않는다.
     */
    private String deriveDeidentStatus(LsDataRaw raw, LsDeidentProcLog latestLog) {
        String yn = raw.getDeIdntfYn();
        if ("Y".equals(yn)) {
            return VideoSummaryResponse.DeidentStatus.DONE;
        }
        if ("F".equals(yn) || (latestLog != null && LsDeidentProcLog.FAILED.equals(latestLog.getProcSttsCd()))) {
            return VideoSummaryResponse.DeidentStatus.FAILED;
        }
        if (latestLog != null && isDeidentInProgress(latestLog)) {
            return VideoSummaryResponse.DeidentStatus.IN_PROGRESS;
        }
        return VideoSummaryResponse.DeidentStatus.NONE;
    }

    /** 최신 procLog 가 진행 중인지: PROC_STTS=REQUESTED 또는 POLL_STTS=WAITING/POLLING. */
    private boolean isDeidentInProgress(LsDeidentProcLog log) {
        if (LsDeidentProcLog.REQUESTED.equals(log.getProcSttsCd())) {
            return true;
        }
        String poll = log.getPollSttsCd();
        return LsDeidentProcLog.POLL_WAITING.equals(poll) || LsDeidentProcLog.POLL_POLLING.equals(poll);
    }

    /**
     * 페이지 단위로 rawSn 들의 현재 활성 LABELER 배정을 batch 조회 (N+1 회피).
     *
     * <p>산출 기준은 작업 목록(TaskBoardService)과 100% 동일하다:
     * TASK_TYPE_CD='LABELER' 배정을 REG_DT DESC 정렬로 IN 절 1회 조회하고, rawDataId 별 첫 매칭
     * (= 가장 최근 REG_DT, putIfAbsent)만 현재 배정으로 채택한다 — 재배정 시 최신 배정자가 반영된다.
     * 배정자 이름은 userNo 집합에 대해 1회 IN 쿼리(findByUserNoIn)로 batch lookup 한다.
     *
     * <p>LABELER 배정이 없는 영상은 Map 에서 누락 → DTO 의 배정 필드는 모두 null.
     */
    private Map<Long, VideoSummaryResponse.AssignmentInfo> lookupCurrentAssignments(List<LsDataRaw> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> rawSns = rows.stream().map(LsDataRaw::getRawSn).toList();
        List<LsTaskAssignment> all = taskAssignmentRepository
                .findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(LsTaskAssignment.TASK_LABELER, rawSns);
        if (all.isEmpty()) {
            return Collections.emptyMap();
        }
        // rawDataId 별 가장 최근(REG_DT DESC 첫 매칭) 1건만 현재 배정으로 채택.
        Map<Long, LsTaskAssignment> latestByRaw = new HashMap<>();
        Set<Long> userNos = new HashSet<>();
        for (LsTaskAssignment a : all) {
            if (a.getRawDataId() == null) {
                continue;
            }
            if (latestByRaw.putIfAbsent(a.getRawDataId(), a) == null && a.getUserNo() != null) {
                userNos.add(a.getUserNo());
            }
        }
        UserNameResolver.UserNames names = userNameResolver.resolveAllByNo(userNos);
        Map<Long, VideoSummaryResponse.AssignmentInfo> map = new HashMap<>();
        for (Map.Entry<Long, LsTaskAssignment> entry : latestByRaw.entrySet()) {
            LsTaskAssignment a = entry.getValue();
            String workerName = names.nameOf(a.getUserNo());
            map.put(entry.getKey(), new VideoSummaryResponse.AssignmentInfo(
                    a.getAssignmentId(), a.getUserNo(), workerName, a.getRegDt()));
        }
        return map;
    }

    /**
     * 페이지 단위로 rawSn 들의 검수 완료 시각을 한 번에 조회 (N+1 회피).
     *
     * <p>LS_RAW_DATA_STATUS row 가 있고 dataSttsCd='APPROVED' 인 영상만 매핑한다.
     * 그 외 상태(PENDING/ASSIGNED/IN_REVIEW/REJECTED)나 row 부재 시 Map 에서 누락 →
     * DTO 의 reviewCompletedAt 은 null (정확성 — "검수 완료 일시"의 의미 보존).
     *
     * <p><b>한계</b>: 원천 {@code UPD_DT} 는 엄밀히 "그 상태 행의 마지막 수정 시각"이라 승인 후 같은
     * 행이 또 갱신되면 승인 시각과 어긋날 수 있다(별도 승인일시 컬럼 신설은 스코프 밖). 정렬 키
     * {@code reviewCompletedAt} 도 같은 값을 쓰므로 표시와 정렬의 원천은 항상 일치한다.
     */
    private Map<Long, LocalDateTime> lookupReviewCompletedAt(List<LsDataRaw> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> rawSns = rows.stream().map(LsDataRaw::getRawSn).toList();
        List<LsRawDataStatus> statuses = rawDataStatusRepository.findByRawDataIdIn(rawSns);
        Map<Long, LocalDateTime> map = new HashMap<>();
        for (LsRawDataStatus s : statuses) {
            if (LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd())) {
                map.put(s.getRawDataId(), s.getUpdDt());
            }
        }
        return map;
    }

    /**
     * 이 요청이 {@code LS_RAW_DATA_STATUS} 조인 쿼리를 타는지 — 즉 검수 완료 시각 정렬
     * ({@code reviewCompletedAt} → {@code s.updDt})을 쓸 수 있는 호출인지 판정한다.
     *
     * <p>정렬 allowlist 선택({@code VideoController.safeSort})의 단일 판정이다.
     *
     * <p><b>현재는 통합 쿼리가 조인을 항상 걸고 있어</b>({@code VideoRepository.searchOriginals} 의
     * {@code LEFT JOIN LsRawDataStatus s}) alias 자체는 언제나 유효하다. 그럼에도 이 판정을 유지하는
     * 이유는 <b>기존 정렬 계약을 그대로 두기 위함</b>이다 — 검수 상태 필터 없이 {@code reviewCompletedAt}
     * 정렬을 허용하면 "검수 완료 시각이 없는 영상"이 정렬 축에 섞여 결과 순서가 바뀐다(포털 영상 목록도
     * {@code SortAllowlist#VIDEO} 를 공유한다). 조건은 여기 한 곳에만 두고 복제하지 않는다.
     */
    public static boolean usesReviewStatusJoin(String reviewStatusCd) {
        return normalizeReviewStatusCd(reviewStatusCd) != null;
    }

    /**
     * 검수 상태 필터 정규화 — null/blank → null, 길이 상한 초과 → 매칭되지 않을 sentinel.
     *
     * <p>길이 상한 초과 (예: SQL injection 시도 페이로드) 시 예외를 던지지 않고
     * 매칭되지 않는 sentinel 값으로 변환해 빈 페이지를 반환한다 (정보 노출 회피 + 보안 fail-secure).
     */
    private static String normalizeReviewStatusCd(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > REVIEW_STATUS_MAX_LEN) {
            return "__INVALID_REVIEW_STATUS__";
        }
        return trimmed;
    }

    private static String trimToNull(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 검색어 → 대소문자 무시 부분일치 LIKE 패턴.
     *
     * <p>LIKE 메타문자({@code %}, {@code _})와 이스케이프 문자 자신({@code !})을 이스케이프한다 —
     * 하지 않으면 사용자가 {@code %} 한 글자로 <b>전체 목록</b>을 끌어오거나 {@code _} 로 의도치 않은
     * 광범위 매칭이 된다(오검색 + 자원 소모). 값 자체는 파라미터 바인딩으로만 전달된다(CWE-89).
     */
    private static String likePattern(String raw) {
        String lowered = raw.toLowerCase(java.util.Locale.ROOT);
        StringBuilder escaped = new StringBuilder(lowered.length() + 8);
        for (int i = 0; i < lowered.length(); i++) {
            char ch = lowered.charAt(i);
            if (ch == LIKE_ESCAPE || ch == '%' || ch == '_') {
                escaped.append(LIKE_ESCAPE);
            }
            escaped.append(ch);
        }
        return "%" + escaped + "%";
    }

    /**
     * 검색어가 숫자면 영상 ID(rawSn)로도 해석한다 — FE 라벨이 "CCTV명 / 영상ID" 이기 때문.
     * 숫자가 아니면 null 이며, 이때 쿼리의 {@code v.rawSn = :keywordRawSn} 은 UNKNOWN 이 되어
     * OR 결과에 영향을 주지 않는다(= CCTV 명만 본다).
     */
    private static Long parseRawSn(String keyword) {
        if (keyword == null || !NUMERIC_KEYWORD.matcher(keyword).matches()) {
            return null;
        }
        return Long.valueOf(keyword);
    }

    /**
     * 이벤트 카테고리 키 → 비교 대상 EV-코드 집합.
     *
     * <p>반환값 규약: <b>null = 필터 미적용</b>, 비어 있지 않은 컬렉션 = 그 코드들만 매칭.
     * 미등록/과대길이 카테고리 키는 예외가 아니라 {@link #NO_EVENT_MATCH} sentinel 로 <b>0건</b>을
     * 만든다 — 목록 조회가 잘못된 코드 하나로 500 이 되면 북마크·뒤로가기 진입이 통째로 죽는다.
     * 영상이 보유한 EV-코드가 관제 마스터에 없으면 어떤 카테고리 집합에도 속하지 않아 자동 제외된다.
     */
    private Collection<String> resolveEventCodes(String eventTypeCd) {
        String categoryKey = trimToNull(eventTypeCd);
        if (categoryKey == null) {
            return null;
        }
        if (categoryKey.length() > EVENT_TYPE_MAX_LEN) {
            return NO_EVENT_MATCH;
        }
        Set<String> codes = eventTypeService.codesForFilterKey(categoryKey);
        return codes.isEmpty() ? NO_EVENT_MATCH : codes;
    }

    /**
     * 촬영기간 시작 경계 — 해당일 {@code 00:00:00} 부터 <b>포함</b>. from &gt; to 역전 입력은 400 이다.
     *
     * <p>빈 결과로 두지 않는 이유: 역전 입력의 빈 목록은 "검색 결과 없음"과 구분되지 않아 사용자가
     * 입력 오류를 알 수 없다. 두 파라미터 모두 <b>이번에 신설</b>된 optional 파라미터라 400 으로
     * 거부해도 파손될 기존 계약이 없다(정렬 키의 strict/lenient 판단 기준 "변경 전에 200 이었는가"에서,
     * 이 파라미터는 변경 전에 <b>존재하지 않았다</b>).
     */
    private static LocalDateTime rangeStart(java.time.LocalDate from, java.time.LocalDate to) {
        if (from == null) {
            return null;
        }
        if (to != null && from.isAfter(to)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "시작일은 종료일보다 늦을 수 없습니다.");
        }
        return from.atStartOfDay();
    }

    /** 촬영기간 종료 경계 — 해당일 마지막 순간까지 <b>포함</b>(FE 는 날짜만 보내므로 종일 포함이 기대값). */
    private static LocalDateTime rangeEnd(java.time.LocalDate to) {
        return to == null ? null : to.atTime(java.time.LocalTime.MAX);
    }

    /**
     * 페이지 단위로 rawSn 들의 최신 export 요약을 한 번의 native 쿼리로 조회 (N+1 회피).
     * 매핑된 export 가 없으면 해당 rawSn 은 Map 에서 누락 → DTO 의 export 필드는 null.
     */
    private Map<Long, VideoSummaryResponse.ExportInfo> lookupExportInfos(List<LsDataRaw> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> rawSns = rows.stream().map(LsDataRaw::getRawSn).toList();
        List<VideoExportProjection> projections = videoRepository.findLatestExportsByRawSns(rawSns);
        return projections.stream().collect(Collectors.toMap(
                VideoExportProjection::getRawSn,
                p -> new VideoSummaryResponse.ExportInfo(
                        p.getExportSttsCd(),
                        p.getExportedAt(),
                        p.getErrorMessage()
                ),
                // 안전망: 동일 rawSn 중복 시 첫 값 유지 (쿼리상 보장되지만 방어적 병합).
                (a, b) -> a
        ));
    }

    public VideoDetailResponse getOne(Long rawSn) {
        LsDataRaw entity = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
        // CCTV 명은 관제 인입 평면값에서 온다(구 MNG_RESOURCE_CCTV 조인은 V167 로 제거).
        //   파생영상은 자기 인입 행이 없지만 IngestSourceLink 의 ORGNL_RAW_SN 1단계 폴백이
        //   부모 인입 행을 보므로 상세 화면에서도 이름이 그대로 표시된다.
        IngestSourceRow sourceMeta = ingestSourceRepository.findSourceMeta(entity.getRawSn());
        String cctvName = sourceMeta == null ? null : sourceMeta.getCctvNm();
        long frameCount = srcRepository.countByRawSn(entity.getRawSn());
        List<VideoDetailResponse.FramePreviewDto> framePreviews = srcRepository
                .findByRawSnOrderByFrameNoAsc(entity.getRawSn())
                .stream()
                .map(src -> new VideoDetailResponse.FramePreviewDto(
                        src.getSrcSn(),
                        Math.toIntExact(src.getFrameNo()),
                        "/v1/frames/" + src.getSrcSn() + "/image"
                ))
                .toList();
        // 검수 상태(reviewSttsCd) = LS_RAW_DATA_STATUS.DATA_STTS_CD (진실원).
        // status(=배치단계 LS_DATA_RAW.DATA_STTS_CD)와 출처가 다르므로 별도 조회해 노출한다.
        // 상태 row 가 없으면 미검수로 간주(null). ReviewApprovalGate.isApproved 와 동일 조회 패턴이나,
        //   이 메서드는 boolean 이 아니라 상태 코드 문자열 자체가 필요해 게이트를 재사용하지 않는다.
        String reviewSttsCd = rawDataStatusRepository.findByRawDataIdIn(List.of(entity.getRawSn())).stream()
                .findFirst()
                .map(LsRawDataStatus::getDataSttsCd)
                .orElse(null);
        // 배치 파이프라인 단계별 진행 상태(이슈1). 최신 LS_BATCH_PROC_LOG 1행 기준으로 canonical 순서 구성.
        // 로그 없으면 빈 리스트 → FE 배지 폴백(하위호환). 단계 코드/상태만 노출(PII/경로/스택 없음).
        boolean videoCompleted = LsDataRaw.DATA_STTS_COMPLETED.equals(entity.getDataSttsCd());
        List<VideoDetailResponse.StageStatusDto> stages = batchStatusService
                .stagesFor(entity.getRawSn(), videoCompleted)
                .stream()
                .map(s -> new VideoDetailResponse.StageStatusDto(s.name(), s.status(), s.progress()))
                .toList();
        // DEV_FIX(H10) — 마킹 화면이 frameIndex 를 서버와 동일한 fps 로 산출하도록 실 fps 를 함께 내린다.
        //   진실원은 MarkingService 상한 검증이 쓰는 것과 같은 VideoFpsResolver(미상 시 30.0 폴백).
        double fps = fpsResolver.resolveFps(entity.getRawSn());
        // P2b — 화면이 신고·폐기 버튼을 미리 비활성화하도록 <b>승인 이력</b>을 함께 내린다.
        //   reviewSttsCd(현재 상태)와 다른 축이다 — 재검수 재제출로 상태가 내려간 구간에도 true 다.
        //   판정은 ReviewApprovalGate 단일 원천에 위임한다(여기서 재유도하지 않는다).
        // [@design API-043] 배치 실패 사유 — 화면이 "왜 멈췄는지"를 알아야 재기동/스킵을 고를 수 있다.
        //   ★ stages 배열이 아니라 영상 단위 필드다: 단계 미상 실패(PROC_STEP_CD='FAILED')는 stages 가
        //     빈 배열이라 사유를 단계 안에 넣으면 그 영상이 아무것도 못 본다.
        //   ★ 내부 원문(ERR_MSG_CN)은 읽지 않는다 — 변환은 BatchFailureReasonPolicy 단일 지점(CWE-209).
        String batchFailureReason = batchStatusService.failureReasonFor(entity.getRawSn());
        // [@design API-043] 수동 스킵 <b>작업 묶음</b> 목록(VLM/AUTOLABEL) — 화면의 스킵 표시·되돌리기
        //   조작 노출 근거. 응답 필드명은 하위호환으로 skippedStages 를 유지하되 값은 묶음 코드다.
        //   ★ stages 로 대체 불가: 스킵된 묶음은 markStage 를 타지 않고 표식 행도 진행 조회에서 제외돼
        //     진행 축에 흔적이 없다. 판정은 BatchStatusService 단일 지점이며 여기서 재유도하지 않는다.
        List<String> skippedStages = batchStatusService.manuallySkippedBundles(entity.getRawSn());
        // [@design API-043] [@design ADR-050] 건너뛰기가 <b>해제된</b> 묶음 목록 — 위 목록의 뒷면이다.
        //   ★ 이 필드가 없으면 한 번 재수행한 영상을 화면에서 다시 재수행할 수 없다: 재수행이 건너뜀
        //     표식을 스스로 풀면서 해제 표식을 남겨 그 묶음이 skippedStages 에서 빠지기 때문이다.
        //     화면은 두 목록의 <b>합집합</b>으로 재수행 버튼 노출을 정한다.
        List<String> clearedStages = batchStatusService.clearedBundles(entity.getRawSn());
        // [@design API-043] [@design ADR-050] <b>지금 실패한 상태인</b> 묶음 목록 — 건너뛰기·재수행 입구.
        //   ★ status·stages 로는 대체 불가: 시계열 위탁은 논블로킹이라 실패해도 예외가 위로 올라가지
        //     않아 배치 상태가 완료로 남고 단계 실패 표시도 서지 않는다. 이 필드가 없으면 위탁이 확정
        //     실패한 영상에서 화면이 조작 버튼을 띄울 근거가 전혀 없다.
        //   ★ 판정은 건너뛰기 허용을 정하는 서버 판정과 <b>같은 지점</b>(BatchBundleFailureGate)이다 —
        //     여기서 규칙을 재유도하면 화면에 뜬 버튼이 눌렀을 때 412 로 튕긴다.
        List<String> failedStages = bundleFailureGate.failedBundles(entity.getRawSn());
        // [@design API-043] [@design ERD-033] 검증 이벤트 유형 + 그 유형의 질문 목록.
        //   ★ 마킹 화면이 고를 목록을 얻을 <b>유일한</b> 통로다 — 질문 카탈로그 관리 조회 경로는
        //     검수자 전용이라 마킹 작업자에게 403 이다. 경로를 새로 만들지 않고 이 응답에 싣는다.
        //   ★ 유형·질문이 없으면 예외가 아니라 「비어 있음」이다: 카탈로그는 허용목록이 아니고
        //     (확정 정책상 목록 밖 유형도 위탁은 그대로 나간다), 인입 행이 없는 영상은 유형 자체가 없다.
        String vrfcEvntTypeCd = LsDataIngest.normalizeVrfcEvntType(
                sourceMeta == null ? null : sourceMeta.getVrfcEvntTypeCd());
        List<VideoDetailResponse.VrfcEvntQuestionDto> vrfcEvntQuestions =
                verificationEventQuestions(vrfcEvntTypeCd);
        return VideoDetailResponse.from(entity, cctvName, null, frameCount, framePreviews, reviewSttsCd,
                stages, fps, deidentHistory(entity.getRawSn()),
                approvalGate.hasEverApproved(entity.getRawSn()), batchFailureReason,
                skippedStages, clearedStages, failedStages, vrfcEvntTypeCd, vrfcEvntQuestions);
    }

    /**
     * 그 검증 이벤트 유형에 등록된 질문 목록 — <b>정렬순서 오름차순</b>. [@design API-043] [@design ERD-033]
     *
     * <p>정렬은 저장소 메서드 이름이 갖는다({@code ...OrderBySortSeqAsc}). 여기서 다시 정렬하거나
     * 「첫 번째」를 해석하지 않는다 — 그 해석의 단일 진실원은
     * {@code VerificationEventQuestionResolver} 이며 사본을 두면 화면이 보여준 질문과 산출물에 실린
     * 질문이 조용히 어긋난다.
     *
     * <p>유형이 {@code null}(관제 미송신·인입 행 없는 파생영상)이거나 등록된 질문이 0건이면
     * <b>빈 목록</b>이다 — 예외를 던지지 않는다.
     *
     * @param normalizedTypeCd {@code LsDataIngest.normalizeVrfcEvntType} 를 통과한 유형 코드
     */
    private List<VideoDetailResponse.VrfcEvntQuestionDto> verificationEventQuestions(String normalizedTypeCd) {
        if (normalizedTypeCd == null) {
            return Collections.emptyList();
        }
        return vrfcEvntQstnRepository.findByVrfcEvntTypeCdOrderBySortSeqAsc(normalizedTypeCd).stream()
                .map(q -> new VideoDetailResponse.VrfcEvntQuestionDto(q.getVrfcEvntQstnSn(), q.getQstnCn()))
                .toList();
    }

    /**
     * 비식별 이력 — {@code LS_DEIDENT_PROC_LOG} 의 회차 행들을 최신순으로 옮긴다. [req: R14]
     *
     * <p>이 테이블은 위탁 회차마다 새 행을 INSERT 하므로(최초 배치 비식별 + 재비식별 재위탁) 그 행들이
     * 그대로 이력이다 — 별도 이력 테이블을 두지 않는다.
     *
     * <p><b>정렬 2차 키</b>: 저장소 메서드는 {@code REQ_DT DESC} 뿐이라 같은 시각에 들어온 회차의
     * 순서가 흔들린다. 이 저장소의 관례(뷰·{@code findSuccessHistory} 와 동일)대로
     * {@code PROC_LOG_SN DESC}(IDENTITY 증가라 결정적)를 2차 키로 덧붙인다. 건수가 적어 메모리 정렬로
     * 충분하며 새 쿼리를 만들지 않는다.
     *
     * <p><b>상한</b>: 위탁 실패가 누적되면 한 영상의 행이 계속 늘 수 있으므로 응답 건수를 제한한다
     * (CWE-770). 상세 화면이 보여줄 수 있는 양을 넘는 이력은 화면의 관심사가 아니다.
     */
    private List<VideoDetailResponse.DeidentHistoryDto> deidentHistory(Long rawSn) {
        return deidentProcLogRepository.findAllByDataRawSnOrderByReqDtDesc(rawSn).stream()
                .sorted(java.util.Comparator
                        .comparing(LsDeidentProcLog::getReqDt,
                                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                        .thenComparing(LsDeidentProcLog::getProcLogSn,
                                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .limit(DEIDENT_HISTORY_MAX)
                .map(p -> new VideoDetailResponse.DeidentHistoryDto(
                        p.getProcLogSn(),
                        p.getProcSttsCd(),
                        p.getReqKindCd(),
                        p.getReqDt(),
                        p.getResDt(),
                        p.getFaceDtctCnt(),
                        p.getNoPltDtctCnt(),
                        p.getFrmeCnt(),
                        p.getPrcsBgngDt(),
                        p.getPrcsEndDt()))
                .toList();
    }

    /**
     * 영상별 오토라벨 결과 조회 — FE FrameLabels 매핑.
     * rawSn 영상이 없거나 라벨이 없으면 빈 objects 반환 (404 던지지 않음).
     *
     * <p>auto/manual 구분과 신뢰도는 {@code LS_DATA_LBL} <b>본체 컬럼</b>이다(V6 흡수 — 구
     * {@code LS_DATA_LBL_AI_INFO} 조인 없음). {@code AUTO_LBL_YN='Y'} 인 라벨은
     * createdBy='auto' + 실제 conf_score, 그 외는 'manual' 로 매핑한다(매핑 규칙 자체는 불변).
     */
    public AutoLabelResultResponse getAutoLabels(Long rawSn) {
        videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "rawSn=" + rawSn));
        List<AutoLabelInfoProjection> labels = lblRepository.findAutoLabelInfoByRawSn(rawSn);
        List<AutoLabelResultResponse.LabelObjectDto> objects = labels.stream()
                .map(l -> {
                    boolean isAuto = LsDataLbl.AUTO_YES.equals(l.getAutoLblYn());
                    return new AutoLabelResultResponse.LabelObjectDto(
                            String.valueOf(l.getLblSn()),
                            l.getLabelNm(),
                            l.getLabelNm(),
                            DEFAULT_LABEL_COLOR,
                            l.getConfScore() == null ? null : l.getConfScore().doubleValue(),
                            isAuto ? "auto" : "manual"
                    );
                })
                .toList();
        return new AutoLabelResultResponse(rawSn, objects);
    }

    /**
     * 페이지 단위로 CCTV 명을 단일 쿼리로 batch 조회해 N+1 회피 — 목록·작업목록·검수목록이 공유하는
     * {@link VideoRepository#findCctvNamesByRawSns} 를 그대로 쓴다.
     *
     * <p><b>키가 {@code VMS_CCTV_ID} 가 아니라 {@code RAW_SN} 인 이유</b>: 소스가 관제 인입 평면값
     * ({@code LS_DATA_INGEST.CCTV_NM})으로 바뀌면서 이름이 <b>CCTV 단위가 아니라 영상(수신) 단위</b>가
     * 됐다. CCTV 단위로 키를 잡으면 같은 CCTV 의 서로 다른 수신 건이 한 이름으로 뭉개진다.
     *
     * <p>CCTV 명이 null 이어도 그대로 map 에 담는다 — DTO 변환 시 cctvName 이 null/blank 이면
     * vmsCctvId 로 폴백하므로 기존 동작과 동일하다. 인입 행이 없는 환경에서는 빈 값이 담긴다.
     */
    private Map<Long, String> lookupCctvNames(List<LsDataRaw> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> rawSns = rows.stream().map(LsDataRaw::getRawSn).toList();
        Map<Long, String> map = new HashMap<>();
        for (Object[] row : videoRepository.findCctvNamesByRawSns(rawSns)) {
            map.put(((Number) row[0]).longValue(), (String) row[1]);
        }
        return map;
    }
}
