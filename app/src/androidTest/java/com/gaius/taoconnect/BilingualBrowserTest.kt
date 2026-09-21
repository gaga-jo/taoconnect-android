package com.gaius.taoconnect

import android.content.Intent
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Android WebView + production bridge/DOM engine. Fixture is not live Taobao. */
@RunWith(AndroidJUnit4::class)
class BilingualBrowserTest {
    private fun launch(): ActivityScenario<TaobaoBrowserActivity> {
        val intent = Intent(ApplicationProvider.getApplicationContext(), TaobaoBrowserActivity::class.java)
            .putExtra(TaobaoBrowserActivity.EXTRA_TEST_PAGE, "https://appassets.androidplatform.net/assets/qa/bilingual.html")
        return ActivityScenario.launch<TaobaoBrowserActivity>(intent).also { scenario ->
            scenario.onActivity { it.setBilingual(true) }
            until(scenario, "!!window.__taoConnect && !!document.querySelector('#title [data-tao-fr]')")
        }
    }

    private fun js(scenario: ActivityScenario<TaobaoBrowserActivity>, code: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        scenario.onActivity { it.webView.evaluateJavascript(code) { value -> result = value; latch.countDown() } }
        assertTrue("JavaScript callback timed out", latch.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun until(scenario: ActivityScenario<TaobaoBrowserActivity>, expression: String, timeout: Long = 20000) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            if (js(scenario, "Boolean($expression)") == "true") return
            SystemClock.sleep(100)
        }
        fail("Condition failed: $expression; stats=${js(scenario, "window.__taoConnect && window.__taoConnect.stats()")}")
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = "/sdcard/Pictures/TaoConnect-$name.png"
        // The test APK is removed after connectedDebugAndroidTest. A shell screenshot in
        // Pictures survives that uninstall and can therefore be collected by CI.
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("screencap -p $output")
        ).use { stream -> while (stream.read() != -1) Unit }
    }

    @Test fun originalTextInteractionsScrollAndDynamicContentStayTogether() {
        launch().use { scenario ->
            until(scenario, "!!document.querySelector('#buy [data-tao-fr]')")
            assertEquals("true", js(scenario, "document.querySelector('#title').firstChild.nodeValue === '商品详情'"))
            assertEquals("true", js(scenario, "document.querySelector('#price').textContent === '¥ 128.50' && document.querySelector('#reference').textContent === 'SKU: AB-903LU'"))
            assertEquals("true", js(scenario, "document.querySelector('#phone').value === '用户输入不应该被翻译' && !document.querySelector('#excluded [data-tao-fr]')"))
            assertEquals("true", js(scenario, "(() => {const el=document.querySelector('#title'),r=document.createRange();r.selectNode(el.firstChild);return el.querySelector('[data-tao-fr]').getBoundingClientRect().top>=r.getBoundingClientRect().bottom-1;})()"))
            capture("01-bilingual-top")
            js(scenario, "window.beforeY=document.querySelector('#buy').getBoundingClientRect().top;window.beforeNoteY=document.querySelector('#buy [data-tao-fr]').getBoundingClientRect().top;window.scrollTo(0,180)")
            until(scenario, "window.scrollY >= 170")
            assertEquals("true", js(scenario, "Math.abs((window.beforeY-document.querySelector('#buy').getBoundingClientRect().top)-(window.beforeNoteY-document.querySelector('#buy [data-tao-fr]').getBoundingClientRect().top))<1"))
            js(scenario, "document.querySelector('#buy [data-tao-fr]').click()")
            assertEquals("1", js(scenario, "window.buyCount"))
            js(scenario, "document.querySelector('#changing').textContent='查看物流';document.querySelector('#changing').scrollIntoView()")
            until(scenario, "document.querySelector('#changing [data-tao-fr]')?.textContent === 'Suivre la livraison'")
            js(scenario, "document.querySelector('#changing').firstChild.nodeValue='AB-903LU'")
            until(scenario, "!document.querySelector('#changing [data-tao-fr]')")
            js(scenario, "document.querySelector('#changing').firstChild.nodeValue='退款'")
            until(scenario, "document.querySelector('#changing [data-tao-fr]')?.textContent === 'Remboursement'")
            js(scenario, "document.querySelector('#modalButton').click()")
            until(scenario, "!!document.querySelector('dialog [data-tao-fr]')")
            capture("02-modal")
            js(scenario, "document.querySelector('dialog').close();document.querySelector('#last').scrollIntoView()")
            until(scenario, "document.querySelector('#last [data-tao-fr]')?.textContent === 'Confirmer la réception'")
            capture("03-after-scroll")
            val batchCount = js(scenario, "window.__taoConnect.stats().batches")
            SystemClock.sleep(1400)
            assertEquals("No repeated translation on an unchanged page", batchCount, js(scenario, "window.__taoConnect.stats().batches"))
            assertEquals("true", js(scenario, "[...document.querySelectorAll('[data-tao-fr]')].every(n=>n.parentElement.querySelectorAll(':scope > [data-tao-fr]').length===1)"))
            scenario.onActivity { it.setBilingual(false) }
            until(scenario, "document.querySelectorAll('[data-tao-fr]').length===0")
            assertEquals("true", js(scenario, "document.querySelector('#buy').style.height==='' && document.querySelector('#title').textContent==='商品详情'"))
            scenario.onActivity { it.setBilingual(true) }
            until(scenario, "!!document.querySelector('#last [data-tao-fr]')")
        }
    }

    @Test fun machineTranslationWorksOnDeviceWithoutMockedProvider() {
        launch().use { scenario ->
            js(scenario, "document.querySelector('#machine').hidden=false;document.querySelector('#machine').scrollIntoView()")
            until(scenario, "!!document.querySelector('#machine [data-tao-fr]')", 240000)
            val translated = JSONTokener(js(scenario, "document.querySelector('#machine [data-tao-fr]').textContent")).nextValue().toString()
            assertTrue("Expected French cotton/shirt meaning, got: $translated", translated.contains("coton", true) && translated.contains("chemise", true))
            assertEquals("true", js(scenario, "document.querySelector('#machine').firstChild.nodeValue==='这件衬衫是纯棉的'"))
            capture("04-real-translation")
            Log.i("TaoConnectTest", "Source: 这件衬衫是纯棉的 | Google ML Kit, appareil Android: $translated")
        }
    }

    @Test fun appCannotOverlayOrCaptureOtherApplications() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains("android.permission.SYSTEM_ALERT_WINDOW"))
        assertFalse(permissions.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"))
        assertFalse(permissions.contains("android.permission.BIND_ACCESSIBILITY_SERVICE"))
        assertFalse(permissions.contains("android.permission.QUERY_ALL_PACKAGES"))
    }
}
