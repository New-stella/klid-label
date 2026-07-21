package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.entity.LsResolutionExport;
import kr.co.cudo.authoring.video.entity.LsResolutionLblMap;
import kr.co.cudo.authoring.video.repository.LsResolutionExportRepository;
import kr.co.cudo.authoring.video.repository.LsResolutionLblMapRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.port.ImageResizer;
import kr.co.cudo.authoring.video.service.port.VideoFileCopier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 해상도 파생영상 확정(비동기부, REQUIRES_NEW) — Phase 2 (RQ-SFR-06-03 파생영상).
 *
 * <p>{@link ResolutionReservationPersister} 가 PENDING·deIdntfYn='N' 으로 커밋한 파생 RAW 를 받아
 * ①원본 비식별 비디오 복사 → ②프레임 목표해상도 리스케일(축소/확대) → ③원본 라벨 좌표 스케일 복사 +
 * {@link LsResolutionLblMap} 적재 → ④비식별 완료 불변식 확정(MARKING_READY + deIdntfYn='Y' + SUCCESS
 * procLog)을 <b>단일 REQUIRES_NEW 트랜잭션</b>으로 원자 처리한다(부분 실패 시 전체 롤백 = all-or-nothing).
 *
 * <p>{@code AugmentFrameExtractionService} 패턴을 재사용한다: videoFrameNo 명시 키 매핑(인덱스 zip 금지),
 * distinct 가드, {@code deIdntfYn=='Y'} 멱등 가드(중복 트리거 방어), 확정 블록을 라벨 저장 이후 배치.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResolutionDerivativeFinalizer {

    /** 프레임 청크 순회 크기(대용량 리스케일). */
    private static final int FRAME_CHUNK_SIZE = 500;

    private final VideoRepository videoRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository lblRepository;
    private final LsResolutionLblMapRepository lblMapRepository;
    private final LsResolutionExportRepository exportRepository;
    private final LsDeidentProcLogRepository deidentProcLogRepository;
    private final ImageResizer imageResizer;
    private final VideoFileCopier videoFileCopier;
    private final ResizeConcurrencyGate resizeGate;

    @Value("${authoring.storage.raw-path:./storage/raw}")
    private String storageRawPath;

    /**
     * 파생 RAW 를 확정한다(원자). 성공 시에만 deIdntfYn='Y' + MARKING_READY + SUCCESS procLog.
     *
     * @param newRawSn    파생 RAW_SN (PENDING·deIdntfYn='N' 으로 예약 커밋됨)
     * @param parentRawSn 원본 RAW_SN (프레임/라벨 복사원)
     * @param resExportSn 산출 추적 EXPORT_SN (배율/해상도 계산 + LBL_MAP FK)
     * @param preset      목표 해상도 프리셋
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void finalizeDerivative(Long newRawSn, Long parentRawSn, Long resExportSn, ResolutionPreset preset) {
        LsDataRaw newRaw = videoRepository.findById(newRawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "파생 영상을 찾을 수 없습니다: rawSn=" + newRawSn));

        // 멱등 — 이미 확정(비식별 완료)된 파생 RAW 면 재실행하지 않는다(AFTER_COMMIT 중복 트리거 방어).
        if ("Y".equals(newRaw.getDeIdntfYn())) {
            log.info("[Video][ResolutionDerivative] already finalized rawSn={} — skip", newRawSn);
            return;
        }

        LsResolutionExport export = exportRepository.findById(resExportSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "파생 산출 추적행을 찾을 수 없습니다: resExportSn=" + resExportSn));

        // HIGH (CWE-359 PII TOCTOU) — 예약 커밋 ~ 이 비동기 확정 사이 창에서 부모가 비식별 누락 신고로
        // deIdntfYn='F'(PII 노출 확정)로 전이됐을 수 있다. 실제 픽셀 복사/리스케일/'Y' 스탬프 이전에
        // 부모를 PESSIMISTIC_WRITE 로 재잠금 + deIdntfYn=='Y' 재검증한다(동기 예약부 게이트를 확정부까지 확장).
        // 이 잠금·검증은 아래 파일 복사와 동일 REQUIRES_NEW 트랜잭션 안에 있어 신고 UPDATE 와 직렬화되며,
        // 'Y' 가 아니면 예외로 롤백 → 러너가 파생 RAW 를 FAILED 전이(PII 절대 미복제).
        LsDataRaw parent = videoRepository.findByRawSnForUpdate(parentRawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "원본 영상을 찾을 수 없습니다: parentRawSn=" + parentRawSn));
        if (!"Y".equals(parent.getDeIdntfYn())) {
            log.warn("[Video][ResolutionDerivative] parent no longer deidentified — abort finalize (PII guard) "
                            + "parentRawSn={} newRawSn={} deIdntfYn={}",
                    parentRawSn, newRawSn, safe(parent.getDeIdntfYn()));
            throw new CustomException(ErrorCode.CONFLICT,
                    "비식별 완료된 원본 영상만 파생영상을 확정할 수 있습니다.");
        }

        int targetW = preset.width();
        int targetH = preset.height();
        double scaleX = (double) export.getTargetW() / export.getOrgnlW();
        double scaleY = (double) export.getTargetH() / export.getOrgnlH();

        resizeGate.acquire();
        try {
            Path base = Paths.get(storageRawPath).toAbsolutePath().normalize();

            // 1) 원본 비식별 비디오 복사 → 파생 RAW 경로 (CWE-22: 양쪽 normalize + base 검증).
            copyDeidentifiedVideo(base, parentRawSn, newRaw);

            // 2) 프레임 청크 리스케일 + 명시 videoFrameNo 키 매핑.
            FrameMapping mapping = rescaleFrames(base, parentRawSn, newRawSn, targetW, targetH);

            // 3) 라벨 좌표 스케일 복사 + LS_RESOLUTION_LBL_MAP 적재.
            int copiedLabels = copyScaledLabels(mapping.parentSrcToNewSrc(), resExportSn, scaleX, scaleY,
                    export.getRegId());

            // 4) 성공 시에만 확정 불변식(같은 커밋): deIdntfYn='Y' + MARKING_READY + SUCCESS procLog + frameCnt.
            String videoDst = newRaw.getRawFilePathNm();
            newRaw.markDeidentified("Y");
            newRaw.markMarkingReady();
            export.updateFrameCnt(mapping.newFrameCount());
            LsDeidentProcLog procLog = LsDeidentProcLog.request(
                    newRaw.getRawSn(), null, videoDst, "resolution-derivative");
            procLog.succeed(videoDst);
            deidentProcLogRepository.save(procLog);

            log.info("[Video][ResolutionDerivative] finalized rawSn={} orgnlRawSn={} frames={} labels={} scale={}x{}",
                    newRawSn, parentRawSn, mapping.newFrameCount(), copiedLabels, scaleX, scaleY);
        } finally {
            resizeGate.release();
        }
    }

    /**
     * MEDIUM — finalize 실패 시 이미 쓰인 파생 산출물(리스케일 프레임 디렉토리 + 파생 비디오 파일)을
     * best-effort 삭제한다. DB 는 REQUIRES_NEW 롤백으로 고아가 없지만 파일은 트랜잭션 밖 I/O 라 남는다.
     * 러너 catch 에서 호출되며, 예외는 삼키고 경로는 마스킹 로그만 남긴다(CWE-209/PII 경로 미노출).
     */
    public void cleanupDerivativeArtifacts(Long newRawSn) {
        if (newRawSn == null) {
            return;
        }
        Path base = Paths.get(storageRawPath).toAbsolutePath().normalize();

        // 1) 리스케일 프레임 디렉토리 resolution/{newRawSn}/ 재귀 삭제.
        try {
            Path framesDir = resolveSafeDir(base, "resolution/" + newRawSn);
            deleteRecursivelyQuietly(framesDir);
        } catch (RuntimeException e) {
            log.warn("[Video][ResolutionDerivative] frames dir cleanup skipped newRawSn={} cause={}",
                    newRawSn, e.getClass().getSimpleName());
        }

        // 2) 파생 비디오 파일(newRaw.rawFilePathNm) 삭제 — 파생 RAW 경로만 사용(원본 절대 미삭제).
        try {
            videoRepository.findById(newRawSn)
                    .map(LsDataRaw::getRawFilePathNm)
                    .filter(p -> p != null && !p.isBlank())
                    .ifPresent(p -> deleteFileQuietly(resolveSafeFile(base, p)));
        } catch (RuntimeException e) {
            log.warn("[Video][ResolutionDerivative] video file cleanup skipped newRawSn={} cause={}",
                    newRawSn, e.getClass().getSimpleName());
        }
    }

    private static void deleteRecursivelyQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort — 개별 파일 삭제 실패는 무시.
                }
            });
        } catch (IOException ignored) {
            // best-effort — 디렉토리 순회 실패는 무시.
        }
    }

    private static void deleteFileQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best-effort — 삭제 실패는 무시.
        }
    }

    private static String safe(String s) {
        return s == null ? "null" : s.replaceAll("[\\r\\n\\t]", "_");
    }

    /** 원본 비식별 비디오(LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM) → 파생 경로 복사. */
    private void copyDeidentifiedVideo(Path base, Long parentRawSn, LsDataRaw newRaw) {
        String deidVideoPath = deidentProcLogRepository.findLatestSuccessByDataRawSn(parentRawSn)
                .map(LsDeidentProcLog::getDeIdntfFilePathNm)
                .filter(p -> p != null && !p.isBlank())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "원본 비식별 영상 경로를 찾을 수 없습니다: parentRawSn=" + parentRawSn));

        Path videoSrc = resolveSafeFile(base, deidVideoPath);
        if (!videoFileCopier.exists(videoSrc)) {
            throw new CustomException(ErrorCode.NOT_FOUND, "원본 비식별 영상 파일을 찾을 수 없습니다.");
        }
        Path videoDst = resolveSafeFile(base, newRaw.getRawFilePathNm());
        videoFileCopier.copy(videoSrc, videoDst);
    }

    /** 프레임 청크 리스케일 → 새 LS_DATA_SRC 생성 + videoFrameNo 명시 키 매핑(인덱스 zip 금지). */
    private FrameMapping rescaleFrames(Path base, Long parentRawSn, Long newRawSn, int targetW, int targetH) {
        Map<Long, Long> frameNoToNewSrc = new LinkedHashMap<>();   // videoFrameNo → 신규 SRC_SN (중복 가드)
        Map<Long, Long> parentSrcToNewSrc = new LinkedHashMap<>(); // 부모 SRC_SN → 신규 SRC_SN (라벨 재매핑)
        int page = 0;
        int total = 0;
        while (true) {
            List<LsDataSrc> chunk = srcRepository
                    .findByRawSnOrderByFrameNoAsc(parentRawSn, PageRequest.of(page, FRAME_CHUNK_SIZE))
                    .getContent();
            if (chunk.isEmpty()) {
                break;
            }
            for (LsDataSrc pf : chunk) {
                long frameKey = frameNumberOf(pf);
                if (frameNoToNewSrc.containsKey(frameKey)) {
                    // 부모 프레임에 중복 videoFrameNo 가 있으면 라벨 이중매핑 → fail-fast(고아 원천 차단).
                    throw new CustomException(ErrorCode.INTERNAL_ERROR,
                            "부모 프레임에 중복 videoFrameNo 가 있습니다: parentRawSn=" + parentRawSn);
                }
                // LOW — 파생 픽셀 복사는 반드시 비식별 프레임에서만. deid 경로가 blank/부재면 폴백하지
                // 않고 실패시켜 원본(비-비식별) 픽셀이 복제 후 'Y' 스탬프되는 불변식 위반을 차단한다.
                String deidFrameSrc = deidFrameSourceStrict(pf);
                Path fsrc = resolveSafeFile(base, deidFrameSrc);
                Path fdst = resolveSafeDir(base,
                        "resolution/" + newRawSn + "/frames/" + fileNameOf(deidFrameSrc, pf));
                imageResizer.resize(fsrc, fdst, targetW, targetH);

                LsDataSrc nf = srcRepository.save(LsDataSrc.create(
                        newRawSn, pf.getFrameNo(), pf.getVideoFrameNo(), fdst.toString(), pf.getShtDt()));
                nf.attachDeidPath(fdst.toString()); // 파생본은 비식별 산출 → 비식별 프레임 경로 동시 기록
                frameNoToNewSrc.put(frameKey, nf.getSrcSn());
                parentSrcToNewSrc.put(pf.getSrcSn(), nf.getSrcSn());
                total++;
            }
            page++;
        }
        if (total == 0) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR,
                    "파생할 프레임이 없습니다: parentRawSn=" + parentRawSn);
        }
        return new FrameMapping(parentSrcToNewSrc, total);
    }

    /** 부모 라벨을 좌표 스케일 복사 + LS_RESOLUTION_LBL_MAP(coordRecalc='Y', scaleX/scaleY) 적재. */
    private int copyScaledLabels(Map<Long, Long> parentSrcToNewSrc, Long resExportSn,
                                 double scaleX, double scaleY, String regId) {
        List<LsDataLbl> parentLabels = lblRepository.findBySrcSnIn(parentSrcToNewSrc.keySet());
        if (parentLabels.isEmpty()) {
            return 0;
        }
        BigDecimal sx = BigDecimal.valueOf(scaleX);
        BigDecimal sy = BigDecimal.valueOf(scaleY);

        // saveAll 반환 순서 비의존 — 원본 lblSn 을 복사본과 동반해 명시 매핑(AugmentFrameExtractionService 패턴).
        List<LabelCopy> copies = parentLabels.stream()
                .map(lbl -> new LabelCopy(lbl.getLblSn(),
                        LsDataLbl.copyForNewSrcScaled(parentSrcToNewSrc.get(lbl.getSrcSn()), lbl, scaleX, scaleY)))
                .toList();
        lblRepository.saveAll(copies.stream().map(LabelCopy::copy).toList());

        List<LsResolutionLblMap> maps = new ArrayList<>(copies.size());
        for (LabelCopy c : copies) {
            maps.add(LsResolutionLblMap.create(
                    resExportSn, c.originalLblSn(), c.copy().getLblSn(), true, sx, sy, regId));
        }
        lblMapRepository.saveAll(maps);
        return copies.size();
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

    /** 파일 경로 normalize + base 검증 (CWE-22). */
    private Path resolveSafeFile(Path base, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new CustomException(ErrorCode.NOT_FOUND, "경로가 비어있습니다.");
        }
        Path candidate = Paths.get(filePath);
        Path resolved = candidate.isAbsolute() ? candidate.normalize() : base.resolve(candidate).normalize();
        if (!resolved.startsWith(base)) {
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

    /** 원본 lblSn 을 복사본과 동반(saveAll 순서 비의존 명시 매핑). */
    private record LabelCopy(Long originalLblSn, LsDataLbl copy) {
    }

    /** 프레임 매핑 결과 — 부모 SRC_SN → 신규 SRC_SN + 신규 프레임 수. */
    private record FrameMapping(Map<Long, Long> parentSrcToNewSrc, int newFrameCount) {
    }
}
