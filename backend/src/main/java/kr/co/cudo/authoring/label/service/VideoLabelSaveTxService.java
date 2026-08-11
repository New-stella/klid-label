package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.VideoLabelSaveRequest;
import kr.co.cudo.authoring.label.dto.VideoLabelSaveResponse;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * API-196 — 영상 라벨 일괄 확정 저장의 <b>쓰기 트랜잭션</b>.
 *
 * <h3>2단계 흐름에서 유일한 쓰기 지점이다 (2026-08-11 확정, 구속)</h3>
 * 산출 회차 불러오기(API-195 {@code StartVersionService#loadVersionLabels})는 <b>서버에 아무것도 쓰지
 * 않는다</b>. 화면에서 한 일은 이 저장을 눌러야 남고, 저장하지 않고 떠나면 전부 되돌아간다.
 * 폐기와 복원도 이 저장에 함께 묶이므로 따로 확정하는 경로를 두지 않는다.
 *
 * <p><b>구 {@code PUT /v1/videos/{rawSn}/start-version}(즉시 적용)은 폐기됐다</b> — 그 경로가 남으면
 * 확정 게이트를 우회하는 두 번째 쓰기 경로가 된다.
 *
 * <h3>저장 규칙을 재구현하지 않는다 (Critical)</h3>
 * 프레임마다 {@code LabelService.applyFrameSave} <b>같은 코어</b>를 호출한다. 낙관적 동시성(CAS)·좌표
 * 검증·전체 교체 삭제 델타·변경 이력·재산출 통지·폐기 전이가 두 곳으로 갈리면 한쪽만 갱신되는 순간
 * 어긋난다. 폐기·복원 역시 {@code FrameDiscardApplier} 단일 적용 지점을 그대로 탄다.
 *
 * <h3>전체 트랜잭션 — 하나라도 어긋나면 아무것도 저장하지 않는다</h3>
 * 프레임 순회 전체가 한 트랜잭션이다. 어느 프레임의 판번호가 어긋나 코어가 {@code 409} 를 던지면 앞
 * 프레임의 저장까지 함께 롤백된다 — 일부만 저장하면 서로 다른 시점의 프레임이 섞인 채 확정되고
 * 그대로 외부로 나가기 때문이다(AC-008 ⑥).
 *
 * <h3>★잠금 — 영상 범위 다중 프레임은 {@code SRC_SN} 축 선점 규약에 합류한다 (변경 금지)</h3>
 * 트랜잭션 <b>맨 앞에서</b> {@link LsDataSrcRepository#lockFramesByRawSn}(단일 문장,
 * {@code ORDER BY SRC_SN … FOR NO KEY UPDATE})로 영상 전 프레임 락을 <b>1회 선점</b>한다.
 * 이후 코어가 프레임마다 잡는 {@code FOR UPDATE}·{@code LBL_VER} bump 는 <b>이미 보유한 행</b>만
 * 건드려 새 락을 얻지 않으므로, 대기 지점이 트랜잭션당 1곳으로 줄고 순환 대기가 성립하지 않는다.
 *
 * <p><b>왜 프레임별 잠금만으로는 안 되나</b>: 이 저장소의 영상 범위 다중 프레임 경로
 * ({@code TrackEditService} · {@code TrackMergeService} · {@code TrackInterpolationStep})는 전부 위
 * 선점을 쓰고 그 획득 순서는 <b>{@code SRC_SN}</b> 오름차순이다. 여기서 {@code FRM_NO} 순서로 프레임마다
 * 개별 락을 잡으면 두 순서가 어긋나는 영상(추출 순번과 PK 순서가 다른 경우)에서 그 3경로와 순환 대기 →
 * {@code 40P01} → 500 으로 영상 전체 저장이 통째로 롤백된다.
 *
 * <p>순회 자체는 {@code FRAME_NO} 오름차순을 유지한다 — 선점 이후라 순서가 교착을 만들지 않고,
 * 승인 스냅샷 경로({@code VersionService.commitApproved})와 같은 정렬이어서 읽기 흐름이 일관된다.
 * {@code LS_DATA_RAW}·{@code LS_RAW_DATA_STATUS} 는 잠그지 않아 배치와의 교착 축과 무관하다
 * ({@code LockOrderGuardTest} 불변식 유지).
 *
 * <h3>게이트 순서 — 인가 → 존재 → 신고(412) → 작업락(409)</h3>
 * 비식별 누락 신고는 작업락과 {@code DE_IDNTF_YN='F'} 를 함께 세우는데 락은 6시간 뒤 회수되고
 * {@code 'F'} 는 resolve 까지 남는다. 락을 먼저 보면 같은 사유가 <b>신고 직후엔 409, 6시간 뒤엔 412</b>
 * 가 되어 응답 코드가 잠금 상태를 알려주는 오라클이 된다(CWE-209 · C-ISSUE-22 확정 ·
 * {@code LabelService.bulkUpsert} 와 같은 순서). <b>게이트는 프레임 락 선점보다 먼저</b> 평가한다 —
 * 거부될 요청이 영상 전 프레임을 잠그고 나서 거부되면 그 사이 정상 저장이 대기한다.
 *
 * <h3>보안</h3>
 * <ul>
 *   <li>IDOR (CWE-639): 영상 단위 인가 1회 + 요청 {@code srcSn} 이 <b>그 영상 소속인지</b> 대조(404).
 *       프레임 소유를 대조하지 않으면 남의 영상 프레임을 이 요청에 끼워 저장할 수 있다.</li>
 *   <li>CWE-770: 프레임 수 상한은 호출부({@code VideoLabelSaveService})가 트랜잭션 진입 이전에 판정한다.</li>
 *   <li>CWE-778: 어느 회차에서 시작한 저장인지 영상 단위 감사로 남긴다(식별자 한 토큰만 — CWE-359).</li>
 *   <li>CWE-359: 로그에 라벨 본문·좌표·경로를 남기지 않는다(식별자·건수만).</li>
 * </ul>
 *
 * @design API-196
 * @req R6
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoLabelSaveTxService {

    private final LabelAccessGuard accessGuard;
    private final VideoRepository videoRepository;
    private final WorkLockService workLockService;
    private final LsDataSrcRepository srcRepository;
    /** 저장 코어의 소유자 — 프레임 단위 저장과 <b>같은 규칙</b>을 쓴다. */
    private final LabelService labelService;
    /** CWE-778 — 어느 회차에서 시작한 확정인지 영상 단위 감사. */
    private final LsTaskEventLogRepository taskEventLogRepository;

    /**
     * @param preResolvedBounds 트랜잭션 밖에서 확보한 프레임별 좌표 경계 기준값
     *                          ({@code srcSn → [w,h]}, 측정 불가면 값이 {@code null}).
     *                          트랜잭션 안에서 이미지를 디코딩하지 않기 위한 입력이다.
     */
    @Transactional("controlTransactionManager")
    public VideoLabelSaveResponse saveInTx(Long rawSn, VideoLabelSaveRequest req, TokenClaims actor,
                                           Map<Long, int[]> preResolvedBounds) {
        accessGuard.verifyRawAccess(rawSn, actor);
        videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
        // ★ 신고 게이트가 작업락보다 <b>먼저</b>다 — 같은 사유에 409/412 가 갈리면 응답이 잠금 상태를
        //   알려주는 오라클이 된다(CWE-209, C-ISSUE-22 확정).
        accessGuard.requireNotUnderDeidentReport(rawSn);
        //   여기 남는 409 는 <b>신고와 무관한 락</b>(트랙 병합·재비식별 진행 중)뿐이다.
        if (workLockService.isRawLocked(rawSn)) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "비식별 재처리 중인 영상은 라벨을 수정할 수 없습니다.");
        }

        // ★ 영상 범위 다중 프레임 잠금 규약에 합류 — SRC_SN 오름차순 단일 문장 선점(클래스 javadoc).
        //   게이트를 모두 통과한 뒤에 잡는다: 거부될 요청이 전 프레임을 잠그고 거부되면 그 사이 정상
        //   저장이 대기한다. 이후 코어의 프레임별 잠금은 이미 보유한 행만 건드린다.
        srcRepository.lockFramesByRawSn(rawSn);

        Map<Long, VideoLabelSaveRequest.Frame> requested = indexBySrcSn(req.frames());

        // CWE-639 — 요청 식별자를 신뢰하지 않는다. 그 영상 소속 프레임만 대상이다.
        Map<Long, LsDataSrc> owned = new HashMap<>();
        for (LsDataSrc frame : srcRepository.findByRawSnOrderByFrameNoAsc(rawSn)) {
            owned.put(frame.getSrcSn(), frame);
        }
        List<LsDataSrc> targets = new ArrayList<>(requested.size());
        for (Long srcSn : requested.keySet()) {
            LsDataSrc frame = owned.get(srcSn);
            if (frame == null) {
                // 소속 여부를 사유로 구분하지 않는다(없는 프레임 / 남의 프레임 모두 404).
                throw new CustomException(ErrorCode.NOT_FOUND, "프레임을 찾을 수 없습니다.");
            }
            targets.add(frame);
        }
        // 승인 스냅샷 경로와 같은 정렬로 순회한다(선점 이후라 순서가 교착을 만들지 않는다).
        targets.sort(Comparator.comparing(LsDataSrc::getFrameNo,
                Comparator.nullsLast(Comparator.naturalOrder())));

        Long actorNo = accessGuard.parseUserNo(actor.sub());
        Map<Long, int[]> bounds = preResolvedBounds == null ? Map.of() : preResolvedBounds;
        List<VideoLabelSaveResponse.Frame> saved = new ArrayList<>(targets.size());
        int discarded = 0;
        for (LsDataSrc frame : targets) {
            VideoLabelSaveRequest.Frame item = requested.get(frame.getSrcSn());
            // 판번호 불일치는 코어가 409 로 던진다 — 트랜잭션 전체가 롤백되어 부분 저장이 없다.
            //   좌표 경계 기준값은 트랜잭션 밖에서 확보한 값을 넘겨 코어가 파일을 열지 않게 한다.
            LabelService.FrameSaveOutcome outcome = labelService.applyFrameSave(
                    frame.getSrcSn(), frame, item.toFrameRequest(), actorNo,
                    LabelService.FrameSaveOptions.withBounds(bounds.get(frame.getSrcSn())));
            String dscdYn = frame.getDscdYn() == null ? LsDataSrc.DSCD_NO : frame.getDscdYn();
            if (LsDataSrc.DSCD_YES.equals(dscdYn)) {
                discarded++;
            }
            saved.add(new VideoLabelSaveResponse.Frame(
                    frame.getSrcSn(), dscdYn, outcome.labelVersion()));
        }

        recordStartVersionAudit(rawSn, actorNo, req.loadedVersion());
        // 식별자·건수만 남긴다(라벨 본문·좌표 미출력 — CWE-359).
        log.info("[Label] video labels confirmed rawSn={} actor={} frames={} discarded={}",
                rawSn, actorNo, saved.size(), discarded);
        return new VideoLabelSaveResponse(rawSn, saved, saved.size(), discarded);
    }

    /**
     * 요청 프레임을 {@code srcSn} 으로 인덱싱한다 — <b>중복은 400</b>.
     *
     * <p>같은 프레임을 두 번 보내면 전체 교체 저장이 두 번 돌아 뒤 항목이 앞 항목을 지운다. 게다가 두
     * 번째 호출은 앞 호출이 올린 판번호와 어긋나 <b>영상 전체가 409</b> 로 거부되므로, 사용자에게는
     * "왜 실패했는지 알 수 없는 저장"이 된다. 조용히 마지막 값을 채택하지 않고 입구에서 거부한다.
     */
    private Map<Long, VideoLabelSaveRequest.Frame> indexBySrcSn(List<VideoLabelSaveRequest.Frame> frames) {
        Map<Long, VideoLabelSaveRequest.Frame> indexed = new HashMap<>(frames.size());
        Set<Long> seen = new HashSet<>();
        for (VideoLabelSaveRequest.Frame frame : frames) {
            if (frame.srcSn() == null) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "srcSn 은 필수입니다.");
            }
            if (!seen.add(frame.srcSn())) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "같은 프레임이 두 번 실려 있습니다.");
            }
            indexed.put(frame.srcSn(), frame);
        }
        return indexed;
    }

    /**
     * 어느 산출 회차에서 시작한 확정인지 남긴다 (CWE-778 · OWASP A09).
     *
     * <p>불러오기를 거치지 않은 평상시 저장({@code loadedVersion} 부재)은 감사 대상이 아니다 — 그건
     * 라벨 이력({@code LS_DATA_LBL_HSTRY})이 이미 담는 일반 편집이고, 여기 남겨야 하는 것은
     * "영상 전체를 과거 회차 상태로 되돌리기로 했다"는 <b>비가역 결정</b>이다.
     *
     * <p>{@code RSN} 에는 회차 번호 한 토큰만 싣는다(자유 문구·본문 금지 — CWE-359/117). DTO 가 숫자만
     * 허용하므로 여기서 형식을 다시 검사하지 않되, 파싱 실패는 <b>저장을 깨뜨리지 않고</b> 감사만
     * 건너뛴다(이미 확정된 라벨을 감사 실패로 되돌리면 사용자의 작업이 사라진다).
     */
    private void recordStartVersionAudit(Long rawSn, Long actorNo, String loadedVersion) {
        if (loadedVersion == null || loadedVersion.isBlank()) {
            return;
        }
        try {
            taskEventLogRepository.save(LsTaskEventLog.startVersionApplied(
                    rawSn, actorNo, Integer.valueOf(loadedVersion)));
        } catch (NumberFormatException e) {
            log.warn("[Label] video save audit skipped — unreadable loadedVersion rawSn={}", rawSn);
        }
    }
}
