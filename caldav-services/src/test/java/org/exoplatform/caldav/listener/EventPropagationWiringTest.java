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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.EnumSet;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import org.exoplatform.agenda.constant.AgendaEventModificationType;
import org.exoplatform.agenda.model.AgendaEventModification;
import org.exoplatform.caldav.service.CaldavEventPropagationService;
import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.services.listener.Asynchronous;
import org.exoplatform.services.listener.Event;

/**
 * That the propagation is actually reached from agenda's broadcasts.
 *
 * <p>
 * The failure this exists for is not a wrong predicate: it is a correct service
 * nothing calls. That passes every unit test in the suite and does nothing
 * whatsoever in production, and it has happened on this codebase more than
 * once. So the listener under test here is not constructed from a name typed
 * into a test — it is <b>instantiated from the class the kernel configuration
 * declares</b>, for the event name the configuration binds it to, and then
 * driven with the payload agenda really broadcasts.
 */
public class EventPropagationWiringTest {

  private static final String                   CONFIGURATION = "conf/portal/configuration.xml";

  /** The package prefix that tells this add-on's registrations from every other one sharing the same file name. */
  private static final String                   ADDON_PACKAGE = "org.exoplatform.caldav.";

  private static final long                     EVENT         = 4242L;

  private ExoContainer                          container;

  private CaldavEventPropagationService         propagationService;

  /**
   * Establishes the container the kernel's asynchronous wrapper establishes,
   * and which the listeners refuse to run without.
   *
   * <p>
   * Held in a field on purpose: the kernel keeps only a weak reference to the
   * current container, so a mock nothing else points at can be collected
   * mid-test.
   */
  @BeforeEach
  public void establishAContainer() {
    container = mock(ExoContainer.class);
    ExoContainerContext.setCurrentContainer(container);
    propagationService = mock(CaldavEventPropagationService.class);
  }

  /**
   * Leaves no container behind for the next test in the JVM.
   */
  @AfterEach
  public void clearTheContainer() {
    ExoContainerContext.setCurrentContainer(null);
    container = null;
  }

  /**
   * An edit broadcast by agenda reaches the propagation service, through the
   * class the configuration declares for {@code exo.agenda.event.updated}.
   *
   * @throws Exception when the configuration cannot be read or the declared
   *           class cannot be instantiated
   */
  @Test
  public void theEditListenerTheConfigurationDeclaresReachesTheService() throws Exception {
    AgendaEventPropagationListener listener = listenerDeclaredFor("exo.agenda.event.updated");

    Set<AgendaEventModificationType> types = EnumSet.of(AgendaEventModificationType.UPDATED,
                                                        AgendaEventModificationType.START_DATE_UPDATED);
    listener.onEvent(broadcastOf(new AgendaEventModification(EVENT, 7L, 9L, types)));

    verify(propagationService).propagateUpdate(EVENT, types);
  }

  /**
   * A deletion broadcast by agenda reaches the propagation service, through the
   * class the configuration declares for {@code exo.agenda.event.deleted}.
   *
   * @throws Exception when the configuration cannot be read or the declared
   *           class cannot be instantiated
   */
  @Test
  public void theDeletionListenerTheConfigurationDeclaresReachesTheService() throws Exception {
    AgendaEventPropagationListener listener = listenerDeclaredFor("exo.agenda.event.deleted");

    listener.onEvent(broadcastOf(new AgendaEventModification(EVENT,
                                                             7L,
                                                             9L,
                                                             EnumSet.of(AgendaEventModificationType.DELETED))));

    verify(propagationService).propagateDeletion(EVENT);
  }

  /**
   * Both declared listeners are asynchronous. A synchronous one would talk to
   * as many calendar servers as there are attendees from inside the transaction
   * that saves the edit.
   *
   * @throws Exception when the configuration cannot be read
   */
  @Test
  public void bothDeclaredListenersAreAsynchronous() throws Exception {
    assertTrue(classDeclaredFor("exo.agenda.event.updated").isAnnotationPresent(Asynchronous.class),
               "the edit listener must not run inside the saving transaction");
    assertTrue(classDeclaredFor("exo.agenda.event.deleted").isAnnotationPresent(Asynchronous.class),
               "the deletion listener must not run inside the saving transaction");
  }

  /**
   * With no container established, nothing is attempted: the transactional write
   * that records what was pushed would be rolled back as a warning, and the
   * copies would look carried out while nothing was.
   *
   * @throws Exception when the configuration cannot be read
   */
  @Test
  public void withNoContainerNothingIsAttempted() throws Exception {
    AgendaEventPropagationListener listener = listenerDeclaredFor("exo.agenda.event.updated");
    ExoContainerContext.setCurrentContainer(null);

    listener.onEvent(broadcastOf(new AgendaEventModification(EVENT,
                                                             7L,
                                                             9L,
                                                             EnumSet.of(AgendaEventModificationType.UPDATED))));

    verify(propagationService, never()).propagateUpdate(org.mockito.ArgumentMatchers.anyLong(),
                                                        org.mockito.ArgumentMatchers.any());
  }

  // ---------------------------------------------------------------- fixtures

  /**
   * The listener class the kernel configuration binds to one agenda event,
   * instantiated and handed a mocked service.
   *
   * @param eventName the broadcast name the plugin is registered under
   * @return the listener, ready to be driven
   * @throws Exception when the class cannot be instantiated
   */
  private AgendaEventPropagationListener listenerDeclaredFor(String eventName) throws Exception {
    Class<?> declared = classDeclaredFor(eventName);
    Object listener = declared.getDeclaredConstructor().newInstance();
    assertTrue(listener instanceof AgendaEventPropagationListener,
               declared.getName() + " must be one of this add-on's propagation listeners");
    AgendaEventPropagationListener propagationListener = (AgendaEventPropagationListener) listener;
    // The setter is protected and this test lives in the listeners' own
    // package, which is what protected access is for. No reflection: a
    // reflective call would still pass if the whole hierarchy were replaced by
    // something unrelated carrying the same method name.
    propagationListener.setCaldavEventPropagationService(propagationService);
    return propagationListener;
  }

  /**
   * The class the kernel configuration declares for one agenda event.
   *
   * <p>
   * Every {@code conf/portal/configuration.xml} on the classpath is read, not
   * the first: the name is shared across the whole platform and several
   * add-ons ship one. The add-on's own registration is the one whose declared
   * type is a class of this add-on — nothing else in the platform can declare
   * one.
   *
   * @param eventName the broadcast name the plugin is registered under
   * @return the declared class
   * @throws Exception when the configuration cannot be read or the class is
   *           absent
   */
  private Class<?> classDeclaredFor(String eventName) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    factory.setNamespaceAware(false);
    Enumeration<URL> configurations = Thread.currentThread().getContextClassLoader().getResources(CONFIGURATION);
    assertTrue(configurations.hasMoreElements(), CONFIGURATION + " must be on the classpath");
    while (configurations.hasMoreElements()) {
      URL configuration = configurations.nextElement();
      Document document;
      try (InputStream stream = configuration.openStream()) {
        document = factory.newDocumentBuilder().parse(stream);
      }
      NodeList plugins = document.getElementsByTagName("component-plugin");
      for (int i = 0; i < plugins.getLength(); i++) {
        Element plugin = (Element) plugins.item(i);
        String type = textOf(plugin, "type");
        if (!eventName.equals(textOf(plugin, "name")) || type == null || !type.startsWith(ADDON_PACKAGE)) {
          continue;
        }
        assertEquals("addListener",
                     textOf(plugin, "set-method"),
                     eventName + " must be registered as a listener, not as some other plugin");
        return Class.forName(type);
      }
    }
    throw new AssertionError("No component-plugin of this add-on registered for " + eventName + " in " + CONFIGURATION
        + " — the service would be correct and nothing would ever call it");
  }

  /**
   * The text of a plugin's direct child element.
   *
   * @param plugin the component-plugin element
   * @param tag the child element's name
   * @return its trimmed text, or null when the child is absent
   */
  private String textOf(Element plugin, String tag) {
    NodeList children = plugin.getElementsByTagName(tag);
    return children.getLength() == 0 ? null : children.item(0).getTextContent().trim();
  }

  /**
   * The broadcast agenda actually makes: the modification as the source, and
   * nothing as the data.
   *
   * @param modification what agenda says moved
   * @return the kernel event
   */
  private Event<AgendaEventModification, Object> broadcastOf(AgendaEventModification modification) {
    return new Event<>("test", modification, null);
  }
}
