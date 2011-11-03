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

package org.vertx.java.core.net;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
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
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.ChannelGroupFuture;
import org.jboss.netty.channel.group.ChannelGroupFutureListener;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioSocketChannel;
import org.jboss.netty.channel.socket.nio.NioWorker;
import org.jboss.netty.channel.socket.nio.NioWorkerPool;
import org.jboss.netty.handler.ssl.SslHandler;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.internal.VertxInternal;
import org.vertx.java.core.logging.Logger;

import javax.net.ssl.SSLEngine;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * <p>Encapsulates a server that understands TCP or SSL.</p>
 *
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class NetServer extends NetServerBase {

  private static final Logger log = Logger.getLogger(NetServer.class);

  private Map<Channel, NetSocket> socketMap = new ConcurrentHashMap();
  private ChannelGroup serverChannelGroup;
  private boolean listening;

  private NetServerWorkerPool availableWorkers = new NetServerWorkerPool();
  private Map<NioWorker, Handlers> handlerMap = new ConcurrentHashMap<>();

  // This is currently a hack
  private static class NetServerWorkerPool extends NioWorkerPool {

    NetServerWorkerPool() {
      super(0, null);
    }

    int pos;

    List<NioWorker> workers = new ArrayList<>();

    public synchronized NioWorker nextWorker() {
      NioWorker worker = workers.get(pos);
      pos++;
      checkPos();
      return worker;
    }

    public synchronized void addWorker(NioWorker worker) {
      if (!workers.contains(worker)) {
        workers.add(worker);
      }
    }

    public synchronized void removeWorker(NioWorker worker) {
      workers.remove(worker);
      checkPos();
    }

    private void checkPos() {
      if (pos == workers.size()) {
        pos = 0;
      }
    }
  }

  private static class Handlers {
    int pos;
    final List<HandlerHolder> list = new ArrayList<>();
    HandlerHolder chooseHandler() {
      HandlerHolder handler = list.get(pos);
      pos++;
      checkPos();
      return handler;
    }

    void addHandler(HandlerHolder handler) {
      list.add(handler);
    }

    boolean removeHandler(HandlerHolder handler) {
      if (list.remove(handler)) {
        checkPos();
        return true;
      } else {
        return false;
      }
    }

    boolean isEmpty() {
      return list.isEmpty();
    }

    void checkPos() {
      if (pos == list.size()) {
        pos = 0;
      }
    }
  }

  private static class HandlerHolder {
    final long contextID;
    final Handler<NetSocket> handler;

    HandlerHolder(long contextID, Handler<NetSocket> handler) {
      this.contextID = contextID;
      this.handler = handler;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      HandlerHolder that = (HandlerHolder) o;

      if (contextID != that.contextID) return false;
      if (handler != null ? !handler.equals(that.handler) : that.handler != null) return false;

      return true;
    }

    @Override
    public int hashCode() {
      int result = (int) (contextID ^ (contextID >>> 32));
      result = 31 * result + (handler != null ? handler.hashCode() : 0);
      return result;
    }
  }


  private synchronized HandlerHolder chooseHandler(NioWorker worker) {
    Handlers handlers = handlerMap.get(worker);
    if (handlers == null) {
      return null;
    }
    return handlers.chooseHandler();
  }

  public synchronized void addConnectHandler(Handler<NetSocket> handler) {
    NioWorker worker = VertxInternal.instance.getWorkerForContextID(Vertx.instance.getContextID());
    availableWorkers.addWorker(worker);
    Handlers handlers = handlerMap.get(worker);
    if (handlers == null) {
      handlers = new Handlers();
      handlerMap.put(worker, handlers);
    }
    handlers.addHandler(new HandlerHolder(Vertx.instance.getContextID(), handler));
  }

  public synchronized void removeConnectHandler(Handler<NetSocket> handler) {
    NioWorker worker = VertxInternal.instance.getWorkerForContextID(Vertx.instance.getContextID());
    Handlers handlers = handlerMap.get(worker);
    if (!handlers.removeHandler(new HandlerHolder(Vertx.instance.getContextID(), handler))) {
      throw new IllegalStateException("Can't find handler");
    }
    if (handlers.isEmpty()) {
      handlerMap.remove(worker);
      availableWorkers.removeWorker(worker);
    }
  }

  public synchronized boolean hasHandlers() {
    return availableWorkers != null;
  }

  /**
   * Create a new NetServer instance.
   */
  public NetServer() {
    super();
  }

  private NetConfig config;

  public NetServer(NetConfig config) {
    super();
    this.config = config;

    //TODO configure the server with the config
  }

  public NetConfig getConfig() {
    return config;
  }

//  /**
//   * Supply a connect handler for this server. The server can only have at most one connect handler at any one time.
//   * As the server accepts TCP or SSL connections it creates an instance of {@link NetSocket} and passes it to the
//   * connect handler.
//   * @return a reference to this so multiple method calls can be chained together
//   */



  // Keep this in for now so it compiles
  public NetServer connectHandler(Handler<NetSocket> connectHandler) {
//    checkThread();
//    this.connectHandler = connectHandler;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  public NetServer setSSL(boolean ssl) {
    checkThread();
    return (NetServer)super.setSSL(ssl);
  }

  /**
   * {@inheritDoc}
   */
  public NetServer setKeyStorePath(String path) {
    checkThread();
    return (NetServer)super.setKeyStorePath(path);
  }

  /**
   * {@inheritDoc}
   */
  public NetServer setKeyStorePassword(String pwd) {
    checkThread();
    return (NetServer)super.setKeyStorePassword(pwd);
  }

  /**
   * {@inheritDoc}
   */
  public NetServer setTrustStorePath(String path) {
    checkThread();
    return (NetServer)super.setTrustStorePath(path);
  }

  /**
   * {@inheritDoc}
   */
  public NetServer setTrustStorePassword(String pwd) {
    checkThread();
    return (NetServer)super.setTrustStorePassword(pwd);
  }

  /**
   * {@inheritDoc}
   */
  public NetServer setClientAuthRequired(boolean required) {
    checkThread();
    return (NetServer)super.setClientAuthRequired(required);
  }

  /**
   * {@inheritDoc}
   */
  public NetServer setTcpNoDelay(boolean tcpNoDelay) {
    checkThread();
    return (NetServer)super.setTcpNoDelay(tcpNoDelay);
  }

  /**
   * {@inheritDoc}
   */
  public NetServer setSendBufferSize(int size) {
    checkThread();
    return (NetServer)super.setSendBufferSize(size);
  }

  /**
   * {@inheritDoc}
   */
  public NetServer setReceiveBufferSize(int size) {
    checkThread();
    return (NetServer)super.setReceiveBufferSize(size);
  }

  /**
   * {@inheritDoc}
   */
  public NetServer setTCPKeepAlive(boolean keepAlive) {
    checkThread();
    return (NetServer)super.setTCPKeepAlive(keepAlive);
  }

  /**
   * {@inheritDoc}
   */
  public NetServer setReuseAddress(boolean reuse) {
    checkThread();
    return (NetServer)super.setReuseAddress(reuse);
  }

  /**
   * {@inheritDoc}
   */
  public NetServer setSoLinger(boolean linger) {
    checkThread();
    return (NetServer)super.setSoLinger(linger);
  }

  /**
   * {@inheritDoc}
   */
  public NetServer setTrafficClass(int trafficClass) {
    checkThread();
    return (NetServer)super.setTrafficClass(trafficClass);
  }

  /**
   * Instruct the server to listen for incoming connections on the specified {@code port} and all available interfaces.
   * @return a reference to this so multiple method calls can be chained together
   */
  public NetServer listen(int port) {
    return listen(port, "0.0.0.0");
  }

  /**
   * Instruct the server to listen for incoming connections on the specified {@code port} and {@code host}. {@code host} can
   * be a host name or an IP address.
   * @return a reference to this so multiple method calls can be chained together
   */
  public NetServer listen(int port, String host) {
    checkThread();
//    if (connectHandler == null) {
//      throw new IllegalStateException("Set connect handler first");
//    }
    if (listening) {
      throw new IllegalStateException("Listen already called");
    }
    listening = true;

    serverChannelGroup = new DefaultChannelGroup("vertx-acceptor-channels");

    ChannelFactory factory =
        new NioServerSocketChannelFactory(
            VertxInternal.instance.getAcceptorPool(),
            availableWorkers);
    ServerBootstrap bootstrap = new ServerBootstrap(factory);

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
        pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());  // For large file / sendfile support
        pipeline.addLast("handler", new ServerHandler());
        return pipeline;
      }
    });

    bootstrap.setOptions(connectionOptions);

    try {
      //TODO - currently bootstrap.bind is blocking - need to make it non blocking by not using bootstrap directly
      Channel serverChannel = bootstrap.bind(new InetSocketAddress(InetAddress.getByName(host), port));
      serverChannelGroup.add(serverChannel);
      log.info("Net server listening on " + host + ":" + port);
    } catch (UnknownHostException e) {
      e.printStackTrace();
    }

    return this;
  }

  /**
   * Close the server. This will close any currently open connections.
   */
  public void close() {
    close(null);
  }

  /**
   * Close the server. This will close any currently open connections. The event handler {@code done} will be called
   * when the close is complete.
   */
  public void close(final Handler<Void> done) {
    checkThread();

    long cid = Vertx.instance.getContextID();

    for (NetSocket sock : socketMap.values()) {
      sock.internalClose();
    }

    // We need to reset it since sock.internalClose() above can call into the close handlers of sockets on the same thread
    // which can cause context id for the thread to change!

    VertxInternal.instance.setContextID(cid);

    if (done != null) {
      final Long contextID = Vertx.instance.getContextID();
      serverChannelGroup.close().addListener(new ChannelGroupFutureListener() {
        public void operationComplete(ChannelGroupFuture channelGroupFuture) throws Exception {

          Runnable runner = new Runnable() {
            public void run() {
              listening = false;
              done.handle(null);
            }
          };
          VertxInternal.instance.executeOnContext(contextID, runner);
        }
      });
    }
  }

  private class ServerHandler extends SimpleChannelHandler {

    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) {
      final NioSocketChannel ch = (NioSocketChannel) e.getChannel();

      NioWorker worker = ch.getWorker();

      //Choose a handler
      final HandlerHolder handler = chooseHandler(worker);

      if (handler == null) {
        //Ignore
        return;
      }

      VertxInternal.instance.executeOnContext(handler.contextID, new Runnable() {
        public void run() {
          VertxInternal.instance.setContextID(handler.contextID);
          NetSocket sock = new NetSocket(ch, handler.contextID, Thread.currentThread());
          socketMap.put(ch, sock);
          handler.handler.handle(sock);
        }
      });

    }

    @Override
    public void channelInterestChanged(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
      final NioSocketChannel ch = (NioSocketChannel) e.getChannel();
      final NetSocket sock = socketMap.get(ch);
      ChannelState state = e.getState();
      if (state == ChannelState.INTEREST_OPS) {
        runOnCorrectThread(ch, new Runnable() {
          public void run() {
            sock.handleInterestedOpsChanged();
          }
        });
      }
    }

    @Override
    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) {
      final NioSocketChannel ch = (NioSocketChannel) e.getChannel();
      final NetSocket sock = socketMap.remove(ch);
      if (sock != null) {
        runOnCorrectThread(ch, new Runnable() {
          public void run() {
            sock.handleClosed();
            VertxInternal.instance.destroyContext(sock.getContextID());
          }
        });
      }
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) {
      Channel ch = e.getChannel();
      NetSocket sock = socketMap.get(ch);
      ChannelBuffer buff = (ChannelBuffer) e.getMessage();
      sock.handleDataReceived(new Buffer(buff.slice()));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
      final NioSocketChannel ch = (NioSocketChannel) e.getChannel();
      final NetSocket sock = socketMap.get(ch);
      ch.close();
      final Throwable t = e.getCause();
      if (sock != null && t instanceof Exception) {
        runOnCorrectThread(ch, new Runnable() {
          public void run() {
            sock.handleException((Exception) t);
          }
        });
      } else {
        t.printStackTrace();
      }
    }
  }
}
