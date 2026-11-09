package dev.shulhi.rex

import org.junit.Assert.*
import org.junit.Test

class WorkspacePresentationTest {
    // The cache stores presentation only, so cached identities carry no process candidates.
    private val codex=ProgramIdentity.resolve(listOf("codex")).copy(candidates=emptyList())
    private fun snapshot(session: String="s",program: ProgramIdentity?=null,title: String?=null)=AppState(
        phase="connected",selectedSession=session,selectedBlock="p",
        windows=listOf(WindowItem("w","window-123abc",listOf(BlockItem("p","shell")))),
        programs=program?.let {mapOf("p" to it)}.orEmpty(),titles=title?.let {mapOf("p" to it)}.orEmpty(),
    )
    @Test fun restoredMetadataIsPresentationOnlyAndFreshIdentityEmitsAChange() {
        val cache=WorkspacePresentation()
        assertTrue(cache.observe(snapshot(program=codex,title="Investigate protocol").copy(directories=mapOf("p" to "~/Dev/rexlink"))))
        val restored=WorkspacePresentation.decode(cache.encode())
        val raw=snapshot()
        restored.observe(raw)
        val chrome=restored.present(raw)
        assertEquals(codex,chrome.programs["p"])
        assertEquals("Investigate protocol",chrome.titles["p"])
        assertEquals("~/Dev/rexlink",chrome.directories["p"])
        assertEquals(setOf("p"),chrome.cachedPrograms)
        assertTrue(raw.programs.isEmpty());assertTrue(raw.titles.isEmpty())
        assertFalse(chrome.controlled);assertNull(chrome.screen);assertTrue(chrome.workingBlocks.isEmpty())
        val fresh=snapshot(program=codex)
        assertFalse(restored.observe(fresh))
        assertTrue(restored.present(fresh).cachedPrograms.isEmpty())
        assertNotEquals(chrome,restored.present(fresh))
    }
    @Test fun unknownPanesUseNeutralLoadingInsteadOfPretendingToBeShells() {
        val cache=WorkspacePresentation()
        for(program in listOf(null,ProgramIdentity.Terminal)) {
            val chrome=cache.present(snapshot(program=program))
            assertEquals(ProgramIdentity.Loading,chrome.programs["p"])
            assertEquals("Loading…",chrome.programs.getValue("p").tabTitle("window-123abc","shell",1))
        }
        val fish=ProgramIdentity.resolve(listOf("fish"))
        assertEquals(fish,cache.present(snapshot(program=fish)).programs["p"])
    }
    @Test fun changedProcessDropsOldTaskTitleAndExplicitEmptyTitleClearsIt() {
        val cache=WorkspacePresentation()
        cache.observe(snapshot(program=codex,title="Old task"))
        val shell=snapshot(program=ProgramIdentity.resolve(listOf("fish")))
        assertTrue(cache.observe(shell))
        assertEquals("",cache.present(shell).titles["p"])
        cache.observe(snapshot(program=codex,title="New task"))
        cache.observe(snapshot(program=codex,title=""))
        assertEquals("",cache.present(snapshot()).titles["p"])
    }
    @Test fun sessionsAndDeletedPanesCannotReuseUnrelatedMetadata() {
        val cache=WorkspacePresentation()
        cache.observe(snapshot(program=codex,title="Task"))
        assertEquals(ProgramIdentity.Loading,cache.present(snapshot(session="different")).programs["p"])
        val removed=snapshot().copy(selectedBlock="q",windows=listOf(WindowItem("w","Window",listOf(BlockItem("q","shell")))))
        cache.observe(removed)
        assertEquals(ProgramIdentity.Loading,cache.present(snapshot()).programs["p"])
    }
    @Test fun repeatedFramesDoNotDirtyCacheAndAnimatedTitlesDoNotRequestImmediateSave() {
        val cache=WorkspacePresentation()
        val live=snapshot(program=codex,title="Task")
        assertTrue(cache.observe(live));cache.markSaved()
        assertFalse(cache.observe(live.copy(revision=42)));assertFalse(cache.dirty)
        assertFalse(cache.observe(live.copy(titles=mapOf("p" to "Task ·"))))
        assertTrue(cache.dirty)
        assertEquals("Task ·",WorkspacePresentation.decode(cache.encode()).present(snapshot()).titles["p"])
    }
    @Test fun processCandidatesAreNotCachedAndDoNotDirtyTheCache() {
        val cache=WorkspacePresentation()
        assertTrue(cache.observe(snapshot(program=ProgramIdentity.resolve(listOf("/opt/bin/codex","codex")))));cache.markSaved()
        assertFalse(cache.observe(snapshot(program=ProgramIdentity.resolve(listOf("codex")))));assertFalse(cache.dirty)
        val restored=WorkspacePresentation.decode(cache.encode())
        assertEquals(emptyList<String>(),restored.present(snapshot()).programs["p"]?.candidates)
    }
    @Test fun disconnectedCreatingAndPreviewStatesDoNotOverwriteCache() {
        val cache=WorkspacePresentation()
        cache.observe(snapshot(program=codex,title="Task"));cache.markSaved()
        assertFalse(cache.observe(snapshot(program=ProgramIdentity.Terminal).copy(phase="disconnected")))
        assertFalse(cache.observe(snapshot(program=ProgramIdentity.Terminal).copy(creating=true)))
        assertFalse(cache.observe(snapshot(program=ProgramIdentity.Terminal).copy(phase="preview")))
        assertFalse(cache.dirty)
        assertEquals(codex,cache.present(snapshot()).programs["p"])
    }
    @Test fun persistenceIsBoundedAndMalformedEntriesFailSafely() {
        assertEquals(ProgramIdentity.Loading,WorkspacePresentation.decode("broken").present(snapshot()).programs["p"])
        val malformed=WorkspacePresentation.decode("""{"s":{"p":{"kind":"NOT_A_PROGRAM","label":"Bad","title":"Task"}}}""")
        assertEquals(ProgramIdentity.Loading,malformed.present(snapshot()).programs["p"])
        val cache=WorkspacePresentation()
        repeat(10) {cache.observe(snapshot(session="s$it",program=codex))}
        assertEquals(ProgramIdentity.Loading,cache.present(snapshot(session="s0")).programs["p"])
        assertEquals(codex,cache.present(snapshot(session="s9")).programs["p"])
        val many=snapshot(program=codex).copy(windows=listOf(WindowItem("w","Window",List(100) {BlockItem("p$it","shell")})),selectedBlock="p99",programs=(0..99).associate {"p$it" to codex})
        cache.observe(many)
        val stored=org.json.JSONObject(cache.encode()).getJSONObject("s")
        assertEquals(64,stored.length());assertTrue(stored.has("p99"))
    }
}
