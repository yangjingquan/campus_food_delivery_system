package com.campusfood.merchant.service;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

/** 商鹏云表单接口，异常只返回固定文案，避免上游报文带出签名和设备密钥。 */
@Service
public class SpyunClient {
    static final class PrintException extends RuntimeException {
        private final boolean uncertain;

        PrintException(String message, boolean uncertain) {
            super(message);
            this.uncertain = uncertain;
        }

        boolean uncertain() {
            return uncertain;
        }
    }

    private final RestClient client;
    private final ObjectMapper json;

    @Autowired
    public SpyunClient(ObjectMapper json) {
        this(httpClient(), json);
    }

    SpyunClient(RestClient client, ObjectMapper json) {
        this.json = json;
        this.client = client;
    }

    private static RestClient httpClient() {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl("https://open.spyun.net").requestFactory(factory).build();
    }

    public void bind(PrinterCredentials.Credentials credentials) {
        var response = call("/v1/printer/add", credentials, Map.of("business", "1", "sn", credentials.sn(),
                "pkey", credentials.pkey(), "name", credentials.name()), false);
        int code = code(response);
        // 官方添加设备接口的 5 表示已添加，可继续在同一开发者账户下打印。
        if (code != 0 && code != 5) throw rejected(code, false);
    }

    public String print(PrinterCredentials.Credentials credentials, String content) {
        var response = call("/v1/printer/print", credentials,
                Map.of("sn", credentials.sn(), "content", content, "times", "1"), true);
        int code = code(response);
        if (code != 0) throw rejected(code, false);
        Object id = response.get("id");
        if (!(id instanceof String value) || value.isBlank() || value.length() > 128) {
            throw new PrintException("打印平台已接收，但未返回有效任务编号，请核对设备后再补打", true);
        }
        return value;
    }

    static String sign(Map<String, String> parameters, String secret) {
        String canonical = new TreeMap<>(parameters).entrySet().stream()
                .filter(entry -> !"sign".equals(entry.getKey()) && entry.getValue() != null && !entry.getValue().isEmpty())
                .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining("&"));
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("MD5")
                    .digest((canonical + "&appsecret=" + secret).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("签名算法不可用");
        }
    }

    private Map<?, ?> call(String path, PrinterCredentials.Credentials credentials,
                          Map<String, String> fields, boolean printing) {
        var parameters = new TreeMap<>(fields);
        parameters.put("appid", credentials.appId());
        parameters.put("timestamp", Long.toString(Instant.now().getEpochSecond()));
        parameters.put("sign", sign(parameters, credentials.appSecret()));
        var form = new LinkedMultiValueMap<String, String>();
        parameters.forEach(form::add);
        try {
            String body = client.post().uri(path).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(String.class);
            Map<?, ?> response = body == null ? null : json.readValue(body, Map.class);
            if (response == null || !(response.get("errorcode") instanceof Number)) {
                throw new PrintException("打印平台响应无法确认，请核对设备后再补打", printing);
            }
            return response;
        } catch (PrintException e) {
            throw e;
        } catch (RestClientResponseException e) {
            // 官方以非 200 HTTP 状态返回业务错误，仍需读取 errorcode。
            try {
                Map<?, ?> response = json.readValue(e.getResponseBodyAsString(), Map.class);
                if (response.get("errorcode") instanceof Number number && number.intValue() != 0) return response;
            } catch (RuntimeException ignored) {
            }
            throw new PrintException("打印平台响应无法确认，请核对设备后再补打", printing);
        } catch (RuntimeException e) {
            throw new PrintException(printing ? "打印提交结果待确认，请先查看设备是否已出纸，再决定补打"
                    : "连接打印平台失败，请稍后重新连接设备", printing);
        }
    }

    private int code(Map<?, ?> response) {
        return ((Number) response.get("errorcode")).intValue();
    }

    private PrintException rejected(int code, boolean uncertain) {
        return new PrintException("商鹏云拒绝请求（错误码 " + code + "），请管理员检查平台凭据和本店设备配置", uncertain);
    }
}
