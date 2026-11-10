package com.victorious.pyraclaw

import com.victorious.pyraclaw.data.HubUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HubUrlTest {
    @Test fun addsSchemePortAndSlash() {
        assertEquals("http://192.168.1.20:8787/", HubUrl.normalize("192.168.1.20"))
        assertEquals("http://pyra.local:8787/", HubUrl.normalize("  pyra.local "))
    }

    @Test fun keepsExplicitPortAndScheme() {
        assertEquals("http://10.0.0.5:9000/", HubUrl.normalize("10.0.0.5:9000"))
        assertEquals("https://hub.example:8443/", HubUrl.normalize("https://hub.example:8443"))
        assertEquals("http://192.168.42.1:8787/", HubUrl.normalize("http://192.168.42.1:8787/"))
    }

    @Test fun rejectsGarbage() {
        assertNull(HubUrl.normalize(""))
        assertNull(HubUrl.normalize("http://"))
        assertNull(HubUrl.normalize("::::"))
    }

    @Test fun webSocketUrl() {
        assertEquals("ws://192.168.1.20:8787/ws", HubUrl.webSocket("http://192.168.1.20:8787/"))
        assertEquals("wss://h:1/ws", HubUrl.webSocket("https://h:1/"))
    }
}
