# WSO2 API Platform control plane image for the Gateway Federation demo.
#
# Bakes in TWO authentic WSO2 custom gateway connectors (built from ../gateway-connectors):
#   - HomeGrown  -> federates to the mock third-party gateway (:8090 /mock/deploy)
#   - KongLocal  -> federates to Kong via its Admin API
#
# Each connector is an OSGi bundle (@Component providing GatewayAgentConfiguration).
# Two things are required to make a custom gateway TYPE selectable in the Admin
# Portal, and this image provides both:
#   1. the connector JAR in repository/components/dropins/  (registers the service)
#   2. the type listed in the [apim] gateway_type allowlist in deployment.toml
#      (APIM filters the gateway-type list by this allowlist)
#
# Rather than replace the whole stock deployment.toml, we inject the two custom
# types into the existing gateway_type line in place — a minimal, upgrade-safe
# change that also avoids shipping a committed encryption key (WSO2 generates one
# at first boot). sed -i preserves the file's wso2carbon ownership/permissions.
ARG WSO2_IMAGE=wso2/wso2am:4.7.0-alpine

FROM ${WSO2_IMAGE}

ARG APIM_HOME=/home/wso2carbon/wso2am-4.7.0

# Custom gateway connectors (prebuilt bundles; rebuilt by start.sh if missing).
COPY --chown=wso2carbon:wso2 \
     gateway-connectors/homegrown/components/homegrown.gw.manager/target/homegrown.gw.manager-*.jar \
     ${APIM_HOME}/repository/components/dropins/
COPY --chown=wso2carbon:wso2 \
     gateway-connectors/konglocal/components/konglocal.gw.manager/target/konglocal.gw.manager-*.jar \
     ${APIM_HOME}/repository/components/dropins/

# Append HomeGrown,KongLocal to the [apim] gateway_type allowlist (the line that
# lists the built-in types, identified by "APIPlatform"). Idempotent: only adds
# the types when they are not already present.
RUN sed -i \
      '/^gateway_type = ".*APIPlatform/ { /HomeGrown/! s/"\s*$/,HomeGrown,KongLocal"/ }' \
      ${APIM_HOME}/repository/conf/deployment.toml
