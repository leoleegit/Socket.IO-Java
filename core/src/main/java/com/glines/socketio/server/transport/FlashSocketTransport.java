/**
 * The MIT License
 * Copyright (c) 2010 Tad Glines
 *
 * Contributors: Ovea.com, Mycila.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.glines.socketio.server.transport;

import com.glines.socketio.server.*;
import com.glines.socketio.util.IO;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FlashSocketTransport extends AbstractTransport {

    public static final String PARAM_FLASHPOLICY_DOMAIN = "flashPolicyDomain";
    public static final String PARAM_FLASHPOLICY_SERVER_HOST = "flashPolicyServerHost";
    public static final String PARAM_FLASHPOLICY_SERVER_PORT = "flashPolicyServerPort";
    public static final String PARAM_FLASHPOLICY_PORTS = "flashPolicyPorts";

    private static final Logger LOGGER = Logger.getLogger(FlashSocketTransport.class.getName());
    private static final String FLASHFILE_NAME = "WebSocketMain.swf";
    private static final String FLASHFILE_PATH = TransportType.FLASH_SOCKET + "/" + FLASHFILE_NAME;

    private ServerSocketChannel flashPolicyServer;
    private ExecutorService executor = Executors.newCachedThreadPool();
    private Future<?> policyAcceptorThread;

    private int flashPolicyServerPort;
    private String flashPolicyServerHost;
    private String flashPolicyDomain;
    private String flashPolicyPorts;

    private Transport delegate;

    @Override
    public TransportType getType() {
        return TransportType.FLASH_SOCKET;
    }

    @Override
    public void init() throws TransportInitializationException {
        this.flashPolicyDomain = getConfig().getString(PARAM_FLASHPOLICY_DOMAIN);
        this.flashPolicyPorts = getConfig().getString(PARAM_FLASHPOLICY_PORTS);
        this.flashPolicyServerHost = getConfig().getString(PARAM_FLASHPOLICY_SERVER_HOST);
        this.flashPolicyServerPort = getConfig().getInt(PARAM_FLASHPOLICY_SERVER_PORT, 843);
        this.delegate = getConfig().getWebSocketTransport();

        if (LOGGER.isLoggable(Level.FINE))
            LOGGER.fine(getType() + " configuration:\n" +
                    " - flashPolicyDomain=" + flashPolicyDomain + "\n" +
                    " - flashPolicyPorts=" + flashPolicyPorts + "\n" +
                    " - flashPolicyServerHost=" + flashPolicyServerHost + "\n" +
                    " - flashPolicyServerPort=" + flashPolicyServerPort + "\n" +
                    " - websocket delegate=" + (delegate == null ? "<none>" : delegate.getClass().getName()));

        if (delegate == null)
            throw new TransportInitializationException("No WebSocket transport available for this transport: " + getClass().getName());

        if (flashPolicyServerHost != null && flashPolicyDomain != null && flashPolicyPorts != null) {
            try {
                startFlashPolicyServer();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    @Override
    public void destroy() {
        stopFlashPolicyServer();
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       Transport.InboundFactory inboundFactory,
                       SessionManager sessionFactory) throws IOException {

        String path = request.getPathInfo();
        if (path == null || path.length() == 0 || "/".equals(path)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid " + TransportType.FLASH_SOCKET + " transport request");
            return;
        }
        if (path.startsWith("/")) path = path.substring(1);
        String[] parts = path.split("/");
        if ("GET".equals(request.getMethod()) && TransportType.FLASH_SOCKET.toString().equals(parts[0])) {
            if (!FLASHFILE_PATH.equals(path)) {
                delegate.handle(request, response, inboundFactory, sessionFactory);
            } else {
                response.setContentType("application/x-shockwave-flash");
                InputStream is = this.getClass().getClassLoader().getResourceAsStream("com/glines/socketio/" + FLASHFILE_NAME);
                OutputStream os = response.getOutputStream();
                try {
                    IO.copy(is, os);
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, "Error writing " + FLASHFILE_NAME + ": " + e.getMessage(), e);
                }
            }
        } else {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid " + TransportType.FLASH_SOCKET + " transport request");
        }
    }

    /**
     * Starts this server, binding to the previously passed SocketAddress.
     */
    public void startFlashPolicyServer() throws IOException {
        final String POLICY_FILE_REQUEST = "<policy-file-request/>";
        flashPolicyServer = ServerSocketChannel.open();
        flashPolicyServer.socket().setReuseAddress(true);
        flashPolicyServer.socket().bind(new InetSocketAddress(flashPolicyServerHost, flashPolicyServerPort));
        flashPolicyServer.configureBlocking(true);

        // Spawn a new server acceptor thread, which must accept incoming
        // connections indefinitely - until a ClosedChannelException is thrown.
        policyAcceptorThread = executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        final SocketChannel serverSocket = flashPolicyServer.accept();
                        executor.submit(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    serverSocket.configureBlocking(true);
                                    Socket s = serverSocket.socket();
                                    StringBuilder request = new StringBuilder();
                                    InputStreamReader in = new InputStreamReader(s.getInputStream());
                                    int c;
                                    while ((c = in.read()) != 0 && request.length() <= POLICY_FILE_REQUEST.length()) {
                                        request.append((char) c);
                                    }
                                    if (request.toString().equalsIgnoreCase(POLICY_FILE_REQUEST) ||
                                            flashPolicyDomain != null && flashPolicyPorts != null) {
                                        PrintWriter out = new PrintWriter(s.getOutputStream());
                                        out.println("<cross-domain-policy><allow-access-from domain=\"" + flashPolicyDomain + "\" to-ports=\"" + flashPolicyPorts + "\" /></cross-domain-policy>");
                                        out.write(0);
                                        out.flush();
                                    }
                                    serverSocket.close();
                                } catch (IOException e) {
                                    LOGGER.log(Level.FINE, "startFlashPolicyServer: " + e.getMessage(), e);
                                } finally {
                                    try {
                                        serverSocket.close();
                                    } catch (IOException e) {
                                        // Ignore error on close.
                                    }
                                }
                            }
                        });
                    }
                } catch (ClosedChannelException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Server should not throw a misunderstood IOException", e);
                }
            }
        });
    }

    private void stopFlashPolicyServer() {
        if (flashPolicyServer != null) {
            try {
                flashPolicyServer.close();
            } catch (IOException e) {
                // Ignore
            }
        }
        if (policyAcceptorThread != null) {
            try {
                policyAcceptorThread.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException();
            } catch (ExecutionException e) {
                throw new IllegalStateException("Server thread threw an exception", e.getCause());
            }
            if (!policyAcceptorThread.isDone()) {
                throw new IllegalStateException("Server acceptor thread has not stopped.");
            }
        }
    }

}
