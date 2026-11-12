package dev.shulhi.rex

import org.junit.Assert.*
import org.junit.Test

class ServersTest {
    @Test fun serverURLsNormalizeWithoutDroppingPortsOrTransportChoice() {
        assertEquals("https://rex.example.ts.net",ServerProfile.normalizeEndpoint(" HTTPS://REX.EXAMPLE.TS.NET:443/ "))
        assertEquals("http://host:9080",ServerProfile.normalizeEndpoint("http://HOST:9080/"))
        assertEquals("wss://host",ServerProfile.normalizeEndpoint("wss://host/"))
        assertEquals("http://[::1]:9000",ServerProfile.normalizeEndpoint("http://[::1]:9000/"))
    }
    @Test fun invalidOrCredentialBearingURLsCannotBecomeSavedProfiles() {
        for(url in listOf("host","unix:///tmp/rex.sock","https://user:pass@host","https://host/api/control","https://host/?secret=x","https://host/#x","http://host:0")) {
            assertTrue(url,runCatching {ServerProfile.create("Test",url)}.isFailure)
        }
    }
    @Test fun profilesAndStableIdsRoundTripAcrossRestart() {
        val servers=listOf(ServerProfile.create("Mac","https://mac.example.ts.net","a"),ServerProfile.create("Linux","https://linux.example.ts.net","b"))
        assertEquals(servers,SavedServers.decode(SavedServers.encode(servers),"https://legacy"))
    }
    @Test fun migrationPreservesTheExistingServerAndAnExplicitEmptyListStaysEmpty() {
        assertEquals("https://legacy",SavedServers.decode(null,"https://legacy/").single().endpoint)
        assertEquals("legacy",SavedServers.decode(null,"https://legacy").single().id)
        assertTrue(SavedServers.decode("[]","https://legacy").isEmpty())
        assertEquals("https://legacy",SavedServers.decode("not json","https://legacy").single().endpoint)
    }
    @Test fun corruptEntriesDoNotDiscardOtherSavedHosts() {
        val decoded=SavedServers.decode("""[{"id":"a","label":"Mac","endpoint":"https://mac"},{"id":"bad","endpoint":"file:///x"},{"id":"b","label":"Linux","endpoint":"https://linux"}]""","https://legacy")
        assertEquals(listOf("a","b"),decoded.map {it.id})
    }
    @Test fun freshInstallsHaveNoPresetPersonalServer() {
        assertTrue(SavedServers.decode(null,"").isEmpty())
        assertTrue(SavedServers.decode("not json","").isEmpty())
        assertTrue(SavedServers.decode(null,"invalid").isEmpty())
    }

    @Test fun serverIconsPersistWithoutChangingIdentityOrEndpoint() {
        val profiles=ServerIcon.entries.mapIndexed {index,icon->ServerProfile.create("Host $index","https://host$index","id$index",icon)}
        assertEquals(profiles,SavedServers.decode(SavedServers.encode(profiles),""))
    }
    @Test fun oldAndUnknownIconValuesKeepTheServerAndDefaultToGlobe() {
        val json="""[{"id":"old","label":"Old","endpoint":"https://old"},{"id":"new","label":"New","endpoint":"https://new","icon":"future-icon"}]"""
        val decoded=SavedServers.decode(json,"")
        assertEquals(listOf("old","new"),decoded.map {it.id})
        assertTrue(decoded.all {it.icon==ServerIcon.GLOBE})
    }

}
