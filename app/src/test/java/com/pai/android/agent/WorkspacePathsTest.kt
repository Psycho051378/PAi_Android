package com.pai.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePathsTest {

    @Test
    fun keepsPlainRelativePath() {
        assertEquals("projects/a.txt", WorkspacePaths.normalize("projects/a.txt"))
    }

    @Test
    fun stripsLeadingSlash() {
        assertEquals("a/b", WorkspacePaths.normalize("/a/b"))
    }

    @Test
    fun dropsParentSegments() {
        assertEquals("a/b", WorkspacePaths.normalize("a/../../b"))
        assertEquals("etc/passwd", WorkspacePaths.normalize("../../etc/passwd"))
        assertEquals("", WorkspacePaths.normalize(".."))
    }

    @Test
    fun dropsDotSegments() {
        assertEquals("x/y", WorkspacePaths.normalize("./x/./y"))
    }

    @Test
    fun isInsideWorks() {
        assertTrue(WorkspacePaths.isInside("/ws", "/ws/a.txt"))
        assertTrue(WorkspacePaths.isInside("/ws", "/ws"))
        assertFalse(WorkspacePaths.isInside("/ws", "/ws2/a.txt"))
        assertFalse(WorkspacePaths.isInside("/ws", "/etc/passwd"))
    }
}
