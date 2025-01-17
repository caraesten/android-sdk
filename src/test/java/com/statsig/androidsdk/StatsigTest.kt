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


class StatsigTest {

    private lateinit var app: Application
    private var flushedLogs: String = ""
    private var initUser: StatsigUser? = null
    private var client: StatsigClient = StatsigClient()
    private lateinit var network: StatsigNetwork
    private lateinit var testSharedPrefs: TestSharedPreferences
    private val gson = Gson()

    @Before
    internal fun setup() {
        TestUtil.overrideMainDispatcher()

        app = mockk()
        testSharedPrefs = TestUtil.stubAppFunctions(app)

        TestUtil.mockStatsigUtil()

        network = TestUtil.mockNetwork() { user ->
            initUser = user
        }

        coEvery {
            network.apiPostLogs(any(), any(), any())
        } answers {
            flushedLogs = thirdArg()
        }
    }

    @After
    internal fun tearDown() {
        unmockkAll()
    }

    @Test
    fun testInitializeBadInput() = runBlocking {
        client = StatsigClient()

        try {
            client.initialize(
                app,
                "secret-111aaa",
                null,
            )

            fail("Statsig.initialize() did not fail for a non client/test key")
        } catch (expectedException: java.lang.IllegalArgumentException) {
        }
    }

    @Test
    fun testInitialize() {
        val user = StatsigUser("123")
        val now = System.currentTimeMillis()
        user.customIDs = mapOf("random_id" to "abcde")

        TestUtil.startStatsigAndWait(app, user, StatsigOptions(overrideStableID = "custom_stable_id"), network = network)
        client = Statsig.client

        assertEquals(
            Gson().toJson(initUser?.customIDs),
            Gson().toJson(mapOf("random_id" to "abcde"))
        )
        assertTrue(client.checkGate("always_on"))
        assertFalse(client.checkGate("always_off"))
        assertFalse(client.checkGate("not_a_valid_gate_name"))

        val config = client.getConfig("test_config")
        assertEquals("test", config.getString("string", "fallback"))
        assertEquals("test_config", config.getName())
        assertEquals(42, config.getInt("number", 0))
        assertEquals(
            "default string instead",
            config.getString("otherNumber", "default string instead")
        )
        assertEquals("default",config.getRuleID())

        val invalidConfig = client.getConfig("not_a_valid_config")
        assertEquals("", invalidConfig.getRuleID())
        assertEquals("not_a_valid_config", invalidConfig.getName())

        val exp = client.getExperiment("exp")
        assertEquals("exp", exp.getName())
        assertEquals(42, exp.getInt("number", 0))


        client.logEvent("test_event1", 1.toDouble(), mapOf("key" to "value"));
        client.logEvent("test_event2", mapOf("key" to "value2"));
        client.logEvent("test_event3", "1");

        // check a few previously checked gate and config; they should not result in exposure logs due to deduping logic
        client.checkGate("always_on")
        client.getConfig("test_config")
        client.getExperiment("exp")

        client.shutdown()

        val parsedLogs = Gson().fromJson(flushedLogs, LogEventData::class.java)
        assertEquals(9, parsedLogs.events.count())
        // first 2 are exposures pre initialize() completion
        assertEquals("custom_stable_id", parsedLogs.statsigMetadata.stableID)
        assertEquals("custom_stable_id", client.getStableID())
        assertEquals("Android", parsedLogs.statsigMetadata.systemName)
        assertEquals("Android", parsedLogs.statsigMetadata.deviceOS)

        // validate gate exposure
        assertEquals(parsedLogs.events[0].eventName, "statsig::gate_exposure")
        assertEquals(parsedLogs.events[0].user!!.userID, "123")
        assertEquals(parsedLogs.events[0].metadata!!["gate"], "always_on")
        assertEquals(parsedLogs.events[0].metadata!!["gateValue"], "true")
        assertEquals(parsedLogs.events[0].metadata!!["ruleID"], "always_on_rule_id")
        assertEquals(parsedLogs.events[0].metadata!!["reason"], "Network")

        var evalTime = parsedLogs.events[0].metadata!!["time"]!!.toLong()
        assertTrue(evalTime >= now && evalTime < now + 2000)
        assertEquals(
            Gson().toJson(parsedLogs.events[0].secondaryExposures), Gson().toJson(
                arrayOf(
                    mapOf(
                        "gate" to "dependent_gate",
                        "gateValue" to "true",
                        "ruleID" to "rule_id_1"
                    ),
                    mapOf(
                        "gate" to "dependent_gate_2",
                        "gateValue" to "true",
                        "ruleID" to "rule_id_2"
                    )
                )
            )
        )

        // validate non-existent gate's evaluation reason
        assertEquals(parsedLogs.events[2].metadata!!["reason"], "Unrecognized")

        // validate config exposure
        assertEquals(parsedLogs.events[3].eventName, "statsig::config_exposure")
        assertEquals(parsedLogs.events[3].user!!.userID, "123")
        assertEquals(parsedLogs.events[3].metadata!!["config"], "test_config")
        assertEquals(parsedLogs.events[3].metadata!!["ruleID"], "default")
        assertEquals(parsedLogs.events[3].metadata!!["reason"], "Network")
        evalTime = parsedLogs.events[3].metadata!!["time"]!!.toLong()
        assertTrue(evalTime >= now && evalTime < now + 2000)
        assertEquals(
            Gson().toJson(parsedLogs.events[3].secondaryExposures), Gson().toJson(
                arrayOf(
                    mapOf(
                        "gate" to "dependent_gate",
                        "gateValue" to "true",
                        "ruleID" to "rule_id_1"
                    )
                )
            )
        )

        // validate exp exposure
        assertEquals(parsedLogs.events[5].eventName, "statsig::config_exposure")
        assertEquals(parsedLogs.events[5].user!!.userID, "123")
        assertEquals(parsedLogs.events[5].metadata!!["config"], "exp")
        assertEquals(parsedLogs.events[5].metadata!!["ruleID"], "exp_rule")
        assertEquals(parsedLogs.events[5].metadata!!["reason"], "Network")
        evalTime = parsedLogs.events[5].metadata!!["time"]!!.toLong()
        assertTrue(evalTime >= now && evalTime < now + 2000)
        assertEquals(parsedLogs.events[5].secondaryExposures?.count() ?: 1, 0)

        // Validate custom logs
        assertEquals(parsedLogs.events[6].eventName, "test_event1")
        assertEquals(parsedLogs.events[6].user!!.userID, "123")
        assertEquals(parsedLogs.events[6].value, 1.0)
        assertEquals(
            Gson().toJson(parsedLogs.events[6].metadata),
            Gson().toJson(mapOf("key" to "value"))
        )
        assertNull(parsedLogs.events[6].secondaryExposures)

        assertEquals(parsedLogs.events[7].eventName, "test_event2")
        assertEquals(parsedLogs.events[7].user!!.userID, "123")
        assertEquals(parsedLogs.events[7].value, null)
        assertEquals(
            Gson().toJson(parsedLogs.events[7].metadata),
            Gson().toJson(mapOf("key" to "value2"))
        )
        assertNull(parsedLogs.events[7].secondaryExposures)

        assertEquals(parsedLogs.events[8].eventName, "test_event3")
        assertEquals(parsedLogs.events[8].user!!.userID, "123")
        assertEquals(parsedLogs.events[8].value, "1")
        assertNull(parsedLogs.events[8].metadata)
        assertNull(parsedLogs.events[8].secondaryExposures)
    }

    @Test
    fun testReinitialize() = runBlocking {
        var countdown = CountDownLatch(1)
        val callback = object : IStatsigCallback {
            override fun onStatsigInitialize() {
                countdown.countDown()
            }

            override fun onStatsigUpdateUser() {
                fail("Statsig.onStatsigUpdateUser should not have been called")
            }
        }

        var user: StatsigUser? = null
        Statsig.client.statsigNetwork = TestUtil.mockNetwork() {
            user = it
        }
        Statsig.initializeAsync(app, "client-sdkkey", StatsigUser("jkw"), callback)

        countdown.await(1L, TimeUnit.SECONDS)
        countdown = CountDownLatch(1)

        assertTrue(Statsig.client.isInitialized())
        assertEquals("jkw", user?.userID)

        Statsig.shutdown()

        assertFalse(Statsig.client.isInitialized())

        Statsig.client.statsigNetwork = TestUtil.mockNetwork() {
            user = it
        }
        Statsig.initializeAsync(app, "client-sdkkey", StatsigUser("dloomb"), callback)

        countdown.await(1L, TimeUnit.SECONDS)

        assertEquals("dloomb", user?.userID)
        return@runBlocking
    }
}
