package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.DeidentArtifactIntegrity;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.service.port.ImageResizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * DeidentFrameAttacher (Phase 2 — frame-exact 재설계) — 검수완료 영상 비식별 프레임 attach 전용 컴포넌트.
 *
 * <p>이미 비식별 완료된 영상({@code deidVideo})에서, 기존 프레임 행({@link LsDataSrc})이 가리키는
 * <b>영상 내 위치</b>의 비식별 프레임을 추출해 <b>같은 행에 attach</b>한다. 새 프레임 행을 만들지
 * 않으므로 라벨(LS_DATA_LBL)은 보존된다. 해상도 불일치 시 fail-closed(예외+롤백),
 * 멱등(이미 attach된 행 skip).
 *
 * <p><b>frame-exact 정합(재설계)</b>: 비식별 영상은 원본에 마스킹만 한 것이라 프레임 시퀀스가 동일하므로
 * "원본 N번 프레임"과 "비식별 N번 프레임"은 같은 장면이다. 따라서 fps 가정·seek 변환 없이
 * 프레임 번호로 직접 추출({@code ffmpeg select=eq(n,N)})한다.
 *
 * <p><b>★ 그 "N" 은 {@link LsDataSrc#getVideoFrameNo() VDO_FRM_NO}(실제 영상 내 위치)다</b> —
 * {@link LsDataSrc#getFrameNo() FRM_NO}(추출 순번)가 아니다. 구 구현은 순번을 넘겨 마킹 위치가
 * 1000·2000·3000 인 영상에서 <b>영상 맨 앞 0·1·2 번</b>을 뽑아 붙였다(라벨 좌표와 픽셀이 어긋남).
 * 상세·NULL 정책은 {@code resolveVideoFrameNo} javadoc 참조.
 *
 * <p><b>출력 파일명은 여전히 {@code frame-{FRM_NO}.jpg}</b> 다 — 초기 추출({@link FfmpegFrameExtractor})이
 * 같은 이름으로 쓴 파일을 <b>제자리 교체</b>해야 구 파일이 고아로 남지 않는다(디렉토리도 동일:
 * {@code frames/deid/{rawSn}}). 즉 "어디서 뽑는가"만 바뀌고 "어디에 쓰는가"는 불변이다.
 *
 * <p><b>책임 분리</b>: 이 컴포넌트는 프레임 attach 전용이다. de_idntf_yn/prvc/상태 전이·KPST 호출·락은
 * 상위 서비스(Phase 3) 책임이며 여기서 다루지 않는다. 라벨 레포에는 접근하지 않는다(생성자 의존 없음).
 *
 * <p><b>경로 스킴(스킴 A) 통일</b>: 비식별 프레임 출력 디렉토리는 {@code {baseDeidPath}/frames/deid/{rawSn}}
 * 로, {@link FfmpegFrameExtractor} 의 초기 비식별 추출 경로({@link FrameKind#DEID})와 <b>동일 위치</b>다.
 * 재비식별은 같은 위치를 갱신하는 것이 의도된 의미이며, 구 스킴({@code frames/{rawSn}}) 으로 추출된
 * 원본 프레임을 덮어쓸 위험을 제거한다. 세그먼트 문자열은 {@link FrameKind} 가 단일 정의한다.
 *
 * <p>보안:
 * <ul>
 *   <li>Path Manipulation (CWE-22): 출력 경로는 deidentified base 기반 + Path.normalize + base 검증.</li>
 *   <li>Privacy (CWE-209): 영상/프레임 경로는 hash 로 마스킹 후 로그 출력.</li>
 *   <li>fail-closed: 해상도 측정 불가/불일치 시 예외로 전체 롤백한다(임의 통과 금지).</li>
 * </ul>
 */
@Slf4j
@Component
public class DeidentFrameAttacher {

    /**
     * {@code VDO_FRM_NO} 결측으로 프레임 재추출을 건너뛴 사유 — {@code LS_BATCH_PROC_LOG} 적재/되읽기의
     * <b>단일 원천</b> 상수다.
     *
     * <p>{@link BatchStatusService#isStageSkippedWithReason} 가 <b>정확 일치</b>로 되읽으므로 건수 등
     * 가변값을 섞으면 안 된다(건수는 WARN 로그에만 남긴다).
     */
    public static final String SKIP_REASON_NO_VIDEO_FRAME_NO = "VDO_FRM_NO_MISSING";

    private final LsDataSrcRepository srcRepository;
    private final FfmpegFrameExtractor.FrameWriter frameWriter;
    private final ImageResizer imageResizer;
    private final BatchStatusService batchStatusService;

    private final Path baseDeidPath;

    public DeidentFrameAttacher(LsDataSrcRepository srcRepository,
                                FfmpegFrameExtractor.FrameWriter frameWriter,
                                ImageResizer imageResizer,
                                BatchStatusService batchStatusService,
                                @Value("${authoring.storage.deidentified-path:./storage/deidentified}") String storageDeidPath) {
        this.srcRepository = srcRepository;
        this.frameWriter = frameWriter;
        this.imageResizer = imageResizer;
        this.batchStatusService = batchStatusService;
        this.baseDeidPath = Paths.get(storageDeidPath).toAbsolutePath().normalize();
    }

    /**
     * 비식별 영상에서 기존 프레임 frm_no 번호 프레임을 직접 추출해 같은 LS_DATA_SRC 행에 attach.
     *
     * <p><b>단일 트랜잭션 진입점(self-invocation 제거)</b>: 본 메서드만 {@code @Transactional}
     * REQUIRES_NEW 를 갖는다. 과거 2-arg 오버로드가 3-arg 를 {@code this.}self-invoke 하던 구조는
     * Spring AOP 프록시가 내부 호출을 가로채지 못해 3-arg 의 트랜잭션이 무력화되는 혼동을 유발했다.
     * 프로덕션 유일 호출처({@link KpstDeidentTxService#applyRedeidentCompletion})가 본 3-arg 를
     * cross-bean 으로 직접 호출하므로 REQUIRES_NEW 경계가 정상 적용되고, 예외 시 전체 롤백된다.
     *
     * <p><b>재비식별(SC-009) 정합 — 개인정보 누락 프레임 교체</b>: 초기 파이프라인
     * {@link FfmpegFrameExtractor} 가 추출 시 이미 비식별 프레임도 생성하므로 검수완료(APPROVED) 영상은
     * 모든 프레임에 {@code de_idntf_src_file_path_nm} 이 이미 설정돼 있다. 재비식별 시 멱등 skip 이 동작하면
     * 전 프레임이 skip 되어 비식별 프레임이 옛 것(개인정보 누락 잔존) 그대로 남는다. 이를 막기 위해
     * {@code refreshExisting=true} 면 멱등 skip 을 우회하여 모든 프레임을 새 비식별 영상으로 재추출해
     * 같은 위치({@code frames/deid/{rawSn}})를 덮어쓰며 갱신한다(경로 재기록 포함).
     *
     * @param raw             대상 영상 메타 (rawSn 사용)
     * @param deidVideo       비식별 완료 영상 경로 (존재 + 크기>0 이어야 함)
     * @param refreshExisting true 면 이미 deident 경로가 있는 프레임도 강제 재추출(재비식별).
     *                        false 면 기존 멱등 동작(deident 경로 없는 프레임만 attach).
     * @return attach/재추출 처리한 프레임 수. 0건/전부 skip 이면 0.
     *         <b>0 의 의미는 두 가지</b>이고 반환값만으로는 구분되지 않는다 — "재추출할 프레임이 원래
     *         없었음" vs "{@code VDO_FRM_NO} 결측으로 전부 skip". 후자는 {@code PROC_STEP_CD='FRAME_EXTRACT' /
     *         PROC_STTS_CD='SKIPPED' / ERR_MSG_CN=}{@link #SKIP_REASON_NO_VIDEO_FRAME_NO} 감사 행이
     *         적재되므로 {@link BatchStatusService#isStageSkippedWithReason} 로 구분·추적한다.
     * @throws CustomException 비식별 영상 부재/0바이트, 해상도 측정 불가/불일치, 추출 실패 시 → 전체 롤백.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public int attachDeidentFrames(LsDataRaw raw, Path deidVideo, boolean refreshExisting) {
        if (raw == null || raw.getRawSn() == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "영상 메타가 비어있습니다.");
        }
        // 1. deidVideo 사용성 검증 — 존재 + 크기>0
        if (!isUsable(deidVideo)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "비식별 영상을 사용할 수 없습니다 rawSn=" + raw.getRawSn());
        }

        // 2. 기존 프레임 로드
        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(raw.getRawSn());
        if (frames.isEmpty()) {
            log.info("[Batch][DeidAttach] no frames to attach rawSn={}", raw.getRawSn());
            return 0;
        }

        Path outputDir = resolveSafeOutputDir(raw.getRawSn());
        ensureDir(outputDir);

        boolean resolutionVerified = false;
        int attached = 0;
        int skippedNoVideoFrameNo = 0;
        try {
            for (LsDataSrc src : frames) {
                // 4. 멱등 — 이미 비식별 경로가 있는 프레임은 skip.
                //    단 refreshExisting(재비식별, SC-009)이면 skip 을 우회해 강제 재추출(개인정보 누락 프레임 교체).
                if (!refreshExisting
                        && src.getDeIdntfSrcFilePathNm() != null
                        && !src.getDeIdntfSrcFilePathNm().isBlank()) {
                    continue;
                }

                // ★ 추출 위치는 VDO_FRM_NO(실제 영상 내 프레임 위치)다 — FRM_NO(추출 순번)가 아니다.
                //   값이 없으면 fail-closed 로 이 프레임만 건너뛴다(아래 resolveVideoFrameNo javadoc).
                Integer videoFrameNo = resolveVideoFrameNo(src, raw.getRawSn());
                if (videoFrameNo == null) {
                    skippedNoVideoFrameNo++;
                    continue;
                }

                // 출력 파일명은 <b>FRM_NO(추출 순번)</b> 를 쓴다 — 초기 추출(FfmpegFrameExtractor)이
                // frames/deid/{rawSn}/frame-{순번}.jpg 로 쓴 그 파일을 <b>제자리 교체</b>해야 하기 때문이다.
                // 여기서 파일명을 VDO_FRM_NO 로 바꾸면 구 파일이 고아로 남고 DB 경로만 갈아타 저장소가 샌다.
                int frameNo = Math.toIntExact(src.getFrameNo());
                Path deidFrameFile = resolveSafeFrameFile(outputDir, frameNo);

                // frame-exact: 영상 내 프레임 번호로 직접 추출(fps 무관). 비식별=원본 프레임 시퀀스 동일.
                frameWriter.writeFrameByNumber(deidVideo, deidFrameFile, videoFrameNo);

                // 3. 해상도 가드 — 첫 추출 프레임에서 1회 비교(fail-closed).
                if (!resolutionVerified) {
                    verifyResolution(src, deidFrameFile, raw.getRawSn());
                    resolutionVerified = true;
                }

                // 같은 행 갱신 (dirty checking — 새 LsDataSrc.create()/save() 금지).
                src.attachDeidPath(deidFrameFile.toString());
                attached++;
            }
        } catch (IOException e) {
            log.error("[Batch][DeidAttach] frame extraction failed rawSn={} err={}",
                    raw.getRawSn(), e.getMessage());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "비식별 프레임 추출에 실패했습니다.", e);
        }

        if (skippedNoVideoFrameNo > 0) {
            log.warn("[Batch][DeidAttach] skipped frames without VDO_FRM_NO rawSn={} skipped={} attached={}"
                            + " — 재추출 위치를 알 수 없어 옛 비식별 프레임을 그대로 둔다(수동 확인 필요)",
                    raw.getRawSn(), skippedNoVideoFrameNo, attached);
            // ★ 반환값(attach 건수)만으로는 "재추출할 프레임이 원래 없었음"(0건)과 "전부 skip 됨"(0건)이
            //   구분되지 않는다 — 후자는 마스킹 실패 픽셀이 그대로 남는데 호출자에겐 "성공"으로 보인다(CWE-359).
            //   B-ISSUE-24 선례대로 SKIPPED 감사 행을 적재해 운영이 재처리 대상을 식별할 수 있게 한다
            //   (되읽기: BatchStatusService.isStageSkippedWithReason(rawSn, FRAME_EXTRACT, SKIP_REASON_...)).
            //   ⚠ VDO_FRM_NO 는 V70 신설 이후 초기 추출에서만 채워지고 백필이 없다 — 레거시 행은 영구 NULL 이라
            //   재비식별로도 채워지지 않는다. 값을 지어내지 않으므로(백필 금지) 이 기록이 유일한 추적 수단이다.
            batchStatusService.recordStageSkipped(
                    raw.getRawSn(), BatchStage.FRAME_EXTRACT, SKIP_REASON_NO_VIDEO_FRAME_NO);
        }
        log.info("[Batch][DeidAttach] attached rawSn={} frames={}", raw.getRawSn(), attached);
        return attached;
    }

    /**
     * 재추출할 <b>영상 내 프레임 위치</b> 해석 — {@code VDO_FRM_NO}(nullable) 만 사용한다.
     *
     * <h3>왜 {@code FRM_NO} 를 쓰면 안 되는가 (선결 결함 수정)</h3>
     * <p>{@code FRM_NO} 는 <b>추출 순번</b>(0,1,2…)이고 {@code VDO_FRM_NO} 가 <b>실제 영상 내 위치</b>다
     * ({@code LsDataSrc} 필드 주석이 이 컬럼을 "재비식별 재추출용"이라 명시한다). 초기 추출
     * ({@code FfmpegFrameExtractor})은 마킹의 frameIndex 를 {@code VideoFrameTimeCalculator.millisAt} 로
     * seek 위치로 옮겨 <b>실제 위치</b>를 찾아 뽑고 {@code LsDataSrc.create(rawSn, i, mark.frameIndex(), …)}
     * 로 두 값을 각각 적재한다.
     * 구 구현은 재추출 시 {@code FRM_NO} 를 프레임 번호로 넘겨, 마킹이 영상 1000·2000·3000 번이면
     * 비식별 영상의 <b>0·1·2 번(영상 맨 앞)</b> 을 뽑아 붙였다 — 라벨 좌표는 원래 장면 기준이므로
     * "라벨 좌표 보존"이 성립하지 않았다.
     *
     * <h3>NULL(레거시 행) 은 fail-closed — 순번 폴백 금지</h3>
     * <p>{@code VDO_FRM_NO} 는 nullable 이라 이 컬럼 도입 이전 행에는 값이 없다. {@code FRM_NO} 로
     * 폴백하면 <b>지금 고치는 결함을 그대로 유지</b>하는 것이므로, 값이 없으면 그 프레임의 재추출을
     * 건너뛰고 WARN 으로 드러낸다(옛 비식별 프레임이 남는다 = 눈에 띄는 미해결 상태). 조용히 엉뚱한
     * 장면으로 덮어써 라벨과 픽셀이 어긋나는 것보다 낫다.
     *
     * <p>값이 <b>있지만 비정상(음수 등)</b> 이면 건너뛰지 않고 그대로 추출기에 넘긴다 — 그 경우
     * {@code FrameWriter} 가 {@code IOException} 을 던져 <b>전체 롤백</b>되므로, 조용한 skip 보다
     * 시끄러운 실패가 맞다(데이터 오염 신호를 감추지 않는다).
     *
     * @return 추출할 영상 내 프레임 번호. 값이 없으면(레거시 NULL) {@code null}(그 프레임만 건너뛴다).
     */
    private Integer resolveVideoFrameNo(LsDataSrc src, Long rawSn) {
        Long videoFrameNo = src.getVideoFrameNo();
        if (videoFrameNo == null) {
            log.warn("[Batch][DeidAttach] VDO_FRM_NO missing rawSn={} srcSn={} frmNo={} — skip (no index fallback)",
                    rawSn, src.getSrcSn(), src.getFrameNo());
            return null;
        }
        return Math.toIntExact(videoFrameNo);
    }

    /**
     * 비식별 영상 사용성 — 판정은 <b>단일 원천</b> {@link DeidentArtifactIntegrity} 에 위임한다
     * (DEV_FIX 2차 LOW-5).
     *
     * <p>구 구현은 "존재 + 크기&gt;0" 자체 판정이라, 상위(KPST 회수)가 이미 무결성 판정을 통과시킨
     * 값만 들어온다는 전제에 의존했다. 전제는 맞았지만 "판정은 한 곳" 이라는 불변식이 문자 그대로는
     * 성립하지 않아, 나중에 다른 진입점이 추가되면 18바이트 스텁이 여기까지 흘러올 수 있었다.
     * 통일 비용이 픽스처 1건({@code TestVideoFixtures.writeTinyMp4})뿐이라 축을 합쳤다.
     */
    private boolean isUsable(Path video) {
        return video != null && DeidentArtifactIntegrity.isValidVideoArtifact(video.toString());
    }

    /**
     * 해상도 가드(fail-closed): 비식별 출력 프레임이 원본 프레임과 동일 해상도여야 한다.
     * 원본 프레임 파일 부재 등으로 측정 불가하면 {@link ImageResizer#readDimensions} 가 예외를 던지며
     * 그대로 전파해 전체 롤백한다(임의 통과 금지).
     */
    private void verifyResolution(LsDataSrc src, Path deidFrameFile, Long rawSn) {
        // M-6 (null 가드) — 원본 경로가 결측인 프레임(해상도 파생 등)에서 Paths.get(null) NPE(500)가 난다.
        //                   결측은 "측정 불가"이므로 임의 통과가 아니라 명시적 실패로 처리한다.
        String origPath = src.getSrcFilePathNm();
        if (origPath == null || origPath.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "원본 프레임 경로가 없어 해상도를 검증할 수 없습니다 rawSn=" + rawSn);
        }
        int[] origDim = imageResizer.readDimensions(Paths.get(origPath));
        int[] deidDim = imageResizer.readDimensions(deidFrameFile);
        if (origDim == null || deidDim == null
                || origDim.length < 2 || deidDim.length < 2
                || origDim[0] <= 0 || origDim[1] <= 0
                || deidDim[0] <= 0 || deidDim[1] <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "원본/비식별 프레임 해상도를 측정할 수 없습니다 rawSn=" + rawSn);
        }
        if (origDim[0] != deidDim[0] || origDim[1] != deidDim[1]) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "비식별 출력 해상도가 원본과 다릅니다 rawSn=" + rawSn);
        }
    }

    /**
     * {@code baseDeidPath/frames/deid/{rawSn}} 디렉토리를 안전 해석 (Path Manipulation 방어, CWE-22).
     * <p>
     * 스킴 A 통일: {@link FfmpegFrameExtractor} 의 초기 비식별 추출과 동일하게 {@link FrameKind#DEID}
     * 세그먼트를 사용해, 원본 프레임({@code frames/raw}) 과 디렉토리가 충돌하지 않는다.
     * {@code frames/deid} 도 base 하위이므로 startsWith 가드를 통과한다.
     */
    private Path resolveSafeOutputDir(Long rawSn) {
        Path resolved = baseDeidPath.resolve("frames").resolve(FrameKind.DEID.getSegment())
                .resolve(String.valueOf(rawSn)).normalize();
        if (!resolved.startsWith(baseDeidPath)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "프레임 출력 경로가 허용된 저장 경로를 벗어납니다.");
        }
        return resolved;
    }

    /** outputDir/frame-{frameNo}.jpg 를 안전 해석 (Path Manipulation 방어). */
    private Path resolveSafeFrameFile(Path outputDir, int frameNo) {
        Path resolved = outputDir.resolve("frame-" + frameNo + ".jpg").normalize();
        if (!resolved.startsWith(baseDeidPath)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "프레임 출력 경로가 허용된 저장 경로를 벗어납니다.");
        }
        return resolved;
    }

    private void ensureDir(Path dir) {
        try {
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "프레임 출력 디렉토리 생성에 실패했습니다.", e);
        }
    }
}
