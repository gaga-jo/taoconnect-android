package com.gaius.taoconnect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaobaoNavigationPolicyTest {
    @Test fun permitsOnlySecureTaobaoHosts() {
        listOf("https://taobao.com/", "https://m.taobao.com/a", "https://login.m.taobao.com/", "https://item.taobao.com:443/item.htm?id=123")
            .forEach { assertTrue(it, TaobaoNavigationPolicy.isTaobao(it)) }
        listOf(null, "", "http://m.taobao.com/", "https://taobao.com.evil.example/", "https://nottaobao.com/",
            "https://taobao.com@evil.example/", "https://evil@taobao.com/", "https://taobao.com:8443/",
            "javascript:alert(1)", "file:///etc/hosts", "intent://taobao", "https://tmall.com/", "https://1688.com/",
            "https://appassets.androidplatform.net/assets/qa/bilingual.html")
            .forEach { assertFalse(it, TaobaoNavigationPolicy.isTaobao(it)) }
    }

    @Test fun fixturesRequireExplicitInternalTestMode() {
        val fixture = "https://appassets.androidplatform.net/assets/qa/bilingual.html"
        assertFalse(TaobaoNavigationPolicy.canLoad(fixture, false))
        assertTrue(TaobaoNavigationPolicy.canLoad(fixture, true))
        assertFalse(TaobaoNavigationPolicy.canLoad("https://example.com/assets/qa/bilingual.html", true))
    }
}
