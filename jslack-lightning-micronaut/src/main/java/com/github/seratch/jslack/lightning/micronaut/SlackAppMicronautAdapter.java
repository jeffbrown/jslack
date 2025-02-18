package com.github.seratch.jslack.lightning.micronaut;

import com.github.seratch.jslack.lightning.AppConfig;
import com.github.seratch.jslack.lightning.request.Request;
import com.github.seratch.jslack.lightning.request.RequestHeaders;
import com.github.seratch.jslack.lightning.response.Response;
import com.github.seratch.jslack.lightning.util.SlackRequestParser;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.server.netty.NettyHttpResponseFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class SlackAppMicronautAdapter {

    private SlackRequestParser requestParser;

    public SlackAppMicronautAdapter(AppConfig appConfig) {
        this.requestParser = new SlackRequestParser(appConfig);
    }

    public Request<?> toSlackRequest(HttpRequest<?> req, LinkedHashMap<String, String> body) {
        String requestBody = body.entrySet().stream().map(e -> {
            try {
                String k = URLEncoder.encode(e.getKey(), "UTF-8");
                String v = URLEncoder.encode(e.getValue(), "UTF-8");
                return k + "=" + v;
            } catch (UnsupportedEncodingException ex) {
                return e.getKey() + "=" + e.getValue();
            }
        }).collect(Collectors.joining("&"));
        RequestHeaders headers = new RequestHeaders(flatten(req.getHeaders().asMap()));

        SlackRequestParser.HttpRequest rawRequest = SlackRequestParser.HttpRequest.builder()
                .requestUri(req.getPath())
                .queryString(flatten(req.getParameters().asMap()))
                .headers(headers)
                .requestBody(requestBody)
                .remoteAddress(toString(req.getRemoteAddress().getAddress().getAddress()))
                .build();
        return requestParser.parse(rawRequest);
    }

    private NettyHttpResponseFactory micronautResponseFactory = new NettyHttpResponseFactory();

    public HttpResponse<String> toMicronautResponse(Response resp) {
        HttpStatus status = HttpStatus.valueOf(resp.getStatusCode());
        MutableHttpResponse<String> response = micronautResponseFactory.status(status);
        for (Map.Entry<String, String> header : resp.getHeaders().entrySet()) {
            response.header(header.getKey(), header.getValue());
        }
        response.body(resp.getBody());
        return response;
    }

    private static final Map<String, String> flatten(Map<String, List<String>> map) {
        if (map == null) {
            return null;
        }
        return map.entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get(0)));
    }

    private static final String toString(byte[] rawBytes) {
        int i = 4;
        StringBuilder ipAddress = new StringBuilder();
        for (byte raw : rawBytes) {
            ipAddress.append(raw & 0xFF);
            if (--i > 0) {
                ipAddress.append(".");
            }
        }
        return ipAddress.toString();
    }

}
