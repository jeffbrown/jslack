package com.github.seratch.jslack.app_backend.events;

import com.github.seratch.jslack.app_backend.events.payload.EventsApiPayload;
import com.github.seratch.jslack.common.json.GsonFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class EventsDispatcherImpl implements EventsDispatcher {

    private final ConcurrentMap<String, List<EventHandler<?>>> eventTypeAndHandlers = new ConcurrentHashMap<>();

    private AtomicBoolean closed = new AtomicBoolean(false);

    private long maxTerminationDelayMillis = 10000L;

    public long getMaxTerminationDelayMillis() {
        return maxTerminationDelayMillis;
    }

    public void setMaxTerminationDelayMillis(long maxTerminationDelayMillis) {
        this.maxTerminationDelayMillis = maxTerminationDelayMillis;
    }

    private final Queue<String> queue = new LinkedList<>();

    private final Thread eventLoopThread = new Thread(() -> {
        while (true) {
            String json = queue.poll();
            if (json != null) {
                log.debug("New message found: {}", json);
                dispatch(json);
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    });

    private final EventTypeExtractor eventTypeExtractor;

    public EventsDispatcherImpl() {
        this(new EventTypeExtractorImpl());
    }

    public EventsDispatcherImpl(EventTypeExtractor eventTypeExtractor) {
        this.eventTypeExtractor = eventTypeExtractor;
    }

    @Override
    public boolean isRunning() {
        return !closed.get();
    }

    @Override
    public boolean isEmpty() {
        return eventTypeAndHandlers.isEmpty();
    }

    @Override
    public void register(EventHandler<? extends EventsApiPayload<?>> handler) {
        String eventType = handler.getEventType();
        List<EventHandler<?>> handlers = eventTypeAndHandlers.getOrDefault(eventType, new ArrayList<>());
        handlers.add(handler);
        eventTypeAndHandlers.put(eventType, handlers);
    }

    @Override
    public void deregister(EventHandler<? extends EventsApiPayload<?>> handler) {
        String eventType = handler.getEventType();
        List<EventHandler<?>> handlers = eventTypeAndHandlers.getOrDefault(eventType, new ArrayList<>());
        List<EventHandler<?>> newHandlers = new ArrayList<>();
        for (EventHandler<?> h : handlers) {
            if (!h.equals(handler)) {
                newHandlers.add(h);
            }
        }
        eventTypeAndHandlers.put(eventType, newHandlers);
    }

    @Override
    public void dispatch(String json) {
        String eventType = eventTypeExtractor.extractEventType(json);
        if (eventType == null) {
            log.debug("Failed to detect event type from the given JSON data: {}", json);
            return;
        }

        List<EventHandler<?>> eventHandlers = eventTypeAndHandlers.get(eventType);
        if (eventHandlers == null || eventHandlers.size() == 0) {
            log.debug("No event handler registered for type: {}", eventType);
        } else {
            try {
                Class<?> clazz = eventHandlers.get(0).getEventPayloadClass();
                EventsApiPayload<?> event = (EventsApiPayload) GsonFactory.createSnakeCase().fromJson(json, clazz);
                for (EventHandler<?> handler : eventHandlers) {
                    handler.acceptUntypedObject(event);
                }
            } catch (Exception ex) {
                log.error("Exception handling event with type: {}", eventType, ex);
            }
        }
    }

    @Override
    public void enqueue(String json) {
        if (closed.get()) {
            throw new IllegalStateException("EventDispatcher is stopping.");
        } else {
            queue.add(json);
        }
    }

    @Override
    public void start() {
        closed.set(false);
        eventLoopThread.start();
    }

    @Override
    public void stop() {
        try {
            closed.set(true);
            long waitMillis = 0;
            while (queue.size() > 1 & waitMillis < getMaxTerminationDelayMillis()) {
                Thread.sleep(50L);
            }
            eventLoopThread.interrupt();
        } catch (InterruptedException e) {
            eventLoopThread.interrupt();
            Thread.currentThread().interrupt();
        }
    }

}
