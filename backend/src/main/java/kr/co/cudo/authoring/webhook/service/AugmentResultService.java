package kr.co.cudo.authoring.webhook.service;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.common.util.ExternalUrlValidator;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.runner.AsyncAugmentFrameRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * 외부 생성형 AI 증강 결과 인계 처리 서비스 — 연동정의서 정합.
 *
 * <p>생성형 AI 결과 웹훅({@code POST /v1/genai/callback}) 의 job 집계가 끝난 뒤 호출된다.
 * {@link LsDataAug} 의 PENDING 상태 행을 {@code ACCEPTED}/{@code REJECTED} 로 전이하고,
 * 성공 시 새 증강 영상(RAW_SN)을 생성한다.
 *
 * <h3>재전송 멱등 방어 (CRITICAL — 중복 영상 생성 차단)</h3>
 * <p>증강 성공 콜백은 <b>새 영상을 생성</b>하므로 webhook 재전송이 중복 영상을 만들면 안 된다.
 * 콜백 페이로드에는 요청 시점 발급 키가 없고 {@code otsd_job_id} 는 외부 시스템이 콜백 시점에
 * 부여하므로, 요청 시점 원장(ledger) 게이트로는 재전송을 막을 수 없다. 대신:
 * <ol>
 *   <li><b>1차 앵커(주 방어선)</b>: {@code data_aug_sn} 으로 대상 행을 조회해 <b>종결 상태
 *       (non-PENDING)</b>이면 재전송으로 간주하고 skip(신규 영상 미생성). 최초 콜백이
 *       PENDING→ACCEPTED/REJECTED 로 전이시키므로 순차 재전송(webhook 재시도)은 여기서 완전히
 *       차단된다.</li>
 *   <li><b>2차 앵커(동시/오배송)</b>: {@code otsd_job_id} 를 {@code LS_DATA_AUG.OTSD_JOB_ID}
 *       (UNIQUE {@code uk_aug_external_job_id})에 적재한다. 적재 <b>이전</b>에 소유자를 확인해
 *       (선점 검사) <b>다른 증강</b>이 이미 그 job_id 를 보유하면 {@link ErrorCode#CONFLICT}(409)로
 *       종결한다. 잔여 동시 위반은 {@link AugmentJobIdOwnerLookup}(REQUIRES_NEW)로만 판별한다.</li>
 * </ol>
 *
 * <h3>E-ISSUE-05 — 재수신(200 no-op) 과 진짜 충돌(409) 은 다른 사건이다</h3>
 * <p>외부/목업은 웹훅을 <b>최대 2회 재시도</b>하므로 <b>같은 증강의 중복 콜백은 정상 시나리오</b>다.
 * 구 구현은 UNIQUE 위반을 무조건 409 로 종결했는데, 그러면 정상 재수신이 오류로 회신될 여지가 있었다.
 * 지금은 둘을 이렇게 가른다:
 * <ul>
 *   <li><b>같은 증강의 재수신</b> → 1차 앵커(non-PENDING skip) + 선점 검사의 "소유자가 자기 자신"
 *       판정으로 흡수 → {@link AugmentApplyResult#DUPLICATE}(HTTP 200, {@code applied:false}).</li>
 *   <li><b>다른 증강이 같은 job_id 선점</b> → 409. <b>쓰기 이전</b>에 던지므로 트랜잭션을
 *       rollback-only 로 오염시키지 않고, 오류 응답도 정확하다.</li>
 * </ul>
 * <p><b>PostgreSQL 25P02 함정</b>: UNIQUE 위반은 트랜잭션 전체를 abort 시켜 <b>같은 트랜잭션의 후속
 * 쿼리를 모두 거부</b>한다. 따라서 위반 이후 "누가 선점했나" 를 여기서 재조회하면 안 된다(구 500 의
 * 원인). 선점 검사(쓰기 이전)로 대부분을 회피하고, 검사 통과 후에도 동시 커밋으로 위반이 나면
 * 소유자 판별은 {@link AugmentJobIdOwnerLookup}(REQUIRES_NEW, 새 커넥션)에서만 한다. 그 경우 이
 * 트랜잭션은 이미 rollback-only 라 200 으로 되돌릴 수 없으므로 409 로 종결하고, 외부가 재전송하면
 * 그때는 위 두 멱등 흡수 경로가 200 을 준다.
 *
 * <h3>Phase 11 — 프레임 재추출 비동기 전환 + 동기/비동기 경계 (반드시 준수)</h3>
 * <p>증강 영상(WINTER/NIGHT/RAIN)은 원본과 픽셀이 달라 부모 프레임을 복사하면 오손이다. 따라서
 * 프레임은 <b>증강 파일에서 새로 추출</b>하며, 블로킹 추출은 커밋 후 비동기
 * ({@link AsyncAugmentFrameRunner})로 미룬다. 다만 아래 <b>부모 안전 판정은 동기 트랜잭션에 그대로
 * 둔다(절대 async 로 이동 금지)</b>:
 * <ul>
 *   <li>부모 {@code findByRawSnForUpdate} 잠금 + 비식별 산출물 존재 판정(동시 콜백 직렬화)</li>
 *   <li>콜백 멱등 앵커(non-PENDING skip + UNIQUE otsd_job_id)</li>
 * </ul>
 * 이들은 <b>동기 시점의 부모 상태 판정</b>으로만 유효하다. async 로 옮기면 커밋~async 사이 비식별
 * 신고가 부모를 'F' 로 되돌려도 못 막아 Phase 5 의 PII 노출 창을 재개방한다. 동기 단계는 신규 RAW 를
 * PENDING·deIdntfYn='N' 으로만 커밋하고, 프레임/라벨/메타/procLog/COMPLETED(배치 마감) 는 추출 성공 후
 * async 커밋에서만 관측된다.
 *
 * <h3>Phase 8-B — 부모 판정은 상태 전이 <b>이전</b>에 끝낸다 (E-ISSUE-11)</h3>
 * <p>구 순서(전이 먼저 → 영상 생성 단계에서 게이트)는 게이트에 걸려도 증강 행이 이미 {@code ACCEPTED}
 * 로 종결돼 ①화면·집계상 정상 완료로 보이고 ②신규 영상은 0건이며 ③non-PENDING 멱등 앵커 때문에
 * 재콜백도 스킵돼 그 증강이 <b>영구 유실</b>됐다. 지금은 판정을 앞으로 당겨 부모를 쓸 수 없으면
 * {@code REJECTED} 로 실패 확정한다.
 *
 * <h3>★ 신고({@code 'F'})는 파생 생성을 막지 않는다 (2026-07-29 확정)</h3>
 * <p>"파생영상은 비식별 신고 체계 바깥" 정책과 대칭이다 — 신고 구간에 콜백이 도착해도 파생을 만든다.
 * 신고가 막아야 하는 것은 <b>외부 위탁</b>({@code AugmentRequestService} 요청 입구 ·
 * {@code AugmentJobSubmitService} 전송 진입점)이고 그 차단도 보류가 아니라 거부다. 여기서 보류하면
 * 재개 트리거가 없는 PENDING 고착만 남는다(자손 팬아웃 복구 배선은 철회됐다).
 *
 * <h3>Phase 8-B — 실패 인계는 dead-letter 로 못박는다 (E-ISSUE-06)</h3>
 * <p>본 서비스는 증강 실패의 <b>단일 깔때기</b>다(웹훅 롤업 · 만료 스윕 롤업 · 위탁 0건 롤업).
 * 실패 인계에서 {@code RTRY_NMTM} 을 누적하고 {@code DEAD_LETTER_AT} 을 찍어야 집계가 실패를
 * 실패(FAILED)로 보인다 — 찍지 않으면 {@code REJECTED} 만 남아 "검수 완료(COMPLETED)" 로 오분류된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AugmentResultService {

    private static final Pattern LOG_UNSAFE = Pattern.compile("[\\r\\n\\t]");

    private final LsDataAugRepository augRepository;
    private final VideoRepository videoRepository;
    private final LsDataSrcRepository srcRepository;
    /**
     * Phase 11 — 증강 신규 영상의 프레임 재추출(증강 파일 기반) + 라벨/메타 복사 + 비식별 완료 불변식을
     * 커밋 후 비동기로 수행한다. 동기 handle 트랜잭션은 신규 RAW 를 PENDING·deIdntfYn='N' 으로만 남긴다.
     */
    private final AsyncAugmentFrameRunner asyncAugmentFrameRunner;
    /**
     * 적재 시점 경로 검증용 — 콜백이 준 {@code raw_file_path_nm} 이 고정 allowlist(마운트 루트) 하위인지
     * 확인한다({@link #validateFilePath}).
     */
    private final VideoArtifactRootResolver artifactRootResolver;

    /**
     * UNIQUE 위반 <b>이후</b>의 소유자 판별 전용(REQUIRES_NEW). abort 된 트랜잭션에서는 어떤 쿼리도
     * 나갈 수 없으므로(PG 25P02) 이 경로만 사용한다 — {@link #augRepository} 로 재조회 금지.
     */
    private final AugmentJobIdOwnerLookup jobIdOwnerLookup;

    /**
     * 복사 원본 경로 조달의 <b>단일 진실원</b> — 관제는 비식별본, 포털은 본인 원본.
     * 부모 게이트와 Phase A 가 <b>같은 판정기</b>를 쓴다(조달 규칙을 흩지 않는다).
     */
    private final kr.co.cudo.authoring.video.service.DerivativeSourceVideoResolver
            derivativeSourceVideoResolver;

    /**
     * 파생영상(비식별 사본) 출력 base — <b>비식별 저장소</b>. 파생영상의 유일한 비디오 산출물은 부모
     * 비식별 영상의 복사본이므로 그 경로도 비식별 저장소 서브트리({@code videos/augment/…})에 있다
     * (해상도 파생 {@code ResolutionReservationPersister} 와 동일 규약).
     */
    @Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String storageDeidentifiedPath;

    /**
     * <b>커밋 문맥과 무관하게</b> 항상 새 물리 트랜잭션에서 인계하는 진입점 (적대검증 2차 MEDIUM-1).
     *
     * <h3>왜 {@code @Async} 만으로는 부족한가</h3>
     * <p>재개/롤업 리스너({@code AugmentRequestBridge})는 {@code @Async("batchAsyncExecutor")} 로
     * "다른 스레드" 를 기대하지만, 그 풀의 거부 정책은 {@code CallerRunsPolicy} 다. <b>풀이 포화되면
     * 호출 스레드가 직접 실행</b>하고, 그 호출 스레드는 곧 {@code TransactionSynchronization.afterCommit}
     * 문맥(원 트랜잭션 리소스가 아직 바인딩된 상태)이다. 그러면 {@link #handle} 의 {@code REQUIRED} 가
     * <b>이미 커밋된</b> 트랜잭션에 참여해 ①뒤따르는 커밋이 없어 변경이 사라지고 ②첫 동작인
     * {@code findByDataAugSnForUpdate}(PESSIMISTIC_WRITE)가 {@code TransactionRequiredException} 으로
     * 튄다 — 리스너의 건별 catch 가 그것을 삼키면 보류분이 <b>조용히 PENDING 에 영구 고착</b>된다.
     * 즉 스레드 배정에 기대는 방어는 부하에서 무너진다.
     *
     * <p>그래서 <b>진입점 자체</b>를 {@code REQUIRES_NEW} 로 둔다. 어느 스레드에서 실행되든 기존
     * 트랜잭션을 suspend 하고 새 물리 트랜잭션을 열어 커밋하므로, 스레드 가정이 전혀 필요 없다
     * (Spring 이 {@code afterCommit} 규약으로 명시한 "Use PROPAGATION_REQUIRES_NEW for any transactional
     * operation called from here" 그대로다).
     *
     * <p><b>{@link #handle} 자체의 전파는 바꾸지 않는다</b> — 웹훅 수신({@code GenAiCallbackService})이
     * 자기 트랜잭션에 조인시켜 멱등 앵커·409 종결 의미론을 세우고 있어(클래스 javadoc "E-ISSUE-05")
     * 전파를 바꾸면 그 계약이 깨진다. 아래 자기호출({@code this.handle})은 <b>의도된</b> 구조다: 프록시
     * 진입점은 이 메서드이므로 {@code REQUIRES_NEW} 는 정상 적용되고, 내부 호출은 그 새 트랜잭션 안에서
     * 실행된다(웹훅 경로는 {@link #handle} 을 직접 불러 종전 의미론을 그대로 쓴다).
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public AugmentApplyResult handleInNewTransaction(AugmentOutcome outcome) {
        return handle(outcome);
    }

    /**
     * 증강 1건의 최종 인계.
     *
     * <p><b>호출 규약</b>: 커밋 후 콜백({@code AFTER_COMMIT}) 문맥에서 부를 수 있는 호출자는 이 메서드가
     * 아니라 {@link #handleInNewTransaction} 을 써야 한다(위 javadoc 참조).
     *
     * @return {@link AugmentApplyResult} — 적용/멱등 스킵/정책 보류. 보류는 상태를 전이시키지 않는다.
     */
    @Transactional("controlTransactionManager")
    public AugmentApplyResult handle(AugmentOutcome outcome) {
        // 1) SSRF/경로순회 — 결과 영상 경로(있을 때만)
        validateFilePath(outcome.rawFilePathNm());

        // 2) 대상 증강 행 조회 (data_aug_sn = 요청 시 발급된 LS_DATA_AUG PK).
        //    MED #2 — 같은 dataAugSn 동시 콜백을 직렬화하기 위해 PESSIMISTIC_WRITE(FOR UPDATE)로 잠금 조회한다.
        //    이렇게 해야 아래 1차 앵커(non-PENDING skip)의 read-then-act 가 원자적이 되어, 서로 다른
        //    otsd_job_id 를 가진 동시 콜백이 둘 다 PENDING 을 통과해 이중 영상을 만드는 창이 닫힌다.
        //    [Phase 11] 이 잠금은 동기 트랜잭션에 유지한다(절대 async 로 이동 금지 — 멱등 판정 원자성).
        //    [Phase 7-A2] 분할 job 롤업의 "1회만 확정" 도 이 잠금 + 아래 non-PENDING 앵커로 성립한다.
        LsDataAug aug = augRepository.findByDataAugSnForUpdate(outcome.dataAugSn())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "증강 행을 찾을 수 없습니다: dataAugSn=" + outcome.dataAugSn()));

        // 3) 재전송 멱등 방어(1차 앵커) — 이미 종결(non-PENDING)된 행이면 재전송이다 → skip.
        if (!LsDataAug.STTS_PENDING.equals(aug.getAugProcSttsCd())) {
            if (outcome.success() && !LsDataAug.STTS_ACCEPTED.equals(aug.getAugProcSttsCd())) {
                // ★ 조용한 폐기 가시화 (2026-07-29) — <b>성공</b> 결과가 <b>ACCEPTED 아닌 종결 행</b>에
                // 도착하는 것은 정상 재전송이 아니다(성공의 재전송이라면 상태는 ACCEPTED 여야 한다).
                // 성립 경로는 둘이다: ①REJECTED — in-flight PENDING 이 중간에 강등(V143 선행 정리,
                // 위탁 0건 실패 롤업, REVIEWER 반려) ②CANCELED — 사용자 취소 후 벤더가 마저 성공.
                // 어느 쪽이든 이 결과물은 어디에도 반영되지 않고 사라진다(200/applied=false, 오류 없음).
                // 운영에서 "요청했는데 파생영상이 없다"의 유일한 단서이므로 WARN 으로 드러낸다.
                //   ⚠ 구 조건은 REJECTED 만 봐서 <취소 경로가 INFO 로 묻혔다>(DEV_FIX LOW) — 취소는
                //     "폐기가 정상" 인 경로지만, 폐기된 산출물이 실재한다는 사실 자체는 관측 가능해야 한다.
                log.warn("[Webhook][Augment] success result discarded — aug already terminal({}) "
                                + "dataAugSn={} otsdJobId={} (강등/반려/취소 — 필요 시 재요청)",
                        safe(aug.getAugProcSttsCd()),
                        outcome.dataAugSn(), safe(outcome.externalJobId()));
            } else {
                log.info("[Webhook][Augment] duplicate result skipped dataAugSn={} otsdJobId={} state={}",
                        outcome.dataAugSn(), safe(outcome.externalJobId()), safe(aug.getAugProcSttsCd()));
            }
            return AugmentApplyResult.DUPLICATE;
        }

        // 3-0) [E-ISSUE-05] otsd_job_id 선점 검사 — <쓰기 이전>에 소유자를 확인해 재수신과 충돌을 가른다.
        //      여기서 던지면 트랜잭션이 아직 깨끗하므로 409 응답이 정확하고, UNIQUE 위반으로 트랜잭션이
        //      abort(PG 25P02) 되는 경로 자체를 대부분 회피한다.
        requireJobIdNotOwnedByOtherAug(aug, outcome.externalJobId());

        // 3-1) [E-ISSUE-11] 부모 안전 판정을 <상태 전이 이전에> 끝낸다.
        //      구 구현은 먼저 ACCEPTED 로 전이한 뒤 영상 생성 단계에서 게이트에 걸리면 조용히 return 했다.
        //      그러면 aug 는 ACCEPTED 로 종결되고 신규 영상은 0건인데, non-PENDING 앵커 때문에 재콜백도
        //      멱등 스킵돼 그 증강이 영구 유실된다. 판정을 앞으로 당겨 실패는 실패로 확정시킨다.
        ParentGate gate = outcome.success() ? evaluateParentGate(aug) : ParentGate.notApplicable();
        if (gate.decision() == ParentGate.Decision.FAIL) {
            // 재개 트리거가 존재하지 않는 데이터 이상(부모/프레임 부재·비식별 미완료)이다. 보류로 두면
            // 아무도 깨우지 못하는 PENDING 고착이 되므로, 실패로 확정해 집계(FAILED)에 드러낸다.
            log.warn("[Webhook][Augment] result failed — parent unavailable dataAugSn={} reason={}",
                    outcome.dataAugSn(), gate.reason());
        }

        // 4) 상태 전이 + otsd_job_id 를 externalJobId(재전송 멱등 앵커)에 적재.
        //    uk_aug_external_job_id UNIQUE 위반(동시 콜백/다른 행 오배송)이면 재조회 후 멱등 흡수한다.
        //    [Phase 11] 멱등 앵커도 동기 트랜잭션에 유지(절대 async 로 이동 금지).
        //    (구 계약의 augType 대조는 제거됐다 — 새 계약은 request_id → job → dataAugSn 으로 대상을
        //     역산하므로 외부가 aug_type 을 잘못 실어 다른 행에 인계할 경로 자체가 없다.)
        boolean success = outcome.success() && gate.decision() != ParentGate.Decision.FAIL;
        String newStatus = success ? LsDataAug.STTS_ACCEPTED : LsDataAug.STTS_REJECTED;
        try {
            applyAugStateAndExternalJobId(aug, outcome, newStatus);
            if (!success) {
                markProcessingFailure(aug);
            }
            augRepository.save(aug);
            augRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // 선점 검사를 통과했는데도 위반이 났다 = 그 사이 다른 트랜잭션이 같은 otsd_job_id 를 커밋했다.
            //
            // 여기서 흡수(200)할 수는 없다. 본 메서드는 호출자(GenAiCallbackService.handle) 트랜잭션에
            // <조인>돼 있어 UNIQUE 위반 시점에 트랜잭션이 이미 rollback-only 로 마킹되므로, 정상 반환하면
            // 컨트롤러가 200 을 만들고 커밋 단계에서 UnexpectedRollbackException(500) 이 터진다(응답과
            // 실제 결과 불일치). 또한 PostgreSQL 은 제약 위반 이후 같은 트랜잭션의 후속 쿼리를 거부하므로
            // (25P02) <이 자리에서 augRepository 로 승자를 재조회하면 그 조회마저 실패한다>.
            // → 소유자 판별은 REQUIRES_NEW 독립 트랜잭션에서만 하고(로그 근거 확보), 409 로 종결한다.
            //   외부가 재전송하면 그때는 1차 앵커(non-PENDING skip)/선점 검사가 200 으로 흡수한다.
            throw conflictAfterUniqueViolation(outcome);
        }

        // 5) 성공 시 새 영상(RAW_SN)만 동기 생성. 프레임/라벨/메타/procLog/COMPLETED(배치 마감) 는 커밋 후 async.
        if (gate.decision() == ParentGate.Decision.PASS) {
            createAugmentedVideo(aug, gate.parentRaw());
        }

        log.info("[Webhook][Augment] result applied dataAugSn={} success={} otsdJobId={}",
                outcome.dataAugSn(), success, safe(outcome.externalJobId()));
        return AugmentApplyResult.APPLIED;
    }

    /**
     * {@code otsd_job_id} 선점 검사 (E-ISSUE-05) — <b>쓰기 이전</b>에 수행한다.
     *
     * <p>소유자가 <b>대상 증강 자신</b>이면 같은 값을 다시 쓰는 것이라 UNIQUE 위반이 발생하지 않는다
     * (재수신 정상 경로 — 1차 앵커가 흡수한다). 소유자가 <b>다른 증강</b>이면 오배송/충돌이므로
     * 409 로 종결한다. 이 시점의 트랜잭션은 아직 깨끗해서 응답과 실제 결과가 어긋나지 않는다.
     *
     * <p>거부 메시지에 소유 증강 식별자·내부 경로를 담지 않는다(CWE-209).
     */
    private void requireJobIdNotOwnedByOtherAug(LsDataAug target, String externalJobId) {
        if (externalJobId == null || externalJobId.isBlank()) {
            return;
        }
        augRepository.findByExternalJobId(externalJobId)
                .map(LsDataAug::getDataAugSn)
                .filter(ownerSn -> !ownerSn.equals(target.getDataAugSn()))
                .ifPresent(ownerSn -> {
                    log.warn("[Webhook][Augment] otsd_job_id 선점 충돌(다른 증강 보유) — 409 종결 "
                                    + "dataAugSn={} ownerDataAugSn={} otsdJobId={}",
                            target.getDataAugSn(), ownerSn, safe(externalJobId));
                    throw new CustomException(ErrorCode.CONFLICT,
                            "이미 다른 증강 결과에 인계된 작업 ID 입니다.");
                });
    }

    /**
     * UNIQUE 위반 이후의 종결 — 소유자 판별을 <b>독립 트랜잭션</b>에서만 수행한다(PG 25P02).
     *
     * <p>이 트랜잭션은 이미 abort/rollback-only 라 어떤 결과든 200 으로 되돌릴 수 없다. 판별 결과는
     * <b>운영 관측용</b>이다 — 소유자가 자기 자신으로 나오면(정상적으로는 행 잠금 때문에 발생하지
     * 않는다) 멱등 앵커 배선이 깨졌다는 신호이므로 로그로 드러낸다.
     */
    private CustomException conflictAfterUniqueViolation(AugmentOutcome outcome) {
        Long ownerSn = jobIdOwnerLookup.findOwnerDataAugSn(outcome.externalJobId()).orElse(null);
        if (ownerSn != null && ownerSn.equals(outcome.dataAugSn())) {
            log.error("[Webhook][Augment] otsd_job_id UNIQUE 위반인데 소유자가 자기 자신이다 "
                            + "(멱등 앵커 배선 점검 필요) dataAugSn={} otsdJobId={}",
                    outcome.dataAugSn(), safe(outcome.externalJobId()));
        } else {
            log.warn("[Webhook][Augment] otsd_job_id 동시 선점 충돌 — 409 종결 "
                            + "dataAugSn={} ownerDataAugSn={} otsdJobId={}",
                    outcome.dataAugSn(), ownerSn, safe(outcome.externalJobId()));
        }
        return new CustomException(ErrorCode.CONFLICT, "이미 처리된 증강 결과입니다.");
    }

    /**
     * 처리 실패 확정 — 재시도 카운트를 누적하고 dead-letter 로 못박는다 (E-ISSUE-06 배선).
     *
     * <p>이 두 컬럼({@code RTRY_NMTM}/{@code DEAD_LETTER_AT})은 정의만 있고 프로덕션 호출자가
     * 0건이었다. 그래서 실패한 증강이 {@code REJECTED} 로만 끝나 집계에서 <b>COMPLETED</b>(검수 완료)로
     * 보였다. 여기가 증강 실패의 <b>단일 깔때기</b>다 — 웹훅 롤업·만료 스윕 롤업·위탁 0건 롤업이
     * 모두 이 경로로 들어온다. 별도 스케줄러를 만들지 않는 이유이기도 하다.
     *
     * <p><b>임계는 1이다</b>: 증강 채널에는 실패 이후 자동 재시도 구동기가 없다(재개는 정책 보류
     * 해제 트리거뿐이며, 그 경로는 실패가 아니다). 실패가 확정되는 순간이 곧 영구 실패이므로
     * 관측 즉시 dead-letter 로 못박는다. 임계를 설정으로 열면 그 사이 구간의 증강이 dead-letter
     * 없이 REJECTED 로만 남아 다시 COMPLETED 로 오분류되므로(=본 결함 재발) 열지 않는다.
     */
    private static void markProcessingFailure(LsDataAug aug) {
        aug.incrementRetryCount();
        aug.markDeadLetter();
    }

    /**
     * 증강본 생성 전 <b>부모 안전 판정</b> 결과 (E-ISSUE-11).
     *
     * @param decision   판정
     * @param parentRaw  PASS 시 잠금 조회된 부모 RAW (그 외 null)
     * @param reason     FAIL 사유(내부 로그용 — 경로/식별정보 없음)
     */
    private record ParentGate(Decision decision, LsDataRaw parentRaw, String reason) {

        enum Decision {
            /** 실패 인계라 부모 판정 자체가 불필요. */
            NOT_APPLICABLE,
            /** 부모 안전 — 증강본을 생성한다. */
            PASS,
            /** 데이터 이상 — 재개 트리거가 없으므로 실패로 확정해 집계에 드러낸다. */
            FAIL
        }

        static ParentGate notApplicable() {
            return new ParentGate(Decision.NOT_APPLICABLE, null, null);
        }

        static ParentGate pass(LsDataRaw parentRaw) {
            return new ParentGate(Decision.PASS, parentRaw, null);
        }

        static ParentGate fail(String reason) {
            return new ParentGate(Decision.FAIL, null, reason);
        }

        Long parentRawSn() {
            return parentRaw != null ? parentRaw.getRawSn() : null;
        }
    }

    /**
     * 부모 가용성 판정 — <b>동기 트랜잭션에서만</b> 수행한다(절대 async 로 이동 금지).
     *
     * <p>부모 RAW 를 {@code PESSIMISTIC_WRITE}(FOR UPDATE)로 잠근 뒤 파생 생성의 물리적 전제
     * (부모 존재 · 비식별 산출물 존재 · 프레임 존재)를 재검증한다. 잠금은 이 트랜잭션 커밋까지
     * 유지되므로 판정~신규 RAW 커밋 구간이 동시 콜백과 직렬화된다.
     *
     * <h3>★ 비식별 누락 신고({@code 'F'})는 여기서 막지 않는다 (2026-07-29 확정)</h3>
     * <p>"파생영상은 비식별 신고 체계 바깥" 정책과 대칭이다 — 파생 생성은 원본 신고와 무관하며,
     * 신고 구간에 콜백이 도착해도 파생을 만든다. 신고가 실제로 막아야 하는 것은 <b>외부 위탁
     * (요청·전송)</b> 이고 그 차단은 {@code AugmentRequestService}(요청 입구)와
     * {@code AugmentJobSubmitService}(전송 진입점)가 담당한다. 여기서 보류하면 재개 트리거가 없는
     * PENDING 고착만 남는다(자손 팬아웃 복구 배선은 철회됐다).
     *
     * <p>따라서 차단 대상은 <b>복사할 영상 파일이 없는 경우</b> 하나다 — 파생 비디오는 언제나 부모
     * 영상 파일의 복사본이라(증강 AI 는 이미지-to-이미지라 영상을 재생성하지 않는다) 재료가 없으면
     * 만들 수 없다. 이 역시 스스로 재개되지 않으므로 <b>보류가 아니라 실패로 확정</b>한다.
     *
     * <h3>★ 비식별은 애초에 전제조건이 아니었다 (2026-09-02 사용자 확정, 구속)</h3>
     * <p>흡수 이전 이 자리는 비식별 플래그를 직접 봤고 그래서 「비식별이 완료된 것만 증강한다」로
     * 읽혔다. <b>그것은 조건이 아니라 결과였다</b> — 관제 채널의 진짜 전제조건은 <b>검수 완료</b>이고
     * 비식별은 그 안에 이미 포함된 결과일 뿐이다. 결과로 따라오는 사실을 별도 조건으로 적었더니,
     * 그 사실이 성립하지 않는 채널(포털 — 전제조건이 <b>본인 자산</b>이고 검수가 없다)이 생기자 막혔다.
     *
     * <p>그래서 그 판정을 <b>걷어냈다</b>. 남은 것은 복사 원본 경로 조달뿐이고 경로가 없으면 실패한다.
     * 판정은 {@link kr.co.cudo.authoring.video.service.DerivativeSourceVideoResolver} <b>한 곳</b>이
     * 하며 되살리면 안 되는 이유(관제에서는 한 번도 걸릴 수 없고 포털만 막는다)는 그 클래스 주석에 있다.
     */
    private ParentGate evaluateParentGate(LsDataAug aug) {
        LsDataSrc originSrc = srcRepository.findById(aug.getSrcSn()).orElse(null);
        if (originSrc == null) {
            return ParentGate.fail("origin frame not found");
        }
        LsDataRaw parentRaw = videoRepository.findByRawSnForUpdate(originSrc.getRawSn()).orElse(null);
        if (parentRaw == null) {
            return ParentGate.fail("parent video not found");
        }
        // 「복사할 영상 파일 경로를 구한다 — 못 구하면 실패」. 조건이 아니라 <조달 실패>다.
        //   조달처(관제=비식별본 / 포털=본인 원본)는 판정기가 가르고 여기서 출처를 분기하지 않는다.
        //   ★ 비식별 플래그를 여기서 다시 보지 마라 — 관제에서는 한 번도 걸릴 수 없고(검수 완료만
        //     받고, 검수까지 갔으면 비식별은 끝나 있으며, 되돌아가는 경로가 0건이다) 포털만 막는다.
        if (derivativeSourceVideoResolver.resolveQuietly(parentRaw).isEmpty()) {
            return ParentGate.fail("parent has no copyable source video");
        }
        // 프레임 존재 가드 — 부모에 프레임이 없으면 라벨링 대상이 없는 빈 증강본이 된다.
        if (srcRepository.findByRawSnOrderByFrameNoAsc(parentRaw.getRawSn()).isEmpty()) {
            return ParentGate.fail("parent has no frames");
        }
        return ParentGate.pass(parentRaw);
    }

    /**
     * Phase 11 — 증강 성공 시 <b>새 영상(RAW_SN)만</b> 동기 생성한다. 부모 안전 판정(잠금·게이트)은
     * 동기 유지하되, 프레임 재추출/라벨·메타 복사/비식별 완료 불변식은 커밋 후
     * {@link AsyncAugmentFrameRunner} 로 미룬다.
     *
     * <p>신규 RAW 는 {@code createFromAugment} 기본값(PENDING·deIdntfYn='N') 그대로 커밋된다 —
     * 추출 성공 전까지는 스트리밍/마킹 진입이 불가하다(프레임 0건 차단). 추출 성공 async 커밋에서
     * COMPLETED(배치 마감)로 전이돼 작업보드에 노출된다.
     */
    private void createAugmentedVideo(LsDataAug aug, LsDataRaw parentRaw) {
        // [파생영상은 원본이 없다] RAW_FILE_PATH_NM 에는 <파생 자신의 비식별 사본 경로>를 적재한다.
        //
        // 구 구현은 콜백이 준 경로(통상 공백)를 쓰고 공백이면 <부모의 RAW_FILE_PATH_NM(비식별 이전 원본
        // NAS 경로)>으로 폴백했다. 그러면 ①파생 행이 부모의 PII 원본 경로를 보유하고(CWE-359) ②그 값이
        // 승인 동결(LS_DATASET_VIDEO_META)을 거쳐 관제 뷰로 노출되며 ③DB 가 가리키는 파일과 실제 산출
        // 파일(Phase B 가 복사한 videoDst)이 서로 다른 파일이 된다.
        //
        // 증강 AI 는 이미지-to-이미지라 영상을 재생성하지 않으므로 파생의 비디오는 항상 <부모 비식별
        // 영상의 복사본>이고 그 위치는 {@link StorageSubtreePolicy#augmentVideoFile} 규약으로 결정된다
        // (Phase A {@code AugmentExtractSnapshot} 의 videoDst 와 <동일 계산식> → 값이 일치한다).
        // 외부가 준 경로는 신뢰 대상이 아니므로 쓰기 base 로 승격하지 않는다(위 validateFilePath 는
        // 손상/allowlist 밖 페이로드를 입구에서 거르는 방어로 유지).
        //
        // 경로 키에 파생 RAW_SN 이 들어가야 하는데 RAW_SN 은 INSERT 이후에만 알 수 있으므로, 해상도 파생
        // (ResolutionReservationPersister A-6)과 동일하게 ①잠정 경로로 INSERT(NOT NULL 충족) → ②확정
        // RAW_SN 으로 최종 경로 배정 순으로 처리한다. 같은 트랜잭션이라 잠정값은 외부에 커밋·관측되지
        // 않으며, LS_DATA_RAW 는 @DynamicUpdate 라 UPDATE 는 RAW_FILE_PATH_NM 한 컬럼만 건드린다.
        Path deidBase = deidBase();
        // 식별자(VMS_CLIP_ID) 유일화는 <시각이 아니라> 증강 행 PK 로 한다 — 같은 (영상 × 종류) 재요청이
        // 허용된 뒤로는 두 콜백이 같은 밀리초에 도달하면 UK_LS_DATA_RAW_VMS_CLIP 위반으로 이 트랜잭션이
        // 통째로 롤백돼 이미 생성된 외부 결과물이 유실된다(LsDataRaw.createFromAugment 주석 참조).
        LsDataRaw newRaw = videoRepository.save(LsDataRaw.createFromAugment(
                parentRaw, provisionalVideoPath(deidBase, parentRaw.getRawSn(), aug.getAugTypeCd()),
                aug.getAugTypeCd(), aug.getDataAugSn()));
        newRaw.assignDerivativeVideoPath(resolveSafeDeidFile(deidBase,
                StorageSubtreePolicy.augmentVideoFile(
                        parentRaw.getRawSn(), newRaw.getRawSn(), aug.getAugTypeCd())).toString());

        // [V155] 증강 행 ↔ 파생 영상 매핑을 <같은 트랜잭션에서> 확정한다.
        //
        // ⚠ 이 대입을 AFTER_COMMIT/@Async 로 미루면 안 된다. 파생영상 등재 게이트는 "NEW_RAW_SN 이
        //   비어 있는 파생 = V155 이전 생성분" 으로 보고 <그랜드퍼더링 통과>시키므로, 커밋~비동기 사이
        //   창에서 미검수 파생이 작업목록·배정에 노출된다. aug 는 handle() 상단에서 FOR UPDATE 로 잠근
        //   바로 그 인스턴스라 dirty checking 으로 반영된다(재조회 금지 — 다른 인스턴스에 쓰면 이
        //   인스턴스의 flush 에 덮인다).
        aug.assignDerivativeRawSn(newRaw.getRawSn());

        // 커밋 후 비동기 프레임 재추출 + 라벨/메타 복사 + 비식별 완료 불변식 확정 트리거.
        triggerAsyncFrameExtractionAfterCommit(newRaw.getRawSn(), aug.getDataAugSn());

        log.info("[Webhook][Augment] new video created (pending, async extraction) rawSn={} orgnlRawSn={} augType={} dataStts={}",
                newRaw.getRawSn(), parentRaw.getRawSn(), safe(aug.getAugTypeCd()), newRaw.getDataSttsCd());
    }

    /**
     * 증강 프레임 재추출을 <b>커밋 이후</b>에 트리거한다. 러너가 REQUIRES_NEW 독립 트랜잭션에서 새
     * RAW_SN 을 재조회하므로, 외부 트랜잭션 커밋 전에 호출하면 새 영상이 아직 보이지 않는 레이스가
     * 생긴다. 트랜잭션 동기화가 활성이면 AFTER_COMMIT 으로 미룬다. 동기화 미활성(단위 테스트 등)이면
     * 직접 호출로 폴백한다.
     *
     * <p><b>기술메타(ffprobe) 러너는 여기서 기동하지 않는다</b> — 파생의 기술메타는 <b>파생의 비식별
     * 사본</b>을 측정한 값이어야 하는데 그 사본은 프레임 러너의 Phase B 가 만든다. 두 러너를 같은
     * AFTER_COMMIT 에서 나란히 기동하면 순서 보장이 없어 사본 생성 전에 probe 가 돌 수 있다(레이스).
     * 따라서 메타 추출은 {@link AsyncAugmentFrameRunner} 가 <b>확정(Phase C) 성공 이후</b> 트리거한다.
     */
    private void triggerAsyncFrameExtractionAfterCommit(Long newRawSn, Long dataAugSn) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    asyncAugmentFrameRunner.runAsync(newRawSn, dataAugSn);
                }
            });
        } else {
            asyncAugmentFrameRunner.runAsync(newRawSn, dataAugSn);
        }
    }

    /** 비식별 저장소 base(정규화 절대경로) — 파생 산출물 출력 base. */
    private Path deidBase() {
        return Paths.get(storageDeidentifiedPath).toAbsolutePath().normalize();
    }

    /**
     * INSERT 시점 잠정 경로 — {@code RAW_FILE_PATH_NM} 이 NOT NULL 이라 필요한 자리표시자다. 최종 경로는
     * 확정 RAW_SN 이 붙은 {@link StorageSubtreePolicy#augmentVideoFile} 로 같은 트랜잭션 안에서 즉시
     * 교체된다(잠정값이 커밋되는 경로는 존재하지 않는다 — 해상도 파생과 동일).
     */
    private static String provisionalVideoPath(Path base, Long parentRawSn, String augTypeCd) {
        return resolveSafeDeidFile(base, StorageSubtreePolicy.SEG_VIDEOS + "/"
                + StorageSubtreePolicy.SEG_AUGMENT + "/" + parentRawSn
                + "/.pending/" + augTypeCd + ".mp4").toString();
    }

    /**
     * 파생 비디오 경로 해석 — base 하위 + <b>비식별 전용 서브트리</b> 검증(CWE-22 / CWE-359).
     * 두 저장소 base 가 같은 경로인 운영 형상에서도 파생 산출물이 원본 서브트리로 새지 않게 한다.
     */
    private static Path resolveSafeDeidFile(Path base, String relative) {
        Path resolved = base.resolve(relative).normalize();
        if (!StorageSubtreePolicy.isDeidentifiedArtifact(base, resolved)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "출력 경로가 허용된 비식별 저장 경로를 벗어납니다.");
        }
        return resolved;
    }

    /**
     * PENDING 상태 전이 + otsd_job_id 를 externalJobId(재전송 멱등 앵커)에 적재.
     * externalJobId 는 요청 시점 placeholder 를 콜백 시점의 실제 otsd_job_id 로 갱신한다(무조건 덮어쓰기)
     * → 종결 행이 항상 otsd_job_id 를 보유하여 UNIQUE(uk_aug_external_job_id) 재전송 방어가 성립한다.
     */
    private static void applyAugStateAndExternalJobId(LsDataAug target, AugmentOutcome outcome,
                                                      String newStatus) {
        if (LsDataAug.STTS_PENDING.equals(target.getAugProcSttsCd())) {
            target.applyGenerationResult(newStatus);
        }
        target.assignExternalJobId(outcome.externalJobId());
    }

    /**
     * 콜백이 싣는 {@code raw_file_path_nm} 검증.
     *
     * <ul>
     *   <li><b>URL 형태</b> — SSRF 차단({@link ExternalUrlValidator}).</li>
     *   <li><b>로컬 경로</b> — 고정 allowlist(마운트 루트) 하위인지 <b>적재 시점</b>에 확인한다(CWE-20/22).
     *       파생영상의 {@code RAW_FILE_PATH_NM} 은 더 이상 이 값에서 오지 않지만(파생 자신의 비식별
     *       사본 경로를 우리가 계산해 넣는다), 손상되거나 allowlist 밖을 가리키는 페이로드는 입구에서
     *       거부해 이후 어떤 경로로도 승격될 수 없게 유지한다(fail-closed 다층 방어).</li>
     * </ul>
     * 거부 메시지에 경로 원문/NAS 구조를 담지 않는다(CWE-209).
     */
    private void validateFilePath(String filePath) {
        if (filePath == null || filePath.isBlank()) return;
        if (filePath.contains("://")) {
            try {
                ExternalUrlValidator.validate(filePath, false, "rawFilePathNm");
            } catch (IllegalArgumentException e) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "rawFilePathNm SSRF 차단: " + e.getMessage());
            }
            return;
        }
        try {
            artifactRootResolver.verifyIngestablePath(filePath);
        } catch (CustomException e) {
            log.warn("[Webhook][Augment] rawFilePathNm rejected (outside allowed mount roots) code={}",
                    e.getErrorCode());
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawFilePathNm 이 허용된 저장 경로가 아닙니다.");
        }
    }

    private static String safe(String s) {
        if (s == null) return "null";
        return LOG_UNSAFE.matcher(s).replaceAll("_");
    }
}
