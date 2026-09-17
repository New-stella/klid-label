package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.portal.dto.PortalDatasetRegistrationResponse;
import kr.co.cudo.authoring.portal.dto.PortalDatasetRegistrationState;
import kr.co.cudo.authoring.portal.dto.PortalDatasetVideoPageResponse;
import kr.co.cudo.authoring.portal.dto.PortalDatasetVideoResponse;
import kr.co.cudo.authoring.portal.repository.PortalDatasetVideoRepository;
import kr.co.cudo.authoring.portal.repository.PortalDatasetVideoRepository.VideoRow;
import kr.co.cudo.authoring.portal.repository.PortalUserWorkListRepository;
import kr.co.cudo.authoring.portal.repository.PortalUserWorkListRepository.DatamartWork;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 포털 데이터셋 영상 목록 — 소재가 준비된 데이터셋의 등록 영상을 페이지로 돌려준다. @design API-253
 *
 * <h3>판정 순서</h3>
 * <ol>
 *   <li>소재가 준비 완료가 아니면(가져온 적 없음·진행 중·실패) <b>409</b>. 화면은 그 상태를 이 창구가 아니라
 *       상태 조회 창구로 판정한다.</li>
 *   <li>등록 상태를 정한다 — 등록이 끝나지 않은 것은 409 가 아니라 <b>200 과 등록 상태</b>로 답한다.</li>
 *   <li>지금까지 등록된 영상을 페이지로 싣는다.</li>
 * </ol>
 *
 * <h3>★ 끊긴 등록을 이 조회가 회복시킨다</h3>
 * <p>소재는 준비됐는데 등록 표식이 없거나, 진행 중 표식이 {@link PortalDatasetRegistrationService#STALE_IN_PROGRESS}
 * 보다 오래됐고 <b>이 노드에서</b> 진행 중인 작업이 없으면 등록을 다시 시작시키고 진행 중으로 답한다.
 * 등록이 클립 식별자 기준 멱등이라 다시 시작해도 행이 늘지 않는다.
 *
 * <h3>★ 행 값의 조달처</h3>
 * <ul>
 *   <li>{@code labelCount} — 등록된 <b>원본</b> 라벨 수(사용자 저장 라벨 수가 아니다).</li>
 *   <li>{@code entrySrcSn}·{@code lastSavedAt} — <b>요청 사용자 기준</b>이며 「내 작업」 목록과 <b>같은
 *       판정</b>을 쓴다({@link PortalUserWorkListRepository#findDatamartWorkByVideos}). 따로 셈하지 않는다.</li>
 * </ul>
 *
 * <h3>N+1 을 만들지 않는다</h3>
 * <p>페이지 한 장에 대해 프레임 수·라벨 수·저작 판정을 각 1회 일괄 조회한다.
 *
 * <h3>★ 실패 표식은 목록이 다시 시작시키지 않는다 — 재착수는 사람이 한다(API-262)</h3>
 * <p>구조 불일치는 다시 돌려도 같은 사유로 실패하므로 자동으로 되풀이하지 않는다. 목록은 실패 사유 분류를
 * 실어 화면이 문구로 보이고, {@link #restartRegistration} 이 실패 표식일 때만 등록을 다시 시작시킨다.
 *
 * <p>이 창구는 원장·오버레이를 <b>쓰지 않는다</b>(등록을 다시 시작시키는 것은 백그라운드 작업이다).
 *
 * @design ADR-068
 * @design API-262
 * @design AC-1119
 * @design AC-1132
 */
@Slf4j
@Service
public class PortalDatasetVideoService {

    /** 페이지 크기 기본값·상한(API-253). */
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final PortalMaterialsWorkspace workspace;
    private final PortalDatasetRegistrationService registrationService;
    private final PortalDatasetRegistrationRunner registrationRunner;
    private final PortalDatasetVideoRepository videoRepository;
    private final LsDataSrcRepository srcRepository;
    private final PortalUserWorkListRepository workListRepository;
    private final Clock clock;

    @Autowired
    public PortalDatasetVideoService(PortalMaterialsWorkspace workspace,
                                     PortalDatasetRegistrationService registrationService,
                                     PortalDatasetRegistrationRunner registrationRunner,
                                     PortalDatasetVideoRepository videoRepository,
                                     LsDataSrcRepository srcRepository,
                                     PortalUserWorkListRepository workListRepository) {
        this(workspace, registrationService, registrationRunner, videoRepository, srcRepository,
                workListRepository, Clock.systemUTC());
    }

    public PortalDatasetVideoService(PortalMaterialsWorkspace workspace,
                                     PortalDatasetRegistrationService registrationService,
                                     PortalDatasetRegistrationRunner registrationRunner,
                                     PortalDatasetVideoRepository videoRepository,
                                     LsDataSrcRepository srcRepository,
                                     PortalUserWorkListRepository workListRepository,
                                     Clock clock) {
        this.workspace = workspace;
        this.registrationService = registrationService;
        this.registrationRunner = registrationRunner;
        this.videoRepository = videoRepository;
        this.srcRepository = srcRepository;
        this.workListRepository = workListRepository;
        this.clock = clock;
    }

    /**
     * 데이터셋 영상 한 페이지.
     *
     * @param portalUserNo 요청 사용자(토큰 주체) — 저작 판정의 격리 키
     * @throws CustomException 400(식별자) · 401(토큰) · 409(소재 미준비)
     */
    public PortalDatasetVideoPageResponse list(long datasetId, int page, int size, String portalUserNo) {
        requireReadyDataset(datasetId, portalUserNo);

        PortalDatasetRegistrationState state = resolveRegistrationState(datasetId);

        PageRequest pageable = PageRequest.of(Math.max(page, 0), clampSize(size));
        Page<VideoRow> rows = videoRepository.findPage(datasetId, pageable);
        List<Long> rawSns = rows.getContent().stream().map(VideoRow::rawSn).toList();

        Map<Long, Long> frameCounts = rawSns.isEmpty() ? Map.of() : frameCounts(rawSns);
        Map<Long, Long> labelCounts = rawSns.isEmpty() ? Map.of() : videoRepository.countLabelsByVideos(rawSns);
        Map<Long, DatamartWork> work = rawSns.isEmpty()
                ? Map.of()
                : workListRepository.findDatamartWorkByVideos(portalUserNo, rawSns);

        List<PortalDatasetVideoResponse> content = rows.getContent().stream()
                .map(r -> {
                    DatamartWork w = work.get(r.rawSn());
                    return new PortalDatasetVideoResponse(
                            r.rawSn(),
                            r.videoName(),
                            Math.toIntExact(frameCounts.getOrDefault(r.rawSn(), 0L)),
                            Math.toIntExact(labelCounts.getOrDefault(r.rawSn(), 0L)),
                            w == null ? null : w.entrySrcSn(),
                            w == null ? null : w.lastSavedAt());
                })
                .toList();

        return new PortalDatasetVideoPageResponse(state, failureReasonOf(datasetId, state), content,
                rows.getTotalElements(), rows.getTotalPages(), rows.getNumber(), rows.getSize());
    }

    /**
     * 등록 재착수 — <b>실패 표식일 때만</b> 등록을 다시 시작시키고 진행 중으로 답한다. @design API-262
     *
     * <p>검사 순서는 {@link #list} 와 같다(토큰 401 → 식별자 400 → 소재 미준비 409). 그다음 현재 상태를
     * {@link #resolveRegistrationState} 로 정한다 — 표식 없음·오래된 진행 중의 회복 판정은 그 안에 있다.
     * 완료·진행 중이면 새 작업 없이 그대로 답한다(멱등). 실패면 선점 뒤 백그라운드로 다시 시작시키며,
     * 대기열이 차 맡기지 못하면 선점을 되돌리고 503 으로 거절한다 — 남기면 아무도 일하지 않는데 이 노드에서
     * 「진행 중」으로 굳는다. 등록 토글이 꺼져 있으면 409 — 다시 시작시킬 수 없는 상태를 진행 중으로 꾸며
     * 답하지 않는다.
     *
     * <p>어느 경우든 응답 코드는 200 이고 구분은 본문의 등록 상태가 싣는다. 재착수가 접수됐을 때 실패 사유는
     * 비어 있다. 표식 삭제는 따로 하지 않는다 — 등록 본체가 첫 줄에서 진행 중 표식을 덮어쓴다.
     *
     * @param portalUserNo 요청 사용자(토큰 주체)
     * @throws CustomException 400(식별자) · 401(토큰) · 409(소재 미준비 · 등록 꺼짐) · 503(대기열 포화)
     * @design AC-1132
     */
    public PortalDatasetRegistrationResponse restartRegistration(long datasetId, String portalUserNo) {
        requireReadyDataset(datasetId, portalUserNo);

        PortalDatasetRegistrationState state = resolveRegistrationState(datasetId);
        if (state != PortalDatasetRegistrationState.FAILED) {
            return new PortalDatasetRegistrationResponse(state, null);
        }
        if (!registrationService.isEnabled()) {
            throw new CustomException(ErrorCode.CONFLICT, "등록이 꺼져 있어 다시 시작할 수 없습니다.");
        }
        if (registrationService.tryClaim(datasetId)) {
            try {
                registrationRunner.runAsync(datasetId);
                log.info("[PortalDataset] 실패한 등록을 사람이 다시 시작시켰습니다 datasetId={}", datasetId);
            } catch (TaskRejectedException e) {
                // 선점을 반드시 되돌린다 — 남기면 아무도 일하지 않는데 이 노드에서 「진행 중」으로 굳는다.
                registrationService.release(datasetId);
                log.warn("[PortalDataset] 재착수 등록 작업을 접수하지 못했습니다(대기열 포화) datasetId={}",
                        datasetId);
                throw new CustomException(ErrorCode.SERVICE_UNAVAILABLE,
                        "등록 작업이 밀려 있습니다. 잠시 후 다시 시도해 주세요.");
            } catch (RuntimeException e) {
                registrationService.release(datasetId);
                throw e;
            }
        }
        // 선점에 졌으면 다른 경로가 방금 시작한 것이다 — 새로 시작하지 않고 진행 중을 답한다.
        return new PortalDatasetRegistrationResponse(PortalDatasetRegistrationState.IN_PROGRESS, null);
    }

    /** 두 창구가 같은 순서로 거른다 — 토큰 401 → 식별자 400 → 소재 미준비 409. */
    private void requireReadyDataset(long datasetId, String portalUserNo) {
        if (portalUserNo == null || portalUserNo.isBlank()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
        if (datasetId <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "데이터셋 식별자가 유효하지 않습니다.");
        }
        if (!workspace.isReady(datasetId)) {
            throw new CustomException(ErrorCode.CONFLICT, "데이터셋 소재가 아직 준비되지 않았습니다.");
        }
    }

    /**
     * 실패 사유 — 등록 상태가 실패일 때만 읽고 그 밖은 {@code null}(API-253 v3 · API-262).
     *
     * <p>표식에 사유가 있으면 그 값이다. 표식이 없거나 사유가 비어 있는데 실패로 판정된 것은 <b>등록 토글이
     * 꺼져 있어</b> 회복 판정이 실패로 답한 경우뿐이므로 그때는 {@link PortalDatasetRegistrationFailureReason#DISABLED}
     * 를 돌려준다 — 계약은 「실패면 사유가 있다」이고, 사유가 비면 화면이 폴백 문구로 떨어져 운영자 설정이 원인이라는
     * 사실이 드러나지 않는다.
     */
    private PortalDatasetRegistrationFailureReason failureReasonOf(long datasetId,
                                                                   PortalDatasetRegistrationState state) {
        if (state != PortalDatasetRegistrationState.FAILED) {
            return null;
        }
        PortalDatasetRegistrationStatus status = workspace.readRegistration(datasetId);
        if (status != null && status.failureReason() != null) {
            return status.failureReason();
        }
        return registrationService.isEnabled() ? null : PortalDatasetRegistrationFailureReason.DISABLED;
    }

    /**
     * 등록 상태를 정하고, 끊긴 등록이면 다시 시작시킨다.
     *
     * <p>순서: 이 노드에서 진행 중 → 표식 완료·실패 → 표식이 신선한 진행 중 → (표식 없음 · 오래된 진행 중)
     * 회복. 회복 판정에서 등록 토글이 꺼져 있으면 <b>아무도 끝내 주지 않으므로</b> 실패로 답한다.
     */
    PortalDatasetRegistrationState resolveRegistrationState(long datasetId) {
        if (registrationService.inProgress(datasetId)) {
            return PortalDatasetRegistrationState.IN_PROGRESS;
        }
        PortalDatasetRegistrationStatus status = workspace.readRegistration(datasetId);
        if (status != null && status.state() != PortalDatasetRegistrationState.IN_PROGRESS) {
            return status.state();
        }
        boolean needsRestart = status == null
                || status.isStaleInProgress(clock.instant(), PortalDatasetRegistrationService.STALE_IN_PROGRESS);
        if (!needsRestart) {
            // 신선한 진행 중 표식 — 다른 노드가 진행 중일 수 있다. 새로 시작하지 않는다.
            return PortalDatasetRegistrationState.IN_PROGRESS;
        }
        if (!registrationService.isEnabled()) {
            return PortalDatasetRegistrationState.FAILED;
        }
        if (registrationService.tryClaim(datasetId)) {
            try {
                registrationRunner.runAsync(datasetId);
                log.info("[PortalDataset] 끊긴 등록을 다시 시작합니다 datasetId={} markerPresent={}",
                        datasetId, status != null);
            } catch (TaskRejectedException e) {
                // 선점을 되돌린다 — 남기면 아무도 일하지 않는데 이 노드에서 「진행 중」으로 굳는다.
                registrationService.release(datasetId);
                log.warn("[PortalDataset] 등록 작업을 접수하지 못했습니다(대기열 포화) datasetId={}", datasetId);
            } catch (RuntimeException e) {
                registrationService.release(datasetId);
                throw e;
            }
        }
        return PortalDatasetRegistrationState.IN_PROGRESS;
    }

    private Map<Long, Long> frameCounts(List<Long> rawSns) {
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : srcRepository.countByRawSnsGrouped(rawSns)) {
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) {
                continue;
            }
            map.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return map;
    }

    /** 페이지 크기 — 1~{@value #MAX_PAGE_SIZE}. 벗어나면 가까운 경계로 접는다(형제 목록 창구와 같다). */
    static int clampSize(int size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
