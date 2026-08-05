package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.step.DeidentFrameAttacher;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.label.entity.LsDeidentReport;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 비식별 누락 신고 해소 후 <b>신고 단계별 작업 재개</b> 처리 (V171).
 *
 * <h3>재개 지점 (사용자 확정, 구속)</h3>
 * <table border="1">
 *   <caption>신고 단계 → 재개 지점</caption>
 *   <tr><th>신고 단계</th><th>재개 지점</th></tr>
 *   <tr><td>{@code MARKING}</td><td>비식별 재수행 결과 위에서 <b>마킹부터 다시</b></td></tr>
 *   <tr><td>{@code LABELING}</td><td><b>프레임 이미지만 재추출</b>하고 라벨링을 이어간다(마킹 유지 · 라벨 좌표 보존)</td></tr>
 * </table>
 *
 * <h3>왜 별도 빈의 {@code REQUIRES_NEW} 인가</h3>
 * <p>호출자({@link kr.co.cudo.authoring.label.listener.DeidentStageResumeBridge})는
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 라 <b>활성 트랜잭션이 없는</b> 컨텍스트에서 실행된다.
 * 엔티티를 조회·변경해도 dirty checking 이 작동하지 않아 영속되지 않으며, 같은 빈 내부
 * {@code @Transactional} 자기호출은 프록시를 우회한다({@code MarkingSkipTxService} 와 동일 구조).
 *
 * <h3>보안</h3>
 * <ul>
 *   <li><b>경로 조작(CWE-22)</b>: 비식별 영상 경로는 {@code LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM}
 *       <b>DB 적재값만</b> 사용한다. 파일명을 조합·추측하지 않는다 — mock 은 {@code deidentified.mp4},
 *       KPST 실연동은 {@code {원본stem}-mask{ext}} 라 영상마다 다르다(구속 규칙).</li>
 *   <li><b>Privacy(CWE-359)</b>: 경로 원문을 로그에 싣지 않는다(rawSn 만 기록).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeidentStageResumeService {

    private final VideoRepository videoRepository;
    private final LsMarkingRepository markingRepository;
    private final LsDeidentProcLogRepository procLogRepository;
    private final LsDataSrcRepository srcRepository;
    private final DeidentFrameAttacher deidentFrameAttacher;

    /**
     * 마킹 단계 재개 — 배치 단계 상태를 {@code MARKING_READY} 로 되감고 활성 마킹을 종결한다.
     *
     * <p><b>상태 되감기까지만 한다.</b> 실제 재실행은 사람이 다시 마킹하면 기존
     * {@code MarkingCompletedEvent → MarkingBatchBridge} 가 그대로 타므로, 여기서 파이프라인을
     * 재구현하지 않는다.
     *
     * <p><b>멱등</b>: 이미 {@code MARKING_READY} 면 상태를 건드리지 않고, 활성 마킹이 없으면 종결도
     * no-op 이다. 마킹 단계 신고는 {@code MARKING_READY} 에서만 접수되고 신고 구간에는 마킹 API 가
     * {@code DE_IDNTF_YN='Y'} 가드로 막히므로, 실제로는 대개 둘 다 no-op 인 <b>확인 성격</b>의 재개다.
     * 그럼에도 되감기를 수행하는 이유는 접수~해소 사이에 다른 경로가 상태를 옮겼을 때 재마킹 진입이
     * 영구히 닫히지 않게 하기 위함이다(fail-safe).
     *
     * <h3>★ 되감기 전 자기 방어 — "프레임이 아직 없다"를 직접 재확인한다 (fail-closed)</h3>
     * <p>{@link LsDataRaw#changeStatus} 는 전이 검증이 없는 단순 setter 라 <b>현재 상태가 무엇이든</b>
     * 되감긴다. 지금 이 되감기가 안전한 근거는 이 파일 밖(마킹 단계 신고 접수 조건 · {@code MarkingGuards} ·
     * {@code MarkingBatchBridge})에 흩어져 있고, 그중 어느 하나가 바뀌면 <b>여기는 아무것도 재확인하지
     * 않는다</b>. 프레임이 이미 추출된 영상을 되감으면 사람이 재마킹할 때
     * {@code FfmpegFrameExtractor} 가 {@code LsDataSrc.create()} 로 <b>새 행을 INSERT</b> 해
     * 기존 라벨이 고아가 된다(라벨링 재개가 {@link DeidentFrameAttacher} 를 쓰도록 막아 둔 바로 그 파괴 패턴).
     * 그래서 되감기 <b>직전</b>에 프레임 존재를 직접 확인하고, 있으면 조용히 성공하지 않고 예외로 중단한다.
     *
     * <p>호출자({@code DeidentStageResumeBridge})는 예외를 ERROR 로 흡수하므로 <b>재개 전체가 영구
     * 차단되지는 않는다</b>(해소 커밋 자체는 이미 끝났고 다른 리스너도 계속 실행된다). 다만 그 ERROR 는
     * 메시지만 남기므로, 무엇이 왜 막혔는지는 던지기 전에 이 메서드가 ERROR 로그로 특정한다.
     *
     * @return 되감기/종결 중 하나라도 실제로 수행했으면 {@code true}
     * @throws CustomException 프레임이 이미 존재하는 영상을 마킹으로 되감으려 한 경우(fail-closed)
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean resumeMarking(Long rawSn) {
        if (rawSn == null) {
            return false;
        }
        LsDataRaw raw = videoRepository.findById(rawSn).orElse(null);
        if (raw == null) {
            log.warn("[DeidentResume] raw video not found rawSn={} (marking resume) — skip", rawSn);
            return false;
        }

        // ★ 남의 불변식에 기대지 않는다 — 되감기 직전에 "프레임이 아직 없다"를 직접 재확인(fail-closed).
        long frameCnt = srcRepository.countByRawSn(rawSn);
        if (frameCnt > 0) {
            log.error("[DeidentResume] marking resume BLOCKED rawSn={} frames={} currentStage={}"
                            + " — 프레임이 이미 추출된 영상은 마킹 단계로 되감지 않는다."
                            + " 되감으면 재마킹 시 FfmpegFrameExtractor 가 LS_DATA_SRC 새 행을 INSERT 해 기존 라벨이 고아가 된다."
                            + " 점검 대상: 마킹 단계 신고가 MARKING_READY 밖에서 접수됐는가 / 배치 진입점·게이팅이 바뀌었는가.",
                    rawSn, frameCnt, raw.getDataSttsCd());
            throw new CustomException(ErrorCode.CONFLICT,
                    "프레임이 이미 추출된 영상은 마킹 단계로 되감을 수 없습니다 rawSn=" + rawSn);
        }

        boolean changed = false;
        if (!LsDataRaw.DATA_STTS_MARKING_READY.equals(raw.getDataSttsCd())) {
            raw.markMarkingReady();
            changed = true;
            log.info("[DeidentResume] batch stage rewound to MARKING_READY rawSn={}", rawSn);
        }

        // 활성 마킹이 1건이라도 남으면 재마킹이 409(V142 부분 유니크)로 막힌다 → 종결시켜 진입을 연다.
        List<LsMarking> actives = markingRepository.findByRawSnAndSttsCdIn(rawSn, LsMarking.ACTIVE_STATUSES);
        for (LsMarking marking : actives) {
            if (marking.markSkippedForRedeident()) {
                changed = true;
                log.warn("[DeidentResume] active marking terminated for re-marking rawSn={} markingSn={}",
                        rawSn, marking.getMarkingSn());
            }
        }
        return changed;
    }

    /**
     * 라벨링 단계 재개 — <b>프레임 이미지만 재추출</b>한다(마킹 유지 · 라벨 좌표 보존).
     *
     * <p>{@link DeidentFrameAttacher#attachDeidentFrames}({@code refreshExisting=true})를 재사용한다.
     * 이 컴포넌트는 기존 {@code LS_DATA_SRC} 행을 dirty-update 하므로 {@code SRC_SN} 이 보존되고 라벨
     * FK 가 끊기지 않는다. ⚠ {@code FfmpegFrameExtractor} 를 쓰면 안 된다 — {@code LsDataSrc.create()} 로
     * <b>새 행을 INSERT</b> 해 기존 라벨이 고아가 된다.
     *
     * <p>비식별 영상 경로는 {@code LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM}(최신 SUCCEEDED) <b>적재값</b>을
     * 읽는다. 경로가 없으면 재추출을 <b>건너뛴다</b>(fail-closed — 파일명을 추측해 엉뚱한 영상에서
     * 뽑느니 옛 프레임을 남기고 WARN 으로 드러낸다).
     *
     * @return 실제로 재추출한 프레임 수 (건너뛰었으면 0)
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public int resumeLabeling(Long rawSn) {
        if (rawSn == null) {
            return 0;
        }
        LsDataRaw raw = videoRepository.findById(rawSn).orElse(null);
        if (raw == null) {
            log.warn("[DeidentResume] raw video not found rawSn={} (labeling resume) — skip", rawSn);
            return 0;
        }
        Path deidVideo = resolveDeidVideo(rawSn);
        if (deidVideo == null) {
            return 0;
        }
        int attached = deidentFrameAttacher.attachDeidentFrames(raw, deidVideo, true);
        log.info("[DeidentResume] deident frames re-extracted rawSn={} frames={}", rawSn, attached);
        return attached;
    }

    /**
     * 비식별 영상 경로 해석 — DB 적재값만 사용한다(조합·추측 금지). 없으면 {@code null}.
     */
    private Path resolveDeidVideo(Long rawSn) {
        String deidPath = procLogRepository.findLatestSuccessByDataRawSn(rawSn)
                .map(LsDeidentProcLog::getDeIdntfFilePathNm)
                .orElse(null);
        if (deidPath == null || deidPath.isBlank()) {
            log.warn("[DeidentResume] no deidentified video path recorded rawSn={} — frame re-extraction skipped",
                    rawSn);
            return null;
        }
        try {
            return Paths.get(deidPath);
        } catch (InvalidPathException e) {
            log.warn("[DeidentResume] invalid deidentified video path rawSn={} — frame re-extraction skipped",
                    rawSn);
            return null;
        }
    }

    /** 단계 코드 → 재개 실행 (브리지가 얇게 위임하기 위한 단일 진입점). */
    public void resume(Long rawSn, String stage) {
        if (LsDeidentReport.STAGE_MARKING.equals(stage)) {
            resumeMarking(rawSn);
        } else if (LsDeidentReport.STAGE_LABELING.equals(stage)) {
            resumeLabeling(rawSn);
        } else {
            // 단계 미상(NULL)은 애초에 이벤트를 발행하지 않으므로 여기 도달하지 않는다(fail-safe 로깅).
            log.warn("[DeidentResume] unknown report stage rawSn={} — no resume action", rawSn);
        }
    }
}
