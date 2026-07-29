package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
import kr.co.cudo.authoring.common.util.LetterboxTransform;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.port.ImageResizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 해상도 파생 확정 — <b>Phase A(검증·스냅샷)</b>. 락-I/O 분리 리팩터의 1단계 (RQ-SFR-06-03 파생영상).
 *
 * <p><b>짧은 {@code REQUIRES_NEW} 트랜잭션 + 부모 PESSIMISTIC_WRITE 잠금</b>만 담당한다. 무거운 파일
 * I/O(비디오 복사·전 프레임 리스케일)는 절대 이 단계에 넣지 않는다 — 잠금·커넥션을 쥔 채 대용량 I/O 를
 * 돌리면 3프리셋 동시 시 커넥션풀 소진·잠금 경합이 발생하기 때문이다(리팩터 목적).
 *
 * <h3>수행</h3>
 * <ol>
 *   <li>멱등 가드 — 파생 RAW 가 이미 {@code deIdntfYn=='Y'} 면 {@link Optional#empty()} 반환(중복 트리거 skip)</li>
 *   <li>부모 {@code findByRawSnForUpdate} 재잠금 + 비식별 산출물 존재({@code hasDeidentArtifact()}) 재검증
 *       — 신고('F')는 통과, 'N'(미수행)만 차단</li>
 *   <li>부모 프레임 수 &gt; 0 fail-fast(#9)</li>
 *   <li>치수(srcW/H, targetW/H, scaleX/scaleY) + 비식별 비디오 경로 + 프레임별 비식별 경로/목표 경로
 *       + 등록자 전부 스냅샷 → {@link ResolutionSnapshot} 로 반환 후 커밋(잠금·커넥션 해제)</li>
 * </ol>
 *
 * <p>Phase B 가 리포지토리 의존 0 이 되도록, Phase B/C 가 뒤에 필요로 하는 모든 DB 값을 여기서 확정한다.
 * 모든 경로는 CWE-22 정규화 + base 검증을 통과한 절대 경로로 담는다(#6).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResolutionSnapshotService {

    /** 프레임 청크 순회 크기(대용량). */
    private static final int FRAME_CHUNK_SIZE = 500;

    private final VideoRepository videoRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsDeidentProcLogRepository deidentProcLogRepository;
    private final LsDataAugRepository augRepository;
    private final ImageResizer imageResizer;
    /**
     * S6 — 부모의 비식별 <b>영상</b>이 co-locate 위치({@code dirname(원본)/{rawSn}/deid/})로 이동하면서
     * 비식별 저장소 단일 base 검증만으로는 신규 위치 파일이 전부 거부된다. 허용 base 를 2-way 로 넓히는 데
     * 사용한다(구 위치 = 비식별 저장소 서브트리, 신 위치 = co-locate 비식별 영상 디렉터리).
     */
    private final VideoArtifactRootResolver artifactRootResolver;

    @Value("${authoring.storage.raw-path:./storage/raw}")
    private String storageRawPath;

    /**
     * 비식별 프레임/비디오 base 경로. 비식별 산출물(deidFilePath·비식별 비디오 procLog 경로)은
     * deidentified-path 기준 절대경로다.
     *
     * <p><b>E-ISSUE-21</b>: 파생 산출물(리스케일 프레임·복사 비디오)은 <b>비식별 산출물</b>이므로
     * 출력 base 도 raw-path 가 아닌 이 deidentified-path 로 강제한다. 또한 두 base 가 동일 경로로
     * 설정된 운영 환경을 위해 {@link StorageSubtreePolicy} 서브트리 규약({@code frames/deid}·
     * {@code videos})까지 함께 강제한다(E-ISSUE-22 fail-open 차단).
     */
    @Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String storageDeidentifiedPath;

    /**
     * 파생 확정의 검증·스냅샷 단계. 성공 시 Phase B/C 가 필요로 하는 불변 값 묶음을 반환한다.
     *
     * @return 스냅샷 — 이미 확정된 파생 RAW 면 {@link Optional#empty()}(멱등 skip)
     * @throws CustomException PII 게이트 실패(CONFLICT)·프레임 0건·경로 위반 등 — 러너가 catch 하여
     *                         cleanup + FAILED 전이한다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public Optional<ResolutionSnapshot> snapshot(Long newRawSn, Long parentRawSn, Long dataAugSn,
                                                 ResolutionPreset preset) {
        LsDataRaw newRaw = videoRepository.findById(newRawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "파생 영상을 찾을 수 없습니다: rawSn=" + newRawSn));

        // 1) 멱등 — 이미 확정(비식별 완료)된 파생 RAW 면 재실행하지 않는다(AFTER_COMMIT 중복 트리거 방어).
        if ("Y".equals(newRaw.getDeIdntfYn())) {
            log.info("[Video][ResolutionDerivative][A] already finalized rawSn={} — skip", newRawSn);
            return Optional.empty();
        }

        // 2) 부모를 PESSIMISTIC_WRITE 로 재잠금 + 비식별 산출물 존재({@code hasDeidentArtifact()}) 재검증.
        //    ★ 비식별 누락 신고('F')는 여기서 막지 않는다 (2026-07-29 확정, 예약 게이트와 동일 정책).
        //      해상도 파생은 외부 위탁이 없는 내부 리스케일이라 신고 구간 생성이 외부 유출을 만들지 않는다.
        //    차단 대상은 'N'(비식별 미수행)·null — 복사할 비식별 산출물이 물리적으로 없다. 이때는 예외로
        //    롤백 → 러너가 파생 RAW 를 FAILED 전이한다.
        LsDataRaw parent = videoRepository.findByRawSnForUpdate(parentRawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "원본 영상을 찾을 수 없습니다: parentRawSn=" + parentRawSn));
        if (!parent.hasDeidentArtifact()) {
            log.warn("[Video][ResolutionDerivative][A] parent has no deident artifact — abort "
                            + "parentRawSn={} newRawSn={} deIdntfYn={}",
                    parentRawSn, newRawSn, safe(parent.getDeIdntfYn()));
            throw new CustomException(ErrorCode.CONFLICT,
                    "비식별 산출물이 있는 원본 영상만 파생영상을 확정할 수 있습니다.");
        }
        // HIGH (CWE-359 stale 창 게이트) — 부모 산출물 존재를 잠금 하에 확정한 이 시각을 스냅샷 기준 시각으로 고정한다.
        // Phase C 가 "스냅샷 이후 비식별본이 재비식별로 교체됐는가"를 이 시각 기준으로 결정적으로 재검증한다.
        Instant capturedAt = Instant.now();

        int targetW = preset.width();
        int targetH = preset.height();
        // 배율은 preset(목표) / parent 첫 프레임 실측(원본)으로 산정.
        int[] srcDim = measureParentDimensions(parentRawSn);
        int srcW = srcDim[0];
        int srcH = srcDim[1];
        if (srcW <= 0 || srcH <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "원본 프레임 해상도를 확인할 수 없습니다.");
        }
        // G-1 — 종횡비 <b>보존</b>(레터박스). 균일 배율 + 중앙 정렬 오프셋을 픽셀·라벨이 공유한다.
        //       구 구현은 축별 독립 배율(scaleX=targetW/srcW, scaleY=targetH/srcH)로 강제 스케일해
        //       비-16:9 원본(예: 1080×1920 세로)을 왜곡했다(E-ISSUE-26).
        LetterboxTransform box = LetterboxTransform.of(srcW, srcH, targetW, targetH);
        double scaleX = box.scale();
        double scaleY = box.scale();
        int offsetX = box.offsetX();
        int offsetY = box.offsetY();

        // E-ISSUE-21 — 파생 산출물(비디오·프레임)의 출력 base 는 비식별 저장소다(구 raw base 폐기).
        Path outBase = deidBase();

        // 비식별 비디오 원본 경로(검증) + 파생 비디오 목적 경로(검증) 스냅샷.
        String deidVideoPath = deidentProcLogRepository.findLatestSuccessByDataRawSn(parentRawSn)
                .map(LsDeidentProcLog::getDeIdntfFilePathNm)
                .filter(p -> p != null && !p.isBlank())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "원본 비식별 영상 경로를 찾을 수 없습니다: parentRawSn=" + parentRawSn));
        // 입력(비식별 비디오 소스)·출력(파생 비디오 목적지) 모두 비식별 저장소 + 비식별 서브트리로 강제.
        Path deidVideoSrc = resolveSafeDeidVideoSource(
                deidVideoPath, parentRawSn, parent.getRawFilePathNm());
        Path videoDst = resolveSafeDeidFile(outBase, newRaw.getRawFilePathNm());

        // 3) 프레임별 스펙 스냅샷 + 프레임 0건 fail-fast(#9) + 중복 videoFrameNo fail-fast.
        List<ResolutionSnapshot.FrameSpec> frames = buildFrameSpecs(outBase, parentRawSn, newRawSn);
        if (frames.isEmpty()) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR,
                    "파생할 프레임이 없습니다: parentRawSn=" + parentRawSn);
        }

        LsDataAug aug = augRepository.findById(dataAugSn).orElse(null);
        String regId = aug != null ? aug.getRegUserNo() : null;

        log.info("[Video][ResolutionDerivative][A] snapshot ready rawSn={} parentRawSn={} frames={} scale={} offset={},{}",
                newRawSn, parentRawSn, frames.size(), scaleX, offsetX, offsetY);
        return Optional.of(new ResolutionSnapshot(
                newRawSn, parentRawSn, dataAugSn, preset,
                srcW, srcH, targetW, targetH, scaleX, scaleY, offsetX, offsetY,
                regId, deidVideoSrc, videoDst, capturedAt, frames));
    }

    /**
     * 부모 프레임을 청크 순회하며 리스케일 입출력 경로를 확정한다(파일 I/O 없음 — 경로 계산만).
     *
     * @param base 출력 base — 비식별 저장소({@link #deidBase()})
     */
    private List<ResolutionSnapshot.FrameSpec> buildFrameSpecs(Path base, Long parentRawSn, Long newRawSn) {
        List<ResolutionSnapshot.FrameSpec> specs = new ArrayList<>();
        java.util.Set<Long> seenFrameKeys = new java.util.HashSet<>();
        int page = 0;
        while (true) {
            List<LsDataSrc> chunk = srcRepository
                    .findByRawSnOrderByFrameNoAsc(parentRawSn, PageRequest.of(page, FRAME_CHUNK_SIZE))
                    .getContent();
            if (chunk.isEmpty()) {
                break;
            }
            for (LsDataSrc pf : chunk) {
                long frameKey = frameNumberOf(pf);
                if (!seenFrameKeys.add(frameKey)) {
                    // 부모 프레임에 중복 videoFrameNo 가 있으면 라벨 이중매핑 → fail-fast(고아 원천 차단).
                    throw new CustomException(ErrorCode.INTERNAL_ERROR,
                            "부모 프레임에 중복 videoFrameNo 가 있습니다: parentRawSn=" + parentRawSn);
                }
                // LOW — 파생 픽셀 복사는 반드시 비식별 프레임에서만. deid 경로가 blank/부재면 실패시켜
                // 원본(비-비식별) 픽셀이 복제 후 'Y' 스탬프되는 불변식 위반을 차단한다.
                String deidFrameSrc = deidFrameSourceStrict(pf);
                // 입력(비식별 프레임 소스)·출력(리스케일 목적지) 모두 비식별 저장소 + frames/deid 서브트리.
                Path fsrc = resolveSafeDeidSource(deidFrameSrc);
                Path fdst = resolveSafeDir(base,
                        StorageSubtreePolicy.deidFramesDir(newRawSn) + "/" + fileNameOf(deidFrameSrc, pf));
                specs.add(new ResolutionSnapshot.FrameSpec(
                        pf.getSrcSn(), pf.getFrameNo(), pf.getVideoFrameNo(), pf.getShtDt(), fsrc, fdst));
            }
            page++;
        }
        return specs;
    }

    /** 부모 첫 프레임(FRM_NO 최소) 실측 해상도 — 배율 산정 기준(비식별 프레임 우선). */
    private int[] measureParentDimensions(Long parentRawSn) {
        LsDataSrc first = srcRepository.findByRawSnAndFrameNo(parentRawSn, 0)
                .orElseGet(() -> srcRepository.findByRawSnOrderByFrameNoAsc(parentRawSn).stream()
                        .min(java.util.Comparator.comparing(LsDataSrc::getFrameNo))
                        .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_ERROR,
                                "파생할 프레임이 없습니다: parentRawSn=" + parentRawSn)));
        Path srcPath = resolveSafeMeasureSource(ResolutionDerivativeService.frameSourcePath(first));
        return imageResizer.readDimensions(srcPath);
    }

    /** 재추출/재매핑 대상 프레임 번호: videoFrameNo 우선, 없으면 frameNo 폴백(양쪽 동일 계산식). */
    private static long frameNumberOf(LsDataSrc frame) {
        return frame.getVideoFrameNo() != null ? frame.getVideoFrameNo() : frame.getFrameNo();
    }

    private static String fileNameOf(String frameSrc, LsDataSrc frame) {
        Path name = Paths.get(frameSrc).getFileName();
        return name != null ? name.toString() : (frame.getFrameNo() + ".jpg");
    }

    /**
     * 파생 리스케일 소스 = 비식별 프레임 경로만 허용(LOW 하드가드). blank/부재면 폴백하지 않고 실패시켜
     * 원본(비-비식별) 픽셀 복제 + 'Y' 위장을 원천 차단한다.
     */
    private static String deidFrameSourceStrict(LsDataSrc frame) {
        String deid = frame.getDeidFilePath();
        if (deid == null || deid.isBlank()) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "비식별 프레임 경로가 없어 파생영상을 생성할 수 없습니다: srcSn=" + frame.getSrcSn());
        }
        return deid;
    }

    /** 비식별 저장소 base(정규화 절대경로). */
    private Path deidBase() {
        return Paths.get(storageDeidentifiedPath).toAbsolutePath().normalize();
    }

    /**
     * <b>비식별</b> 입력 소스(비식별 프레임/비디오) 경로 normalize + base + 서브트리 검증
     * (CWE-22 traversal + CWE-359 격리).
     *
     * <p>E-ISSUE-22 — deid base 하위라도 {@code frames/raw/**} 는 원본 프레임 서브트리이므로 거부한다.
     * 두 base 가 동일 문자열인 운영 환경에서 base 검사만으로는 원본 픽셀 유입을 막지 못하기 때문이다.
     * 상대경로는 비식별 base 기준으로 해석한다.
     */
    /**
     * 부모 비식별 <b>영상</b> 소스 경로 검증 (S6 — 2-way).
     * <ol>
     *   <li>구 위치 — 비식별 저장소 + 비식별 전용 서브트리({@code videos/**}).</li>
     *   <li>신 위치 — co-locate 비식별 영상 디렉터리 하위.</li>
     * </ol>
     * 둘 다 아니면 거부한다(fail-secure, 경로 원문 미노출).
     */
    private Path resolveSafeDeidVideoSource(String filePath, Long parentRawSn, String parentRawFilePathNm) {
        if (filePath == null || filePath.isBlank()) {
            throw new CustomException(ErrorCode.NOT_FOUND, "경로가 비어있습니다.");
        }
        Optional<Path> coLocateDir = (artifactRootResolver == null)
                ? Optional.empty()
                : artifactRootResolver.deidVideoDirQuietly(parentRawSn, parentRawFilePathNm);
        if (coLocateDir.isPresent()) {
            Path candidate = Paths.get(filePath);
            Path resolved = candidate.isAbsolute()
                    ? candidate.normalize()
                    : coLocateDir.get().resolve(candidate).normalize();
            if (resolved.startsWith(coLocateDir.get())) {
                return resolved;
            }
        }
        return resolveSafeDeidSource(filePath);
    }

    private Path resolveSafeDeidSource(String filePath) {
        Path base = deidBase();
        if (filePath == null || filePath.isBlank()) {
            throw new CustomException(ErrorCode.NOT_FOUND, "경로가 비어있습니다.");
        }
        Path candidate = Paths.get(filePath);
        Path resolved = candidate.isAbsolute() ? candidate.normalize() : base.resolve(candidate).normalize();
        if (!StorageSubtreePolicy.isDeidentifiedArtifact(base, resolved)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "경로가 허용된 비식별 저장 경로를 벗어납니다.");
        }
        return resolved;
    }

    /**
     * 출력(파생 비디오 목적지) 파일 경로 normalize + base + 비식별 서브트리 검증 (CWE-22).
     * 파생 산출물은 비식별 저장소의 비식별 전용 서브트리에만 쓴다.
     */
    private Path resolveSafeDeidFile(Path base, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new CustomException(ErrorCode.NOT_FOUND, "경로가 비어있습니다.");
        }
        Path candidate = Paths.get(filePath);
        Path resolved = candidate.isAbsolute() ? candidate.normalize() : base.resolve(candidate).normalize();
        if (!StorageSubtreePolicy.isDeidentifiedArtifact(base, resolved)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "출력 경로가 허용된 비식별 저장 경로를 벗어납니다.");
        }
        return resolved;
    }

    /**
     * 실측(치수 측정) 전용 소스 경로 해석 — 원본/비식별 어느 쪽이든 허용하되 두 base 중 하나는 반드시
     * 만족해야 한다(CWE-22). 측정은 픽셀을 산출물로 내보내지 않으므로 서브트리 강제 대상이 아니다.
     */
    private Path resolveSafeMeasureSource(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new CustomException(ErrorCode.NOT_FOUND, "경로가 비어있습니다.");
        }
        Path rawBase = Paths.get(storageRawPath).toAbsolutePath().normalize();
        Path deid = deidBase();
        Path candidate = Paths.get(filePath);
        Path resolved = candidate.isAbsolute() ? candidate.normalize() : rawBase.resolve(candidate).normalize();
        if (!resolved.startsWith(rawBase) && !resolved.startsWith(deid)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "경로가 허용된 저장 경로를 벗어납니다.");
        }
        return resolved;
    }

    /** 디렉토리/파일 상대경로를 base 하위로 결정론적 해석 + normalize 검증 (CWE-22). */
    private Path resolveSafeDir(Path base, String relative) {
        Path resolved = base.resolve(relative).normalize();
        if (!resolved.startsWith(base)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "출력 경로가 허용된 저장 경로를 벗어납니다.");
        }
        return resolved;
    }

    private static String safe(String s) {
        return s == null ? "null" : s.replaceAll("[\\r\\n\\t]", "_");
    }
}
