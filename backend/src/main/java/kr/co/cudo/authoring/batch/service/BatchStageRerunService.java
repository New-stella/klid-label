package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.batch.dto.BatchStageRerunResponse;
import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import kr.co.cudo.authoring.batch.orchestrator.MetadataOnlyRerunPolicy;
import kr.co.cudo.authoring.batch.pipeline.BatchBundleTogglePolicy;
import kr.co.cudo.authoring.batch.runner.AsyncBatchReprocessRunner;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 건너뛰기를 해제한 <b>작업 묶음</b>의 지목 재수행 서비스 — REVIEWER 전용. [@design API-201]
 *
 * <h3>왜 전체 재기동과 분리했나 (데이터 파괴 차단)</h3>
 * <p>완주 영상을 전체 재기동에 태우면 파이프라인이 통째로 순회하는데, 트랙 보간
 * ({@code TrackInterpolationStep})은 {@code lblSrcCd='INTERPOLATE'} 라벨을 사람이 고쳤는지 보지 않고
 * 전량 삭제한 뒤 재생성한다. 완주 영상은 곧 작업자가 라벨링 중이거나 끝낸 영상이라, 삭제 이력도 승인
 * 스냅샷도 없는 <b>복구 지점 0</b> 의 파괴가 된다. 그래서 「문제가 생긴 곳부터 재시도한다」는 원칙에
 * 맞춰 <b>해제한 그 묶음을 지목</b>하는 요청으로 분리했다.
 *
 * <h3>★범위를 고르지 않는다 — 묶음이 곧 범위다 (구 {@code scope} 요청 필드 폐기)</h3>
 * <p>단위가 개별 단계였을 때는 요청이 「그 단계만 / 그 단계부터 끝까지」를 골라야 했다. 단위를 묶음으로
 * 바꾸면 그 선택이 사라진다 — 건너뛰기를 해제한 묶음만 수행하고 다른 묶음은 건드리지 않는다. 오토라벨 재수행이
 * 보간을 다시 만드는 것은 「오토라벨을 통째로 다시 만든다」의 <b>예상되는 결과</b>이며, 화면이 고르는
 * 시점에 알린다.
 *
 * <h3>★대상 묶음을 요청이 자유롭게 고르지 못한다 (Critical)</h3>
 * <p>수락 조건은 "그 영상에서 <b>실제로 건너뛰기를 해제한</b> 묶음인가" 하나이며 판정은
 * {@link BatchStatusService#hasClearedManualSkip(Long, BatchStageBundle)}(수동 스킵 판정과 같은 축)
 * 단일 지점이다. 임의 묶음을 받으면 요청이 앞 작업을 건너뛰도록 <b>강제</b>할 수 있어 전제 없는
 * 산출물이 만들어진다 — 이 제한이 그 통로를 닫는 장치이므로 느슨하게 만들지 않는다.
 *
 * <h3>허용 조건 (fail-secure, 평가 순서가 계약이다)</h3>
 * <ol>
 *   <li>영상 미존재 → {@link ErrorCode#NOT_FOUND}(404).</li>
 *   <li>묶음 미지원 / 건너뛰기를 해제한 묶음 아님 → {@link ErrorCode#INVALID_INPUT}(400).</li>
 *   <li><b>한번이라도 검수가 완료된 영상 + 오토라벨 묶음</b> → {@link ErrorCode#INVALID_INPUT}(400).
 *       확정된 학습데이터의 라벨은 되돌리지 않는다. 판정은 신고 차단·프레임 폐기 차단과 <b>같은 단일
 *       원천</b>({@link ReviewApprovalGate#hasEverApproved})을 <b>주입해</b> 쓰며 규칙을 복제하지 않는다.
 *       거부 코드가 400 인 것은 승인 이력이 <b>영구 조건</b>이라 재시도 여지가 없기 때문이다.
 *       <b>시계열 묶음은 이 게이트를 받지 않는다</b>(아래 절).</li>
 *   <li>검수 소유 작업 상태 / 완주 상태가 아님 / 이미 다른 주체가 선점 → {@link ErrorCode#CONFLICT}(409).</li>
 *   <li>접수 용량 초과(디스패치 거부) → {@link ErrorCode#SERVICE_UNAVAILABLE}(503).</li>
 * </ol>
 * <p>승인 이력 게이트는 <b>클레임보다 먼저</b> 평가한다 — 뒤에 두면 거부되는 영상의 상태를 선점했다가
 * 되돌려야 한다.
 *
 * <h3>★승인 이력 거부는 오토라벨 한정이다 — 시계열은 승인 이력이 있어도 받는다 [@design API-201]</h3>
 * <p>두 묶음이 승인 완료 영상에 미치는 영향이 다르다.
 * <ul>
 *   <li><b>오토라벨</b>은 라벨을 <b>다시 만든다</b> — 승인 시점 스냅샷과 어긋나므로 예외가 아니다.
 *       이 축을 함께 열지 말 것.</li>
 *   <li><b>시계열</b>은 확정된 라벨을 되돌리지 않고 <b>메타만 더한다</b>. 실행 범위는
 *       {@link BatchBundleTogglePolicy} 가 건너뛰기를 해제한 묶음의 구성원만 켜므로 프레임·라벨을 다시 만들지
 *       않는다.</li>
 * </ul>
 * <p>★<b>면제는 게이트 <u>셋</u>에 함께 걸린다</b> — 승인 이력(400)만 풀면 바로 다음 줄의 검수 소유
 * 작업 상태 검사가 {@code APPROVED} 를 <b>409 로 다시 막고</b>, 그마저 풀어도
 * {@code BatchOrchestrator} 의 진입 가드가 <b>step 을 한 건도 돌리지 않고 SKIPPED</b> 로 끝낸다. 즉
 * 한 겹만 열면 접수 코드만 바뀌고 동작은 그대로다. 면제 대상 판정은 {@link MetadataOnlyRerunPolicy}
 * <b>allowlist</b> 단일 지점이며, 구 {@code bundle == AUTOLABEL} denylist 로 되돌리지 말 것 — 새 묶음이
 * 추가되면 아무도 손대지 않았는데 자동으로 면제된다(fail-open).
 * <p>승인 완료 영상에 시계열 서술이 들어오는 경우는 <b>하류가 이미 정확히 상정</b>하고 있다 —
 * {@code VlmResultService} 가 승인 영상의 서술 갱신 시 검토행을 대기로 되돌리고(재검수 강제) 산출물
 * 재생성·관제 재통지를 건다. <b>값이 같으면 아무 일도 일어나지 않는다</b>(멱등). 벤더 연동이 늦어져
 * 건너뛴 영상이 그대로 승인되더라도 시계열을 나중에 받을 수 있어야 한다.
 *
 * <h3>왜 완주(COMPLETED) 상태에서만 선점하나 — 실패 영상은 받지 않는다</h3>
 * <p>실패 영상을 여기서 받으면 <b>완료로 잘못 마감</b>된다. 오케스트레이터는 루프를 마치면 무조건
 * {@code markRawDataCompleted} 로 마감하므로, 예컨대 프레임 추출에서 실패한 영상에 「시계열만」을 돌리면
 * 프레임이 한 장도 없는 영상이 {@code COMPLETED} 가 되어 검수·산출 축으로 흘러간다. 실패 영상의 복구는
 * 파이프라인을 처음부터 다시 도는 {@link BatchReprocessService}(전체 재기동)가 담당하며, 그 경로는
 * 실패 영상에 <b>사람이 만든 라벨이 없다</b>(라벨링·검수는 배치 완주 이후 워크플로우다)는 점에서 보간
 * 재계산이 파괴가 되지 않는다.
 *
 * <h3>★재수행이 실패해도 영상 상태를 훼손하지 않는다</h3>
 * <p>실행 중 실패는 오케스트레이터의 <b>전용 진입</b>({@code processStageRerun})이 처리한다 — 선점 직전
 * 상태로 되돌리고 자동 재시도 큐에 넣지 않는다. 접수 단계(디스패치 거부)의 보상도 같은 원칙이다.
 * 스킵의 존재 이유가 "기다려도 성공하지 않는 작업"이라 <b>해제해 재수행하면 실패가 기대값</b>이며, 그
 * 실패가 완주 영상을 FAILED 로 강등시키면 자동 재시도가 범위를 모른 채 전 단계를 돌려 사람이 손댄 보간
 * 라벨을 지운다.
 *
 * <h3>동시성 (CWE-362)</h3>
 * <p>선점은 {@link BatchTransitionService#tryClaimReprocessFromCompleted} 단일 조건부 UPDATE 다 —
 * read-then-write 로 바꾸면 동시 요청이 둘 다 통과해 파이프라인이 2벌 돈다. 이 서비스는 상태를 미리
 * 읽어 판정하지 않는다.
 *
 * <p>보안: 호출 인가는 컨트롤러 {@code @PreAuthorize("hasRole('REVIEWER')")} 가 강제한다. 거부 메시지에
 * 요청 값(묶음 문자열)을 되비추지 않으며 상태도 드러내지 않는다(CWE-79/117/209).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchStageRerunService {

    /**
     * 승인 이력 거부 문구 — 영구 조건이므로 "다시 시도"를 권하지 않는다.
     *
     * <p>거부 대상이 <b>오토라벨 묶음뿐</b>임을 드러낸다. 요청자는 자기가 어느 묶음을 지목했는지 이미
     * 알고 있어(경로 변수) 이 문구가 영상 상태를 새로 알려주지는 않는다 — 드러나는 사실(승인 이력)은
     * 구 문구도 이미 같았다. {@link #NOT_CLEARED_BUNDLE_REASON} 이 두 사유에 <b>같은 문구</b>를 쓰는
     * 관례(CWE-209)와 축이 다르다: 그쪽은 「어느 묶음을 건너뛰었는지」를 감추는 장치다.
     */
    static final String EVER_APPROVED_REASON = "검수가 완료된 적이 있는 영상은 오토라벨 묶음을 다시 수행할 수 없습니다.";

    /**
     * 대상 묶음 거부 문구 — <b>미지원 묶음</b>과 <b>건너뛰기를 해제하지 않은 묶음</b>에 같은 문구를 쓴다.
     * 갈라 놓으면 응답이 "그 영상이 어느 묶음을 건너뛰었는지" 알려주는 오라클이 된다(CWE-209).
     */
    static final String NOT_CLEARED_BUNDLE_REASON = "건너뛰기를 해제한 작업 묶음이 아니거나 지원하지 않는 값입니다.";

    /** 클레임 실패(409) 문구 — 상태를 되비추지 않는다(CWE-209). */
    static final String NOT_CLAIMABLE_REASON =
            "지금은 이 영상의 작업 묶음을 다시 수행할 수 없습니다. 이미 재수행이 진행 중일 수 있습니다.";

    /** 검수 소유 작업 상태 거부 문구 — 전체 재기동과 같은 축의 사전 차단이다. */
    static final String REVIEW_OWNED_REASON = "검수 진행/완료(또는 반려) 상태의 영상은 작업 묶음을 다시 수행할 수 없습니다.";

    /** 접수 용량 초과(디스패치 거부) 문구 — 내부 큐·풀 구성을 드러내지 않는다(CWE-209). */
    static final String DISPATCH_REJECTED_REASON = "재수행 요청이 밀려 접수하지 못했습니다. 잠시 후 다시 시도해 주세요.";

    /** 접수 거부로 선점을 되돌렸을 때 표식을 닫는 사유 문구 — 고정 상수(CWE-209/532). */
    static final String CLAIM_CLOSED_DISPATCH_REJECTED = "접수 거부로 선점 해제";

    private final VideoRepository videoRepository;
    private final BatchStatusService batchStatusService;
    private final BatchTransitionService transitionService;
    private final ReviewApprovalGate reviewApprovalGate;
    private final BatchBundleTogglePolicy togglePolicy;
    private final AsyncBatchReprocessRunner reprocessRunner;

    /**
     * 건너뛰기를 해제한 작업 묶음의 재수행을 <b>접수</b>한다 — 상태 선점까지만 요청 안에서 수행한다.
     *
     * @param rawSn      대상 영상 식별자
     * @param bundleName 경로 변수의 묶음 문자열(VLM/AUTOLABEL 외에는 400)
     * @return 접수 결과(대상 묶음 반영)
     */
    public BatchStageRerunResponse rerun(Long rawSn, String bundleName) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 은 필수입니다.");
        }
        // 존재 검증(404) — 없는 영상은 재수행 대상이 아니다.
        if (!videoRepository.existsById(rawSn)) {
            throw new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다.");
        }

        // 값 해석(400) — 상수 목록 대조이며 요청 값을 응답에 되비추지 않는다.
        BatchStageBundle bundle = BatchStageBundle.parse(bundleName);
        if (bundle == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, NOT_CLEARED_BUNDLE_REASON);
        }

        // ★ 요청이 대상 묶음을 자유롭게 고르지 못한다 — 그 영상에서 실제로 건너뛰기를 해제한 묶음만 수락한다.
        //   느슨하게 하면 앞 작업을 건너뛰도록 요청이 강제할 수 있어 전제 없는 산출물이 만들어진다.
        if (!batchStatusService.hasClearedManualSkip(rawSn, bundle)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, NOT_CLEARED_BUNDLE_REASON);
        }

        // ★ 한번이라도 검수가 완료된 영상은 <오토라벨을> 받지 않는다 — 라벨을 다시 만들어 승인 시점
        //   스냅샷과 어긋나기 때문이다. 지금 상태가 아니라 이력으로 판정한다(APPROVED→PENDING 재제출
        //   구간에 뚫리지 않게).
        //   ★시계열은 예외다 — 확정된 라벨을 되돌리지 않고 메타만 더하며, 승인 영상의 서술 갱신은
        //   하류(VlmResultService)가 재검수·재산출·재통지로 받는다(값이 같으면 멱등 no-op).
        //   ⚠ 이 예외를 오토라벨로 넓히지 말 것. ⚠ 평가 위치는 클레임보다 <먼저>다 — 뒤로 옮기면
        //   거부될 영상의 상태를 선점했다가 되돌려야 한다. [design: API-201]
        //   ★면제 대상 판정은 allowlist(MetadataOnlyRerunPolicy) 단일 지점이다 — 구 denylist
        //   (bundle == AUTOLABEL)는 새 묶음이 추가되면 자동으로 면제되는 fail-open 이었다.
        boolean metadataOnly = MetadataOnlyRerunPolicy.isMetadataOnly(bundle);
        if (!metadataOnly && reviewApprovalGate.hasEverApproved(rawSn)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, EVER_APPROVED_REASON);
        }

        // 검수 소유 작업 상태 사전 차단 — 실행이 비동기라 진입 가드의 SKIPPED 를 요청이 볼 수 없다.
        //   클레임 전에 걸러야 "못 돌리는 영상"이 200(접수됨)으로 가려지지 않고, 되돌릴 클레임도 안 생긴다.
        //   ★★같은 면제가 <여기에도> 걸린다 — 승인 이력 게이트만 풀면 바로 다음 줄에서 APPROVED 가
        //   다시 409 로 막혀 「승인 후에도 시계열을 나중에 받는다」가 성립하지 않았다(게이트 2겹).
        if (!metadataOnly && transitionService.isReviewOwnedWorkStatus(rawSn)) {
            throw new CustomException(ErrorCode.CONFLICT, REVIEW_OWNED_REASON);
        }

        // CWE-362 — 완주(COMPLETED)→PROCESSING 단일 조건부 UPDATE. 상태를 미리 읽어 판정하지 않는다.
        if (!transitionService.tryClaimReprocessFromCompleted(rawSn)) {
            throw new CustomException(ErrorCode.CONFLICT, NOT_CLAIMABLE_REASON);
        }

        // ★ 선점 출발 상태(COMPLETED)를 DB 에 남긴다 — 노드가 죽으면 이 값이 유일한 복구 근거다.
        //   완주 영상을 FAILED 로 되돌리면 전체 재기동 경로가 열려 사람이 손댄 보간 라벨이 전량
        //   삭제·재생성된다(이 기능이 막으려던 바로 그 파괴). 회수 스윕이 이 표식을 읽는다.
        //   디스패치 <b>전에</b> 남겨야 열림/닫힘 순서가 뒤집히지 않는다.
        batchStatusService.recordReprocessClaimOpened(rawSn, LsDataRaw.DATA_STTS_COMPLETED);

        // 묶음 → stage 토글 환산은 단일 지점(정책 빈)이 담당한다. 여기서 구성원을 재유도하지 않는다.
        Map<String, Boolean> toggles = togglePolicy.togglesFor(bundle);
        log.info("[BatchStageRerun] claimed rawSn={} bundle={}", rawSn, bundle);

        // ★ 디스패치 거부는 접수 실패다 — 조용히 삼키면 사용자는 접수됐다고 믿는데 아무것도 돌지 않고,
        //   선점한 PROCESSING 이 되돌려지지 않아 그 영상은 이후 영구 409 가 된다.
        try {
            // ★ 면제 여부를 실행 경로까지 나른다 — 오케스트레이터의 진입 가드가 <세 번째 겹>이라
            //   여기까지만 열면 파이프라인이 step 을 한 건도 돌리지 않고 SKIPPED 로 끝난다.
            reprocessRunner.runBundleRerunAsync(rawSn, LsDataRaw.DATA_STTS_COMPLETED, toggles, metadataOnly);
        } catch (TaskRejectedException e) {
            // 보상은 <b>선점 직전 상태(COMPLETED)</b> 로 되돌린다 — FAILED 로 떨어뜨리면 아무것도 실패하지
            //   않았는데 화면이 "실패"로 보이고 완주 사실이 지워진다. 여기서는 아직 파이프라인이 시작되지
            //   않아 작업 상태를 선점한 적이 없으므로 그 컬럼은 건드리지 않는다.
            transitionService.releaseReprocessClaim(rawSn, LsDataRaw.DATA_STTS_COMPLETED);
            // ★ 선점 표식도 닫는다 — 열어 둔 채로 두면 뒤에 다른 경로로 고착된 같은 영상을 회수 스윕이
            //   이 옛 표식의 출발 상태로 되돌린다.
            closeClaimMarkerQuietly(rawSn);
            log.warn("[BatchStageRerun] dispatch rejected — claim compensated rawSn={}", rawSn);
            throw new CustomException(ErrorCode.SERVICE_UNAVAILABLE, DISPATCH_REJECTED_REASON);
        }
        return new BatchStageRerunResponse(rawSn, bundle.name(), true);
    }

    /**
     * 선점 표식 닫기 시도 — <b>실패해도 응답 코드를 뒤집지 않는다</b>.
     *
     * <p>이 호출은 디스패치 거부 보상 안에 있다. 여기서 예외가 올라가면 의도한 <b>503</b> 대신 그 예외가
     * 나가 사용자는 "접수 실패(재시도하면 된다)" 대신 알 수 없는 오류를 본다 — 상태는 이미 되돌아갔는데도.
     * 러너({@code AsyncBatchReprocessRunner})·전체 재기동({@code BatchReprocessService})이 같은 관례를
     * 쓰며, 한 곳만 감싸면 같은 기록 실패가 경로에 따라 다르게 드러난다.
     *
     * <p>대신 <b>기록 실패 사실은 반드시 남긴다</b> — 표식이 열린 채 남으면 회수 스윕의 판정 입력이
     * 오염되므로 운영자가 알아야 한다.
     */
    private void closeClaimMarkerQuietly(Long rawSn) {
        try {
            batchStatusService.recordReprocessClaimClosed(rawSn, CLAIM_CLOSED_DISPATCH_REJECTED);
        } catch (RuntimeException e) {
            log.error("[BatchStageRerun] claim marker close failed rawSn={} reason={}",
                    rawSn, e.getClass().getSimpleName());
        }
    }
}
