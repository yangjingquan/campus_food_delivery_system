package com.campusfood.user;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class WechatIdentityServiceTest {
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer wechat = MockRestServiceServer.bindTo(builder).build();
    private final WechatConfigurationService configuration = mock(WechatConfigurationService.class);
    private final WechatIdentityService identity =
        new WechatIdentityService(configuration, JsonMapper.builder().build(), builder.build());

    @Test
    void acceptsOpenIdFromPlainTextJson() {
        respondWithPlainText("{\"openid\":\"test-openid\",\"session_key\":\"unused\"}");

        assertEquals("test-openid", identity.exchangeCode("test-code"));
        wechat.verify();
    }

    @Test
    void rejectsInvalidCodeFromPlainTextJson() {
        respondWithPlainText("{\"errcode\":40029,\"errmsg\":\"invalid code\"}");

        var error = assertThrows(ResponseStatusException.class, () -> identity.exchangeCode("test-code"));
        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
        wechat.verify();
    }

    private void respondWithPlainText(String body) {
        when(configuration.credentials()).thenReturn(
            new WechatConfigurationService.Credentials("wx1111111111111111", "test-secret"));
        wechat.expect(requestTo(startsWith("https://api.weixin.qq.com/sns/jscode2session?")))
            .andRespond(withSuccess(body, MediaType.TEXT_PLAIN));
    }
}
