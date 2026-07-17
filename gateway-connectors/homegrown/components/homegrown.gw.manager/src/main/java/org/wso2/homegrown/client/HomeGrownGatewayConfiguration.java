/*
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
 */

package org.wso2.homegrown.client;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.service.component.annotations.Component;
import org.wso2.homegrown.client.util.HomeGrownAPIUtil;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.ConfigurationDto;
import org.wso2.carbon.apimgt.api.model.GatewayAgentConfiguration;
import org.wso2.carbon.apimgt.api.model.GatewayMode;
import org.wso2.carbon.apimgt.api.model.GatewayPortalConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;


/**
 * This class contains the configurations related to HomeGrown Gateway
 */
@Component(
        name = "homegrown.external.gateway.configuration.component",
        immediate = true,
        service = GatewayAgentConfiguration.class
)
public class HomeGrownGatewayConfiguration implements GatewayAgentConfiguration {
    private static final Log log = LogFactory.getLog(HomeGrownAPIUtil.class);

    @Override
    public String getGatewayDeployerImplementation() {
        return HomeGrownGatewayDeployer.class.getName();
    }

    @Override
    public String getImplementation() {
        // Deprecated method, kept for backward compatibility
        return getGatewayDeployerImplementation();
    }

    @Override
    public String getDiscoveryImplementation() {
        return HomeGrownFederatedAPIDiscovery.class.getName();
    }

    @Override
    public List<String> getSupportedModes() {
        return Arrays.asList(GatewayMode.READ_WRITE.getMode());
    }

    @Override
    public List<ConfigurationDto> getConnectionConfigurations() {
        List<ConfigurationDto> configurationDtoList = new ArrayList<>();
        configurationDtoList
            .add(new ConfigurationDto("admin_url", "HomeGrown Admin URL", "input",
                "HomeGrown Admin API URL (e.g., http://third-party-gateway:8090/)",
                HomeGrownConstants.HOMEGROWN_DEFAULT_ADMIN_URL, true, false,
                Collections.emptyList(), false));
        configurationDtoList
            .add(new ConfigurationDto("proxy_url", "HomeGrown Proxy URL", "input",
                "HomeGrown Proxy URL for API execution (e.g., http://third-party-gateway:8090/)",
                HomeGrownConstants.HOMEGROWN_DEFAULT_PROXY_URL, true, false,
                Collections.emptyList(), false));
        configurationDtoList.add(new ConfigurationDto("stage", "Stage Name", "input",
            "Optional stage label", "default", false,
                false,
                Collections.emptyList(), false));
        // Rendered as a dropdown in the Admin Portal. The gateway-environment form
        // honors type "options" (as WSO2's built-in Kong connector uses), where each
        // value is an option object; type "select"/"checkbox" are NOT honored here
        // and fall back to a text box. The selected option's name ("true"/"false")
        // is what gets stored in additionalProperties.
        List<ConfigurationDto> autoPublishOptions = Arrays.asList(
            new ConfigurationDto("false", "Disabled — leave discovered APIs in CREATED",
                "labelOnly", "", "", false, false, Collections.emptyList(), false),
            new ConfigurationDto("true", "Enabled — publish discovered APIs to the Dev Portal",
                "labelOnly", "", "", false, false, Collections.emptyList(), false));
        configurationDtoList.add(new ConfigurationDto(HomeGrownConstants.HOMEGROWN_AUTO_PUBLISH,
            "Auto-publish discovered APIs", "options",
            "If enabled, APIs discovered on this gateway are published to the Dev Portal "
                + "automatically. If disabled, they are left in CREATED for manual review.",
            "false", false, false, autoPublishOptions, false));

        return configurationDtoList;
    }

    @Override
    public String getType() {
        return HomeGrownConstants.HOMEGROWN_TYPE;
    }

    @Override
    public GatewayPortalConfiguration getGatewayFeatureCatalog() throws APIManagementException {
        try (InputStream inputStream = HomeGrownGatewayConfiguration.class.getClassLoader()
                .getResourceAsStream("GatewayFeatureCatalog.json")) {

            if (inputStream == null) {
                throw new APIManagementException("Gateway Feature Catalog JSON not found");
            }

            // Initialize Gson
            Gson gson = new Gson();

            InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
            JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();

            JsonObject gatewayObject = jsonObject.getAsJsonObject(HomeGrownConstants.HOMEGROWN_TYPE);

            List<String> apiTypes = gson.fromJson(gatewayObject.get("apiTypes"),
                    new TypeToken<List<String>>() {}.getType());
            JsonObject gatewayFeatures = gatewayObject.get("gatewayFeatures").getAsJsonObject();

            GatewayPortalConfiguration config = new GatewayPortalConfiguration();
            config.setGatewayType(HomeGrownConstants.HOMEGROWN_TYPE);
            config.setSupportedAPITypes(apiTypes);
            config.setSupportedFeatures(gatewayFeatures);

            return config;
        } catch (Exception e) {
            throw new APIManagementException("Error occurred while reading Gateway Feature Catalog JSON", e);
        }
    }

    @Override
    public String getDefaultHostnameTemplate() {

        return HomeGrownConstants.HOMEGROWN_API_EXECUTION_URL_TEMPLATE;
    }
}
