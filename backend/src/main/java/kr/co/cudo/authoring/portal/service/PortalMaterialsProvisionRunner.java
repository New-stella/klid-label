package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import kr.co.cudo.authoring.common.client.PortalMaterialsClient;
import kr.co.cudo.authoring.common.client.dto.PortalMaterialsResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

/**
 * 조달 한 건을 실제로 수행하는 작업자 — <b>조회 → 경로 재검증 → 사본 복사·해제 → 공개</b>.
 *
 * <h3>왜 별도 빈인가</h3>
 * <p>{@code @Async} 는 프록시를 거쳐야 걸리므로 같은 빈 안에서 부르면 <b>동기로 실행된다</b>.
 * 착수 창구가 즉시 답하려면 이 진입점이 다른 빈에 있어야 한다.
 *
 * <h3>★ 예외를 밖으로 던지지 않는다</h3>
 * <p>{@code @Async void} 의 예외는 호출자에게 닿지 않고 기본 처리기 로그로만 사라진다. 그래서
 * 여기서 붙잡아 <b>실패 사유를 값으로 기록</b>하고, 사용자는 상태 조회로 그것을 본다.
 *
 * <h3>★ 원본은 읽기만 한다</h3>
 * <p>조달처의 배포 압축본은 포털 소유 원본이라 이동·수정·삭제·덮어쓰기를 하지 않는다. 이 클래스가
 * 원본에 대해 하는 유일한 일은 <b>재검증된 실경로를 읽기로 여는 것</b>이다.
 *
 * @design INT-014
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalMaterialsProvisionRunner {

    private final PortalMaterialsClient client;
    private final PortalMaterialsPathGuard pathGuard;
    private final PortalMaterialsUnpacker unpacker;
    private final PortalMaterialsWorkspace workspace;
    private final PortalMaterialsProvisionState state;

    /**
     * 조달을 수행한다 — 선점({@code state.claim})은 <b>호출자가 이미 마쳤다</b>.
     *
     * <p>선점을 여기서 하지 않는 이유: 실행이 큐에서 지연되는 동안 같은 대상의 착수가 또 들어오면
     * 중복 착수가 된다. 선점은 <b>착수 요청을 받은 그 순간</b> 이뤄져야 한다.
     */
    @Async("portalMaterialsExecutor")
    public void runAsync(long datasetId) {
        try {
            provision(datasetId);
            state.clearFailure(datasetId);
        } catch (Throwable t) {
            PortalMaterialsFailureReason reason = classify(t);
            state.recordFailure(datasetId, reason);
            // ⚠ 예외 메시지에 경로·본문 원문이 섞일 수 있으므로 그대로 찍지 않는다(CWE-209).
            //   사유 분류와 예외 타입 이름만 남긴다.
            log.warn("[PortalMaterials] 조달 실패 datasetId={} reason={} type={}",
                    datasetId, reason, t.getClass().getSimpleName());
        } finally {
            state.release(datasetId);
        }
    }

    /** 조달 본체 — 단계마다 실패를 <b>사유 있는 예외</b>로 올린다. */
    void provision(long datasetId) throws IOException {
        PortalMaterialsResponse response = client.fetch(datasetId);
        if (response == null) {
            throw new ProvisionFailure(PortalMaterialsFailureReason.FETCH_FAILED);
        }
        PortalMaterialsResponse.MaterialFile zip = response.deploymentZip();
        if (zip == null) {
            // 모르는 소재 구분은 무시하지만, 배포 압축본이 <아예 없는> 것은 조달이 성립하지 않는다.
            throw new ProvisionFailure(PortalMaterialsFailureReason.NO_DEPLOYMENT_ZIP);
        }

        // ★ 받은 절대경로를 그대로 열지 않는다 — 루트 하위인지 실경로로 재검증하고 그 실경로로 연다.
        PortalMaterialsPathGuard.Check check =
                pathGuard.resolveWithinRoot(response.repoRootDir(), zip.localPath());
        if (!check.ok()) {
            log.warn("[PortalMaterials] 소재 경로 재검증 거부 datasetId={} verdict={}",
                    datasetId, check.verdict());
            throw new ProvisionFailure(PortalMaterialsFailureReason.MATERIAL_PATH_REJECTED);
        }

        Path staging = null;
        try {
            staging = workspace.createStaging(datasetId);
            PortalMaterialsUnpacker.UnpackResult result =
                    unpacker.copyAndUnpack(check.realPath(), staging);
            workspace.writeSummary(staging, new PortalMaterialsSummary(
                    response.code(), response.version(), response.variant(),
                    result.entryCount(), result.totalBytes(),
                    response.datasetVideos().size(), Instant.now()));
            boolean published = workspace.publish(datasetId, staging);
            if (published) {
                staging = null; // 공개된 자리는 우리 것이 아니다 — 정리 대상에서 뺀다.
                log.info("[PortalMaterials] 조달 완료 datasetId={} entries={} bytes={}",
                        datasetId, result.entryCount(), result.totalBytes());
            } else {
                // 다른 노드가 먼저 끝냈다 — 실패가 아니다. 우리 것을 버리고 그 결과를 쓴다.
                log.info("[PortalMaterials] 이미 공개된 해제본이 있어 우리 작업본을 버립니다 datasetId={}",
                        datasetId);
            }
        } finally {
            // 공개하지 못한 작업본은 어떤 경로로 끝나든 남기지 않는다(반쯤 풀린 자리 누적 방지).
            workspace.discardQuietly(staging);
        }
    }

    /**
     * 실패를 사유 분류로 접는다.
     *
     * <p>⚠ 미구성({@code PortalMaterialsUnavailableException})은 <b>4xx 거부보다 먼저</b> 본다 —
     * 그 예외가 {@link NonRetryableExternalException} 의 하위라 순서를 뒤집으면 「설정이 없다」가
     * 「포털이 거부했다」로 잘못 기록되고, 운영자가 엉뚱한 곳을 본다.
     */
    private static PortalMaterialsFailureReason classify(Throwable t) {
        if (t instanceof ProvisionFailure f) {
            return f.reason();
        }
        if (t instanceof PortalMaterialsClient.PortalMaterialsUnavailableException) {
            return PortalMaterialsFailureReason.NOT_CONFIGURED;
        }
        if (t instanceof NonRetryableExternalException) {
            return PortalMaterialsFailureReason.FETCH_REJECTED;
        }
        if (t instanceof PortalMaterialsUnpacker.UnpackException) {
            return PortalMaterialsFailureReason.UNPACK_REJECTED;
        }
        if (t instanceof IOException) {
            return PortalMaterialsFailureReason.IO_ERROR;
        }
        return PortalMaterialsFailureReason.FETCH_FAILED;
    }

    /** 단계별 실패 — 사유를 값으로 실어 나른다. */
    static class ProvisionFailure extends RuntimeException {

        private final PortalMaterialsFailureReason reason;

        ProvisionFailure(PortalMaterialsFailureReason reason) {
            super(reason.name());
            this.reason = reason;
        }

        PortalMaterialsFailureReason reason() {
            return reason;
        }
    }
}
