package com.networknt.portal.registry.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.networknt.client.Http2Client;
import com.networknt.config.Config;
import com.networknt.config.JsonMapper;
import com.networknt.httpstring.HttpStringConstants;
import com.networknt.portal.registry.PortalRegistryConfig;
import com.networknt.portal.registry.PortalRegistryService;
import com.networknt.utility.StringUtils;
import io.undertow.UndertowOptions;
import io.undertow.client.ClientConnection;
import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.OptionMap;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static com.networknt.portal.registry.PortalRegistryConfig.CONFIG_NAME;

public class PortalRegistryClientImpl implements PortalRegistryClient {
    private static final Logger logger = LoggerFactory.getLogger(PortalRegistryClientImpl.class);
    private static final PortalRegistryConfig config = (PortalRegistryConfig) Config.getInstance().getJsonObjectConfig(CONFIG_NAME, PortalRegistryConfig.class);
    private static final int UNUSUAL_STATUS_CODE = 300;
    private Http2Client client = Http2Client.getInstance();

    private OptionMap optionMap;
    private URI uri;

    /**
     * Construct PortalRegistryClient with all parameters from portal-registry.yml config file. The other two constructors are
     * just for backward compatibility.
     */
    public PortalRegistryClientImpl() {
        String portalUrl = config.getPortalUrl().toLowerCase();
        optionMap = OptionMap.create(UndertowOptions.ENABLE_HTTP2, true);
        logger.debug("url = {}", portalUrl);
        try {
            uri = new URI(portalUrl);
        } catch (URISyntaxException e) {
            logger.error("Invalid URI " + portalUrl, e);
            throw new RuntimeException("Invalid URI " + portalUrl, e);
        }
    }

    @Override
    public void checkPass(PortalRegistryService service, String token) {
        String key = service.getTag() == null ? service.getServiceId() : service.getServiceId() + "|"  + service.getTag();
        String checkId = String.format("%s:%s:%s", key, service.getAddress(), service.getPort());
        if(logger.isTraceEnabled()) logger.trace("checkPass id = {}", checkId);
        Map<String, Object> map = new HashMap<>();
        map.put("id", checkId);
        map.put("pass", true);
        map.put("checkInterval", config.getCheckInterval());
        String path = "/services/check";
        ClientConnection connection = null;
        try {
            connection = client.borrowConnection(uri, Http2Client.WORKER, Http2Client.SSL, Http2Client.BUFFER_POOL, optionMap).get();
            AtomicReference<ClientResponse> reference = send(connection, Methods.PUT, path, token, JsonMapper.toJson(map));
            int statusCode = reference.get().getResponseCode();
            if (statusCode >= UNUSUAL_STATUS_CODE) {
                logger.error("checkPass error: {} : {}", statusCode, reference.get().getAttachment(Http2Client.RESPONSE_BODY));
            }
        } catch (Exception e) {
            logger.error("CheckPass request exception", e);
        } finally {
            client.returnConnection(connection);
        }
    }

    @Override
    public void checkFail(PortalRegistryService service, String token) {
        String key = service.getTag() == null ? service.getServiceId() : service.getServiceId() + "|"  + service.getTag();
        String checkId = String.format("%s:%s:%s", key, service.getAddress(), service.getPort());
        if(logger.isTraceEnabled()) logger.trace("checkFail id = {}", checkId);
        Map<String, Object> map = new HashMap<>();
        map.put("id", checkId);
        map.put("pass", false);
        map.put("checkInterval", config.getCheckInterval());
        String path = "/services/check";
        ClientConnection connection = null;
        try {
            connection = client.borrowConnection(uri, Http2Client.WORKER, Http2Client.SSL, Http2Client.BUFFER_POOL, optionMap).get();
            AtomicReference<ClientResponse> reference = send(connection, Methods.PUT, path, token, JsonMapper.toJson(map));
            int statusCode = reference.get().getResponseCode();
            if (statusCode >= UNUSUAL_STATUS_CODE) {
                logger.error("checkFail error: {} : {}", statusCode, reference.get().getAttachment(Http2Client.RESPONSE_BODY));
            }
        } catch (Exception e) {
            logger.error("CheckFail request exception", e);
        } finally {
            client.returnConnection(connection);
        }
    }

    @Override
    public void registerService(PortalRegistryService service, String token) {
        String json = service.toString();
        String path = "/services";
        ClientConnection connection = null;
        try {
            connection = client.borrowConnection(uri, Http2Client.WORKER, Http2Client.SSL, Http2Client.BUFFER_POOL, optionMap).get();
            AtomicReference<ClientResponse> reference = send(connection, Methods.POST, path, token, json);
            int statusCode = reference.get().getResponseCode();
            if (statusCode >= UNUSUAL_STATUS_CODE) {
                logger.error("Failed to register on portal controller: {} : {}", statusCode, reference.get().getAttachment(Http2Client.RESPONSE_BODY));
                System.out.println("Error registerService: " + reference.get().getAttachment(Http2Client.RESPONSE_BODY));
                throw new Exception("Failed to register on portal controller: " + statusCode);
            }
        } catch (Exception e) {
            logger.error("Failed to register on portal controller json = " + json + " uri = " + uri, e);
            throw new RuntimeException(e.getMessage());
        } finally {
            client.returnConnection(connection);
        }
    }

    @Override
    public void unregisterService(PortalRegistryService service, String token) {
        String path = "/services?serviceId=" + service.getServiceId() + "&protocol=" + service.getProtocol() + "&address=" + service.getAddress() + "&port=" + service.getPort() + "&checkInterval=" + config.getCheckInterval();
        if(service.getTag() != null) path = path + "&tag=" + service.getTag();
        System.out.println("de-register path = " + path);
        ClientConnection connection = null;
        try {
            connection = client.borrowConnection(uri, Http2Client.WORKER, Http2Client.SSL, Http2Client.BUFFER_POOL, optionMap).get();
            final AtomicReference<ClientResponse> reference = send(connection, Methods.DELETE, path, token, null);
            int statusCode = reference.get().getResponseCode();
            if (statusCode >= UNUSUAL_STATUS_CODE) {
                logger.error("Failed to unregister on portal controller, body = {}", reference.get().getAttachment(Http2Client.RESPONSE_BODY));
                // there is no reason to throw an exception here as the server is down.
            }
        } catch (Exception e) {
            logger.error("Failed to unregister on portal controller, Exception:", e);
        } finally {
            client.returnConnection(connection);
        }
    }

    /**
     * to lookup health services based on serviceId and optional tag,
     *
     * @param serviceId       serviceId
     * @param tag             tag that is used for filtering
     * @param token           jwt token for security
     * @return null if serviceId is blank
     */
    @Override
    public List<Map<String, Object>> lookupHealthService(String serviceId, String tag, String token) {
        List<Map<String, Object>> services = null;
        if (StringUtils.isBlank(serviceId)) {
            return null;
        }
        ClientConnection connection = null;
        String path = "/services/lookup" + "?serviceId=" + serviceId;
        if (tag != null) {
            path = path + "&tag=" + tag;
        }
        logger.trace("path = {}", path);
        try {
            connection = client.borrowConnection(uri, Http2Client.WORKER, Http2Client.SSL, Http2Client.BUFFER_POOL, optionMap).get();
            AtomicReference<ClientResponse> reference = send(connection, Methods.GET, path, token, null);
            int statusCode = reference.get().getResponseCode();
            if (statusCode >= UNUSUAL_STATUS_CODE) {
                throw new Exception("Failed to unregister on Consul: " + statusCode);
            } else {
                String body = reference.get().getAttachment(Http2Client.RESPONSE_BODY);
                services = JsonMapper.string2List(body);
            }
        } catch (Exception e) {
            logger.error("Exception:", e);
        } finally {
            client.returnConnection(connection);
        }
        return services;
    }

    /**
     * send to portal controller with the passed in connection
     *
     * @param connection ClientConnection
     * @param method     http method to use
     * @param path       path to send to controller
     * @param token      token to put in header
     * @param json       request body to send
     * @return AtomicReference<ClientResponse> response
     */
    AtomicReference<ClientResponse> send(ClientConnection connection, HttpString method, String path, String token, String json) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<ClientResponse> reference = new AtomicReference<>();

        ClientRequest request = new ClientRequest().setMethod(method).setPath(path);
        request.getRequestHeaders().put(Headers.HOST, "localhost");
        if (token != null) request.getRequestHeaders().put(Headers.AUTHORIZATION, token); // token is a JWT with Bearer prefix
        logger.trace("The request sent to controller: {} = request header: {}, request body is empty", uri.toString(), request.toString());
        if (StringUtils.isBlank(json)) {
            connection.sendRequest(request, client.createClientCallback(reference, latch));
        } else {
            request.getRequestHeaders().put(Headers.TRANSFER_ENCODING, "chunked");
            request.getRequestHeaders().put(Headers.CONTENT_TYPE, "application/json");
            connection.sendRequest(request, client.createClientCallback(reference, latch, json));
        }
        latch.await();
        logger.trace("The response got from controller: {} = {}", uri.toString(), reference.get().toString());
        return reference;
    }
}
