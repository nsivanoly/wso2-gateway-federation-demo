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

package org.wso2.konglocal.client;

import com.google.gson.JsonObject;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.FederatedAPIDiscovery;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.DiscoveredAPI;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.konglocal.client.util.KongLocalAPIUtil;
import org.wso2.konglocal.client.util.KongLocalGatewayUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Represents the federated API discovery implementation for KongLocal gateway.
 */
public class KongLocalFederatedAPIDiscovery implements FederatedAPIDiscovery {

    private static final Log log = LogFactory.getLog(KongLocalFederatedAPIDiscovery.class);

    private Environment environment;
    private String organization;
    private String adminUrl;

    @Override
    public void init(Environment environment, String organization)
            throws APIManagementException {
        log.debug("Initializing KongLocal API discovery for environment: " + environment.getName());
        this.environment = environment;
        this.organization = organization;
        this.adminUrl = environment.getAdditionalProperties()
                .getOrDefault(KongLocalConstants.KONGLOCAL_ENVIRONMENT_ADMIN_URL,
                        KongLocalConstants.KONGLOCAL_DEFAULT_ADMIN_URL);

        if (KongLocalGatewayUtil.validateKongLocalAPIEndpoint(adminUrl) != null) {
            throw new APIManagementException("Invalid Kong Admin URL: " + adminUrl);
        }
    }

    @Override
    public List<DiscoveredAPI> discoverAPI() {
        List<DiscoveredAPI> retrievedAPIs = new ArrayList<>();
        try {
            List<JsonObject> services = KongLocalAPIUtil.getServices(adminUrl);
            Map<String, List<JsonObject>> routesByService = KongLocalAPIUtil.getRoutesByService(adminUrl);
            for (JsonObject service : services) {
                String serviceRef = service.has("id") ? service.get("id").getAsString()
                        : service.get("name").getAsString();
                List<JsonObject> routes = routesByService.get(serviceRef);
                JsonObject route = (routes == null || routes.isEmpty()) ? null : routes.get(0);
                if (route == null) {
                    continue;
                }

                if (KongLocalAPIUtil.isWSO2ManagedService(service)
                        || KongLocalAPIUtil.isLikelyConnectorManagedService(service, route)) {
                    if (log.isDebugEnabled()) {
                        log.debug("Skipping APIM-managed Kong service from federated discovery: "
                                + service.get("name").getAsString());
                    }
                    continue;
                }

                API api = KongLocalAPIUtil.kongServiceToAPI(service, route, organization, environment);
                DiscoveredAPI discoveredAPI = new DiscoveredAPI(api,
                        KongLocalAPIUtil.createReferenceArtifact(service, route));
                retrievedAPIs.add(discoveredAPI);
            }
        } catch (Exception e) {
            log.error("Error while discovering APIs from KongLocal gateway", e);
        }
        return retrievedAPIs;
    }

    @Override
    public boolean isAPIUpdated(String existingReferenceArtifact, String newReferenceArtifact) {
        return !existingReferenceArtifact.equals(newReferenceArtifact);
    }
}
