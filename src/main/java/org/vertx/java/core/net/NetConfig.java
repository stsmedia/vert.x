package org.vertx.java.core.net;

import java.util.HashMap;
import java.util.Map;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class NetConfig {

  private boolean ssl;
  private String keyStorePath;
  private String keyStorePassword;
  private String trustStorePath;
  private String trustStorePassword;
  private boolean trustAll;

  private boolean tcpNoDelay;
  private int sendBufferSize;
  private int receiveBufferSize;
  private boolean tcpKeepAlive;
  private boolean reuseAddress;
  private boolean soLinger;
  private int trafficClass;
  
  /**
   * If {@code tcpNoDelay} is set to {@code true} then <a href="http://en.wikipedia.org/wiki/Nagle's_algorithm">Nagle's algorithm</a>
   * will turned <b>off</b> for the TCP connections created by this instance.
   * @return a reference to this so multiple method calls can be chained together
   */
  public NetConfig setTcpNoDelay(boolean tcpNoDelay) {
    this.tcpNoDelay = tcpNoDelay;
    return this;
  }

  /**
   * Set the TCP send buffer size for connections created by this instance to {@code size} in bytes.
   * @return a reference to this so multiple method calls can be chained together
   */
  public NetConfig setSendBufferSize(int size) {
    this.sendBufferSize = size;
    return this;
  }

  /**
   * Set the TCP receive buffer size for connections created by this instance to {@code size} in bytes.
   * @return a reference to this so multiple method calls can be chained together
   */
  public NetConfig setReceiveBufferSize(int size) {
    this.receiveBufferSize = size;
    return this;
  }

  /**
   * Set the TCP keepAlive setting for connections created by this instance to {@code keepAlive}.
   * @return a reference to this so multiple method calls can be chained together
   */
  public NetConfig setTCPKeepAlive(boolean keepAlive) {
    this.tcpKeepAlive = keepAlive;
    return this;
  }

  /**
   * Set the TCP reuseAddress setting for connections created by this instance to {@code reuse}.
   * @return a reference to this so multiple method calls can be chained together
   */
  public NetConfig setReuseAddress(boolean reuse) {
    this.reuseAddress = reuse;
    return this;
  }

  /**
   * Set the TCP soLinger setting for connections created by this instance to {@code reuse}.
   * @return a reference to this so multiple method calls can be chained together
   */
  public NetConfig setSoLinger(boolean linger) {
    this.soLinger = linger;
    return this;
  }

  /**
   * Set the TCP trafficClass setting for connections created by this instance to {@code reuse}.
   * @return a reference to this so multiple method calls can be chained together
   */
  public NetConfig setTrafficClass(int trafficClass) {
    this.trafficClass = trafficClass;
    return this;
  }


  /**
   * If {@code ssl} is {@code true}, this signifies that any connections will be SSL connections.
   * @return A reference to this, so multiple invocations can be chained together.
   */
  public NetConfig setSSL(boolean ssl) {
    this.ssl = ssl;
    return this;
  }

  /**
   * Set the path to the SSL key store. This method should only be used in SSL mode, i.e. after {@link #setSSL(boolean)}
   * has been set to {@code true}.<p>
   * The SSL key store is a standard Java Key Store, and, if on the server side will contain the server certificate. If
   * on the client side it will contain the client certificate. Client certificates are only required if the server
   * requests client authentication.<p>
   * @return A reference to this, so multiple invocations can be chained together.
   */
  public NetConfig setKeyStorePath(String path) {
    this.keyStorePath = path;
    return this;
  }

  /**
   * Set the password for the SSL key store. This method should only be used in SSL mode, i.e. after {@link #setSSL(boolean)}
   * has been set to {@code true}.<p>
   * @return A reference to this, so multiple invocations can be chained together.
   */
  public NetConfig setKeyStorePassword(String pwd) {
    this.keyStorePassword = pwd;
    return this;
  }

  /**
   * Set the path to the SSL trust store. This method should only be used in SSL mode, i.e. after {@link #setSSL(boolean)}
   * has been set to {@code true}.<p>
   * The trust store is a standard Java Key Store, and, if on the server side it should contain the certificates of
   * any clients that the server trusts - this is only necessary if client authentication is enabled. If on the
   * client side, it should contain the certificates of any servers the client trusts.
   * If you wish the client to trust all server certificates you can use the {@link NetClientBase#setTrustAll(boolean)} method.<p>
   * @return A reference to this, so multiple invocations can be chained together.
   */
  public NetConfig setTrustStorePath(String path) {
    this.trustStorePath = path;
    return this;
  }

  /**
   * Set the password for the SSL trust store. This method should only be used in SSL mode, i.e. after {@link #setSSL(boolean)}
   * has been set to {@code true}.<p>
   * @return A reference to this, so multiple invocations can be chained together.
   */
  public NetConfig setTrustStorePassword(String pwd) {
    this.trustStorePassword = pwd;
    return this;
  }

  boolean isSsl() {
    return ssl;
  }

  String getKeyStorePath() {
    return keyStorePath;
  }

  String getKeyStorePassword() {
    return keyStorePassword;
  }

  String getTrustStorePath() {
    return trustStorePath;
  }

  String getTrustStorePassword() {
    return trustStorePassword;
  }

  boolean isTrustAll() {
    return trustAll;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    NetConfig netConfig = (NetConfig) o;

    if (receiveBufferSize != netConfig.receiveBufferSize) return false;
    if (reuseAddress != netConfig.reuseAddress) return false;
    if (sendBufferSize != netConfig.sendBufferSize) return false;
    if (soLinger != netConfig.soLinger) return false;
    if (ssl != netConfig.ssl) return false;
    if (tcpKeepAlive != netConfig.tcpKeepAlive) return false;
    if (tcpNoDelay != netConfig.tcpNoDelay) return false;
    if (trafficClass != netConfig.trafficClass) return false;
    if (trustAll != netConfig.trustAll) return false;
    if (keyStorePassword != null ? !keyStorePassword.equals(netConfig.keyStorePassword) : netConfig.keyStorePassword != null)
      return false;
    if (keyStorePath != null ? !keyStorePath.equals(netConfig.keyStorePath) : netConfig.keyStorePath != null)
      return false;
    if (trustStorePassword != null ? !trustStorePassword.equals(netConfig.trustStorePassword) : netConfig.trustStorePassword != null)
      return false;
    if (trustStorePath != null ? !trustStorePath.equals(netConfig.trustStorePath) : netConfig.trustStorePath != null)
      return false;

    return true;
  }

  @Override
  public int hashCode() {
    int result = (ssl ? 1 : 0);
    result = 31 * result + (keyStorePath != null ? keyStorePath.hashCode() : 0);
    result = 31 * result + (keyStorePassword != null ? keyStorePassword.hashCode() : 0);
    result = 31 * result + (trustStorePath != null ? trustStorePath.hashCode() : 0);
    result = 31 * result + (trustStorePassword != null ? trustStorePassword.hashCode() : 0);
    result = 31 * result + (trustAll ? 1 : 0);
    result = 31 * result + (tcpNoDelay ? 1 : 0);
    result = 31 * result + sendBufferSize;
    result = 31 * result + receiveBufferSize;
    result = 31 * result + (tcpKeepAlive ? 1 : 0);
    result = 31 * result + (reuseAddress ? 1 : 0);
    result = 31 * result + (soLinger ? 1 : 0);
    result = 31 * result + trafficClass;
    return result;
  }
}
