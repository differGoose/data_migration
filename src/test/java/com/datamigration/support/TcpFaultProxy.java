package com.datamigration.support;

import java.io.IOException;
import java.net.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Byte-transparent relay: TLS stays end-to-end. Cut closes existing sockets and rejects new ones. */
public final class TcpFaultProxy implements AutoCloseable {
    private final ServerSocket listener;
    private final String upstreamHost;
    private final int upstreamPort;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "resilience-relay"); thread.setDaemon(true); return thread;
    });
    private volatile boolean cut;
    private volatile boolean closed;
    private final AtomicLong forwarded = new AtomicLong();

    public TcpFaultProxy(String host, int port, int localPort) throws IOException {
        upstreamHost = host; upstreamPort = port;
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), localPort));
        workers.submit(this::accept);
    }
    public int port() { return listener.getLocalPort(); }
    public long forwardedConnections() { return forwarded.get(); }
    private void accept() {
        while (!closed) {
            try {
                Socket client = listener.accept();
                workers.submit(() -> connect(client));
            } catch (IOException | RejectedExecutionException e) { if (closed) return; }
        }
    }
    private void connect(Socket client) {
        Socket server = new Socket();
        try {
            synchronized (this) {
                if (cut || closed) { close(client); return; }
                sockets.add(client); sockets.add(server);
            }
            server.connect(new InetSocketAddress(upstreamHost, upstreamPort), 5000);
            synchronized (this) {
                if (cut || closed) { close(client); close(server); return; }
                forwarded.incrementAndGet();
            }
            workers.submit(() -> copy(client, server));
            copy(server, client);
        } catch (IOException | RejectedExecutionException ignored) {
            close(client); close(server);
        } finally { sockets.remove(client); sockets.remove(server); }
    }
    private void copy(Socket from, Socket to) {
        try { from.getInputStream().transferTo(to.getOutputStream()); }
        catch (IOException ignored) { /* Connection reset is the deliberate fault. */ }
        finally { close(from); close(to); }
    }
    public synchronized void cut() { cut = true; sockets.forEach(TcpFaultProxy::close); }
    public synchronized void restore() { cut = false; }
    @Override public synchronized void close() throws IOException {
        closed = true; cut(); listener.close(); workers.shutdownNow();
    }
    private static void close(Socket socket) { try { socket.close(); } catch (IOException ignored) { } }
}
