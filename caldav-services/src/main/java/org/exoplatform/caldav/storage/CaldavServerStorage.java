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
package org.exoplatform.caldav.storage;

import java.io.ByteArrayInputStream;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.caldav.dao.CaldavServerDAO;
import org.exoplatform.caldav.entity.CaldavServerEntity;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.commons.file.model.FileInfo;
import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.commons.utils.IOUtil;
import org.exoplatform.upload.UploadResource;
import org.exoplatform.upload.UploadService;

import lombok.SneakyThrows;

/**
 * Maps CalDAV server registrations between their JPA entity and the
 * credential-free {@link CaldavServer} DTO. No business logic lives here: the
 * provider-name derivation is mechanical (the seed name is given by the
 * service, every other row derives its name from its own id) and the choices
 * — who may write, when to seed — belong to the service.
 */
@Component
public class CaldavServerStorage {

  /**
   * FileService namespace the uploaded server images are stored under.
   */
  public static final String NAME_SPACE            = "caldavServer";

  /**
   * Fallback cache-busting version for rows whose image has no readable
   * modification date.
   */
  public static final Long   DEFAULT_LAST_MODIFIED = System.currentTimeMillis();

  @Autowired
  private CaldavServerDAO    caldavServerDAO;

  @Autowired
  private UploadService      uploadService;

  @Autowired
  private FileService        fileService;

  /**
   * Reads every registration, seed first (it holds the lowest id by
   * construction).
   *
   * @return all registrations, ordered by id
   */
  public List<CaldavServer> getServers() {
    return caldavServerDAO.findAll(Sort.by("id")).stream().map(this::fromEntity).toList();
  }

  /**
   * Reads one registration by its technical identifier.
   *
   * @param id technical identifier of the registration
   * @return the registration, or null when the row does not exist
   */
  public CaldavServer getServerById(long id) {
    return caldavServerDAO.findById(id).map(this::fromEntity).orElse(null);
  }

  /**
   * Reads one registration by the agenda remote provider it is bridged to.
   *
   * @param providerName name of the agenda remote provider
   * @return the registration, or null when none carries that name
   */
  public CaldavServer getServerByProviderName(String providerName) {
    return caldavServerDAO.findByProviderName(providerName).map(this::fromEntity).orElse(null);
  }

  /**
   * Counts the registrations — what the seeding decision reads: the seed row
   * is only ever written into an empty registry, so an administrator's edits
   * are never overwritten by a restart.
   *
   * @return number of registration rows
   */
  public long countServers() {
    return caldavServerDAO.count();
  }

  /**
   * Creates a registration whose provider name derives from its own row id
   * ({@code <providerNamePrefix>.<id>}). Two steps in one transaction —
   * insert, then name the row after the id the insert produced — because the
   * column is NOT NULL and the id does not exist before the insert. The
   * transaction keeps the intermediate placeholder name unobservable.
   *
   * @param server registration to create (provider name ignored)
   * @param providerNamePrefix prefix the provider name is derived from
   * @return the created registration, carrying its id and provider name
   */
  @Transactional
  public CaldavServer createServer(CaldavServer server, String providerNamePrefix) {
    CaldavServerEntity entity = toEntity(server);
    entity.setId(null);
    entity.setProviderName(providerNamePrefix + ".pending." + System.nanoTime());
    if (StringUtils.isNotBlank(server.getImageUploadId())) {
      entity.setImageFileId(saveImageFileItem(null, server.getImageUploadId()));
    }
    entity = caldavServerDAO.save(entity);
    entity.setProviderName(providerNamePrefix + "." + entity.getId());
    entity = caldavServerDAO.save(entity);
    return fromEntity(entity);
  }

  /**
   * Creates the seed registration under the fixed legacy provider name — the
   * one row whose provider the kernel plugin already registers, so the
   * accounts connected before the registry existed keep resolving through it.
   *
   * @param server registration to create
   * @param providerName fixed provider name of the seed
   * @return the created registration
   */
  @Transactional
  public CaldavServer createSeedServer(CaldavServer server, String providerName) {
    CaldavServerEntity entity = toEntity(server);
    entity.setId(null);
    entity.setProviderName(providerName);
    return fromEntity(caldavServerDAO.save(entity));
  }

  /**
   * Updates the user-editable fields of a registration: name, description,
   * URL, activation and whether the copies pushed to this server carry answer
   * links. The provider name never changes — it is the join key user settings
   * and agenda rows hang from.
   *
   * @param server registration carrying the id to update and the new values
   * @return the updated registration, or null when the row does not exist
   */
  @Transactional
  public CaldavServer updateServer(CaldavServer server) {
    return caldavServerDAO.findById(server.getId()).map(entity -> {
      entity.setName(server.getName());
      entity.setDescription(server.getDescription());
      entity.setServerUrl(server.getServerUrl());
      entity.setActive(server.isActive());
      entity.setIcon(server.getIcon());
      entity.setAnswerLinksInCopy(server.isAnswerLinksInCopy());
      Long oldImageFileId = entity.getImageFileId();
      boolean imageRemoved = (server.getImageFileId() == null || server.getImageFileId() == 0)
          && oldImageFileId != null && oldImageFileId > 0;
      if (imageRemoved) {
        // The image was explicitly dropped by the drawer: the stored file has
        // no other referrer, so it goes with the reference.
        entity.setImageFileId(null);
        fileService.deleteFile(oldImageFileId);
        oldImageFileId = null;
      }
      if (StringUtils.isNotBlank(server.getImageUploadId())) {
        entity.setImageFileId(saveImageFileItem(oldImageFileId, server.getImageUploadId()));
      }
      return fromEntity(caldavServerDAO.save(entity));
    }).orElse(null);
  }

  /**
   * Deletes a registration row and, with it, its uploaded image — the file
   * has no other referrer, so keeping it would only leak storage.
   *
   * @param serverId technical identifier of the registration
   * @return true when a row was deleted, false when none carried that id
   */
  @Transactional
  public boolean deleteServer(long serverId) {
    return caldavServerDAO.findById(serverId).map(entity -> {
      if (entity.getImageFileId() != null && entity.getImageFileId() > 0) {
        fileService.deleteFile(entity.getImageFileId());
      }
      caldavServerDAO.delete(entity);
      return true;
    }).orElse(false);
  }

  /**
   * Turns a fresh browser upload into a FileService file, updating the
   * existing file when the row already had one — the exact mechanics of
   * email-connector's admin images.
   *
   * @param imageFileId existing file to update, or null to write a new one
   * @param uploadId identifier of the browser upload
   * @return identifier of the stored file, or null when storing failed
   */
  @SneakyThrows
  private Long saveImageFileItem(Long imageFileId, String uploadId) {
    UploadResource uploadResource = uploadService.getUploadResource(uploadId);
    byte[] bytesContent = IOUtil.getFileContentAsBytes(uploadResource.getStoreLocation());
    FileItem fileItem = new FileItem(imageFileId,
                                     "caldavServerIllustration",
                                     "image/png",
                                     NAME_SPACE,
                                     bytesContent.length,
                                     new Date(),
                                     null,
                                     false,
                                     new ByteArrayInputStream(bytesContent));
    if (imageFileId != null && imageFileId > 0) {
      fileItem = fileService.updateFile(fileItem);
    } else {
      fileItem = fileService.writeFile(fileItem);
    }
    return fileItem == null || fileItem.getFileInfo() == null ? null : fileItem.getFileInfo().getId();
  }

  /**
   * Maps an entity to the credential-free DTO.
   *
   * @param entity row to map
   * @return the DTO
   */
  private CaldavServer fromEntity(CaldavServerEntity entity) {
    long imageLastModified = DEFAULT_LAST_MODIFIED;
    if (entity.getImageFileId() != null && entity.getImageFileId() > 0) {
      FileInfo fileInfo = fileService.getFileInfo(entity.getImageFileId());
      if (fileInfo != null && fileInfo.getUpdatedDate() != null) {
        imageLastModified = fileInfo.getUpdatedDate().getTime();
      }
    }
    return new CaldavServer(entity.getId() == null ? 0 : entity.getId(),
                            entity.getProviderName(),
                            entity.getName(),
                            entity.getDescription(),
                            entity.getServerUrl(),
                            entity.isActive(),
                            entity.getIcon(),
                            entity.getImageFileId(),
                            null,
                            getImageUrl(entity.getImageFileId(), entity.getId(), imageLastModified),
                            entity.isAnswerLinksInCopy());
  }

  /**
   * The URL the browser fetches a stored image from, versioned by its last
   * modification so an updated image escapes the browser cache. Null when the
   * row has no image.
   *
   * @param imageFileId identifier of the stored file, or null
   * @param id identifier of the registration row
   * @param imageLastModified cache-busting version
   * @return the image URL, or null when no image exists
   */
  private String getImageUrl(Long imageFileId, Long id, long imageLastModified) {
    if (imageFileId == null || imageFileId.longValue() == 0) {
      return null;
    }
    return String.format("/caldav/rest/servers/%s/image?v=%s", id, imageLastModified);
  }

  /**
   * Maps a DTO to an entity, provider name excluded — it is always decided by
   * the storage method doing the write.
   *
   * @param server DTO to map
   * @return the entity
   */
  private CaldavServerEntity toEntity(CaldavServer server) {
    CaldavServerEntity entity = new CaldavServerEntity();
    entity.setId(server.getId() <= 0 ? null : server.getId());
    entity.setName(server.getName());
    entity.setDescription(server.getDescription());
    entity.setServerUrl(server.getServerUrl());
    entity.setActive(server.isActive());
    entity.setIcon(server.getIcon());
    entity.setImageFileId(server.getImageFileId());
    entity.setAnswerLinksInCopy(server.isAnswerLinksInCopy());
    return entity;
  }
}
