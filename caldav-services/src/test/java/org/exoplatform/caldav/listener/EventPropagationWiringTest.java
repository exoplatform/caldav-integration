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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
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
import org.mockito.MockedStatic;
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
   * Leaves no container bound to this thread for the next test.
   *
   * <p>
   * This thread's binding, and only that: the static {@code topContainer} an
   * earlier test may have pinned is out of reach of any public setter, which is
   * the whole reason {@link #withNoContainerNothingIsAttempted()} states its
   * condition instead of arranging it.
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
   * A creation broadcast by agenda reaches the propagation service, through the
   * class the configuration declares for {@code exo.agenda.event.created}.
   *
   * <p>
   * The registration is the whole fix of EXO-89754, and it is the one part of
   * it no unit test of any service could see: the propagation service was
   * correct and nothing called it, so a new meeting reached nobody's calendar
   * while every other test in the suite stayed green. Reverting the
   * {@code component-plugin} in {@code configuration.xml} must fail here.
   *
   * <p>
   * The modifier the broadcast carries — {@code 9} in this payload — is handed
   * over with the event, because the author's own copy is written by their
   * browser and the service skips them. Dropping it here would put the
   * collision back.
   *
   * @throws Exception when the configuration cannot be read or the declared
   *           class cannot be instantiated
   */
  @Test
  public void theCreationListenerTheConfigurationDeclaresReachesTheService() throws Exception {
    AgendaEventPropagationListener listener = listenerDeclaredFor("exo.agenda.event.created");

    listener.onEvent(broadcastOf(new AgendaEventModification(EVENT,
                                                             7L,
                                                             9L,
                                                             EnumSet.of(AgendaEventModificationType.ADDED))));

    verify(propagationService).propagateCreation(EVENT, 9L);
  }

  /**
   * A creation asks for a creation and nothing else.
   *
   * <p>
   * The failure mode this pins is the double push. A creation already reaches
   * this add-on twice — agenda auto-accepts the organiser from inside the
   * {@code created} broadcast, which makes it emit {@code responseSaved}, which
   * this add-on also listens to — so the created listener adding an update of
   * its own on top would be a third write of the same object with a fresh
   * {@code DTSTAMP}, which is the churn EXO-89716 spent a day removing.
   *
   * @throws Exception when the configuration cannot be read or the declared
   *           class cannot be instantiated
   */
  @Test
  public void aCreationIsNotAlsoCarriedAsAnEdit() throws Exception {
    AgendaEventPropagationListener listener = listenerDeclaredFor("exo.agenda.event.created");

    listener.onEvent(broadcastOf(new AgendaEventModification(EVENT,
                                                             7L,
                                                             9L,
                                                             EnumSet.of(AgendaEventModificationType.ADDED))));

    verify(propagationService, never()).propagateUpdate(anyLong(), any());
    verify(propagationService, never()).propagateDeletion(anyLong());
  }

  /**
   * A date poll is not a scheduled meeting, and no copy of one is ever pushed.
   * Agenda broadcasts it under a name of its own, and this add-on deliberately
   * does not subscribe to that name — declaring a listener for it would make
   * this add-on write a copy of something that has no time yet.
   *
   * @throws Exception when the configuration cannot be read
   */
  @Test
  public void aDatePollCreationIsNotSubscribedTo() throws Exception {
    assertNull(declaredFor("exo.agenda.event.poll.created"),
               "no copy of a date poll is ever pushed, so nothing must listen to its creation");
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
   * Every declared listener is asynchronous. A synchronous one would talk to as
   * many calendar servers as there are attendees from inside the transaction
   * that saves the event.
   *
   * <p>
   * On the creation listener it does a second job: the kernel's listener
   * executor is a single thread, so being asynchronous is what makes the
   * creation and the organiser's auto-accepted answer — which agenda emits from
   * inside this very broadcast — run one after the other rather than at once.
   *
   * @throws Exception when the configuration cannot be read
   */
  @Test
  public void everyDeclaredListenerIsAsynchronous() throws Exception {
    assertTrue(classDeclaredFor("exo.agenda.event.created").isAnnotationPresent(Asynchronous.class),
               "the creation listener must not run inside the saving transaction");
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
   * <b>Why the absence is mocked rather than arranged.</b>
   *
   * <p>
   * {@code setCurrentContainer(null)} does not establish this condition, which
   * is what made this test pass here and fail on CI against the same tree. The
   * kernel resolves the container in two steps: a per-thread
   * {@code ThreadLocal} first, and — when that is empty — a
   * <b>static, JVM-wide {@code topContainer}</b> field
   * ({@code ExoContainerContext.getCurrentContainerIfPresent}). Only the first
   * of those is a setter away; nothing public clears the second.
   *
   * <p>
   * And it gets set behind our backs. Any test in the fork that drives a method
   * carrying {@code @ExoTransactional} — {@code CaldavSyncSweepJobTest} does,
   * through the woven aspect's
   * {@code ExoContainerContext.getService(EntityManagerService.class)} — walks
   * {@code getCurrentContainer()} into {@code RootContainer.getInstance()},
   * which pins a root container into that static for the rest of the JVM. From
   * then on no thread can see "no container" again, so whether this assertion
   * held came down to Surefire's class order, which differs between a developer
   * machine and CI.
   *
   * <p>
   * So the condition is stated rather than arranged, with the same
   * {@code mockStatic} this add-on's tests already use for this class. Scoped
   * to the block and to this thread, it neither reads what ran before nor
   * leaves anything behind for what runs next.
   *
   * @throws Exception when the configuration cannot be read
   */
  @Test
  public void withNoContainerNothingIsAttempted() throws Exception {
    AgendaEventPropagationListener listener = listenerDeclaredFor("exo.agenda.event.updated");

    try (MockedStatic<ExoContainerContext> containerContext = mockStatic(ExoContainerContext.class)) {
      containerContext.when(ExoContainerContext::getCurrentContainerIfPresent).thenReturn(null);

      listener.onEvent(broadcastOf(new AgendaEventModification(EVENT,
                                                               7L,
                                                               9L,
                                                               EnumSet.of(AgendaEventModificationType.UPDATED))));
    }

    verify(propagationService, never()).propagateUpdate(anyLong(), any());
  }

  /**
   * A propagation that blows up does not blow up the edit.
   *
   * <p>
   * The listener runs on agenda's asynchronous thread, but it still runs inside
   * the kernel's own {@code RunListener} — an exception escaping here is logged
   * by the kernel against the broadcast, and on a synchronous listener would
   * fail the save. The edit is already recorded in eXo and must stand whatever
   * any calendar server says; what is owed to each copy has been written down
   * before this point, so the sweep settles what the attempt did not.
   *
   * <p>
   * {@code LinkageError} rather than an exception, deliberately: one escaped a
   * {@code catch (RuntimeException)} on this very code path once and took a
   * whole sweep down with it, and an {@code Error} is what a
   * {@code catch (Exception)} would let past.
   *
   * @throws Exception when the configuration cannot be read
   */
  @Test
  public void aPropagationThatFailsDoesNotFailTheEdit() throws Exception {
    AgendaEventPropagationListener listener = listenerDeclaredFor("exo.agenda.event.updated");
    Set<AgendaEventModificationType> types = EnumSet.of(AgendaEventModificationType.UPDATED);
    doThrow(new NoSuchMethodError("a half-assembled classpath")).when(propagationService)
                                                               .propagateUpdate(anyLong(), any());

    assertDoesNotThrow(() -> listener.onEvent(broadcastOf(new AgendaEventModification(EVENT, 7L, 9L, types))));

    verify(propagationService).propagateUpdate(EVENT, types);
  }

  /**
   * A bridge that cannot hand back the service does not throw out of the
   * listener.
   *
   * <p>
   * Resolving the bean walks a class graph across the Kernel/Spring bridge, and
   * a container assembled without part of it answers with an {@code Error}
   * rather than an exception. The call that does it sits <b>outside</b> the
   * {@code try} that guards the propagation itself, so this catch is the only
   * thing between that failure and an exception escaping into agenda's own
   * broadcast — where, on a synchronous listener, it would fail the save of an
   * edit that has nothing to do with calendars.
   *
   * <p>
   * The copies stay as they are and nothing records that they should not, which
   * is the one arrear this design does not cover: the record is written by the
   * service, and the service is what could not be reached.
   *
   * @throws Exception when the configuration cannot be read
   */
  @Test
  public void aBridgeThatCannotHandBackTheServiceDoesNotThrowOutOfTheListener() throws Exception {
    // Deliberately NOT handed a service, so the listener has to go and resolve
    // one; listenerDeclaredFor injects one, which would skip the whole path.
    AgendaEventPropagationListener listener =
                                            (AgendaEventPropagationListener) classDeclaredFor("exo.agenda.event.updated").getDeclaredConstructor()
                                                                                                                        .newInstance();

    try (MockedStatic<ExoContainerContext> containerContext = mockStatic(ExoContainerContext.class)) {
      containerContext.when(ExoContainerContext::getCurrentContainerIfPresent).thenReturn(container);
      containerContext.when(() -> ExoContainerContext.getService(CaldavEventPropagationService.class))
                      .thenThrow(new NoClassDefFoundError("a half-assembled container"));

      assertDoesNotThrow(() -> listener.onEvent(broadcastOf(new AgendaEventModification(EVENT,
                                                                                        7L,
                                                                                        9L,
                                                                                        EnumSet.of(AgendaEventModificationType.UPDATED)))));
    }

    verify(propagationService, never()).propagateUpdate(anyLong(), any());
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
   * The class the kernel configuration declares for one agenda event, insisting
   * that there is one.
   *
   * @param eventName the broadcast name the plugin is registered under
   * @return the declared class
   * @throws Exception when the configuration cannot be read or the class is
   *           absent
   */
  private Class<?> classDeclaredFor(String eventName) throws Exception {
    Class<?> declared = declaredFor(eventName);
    assertNotNull(declared,
                  "No component-plugin of this add-on registered for " + eventName + " in " + CONFIGURATION
                      + " — the service would be correct and nothing would ever call it");
    return declared;
  }

  /**
   * The class the kernel configuration declares for one agenda event, or null
   * when this add-on registers nothing for it.
   *
   * <p>
   * Every {@code conf/portal/configuration.xml} on the classpath is read, not
   * the first: the name is shared across the whole platform and several
   * add-ons ship one. The add-on's own registration is the one whose declared
   * type is a class of this add-on — nothing else in the platform can declare
   * one.
   *
   * <p>
   * Answering null rather than failing is what lets a test assert an
   * <i>absence</i> — that this add-on deliberately does not subscribe to a
   * broadcast — which is as much a decision as a registration is.
   *
   * @param eventName the broadcast name the plugin is registered under
   * @return the declared class, or null when there is no registration
   * @throws Exception when the configuration cannot be read
   */
  private Class<?> declaredFor(String eventName) throws Exception {
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
    return null;
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
