/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.exoplatform.caldav.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * EXO-89653. The listener exists only if the Kernel registers it on the login
 * event: a class nobody binds attaches nobody, and no unit test of the class
 * would notice. Read from the shipped configuration, as the propagation wiring
 * test does.
 */
class CaldavManagedLoginWiringTest {

  @Test
  void theLoginListenerIsBoundToTheRegistryRegisterEvent() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    factory.setNamespaceAware(false);
    // Several jars ship a conf/portal/configuration.xml: every one is read, and
    // the listener is looked for by its class, as the propagation wiring test does.
    Enumeration<URL> configurations = Thread.currentThread().getContextClassLoader().getResources("conf/portal/configuration.xml");
    assertTrue(configurations.hasMoreElements(), "conf/portal/configuration.xml must be on the classpath");
    String boundTo = null;
    while (configurations.hasMoreElements()) {
      Document document;
      try (InputStream stream = configurations.nextElement().openStream()) {
        document = factory.newDocumentBuilder().parse(stream);
      }
      NodeList plugins = document.getElementsByTagName("component-plugin");
      for (int i = 0; i < plugins.getLength(); i++) {
        Element plugin = (Element) plugins.item(i);
        if ("org.exoplatform.caldav.listener.CaldavManagedLoginListener".equals(text(plugin, "type"))) {
          boundTo = text(plugin, "name");
          assertEquals("addListener", text(plugin, "set-method"));
          // The same plugin under another component would pass the checks above
          // and bind nothing.
          assertEquals("org.exoplatform.services.listener.ListenerService",
                       text((Element) plugin.getParentNode(), "target-component"),
                       "the login listener must be a plugin of the ListenerService");
        }
      }
    }
    assertEquals("exo.core.security.ConversationRegistry.register", boundTo,
                 "the managed login listener must be bound to the session-registration event");
  }

  private static String text(Element plugin, String tag) {
    NodeList nodes = plugin.getElementsByTagName(tag);
    return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
  }
}
