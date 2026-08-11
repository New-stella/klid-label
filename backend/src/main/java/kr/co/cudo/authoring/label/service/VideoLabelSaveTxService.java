package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.LabelBulkUpsertRequest;
import kr.co.cudo.authoring.label.dto.LabelItemDto;
import kr.co.cudo.authoring.label.dto.LabelResponse;
import kr.co.cudo.authoring.label.dto.VideoLabelSaveRequest;
import kr.co.cudo.authoring.label.dto.VideoLabelSaveResponse;
import kr.co.cudo.authoring.version.service.VersionSnapshotReader;
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
import java.util.Optional;
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
 * <h3>동작 — 회차를 읽어 전 프레임에 적용하고, 고친 프레임만 덮는다</h3>
 * <ol>
 *   <li>{@code loadedVersion} 스냅샷을 <b>읽어</b> 영상 전 프레임에 적용한다 — 그래서 사람이 그린
 *       것인지 자동으로 붙은 것인지와 추적 식별자가 <b>회차에 적힌 대로 살아남는다</b>.</li>
 *   <li>{@code edits} 에 온 프레임만 그 내용으로 덮는다(그 프레임에서는 사람이 보낸 것이 기준이다).</li>
 *   <li>{@code frameVersions} 가 전 프레임을 덮지 않으면 <b>400</b>.</li>
 * </ol>
 *
 * <h3>★버전 축을 건드리지 않는다 (사용자 확정 원칙, 구속)</h3>
 * 확정 저장은 회차 스냅샷을 <b>읽기만</b> 한다. 회차 기록·활성 표식({@code ACTVTN_YN})·회차↔스냅샷
 * 매핑을 <b>일절 변경하지 않는다</b> — 구체적으로 {@code VersionService.activateRollbackTarget} ·
 * {@code deactivateOthers} · {@code LsOutputVerSnpshRepository.recordActiveSnapshots} 를 호출하지 않는다.
 *
 * <p>근거: 각 회차는 서로 간섭해선 안 된다. 저장은 <b>기존 데이터를 덮어쓰는 것이 아니라 새로 저장</b>
 * 하는 것이고, 그래야 검수 완료 시점마다 만들어진 데이터마트가 각각 유지된다. 편해 보여도 기존 롤백
 * 시맨틱(대상 스냅샷 재활성)을 재사용하면 그 순간 이 원칙이 깨진다. 회귀 가드:
 * {@code VideoLabelSaveTxServiceTest.확정_저장은_회차_기록과_활성_표식을_바꾸지_않는다}.
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
     * 회차 스냅샷 <b>읽기</b>의 단일 진실원 — 불러오기(API-195)와 같은 규칙을 쓴다.
     * 화면에 보인 것과 저장되는 것이 갈리지 않게 하는 축이다.
     */
    private final VersionSnapshotReader snapshotReader;
    private final LsDataLblRepository labelRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

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

        // CWE-639 — 요청 회차를 신뢰하지 않는다. 그 영상에 실재하는 회차일 때만 진행한다.
        //   대조 없이 번호를 믿으면 다른 영상 스냅샷을 끌어와 확정하고, 감사에는 검증되지 않은
        //   클라이언트 주장이 그대로 남는다(비가역 결정을 남기려는 감사의 목적과 어긋난다).
        if (!snapshotReader.versionExists(rawSn, req.loadedVersion())) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "해당 산출 버전의 스냅샷을 찾을 수 없습니다.");
        }

        Map<Long, Long> versionBySrcSn = indexVersions(req.frameVersions());
        // ★ 커버리지 강제 — 전 프레임을 덮지 않으면 400. <b>엔티티 로드 이전</b>에 count 로 판정한다
        //   (불러오기 경로가 쓰는 것과 같은 규칙 — 거부할 요청이 프레임 행을 전량 힙에 올린 뒤에야
        //   거부되면 상한·커버리지가 지키려던 자원을 지키지 못한다, CWE-770).
        //   폐기된 프레임도 전 프레임에 포함된다(불러오기가 전 프레임을 돌려주므로 왕복이 성립한다).
        long frameCount = srcRepository.countByRawSn(rawSn);
        if (versionBySrcSn.size() != frameCount) {
            log.warn("[Label] video save rejected — frame coverage mismatch rawSn={} sent={} total={}",
                    rawSn, versionBySrcSn.size(), frameCount);
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "영상의 전체 프레임을 함께 보내야 확정할 수 있습니다. 최신 상태를 다시 불러온 뒤 저장해 주세요.");
        }

        // ★ 영상 범위 다중 프레임 잠금 규약에 합류 — SRC_SN 오름차순 단일 문장 선점(클래스 javadoc).
        //   게이트·커버리지를 모두 통과한 뒤에 잡는다: 거부될 요청이 전 프레임을 잠그고 거부되면
        //   그 사이 정상 저장이 대기한다. 이후 코어의 프레임별 잠금은 이미 보유한 행만 건드린다.
        srcRepository.lockFramesByRawSn(rawSn);

        // CWE-639 — 요청 식별자를 신뢰하지 않는다. 그 영상 소속 프레임만 대상이다.
        Map<Long, LsDataSrc> owned = new HashMap<>();
        for (LsDataSrc frame : srcRepository.findByRawSnOrderByFrameNoAsc(rawSn)) {
            owned.put(frame.getSrcSn(), frame);
        }
        // F-06 — 커버리지 판정(count)과 락 사이에 프레임이 추가됐는지 <b>락 이후</b> 다시 본다.
        //   그 창에서 늘어나면 요청이 부분집합이 되는데 targets 루프는 404 를 내지 않아
        //   "한 영상 = 한 회차"가 그 프레임 하나에서 조용히 깨진다.
        if (owned.size() != versionBySrcSn.size()) {
            log.warn("[Label] video save rejected — frame set changed under lock rawSn={} sent={} owned={}",
                    rawSn, versionBySrcSn.size(), owned.size());
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "영상의 전체 프레임을 함께 보내야 확정할 수 있습니다. 최신 상태를 다시 불러온 뒤 저장해 주세요.");
        }
        List<LsDataSrc> targets = new ArrayList<>(versionBySrcSn.size());
        for (Long srcSn : versionBySrcSn.keySet()) {
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

        // F-05 — 대조 집합은 <b>판번호 목록</b>이다(소속 집합이 아니다). 지금은 커버리지 강제 덕에 두
        //   집합이 같지만, 커버리지 규칙이 완화되면 판번호 없는 프레임이 edits 로 들어와
        //   requireLabelVersionMatch 가 "요청값 null → 검사 skip" 으로 빠져 CAS 가 통째로 꺼진다.
        Map<Long, VideoLabelSaveRequest.FrameEdit> edits =
                indexEdits(req.safeEdits(), versionBySrcSn.keySet());
        // 회차↔스냅샷 해석은 <b>읽기 전용</b>이다 — 활성 표식·회차 매핑을 바꾸지 않는다(클래스 javadoc).
        Map<Long, Long> snapshotTargets = snapshotReader.resolveTargets(rawSn, req.loadedVersion());

        Long actorNo = accessGuard.parseUserNo(actor.sub());
        Map<Long, int[]> bounds = preResolvedBounds == null ? Map.of() : preResolvedBounds;
        List<VideoLabelSaveResponse.Frame> saved = new ArrayList<>(targets.size());
        // F-2 — 승인 이력 판정의 <b>요청 스코프</b> 캐시. 프레임마다 최대 3쿼리가 도는 것을 1회로 줄인다
        //   (영상 1건당 rawSn 은 하나라 적중률 100%). 이 루프는 영상 전 프레임 행 락을 보유한 상태라
        //   지연이 곧 락 보유 시간이다. 빈은 stateless 여야 하므로 캐시는 여기서 만들어 넘긴다.
        Map<Long, Boolean> approvalCache = new HashMap<>(1);
        int discarded = 0;
        for (LsDataSrc frame : targets) {
            FramePlan plan = planFor(frame, versionBySrcSn.get(frame.getSrcSn()),
                    edits.get(frame.getSrcSn()), snapshotTargets);
            // 판번호 불일치는 코어가 409 로 던진다 — 트랜잭션 전체가 롤백되어 부분 저장이 없다.
            //   좌표 경계 기준값은 트랜잭션 밖에서 확보한 값을 넘겨 코어가 파일을 열지 않게 한다.
            LabelService.FrameSaveOutcome outcome = labelService.applyFrameSave(
                    frame.getSrcSn(), frame, plan.request(), actorNo,
                    LabelService.FrameSaveOptions.of(bounds.get(frame.getSrcSn()), plan.hints(),
                            plan.discardFromSnapshot(), approvalCache));
            String dscdYn = frame.getDscdYn() == null ? LsDataSrc.DSCD_NO : frame.getDscdYn();
            if (LsDataSrc.DSCD_YES.equals(dscdYn)) {
                discarded++;
            }
            saved.add(new VideoLabelSaveResponse.Frame(
                    frame.getSrcSn(), dscdYn, outcome.labelVersion()));
        }

        recordStartVersionAudit(rawSn, actorNo, req.loadedVersion());
        // 식별자·건수만 남긴다(라벨 본문·좌표 미출력 — CWE-359).
        log.info("[Label] video labels confirmed rawSn={} actor={} version={} frames={} edits={} discarded={}",
                rawSn, actorNo, req.loadedVersion(), saved.size(), edits.size(), discarded);
        return new VideoLabelSaveResponse(rawSn, saved, saved.size(), discarded);
    }

    /**
     * 프레임 1건에 적용할 저장 요청 + 복원 힌트 + <b>폐기 값의 출처</b>.
     *
     * @param discardFromSnapshot 그 프레임의 폐기 값이 <b>회차 스냅샷과 같은가</b>. 사용자가
     *                            {@code edits} 로 회차와 <b>다른</b> 값을 지정하면 {@code false} 다 —
     *                            승인 이력 영상의 <b>새 폐기·복원 조작</b>은 차단 대상이고(P2b), 회차
     *                            적용분은 예외다.
     *                            <p>판정이 "필드 존재"가 아니라 <b>값 비교</b>인 이유: 화면은 폐기를
     *                            토글하지 않아도 회차 값을 그대로 실어 보내므로, 존재만 보면 라벨만 고친
     *                            정상 저장까지 막힌다(F-1). 반대로 이 구분을 경로 단위로 뭉개면 회차를
     *                            한 번 불러오는 것만으로 차단이 통째로 우회된다.
     */
    private record FramePlan(LabelBulkUpsertRequest request, Map<Long, LabelService.RestoreHint> hints,
                             boolean discardFromSnapshot) {
    }

    /**
     * 프레임 1건의 확정 내용을 정한다 — <b>회차 스냅샷을 바탕으로, 고친 프레임만 덮는다</b>.
     *
     * <p>{@code edits} 에 없는 프레임은 스냅샷 본문이 그대로 확정 내용이고, 있는 프레임은 사람이 보낸
     * 내용이 기준이다. 폐기 여부도 같은 규칙이며 {@code edits} 가 {@code dscdYn} 을 생략하면 스냅샷 값을
     * 쓴다.
     *
     * <p><b>복원 힌트는 두 경우 모두 스냅샷에서 만든다</b> — 고친 프레임에서도 사람이 건드리지 않은
     * 라벨은 그대로 되살아나야 하고, 그 생산이력의 출처는 언제나 서버가 읽은 스냅샷이다.
     *
     * <p>그 회차 이하 스냅샷이 아예 없는 프레임({@code resolved=false})은 <b>현재 작업본을 유지</b>한다 —
     * 없는 과거를 추측해 라벨을 지우지 않는다. {@code edits} 가 있으면 그 내용으로만 저장한다.
     */
    private FramePlan planFor(LsDataSrc frame, Long lblVer,
                              VideoLabelSaveRequest.FrameEdit edit, Map<Long, Long> snapshotTargets) {
        Optional<VersionSnapshotReader.FrameSnapshot> snapshot =
                snapshotReader.readFrame(snapshotTargets, frame.getSrcSn());
        Map<Long, LabelService.RestoreHint> hints = snapshot
                .map(s -> toHints(s.items()))
                .orElseGet(Map::of);
        if (edit != null) {
            String snapshotDscdYn = snapshot
                    .map(VersionSnapshotReader.FrameSnapshot::dscdYn).orElse(null);
            String dscdYn = edit.dscdYn() != null ? edit.dscdYn() : snapshotDscdYn;
            // ★ 판정은 <b>값 비교</b>다 — "필드가 실렸는가"가 아니다 (F-1).
            //   화면은 폐기를 <b>토글하지 않아도</b> 회차 값을 그대로 실어 보낸다(전 프레임 판번호와
            //   같은 축으로 세트를 왕복시키기 때문). 필드 존재만 보면 <b>라벨만 고친 정상 저장이
            //   400</b> 이 되어, 이 게이트의 javadoc 이 명시한 "승인 영상의 라벨 수정은 여전히 허용된다"
            //   를 정면으로 막는다.
            //   ⚠ FE 가 값을 생략하게 만드는 방식으로 풀지 않는다 — 서버가 클라이언트의 성실성에
            //     의존하게 되고(신뢰경계), 소비자가 생산자 조건을 재유도하는 드리프트가 된다.
            //   ⚠ 경로 단위 예외(항상 허용)로도 풀지 않는다 — 회차를 한 번 불러오는 것만으로
            //     edits[].dscdYn 에 임의 값을 실어 차단을 우회할 수 있게 된다.
            //   스냅샷을 모르는 프레임은 비교 기준이 없으므로 <b>보수적으로 새 조작</b>으로 본다.
            boolean newDiscardOperation = edit.dscdYn() != null
                    && (snapshotDscdYn == null || !edit.dscdYn().equals(snapshotDscdYn));
            return new FramePlan(new LabelBulkUpsertRequest(edit.items(), lblVer, dscdYn), hints,
                    !newDiscardOperation);
        }
        if (snapshot.isEmpty()) {
            // 그 회차를 알 수 없는 프레임 — 본문을 건드리지 않는다(현재 라벨을 그대로 재전송).
            //   판번호만 검증되고 폐기 여부도 유지된다(dscdYn=null → "현재 값 유지" 규약).
            return new FramePlan(new LabelBulkUpsertRequest(
                    currentItemsOf(frame.getSrcSn()), lblVer, null), Map.of(), true);
        }
        return new FramePlan(new LabelBulkUpsertRequest(
                toItems(snapshot.get().items()), lblVer, snapshot.get().dscdYn()), hints, true);
    }

    /**
     * 스냅샷 항목을 저장 요청 항목으로 옮긴다.
     *
     * <p>{@code labelId} 를 반드시 함께 옮긴다 — 라벨 마스터 연결의 실체이고, 잃으면 재조회 시 표시
     * 색상·라벨명·속성 정의가 함께 끊긴다(저장 전에는 정상으로 보여 발견이 늦는 실사고 유형).
     * 생산이력은 요청 항목에 담지 않는다(신뢰경계) — 별도 복원 힌트로 전달된다.
     */
    private List<LabelItemDto> toItems(List<LabelResponse.Item> items) {
        List<LabelItemDto> converted = new ArrayList<>(items.size());
        for (LabelResponse.Item item : items) {
            converted.add(new LabelItemDto(item.id(), item.lblTypeCd(), item.labelId(), item.label(),
                    item.points(), null, null, null, null, item.trackId()));
        }
        return converted;
    }

    /** 회차 스냅샷의 생산이력·추적 식별자를 {@code LBL_SN} 으로 인덱싱한다. */
    private Map<Long, LabelService.RestoreHint> toHints(List<LabelResponse.Item> items) {
        Map<Long, LabelService.RestoreHint> hints = new HashMap<>(items.size());
        for (LabelResponse.Item item : items) {
            if (item.id() == null) {
                continue;
            }
            hints.put(item.id(), new LabelService.RestoreHint(
                    item.autoLblYn(), item.confScore(), item.lblSrcCd(), item.trackId()));
        }
        return hints;
    }

    /** 그 회차를 알 수 없는 프레임의 현재 라벨 — 무변경 재전송용(본문을 추측하지 않는다). */
    private List<LabelItemDto> currentItemsOf(Long srcSn) {
        List<LabelItemDto> items = new ArrayList<>();
        for (LsDataLbl label : labelRepository.findBySrcSn(srcSn)) {
            items.add(new LabelItemDto(label.getLblSn(), label.getLblTypeCd(), label.getLabelId(),
                    label.getLabelNm(), pointsOf(label), null, null, null, null, label.getTrackId()));
        }
        return items;
    }

    private List<List<Double>> pointsOf(LsDataLbl label) {
        return LabelResponse.Item.from(label, null, null, objectMapper).points();
    }

    /** {@code frameVersions} 를 인덱싱한다 — <b>중복 프레임은 400</b>. */
    private Map<Long, Long> indexVersions(List<VideoLabelSaveRequest.FrameVersion> versions) {
        Map<Long, Long> indexed = new HashMap<>(versions.size());
        for (VideoLabelSaveRequest.FrameVersion version : versions) {
            if (version.srcSn() == null) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "srcSn 은 필수입니다.");
            }
            if (indexed.put(version.srcSn(), version.lblVer()) != null) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "같은 프레임이 두 번 실려 있습니다.");
            }
        }
        return indexed;
    }

    /**
     * {@code edits} 를 인덱싱한다 — <b>중복은 400</b>, <b>그 영상 소속이 아니면 404</b>.
     *
     * <p>{@code frameVersions} 에 없는 프레임이 {@code edits} 에만 오면 판번호가 없어 낙관적 동시성
     * 검증을 우회한다 — 그래서 대조 집합은 <b>판번호 목록</b>이다. 그 목록은 이미 영상 소속 검증을
     * 통과한 집합이므로 소속 검증을 겸한다.
     */
    private Map<Long, VideoLabelSaveRequest.FrameEdit> indexEdits(
            List<VideoLabelSaveRequest.FrameEdit> edits, Set<Long> versionedSrcSns) {
        Map<Long, VideoLabelSaveRequest.FrameEdit> indexed = new HashMap<>(edits.size());
        for (VideoLabelSaveRequest.FrameEdit edit : edits) {
            if (edit.srcSn() == null) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "srcSn 은 필수입니다.");
            }
            if (!versionedSrcSns.contains(edit.srcSn())) {
                throw new CustomException(ErrorCode.NOT_FOUND, "프레임을 찾을 수 없습니다.");
            }
            if (indexed.put(edit.srcSn(), edit) != null) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "같은 프레임이 두 번 실려 있습니다.");
            }
        }
        return indexed;
    }

    /**
     * 어느 산출 회차에서 시작한 확정인지 남긴다 (CWE-778 · OWASP A09).
     *
     * <p>남겨야 하는 것은 "영상 전체를 이 회차 상태로 확정하기로 했다"는 <b>비가역 결정</b>이다.
     * {@code loadedVersion} 은 필수이고 <b>그 영상에 실재하는 회차임을 이미 대조</b>했으므로, 검증을
     * 통과한 값만 실린다(검증되지 않은 클라이언트 주장을 감사로 남기지 않는다).
     *
     * <p>{@code RSN} 에는 회차 번호 한 토큰만 싣는다(자유 문구·본문 금지 — CWE-359). 값이 정수 타입이라
     * 로그 인젝션 축(CWE-117)은 타입 자체로 닫힌다.
     */
    private void recordStartVersionAudit(Long rawSn, Long actorNo, Integer loadedVersion) {
        taskEventLogRepository.save(LsTaskEventLog.startVersionApplied(rawSn, actorNo, loadedVersion));
    }
}
