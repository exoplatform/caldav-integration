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
package org.exoplatform.caldav;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.PropertySource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import io.meeds.spring.AvailableIntegration;
import io.meeds.spring.kernel.PortalApplicationContextInitializer;

/**
 * Spring Boot bootstrap of the CalDAV add-on webapp. Extending
 * PortalApplicationContextInitializer registers this WAR's Spring context with
 * the eXo Kernel before the portal container boots, so that the beans of this
 * add-on and of the platform are mutually injectable. The pre-existing kernel
 * components of the add-on (the JAX-RS resource, the per-user settings
 * storage and service) stay kernel-configured and coexist with the Spring
 * beans through the bridge, exactly as in email-connector.
 */
@SpringBootApplication(scanBasePackages = { CaldavApplication.MODULE_NAME, AvailableIntegration.KERNEL_MODULE,
    AvailableIntegration.JPA_MODULE, AvailableIntegration.LIQUIBASE_MODULE, AvailableIntegration.WEB_MODULE })
@EnableJpaRepositories(basePackages = { CaldavApplication.MODULE_NAME })
@PropertySource("classpath:application.properties")
@PropertySource("classpath:application-common.properties")
@PropertySource("classpath:caldav.properties")
public class CaldavApplication extends PortalApplicationContextInitializer {

  /**
   * Base package of the add-on, used both as the Spring component scan root
   * and as the base package of its JPA repositories.
   */
  public static final String MODULE_NAME = "org.exoplatform.caldav";

}
