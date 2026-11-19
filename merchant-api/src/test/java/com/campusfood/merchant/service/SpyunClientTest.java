package com.campusfood.merchant.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class SpyunClientTest {
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://open.spyun.net");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final SpyunClient client = new SpyunClient(builder.build(), JsonMapper.builder().build());
    private final PrinterCredentials.Credentials credentials =
            new PrinterCredentials.Credentials("app", "secret", "SN1", "device-key", "测试店铺");

    @Test
    void signsSortedRawValuesAndExcludesEmptyAndExistingSign() {
        var fields = new LinkedHashMap<String, String>();
        fields.put("timestamp", "1544765873");
        fields.put("sn", "111111111");
        fields.put("pkey", "22222222");
        fields.put("name", "test");
        fields.put("appid", "sp5c1314095ed15");
        fields.put("sign", "ignored");
        fields.put("empty", "");
        fields.put("null", null);
        assertEquals("0D6E220C0E3FCE6A68895C0FAE0EB755",
                SpyunClient.sign(fields, "735aa25a15b75e6c1e0760823a22346a"));
        assertNotEquals(SpyunClient.sign(Map.of("x", "0"), "s"), SpyunClient.sign(Map.of(), "s"));
        assertNotEquals(SpyunClient.sign(Map.of("x", "中&文"), "s"),
                SpyunClient.sign(Map.of("x", "%E4%B8%AD%26%E6%96%87"), "s"));
    }

    @Test
    void submitsUtf8FormAndReturnsProviderTaskId() {
        String content = "<C>测试 A&B</C><BR><QRCODE>202611190001</QRCODE>";
        server.expect(requestTo("https://open.spyun.net/v1/printer/print"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(Map.of("appid", "app", "sn", "SN1", "times", "1", "content", content)))
                .andRespond(withSuccess("{\"errorcode\":0,\"id\":\"provider-123\"}", MediaType.APPLICATION_JSON));
        assertEquals("provider-123", client.print(credentials, content));
        server.verify();
    }

    @Test
    void alreadyBoundDeviceCanBeUsed() {
        server.expect(requestTo("https://open.spyun.net/v1/printer/add"))
                .andExpect(content().formDataContains(Map.of("business", "1", "pkey", "device-key", "name", "测试店铺")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"errorcode\":5,\"errormsg\":\"此设备已添加，不能重复添加\"}"));
        assertDoesNotThrow(() -> client.bind(credentials));
        server.verify();
    }

    @Test
    void handlesExplicitProviderRejectionWithoutLeakingResponse() {
        server.expect(requestTo("https://open.spyun.net/v1/printer/print"))
                .andRespond(withSuccess("{\"errorcode\":5,\"errormsg\":\"secret device-key\"}", MediaType.APPLICATION_JSON));
        var failure = assertThrows(SpyunClient.PrintException.class, () -> client.print(credentials, "小票"));
        assertFalse(failure.uncertain());
        assertFalse(failure.getMessage().contains("secret"));
        server.verify();
    }

    @Test
    void timeoutIsUncertainAndIsNotRetried() {
        server.expect(requestTo("https://open.spyun.net/v1/printer/print"))
                .andRespond(withException(new IOException("timeout with secret")));
        var failure = assertThrows(SpyunClient.PrintException.class, () -> client.print(credentials, "小票"));
        assertTrue(failure.uncertain());
        assertFalse(failure.getMessage().contains("secret"));
        server.verify();
    }

    @Test
    void successfulResponseWithoutTaskIdIsUncertain() {
        server.expect(requestTo("https://open.spyun.net/v1/printer/print"))
                .andRespond(withSuccess("{\"errorcode\":0}", MediaType.APPLICATION_JSON));
        assertTrue(assertThrows(SpyunClient.PrintException.class,
                () -> client.print(credentials, "小票")).uncertain());
        server.verify();
    }
}
