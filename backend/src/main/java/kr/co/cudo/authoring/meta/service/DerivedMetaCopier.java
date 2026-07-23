package kr.co.cudo.authoring.meta.service;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepositoryCustom.MetaUpsert;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
import kr.co.cudo.authoring.meta.repository.LsDataMetaReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 파생영상(증강·해상도) 확정 시 원본(부모) 메타를 파생 RAW 로 복사하는 공용 헬퍼.
 *
 * <p><b>증강 경로({@code AugmentExtractPersist})와 해상도 파생 경로({@code ResolutionPersistService})가 동일
 * 로직을 공유</b>하도록 단일 진실원으로 추출했다(cudo_246 이슈2 통일 요구). 두 경로의 메타 복사·검수행 정책이
 * 드리프트하지 않도록 여기서만 정의한다.
 *
 * <h3>정책(사용자 확정 3항목)</h3>
 * <ol>
 *   <li><b>메타 값 전체 복사</b> — {@code video.*} 기술메타도 <b>포함</b>. 파생영상의 비디오 파일은 원본(비식별)
 *       복사본이라 {@code video.*}={원본값}이 정합적이다(구 {@code isTechnicalKey} skip 해제).</li>
 *   <li><b>검수행은 "부모에 검수행이 있던 메타키만" 미검수(PENDING)로 신규 생성</b> — 부모의 {@code META_TYPE_CD}/
 *       {@code SRC_SYS_CD}는 승계하되 상태만 미검수. 원본이 APPROVED 여도 파생은 미검수로 시작한다(파생영상
 *       자체가 미검수). {@code video.*}·수동입력 등 부모에 검수행이 없던 메타는 값만 복사하고 검수행을 만들지 않는다.</li>
 *   <li><b>중복/재실행 안전</b> — {@code upsertMetaBatch}(ON CONFLICT)로 값 멱등. 검수행은 삽입 전
 *       {@code (metaSn, metaTypeCd)} 존재확인으로 선재 skip 해 같은 트랜잭션 UNIQUE 위반(→PostgreSQL tx
 *       전체 abort)을 원천 차단한다.</li>
 * </ol>
 *
 * <h3>성능 — 메타 배치 upsert (HIGH#1)</h3>
 * <p>부모 메타는 VLM 시계열이면 수십~수백 키가 될 수 있어, 키당 1문 왕복 대신
 * {@link LsDataMetaRepository#upsertMetaBatch} 단일 JDBC 배치(1 라운드트립)로 적재한다.
 *
 * <h3>호출 순서 계약 (HIGH#4 — Critical)</h3>
 * <p>{@link LsDataMetaRepository#upsertMetaBatch}는 실행 전 {@code flush()} + 실행 후 {@code clear()}
 * 로 구 {@code upsertMeta} 의 {@code flushAutomatically/clearAutomatically} 동작을 배치에서도 그대로
 * 재현한다. 따라서 이 메서드는 <b>반드시 호출자의 확정 로직(markDeidentified/markCompleted/
 * markResolutionGenerated/procLog 저장) 이후</b>에 호출해야 한다. 앞에 두면 파생 RAW·aug 의 dirty 변경이
 * flush 되지 않고 detached 로 남아 영영 미확정 고착된다. 이 메서드 이후 어떤 관리 엔티티도 재수정하지 않는다.
 *
 * <p>트랜잭션: 자체 {@code @Transactional} 을 두지 않아 <b>호출자의 활성 트랜잭션에 참여</b>한다(같은 커밋 원자성).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DerivedMetaCopier {

    private final LsDataMetaRepository metaRepository;
    private final LsDataMetaReviewRepository reviewRepository;

    /** 복사 결과 요약 — 로그·검증용. */
    public record CopyResult(int copiedMetaCount, int createdReviewCount) {
    }

    /**
     * 부모 RAW 의 LS_DATA_META 를 파생 RAW 로 전체 복사(video.* 포함)하고, 부모에 검수행이 있던 메타키만
     * 파생에 미검수(PENDING) 검수행을 신규 생성한다.
     *
     * @param parentRawSn 원본(부모) RAW_SN — 복사 소스(내부 파이프라인 값, 사용자 입력 아님)
     * @param newRawSn    파생 RAW_SN — 복사 대상
     * @return 복사된 메타 건수·신규 생성 검수행 건수
     */
    public CopyResult copyMetaAndReviews(Long parentRawSn, Long newRawSn) {
        List<LsDataMeta> parentMetas = metaRepository.findByRawSn(parentRawSn);
        if (parentMetas.isEmpty()) {
            // MEDIUM#6 — 부모 메타 0건은 no-op 이 안전한 기본값. 가시성만 로그로 확보.
            log.info("[MetaCopy] parentMetas=0 rawSn={} orgnlRawSn={}", newRawSn, parentRawSn);
            return new CopyResult(0, 0);
        }

        // 부모 검수행 매핑: dataMetaSn -> 그 metaSn 의 <b>모든</b> 검수행(유형 무관). MEDIUM#3 — 한 metaSn 에
        //    VLM·EXTERNAL 두 유형 검수행이 있으면 UNIQUE(DATA_META_SN, META_TYPE_CD) 상 2건 공존 가능하므로,
        //    유형별로 모두 승계해야 한다(구 toMap 은 유형 1건만 남기고 나머지를 조용히 드롭했다).
        Map<Long, List<LsDataMetaReview>> parentReviewsByMetaSn = reviewRepository.findAllByDataRawSn(parentRawSn).stream()
                .collect(Collectors.groupingBy(LsDataMetaReview::getDataMetaSn));

        // 1) 메타 값 전체 복사(video.* 포함) — 단일 배치 upsert(HIGH#1). 복사 과정에서 "부모 검수행이 있던
        //    메타키" 를 metaKey 축으로 수집(값은 유형별 전체 리스트 — 파생 새 metaSn 은 이후 재조회로 확정).
        List<MetaUpsert> upserts = new ArrayList<>(parentMetas.size());
        Map<String, List<LsDataMetaReview>> reviewSourcesByMetaKey = new LinkedHashMap<>();
        for (LsDataMeta pm : parentMetas) {
            upserts.add(new MetaUpsert(pm.getMetaKey(), pm.getMetaVl()));
            List<LsDataMetaReview> parentReviews = parentReviewsByMetaSn.get(pm.getMetaSn());
            if (parentReviews != null && !parentReviews.isEmpty()) {
                reviewSourcesByMetaKey.put(pm.getMetaKey(), parentReviews);
            }
        }
        metaRepository.upsertMetaBatch(newRawSn, upserts);
        int copiedMetaCount = upserts.size();

        if (reviewSourcesByMetaKey.isEmpty()) {
            log.info("[MetaCopy] copied rawSn={} orgnlRawSn={} metas={} reviews=0", newRawSn, parentRawSn, copiedMetaCount);
            return new CopyResult(copiedMetaCount, 0);
        }

        // 2) 파생 새 metaSn 확정 — 배치 upsert 후 컨텍스트가 비워졌으므로 DB 재조회(metaKey -> 파생 metaSn).
        //    유형 무관하게 metaKey 축으로 매핑하므로 같은 metaSn 의 다유형 검수행이 모두 정확히 연결된다.
        Map<String, Long> newMetaSnByKey = metaRepository
                .findByRawSnAndMetaKeyIn(newRawSn, reviewSourcesByMetaKey.keySet()).stream()
                .collect(Collectors.toMap(LsDataMeta::getMetaKey, LsDataMeta::getMetaSn, (a, b) -> a));

        // 3) HIGH#2 — 이미 검수행이 존재하는 (파생 metaSn, 유형) 은 선재 skip(재실행·중복 복사 흡수). 같은
        //    트랜잭션에서 UNIQUE(DATA_META_SN, META_TYPE_CD) 위반이 나면 PostgreSQL 이 tx 전체를 abort 하므로,
        //    catch 로는 회복 불가다. 삽입 전 (metaSn, 유형) 복합키 존재확인으로 위반 자체를 차단한다.
        Set<Long> targetMetaSns = new HashSet<>(newMetaSnByKey.values());
        Set<String> alreadyReviewed = reviewRepository.findByDataMetaSnIn(targetMetaSns).stream()
                .map(r -> reviewKey(r.getDataMetaSn(), r.getMetaTypeCd()))
                .collect(Collectors.toSet());

        List<LsDataMetaReview> toCreate = new ArrayList<>();
        for (Map.Entry<String, List<LsDataMetaReview>> e : reviewSourcesByMetaKey.entrySet()) {
            Long newMetaSn = newMetaSnByKey.get(e.getKey());
            if (newMetaSn == null) {
                // 방어 — 방금 배치 삽입한 메타키의 파생 metaSn 이 조회되지 않음(정상 경로에선 발생 불가).
                log.warn("[MetaCopy] derived metaSn not resolved — review skipped rawSn={} metaKey={}",
                        newRawSn, e.getKey());
                continue;
            }
            // MEDIUM#3 — 부모 검수행을 유형(META_TYPE_CD)별로 모두 승계한다. 부모에 같은 유형이 중복(비정상)이면
            //    유형당 1건만 승계하고 드롭을 로그로 남긴다(조용한 누락 방지).
            Set<String> seenTypes = new HashSet<>();
            for (LsDataMetaReview src : e.getValue()) {
                String metaTypeCd = src.getMetaTypeCd();
                if (!seenTypes.add(metaTypeCd)) {
                    log.warn("[MetaCopy] duplicate parent review type dropped rawSn={} metaKey={} metaTypeCd={}",
                            newRawSn, e.getKey(), metaTypeCd);
                    continue;
                }
                if (alreadyReviewed.contains(reviewKey(newMetaSn, metaTypeCd))) {
                    continue; // 이미 같은 (metaSn, 유형) 검수행 존재 → skip
                }
                // 부모의 유형(META_TYPE_CD)·출처(SRC_SYS_CD)는 승계, 상태만 미검수(PENDING) — APPROVED 승계 안 함.
                toCreate.add(LsDataMetaReview.createAuto(
                        newMetaSn, newRawSn, null,
                        metaTypeCd,
                        src.getSrcSysCd(),
                        LsDataMetaReview.STTS_PENDING));
            }
        }

        int createdReviewCount = 0;
        if (!toCreate.isEmpty()) {
            reviewRepository.saveAll(toCreate);
            createdReviewCount = toCreate.size();
        }
        log.info("[MetaCopy] copied rawSn={} orgnlRawSn={} metas={} reviews={}",
                newRawSn, parentRawSn, copiedMetaCount, createdReviewCount);
        return new CopyResult(copiedMetaCount, createdReviewCount);
    }

    /** 검수행 복합키 — {@code (DATA_META_SN, META_TYPE_CD)}. UNIQUE 제약과 동일 축으로 선재 skip 판정. */
    private static String reviewKey(Long dataMetaSn, String metaTypeCd) {
        return dataMetaSn + "|" + metaTypeCd;
    }
}
