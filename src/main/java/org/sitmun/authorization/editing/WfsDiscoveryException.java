package org.sitmun.authorization.editing;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.xml.stream.XMLStreamException;
import org.hibernate.LazyInitializationException;
import org.springframework.dao.DataAccessException;

/** Safe diagnostic tokens only: never retains a remote message, URL, body or cause. */
public final class WfsDiscoveryException extends XMLStreamException {
  public enum Stage {
    CONFIGURATION, CAPABILITIES_REQUEST, CAPABILITIES_PARSE, SCHEMA_REQUEST, SCHEMA_PARSE
  }

  public enum Category {
    DNS, CONNECTION, TIMEOUT, IO, XML, LAZY_INITIALIZATION, DATA_ACCESS, CONFIGURATION, UNEXPECTED
  }

  private final Stage stage;
  private final Category category;

  private WfsDiscoveryException(Stage stage, Category category) {
    super("WFS discovery failed: " + stage + "/" + category);
    this.stage = stage;
    this.category = category;
  }

  public Stage stage() {
    return stage;
  }

  public Category category() {
    return category;
  }

  public static WfsDiscoveryException at(Stage stage, Exception failure) {
    if (failure instanceof WfsDiscoveryException discovery) return discovery;
    Category category;
    if (failure instanceof UnknownHostException) category = Category.DNS;
    else if (failure instanceof SocketTimeoutException) category = Category.TIMEOUT;
    else if (failure instanceof ConnectException) category = Category.CONNECTION;
    else if (failure instanceof LazyInitializationException) category = Category.LAZY_INITIALIZATION;
    else if (failure instanceof DataAccessException) category = Category.DATA_ACCESS;
    else if (failure instanceof IOException) category = Category.IO;
    else if (failure instanceof XMLStreamException) category = Category.XML;
    else if (failure instanceof IllegalArgumentException) category = Category.CONFIGURATION;
    else category = Category.UNEXPECTED;
    return new WfsDiscoveryException(stage, category);
  }
}
