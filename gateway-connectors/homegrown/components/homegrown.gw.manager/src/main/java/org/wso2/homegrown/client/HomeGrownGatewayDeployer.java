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

import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.GatewayAPIValidationResult;
import org.wso2.carbon.apimgt.api.model.GatewayDeployer;
import org.wso2.carbon.apimgt.api.model.URITemplate;
import org.wso2.homegrown.client.util.HomeGrownAPIUtil;
import org.wso2.homegrown.client.util.HomeGrownGatewayUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * This class controls API deployments on a HomeGrown gateway.
 */
public class HomeGrownGatewayDeployer implements GatewayDeployer {

    private String adminUrl;
    private String proxyUrl;

    @Override
    public void init(Environment environment) throws APIManagementException {
        this.adminUrl = environment.getAdditionalProperties()
                .getOrDefault(HomeGrownConstants.HOMEGROWN_ENVIRONMENT_ADMIN_URL,
                        HomeGrownConstants.HOMEGROWN_DEFAULT_ADMIN_URL);
        this.proxyUrl = environment.getAdditionalProperties()
                .getOrDefault(HomeGrownConstants.HOMEGROWN_ENVIRONMENT_PROXY_URL,
                        HomeGrownConstants.HOMEGROWN_DEFAULT_PROXY_URL);

        if (HomeGrownGatewayUtil.validateHomeGrownAPIEndpoint(adminUrl) != null) {
            throw new APIManagementException("Invalid HomeGrown Admin URL: " + adminUrl);
        }
        if (HomeGrownGatewayUtil.validateHomeGrownAPIEndpoint(proxyUrl) != null) {
            throw new APIManagementException("Invalid HomeGrown Proxy URL: " + proxyUrl);
        }
    }

    @Override
    public String getType() {
        return HomeGrownConstants.HOMEGROWN_TYPE;
    }

    @Override
    public String deploy(API api, String externalReference) throws APIManagementException {
        if (externalReference == null) {
            return HomeGrownAPIUtil.importAPI(api, adminUrl);
        }
        return HomeGrownAPIUtil.reimportAPI(externalReference, api, adminUrl);
    }

    @Override
    public boolean undeploy(String externalReference, boolean delete) throws APIManagementException {
        if (delete) {
            HomeGrownAPIUtil.deleteAPI(externalReference, adminUrl);
        }
        return true;
    }

    @Override
    public boolean undeploy(String externalReference) throws APIManagementException {
        return undeploy(externalReference, true);
    }

    @Override
    public GatewayAPIValidationResult validateApi(API api) throws APIManagementException {
        List<String> errorList = new ArrayList<>();
        errorList.add(HomeGrownGatewayUtil.validateHomeGrownAPIEndpoint(HomeGrownGatewayUtil.getEndpointURL(api)));
        errorList.add(HomeGrownGatewayUtil.validateResourceContexts(api));

        GatewayAPIValidationResult result = new GatewayAPIValidationResult();
        result.setValid(errorList.stream().allMatch(Objects::isNull));
        result.setErrors(errorList.stream().filter(Objects::nonNull).collect(Collectors.toList()));
        return result;
    }

    @Override
    public String getAPIExecutionURL(String externalReference) {
        String path = HomeGrownGatewayUtil.getHomeGrownPathFromReferenceArtifact(externalReference);
        return proxyUrl + path;
    }

    @Override
    public void transformAPI(API api) {
        for (URITemplate resource : api.getUriTemplates()) {
            if (resource.getUriTemplate().endsWith("/*")) {
                resource.setUriTemplate(resource.getUriTemplate().replace("/*", "/"));
            }
        }
    }
}
