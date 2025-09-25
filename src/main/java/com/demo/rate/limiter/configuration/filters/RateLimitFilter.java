package com.demo.rate.limiter.configuration.filters;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private static final Integer LIMIT = 2;
    private static final Long TIME_INTERVAL = 5000L; // 5 sec

    private final ConcurrentHashMap<String, RequestBucket> buckets = new ConcurrentHashMap<>();
    private ScheduledExecutorService cleanerThreadPool;

    @PostConstruct
    private void init() {
        cleanerThreadPool = Executors.newScheduledThreadPool(1);
        cleanerThreadPool.schedule(this::cleanUpBucket, 5L, TimeUnit.MINUTES);

    }

    @PreDestroy
    private void onDestroy() {
        if (this.cleanerThreadPool != null && !this.cleanerThreadPool.isShutdown()) {
            cleanerThreadPool.shutdown();
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String clientIp = request.getRemoteAddr();
        RequestBucket currentRequestBucket = buckets.computeIfAbsent(clientIp, k -> new RequestBucket());
        boolean allowed;
        synchronized (currentRequestBucket) {
            long now = System.currentTimeMillis();
            if (now - currentRequestBucket.startTime > TIME_INTERVAL) {
                currentRequestBucket.startTime = now;
                currentRequestBucket.counter.set(0);
            }
            allowed = currentRequestBucket.counter.incrementAndGet() <= LIMIT;
        }

        if (!allowed) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.addHeader("Retry-After", String.valueOf(TIME_INTERVAL / 1000));
            response.getWriter().write("Too many requests");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void cleanUpBucket() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, RequestBucket>> iterator = buckets.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, RequestBucket> clientBucket = iterator.next();
            RequestBucket bucket = clientBucket.getValue();
            boolean shouldKeep = now - bucket.startTime < 2 * TIME_INTERVAL;
            if (!shouldKeep) {
                buckets.remove(clientBucket.getKey());
            }
        }
    }

    private static class RequestBucket {
        long startTime = Instant.now().toEpochMilli();
        AtomicInteger counter = new AtomicInteger(0);
    }
}
