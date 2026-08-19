package kr.co.cudo.authoring.dev.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.step.DeidentFrameAttacher;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.DeidentArtifactIntegrity;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.dev.dto.DeidentFrameRecoveryResponse;
import kr.co.cudo.authoring.dev.service.DeidentFrameNoBackfillTxService.FrameNoFix;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [개발/검수 전용] <b>레거시 비식별 프레임 복구</b> — {@code LS_DATA_SRC.DE_IDNTF_SRC_FILE_PATH_NM} 이
 * NULL 로 남은 영상의 비식별 프레임을 되살린다.
 *
 * <h3>왜 필요한가</h3>
 * <p>프레임 추출기의 self-invocation 결함을 고치기 <b>이전</b>에 적재된 영상은 비식별 프레임 경로가
 * 비어 있다. 프레임 이미지 API 는 비식별본만 서빙하므로({@code PRVC}/{@code PSDO} 영상) 경로가 없으면
 * <b>404</b> 가 되어 라벨링·검수 화면이 빈 화면이 된다. 코드는 이미 고쳐졌고 신규 영상은 정상이므로,
 * 남은 것은 <b>기존 행 복구</b> 하나다.
 *
 * <h3>복구 순서 (Critical — 뒤집으면 조용히 아무것도 안 한다)</h3>
 * <ol>
 *   <li><b>{@code VDO_FRM_NO} 복원</b> — 재추출 위치를 모르면 {@link DeidentFrameAttacher} 가 그 프레임을
 *       <b>순번 폴백 없이 전부 skip</b> 한다(의도된 fail-closed). 값은 {@code LS_MARKING.MARK_CN} 의
 *       {@code frameIndex} 배열에서 되살린다.</li>
 *   <li>복원 트랜잭션 <b>커밋</b> — attacher 는 {@code REQUIRES_NEW} 라 자기 트랜잭션에서 다시 읽는다
 *       ({@link DeidentFrameNoBackfillTxService} javadoc).</li>
 *   <li><b>비식별 프레임 재추출</b> — {@link DeidentFrameAttacher#attachDeidentFrames} 재사용.
 *       기존 행을 dirty-update 하므로 {@code SRC_SN} 이 보존되어 라벨 FK 가 끊기지 않는다.
 *       ⚠ {@code FfmpegFrameExtractor} 를 쓰면 안 된다 — 새 행을 INSERT 해 기존 라벨이 고아가 된다.</li>
 * </ol>
 *
 * <h3>{@code VDO_FRM_NO} ↔ 마킹 매핑 규칙 (fail-closed)</h3>
 * <p>초기 추출은 마킹 배열을 순서대로 돌며 {@code LsDataSrc.create(rawSn, i, mark.frameIndex(), …)} 로
 * <b>추출 순번 i</b> 와 <b>영상 내 위치 frameIndex</b> 를 각각 적재한다. 따라서 {@code FRM_NO} 오름차순
 * 프레임 행과 마킹 {@code frameIndex} 배열은 <b>같은 순서의 1:1 대응</b>이다(마킹 배열을 정렬하지 않는다 —
 * 정렬하면 그 대응이 깨진다).
 *
 * <p><b>개수가 다르면 복원하지 않는다.</b> 어느 마킹이 어느 프레임인지 특정할 수 없고, 위치가 어긋나면
 * 기존 라벨 좌표가 <b>엉뚱한 장면 위에</b> 얹힌다. 추측 매핑은 조용한 데이터 오염이므로 그 영상은
 * 건너뛰고 사유를 결과에 담는다. 이미 값이 있는 행과 매핑 결과가 <b>충돌</b>하면 매핑 자체가
 * 틀렸다는 신호이므로 역시 건너뛴다.
 *
 * <h3>도메인 이벤트를 발행하지 않는다 (구속)</h3>
 * <p>이 복구는 {@code LS_DATA_SRC} 의 경로·위치 컬럼만 채운다. 라벨·메타·산출물 내용은 바뀌지 않으므로
 * {@code TaskModifiedEvent} 등 어떤 도메인 이벤트도 발행하지 않는다 — 발행하면 승인 영상의 export 가
 * 새 버전으로 재생성되고 관제 재통지까지 나간다(재생성·통지 트리거는 「검수 승인」 한 곳뿐이라는 구속
 * 정책 위반). 재사용하는 {@link DeidentFrameAttacher} 도 이벤트 발행 배선이 없다.
 *
 * <h3>트랜잭션 경계</h3>
 * <p>이 오케스트레이터는 <b>{@code @Transactional} 이 아니다</b>. 재추출은 프레임마다 ffmpeg 파일 I/O 를
 * 하므로 커넥션을 쥔 채 돌면 커넥션 기아로 간다(이 저장소가 겪은 결함). 각 쓰기는 협력 빈이 자기
 * {@code REQUIRES_NEW} 경계에서 커밋하며, 한 영상의 실패가 다른 영상을 롤백하지 않는다(부분 진행 허용).
 *
 * <h3>보안</h3>
 * <ul>
 *   <li><b>경로 조작(CWE-22/59/367)</b>: 비식별 영상 경로는 {@code LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM}
 *       <b>적재값만</b> 쓰고 조합·추측하지 않는다. 허용 base 목록
 *       ({@link VideoArtifactRootResolver#readableDeidVideoBases})과 실경로 판정
 *       ({@link VideoArtifactRootResolver#resolveRealPathUnder})을 <b>재사용</b>하며, 판정이 돌려준
 *       실경로를 그대로 넘긴다.</li>
 *   <li><b>원본 폴백 금지(CWE-359)</b>: 비식별 경로가 없거나 판정에 걸리면 <b>건너뛴다</b>.
 *       원본(마스킹 전) 영상으로 대체하지 않는다.</li>
 *   <li><b>정보 노출(CWE-209/532)</b>: 로그·응답에 경로·파일명·PII 를 싣지 않는다(rawSn·건수·
 *       서버가 고른 사유 코드만). 요청값이 응답으로 반사되지 않는다.</li>
 *   <li><b>신고 구간 차단(CWE-359)</b>: {@link DeidentReportGate} 로 {@code DE_IDNTF_YN='F'} 영상을
 *       <b>진입부에서</b> 건너뛴다({@link SkipReason#UNDER_DEIDENT_REPORT}). 마스킹 실패가 확인된
 *       비식별본에서 프레임을 뽑아 경로를 채우면 그 값이 {@code V_COMPLETED_FRAME.DEIDENTIFIED_PATH}
 *       로 관제에 나가 <b>없던 노출을 새로 만든다</b>. dry-run 도 같은 판정을 탄다.</li>
 *   <li><b>자원 소모(CWE-770)</b>: 전체 모드는 1회 {@link #MAX_VIDEOS_PER_RUN} 건 상한을 강제하고,
 *       같은 영상에 대한 동시 실행은 {@link #inFlight} 클레임으로 409 거절한다(커넥션·ffmpeg 점유 곱셈
 *       방지 + 같은 출력 파일 동시 write 방지).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeidentFrameRecoveryService {

    /** 1회 호출당 처리 영상 상한 — 무제한 조회·폭주 방지. 잔여는 응답으로 알리고 재호출로 이어간다. */
    public static final int MAX_VIDEOS_PER_RUN = 50;

    private static final TypeReference<List<MarkItem>> MARK_LIST = new TypeReference<>() {
    };

    private static final String RESULT_RECOVERED = "RECOVERED";
    private static final String RESULT_PLANNED = "PLANNED";
    private static final String RESULT_SKIPPED = "SKIPPED";
    private static final String RESULT_FAILED = "FAILED";

    private final VideoRepository videoRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsMarkingRepository markingRepository;
    private final LsDeidentProcLogRepository procLogRepository;
    private final DeidentFrameNoBackfillTxService frameNoBackfillTxService;
    private final DeidentFrameAttacher deidentFrameAttacher;
    private final VideoArtifactRootResolver artifactRootResolver;
    private final DeidentReportGate deidentReportGate;
    private final ObjectMapper objectMapper;

    /**
     * 영상 단위 <b>진행 중 클레임</b> — 같은 영상에 복구가 이미 돌고 있으면 새 요청을 409 로 끊는다
     * (F2/F3 · CWE-770/362). 재추출은 {@code REQUIRES_NEW} 트랜잭션 <b>안에서</b> 프레임마다 ffmpeg 을
     * 돌리므로 영상 1건 처리 내내 DB 커넥션 1개를 점유한다. 응답이 안 온다고 재클릭하거나 탭을 여러 개
     * 열면 ffmpeg 프로세스와 커넥션 점유가 곱해져 HikariCP 고갈 → 앱 전체 5xx 로 간다. 파일 축도
     * 같은 문제다 — 두 실행이 겹치면 같은 출력 경로({@code frames/deid/&#123;rawSn&#125;/frame-*.jpg})에 두 ffmpeg 이
     * 동시에 write 해 <b>부분 기록된 JPEG</b> 이 확정될 수 있다.
     *
     * <p><b>노드-로컬 best-effort 다</b>(선례 {@code AutolabelOnlineService.inFlight}). 2노드
     * Active-Active 배포에서는 각 노드가 독립 Set 을 가지므로 <b>노드 간 동시 실행은 막지 못한다</b>.
     * 이 API 는 dev/검수 전용 수동 도구(prd 미노출)이고 방어 대상이 "사람의 재클릭"이라 DB 원자 클레임
     * (조건부 UPDATE + 전용 원장 테이블)까지 두지 않았다. 노드 간 차단이 필요해지면 그때 원장으로 올린다.
     */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * 건너뜀 사유 — 응답 {@code reason} 의 <b>유일한 원천</b>. 서버가 고른 열거값만 나가므로 요청값이
     * 응답으로 반사되지 않는다(CWE-79/209).
     */
    enum SkipReason {
        /**
         * 비식별 누락 신고 구간({@code DE_IDNTF_YN='F'})이다 — 그 비식별본은 <b>마스킹 실패가 확인된
         * 것</b>이라 프레임을 뽑아 경로를 채우면 없던 노출을 새로 만든다(CWE-359). 해소 후 재실행한다.
         */
        UNDER_DEIDENT_REPORT,
        /** 영상 행이 없다. */
        VIDEO_NOT_FOUND,
        /** 프레임 행이 하나도 없다(아직 추출 전). */
        NO_FRAMES,
        /** 비식별 프레임 경로가 이미 모두 채워져 있다(복구 불필요 — 멱등 재실행의 정상 결과). */
        ALREADY_RECOVERED,
        /** 비식별 처리 성공 이력에 결과 경로가 적재돼 있지 않다. */
        NO_DEID_VIDEO_PATH,
        /** 적재된 비식별 영상 경로가 허용 저장소 경계 밖(또는 실경로 확인 불가)이다. */
        DEID_VIDEO_PATH_REJECTED,
        /** 비식별 영상 파일이 없거나 사용할 수 없다. */
        DEID_VIDEO_MISSING,
        /** VDO_FRM_NO 복원이 필요한데 마킹이 없다. */
        NO_MARKING,
        /** 마킹 본문을 프레임 위치 배열로 읽을 수 없다. */
        MARK_PARSE_FAILED,
        /** 프레임 수와 마킹 수가 달라 1:1 대응을 특정할 수 없다(추측 매핑 금지). */
        MARK_COUNT_MISMATCH,
        /** 이미 채워진 VDO_FRM_NO 가 마킹 매핑 결과와 달라 매핑 자체를 신뢰할 수 없다. */
        VDO_FRM_NO_CONFLICT,
        /** 재추출 중 실패(해상도 불일치·원본 프레임 부재·ffmpeg 오류 등) — 그 영상만 롤백된다. */
        ATTACH_FAILED
    }

    /**
     * dry-run — 대상과 예상 결과만 돌려주고 <b>아무것도 변경하지 않는다</b>.
     *
     * @param rawSn 단건 대상(선택). null 이면 전체 대상을 상한만큼.
     */
    public DeidentFrameRecoveryResponse preview(Long rawSn) {
        return run(rawSn, true);
    }

    /**
     * 실제 복구 — {@code VDO_FRM_NO} 복원 후 비식별 프레임을 재추출한다.
     *
     * <p><b>멱등</b>: 복원은 {@code WHERE VDO_FRM_NO IS NULL} 조건부 UPDATE, 재추출은
     * {@code refreshExisting=false} 라 이미 경로가 있는 프레임을 건드리지 않는다. 두 번째 호출은
     * 대상 자체가 사라져 {@link SkipReason#ALREADY_RECOVERED} 이거나 대상 0건이다.
     *
     * @param rawSn 단건 대상(선택). null 이면 전체 대상을 상한만큼.
     */
    public DeidentFrameRecoveryResponse recover(Long rawSn) {
        return run(rawSn, false);
    }

    private DeidentFrameRecoveryResponse run(Long rawSn, boolean dryRun) {
        if (dryRun) {
            // dry-run 은 읽기 전용이라 ffmpeg·파일 쓰기 경합을 만들지 않는다 — 진행 중에도 상태를
            // 볼 수 있어야 하므로 클레임을 타지 않는다.
            return process(resolveTargets(rawSn), true);
        }
        List<Long> claimed = claim(resolveTargets(rawSn));
        try {
            return process(claimed, false);
        } finally {
            // 해제하지 않으면 그 영상은 재기동 전까지 영구히 409 가 된다(예외 경로 포함).
            claimed.forEach(inFlight::remove);
        }
    }

    /**
     * 대상 영상을 <b>전부</b> 클레임한다. 하나라도 이미 진행 중이면 <b>아무것도 시작하지 않고</b>
     * 409 로 끊는다(부분 실행 금지 — 절반만 도는 것이 운영자에게 더 혼란스럽다).
     *
     * <p>실패 시 이미 확보한 클레임을 되돌려 놓는다. 두 전체 모드 실행이 서로 엇갈려 <b>둘 다</b>
     * 409 가 될 수는 있으나, 그때도 쓰기는 한 건도 일어나지 않으므로 데이터는 안전하다(재호출로 해소).
     *
     * <p>응답 메시지에 어느 영상이 진행 중인지 싣지 않는다 — 존재·상태 오라클이 되지 않게(CWE-209).
     */
    private List<Long> claim(List<Long> targets) {
        List<Long> claimed = new ArrayList<>(targets.size());
        for (Long target : targets) {
            if (!inFlight.add(target)) {
                claimed.forEach(inFlight::remove);
                log.warn("[DevRecovery] recovery already in progress rawSn={} — reject", target);
                throw new CustomException(ErrorCode.CONFLICT, "이미 복구가 진행 중입니다. 완료 후 다시 시도해 주세요.");
            }
            claimed.add(target);
        }
        return claimed;
    }

    private DeidentFrameRecoveryResponse process(List<Long> targets, boolean dryRun) {
        List<DeidentFrameRecoveryResponse.Item> items = new ArrayList<>(targets.size());
        int recovered = 0;
        int skipped = 0;
        int restoredTotal = 0;
        int attachedTotal = 0;

        for (Long target : targets) {
            DeidentFrameRecoveryResponse.Item item = processOne(target, dryRun);
            items.add(item);
            if (RESULT_RECOVERED.equals(item.result())) {
                recovered++;
            } else if (!RESULT_PLANNED.equals(item.result())) {
                skipped++;
            }
            if (!dryRun) {
                restoredTotal += item.videoFrameNoRestored();
                attachedTotal += item.deidFrameAttached();
            }
        }

        long remaining = srcRepository.countRawSnsMissingDeidFramePath();
        log.info("[DevRecovery] deident frame recovery dryRun={} targets={} recovered={} skipped={} remaining={}",
                dryRun, targets.size(), recovered, skipped, remaining);
        return new DeidentFrameRecoveryResponse(dryRun, items.size(), remaining,
                recovered, skipped, restoredTotal, attachedTotal, List.copyOf(items));
    }

    /** 단건 지정이면 그 하나만, 아니면 비식별 프레임 경로가 빈 영상을 상한만큼. */
    private List<Long> resolveTargets(Long rawSn) {
        if (rawSn != null) {
            return List.of(rawSn);
        }
        return srcRepository.findRawSnsMissingDeidFramePath(PageRequest.of(0, MAX_VIDEOS_PER_RUN));
    }

    private DeidentFrameRecoveryResponse.Item processOne(long rawSn, boolean dryRun) {
        // ── 비식별 누락 신고 게이트 (CWE-359) — 마킹 파싱·파일 I/O 이전, 가장 먼저.
        //
        //  신고 구간('F')의 비식별본은 <b>마스킹 실패가 확인된</b> 영상이다. 거기서 프레임을 뽑아
        //  DE_IDNTF_SRC_FILE_PATH_NM 을 채우면 그 값이 V_COMPLETED_FRAME.DEIDENTIFIED_PATH 로 관제에
        //  노출된다 — 지금은 NULL 이라 관제가 아무것도 가져갈 수 없는데, 이 복구가 <b>없던 노출을
        //  새로 만든다</b>. 또 해소(resolve) 재비식별과 겹치면 같은 출력 파일을 제자리 교체하므로
        //  복구가 나중에 끝날 경우 <b>재비식별 이전(마스킹 실패) 프레임이 최종본으로 굳고</b>, 경로가
        //  채워져 있어 어떤 재처리 트리거도 걸리지 않는다.
        //
        //  판정은 DeidentReportGate 단일 원천을 재사용한다(호출부마다 재구현하면 정책이 갈라진다).
        //  예외가 아니라 <b>건너뜀</b>이다 — 전체 모드에서 다른 영상 처리를 막지 않는다. dry-run 도
        //  같은 판정을 타므로 운영자가 대상 목록에서 미리 걸러 볼 수 있다.
        //  조회가 DB 오류로 실패하면 예외가 그대로 전파돼 복구가 진행되지 않는다(fail-closed).
        if (deidentReportGate.isUnderDeidentReport(rawSn)) {
            log.warn("[DevRecovery] recovery withheld — deident report open rawSn={}", rawSn);
            return skip(rawSn, SkipReason.UNDER_DEIDENT_REPORT, 0, null, 0, 0);
        }

        LsDataRaw raw = videoRepository.findById(rawSn).orElse(null);
        if (raw == null) {
            return skip(rawSn, SkipReason.VIDEO_NOT_FOUND, 0, null, 0, 0);
        }

        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);
        if (frames.isEmpty()) {
            return skip(rawSn, SkipReason.NO_FRAMES, 0, null, 0, 0);
        }
        int frameCount = frames.size();
        int missingDeidPath = (int) frames.stream().filter(f -> isBlank(f.getDeIdntfSrcFilePathNm())).count();
        if (missingDeidPath == 0) {
            return skip(rawSn, SkipReason.ALREADY_RECOVERED, frameCount, null, 0, 0);
        }

        // ── 비식별 영상 확보 (원본 폴백 없음 — fail-closed)
        String deidPathValue = procLogRepository.findLatestSuccessByDataRawSn(rawSn)
                .map(LsDeidentProcLog::getDeIdntfFilePathNm)
                .filter(p -> !isBlank(p))
                .orElse(null);
        if (deidPathValue == null) {
            log.warn("[DevRecovery] no deidentified video path recorded rawSn={} — skip", rawSn);
            return skip(rawSn, SkipReason.NO_DEID_VIDEO_PATH, frameCount, null, 0, missingDeidPath);
        }
        Path deidVideo = resolveVerifiedDeidVideo(raw, deidPathValue);
        if (deidVideo == null) {
            log.warn("[DevRecovery] deidentified video path rejected rawSn={} — skip (no original fallback)", rawSn);
            return skip(rawSn, SkipReason.DEID_VIDEO_PATH_REJECTED, frameCount, null, 0, missingDeidPath);
        }
        if (!DeidentArtifactIntegrity.isValidVideoArtifact(deidVideo.toString())) {
            log.warn("[DevRecovery] deidentified video unusable rawSn={} — skip (no original fallback)", rawSn);
            return skip(rawSn, SkipReason.DEID_VIDEO_MISSING, frameCount, null, 0, missingDeidPath);
        }

        // ── VDO_FRM_NO 복원 계획 (필요할 때만 마킹을 읽는다)
        boolean needsFrameNo = frames.stream().anyMatch(f -> f.getVideoFrameNo() == null);
        List<FrameNoFix> fixes = List.of();
        Integer markCount = null;
        if (needsFrameNo) {
            List<Integer> frameIndexes = loadMarkFrameIndexes(rawSn);
            if (frameIndexes == null) {
                return skip(rawSn, markingReasonFor(rawSn), frameCount, null, 0, missingDeidPath);
            }
            markCount = frameIndexes.size();
            if (markCount != frameCount) {
                log.warn("[DevRecovery] frame/mark count mismatch rawSn={} frames={} marks={} — skip (no guessing)",
                        rawSn, frameCount, markCount);
                return skip(rawSn, SkipReason.MARK_COUNT_MISMATCH, frameCount, markCount, 0, missingDeidPath);
            }
            fixes = planFrameNoFixes(rawSn, frames, frameIndexes);
            if (fixes == null) {
                return skip(rawSn, SkipReason.VDO_FRM_NO_CONFLICT, frameCount, markCount, 0, missingDeidPath);
            }
        }

        if (dryRun) {
            return new DeidentFrameRecoveryResponse.Item(rawSn, RESULT_PLANNED, null,
                    frameCount, markCount, missingDeidPath, fixes.size(), missingDeidPath);
        }

        // ── ① VDO_FRM_NO 복원 (별도 트랜잭션에서 커밋) → ② 재추출
        //    복원할 것이 없으면 트랜잭션 경계를 열지 않는다(구간 B — 위치는 이미 있고 경로만 빈 영상).
        int restored = fixes.isEmpty() ? 0 : frameNoBackfillTxService.restore(rawSn, fixes);
        int attached;
        try {
            attached = deidentFrameAttacher.attachDeidentFrames(raw, deidVideo, false);
        } catch (RuntimeException e) {
            // 개별 영상 실패는 격리한다 — 그 영상의 재추출 트랜잭션만 롤백되고 나머지는 계속 진행.
            // 예외 메시지에 경로가 실릴 수 있어 응답·로그 어디에도 싣지 않는다(CWE-209).
            log.warn("[DevRecovery] deident frame attach failed rawSn={} type={}",
                    rawSn, e.getClass().getSimpleName());
            return new DeidentFrameRecoveryResponse.Item(rawSn, RESULT_FAILED,
                    SkipReason.ATTACH_FAILED.name(), frameCount, markCount, missingDeidPath, restored, 0);
        }
        return new DeidentFrameRecoveryResponse.Item(rawSn, RESULT_RECOVERED, null,
                frameCount, markCount, missingDeidPath, restored, attached);
    }

    /**
     * 마킹 본문에서 영상 내 프레임 위치 배열을 읽는다. 마킹이 없거나 읽을 수 없으면 {@code null}.
     *
     * <p>어느 마킹을 읽는지는 초기 추출과 <b>같은 정렬</b>(등록일시·PK 내림차순 = 최신 1건)을 쓴다 —
     * 다른 마킹을 고르면 매핑이 통째로 어긋난다.
     */
    private List<Integer> loadMarkFrameIndexes(long rawSn) {
        List<LsMarking> markings = markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(rawSn);
        if (markings.isEmpty()) {
            return null;
        }
        String markCn = markings.get(0).getMarkCn();
        if (isBlank(markCn)) {
            return null;
        }
        List<MarkItem> marks;
        try {
            marks = objectMapper.readValue(markCn, MARK_LIST);
        } catch (Exception e) {
            log.warn("[DevRecovery] marking payload unreadable rawSn={} type={}",
                    rawSn, e.getClass().getSimpleName());
            return null;
        }
        if (marks == null || marks.isEmpty()) {
            return null;
        }
        List<Integer> indexes = new ArrayList<>(marks.size());
        for (MarkItem mark : marks) {
            if (mark == null || mark.frameIndex() == null || mark.frameIndex() < 0) {
                log.warn("[DevRecovery] marking payload has invalid frameIndex rawSn={}", rawSn);
                return null;
            }
            indexes.add(mark.frameIndex());
        }
        return indexes;
    }

    /** 마킹을 못 읽은 원인 구분 — 마킹 자체가 없으면 {@code NO_MARKING}, 있으면 파싱 실패다. */
    private SkipReason markingReasonFor(long rawSn) {
        return markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(rawSn).isEmpty()
                ? SkipReason.NO_MARKING
                : SkipReason.MARK_PARSE_FAILED;
    }

    /**
     * {@code FRM_NO} 오름차순 프레임 ↔ 마킹 배열의 위치 대응으로 복원 지시를 만든다.
     * 이미 값이 있는 행이 매핑과 <b>다르면</b> 매핑을 신뢰할 수 없으므로 {@code null}(전체 건너뜀).
     */
    private List<FrameNoFix> planFrameNoFixes(long rawSn, List<LsDataSrc> frames, List<Integer> frameIndexes) {
        List<FrameNoFix> fixes = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) {
            LsDataSrc frame = frames.get(i);
            long mapped = frameIndexes.get(i);
            Long current = frame.getVideoFrameNo();
            if (current == null) {
                fixes.add(new FrameNoFix(frame.getSrcSn(), mapped));
            } else if (current != mapped) {
                log.warn("[DevRecovery] existing VDO_FRM_NO conflicts with marking mapping rawSn={} position={}"
                        + " — skip (mapping cannot be trusted)", rawSn, i);
                return null;
            }
        }
        return fixes;
    }

    /**
     * 비식별 <b>영상</b> 경로 판정 — 허용 base 목록과 실경로 판정기를 <b>재사용</b>하고, 판정이 돌려준
     * 실경로를 그대로 반환한다. 어느 base 에도 속하지 않으면 {@code null}(fail-closed).
     *
     * <p>⚠ 프레임(이미지)의 판정기({@code StorageSubtreePolicy.verifyDeidentifiedFile})를 영상에 쓰면
     * co-locate 기본 형상이 전부 거부돼 기능이 죽는다 — 축이 다르다.
     */
    private Path resolveVerifiedDeidVideo(LsDataRaw raw, String deidPathValue) {
        Path candidate;
        try {
            candidate = Paths.get(deidPathValue);
        } catch (InvalidPathException e) {
            return null;
        }
        List<Path> bases;
        try {
            bases = artifactRootResolver.readableDeidVideoBases(raw.getRawSn(), raw.getRawFilePathNm());
        } catch (RuntimeException e) {
            return null;
        }
        for (Path base : bases) {
            try {
                return VideoArtifactRootResolver.resolveRealPathUnder(candidate, base);
            } catch (RuntimeException ignored) {
                // 이 base 밖 — 다음 후보로. 전부 실패하면 거부한다(넓히지 않는다).
            }
        }
        return null;
    }

    private DeidentFrameRecoveryResponse.Item skip(long rawSn, SkipReason reason, int frameCount,
                                                   Integer markCount, int restored, int missingDeidPath) {
        return new DeidentFrameRecoveryResponse.Item(rawSn, RESULT_SKIPPED, reason.name(),
                frameCount, markCount, missingDeidPath, restored, 0);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
