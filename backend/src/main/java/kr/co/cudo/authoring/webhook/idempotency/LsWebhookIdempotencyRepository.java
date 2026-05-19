package kr.co.cudo.authoring.webhook.idempotency;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

@ControlRepo
public interface LsWebhookIdempotencyRepository extends JpaRepository<LsWebhookIdempotency, String> {
}
