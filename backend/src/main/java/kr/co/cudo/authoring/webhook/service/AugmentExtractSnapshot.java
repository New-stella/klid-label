package kr.co.cudo.authoring.webhook.service;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugJobFile;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobFileRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 증강 프레임 확정 — <b>Phase A(검증·스냅샷)</b>. 커넥션-점유 분리 리팩터의 1단계(증강 경로).
 *
 * <p><b>짧은 {@code REQUIRES_NEW} 트랜잭션</b>만 담당한다. 무거운 파일 I/O(외부 산출 이미지 반입·
 * 해상도 실측)는 절대 이 단계에 넣지 않는다 — 커넥션을 쥔 채 대용량 I/O 를 돌리면 다건 증강 동시 시
 * 커넥션풀이 압박받기 때문이다(리팩터 목적). 여기서는 멱등 가드 + 위탁 매핑 검증 + 경로 계산만
 * 수행하고 즉시 커밋한다.
 *
 * <h3>Phase 7-D — 외부 산출물 대응 복원 (fail-closed)</h3>
 * <p>프레임 픽셀은 외부 생성형 AI 산출물에서 온다. 어느 산출물이 어느 프레임의 변환본인지는
 * <b>위탁 시점에 못박아 둔 매핑</b>({@code LS_DATA_AUG_JOB_FILE}, JOB_SEQ→FILE_SEQ 순)으로만
 * 판단한다. 매핑이 없거나(구 위탁), 프레임과 1:1 로 맞지 않거나, 산출 경로가 비어 있으면
 * <b>부모 재추출로 폴백하지 않고 실패</b>시킨다 — 폴백하면 증강 효과가 0 인 사본이 모든 게이트를
 * 통과해 가짜 학습데이터가 된다.
 *
 * <h3>부모 잠금·비식별 재검증 미추가 (설계 유지 — 반드시 준수)</h3>
 * <p>부모 {@code findByRawSnForUpdate} 잠금·{@code deIdntfYn=='Y'} 게이트는
 * {@code AugmentResultService.handle} 의 동기 트랜잭션 시점 판정으로만 유효하다(CWE-359 PII TOCTOU).
 * 해상도 파생의 부모 재검증 게이트는 이식하지 않는다(하면 동기 판정 이후 창을 재개방한다).
 *
 * <h3>산출 위치 — 비식별 서브트리 (PII 격리)</h3>
 * <p>외부 산출물의 입력은 <b>비식별 프레임</b>이므로 그 변환본도 비식별 계열 산출물이다. 따라서
 * 파생 프레임은 {@code {deidBase}/frames/deid/{newRawSn}/} 에 쓰고 DB 에도 비식별 프레임 경로로
 * 적재한다(해상도 파생과 동일 규약, V133 정책 A). 원본 프레임 서브트리({@code frames/raw})에 쓰면
 * "원본(비-비식별) 픽셀"로 오분류되어 export 가 {@code anonymity="N"} 으로 잘못 표기한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AugmentExtractSnapshot {

    /** 반입 허용 이미지 확장자 — 산출 파일명 결정에 사용(그 외는 미지의 매체로 거부). */
    private static final Set<String> ALLOWED_IMAGE_EXT = Set.of("jpg", "jpeg", "png", "bmp");

    private final VideoRepository videoRepository;
    private final LsDataAugRepository augRepository;
    private final LsDataSrcRepository srcRepository;
    /** 위탁 시점에 못박은 순서↔프레임 대응 + 콜백이 되붙인 외부 산출 경로. */
    private final LsDataAugJobFileRepository jobFileRepository;
    /**
     * 부모 <b>비식별 영상</b> 경로의 진실원. 파일명은 고정이 아니므로(mock={@code deidentified.mp4} ·
     * KPST={@code {원본stem}-mask{ext}}) 조합·추측하지 않고 적재된 값을 읽는다.
     */
    private final LsDeidentProcLogRepository deidentProcLogRepository;
    /**
     * 부모 비식별 영상이 co-locate 위치({@code dirname(원본)/{rawSn}/deid/})에 있을 수 있어, 소스 검증
     * base 를 2-way(구 위치=비식별 저장소 서브트리 / 신 위치=co-locate 디렉터리)로 넓히는 데 쓴다
     * (해상도 파생 {@code ResolutionSnapshotService} 와 동일).
     */
    private final VideoArtifactRootResolver artifactRootResolver;

    /** 파생(비식별 계열) 산출물의 출력 base. 원본 base 가 아니다. */
    @Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String storageDeidentifiedPath;

    /**
     * 프레임 확정의 검증·스냅샷 단계. 성공 시 Phase B/C 가 필요로 하는 불변 값 묶음을 반환한다.
     *
     * @return 계획 — 이미 확정된 신규 RAW 면 {@link Optional#empty()}(멱등 skip)
     * @throws CustomException 신규 RAW/증강행 부재(NOT_FOUND)·프레임 0건·중복 videoFrameNo·
     *                         외부 산출 매핑 불일치·경로 위반 등 — 러너가 catch 하여 markRawDataFailed 전이한다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public Optional<AugmentExtractPlan> snapshot(Long newRawSn, Long dataAugSn) {
        LsDataRaw newRaw = videoRepository.findById(newRawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "증강 신규 영상을 찾을 수 없습니다: rawSn=" + newRawSn));

        // 1) 멱등 — 이미 처리(비식별 완료)된 신규 RAW 면 재실행하지 않는다(AFTER_COMMIT 중복 트리거 방어).
        if ("Y".equals(newRaw.getDeIdntfYn())) {
            log.info("[Augment][ExtractA] already finalized rawSn={} — skip", newRawSn);
            return Optional.empty();
        }

        // 영상 파일 경로는 프레임 반입에 쓰이지 않지만(이미지-to-이미지), 산출물 co-locate base
        // (dirname(값)/{rawSn}/) 도출에 쓰이므로 비어 있으면 죽은 RAW 가 확정된다 — fail-fast(구 동작 보존).
        if (newRaw.getRawFilePathNm() == null || newRaw.getRawFilePathNm().isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "증강 영상 메타가 비어있습니다.");
        }

        LsDataAug aug = augRepository.findById(dataAugSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "증강 행을 찾을 수 없습니다: dataAugSn=" + dataAugSn));

        Long parentRawSn = newRaw.getOrgnlRawSn();
        List<LsDataSrc> parentFrames = srcRepository.findByRawSnOrderByFrameNoAsc(parentRawSn);
        if (parentFrames.isEmpty()) {
            // 동기 단계에서 가드했으므로 정상적으로 도달하지 않는다. 방어적으로 실패 처리.
            throw new CustomException(ErrorCode.INTERNAL_ERROR,
                    "부모 프레임이 없습니다: parentRawSn=" + parentRawSn);
        }

        // 2) 외부 산출물 대응 복원 — 위탁 순서(JOB_SEQ, FILE_SEQ) 그대로. 없거나 어긋나면 fail-closed.
        Map<Long, String> outputBySrcSn = resolveOutputs(dataAugSn, parentFrames.size());

        Path base = deidBase();
        Path framesDir = resolveSafeDeidDir(base, StorageSubtreePolicy.deidFramesDir(newRawSn));

        // 3) 프레임별 스펙 — 부모 프레임 순서를 그대로 이어받고, 각 프레임에 자기 산출물을 붙인다.
        Set<Long> seenFrameNo = new HashSet<>();
        List<AugmentExtractPlan.FrameSpec> frames = new ArrayList<>(parentFrames.size());
        for (int i = 0; i < parentFrames.size(); i++) {
            LsDataSrc pf = parentFrames.get(i);
            long frameNo = frameNumberOf(pf);
            // 부모 프레임에 중복 videoFrameNo 가 있으면 라벨 재매핑 key 가 붕괴해 고아 프레임 + 라벨
            // 이중매핑이 발생한다. fail-fast 로 고아 생성을 원천 차단한다.
            if (!seenFrameNo.add(frameNo)) {
                throw new CustomException(ErrorCode.INTERNAL_ERROR,
                        "부모 프레임에 중복 videoFrameNo 가 있습니다: parentRawSn=" + parentRawSn);
            }
            String output = outputBySrcSn.get(pf.getSrcSn());
            if (output == null) {
                throw new CustomException(ErrorCode.CONFLICT,
                        "외부 증강 산출물이 없는 프레임이 있습니다: srcSn=" + pf.getSrcSn());
            }
            Path dst = resolveSafeDeidDir(base,
                    StorageSubtreePolicy.deidFramesDir(newRawSn) + "/frame-" + i + "." + extensionOf(output));
            frames.add(new AugmentExtractPlan.FrameSpec(
                    pf.getSrcSn(), i, frameNo, newRaw.getShtDt(), Paths.get(output).normalize(), dst));
        }

        // 4) 해상도 기준 — 외부에 위탁했던 입력(부모 비식별 프레임) 1건. 산출물이 이 해상도와 다르면
        //    라벨 좌표 그대로 복사 전제가 깨지므로 Phase B 가 fail-closed 로 막는다.
        Path referenceFrame = resolveSafeDeidSource(base, deidFrameSourceStrict(parentFrames.get(0)));

        // 5) 비디오 — 증강 AI 는 영상을 재생성하지 않으므로 <부모 비식별 영상>을 파생 전용 경로로 복사한다
        //    (해상도 파생 ResolutionSnapshotService 와 동일 규약). 소스 경로는 조합하지 않고 procLog 값을
        //    읽으며, 없으면 원본(비-비식별)으로 폴백하지 않고 실패시킨다(PII 복제 원천 차단, CWE-359).
        Path deidVideoSrc = resolveParentDeidVideo(parentRawSn);
        Path videoDst = resolveSafeDeidDir(base,
                StorageSubtreePolicy.augmentVideoFile(parentRawSn, newRawSn, aug.getAugTypeCd()));

        log.info("[Augment][ExtractA] plan ready rawSn={} orgnlRawSn={} frames={} (external outputs)",
                newRawSn, parentRawSn, frames.size());
        return Optional.of(new AugmentExtractPlan(
                newRawSn, parentRawSn, dataAugSn, aug.getRegUserNo(),
                referenceFrame, deidVideoSrc, videoDst, framesDir, frames));
    }

    /**
     * 위탁 매핑을 읽어 {@code srcSn → 외부 산출 경로} 로 복원한다.
     *
     * <p>검증(모두 fail-closed): ①매핑 존재 ②산출 경로 전건 적재 ③프레임 수와 1:1 ④srcSn 중복 없음.
     * 어긋나면 예외를 던져 러너가 신규 RAW 를 FAILED 로 전이하게 한다 — 부모 재추출 폴백은 없다.
     */
    private Map<Long, String> resolveOutputs(Long dataAugSn, int parentFrameCount) {
        List<LsDataAugJobFile> mappings = jobFileRepository.findByDataAugSnOrderByJobAndFileSeq(dataAugSn);
        if (mappings.isEmpty()) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "외부 증강 위탁 매핑이 없습니다: dataAugSn=" + dataAugSn);
        }
        if (mappings.size() != parentFrameCount) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "위탁 건수와 프레임 수가 다릅니다: dataAugSn=" + dataAugSn
                            + " issued=" + mappings.size() + " frames=" + parentFrameCount);
        }
        Map<Long, String> bySrcSn = new LinkedHashMap<>();
        for (LsDataAugJobFile m : mappings) {
            String path = m.getResultFilePathNm();
            if (path == null || path.isBlank()) {
                throw new CustomException(ErrorCode.CONFLICT,
                        "외부 증강 산출 경로가 비어 있습니다: dataAugSn=" + dataAugSn);
            }
            if (bySrcSn.put(m.getSrcSn(), path) != null) {
                throw new CustomException(ErrorCode.CONFLICT,
                        "같은 프레임에 산출물이 둘 이상 매핑됐습니다: srcSn=" + m.getSrcSn());
            }
        }
        return bySrcSn;
    }

    /**
     * 반입 대상 확장자 — 외부 산출 파일명에서 취한다. 이미지 외 확장자는 거부한다(CWE-20).
     * 확장자를 임의로 {@code .jpg} 로 고정하면 내용과 이름이 어긋나 뷰어/ImageIO 판정이 갈린다.
     */
    private static String extensionOf(String outputPath) {
        Path name = Paths.get(outputPath).getFileName();
        String fileName = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        int dot = fileName.lastIndexOf('.');
        String ext = dot < 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot + 1);
        if (!ALLOWED_IMAGE_EXT.contains(ext)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "외부 증강 산출물이 허용된 이미지 형식이 아닙니다.");
        }
        return ext;
    }

    /**
     * 해상도 기준 소스 = 비식별 프레임 경로만 허용(하드가드). blank/부재면 폴백하지 않고 실패시켜
     * 원본(비-비식별) 프레임을 기준으로 삼는 경로를 원천 차단한다.
     */
    private static String deidFrameSourceStrict(LsDataSrc frame) {
        String deid = frame.getDeidFilePath();
        if (deid == null || deid.isBlank()) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "비식별 프레임 경로가 없어 증강 파생영상을 확정할 수 없습니다: srcSn=" + frame.getSrcSn());
        }
        return deid;
    }

    /**
     * 재매핑 대상 프레임 번호. {@code videoFrameNo}(실제 영상 프레임 위치)가 있으면 그것을, 없으면
     * (구 데이터) 추출 순번 {@code frameNo} 로 폴백한다. 신규 프레임의 videoFrameNo 에도 동일 값을 실어
     * 라벨 재매핑 key 로 사용한다(양쪽 동일 계산식이라 매핑 정합).
     */
    private static long frameNumberOf(LsDataSrc frame) {
        return frame.getVideoFrameNo() != null ? frame.getVideoFrameNo() : frame.getFrameNo();
    }

    /**
     * 복사 소스 = <b>부모 비식별 영상</b> 경로. 최신 SUCCESS {@link LsDeidentProcLog} 의
     * {@code DE_IDNTF_FILE_PATH_NM} <b>값</b>을 읽는다(파일명 조합·추측 금지 — mock 과 KPST 의 이름이 다르다).
     * 값이 없으면 원본으로 폴백하지 않고 실패시킨다(PII 복제 차단).
     *
     * <p>경로 검증은 해상도 파생과 동일한 <b>2-way</b>다 — ①co-locate 비식별 영상 디렉터리 하위 또는
     * ②비식별 저장소의 비식별 전용 서브트리({@code videos/**}). 둘 다 아니면 거부한다(fail-secure,
     * 경로 원문 미노출 CWE-209).
     */
    private Path resolveParentDeidVideo(Long parentRawSn) {
        String deidVideoPath = deidentProcLogRepository.findLatestSuccessByDataRawSn(parentRawSn)
                .map(LsDeidentProcLog::getDeIdntfFilePathNm)
                .filter(p -> p != null && !p.isBlank())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "원본 비식별 영상 경로를 찾을 수 없습니다: parentRawSn=" + parentRawSn));
        Optional<Path> coLocateDir = (artifactRootResolver == null)
                ? Optional.empty()
                : videoRepository.findById(parentRawSn)
                        .flatMap(p -> artifactRootResolver.deidVideoDirQuietly(parentRawSn, p.getRawFilePathNm()));
        if (coLocateDir.isPresent()) {
            Path candidate = Paths.get(deidVideoPath);
            Path resolved = candidate.isAbsolute()
                    ? candidate.normalize()
                    : coLocateDir.get().resolve(candidate).normalize();
            if (resolved.startsWith(coLocateDir.get())) {
                return resolved;
            }
        }
        return resolveSafeDeidSource(deidBase(), deidVideoPath);
    }

    /** 비식별 저장소 base(정규화 절대경로). */
    private Path deidBase() {
        return Paths.get(storageDeidentifiedPath).toAbsolutePath().normalize();
    }

    /**
     * 출력(파생 프레임 목적지) 경로 해석 — base 하위 + <b>비식별 전용 서브트리</b> 검증 (CWE-22/CWE-359).
     * 두 base 가 같은 경로로 설정된 운영 환경에서 base 검사만으로는 원본 서브트리 유입을 못 막는다.
     */
    private static Path resolveSafeDeidDir(Path base, String relative) {
        Path resolved = base.resolve(relative).normalize();
        if (!StorageSubtreePolicy.isDeidentifiedArtifact(base, resolved)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "프레임 출력 경로가 허용된 저장 경로를 벗어납니다.");
        }
        return resolved;
    }

    /** 비식별 입력 소스(부모 비식별 프레임) 경로 해석 — 동일 규약. 상대경로는 비식별 base 기준. */
    private static Path resolveSafeDeidSource(Path base, String filePath) {
        Path candidate = Paths.get(filePath);
        Path resolved = candidate.isAbsolute() ? candidate.normalize() : base.resolve(candidate).normalize();
        if (!StorageSubtreePolicy.isDeidentifiedArtifact(base, resolved)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "경로가 허용된 비식별 저장 경로를 벗어납니다.");
        }
        return resolved;
    }
}
