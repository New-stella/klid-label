package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
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
 * <p>이미 비식별 완료된 영상({@code deidVideo})에서, 기존 프레임 행({@link LsDataSrc})의 frm_no
 * 위치 비식별 프레임을 추출해 <b>같은 행에 attach</b>한다. 새 프레임 행을 만들지 않으므로
 * 라벨(LS_DATA_LBL)은 보존된다. 해상도 불일치 시 fail-closed(예외+롤백), 멱등(이미 attach된 행 skip).
 *
 * <p><b>frame-exact 정합(재설계)</b>: 비식별 영상은 원본에 마스킹만 한 것이라 프레임 시퀀스가 동일하므로
 * "원본 N번 프레임"과 "비식별 N번 프레임"은 같은 장면이다. 따라서 fps 가정·seek 변환 없이
 * {@link LsDataSrc#getFrameNo() frm_no} 번호로 직접 추출({@code ffmpeg select=eq(n,N)})한다.
 * 기존 seek 방식(frm_no × 1000 / fps)은 ① fps 가정 의존 ② frm_no 의미가 출처별 상이(v2 추출순번 vs
 * 마이그레이션 실프레임번호)라 좌표가 어긋났다 — frame-exact 로 이를 구조적으로 제거한다.
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

    private final LsDataSrcRepository srcRepository;
    private final FfmpegFrameExtractor.FrameWriter frameWriter;
    private final ImageResizer imageResizer;

    private final Path baseDeidPath;

    public DeidentFrameAttacher(LsDataSrcRepository srcRepository,
                                FfmpegFrameExtractor.FrameWriter frameWriter,
                                ImageResizer imageResizer,
                                @Value("${authoring.storage.deidentified-path:./storage/deidentified}") String storageDeidPath) {
        this.srcRepository = srcRepository;
        this.frameWriter = frameWriter;
        this.imageResizer = imageResizer;
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
        try {
            for (LsDataSrc src : frames) {
                // 4. 멱등 — 이미 비식별 경로가 있는 프레임은 skip.
                //    단 refreshExisting(재비식별, SC-009)이면 skip 을 우회해 강제 재추출(개인정보 누락 프레임 교체).
                if (!refreshExisting
                        && src.getDeIdntfSrcFilePathNm() != null
                        && !src.getDeIdntfSrcFilePathNm().isBlank()) {
                    continue;
                }
                int frameNo = Math.toIntExact(src.getFrameNo());
                Path deidFrameFile = resolveSafeFrameFile(outputDir, frameNo);

                // frame-exact: frm_no 번호로 직접 추출(fps 무관). 비식별=원본 프레임 시퀀스 동일.
                frameWriter.writeFrameByNumber(deidVideo, deidFrameFile, frameNo);

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

        log.info("[Batch][DeidAttach] attached rawSn={} frames={}", raw.getRawSn(), attached);
        return attached;
    }

    /** 비식별 영상 사용성 — 존재하고 크기가 0보다 큰가. */
    private boolean isUsable(Path video) {
        if (video == null || !frameWriter.sourceExists(video)) {
            return false;
        }
        try {
            return Files.size(video) > 0;
        } catch (IOException e) {
            return false;
        }
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
