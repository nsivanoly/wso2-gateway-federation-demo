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

package org.wso2.konglocal.client.util;

import org.apache.commons.lang3.StringUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.URITemplate;
import org.wso2.konglocal.client.KongLocalConstants;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class contains utility methods for KongLocal gateway interactions.
 */
public class KongLocalGatewayUtil {

    private static final Pattern VALID_PATH_PATTERN = Pattern.compile("^[a-zA-Z0-9-._~%!$&'()*+,;=:@/]*$");

    public static String getKongLocalServiceIdFromReferenceArtifact(String referenceArtifact)
            throws APIManagementException {
        return getField(referenceArtifact, KongLocalConstants.KONGLOCAL_SERVICE_ID_PATTERN,
                "serviceId");
    }

    public static String getKongLocalRouteIdFromReferenceArtifact(String referenceArtifact)
            throws APIManagementException {
        return getField(referenceArtifact, KongLocalConstants.KONGLOCAL_ROUTE_ID_PATTERN,
                "routeId");
    }

        public static String getKongLocalServiceNameFromReferenceArtifact(String referenceArtifact)
            throws APIManagementException {
        return getField(referenceArtifact, KongLocalConstants.KONGLOCAL_SERVICE_NAME_PATTERN,
            "serviceName");
        }

        public static String getKongLocalRouteNameFromReferenceArtifact(String referenceArtifact)
            throws APIManagementException {
        return getField(referenceArtifact, KongLocalConstants.KONGLOCAL_ROUTE_NAME_PATTERN,
            "routeName");
        }

    public static String getKongLocalPathFromReferenceArtifact(String referenceArtifact) {
        try {
            return getField(referenceArtifact, KongLocalConstants.KONGLOCAL_PATH_PATTERN,
                    "path");
        } catch (APIManagementException e) {
            return "/";
        }
    }

    public static String createReferenceArtifact(String serviceId, String routeId, String path) {
        return createReferenceArtifact(serviceId, routeId, "", "", path);
    }

    public static String createReferenceArtifact(String serviceId, String routeId,
                                                 String serviceName, String routeName,
                                                 String path) {
        return "serviceId=" + serviceId + ";routeId=" + routeId + ";serviceName=" + serviceName
                + ";routeName=" + routeName + ";path=" + path;
    }

    private static String getField(String referenceArtifact, String regex, String fieldName)
            throws APIManagementException {
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(referenceArtifact);

        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new APIManagementException("Error while extracting KongLocal " + fieldName
                + " from reference artifact");
    }

    public static String getEndpointURL(API api) throws APIManagementException {
        try {
            String endpointConfig = api.getEndpointConfig();
            if (StringUtils.isEmpty(endpointConfig)) {
                return endpointConfig;
            }
            JSONParser parser = new JSONParser();
            JSONObject endpointConfigJson = (JSONObject) parser.parse(endpointConfig);

            JSONObject prodEndpoints = (JSONObject) endpointConfigJson.get(KongLocalConstants.PRODUCTION_ENDPOINTS);
            String productionEndpoint = (String) prodEndpoints.get(KongLocalConstants.URL_PROP);

            return productionEndpoint.charAt(productionEndpoint.length() - 1) == '/'
                    ? productionEndpoint.substring(0, productionEndpoint.length() - 1)
                    : productionEndpoint;
        } catch (ParseException e) {
            throw new APIManagementException("Error while parsing endpoint configuration", e);
        }
    }

    public static String validateKongLocalAPIEndpoint(String urlString) {
        try {
            if (StringUtils.isEmpty(urlString)) {
                return null;
            }
            URL url = new URL(urlString);

            String protocol = url.getProtocol();
            if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
                return "Invalid Endpoint URL";
            }

            if (url.getHost() == null || url.getHost().isEmpty()) {
                return "Invalid Endpoint URL";
            }

            if (!VALID_PATH_PATTERN.matcher(url.getPath()).matches()) {
                return "Invalid Endpoint URL";
            }
            return null;
        } catch (MalformedURLException e) {
            return "Invalid Endpoint URL";
        }
    }

    public static String validateResourceContexts(API api) {
        Set<URITemplate> uriTemplates = api.getUriTemplates();

        if (!uriTemplates.isEmpty()) {
            for (URITemplate uriTemplate : uriTemplates) {
                if (uriTemplate.getUriTemplate().contains("*")) {
                    return "Some resource contexts contain '*' wildcard";
                }
            }
        }
        return null;
    }
}
