package com.crall.kokororeader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal dependency-free HTTP server for repository integration tests.
 *
 * <p>This deliberately avoids {@code com.sun.net.httpserver}, which lives in the
 * optional {@code jdk.httpserver} JDK module and is not available on Android's
 * unit-test compilation classpath.</p>
 */
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

    private final ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Mode mode;
    private final byte[] response;
    volatile String lastRequestBody = "";

    FakeKokoroServer(Mode mode, byte[] response) throws IOException {
        this.mode = mode;
        this.response = response.clone();
        serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        executor.execute(this::acceptConnections);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + serverSocket.getLocalPort();
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

    private void acceptConnections() {
        while (!closed.get()) {
            try {
                Socket socket = serverSocket.accept();
                executor.execute(() -> handleConnection(socket));
            } catch (SocketException ex) {
                if (!closed.get()) {
                    throw new IllegalStateException("Fake Kokoro server socket failed.", ex);
                }
                return;
            } catch (IOException ex) {
                if (!closed.get()) {
                    throw new IllegalStateException("Fake Kokoro server accept failed.", ex);
                }
                return;
            }
        }
    }

    private void handleConnection(Socket socket) {
        try (Socket connection = socket;
             InputStream rawInput = new BufferedInputStream(connection.getInputStream());
             OutputStream output = new BufferedOutputStream(connection.getOutputStream())) {
            connection.setTcpNoDelay(true);
            Request request = readRequest(rawInput);
            if (!"POST".equals(request.method) || !"/v1/audio/speech".equals(request.path)) {
                writeEmptyResponse(output, 404, "Not Found");
                return;
            }

            requestCount.incrementAndGet();
            lastRequestBody = new String(request.body, StandardCharsets.UTF_8);
            requestStarted.countDown();

            if (mode == Mode.BLOCK_BEFORE_RESPONSE
                    && !releaseResponse.await(10, TimeUnit.SECONDS)) {
                writeEmptyResponse(output, 504, "Gateway Timeout");
                return;
            }

            if (mode == Mode.TRUNCATED) {
                writeHeaders(output, 200, "OK", response.length + 128L);
                output.write(response);
                output.flush();
                return;
            }

            writeHeaders(output, 200, "OK", response.length);
            if (mode == Mode.SLOW_STREAM) {
                int offset = 0;
                while (offset < response.length) {
                    int count = Math.min(1024, response.length - offset);
                    output.write(response, offset, count);
                    output.flush();
                    offset += count;
                    streamingStarted.countDown();
                    Thread.sleep(25L);
                }
            } else {
                output.write(response);
                output.flush();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Client cancellation deliberately disconnects blocking or slow responses.
        }
    }

    private static Request readRequest(InputStream input) throws IOException {
        String requestLine = readAsciiLine(input);
        if (requestLine == null || requestLine.isEmpty()) {
            throw new EOFException("Missing HTTP request line.");
        }
        String[] parts = requestLine.split(" ", 3);
        if (parts.length != 3) {
            throw new IOException("Malformed HTTP request line: " + requestLine);
        }

        int contentLength = 0;
        while (true) {
            String line = readAsciiLine(input);
            if (line == null) {
                throw new EOFException("HTTP headers ended unexpectedly.");
            }
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if ("content-length".equals(name)) {
                try {
                    contentLength = Integer.parseInt(value);
                } catch (NumberFormatException ex) {
                    throw new IOException("Invalid Content-Length: " + value, ex);
                }
                if (contentLength < 0) {
                    throw new IOException("Negative Content-Length: " + value);
                }
            }
        }

        byte[] body = new byte[contentLength];
        int offset = 0;
        while (offset < body.length) {
            int count = input.read(body, offset, body.length - offset);
            if (count == -1) {
                throw new EOFException("HTTP request body ended early.");
            }
            offset += count;
        }
        return new Request(parts[0], parts[1], body);
    }

    private static String readAsciiLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int value = input.read();
            if (value == -1) {
                if (previous != -1) {
                    line.write(previous);
                }
                return line.size() == 0 ? null : line.toString(StandardCharsets.US_ASCII.name());
            }
            if (previous == '\r' && value == '\n') {
                return line.toString(StandardCharsets.US_ASCII.name());
            }
            if (previous != -1) {
                line.write(previous);
            }
            previous = value;
        }
    }

    private static void writeHeaders(
            OutputStream output, int status, String reason, long contentLength) throws IOException {
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: audio/mpeg\r\n"
                + "Content-Length: " + contentLength + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static void writeEmptyResponse(
            OutputStream output, int status, String reason) throws IOException {
        writeHeaders(output, status, reason, 0L);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        releaseResponse.countDown();
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // Closing an already-failed test server is best-effort cleanup.
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Request {
        final String method;
        final String path;
        final byte[] body;

        Request(String method, String path, byte[] body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }
    }
}
