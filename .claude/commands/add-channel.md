Add a new notification channel dispatcher to the service.

## When to use
When a new channel type (e.g. SLACK, TELEGRAM, VOICE) needs to be supported.

## Steps

### 1. Add the enum value
Edit `src/main/java/com/dmg/notification/domain/enums/Channel.java`:
```java
public enum Channel { EMAIL, SMS, WHATSAPP, PUSH, IN_APP, <NEW_CHANNEL> }
```

### 2. Create the dispatcher stub
Create `src/main/java/com/dmg/notification/channel/<NewChannel>ChannelDispatcher.java`:

```java
@Slf4j
@Service
public class <NewChannel>ChannelDispatcher implements ChannelDispatcher {

    @Override
    public Channel channel() {
        return Channel.<NEW_CHANNEL>;
    }

    @Override
    public DispatchResult dispatch(Notification notification) {
        String address = notification.getRecipientAddress();

        // Test hooks — remove when replacing with real provider SDK
        if (address.startsWith("fail-transient-<slug>:")) {
            return DispatchResult.transientFailure("Simulated transient failure");
        }
        if (address.startsWith("fail-permanent-<slug>:")) {
            return DispatchResult.permanentFailure("Simulated permanent failure");
        }
        if (address.startsWith("circuit-trip-<slug>:")) {
            throw new RuntimeException("Simulated exception to trip circuit breaker");
        }

        log.info("[<NEW_CHANNEL>] Dispatching to={} subject={}", address,
                notification.getRenderedSubject());

        // TODO: replace with real provider SDK call
        return DispatchResult.success("<NEW_CHANNEL>-mock-id-" + System.nanoTime());
    }
}
```

### 3. Add address resolution
In `NotificationService.resolveAddress()`, add a case for the new channel:
```java
case <NEW_CHANNEL> -> req.<getNewChannelField>() != null
        ? req.<getNewChannelField>()
        : recipientRef;
```

Add the corresponding field to `SendNotificationRequest.java` and `NotificationRequestEvent.java`.

### 4. Add Resilience4j circuit breaker config
In `src/main/resources/application.yml`, add under `resilience4j.circuitbreaker.instances`:
```yaml
<new_channel_lowercase>:
  base-config: default
  wait-duration-in-open-state: 60s   # adjust for expected outage duration
```

### 5. Write a unit test for the stub
Add test cases in a new test class `src/test/java/.../unit/<NewChannel>DispatcherTest.java`
covering: success, transient failure, permanent failure, circuit trip.

### 6. Write an integration test
Add at least one test in `KafkaFlowIntegrationTest` or a new test class verifying
end-to-end delivery through the new channel.

### 7. Update documentation
- Add the channel to `docs/context/domain-model.md` Glossary if it has a non-obvious address format
- Mention the channel in `README.md` channel table

## Checklist
- [ ] Channel enum value added
- [ ] ChannelDispatcher implementation created with test hooks
- [ ] `resolveAddress()` case added
- [ ] `SendNotificationRequest` field added
- [ ] `NotificationRequestEvent` field added
- [ ] Resilience4j config added (lowercase key)
- [ ] Unit test written
- [ ] Integration test written
- [ ] Docs updated
- [ ] `mvn test-compile -q` passes
