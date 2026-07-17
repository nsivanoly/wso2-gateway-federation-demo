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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.URITemplate;
import org.wso2.konglocal.client.KongLocalConstants;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;

/**
 * This class contains utility methods to interact with Kong Admin API.
 */
public class KongLocalAPIUtil {

    private static final String DBLESS_ID_PLACEHOLDER = "db-less";
    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{([^/{}]+)\\}");
    private static final String WSO2_MANAGED_TAG = "wso2-apim-managed";

    public static String importAPI(API api, String adminUrl) throws APIManagementException {
        String serviceName = buildEntityName(api);
        String routeName = createRouteName(serviceName);
        String routePath = normalizeContext(api.getContext());
        String upstreamUrl = KongLocalGatewayUtil.getEndpointURL(api);
        JsonArray methods = extractMethods(api);

        try {
            JsonObject servicePayload = new JsonObject();
            servicePayload.addProperty("name", serviceName);
            servicePayload.addProperty("url", upstreamUrl);
            servicePayload.add("tags", buildManagedTags(api));

            JsonObject service = executeJsonRequest(adminUrl + KongLocalConstants.KONG_SERVICES_PATH,
                    "POST", servicePayload.toString());
            String serviceId = service.get("id").getAsString();

            JsonObject routePayload = createRoutePayload(routeName, routePath, methods);
            JsonObject route = executeJsonRequest(adminUrl + KongLocalConstants.KONG_SERVICES_PATH + "/"
                    + encode(serviceId) + KongLocalConstants.KONG_ROUTES_PATH, "POST", routePayload.toString());

            return KongLocalGatewayUtil.createReferenceArtifact(serviceId,
                    route.get("id").getAsString(), serviceName, routeName, routePath);
        } catch (APIManagementException e) {
            if (!isDbLessUnsupported(e)) {
                throw e;
            }

            upsertDeclarativeConfig(adminUrl, serviceName, routeName, routePath, upstreamUrl, methods, api);
            return KongLocalGatewayUtil.createReferenceArtifact(DBLESS_ID_PLACEHOLDER,
                    DBLESS_ID_PLACEHOLDER, serviceName, routeName, routePath);
        }
    }

    public static String reimportAPI(String referenceArtifact, API api, String adminUrl)
            throws APIManagementException {
        String serviceName = getServiceName(referenceArtifact, api);
        String routeName = getRouteName(referenceArtifact, serviceName);
        String routePath = normalizeContext(api.getContext());
        String upstreamUrl = KongLocalGatewayUtil.getEndpointURL(api);
        JsonArray methods = extractMethods(api);

        if (!isDbLessArtifact(referenceArtifact)) {
            try {
                String serviceId = KongLocalGatewayUtil.getKongLocalServiceIdFromReferenceArtifact(referenceArtifact);
                String routeId = KongLocalGatewayUtil.getKongLocalRouteIdFromReferenceArtifact(referenceArtifact);

                JsonObject servicePayload = new JsonObject();
                servicePayload.addProperty("name", serviceName);
                servicePayload.addProperty("url", upstreamUrl);
                executeJsonRequest(adminUrl + KongLocalConstants.KONG_SERVICES_PATH + "/" + encode(serviceId),
                        "PATCH", servicePayload.toString());

                JsonObject routePayload = createRoutePayload(routeName, routePath, methods);
                executeJsonRequest(adminUrl + KongLocalConstants.KONG_ROUTES_PATH + "/" + encode(routeId),
                        "PATCH", routePayload.toString());

                return KongLocalGatewayUtil.createReferenceArtifact(serviceId, routeId,
                        serviceName, routeName, routePath);
            } catch (APIManagementException e) {
                if (!isDbLessUnsupported(e)) {
                    throw e;
                }
            }
        }

        upsertDeclarativeConfig(adminUrl, serviceName, routeName, routePath, upstreamUrl, methods, api);
        return KongLocalGatewayUtil.createReferenceArtifact(DBLESS_ID_PLACEHOLDER,
                DBLESS_ID_PLACEHOLDER, serviceName, routeName, routePath);
    }

    public static boolean isWSO2ManagedService(JsonObject service) {
        if (service == null || !service.has("tags") || !service.get("tags").isJsonArray()) {
            return false;
        }

        JsonArray tags = service.getAsJsonArray("tags");
        for (JsonElement tagElement : tags) {
            if (tagElement.isJsonNull()) {
                continue;
            }
            if (WSO2_MANAGED_TAG.equalsIgnoreCase(tagElement.getAsString())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isLikelyConnectorManagedService(JsonObject service, JsonObject route) {
        String serviceName = safeString(service, "name");
        String routeName = safeString(route, "name");
        if (serviceName.isEmpty() || routeName.isEmpty()) {
            return false;
        }
        return routeName.equals(createRouteName(serviceName));
    }

    public static void deleteAPI(String referenceArtifact, String adminUrl)
            throws APIManagementException {
        if (!isDbLessArtifact(referenceArtifact)) {
            try {
                String serviceId = KongLocalGatewayUtil.getKongLocalServiceIdFromReferenceArtifact(referenceArtifact);
                String routeId = KongLocalGatewayUtil.getKongLocalRouteIdFromReferenceArtifact(referenceArtifact);

                executeNoContentRequest(adminUrl + KongLocalConstants.KONG_ROUTES_PATH + "/" + encode(routeId),
                        "DELETE");
                executeNoContentRequest(adminUrl + KongLocalConstants.KONG_SERVICES_PATH + "/" + encode(serviceId),
                        "DELETE");
                return;
            } catch (APIManagementException e) {
                if (!isDbLessUnsupported(e)) {
                    throw e;
                }
            }
        }

        String serviceName;
        String routeName;
        try {
            serviceName = KongLocalGatewayUtil.getKongLocalServiceNameFromReferenceArtifact(referenceArtifact);
            routeName = KongLocalGatewayUtil.getKongLocalRouteNameFromReferenceArtifact(referenceArtifact);
        } catch (APIManagementException e) {
            throw new APIManagementException("Unable to remove API from DB-less Kong: reference artifact does not "
                    + "contain serviceName/routeName", e);
        }

        removeFromDeclarativeConfig(adminUrl, serviceName, routeName);
    }

    public static List<JsonObject> getServices(String adminUrl) throws APIManagementException {
        try {
            return fetchPaginatedData(adminUrl + KongLocalConstants.KONG_SERVICES_PATH);
        } catch (APIManagementException e) {
            if (!isDbLessUnsupported(e)) {
                throw e;
            }
            return getServicesFromDeclarativeConfig(adminUrl);
        }
    }

    public static Map<String, List<JsonObject>> getRoutesByService(String adminUrl)
            throws APIManagementException {
        Map<String, List<JsonObject>> routesByService = new HashMap<>();
        for (JsonObject route : getRoutes(adminUrl)) {
            String serviceRef = extractServiceReference(route);
            if (serviceRef == null) {
                continue;
            }
            routesByService.computeIfAbsent(serviceRef, key -> new ArrayList<>()).add(route);
        }
        return routesByService;
    }

    public static List<JsonObject> getRoutes(String adminUrl) throws APIManagementException {
        try {
            return fetchPaginatedData(adminUrl + KongLocalConstants.KONG_ROUTES_PATH);
        } catch (APIManagementException e) {
            if (!isDbLessUnsupported(e)) {
                throw e;
            }
            return getRoutesFromDeclarativeConfig(adminUrl);
        }
    }

    public static API kongServiceToAPI(JsonObject service, JsonObject route, String organization,
                                       Environment environment) {
        String name = service.has("name") ? service.get("name").getAsString()
                : service.get("id").getAsString();
        String routePath = extractRoutePath(route, name);
        APIIdentifier identifier = new APIIdentifier("admin", name, KongLocalConstants.DEFAULT_VERSION);
        API api = new API(identifier);

        api.setDisplayName(name);
        api.setUuid(service.has("id") ? service.get("id").getAsString() : name);
        api.setContext(routePath);
        api.setContextTemplate(routePath);
        api.setOrganization(organization);
        api.setRevision(false);
        api.setLastUpdated(new Date());
        api.setCreatedTime(Long.toString(new Date().getTime()));
        api.setInitiatedFromGateway(true);
        api.setGatewayVendor("external");
        api.setEnableSubscriberVerification(false);
        api.setGatewayType(environment.getGatewayType());

        JsonObject endpointConfig = new JsonObject();
        endpointConfig.addProperty("endpoint_type", "http");
        String serviceUrl = service.has("url") ? service.get("url").getAsString()
                : KongLocalConstants.KONGLOCAL_DEFAULT_PROXY_URL;
        JsonObject prod = new JsonObject();
        prod.addProperty(KongLocalConstants.URL_PROP, serviceUrl);
        JsonObject sand = new JsonObject();
        sand.addProperty(KongLocalConstants.URL_PROP, serviceUrl);
        endpointConfig.add(KongLocalConstants.PRODUCTION_ENDPOINTS, prod);
        endpointConfig.add(KongLocalConstants.SANDBOX_ENDPOINTS, sand);
        api.setEndpointConfig(endpointConfig.toString());
        api.setSwaggerDefinition(createMinimalOpenAPIDefinition(name, routePath, extractRouteMethods(route)));

        return api;
    }

    private static String createMinimalOpenAPIDefinition(String apiName, String routePath, Set<String> methods) {
        JsonObject oas = new JsonObject();
        oas.addProperty("openapi", "3.0.1");

        JsonObject info = new JsonObject();
        info.addProperty("title", apiName);
        info.addProperty("version", KongLocalConstants.DEFAULT_VERSION);
        oas.add("info", info);

        JsonObject paths = new JsonObject();
        JsonObject pathItem = new JsonObject();

        JsonArray pathParameters = buildPathParameters(routePath);
        if (!pathParameters.isEmpty()) {
            pathItem.add("parameters", pathParameters);
        }

        for (String method : methods) {
            JsonObject operation = new JsonObject();
            operation.addProperty("operationId", method.toLowerCase() + sanitizeOperationSuffix(apiName));

            JsonObject responses = new JsonObject();
            JsonObject ok = new JsonObject();
            ok.addProperty("description", "Success");
            responses.add("200", ok);
            operation.add("responses", responses);

            pathItem.add(method.toLowerCase(), operation);
        }

        paths.add(routePath, pathItem);
        oas.add("paths", paths);
        return oas.toString();
    }

    private static JsonArray buildPathParameters(String routePath) {
        JsonArray parameters = new JsonArray();
        Matcher matcher = PATH_PARAM_PATTERN.matcher(routePath);
        Set<String> seen = new LinkedHashSet<>();

        while (matcher.find()) {
            String parameterName = matcher.group(1);
            if (!seen.add(parameterName)) {
                continue;
            }

            JsonObject parameter = new JsonObject();
            parameter.addProperty("name", parameterName);
            parameter.addProperty("in", "path");
            parameter.addProperty("required", true);

            JsonObject schema = new JsonObject();
            schema.addProperty("type", "string");
            parameter.add("schema", schema);

            parameters.add(parameter);
        }

        return parameters;
    }

    private static Set<String> extractRouteMethods(JsonObject route) {
        Set<String> methods = new LinkedHashSet<>();
        if (route != null && route.has("methods") && route.get("methods").isJsonArray()) {
            JsonArray methodsArray = route.getAsJsonArray("methods");
            for (JsonElement method : methodsArray) {
                methods.add(method.getAsString().toUpperCase());
            }
        }

        if (methods.isEmpty()) {
            methods.add("GET");
        }

        return methods;
    }

    private static String sanitizeOperationSuffix(String value) {
        return value.replaceAll("[^a-zA-Z0-9]", "_");
    }

    private static JsonArray buildManagedTags(API api) {
        JsonArray tags = new JsonArray();
        tags.add(WSO2_MANAGED_TAG);
        if (api != null && api.getUuid() != null && !api.getUuid().isBlank()) {
            tags.add("wso2-api-uuid:" + api.getUuid());
        }
        return tags;
    }

    private static void mergeServiceTags(JsonObject serviceObject, JsonArray tagsToAdd) {
        JsonArray tags = ensureArray(serviceObject, "tags");
        Set<String> existingTags = new LinkedHashSet<>();

        for (JsonElement element : tags) {
            if (!element.isJsonNull()) {
                existingTags.add(element.getAsString());
            }
        }

        for (JsonElement element : tagsToAdd) {
            if (element.isJsonNull()) {
                continue;
            }
            String tagValue = element.getAsString();
            if (!existingTags.contains(tagValue)) {
                tags.add(tagValue);
                existingTags.add(tagValue);
            }
        }
    }

    public static String createReferenceArtifact(JsonObject service, JsonObject route) {
        String serviceName = service.has("name") ? service.get("name").getAsString() : "";
        String routeName = (route != null && route.has("name")) ? route.get("name").getAsString() : "";
        String serviceId = service.has("id") ? service.get("id").getAsString() : DBLESS_ID_PLACEHOLDER;
        String routeId = (route != null && route.has("id")) ? route.get("id").getAsString() : DBLESS_ID_PLACEHOLDER;
        String path = extractRoutePath(route, "/");

        return KongLocalGatewayUtil.createReferenceArtifact(serviceId, routeId, serviceName, routeName, path);
    }

    private static void upsertDeclarativeConfig(String adminUrl, String serviceName, String routeName,
                                                String routePath, String upstreamUrl, JsonArray methods,
                                                API api)
            throws APIManagementException {
        JsonObject config = getDeclarativeConfig(adminUrl);
        if (!config.has("_format_version")) {
            config.addProperty("_format_version", "3.0");
        }

        JsonArray services = ensureArray(config, "services");
        JsonObject serviceObject = findObjectByProperty(services, "name", serviceName);
        if (serviceObject == null) {
            serviceObject = new JsonObject();
            serviceObject.addProperty("name", serviceName);
            services.add(serviceObject);
        }
        serviceObject.addProperty("url", upstreamUrl);
        mergeServiceTags(serviceObject, buildManagedTags(api));

        JsonArray routes = ensureArray(serviceObject, "routes");
        JsonObject routeObject = findObjectByProperty(routes, "name", routeName);
        JsonObject newRoutePayload = createRoutePayload(routeName, routePath, methods);
        if (routeObject == null) {
            routes.add(newRoutePayload);
        } else {
            routeObject.add("paths", newRoutePayload.get("paths"));
            routeObject.add("methods", newRoutePayload.get("methods"));
            routeObject.addProperty("strip_path", false);
        }

        executeJsonRequest(adminUrl + KongLocalConstants.KONG_CONFIG_PATH, "POST", config.toString());
    }

    private static void removeFromDeclarativeConfig(String adminUrl, String serviceName, String routeName)
            throws APIManagementException {
        JsonObject config = getDeclarativeConfig(adminUrl);
        JsonArray services = ensureArray(config, "services");

        for (int i = services.size() - 1; i >= 0; i--) {
            JsonObject service = services.get(i).getAsJsonObject();
            String existingServiceName = safeString(service, "name");
            if (!serviceName.equals(existingServiceName)) {
                continue;
            }

            JsonArray routes = ensureArray(service, "routes");
            for (int j = routes.size() - 1; j >= 0; j--) {
                JsonObject route = routes.get(j).getAsJsonObject();
                if (routeName.equals(safeString(route, "name"))) {
                    routes.remove(j);
                }
            }

            if (routes.isEmpty()) {
                services.remove(i);
            }
        }

        if (!config.has("_format_version")) {
            config.addProperty("_format_version", "3.0");
        }
        executeJsonRequest(adminUrl + KongLocalConstants.KONG_CONFIG_PATH, "POST", config.toString());
    }

    private static List<JsonObject> fetchPaginatedData(String initialUrl) throws APIManagementException {
        List<JsonObject> all = new ArrayList<>();
        String nextUrl = initialUrl;

        while (nextUrl != null) {
            JsonObject response = executeJsonRequest(nextUrl, "GET", null);
            all.addAll(toObjectList(response.getAsJsonArray("data")));
            nextUrl = resolveNextPageUrl(initialUrl, response);
        }

        return all;
    }

    private static String resolveNextPageUrl(String initialUrl, JsonObject response) {
        if (response.has("next") && !response.get("next").isJsonNull()) {
            String next = response.get("next").getAsString();
            if (next != null && !next.isBlank()) {
                return toAbsoluteUrl(initialUrl, next);
            }
        }

        if (response.has("offset") && !response.get("offset").isJsonNull()) {
            String offset = response.get("offset").getAsString();
            if (offset != null && !offset.isBlank()) {
                return appendOrReplaceQueryParam(initialUrl, "offset", offset);
            }
        }

        return null;
    }

    private static String appendOrReplaceQueryParam(String url, String key, String value) {
        URI uri = URI.create(url);
        String existingQuery = uri.getQuery();
        List<String> parts = new ArrayList<>();

        if (existingQuery != null && !existingQuery.isBlank()) {
            for (String part : existingQuery.split("&")) {
                if (!part.startsWith(key + "=")) {
                    parts.add(part);
                }
            }
        }

        parts.add(key + "=" + encode(value));
        String newQuery = String.join("&", parts);
        return uri.getScheme() + "://" + uri.getAuthority() + uri.getPath() + "?" + newQuery;
    }

    private static String toAbsoluteUrl(String baseUrl, String pathOrUrl) {
        URI candidate = URI.create(pathOrUrl);
        if (candidate.isAbsolute()) {
            return pathOrUrl;
        }
        return URI.create(baseUrl).resolve(pathOrUrl).toString();
    }

    private static JsonObject getDeclarativeConfig(String adminUrl) throws APIManagementException {
        JsonObject config = executeJsonRequest(adminUrl + KongLocalConstants.KONG_CONFIG_PATH, "GET", null);
        if (config == null || config.isJsonNull()) {
            return new JsonObject();
        }
        return config;
    }

    private static List<JsonObject> getServicesFromDeclarativeConfig(String adminUrl)
            throws APIManagementException {
        JsonObject config = getDeclarativeConfig(adminUrl);
        return toObjectList(config.getAsJsonArray("services"));
    }

    private static List<JsonObject> getRoutesFromDeclarativeConfig(String adminUrl)
            throws APIManagementException {
        List<JsonObject> routes = new ArrayList<>();
        JsonObject config = getDeclarativeConfig(adminUrl);

        JsonArray topLevelRoutes = config.getAsJsonArray("routes");
        if (topLevelRoutes != null) {
            routes.addAll(toObjectList(topLevelRoutes));
        }

        for (JsonObject service : toObjectList(config.getAsJsonArray("services"))) {
            JsonArray nestedRoutes = service.getAsJsonArray("routes");
            if (nestedRoutes == null) {
                continue;
            }

            for (JsonElement routeElement : nestedRoutes) {
                JsonObject route = routeElement.getAsJsonObject().deepCopy();
                JsonObject serviceRef = new JsonObject();
                if (service.has("id")) {
                    serviceRef.addProperty("id", service.get("id").getAsString());
                }
                if (service.has("name")) {
                    serviceRef.addProperty("name", service.get("name").getAsString());
                }
                route.add("service", serviceRef);
                routes.add(route);
            }
        }

        return routes;
    }

    private static String extractServiceReference(JsonObject route) {
        if (route == null || !route.has("service") || !route.get("service").isJsonObject()) {
            return null;
        }

        JsonObject service = route.getAsJsonObject("service");
        if (service.has("id") && !service.get("id").isJsonNull()) {
            return service.get("id").getAsString();
        }
        if (service.has("name") && !service.get("name").isJsonNull()) {
            return service.get("name").getAsString();
        }
        return null;
    }

    private static JsonObject createRoutePayload(String routeName, String routePath, JsonArray methods) {
        JsonObject routePayload = new JsonObject();
        routePayload.addProperty("name", routeName);

        JsonArray paths = new JsonArray();
        paths.add(routePath);
        routePayload.add("paths", paths);
        routePayload.add("methods", methods);
        routePayload.addProperty("strip_path", false);
        return routePayload;
    }

    private static JsonArray extractMethods(API api) {
        Set<String> methods = new LinkedHashSet<>();
        for (URITemplate uriTemplate : api.getUriTemplates()) {
            methods.add(uriTemplate.getHTTPVerb().toUpperCase());
        }
        if (methods.isEmpty()) {
            methods.add("GET");
        }

        JsonArray methodsJson = new JsonArray();
        for (String method : methods) {
            methodsJson.add(method);
        }
        return methodsJson;
    }

    private static JsonObject executeJsonRequest(String url, String method, String body)
            throws APIManagementException {
        HttpResponse<String> response = execute(url, method, body);
        String responseBody = response.body();
        if (responseBody == null || responseBody.isBlank()) {
            return new JsonObject();
        }
        return JsonParser.parseString(responseBody).getAsJsonObject();
    }

    private static void executeNoContentRequest(String url, String method) throws APIManagementException {
        execute(url, method, null);
    }

    private static HttpResponse<String> execute(String url, String method, String body)
            throws APIManagementException {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url))
                    .header("Content-Type", KongLocalConstants.JSON_PAYLOAD_TYPE);

            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.method(method, HttpRequest.BodyPublishers.ofString(body));
            }

            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                String responseBody = response.body();
                throw new APIManagementException("Kong Admin API call failed: " + method + " " + url
                        + " returned status " + statusCode + " body: " + responseBody);
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new APIManagementException("Error calling Kong Admin API", e);
        } catch (IOException e) {
            throw new APIManagementException("Error calling Kong Admin API", e);
        }
    }

    private static boolean isDbLessUnsupported(APIManagementException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("not using a database")
                || message.contains("only available when not using a database");
    }

    private static boolean isDbLessArtifact(String referenceArtifact) {
        try {
            String serviceId = KongLocalGatewayUtil.getKongLocalServiceIdFromReferenceArtifact(referenceArtifact);
            return DBLESS_ID_PLACEHOLDER.equalsIgnoreCase(serviceId);
        } catch (APIManagementException e) {
            return true;
        }
    }

    private static String getServiceName(String referenceArtifact, API api) {
        try {
            return KongLocalGatewayUtil.getKongLocalServiceNameFromReferenceArtifact(referenceArtifact);
        } catch (APIManagementException ignore) {
            return buildEntityName(api);
        }
    }

    private static String getRouteName(String referenceArtifact, String serviceName) {
        try {
            return KongLocalGatewayUtil.getKongLocalRouteNameFromReferenceArtifact(referenceArtifact);
        } catch (APIManagementException ignore) {
            return createRouteName(serviceName);
        }
    }

    private static String buildEntityName(API api) {
        return api.getId().getApiName().toLowerCase().replace(" ", "-") + "-"
                + api.getId().getVersion().toLowerCase().replace(" ", "-");
    }

    private static String createRouteName(String serviceName) {
        return serviceName + "-route";
    }

    private static String normalizeContext(String context) {
        if (context == null || context.isBlank()) {
            return "/";
        }
        return context.startsWith("/") ? context : "/" + context;
    }

    private static String extractRoutePath(JsonObject route, String fallback) {
        if (route == null || !route.has("paths")) {
            return normalizeContext(fallback);
        }
        JsonArray paths = route.getAsJsonArray("paths");
        if (paths == null || paths.isEmpty()) {
            return normalizeContext(fallback);
        }
        return normalizeContext(paths.get(0).getAsString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static List<JsonObject> toObjectList(JsonArray array) {
        List<JsonObject> list = new ArrayList<>();
        if (array == null) {
            return list;
        }
        for (JsonElement element : array) {
            list.add(element.getAsJsonObject());
        }
        return list;
    }

    private static JsonArray ensureArray(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            JsonArray created = new JsonArray();
            object.add(key, created);
            return created;
        }
        return object.getAsJsonArray(key);
    }

    private static JsonObject findObjectByProperty(JsonArray array, String property, String value) {
        for (JsonElement element : array) {
            JsonObject object = element.getAsJsonObject();
            if (value.equals(safeString(object, property))) {
                return object;
            }
        }
        return null;
    }

    private static String safeString(JsonObject object, String fieldName) {
        if (object == null || !object.has(fieldName) || object.get(fieldName).isJsonNull()) {
            return "";
        }
        return object.get(fieldName).getAsString();
    }

}
