package com.yeremitech.mailplatform.api.config;

import com.yeremitech.mailplatform.api.security.ClientTemplatePolicy;
import com.yeremitech.mailplatform.api.security.PersistentClientCredentials;
import com.yeremitech.mailplatform.api.security.ClientDisplayNames;
import com.yeremitech.mailplatform.application.port.AttachmentStoragePort;
import com.yeremitech.mailplatform.application.port.DeliveryAttemptRepository;
import com.yeremitech.mailplatform.application.port.DeliveryPolicyPort;
import com.yeremitech.mailplatform.application.port.MailBatchRepository;
import com.yeremitech.mailplatform.application.port.MailMessageRepository;
import com.yeremitech.mailplatform.application.port.MailProcessingPort;
import com.yeremitech.mailplatform.application.port.MailProviderPort;
import com.yeremitech.mailplatform.application.port.MailQueuePort;
import com.yeremitech.mailplatform.application.port.MailRetryPort;
import com.yeremitech.mailplatform.application.port.MailSubmissionPort;
import com.yeremitech.mailplatform.application.port.OutboxRepository;
import com.yeremitech.mailplatform.application.port.PasswordRecoveryRepository;
import com.yeremitech.mailplatform.application.port.PasswordRecoveryThrottlePort;
import com.yeremitech.mailplatform.application.port.SensitivePayloadPort;
import com.yeremitech.mailplatform.application.port.TemplateRendererPort;
import com.yeremitech.mailplatform.application.security.SecureTokenGenerator;
import com.yeremitech.mailplatform.application.security.TokenHasher;
import com.yeremitech.mailplatform.application.usecase.ConsumeResetGrantUseCase;
import com.yeremitech.mailplatform.application.usecase.CreateBatchUseCase;
import com.yeremitech.mailplatform.application.usecase.GetBatchSummaryUseCase;
import com.yeremitech.mailplatform.application.usecase.QueueEmailUseCase;
import com.yeremitech.mailplatform.application.usecase.RequestPasswordRecoveryUseCase;
import com.yeremitech.mailplatform.application.usecase.VerifyPasswordRecoveryUseCase;
import com.yeremitech.mailplatform.infrastructure.adapter.JdbcDeliveryAttemptAdapter;
import com.yeremitech.mailplatform.infrastructure.adapter.JdbcMarketingDeliveryPolicyAdapter;
import com.yeremitech.mailplatform.infrastructure.adapter.JdbcMailRetryAdapter;
import com.yeremitech.mailplatform.infrastructure.adapter.JdbcOutboxAdapter;
import com.yeremitech.mailplatform.infrastructure.adapter.JdbcTransactionalMailSubmissionAdapter;
import com.yeremitech.mailplatform.infrastructure.adapter.JpaMailBatchAdapter;
import com.yeremitech.mailplatform.infrastructure.adapter.JdbcMailProcessingAdapter;
import com.yeremitech.mailplatform.infrastructure.adapter.JdbcSensitivePayloadAdapter;
import com.yeremitech.mailplatform.infrastructure.adapter.JdbcPasswordRecoveryThrottleAdapter;
import com.yeremitech.mailplatform.infrastructure.adapter.JpaMailMessageAdapter;
import com.yeremitech.mailplatform.infrastructure.adapter.JpaPasswordRecoveryAdapter;
import com.yeremitech.mailplatform.infrastructure.adapter.JdbcAttachmentStorageAdapter;
import com.yeremitech.mailplatform.infrastructure.adapter.RabbitMailQueueAdapter;
import com.yeremitech.mailplatform.infrastructure.jpa.SpringDataMailBatchRepository;
import com.yeremitech.mailplatform.infrastructure.jpa.SpringDataMailRepository;
import com.yeremitech.mailplatform.infrastructure.jpa.SpringDataPasswordChallengeRepository;
import com.yeremitech.mailplatform.infrastructure.jpa.SpringDataResetGrantRepository;
import com.yeremitech.mailplatform.provider.smtp.SmtpMailProvider;
import com.yeremitech.mailplatform.template.ThymeleafTemplateRenderer;
import com.yeremitech.mailplatform.worker.MailDeliveryWorker;
import com.yeremitech.mailplatform.worker.OutboxPublisher;
import com.yeremitech.mailplatform.worker.StaleProcessingRecovery;
import com.yeremitech.mailplatform.worker.SensitivePayloadCleanup;
import java.time.Clock;
import java.time.Duration;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.TemplateEngine;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class ApplicationConfiguration {

    @Bean
    PersistentClientCredentials persistentClientCredentials(NamedParameterJdbcTemplate jdbc,
            @Value("${app.security.api-key-pepper:}") String pepper) {
        return new PersistentClientCredentials(jdbc, pepper);
    }

    @Bean
    ClientTemplatePolicy clientTemplatePolicy(@Value("${app.mail.client-template-access:}") String grants,
            com.yeremitech.mailplatform.api.service.DynamicTemplateService templates) {
        return new ClientTemplatePolicy(grants, templates);
    }

    @Bean
    ClientDisplayNames clientDisplayNames(@Value("${app.mail.client-display-names:}") String names) {
        return new ClientDisplayNames(names);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SecureTokenGenerator secureTokenGenerator() {
        return new SecureTokenGenerator();
    }

    @Bean
    TokenHasher tokenHasher(@Value("${app.security.otp-hmac-secret}") String secret) {
        return new TokenHasher(secret);
    }

    @Bean
    MailMessageRepository mailMessageRepository(SpringDataMailRepository repository, JsonMapper jsonMapper) {
        return new JpaMailMessageAdapter(repository, jsonMapper);
    }

    @Bean
    MailSubmissionPort mailSubmissionPort(
            NamedParameterJdbcTemplate jdbc,
            JsonMapper jsonMapper,
            MailMessageRepository mailMessageRepository,
            SensitivePayloadPort sensitivePayloads) {
        return new JdbcTransactionalMailSubmissionAdapter(jdbc, jsonMapper, mailMessageRepository, sensitivePayloads);
    }

    @Bean
    OutboxRepository outboxRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcOutboxAdapter(jdbc);
    }

    @Bean
    DeliveryAttemptRepository deliveryAttemptRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcDeliveryAttemptAdapter(jdbc);
    }

    @Bean
    DeliveryPolicyPort deliveryPolicyPort(NamedParameterJdbcTemplate jdbc) {
        return new JdbcMarketingDeliveryPolicyAdapter(jdbc);
    }

    @Bean
    MailRetryPort mailRetryPort(NamedParameterJdbcTemplate jdbc) {
        return new JdbcMailRetryAdapter(jdbc);
    }

    @Bean
    MailBatchRepository mailBatchRepository(SpringDataMailBatchRepository repository) {
        return new JpaMailBatchAdapter(repository);
    }

    @Bean
    PasswordRecoveryRepository passwordRecoveryRepository(
            SpringDataPasswordChallengeRepository challenges,
            SpringDataResetGrantRepository grants,
            NamedParameterJdbcTemplate jdbc) {
        return new JpaPasswordRecoveryAdapter(challenges, grants, jdbc);
    }

    @Bean
    MailProcessingPort mailProcessingPort(NamedParameterJdbcTemplate jdbc, MailMessageRepository repository) {
        return new JdbcMailProcessingAdapter(jdbc, repository);
    }

    @Bean
    SensitivePayloadPort sensitivePayloadPort(
            NamedParameterJdbcTemplate jdbc,
            JsonMapper jsonMapper,
            @Value("${app.security.sensitive-payload-key}") String key) {
        return new JdbcSensitivePayloadAdapter(jdbc, jsonMapper, key);
    }


    @Bean
    PasswordRecoveryThrottlePort passwordRecoveryThrottlePort(NamedParameterJdbcTemplate jdbc) {
        return new JdbcPasswordRecoveryThrottleAdapter(jdbc);
    }
    @Bean
    MailQueuePort mailQueuePort(RabbitTemplate rabbitTemplate) {
        return new RabbitMailQueueAdapter(rabbitTemplate);
    }

    @Bean
    AttachmentStoragePort attachmentStoragePort(NamedParameterJdbcTemplate jdbc) {
        return new JdbcAttachmentStorageAdapter(jdbc);
    }

    @Bean
    TemplateRendererPort templateRendererPort(TemplateEngine engine,
            com.yeremitech.mailplatform.api.service.DynamicTemplateService templates) {
        var packaged = new ThymeleafTemplateRenderer(engine);
        return new TemplateRendererPort() {
            public RenderedTemplate render(String key, java.util.Map<String,Object> vars) {
                return packaged.render(key, vars);
            }
            @Override public RenderedTemplate render(String clientId, String key, java.util.Map<String,Object> vars) {
                return key.startsWith("custom/") ? templates.render(clientId, key, vars) : packaged.render(key, vars);
            }
        };
    }

    @Bean
    MailProviderPort mailProviderPort(
            JavaMailSender mailSender,
            @Value("${app.mail.from}") String from,
            @Value("${app.mail.from-name}") String fromName,
            @Value("${app.marketing.one-click-headers-enabled:false}") boolean enableOneClickHeaders) {
        return new SmtpMailProvider(mailSender, from, fromName, enableOneClickHeaders);
    }

    @Bean
    QueueEmailUseCase queueEmailUseCase(
            MailMessageRepository repository,
            MailSubmissionPort submission,
            Clock clock) {
        return new QueueEmailUseCase(repository, submission, clock);
    }

    @Bean
    CreateBatchUseCase createBatchUseCase(
            MailBatchRepository batchRepository,
            QueueEmailUseCase queueEmailUseCase,
            Clock clock) {
        return new CreateBatchUseCase(batchRepository, queueEmailUseCase, clock);
    }

    @Bean
    GetBatchSummaryUseCase getBatchSummaryUseCase(
            MailBatchRepository batchRepository,
            MailMessageRepository mailRepository) {
        return new GetBatchSummaryUseCase(batchRepository, mailRepository);
    }

    @Bean
    RequestPasswordRecoveryUseCase requestPasswordRecoveryUseCase(
            PasswordRecoveryRepository repository,
            PasswordRecoveryThrottlePort throttle,
            QueueEmailUseCase queueEmail,
            TokenHasher hasher,
            SecureTokenGenerator tokens,
            Clock clock) {
        return new RequestPasswordRecoveryUseCase(repository, throttle, queueEmail, hasher, tokens, clock);
    }

    @Bean
    VerifyPasswordRecoveryUseCase verifyPasswordRecoveryUseCase(
            PasswordRecoveryRepository repository,
            TokenHasher hasher,
            SecureTokenGenerator tokens,
            Clock clock) {
        return new VerifyPasswordRecoveryUseCase(repository, hasher, tokens, clock);
    }

    @Bean
    ConsumeResetGrantUseCase consumeResetGrantUseCase(
            PasswordRecoveryRepository repository,
            TokenHasher hasher,
            Clock clock) {
        return new ConsumeResetGrantUseCase(repository, hasher, clock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
    OutboxPublisher outboxPublisher(
            OutboxRepository outbox,
            MailQueuePort queue,
            Clock clock,
            @Value("${app.outbox.batch-size:100}") int batchSize,
            @Value("${app.outbox.max-attempts:10}") int maxAttempts,
            @Value("${app.outbox.lease-seconds:30}") long leaseSeconds) {
        return new OutboxPublisher(
                outbox,
                queue,
                clock,
                batchSize,
                maxAttempts,
                Duration.ofSeconds(leaseSeconds));
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
    StaleProcessingRecovery staleProcessingRecovery(
            MailRetryPort retry,
            Clock clock,
            @Value("${app.delivery.processing-stale-seconds:300}") long staleSeconds,
            @Value("${app.delivery.heartbeat-interval-ms:30000}") long heartbeatMillis,
            @Value("${app.delivery.stale-recovery-batch-size:100}") int batchSize) {
        if (heartbeatMillis < 1000 || staleSeconds < 1 || staleSeconds * 1000 < heartbeatMillis * 3) {
            throw new IllegalArgumentException("processing stale timeout must be at least three heartbeat intervals");
        }
        return new StaleProcessingRecovery(
                retry, clock, Duration.ofSeconds(staleSeconds), batchSize);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
    MailDeliveryWorker mailDeliveryWorker(
            MailProcessingPort processing,
            TemplateRendererPort renderer,
            MailProviderPort provider,
            AttachmentStoragePort storage,
            SensitivePayloadPort sensitivePayloads,
            PasswordRecoveryRepository recovery,
            DeliveryAttemptRepository attempts,
            MailRetryPort retry,
            Clock clock,
            DeliveryPolicyPort deliveryPolicy,
            @Value("${app.delivery.max-attempts:5}") int maxDeliveryAttempts) {
        return new MailDeliveryWorker(
                processing,
                renderer,
                provider,
                storage,
                sensitivePayloads,
                recovery,
                attempts,
                retry,
                clock,
                maxDeliveryAttempts,
                deliveryPolicy);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
    SensitivePayloadCleanup sensitivePayloadCleanup(
            SensitivePayloadPort sensitivePayloads,
            Clock clock,
            @Value("${app.sensitive-payload.cleanup-batch-size:500}") int batchSize) {
        return new SensitivePayloadCleanup(sensitivePayloads, clock, batchSize);
    }

    @Bean
    DirectExchange mailExchange() {
        return new DirectExchange("mail.exchange", true, false);
    }

    @Bean
    DirectExchange mailDeadLetterExchange() {
        return new DirectExchange("mail.dlx", true, false);
    }

    @Bean
    Declarables mailQueues(DirectExchange mailExchange, DirectExchange mailDeadLetterExchange) {
        Queue security = durableQueue("mail.security", "mail.dead.security");
        Queue transactional = durableQueue("mail.transactional", "mail.dead.transactional");
        Queue document = durableQueue("mail.document", "mail.dead.document");
        Queue bulk = durableQueue("mail.bulk", "mail.dead.bulk");

        Queue deadSecurity = QueueBuilder.durable("mail.dead.security").build();
        Queue deadTransactional = QueueBuilder.durable("mail.dead.transactional").build();
        Queue deadDocument = QueueBuilder.durable("mail.dead.document").build();
        Queue deadBulk = QueueBuilder.durable("mail.dead.bulk").build();

        return new Declarables(
                security, transactional, document, bulk,
                deadSecurity, deadTransactional, deadDocument, deadBulk,
                BindingBuilder.bind(security).to(mailExchange).with("mail.security"),
                BindingBuilder.bind(transactional).to(mailExchange).with("mail.transactional"),
                BindingBuilder.bind(document).to(mailExchange).with("mail.document"),
                BindingBuilder.bind(bulk).to(mailExchange).with("mail.bulk"),
                BindingBuilder.bind(deadSecurity).to(mailDeadLetterExchange).with("mail.dead.security"),
                BindingBuilder.bind(deadTransactional).to(mailDeadLetterExchange).with("mail.dead.transactional"),
                BindingBuilder.bind(deadDocument).to(mailDeadLetterExchange).with("mail.dead.document"),
                BindingBuilder.bind(deadBulk).to(mailDeadLetterExchange).with("mail.dead.bulk"));
    }

    private Queue durableQueue(String name, String deadRoutingKey) {
        return QueueBuilder.durable(name)
                .deadLetterExchange("mail.dlx")
                .deadLetterRoutingKey(deadRoutingKey)
                .build();
    }
}
