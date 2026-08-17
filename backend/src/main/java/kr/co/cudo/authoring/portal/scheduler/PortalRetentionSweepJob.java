package kr.co.cudo.authoring.portal.scheduler;

import kr.co.cudo.authoring.portal.service.PortalRetentionSweepTxService;
import kr.co.cudo.authoring.portal.service.PortalRetentionSweepTxService.ExpiredUpload;
import kr.co.cudo.authoring.portal.service.PortalStoragePathGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;

/**
 * 포털 보존기간 만료 자동 삭제 잡 (2축). @design DFEAT-055, AC-032, AC-036, AC-037
 *
 * <ul>
 *   <li><b>축 A — 데이터마트 라벨</b>: 그 (사용자, 영상) 그룹 저장 라벨의 {@code MAX(REG_DT)} 가
 *       커트라인보다 이르면 그 그룹의 라벨을 삭제한다. 파일이 없어 DB 한 문장으로 끝난다.</li>
 *   <li><b>축 B — 업로드 자산</b>: {@code READY}(등록일·라벨 최종 저장일 중 늦은 쪽 기준) 와
 *       {@code FAILED}({@code MDFCN_DT} 기준) 를 각자의 보존기간으로 판정해 <b>파일 → DB</b> 순으로
 *       삭제한다.</li>
 * </ul>
 *
 * <h3>★ 이 잡은 사용자 데이터를 비가역으로 지운다 — 지키는 규칙</h3>
 * <ol>
 *   <li><b>설정이 없으면 그 축을 건너뛴다</b>(폴백 금지). 판정은
 *       {@link PortalRetentionSweepTxService} 가 하고 여기서 기본값을 만들지 않는다.</li>
 *   <li><b>{@code PROCESSING} 은 절대 지우지 않는다</b>(AC-036). 후보 쿼리에 상태 리터럴이 박혀 있어
 *       구조적으로 후보가 되지 못한다 — 프레임 추출 러너와 경쟁하면 파일·DB 불일치가 난다.</li>
 *   <li><b>파일 먼저, DB 나중</b>. 뒤집으면 DB 가 먼저 사라져 <b>어느 파일을 지워야 하는지 알 수
 *       없게</b> 되고 고아 파일이 영구히 남는다.</li>
 *   <li><b>실경로 판정이 {@code OK} 가 아니면 그 자산을 통째로 건너뛴다</b>(AC-037 and_examples[2]) —
 *       파일이 남았는데 DB 만 지우면 어느 파일인지 알 수 없어지므로 DB 행도 두고 다음 회차에
 *       재후보한다. <b>배치 전체를 중단하지는 않는다</b>.</li>
 *   <li>로그에 절대 경로·PII 를 남기지 않는다(CWE-209/359) — 식별자와 사유만 남긴다.</li>
 * </ol>
 *
 * <p>기존 {@link PortalUploadSweepJob}(TUS 세션 정리 + 고착 FAILED 전이)에 얹지 않고 분리한 것은
 * 의도다 — 비가역 파괴 작업이라 주기·실패 격리·감사를 독립시킨다. 다만 패턴은 그대로 따른다:
 * 잡은 <b>오케스트레이션만</b> 하고 트랜잭션 경계는 별 빈이 가지며(self-invocation 프록시 우회 방지),
 * 2노드 Active-Active 는 <b>조건부 벌크 쿼리의 영향행수</b>로 멱등화한다(분산 락을 새로 만들지 않는다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalRetentionSweepJob {

    private final PortalRetentionSweepTxService txService;

    /** 삭제·서빙이 공유하는 <b>단일 판정기</b> — 여기서 경로 판정을 복제하지 않는다. @design AC-037 */
    private final PortalStoragePathGuard pathGuard;

    /**
     * 보존기간 스윕(기본 1시간 간격).
     *
     * <p>보존기간은 일 단위라 분 단위 정밀도가 필요 없다. 초기 지연을 길게 두는 것은 기동 직후
     * 마이그레이션·캐시 준비와 겹치지 않게 하기 위함이다. 주기/초기지연을 프로퍼티로 옮기지 못하고
     * placeholder 로 두는 것은 {@code @Scheduled} 속성이 상수 표현식만 허용하기 때문이며,
     * 두 키는 공통 yml 에 명시해 운영자가 발견·조정할 수 있게 한다(형제 잡과 동일 관례).
     */
    @Scheduled(fixedDelayString = "${portal.retention.sweep.interval-ms:3600000}",
               initialDelayString = "${portal.retention.sweep.initial-delay-ms:900000}")
    public void run() {
        try {
            int labelGroups = sweepDatamartLabels();
            int uploads = sweepExpiredUploads();
            if (labelGroups > 0 || uploads > 0) {
                log.info("[PortalRetention] datamartLabelGroups={} uploads={}", labelGroups, uploads);
            }
        } catch (RuntimeException e) {
            // 한 회차 실패가 잡 자체를 죽이지 않게 한다(다음 회차에 재시도).
            log.error("[PortalRetention] sweep failed reason={}", e.getClass().getSimpleName());
        }
    }

    /** 축 A — 데이터마트 저장 라벨 만료 삭제. @design AC-032 */
    public int sweepDatamartLabels() {
        return txService.sweepDatamartLabels();
    }

    /**
     * 축 B — 업로드 자산 만료 삭제. <b>자산마다 파일을 먼저 지우고 그 다음에 DB 행을 지운다.</b>
     * @design AC-036, AC-037
     *
     * <p>파일 삭제가 거부·실패한 자산은 <b>DB 행을 건드리지 않고</b> 건너뛴다 — 다음 회차에 다시
     * 후보가 된다. 한 자산의 실패가 나머지 자산 처리를 막지 않는다.
     *
     * @return DB 행 삭제까지 완료된 자산 수
     */
    public int sweepExpiredUploads() {
        int deleted = 0;
        for (ExpiredUpload candidate : txService.findExpiredUploads()) {
            if (!deleteFiles(candidate)) {
                // AC-037 and_examples[2] — 파일이 남았으므로 DB 행도 남긴다(다음 회차 재후보).
                continue;
            }
            int removed = txService.deleteExpiredUpload(candidate);
            if (removed > 0) {
                deleted++;
                continue;
            }
            // 0행의 정상 원인은 "타 노드가 먼저 지웠다" 뿐이다. 행이 아직 있다면 스캔~삭제 사이에
            // 조건이 풀린 것이라, 파일만 사라진 자산이 남는다 — 조용히 넘기지 않고 드러낸다.
            if (txService.exists(candidate.uldSn())) {
                log.error("[PortalRetention] 파일은 삭제됐으나 DB 행이 조건 불일치로 남았다 uldSn={} axis={}",
                        candidate.uldSn(), candidate.axis());
            }
        }
        return deleted;
    }

    /**
     * 자산 1건의 파일 전부 삭제(트랜잭션 밖). @design AC-037
     *
     * <p>판정은 {@link PortalStoragePathGuard} 한 곳이고 <b>판정이 돌려준 실경로로 지운다</b> —
     * lexical 경로로 검증하고 lexical 경로로 지우면 그 사이 경로 중간 디렉터리를 심링크로 갈아끼워
     * 저장 루트 밖 파일을 지우게 할 수 있다(CWE-59/367).
     *
     * @return 전부 처리됐으면 {@code true}(부재는 멱등 성공), 하나라도 거부·실패면 {@code false}
     */
    private boolean deleteFiles(ExpiredUpload candidate) {
        for (String pathStr : candidate.filePaths()) {
            PortalStoragePathGuard.RealPathCheck check = pathGuard.checkStoredPath(pathStr);
            switch (check.verdict()) {
                case ABSENT -> {
                    // 이미 없다 = 지울 것이 없다. 재시도 삭제가 영영 실패하지 않도록 정상 통과.
                }
                case OK -> {
                    try {
                        Files.deleteIfExists(check.realPath());
                    } catch (IOException e) {
                        log.warn("[PortalRetention] 파일 삭제 실패로 자산을 건너뛴다 uldSn={} causeType={}",
                                candidate.uldSn(), e.getClass().getSimpleName());
                        return false;
                    }
                }
                // fail-closed — 원본 삭제보다 고아 파일 존치가 안전하다. 경로 원문은 남기지 않는다.
                case ESCAPED, UNRESOLVABLE -> {
                    log.warn("[PortalRetention] 경로 판정 실패로 자산을 건너뛴다 uldSn={} verdict={}",
                            candidate.uldSn(), check.verdict());
                    return false;
                }
            }
        }
        return true;
    }
}
