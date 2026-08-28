/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.exoplatform.caldav.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Sort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.dao.CaldavServerDAO;
import org.exoplatform.caldav.entity.CaldavServerEntity;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.ObservedQuirk;
import org.exoplatform.caldav.model.ServerQuirkDirection;
import org.exoplatform.caldav.utils.ServerQuirkSummary.Observation;
import org.exoplatform.commons.file.model.FileInfo;
import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.upload.UploadResource;
import org.exoplatform.upload.UploadService;

/**
 * The provider name is the join key everything hangs from — agenda's
 * REMOTE_PROVIDER row, the user's connected-provider binding — so what this
 * storage must guarantee is that every created row ends up with its own
 * derived name (prefix.id, unique because ids are), that the seed keeps the
 * fixed legacy name, and that an update can move everything EXCEPT that name.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavServerStorageTest {

  private static final String PREFIX = "agenda.caldavCalendar";

  @Mock
  private CaldavServerDAO     caldavServerDAO;

  @Mock
  private UploadService       uploadService;

  @Mock
  private FileService         fileService;

  @InjectMocks
  private CaldavServerStorage caldavServerStorage;

  private AtomicLong          nextId;

  /**
   * Makes the mocked DAO behave like a database for ids: the first save of an
   * id-less entity assigns the next id, every save returns its argument.
   */
  @BeforeEach
  public void setUp() {
    nextId = new AtomicLong(4);
    lenient().when(caldavServerDAO.save(any(CaldavServerEntity.class))).thenAnswer(invocation -> {
      CaldavServerEntity entity = invocation.getArgument(0);
      if (entity.getId() == null) {
        entity.setId(nextId.incrementAndGet());
      }
      return entity;
    });
  }

  /**
   * Two created rows derive two distinct provider names, each being exactly
   * prefix.id of its own row — the uniqueness the DB index then enforces is
   * structural here, not accidental.
   */
  @Test
  public void shouldDeriveDistinctProviderNamesFromRowIds() {
    CaldavServer first = caldavServerStorage.createServer(server(0, null, "One", null, "https://one/", true),
                                                          PREFIX);
    CaldavServer second = caldavServerStorage.createServer(server(0, null, "Two", null, "https://two/", true),
                                                           PREFIX);

    assertEquals(PREFIX + "." + first.getId(), first.getProviderName());
    assertEquals(PREFIX + "." + second.getId(), second.getProviderName());
    assertNotEquals(first.getProviderName(), second.getProviderName());
  }

  /**
   * The seed row keeps the FIXED legacy provider name, undecorated: accounts
   * connected before the registry existed resolve through that exact name.
   */
  @Test
  public void shouldKeepFixedProviderNameOnSeed() {
    CaldavServer seed = caldavServerStorage.createSeedServer(server(0, null, "CalDAV", null, "https://seed/", true),
                                                             PREFIX);

    assertEquals(PREFIX, seed.getProviderName());
  }

  /**
   * An update may move the name, description, URL and activation — never the
   * provider name, even when the caller sends one.
   */
  @Test
  public void shouldUpdateEverythingButTheProviderName() {
    CaldavServerEntity existing = new CaldavServerEntity(7L, PREFIX + ".7", "Old", null, "https://old/", true, null, null, true, null, null, null, null);
    when(caldavServerDAO.findById(7L)).thenReturn(Optional.of(existing));

    CaldavServer updated = caldavServerStorage.updateServer(server(7, "hijacked.name", "New", "desc",
                                                                             "https://new/", false));

    assertEquals(PREFIX + ".7", updated.getProviderName());
    assertEquals("New", updated.getName());
    assertEquals("https://new/", updated.getServerUrl());
    assertEquals(false, updated.isActive());
  }

  /**
   * Updating a row that does not exist answers null — the service turns that
   * into the 404.
   */
  @Test
  public void shouldAnswerNullOnUpdateOfMissingRow() {
    when(caldavServerDAO.findById(99L)).thenReturn(Optional.empty());

    assertNull(caldavServerStorage.updateServer(server(99, null, "New", null, "https://new/", true)));
  }

  /**
   * A fresh browser upload becomes a stored file whose id lands on the row,
   * and the mapped DTO points the browser at the add-on's own image endpoint.
   *
   * @throws Exception when the fake upload cannot be written
   */
  @Test
  public void shouldStoreUploadedImageOnCreate() throws Exception {
    java.io.File upload = java.io.File.createTempFile("caldav-icon", ".png");
    upload.deleteOnExit();
    java.nio.file.Files.write(upload.toPath(), new byte[] { 1, 2, 3 });
    UploadResource uploadResource = mock(UploadResource.class);
    when(uploadResource.getStoreLocation()).thenReturn(upload.getAbsolutePath());
    when(uploadService.getUploadResource("upload-1")).thenReturn(uploadResource);
    FileItem storedFile = mock(FileItem.class);
    FileInfo storedFileInfo = mock(FileInfo.class);
    when(storedFile.getFileInfo()).thenReturn(storedFileInfo);
    when(storedFileInfo.getId()).thenReturn(55L);
    when(fileService.writeFile(any(FileItem.class))).thenReturn(storedFile);
    when(fileService.getFileInfo(55L)).thenReturn(storedFileInfo);

    CaldavServer toCreate = server(0, null, "One", null, "https://one/", true);
    toCreate.setImageUploadId("upload-1");
    CaldavServer created = caldavServerStorage.createServer(toCreate, PREFIX);

    assertEquals(Long.valueOf(55L), created.getImageFileId());
    assertEquals(true, created.getImageUrl().startsWith("/caldav/rest/servers/" + created.getId() + "/image"));
  }

  /**
   * An icon survives the update, and an image explicitly dropped by the
   * drawer (imageFileId sent back null while the row holds one) is deleted
   * from the file storage along with its reference.
   */
  @Test
  public void shouldPersistIconAndDropRemovedImageOnUpdate() {
    CaldavServerEntity existing = new CaldavServerEntity(7L, PREFIX + ".7", "Old", null, "https://old/", true, null, 55L, true, null, null, null, null);
    when(caldavServerDAO.findById(7L)).thenReturn(Optional.of(existing));

    CaldavServer payload = server(7, null, "New", null, "https://new/", true);
    payload.setIcon("fa-server");
    payload.setImageFileId(null);
    CaldavServer updated = caldavServerStorage.updateServer(payload);

    assertEquals("fa-server", updated.getIcon());
    assertNull(updated.getImageFileId());
    verify(fileService).deleteFile(55L);
  }

  /**
   * Deleting a row deletes its stored image too — the file has no other
   * referrer — and answers whether a row was actually removed.
   */
  @Test
  public void shouldDeleteRowAndItsImage() {
    CaldavServerEntity existing = new CaldavServerEntity(7L, PREFIX + ".7", "Old", null, "https://old/", true, null, 55L, true, null, null, null, null);
    when(caldavServerDAO.findById(7L)).thenReturn(Optional.of(existing));

    assertEquals(true, caldavServerStorage.deleteServer(7L));

    verify(fileService).deleteFile(55L);
    verify(caldavServerDAO).delete(existing);

    when(caldavServerDAO.findById(99L)).thenReturn(Optional.empty());
    assertEquals(false, caldavServerStorage.deleteServer(99L));
  }

  /**
   * The listing reads the rows ordered by id — seed first, since the seed
   * holds the lowest id by construction — and maps every row to its DTO. An
   * accidental unordered read would shuffle the seed away from the top of the
   * admin table.
   */
  @Test
  public void shouldListServersOrderedById() {
    CaldavServerEntity seed = new CaldavServerEntity(1L, PREFIX, "Stalwart", null, "https://seed/", true, null, null, true, null, null, null, null);
    CaldavServerEntity declared = new CaldavServerEntity(7L, PREFIX + ".7", "Nextcloud", null, "https://declared/", false, null,
                                                         null, true, null, null, null, null);
    ArgumentCaptor<Sort> sort = ArgumentCaptor.forClass(Sort.class);
    when(caldavServerDAO.findAll(sort.capture())).thenReturn(List.of(seed, declared));

    List<CaldavServer> servers = caldavServerStorage.getServers();

    assertEquals(Sort.by("id"), sort.getValue());
    assertEquals(2, servers.size());
    assertEquals(PREFIX, servers.get(0).getProviderName());
    assertEquals("Nextcloud", servers.get(1).getName());
    assertEquals(false, servers.get(1).isActive());
  }

  /**
   * A row reads back by id as its DTO, and a missing id answers null — the
   * service turns that into the 404.
   */
  @Test
  public void shouldReadOneServerByIdOrAnswerNull() {
    CaldavServerEntity existing = new CaldavServerEntity(7L, PREFIX + ".7", "Nextcloud", "desc", "https://declared/", true,
                                                         null, null, true, null, null, null, null);
    when(caldavServerDAO.findById(7L)).thenReturn(Optional.of(existing));
    when(caldavServerDAO.findById(99L)).thenReturn(Optional.empty());

    CaldavServer server = caldavServerStorage.getServerById(7L);

    assertEquals(7L, server.getId());
    assertEquals(PREFIX + ".7", server.getProviderName());
    assertEquals("desc", server.getDescription());
    assertNull(caldavServerStorage.getServerById(99L));
  }

  /**
   * The provider name resolves back to its row — the read the URL resolution
   * uses to find the seed — and an unknown name answers null.
   */
  @Test
  public void shouldReadOneServerByProviderNameOrAnswerNull() {
    CaldavServerEntity seed = new CaldavServerEntity(1L, PREFIX, "Stalwart", null, "https://seed/", true, null, null, true, null, null, null, null);
    when(caldavServerDAO.findByProviderName(PREFIX)).thenReturn(Optional.of(seed));
    when(caldavServerDAO.findByProviderName("unknown")).thenReturn(Optional.empty());

    CaldavServer server = caldavServerStorage.getServerByProviderName(PREFIX);

    assertEquals("https://seed/", server.getServerUrl());
    assertNull(caldavServerStorage.getServerByProviderName("unknown"));
  }

  /**
   * The row count is the DAO's — it is what the seeding decision reads to
   * know whether the registry is untouched.
   */
  @Test
  public void shouldCountTheRegistrationRows() {
    when(caldavServerDAO.count()).thenReturn(3L);

    assertEquals(3L, caldavServerStorage.countServers());
  }

  /**
   * A fresh upload arriving while the row already holds an image UPDATES the
   * stored file in place (same file id, new bytes) instead of writing a
   * second file and leaking the first — and the kept image is not deleted on
   * the way.
   *
   * @throws Exception when the fake upload cannot be written
   */
  @Test
  public void shouldUpdateTheStoredFileWhenReplacingTheImage() throws Exception {
    CaldavServerEntity existing = new CaldavServerEntity(7L, PREFIX + ".7", "Old", null, "https://old/", true, null, 55L, true, null, null, null, null);
    when(caldavServerDAO.findById(7L)).thenReturn(Optional.of(existing));
    java.io.File upload = java.io.File.createTempFile("caldav-icon", ".png");
    upload.deleteOnExit();
    java.nio.file.Files.write(upload.toPath(), new byte[] { 4, 5, 6 });
    UploadResource uploadResource = mock(UploadResource.class);
    when(uploadResource.getStoreLocation()).thenReturn(upload.getAbsolutePath());
    when(uploadService.getUploadResource("upload-2")).thenReturn(uploadResource);
    FileItem updatedFile = mock(FileItem.class);
    FileInfo updatedFileInfo = mock(FileInfo.class);
    when(updatedFile.getFileInfo()).thenReturn(updatedFileInfo);
    when(updatedFileInfo.getId()).thenReturn(55L);
    ArgumentCaptor<FileItem> fileItem = ArgumentCaptor.forClass(FileItem.class);
    when(fileService.updateFile(fileItem.capture())).thenReturn(updatedFile);
    Date lastModified = new Date();
    when(updatedFileInfo.getUpdatedDate()).thenReturn(lastModified);
    when(fileService.getFileInfo(55L)).thenReturn(updatedFileInfo);

    CaldavServer payload = server(7, null, "Old", null, "https://old/", true);
    payload.setImageFileId(55L);
    payload.setImageUploadId("upload-2");
    CaldavServer updated = caldavServerStorage.updateServer(payload);

    assertEquals(Long.valueOf(55L), fileItem.getValue().getFileInfo().getId());
    assertEquals(Long.valueOf(55L), updated.getImageFileId());
    // the image URL is versioned by the file's new modification date, so the
    // browser cache cannot keep serving the replaced image
    assertTrue(updated.getImageUrl().endsWith("?v=" + lastModified.getTime()));
    verify(fileService, never()).writeFile(any());
    verify(fileService, never()).deleteFile(org.mockito.ArgumentMatchers.anyLong());
  }

  /**
   * Deleting a row that never had an image touches nothing in the file
   * storage — a spurious deleteFile there would remove somebody else's file
   * id 0 or crash the delete.
   */
  @Test
  public void shouldDeleteAnImagelessRowWithoutTouchingFileStorage() {
    CaldavServerEntity existing = new CaldavServerEntity(7L, PREFIX + ".7", "Old", null, "https://old/", true, null, null, true, null, null, null, null);
    when(caldavServerDAO.findById(7L)).thenReturn(Optional.of(existing));

    assertEquals(true, caldavServerStorage.deleteServer(7L));

    verify(fileService, never()).deleteFile(org.mockito.ArgumentMatchers.anyLong());
    verify(caldavServerDAO).delete(existing);
  }

  /**
   * A zero imageFileId means "no image" everywhere, exactly like null: the
   * mapped DTO carries no image URL and the file storage is never asked —
   * the drawer sends 0 where JSON dropped the null.
   */
  @Test
  public void shouldTreatAZeroImageFileIdAsNoImage() {
    CaldavServerEntity existing = new CaldavServerEntity(7L, PREFIX + ".7", "Old", null, "https://old/", true, null, 0L, true, null, null, null, null);
    when(caldavServerDAO.findById(7L)).thenReturn(Optional.of(existing));

    CaldavServer server = caldavServerStorage.getServerById(7L);

    assertNull(server.getImageUrl());
    verify(fileService, never()).getFileInfo(org.mockito.ArgumentMatchers.anyLong());
  }

  /**
   * The drawer may report a dropped image as 0 instead of null — both mean
   * the same removal: the stored file goes, and its reference with it.
   */
  @Test
  public void shouldDropTheRemovedImageWhenReportedAsZero() {
    CaldavServerEntity existing = new CaldavServerEntity(7L, PREFIX + ".7", "Old", null, "https://old/", true, null, 55L, true, null, null, null, null);
    when(caldavServerDAO.findById(7L)).thenReturn(Optional.of(existing));

    CaldavServer payload = server(7, null, "Old", null, "https://old/", true);
    payload.setImageFileId(0L);
    CaldavServer updated = caldavServerStorage.updateServer(payload);

    assertNull(updated.getImageFileId());
    verify(fileService).deleteFile(55L);
  }

  /**
   * An upload the file storage refuses to write leaves the row imageless
   * rather than failing the whole create: the administrator keeps the server
   * and retries the image.
   *
   * @throws Exception when the fake upload cannot be written
   */
  @Test
  public void shouldCreateTheRowImagelessWhenStoringTheUploadFails() throws Exception {
    java.io.File upload = java.io.File.createTempFile("caldav-icon", ".png");
    upload.deleteOnExit();
    java.nio.file.Files.write(upload.toPath(), new byte[] { 1, 2, 3 });
    UploadResource uploadResource = mock(UploadResource.class);
    when(uploadResource.getStoreLocation()).thenReturn(upload.getAbsolutePath());
    when(uploadService.getUploadResource("upload-3")).thenReturn(uploadResource);
    when(fileService.writeFile(any(FileItem.class))).thenReturn(null);

    CaldavServer toCreate = server(0, null, "One", null, "https://one/", true);
    toCreate.setImageUploadId("upload-3");
    CaldavServer created = caldavServerStorage.createServer(toCreate, PREFIX);

    assertNull(created.getImageFileId());
    assertNull(created.getImageUrl());
  }

  // ------------------------- what the server has been seen doing (EXO-89771)

  @Test
  public void shouldMapTheStoredSummaryIntoWhatTheDrawerLists() {
    CaldavServerEntity existing = new CaldavServerEntity(7L, PREFIX + ".7", "Bluemind", null, "https://bm/", true, null, null,
                                                         true, null, null, null,
                                                         "DROPPED:CONFERENCE=399;ADDED:X-MOZ-GENERATION=41");
    when(caldavServerDAO.findById(7L)).thenReturn(Optional.of(existing));

    List<ObservedQuirk> observed = caldavServerStorage.getServerById(7L).getObservedQuirks();

    assertEquals(2, observed.size(), "largest count first, so the drawer leads with what the server always does");
    assertEquals("dropsConference", observed.get(0).quirkId());
    assertEquals(399L, observed.get(0).count());
    assertEquals(List.of("CONFERENCE"), observed.get(0).patterns());
    assertEquals("addsCompatibilityMarkers", observed.get(1).quirkId());
    assertEquals(List.of("X-MICROSOFT-*", "X-MOZ-*"),
                 observed.get(1).patterns(),
                 "ticking a family excuses the family the sentence names, not only the marker seen first");
  }

  @Test
  public void shouldStillListABehaviourNothingInTheCatalogueDescribes() {
    // The catalogue is code and deliberately incomplete; an administrator meeting
    // a server nobody here has seen must still be able to excuse what it does.
    CaldavServerEntity existing = new CaldavServerEntity(7L, PREFIX + ".7", "Odd", null, "https://odd/", true, null, null, true,
                                                         null, null, null, "ADDED:X-BM-FOO=3");
    when(caldavServerDAO.findById(7L)).thenReturn(Optional.of(existing));

    List<ObservedQuirk> observed = caldavServerStorage.getServerById(7L).getObservedQuirks();

    assertEquals(1, observed.size());
    assertNull(observed.get(0).quirkId(), "nothing describes it, so the drawer falls back to its generic wording");
    assertEquals("X-BM-FOO", observed.get(0).property());
    assertEquals(List.of("X-BM-FOO"), observed.get(0).patterns());
  }

  @Test
  public void shouldAddWhatAPassSawToWhatIsAlreadyStored() {
    CaldavServerEntity existing = new CaldavServerEntity(7L, PREFIX + ".7", "Bluemind", null, "https://bm/", true, null, null,
                                                         true, null, null, null, "DROPPED:CONFERENCE=399");
    when(caldavServerDAO.findById(7L)).thenReturn(Optional.of(existing));

    caldavServerStorage.mergeObservedQuirks(7L, Map.of(Observation.of(ServerQuirkDirection.DROPPED, "CONFERENCE"), 5L));

    assertEquals("DROPPED:CONFERENCE=404", existing.getObservedQuirks());
  }

  @Test
  public void shouldNotLetAnAdministratorSaveEraseWhatTheSweepRecorded() {
    // The summary is the sweep's column. Routed through the ordinary update it
    // would be wiped by every save from a drawer that never carried it.
    CaldavServerEntity existing = new CaldavServerEntity(7L, PREFIX + ".7", "Bluemind", null, "https://bm/", true, null, null,
                                                         true, null, null, null, "DROPPED:CONFERENCE=399");
    when(caldavServerDAO.findById(7L)).thenReturn(Optional.of(existing));
    CaldavServer edited = server(7L, PREFIX + ".7", "Bluemind renamed", null, "https://bm/", true);
    edited.setDroppedProperties("CONFERENCE");

    caldavServerStorage.updateServer(edited);

    assertEquals("DROPPED:CONFERENCE=399", existing.getObservedQuirks());
    assertEquals("CONFERENCE", existing.getDroppedProperties(), "while the ticks an administrator made are stored");
  }

  /**
   * Builds a registration with the six identity fields — the icon/image
   * fields default to null, exactly as a fresh REST payload leaves them.
   *
   * @param id technical identifier
   * @param providerName agenda provider name
   * @param name display name
   * @param description optional description
   * @param serverUrl base URL
   * @param active activation
   * @return the registration
   */
  private static CaldavServer server(long id, String providerName, String name, String description, String serverUrl,
                                     boolean active) {
    return new CaldavServer(id, providerName, name, description, serverUrl, active, null, null, null, null, true, null, null, null, null);
  }
}
