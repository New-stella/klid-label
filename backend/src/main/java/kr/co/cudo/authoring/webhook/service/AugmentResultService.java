package kr.co.cudo.authoring.webhook.service;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.runner.AsyncVideoMetaRunner;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.ExternalUrlValidator;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.dto.AugmentResultRequest;
import kr.co.cudo.authoring.webhook.runner.AsyncAugmentFrameRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 외부 생성형 AI 증강 결과 인계 처리 서비스 — 연동정의서 정합.
 *
 * <p>{@code POST /v1/aug/callback} 의 HMAC 인증 통과 후 호출된다.
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
 *       ({@link LsDataAugRepository#findByExternalJobId}) 재조회 후 멱등 흡수(skip). 동시 콜백/
 *       다른 행 오배송으로 같은 otsd_job_id 가 이미 선점된 경우를 방어한다.</li>
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
 * PENDING·deIdntfYn='N' 으로만 커밋하고, 프레임/라벨/메타/procLog/MARKING_READY 는 추출 성공 후
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
     * 증강본 기술메타(ffprobe) 추출 트리거. {@code VideoIngestedEvent} 미발행 경로라 메타추출 브리지가
     * 스킵되므로 증강 생성 커밋 후 직접 호출한다(비식별은 트리거하지 않음 — 재비식별 skip 유지).
     */
    private final AsyncVideoMetaRunner asyncVideoMetaRunner;

    /**
     * @return true = 신규 적재 / false = 재전송 멱등 스킵(신규 영상 미생성)
     */
    @Transactional("controlTransactionManager")
    public boolean handle(AugmentResultRequest req) {
        // 1) SSRF — raw_file_path_nm
        validateFilePath(req.rawFilePathNm());

        // 2) 대상 증강 행 조회 (data_aug_sn = 요청 시 발급된 LS_DATA_AUG PK).
        //    MED #2 — 같은 dataAugSn 동시 콜백을 직렬화하기 위해 PESSIMISTIC_WRITE(FOR UPDATE)로 잠금 조회한다.
        //    이렇게 해야 아래 1차 앵커(non-PENDING skip)의 read-then-act 가 원자적이 되어, 서로 다른
        //    otsd_job_id 를 가진 동시 콜백이 둘 다 PENDING 을 통과해 이중 영상을 만드는 창이 닫힌다.
        //    [Phase 11] 이 잠금은 동기 트랜잭션에 유지한다(절대 async 로 이동 금지 — 멱등 판정 원자성).
        LsDataAug aug = augRepository.findByDataAugSnForUpdate(req.dataAugSn())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "증강 행을 찾을 수 없습니다: dataAugSn=" + req.dataAugSn()));

        // 3) 재전송 멱등 방어(1차 앵커) — 이미 종결(non-PENDING)된 행이면 재전송이다 → skip.
        if (!LsDataAug.STTS_PENDING.equals(aug.getAugProcSttsCd())) {
            log.info("[Webhook][Augment] duplicate result skipped dataAugSn={} otsdJobId={} state={}",
                    req.dataAugSn(), safe(req.otsdJobId()), safe(aug.getAugProcSttsCd()));
            return false;
        }

        // 4) augType 불일치 차단 — 외부 시스템이 다른 행에 잘못 인계하는 사고 방지
        if (!req.augTypeCd().equals(aug.getAugTypeCd())) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "augType 불일치: row=" + aug.getAugTypeCd() + " request=" + req.augTypeCd());
        }

        // 5) 상태 전이 + otsd_job_id 를 externalJobId(재전송 멱등 앵커)에 적재.
        //    uk_aug_external_job_id UNIQUE 위반(동시 콜백/다른 행 오배송)이면 재조회 후 멱등 흡수한다.
        //    [Phase 11] 멱등 앵커도 동기 트랜잭션에 유지(절대 async 로 이동 금지).
        String newStatus = "SUCCESS".equals(req.augProcStsCd())
                ? LsDataAug.STTS_ACCEPTED
                : LsDataAug.STTS_REJECTED;
        try {
            applyAugStateAndExternalJobId(aug, req, newStatus);
            augRepository.save(aug);
            augRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // 동시/오배송 재전송 — 다른 트랜잭션이 이미 동일 otsd_job_id 를 선점했다.
            LsDataAug existing = augRepository.findByExternalJobId(req.otsdJobId())
                    .orElseThrow(() -> new IllegalStateException(
                            "UNIQUE(otsd_job_id) 위반 후 재조회 실패", e));
            log.warn("[Webhook][Augment] race 감지 후 멱등 흡수 dataAugSn={} otsdJobId={} winnerState={}",
                    req.dataAugSn(), safe(req.otsdJobId()), safe(existing.getAugProcSttsCd()));
            return false;
        }

        // 6) 성공 시 새 영상(RAW_SN)만 동기 생성. 프레임/라벨/메타/procLog/MARKING_READY 는 커밋 후 async.
        if (LsDataAug.STTS_ACCEPTED.equals(newStatus)) {
            createAugmentedVideo(aug, req);
        }

        log.info("[Webhook][Augment] result applied dataAugSn={} status={} otsdJobId={}",
                req.dataAugSn(), safe(req.augProcStsCd()), safe(req.otsdJobId()));
        return true;
    }

    /**
     * Phase 11 — 증강 성공 시 <b>새 영상(RAW_SN)만</b> 동기 생성한다. 부모 안전 판정(잠금·게이트)은
     * 동기 유지하되, 프레임 재추출/라벨·메타 복사/비식별 완료 불변식은 커밋 후
     * {@link AsyncAugmentFrameRunner} 로 미룬다.
     *
     * <p>신규 RAW 는 {@code createFromAugment} 기본값(PENDING·deIdntfYn='N') 그대로 커밋된다 —
     * 추출 성공 전까지는 스트리밍/마킹 진입이 불가하다(프레임 0건 MARKING_READY 차단).
     */
    private void createAugmentedVideo(LsDataAug aug, AugmentResultRequest req) {
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

        String filePath = req.rawFilePathNm() != null ? req.rawFilePathNm() : parentRaw.getRawFilePathNm();
        // createFromAugment 기본값(PENDING·deIdntfYn='N') 그대로 커밋. 추가 상태 세팅 없음(async 에서 확정).
        LsDataRaw newRaw = videoRepository.save(LsDataRaw.createFromAugment(parentRaw, filePath, req.augTypeCd()));

        // 커밋 후 비동기 프레임 재추출 + 라벨/메타 복사 + 비식별 완료 불변식 확정 트리거.
        triggerAsyncFrameExtractionAfterCommit(newRaw.getRawSn(), aug.getDataAugSn());

        log.info("[Webhook][Augment] new video created (pending, async extraction) rawSn={} orgnlRawSn={} augType={} dataStts={}",
                newRaw.getRawSn(), parentRaw.getRawSn(), req.augTypeCd(), newRaw.getDataSttsCd());
    }

    /**
     * 증강 프레임 재추출/메타추출을 <b>커밋 이후</b>에 트리거한다. 두 러너 모두 REQUIRES_NEW 독립
     * 트랜잭션에서 새 RAW_SN 을 재조회하므로, 외부 트랜잭션 커밋 전에 호출하면 새 영상이 아직 보이지
     * 않는 레이스가 생긴다. 트랜잭션 동기화가 활성이면 AFTER_COMMIT 으로 미룬다. 동기화 미활성(단위
     * 테스트 등)이면 직접 호출로 폴백한다.
     */
    private void triggerAsyncFrameExtractionAfterCommit(Long newRawSn, Long dataAugSn) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    asyncAugmentFrameRunner.runAsync(newRawSn, dataAugSn);
                    asyncVideoMetaRunner.runAsync(newRawSn);
                }
            });
        } else {
            asyncAugmentFrameRunner.runAsync(newRawSn, dataAugSn);
            asyncVideoMetaRunner.runAsync(newRawSn);
        }
    }

    /**
     * PENDING 상태 전이 + otsd_job_id 를 externalJobId(재전송 멱등 앵커)에 적재.
     * externalJobId 는 요청 시점 placeholder 를 콜백 시점의 실제 otsd_job_id 로 갱신한다(무조건 덮어쓰기)
     * → 종결 행이 항상 otsd_job_id 를 보유하여 UNIQUE(uk_aug_external_job_id) 재전송 방어가 성립한다.
     */
    private static void applyAugStateAndExternalJobId(LsDataAug target, AugmentResultRequest req,
                                                      String newStatus) {
        if (LsDataAug.STTS_PENDING.equals(target.getAugProcSttsCd())) {
            target.applyReviewStatus(newStatus);
        }
        target.assignExternalJobId(req.otsdJobId());
    }

    private void validateFilePath(String filePath) {
        if (filePath == null || filePath.isBlank()) return;
        if (filePath.contains("://")) {
            try {
                ExternalUrlValidator.validate(filePath, false, "rawFilePathNm");
            } catch (IllegalArgumentException e) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "rawFilePathNm SSRF 차단: " + e.getMessage());
            }
        }
    }

    private static String safe(String s) {
        if (s == null) return "null";
        return LOG_UNSAFE.matcher(s).replaceAll("_");
    }
}
