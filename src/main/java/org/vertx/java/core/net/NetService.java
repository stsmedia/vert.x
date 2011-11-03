package org.vertx.java.core.net;

import org.vertx.java.core.Handler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class NetService {

  private ConcurrentMap<ServerID, NetServer> servers = new ConcurrentHashMap<>();

  //TODO - for now synchronized - this needs to be improved

  public synchronized void listen(int port, String host, Handler<NetSocket> connectionHandler) {
    listen(port, host, new NetConfig(), connectionHandler);
  }

  public synchronized void listen(int port, String host, NetConfig config, Handler<NetSocket> connectionHandler) {
    ServerID id = new ServerID(port, host);
    NetServer server = servers.get(id);
    if (server == null) {
      server = new NetServer(config);
      NetServer oldServer = servers.putIfAbsent(id, server);
      if (oldServer != null) {
        server = oldServer;
        checkConfig(server, config);
      } else {
        server.listen(port, host);
      }
    } else {
      checkConfig(server, config);
    }
    server.addConnectHandler(connectionHandler);
  }

  public synchronized void unlisten(int port, String host, Handler<NetSocket> connectionHandler) {
    ServerID id = new ServerID(port, host);
    NetServer server = servers.get(id);
    if (server == null) {
      throw new IllegalArgumentException("No server listening on " + host + ":" + port);
    }
    server.removeConnectHandler(connectionHandler);
    if (server.hasHandlers()) {
      //TODO there is a race here - if close then open quickly on same host/port might get port conflict since close is async
      server.close();
      servers.remove(id);
    }
  }

  private void checkConfig(NetServer server, NetConfig config) {
    if (!config.equals(server.getConfig())) {
      throw new IllegalArgumentException("A server is already listening with different configuration");
    }
  }


  private static class ServerID {
    final int port;
    final String host;

    private ServerID(int port, String host) {
      this.port = port;
      this.host = host;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      ServerID serverID = (ServerID) o;

      if (port != serverID.port) return false;
      if (host != null ? !host.equals(serverID.host) : serverID.host != null) return false;

      return true;
    }

    @Override
    public int hashCode() {
      int result = port;
      result = 31 * result + (host != null ? host.hashCode() : 0);
      return result;
    }
  }


}
