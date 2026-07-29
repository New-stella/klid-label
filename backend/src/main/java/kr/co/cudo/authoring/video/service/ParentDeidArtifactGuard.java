package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * 파생영상 생성의 <b>물리적 전제</b> — 부모 비식별 <b>영상 산출물</b> 실재 여부를 요청 시점(동기)에 확인한다.
 *
 * <h3>왜 플래그만으로 부족한가 (E-ISSUE — 201 후 조용한 소멸)</h3>
 * <p>{@link LsDataRaw#hasDeidentArtifact()} 는 {@code 'F'} 를 통과시킨다("파생영상은 비식별 신고 체계
 * 바깥" 2026-07-29 확정). 그런데 {@code 'F'} 는 의미가 둘이라 ①<b>비식별 누락 신고</b>(비식별본은
 * 디스크에 존재)와 ②<b>비식별 API 실패</b>(산출물 자체가 없음)를 플래그로 구분할 수 없다. ②인 경우
 * 예약은 201 CREATED 로 응답하지만 비동기 확정(Phase A/B)이 반드시 실패하고, 러너 cleanup 이 파생
 * RAW·예약행을 지워 <b>흔적이 남지 않는다</b> — 사용자에겐 성공으로 보이고 목록은 0건이다.
 *
 * <p>그래서 예약 이전에 <b>최신 SUCCESS 비식별 procLog 경로의 파일 실재</b>를 확인해 ②를 동기 4xx 로
 * 거른다. 파일 <b>내용은 읽지 않고 존재 여부만</b> 본다(가벼운 I/O — 요청 트랜잭션 밖 호출).
 *
 * <p>경로 검증은 다른 파생 경로({@code ResolutionSnapshotService}·{@code AugmentExtractSnapshot})와 동일한
 * <b>2-way</b>다 — ①co-locate 비식별 영상 디렉터리 하위 또는 ②비식별 저장소의 비식별 전용 서브트리
 * ({@code videos/**}). 둘 다 아니면 거부한다(CWE-22). 로그·응답 어디에도 경로 원문을 담지 않는다(CWE-209).
 *
 * <p><b>남는 창은 관측으로 덮는다</b>: 이 확인 이후 확정(async)까지 사이에 산출물이 사라지는 경우까지
 * 막지는 못한다. 그 잔여 실패는 {@code AsyncResolutionRunner} 의 WARN + {@code resolution.finalize.failed}
 * 메트릭으로 드러난다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParentDeidArtifactGuard {

    /**
     * 부모 비식별 <b>영상</b> 경로의 진실원. 파일명은 고정이 아니므로(mock={@code deidentified.mp4} ·
     * KPST={@code {원본stem}-mask{ext}}) 조합·추측하지 않고 적재된 값을 읽는다.
     */
    private final LsDeidentProcLogRepository deidentProcLogRepository;

    /** co-locate 비식별 영상 디렉터리({@code dirname(원본)/{rawSn}/deid/}) 판정용. */
    private final VideoArtifactRootResolver artifactRootResolver;

    @Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String storageDeidentifiedPath;

    /**
     * 부모 비식별 산출물(영상 파일) 실재를 확인하고, 없으면 <b>동기 거부</b>한다.
     *
     * @param parent 원본(부모) 영상 — 파생 생성 대상
     * @throws CustomException {@link ErrorCode#CONFLICT} 산출물 부재(플래그 'N'·null 포함) /
     *                         {@link ErrorCode#INVALID_INPUT} 적재 경로가 허용 저장 경로 밖
     */
    public void requireParentDeidVideoPresent(LsDataRaw parent) {
        if (parent == null) {
            throw new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다.");
        }
        // 'N'(비식별 미수행)·null — 산출물이 존재할 수 없다(예약 게이트와 동일 판정, 동일 메시지 축).
        if (!parent.hasDeidentArtifact()) {
            log.warn("[Video][DeidArtifact] parent has no deident artifact — reject at request rawSn={} deIdntfYn={}",
                    parent.getRawSn(), safe(parent.getDeIdntfYn()));
            throw new CustomException(ErrorCode.CONFLICT,
                    "비식별 산출물이 있는 원본 영상만 파생영상을 만들 수 있습니다.");
        }

        String recordedPath = deidentProcLogRepository.findLatestSuccessByDataRawSn(parent.getRawSn())
                .map(LsDeidentProcLog::getDeIdntfFilePathNm)
                .filter(p -> p != null && !p.isBlank())
                .orElse(null);
        if (recordedPath == null) {
            log.warn("[Video][DeidArtifact] no SUCCESS deident proc log — reject at request rawSn={} deIdntfYn={}",
                    parent.getRawSn(), safe(parent.getDeIdntfYn()));
            throw new CustomException(ErrorCode.CONFLICT, ARTIFACT_MISSING_MESSAGE);
        }

        Path resolved = resolveSafeDeidVideo(recordedPath, parent);
        if (!Files.isRegularFile(resolved)) {
            // 경로 원문은 로그에도 남기지 않는다(CWE-209/532) — 식별자와 플래그만.
            log.warn("[Video][DeidArtifact] deident video file missing on disk — reject at request rawSn={} deIdntfYn={}",
                    parent.getRawSn(), safe(parent.getDeIdntfYn()));
            throw new CustomException(ErrorCode.CONFLICT, ARTIFACT_MISSING_MESSAGE);
        }
    }

    /** 사용자 노출 메시지 — 내부 경로·구조를 담지 않는다(CWE-209). */
    private static final String ARTIFACT_MISSING_MESSAGE =
            "원본의 비식별 산출물을 찾을 수 없어 해상도 파생을 만들 수 없습니다.";

    /**
     * 부모 비식별 영상 경로 검증 (2-way) — ①co-locate 비식별 영상 디렉터리 하위 또는 ②비식별 저장소의
     * 비식별 전용 서브트리. 둘 다 아니면 거부(fail-secure, 경로 원문 미노출).
     */
    private Path resolveSafeDeidVideo(String filePath, LsDataRaw parent) {
        Optional<Path> coLocateDir = (artifactRootResolver == null)
                ? Optional.empty()
                : artifactRootResolver.deidVideoDirQuietly(parent.getRawSn(), parent.getRawFilePathNm());
        if (coLocateDir.isPresent()) {
            Path candidate = Paths.get(filePath);
            Path resolved = candidate.isAbsolute()
                    ? candidate.normalize()
                    : coLocateDir.get().resolve(candidate).normalize();
            if (resolved.startsWith(coLocateDir.get())) {
                return resolved;
            }
        }
        Path base = Paths.get(storageDeidentifiedPath).toAbsolutePath().normalize();
        Path candidate = Paths.get(filePath);
        Path resolved = candidate.isAbsolute() ? candidate.normalize() : base.resolve(candidate).normalize();
        if (!StorageSubtreePolicy.isDeidentifiedArtifact(base, resolved)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "경로가 허용된 비식별 저장 경로를 벗어납니다.");
        }
        return resolved;
    }

    private static String safe(String s) {
        return s == null ? "null" : s.replaceAll("[\\r\\n\\t]", "_");
    }
}
