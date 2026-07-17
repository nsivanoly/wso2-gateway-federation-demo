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

package org.wso2.homegrown.client.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.URITemplate;
import org.wso2.homegrown.client.HomeGrownConstants;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class contains utility methods to interact with HomeGrown admin API.
 */
public class HomeGrownAPIUtil {

    private static final Gson GSON = new Gson();
    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{([^/{}]+)}");

    public static String importAPI(API api, String adminUrl) throws APIManagementException {
        String serviceName = buildEntityName(api);
        String upstreamUrl = HomeGrownGatewayUtil.getEndpointURL(api);
        String routePath = normalizeContext(api.getContext());
        JsonObject deployPayload = createDeployPayload(api, serviceName, routePath, upstreamUrl);

        JsonObject response = executeJsonRequest(adminUrl + HomeGrownConstants.HOMEGROWN_DEPLOY_PATH,
            "POST", deployPayload.toString());
        JsonObject route = response.has("route") ? response.getAsJsonObject("route") : null;

        String resolvedPath = route != null && route.has("context")
            ? normalizeContext(route.get("context").getAsString()) : routePath;
        String serviceId = buildServiceId(serviceName, api.getId().getVersion(), resolvedPath);
        String routeId = serviceId + "-route";

        return HomeGrownGatewayUtil.createReferenceArtifact(serviceId, routeId, resolvedPath);
    }

    public static String reimportAPI(String referenceArtifact, API api, String adminUrl)
            throws APIManagementException {
        return importAPI(api, adminUrl);
    }

    public static void deleteAPI(String referenceArtifact, String adminUrl)
            throws APIManagementException {
        // Remove the route from the HomeGrown gateway by its context (carried in the
        // reference artifact). Keeps the runtime in sync when WSO2 undeploys the API.
        String path = HomeGrownGatewayUtil.getHomeGrownPathFromReferenceArtifact(referenceArtifact);
        if (path == null || path.isEmpty()) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("context", path);
        executeJsonRequest(adminUrl + HomeGrownConstants.HOMEGROWN_UNDEPLOY_PATH, "POST", payload.toString());
    }

    public static List<JsonObject> getServices(String adminUrl) throws APIManagementException {
        JsonObject response = executeJsonRequest(adminUrl + HomeGrownConstants.HOMEGROWN_REGISTRY_PATH,
            "GET", null);
        List<JsonObject> services = new ArrayList<>();
        JsonArray routes = extractRegistryRoutes(response);
        if (routes == null || routes.isEmpty()) {
            return services;
        }
        for (JsonElement routeEntry : routes) {
            JsonObject routeObject = routeEntry.getAsJsonObject();
            if (isExplicitlyUnpublished(routeObject)) {
                continue;
            }
            String name = routeObject.has("name") ? routeObject.get("name").getAsString() : "homegrown-api";
            String version = routeObject.has("version")
                ? routeObject.get("version").getAsString() : HomeGrownConstants.DEFAULT_VERSION;
            if (isSelfManagedRoute(routeObject, name, version)) {
                continue;
            }
            String path = extractContext(routeObject);

            JsonObject service = new JsonObject();
            service.addProperty("id", buildServiceId(name, version, path));
            service.addProperty("name", name);
            service.addProperty("url", extractBackendUrl(routeObject));
            service.addProperty("version", version);
            services.add(service);
        }
        return services;
    }

    public static JsonObject getFirstRouteForService(String adminUrl, String serviceId)
            throws APIManagementException {
        JsonObject response = executeJsonRequest(adminUrl + HomeGrownConstants.HOMEGROWN_REGISTRY_PATH,
                "GET", null);
        JsonArray routes = extractRegistryRoutes(response);
        if (routes == null || routes.isEmpty()) {
            return null;
        }
        for (JsonElement routeEntry : routes) {
            JsonObject routeObject = routeEntry.getAsJsonObject();
            if (isExplicitlyUnpublished(routeObject)) {
                continue;
            }
            String name = routeObject.has("name") ? routeObject.get("name").getAsString() : "homegrown-api";
            String version = routeObject.has("version")
                    ? routeObject.get("version").getAsString() : HomeGrownConstants.DEFAULT_VERSION;
            if (isSelfManagedRoute(routeObject, name, version)) {
                continue;
            }
            String path = extractContext(routeObject);

            if (!buildServiceId(name, version, path).equals(serviceId)) {
                continue;
            }

            JsonObject route = new JsonObject();
            route.addProperty("id", routeObject.has("id") ? routeObject.get("id").getAsString()
                    : serviceId + "-route");
            JsonArray paths = new JsonArray();
            paths.add(path);
            route.add("paths", paths);
            if (routeObject.has("resources") && routeObject.get("resources").isJsonArray()) {
                route.add("resources", routeObject.getAsJsonArray("resources"));
            }
            if (routeObject.has("methods") && routeObject.get("methods").isJsonArray()) {
                route.add("methods", routeObject.getAsJsonArray("methods"));
            }
            return route;
        }
        return null;
    }

    public static API homeGrownServiceToAPI(JsonObject service, JsonObject route, String organization,
                                       Environment environment) {
        String name = service.has("name") ? service.get("name").getAsString() : service.get("id").getAsString();
        String version = service.has("version") ? service.get("version").getAsString()
            : HomeGrownConstants.DEFAULT_VERSION;
        APIIdentifier identifier = new APIIdentifier("admin", name, version);
        API api = new API(identifier);

        api.setDisplayName(name);
        api.setUuid(service.get("id").getAsString());
        api.setContext(extractRoutePath(route, name));
        api.setContextTemplate(extractRoutePath(route, name));
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
        JsonObject prod = new JsonObject();
        prod.addProperty(HomeGrownConstants.URL_PROP, service.get("url").getAsString());
        JsonObject sand = new JsonObject();
        sand.addProperty(HomeGrownConstants.URL_PROP, service.get("url").getAsString());
        endpointConfig.add(HomeGrownConstants.PRODUCTION_ENDPOINTS, prod);
        endpointConfig.add(HomeGrownConstants.SANDBOX_ENDPOINTS, sand);
        api.setEndpointConfig(endpointConfig.toString());

        Set<URITemplate> uriTemplates = extractUriTemplates(route);
        api.setUriTemplates(uriTemplates);
        api.setSwaggerDefinition(createSwaggerDefinition(name, version, api.getContext(), uriTemplates));

        return api;
    }

    public static String createReferenceArtifact(JsonObject service, JsonObject route) {
        String path = extractRoutePath(route, "/");
        return HomeGrownGatewayUtil.createReferenceArtifact(service.get("id").getAsString(),
                route.get("id").getAsString(), path);
    }

    private static JsonObject createDeployPayload(API api, String serviceName, String routePath, String upstreamUrl) {
        JsonObject payload = new JsonObject();
        payload.addProperty("name", serviceName);
        payload.addProperty("version", api.getId().getVersion());
        payload.addProperty("context", routePath);
        payload.addProperty("backendUrl", upstreamUrl);
        payload.addProperty(HomeGrownConstants.HOMEGROWN_MANAGED_BY_PROP,
            HomeGrownConstants.HOMEGROWN_MANAGED_BY_VALUE);

        JsonArray resources = new JsonArray();
        for (URITemplate uriTemplate : api.getUriTemplates()) {
            JsonObject resource = new JsonObject();
            resource.addProperty("verb", uriTemplate.getHTTPVerb());
            resource.addProperty("target", uriTemplate.getUriTemplate());
            resources.add(resource);
        }
        payload.add("resources", resources);
        return payload;
    }

    private static JsonObject createRoutePayload(API api, String serviceName, String routePath) {
        JsonObject routePayload = new JsonObject();
        routePayload.addProperty("name", serviceName + "-route");

        JsonArray paths = new JsonArray();
        paths.add(routePath);
        routePayload.add("paths", paths);

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
        routePayload.add("methods", methodsJson);
        routePayload.addProperty("strip_path", false);
        return routePayload;
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
                    .header("Content-Type", HomeGrownConstants.JSON_PAYLOAD_TYPE);

            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.method(method, HttpRequest.BodyPublishers.ofString(body));
            }

            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                throw new APIManagementException("HomeGrown admin API call failed: " + method + " " + url
                        + " returned status " + statusCode + " body: " + response.body());
            }
            return response;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new APIManagementException("Error calling HomeGrown admin API", e);
        }
    }

    private static String buildEntityName(API api) {
        return api.getId().getApiName().toLowerCase().replace(" ", "-") + "-"
                + api.getId().getVersion().toLowerCase().replace(" ", "-");
    }

    private static String buildServiceId(String name, String version, String path) {
        return name + ":" + version + ":" + path;
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
        if (paths.isEmpty()) {
            return normalizeContext(fallback);
        }
        return normalizeContext(paths.get(0).getAsString());
    }

    private static JsonArray extractRegistryRoutes(JsonObject response) {
        if (response == null) {
            return null;
        }
        if (response.has("routes") && response.get("routes").isJsonArray()) {
            return response.getAsJsonArray("routes");
        }
        if (response.has("services") && response.get("services").isJsonArray()) {
            return response.getAsJsonArray("services");
        }
        if (response.has("apis") && response.get("apis").isJsonArray()) {
            return response.getAsJsonArray("apis");
        }
        for (Map.Entry<String, JsonElement> entry : response.entrySet()) {
            JsonElement child = entry.getValue();
            if (!child.isJsonObject()) {
                continue;
            }
            JsonObject childObject = child.getAsJsonObject();
            if (childObject.has("routes") && childObject.get("routes").isJsonArray()) {
                return childObject.getAsJsonArray("routes");
            }
            if (childObject.has("services") && childObject.get("services").isJsonArray()) {
                return childObject.getAsJsonArray("services");
            }
            if (childObject.has("apis") && childObject.get("apis").isJsonArray()) {
                return childObject.getAsJsonArray("apis");
            }
        }
        return null;
    }

    private static boolean isExplicitlyUnpublished(JsonObject routeObject) {
        return routeObject.has("published") && routeObject.get("published").isJsonPrimitive()
                && !routeObject.get("published").getAsBoolean();
    }

    private static boolean isSelfManagedRoute(JsonObject routeObject, String name, String version) {
        // A route is "ours" only if it carries the explicit ownership marker the
        // connector stamps at deploy time (mirrors KongLocal's wso2-apim-managed tag).
        // We deliberately do NOT guess from the route name (e.g. name ends with
        // "-<version>"): that over-matched genuinely external APIs like "mocky1-v1"
        // and silently excluded them from reverse-discovery.
        if (routeObject.has(HomeGrownConstants.HOMEGROWN_MANAGED_BY_PROP)
                && routeObject.get(HomeGrownConstants.HOMEGROWN_MANAGED_BY_PROP).isJsonPrimitive()) {
            String managedBy = routeObject.get(HomeGrownConstants.HOMEGROWN_MANAGED_BY_PROP).getAsString();
            return HomeGrownConstants.HOMEGROWN_MANAGED_BY_VALUE.equals(managedBy);
        }
        return false;
    }

    private static String extractContext(JsonObject routeObject) {
        if (routeObject.has("context") && !routeObject.get("context").isJsonNull()) {
            return normalizeContext(routeObject.get("context").getAsString());
        }
        if (routeObject.has("path") && !routeObject.get("path").isJsonNull()) {
            return normalizeContext(routeObject.get("path").getAsString());
        }
        if (routeObject.has("basePath") && !routeObject.get("basePath").isJsonNull()) {
            return normalizeContext(routeObject.get("basePath").getAsString());
        }
        return "/";
    }

    private static String extractBackendUrl(JsonObject routeObject) {
        if (routeObject.has("backendUrl") && !routeObject.get("backendUrl").isJsonNull()) {
            return routeObject.get("backendUrl").getAsString();
        }
        if (routeObject.has("url") && !routeObject.get("url").isJsonNull()) {
            return routeObject.get("url").getAsString();
        }
        if (routeObject.has("endpoint") && !routeObject.get("endpoint").isJsonNull()) {
            return routeObject.get("endpoint").getAsString();
        }
        return "http://localhost";
    }

    private static Set<URITemplate> extractUriTemplates(JsonObject route) {
        Set<URITemplate> uriTemplates = new LinkedHashSet<>();
        if (route != null && route.has("resources") && route.get("resources").isJsonArray()) {
            JsonArray resources = route.getAsJsonArray("resources");
            for (JsonElement resourceElement : resources) {
                if (!resourceElement.isJsonObject()) {
                    continue;
                }
                JsonObject resourceObject = resourceElement.getAsJsonObject();
                String method = resourceObject.has("verb") ? resourceObject.get("verb").getAsString() : "GET";
                String resourcePath = resourceObject.has("target") ? resourceObject.get("target").getAsString() : "/";
                uriTemplates.add(createUriTemplate(method, resourcePath));
            }
        }

        if (uriTemplates.isEmpty() && route != null && route.has("methods") && route.get("methods").isJsonArray()) {
            JsonArray methods = route.getAsJsonArray("methods");
            for (JsonElement methodElement : methods) {
                uriTemplates.add(createUriTemplate(methodElement.getAsString(), "/"));
            }
        }

        if (uriTemplates.isEmpty()) {
            uriTemplates.add(createUriTemplate("GET", "/"));
        }
        return uriTemplates;
    }

    private static URITemplate createUriTemplate(String method, String resourcePath) {
        URITemplate uriTemplate = new URITemplate();
        uriTemplate.setHTTPVerb(method == null ? "GET" : method.toUpperCase());
        uriTemplate.setUriTemplate(normalizeResourcePath(resourcePath));
        return uriTemplate;
    }

    private static String createSwaggerDefinition(String name, String version, String context,
                                                  Set<URITemplate> uriTemplates) {
        JsonObject swagger = new JsonObject();
        swagger.addProperty("swagger", "2.0");

        JsonObject info = new JsonObject();
        info.addProperty("title", name);
        info.addProperty("version", version);
        swagger.add("info", info);
        swagger.addProperty("basePath", normalizeContext(context));

        JsonObject paths = new JsonObject();
        for (URITemplate uriTemplate : uriTemplates) {
            String pathKey = normalizeSwaggerPath(uriTemplate.getUriTemplate());
            JsonObject pathObject = paths.has(pathKey) ? paths.getAsJsonObject(pathKey) : new JsonObject();

            JsonObject operation = new JsonObject();
            JsonArray parameters = createPathParameters(pathKey);
            if (!parameters.isEmpty()) {
                operation.add("parameters", parameters);
            }
            JsonObject responses = new JsonObject();
            JsonObject success = new JsonObject();
            success.addProperty("description", "Successful response");
            responses.add("200", success);
            operation.add("responses", responses);

            String method = uriTemplate.getHTTPVerb() == null ? "get"
                    : uriTemplate.getHTTPVerb().toLowerCase();
            pathObject.add(method, operation);
            paths.add(pathKey, pathObject);
        }
        swagger.add("paths", paths);
        return GSON.toJson(swagger);
    }

    private static JsonArray createPathParameters(String path) {
        JsonArray parameters = new JsonArray();
        Matcher matcher = PATH_PARAM_PATTERN.matcher(path);
        while (matcher.find()) {
            JsonObject parameter = new JsonObject();
            parameter.addProperty("name", matcher.group(1));
            parameter.addProperty("in", "path");
            parameter.addProperty("required", true);
            parameter.addProperty("type", "string");
            parameters.add(parameter);
        }
        return parameters;
    }

    private static String normalizeResourcePath(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return "/";
        }
        return resourcePath.startsWith("/") ? resourcePath : "/" + resourcePath;
    }

    private static String normalizeSwaggerPath(String resourcePath) {
        return normalizeResourcePath(resourcePath).replaceAll("//+", "/");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
