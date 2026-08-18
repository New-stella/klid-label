package kr.co.cudo.authoring.portal.scheduler;

import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.service.PortalUploadSweepTxService;
import kr.co.cudo.authoring.upload.service.TusChunkStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 포털 업로드 정리 스윕 잡.
 *
 * <ul>
 *   <li>#13 만료 TUS 세션 정리 — {@code EXPRY_DT < now} + 진행 중 세션의 임시파일 삭제 + 행 제거
 *       (관제 {@code TusUploadCleanupJob} 패턴 + storage root 가드).</li>
 *   <li>#4/#5 고착 자산 → FAILED — UPLOADED/PROCESSING 상태로 N분 이상 갱신이 멈춘
 *       LS_PORTAL_ULD 를 FAILED 전이(영구 로딩 방지).</li>
 * </ul>
 *
 * <p>이 잡은 <b>스케줄 오케스트레이션만</b> 담당한다 — 벌크 UPDATE/DELETE 는 {@code @Transactional}
 * 별 빈({@link PortalUploadSweepTxService})에 위임하고, 파일 정리(비-tx best-effort)만 잡에서 수행한다.
 * 트랜잭션 경계 메서드를 이 잡의 self-invocation 으로 호출하면 {@code @Scheduled} 프록시가 우회돼
 * 트랜잭션이 적용되지 않으므로({@code TransactionRequiredException}) 반드시 별 빈을 통한다.
 *
 * <p>빈 등록은 <b>자기 토글</b>({@code portal.upload.sweep.enabled})이 소유한다 — 스케줄링 활성화
 * ({@link PortalUploadSweepSchedulingConfig})와 <b>같은 키</b>여야 한다. 한쪽만 걸면 남의 기능이 켜 둔
 * {@code @EnableScheduling} 위에서 이 잡이 자기 토글과 무관하게 도는 반대 방향의 결함이 생긴다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "portal.upload.sweep.enabled", havingValue = "true", matchIfMissing = false)
public class PortalUploadSweepJob {

    private final PortalUploadSweepTxService txService;
    /** 보안 LOW(CWE-22): TUS 임시파일 삭제 대상이 storage root 하위인지 재검증. */
    private final Path storageRoot;
    private final long stuckTimeoutMinutes;

    public PortalUploadSweepJob(PortalUploadSweepTxService txService, PortalUploadProperties properties) {
        this.txService = txService;
        this.storageRoot = Paths.get(properties.storagePath()).toAbsolutePath().normalize();
        this.stuckTimeoutMinutes = properties.stuckTimeoutMinutes();
    }

    /**
     * 30분 간격 스윕.
     *
     * <p>주기/초기지연은 {@link PortalUploadProperties} 로 옮기지 못하고 placeholder 로 남는다 —
     * {@code @Scheduled} 어노테이션 속성은 상수 표현식만 허용하므로 record 값을 참조할 수 없다.
     * 대신 두 키를 공통 yml 에 명시해 운영자가 발견·조정할 수 있게 한다(D1).
     */
    @Scheduled(fixedDelayString = "${portal.upload.sweep.interval-ms:1800000}",
               initialDelayString = "${portal.upload.sweep.initial-delay-ms:600000}")
    public void run() {
        try {
            int sessions = cleanupExpiredSessions();
            int stuck = failStuckUploads();
            if (sessions > 0 || stuck > 0) {
                log.info("[PortalSweep] expiredSessions={} stuckFailed={}", sessions, stuck);
            }
        } catch (RuntimeException e) {
            log.error("[PortalSweep] sweep failed reason={}", e.getClass().getSimpleName());
        }
    }

    /**
     * #13 + database 🔴1 / security M-4: 만료 TUS 세션 벌크 삭제(트랜잭션 위임) + 소유 세션의
     * 임시파일 정리(비-tx best-effort). 파일 삭제는 {@code deleteQuietly} 라 멱등.
     */
    public int cleanupExpiredSessions() {
        List<String> claimedFilePaths = txService.claimExpiredSessions();
        for (String filePathNm : claimedFilePaths) {
            TusChunkStore.deleteQuietly(filePathNm, storageRoot);
        }
        return claimedFilePaths.size();
    }

    /**
     * #4/#5 + adversarial #3: 고착 자산 FAILED 전이(트랜잭션 위임) + FAILED 전이에 성공한 자산의
     * 부분 추출 프레임 디렉토리 정리(비-tx best-effort). 원본 영상은 보존(삭제 API 가 정리).
     *
     * <h3>판정 축은 「총 처리 시간」이 아니라 「최종 변경 일시 무갱신 경과」다</h3>
     * <p>커트라인은 {@code MDFCN_DT < now - stuckTimeoutMinutes} 이며
     * ({@code LsPortalUldRepository#findStuck}), 프레임 추출 러너가 하트비트
     * ({@code touchProcessing})로 그 값을 계속 밀어낸다. 그래서 <b>대용량 영상의 추출이 몇 시간
     * 걸려도 방치로 판정되지 않는다</b>. 총 경과시간으로 바꾸면 정상 처리를 죽인다.
     *
     * <h3>★ 그 전제는 <b>하트비트 간격에 상한이 있을 때만</b> 성립한다</h3>
     * <p>이 문단은 한동안 사실이 아니었다 — 러너는 <b>50프레임마다</b> 한 번만 하트비트를 쳤고, 그
     * 사이 49장을 뽑는 데 걸리는 시간에는 상한이 없었다(프레임 정확 추출은 입력 seek 없이 파일
     * 처음부터 디코딩해 1장의 비용이 프레임 위치에 비례해 커지고, 프로세스 대기에도 상한이 없었다).
     * 즉 <b>정상 추출이 방치로 판정</b>돼 부분 프레임이 지워지고, 실패 보존기간 뒤 원본 영상까지
     * 비가역 삭제될 수 있었다.
     * <p>지금은 두 축이 그 간격을 유계로 만든다 —
     * ① 러너의 하트비트가 <b>경과 시간</b> 기준이고 그 간격은 이 커트라인에서 파생되며
     * ({@code PortalFrameExtractRunner#heartbeatIntervalSec}),
     * ② 프레임 1장의 추출 프로세스에 대기 상한이 있다({@code authoring.ffmpeg.frame-timeout-sec}).
     * 성립해야 하는 관계는 <b>하트비트 간격 + 프레임 대기 상한 &lt; 이 커트라인</b> 이며
     * {@code PortalFrameExtractHeartbeatTest} 가 기본값 조합으로 고정한다.
     *
     * <h3>설정이 비정상이면 그 회차를 건너뛴다 — 기본값으로 대체하지 않는다</h3>
     * <p>이 전이는 <b>삭제의 예고</b>다. FAILED 로 내려간 자산은 실패 보존기간
     * ({@code portal.upload.failed-retention-days}, 기본 1일)에 걸려 보존기간 스윕이 파일과 DB 행을
     * 비가역으로 지운다. 그런데 {@code stuck-timeout-minutes} 가 0 이면 "0분간 갱신 없으면 실패"가
     * 되어 <b>정상 처리 중인 자산 전량</b>이 즉시 대상이 되고, 음수면 커트라인이 미래가 되어 결과가
     * 같다. 여기서 임의 기본값으로 조용히 메우면 <b>운영자가 지시하지 않은 기준으로 사용자 데이터가
     * 지워진다</b> — 그래서 폴백하지 않고 그 회차를 건너뛰며 경고만 남긴다(형제 규칙: 보존기간 3키가
     * 없으면 삭제 배치가 그 회차를 건너뛰는 것과 같은 취지).
     *
     * <p>⚠ <b>여기서 걸러지는 것은 「0 이하」뿐이다.</b> 값이 숫자가 아니거나 비어 있으면 이 잡이 아니라
     * <b>설정 바인딩</b>이 먼저 막는다 — {@code PortalUploadProperties} 의 {@code long} 성분으로
     * 변환할 수 없어 기동이 실패한다({@code ConfigurationPropertiesBindException}, 실측 확인).
     * 그 편이 더 안전하다: 오타 하나가 삭제 기준을 조용히 바꾸는 대신 기동 단계에서 드러난다.
     * 회귀 고정은 {@code PortalStuckTimeoutBindingTest}.
     *
     * @return FAILED 전이에 성공한 자산 수(설정이 비정상이면 아무것도 하지 않고 {@code 0})
     */
    public int failStuckUploads() {
        if (stuckTimeoutMinutes <= 0) {
            log.warn("[PortalSweep] portal.upload.stuck-timeout-minutes 가 유효하지 않아 이 회차의 "
                    + "고착 자산 마감을 건너뛴다(기본값으로 대체하지 않는다) value={}", stuckTimeoutMinutes);
            return 0;
        }
        List<Long> failedUldSns = txService.failStuckUploads(stuckTimeoutMinutes);
        for (Long uldSn : failedUldSns) {
            cleanupFrameDir(uldSn);
        }
        return failedUldSns.size();
    }

    /** 고착 FAILED 자산의 부분 추출 프레임 디렉토리 정리(best-effort, storage root 가드). */
    private void cleanupFrameDir(Long uldSn) {
        Path framesDir = storageRoot.resolve("frames").resolve(String.valueOf(uldSn)).normalize();
        if (!framesDir.startsWith(storageRoot)) {
            log.warn("[PortalSweep] frame dir outside storage root — skip uldSn={}", uldSn);
            return;
        }
        if (!Files.exists(framesDir)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(framesDir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (java.io.IOException e) {
                    log.warn("[PortalSweep] frame file cleanup failed uldSn={} cause={}",
                            uldSn, e.getClass().getSimpleName());
                }
            });
        } catch (java.io.IOException e) {
            log.warn("[PortalSweep] frame dir walk failed uldSn={} cause={}",
                    uldSn, e.getClass().getSimpleName());
        }
    }
}
