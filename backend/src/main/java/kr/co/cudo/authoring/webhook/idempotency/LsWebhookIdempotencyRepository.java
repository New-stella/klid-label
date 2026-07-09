package kr.co.cudo.authoring.webhook.idempotency;

import jakarta.persistence.LockModeType;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

@ControlRepo
public interface LsWebhookIdempotencyRepository extends JpaRepository<LsWebhookIdempotency, String> {

    /**
     * 비관적 쓰기 락(SELECT ... FOR UPDATE) 으로 원장 행을 조회한다.
     * 동일 idempotencyKey 의 동시 콜백을 직렬화하여 검수큐 중복 적재/META race(CWE-362)를 차단한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from LsWebhookIdempotency e where e.idmpKey = :key")
    Optional<LsWebhookIdempotency> findByIdmpKeyForUpdate(@Param("key") String key);
}
