package kr.co.cudo.authoring.version.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.label.service.FrameDiscardApplier;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.version.config.StartVersionProperties;
import kr.co.cudo.authoring.version.dto.SnapshotVersionRef;
import kr.co.cudo.authoring.version.dto.StartVersionApplyResult;
import kr.co.cudo.authoring.version.dto.VideoVersionItem;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.version.repository.LsOutputVerSnpshRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * R6 — 영상 단위 「시작 버전 선택」.
 *
 * <h3>무엇을 하는가</h3>
 * 검수 완료 영상에 수정을 시작할 때 <b>어느 산출 버전 상태에서 시작할지</b>를 고르면, 그 시점의
 * 라벨 본문과 <b>프레임 폐기 상태</b>를 영상 전체에 일괄 복원한다(예: {@code v1} 을 고르면 {@code v2}
 * 에서 폐기됐던 프레임이 되살아난다).
 *
 * <h3>D4 — 「제거」가 아니라 「재배치」다</h3>
 * 버전 목록 · 버전 간 diff · 작업본 diff · 롤백은 <b>폐기되지 않는다</b>. 이 서비스는 그 넷을 영상
 * 단위 동선으로 묶는 오케스트레이션일 뿐이며, 프레임 단위 계약({@code /v1/frames/{srcSn}/versions} ·
 * {@code /v1/versions/{version}/diff} · {@code /diff-with-working} · {@code /rollback})은 그대로다
 * (선택 전 미리보기 = 기존 diff, 확정 = 이 경로).
 *
 * <h3>조회 규칙 — 회차↔스냅샷 <b>매핑</b>에서 「회차 ≤ N 중 최대」</h3>
 * 판정 원천은 {@code LS_OUTPUT_VER_SNPSH}(V183) 한 곳이다. 내용이 바뀌지 않은 프레임은
 * {@code (DATA_SRC_SN, VERSION_HASH)} UNIQUE 때문에 그 회차에 스냅샷이 생기지 않으므로(멱등 skip —
 * 정상 동작) 여전히 "≤ N 중 최대"로 고르지만, <b>기준이 번호가 아니라 매핑에 기록된 회차</b>다.
 *
 * <p><b>왜 {@code VER_NO} 로는 안 되나</b>: 그 컬럼은 값이 하나라 <b>한 스냅샷이 여러 회차의 내용</b>
 * (1:N)임을 담지 못한다. 롤백으로 옛 스냅샷을 재활성한 뒤 재승인·재산출하면 그 회차의 실제 내용은
 * 옛 스냅샷인데 번호는 갱신되지 않아, 번호 기반 규칙이 <b>그 사이 회차의 비활성 스냅샷</b>을 골라
 * <b>그 회차에 존재한 적 없는 내용</b>으로 되돌렸다(예외도 미해결 집계도 없는 조용한 오복원).
 * {@code VER_NO} 는 조회·표시(회차 목록·존재 대조)용으로 <b>남아 있으나 판정에 쓰지 않는다</b>.
 *
 * <p>매핑이 없는 프레임(그 회차 이전에 승인 스냅샷이 한 번도 없던 프레임, 라벨 0건 프레임)은
 * 건드리지 않고 {@link StartVersionApplyResult#unresolvedFrames()} 로 드러낸다.
 *
 * <h3>자원 상한과 중복 실행 차단 (CWE-770)</h3>
 * 요청 1건이 영상의 전 프레임 라벨을 교체하고 프레임 행 락을 커밋까지 보유하므로,
 * ①같은 영상에 대한 <b>동시 실행</b>은 non-blocking advisory 잠금으로 즉시 {@code 409} 로 끊고
 * ②프레임 수가 설정 상한({@link StartVersionProperties#maxFrames()})을 넘으면 {@code 400} 으로 거부한다
 * (조용히 잘라내면 절반만 되돌아간 혼합 영상이 된다).
 *
 * <h3>부분 실패 — 전체 트랜잭션</h3>
 * 프레임 순회 전체가 <b>한 트랜잭션</b>이다. 중간 프레임에서 실패하면 앞 프레임의 복원까지 함께
 * 롤백된다 — 영상의 절반만 과거 버전인 <b>혼합 상태</b>를 만들지 않기 위함이다.
 * 반면 <b>스냅샷이 없는 프레임은 실패가 아니라 건너뜀</b>이며 결과에 건수로 드러낸다
 * ({@link StartVersionApplyResult#unresolvedFrames()}).
 *
 * <h3>잠금 순서 (변경 금지)</h3>
 * 프레임을 {@code FRAME_NO} 오름차순으로 순회하며 프레임마다 <b>VERSION → SRC → LBL</b> 로 잡는다
 * ({@link VersionService#rollbackToSnapshot} 이 그 순서를 소유). 영상 전 프레임을
 * {@code lockFramesByRawSn} 으로 <b>선점하지 않는</b> 이유는, 그러면 SRC 를 쥔 채 VERSION 을 요구해
 * 기존 롤백 경로(VERSION → SRC)와 정확히 반대 방향이 되어 즉시 ABBA(40P01)가 성립하기 때문이다.
 * 순회 순서는 승인 스냅샷 경로({@code VersionService.commitApproved})와 같은 정렬을 쓴다 —
 * 두 다중 프레임 경로가 같은 순서로 잠가야 서로 교차하지 않는다.
 * {@code LS_DATA_RAW}·{@code LS_RAW_DATA_STATUS} 는 잠그지 않아 배치와의 교착 축과 무관하다
 * ({@code LockOrderGuardTest} 불변식 유지).
 *
 * <h3>보안</h3>
 * <ul>
 *   <li>IDOR (CWE-639): 영상 단위 인가({@link LabelAccessGuard#verifyRawAccess}) + 요청 버전이
 *       <b>그 영상에</b> 실재하는지 대조. 대조 없이 번호를 믿으면 다른 영상 스냅샷을 끌어와 오염된다.</li>
 *   <li>TOCTOU (CWE-367): 비식별 신고 게이트를 진입부뿐 아니라 <b>커밋 직전에 한 번 더</b> 평가한다.
 *       아래 "게이트 재판정" 참조.</li>
 *   <li>CWE-209: 응답·메시지가 영상 처리 단계를 알려주는 오라클이 되지 않게 사유를 좁히지 않는다.
 *       게이트 순서도 같은 이유로 <b>신고(412) → 작업락(409)</b> 이다(아래).</li>
 *   <li>CWE-778: 비가역 조작이므로 <b>누가 어느 회차를 골랐는지</b>를 영상 단위 감사 이력으로 남긴다.</li>
 *   <li>CWE-359: 로그·감사에 라벨 본문·좌표·경로를 남기지 않는다(식별자·건수만).</li>
 * </ul>
 *
 * <h3>게이트 순서 — 신고(412)가 작업락(409)보다 <b>먼저</b> (C-ISSUE-22 확정)</h3>
 * 비식별 누락 신고는 작업락과 {@code DE_IDNTF_YN='F'} 를 함께 세우는데, 락은 6시간 뒤
 * {@code WorkLockSweepJob} 이 회수하고 {@code 'F'} 는 resolve 까지 남는다. 락을 먼저 보면 같은 영상이
 * <b>신고 직후엔 409, 6시간 뒤엔 412</b> 를 주어 응답 코드가 내부 잠금 상태를 알려주는 오라클이 된다.
 * 그래서 신고 구간은 락 유무와 무관하게 항상 412 다({@code LabelService.bulkUpsert} 와 같은 순서).
 *
 * <h3>게이트 재판정 — 루프가 끝난 뒤 한 번 더 (CWE-367)</h3>
 * 진입부 판정 이후 프레임 순회가 수초~수분 돈다. 그 사이 <b>사용자 개입 없이도</b> 비식별 배치 실패
 * 경로({@code BatchTransitionService}/{@code KpstDeidentTxService})가 작업락 없이 {@code 'F'} 를
 * 커밋할 수 있고, READ COMMITTED 라 이 루프는 그 변화를 보지 못한 채 남은 전 프레임의 라벨을 계속
 * 교체한다(승인 영상이면 산출물 전량 재생성까지). 그래서 <b>커밋 직전</b>에 같은 게이트를 다시
 * 평가해 전체를 롤백시킨다 — 새 문장 스냅샷이 커밋된 {@code 'F'} 를 관측한다.
 *
 * <p><b>이 재판정은 무잠금이며, 산출 마감이 같은 창을 닫는 방식과 <u>다르다</u></b>(같다고 적었던
 * 구 주석은 사실이 아니다). {@code DatasetExportTxService.finalizeUnlessUnderDeidentReport} 는
 * {@code DeidentReportGate.isUnderDeidentReportLocked}({@code findByRawSnForUpdate})로 <b>RAW 행을 잠근 채</b>
 * 판정해 신고 UPDATE 와 직렬화하지만, 여기서는 {@code LabelAccessGuard.requireNotUnderDeidentReport}
 * ({@code isUnderDeidentReport}) 라 직렬화되지 않는다. 즉 신고 트랜잭션이 <b>아직 커밋 전인</b>
 * 구간(ms~수십 ms)은 이 재판정도 통과한다 — 그 잔여 창은 <b>인지·수용</b>한다.
 *
 * <p><b>잠금 변형으로 바꾸면 안 된다.</b> 이 트랜잭션은 이 시점에 이미 VERSION·SRC·LBL 을 보유하므로
 * 여기서 RAW 행 락을 요구하면 <b>VERSION → RAWrow</b> 간선이 생긴다. 그런데 산출 마감은 위처럼
 * <b>RAWrow</b> 를 먼저 잡은 뒤 {@code OutputVersionStamper.stamp} 로 <b>VERSION</b> 을 갱신해
 * <b>RAWrow → VERSION</b> 간선을 갖는다 — 둘이 정확히 ABBA 라 40P01(교착)이 성립한다.
 * 같은 이유로 RAW 행 락 선점도 쓰지 않는다({@code LockOrderGuardTest} 불변식).
 *
 * @design D4
 * @design D5
 * @req R6
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class StartVersionService {

    private final LabelAccessGuard accessGuard;
    private final VideoRepository videoRepository;
    private final WorkLockService workLockService;
    private final LsDataSrcRepository srcRepository;
    private final LsLabelVersionRepository labelVersionRepository;
    /** 회차↔스냅샷 매핑(V183) — 되돌릴 대상 판정의 <b>단일 원천</b>. */
    private final LsOutputVerSnpshRepository outputVerSnpshRepository;
    /** 롤백 시맨틱(재활성·보존 복원·이력·멱등 no-op·통지)의 소유자 — 여기서 재구현하지 않는다. */
    private final VersionService versionService;
    /** R4·R5 폐기·복원 상태 전이 + 감사의 단일 적용 지점 — 새 경로를 만들지 않는다. */
    private final FrameDiscardApplier frameDiscardApplier;
    private final ApplicationEventPublisher eventPublisher;
    private final ReviewApprovalGate approvalGate;
    /** CWE-778 — 비가역 조작의 영상 단위 감사(누가·어느 회차). */
    private final LsTaskEventLogRepository taskEventLogRepository;
    private final StartVersionProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 「시작 버전 선택」 전용 advisory 잠금 네임스페이스(2키 형식의 첫 키).
     *
     * <p>PostgreSQL 의 2키 advisory 공간은 1키 공간과 겹치지 않으므로, 선존
     * {@code pg_advisory_xact_lock(rawSn)}(승인 동결·환경메타·개인정보 PUT)과 <b>절대 경합하지 않는다</b>.
     * 이 키를 잡는 코드가 이 서비스 하나뿐이라 어떤 조합으로도 잠금 순환이 성립하지 않는다.
     */
    static final int START_VERSION_LOCK_CLASS = 0x5356; // 'SV'

    /**
     * 영상 단위 산출 버전 목록(내림차순) — 「시작 버전 선택」의 선택지.
     *
     * <p>본문을 내려주지 않으므로(번호·건수·시각뿐) 비식별 신고 게이트를 적용하지 않는다 —
     * 프레임 버전 목록({@code VersionService.listVersions})과 같은 판단이다.
     */
    public List<VideoVersionItem> listVideoVersions(Long rawSn, TokenClaims actor) {
        accessGuard.verifyRawAccess(rawSn, actor);
        videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
        return labelVersionRepository.findVideoVersions(rawSn);
    }

    /**
     * 선택한 산출 버전 상태로 영상 전체를 되돌린다(라벨 본문 + 프레임 폐기 상태).
     *
     * <p>게이트 순서는 <b>인가 → 존재 → 신고(412) → 작업락(409) → 중복 실행(409) → 버전 대조(404)
     * → 프레임 상한(400)</b> 이다. 인가를 가장 먼저 두어 이후 응답이 미인가자에게 영상 상태를 알려주지
     * 않게 하고, 신고를 락보다 먼저 두어 응답 코드가 잠금 상태 오라클이 되지 않게 한다(클래스 javadoc).
     */
    @Transactional("controlTransactionManager")
    public StartVersionApplyResult applyStartVersion(Long rawSn, Integer versionNo, TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        if (rawSn == null || versionNo == null || versionNo < 1) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "시작 버전 번호가 올바르지 않습니다.");
        }
        accessGuard.verifyRawAccess(rawSn, actor);
        LsDataRaw raw = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
        // ★ 신고 게이트가 작업락보다 <b>먼저</b>다 — 같은 사유에 409/412 가 갈리면 응답이 잠금 상태를
        //   알려주는 오라클이 된다(CWE-209, C-ISSUE-22 확정. LabelService.bulkUpsert 와 같은 순서).
        accessGuard.requireNotUnderDeidentReport(rawSn);
        // 라벨 본문을 통째로 교체하므로 프레임 단위 롤백과 <b>같은</b> 전제 조건을 요구한다.
        //   여기 남는 409 는 <b>신고와 무관한 락</b>(트랙 병합 등 일시적 충돌)뿐이다.
        if (workLockService.isRawLocked(rawSn)) {
            throw new CustomException(ErrorCode.CONFLICT, "작업이 잠긴 영상은 되돌릴 수 없습니다.");
        }
        acquireExclusiveOrConflict(rawSn);
        // CWE-639 — 요청 번호를 신뢰하지 않는다. 그 영상에 실재하는 회차일 때만 진행한다.
        //   ★ 여기서 404 를 내는 것이 핵심이다: 번호가 없는데 그냥 진행하면 "해석 결과 0건 = 성공"이
        //   되어 화면이 <b>"변경 없음"</b>으로 표시한다(거짓말). 채번 이전 스냅샷만 있는 과도기 영상도
        //   이 경로로 명시 거부된다.
        if (!labelVersionRepository.existsByDataRawSnAndVersionNo(rawSn, versionNo)) {
            throw new CustomException(ErrorCode.NOT_FOUND, "해당 산출 버전의 스냅샷을 찾을 수 없습니다.");
        }

        Long actorNo = accessGuard.parseUserNo(actor.sub());
        // ★ 상한은 <로드 이전>에 판정한다 — 엔티티를 먼저 읽고 세면 거부할 영상도 프레임 행이 전량
        //   힙에 올라온 뒤에야 거부되어, 상한이 지키려던 자원을 지키지 못한다(CWE-770).
        requireWithinFrameLimit(rawSn, srcRepository.countByRawSn(rawSn));
        // 승인 스냅샷 경로와 같은 정렬 — 다중 프레임 경로끼리 같은 순서로 잠가야 교차하지 않는다.
        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);
        Map<Long, Long> targetByFrame = resolveTargets(rawSn, versionNo);

        int applied = 0;
        int revived = 0;
        int discarded = 0;
        int unresolved = 0;
        boolean approved = approvalGate.isApproved(rawSn);
        for (LsDataSrc frame : frames) {
            Optional<LsLabelVersion> target = loadTarget(targetByFrame.get(frame.getSrcSn()));
            if (target.isEmpty()) {
                // 되돌릴 근거가 없는 프레임 — 라벨도 폐기여부도 건드리지 않는다(추측 금지).
                unresolved++;
                continue;
            }
            LsLabelVersion snapshot = target.get();
            // 라벨 본문 복원(프레임 행 락 획득 포함)이 먼저다 — 폐기 적용은 그 락 구간 안에서 이뤄져야
            //   두 축(라벨셋·폐기)이 서로 다른 시점으로 갈라지지 않는다.
            versionService.rollbackToSnapshot(raw, frame, snapshot, actor);
            applied++;

            FrameDiscardApplier.Outcome outcome = frameDiscardApplier.apply(frame.getSrcSn(), frame,
                    SnapshotDiscardPolicy.resolve(
                            snapshot.getLabelPayload(), objectMapper, frame.getSrcSn()),
                    actorNo);
            if (outcome == FrameDiscardApplier.Outcome.RESTORED) {
                revived++;
            } else if (outcome == FrameDiscardApplier.Outcome.DISCARDED) {
                discarded++;
            }
            publishDiscardChange(approved, rawSn, frame.getSrcSn(), outcome, actorNo);
        }

        // CWE-367 — 진입부 판정 이후 루프가 도는 동안 비식별 배치 실패 경로가 작업락 없이
        //   DE_IDNTF_YN='F' 를 커밋할 수 있다(READ COMMITTED 라 위 루프는 그 변화를 보지 못한다).
        //   커밋 직전에 다시 평가해 그 구간의 라벨 교체·산출 재생성을 <b>전체 롤백</b>시킨다.
        //   새 문장 스냅샷이 <커밋된> 'F' 를 관측한다. 무잠금이라 신고가 아직 커밋 전인 좁은 구간은
        //   통과하며(인지·수용), 여기서 RAW 행 락을 잡으면 산출 마감과 ABBA 가 된다(클래스 javadoc).
        accessGuard.requireNotUnderDeidentReport(rawSn);

        // CWE-778 — 비가역 조작이므로 "누가 어느 회차를 골랐는가"를 영상 단위로 남긴다.
        //   프레임별 이력(LS_DATA_LBL_HSTRY 롤백 이벤트 · 폐기/복원 감사)만으로는 ①전 프레임이 멱등
        //   no-op 이면 흔적이 하나도 남지 않고 ②고른 회차 번호가 어디에도 없다(해시에서 역산해야 한다).
        //   판단값이 아니라 식별자 한 토큰만 싣는다(CWE-359 — LsTaskEventLog.RSN_FRAME_PREFIX 선례).
        taskEventLogRepository.save(LsTaskEventLog.startVersionApplied(rawSn, actorNo, versionNo));

        log.info("[Version] start version applied rawSn={} versionNo={} frames={} applied={} "
                        + "revived={} discarded={} unresolved={} actor={}",
                rawSn, versionNo, frames.size(), applied, revived, discarded, unresolved, actor.sub());
        if (unresolved > 0) {
            // 조용한 누락 방지 — 되돌리지 못한 프레임이 있었다는 사실을 운영에서도 관측할 수 있게 한다.
            log.warn("[Version] start version left frames untouched rawSn={} versionNo={} unresolved={}",
                    rawSn, versionNo, unresolved);
        }
        return new StartVersionApplyResult(rawSn, versionNo, frames.size(), applied,
                revived, discarded, unresolved);
    }

    /**
     * 같은 영상에 대한 <b>동시 실행</b>을 즉시 끊는다 (CWE-770).
     *
     * <p>대기시키지 않는 이유: 이 작업은 커넥션을 장시간 쥐므로, 줄을 세우면 대기자들도 커넥션을 쥔 채
     * 남아 고갈을 키운다. 잠금은 트랜잭션 종료 시 자동 해제되어 노드가 죽어도 고착되지 않는다.
     */
    private void acquireExclusiveOrConflict(Long rawSn) {
        if (!labelVersionRepository.tryAcquireVideoVersionLock(
                START_VERSION_LOCK_CLASS, rawSn.intValue())) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "이 영상의 시작 버전 적용이 이미 진행 중입니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    /**
     * 요청 1건이 처리할 프레임 수 상한 (CWE-770).
     *
     * <p>초과분을 잘라내지 않고 <b>거부</b>한다 — 일부 프레임만 되돌아간 영상은 서로 다른 회차가 섞인
     * 혼합 상태이고, 이 서비스가 전체 트랜잭션으로 막으려는 바로 그 상태다.
     *
     * <p>입력은 <b>{@code count} 선조회</b> 결과다(엔티티 로드 이전). 이 판정과 실제 로드 사이에 프레임이
     * 늘어나면 로드 크기가 상한을 근소하게 넘을 수 있으나, 이 상한은 <b>정합성 불변식이 아니라 자원
     * 경계</b>이고 상한을 <b>몇 배로</b> 넘기는 입력(그래서 위험한 입력)은 선조회에서 그대로 걸린다.
     */
    private void requireWithinFrameLimit(Long rawSn, long frameCount) {
        if (frameCount > properties.maxFrames()) {
            log.warn("[Version] start version rejected — frame limit exceeded rawSn={} frames={} limit={}",
                    rawSn, frameCount, properties.maxFrames());
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "프레임이 너무 많아 한 번에 되돌릴 수 없습니다(최대 "
                            + properties.maxFrames() + "장).");
        }
    }

    /**
     * 프레임마다 <b>회차 ≤ N 중 가장 큰 회차</b>의 스냅샷을 고른다(값: 스냅샷 행 PK).
     *
     * <p>원천은 회차↔스냅샷 매핑({@code LS_OUTPUT_VER_SNPSH}) 하나다 — {@code LS_LABEL_VERSION.VER_NO}
     * 로 다시 유도하지 않는다(그 컬럼은 1:N 을 담지 못해 조용한 오복원을 냈다. 클래스 javadoc 참조).
     *
     * <p>결측 필드가 있는 참조는 걸러낸다 — 조회 경로가 하나 더 생겨도 규칙이 조용히 무너지지 않게
     * 하기 위한 이중 방어다. 같은 회차가 둘이면(UK 상 불가능) 행 PK 가 큰 쪽을 택해 결과를 결정적으로
     * 만든다.
     */
    private Map<Long, Long> resolveTargets(Long rawSn, Integer versionNo) {
        Map<Long, SnapshotVersionRef> best = new HashMap<>();
        for (SnapshotVersionRef ref : outputVerSnpshRepository.findRefsUpTo(rawSn, versionNo)) {
            if (ref.versionNo() == null || ref.dataSrcSn() == null || ref.labelVersionSn() == null) {
                continue;
            }
            SnapshotVersionRef current = best.get(ref.dataSrcSn());
            if (current == null || isLater(ref, current)) {
                best.put(ref.dataSrcSn(), ref);
            }
        }
        Map<Long, Long> resolved = new HashMap<>(best.size());
        best.forEach((srcSn, ref) -> resolved.put(srcSn, ref.labelVersionSn()));
        return resolved;
    }

    private static boolean isLater(SnapshotVersionRef candidate, SnapshotVersionRef current) {
        int byVersion = candidate.versionNo().compareTo(current.versionNo());
        return byVersion > 0
                || (byVersion == 0 && candidate.labelVersionSn() > current.labelVersionSn());
    }

    private Optional<LsLabelVersion> loadTarget(Long labelVersionSn) {
        return labelVersionSn == null ? Optional.empty() : labelVersionRepository.findById(labelVersionSn);
    }

    /**
     * 폐기 상태가 실제로 바뀐 승인 영상에 재산출 통지를 발행한다.
     *
     * <p>라벨 본문 변경 통지는 {@link VersionService#rollbackToSnapshot} 이 이미 발행한다. 그런데
     * <b>라벨은 그대로인데 폐기 상태만 되돌아간</b> 프레임은 그 경로가 no-op 이라 통지가 없다 —
     * 그대로 두면 관제가 되살아난(혹은 사라진) 프레임을 영영 모른다. {@code LabelService.bulkUpsert}
     * 의 폐기·복원 통지와 <b>같은 축·같은 플래그</b>({@code exportRegenerated=true},
     * {@code needsRecheck=true})를 쓴다.
     */
    private void publishDiscardChange(boolean approved, Long rawSn, Long srcSn,
                                      FrameDiscardApplier.Outcome outcome, Long actorNo) {
        if (!approved || outcome == null || !outcome.isChanged()) {
            return;
        }
        String changeType = outcome == FrameDiscardApplier.Outcome.DISCARDED
                ? ChangeType.FRAME_DISCARDED : ChangeType.FRAME_RESTORED;
        eventPublisher.publishEvent(
                new TaskModifiedEvent(rawSn, srcSn, changeType, actorNo, true, true));
    }
}
