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

package org.wso2.konglocal.client;

/**
 * This class contains the constants used in KongLocal client.
 */
public class KongLocalConstants {
    public static final String KONGLOCAL_TYPE = "KongLocal";
    public static final String KONGLOCAL_SERVICE_ID_PATTERN = "serviceId=([^;]+)";
    public static final String KONGLOCAL_ROUTE_ID_PATTERN = "routeId=([^;]+)";
    public static final String KONGLOCAL_SERVICE_NAME_PATTERN = "serviceName=([^;]+)";
    public static final String KONGLOCAL_ROUTE_NAME_PATTERN = "routeName=([^;]+)";
    public static final String KONGLOCAL_PATH_PATTERN = "path=([^;]+)";

    // Environment related constants
    public static final String KONGLOCAL_ENVIRONMENT_ADMIN_URL = "admin_url";
    public static final String KONGLOCAL_ENVIRONMENT_PROXY_URL = "proxy_url";
    public static final String KONGLOCAL_DEFAULT_ADMIN_URL = "http://localhost:8001";
    public static final String KONGLOCAL_DEFAULT_PROXY_URL = "http://kong:8000/";
    public static final String KONGLOCAL_API_EXECUTION_URL_TEMPLATE = "http://kong:8000/";

    // Kong Admin API constants
    public static final String KONG_SERVICES_PATH = "/services";
    public static final String KONG_ROUTES_PATH = "/routes";
    public static final String KONG_CONFIG_PATH = "/config";
    public static final String JSON_PAYLOAD_TYPE = "application/json";

    public static final String PRODUCTION_ENDPOINTS = "production_endpoints";
    public static final String SANDBOX_ENDPOINTS = "sandbox_endpoints";
    public static final String URL_PROP = "url";
    public static final String DEFAULT_VERSION = "1.0.0";
}
