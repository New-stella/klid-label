package kr.co.cudo.authoring.webhook.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.video.service.port.ImageResizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 증강 프레임 확정 — <b>Phase B(파일 I/O)</b>. 커넥션-점유 분리 리팩터의 2단계(증강 경로).
 *
 * <p><b>{@code @Transactional} 절대 금지 · 리포지토리 주입 금지</b> — 순수 파일 서비스다. Phase A 가 확정한
 * {@link AugmentExtractPlan}(외부 산출 경로·산출 경로 전부 포함)만 받아 <b>파일로만</b> 산출한다. DB 접근 0,
 * 부모 잠금 0 이므로 이 대용량 I/O 구간에서 커넥션을 점유하지 않는다(리팩터 목적).
 *
 * <h3>Phase 7-D — 외부 산출물 반입 (구 ffmpeg 재추출 대체)</h3>
 * <p>구 구현은 증강 영상 파일에서 프레임을 <b>재추출</b>했는데, 그 영상은 부모(원본) 영상 그 자체라
 * 픽셀이 부모와 동일했다 — 즉 증강 효과가 0 인 사본이 모든 게이트를 통과했다. 이제 외부 생성형 AI 가
 * 반환한 산출 이미지를 파생 프레임 위치로 <b>반입(복사)</b> 한다. 영상 파일 자체는 이미지-to-이미지
 * 계약상 재생성되지 않으므로 그대로 둔다.
 *
 * <h3>반입 전 검증 (fail-closed — 모두 통과해야 복사)</h3>
 * <ol>
 *   <li><b>허용 루트</b> — 외부가 준 경로가 고정 allowlist 하위인지 다시 확인한다(CWE-22 다층 방어).
 *       수신 시점에도 검증하지만, DB 를 거쳐 온 값을 <b>쓰기 직전</b> 한 번 더 본다.</li>
 *   <li><b>실재</b> — 정규 파일이고 크기 &gt; 0. 빈 파일은 산출 실패의 흔한 형태다.</li>
 *   <li><b>해상도 동일</b> — 위탁했던 부모 비식별 프레임과 같은 해상도여야 한다. 외부 증강 3종
 *       (WINTER/NIGHT/RAIN)은 해상도를 바꾸지 않는다는 전제 위에서 라벨 좌표를 그대로 복사하므로,
 *       다르면 좌표가 통째로 어긋난다.</li>
 * </ol>
 *
 * <p><b>부분 쓰기 방지</b>: 임시 파일(.part)에 복사한 뒤 목적 경로로 move 한다 — 중간에 죽어도 반쯤
 * 쓰인 프레임이 목적 경로에 남지 않는다. 한 프레임이라도 실패하면 전체 실패(all-or-nothing)이며 러너가
 * {@link #cleanup} 으로 부분 산출을 정리하고 신규 RAW 를 FAILED 로 전이한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AugmentFrameProducer {

    /** 정상 산출로 인정하는 최소 파일 크기(byte). */
    private static final long MIN_OUTPUT_BYTES = 1L;

    /** 반입 중 임시 파일 접미사 — 목적 경로에 반쯤 쓰인 파일이 남지 않게 한다. */
    private static final String PART_SUFFIX = ".part";

    /** 외부가 준 산출 경로가 <b>읽기</b> 허용 루트 하위인지 복사 직전 재확인한다(CWE-22). */
    private final VideoArtifactRootResolver artifactRootResolver;
    /** 해상도 실측 — 프로젝트 기존 판독 수단(ImageIO 기반 포트)을 그대로 재사용한다. */
    private final ImageResizer imageResizer;

    /** 파생(비식별 계열) 산출물 base — cleanup 대상 디렉토리 산정 기준. */
    @Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String storageDeidentifiedPath;

    /**
     * 계획을 파일로 산출한다 — 외부 산출 이미지를 검증 후 파생 프레임 위치로 반입한다.
     * DB 접근·부모 잠금 없이 순수 파일 작업만 수행한다.
     *
     * @throws CustomException 기준 프레임 판독 실패·산출물 부재/빈 파일·해상도 불일치(INVALID_INPUT/CONFLICT)
     *                         · 복사 실패(INTERNAL_ERROR)
     */
    public void produce(AugmentExtractPlan plan) {
        // 기준 해상도는 부모 비식별 프레임 1건으로 실측한다 — 한 영상에서 추출한 프레임은 해상도가
        // 동일하므로 프레임마다 부모를 다시 디코딩할 필요가 없다(산출물은 전건 실측한다).
        int[] reference = readDimensions(plan.referenceFrame(), "기준 프레임");
        try {
            // 1) 비디오 — 부모 <비식별> 영상을 파생 전용 경로로 복사한다(증강 AI 는 영상을 재생성하지
            //    않는다). 소스 부재는 성공으로 둔갑시키지 않고 NOT_FOUND 로 실패시킨다(fail-closed,
            //    ResolutionFileMaterializer.materialize 1) 과 동일 자세).
            copyDeidVideo(plan);
            // 2) 프레임 — 외부 산출 이미지를 반입한다.
            ensureDir(plan.framesDir());
            for (AugmentExtractPlan.FrameSpec f : plan.frames()) {
                // 검증한 <실경로>를 그대로 복사한다 — lexical 경로를 다시 열면 검증 대상과 사용 대상이
                // 달라진다(TOCTOU, CWE-367/59).
                copyAtomically(verifySource(f, reference, plan.newRawSn()), f.dst());
            }
        } catch (IOException e) {
            // all-or-nothing — 러너가 cleanup 으로 부분 산출 파일을 정리한다.
            log.error("[Augment][ExtractB] external frame ingest failed rawSn={} err={}",
                    plan.newRawSn(), e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "증강 프레임 반입 실패", e);
        }
        log.info("[Augment][ExtractB] ingested rawSn={} frames={} (external outputs)",
                plan.newRawSn(), plan.frames().size());
    }

    /**
     * 부모 <b>비식별</b> 영상을 파생 전용 경로로 복사한다 — 파생영상의 비디오 파일 실체를 만든다.
     *
     * <p>소스({@code plan.deidVideoSrc()})와 목적지({@code plan.videoDst()})는 Phase A 가 CWE-22 검증까지
     * 마친 절대 경로다. 목적지는 파생 RAW_SN 을 키에 포함하므로 <b>부모 파일과 절대 겹치지 않는다</b>
     * (원본·부모 덮어쓰기 불가). 복사는 임시 파일(.part) 경유라 중간 실패 시 목적 경로에 반쯤 쓰인
     * 영상이 남지 않으며, 재실행하면 같은 경로를 덮어써 파일이 중복 적재되지 않는다(멱등).
     */
    private void copyDeidVideo(AugmentExtractPlan plan) throws IOException {
        Path src = plan.deidVideoSrc();
        if (src == null || !Files.isRegularFile(src)) {
            log.warn("[Augment][ExtractB] parent deid video missing rawSn={}", plan.newRawSn());
            throw new CustomException(ErrorCode.NOT_FOUND, "원본 비식별 영상 파일을 찾을 수 없습니다.");
        }
        Path dst = plan.videoDst();
        if (dst == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "파생 영상 산출 경로가 없습니다.");
        }
        ensureDir(dst.getParent());
        copyAtomically(src, dst);
        log.info("[Augment][ExtractB] deid video copied rawSn={}", plan.newRawSn());
    }

    /**
     * 반입 전 3중 검증 — 허용 루트 · 실재 · 해상도. 어느 하나라도 어긋나면 반입하지 않는다.
     *
     * <p>허용 루트는 <b>lexical 경로와 실경로 양쪽</b>에 적용한다 — 외부 저장소에 심어진 심링크가
     * 허용 루트 밖(예: 시스템 파일)을 가리키면 그 내용이 파생 프레임으로 복사돼 서빙/export 로
     * 새어 나가기 때문이다(CWE-59).
     *
     * @return 검증을 통과한 산출물의 <b>실경로</b>(복사 소스로 그대로 쓴다)
     */
    private Path verifySource(AugmentExtractPlan.FrameSpec frame, int[] reference, Long newRawSn) {
        Path src = frame.externalSource();
        verifyUnderAllowedRoots(src, frame, newRawSn);
        if (!Files.isRegularFile(src)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "증강 산출물 파일이 없습니다: frameNo=" + frame.frameNo());
        }
        Path real;
        long size;
        try {
            real = src.toRealPath();
            size = Files.size(real);
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "증강 산출물 파일을 읽을 수 없습니다: frameNo=" + frame.frameNo());
        }
        verifyRealPathUnderAllowedRoots(real, frame, newRawSn);
        if (size < MIN_OUTPUT_BYTES) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "증강 산출물 파일이 비어 있습니다: frameNo=" + frame.frameNo());
        }
        int[] dim = readDimensions(real, "증강 산출물");
        if (dim[0] != reference[0] || dim[1] != reference[1]) {
            // 해상도가 다르면 부모 라벨 좌표를 그대로 복사할 수 없다(좌표 전제 붕괴) → 확정 거부.
            log.warn("[Augment][ExtractB] resolution mismatch rawSn={} frameNo={} expected={}x{} actual={}x{}",
                    newRawSn, frame.frameNo(), reference[0], reference[1], dim[0], dim[1]);
            throw new CustomException(ErrorCode.CONFLICT,
                    "증강 산출물 해상도가 원본 프레임과 다릅니다: frameNo=" + frame.frameNo());
        }
        return real;
    }

    /**
     * <b>읽기</b> 허용 루트 하위 여부(lexical + 실경로 규약). 사유에 경로 원문을 담지 않는다(CWE-209).
     *
     * <p>벤더 산출물은 우리가 읽어서 복사할 대상이므로 판정 축은 <b>읽기</b> allowlist
     * ({@code raw-mount-roots ∪ external-read-roots})다 — 쓰기 base allowlist 는 넓히지 않는다.
     */
    private void verifyUnderAllowedRoots(Path path, AugmentExtractPlan.FrameSpec frame, Long newRawSn) {
        try {
            artifactRootResolver.verifyExternalReadablePath(path.toString());
        } catch (CustomException e) {
            rejectPath(frame, newRawSn, e);
        }
    }

    /**
     * 파일 <b>자체</b>가 심링크로 허용 루트 밖을 가리키는지 확인한다(CWE-59). 허용 루트 중 하나라도
     * 실경로 기준으로 만족하면 통과한다 — 루트 자체가 심링크 경로인 환경(예: {@code /var}→{@code /private/var})
     * 에서도 양쪽을 실경로로 맞춰 비교하므로 정상 파일을 오탐하지 않는다.
     */
    private void verifyRealPathUnderAllowedRoots(Path real, AugmentExtractPlan.FrameSpec frame, Long newRawSn) {
        for (Path root : artifactRootResolver.readableRoots()) {
            try {
                VideoArtifactRootResolver.verifyRealPathUnder(real, root);
                return;
            } catch (CustomException ignored) {
                // 다음 루트 후보로 계속 — 전부 실패하면 아래에서 거부한다(fail-secure).
            }
        }
        rejectPath(frame, newRawSn, new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 경로입니다."));
    }

    private void rejectPath(AugmentExtractPlan.FrameSpec frame, Long newRawSn, CustomException cause) {
        log.warn("[Augment][ExtractB] output path rejected (outside allowed roots) rawSn={} frameNo={} code={}",
                newRawSn, frame.frameNo(), cause.getErrorCode());
        throw new CustomException(ErrorCode.INVALID_INPUT,
                "증강 산출물 경로가 허용된 저장 경로가 아닙니다.");
    }

    /** 해상도 실측 — 판독 실패(부재/손상/미지원 포맷)는 fail-closed 로 거부한다. */
    private int[] readDimensions(Path path, String what) {
        try {
            int[] dim = imageResizer.readDimensions(path);
            if (dim == null || dim.length < 2 || dim[0] <= 0 || dim[1] <= 0) {
                throw new CustomException(ErrorCode.INVALID_INPUT, what + " 해상도를 확인할 수 없습니다.");
            }
            return dim;
        } catch (CustomException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, what + " 해상도를 확인할 수 없습니다.");
        }
    }

    /**
     * 임시 파일 경유 복사 — 목적 경로에 반쯤 쓰인 파일을 남기지 않는다.
     *
     * <p><b>소스는 {@code NOFOLLOW_LINKS} 로 연다</b>(DEV_FIX LOW-1, CWE-59/367). {@code toRealPath()}
     * 검증과 실제 복사 사이에 벤더가 최종 컴포넌트를 허용 루트 밖 심링크로 교체하면 경로 기반 복사
     * ({@code Files.copy(Path, ...)})는 그 링크를 따라간다. 스트림을 심링크 비추종으로 먼저 열고
     * <b>그 스트림에서</b> 복사하면, 교체가 일어난 경우 복사가 성립하지 않고 실패로 끝난다(fail-closed).
     */
    private static void copyAtomically(Path src, Path dst) throws IOException {
        Path tmp = dst.resolveSibling(dst.getFileName() + PART_SUFFIX);
        try {
            try (java.io.InputStream in = Files.newInputStream(src, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * 실패 시 Phase B 산출 아티팩트(파생 프레임 디렉토리 {@code frames/deid/{newRawSn}})를
     * <b>동기 삭제 후 {@link Files#exists} 재확인</b>한다. 잔존이 확인되면 {@code false} 를 반환하여
     * 러너가 ERROR 로그 + 메트릭({@code augment.cleanup.failed}) 을 남기게 한다.
     *
     * <p>신규(증강) RAW 경로만 삭제 대상이다 — 원본·부모 프레임/영상은 절대 삭제하지 않는다. 외부 산출
     * 원본 파일도 우리 소유가 아니므로 건드리지 않는다. 디렉토리는 {@code newRawSn} 으로 재계산해
     * CWE-22 검증한다(Phase A 와 동일 규약).
     *
     * <p>파생 비디오 사본도 함께 정리한다({@code ResolutionFileMaterializer.cleanup} 와 동일) — 경로가
     * 파생 RAW_SN 을 키에 포함하므로 다른 파생/부모 파일을 지울 수 없다.
     *
     * @param newRawSn 신규 증강 RAW_SN (프레임 디렉토리 {@code frames/deid/{newRawSn}/} 산정 기준)
     * @param videoDst 파생 비디오 목적 경로(검증 완료, nullable — Phase A 미도달 시 null)
     * @return 잔존 아티팩트 없음(정리 성공)=true, 삭제 후에도 잔존=false
     */
    public boolean cleanup(Long newRawSn, Path videoDst) {
        if (newRawSn == null) {
            return true;
        }
        Path base = Paths.get(storageDeidentifiedPath).toAbsolutePath().normalize();
        boolean clean = true;
        try {
            Path framesDir = resolveSafeDeidDir(base, StorageSubtreePolicy.deidFramesDir(newRawSn));
            deleteRecursivelyQuietly(framesDir);
            if (Files.exists(framesDir)) {
                clean = false;
            }
        } catch (RuntimeException e) {
            log.warn("[Augment][ExtractB] frames dir cleanup skipped newRawSn={} cause={}",
                    newRawSn, e.getClass().getSimpleName());
            clean = false;
        }
        if (videoDst != null) {
            try {
                Files.deleteIfExists(videoDst);
                if (Files.exists(videoDst)) {
                    clean = false;
                }
            } catch (IOException | RuntimeException e) {
                clean = false;
                log.warn("[Augment][ExtractB] video file cleanup skipped newRawSn={} cause={}",
                        newRawSn, e.getClass().getSimpleName());
            }
        }
        return clean;
    }

    private static void ensureDir(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
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
                    // best-effort — 개별 파일 삭제 실패는 무시(상위에서 exists 재확인으로 잔존 판정).
                }
            });
        } catch (IOException ignored) {
            // best-effort — 디렉토리 순회 실패는 무시(상위에서 exists 재확인으로 잔존 판정).
        }
    }

    /** 상대경로를 base 하위 + 비식별 전용 서브트리로 해석 (CWE-22 / CWE-359). */
    private static Path resolveSafeDeidDir(Path base, String relative) {
        Path resolved = base.resolve(relative).normalize();
        if (!StorageSubtreePolicy.isDeidentifiedArtifact(base, resolved)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "프레임 출력 경로가 허용된 저장 경로를 벗어납니다.");
        }
        return resolved;
    }
}
