package kr.co.cudo.authoring.eventtype.service;

import kr.co.cudo.authoring.common.config.CacheConfig;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.eventtype.repository.LsEvntTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.util.StringUtils;

/**
 * 이벤트유형 <b>자동등록</b> — 관제 인입을 소비할 때 미등록 유형코드를 우리 마스터
 * ({@code LS_EVNT_TYPE})에 등록한다 (V168).
 *
 * <h3>왜 별도 빈인가</h3>
 * <p>등록은 <b>쓰기 한 방향</b>이고 호출자는 영상 적재({@code TrainingVideoIngestTx})다. 이것을
 * 조회 서비스({@link EventTypeService}) 안에 두면 적재 경로가 필터·라벨 조회 서비스에 의존하게 되고,
 * 그 서비스는 {@code @Cacheable} 프록시 위에 있어 자기호출 함정이 겹친다.
 *
 * <h3>계약</h3>
 * <ol>
 *   <li><b>원자적</b> — {@code INSERT ... ON CONFLICT DO NOTHING} 1문장
 *       ({@link LsEvntTypeRepository#registerIfAbsent}). 2노드 동시 인입에서도 중복 등록·PK 위반·
 *       트랜잭션 abort 가 없다(CWE-362).</li>
 *   <li><b>관제 칸만 갱신한다</b> (사용자 확정 2026-08-04 — 구 {@code DO NOTHING} 폐기).
 *       관제 마스터에는 <b>유형별 이름이 없었고</b> 앞으로 관제가 인입으로 보내주므로, 등록된 행의
 *       관제 수신명({@code EVNT_NM})·대분류·카테고리를 <b>값이 바뀔 때만</b> 갱신한다.
 *       운영자 표시명({@code OPTR_INDCT_NM})과 수집여부는 <b>관제가 쓰지 않는 칸</b>이라 별도
 *       표식 없이도 보호된다. 조건 상세는
 *       {@link kr.co.cudo.authoring.eventtype.repository.LsEvntTypeRepository#registerOrRefresh}.</li>
 *   <li><b>★ 자체 트랜잭션({@link Propagation#REQUIRES_NEW})에서 실행한다 — 아래 4번 삼키기의
 *       전제조건</b>이다.</li>
 *   <li><b>실패해도 적재를 막지 않는다</b> — 등록은 부가 기능이다. 여기서 예외를 전파해 영상 적재
 *       트랜잭션을 롤백시키면 영상이 아예 들어오지 않아 되돌리기가 더 어렵다.</li>
 * </ol>
 *
 * <h3>★ 흡수는 <b>트랜잭션 프록시 바깥</b>에서만 성립한다 (Critical — 되돌리지 말 것)</h3>
 * <p>이 클래스에는 {@code @Transactional} 이 <b>없다</b>. 등록의 트랜잭션 경계는 별도 빈
 * ({@link EventTypeRegistrationTx}, {@code REQUIRES_NEW})이 갖고, 이 클래스는 그 프록시 호출을
 * <b>감싸서</b> 예외를 흡수한다. 트랜잭션 메서드 <b>안</b>에서 잡으면 리포지토리 예외가 남긴
 * rollback-only 마킹 때문에 <b>커밋 시점에</b> {@code UnexpectedRollbackException} 이 튀어나와
 * 흡수를 빠져나간다(실측 — 이 구조가 아니면 테스트가 그 예외로 실패한다).
 *
 * <p>{@code ON CONFLICT DO NOTHING} 이 막는 것은 <b>PK 유니크 위반 하나</b>뿐이다. 같은 행 잠금
 * 대기(lock timeout)·데드락·statement timeout·커넥션 순단이 나면 PostgreSQL 은 그 문 실행 시점에
 * <b>트랜잭션 전체를 aborted 상태</b>로 만든다. 이 메서드가 호출자의 트랜잭션에 <b>참여</b>(기본
 * {@code REQUIRED})하고 있으면, 아래 {@code catch} 가 Java 레벨에서 예외를 삼켜도 <b>물리 트랜잭션은
 * 이미 죽어 있어</b> 곧이어 도는 {@code videoRepository.save(raw)} 가 "current transaction is
 * aborted" 로 실패한다 — <b>영상 적재가 통째로 롤백</b>된다. 즉 트랜잭션 안에서 예외를 삼키는 것은
 * 성립하지 않는다(이 저장소의 확립된 결함 패턴). {@code REQUIRES_NEW} 는 별도 물리 트랜잭션·커넥션을
 * 쓰므로 내부에서 무슨 오류가 나도 호출자 상태에 영향이 없고, 그때 비로소 삼키기가 유효해진다.
 * 회귀 가드: {@code EventTypeAutoRegisterIsolationIT}.
 *
 * <h3>캐시 무효화 — <b>커밋 이후</b>에만</h3>
 * <p><b>등록 또는 갱신이 실제로 일어난 경우에만</b> {@link CacheConfig#CACHE_EVENT_TYPE} 를 비운다.
 * 매 인입마다 비우면 장수명(6h) 캐시가 사실상 무력화된다. 무효화는
 * {@link EventTypeCacheEvictor#evictAfterCommit()} 에 위임한다 — 커밋 전에 비우면 다른 요청이
 * <b>미커밋 상태를 읽어 옛 값으로 캐시를 다시 채우고</b> TTL 동안 새 유형이 안 보인다(상세는 그
 * 클래스 javadoc). 위 {@code REQUIRES_NEW} 와 결합되어 <b>자체 트랜잭션 커밋 직후</b> 비워진다.
 *
 * <p>⚠ 이 캐시는 <b>프로세스 로컬</b>(Caffeine)이라 evict 가 다른 노드에 전파되지 않는다. 다른 노드는
 * TTL(6시간) 만료 후 새 유형을 본다 — 시스템 설정 변경({@code SystemConfigService.update})과 동일한
 * 기존 특성이며 이 경로만의 결함이 아니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventTypeAutoRegistrar {

    /** {@code LS_EVNT_TYPE.EVNT_NM} 컬럼 길이(명V200) — 초과 수신값은 잘라 넣는다. */
    private static final int MAX_NM_LENGTH = 200;

    /** {@code LS_EVNT_TYPE.EVNT_CLSF_CD} 컬럼 길이(코드V20). */
    private static final int MAX_CLSF_LENGTH = 20;

    /** {@code LS_EVNT_TYPE.EVNT_TYPE_CD} 컬럼 길이(코드V20) — 초과면 등록하지 않는다(PK 는 자를 수 없다). */
    private static final int MAX_CD_LENGTH = 20;

    private final EventTypeRegistrationTx registrationTx;
    private final EventTypeCacheEvictor cacheEvictor;

    /**
     * 유형코드를 등록한다(미등록일 때만).
     *
     * @param evntTypeCd 관제 수신 이벤트유형코드. null/공백이면 no-op
     * @param evntNm     관제 수신 이벤트명(없으면 null — 소비측이 원문 코드로 폴백한다)
     * @param evntClsfCd 관제 수신 이벤트분류코드(대분류). 없으면 null — <b>유도하지 않는다</b>
     * @param evntCtgryCd 관제 수신 이벤트카테고리코드(3계층 중간 레벨). 없으면 null
     * @return 신규 등록되거나 실제로 갱신됐으면 true
     */
    public boolean register(String evntTypeCd, String evntNm, String evntClsfCd,
                            String evntCtgryCd) {
        String code = trimToNull(evntTypeCd);
        if (code == null) {
            return false;
        }
        if (code.length() > MAX_CD_LENGTH) {
            // 컬럼 길이를 넘는 코드는 등록할 수 없다(PK 는 자르면 다른 값이 된다). 적재는 계속한다.
            log.warn("[EventType] 유형코드 길이 초과 — 자동등록 건너뜀 length={}", code.length());
            return false;
        }
        int affected;
        try {
            affected = registrationTx.registerOrRefresh(code,
                    truncate(trimToNull(evntNm), MAX_NM_LENGTH),
                    truncate(trimToNull(evntClsfCd), MAX_CLSF_LENGTH),
                    truncate(trimToNull(evntCtgryCd), MAX_CLSF_LENGTH));
        } catch (RuntimeException e) {
            // ★여기가 유일한 흡수 지점이다 — 등록 트랜잭션 커밋 실패(UnexpectedRollbackException)
            //   까지 포함해 전부 잡는다. 관제 자유텍스트는 정제해 싣는다(CWE-117).
            log.warn("[EventType] 자동등록 실패 — 적재는 계속한다 code={} causeType={}",
                    LogSanitizer.sanitize(code, MAX_CD_LENGTH), e.getClass().getSimpleName());
            return false;
        }
        if (affected <= 0) {
            // 신규도 아니고 바뀐 값도 없다 — 대다수 인입이 여기로 온다(캐시를 건드리지 않는다).
            return false;
        }
        // 등록 트랜잭션이 <이미 커밋된> 시점이므로 즉시 비운다. 호출자(영상 적재) 트랜잭션의
        //   커밋을 기다리면 그 트랜잭션이 롤백될 때 <커밋된 유형이 캐시에 반영되지 않는다>.
        cacheEvictor.evictNow();
        log.info("[EventType] 이벤트유형 자동등록/갱신 code={}", LogSanitizer.sanitize(code, MAX_CD_LENGTH));
        return true;
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
