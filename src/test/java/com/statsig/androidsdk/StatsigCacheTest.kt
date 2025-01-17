package com.statsig.androidsdk

import android.app.Application
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit


class StatsigCacheTest {

    private lateinit var app: Application
    private var client: StatsigClient = StatsigClient()
    private lateinit var testSharedPrefs: TestSharedPreferences
    private val gson = Gson()

    @Before
    internal fun setup() {
        TestUtil.overrideMainDispatcher()

        app = mockk()
        testSharedPrefs = TestUtil.stubAppFunctions(app)

        TestUtil.mockStatsigUtil()

    }

    @After
    internal fun tearDown() {
        unmockkAll()
    }

    @Test
    fun testInitializeUsesCacheBeforeNetworkResponse() {
        val user = StatsigUser("123")

        var cacheById: MutableMap<String, Any> = HashMap()
        var values: MutableMap<String, Any> = HashMap()
        val sticky: MutableMap<String, Any> = HashMap()
        var initialize = TestUtil.makeInitializeResponse()
        values.put("values", initialize)
        values.put("stickyUserExperiments", sticky)
        cacheById.put("123", values)

        testSharedPrefs.edit().putString("Statsig.CACHE_BY_USER", gson.toJson(cacheById))

        TestUtil.startStatsigAndDontWait(app, user, StatsigOptions())
        client = Statsig.client
        assertTrue(client.checkGate("always_on"))

        val config = client.getConfig("test_config")
        assertEquals("test", config.getString("string", "fallback"))
        assertEquals(EvaluationReason.Cache, config.getEvaluationDetails().reason)
    }

}
