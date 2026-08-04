package kr.co.cudo.authoring.user.repository;

import kr.co.cudo.authoring.user.entity.LsUserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LS_USER_ROLE(저작도구 소유 역할 매핑) 엔티티 ↔ 실 PostgreSQL(Testcontainers) round-trip 검증.
 *
 * <p>저작도구 고유 역할(REVIEWER/WORKER/PORTAL_USER)을 구 구조의 관제 소유 권한 매핑 테이블에서
 * 분리해 저작도구 자체 LS 테이블로 보관하기 위한 Phase 1 산출물(테이블/엔티티/리포)을
 * 실제 마이그레이션(V75)이 적용된 컨테이너 위에서 런타임 증명한다. 구 권한 테이블은 V165 로
 * 삭제됐고, 지금은 이 테이블이 저작도구 인가 역할의 단일 진실원이다.
 *
 * <p>{@code LsUserRoleRepository} 는 {@code @ControlRepo} 이므로 control 데이터소스의
 * {@code controlTransactionManager} 트랜잭션 안에서 동작한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class LsUserRoleRepositoryIT {

    @Autowired
    private LsUserRoleRepository repository;

    /** 본 테스트 전용 USER_NO 대역 — 다른 통합테스트/시드와 충돌 회피. */
    private static final AtomicLong USER_NO_SEQ = new AtomicLong(975_100_000L);

    @Test
    @DisplayName("LS_USER_ROLE_저장_후_USER_NO로_조회된다")
    void LS_USER_ROLE_저장_후_USER_NO로_조회된다() {
        // given — REVIEWER 역할 매핑 1행
        long userNo = USER_NO_SEQ.incrementAndGet();
        LsUserRole role = LsUserRole.of(userNo, "REVIEWER");

        // when — 실 PostgreSQL 저장 후 USER_NO 로 재조회
        repository.saveAndFlush(role);
        Optional<LsUserRole> found = repository.findByUserNo(userNo);

        // then — USER_NO/ROLE_CD round-trip, REG_DT 채워짐
        assertThat(found).isPresent();
        assertThat(found.get().getUserNo()).isEqualTo(userNo);
        assertThat(found.get().getRoleCd()).isEqualTo("REVIEWER");
        assertThat(found.get().getRegDt()).isNotNull();
        assertThat(found.get().getUpdDt()).isNull();
    }

    @Test
    @DisplayName("changeRole_호출시_ROLE_CD가_변경되고_UPD_DT가_채워진다")
    void changeRole_호출시_ROLE_CD가_변경되고_UPD_DT가_채워진다() {
        // given — WORKER 로 저장된 행
        long userNo = USER_NO_SEQ.incrementAndGet();
        repository.saveAndFlush(LsUserRole.of(userNo, "WORKER"));

        // when — 비즈니스 메서드로 REVIEWER 전환 후 flush
        LsUserRole role = repository.findByUserNo(userNo).orElseThrow();
        role.changeRole("REVIEWER");
        repository.saveAndFlush(role);

        // then — ROLE_CD 변경 + UPD_DT 채워짐
        LsUserRole reloaded = repository.findByUserNo(userNo).orElseThrow();
        assertThat(reloaded.getRoleCd()).isEqualTo("REVIEWER");
        assertThat(reloaded.getUpdDt()).isNotNull();
    }

    @Test
    @DisplayName("findByUserNoIn으로_여러_USER_NO를_한번에_조회한다")
    void findByUserNoIn으로_여러_USER_NO를_한번에_조회한다() {
        // given — 서로 다른 역할 3행
        long a = USER_NO_SEQ.incrementAndGet();
        long b = USER_NO_SEQ.incrementAndGet();
        long c = USER_NO_SEQ.incrementAndGet();
        repository.saveAndFlush(LsUserRole.of(a, "REVIEWER"));
        repository.saveAndFlush(LsUserRole.of(b, "WORKER"));
        repository.saveAndFlush(LsUserRole.of(c, "PORTAL_USER"));

        // when — IN 절 일괄 조회 (N+1 방지)
        List<LsUserRole> roles = repository.findByUserNoIn(List.of(a, b, c));

        // then — 3행 모두 조회
        assertThat(roles).hasSize(3);
        assertThat(roles).extracting(LsUserRole::getUserNo)
                .containsExactlyInAnyOrder(a, b, c);
    }

    @Test
    @DisplayName("findByUserNoIn_빈_컬렉션이면_빈_리스트를_반환한다")
    void findByUserNoIn_빈_컬렉션이면_빈_리스트를_반환한다() {
        // given/when — 빈 컬렉션 조회
        List<LsUserRole> roles = repository.findByUserNoIn(List.of());

        // then — 빈 리스트(예외 없음)
        assertThat(roles).isEmpty();
    }

    @Test
    @DisplayName("같은_userNo_역할변경_2회_단일행_멱등_upsert")
    void 같은_userNo_역할변경_2회_단일행_멱등_upsert() {
        // given — 동일 USER_NO 에 WORKER → REVIEWER 순차 upsert (PK race 대비 원자 문)
        long userNo = USER_NO_SEQ.incrementAndGet();

        // when — 같은 PK 로 2회 upsert (PK 중복 예외 없이 ON CONFLICT DO UPDATE)
        int first = repository.upsertRole(userNo, "WORKER");
        int second = repository.upsertRole(userNo, "REVIEWER");

        // then — 각 호출 1행 영향, 단일 행만 남고 최종 역할은 REVIEWER
        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(1);
        List<LsUserRole> rows = repository.findByUserNoIn(List.of(userNo));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRoleCd()).isEqualTo("REVIEWER");
    }

    @Test
    @DisplayName("동일_역할_재upsert는_멱등하여_단일행을_유지한다")
    void 동일_역할_재upsert는_멱등하여_단일행을_유지한다() {
        long userNo = USER_NO_SEQ.incrementAndGet();
        repository.upsertRole(userNo, "WORKER");
        repository.upsertRole(userNo, "WORKER");

        List<LsUserRole> rows = repository.findByUserNoIn(List.of(userNo));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRoleCd()).isEqualTo("WORKER");
    }
}
