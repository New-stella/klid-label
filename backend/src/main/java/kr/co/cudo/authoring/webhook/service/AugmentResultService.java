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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
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
 *       (UNIQUE {@code uk_aug_external_job_id})에 적재하고, 저장 시 UNIQUE 위반이면
 *       <b>{@link ErrorCode#CONFLICT}(409) 로 종결</b>한다. 위반은 호출자 트랜잭션을 rollback-only 로
 *       만들기 때문에 이 자리에서 "흡수(200)" 하면 커밋 단계에서 500 이 되고, PostgreSQL 은 위반 이후
 *       같은 트랜잭션의 후속 조회도 거부한다. 외부 재전송은 1차 앵커가 멱등 흡수한다.</li>
 * </ol>
 *
 * <h3>Phase 11 — 프레임 재추출 비동기 전환 + 동기/비동기 경계 (반드시 준수)</h3>
 * <p>증강 영상(WINTER/NIGHT/RAIN)은 원본과 픽셀이 달라 부모 프레임을 복사하면 오손이다. 따라서
 * 프레임은 <b>증강 파일에서 새로 추출</b>하며, 블로킹 추출은 커밋 후 비동기
 * ({@link AsyncAugmentFrameRunner})로 미룬다. 다만 아래 <b>부모 안전 판정은 동기 트랜잭션에 그대로
 * 둔다(절대 async 로 이동 금지)</b>:
 * <ul>
 *   <li>부모 {@code findByRawSnForUpdate} 잠금 + {@code deIdntfYn=='Y'} 게이트 (CWE-359 PII TOCTOU)</li>
 *   <li>콜백 멱등 앵커(non-PENDING skip + UNIQUE otsd_job_id)</li>
 * </ul>
 * 이들은 <b>동기 시점의 부모 상태 판정</b>으로만 유효하다. async 로 옮기면 커밋~async 사이 비식별
 * 신고가 부모를 'F' 로 되돌려도 못 막아 Phase 5 의 PII 노출 창을 재개방한다. 동기 단계는 신규 RAW 를
 * PENDING·deIdntfYn='N' 으로만 커밋하고, 프레임/라벨/메타/procLog/COMPLETED(배치 마감) 는 추출 성공 후
 * async 커밋에서만 관측된다.
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
     * 파생영상(비식별 사본) 출력 base — <b>비식별 저장소</b>. 파생영상의 유일한 비디오 산출물은 부모
     * 비식별 영상의 복사본이므로 그 경로도 비식별 저장소 서브트리({@code videos/augment/…})에 있다
     * (해상도 파생 {@code ResolutionReservationPersister} 와 동일 규약).
     */
    @Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String storageDeidentifiedPath;

    /**
     * @return true = 신규 적재 / false = 재전송 멱등 스킵(신규 영상 미생성)
     */
    @Transactional("controlTransactionManager")
    public boolean handle(AugmentOutcome outcome) {
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
            log.info("[Webhook][Augment] duplicate result skipped dataAugSn={} otsdJobId={} state={}",
                    outcome.dataAugSn(), safe(outcome.externalJobId()), safe(aug.getAugProcSttsCd()));
            return false;
        }

        // 4) 상태 전이 + otsd_job_id 를 externalJobId(재전송 멱등 앵커)에 적재.
        //    uk_aug_external_job_id UNIQUE 위반(동시 콜백/다른 행 오배송)이면 재조회 후 멱등 흡수한다.
        //    [Phase 11] 멱등 앵커도 동기 트랜잭션에 유지(절대 async 로 이동 금지).
        //    (구 계약의 augType 대조는 제거됐다 — 새 계약은 request_id → job → dataAugSn 으로 대상을
        //     역산하므로 외부가 aug_type 을 잘못 실어 다른 행에 인계할 경로 자체가 없다.)
        String newStatus = outcome.success() ? LsDataAug.STTS_ACCEPTED : LsDataAug.STTS_REJECTED;
        try {
            applyAugStateAndExternalJobId(aug, outcome, newStatus);
            augRepository.save(aug);
            augRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // 동시/오배송 재전송 — 다른 트랜잭션이 이미 동일 otsd_job_id 를 선점했다.
            //
            // [DEV_FIX LOW] 여기서 흡수(return false)하면 안 된다. 본 메서드는 호출자
            // (GenAiCallbackService.handle) 트랜잭션에 <조인>돼 있어 UNIQUE 위반 시점에 트랜잭션이
            // 이미 rollback-only 로 마킹된다 → 정상 반환하면 컨트롤러가 200 을 만들고 커밋 단계에서
            // UnexpectedRollbackException(500) 이 터진다(응답과 실제 결과 불일치). 또한 PostgreSQL 은
            // 제약 위반 이후 같은 트랜잭션의 후속 쿼리를 거부하므로(25P02) 승자 재조회 자체가 성립하지
            // 않는다. 따라서 선점 충돌은 409 로 종결한다 — 외부가 재전송하면 위 1차 앵커
            // (non-PENDING skip)가 200/applied=false 로 멱등 흡수한다.
            log.warn("[Webhook][Augment] otsd_job_id 선점 충돌 — 409 종결 dataAugSn={} otsdJobId={}",
                    outcome.dataAugSn(), safe(outcome.externalJobId()));
            throw new CustomException(ErrorCode.CONFLICT, "이미 처리된 증강 결과입니다.");
        }

        // 5) 성공 시 새 영상(RAW_SN)만 동기 생성. 프레임/라벨/메타/procLog/COMPLETED(배치 마감) 는 커밋 후 async.
        if (LsDataAug.STTS_ACCEPTED.equals(newStatus)) {
            createAugmentedVideo(aug, outcome);
        }

        log.info("[Webhook][Augment] result applied dataAugSn={} success={} otsdJobId={}",
                outcome.dataAugSn(), outcome.success(), safe(outcome.externalJobId()));
        return true;
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
    private void createAugmentedVideo(LsDataAug aug, AugmentOutcome outcome) {
        LsDataSrc originSrc = srcRepository.findById(aug.getSrcSn()).orElse(null);
        if (originSrc == null) {
            log.warn("[Webhook][Augment] originSrc not found srcSn={} — skip video creation", aug.getSrcSn());
            return;
        }
        // HIGH #1 [동기 유지] — 부모 RAW 를 PESSIMISTIC_WRITE(FOR UPDATE)로 재조회해 잠근다. 아래 게이트
        // 검사~신규 RAW 커밋을 동시 비식별 신고(부모 markDeidentified('F'))와 같은 row 에서 직렬화한다.
        // 이 잠금과 게이트는 절대 async 로 옮기지 않는다(CWE-359 PII TOCTOU 창 재개방 방지).
        LsDataRaw parentRaw = videoRepository.findByRawSnForUpdate(originSrc.getRawSn()).orElse(null);
        if (parentRaw == null) {
            log.warn("[Webhook][Augment] parentRaw not found rawSn={} — skip video creation", originSrc.getRawSn());
            return;
        }

        // HIGH [동기 유지] — 부모 비식별 완료('Y')를 콜백 처리 시점에 잠금 하에서 재검증한다. 동시 신고가
        // 'F' 를 먼저 커밋했으면 여기서 관측하고 생성을 보류한다(PII 파생 증강본 차단, CWE-359).
        if (!"Y".equals(parentRaw.getDeIdntfYn())) {
            log.warn("[Webhook][Augment] parent not deidentified — blocking augmented video parentRawSn={} deIdntfYn={}",
                    parentRaw.getRawSn(), safe(parentRaw.getDeIdntfYn()));
            return;
        }

        // 프레임 존재 가드 — 부모에 프레임이 없으면 라벨링 대상이 없는 빈 증강본이므로 생성 보류.
        // (복사는 async 로 옮겼으나, 빈 부모에서 고아 RAW 를 만들지 않도록 존재 여부만 동기 확인.)
        List<LsDataSrc> parentFrames = srcRepository.findByRawSnOrderByFrameNoAsc(parentRaw.getRawSn());
        if (parentFrames.isEmpty()) {
            log.warn("[Webhook][Augment] parent has no frames — blocking augmented video parentRawSn={}",
                    parentRaw.getRawSn());
            return;
        }

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
        LsDataRaw newRaw = videoRepository.save(LsDataRaw.createFromAugment(
                parentRaw, provisionalVideoPath(deidBase, parentRaw.getRawSn(), aug.getAugTypeCd()),
                aug.getAugTypeCd()));
        newRaw.assignDerivativeVideoPath(resolveSafeDeidFile(deidBase,
                StorageSubtreePolicy.augmentVideoFile(
                        parentRaw.getRawSn(), newRaw.getRawSn(), aug.getAugTypeCd())).toString());

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
            target.applyReviewStatus(newStatus);
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
