package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 증강 취소의 <b>트랜잭션 경계</b> — 클레임(선점)과 확정(전이)을 분리한다.
 *
 * <h3>왜 "클레임 먼저 → 외부 호출 → 확정" 인가 (S4)</h3>
 * <p>웹훅·만료스윕은 {@code findByDataAugSnForUpdate}(FOR UPDATE)로 직렬화하는데, 취소만 잠금 없이
 * read-then-act 를 하면 동시 요청 둘이 모두 "아직 종결 아님" 을 관측하고 <b>둘 다 외부로 나가</b>
 * 두 번째가 벤더 409({@code STATE_CONFLICT})를 받는다. 사용자에게 409 가 노출되는 것은 결함이다.
 *
 * <p>그래서 {@link #claim}이 <b>잠금 안에서 상태를 판정하고 곧바로 {@code CANCELED} 로 전이</b>한다.
 * 상태 전이 자체가 클레임이므로 후행 요청은 잠금 해제 후 non-PENDING 을 관측해 <b>외부 호출 없이</b>
 * 멱등 200 으로 끝난다({@code AugmentQueryResult.SkipReason#LOCAL_TERMINAL} 과 같은 의미론을 서비스
 * 계층에서 미리 성립시키는 것이다).
 *
 * <p><b>외부 호출은 잠금 밖</b>이다 — 벤더 왕복 동안 행 잠금과 커넥션을 붙잡으면 그 증강에 대한
 * 웹훅 수신까지 대기시켜 교착에 가까운 지연을 만든다.
 *
 * <h3>클레임 이후 외부 취소가 실패하면? (의도된 트레이드오프)</h3>
 * <p>로컬은 {@code CANCELED} 인데 벤더는 계속 처리할 수 있다. 이때 뒤늦게 도착한 성공 웹훅은
 * {@code AugmentResultService} 의 non-PENDING 앵커에 흡수되어 <b>폐기</b>된다(WARN 로그).
 * 사용자가 "취소" 를 눌렀으므로 결과를 버리는 쪽이 의미상 맞고, 무엇보다 <b>PENDING 영구 고착</b>
 * (S1)을 만들지 않는다. 취소하지 못한 청크는 응답에 그대로 드러낸다(S2).
 *
 * <h3>★ 실제 전이 순서 — 두 트랜잭션이다 (DEV_FIX MED-6a 정정)</h3>
 * <p>구 주석은 "aug + job 을 <b>같은 트랜잭션·같은 잠금</b>에서 전이한다" 고 단언했으나 사실이 아니다.
 * 실제는 <b>aug 전이(tx1 커밋) → 외부 HTTP 왕복(청크마다 최대 블로킹 상한) → job 전이(tx2)</b> 이며,
 * 그 창에 SUCCEEDED 웹훅이 커밋되면 {@link #markJobsCanceled} 가 그 job 을 <b>의도적으로 건너뛴다</b>
 * (성공을 취소로 덮지 않는다). 따라서 <b>(aug=CANCELED, job=SUCCEEDED) 조합은 잔존할 수 있다</b>.
 *
 * <p><b>왜 이 구조를 유지하는가</b>: 외부 왕복을 잠금 안으로 넣으면 그 증강의 웹훅 수신까지 잠금
 * 대기에 걸려 교착에 가까운 지연이 생기고, 반대로 클레임 시점에 청크를 전부 CANCELED 로 미리 확정하면
 * 벤더가 실제로 성공시킨 청크까지 "취소됨" 으로 표기돼 산출물 유무를 오도한다.
 *
 * <p><b>잔존 불일치의 실제 영향은 관측상의 것뿐이다</b>: ①결과 인계는 증강 행 non-PENDING 앵커가
 * 막아 파생영상·롤업이 생기지 않고 ②화면 상태는 {@code AugmentProgressCalculator} 가 증강 상태
 * (CANCELED)를 최우선으로 읽으며 ③만료 스윕·회수는 terminal job 을 건드리지 않는다. 즉 <b>정합
 * (데이터 확정) 축에는 영향이 없고</b> job 행 하나가 SUCCEEDED 로 보이는 것이 남는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AugmentCancelTxService {

    /** 취소 확정 시 job 에 남기는 사유 — 내부 경로/식별정보 없이 원인만(CWE-209). */
    static final String CANCEL_REASON = "사용자 취소 요청(POST /v1/augments/{id}/cancel)";

    private final LsDataAugRepository augRepository;
    private final LsDataAugJobRepository jobRepository;

    /**
     * 취소 대상 청크 1건 — 외부 호출에 필요한 최소 정보만 담는다.
     *
     * @param augJobSn      청크 job PK
     * @param jobSeq        청크 순번(사용자 안내용)
     * @param externalJobId 외부 job_id. {@code null} 이면 202 ACK 미수신이라 외부에 취소를 보낼 수 없다
     */
    public record CancelTarget(Long augJobSn, int jobSeq, String externalJobId) {

        /** 외부 취소를 개시할 수 있는가 — job_id 가 없으면 보낼 대상이 없다. */
        public boolean hasExternalJob() {
            return externalJobId != null && !externalJobId.isBlank();
        }
    }

    /**
     * 클레임 결과.
     *
     * @param claimed   이번 호출이 취소를 선점했는가(false = 이미 종결 → 멱등 200)
     * @param augTypeCd 증강 종류
     * @param status    클레임 후 상태(선점 성공이면 {@code CANCELED}, 아니면 기존 종결 상태)
     * @param targets   외부 취소를 시도할 비종결 청크 목록
     */
    public record CancelClaim(boolean claimed, String augTypeCd, String status,
                              List<CancelTarget> targets) {

        public CancelClaim {
            targets = targets == null ? List.of() : List.copyOf(targets);
        }
    }

    /**
     * 취소를 선점한다 — 증강 행을 잠그고 {@code PENDING} 이면 {@code CANCELED} 로 전이한다.
     *
     * @throws CustomException 404 없는 id, 400 해상도 파생(RESL_*)
     */
    @Transactional("controlTransactionManager")
    public CancelClaim claim(Long dataAugSn) {
        LsDataAug aug = augRepository.findByDataAugSnForUpdate(dataAugSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "증강 결과를 찾을 수 없습니다."));
        // 해상도 파생은 외부 위탁이 없어 취소할 대상 자체가 없다(S11) — 진행상태 조회와 동일 판정.
        AugmentSnapshotLoader.requireExternalAugment(aug);

        if (!LsDataAug.STTS_PENDING.equals(aug.getAugProcSttsCd())) {
            // 멱등 — 이미 종결(취소·검수·실패)이다. 409 를 노출하지 않는다.
            log.info("[Augment][Cancel] already terminal — 멱등 응답 dataAugSn={} status={}",
                    dataAugSn, aug.getAugProcSttsCd());
            return new CancelClaim(false, aug.getAugTypeCd(), aug.getAugProcSttsCd(), List.of());
        }

        List<CancelTarget> targets = jobRepository.findByDataAugSnOrderByJobSeqAsc(dataAugSn).stream()
                .filter(job -> !job.isTerminal())
                .map(job -> new CancelTarget(job.getAugJobSn(),
                        job.getJobSeq() == null ? 0 : job.getJobSeq(), job.getExternalJobId()))
                .toList();

        aug.markCanceled();
        augRepository.save(aug);
        log.info("[Augment][Cancel] claimed dataAugSn={} targetJobs={}", dataAugSn, targets.size());
        return new CancelClaim(true, aug.getAugTypeCd(), LsDataAug.STTS_CANCELED, targets);
    }

    /**
     * 외부 취소가 성립한 청크를 로컬에서도 종결한다 (S12 — 두 축 불일치 최소화).
     *
     * <p>증강 행을 다시 잠그고 job 을 전이시킨다. 잠금을 다시 잡는 이유는 콜백 경로도 같은 행을
     * 잠그기 때문이다 — 그래야 "우리가 CANCELED 로 쓰는 사이 웹훅이 SUCCEEDED 로 덮어쓰는" 경합이
     * <b>이 트랜잭션 구간에서</b> 직렬화된다. 이미 종결된 job 은 <b>건드리지 않는다</b>(성공한 청크를
     * 취소로 덮으면 산출물이 있는데 없는 것처럼 보인다).
     *
     * <p><b>⚠ 두 축 불일치가 완전히 차단되지는 않는다</b>(클래스 주석 "실제 전이 순서" 참조).
     *
     * @param canceledAugJobSns 외부 취소가 성립한 청크 PK 목록
     * @return 실제로 {@code CANCELED} 로 전이된 청크 수
     */
    @Transactional("controlTransactionManager")
    public int markJobsCanceled(Long dataAugSn, List<Long> canceledAugJobSns) {
        if (canceledAugJobSns == null || canceledAugJobSns.isEmpty()) {
            return 0;
        }
        if (augRepository.findByDataAugSnForUpdate(dataAugSn).isEmpty()) {
            return 0;
        }
        List<LsDataAugJob> jobs = jobRepository.findByDataAugSnOrderByJobSeqAsc(dataAugSn);
        int transitioned = 0;
        for (LsDataAugJob job : jobs) {
            if (!canceledAugJobSns.contains(job.getAugJobSn()) || job.isTerminal()) {
                continue;
            }
            job.markCanceled(job.getExternalJobId(), CANCEL_REASON);
            transitioned++;
        }
        if (transitioned > 0) {
            jobRepository.saveAll(jobs);
            jobRepository.flush();
        }
        return transitioned;
    }
}
