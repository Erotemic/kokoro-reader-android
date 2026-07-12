package com.crall.kokororeader;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class FakeKokoroServer implements AutoCloseable {
    enum Mode {
        SUCCESS,
        BLOCK_BEFORE_RESPONSE,
        SLOW_STREAM,
        TRUNCATED
    }

    final AtomicInteger requestCount = new AtomicInteger();
    final CountDownLatch requestStarted = new CountDownLatch(1);
    final CountDownLatch streamingStarted = new CountDownLatch(1);
    final CountDownLatch releaseResponse = new CountDownLatch(1);

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Mode mode;
    private final byte[] response;
    volatile String lastRequestBody = "";

    FakeKokoroServer(Mode mode, byte[] response) throws IOException {
        this.mode = mode;
        this.response = response.clone();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/audio/speech", this::handleSpeech);
        server.setExecutor(executor);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    boolean awaitRequest(long timeout, TimeUnit unit) throws InterruptedException {
        return requestStarted.await(timeout, unit);
    }

    boolean awaitStreaming(long timeout, TimeUnit unit) throws InterruptedException {
        return streamingStarted.await(timeout, unit);
    }

    void releaseResponse() {
        releaseResponse.countDown();
    }

    private void handleSpeech(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        lastRequestBody = readAll(exchange.getRequestBody());
        requestStarted.countDown();
        exchange.getResponseHeaders().add("Content-Type", "audio/mpeg");
        try {
            if (mode == Mode.BLOCK_BEFORE_RESPONSE) {
                if (!releaseResponse.await(10, TimeUnit.SECONDS)) {
                    exchange.sendResponseHeaders(504, -1);
                    return;
                }
            }
            if (mode == Mode.TRUNCATED) {
                exchange.sendResponseHeaders(200, response.length + 128L);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(response);
                    output.flush();
                }
                return;
            }
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                if (mode == Mode.SLOW_STREAM) {
                    int offset = 0;
                    while (offset < response.length) {
                        int count = Math.min(1024, response.length - offset);
                        output.write(response, offset, count);
                        output.flush();
                        offset += count;
                        streamingStarted.countDown();
                        try {
                            Thread.sleep(25L);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                } else {
                    output.write(response);
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            exchange.sendResponseHeaders(503, -1);
        } catch (IOException ignored) {
            // Client cancellation deliberately disconnects a slow response.
        } finally {
            exchange.close();
        }
    }

    private static String readAll(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = source.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    @Override
    public void close() {
        releaseResponse.countDown();
        server.stop(0);
        executor.shutdownNow();
    }
}
