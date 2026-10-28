package com.datamigration.component;
import com.datamigration.support.*;

import java.net.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TcpFaultProxyTest {
    @Test void cutsExistingConnectionsRejectsNewOnesAndRestoresOnSamePort() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
             var proxy = new TcpFaultProxy("127.0.0.1", server.getLocalPort(), 0)) {
            executor.submit(() -> {
                try {
                    while (!server.isClosed()) try (var socket = server.accept()) {
                        socket.getInputStream().transferTo(socket.getOutputStream());
                    }
                } catch (Exception ignored) { }
            });
            try (var client = new Socket("127.0.0.1", proxy.port())) {
                client.setSoTimeout(2000); client.getOutputStream().write(42);
                assertThat(client.getInputStream().read()).isEqualTo(42);
                proxy.cut(); assertThat(client.getInputStream().read()).isEqualTo(-1);
            }
            try (var rejected = new Socket("127.0.0.1", proxy.port())) {
                rejected.setSoTimeout(2000); assertThat(rejected.getInputStream().read()).isEqualTo(-1);
            }
            proxy.restore();
            try (var client = new Socket("127.0.0.1", proxy.port())) {
                client.setSoTimeout(2000); client.getOutputStream().write(43);
                assertThat(client.getInputStream().read()).isEqualTo(43);
            }
            assertThat(proxy.forwardedConnections()).isEqualTo(2);
        } finally { executor.shutdownNow(); }
    }
}
