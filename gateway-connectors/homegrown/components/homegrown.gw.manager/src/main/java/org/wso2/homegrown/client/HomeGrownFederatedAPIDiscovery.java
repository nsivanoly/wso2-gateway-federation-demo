/*
 *
 * Copyright (c) 2025 WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package org.wso2.homegrown.client;

import com.google.gson.JsonObject;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.FederatedAPIDiscovery;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.DiscoveredAPI;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.homegrown.client.util.HomeGrownAPIUtil;
import org.wso2.homegrown.client.util.HomeGrownGatewayUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the federated API discovery implementation for HomeGrown gateway.
 */
public class HomeGrownFederatedAPIDiscovery implements FederatedAPIDiscovery {

    private static final Log log = LogFactory.getLog(HomeGrownFederatedAPIDiscovery.class);

    private Environment environment;
    private String organization;
    private String adminUrl;

    @Override
    public void init(Environment environment, String organization)
            throws APIManagementException {
        log.debug("Initializing HomeGrown API discovery for environment: " + environment.getName());
        this.environment = environment;
        this.organization = organization;
        this.adminUrl = environment.getAdditionalProperties()
                .getOrDefault(HomeGrownConstants.HOMEGROWN_ENVIRONMENT_ADMIN_URL,
                        HomeGrownConstants.HOMEGROWN_DEFAULT_ADMIN_URL);

        if (HomeGrownGatewayUtil.validateHomeGrownAPIEndpoint(adminUrl) != null) {
            throw new APIManagementException("Invalid HomeGrown Admin URL: " + adminUrl);
        }
    }

    @Override
    public List<DiscoveredAPI> discoverAPI() {
        List<DiscoveredAPI> retrievedAPIs = new ArrayList<>();
        // Env config "auto_publish": PUBLISH discovered APIs straight to the Dev
        // Portal, or leave them in CREATED for manual review (default).
        boolean autoPublish = Boolean.parseBoolean(environment.getAdditionalProperties()
                .getOrDefault(HomeGrownConstants.HOMEGROWN_AUTO_PUBLISH, "false"));
        String targetStatus = autoPublish
                ? HomeGrownConstants.STATUS_PUBLISHED : HomeGrownConstants.STATUS_CREATED;
        try {
            List<JsonObject> services = HomeGrownAPIUtil.getServices(adminUrl);
            for (JsonObject service : services) {
                JsonObject route = HomeGrownAPIUtil.getFirstRouteForService(adminUrl,
                        service.get("id").getAsString());
                if (route == null) {
                    continue;
                }
                API api = HomeGrownAPIUtil.homeGrownServiceToAPI(service, route, organization, environment);
                api.setStatus(targetStatus);
                DiscoveredAPI discoveredAPI = new DiscoveredAPI(api,
                        HomeGrownAPIUtil.createReferenceArtifact(service, route));
                retrievedAPIs.add(discoveredAPI);
            }
        } catch (Exception e) {
            log.error("Error while discovering APIs from HomeGrown gateway", e);
        }
        return retrievedAPIs;
    }

    @Override
    public boolean isAPIUpdated(String existingReferenceArtifact, String newReferenceArtifact) {
        return !existingReferenceArtifact.equals(newReferenceArtifact);
    }
}
