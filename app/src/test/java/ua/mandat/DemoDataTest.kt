package ua.mandat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import ua.mandat.data.DemoData

class DemoDataTest {
    @Test fun movesDaysAndMomentsToToday() {
        val doc = """{"schema":16,"today":100,"tables":{
            "events":{"columns":["id","day","endDay","title","createdAt"],"rows":[[1,98,null,"Подія",8640000000]]},
            "settings":{"columns":["key","value"],"rows":[["cadence_start","50"],["other","7"]]}}}"""
        val out = JSONObject(String(DemoData.shift(doc.toByteArray(), 110)))
        val row = out.getJSONObject("tables").getJSONObject("events").getJSONArray("rows").getJSONArray(0)
        assertEquals(108L, row.getLong(1))
        assertEquals(true, row.isNull(2))
        assertEquals(8640000000L + 10 * 86_400_000L, row.getLong(4))
        val settings = out.getJSONObject("tables").getJSONObject("settings").getJSONArray("rows")
        assertEquals("60", settings.getJSONArray(0).getString(1))
        assertEquals("7", settings.getJSONArray(1).getString(1))
    }
}
