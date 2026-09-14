package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.batch.pipeline.BatchStep;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.service.DeidentExclusionPolicy;
import kr.co.cudo.authoring.batch.service.DeidentExclusionService;
import kr.co.cudo.authoring.batch.service.KpstDeidentService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.cache.StreamMetaCacheEvictor;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.label.service.DeidentReportService;
import kr.co.cudo.authoring.notification.NotificationService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * 비식별화 단계.
 * <p>
 * 경로 단일화 (UC018): 외부 위탁 영상의 비식별 확정은 KPST 폴링으로 단일화되었다. 레거시 동기
 * SPI(DeidentifyClient) 경로는 제거되었으며, 본 단계가 취할 수 있는 경로는 아래와 같다.
 *  - ⓪ ★<b>출처유형 제외</b>({@code ADR-066}): 배포 설정 {@code authoring.deidentify.excluded-src-types}
 *       (기본 {@code GENERATED})에 든 출처유형 영상은 <b>KPST 활성 여부와 무관하게</b> 외부 위탁 없이
 *       {@link DeidentExclusionService} 가 원본을 비식별 영상 쓰기 위치에 일반 파일로 복사하고, 이력에
 *       요청종류 EXCLUDED 성공 행을 남긴 뒤 동기 완료를 돌려준다. 이 판정은 KPST 검사보다 앞이다.
 *       아래 폐지된 자체 채움과 달리 <b>전 영상이 아니라 명시 설정한 출처유형만</b> 대상이고 이력으로 구분된다.
 *  - ★<b>자체 채움(mock) 경로는 폐지했다</b>(2026-08-19). 과거에는 외부 호출 없이 <b>원본을 비식별
 *    경로로 복사</b>하고 {@code DE_IDNTF_YN='Y'} 로 마킹했다 — 즉 <b>마스킹되지 않은 원본이 "비식별
 *    완료"로 통과</b>했고, 그 영상이 데이터마트 뷰와 산출물로 나갔다. local/dev/stg 에서 허용됐으므로
 *    납품과 같은 계열인 stg 에서도 성립했다.
 *    이 프로젝트는 로컬조차 외부 시스템을 <b>별도 목 서버</b>로 세워 실제 HTTP 로 호출한다 —
 *    애플리케이션이 스스로 결과를 지어내는 경로를 두지 않기 위해서다. 그 원칙에 맞춰 없앴다.
 *    비식별을 돌리려면 목 서버든 실서버든 <b>외부 비식별 서버가 있어야 한다</b>.
 *  - ② KPST 위탁({@code kpst.deid.enabled=true}): {@link KpstDeidentService#submit} 위탁만 수행하고
 *       완료(다운로드→Y전이)는 폴링 잡이 담당.
 * <p>
 * 실패 전파 계약 (Phase C-2 — 논블로킹 제출 전환에 따른 재배선):
 *  - <b>제출 이전</b>의 실패(원본 부재·경로 손상·export 디렉터리 생성/검증 실패)는 <b>여전히 예외</b>로
 *    전파되어 본 단계가 실패하고, 'F' 마킹은 별도 트랜잭션으로 커밋된다(기존과 동일).
 *  - <b>제출 이후</b>(ACK 왕복)의 실패는 이 스레드로 돌아오지 않는다. {@code KpstSubmitOutcomeRecorder}
 *    가 전용 풀에서 원장 FAILED + {@code DE_IDNTF_YN='F'}(+재비식별이면 락 해제)를 <b>별도 REQUIRES_NEW
 *    로 커밋</b>하므로 종단 상태는 구 동기 계약과 동일하다. 어느 경로에서도 MARKING_READY 로 전이되지
 *    않으므로 마킹 조기 진입은 발생하지 않는다.
 * 그 경로가 없는 경우(KPST 서비스 미주입)는 설정 오류로 간주하고 명확한 예외를 던진다
 * (레거시 폴백 없음, 내부 정보 미노출). ⚠ 구 서술 "둘 다 아닌 경우(mock 아님 + …)" 폐기 —
 * 견줄 mock 경로가 없으므로 경우의 수는 하나다.
 * <p>
 * 공통 정책 (V2):
 *  - 영상 단위로 비식별. 출력은 비식별 영상이며 원본 filePath 는 절대 변경되지 않는다 — 원본 보존 원칙.
 *  - 결과 영상 경로는 LS_DEIDENT_REPORT.DE_IDNTF_FILE_PATH_NM 에만 저장.
 * <p>
 * 보안:
 *  - SSRF (CWE-918)/경로순회 (CWE-22): URL/CA 신뢰체인·다운로드 경로 검증은 KPST 클라이언트가 방어.
 *    ⚠ 구 서술 폐기 — "mock 출력 경로는 storage.deidentified-path 기반으로 base 이탈을
 *    차단한다". <b>그 출력 경로 자체가 없다</b>(자체 채움 폐지). 외부 위탁 분기의 다운로드 경로 검증은
 *    위 KPST 클라이언트가 단독으로 진다. 출처유형 제외 분기가 쓰는 복사본 경로는
 *    {@link DeidentExclusionService} 가 쓰기 직전 base 재계산·실경로 재확인으로 검증한다.
 *  - <b>비식별 엔드포인트 신뢰 판정은 여기서 하지 않는다</b>: 자체 복사(mock-mode)든 KPST 위탁이든
 *    "위조 비식별(원본이 비식별본으로 서빙됨)" 여부는
 *    {@link kr.co.cudo.authoring.common.security.DeidentifyEndpointTrustGuard} 단일 진입점이 판정한다.
 * <p>
 * ★ 그 판정이 <b>기동을 막지 않는다</b> (2026-09-03 · {@code ADR-062}):
 *  - 구 서술 폐기 — <i>"부팅 시점에 판정한다(비신뢰 → dev/stg WARN, prd 부팅 거부)"</i> ·
 *    <i>"Phase 1 — mock 비식별 모드(local/dev/stg 허용, prd 차단): 외부 서버 없이 원본을 비식별
 *    경로로 복사하여 단계를 통과시킨다 … mock-mode=true 인데 prd 이면 부트 거부"</i>.
 *    <b>둘 다 더 이상 사실이 아니다.</b> 자체 복사 경로 자체가 폐지됐고(위 ★ 항목), 남은 mock-mode
 *    토글도 <b>기동이 아니라 비식별 산출·위탁을 거부</b>하는 축으로 옮겨졌다. 되살리지 말 것.
 *  - 지금 형상: 운영에서 신뢰할 수 없는 비식별 경로(목/시뮬레이터 주소 <b>또는</b> mock-mode)면
 *    기동은 성공하고 ERROR 가 남으며, KPST 위탁 주소가 사용 불가로 낮춰져 이 단계의 {@code submit}
 *    이 전송 가드에서 실패한다 → 'F' 마킹. <b>MARKING_READY 로 전이하지 않으므로</b> 위조 비식별본이
 *    마킹·산출물·관제 통지로 나가는 것은 그대로 막힌다.
 */
@Slf4j
@Component
public class DeidentifyStep implements BatchStep {

    /**
     * UC018 — KPST 폴링 위탁 서비스. {@code kpst.deid.enabled=true} 일 때만 빈으로 존재(아니면 null).
     * 본 Step 은 KPST 위탁(upload→project)만 수행하고 완료(다운로드→Y전이)는 폴링 잡이 담당한다.
     */
    private final KpstDeidentService kpstDeidentService;
    /**
     * 자기참조 프록시 공급자 — 호출 사슬은 {@link #execute(BatchContext)} → 프록시 → {@link #run(LsDataRaw)}
     * (<b>트랜잭션 경계 없음</b>, 출처유형 제외 분기가 먼저) → 프록시 → {@link #submitToExternal(LsDataRaw)}
     * ({@code @Transactional(REQUIRES_NEW)}) 이다. 외부 위탁 분기의 신규 트랜잭션은 이 프록시 경유 호출로만 열린다.
     * 직접 자기호출은 Spring AOP 프록시를 우회해 트랜잭션이 열리지 않는다.
     * <p>{@link ObjectProvider} 는 호출 시점에 지연 해석되므로 자기 빈 순환 의존이 생성 시점에 생기지 않는다.
     * 단위 테스트에서 수동 생성(provider=null)하면 {@code this} 로 폴백한다.
     */
    private final ObjectProvider<DeidentifyStep> selfProvider;

    /**
     * UC018 — KPST 폴링 경로 토글(킬스위치). 기본 true(KPST 단일 경로).
     * false 로 내리면 KPST 위탁이 비활성화되고, <b>다른 경로가 없으므로</b> 설정 오류로 거부된다
     * (레거시 폴백 없음). ⚠ 구 서술 "mock 도 아니면" 폐기 — 대안 경로가 있는 것처럼 읽힌다.
     */
    @Value("${kpst.deid.enabled:true}")
    private boolean kpstEnabled;


    /**
     * 출처유형 제외 판정(ADR-066). {@code null} 이면 제외 축이 없는 것으로 본다(수동 생성 단위 테스트 전용).
     */
    private final DeidentExclusionPolicy exclusionPolicy;
    /** 출처유형 제외 처리 — 원본 복사(트랜잭션 밖) + 짧은 독립 트랜잭션 기록. */
    private final DeidentExclusionService exclusionService;

    @Autowired
    public DeidentifyStep(@Autowired(required = false) KpstDeidentService kpstDeidentService,
                          ObjectProvider<DeidentifyStep> selfProvider,
                          DeidentExclusionPolicy exclusionPolicy,
                          DeidentExclusionService exclusionService) {
        this.kpstDeidentService = kpstDeidentService;
        this.selfProvider = selfProvider;
        this.exclusionPolicy = exclusionPolicy;
        this.exclusionService = exclusionService;
    }

    /** 제외 축 없이 구성 — 수동 생성 단위 테스트 호환용. */
    public DeidentifyStep(KpstDeidentService kpstDeidentService, ObjectProvider<DeidentifyStep> selfProvider) {
        this(kpstDeidentService, selfProvider, null, null);
    }


    @Override
    public BatchStage stage() {
        return BatchStage.DEIDENTIFY;
    }

    /**
     * 균일 파이프라인 인터페이스 — 기존 typed {@link #run(LsDataRaw)} 에 위임 (Phase 2).
     * 선두 비식별 파이프라인({@code preMarkingPipeline}) 이 본 메서드로 단계를 실행한다.
     */
    @Override
    public void execute(BatchContext ctx) {
        // run()(트랜잭션 경계 없음, 출처유형 제외 분기가 먼저) → 프록시 → submitToExternal()(REQUIRES_NEW).
        // 외부 위탁 분기의 트랜잭션은 run() 안의 프록시 경유 호출이 연다(ADR-066 으로 경계를 run() 에서 옮김).
        // DEV_FIX 배경 — 적재 경로(AsyncDeidentifyRunner.runAsync, 무트랜잭션)에서 직접 자기호출은 프록시를
        // 우회해 트랜잭션이 열리지 않아 영속이 커밋되지 않던 결함이 있었다. 그래서 프록시 경유 호출을 유지한다.
        // 단위 테스트(수동 생성, provider=null)는 this 로 폴백(리포지토리 mock — 트랜잭션 불필요).
        DeidentifyStep self = (selfProvider != null) ? selfProvider.getObject() : this;
        DeidentResult result = self.run(ctx.getRaw());
        // 동기 완료(mock) 여부를 컨텍스트에 실어 호출자(AsyncDeidentifyRunner)가 조건부 전이하게 한다.
        // void execute() 라 run() 반환을 직접 못 받으므로 컨텍스트로 브릿지한다(상태머신 단일화).
        ctx.markDeidentCompleted(result.completed());
    }

    /**
     * 영상 비식별을 트리거한다 — 공통 진입점({@code AsyncDeidentifyRunner} 는 {@link #execute} 경유,
     * {@code DevPipelineRunner} 는 직접 호출).
     * <p>
     * 경로 결정:
     *  0. ★<b>출처유형 제외</b>(ADR-066) — 영상 출처유형이 배포 설정 제외 목록에 들면 <b>KPST 활성 여부와
     *     무관하게</b> 외부 위탁 없이 원본을 비식별 영상 자리에 복사하고 {@link DeidentResult#completed} 를
     *     돌려준다(호출자가 MARKING_READY 전이 + 예약 마킹 깨우기). 이 판정은 KPST 검사보다 <b>앞</b>에 있다.
     *     ⚠ 이것은 폐지된 자체 채움(mock) 경로의 부활이 아니다 — 자체 채움은 <b>모든</b> 영상을 조건 없이
     *     통과시켰고, 이 분기는 <b>비식별할 실제 인물이 애초에 없는 출처유형</b>만 명시적 배포 설정으로
     *     가르며 이력에 요청종류 EXCLUDED 로 남긴다.
     *  1. KPST 위탁({@code kpstEnabled} + 서비스 주입): {@link KpstDeidentService#submit} 위탁만 수행.
     *     DE_IDNTF_YN 미전이(완료 대기), MARKING_READY 미전이 — 완료는 폴링 잡이 담당.
     *     제출은 논블로킹이라 ACK 를 기다리지 않는다(Phase C-2) — 제출 이후 실패는 예외가 아니라
     *     완료 핸들러의 별도 커밋('F')으로 나타난다(클래스 javadoc "실패 전파 계약" 참조).
     *  2. 그 외(설정 오류): 폴백 없음 → 명확한 설정 오류 예외(내부 정보 미노출).
     * <p>
     * 트랜잭션: 이 메서드 자체에는 경계가 없다 — 제외 분기의 대용량 복사를 트랜잭션 밖에서 하기 위해서다.
     * 외부 위탁 분기는 자기참조 프록시로 {@link #submitToExternal}({@code REQUIRES_NEW})을 호출해 종전과
     * 같은 트랜잭션 의미를 유지한다. 제외 분기의 기록은 별도 빈의 독립 트랜잭션으로 커밋된다.
     * <p>
     * ⚠ <b>이 메서드에 {@code @Transactional} 을 되돌리지 말 것</b> — 제외 분기의 대용량 원본 복사가
     * 트랜잭션(커넥션 점유) 안에서 돌게 된다.
     *
     * @return 제외 분기면 {@link DeidentResult#completed}(복사본 경로), 외부 위탁이면 {@link DeidentResult#deferred}.
     * @design ADR-066
     * @design DFEAT-041
     */
    public DeidentResult run(LsDataRaw raw) {
        if (raw == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "raw 가 null 입니다.");
        }
        if (exclusionPolicy != null && exclusionService != null && exclusionPolicy.isExcluded(raw.getSrcType())) {
            return DeidentResult.completed(exclusionService.complete(raw));
        }
        DeidentifyStep self = (selfProvider != null) ? selfProvider.getObject() : this;
        return self.submitToExternal(raw);
    }

    /**
     * 외부 비식별 솔루션(KPST) 위탁 — {@link #run} 의 제외 분기 밖 경로. 종전 {@code run()} 본문과 같다.
     * <p>
     * REQUIRES_NEW 트랜잭션: 위탁 실패 시에도 src 레코드는 유지된다. 반드시 프록시 경유로 호출한다
     * ({@link #run} 이 자기참조 프록시로 부른다 — 직접 자기호출은 트랜잭션이 열리지 않는다).
     *
     * @return 외부 위탁(KPST)이므로 {@link DeidentResult#deferred}. 전이는 폴링이 단일 지점에서 수행한다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public DeidentResult submitToExternal(LsDataRaw raw) {
        if (raw == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "raw 가 null 입니다.");
        }
        // UC018 — KPST 폴링 경로(기본): 위탁(upload→project)만 수행하고 완료(다운로드→Y전이)는 폴링 잡이 담당.
        // 제출 직후에는 MARKING_READY 로 전이하지 않는다(DE_IDNTF_YN='N' 유지) — deferred 반환으로 호출자가 전이를 건너뛴다.
        if (kpstEnabled && kpstDeidentService != null) {
            kpstDeidentService.submit(raw);
            return DeidentResult.deferred();
        }
        // 설정 오류 — KPST 서비스가 주입되지 않았다(자체 채움 경로는 폐지돼 대안이 없다).
        // ⚠ 구 주석 "mock 도 아니고" 폐기. 레거시 폴백도 제거되었으므로
        // 임의 동작 대신 명확히 거부한다(CWE-209: 내부 구현/경로 미노출, 고정 메시지만).
        log.error("[Batch][Deid] no deidentify path available rawSn={} kpstEnabled={} kpstService={}",
                raw.getRawSn(), kpstEnabled, kpstDeidentService != null);
        throw new CustomException(ErrorCode.INTERNAL_ERROR, "비식별 경로가 구성되지 않았습니다.");
    }




}
