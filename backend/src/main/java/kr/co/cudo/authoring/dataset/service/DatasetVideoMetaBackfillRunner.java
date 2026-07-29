package kr.co.cudo.authoring.dataset.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 배포 1회 실행되는 데이터마트 통합 메타 <b>백필 실행기</b>.
 *
 * <p>Flyway 마이그레이션 완료(컨텍스트 리프레시) 후 {@link ApplicationRunner} 로 기동 시점에 한 번
 * {@link DatasetVideoMetaBackfillService#backfill()} 을 호출한다. 백필은 NOT EXISTS 가드로 멱등하므로
 * 매 기동마다 실행해도(대상 0건이면 no-op) 안전하다.
 *
 * <p>구현 방식 근거: Flyway Java-based 마이그레이션은 Spring 빈(materialize 서비스·리포지토리)을
 * 주입받기 어렵고, 순수 SQL 백필은 materialize 로직(MNG_* 조인·해시)과 drift 위험이 있다. 따라서
 * 컨텍스트가 완비된 뒤 기존 서비스 경로를 재사용하는 ApplicationRunner 로 구현한다.
 *
 * <p>{@code @Profile("!local")} — 실제 배포 프로파일(dev/stg/prd)에서만 등록한다. 로컬/테스트(local)는
 * 신규 설치라 소급 대상이 없고, 공유 Testcontainer 오염(다른 IT 의 스냅샷/아웃박스 카운트 간섭)을
 * 피하려 미등록한다. 백필 로직 자체는 {@link DatasetVideoMetaBackfillService} 를 IT 에서 직접 검증한다.
 */
@Slf4j
@Component
@Order(Integer.MAX_VALUE) // 다른 기동 러너(시드 등) 이후 마지막에 실행.
@Profile("!local")
@RequiredArgsConstructor
public class DatasetVideoMetaBackfillRunner implements ApplicationRunner {

    private final DatasetVideoMetaBackfillService backfillService;

    @Override
    public void run(ApplicationArguments args) {
        int n = backfillService.backfill();
        log.info("[Dataset] startup backfill materialized {} snapshot(s)", n);
        // 이미 승인됐으나 EVNT_ANNO_CN=NULL 로 동결된 영상(rawSn 24 상황) 소급 치유 — 멱등(대상 0건이면 no-op).
        int healed = backfillService.healMissingEventAnnotation();
        log.info("[Dataset] startup event_annotation heal re-froze {} snapshot(s)", healed);
        // M-1(Phase 10B) — 파생 폴백 폐기 이전에 추정값(NGT/SUMMER)으로 동결된 촬영환경 정정.
        //   1회 실행당 상한이 있어 잔여분은 다음 기동에서 이어진다(멱등 — 대상 0건이면 no-op).
        int corrected = backfillService.correctDerivedShootingEnvironment();
        log.info("[Dataset] startup shooting-env correction re-froze {} snapshot(s)", corrected);
    }
}
