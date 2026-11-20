package com.campusfood.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class StoreOrderingCodeTest {
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer wechat = MockRestServiceServer.bindTo(builder).build();
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final WechatConfigurationService configuration = mock(WechatConfigurationService.class);
    private final StoreOrderingCode codes = new StoreOrderingCode(db, configuration, JsonMapper.builder().build(), builder.build());

    @Test
    void approvedMerchantsGetDifferentScenesOnTheSameReleasedMenuPage() {
        String first = "12345678901234567890123456789012";
        String second = "M04";
        approve(first);
        approve(second);
        when(configuration.credentials()).thenReturn(new WechatConfigurationService.Credentials("wx1111111111111111", "test-secret"));
        wechat.expect(requestTo("https://api.weixin.qq.com/cgi-bin/stable_token"))
                .andRespond(withSuccess("{\"access_token\":\"test-token\",\"expires_in\":7200}", MediaType.TEXT_PLAIN));
        expectImage(first, new byte[] {1, 2});
        expectImage(second, new byte[] {3, 4});

        assertEquals("AQI=", codes.image(first).get("content"));
        assertEquals("AwQ=", codes.image(second).get("content"));
        wechat.verify();
    }

    @Test
    void unapprovedMerchantDoesNotCallWechat() {
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> codes.image("M05")).getStatusCode());
        wechat.verify();
    }

    private void approve(String merchant) {
        when(db.queryForList("SELECT id FROM merchant WHERE id=? AND audit_status='已通过'", String.class, merchant))
                .thenReturn(List.of(merchant));
    }

    private void expectImage(String scene, byte[] image) {
        wechat.expect(requestTo("https://api.weixin.qq.com/wxa/getwxacodeunlimit?access_token=test-token"))
                .andExpect(content().json("""
                    {"scene":"%s","page":"pages/store/index","env_version":"release","check_path":true,"width":430}
                    """.formatted(scene)))
                .andRespond(withSuccess(image, MediaType.IMAGE_PNG));
    }
}
