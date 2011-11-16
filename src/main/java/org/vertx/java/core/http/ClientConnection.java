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
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.HOST;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.UPGRADE;

import java.io.IOException;
import java.net.URI;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.handler.codec.http.HttpChunkTrailer;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.vertx.java.core.Handler;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.http.ws.WebSocketClientHandshaker;
import org.vertx.java.core.http.ws.WebSocketClientHandshakerFactory;
import org.vertx.java.core.http.ws.WebSocketFrame;
import org.vertx.java.core.http.ws.WebSocketSpecificationVersion;
import org.vertx.java.core.http.ws.hybi00.WebSocket00FrameDecoder;
import org.vertx.java.core.http.ws.hybi00.WebSocket00FrameEncoder;
import org.vertx.java.core.http.ws.hybi10.WebSocket08FrameDecoder;
import org.vertx.java.core.http.ws.hybi10.WebSocket08FrameEncoder;
import org.vertx.java.core.logging.Logger;

class ClientConnection extends AbstractConnection {

	private static final Logger log = Logger.getLogger(ClientConnection.class);

	ClientConnection(HttpClient client, Channel channel, String hostHeader, boolean ssl, boolean keepAlive, long contextID, Thread th) {
		super(channel, contextID, th);
		this.client = client;
		this.hostHeader = hostHeader;
		this.ssl = ssl;
		this.keepAlive = keepAlive;
	}

	final HttpClient client;
	final String hostHeader;
	final boolean keepAlive;
	private final boolean ssl;

	private volatile HttpClientRequest currentRequest;
	// Requests can be pipelined so we need a queue to keep track of requests
	private final Queue<HttpClientRequest> requests = new ConcurrentLinkedQueue<>();
	private volatile HttpClientResponse currentResponse;
	private Websocket ws;

	void toWebSocket(final String uri, final Handler<Websocket> wsConnect) {
		toWebSocket(uri, wsConnect, WebSocketSpecificationVersion.V10);
	}

	void toWebSocket(final String uri, final Handler<Websocket> wsConnect, final WebSocketSpecificationVersion version) {
		if (ws != null) {
			throw new IllegalStateException("Already websocket");
		}

		try {
			final WebSocketClientHandshakerFactory clientHandshakerFactory = new WebSocketClientHandshakerFactory();
			final WebSocketClientHandshaker clientHandshaker = clientHandshakerFactory.newHandshaker(new URI(uri), version, null, false);

			HttpClientRequest req = new HttpClientRequest(client, "GET", uri, new Handler<HttpClientResponse>() {
				public void handle(HttpClientResponse resp) {
					clientHandshaker.beginOpeningHandshake(channel.getPipeline().getContext(channel.getPipeline().getFirst()), channel);
					try {
						if (clientHandshaker.isOpeningHandshakeCompleted()) {
							// We upgraded ok
							ChannelPipeline p = channel.getPipeline();
							if (version.equals(WebSocketSpecificationVersion.V00)) {
								p.replace("decoder", "wsdecoder", new WebSocket00FrameDecoder());
								p.replace("encoder", "wsencoder", new WebSocket00FrameEncoder());
							} else if (version.equals(WebSocketSpecificationVersion.V10)) {
								p.replace("decoder", "wsdecoder", new WebSocket08FrameDecoder(true, false));
								p.replace("encoder", "wsencoder", new WebSocket08FrameEncoder(true));
							} else {
								throw new IllegalStateException("Unsupported Web Socket version");
							}
							ws = new Websocket(uri, ClientConnection.this);
							wsConnect.handle(ws);
						} else {
							throw new IOException("Websocket connection attempt failed");
						}
					} catch (Exception e) {
						handleException(e);
					}
				}
			}, contextID, Thread.currentThread());
			clientHandshaker.generateRequest(req, (ssl ? "http://" : "https://") + hostHeader);
			setCurrentRequest(req);
			req.sendDirect(this);
		} catch (Exception e) {
			handleException(e);
		}
	}

	@Override
	public void close() {
		// if (ws != null) {
		// //Need to send 9 zeros to represent a close
		// byte[] bytes = new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0}; // Just to be
		// explicit
		// ChannelFuture future =
		// channel.write(ChannelBuffers.copiedBuffer(bytes));
		// future.addListener(ChannelFutureListener.CLOSE); // Close after it's
		// written
		// }
		client.returnConnection(this);
	}

	void internalClose() {
		// channel.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
		channel.close();
	}

	// TODO - combine these with same in ServerConnection and NetSocket

	void handleInterestedOpsChanged() {
		try {
			if (currentRequest != null) {
				if ((channel.getInterestOps() & Channel.OP_WRITE) == Channel.OP_WRITE) {
					setContextID();
					currentRequest.handleInterestedOpsChanged();
				}
			}
		} catch (Throwable t) {
			handleHandlerException(t);
		}
	}

	void handleResponse(HttpResponse resp) {
		HttpClientRequest req;
		if (resp.getStatus().getCode() == 100) {
			// If we get a 100 continue it will be followed by the real response
			// later, so we don't remove it yet
			req = requests.peek();
		} else {
			req = requests.poll();
		}
		if (req == null) {
			throw new IllegalStateException("No response handler");
		}
		setContextID();
		HttpClientResponse nResp = new HttpClientResponse(this, resp, req.th);
		currentResponse = nResp;
		req.handleResponse(nResp);
	}

	void handleResponseChunk(Buffer buff) {
		setContextID();
		try {
			currentResponse.handleChunk(buff);
		} catch (Throwable t) {
			handleHandlerException(t);
		}
	}

	void handleResponseEnd() {
		handleResponseEnd(null);
	}

	void handleResponseEnd(HttpChunkTrailer trailer) {
		try {
			currentResponse.handleEnd(trailer);
		} catch (Throwable t) {
			handleHandlerException(t);
		}
		if (!keepAlive) {
			close();
		}
	}

	void handleWsFrame(WebSocketFrame frame) {
		if (ws != null) {
			ws.handleFrame(frame);
		}
	}

	protected void handleClosed() {
		super.handleClosed();

		if (ws != null) {
			ws.handleClosed();
		}
	}

	protected long getContextID() {
		return super.getContextID();
	}

	protected void handleException(Exception e) {
		super.handleException(e);

		if (currentRequest != null) {
			currentRequest.handleException(e);
		}
		if (currentResponse != null) {
			currentResponse.handleException(e);
		}
	}

	protected void addFuture(Handler<Void> doneHandler, ChannelFuture future) {
		super.addFuture(doneHandler, future);
	}

	ChannelFuture write(Object obj) {
		return channel.write(obj);
	}

	void setCurrentRequest(HttpClientRequest req) {
		if (currentRequest != null) {
			throw new IllegalStateException("Connection is already writing a request");
		}
		this.currentRequest = req;
		this.requests.add(req);
	}

	void endRequest() {
		if (currentRequest == null) {
			throw new IllegalStateException("No write in progress");
		}
		currentRequest = null;

		if (keepAlive) {
			// Close just returns connection to the pool
			close();
		} else {
			// The connection gets closed after the response is received
		}
	}
}
