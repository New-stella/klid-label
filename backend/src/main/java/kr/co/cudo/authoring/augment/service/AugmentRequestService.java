package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.augment.dto.AugmentRequestRequest;
import kr.co.cudo.authoring.augment.dto.AugmentRequestResponse;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.event.AugmentRequestedItemEvent;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V1.5 SFR-07 — 외부 증강 시스템 요청 서비스 (콜백 충실 플로우 Phase 1).
 *
 * <p>저작도구는 검수 완료된 영상(LsRawDataStatus.dataSttsCd='APPROVED')만 증강 요청 가능.
 * 요청 1건에 대해 다음 선행 상태를 만들어 콜백이 성립하게 한다:
 *
 * <ol>
 *   <li>영상의 대표 프레임(MIN SRC_SN) 조회</li>
 *   <li>idempotencyKey(^[A-Za-z0-9_-]+$, ≤64) 를 먼저 발급
 *       (externalJobId 는 발급하지 않는다 — 외부가 202 응답으로 준다, Phase 7-A1)</li>
 *   <li>{@link LsDataAug#createRequested} 로 키를 실은 PENDING 행을 <b>단일 save</b> 적재 → originAugSn</li>
 *   <li>{@link AugmentRequestedItemEvent} 발행 (외부 위탁은 커밋 이후로 위임)</li>
 * </ol>
 *
 * <h3>단건 계약이 정본이다 (E-ISSUE-08)</h3>
 * <p>한 요청 = <b>영상 1건 × 종류 1개</b>. API 계약은 {@code AugmentRequestRequest} 의
 * {@code @NotEmpty + @Size(max=1)} 이고, 서비스도 같은 규칙을 fail-closed 로 재확인한다
 * ({@code requireSingleSelection}). 과거에는 여기에 distinct 정규화 + (영상 × 종류) 이중 루프가
 * 남아 있었지만 DTO 가 길이 1 만 허용하므로 <b>실행될 수 없는 사문 코드</b>였고, 그 전제 위에 쓰인
 * 테스트(중복 입력 distinct 등)도 계약을 잘못 고정하고 있었다. 다건 재허용이 필요해지면 DTO 계약
 * 변경 → 서비스 → 테스트 순으로 <b>의도적으로</b> 열어야 한다.
 *
 * <h3>생성 0건은 성공이 아니다 (E-ISSUE-09)</h3>
 * <p>프레임 미추출 영상은 위탁 입력({@code input_files})을 만들 수 없다. 구 구현은 그런 영상을
 * 조용히 스킵하고 요청 개수를 그대로 담아 200 을 돌려줘, REVIEWER 는 아무것도 접수되지 않았는데
 * 접수된 줄 알았다. 지금은 <b>412(PRECONDITION_FAILED)</b> 로 종결하고 어떤 영상이 막혔는지
 * ({@code skippedVideoIds}) 알린다. 응답은 요청 echo 가 아니라 실제 생성 수
 * ({@code createdCount})를 담는다.
 *
 * <p><b>고아 위탁 방지 (DEV_FIX HIGH #1)</b>: 외부 위탁({@code AugmentJobSubmitService.submit})은
 * 요청 트랜잭션 안에서 하지 않고, {@code AugmentRequestBridge} 가
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 로 수신해 <b>커밋 확정 후</b>에만 수행한다.
 * 요청 트랜잭션이 롤백되면 aug 행도 위탁도 발생하지 않는다.
 *
 * <p>RBAC: REVIEWER 만 호출 가능 (Service 이중 검증 + Controller @PreAuthorize).
 * <p>트랜잭션: 클래스 기본 readOnly, {@link #request} 만 write override (aug 행 INSERT).
 * <p>실패 격리: 적재 실패를 <b>삼키지 않는다</b> — 사유를 로그로 남기고 호출자에게 실패로 회신한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class AugmentRequestService {

    /** 멱등 키 형식 — 콜백 회신 시 동일 키로 매칭되므로 발급 시점에 강제 (CWE-20). */
    private static final java.util.regex.Pattern IDEMPOTENCY_KEY_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final int IDEMPOTENCY_KEY_MAX = 64;
    /**
     * 콜백 경로 — 단일 진실원
     * ({@link kr.co.cudo.authoring.common.security.webhook.WebhookProtectedPaths#PATH_GENAI_CALLBACK}) 참조.
     *
     * <p>Phase 7-A1: 「생성형 AI API 연동명세서 v1.1」 웹훅 경로로 교체했다. 수신 컨트롤러는 A2 에서
     * 같은 상수를 참조해 추가한다(구 {@code /v1/aug/callback} 는 A2 에서 정리).
     */
    public static final String CALLBACK_PATH = AugmentCallbackUrlResolver.CALLBACK_PATH;

    private final LsRawDataStatusRepository statusRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsDataAugRepository augRepository;
    private final ApplicationEventPublisher eventPublisher;
    /** 비식별 누락 신고 구간 판정 — 단일 원천(자체 재구현 금지). */
    private final DeidentReportGate deidentReportGate;
    /** 콜백 URL 조립 — 요청 경로와 재개 경로가 같은 값을 만들게 하는 단일 원천. */
    private final AugmentCallbackUrlResolver callbackUrlResolver;

    /**
     * placeholder jobId 시퀀스 — 외부 SFR-07 연동 전까지 응답 jobId 발급에 사용.
     * 동시성 안전을 위해 AtomicLong 사용. base 는 인스턴스 시작 시각(ms) 으로 충돌 회피.
     */
    private final AtomicLong jobIdSeq = new AtomicLong(System.currentTimeMillis());

    /**
     * 외부 SFR-07 증강 시스템에 검수 완료 영상 + 증강 유형을 요청한다.
     *
     * @param request 영상 1건 + 종류 1개 (DTO 단계 형식 검증 통과)
     * @param actor   호출자 토큰 (REVIEWER 만 허용)
     * @return jobId / 요청 시각 / 요청 수 / <b>실제 생성 수</b>
     * @throws CustomException FORBIDDEN(WORKER 등), INVALID_INPUT(단건 계약 위반),
     *                         NOT_REVIEWED(미검수), PRECONDITION_FAILED(신고 구간·프레임 미추출),
     *                         INTERNAL_ERROR(적재 실패 — 생성 0건)
     */
    @Transactional("controlTransactionManager")
    public AugmentRequestResponse request(AugmentRequestRequest request, TokenClaims actor) {
        requireReviewer(actor);

        // 1) 단건 계약 강제 (E-ISSUE-08) — DTO @Size(max=1) 과 같은 규칙을 서비스에서도 fail-closed 로
        //    확인한다. 초과분을 조용히 잘라 첫 건만 처리하면 요청자는 나머지도 접수된 줄 안다.
        Long rawSn = requireSingleSelection(request.videoIds(),
                "영상은 한 번에 1건만 증강 요청할 수 있습니다.");
        String augType = requireSingleSelection(request.types(),
                "증강 종류는 한 번에 1개만 선택할 수 있습니다.").name();
        List<Long> videoIds = List.of(rawSn);

        // 2) 검수 완료(APPROVED) 검증 — 미검수면 거부
        List<Long> blocked = findBlockedVideoIds(videoIds);
        if (!blocked.isEmpty()) {
            log.info("[Augment] request blocked — not reviewed actor={} blockedCount={}",
                    sanitize(actor.sub()), blocked.size());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("blockedVideoIds", blocked);
            throw new CustomException(
                    ErrorCode.NOT_REVIEWED,
                    ErrorCode.NOT_REVIEWED.defaultMessage(),
                    details);
        }

        // 2-1) 비식별 누락 신고 구간 차단 (DEV_FIX HIGH-2) — 신고된 영상은 아예 접수하지 않는다.
        //      실제 <b>전송</b> 차단은 AugmentJobSubmitService.submit(전송 진입점 단일 fail-closed)이
        //      담당하며, 여기서의 조기 거부는 ①요청자에게 즉시 사유를 알리고 ②고착될 PENDING 행을
        //      애초에 만들지 않기 위한 것이다(보류이므로 해소 후 재요청하면 정상 진행된다).
        List<Long> underReport = videoIds.stream()
                .filter(deidentReportGate::isUnderDeidentReport)
                .toList();
        if (!underReport.isEmpty()) {
            log.warn("[Augment] request blocked — deident report open actor={} blockedCount={}",
                    sanitize(actor.sub()), underReport.size());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("blockedVideoIds", underReport);
            throw new CustomException(ErrorCode.PRECONDITION_FAILED,
                    "비식별 재처리 대기 중인 영상은 증강을 요청할 수 없습니다.", details);
        }

        // 3) 대표 프레임(MIN SRC_SN) 조회 — 없으면 위탁 입력이 성립하지 않는다(E-ISSUE-09).
        Long representativeSrcSn = findFirstSrcSn(rawSn);
        if (representativeSrcSn == null) {
            // 구 구현은 이 영상을 조용히 스킵하고 200 을 돌려줘, 생성 0건인데 접수된 것처럼 보였다.
            log.warn("[Augment] request blocked — no frame extracted actor={} rawSn={}",
                    sanitize(actor.sub()), rawSn);
            throw new CustomException(ErrorCode.PRECONDITION_FAILED,
                    "프레임이 추출되지 않은 영상은 증강을 요청할 수 없습니다.",
                    skippedDetails(rawSn));
        }

        // 3-1) 중복 증강 요청 차단 — 같은 (원본 × 종류)를 반복/동시 요청하면 콜백마다 새 파생 RAW 가
        //      생기고(AugmentResultService.createAugmentedVideo) 반려해도 그 파생 RAW 행은 남는다.
        //      요청 1회 = 파생영상 1건이라는 계약을 입구에서 지킨다(파생 트리·저장소·검수 큐 오염 방지).
        rejectDuplicateActiveRequests(
                videoIds, List.of(augType), Map.of(rawSn, representativeSrcSn), actor);

        // 4) PENDING 적재 + 멱등 키 발급 + 콜백 컨텍스트 전달.
        //    실패는 삼키지 않는다 — 사유를 남기고(로그) 호출자에게 실패로 회신한다(E-ISSUE-09).
        String regUserNo = actor.sub();
        String callbackUrl = callbackUrlResolver.resolve();
        boolean created = createOneAugmentRequest(
                rawSn, representativeSrcSn, augType, regUserNo, callbackUrl);
        if (!created) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR,
                    "증강 요청을 생성하지 못했습니다.", skippedDetails(rawSn));
        }

        long jobId = jobIdSeq.incrementAndGet();
        LocalDateTime requestedAt = LocalDateTime.now();
        log.info("[Augment] requested jobId={} actor={} rawSn={} augType={} createdAugCount=1",
                jobId, sanitize(regUserNo), rawSn, sanitize(augType));
        return new AugmentRequestResponse(jobId, requestedAt, 1, 1, 1);
    }

    /**
     * 중복 증강 요청 1선 가드 — 이미 <b>활성</b>({@link LsDataAug#ACTIVE_STATUSES} = PENDING·ACCEPTED)
     * 인 (대표프레임 × 종류)를 다시 요청하면 409 로 거부한다.
     *
     * <p>"활성"의 정의는 {@code LsDataAug.ACTIVE_STATUSES} 단일 원천이며 DB 부분 유니크 인덱스
     * {@code UK_LS_DATA_AUG_ACTVTN}(V143)의 술어와 일치한다. REJECTED(반려·종결)는 제외 —
     * 반려 후 재요청은 정당한 운영 동선이다.
     *
     * <p>이 조회는 <b>1선</b>일 뿐이다: 서로의 미커밋 행을 보지 못하는 동시 요청은 여기서 전부 통과하며,
     * 실제 직렬화는 DB 인덱스가 한다({@link #createOneAugmentRequest} 의 제약 위반 처리 참조).
     *
     * <h3>안내 문구는 <b>실제로 수행 가능한 동선</b>만 말한다 (2026-07-29 정정)</h3>
     * 구 문구는 상태와 무관하게 "반려 후 다시 요청하세요" 였는데, {@code ACCEPTED} 는
     * {@code LsDataAug.applyReviewStatus} 가 {@code PENDING} 에서만 전이를 허용하므로 <b>반려로 갈 수
     * 없다</b> — 사용자가 따라 할 수 없는 안내였다. 그래서 중복 건의 실제 상태로 갈라 말한다.
     * <ul>
     *   <li>{@code PENDING}(진행 중) — 결과가 도착해 채택/반려로 종결되거나, 검수 화면에서 REVIEWER 가
     *       반려하면 같은 종류를 다시 요청할 수 있다.</li>
     *   <li>{@code ACCEPTED}(채택 완료) — <b>종결 상태라 되돌릴 수 없다</b>. 같은 (영상 × 종류) 증강은
     *       더 만들지 않는다는 뜻이므로, "기다리면 된다"고 오해하지 않도록 그 사실을 그대로 알린다.</li>
     * </ul>
     */
    private void rejectDuplicateActiveRequests(List<Long> videoIds, List<String> types,
                                               Map<Long, Long> firstSrcSnByRawSn, TokenClaims actor) {
        List<Long> srcSns = videoIds.stream()
                .map(firstSrcSnByRawSn::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (srcSns.isEmpty() || types.isEmpty()) {
            return;
        }
        // (srcSn|augType) → 현재 활성 상태. 상태를 함께 들고 있어야 수행 가능한 안내 문구를 만들 수 있다.
        Map<String, String> activeStatusByKey = new LinkedHashMap<>();
        for (LsDataAug aug : augRepository.findBySrcSnInAndAugProcSttsCdIn(
                srcSns, LsDataAug.ACTIVE_STATUSES)) {
            activeStatusByKey.put(aug.getSrcSn() + "|" + aug.getAugTypeCd(), aug.getAugProcSttsCd());
        }
        if (activeStatusByKey.isEmpty()) {
            return;
        }
        List<Map<String, Object>> duplicated = new java.util.ArrayList<>();
        boolean anyAccepted = false;
        boolean anyPending = false;
        for (Long rawSn : videoIds) {
            Long srcSn = firstSrcSnByRawSn.get(rawSn);
            if (srcSn == null) {
                continue;
            }
            for (String augType : types) {
                String status = activeStatusByKey.get(srcSn + "|" + augType);
                if (status == null) {
                    continue;
                }
                anyAccepted |= LsDataAug.STTS_ACCEPTED.equals(status);
                anyPending |= LsDataAug.STTS_PENDING.equals(status);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("videoId", rawSn);
                item.put("type", augType);
                item.put("status", status);
                duplicated.add(item);
            }
        }
        if (duplicated.isEmpty()) {
            return;
        }
        log.warn("[Augment] request blocked — duplicate active augment actor={} duplicatedCount={}",
                sanitize(actor.sub()), duplicated.size());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("duplicatedRequests", duplicated);
        throw new CustomException(ErrorCode.CONFLICT,
                duplicateGuidance(anyAccepted, anyPending), details);
    }

    /** 중복 상태별 안내 문구 — 채택(되돌릴 수 없음) / 진행 중(종결 후 재요청 가능) 구분. */
    private static String duplicateGuidance(boolean anyAccepted, boolean anyPending) {
        if (anyAccepted && anyPending) {
            return "이미 채택되었거나 진행 중인 증강이 포함되어 있습니다. "
                    + "채택된 증강은 되돌릴 수 없어 같은 영상·종류로 다시 요청할 수 없고, "
                    + "진행 중인 요청은 완료되거나 검수에서 반려된 뒤 다시 요청할 수 있습니다.";
        }
        if (anyAccepted) {
            return "이미 채택된 증강입니다. 채택된 증강은 되돌릴 수 없어 "
                    + "같은 영상·종류로는 다시 요청할 수 없습니다.";
        }
        return "이미 요청되어 진행 중인 증강입니다. "
                + "결과가 도착해 완료되거나 검수에서 반려된 뒤 다시 요청하세요.";
    }

    /**
     * 단건 계약(E-ISSUE-08) 확인 — 정확히 1건만 허용한다.
     *
     * <p>DTO 의 {@code @NotEmpty + @Size(max=1)} 이 API 계약의 정본이지만, 그 검증은 컨트롤러
     * 진입에만 적용된다. 서비스가 초과분을 조용히 자르면 "요청은 2건인데 1건만 접수" 라는 은폐된
     * 부분 처리가 생기므로 여기서도 fail-closed 로 거부한다(CWE-20).
     */
    private static <T> T requireSingleSelection(List<T> values, String message) {
        if (values == null || values.size() != 1 || values.get(0) == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, message);
        }
        return values.get(0);
    }

    /** 생성되지 못한 영상 식별자 — 호출자(관리 화면)가 어떤 영상이 막혔는지 알 수 있게 한다. */
    private static Map<String, Object> skippedDetails(Long rawSn) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("skippedVideoIds", List.of(rawSn));
        return details;
    }

    /**
     * (대표 프레임 × 종류) 증강 요청 처리 — 키 발급 → 키를 실은 PENDING 행 단일 save →
     * AFTER_COMMIT 이벤트 발행. 외부 위탁은 커밋 이후로 위임된다.
     *
     * <p><b>실패 격리</b>: 예외를 <b>삼키지 않고</b> 사유를 로그로 남긴 뒤 {@code false} 를 반환한다.
     * 호출자는 생성 0건을 성공으로 회신하지 않는다(E-ISSUE-09). 예외 원문은 응답으로 나가지 않는다.
     *
     * @return PENDING 행 생성 성공 여부
     */
    private boolean createOneAugmentRequest(Long rawSn, Long srcSn, String augType,
                                            String regUserNo, String callbackUrl) {
        try {
            // 1) idempotencyKey 를 먼저 발급 (UUID 기반 — dataAugSn 비의존).
            //    externalJobId 는 발급하지 않는다 — 외부가 202 응답으로 발급하는 값이다(Phase 7-A1).
            String idempotencyKey = generateIdempotencyKey();

            // 2) 키를 실은 PENDING 행을 단일 save 로 INSERT (이중 save / IDMP_KEY=null orphan 제거)
            LsDataAug aug = augRepository.save(
                    LsDataAug.createRequested(srcSn, augType, regUserNo, idempotencyKey, null));
            Long originAugSn = aug.getDataAugSn();

            // 3) 멱등 키 발급 + 외부 위탁은 요청 트랜잭션 커밋 이후로 위임 (고아 키 방지)
            eventPublisher.publishEvent(new AugmentRequestedItemEvent(
                    originAugSn, rawSn, augType, idempotencyKey, callbackUrl, regUserNo));
            return true;
        } catch (DataIntegrityViolationException e) {
            // 중복 증강 최종 방어 — 부분 유니크 인덱스 UK_LS_DATA_AUG_ACTVTN(V143) 위반.
            //
            // 여기서는 <b>건별 격리로 삼키지 않는다</b>: PostgreSQL 은 제약 위반이 나면 트랜잭션 전체를
            // abort 시켜 이후 모든 문장이 "current transaction is aborted" 로 실패한다. 삼키고 다음 건을
            // 계속 처리하면 그 사실이 커밋 시점에야 드러나 원인 추적이 불가능한 500 이 된다.
            // 요청 트랜잭션을 롤백시키고 409 로 마감한다(위 미검수/중복 검증과 동일한 부분 처리 금지).
            log.warn("[Augment] aug request rejected — active duplicate constraint srcSn={} augType={}",
                    srcSn, sanitize(augType));
            // 문구는 사전 조회 경로와 동일 원천(duplicateGuidance). 여기서는 동시 요청이 원인이라
            // 상대 건이 방금 만들어진 PENDING 이므로 "진행 중" 안내가 정확하다.
            throw new CustomException(ErrorCode.CONFLICT, duplicateGuidance(false, true));
        } catch (Exception e) {
            // 건별 격리 — 한 건 실패가 전체 요청을 깨지 않게 한다.
            log.warn("[Augment] aug request item failed (isolated) srcSn={} augType={} err={}",
                    srcSn, sanitize(augType), sanitize(e.getMessage()));
            return false;
        }
    }

    /** 멱등 키 발급 — UUID 기반 (^[A-Za-z0-9_-]+$, ≤64). 형식 위반 시 fail-closed. */
    private String generateIdempotencyKey() {
        String key = "AUG-" + UUID.randomUUID();
        if (key.length() > IDEMPOTENCY_KEY_MAX || !IDEMPOTENCY_KEY_PATTERN.matcher(key).matches()) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "idempotencyKey 발급 형식 오류");
        }
        return key;
    }


    /** 영상의 대표(첫) 프레임 SRC_SN. 프레임이 아직 추출되지 않았으면 {@code null}. */
    private Long findFirstSrcSn(Long rawSn) {
        for (Object[] row : srcRepository.findFirstSrcSnGroupedByRawSn(List.of(rawSn))) {
            if (rawSn.equals(row[0])) {
                return (Long) row[1];
            }
        }
        return null;
    }

    /**
     * 검수 미완료/미존재 영상 ID 목록 반환 (입력 순서 유지).
     */
    private List<Long> findBlockedVideoIds(List<Long> videoIds) {
        // APPROVED 상태인 raw data ID 집합 조회
        Set<Long> approved = new HashSet<>();
        statusRepository.findByRawDataIdIn(videoIds).forEach(s -> {
            if (LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd())) {
                approved.add(s.getRawDataId());
            }
        });
        // 차집합 — 입력 중 APPROVED 가 아닌 ID (row 자체가 없는 경우도 포함)
        return videoIds.stream()
                .filter(id -> !approved.contains(id))
                .toList();
    }

    private void requireReviewer(TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        if (actor.role() != Role.REVIEWER) {
            throw new CustomException(ErrorCode.FORBIDDEN, "REVIEWER 권한이 필요합니다.");
        }
    }

    /** Log Injection (CWE-117) 방어 — CR/LF 제거. */
    private static String sanitize(String value) {
        if (value == null) return null;
        return value.replace('\n', '_').replace('\r', '_');
    }
}
