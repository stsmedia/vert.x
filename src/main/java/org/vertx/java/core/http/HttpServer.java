/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.vertx.java.core.http;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.UPGRADE;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Values.WEBSOCKET;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.SSLEngine;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelState;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.ChannelGroupFuture;
import org.jboss.netty.channel.group.ChannelGroupFutureListener;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioSocketChannel;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;
import org.jboss.netty.handler.ssl.SslHandler;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import org.vertx.java.core.Handler;
import org.vertx.java.core.http.ws.CloseWebSocketFrame;
import org.vertx.java.core.http.ws.PingWebSocketFrame;
import org.vertx.java.core.http.ws.PongWebSocketFrame;
import org.vertx.java.core.http.ws.WebSocketFrame;
import org.vertx.java.core.http.ws.WebSocketServerHandshaker;
import org.vertx.java.core.http.ws.WebSocketServerHandshakerFactory;
import org.vertx.java.core.internal.VertxInternal;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.net.NetServerBase;

/**
 * <p>
 * An HTTP server.
 * </p>
 * <p/>
 * <p>
 * The server supports both HTTP requests and HTML5 websockets and passes these
 * to the user via the appropriate handlers.
 * </p>
 * <p/>
 * <p>
 * An {@code HttpServer} instance can only be used from the event loop that
 * created it.
 * </p>
 * 
 * @author <a href="http://tfox.org">Tim Fox</a>
 * @author <a href="http://stsmedia.net">Stefan Schmidt</a>
 */
public class HttpServer extends NetServerBase {

	private static final Logger log = Logger.getLogger(HttpServer.class);

	private Handler<HttpServerRequest> requestHandler;
	private Handler<Websocket> wsHandler;
	private Map<Channel, ServerConnection> connectionMap = new ConcurrentHashMap<>();
	private ChannelGroup serverChannelGroup;
	private boolean listening;
	private WebSocketServerHandshaker webSocketHandShaker;

	/**
	 * Create an {@code HttpServer}
	 */
	public HttpServer() {
		super();
	}

	/**
	 * Set the request handler for the server to {@code requestHandler}. As HTTP
	 * requests are received by the server, instances of
	 * {@link HttpServerRequest} will be created and passed to this handler.
	 * 
	 * @return a reference to this, so methods can be chained.
	 */
	public HttpServer requestHandler(Handler<HttpServerRequest> requestHandler) {
		checkThread();
		this.requestHandler = requestHandler;
		return this;
	}

	/**
	 * Set the websocket handler for the server to {@code wsHandler}. If a
	 * websocket connect handshake is successful a new {@link Websocket}
	 * instance will be created and passed to the handler.
	 * 
	 * @return a reference to this, so methods can be chained.
	 */
	public HttpServer websocketHandler(Handler<Websocket> wsHandler) {
		checkThread();
		this.wsHandler = wsHandler;
		return this;
	}

	/**
	 * Tell the server to start listening on all interfaces and port
	 * {@code port}
	 * 
	 * @return a reference to this, so methods can be chained.
	 */
	public HttpServer listen(int port) {
		return listen(port, "0.0.0.0");
	}

	/**
	 * Tell the server to start listening on port {@code port} and host / ip
	 * address given by {@code host}.
	 * 
	 * @return a reference to this, so methods can be chained.
	 */
	public HttpServer listen(int port, String host) {
		checkThread();

		if (requestHandler == null && wsHandler == null) {
			throw new IllegalStateException("Set request or websocket handler first");
		}
		if (listening) {
			throw new IllegalStateException("Listen already called");
		}

		listening = true;

		serverChannelGroup = new DefaultChannelGroup("vertx-acceptor-channels");
		ChannelFactory factory = new NioServerSocketChannelFactory(VertxInternal.instance.getAcceptorPool(), VertxInternal.instance.getWorkerPool());
		ServerBootstrap bootstrap = new ServerBootstrap(factory);
		bootstrap.setOptions(connectionOptions);

		checkSSL();

		bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
			public ChannelPipeline getPipeline() {
				ChannelPipeline pipeline = Channels.pipeline();

				if (ssl) {
					SSLEngine engine = context.createSSLEngine();
					engine.setUseClientMode(false);
					switch (clientAuth) {
					case REQUEST: {
						engine.setWantClientAuth(true);
						break;
					}
					case REQUIRED: {
						engine.setNeedClientAuth(true);
						break;
					}
					case NONE: {
						engine.setNeedClientAuth(false);
						break;
					}
					}
					pipeline.addLast("ssl", new SslHandler(engine));
				}

				pipeline.addLast("decoder", new HttpRequestDecoder());
				pipeline.addLast("encoder", new HttpResponseEncoder());

				pipeline.addLast("chunkedWriter", new ChunkedWriteHandler()); // For large file / sendfile support
				pipeline.addLast("handler", new ServerHandler());
				return pipeline;
			}
		});

		try {
			Channel serverChannel = bootstrap.bind(new InetSocketAddress(InetAddress.getByName(host), port));
			serverChannelGroup.add(serverChannel);
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	public HttpServer setSSL(boolean ssl) {
		checkThread();
		return (HttpServer) super.setSSL(ssl);
	}

	/**
	 * {@inheritDoc}
	 */
	public HttpServer setKeyStorePath(String path) {
		checkThread();
		return (HttpServer) super.setKeyStorePath(path);
	}

	/**
	 * {@inheritDoc}
	 */
	public HttpServer setKeyStorePassword(String pwd) {
		checkThread();
		return (HttpServer) super.setKeyStorePassword(pwd);
	}

	/**
	 * {@inheritDoc}
	 */
	public HttpServer setTrustStorePath(String path) {
		checkThread();
		return (HttpServer) super.setTrustStorePath(path);
	}

	/**
	 * {@inheritDoc}
	 */
	public HttpServer setTrustStorePassword(String pwd) {
		checkThread();
		return (HttpServer) super.setTrustStorePassword(pwd);
	}

	/**
	 * {@inheritDoc}
	 */
	public HttpServer setClientAuthRequired(boolean required) {
		checkThread();
		return (HttpServer) super.setClientAuthRequired(required);
	}

	/**
	 * {@inheritDoc}
	 */
	public HttpServer setTcpNoDelay(boolean tcpNoDelay) {
		checkThread();
		return (HttpServer) super.setTcpNoDelay(tcpNoDelay);
	}

	/**
	 * {@inheritDoc}
	 */
	public HttpServer setSendBufferSize(int size) {
		checkThread();
		return (HttpServer) super.setSendBufferSize(size);
	}

	/**
	 * {@inheritDoc}
	 */
	public HttpServer setReceiveBufferSize(int size) {
		checkThread();
		return (HttpServer) super.setReceiveBufferSize(size);
	}

	/**
	 * {@inheritDoc}
	 */
	public HttpServer setTCPKeepAlive(boolean keepAlive) {
		checkThread();
		return (HttpServer) super.setTCPKeepAlive(keepAlive);
	}

	/**
	 * {@inheritDoc}
	 */
	public HttpServer setReuseAddress(boolean reuse) {
		checkThread();
		return (HttpServer) super.setReuseAddress(reuse);
	}

	/**
	 * {@inheritDoc}
	 */
	public HttpServer setSoLinger(boolean linger) {
		checkThread();
		return (HttpServer) super.setSoLinger(linger);
	}

	/**
	 * {@inheritDoc}
	 */
	public HttpServer setTrafficClass(int trafficClass) {
		checkThread();
		return (HttpServer) super.setTrafficClass(trafficClass);
	}

	/**
	 * Close the server. Any open HTTP connections will be closed.
	 */
	public void close() {
		checkThread();
		close(null);
	}

	/**
	 * Close the server. Any open HTTP connections will be closed.
	 * {@code doneHandler} will be called when the close is complete.
	 */
	public void close(final Handler<Void> doneHandler) {
		checkThread();
		for (ServerConnection conn : connectionMap.values()) {
			conn.internalClose();
		}
		if (doneHandler != null) {
			serverChannelGroup.close().addListener(new ChannelGroupFutureListener() {
				public void operationComplete(ChannelGroupFuture channelGroupFuture) throws Exception {
					VertxInternal.instance.executeOnContext(contextID, new Runnable() {
						public void run() {
							doneHandler.handle(null);
						}
					});
				}
			});
		}
	}

	public class ServerHandler extends SimpleChannelUpstreamHandler {

		@Override
		public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
			NioSocketChannel ch = (NioSocketChannel) e.getChannel();
			Object msg = e.getMessage();
			ServerConnection conn = connectionMap.get(ch);
			if (conn != null) {
				if (msg instanceof HttpRequest) {
					final HttpRequest request = (HttpRequest) msg;
					if (HttpHeaders.is100ContinueExpected(request)) {
						ch.write(new DefaultHttpResponse(HTTP_1_1, CONTINUE));
					}
					if (request.containsHeader(CONNECTION) && request.getHeader(CONNECTION).contains(UPGRADE) && WEBSOCKET.equalsIgnoreCase(request.getHeader(UPGRADE))) {
						webSocketOpeningHandshake(request, ctx, conn);
					} else {
						conn.handleMessage(msg);
					}
				} else if (msg instanceof WebSocketFrame) {
					final WebSocketFrame frame = (WebSocketFrame) msg;
					if (frame instanceof CloseWebSocketFrame) {
						webSocketClosingHandshake(ctx, (CloseWebSocketFrame) frame);
					} else if (frame instanceof PingWebSocketFrame) {
						ctx.getChannel().write(new PongWebSocketFrame(frame.getBinaryData()));
					} else {
						conn.handleMessage(msg);
					}
				} else {
					conn.handleMessage(msg);
				}
			}
		}
		
		private void webSocketClosingHandshake(final ChannelHandlerContext ctx, final CloseWebSocketFrame frame) {
			if (webSocketHandShaker != null) {
				log.debug("Performing closing WebSocket handshake");
				webSocketHandShaker.executeClosingHandshake(ctx, frame);
			}
		}
		
		private void webSocketOpeningHandshake(final HttpRequest request, final ChannelHandlerContext ctx, final ServerConnection conn) {
			log.debug("Performing opening WebSocket handshake");
			WebSocketServerHandshakerFactory handShakerFactory = new WebSocketServerHandshakerFactory(getWebSocketLocation(request, request.getUri()), null, false);
			webSocketHandShaker = handShakerFactory.newHandshaker(ctx, request);
			webSocketHandShaker.executeOpeningHandshake(ctx, request);
			conn.handleWebsocketConnect(request.getUri());
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
			final NioSocketChannel ch = (NioSocketChannel) e.getChannel();
			final ServerConnection conn = connectionMap.get(ch);
			ch.close();
			final Throwable t = e.getCause();
			if (conn != null && t instanceof Exception) {
				runOnCorrectThread(ch, new Runnable() {
					public void run() {
						conn.handleException((Exception) t);
					}
				});
			} else {
				t.printStackTrace();
			}
		}

		@Override
		public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) {
			final NioSocketChannel ch = (NioSocketChannel) e.getChannel();
			final long contextID = VertxInternal.instance.associateContextWithWorker(ch.getWorker());
			runOnCorrectThread(ch, new Runnable() {
				public void run() {
					final ServerConnection conn = new ServerConnection(ch, contextID, Thread.currentThread());
					conn.requestHandler(requestHandler);
					conn.wsHandler(wsHandler);
					connectionMap.put(ch, conn);
				}
			});
		}

		@Override
		public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) {
			final NioSocketChannel ch = (NioSocketChannel) e.getChannel();
			final ServerConnection conn = connectionMap.remove(ch);
			runOnCorrectThread(ch, new Runnable() {
				public void run() {
					conn.handleClosed();
					VertxInternal.instance.destroyContext(conn.getContextID());
				}
			});

		}

		@Override
		public void channelInterestChanged(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
			final NioSocketChannel ch = (NioSocketChannel) e.getChannel();
			final ServerConnection conn = connectionMap.get(ch);
			ChannelState state = e.getState();
			if (state == ChannelState.INTEREST_OPS) {
				runOnCorrectThread(ch, new Runnable() {
					public void run() {
						conn.handleInterestedOpsChanged();
					}
				});
			}
		}

		private String getWebSocketLocation(HttpRequest req, String path) {
			return "ws://" + req.getHeader(HttpHeaders.Names.HOST) + path;
		}
	}

	// TODO: remove after update to Netty 3.2.7
	public class Names {
		/**
		 * {@code "Sec-WebSocket-Version"}
		 */
		public static final String SEC_WEBSOCKET_VERSION = "Sec-WebSocket-Version";
		/**
		 * {@code "Sec-WebSocket-Key"}
		 */
		public static final String SEC_WEBSOCKET_KEY = "Sec-WebSocket-Key";
		/**
		 * {@code "Sec-WebSocket-Accept"}
		 */
		public static final String SEC_WEBSOCKET_ACCEPT = "Sec-WebSocket-Accept";
	}

}
