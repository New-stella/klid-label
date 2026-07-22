package kr.co.cudo.authoring.webhook.service;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
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
import java.util.List;
import java.util.Optional;

/**
 * 증강 프레임 재추출 확정 — <b>Phase A(검증·스냅샷)</b>. 커넥션-점유 분리 리팩터의 1단계(증강 경로).
 *
 * <p><b>짧은 {@code REQUIRES_NEW} 트랜잭션</b>만 담당한다. 무거운 파일 I/O(ffmpeg 프레임 재추출)는 절대
 * 이 단계에 넣지 않는다 — 커넥션을 쥔 채 대용량 I/O 를 돌리면 다건 증강 동시 시 커넥션풀이 압박받기
 * 때문이다(리팩터 목적). 여기서는 멱등 가드 + 부모 프레임 조회 + 경로 계산만 수행하고 즉시 커밋한다.
 *
 * <h3>부모 잠금·비식별 재검증 미추가 (설계 유지 — 반드시 준수)</h3>
 * <p>부모 {@code findByRawSnForUpdate} 잠금·{@code deIdntfYn=='Y'} 게이트는 {@code AugmentResultService.handle}
 * 의 동기 트랜잭션 시점 판정으로만 유효하다(그 시점의 부모 안전 판정만 PII TOCTOU 를 막는다 — CWE-359).
 * 본 리팩터는 <b>순수 커넥션 분리</b>이며, 구 {@code AugmentFrameExtractionService.extractAndCopy} 가 하던
 * 수준(무잠금 findById + 기존 멱등 가드)만 유지한다. 해상도 파생의 부모 재검증 게이트는 이식하지 않는다
 * (하면 동기 판정 이후 창을 재개방한다).
 *
 * <h3>수행</h3>
 * <ol>
 *   <li>멱등 가드 — 신규 RAW 가 이미 {@code deIdntfYn=='Y'} 면 {@link Optional#empty()} 반환(중복 트리거 skip)</li>
 *   <li>부모 프레임 조회 + 프레임 0건 fail-fast + 중복 videoFrameNo fail-fast(고아 라벨 이중매핑 원천 차단)</li>
 *   <li>증강 소스 경로 + 프레임별 산출 경로(CWE-22 검증) 스냅샷 → {@link AugmentExtractPlan} 반환 후 커밋</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AugmentExtractSnapshot {

    private final VideoRepository videoRepository;
    private final LsDataAugRepository augRepository;
    private final LsDataSrcRepository srcRepository;

    @Value("${authoring.storage.raw-path:./storage/raw}")
    private String storageRawPath;

    /**
     * 재추출 확정의 검증·스냅샷 단계. 성공 시 Phase B/C 가 필요로 하는 불변 값 묶음을 반환한다.
     *
     * @return 계획 — 이미 확정된 신규 RAW 면 {@link Optional#empty()}(멱등 skip)
     * @throws CustomException 신규 RAW/증강행 부재(NOT_FOUND)·프레임 0건·중복 videoFrameNo·경로 위반 등 —
     *                         러너가 catch 하여 markRawDataFailed 전이한다.
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

        String sourcePath = newRaw.getRawFilePathNm();
        if (sourcePath == null || sourcePath.isBlank()) {
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

        // 부모 프레임에 중복 videoFrameNo 가 있으면 라벨 재매핑 key 가 붕괴해 고아 프레임 + 라벨 이중매핑이
        // 발생한다. fail-fast 로 고아 생성을 원천 차단한다(구 extractAndCopy 동작 보존).
        java.util.Set<Long> seen = new java.util.HashSet<>();
        Path base = Paths.get(storageRawPath).toAbsolutePath().normalize();
        Path framesDir = resolveSafeDir(base, "frames/raw/" + newRawSn);

        List<AugmentExtractPlan.FrameSpec> frames = new ArrayList<>(parentFrames.size());
        for (int i = 0; i < parentFrames.size(); i++) {
            LsDataSrc pf = parentFrames.get(i);
            long frameNo = frameNumberOf(pf);
            if (!seen.add(frameNo)) {
                throw new CustomException(ErrorCode.INTERNAL_ERROR,
                        "부모 프레임에 중복 videoFrameNo 가 있습니다: parentRawSn=" + parentRawSn);
            }
            Path dst = resolveSafeDir(base, "frames/raw/" + newRawSn + "/frame-" + i + ".jpg");
            frames.add(new AugmentExtractPlan.FrameSpec(
                    pf.getSrcSn(), i, frameNo, newRaw.getShtDt(), dst));
        }

        log.info("[Augment][ExtractA] plan ready rawSn={} orgnlRawSn={} frames={}",
                newRawSn, parentRawSn, frames.size());
        return Optional.of(new AugmentExtractPlan(
                newRawSn, parentRawSn, dataAugSn, aug.getRegUserNo(),
                Paths.get(sourcePath), framesDir, frames));
    }

    /**
     * 재추출 대상 디코더 프레임 번호. {@code videoFrameNo}(실제 영상 프레임 위치)가 있으면 그것을, 없으면
     * (구 데이터) 추출 순번 {@code frameNo} 로 폴백한다. 신규 프레임의 videoFrameNo 에도 동일 값을 실어
     * 라벨 재매핑 key 로 사용한다(양쪽 동일 계산식이라 매핑 정합).
     */
    private static long frameNumberOf(LsDataSrc frame) {
        return frame.getVideoFrameNo() != null ? frame.getVideoFrameNo() : frame.getFrameNo();
    }

    /** 상대경로를 base 하위로 결정론적 해석 + normalize 검증 (CWE-22). */
    private Path resolveSafeDir(Path base, String relative) {
        Path resolved = base.resolve(relative).normalize();
        if (!resolved.startsWith(base)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "프레임 출력 경로가 허용된 저장 경로를 벗어납니다.");
        }
        return resolved;
    }
}
