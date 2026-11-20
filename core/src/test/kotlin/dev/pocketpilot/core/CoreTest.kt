package dev.pocketpilot.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactorTest {

    @Test
    fun rendersCompactFlatTree() {
        val render = Compactor().compile(Fixtures.settingsScreen(), RefRegistry(), 1)
        assertEquals(3, render.lines.size)
        assertTrue(render.text.startsWith("S1 com.example.app/.Main 1080x2400 r0 kb0 3/6"))
        assertEquals(listOf(1, 2, 3), render.lines.map { it.ref })
        for (line in render.text.lines().filter { it.startsWith("@") }) {
            assertEquals("element line must have 9 fields: $line", 9, line.split('|').size)
        }
        assertTrue(render.text.contains("Wi-Fi"))
        assertFalse(render.text.contains("box"))
        assertFalse(render.text.contains("img"))
        assertTrue(render.lines.first { it.text == "Connected" }.flags.contains('k'))
        assertTrue(render.tokens > 0)
    }

    @Test
    fun mergesClickableContainerWithSingleTextChild() {
        val snapshot = Fixtures.screen(
            Fixtures.node("r", -1, Roles.CONTAINER, childCount = 1),
            Fixtures.node("r/0", 0, Roles.ITEM, clickable = true, id = "row", childCount = 1),
            Fixtures.node("r/0/0", 1, Roles.TEXT, text = "Wi-Fi")
        )
        val render = Compactor().compile(snapshot, RefRegistry(), 1)
        assertEquals(1, render.lines.size)
        assertEquals("Wi-Fi", render.lines[0].text)
        assertTrue(render.lines[0].flags.contains('c'))
    }

    @Test
    fun masksPasswordText() {
        val snapshot = Fixtures.screen(
            Fixtures.node("r", -1, Roles.INPUT, text = "hunter2", editable = true, password = true, focused = true)
        )
        val render = Compactor().compile(snapshot, RefRegistry(), 1)
        assertTrue(render.text.contains("•••"))
        assertFalse(render.text.contains("hunter2"))
        assertTrue(render.lines[0].flags.contains('p'))
    }

    @Test
    fun truncatesLongText() {
        val long = "x".repeat(300)
        val snapshot = Fixtures.screen(Fixtures.node("r", -1, Roles.TEXT, text = long))
        val render = Compactor().compile(snapshot, RefRegistry(), 1)
        val text = render.lines[0].text!!
        assertTrue(text.length <= 100)
        assertTrue(text.endsWith("…"))
    }

    @Test
    fun capsNodeCountAndPreservesActionables() {
        val root = Fixtures.node("r", -1, Roles.CONTAINER, childCount = 42)
        val texts = (0 until 40).map { Fixtures.node("r/$it", 0, Roles.TEXT, text = "Item $it") }
        val important = Fixtures.node(
            "r/important", 0, Roles.BUTTON, text = "Continue", clickable = true,
            bounds = Bounds(0, 2000, 1080, 2200)
        )
        val snapshot = Fixtures.screen(root, *texts.toTypedArray(), important)
        val render = Compactor(CompactConfig(maxNodes = 20)).compile(snapshot, RefRegistry(), 1)
        assertTrue(render.truncated)
        assertTrue(render.lines.size <= 22)
        assertTrue(render.lines.any { it.text == "Continue" })
    }

    @Test
    fun escapesDelimiters() {
        val snapshot = Fixtures.screen(Fixtures.node("r", -1, Roles.TEXT, text = "a|b\nc"))
        val render = Compactor().compile(snapshot, RefRegistry(), 1)
        assertEquals(1, render.lines.size)
        assertTrue(render.text.contains("a\\|b c"))
    }

    @Test
    fun producesJsonElementsOnDemand() {
        val render = Compactor().compile(Fixtures.settingsScreen(), RefRegistry(), 1, withJson = true)
        assertEquals(3, render.json.length())
        val first = render.json.getJSONObject(0)
        assertTrue(first.has("ref"))
        assertTrue(first.has("b"))
    }
}

class RefRegistryTest {
    @Test
    fun refsStayStableAcrossSnapshots() {
        val refs = RefRegistry()
        refs.begin()
        val first = refs.refFor("r|TextView|title")
        refs.end()
        refs.begin()
        val second = refs.refFor("r|TextView|title")
        refs.end()
        assertEquals(first, second)
    }

    @Test
    fun refsExpireAfterMissingGenerations() {
        val refs = RefRegistry(maxAgeGenerations = 2)
        refs.begin()
        val ref = refs.refFor("a|TextView|")
        refs.end()
        repeat(4) {
            refs.begin()
            refs.end()
        }
        assertNull(refs.keyFor(ref))
    }

    @Test
    fun parsesRefInput() {
        assertEquals(12, RefRegistry.parse("@12"))
        assertEquals(12, RefRegistry.parse("12"))
        assertEquals(12, RefRegistry.parse(12))
        assertNull(RefRegistry.parse("abc"))
    }
}

class DiffTest {
    @Test
    fun detectsChangedText() {
        val refs = RefRegistry()
        val first = Compactor().compile(
            Fixtures.screen(Fixtures.node("r", -1, Roles.TEXT, text = "One")), refs, 1
        )
        val second = Compactor().compile(
            Fixtures.screen(Fixtures.node("r", -1, Roles.TEXT, text = "Two")), refs, 2
        )
        val diff = Differ.diff(first, second)
        assertNotNull(diff)
        assertEquals(1, diff!!.changed)
        assertTrue(diff.text.contains("~"))
        assertTrue(diff.text.contains("Two"))
    }

    @Test
    fun reportsNoChange() {
        val refs = RefRegistry()
        val first = Compactor().compile(Fixtures.settingsScreen(), refs, 1)
        val second = Compactor().compile(Fixtures.settingsScreen(), refs, 2)
        val diff = Differ.diff(first, second)!!
        assertEquals(0, diff.added + diff.removed + diff.changed)
        assertTrue(diff.text.contains("no change"))
    }

    @Test
    fun fallsBackToFullWhenTooMuchChanged() {
        val refs = RefRegistry()
        val first = Compactor().compile(
            Fixtures.screen(Fixtures.node("r", -1, Roles.TEXT, text = "One")), refs, 1
        )
        val nodes = (0 until 30).map { Fixtures.node("r/$it", 0, Roles.TEXT, text = "Row $it") }
        val second = Compactor().compile(
            Fixtures.screen(Fixtures.node("r", -1, Roles.CONTAINER, childCount = 30), *nodes.toTypedArray()),
            refs,
            2
        )
        assertNull(Differ.diff(first, second))
    }
}

class GuardTest {
    @Test
    fun enforcesAllowlistWhenEnabled() {
        val guard = Guard(GuardPolicy(allowAllPackages = false, allowlist = setOf("com.allowed")))
        assertTrue(guard.checkAction("tap", JSONObject(), "Wi-Fi", false, "com.allowed") is Verdict.Allow)
        assertTrue(guard.checkAction("tap", JSONObject(), "Wi-Fi", false, "com.other") is Verdict.Deny)
    }

    @Test
    fun destructiveLabelsNeedConfirmation() {
        val guard = Guard()
        assertTrue(guard.checkAction("tap", JSONObject(), "Pay now", false, "com.shop") is Verdict.Confirm)
        assertTrue(guard.checkAction("tap", JSONObject(), "Delete", false, "com.shop") is Verdict.Confirm)
        assertTrue(guard.checkAction("tap", JSONObject(), "Wi-Fi", false, "com.shop") is Verdict.Allow)
    }

    @Test
    fun passwordEntryNeedsConfirmation() {
        val guard = Guard()
        val verdict = guard.checkAction(
            "type", JSONObject().put("text", "secret"), "Password", true, "com.shop"
        )
        assertTrue(verdict is Verdict.Confirm)
    }

    @Test
    fun redactsCardsSecretsAndPasswordFields() {
        val guard = Guard()
        val card = guard.redactNode(Fixtures.node("r", text = "card 4111 1111 1111 1111", role = Roles.TEXT))
        assertTrue(card.text!!.contains("[redacted]"))
        assertFalse(card.text!!.contains("4111"))

        val token = guard.redactNode(Fixtures.node("r", text = "a".repeat(40), role = Roles.TEXT))
        assertTrue(token.text!!.contains("[redacted]"))

        val password = guard.redactNode(
            Fixtures.node("r", text = "hunter2", role = Roles.INPUT, password = true, editable = true)
        )
        assertNull(password.text)
    }

    @Test
    fun confirmationStoreIsSingleUseAndExpires() {
        val store = ConfirmationStore(ttlMs = 50)
        val pending = store.create("tap", JSONObject(), "destructive_action", "Delete", "com.app")
        assertNotNull(store.take(pending.id))
        assertNull(store.take(pending.id))

        val expiring = store.create("tap", JSONObject(), "destructive_action", null, "com.app")
        Thread.sleep(80)
        assertNull(store.take(expiring.id))
    }
}

class PilotEngineTest {
    @Test
    fun observeReturnsCompactScreen() {
        val device = FakeDevice(Fixtures.settingsScreen())
        val engine = PilotEngine(device)
        val result = engine.observe(JSONObject())
        assertTrue(result.getBoolean("ok"))
        assertEquals(1, result.getInt("snapshot"))
        assertEquals(3, result.getInt("elements"))
        assertTrue(result.getString("text").contains("Wi-Fi"))
    }

    @Test
    fun sinceReturnsDiff() {
        val device = FakeDevice(Fixtures.screen(Fixtures.node("r", -1, Roles.TEXT, text = "One")))
        val engine = PilotEngine(device)
        val first = engine.observe(JSONObject())
        device.screen = Fixtures.screen(Fixtures.node("r", -1, Roles.TEXT, text = "Two"))
        val second = engine.observe(JSONObject().put("since", first.getInt("snapshot")))
        assertTrue(second.getBoolean("ok"))
        assertTrue(second.has("diff"))
        assertTrue(second.getString("text").contains("Two"))
        assertTrue(second.getString("text").contains("~"))
    }

    @Test
    fun tapResolvesRefToNodeKey() {
        val device = FakeDevice(Fixtures.settingsScreen())
        val engine = PilotEngine(device)
        val observed = engine.observe(JSONObject())
        val ref = Fixtures.refForText(observed.getString("text"), "Connected")
        val result = engine.execute("tap", JSONObject().put("ref", ref))
        assertTrue(result.getBoolean("ok"))
        assertEquals(listOf("r/1/1"), device.clicked)
    }

    @Test
    fun staleRefFailsClearly() {
        val device = FakeDevice(Fixtures.settingsScreen())
        val engine = PilotEngine(device)
        engine.observe(JSONObject())
        val result = engine.execute("tap", JSONObject().put("ref", 999))
        assertFalse(result.getBoolean("ok"))
        assertEquals("stale_ref", result.getString("error"))
    }

    @Test
    fun typesIntoFocusedInput() {
        val device = FakeDevice(
            Fixtures.screen(
                Fixtures.node("r", -1, Roles.INPUT, editable = true, focused = true, hint = "Search")
            )
        )
        val engine = PilotEngine(device)
        engine.observe(JSONObject())
        val result = engine.execute("type", JSONObject().put("text", "hello"))
        assertTrue(result.getBoolean("ok"))
        assertEquals("hello", device.typed?.second)
    }

    @Test
    fun destructiveTapRequiresConfirmationThenExecutes() {
        val device = FakeDevice(
            Fixtures.screen(
                Fixtures.node("r", -1, Roles.BUTTON, text = "Pay now", clickable = true)
            )
        )
        val engine = PilotEngine(device)
        val observed = engine.observe(JSONObject())
        val ref = Fixtures.refForText(observed.getString("text"), "Pay now")

        val blocked = engine.execute("tap", JSONObject().put("ref", ref))
        assertFalse(blocked.getBoolean("ok"))
        assertEquals("confirmation_required", blocked.getString("status"))
        assertTrue(device.clicked.isEmpty())

        val confirmId = blocked.getString("confirmId")
        val approved = engine.confirm(confirmId, true)
        assertTrue(approved.getBoolean("ok"))
        assertEquals(1, device.clicked.size)
    }

    @Test
    fun deniedConfirmationDoesNotAct() {
        val device = FakeDevice(
            Fixtures.screen(Fixtures.node("r", -1, Roles.BUTTON, text = "Delete", clickable = true))
        )
        val engine = PilotEngine(device)
        val observed = engine.observe(JSONObject())
        val ref = Fixtures.refForText(observed.getString("text"), "Delete")
        val blocked = engine.execute("tap", JSONObject().put("ref", ref))
        val denied = engine.confirm(blocked.getString("confirmId"), false)
        assertTrue(denied.getBoolean("ok"))
        assertEquals("denied", denied.getString("status"))
        assertTrue(device.clicked.isEmpty())
    }

    @Test
    fun runTaskStopsOnFailure() {
        val device = FakeDevice(Fixtures.settingsScreen())
        val engine = PilotEngine(device)
        engine.observe(JSONObject())
        val steps = JSONArray().put(
            JSONObject().put("tool", "tap").put("args", JSONObject().put("ref", 999))
        )
        val result = engine.runTask(JSONObject().put("steps", steps))
        assertFalse(result.getBoolean("ok"))
        assertEquals("task_failed", result.getString("error"))
        assertEquals(1, result.getJSONArray("steps").length())
    }

    @Test
    fun runTaskExecutesStepsInOrder() {
        val device = FakeDevice(Fixtures.settingsScreen())
        val engine = PilotEngine(device)
        val observed = engine.observe(JSONObject())
        val ref = Fixtures.refForText(observed.getString("text"), "Connected")
        val steps = JSONArray()
            .put(JSONObject().put("tool", "tap").put("args", JSONObject().put("ref", ref)))
            .put(JSONObject().put("tool", "press").put("args", JSONObject().put("key", "back")))
        val result = engine.runTask(JSONObject().put("steps", steps))
        assertTrue(result.getBoolean("ok"))
        assertEquals(1, device.clicked.size)
        assertEquals("back", device.pressed)
    }

    @Test
    fun waitForFindsText() {
        val device = FakeDevice(Fixtures.settingsScreen())
        val engine = PilotEngine(device)
        val result = engine.execute(
            "wait_for",
            JSONObject().put("text", "Wi-Fi").put("timeout", 500)
        )
        assertTrue(result.getBoolean("ok"))
        assertTrue(result.getString("text").contains("Wi-Fi"))
    }

    @Test
    fun openAppLaunchesPackage() {
        val device = FakeDevice(Fixtures.settingsScreen())
        val engine = PilotEngine(device)
        val result = engine.execute("open_app", JSONObject().put("app", "com.example.app"))
        assertTrue(result.getBoolean("ok"))
        assertEquals("com.example.app", device.launched)
    }

    @Test
    fun statusReportsServiceAndPolicy() {
        val engine = PilotEngine(FakeDevice(Fixtures.settingsScreen()))
        val status = engine.status()
        assertTrue(status.getBoolean("ok"))
        assertTrue(status.getBoolean("service"))
    }

    @Test
    fun redactionAppliesToObservations() {
        val device = FakeDevice(
            Fixtures.screen(
                Fixtures.node("r", -1, Roles.TEXT, text = "4111 1111 1111 1111"),
                Fixtures.node("r/1", 0, Roles.INPUT, editable = true, password = true, text = "hunter2")
            )
        )
        val engine = PilotEngine(device)
        val observed = engine.observe(JSONObject())
        val text = observed.getString("text")
        assertFalse(text.contains("4111"))
        assertFalse(text.contains("hunter2"))
        assertTrue(text.contains("[redacted]") || text.contains("•••"))
    }
}

class ToolsTest {
    @Test
    fun everySchemaIsValidJsonAndNamesAreUnique() {
        assertEquals(Tools.defs.size, Tools.defs.map { it.name }.toSet().size)
        for (def in Tools.defs) {
            val schema = JSONObject(def.schema)
            assertEquals("object", schema.getString("type"))
            assertTrue(def.description.isNotBlank())
        }
        assertTrue(Tools.defs.size >= 13)
    }
}
