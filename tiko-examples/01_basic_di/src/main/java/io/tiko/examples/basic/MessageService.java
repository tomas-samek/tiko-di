package io.tiko.examples.basic;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.PostConstruct;
import io.tiko.annotations.PreDestroy;

/**
 * SINGLETON service demonstrating constructor injection.
 * Depends on MessageRepository.
 */
@Component(scope = Scope.SINGLETON)
public class MessageService {

    private final MessageRepository repository;
    private int messageCounter = 0;

    @Inject
    public MessageService(MessageRepository repository) {
        System.out.println("[MessageService] Constructor called with repository: " + repository);
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        System.out.println("[MessageService] @PostConstruct - Service initialized");
        System.out.println("[MessageService] Repository has " + repository.count() + " messages");
    }

    @PreDestroy
    public void shutdown() {
        System.out.println("[MessageService] @PreDestroy - Service shutting down");
        System.out.println("[MessageService] Processed " + messageCounter + " messages");
    }

    public Long createMessage(String content) {
        messageCounter++;
        String enrichedContent = "[" + messageCounter + "] " + content;
        return repository.save(enrichedContent);
    }

    public String getMessage(Long id) {
        return repository.findById(id);
    }

    public int getProcessedCount() {
        return messageCounter;
    }
}
